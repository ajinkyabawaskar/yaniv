package shop.abwork.yanif.presence;

/**
 * How reachable a player is, derived from their sessions and what those sessions are
 * watching. Never stored — always computed, so it cannot go stale.
 */
public enum PresenceStatus {

    /** No sessions, and no game expecting them. */
    OFFLINE,

    /** Has a session, but is not watching a game. */
    ONLINE,

    /** Has a session watching a game. */
    IN_GAME,

    /** No sessions, but a game they were watching is still expecting them. */
    DISCONNECTED_IN_GAME
}
