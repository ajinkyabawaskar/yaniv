package shop.abwork.yanif.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * RoundHistory entity tracking each round played in a game.
 * Stores round-specific scoring, Yaniv calls, and Asaf situations.
 */
@Entity
@Table(name = "round_histories")
public class RoundHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String gameId;

    @Column(nullable = false)
    private Integer roundNumber;

    @Column(nullable = false, length = 36)
    private String callerId;

    @Column(nullable = false)
    private Boolean isAsaf;

    @Column(length = 36)
    private String asafByUserId;

    @Column(nullable = false, columnDefinition = "JSON")
    private String roundScoresJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RoundHistory() {
    }

    public RoundHistory(String gameId, Integer roundNumber, String callerId,
                       Boolean isAsaf, String asafByUserId, String roundScoresJson) {
        this.gameId = gameId;
        this.roundNumber = roundNumber;
        this.callerId = callerId;
        this.isAsaf = isAsaf;
        this.asafByUserId = asafByUserId;
        this.roundScoresJson = roundScoresJson;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public Integer getRoundNumber() {
        return roundNumber;
    }

    public void setRoundNumber(Integer roundNumber) {
        this.roundNumber = roundNumber;
    }

    public String getCallerId() {
        return callerId;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
    }

    public Boolean getAsaf() {
        return isAsaf;
    }

    public void setAsaf(Boolean asaf) {
        isAsaf = asaf;
    }

    public String getAsafByUserId() {
        return asafByUserId;
    }

    public void setAsafByUserId(String asafByUserId) {
        this.asafByUserId = asafByUserId;
    }

    public String getRoundScoresJson() {
        return roundScoresJson;
    }

    public void setRoundScoresJson(String roundScoresJson) {
        this.roundScoresJson = roundScoresJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
