package shop.abwork.yanif.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Friendship entity representing a friendship relationship between two users.
 */
@Entity
@Table(name = "friendships", uniqueConstraints = {
        @UniqueConstraint(name = "unique_friendship", columnNames = {"user_id_1", "user_id_2"})
})
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id_1", nullable = false, length = 36)
    private String userId1;

    @Column(name = "user_id_2", nullable = false, length = 36)
    private String userId2;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private FriendshipStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum FriendshipStatus {
        PENDING, ACCEPTED, BLOCKED
    }

    public Friendship() {
    }

    public Friendship(String userId1, String userId2, FriendshipStatus status) {
        this.userId1 = userId1;
        this.userId2 = userId2;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId1() {
        return userId1;
    }

    public void setUserId1(String userId1) {
        this.userId1 = userId1;
    }

    public String getUserId2() {
        return userId2;
    }

    public void setUserId2(String userId2) {
        this.userId2 = userId2;
    }

    public FriendshipStatus getStatus() {
        return status;
    }

    public void setStatus(FriendshipStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
