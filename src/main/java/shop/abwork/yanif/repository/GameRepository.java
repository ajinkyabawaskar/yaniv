package shop.abwork.yanif.repository;

import shop.abwork.yanif.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Game entity.
 */
@Repository
public interface GameRepository extends JpaRepository<Game, String> {

    Optional<Game> findByRoomCode(String roomCode);

    List<Game> findByStatusIn(List<Game.GameStatus> statuses);

    @Query("SELECT g FROM Game g WHERE g.status = :status ORDER BY g.createdAt DESC")
    List<Game> findByStatusOrderByCreatedAtDesc(@Param("status") Game.GameStatus status);
}
