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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing user operations.
 * Handles user registration, authentication via fingerprint, and profile updates.
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    /**
     * Display-name cache for the per-action broadcast path.
     *
     * loadRoomView needs every player's display name on EVERY mutation, and
     * names change only via updateDisplayName — so a short-TTL in-memory copy
     * removes the third broadcast query from MySQL almost entirely. Entries
     * live 60s (a rename propagates within a minute at worst) and are dropped
     * eagerly on rename/resolve so the common case is exact, not eventual.
     * User rows themselves are never cached — only the displayName string —
     * so auth/fingerprint reads always hit the database.
     */
    private static final long NAME_CACHE_TTL_MS = 60_000;
    private final ConcurrentHashMap<String, CachedName> nameCache = new ConcurrentHashMap<>();

    private record CachedName(String displayName, long expiresAt) {
    }

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
        long now = System.currentTimeMillis();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String id : userIds) {
            CachedName cached = nameCache.get(id);
            if (cached != null && cached.expiresAt() > now) {
                // Rehydrate a lightweight User carrying the cached name. Only
                // getDisplayName()/getId() of these instances are read on the
                // broadcast path; nothing here is ever saved back.
                User shell = new User();
                shell.setId(id);
                shell.setDisplayName(cached.displayName());
                byId.put(id, shell);
            } else {
                if (cached != null) {
                    nameCache.remove(id);
                }
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            for (User user : userRepository.findAllById(missing)) {
                byId.put(user.getId(), user);
                nameCache.put(user.getId(),
                        new CachedName(user.getDisplayName(), now + NAME_CACHE_TTL_MS));
            }
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
        User saved = userRepository.save(user);
        // Rename must be visible on the next broadcast, not after TTL expiry.
        nameCache.remove(userId);
        return saved;
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
