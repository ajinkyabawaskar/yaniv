package shop.abwork.yanif.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Service for managing user presence status via Redis.
 * Tracks online, offline, and in-game statuses for real-time updates.
 */
@Service
public class PresenceService {

    private static final String PRESENCE_KEY_PREFIX = "user:presence:";
    private static final long PRESENCE_TTL = 5; // minutes
    private static final TimeUnit PRESENCE_TTL_UNIT = TimeUnit.MINUTES;

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_IN_GAME = "IN_GAME";

    private final RedisTemplate<String, String> redisTemplate;

    public PresenceService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Set user as online.
     *
     * @param userId User ID
     */
    public void setUserOnline(String userId) {
        setUserPresence(userId, STATUS_ONLINE);
    }

    /**
     * Set user as offline.
     *
     * @param userId User ID
     */
    public void setUserOffline(String userId) {
        redisTemplate.delete(getPresenceKey(userId));
    }

    /**
     * Set user as in-game.
     *
     * @param userId User ID
     */
    public void setUserInGame(String userId) {
        setUserPresence(userId, STATUS_IN_GAME);
    }

    /**
     * Get user's current presence status.
     *
     * @param userId User ID
     * @return Presence status (ONLINE, IN_GAME, OFFLINE)
     */
    public String getUserPresence(String userId) {
        String presence = redisTemplate.opsForValue().get(getPresenceKey(userId));
        return presence != null ? presence : STATUS_OFFLINE;
    }

    /**
     * Refresh user's presence TTL (called on heartbeat).
     *
     * @param userId User ID
     */
    public void refreshPresence(String userId) {
        String key = getPresenceKey(userId);
        String presence = redisTemplate.opsForValue().get(key);
        if (presence != null) {
            redisTemplate.expire(key, PRESENCE_TTL, PRESENCE_TTL_UNIT);
        }
    }

    /**
     * Renew the current status, restoring ONLINE only when a presence record has expired.
     */
    public void refreshOrSetOnline(String userId) {
        String key = getPresenceKey(userId);
        String presence = redisTemplate.opsForValue().get(key);
        if (presence == null) {
            setUserOnline(userId);
        } else {
            redisTemplate.expire(key, PRESENCE_TTL, PRESENCE_TTL_UNIT);
        }
    }

    /**
     * Set user presence with TTL.
     *
     * @param userId      User ID
     * @param presence    Presence status
     */
    private void setUserPresence(String userId, String presence) {
        redisTemplate.opsForValue().set(
                getPresenceKey(userId),
                presence,
                PRESENCE_TTL,
                PRESENCE_TTL_UNIT
        );
    }

    /**
     * Get Redis key for user presence.
     *
     * @param userId User ID
     * @return Redis key
     */
    private String getPresenceKey(String userId) {
        return PRESENCE_KEY_PREFIX + userId;
    }
}
