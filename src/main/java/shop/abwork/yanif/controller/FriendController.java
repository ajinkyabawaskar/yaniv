package shop.abwork.yanif.controller;

import shop.abwork.yanif.entity.Friendship;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.service.FriendshipService;
import shop.abwork.yanif.repository.UserRepository;
import shop.abwork.yanif.util.FriendCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for friend management and invitations.
 */
@RestController
@RequestMapping("/api/v1/friends")
@CrossOrigin(origins = "*", maxAge = 3600)
public class FriendController {

    private final FriendshipService friendshipService;
    private final UserRepository userRepository;

    public FriendController(FriendshipService friendshipService, UserRepository userRepository) {
        this.friendshipService = friendshipService;
        this.userRepository = userRepository;
    }

    /**
     * Send a friend request using recipient's friend code.
     * Requires JWT authentication.
     *
     * @param request Friend request with friend code
     * @param auth    Spring security authentication (contains userId)
     * @return Created friendship request info
     */
    @PostMapping("/request")
    public ResponseEntity<?> sendFriendRequest(@RequestBody SendFriendRequestRequest request,
                                              Authentication auth) {
        try {
            String senderId = auth.getName();

            if (request.friendCode == null || request.friendCode.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "friendCode is required"));
            }

            if (!FriendCode.isValidFriendCode(request.friendCode)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid friend code format"));
            }

            Friendship friendship = friendshipService.sendFriendRequest(
                    senderId, request.friendCode.toUpperCase().trim()
            );

            Map<String, Object> result = new HashMap<>();
            result.put("friendshipId", friendship.getId());
            result.put("status", friendship.getStatus().toString());
            result.put("createdAt", friendship.getCreatedAt());

            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Respond to a pending friend request.
     * Requires JWT authentication.
     *
     * @param request Response with acceptance flag
     * @param auth    Spring security authentication (contains userId)
     * @return Updated friendship status
     */
    @PostMapping("/respond")
    public ResponseEntity<?> respondToFriendRequest(@RequestBody RespondFriendRequestRequest request,
                                                    Authentication auth) {
        try {
            String recipientId = auth.getName();

            if (request.friendshipId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "friendshipId is required"));
            }

            Friendship friendship = friendshipService.respondToRequest(
                    recipientId, request.friendshipId, request.accepted
            );

            Map<String, Object> result = new HashMap<>();
            result.put("friendshipId", friendship.getId());
            result.put("status", friendship.getStatus().toString());

            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Get list of friends with their online/offline status.
     * Requires JWT authentication.
     *
     * @param auth Spring security authentication (contains userId)
     * @return List of friends with presence info
     */
    @GetMapping("/list")
    public ResponseEntity<?> getFriendsList(Authentication auth) {
        try {
            String userId = auth.getName();
            List<FriendshipService.FriendInfo> friends =
                    friendshipService.getFriendsWithPresence(userId);

            Map<String, Object> result = new HashMap<>();
            result.put("friends", friends);
            result.put("count", friends.size());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Get pending friend requests.
     * Requires JWT authentication.
     *
     * @param auth Spring security authentication (contains userId)
     * @return List of pending friendship requests
     */
    @GetMapping("/pending-requests")
    public ResponseEntity<?> getPendingRequests(Authentication auth) {
        try {
            String userId = auth.getName();
            List<Friendship> requests = friendshipService.getPendingRequests(userId);

            List<Map<String, Object>> requestsList = requests.stream()
                    .map(f -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("friendshipId", f.getId());
                        map.put("fromUserId", f.getUserId1());
                        userRepository.findById(f.getUserId1()).ifPresent(sender -> {
                            map.put("fromDisplayName", sender.getDisplayName());
                            map.put("fromFriendCode", sender.getFriendCode());
                        });
                        map.put("createdAt", f.getCreatedAt());
                        return map;
                    })
                    .toList();

            Map<String, Object> result = new HashMap<>();
            result.put("requests", requestsList);
            result.put("count", requests.size());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Request DTOs
     */
    public static class SendFriendRequestRequest {
        public String friendCode;
    }

    public static class RespondFriendRequestRequest {
        public Long friendshipId;
        public boolean accepted;
    }
}
