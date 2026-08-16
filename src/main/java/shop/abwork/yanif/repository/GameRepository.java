package shop.abwork.yanif.repository;

import shop.abwork.yanif.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for Game entity.
 */
@Repository
public interface GameRepository extends JpaRepository<Game, String> {

    Optional<Game> findByRoomCode(String roomCode);
}
