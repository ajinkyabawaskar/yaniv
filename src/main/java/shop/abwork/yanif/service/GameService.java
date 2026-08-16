package shop.abwork.yanif.service;

import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.repository.GameRepository;
import shop.abwork.yanif.repository.GamePlayerRepository;
import shop.abwork.yanif.repository.RoundHistoryRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing Yaniv game operations.
 * Handles game creation, player management, and game state.
 */
@Service
@Transactional
public class GameService {

    private static final String GAME_STATE_KEY_PREFIX = "game:";
    private static final String GAME_STATE_SUFFIX = ":state";
    private static final long GAME_STATE_TTL = 24; // hours

    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final RoundHistoryRepository roundHistoryRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public GameService(GameRepository gameRepository,
                      GamePlayerRepository gamePlayerRepository,
                      RoundHistoryRepository roundHistoryRepository,
                      RedisTemplate<String, String> redisTemplate) {
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.roundHistoryRepository = roundHistoryRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Create a new game room.
     *
     * @param roomCode    6-character room code
     * @param targetScore Target score for game elimination (default 200)
     * @return Created game object
     */
    public Game createGame(String roomCode, Integer targetScore, String hostUserId) {
        Game game = new Game(roomCode, targetScore, hostUserId);
        return gameRepository.save(game);
    }

    /**
     * Get game by ID.
     *
     * @param gameId Game ID
     * @return Game object or null if not found
     */
    public Game getGameById(String gameId) {
        return gameRepository.findById(gameId).orElse(null);
    }

    /**
     * Get game by room code.
     *
     * @param roomCode 6-character room code
     * @return Game object or null if not found
     */
    public Game getGameByRoomCode(String roomCode) {
        return gameRepository.findByRoomCode(roomCode).orElse(null);
    }

    /**
     * Add a player to a game.
     *
     * @param gameId Game ID
     * @param userId User ID
     * @return GamePlayer record
     */
    public GamePlayer addPlayerToGame(String gameId, String userId) {
        Game game = getGameById(gameId);
        if (game == null) {
            throw new RuntimeException("Game not found: " + gameId);
        }

        // Check if player already in game
        GamePlayer existing = gamePlayerRepository.findByGameIdAndUserId(gameId, userId);
        if (existing != null) {
            throw new RuntimeException("Player already in game: " + userId);
        }

        GamePlayer gamePlayer = new GamePlayer(gameId, userId);
        return gamePlayerRepository.save(gamePlayer);
    }

    /**
     * Remove a player from a game.
     *
     * @param gameId Game ID
     * @param userId User ID
     */
    public void removePlayerFromGame(String gameId, String userId) {
        GamePlayer gamePlayer = gamePlayerRepository.findByGameIdAndUserId(gameId, userId);
        if (gamePlayer != null) {
            gamePlayerRepository.delete(gamePlayer);
        }
    }

    /**
     * Get all players in a game.
     *
     * @param gameId Game ID
     * @return List of game players
     */
    public List<GamePlayer> getGamePlayers(String gameId) {
        return gamePlayerRepository.findByGameId(gameId);
    }

    /**
     * Update game status.
     *
     * @param gameId Game ID
     * @param status New game status
     * @return Updated game object
     */
    public Game updateGameStatus(String gameId, Game.GameStatus status) {
        Game game = getGameById(gameId);
        if (game == null) {
            throw new RuntimeException("Game not found: " + gameId);
        }
        game.setStatus(status);
        return gameRepository.save(game);
    }

    /**
     * Finish a game (mark as FINISHED and set winner).
     *
     * @param gameId   Game ID
     * @param winnerId Winner user ID
     * @return Updated game object
     */
    public Game finishGame(String gameId, String winnerId) {
        Game game = getGameById(gameId);
        if (game == null) {
            throw new RuntimeException("Game not found: " + gameId);
        }
        game.setStatus(Game.GameStatus.FINISHED);
        game.setWinnerId(winnerId);
        game.setFinishedAt(java.time.LocalDateTime.now());
        return gameRepository.save(game);
    }

    /**
     * Store game state in Redis.
     * (Implementation will be done when game engine is created)
     *
     * @param gameId      Game ID
     * @param gameState   Game state JSON/object
     */
    public void saveGameState(String gameId, String gameState) {
        String key = getGameStateKey(gameId);
        redisTemplate.opsForValue().set(key, gameState);
        redisTemplate.expire(key, GAME_STATE_TTL, java.util.concurrent.TimeUnit.HOURS);
    }

    /**
     * Retrieve game state from Redis.
     * (Implementation will be done when game engine is created)
     *
     * @param gameId Game ID
     * @return Game state JSON/object or null if not found
     */
    public String getGameState(String gameId) {
        return redisTemplate.opsForValue().get(getGameStateKey(gameId));
    }

    /**
     * Get Redis key for game state.
     *
     * @param gameId Game ID
     * @return Redis key
     */
    private String getGameStateKey(String gameId) {
        return GAME_STATE_KEY_PREFIX + gameId + GAME_STATE_SUFFIX;
    }
}
