package shop.abwork.yanif.service;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.repository.GamePlayerRepository;
import shop.abwork.yanif.repository.GameRepository;
import shop.abwork.yanif.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backend query-count guardrails for the per-action broadcast path.
 *
 * Every game action fans out per-player state built by
 * GameStateController.loadRoomView, which must cost a CONSTANT number of
 * queries regardless of player count (1 game row + 1 player-rows + 1 batched
 * display-name lookup = 3). These tests pin that ceiling through the service
 * layer with Hibernate statistics so an N+1 regression fails the build.
 *
 * Guardrails:
 *  - broadcast view for a 6-player table: <= 3 queries
 *  - open-lobby listing: <= 2 queries
 */
@SpringBootTest
@ActiveProfiles("test")
class GameBroadcastQueryCountTest {

    @Autowired
    private GameService gameService;

    @Autowired
    private UserService userService;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GamePlayerRepository gamePlayerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        gamePlayerRepository.deleteAll();
        gameRepository.deleteAll();
        userRepository.deleteAll();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    private String newUser(String fp, String name, String friendCode) {
        return userRepository.save(new User(fp, name, friendCode)).getId();
    }

    @Test
    @DisplayName("Six-player broadcast view costs at most 3 queries (no N+1)")
    void broadcastViewIsConstantQueries() {
        String host = newUser("qfp1", "Q One", "QAAAAAAA");
        Game game = gameService.createGame("QAA", 100, host, 6);
        gameService.addPlayerToGame(game.getId(), host);
        for (int i = 2; i <= 6; i++) {
            String uid = newUser("qfp" + i, "Q " + i, "QAAAAAA" + i);
            gameService.addPlayerToGame(game.getId(), uid);
        }
        statistics.clear();

        // Mirror of loadRoomView: game row + player rows + batched names.
        Game loaded = gameService.getGameById(game.getId());
        assertNotNull(loaded);
        var players = gameService.getGamePlayers(game.getId());
        assertEquals(6, players.size());
        var names = userService.getUsersByIds(
                players.stream().map(p -> p.getId().getUserId()).toList());
        assertEquals(6, names.size());

        long queries = statistics.getPrepareStatementCount();
        assertTrue(queries <= 3,
                "Broadcast view took " + queries + " queries, ceiling is 3 (N+1 regression?)");
    }

    @Test
    @DisplayName("Open-lobby listing costs at most 2 queries")
    void openLobbiesAreBatched() {
        String host = newUser("lfp1", "L One", "LAAAAAAA");
        gameService.createGame("LAA", 100, host, 6);
        statistics.clear();

        List<java.util.Map<String, Object>> lobbies = gameService.getOpenLobbies();
        assertFalse(lobbies.isEmpty());

        long queries = statistics.getPrepareStatementCount();
        assertTrue(queries <= 2,
                "Open lobbies took " + queries + " queries, ceiling is 2");
    }
}
