import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import SockJS from 'sockjs-client';
import { Client, Message, StompSubscription } from '@stomp/stompjs';
import { useAuthStore } from '../stores/authStore';

/**
 * Set by the server on an ERROR frame when a CONNECT is refused for *auth* reasons, as
 * opposed to a server fault. Spelled identically in `config/StompAuthErrorHandler.java`;
 * both sides assert it, because a mismatch fails silently as an endless retry loop.
 */
const AUTH_ERROR_HEADER = 'x-auth-error';

interface StompContextType {
  isConnected: boolean;
  client: Client | null;
  send: (destination: string, body: any) => void;
  subscribe: (destination: string, callback: (message: Message) => void) => StompSubscription | null;
  flushPending: () => void;
}

const StompContext = createContext<StompContextType | undefined>(undefined);

// Get WebSocket URL dynamically based on current host
// SockJS expects an HTTP/HTTPS URL (not ws:// or wss://) - it handles the upgrade internally
// The WebSocket endpoint is on the backend (port 8080), not the frontend (port 3000)
const getWsUrl = (): string => {
  const configuredUrl = process.env.REACT_APP_WS_URL;
  let url: string;

  if (configuredUrl && configuredUrl.trim() !== '') {
    url = configuredUrl;
  } else {
    // Use the backend port (8080) for WebSocket connections
    // In development, the frontend is on port 3000 but backend is on 8080
    const protocol = window.location.protocol;
    const hostname = window.location.hostname;
    url = `${protocol}//${hostname}:8080/ws`;
  }

  // SockJS expects an HTTP/HTTPS URL (not ws:// or wss://) - it handles the upgrade internally
  return url.replace(/^ws(s)?:\/\//, 'http$1://');
};

export function StompProvider({ children }: { children: React.ReactNode }) {
  const { jwtToken, user } = useAuthStore();
  const userId = user?.userId;
  const [isConnected, setIsConnected] = useState(false);
  const pageHideHandlerRef = useRef<(() => void) | null>(null);
  const pageShowHandlerRef = useRef<(() => void) | null>(null);
  const clientRef = useRef<Client | null>(null);
  const pendingMessagesRef = useRef<Array<{ destination: string; body: any }>>([]);
  const subscriptionsRef = useRef<Map<string, StompSubscription>>(new Map());

  useEffect(() => {
    if (!jwtToken || !userId) return;

    const connect = () => {
      const wsUrl = getWsUrl();

      const client = new Client({
        // A NEW socket per call, never a captured one. stompjs invokes this on every
        // connection attempt, including every automatic retry -- hand it one long-lived
        // instance and the first close is permanent, because each retry re-offers the
        // same dead socket. That is why reconnecting used to need a logout.
        webSocketFactory: () =>
          new SockJS(wsUrl, undefined, {
            transports: ['websocket', 'xhr-streaming', 'xhr-polling'],
          }),
        connectHeaders: {
          Authorization: `Bearer ${jwtToken}`,
        },
        debug: () => {}, // Disable debug logging
        reconnectDelay: 3000,
        // Explicit rather than inherited from the library, and matched to the broker's
        // 10s so a dead socket is noticed in seconds rather than whenever the OS says so.
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
      });

      // Expose for e2e tests (Playwright waits on this handle)
      (window as any).__STOMP_CLIENT__ = client;

      // Published before it connects, not inside onConnect: pagehide and pageshow need
      // a handle to a client that has not finished connecting yet, and a null one there
      // means the socket is never closed on the way out.
      clientRef.current = client;

      client.onConnect = () => {
        console.log('WebSocket connected');
        setIsConnected(true);
        clientRef.current = client;

        pendingMessagesRef.current.splice(0).forEach(({ destination, body }) => {
          client.publish({ destination, body: JSON.stringify(body) });
        });

        // No presence heartbeat: the server tracks sessions directly, and STOMP's own
        // heartbeat tells it when this one dies. The old one was worse than redundant --
        // a beat from a live tab refreshed a DISCONNECTED_IN_GAME status, pinning a
        // player who was sitting right there.
      };

      // onDisconnect only fires for a *graceful* STOMP disconnect. A socket that simply
      // dies -- phone locks, network drops, server restarts -- fires this instead. Without
      // it isConnected stayed true across a reconnect, so nothing that keys on it re-ran:
      // the client never resubscribed, and the server never learned the player was back.
      client.onWebSocketClose = () => {
        console.log('WebSocket closed');
        setIsConnected(false);
      };

      client.onDisconnect = () => {
        console.log('WebSocket disconnected');
        setIsConnected(false);
      };

      client.onWebSocketError = (event) => {
        console.error('WebSocket error:', event);
        setIsConnected(false);
      };

      client.onStompError = (frame) => {
        console.error('STOMP error:', frame.headers['message'], frame.body);

        // stompjs retries a refused CONNECT forever on reconnectDelay. That is right for a
        // server that is down and useless for a token the server will not accept: it is
        // refused identically every 3s, nothing in the loop re-authenticates, and the tab
        // simply never connects. Recovery needed a manual logout or reload -- the REST side
        // has done this since the beginning (api.ts clears auth on a 401); the socket was
        // the one channel with no way back.
        if (frame.headers[AUTH_ERROR_HEADER]) {
          // Stop the retries first. sessionExpired() re-renders StompProvider with a null
          // token and the cleanup deactivates this client anyway, but leaving that to a
          // React effect means the interval keeps firing until it runs.
          client.deactivate().catch(() => {});
          useAuthStore.getState().sessionExpired();
        }
      };

      // Closing a tab does not unmount React, so the cleanup below never runs and the
      // socket can linger until the OS tears it down. pagehide is the event that fires
      // reliably on mobile Safari; closing the socket here makes the drop immediate.
      const handlePageHide = () => {
        clientRef.current?.forceDisconnect();
      };
      pageHideHandlerRef.current = handlePageHide;
      window.addEventListener('pagehide', handlePageHide);

      // ...and the way back. A restored page never unmounts React, so nothing above
      // re-runs, and the browser freezes timers in a backgrounded or bfcached page --
      // including the retry timer that was meant to bring us back. Nudge it instead of
      // waiting for a clock that may never tick.
      const handlePageShow = () => {
        const restored = clientRef.current;
        if (restored && !restored.connected) {
          restored.deactivate().catch(() => {}).then(() => restored.activate());
        }
      };
      pageShowHandlerRef.current = handlePageShow;
      window.addEventListener('pageshow', handlePageShow);

      client.activate();
    };

    connect();

    return () => {
      if (pageHideHandlerRef.current) {
        window.removeEventListener('pagehide', pageHideHandlerRef.current);
        pageHideHandlerRef.current = null;
      }
      if (pageShowHandlerRef.current) {
        window.removeEventListener('pageshow', pageShowHandlerRef.current);
        pageShowHandlerRef.current = null;
      }
      // No offline message on unmount: the server sees the socket close and decides,
      // and it will not call a player away while another tab still holds a session.
      if (clientRef.current && clientRef.current.connected) {
        clientRef.current.deactivate();
      }
    };
  }, [jwtToken, userId]);

  const send = (destination: string, body: any) => {
    console.log('STOMP send:', destination, body);
    if (clientRef.current && clientRef.current.connected) {
      clientRef.current.publish({
        destination,
        body: JSON.stringify(body),
      });
      console.log('STOMP message sent successfully');
    } else {
      // Queue the message - it will be sent when connection is established
      pendingMessagesRef.current.push({ destination, body });
      console.warn(`WebSocket not connected; queued ${destination}`);
      console.log('Client connected:', clientRef.current?.connected);
      console.log('Client exists:', !!clientRef.current);
    }
  };

  const subscribe = (destination: string, callback: (message: Message) => void) => {
    console.log('STOMP subscribe:', destination);
    if (!clientRef.current || !clientRef.current.connected) {
      console.error('Cannot subscribe: WebSocket not connected');
      console.log('Client connected:', clientRef.current?.connected);
      console.log('Client exists:', !!clientRef.current);
      return null as any;
    }

    const subscription = clientRef.current.subscribe(destination, callback);
    subscriptionsRef.current.set(destination, subscription);
    console.log('STOMP subscription created for:', destination);
    return subscription;
  };

  // Expose a method to flush pending messages
  const flushPending = () => {
    if (clientRef.current && clientRef.current.connected) {
      pendingMessagesRef.current.splice(0).forEach(({ destination, body }) => {
        clientRef.current!.publish({ destination, body: JSON.stringify(body) });
        console.log('Flushed pending message:', destination);
      });
    }
  };

  return (
    <StompContext.Provider value={{ isConnected, client: clientRef.current, send, subscribe, flushPending }}>
      {children}
    </StompContext.Provider>
  );
}

export function useStomp() {
  const context = useContext(StompContext);
  if (!context) {
    throw new Error('useStomp must be used within StompProvider');
  }
  return context;
}