package shop.abwork.yanif.presence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The listener translates STOMP frames into Presence's vocabulary. It looked too thin
 * to test — and that was wrong: the room a session is watching is parsed out of a
 * destination string, and getting that wrong silently leaves every player absent.
 */
class PresenceSessionListenerTest {

    private static final String ROOM = "room-abc";

    private Presence presence;
    private PresenceSessionListener listener;

    @BeforeEach
    void setUp() {
        presence = new Presence(Clock.fixed(java.time.Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC));
        listener = new PresenceSessionListener(presence);
    }

    private SessionConnectedEvent connected(String sessionId, String userId) {
        return new SessionConnectedEvent(this, frame(StompCommand.CONNECTED, sessionId, userId, null, null));
    }

    private SessionDisconnectEvent disconnected(String sessionId, String userId) {
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getMessage()).thenReturn(frame(StompCommand.DISCONNECT, sessionId, userId, null, null));
        return event;
    }

    private SessionSubscribeEvent subscribed(String sessionId, String userId, String destination, String subId) {
        return new SessionSubscribeEvent(this, frame(StompCommand.SUBSCRIBE, sessionId, userId, destination, subId));
    }

    private SessionUnsubscribeEvent unsubscribed(String sessionId, String userId, String subId) {
        return new SessionUnsubscribeEvent(this, frame(StompCommand.UNSUBSCRIBE, sessionId, userId, null, subId));
    }

    @SuppressWarnings("unchecked")
    private Message<byte[]> frame(StompCommand command, String sessionId, String userId,
                                  String destination, String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null));
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("Subscribing to a game's destination attaches that session to it")
    void subscribeAttachesTheSessionToTheRoom() {
        listener.onSessionConnected(connected("s1", "alice"));
        listener.onSubscribe(subscribed("s1", "alice", "/user/queue/room/" + ROOM + "/game-state", "sub-0"));

        assertEquals(PresenceStatus.IN_GAME, presence.status("alice"),
                "the destination carries the room; parsing it is the whole job");
        assertTrue(presence.absentSince(ROOM, "alice").isEmpty());
    }

    @Test
    @DisplayName("A destination Spring has rewritten for a user still names the room")
    void subscribeHandlesTheResolvedUserDestination() {
        listener.onSessionConnected(connected("s1", "alice"));
        // Spring rewrites /user/... to a session-suffixed queue before the broker sees it.
        listener.onSubscribe(subscribed("s1", "alice", "/queue/room/" + ROOM + "/game-state-users1", "sub-0"));

        assertEquals(PresenceStatus.IN_GAME, presence.status("alice"));
    }

    @Test
    @DisplayName("Subscribing to something that is not a game is ignored")
    void nonRoomSubscriptionsAreIgnored() {
        listener.onSessionConnected(connected("s1", "alice"));
        listener.onSubscribe(subscribed("s1", "alice", "/user/queue/presence", "sub-0"));

        assertEquals(PresenceStatus.ONLINE, presence.status("alice"),
                "connected, but not watching a game");
    }

    @Test
    @DisplayName("Closing the tab makes the player absent from the game they were watching")
    void disconnectRecordsTheAbsence() {
        listener.onSessionConnected(connected("s1", "alice"));
        listener.onSubscribe(subscribed("s1", "alice", "/user/queue/room/" + ROOM + "/game-state", "sub-0"));

        listener.onSessionDisconnect(disconnected("s1", "alice"));

        assertTrue(presence.absentSince(ROOM, "alice").isPresent(),
                "their turn is never re-armed unless this lands");
        assertEquals(PresenceStatus.DISCONNECTED_IN_GAME, presence.status("alice"));
    }

    @Test
    @DisplayName("Reconnecting on a new session clears the absence")
    void reconnectingClearsTheAbsence() {
        listener.onSessionConnected(connected("s1", "alice"));
        listener.onSubscribe(subscribed("s1", "alice", "/user/queue/room/" + ROOM + "/game-state", "sub-0"));
        listener.onSessionDisconnect(disconnected("s1", "alice"));
        assertTrue(presence.absentSince(ROOM, "alice").isPresent(), "precondition: away");

        // A reopened tab is a brand new session and subscription id.
        listener.onSessionConnected(connected("s2", "alice"));
        listener.onSubscribe(subscribed("s2", "alice", "/user/queue/room/" + ROOM + "/game-state", "sub-0"));

        assertTrue(presence.absentSince(ROOM, "alice").isEmpty(),
                "coming back must clear the absence, or they stay 'reconnecting' for good");
        assertEquals(PresenceStatus.IN_GAME, presence.status("alice"));
    }

    @Test
    @DisplayName("Unsubscribing detaches only the subscription it names")
    void unsubscribeDetachesThatSubscription() {
        listener.onSessionConnected(connected("s1", "alice"));
        listener.onSubscribe(subscribed("s1", "alice", "/user/queue/room/" + ROOM + "/game-state", "sub-0"));

        listener.onUnsubscribe(unsubscribed("s1", "alice", "sub-0"));

        assertTrue(presence.absentSince(ROOM, "alice").isPresent(), "left the game");
        assertEquals(PresenceStatus.ONLINE, presence.status("alice"), "but still connected");
    }

    @Test
    @DisplayName("A second tab closing leaves the player watching the game")
    void oneOfTwoTabsClosingKeepsThePlayerPresent() {
        listener.onSessionConnected(connected("s1", "alice"));
        listener.onSubscribe(subscribed("s1", "alice", "/user/queue/room/" + ROOM + "/game-state", "sub-0"));
        listener.onSessionConnected(connected("s2", "alice"));
        listener.onSubscribe(subscribed("s2", "alice", "/user/queue/room/" + ROOM + "/game-state", "sub-0"));

        listener.onSessionDisconnect(disconnected("s1", "alice"));

        assertTrue(presence.absentSince(ROOM, "alice").isEmpty(),
                "the other tab is still watching");
    }
}
