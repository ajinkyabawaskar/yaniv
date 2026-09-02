package shop.abwork.yanif.service;

import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.repository.UserRepository;
import shop.abwork.yanif.security.JwtProvider;
import shop.abwork.yanif.util.FriendCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing user operations.
 * Handles user registration, authentication via fingerprint, and profile updates.
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    public UserService(UserRepository userRepository, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
    }

    /**
     * Resolve or create a user based on fingerprint hash.
     * If user exists, return existing user with JWT token.
     * If first-time user, create new user with provided display name.
     *
     * @param fingerprintHash Browser fingerprint hash
     * @param displayName     Display name (required for new users)
     * @return User object and JWT token in response object
     */
    public UserAuthResponse resolveOrCreateUser(String fingerprintHash, String displayName) {
        Optional<User> existingUser = userRepository.findByFingerprintHash(fingerprintHash);

        User user;
        boolean isNewUser = false;

        if (existingUser.isPresent()) {
            user = existingUser.get();
            // Touch lastSeenAt on every resolve (UTC)
            user.setLastSeenAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            user = userRepository.save(user);
        } else {
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("Display name is required for new users");
            }
            // Create new user
            user = new User(fingerprintHash, displayName.trim(), FriendCode.generateFriendCode());
            user = userRepository.save(user);
            isNewUser = true;
        }

        // Generate JWT token
        String jwtToken = jwtProvider.generateToken(user.getId(), fingerprintHash);

        return new UserAuthResponse(user.getId(), user.getDisplayName(), user.getFriendCode(),
                jwtToken, isNewUser);
    }

    /**
     * Get user by ID.
     *
     * @param userId User ID
     * @return User object or empty if not found
     */
    public Optional<User> getUserById(String userId) {
        return userRepository.findById(userId);
    }

    /**
     * Look up many users at once, keyed by id.
     *
     * One query instead of one per id — the game-state broadcast needs every player's
     * display name on every mutation.
     */
    public Map<String, User> getUsersByIds(Collection<String> userIds) {
        Map<String, User> byId = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return byId;
        }
        for (User user : userRepository.findAllById(userIds)) {
            byId.put(user.getId(), user);
        }
        return byId;
    }

    /**
     * Get user by friend code.
     *
     * @param friendCode 8-character friend code
     * @return User object or empty if not found
     */
    public Optional<User> getUserByFriendCode(String friendCode) {
        return userRepository.findByFriendCode(friendCode);
    }

    /**
     * Update user's display name.
     *
     * @param userId      User ID
     * @param displayName New display name
     * @return Updated user object
     */
    public User updateDisplayName(String userId, String displayName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        user.setDisplayName(displayName.trim());
        return userRepository.save(user);
    }

    /**
     * Response DTO for user authentication.
     */
    public static class UserAuthResponse {
        public String userId;
        public String displayName;
        public String friendCode;
        public String jwtToken;
        public boolean isNewUser;

        public UserAuthResponse(String userId, String displayName, String friendCode,
                                String jwtToken, boolean isNewUser) {
            this.userId = userId;
            this.displayName = displayName;
            this.friendCode = friendCode;
            this.jwtToken = jwtToken;
            this.isNewUser = isNewUser;
        }
    }
}
