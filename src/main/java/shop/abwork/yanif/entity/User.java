package shop.abwork.yanif.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * User entity representing a player in the Yaniv game.
 * Users are identified by a browser fingerprint; no password login required.
 */
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_fingerprint_hash", columnList = "fingerprint_hash"),
        @Index(name = "idx_friend_code", columnList = "friend_code")
})
public class User {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String fingerprintHash;

    @Column(nullable = false, length = 50)
    private String displayName;

    @Column(nullable = false, unique = true, length = 8)
    private String friendCode;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    public User() {
    }

    public User(String fingerprintHash, String displayName, String friendCode) {
        this.id = UUID.randomUUID().toString();
        this.fingerprintHash = fingerprintHash;
        this.displayName = displayName;
        this.friendCode = friendCode;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        this.lastSeenAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFingerprintHash() {
        return fingerprintHash;
    }

    public void setFingerprintHash(String fingerprintHash) {
        this.fingerprintHash = fingerprintHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getFriendCode() {
        return friendCode;
    }

    public void setFriendCode(String friendCode) {
        this.friendCode = friendCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
