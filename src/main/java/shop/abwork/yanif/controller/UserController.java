package shop.abwork.yanif.controller;

import shop.abwork.yanif.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for user authentication and profile management.
 */
@RestController
@RequestMapping("/api/v1/users")
@CrossOrigin(origins = "*", maxAge = 3600)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Resolve or create user based on browser fingerprint.
     * No JWT required for this endpoint (public).
     *
     * @param request Fingerprint hash and optional display name
     * @return User info with JWT token
     */
    @PostMapping("/resolve")
    public ResponseEntity<?> resolveOrCreateUser(@RequestBody ResolveUserRequest request) {
        try {
            if (request.fingerprintHash == null || request.fingerprintHash.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "fingerprintHash is required"));
            }

            UserService.UserAuthResponse response = userService.resolveOrCreateUser(
                    request.fingerprintHash.trim(),
                    request.displayName
            );

            Map<String, Object> result = new HashMap<>();
            result.put("userId", response.userId);
            result.put("displayName", response.displayName);
            result.put("friendCode", response.friendCode);
            result.put("jwtToken", response.jwtToken);
            result.put("isNewUser", response.isNewUser);

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Update user's display name.
     * Requires JWT authentication.
     *
     * @param request Display name update request
     * @param auth    Spring security authentication (contains userId)
     * @return Updated user info
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody UpdateProfileRequest request,
                                          Authentication auth) {
        try {
            if (request.displayName == null || request.displayName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "displayName is required"));
            }

            String userId = auth.getName();
            var updatedUser = userService.updateDisplayName(userId, request.displayName);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", updatedUser.getId());
            result.put("displayName", updatedUser.getDisplayName());
            result.put("friendCode", updatedUser.getFriendCode());

            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Get current user's profile.
     * Requires JWT authentication.
     *
     * @param auth Spring security authentication (contains userId)
     * @return User profile
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(Authentication auth) {
        try {
            String userId = auth.getName();
            var user = userService.getUserById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Map<String, Object> result = new HashMap<>();
            result.put("userId", user.getId());
            result.put("displayName", user.getDisplayName());
            result.put("friendCode", user.getFriendCode());
            result.put("createdAt", user.getCreatedAt());

            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Request DTOs
     */
    public static class ResolveUserRequest {
        public String fingerprintHash;
        public String displayName;
    }

    public static class UpdateProfileRequest {
        public String displayName;
    }
}
