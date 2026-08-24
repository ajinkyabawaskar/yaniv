package shop.abwork.yanif.controller;

import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for game room management.
 */
@RestController
@RequestMapping("/api/v1/rooms")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RoomController {

    private static final int ROOM_CODE_LENGTH = 6;
    private static final String ROOM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Random RANDOM = new Random();

    private final GameService gameService;
    private final UserService userService;

    public RoomController(GameService gameService, UserService userService) {
        this.gameService = gameService;
        this.userService = userService;
    }

    /**
     * Create a new game room.
     * Requires JWT authentication.
     *
     * @param request Room creation request
     * @param auth    Spring security authentication (contains userId)
     * @return Created game room info with room code
     */
    @PostMapping
    public ResponseEntity<?> createRoom(@RequestBody CreateRoomRequest request,
                                       Authentication auth) {
        try {
            String hostUserId = auth.getName();

            // Validate user exists
            userService.getUserById(hostUserId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Generate unique room code
            String roomCode = generateUniqueRoomCode();

            // Determine target score (default 200)
            Integer targetScore = request.targetScore != null ? request.targetScore : 200;
            // Determine max players (default 6)
            Integer maxPlayers = request.maxPlayers != null ? request.maxPlayers : 6;

            // Create game
            Game game = gameService.createGame(roomCode, targetScore, hostUserId, maxPlayers);

            // Add host as first player
            gameService.addPlayerToGame(game.getId(), hostUserId);

            Map<String, Object> result = new HashMap<>();
            result.put("gameId", game.getId());
            result.put("roomCode", game.getRoomCode());
            result.put("status", game.getStatus().toString());
            result.put("targetScore", game.getTargetScore());
            result.put("hostUserId", game.getHostUserId());
            result.put("createdAt", game.getCreatedAt());

            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Join an existing game room using room code.
     * Requires JWT authentication.
     *
     * @param roomCode Room code (6 characters)
     * @param auth     Spring security authentication (contains userId)
     * @return Game room info
     */
    @PostMapping("/{roomCode}/join")
    public ResponseEntity<?> joinRoom(@PathVariable String roomCode,
                                     Authentication auth) {
        try {
            String userId = auth.getName();

            // Validate user exists
            userService.getUserById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Find game by room code
            Game game = gameService.getGameByRoomCode(roomCode.toUpperCase());
            if (game == null) {
                return ResponseEntity.notFound().build();
            }

            // Existing members may re-enter at any time (reconnection via invite link)
            boolean alreadyMember = gameService.getGamePlayers(game.getId()).stream()
                    .anyMatch(player -> player.getId().getUserId().equals(userId));

            // New joins are only allowed while the room is in LOBBY
            if (game.getStatus() != Game.GameStatus.LOBBY && !alreadyMember) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Game is not in lobby state"));
            }

            // Joining is idempotent so a player can re-enter a room after leaving its UI.
            if (!alreadyMember) {
                // Check max players limit
                int currentPlayerCount = gameService.getGamePlayers(game.getId()).size();
                int maxPlayers = game.getMaxPlayers() != null ? game.getMaxPlayers() : 6;
                if (currentPlayerCount >= maxPlayers) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Room is full (max " + maxPlayers + " players)"));
                }
                gameService.addPlayerToGame(game.getId(), userId);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("gameId", game.getId());
            result.put("roomCode", game.getRoomCode());
            result.put("status", game.getStatus().toString());
            result.put("targetScore", game.getTargetScore());
            result.put("hostUserId", game.getHostUserId());
            result.put("alreadyJoined", alreadyMember);
            result.put("message", alreadyMember ? "Already in room" : "Successfully joined room");

            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Get game info by room code (public endpoint, no JWT required).
     *
     * @param roomCode Room code (6 characters)
     * @return Game info including players
     */
    @GetMapping("/code/{roomCode}")
    public ResponseEntity<?> getGameByCode(@PathVariable String roomCode) {
        try {
            Game game = gameService.getGameByRoomCode(roomCode.toUpperCase());
            if (game == null) {
                return ResponseEntity.notFound().build();
            }

            List<GamePlayer> players = gameService.getGamePlayers(game.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("gameId", game.getId());
            result.put("roomCode", game.getRoomCode());
            result.put("status", game.getStatus().toString());
            result.put("targetScore", game.getTargetScore());
            result.put("hostUserId", game.getHostUserId());
            result.put("playerCount", players.size());
            result.put("createdAt", game.getCreatedAt());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Get game details by game ID.
     * Requires JWT authentication.
     *
     * @param gameId Game ID
     * @param auth   Spring security authentication
     * @return Game details
     */
    @GetMapping("/{gameId}")
    public ResponseEntity<?> getGameById(@PathVariable String gameId,
                                        Authentication auth) {
        try {
            Game game = gameService.getGameById(gameId);
            if (game == null) {
                return ResponseEntity.notFound().build();
            }

            List<GamePlayer> players = gameService.getGamePlayers(gameId);

            Map<String, Object> result = new HashMap<>();
            result.put("gameId", game.getId());
            result.put("roomCode", game.getRoomCode());
            result.put("status", game.getStatus().toString());
            result.put("targetScore", game.getTargetScore());
            result.put("hostUserId", game.getHostUserId());
            result.put("playerCount", players.size());
            result.put("winnerId", game.getWinnerId());
            result.put("createdAt", game.getCreatedAt());
            result.put("finishedAt", game.getFinishedAt());

            // Add player list with display names and host status
            List<Map<String, Object>> playerList = new ArrayList<>();
            for (GamePlayer gp : players) {
                String playerUserId = gp.getId().getUserId();
                userService.getUserById(playerUserId).ifPresent(u -> {
                    Map<String, Object> playerInfo = new HashMap<>();
                    playerInfo.put("userId", playerUserId);
                    playerInfo.put("displayName", u.getDisplayName());
                    playerInfo.put("isHost", game.getHostUserId().equals(playerUserId));
                    playerInfo.put("status", "ONLINE");
                    playerList.add(playerInfo);
                });
            }
            result.put("players", playerList);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Get players in a game.
     * Requires JWT authentication.
     *
     * @param gameId Game ID
     * @param auth   Spring security authentication
     * @return List of players
     */
    @GetMapping("/{gameId}/players")
    public ResponseEntity<?> getGamePlayers(@PathVariable String gameId,
                                           Authentication auth) {
        try {
            Game game = gameService.getGameById(gameId);
            if (game == null) {
                return ResponseEntity.notFound().build();
            }

            List<GamePlayer> players = gameService.getGamePlayers(gameId);
            List<Map<String, Object>> playersList = players.stream()
                    .map(p -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("userId", p.getId().getUserId());
                        map.put("finalScore", p.getFinalScore());
                        map.put("placement", p.getPlacement());
                        return map;
                    })
                    .toList();

            Map<String, Object> result = new HashMap<>();
            result.put("players", playersList);
            result.put("count", players.size());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "An error occurred: " + e.getMessage()));
        }
    }

    /**
     * Generate a unique 6-character room code.
     * (In production, should check database for uniqueness)
     *
     * @return Random 6-character room code
     */
    private String generateUniqueRoomCode() {
        for (int i = 0; i < 10; i++) { // Retry up to 10 times
            StringBuilder code = new StringBuilder(ROOM_CODE_LENGTH);
            for (int j = 0; j < ROOM_CODE_LENGTH; j++) {
                code.append(ROOM_CODE_CHARS.charAt(RANDOM.nextInt(ROOM_CODE_CHARS.length())));
            }
            String roomCode = code.toString();
            if (gameService.getGameByRoomCode(roomCode) == null) {
                return roomCode;
            }
        }
        throw new RuntimeException("Failed to generate unique room code");
    }

    /**
     * Request DTOs
     */
    public static class CreateRoomRequest {
        public Integer targetScore;
        public Integer maxPlayers;
    }
}
