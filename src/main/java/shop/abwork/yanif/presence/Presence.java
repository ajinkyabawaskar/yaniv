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
import java.util.function.Consumer;

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

    /** Told when a player's overall reachability changes, so a projection can follow it. */
    private final List<Consumer<String>> presenceListeners = new CopyOnWriteArrayList<>();

    private final Object[] playerLocks = new Object[64];
    {
        for (int i = 0; i < playerLocks.length; i++) {
            playerLocks[i] = new Object();
        }
    }

    public Presence(Clock clock) {
        this.clock = clock;
    }

    /** Register interest in a game's view of a player changing. */
    public void onAbsenceChanged(BiConsumer<String, String> listener) {
        absenceListeners.add(listener);
    }

    /** Register interest in a player's overall reachability changing. */
    public void onPresenceChanged(Consumer<String> listener) {
        presenceListeners.add(listener);
    }

    public void sessionOpened(String sessionId, String playerId) {
        announcingChange(playerId, () -> playerBySession.put(sessionId, playerId));
    }

    /**
     * Run a mutation, and tell the listeners only if it changed this player's status.
     *
     * Serialised per player. The maps are concurrent, so the state converges either way,
     * but read-mutate-compare is not atomic: two of a player's sessions changing at once
     * could both read the same "before" and conclude nothing changed. A dropped absence
     * announcement means their turn is never re-armed, which is the failure this module
     * exists to prevent.
     */
    private void announcingChange(String playerId, Runnable mutation) {
        synchronized (lockFor(playerId)) {
            PresenceStatus before = status(playerId);
            mutation.run();
            if (before != status(playerId)) {
                presenceListeners.forEach(listener -> listener.accept(playerId));
            }
        }
    }

    /**
     * A fixed set of locks, chosen by player. Bounded, so it needs no cleanup; two
     * unrelated players occasionally sharing one costs nothing but a brief wait.
     */
    private Object lockFor(String playerId) {
        return playerLocks[Math.floorMod(playerId.hashCode(), playerLocks.length)];
    }

    public void sessionClosed(String sessionId) {
        String playerId = playerBySession.get(sessionId);
        if (playerId == null) {
            return;
        }
        announcingChange(playerId, () -> {
            detach(sessionId);
            playerBySession.remove(sessionId);
        });
    }

    public void attachedToRoom(String sessionId, String roomId) {
        String playerId = playerBySession.get(sessionId);
        if (playerId == null) {
            return;
        }
        announcingChange(playerId, () -> {
            detach(sessionId);
            roomBySession.put(sessionId, roomId);
            sessionsByRoom.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
            settleAbsence(roomId, playerId);
        });
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
        String playerId = playerBySession.get(sessionId);
        if (playerId == null || !roomId.equals(roomBySession.get(sessionId))) {
            return;
        }
        announcingChange(playerId, () -> detach(sessionId));
    }

    /**
     * This game is over, aborted, or dropped from memory: stop expecting its players.
     *
     * Without this an absence outlives the game that recorded it, and the player stays
     * DISCONNECTED_IN_GAME for good.
     */
    /**
     * Not serialised per player: this spans everyone in the room, and it runs when a game
     * is finished, aborted or evicted. A session event racing it can at worst leave the
     * projection briefly behind, which the player's next change corrects.
     */
    public void roomClosed(String roomId) {
        // Everyone this room had an opinion about may now be reachable differently.
        Set<String> affected = new java.util.HashSet<>();
        String prefix = roomId + "\u0000";
        absentSince.keySet().stream().filter(k -> k.startsWith(prefix))
                .forEach(k -> affected.add(k.substring(prefix.length())));
        Set<String> sessions = sessionsByRoom.getOrDefault(roomId, Set.of());
        sessions.stream().map(playerBySession::get).filter(java.util.Objects::nonNull).forEach(affected::add);

        Map<String, PresenceStatus> before = new java.util.HashMap<>();
        affected.forEach(playerId -> before.put(playerId, status(playerId)));

        absentSince.keySet().removeIf(k -> k.startsWith(prefix));
        Set<String> removed = sessionsByRoom.remove(roomId);
        if (removed != null) {
            removed.forEach(roomBySession::remove);
        }

        before.forEach((playerId, was) -> {
            if (was != status(playerId)) {
                presenceListeners.forEach(listener -> listener.accept(playerId));
            }
        });
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
