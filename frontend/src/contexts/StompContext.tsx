import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import SockJS from 'sockjs-client';
import { Client, Message, StompSubscription } from '@stomp/stompjs';
import { useAuthStore } from '../stores/authStore';

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
  const clientRef = useRef<Client | null>(null);
  const pendingMessagesRef = useRef<Array<{ destination: string; body: any }>>([]);
  const subscriptionsRef = useRef<Map<string, StompSubscription>>(new Map());
  const heartbeatIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const visibilityHandlerRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (!jwtToken || !userId) return;

    const connect = () => {
      const wsUrl = getWsUrl();
      const socket = new SockJS(wsUrl, undefined, {
        transports: ['websocket', 'xhr-streaming', 'xhr-polling'],
      });

      const client = new Client({
        webSocketFactory: () => socket,
        connectHeaders: {
          Authorization: `Bearer ${jwtToken}`,
        },
        debug: () => {}, // Disable debug logging
        reconnectDelay: 3000,
      });

      client.onConnect = () => {
        console.log('WebSocket connected');
        console.log('Client connected state:', client.connected);
        setIsConnected(true);
        clientRef.current = client;

        pendingMessagesRef.current.splice(0).forEach(({ destination, body }) => {
          client.publish({ destination, body: JSON.stringify(body) });
        });

        // Must be less than server's expected client heartbeat (25s from WebSocketConfig)
        const heartbeatInterval = setInterval(() => {
          if (clientRef.current && clientRef.current.connected) {
            clientRef.current.publish({
              destination: '/app/presence/heartbeat',
              body: JSON.stringify({}),
            });
          }
        }, 20000); // Every 20 seconds (server expects client heartbeat every 25s)

        heartbeatIntervalRef.current = heartbeatInterval;

        // Use visibilitychange instead of pagehide for more reliable mobile handling
        // pagehide may not fire when switching apps/tabs on mobile
        const handleVisibilityChange = () => {
          if (document.hidden) {
            // App went to background - send heartbeat to keep presence alive
            // Don't send offline message here; let server detect disconnect via heartbeat timeout
            if (clientRef.current && clientRef.current.connected) {
              clientRef.current.publish({
                destination: '/app/presence/heartbeat',
                body: JSON.stringify({}),
              });
            }
          } else {
            // App came to foreground - connection should be alive or reconnecting
            // If reconnected, flushPending will be called by GameView's isConnected effect
          }
        };

        visibilityHandlerRef.current = handleVisibilityChange;
        window.addEventListener('visibilitychange', handleVisibilityChange);

        client.onDisconnect = () => {
          if (heartbeatIntervalRef.current) {
            window.clearInterval(heartbeatIntervalRef.current);
            heartbeatIntervalRef.current = null;
          }
          if (visibilityHandlerRef.current) {
            window.removeEventListener('visibilitychange', visibilityHandlerRef.current);
            visibilityHandlerRef.current = null;
          }
          console.log('WebSocket disconnected');
          setIsConnected(false);
        };
      };

      client.onStompError = (frame) => {
        console.error('STOMP error:', frame.headers['message'], frame.body);
      };

      client.activate();
    };

    connect();

    return () => {
      if (heartbeatIntervalRef.current) {
        window.clearInterval(heartbeatIntervalRef.current);
      }
      if (visibilityHandlerRef.current) {
        window.removeEventListener('visibilitychange', visibilityHandlerRef.current);
      }
      // Don't send offline message on unmount - let server detect disconnect via heartbeat timeout
      // This prevents incorrectly marking user as offline during navigation/reload
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