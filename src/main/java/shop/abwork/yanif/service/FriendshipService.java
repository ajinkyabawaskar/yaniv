package shop.abwork.yanif.service;

import shop.abwork.yanif.entity.Friendship;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.repository.FriendshipRepository;
import shop.abwork.yanif.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing friendship relationships.
 * Handles friend requests, acceptance/decline, and friendship queries.
 */
@Service
@Transactional
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final PresenceService presenceService;

    public FriendshipService(FriendshipRepository friendshipRepository,
                             UserRepository userRepository,
                             PresenceService presenceService) {
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
        this.presenceService = presenceService;
    }

    /**
     * Send a friend request using the recipient's friend code.
     *
     * @param senderId       User ID of the sender
     * @param recipientCode  Friend code of the recipient
     * @return Created friendship with ACCEPTED status
     */
    public Friendship sendFriendRequest(String senderId, String recipientCode) {
        // Find recipient by friend code
        User recipient = userRepository.findByFriendCode(recipientCode)
                .orElseThrow(() -> new RuntimeException("Friend code not found: " + recipientCode));

        // Check if friendship already exists
        Optional<Friendship> existing = friendshipRepository.findBetween(senderId, recipient.getId());
        if (existing.isPresent()) {
            if (existing.get().getStatus() == Friendship.FriendshipStatus.ACCEPTED) {
                throw new RuntimeException("Already friends with this user");
            } else if (existing.get().getStatus() == Friendship.FriendshipStatus.PENDING) {
                // Friend codes are shared intentionally, so retrying a prior request completes it.
                existing.get().setStatus(Friendship.FriendshipStatus.ACCEPTED);
                return friendshipRepository.save(existing.get());
            }
        }

        // Friend codes are an explicit opt-in; one accepted row represents a mutual friendship.
        Friendship friendship = new Friendship(senderId, recipient.getId(),
                Friendship.FriendshipStatus.ACCEPTED);
        return friendshipRepository.save(friendship);
    }

    /**
     * Respond to a pending friend request.
     *
     * @param recipientId    User ID of the recipient of the original request
     * @param friendshipId   ID of the friendship request
     * @param accepted       true to accept, false to decline
     * @return Updated friendship (or deleted if declined)
     */
    public Friendship respondToRequest(String recipientId, Long friendshipId, boolean accepted) {
        Friendship friendship = friendshipRepository.findById(friendshipId)
                .orElseThrow(() -> new RuntimeException("Friendship not found: " + friendshipId));

        // Verify the recipient is the intended recipient
        if (!friendship.getUserId2().equals(recipientId)) {
            throw new RuntimeException("Unauthorized: not the recipient of this request");
        }

        if (friendship.getStatus() != Friendship.FriendshipStatus.PENDING) {
            throw new RuntimeException("Friendship is not in PENDING status");
        }

        if (accepted) {
            friendship.setStatus(Friendship.FriendshipStatus.ACCEPTED);
            return friendshipRepository.save(friendship);
        } else {
            friendshipRepository.delete(friendship);
            return friendship;
        }
    }

    /**
     * Get all accepted friends for a user.
     *
     * @param userId User ID
     * @return List of friend user IDs
     */
    public List<String> getAcceptedFriends(String userId) {
        List<Friendship> friendships = friendshipRepository.findActiveFriendships(userId);
        List<Friendship> legacyRequests = friendships.stream()
                .filter(friendship -> friendship.getStatus() == Friendship.FriendshipStatus.PENDING)
                .peek(friendship -> friendship.setStatus(Friendship.FriendshipStatus.ACCEPTED))
                .collect(Collectors.toList());

        if (!legacyRequests.isEmpty()) {
            friendshipRepository.saveAll(legacyRequests);
        }

        return friendships.stream()
                .map(f -> userId.equals(f.getUserId1()) ? f.getUserId2() : f.getUserId1())
                .collect(Collectors.toList());
    }

    /**
     * Get all accepted friends with their presence status.
     *
     * @param userId User ID
     * @return List of friends with display name and presence status
     */
    public List<FriendInfo> getFriendsWithPresence(String userId) {
        List<String> friendIds = getAcceptedFriends(userId);
        return friendIds.stream()
                .map(friendId -> {
                    User friend = userRepository.findById(friendId).orElse(null);
                    if (friend != null) {
                        String presence = presenceService.getUserPresence(friendId);
                        return new FriendInfo(friend.getId(), friend.getDisplayName(),
                                friend.getFriendCode(), presence);
                    }
                    return null;
                })
                .filter(f -> f != null)
                .collect(Collectors.toList());
    }

    /**
     * Get pending friend requests for a user.
     *
     * @param userId User ID
     * @return List of pending friendship requests
     */
    public List<Friendship> getPendingRequests(String userId) {
        return friendshipRepository.findPendingRequests(userId);
    }

    /**
     * Check if two users are accepted friends.
     *
     * @param userId1 First user ID
     * @param userId2 Second user ID
     * @return true if they are accepted friends
     */
    public boolean areAcceptedFriends(String userId1, String userId2) {
        return friendshipRepository.areAcceptedFriends(userId1, userId2);
    }

    /**
     * DTO for friend information with presence status.
     */
    public static class FriendInfo {
        public String userId;
        public String displayName;
        public String friendCode;
        public String presence;  // ONLINE, OFFLINE, IN_GAME

        public FriendInfo(String userId, String displayName, String friendCode, String presence) {
            this.userId = userId;
            this.displayName = displayName;
            this.friendCode = friendCode;
            this.presence = presence;
        }
    }
}
