package shop.abwork.yanif.repository;

import shop.abwork.yanif.entity.RoundHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for RoundHistory entity.
 */
@Repository
public interface RoundHistoryRepository extends JpaRepository<RoundHistory, Long> {

    /**
     * Find all rounds for a specific game.
     */
    List<RoundHistory> findByGameIdOrderByRoundNumber(String gameId);
}
