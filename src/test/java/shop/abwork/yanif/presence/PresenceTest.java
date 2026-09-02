package shop.abwork.yanif.presence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Presence answers two questions: is a player reachable at all, and are they attached
 * to a given game. It knows about sessions and rooms, and nothing about turns.
 */
class PresenceTest {

    private static final Instant T0 = Instant.parse("2026-09-02T12:00:00Z");

    /** Movable so a test can let time pass between an event and the question. */
    private Instant now = T0;
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private final Presence presence = new Presence(clock);

    @Test
    @DisplayName("Closing one of two sessions leaves the player attached to the room")
    void closingOneOfTwoSessionsDoesNotMakeThePlayerAbsent() {
        presence.sessionOpened("session-a", "alice");
        presence.sessionOpened("session-b", "alice");
        presence.attachedToRoom("session-a", "room-1");
        presence.attachedToRoom("session-b", "room-1");

        presence.sessionClosed("session-a");

        assertTrue(presence.absentSince("room-1", "alice").isEmpty(),
                "one tab closed, but another is still watching the room");
    }

    @Test
    @DisplayName("Absence is dated from when the last session left, not from when it is asked")
    void absenceIsDatedFromWhenTheLastSessionLeft() {
        presence.sessionOpened("session-a", "alice");
        presence.attachedToRoom("session-a", "room-1");

        now = T0.plusSeconds(10);
        presence.sessionClosed("session-a");

        now = T0.plusSeconds(90); // time passes before anyone asks
        assertEquals(T0.plusSeconds(10), presence.absentSince("room-1", "alice").orElseThrow(),
                "the grace period is measured from the moment they left");
    }

    @Test
    @DisplayName("Status is derived from sessions and room attachment")
    void statusIsDerivedFromSessionsAndAttachment() {
        assertEquals(PresenceStatus.OFFLINE, presence.status("alice"),
                "never seen: offline");

        presence.sessionOpened("session-a", "alice");
        assertEquals(PresenceStatus.ONLINE, presence.status("alice"),
                "a session, but not watching a game");

        presence.attachedToRoom("session-a", "room-1");
        assertEquals(PresenceStatus.IN_GAME, presence.status("alice"),
                "watching a game");

        presence.sessionClosed("session-a");
        assertEquals(PresenceStatus.DISCONNECTED_IN_GAME, presence.status("alice"),
                "gone, but with a game still expecting her");
    }

    @Test
    @DisplayName("A finished game stops expecting its players")
    void aFinishedGameStopsExpectingItsPlayers() {
        presence.sessionOpened("session-a", "alice");
        presence.attachedToRoom("session-a", "room-1");
        presence.sessionClosed("session-a");
        assertEquals(PresenceStatus.DISCONNECTED_IN_GAME, presence.status("alice"),
                "precondition: the game is still expecting her");

        presence.roomClosed("room-1");

        assertEquals(PresenceStatus.OFFLINE, presence.status("alice"),
                "no game expects her now, so she is simply away");
        assertTrue(presence.absentSince("room-1", "alice").isEmpty(),
                "and the room has no absences left to report");
    }

    @Test
    @DisplayName("Leaving a game without closing the tab makes you absent from it, not away")
    void detachingFromARoomLeavesThePlayerOnline() {
        presence.sessionOpened("session-a", "alice");
        presence.attachedToRoom("session-a", "room-1");

        now = T0.plusSeconds(5);
        presence.detachedFromRoom("session-a", "room-1");

        assertEquals(T0.plusSeconds(5), presence.absentSince("room-1", "alice").orElseThrow(),
                "the game stops seeing her");
        assertEquals(PresenceStatus.ONLINE, presence.status("alice"),
                "but she is still connected, just not watching a game");
    }

    @Test
    @DisplayName("A player in two games is absent from each independently")
    void absenceIsPerGameNotPerPlayer() {
        presence.sessionOpened("session-a", "alice");
        presence.sessionOpened("session-b", "alice");
        presence.attachedToRoom("session-a", "room-1");
        presence.attachedToRoom("session-b", "room-2");

        now = T0.plusSeconds(5);
        presence.sessionClosed("session-a");

        assertEquals(T0.plusSeconds(5), presence.absentSince("room-1", "alice").orElseThrow(),
                "room-1 lost her");
        assertTrue(presence.absentSince("room-2", "alice").isEmpty(),
                "room-2 did not");
        assertEquals(PresenceStatus.IN_GAME, presence.status("alice"),
                "she is still playing, just not that game");
    }

    @Test
    @DisplayName("A change in who is watching a game is announced")
    void absenceChangesAreAnnounced() {
        List<String> announced = new ArrayList<>();
        presence.onAbsenceChanged((roomId, playerId) -> announced.add(roomId + "/" + playerId));

        presence.sessionOpened("session-a", "alice");
        presence.attachedToRoom("session-a", "room-1");
        assertTrue(announced.isEmpty(),
                "arriving for the first time is not a change in absence");

        presence.sessionClosed("session-a");
        assertEquals(List.of("room-1/alice"), announced, "leaving is");

        presence.sessionOpened("session-b", "alice");
        presence.attachedToRoom("session-b", "room-1");
        assertEquals(List.of("room-1/alice", "room-1/alice"), announced, "and so is coming back");
    }

    @Test
    @DisplayName("A second tab closing changes nothing, so nothing is announced")
    void noAnnouncementWhenAnotherSessionStillWatches() {
        presence.sessionOpened("session-a", "alice");
        presence.attachedToRoom("session-a", "room-1");
        presence.sessionOpened("session-b", "alice");
        presence.attachedToRoom("session-b", "room-1");

        List<String> announced = new ArrayList<>();
        presence.onAbsenceChanged((roomId, playerId) -> announced.add(roomId + "/" + playerId));
        presence.sessionClosed("session-a");

        assertTrue(announced.isEmpty(), "she is still watching, so the game's view of her is unchanged");
    }
}
