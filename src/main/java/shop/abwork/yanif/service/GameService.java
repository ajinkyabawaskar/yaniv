package shop.abwork.yanif.service;

import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.entity.RoundHistory;
import shop.abwork.yanif.repository.GameRepository;
import shop.abwork.yanif.repository.GamePlayerRepository;
import shop.abwork.yanif.repository.RoundHistoryRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

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
     * @param roomCode    3-letter room code
     * @param targetScore Target score for game elimination (default 100)
     * @param hostUserId  Host user ID
     * @param maxPlayers  Maximum players (default 6)
     * @return Created game object
     */
    public Game createGame(String roomCode, Integer targetScore, String hostUserId, Integer maxPlayers) {
        Game game = new Game(roomCode, targetScore, hostUserId, maxPlayers);
        return gameRepository.save(game);
    }

    /**
     * Create a new game room with default max players (6).
     *
     * @param roomCode    3-letter room code
     * @param targetScore Target score for game elimination (default 100)
     * @param hostUserId  Host user ID
     * @return Created game object
     */
    public Game createGame(String roomCode, Integer targetScore, String hostUserId) {
        return createGame(roomCode, targetScore, hostUserId, 6);
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
     * @param roomCode 3-letter room code
     * @return Game object or null if not found
     */
    public Game getGameByRoomCode(String roomCode) {
        return gameRepository.findByRoomCode(roomCode).orElse(null);
    }

    /**
     * Get all active games (IN_PROGRESS status).
     * Used for reconnection handling when game engine is not in memory.
     *
     * @return List of active games
     */
    public List<Game> getActiveGames() {
        return gameRepository.findByStatusIn(List.of(
                Game.GameStatus.IN_PROGRESS
        ));
    }

    /**
     * Get open lobbies (games in LOBBY status) created in last 5 minutes,
     * ordered by most recent first. Returns up to 3 lobbies with player counts.
     *
     * @return List of open lobbies with player count info
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getOpenLobbies() {
        List<Game> lobbies = gameRepository.findByStatusOrderByCreatedAtDesc(Game.GameStatus.LOBBY);

        // Only lobbies created in last 5 minutes, up to 3 most recent
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusMinutes(5);
        lobbies = lobbies.stream()
                .filter(g -> g.getCreatedAt() != null && g.getCreatedAt().isAfter(cutoff))
                .limit(3)
                .collect(Collectors.toList());
        
        // Fetch player counts for each lobby
        List<String> gameIds = lobbies.stream().map(Game::getId).collect(Collectors.toList());
        Map<String, Long> playerCounts = new HashMap<>();
        if (!gameIds.isEmpty()) {
            List<Object[]> counts = gamePlayerRepository.countByGameIdIn(gameIds);
            for (Object[] row : counts) {
                playerCounts.put((String) row[0], (Long) row[1]);
            }
        }
        
        // Build response with game info and player count
        List<Map<String, Object>> result = new ArrayList<>();
        for (Game game : lobbies) {
            Map<String, Object> lobby = new HashMap<>();
            lobby.put("gameId", game.getId());
            lobby.put("roomCode", game.getRoomCode());
            lobby.put("status", game.getStatus().toString());
            lobby.put("targetScore", game.getTargetScore());
            lobby.put("maxPlayers", game.getMaxPlayers());
            lobby.put("hostUserId", game.getHostUserId());
            lobby.put("createdAt", game.getCreatedAt());
            lobby.put("playerCount", playerCounts.getOrDefault(game.getId(), 0L));
            result.add(lobby);
        }
        
        return result;
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
     * Delete persisted game state (called when a game finishes or is aborted).
     *
     * @param gameId Game ID
     */
    public void deleteGameState(String gameId) {
        redisTemplate.delete(getGameStateKey(gameId));
    }

    /**
     * Persist one completed round to history.
     *
     * @param roundScoresJson JSON map of playerId -> round score
     */
    public void saveRoundHistory(String gameId, Integer roundNumber, String callerId,
                                 Boolean isAsaf, String asafByUserId, String roundScoresJson) {
        roundHistoryRepository.save(
                new RoundHistory(gameId, roundNumber, callerId, isAsaf, asafByUserId, roundScoresJson));
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
