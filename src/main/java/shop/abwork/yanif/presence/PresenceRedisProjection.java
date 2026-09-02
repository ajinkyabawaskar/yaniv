package shop.abwork.yanif.presence;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import shop.abwork.yanif.service.PresenceService;

/**
 * Mirrors {@link Presence} into Redis, where the friends list and invites can read it.
 *
 * Memory is the truth: a session is a live connection held by this process, and this is
 * a lagging copy kept for readers asking about players who are not in their game, and to
 * survive a restart. See docs/adr/0002. Writes are best-effort — a Redis outage must not
 * change who the server believes is present, because it does not know anything the
 * process does not already know better.
 *
 * The only writer, so nothing races it for the key.
 */
@Component
public class PresenceRedisProjection {

    private final Presence presence;
    private final PresenceService presenceService;

    public PresenceRedisProjection(Presence presence, PresenceService presenceService) {
        this.presence = presence;
        this.presenceService = presenceService;
    }

    /** Public so a test can assemble the same composition Spring does. */
    @PostConstruct
    public void follow() {
        presence.onPresenceChanged(this::write);
    }

    private void write(String playerId) {
        try {
            switch (presence.status(playerId)) {
                case ONLINE -> presenceService.setUserOnline(playerId);
                case IN_GAME -> presenceService.setUserInGame(playerId);
                case DISCONNECTED_IN_GAME -> presenceService.setUserDisconnectedInGame(playerId);
                case OFFLINE -> presenceService.setUserOffline(playerId);
            }
        } catch (Exception e) {
            // The projection is behind; presence itself is unaffected.
            System.err.println("Could not project presence for " + playerId + ": " + e.getMessage());
        }
    }
}
