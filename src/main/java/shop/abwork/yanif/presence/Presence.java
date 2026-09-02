package shop.abwork.yanif.presence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Who is reachable, and who is watching which game.
 *
 * A player holds one session per open tab and is only away when the last one goes.
 * Sessions live here in memory because a session is a live connection held by this
 * process — no store can know about it more accurately than the process itself.
 * See docs/adr/0002-presence-truth-in-memory.md.
 *
 * Knows nothing about turns: it reports when a player became absent, and the caller
 * decides what that means for play.
 */
public class Presence {

    private final Clock clock;

    /** sessionId to the room that session is watching. */
    private final Map<String, String> roomBySession = new ConcurrentHashMap<>();

    /** roomId to the sessions attached to it. */
    private final Map<String, Set<String>> sessionsByRoom = new ConcurrentHashMap<>();

    /** sessionId to the player who owns it. */
    private final Map<String, String> playerBySession = new ConcurrentHashMap<>();

    /** roomId + playerId to the moment that player's last session left the room. */
    private final Map<String, Instant> absentSince = new ConcurrentHashMap<>();

    /**
     * Told when a game's view of a player changes — they arrived, or they went. The
     * game orchestrator uses this to re-evaluate whose turn is waiting on whom; without
     * it, Presence would know a player had gone and nothing would ever act on it.
     */
    private final List<BiConsumer<String, String>> absenceListeners = new CopyOnWriteArrayList<>();

    public Presence(Clock clock) {
        this.clock = clock;
    }

    /** Register interest in a game's view of a player changing. */
    public void onAbsenceChanged(BiConsumer<String, String> listener) {
        absenceListeners.add(listener);
    }

    public void sessionOpened(String sessionId, String playerId) {
        playerBySession.put(sessionId, playerId);
    }

    public void sessionClosed(String sessionId) {
        detach(sessionId);
        playerBySession.remove(sessionId);
    }

    public void attachedToRoom(String sessionId, String roomId) {
        detach(sessionId);
        String playerId = playerBySession.get(sessionId);
        roomBySession.put(sessionId, roomId);
        sessionsByRoom.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        if (playerId != null) {
            settleAbsence(roomId, playerId);
        }
    }

    /**
     * When this player stopped watching this room, or empty if any of their sessions
     * still is.
     */
    public Optional<Instant> absentSince(String roomId, String playerId) {
        return Optional.ofNullable(absentSince.get(key(roomId, playerId)));
    }

    /**
     * This session has stopped watching this room, but is still connected — the player
     * navigated away rather than leaving.
     */
    public void detachedFromRoom(String sessionId, String roomId) {
        if (roomId.equals(roomBySession.get(sessionId))) {
            detach(sessionId);
        }
    }

    /**
     * This game is over, aborted, or dropped from memory: stop expecting its players.
     *
     * Without this an absence outlives the game that recorded it, and the player stays
     * DISCONNECTED_IN_GAME for good.
     */
    public void roomClosed(String roomId) {
        absentSince.keySet().removeIf(k -> k.startsWith(roomId + "\u0000"));
        Set<String> sessions = sessionsByRoom.remove(roomId);
        if (sessions != null) {
            sessions.forEach(roomBySession::remove);
        }
    }

    /**
     * How reachable this player is. Derived on every call from the sessions we hold,
     * so it cannot disagree with them.
     */
    public PresenceStatus status(String playerId) {
        boolean hasSession = playerBySession.containsValue(playerId);
        if (hasSession) {
            return watchingAnyRoom(playerId) ? PresenceStatus.IN_GAME : PresenceStatus.ONLINE;
        }
        return expectedByAnyRoom(playerId) ? PresenceStatus.DISCONNECTED_IN_GAME : PresenceStatus.OFFLINE;
    }

    private boolean watchingAnyRoom(String playerId) {
        return roomBySession.entrySet().stream()
                .anyMatch(e -> playerId.equals(playerBySession.get(e.getKey())));
    }

    /** True while some room still records this player as absent rather than gone. */
    private boolean expectedByAnyRoom(String playerId) {
        return absentSince.keySet().stream().anyMatch(k -> k.endsWith("\u0000" + playerId));
    }

    /**
     * Record the start of an absence, or clear it, after this session's attachment
     * changed. The instant is stamped once and left alone: a later drop is a new
     * absence, and that is what makes the grace period once-per-absence.
     */
    private void settleAbsence(String roomId, String playerId) {
        String key = key(roomId, playerId);
        boolean wasAbsent = absentSince.containsKey(key);
        if (attachedSessions(roomId, playerId).isEmpty()) {
            absentSince.putIfAbsent(key, clock.instant());
        } else {
            absentSince.remove(key);
        }
        // Only a real change is worth announcing: a second tab closing while another
        // still watches leaves the game's view of this player exactly as it was.
        if (wasAbsent != absentSince.containsKey(key)) {
            absenceListeners.forEach(listener -> listener.accept(roomId, playerId));
        }
    }

    private static String key(String roomId, String playerId) {
        return roomId + "\u0000" + playerId;
    }

    private Set<String> attachedSessions(String roomId, String playerId) {
        Set<String> sessions = sessionsByRoom.getOrDefault(roomId, Set.of());
        return sessions.stream()
                .filter(s -> playerId.equals(playerBySession.get(s)))
                .collect(java.util.stream.Collectors.toSet());
    }

    private void detach(String sessionId) {
        String previousRoom = roomBySession.remove(sessionId);
        if (previousRoom == null) {
            return;
        }
        sessionsByRoom.computeIfPresent(previousRoom, (room, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
        String playerId = playerBySession.get(sessionId);
        if (playerId != null) {
            settleAbsence(previousRoom, playerId);
        }
    }
}
