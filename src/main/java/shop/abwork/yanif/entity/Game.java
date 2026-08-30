package shop.abwork.yanif.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Game entity representing a Yaniv game room/session.
 */
@Entity
@Table(name = "games", indexes = {
        @Index(name = "idx_room_code", columnList = "room_code")
})
public class Game {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 6)
    private String roomCode;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private GameStatus status;

    @Column(nullable = false)
    private Integer targetScore;

    @Column(nullable = false)
    private Integer maxPlayers;

    @Column(nullable = false, length = 36)
    private String hostUserId;

    @Column(length = 36)
    private String winnerId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime finishedAt;

    public enum GameStatus {
        LOBBY, IN_PROGRESS, FINISHED
    }

    public Game() {
    }

    public Game(String roomCode, Integer targetScore, String hostUserId) {
        this(roomCode, targetScore, hostUserId, 6); // Default 6 players
    }

    public Game(String roomCode, Integer targetScore, String hostUserId, Integer maxPlayers) {
        this.id = UUID.randomUUID().toString();
        this.roomCode = roomCode;
        this.status = GameStatus.LOBBY;
        this.targetScore = targetScore != null ? targetScore : 100;
        this.maxPlayers = maxPlayers != null ? maxPlayers : 6;
        this.hostUserId = hostUserId;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public Integer getTargetScore() {
        return targetScore;
    }

    public void setTargetScore(Integer targetScore) {
        this.targetScore = targetScore;
    }

    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(Integer maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public String getHostUserId() {
        return hostUserId;
    }

    public void setHostUserId(String hostUserId) {
        this.hostUserId = hostUserId;
    }

    public String getWinnerId() {
        return winnerId;
    }

    public void setWinnerId(String winnerId) {
        this.winnerId = winnerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
