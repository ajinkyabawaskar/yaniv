package shop.abwork.yanif.entity;

import jakarta.persistence.*;

/**
 * GamePlayer entity representing a player's participation in a game.
 * Uses composite primary key (gameId, userId).
 */
@Entity
@Table(name = "game_players")
public class GamePlayer {

    @EmbeddedId
    private GamePlayerId id;

    @Column
    private Integer finalScore;

    @Column
    private Integer placement;

    public GamePlayer() {
    }

    public GamePlayer(String gameId, String userId) {
        this.id = new GamePlayerId(gameId, userId);
    }

    // Getters and Setters
    public GamePlayerId getId() {
        return id;
    }

    public void setId(GamePlayerId id) {
        this.id = id;
    }

    public Integer getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(Integer finalScore) {
        this.finalScore = finalScore;
    }

    public Integer getPlacement() {
        return placement;
    }

    public void setPlacement(Integer placement) {
        this.placement = placement;
    }

    /**
     * Embedded ID class for GamePlayer composite key.
     */
    @Embeddable
    public static class GamePlayerId implements java.io.Serializable {
        @Column(length = 36)
        private String gameId;

        @Column(length = 36)
        private String userId;

        public GamePlayerId() {
        }

        public GamePlayerId(String gameId, String userId) {
            this.gameId = gameId;
            this.userId = userId;
        }

        public String getGameId() {
            return gameId;
        }

        public void setGameId(String gameId) {
            this.gameId = gameId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GamePlayerId that = (GamePlayerId) o;
            return gameId.equals(that.gameId) && userId.equals(that.userId);
        }

        @Override
        public int hashCode() {
            return 31 * gameId.hashCode() + userId.hashCode();
        }
    }
}
