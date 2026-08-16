package shop.abwork.yanif.repository;

import shop.abwork.yanif.entity.GamePlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for GamePlayer entity.
 */
@Repository
public interface GamePlayerRepository extends JpaRepository<GamePlayer, GamePlayer.GamePlayerId> {

    /**
     * Find all players in a game.
     */
    @Query("SELECT gp FROM GamePlayer gp WHERE gp.id.gameId = :gameId")
    List<GamePlayer> findByGameId(String gameId);

    /**
     * Find a player's participation in a game.
     */
    @Query("SELECT gp FROM GamePlayer gp WHERE gp.id.gameId = :gameId AND gp.id.userId = :userId")
    GamePlayer findByGameIdAndUserId(String gameId, String userId);

    /**
     * Find all games a user has participated in.
     */
    @Query("SELECT gp FROM GamePlayer gp WHERE gp.id.userId = :userId")
    List<GamePlayer> findByUserId(String userId);
}
