package shop.abwork.yanif.websocket;

import shop.abwork.yanif.service.FriendshipService;
import shop.abwork.yanif.service.PresenceService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket controller for user presence tracking and broadcasting.
 * Manages online/offline statuses and notifies friends of presence changes.
 */
@Controller
public class PresenceController {

    private final PresenceService presenceService;
    private final FriendshipService friendshipService;
    private final SimpMessagingTemplate messagingTemplate;

    // Track every active WebSocket session so closing one tab does not hide another.
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();

    public PresenceController(PresenceService presenceService,
                             FriendshipService friendshipService,
                             SimpMessagingTemplate messagingTemplate) {
        this.presenceService = presenceService;
        this.friendshipService = friendshipService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * HTTP fallback for clients whose WebSocket connection has not completed yet.
     */
    @PostMapping("/api/v1/presence/online")
    public ResponseEntity<?> markUserOnline(Authentication auth) {
        if (auth == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = auth.getName();
        presenceService.refreshOrSetOnline(userId);
        broadcastPresenceToFriends(userId, presenceService.getUserPresence(userId));
        return ResponseEntity.ok(Map.of("presence", presenceService.getUserPresence(userId)));
    }

    /**
     * Handle WebSocket connection.
     * Sets user to ONLINE and broadcasts presence to all friends.
     */
    @EventListener
    public void handleWebSocketConnect(SessionConnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        // Extract user ID from authentication (set in SecurityConfig)
        // This will be populated by Spring Security after JWT validation
        String userId = headerAccessor.getUser() != null ?
                headerAccessor.getUser().getName() : null;

        if (userId != null) {
            userSessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(sessionId);
            presenceService.refreshOrSetOnline(userId);

            // Notify all friends that this user is now online
            broadcastPresenceToFriends(userId, PresenceService.STATUS_ONLINE);
        }
    }

    /**
     * Handle WebSocket disconnection.
     * Sets user to OFFLINE and broadcasts presence to all friends.
     */
    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        // Find user by session ID
        userSessions.entrySet().stream()
                .filter(entry -> entry.getValue().contains(sessionId))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(userId -> markSessionOffline(userId, sessionId));
    }

    /**
     * Subscribe to friend presence updates.
     * On subscription, sends current status of all friends.
     */
    @MessageMapping("/presence/subscribe")
    public void subscribeToPresence(Authentication auth) {
        try {
            String userId = auth.getName();
            presenceService.refreshOrSetOnline(userId);
            broadcastPresenceToFriends(userId, presenceService.getUserPresence(userId));

            // Get list of friends
            List<FriendshipService.FriendInfo> friends =
                    friendshipService.getFriendsWithPresence(userId);

            // Send all friends' current presence statuses
            PresenceUpdateMessage response = new PresenceUpdateMessage();
            response.type = "FRIENDS_STATUS";
            response.friendsPresence = new HashMap<>();

            for (FriendshipService.FriendInfo friend : friends) {
                response.friendsPresence.put(friend.userId, friend.presence);
            }

            messagingTemplate.convertAndSendToUser(
                    userId,
                    "/queue/presence",
                    response
            );
        } catch (Exception e) {
            // Log error but don't crash connection
            System.err.println("Error subscribing to presence: " + e.getMessage());
        }
    }

    /**
     * Heartbeat message to keep presence alive.
     * Client sends periodic heartbeats to refresh presence TTL.
     */
    @MessageMapping("/presence/heartbeat")
    public void handleHeartbeat(Authentication auth) {
        if (auth != null) {
            String userId = auth.getName();
            presenceService.refreshOrSetOnline(userId);
        }
    }

    /**
     * Browser lifecycle fallback when a tab is closed before the STOMP disconnect event arrives.
     */
    @MessageMapping("/presence/offline")
    public void handleOffline(Authentication auth, SimpMessageHeaderAccessor headerAccessor) {
        if (auth != null) {
            markSessionOffline(auth.getName(), headerAccessor.getSessionId());
        }
    }

    private void markSessionOffline(String userId, String sessionId) {
        Set<String> sessions = userSessions.get(userId);
        if (sessions != null && sessionId != null) {
            sessions.remove(sessionId);
        }

        if (sessions != null && !sessions.isEmpty()) {
            return;
        }

        userSessions.remove(userId);
        presenceService.setUserOffline(userId);
        broadcastPresenceToFriends(userId, PresenceService.STATUS_OFFLINE);
    }

    /**
     * Broadcast presence change to all friends of a user.
     */
    private void broadcastPresenceToFriends(String userId, String status) {
        // Get all friends of this user
        List<String> friendIds = friendshipService.getAcceptedFriends(userId);

        // Send presence update to each friend
        for (String friendId : friendIds) {
            PresenceUpdateMessage message = new PresenceUpdateMessage();
            message.type = "PRESENCE_CHANGED";
            message.userId = userId;
            message.presence = status;

            messagingTemplate.convertAndSendToUser(
                    friendId,
                    "/queue/presence",
                    message
            );
        }
    }

    /**
     * Response DTO for presence updates
     */
    public static class PresenceUpdateMessage {
        public String type;                         // FRIENDS_STATUS, PRESENCE_CHANGED
        public String userId;                       // For single presence changes
        public String presence;                     // ONLINE, OFFLINE, IN_GAME
        public Map<String, String> friendsPresence; // For bulk status update
    }
}
