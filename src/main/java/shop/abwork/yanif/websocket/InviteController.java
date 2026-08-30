package shop.abwork.yanif.websocket;

import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.service.FriendshipService;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.PresenceService;
import shop.abwork.yanif.service.UserService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket controller for game invitations.
 * Handles sending and responding to game invites.
 */
@Controller
public class InviteController {

    private static final String INVITE_KEY_PREFIX = "invite:";
    private static final long INVITE_TTL = 5; // minutes

    private final GameService gameService;
    private final FriendshipService friendshipService;
    private final PresenceService presenceService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    public InviteController(GameService gameService,
                           FriendshipService friendshipService,
                           PresenceService presenceService,
                           UserService userService,
                           SimpMessagingTemplate messagingTemplate,
                           RedisTemplate<String, String> redisTemplate) {
        this.gameService = gameService;
        this.friendshipService = friendshipService;
        this.presenceService = presenceService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Send a game invitation to a friend.
     *
     * Request:
     * {
     *   "friendUserId": "usr_771",
     *   "gameId": "game_123",
     *   "roomCode": "ACE"
     * }
     */
    @MessageMapping("/app/game/invite")
    public void sendGameInvite(SendInviteMessage request,
                               Authentication auth) {
        try {
            String hostUserId = auth.getName();
            String friendUserId = request.friendUserId;

            // Validate request
            if (friendUserId == null || friendUserId.isBlank()) {
                sendInviteError(hostUserId, "friendUserId is required");
                return;
            }

            if (request.gameId == null || request.gameId.isBlank()) {
                sendInviteError(hostUserId, "gameId is required");
                return;
            }

            // Verify host and friend are friends
            if (!friendshipService.areAcceptedFriends(hostUserId, friendUserId)) {
                sendInviteError(hostUserId, "Users are not friends");
                return;
            }

            // Verify friend is online
            String friendPresence = presenceService.getUserPresence(friendUserId);
            if (!friendPresence.equals(PresenceService.STATUS_ONLINE)) {
                sendInviteError(hostUserId, "Friend is not online");
                return;
            }

            // Get game and host info
            Game game = gameService.getGameById(request.gameId);
            if (game == null) {
                sendInviteError(hostUserId, "Game not found");
                return;
            }

            var hostUser = userService.getUserById(hostUserId).orElse(null);
            if (hostUser == null) {
                sendInviteError(hostUserId, "Host user not found");
                return;
            }

            // Generate invite ID
            String inviteId = "inv_" + UUID.randomUUID().toString();

            // Store invite in Redis using Map operations
            Map<String, Object> inviteData = new HashMap<>();
            inviteData.put("hostUserId", hostUserId);
            inviteData.put("hostDisplayName", hostUser.getDisplayName());
            inviteData.put("gameId", request.gameId);
            inviteData.put("roomCode", game.getRoomCode());
            inviteData.put("targetScore", game.getTargetScore().toString());
            inviteData.put("createdAt", String.valueOf(System.currentTimeMillis()));

            // Store in Redis with TTL
            String inviteKey = INVITE_KEY_PREFIX + inviteId;
            redisTemplate.opsForHash().putAll(inviteKey, inviteData);
            redisTemplate.expire(inviteKey, INVITE_TTL, TimeUnit.MINUTES);

            // Send invite notification to friend
            InviteNotificationMessage notification = new InviteNotificationMessage();
            notification.inviteId = inviteId;
            notification.hostDisplayName = hostUser.getDisplayName();
            notification.gameId = request.gameId;
            notification.roomCode = game.getRoomCode();
            notification.targetScore = game.getTargetScore();

            messagingTemplate.convertAndSendToUser(
                    friendUserId,
                    "/queue/invites",
                    notification
            );

            // Return confirmation to sender
            InviteResponseMessage response = new InviteResponseMessage();
            response.inviteId = inviteId;
            response.status = "SENT";
            response.message = "Invite sent to " + friendUserId;

            messagingTemplate.convertAndSendToUser(
                    hostUserId,
                    "/queue/invites",
                    response
            );

        } catch (Exception e) {
            sendInviteError(auth.getName(), e.getMessage());
        }
    }

    /**
     * Respond to a game invitation.
     *
     * Request:
     * {
     *   "inviteId": "inv_8821",
     *   "accepted": true/false
     * }
     */
    @MessageMapping("/app/game/invite-respond")
    public void respondToInvite(RespondInviteMessage request,
                                Authentication auth) {
        try {
            String recipientUserId = auth.getName();
            String inviteId = request.inviteId;

            if (inviteId == null || inviteId.isBlank()) {
                sendInviteError(recipientUserId, "inviteId is required");
                return;
            }

            // Get invite from Redis
            String inviteKey = INVITE_KEY_PREFIX + inviteId;
            Map<Object, Object> inviteData = redisTemplate.opsForHash().entries(inviteKey);

            if (inviteData == null || inviteData.isEmpty()) {
                sendInviteError(recipientUserId, "Invite not found or expired: " + inviteId);
                return;
            }

            String hostUserId = (String) inviteData.get("hostUserId");
            String gameId = (String) inviteData.get("gameId");

            InviteResponseMessage response = new InviteResponseMessage();
            response.inviteId = inviteId;

            if (request.accepted) {
                try {
                    // Add player to game
                    gameService.addPlayerToGame(gameId, recipientUserId);

                    response.status = "ACCEPTED";
                    response.message = "Invite accepted, joining game";
                    response.gameId = gameId;

                    // Notify host that invite was accepted
                    InviteNotificationMessage notification = new InviteNotificationMessage();
                    notification.type = "INVITE_ACCEPTED";
                    notification.inviteId = inviteId;
                    notification.acceptedByUserId = recipientUserId;

                    messagingTemplate.convertAndSendToUser(
                            hostUserId,
                            "/queue/invites",
                            notification
                    );
                } catch (Exception e) {
                    response.status = "ERROR";
                    response.message = "Failed to accept invite: " + e.getMessage();
                }
            } else {
                response.status = "DECLINED";
                response.message = "Invite declined";

                // Notify host that invite was declined
                InviteNotificationMessage notification = new InviteNotificationMessage();
                notification.type = "INVITE_DECLINED";
                notification.inviteId = inviteId;
                notification.declinedByUserId = recipientUserId;

                messagingTemplate.convertAndSendToUser(
                        hostUserId,
                        "/queue/invites",
                        notification
                );
            }

            // Delete invite from Redis
            redisTemplate.delete(inviteKey);

            messagingTemplate.convertAndSendToUser(
                    recipientUserId,
                    "/queue/invites",
                    response
            );

        } catch (Exception e) {
            sendInviteError(auth.getName(), e.getMessage());
        }
    }

    /**
     * Cancel an invitation.
     */
    @MessageMapping("/app/game/invite-cancel")
    public void cancelInvite(CancelInviteMessage request,
                             Authentication auth) {
        try {
            String hostUserId = auth.getName();
            String inviteId = request.inviteId;

            if (inviteId == null || inviteId.isBlank()) {
                sendInviteError(hostUserId, "inviteId is required");
                return;
            }

            // Get invite from Redis
            String inviteKey = INVITE_KEY_PREFIX + inviteId;
            Map<Object, Object> inviteData = redisTemplate.opsForHash().entries(inviteKey);

            if (inviteData == null || inviteData.isEmpty()) {
                sendInviteError(hostUserId, "Invite not found: " + inviteId);
                return;
            }

            // Verify canceller is the host
            String storedHostId = (String) inviteData.get("hostUserId");
            if (!storedHostId.equals(hostUserId)) {
                sendInviteError(hostUserId, "Only host can cancel invite");
                return;
            }

            // Delete invite from Redis
            redisTemplate.delete(inviteKey);

            InviteResponseMessage response = new InviteResponseMessage();
            response.inviteId = inviteId;
            response.status = "CANCELLED";
            response.message = "Invite cancelled";

            messagingTemplate.convertAndSendToUser(
                    hostUserId,
                    "/queue/invites",
                    response
            );

        } catch (Exception e) {
            sendInviteError(auth.getName(), e.getMessage());
        }
    }

    /**
     * Send error message to user.
     */
    private void sendInviteError(String userId, String error) {
        InviteResponseMessage errorMsg = new InviteResponseMessage();
        errorMsg.status = "ERROR";
        errorMsg.message = error;
        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/invites",
                errorMsg
        );
    }

    /**
     * Request DTOs
     */
    public static class SendInviteMessage {
        public String friendUserId;
        public String gameId;
    }

    public static class RespondInviteMessage {
        public String inviteId;
        public boolean accepted;
    }

    public static class CancelInviteMessage {
        public String inviteId;
    }

    /**
     * Response DTOs
     */
    public static class InviteResponseMessage {
        public String inviteId;
        public String status;           // SENT, ACCEPTED, DECLINED, CANCELLED, ERROR
        public String message;
        public String gameId;
    }

    public static class InviteNotificationMessage {
        public String type;             // INVITE (initial), INVITE_ACCEPTED, INVITE_DECLINED
        public String inviteId;
        public String hostDisplayName;
        public String gameId;
        public String roomCode;
        public Integer targetScore;
        public String acceptedByUserId;
        public String declinedByUserId;
    }
}
