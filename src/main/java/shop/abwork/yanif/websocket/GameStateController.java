package shop.abwork.yanif.websocket;

import jakarta.annotation.PreDestroy;
import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.game.AutoPlayStrategy;
import shop.abwork.yanif.game.YanivGameEngine;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Hand;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.PresenceService;
import shop.abwork.yanif.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.*;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * WebSocket controller for in-game actions.
 * Handles player turns, discards, draws, Yaniv calls, and contest timers.
 */
@Controller
public class GameStateController {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final GameService gameService;
    private final PresenceService presenceService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    // In-memory game engines (authoritative while the server runs; snapshots
    // are persisted to Redis after every mutation so restarts can restore)
    private final Map<String, YanivGameEngine> gameEngines = new ConcurrentHashMap<>();

    /**
     * When each room's engine was last touched by a player. Drives idle eviction:
     * the Redis snapshot is the source of truth, so an engine nobody is using is
     * just a cache entry and can be dropped and rebuilt on the next touch.
     */
    private final Map<String, Long> engineLastTouched = new ConcurrentHashMap<>();

    /**
     * Rooms untouched for this long are dropped from memory. The Redis snapshot outlives
     * them, so eviction costs at most one restore. Not final: field-injected by Spring,
     * while direct construction in tests keeps the default.
     */
    @Value("${game.engine-idle-eviction-minutes:60}")
    private long engineIdleEvictionMinutes = 60;

    // Scheduled executor for Yaniv contest timers and turn timers
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> yanivTimers = new ConcurrentHashMap<>();

    // Turn timers: auto-play for the current player when their timer expires
    private final Map<String, ScheduledFuture<?>> turnTimers = new ConcurrentHashMap<>();
    // roomId -> epoch ms when the current player's timer expires (for client display)
    private final Map<String, Long> turnDeadlines = new ConcurrentHashMap<>();

    // Turn timer configuration (game.turn-timer-seconds / game.auto-play-enabled)
    private final int turnTimerSeconds;
    private final boolean autoPlayEnabled;
    // Yaniv contest window (game.yaniv-contest-timer-seconds)
    private final int yanivContestTimerSeconds;
    // Max hand score for a legal Yaniv call (game.yaniv-threshold)
    private final int yanivThreshold;

    // Action deduplication: track processed action IDs per player per room
    // Format: roomId:playerId:actionId -> timestamp
    private final Map<String, Long> processedActions = new ConcurrentHashMap<>();

    // Track disconnected players who are still in game (for reconnection)
    // roomId -> Set of disconnected userIds
    private final Map<String, Set<String>> disconnectedInGame = new ConcurrentHashMap<>();

    public GameStateController(GameService gameService,
                              PresenceService presenceService,
                              UserService userService,
                              SimpMessagingTemplate messagingTemplate,
                              @Value("${game.turn-timer-seconds:45}") int turnTimerSeconds,
                              @Value("${game.auto-play-enabled:false}") boolean autoPlayEnabled,
                              @Value("${game.yaniv-contest-timer-seconds:15}") int yanivContestTimerSeconds,
                              @Value("${game.yaniv-threshold:7}") int yanivThreshold) {
        this.gameService = gameService;
        this.presenceService = presenceService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
        this.turnTimerSeconds = turnTimerSeconds;
        this.autoPlayEnabled = autoPlayEnabled;
        this.yanivContestTimerSeconds = yanivContestTimerSeconds;
        this.yanivThreshold = yanivThreshold;

        // Reclaim memory from abandoned rooms. Interval is coarse on purpose: this is
        // housekeeping, and every eviction is recoverable from the snapshot.
        scheduler.scheduleAtFixedRate(this::evictIdleEngines, 10, 10, TimeUnit.MINUTES);
    }

    /**
     * Handle WebSocket session disconnect.
     * If player is in an active game, mark them as disconnected but keep game state.
     * Their turn timer keeps running; auto-play takes over when it expires.
     */
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String userId = getUserIdFromSession(event);

        if (userId == null) {
            return;
        }

        // Find which room this user is in (if any)
        String roomId = findRoomForUser(userId);
        if (roomId == null) {
            return;
        }

        // Check if there's an active game engine for this room
        YanivGameEngine engine = gameEngines.get(roomId);
        if (engine == null) {
            // Not in an active game, just update presence
            presenceService.setUserOffline(userId);
            return;
        }

        // Check if game is still in progress (not in lobby, not game over)
        if (engine.isGameOver() || engine.isRoundOver()) {
            // Game is between rounds or over, just update presence
            presenceService.setUserOffline(userId);
            return;
        }

        // Player is in an active game - mark as disconnected but keep in game
        disconnectedInGame.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);

        // Update presence to DISCONNECTED_IN_GAME status (extended TTL)
        presenceService.setUserDisconnectedInGame(userId);

        // If it is already this player's turn, arm their auto-play timer now
        if (engine.getCurrentState() == YanivGameEngine.GameState.WAIT_FOR_TURN
                && userId.equals(engine.getCurrentPlayer())) {
            scheduleTurnTimerIfNeeded(engine, roomId);
        }

        // Broadcast disconnected status to other players
        broadcastPlayerDisconnected(roomId, userId, true);

        System.out.println("Player " + userId + " disconnected from active game in room " + roomId);
    }

    /**
     * Handle WebSocket session connect.
     * If player was previously disconnected in an active game, restore their state.
     * This handles both new connections and STOMP reconnections.
     */
    @EventListener
    public void handleSessionConnect(SessionConnectedEvent event) {
        String userId = getUserIdFromSession(event);

        if (userId == null) {
            return;
        }

        // Find which room this user is in - check both active game engines and database
        String roomId = findRoomForUser(userId);
        if (roomId == null) {
            // Check if user has an active game in database (e.g., game engine was cleaned up)
            roomId = findActiveGameRoomForUser(userId);
            if (roomId == null) {
                return;
            }
        }

        // Check if there's an active game engine for this room (memory or Redis snapshot)
        // Track whether we restored from snapshot (as opposed to finding in memory)
        boolean engineWasInMemory = gameEngines.containsKey(roomId);
        YanivGameEngine engine = getOrRestoreEngine(roomId);
        boolean engineRestoredFromSnapshot = !engineWasInMemory && engine != null;

        if (engine == null && shouldAbortToLobby(roomId)) {
            // No restorable game and storage confirms it: return the room to the lobby
            abortStaleGame(roomId);
            presenceService.setUserOnline(userId);
            broadcastLobbyState(roomId);
            return;
        }

        if (engine == null) {
            // Unresolvable but not provably stale (typically a storage outage). Leave the
            // room alone; every path below dereferences the engine.
            presenceService.setUserOnline(userId);
            System.err.println("Could not resolve engine for room " + roomId
                    + " on reconnect of " + userId + "; leaving room untouched");
            return;
        }

        // Check if this player was previously disconnected in this game
        Set<String> disconnected = disconnectedInGame.get(roomId);
        boolean wasDisconnected = disconnected != null && disconnected.contains(userId);

        if (wasDisconnected) {
            // Player is reconnecting to an active game
            disconnected.remove(userId);

            // Update presence back to IN_GAME
            presenceService.setUserInGame(userId);

            // Broadcast reconnected status to other players
            broadcastPlayerDisconnected(roomId, userId, false);

            // Proactively send game state to reconnecting player
            // This avoids race condition where frontend requests state before subscribing
            GameStateMessage stateMessage = buildGameStateForPlayers(engine, roomId, userId);
            messagingTemplate.convertAndSendToUser(userId, "/queue/game-state", stateMessage);

            System.out.println("Player " + userId + " reconnected to active game in room " + roomId + "; game state sent proactively");
        } else {
            // Normal join or new connection to existing game
            presenceService.setUserInGame(userId);

            // Send current game state to the newly connected player
            GameStateMessage stateMessage = buildGameStateForPlayers(engine, roomId, userId);
            messagingTemplate.convertAndSendToUser(userId, "/queue/game-state", stateMessage);
        }

        // Roster changed: drop any auto-play timer armed for the returning player
        // and re-arm one if a still-disconnected player now holds the turn
        scheduleTurnTimerIfNeeded(engine, roomId);
    }

    /**
     * Handle player discard and draw action.
     * Includes action deduplication to handle network retries.
     */
    @MessageMapping("/room/{roomId}/action")
    public void handleGameAction(@DestinationVariable String roomId,
                                GameActionMessage action,
                                Authentication auth) {
        try {
            String userId = auth.getName();
            System.out.println("=== GAME ACTION REQUEST ===");
            System.out.println("Room ID: " + roomId);
            System.out.println("User ID: " + userId);
            System.out.println("Action: " + action.actionType);

            // Validate action
            if (!userId.equals(action.playerId)) {
                sendErrorToUser(userId, "Cannot perform actions for another player");
                return;
            }

            // Action deduplication: check if this action was already processed
            // Action ID format: roomId:userId:actionTimestamp (sent by client)
            if (action.actionId != null && !action.actionId.isEmpty()) {
                String dedupKey = roomId + ":" + userId + ":" + action.actionId;
                Long existing = processedActions.putIfAbsent(dedupKey, System.currentTimeMillis());
                if (existing != null) {
                    System.out.println("Duplicate action ignored: " + dedupKey);
                    // Re-send current state to ensure client is in sync
                    YanivGameEngine engine = gameEngines.get(roomId);
                    if (engine != null) {
                        GameStateMessage stateMessage = buildGameStateForPlayers(engine, roomId, userId);
                        messagingTemplate.convertAndSendToUser(userId, "/queue/game-state", stateMessage);
                    }
                    return;
                }
                // Clean up old entries (older than 5 minutes)
                long cutoff = System.currentTimeMillis() - 5 * 60 * 1000;
                processedActions.entrySet().removeIf(e -> e.getValue() < cutoff);
            }

            // Get or restore game engine (never silently re-deal a lost game)
            YanivGameEngine engine = getOrRestoreEngine(roomId);
            if (engine == null) {
                if (shouldAbortToLobby(roomId)) {
                    abortStaleGame(roomId);
                }
                sendErrorToUser(userId, "Game not found");
                return;
            }

            synchronized (engine) {
                // Check if it's this player's turn
                if (!userId.equals(engine.getCurrentPlayer()) && !"CALL_YANIV".equals(action.actionType)) {
                    sendErrorToUser(userId, "Not your turn");
                    return;
                }

                // Process action
                switch (action.actionType) {
                    case "DISCARD_AND_DRAW" -> {
                        Hand playerHand = engine.getPlayerHand(userId);
                        if (playerHand == null) {
                            sendErrorToUser(userId, "Player hand not found");
                            return;
                        }

                        List<Card> discardedCards = action.discardedCardIds.stream()
                                .map(id -> playerHand.getCardById(id).orElse(null))
                                .filter(Objects::nonNull)
                                .toList();

                        if (discardedCards.size() != action.discardedCardIds.size()) {
                            sendErrorToUser(userId, "Some discarded cards not found in hand");
                            return;
                        }

                        engine.processDiscard(userId, discardedCards);

                        Card drawnCard;
                        if ("DECK".equalsIgnoreCase(action.drawSource)) {
                            drawnCard = null;
                        } else if ("DISCARD_PILE".equalsIgnoreCase(action.drawSource)) {
                            drawnCard = engine.getDiscardPile().getDrawableCard(action.drawnCardId).orElse(null);
                            if (drawnCard == null) {
                                sendErrorToUser(userId, "Card not drawable from discard pile: " + action.drawnCardId);
                                return;
                            }
                        } else {
                            sendErrorToUser(userId, "Invalid draw source: " + action.drawSource);
                            return;
                        }

                        engine.processDraw(userId, action.drawSource, drawnCard);
                    }
                    case "BONUS_DISCARD" -> {
                        if (!engine.isBonusDiscardActive()) {
                            sendErrorToUser(userId, "No bonus discard available");
                            return;
                        }
                        boolean shouldDiscard = action.bonusDiscard != null && action.bonusDiscard;
                        engine.processBonusDiscard(userId, shouldDiscard);
                    }
                    case "CALL_YANIV" -> {
                        engine.callYaniv(userId);
                    }
                    default -> {
                        sendErrorToUser(userId, "Unknown action type: " + action.actionType);
                        return;
                    }
                }

                // Persist snapshot and broadcast game state to all players
                finishMutation(engine, roomId);
            }
            System.out.println("Game action processed, state broadcasted");

        } catch (Exception e) {
            System.err.println("Error processing game action: " + e.getMessage());
            e.printStackTrace();
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Handle player Yaniv call.
     * Transitions to YANIV_CALLED state and starts 15-second contest timer.
     */
    @MessageMapping("/room/{roomId}/call-yaniv")
    public void callYaniv(@DestinationVariable String roomId,
                         YanivCallMessage message,
                         Authentication auth) {
        try {
            String userId = auth.getName();

            YanivGameEngine engine = gameEngines.get(roomId);
            if (engine == null) {
                engine = getOrRestoreEngine(roomId);
            }
            if (engine == null) {
                if (shouldAbortToLobby(roomId)) {
                    abortStaleGame(roomId);
                }
                sendErrorToUser(userId, "Game not found");
                return;
            }

            synchronized (engine) {
                engine.callYaniv(userId);

                // Persist snapshot, schedule the contest timer, broadcast YANIV_CALLED
                finishMutation(engine, roomId);
            }

        } catch (Exception e) {
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Handle player contesting a Yaniv call (Asaf attempt).
     * Cancels the auto-resolve timer and immediately evaluates hands.
     */
    @MessageMapping("/room/{roomId}/contest-yaniv")
    public void contestYaniv(@DestinationVariable String roomId,
                            ContestYanivMessage message,
                            Authentication auth) {
        try {
            String userId = auth.getName();

            YanivGameEngine engine = gameEngines.get(roomId);
            if (engine == null) {
                engine = getOrRestoreEngine(roomId);
            }
            if (engine == null) {
                if (shouldAbortToLobby(roomId)) {
                    abortStaleGame(roomId);
                }
                sendErrorToUser(userId, "Game not found");
                return;
            }

            synchronized (engine) {
                engine.contestYaniv(userId);

                // Cancel the scheduled auto-resolve timer
                ScheduledFuture<?> future = yanivTimers.remove(roomId);
                if (future != null) {
                    future.cancel(false);
                }

                // Persist snapshot and broadcast resolved state to all players
                finishMutation(engine, roomId);
            }
            System.out.println("Yaniv contested by " + userId + " in room " + roomId);

        } catch (Exception e) {
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Get current game state.
     */
    @MessageMapping("/room/{roomId}/state")
    public void getGameState(@DestinationVariable String roomId,
                            Authentication auth) {
        try {
            String userId = auth.getName();

            YanivGameEngine engine = gameEngines.get(roomId);
            if (engine == null) {
                engine = getOrRestoreEngine(roomId);
            }
            if (engine == null) {
                Game game = gameService.getGameById(roomId);
                if (game == null) {
                    sendErrorToUser(userId, "Game not found");
                    return;
                }
                // Live state lost (e.g. pre-snapshot restart): back to lobby
                if (game.getStatus() == Game.GameStatus.IN_PROGRESS && shouldAbortToLobby(roomId)) {
                    abortStaleGame(roomId);
                }

                GameStateMessage lobbyState = new GameStateMessage();
                lobbyState.gameId = roomId;
                lobbyState.roomCode = game.getRoomCode();
                lobbyState.maxPlayers = game.getMaxPlayers();
                lobbyState.currentState = game.getStatus().toString();
                lobbyState.roundNumber = 0;
                lobbyState.scores = new HashMap<>();
                lobbyState.eliminatedPlayers = new ArrayList<>();
                lobbyState.deckCount = 0;
                lobbyState.hand = new ArrayList<>();

                var players = gameService.getGamePlayers(roomId);
                Map<String, String> playerNames = new HashMap<>();
                List<PlayerInfo> playerInfos = new ArrayList<>();
                for (var player : players) {
                    String playerUserId = player.getId().getUserId();
                    userService.getUserById(playerUserId).ifPresent(u -> {
                        playerNames.put(playerUserId, u.getDisplayName());
                        PlayerInfo info = new PlayerInfo();
                        info.userId = playerUserId;
                        info.displayName = u.getDisplayName();
                        info.isHost = game.getHostUserId().equals(playerUserId);
                        info.status = "ONLINE";
                        playerInfos.add(info);
                    });
                }
                lobbyState.playerNames = playerNames;
                lobbyState.players = playerInfos;
                lobbyState.drawableDiscardCards = new ArrayList<>();
                lobbyState.topDiscardCards = new ArrayList<>();

                messagingTemplate.convertAndSendToUser(userId, "/queue/game-state", lobbyState);
                return;
            }

            GameStateMessage stateMessage = buildGameStateForPlayers(engine, roomId, userId);
            messagingTemplate.convertAndSendToUser(
                    userId,
                    "/queue/game-state",
                    stateMessage
            );
        } catch (Exception e) {
            System.err.println("Error getting game state: " + e.getMessage());
            e.printStackTrace();
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Handle player joining a room (for lobby updates).
     */
    @MessageMapping("/room/{roomId}/join")
    public void handleJoin(@DestinationVariable String roomId,
                          Authentication auth) {
        try {
            String userId = auth.getName();

            Game game = gameService.getGameById(roomId);
            if (game == null) {
                sendErrorToUser(userId, "Game not found");
                return;
            }

            // An active game must never receive lobby-shaped state (it would
            // blank every seated client) - answer the requester personally.
            YanivGameEngine engine = gameEngines.get(roomId);
            if (engine == null) {
                engine = getOrRestoreEngine(roomId);
            }
            if (engine != null && !engine.isGameOver()) {
                messagingTemplate.convertAndSendToUser(
                        userId,
                        "/queue/game-state",
                        buildGameStateForPlayers(engine, roomId, userId)
                );
                return;
            }

            // No active game engine (or game is over) - broadcast lobby state to all players
            broadcastLobbyState(roomId);

        } catch (Exception e) {
            System.err.println("Error handling player join: " + e.getMessage());
            e.printStackTrace();
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Build lobby state message for a game.
     */
    private GameStateMessage buildLobbyStateMessage(Game game) {
        var players = gameService.getGamePlayers(game.getId());
        Map<String, String> playerNames = new HashMap<>();
        List<PlayerInfo> playerInfos = new ArrayList<>();
        for (var player : players) {
            String playerUserId = player.getId().getUserId();
            userService.getUserById(playerUserId).ifPresent(u -> {
                playerNames.put(playerUserId, u.getDisplayName());
                PlayerInfo info = new PlayerInfo();
                info.userId = playerUserId;
                info.displayName = u.getDisplayName();
                info.isHost = game.getHostUserId().equals(playerUserId);
                info.status = "ONLINE";
                playerInfos.add(info);
            });
        }

        GameStateMessage lobbyState = new GameStateMessage();
        lobbyState.gameId = game.getId();
        lobbyState.roomCode = game.getRoomCode();
        lobbyState.maxPlayers = game.getMaxPlayers();
        lobbyState.currentState = game.getStatus().toString();
        lobbyState.roundNumber = 0;
        lobbyState.scores = new HashMap<>();
        lobbyState.eliminatedPlayers = new ArrayList<>();
        lobbyState.deckCount = 0;
        lobbyState.hand = new ArrayList<>();
        lobbyState.playerNames = playerNames;
        lobbyState.players = playerInfos;
        lobbyState.drawableDiscardCards = new ArrayList<>();
        lobbyState.topDiscardCards = new ArrayList<>();
        return lobbyState;
    }

    /**
     * Broadcast lobby state to all players in a room.
     */
    private void broadcastLobbyState(String roomId) {
        Game game = gameService.getGameById(roomId);
        if (game == null) return;

        GameStateMessage lobbyState = buildLobbyStateMessage(game);

        var players = gameService.getGamePlayers(roomId);
        for (var player : players) {
            String playerUserId = player.getId().getUserId();
            messagingTemplate.convertAndSendToUser(
                    playerUserId,
                    "/queue/game-state",
                    lobbyState
            );
        }
    }

    /**
     * Start game (transition from LOBBY to IN_PROGRESS).
     */
    @MessageMapping("/room/{roomId}/start")
    public void startGame(@DestinationVariable String roomId,
                         Authentication auth) {
        try {
            String userId = auth.getName();

            var game = gameService.getGameById(roomId);

            if (game == null) {
                sendErrorToUser(userId, "Game not found");
                return;
            }

            if (!game.getHostUserId().equals(userId)) {
                sendErrorToUser(userId, "Only host can start game");
                return;
            }

            if (game.getStatus() == Game.GameStatus.IN_PROGRESS) {
                // A game is already running (possibly restored after a restart)
                sendErrorToUser(userId, "Game already in progress");
                return;
            }

            if (game.getStatus() == Game.GameStatus.FINISHED) {
                // Re-running would deal a fresh game onto a finished row, corrupting history
                sendErrorToUser(userId, "Game has already finished");
                return;
            }

            var players = gameService.getGamePlayers(roomId);
            if (players.size() < 2) {
                sendErrorToUser(userId, "Need at least 2 players to start");
                return;
            }

            gameService.updateGameStatus(roomId, Game.GameStatus.IN_PROGRESS);

            List<String> playerIds = players.stream()
                    .map(gp -> gp.getId().getUserId())
                    .toList();
            YanivGameEngine engine = new YanivGameEngine(roomId, (List<String>) playerIds,
                    yanivThreshold,
                    game.getTargetScore() != null ? game.getTargetScore() : 100);
            engine.setYanivContestTimerSeconds(yanivContestTimerSeconds);
            gameEngines.put(roomId, engine);
            engineLastTouched.put(roomId, System.currentTimeMillis());

            for (String playerId : playerIds) {
                presenceService.setUserInGame(playerId);
            }

            // Persist initial snapshot, schedule first turn timer, broadcast
            finishMutation(engine, roomId);

        } catch (Exception e) {
            System.err.println("Error starting game: " + e.getMessage());
            e.printStackTrace();
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Build masked game state for a specific player.
     * During YANIV_CALLED: include caller info and timer data.
     * During ROUND_OVER: include all player hands revealed.
     */
    /**
     * The per-room data every recipient's message shares: the game row, its players and
     * their display names. Loaded once per broadcast rather than once per recipient,
     * which is what keeps a broadcast at a constant query count instead of O(N^2).
     */
    private record RoomView(Game game,
                            List<GamePlayer> players,
                            Map<String, String> playerNames,
                            List<PlayerInfo> playerInfos) {
    }

    /** Three queries, however many players are at the table. */
    private RoomView loadRoomView(String roomId) {
        Game game = gameService.getGameById(roomId);
        List<GamePlayer> players = gameService.getGamePlayers(roomId);

        List<String> userIds = players.stream().map(gp -> gp.getId().getUserId()).toList();
        Map<String, User> users = userService.getUsersByIds(userIds);

        Map<String, String> playerNames = new HashMap<>();
        List<PlayerInfo> playerInfos = new ArrayList<>();
        for (String playerUserId : userIds) {
            User user = users.get(playerUserId);
            if (user == null) {
                continue;
            }
            playerNames.put(playerUserId, user.getDisplayName());
            PlayerInfo info = new PlayerInfo();
            info.userId = playerUserId;
            info.displayName = user.getDisplayName();
            info.isHost = game != null && game.getHostUserId().equals(playerUserId);
            info.status = "ONLINE";
            playerInfos.add(info);
        }
        return new RoomView(game, players, playerNames, playerInfos);
    }

    private GameStateMessage buildGameStateForPlayers(YanivGameEngine engine, String roomId, String userId) {
        return buildGameStateForPlayers(engine, roomId, userId, null);
    }

    private GameStateMessage buildGameStateForPlayers(YanivGameEngine engine, String roomId, String userId,
                                                      String autoPlayedPlayerId) {
        return buildGameStateForPlayers(engine, roomId, userId, autoPlayedPlayerId, loadRoomView(roomId));
    }

    private GameStateMessage buildGameStateForPlayers(YanivGameEngine engine, String roomId, String userId,
                                                      String autoPlayedPlayerId, RoomView view) {
        GameStateMessage message = new GameStateMessage();
        message.gameId = roomId;
        var game = view.game();
        message.roomCode = game != null ? game.getRoomCode() : "";
        message.maxPlayers = game != null ? game.getMaxPlayers() : 6;
        message.targetScore = game != null ? game.getTargetScore() : 100;
        message.roundNumber = engine.getRoundNumber();
        message.currentState = engine.getCurrentState().toString();
        message.currentTurnPlayerId = engine.getCurrentPlayer();
        message.scores = engine.getPlayerScores();
        message.eliminatedPlayers = new ArrayList<>(engine.getEliminatedPlayers());
        message.deckCount = engine.getDeckCount();
        message.topDiscardCard = engine.getDiscardPile().getTopCard()
                .map(c -> {
                    Map<String, Object> cardMap = new HashMap<>();
                    cardMap.put("id", c.getId());
                    cardMap.put("rank", c.getRank().toString());
                    cardMap.put("suit", c.getSuit().toString());
                    return cardMap;
                })
                .orElse(null);

        // Names and roster come prepared: built once per broadcast, shared by everyone
        var players = view.players();
        Map<String, String> playerNames = view.playerNames();
        message.playerNames = playerNames;
        message.players = view.playerInfos();

        // Add current player's hand
        Hand hand = engine.getPlayerHand(userId);
        if (hand != null) {
            message.hand = hand.getCards().stream()
                    .map(c -> {
                        Map<String, Object> cardMap = new HashMap<>();
                        cardMap.put("id", c.getId());
                        cardMap.put("rank", c.getRank().toString());
                        cardMap.put("suit", c.getSuit().toString());
                        return cardMap;
                    })
                    .toList();
        } else {
            message.hand = new ArrayList<>();
        }

        // Add drawable discard cards
        var drawableCards = engine.getDiscardPile().getDrawableCards();
        message.drawableDiscardCards = drawableCards.stream()
                .map(c -> {
                    Map<String, Object> cardMap = new HashMap<>();
                    cardMap.put("id", c.getId());
                    cardMap.put("rank", c.getRank().toString());
                    cardMap.put("suit", c.getSuit().toString());
                    return cardMap;
                })
                .toList();

        // Add all cards of the top combination (for horizontal fan display)
        var topCombination = engine.getDiscardPile().getTopCombination();
        if (topCombination.isPresent()) {
            message.topDiscardCards = topCombination.get().getCards().stream()
                    .map(c -> {
                        Map<String, Object> cardMap = new HashMap<>();
                        cardMap.put("id", c.getId());
                        cardMap.put("rank", c.getRank().toString());
                        cardMap.put("suit", c.getSuit().toString());
                        return cardMap;
                    })
                    .toList();
        } else {
            message.topDiscardCards = new ArrayList<>();
        }

        // Add opponent card counts
        Map<String, Integer> opponentCounts = new HashMap<>();
        for (var player : players) {
            String pid = player.getId().getUserId();
            if (!pid.equals(userId)) {
                Hand opponentHand = engine.getPlayerHand(pid);
                opponentCounts.put(pid, opponentHand != null ? opponentHand.size() : 0);
            }
        }
        message.opponentCounts = opponentCounts;

        // Yaniv call contest timer data
        if (engine.isYanivCalled() || engine.isRoundOver() || engine.isGameOver()) {
            message.yanivCallerId = engine.getCallerId();
            message.yanivCallerName = playerNames.getOrDefault(engine.getCallerId(), "");
            message.isAsaf = engine.isAsaf();
            message.asafByUserId = engine.getAsafByUserId();
            message.roundWinner = engine.getCallerId(); // Legacy single winner (caller)
            message.roundWinners = engine.getRoundWinners(); // All players with 0 score this round
        }

        if (engine.isYanivCalled()) {
            message.yanivCalledAt = engine.getYanivCalledTimestamp();
            message.yanivContestTimerSeconds = engine.getYanivContestTimerSeconds();
        }

        // Reveal all hands on ROUND_OVER or GAME_OVER
        if (engine.isRoundOver() || engine.isGameOver()) {
            Map<String, List<Map<String, Object>>> allHands = new HashMap<>();
            Map<String, List<Card>> rawHands = engine.getAllPlayerHands();
            for (Map.Entry<String, List<Card>> entry : rawHands.entrySet()) {
                allHands.put(entry.getKey(), entry.getValue().stream()
                        .map(c -> {
                            Map<String, Object> cardMap = new HashMap<>();
                            cardMap.put("id", c.getId());
                            cardMap.put("rank", c.getRank().toString());
                            cardMap.put("suit", c.getSuit().toString());
                            return cardMap;
                        })
                        .toList());
            }
            message.allPlayerHands = allHands;
            message.roundScores = engine.getRoundScores();
        }

        // Turn countdown for the active player
        if (engine.getCurrentState() == YanivGameEngine.GameState.WAIT_FOR_TURN) {
            Long deadline = turnDeadlines.get(roomId);
            if (deadline != null) {
                message.turnTimerSeconds = turnTimerSeconds;
                message.turnEndsAt = deadline;
            }
        }

        if (autoPlayedPlayerId != null) {
            message.autoPlayedPlayerId = autoPlayedPlayerId;
        }

        // Bonus discard state
        if (engine.isBonusDiscardActive()) {
            message.bonusDiscardActive = true;
            Card bonusCard = engine.getPendingBonusCard();
            if (bonusCard != null) {
                Map<String, Object> cardMap = new HashMap<>();
                cardMap.put("id", bonusCard.getId());
                cardMap.put("rank", bonusCard.getRank().toString());
                cardMap.put("suit", bonusCard.getSuit().toString());
                message.pendingBonusCard = cardMap;
            }
        }

        return message;
    }

    /**
     * Handle player acknowledging round over and ready for next round.
     */
    @MessageMapping("/room/{roomId}/next-round")
    public void handleNextRound(@DestinationVariable String roomId,
                               Authentication auth) {
        try {
            String userId = auth.getName();

            // Restore from snapshot like every other handler, or a restart during
            // ROUND_OVER bricks the Next Round button.
            YanivGameEngine engine = getOrRestoreEngine(roomId);
            if (engine == null) {
                sendErrorToUser(userId, "Game not found");
                return;
            }

            if (!engine.getAllPlayerIds().contains(userId)) {
                sendErrorToUser(userId, "You are not a player in this game");
                return;
            }

            if (!engine.isRoundOver()) {
                sendErrorToUser(userId, "Round is not over yet");
                return;
            }

            synchronized (engine) {
                engine.startNextRound();
                finishMutation(engine, roomId);
            }

        } catch (Exception e) {
            System.err.println("Error starting next round: " + e.getMessage());
            e.printStackTrace();
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Broadcast game state to all players in a room.
     * Each player receives their own hand (masked for others).
     */
    private void broadcastGameState(YanivGameEngine engine, String roomId) {
        broadcastGameState(engine, roomId, null);
    }

    private void broadcastGameState(YanivGameEngine engine, String roomId, String autoPlayedPlayerId) {
        RoomView view = loadRoomView(roomId);
        for (var player : view.players()) {
            String playerId = player.getId().getUserId();
            GameStateMessage stateMessage = buildGameStateForPlayers(engine, roomId, playerId,
                    autoPlayedPlayerId, view);
            messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/game-state",
                    stateMessage
            );
        }
    }

    /**
     * Send error message to a specific user.
     */
    private void sendErrorToUser(String userId, String error) {
        GameStateMessage message = new GameStateMessage();
        message.error = error;
        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/game-state",
                message
        );
    }

    /**
     * Extract user ID from STOMP session.
     */
    private String getUserIdFromSession(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        // The user is set in the WebSocketConfig ChannelInterceptor
        if (headerAccessor.getUser() != null) {
            return headerAccessor.getUser().getName();
        }
        return null;
    }

    /**
     * Extract user ID from STOMP session (for connect event).
     */
    private String getUserIdFromSession(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        if (headerAccessor.getUser() != null) {
            return headerAccessor.getUser().getName();
        }
        return null;
    }

    /**
     * Find the room a user is currently in by checking game engines.
     */
    private String findRoomForUser(String userId) {
        for (Map.Entry<String, YanivGameEngine> entry : gameEngines.entrySet()) {
            if (entry.getValue().getAllPlayerIds().contains(userId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Find active game room for user by checking database.
     * Used when game engine is not in memory but game is still active in database.
     */
    private String findActiveGameRoomForUser(String userId) {
        try {
            // Get all active games from database and check if user is a player
            List<Game> activeGames = gameService.getActiveGames();
            for (Game game : activeGames) {
                var players = gameService.getGamePlayers(game.getId());
                if (players.stream().anyMatch(gp -> gp.getId().getUserId().equals(userId))) {
                    return game.getId();
                }
            }
        } catch (Exception e) {
            System.err.println("Error finding active game for user " + userId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Get the engine for a room from memory, or restore it from the Redis snapshot
     * written after every mutation. Returns null when no restorable state exists.
     *
     * A snapshot is only honored when the DB still marks the game IN_PROGRESS -
     * snapshots of finished/aborted games are stale and must not resurrect old
     * games into a lobby. Transient storage errors return null WITHOUT letting
     * callers abort the room (only a confirmed miss may do that).
     */
    /**
     * Drop engines for rooms nobody has touched recently.
     *
     * Safe because the engine map is only a cache: full state is snapshotted to Redis
     * after every mutation, so the next player to touch an evicted room gets it rebuilt
     * by {@link #getOrRestoreEngine}. Without this a game everyone simply abandons
     * mid-round — never finished, never aborted — occupies memory for the life of the
     * process. Rooms with work still scheduled against them are left alone.
     *
     * Package-private so tests can drive it without waiting on the scheduler.
     */
    void evictIdleEngines() {
        long cutoff = System.currentTimeMillis() - engineIdleEvictionMinutes * 60_000L;

        for (String roomId : new ArrayList<>(gameEngines.keySet())) {
            Long lastTouched = engineLastTouched.get(roomId);
            if (lastTouched != null && lastTouched > cutoff) {
                continue; // still in use
            }
            ScheduledFuture<?> turnTimer = turnTimers.get(roomId);
            ScheduledFuture<?> yanivTimer = yanivTimers.get(roomId);
            boolean workPending = (turnTimer != null && !turnTimer.isDone())
                    || (yanivTimer != null && !yanivTimer.isDone());
            if (workPending) {
                continue; // a timer still expects this engine instance
            }

            YanivGameEngine engine = gameEngines.get(roomId);
            if (engine == null) {
                continue;
            }
            synchronized (engine) {
                // Re-check under the lock: a player may have arrived since the scan.
                Long recheck = engineLastTouched.get(roomId);
                if (recheck != null && recheck > cutoff) {
                    continue;
                }
                if (gameEngines.remove(roomId, engine)) {
                    engineLastTouched.remove(roomId);
                    disconnectedInGame.remove(roomId);
                    System.out.println("Evicted idle engine for room " + roomId
                            + "; it will be restored from its snapshot on next use");
                }
            }
        }
    }

    /** Stop the scheduler so its threads do not outlive the application. */
    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }

    private YanivGameEngine getOrRestoreEngine(String roomId) {
        YanivGameEngine engine = gameEngines.get(roomId);
        if (engine != null) {
            engineLastTouched.put(roomId, System.currentTimeMillis());
            return engine;
        }

        String snapshotJson = null;
        try {
            snapshotJson = gameService.getGameState(roomId);
        } catch (Exception e) {
            System.err.println("Could not read game snapshot for room " + roomId + ": " + e.getMessage());
        }

        // Only trust the DB as the tombstone when it is readable
        Game dbGame = null;
        try {
            dbGame = gameService.getGameById(roomId);
        } catch (Exception e) {
            System.err.println("Could not read game record for room " + roomId + ": " + e.getMessage());
        }
        boolean dbSaysInProgress = dbGame != null && dbGame.getStatus() == Game.GameStatus.IN_PROGRESS;

        YanivGameEngine restored = YanivGameEngine.fromSnapshot(snapshotJson);
        if (restored != null && !dbSaysInProgress) {
            // Stale snapshot (game finished/aborted earlier): discard it
            System.out.println("Discarding stale snapshot for room " + roomId
                    + " (status=" + (dbGame != null ? dbGame.getStatus() : "unknown") + ")");
            try {
                gameService.deleteGameState(roomId);
            } catch (Exception ignored) {
            }
            restored = null;
        }

        if (restored != null) {
            // Publish atomically: two requests can miss the map and restore concurrently.
            // Handlers synchronize on the engine instance, so if they each kept their own
            // copy their mutations would not be serialised against each other.
            YanivGameEngine published = gameEngines.putIfAbsent(roomId, restored);
            engineLastTouched.put(roomId, System.currentTimeMillis());
            if (published != null) {
                return published; // another thread won the race; everyone shares its instance
            }
            scheduleTurnTimerIfNeeded(restored, roomId);
            System.out.println("Restored game engine for room " + roomId + " from snapshot");
            return restored;
        }

        // Confirmed miss or unreadable storage - never a reason to abort here
        return null;
    }

    /**
     * True when it is safe to declare the room's live state permanently lost
     * (storage reachable, both snapshot and DB checked). Redis/DB outages must
     * never dump a live table back to the lobby.
     */
    private boolean shouldAbortToLobby(String roomId) {
        try {
            String snapshotJson = gameService.getGameState(roomId);
            Game dbGame = gameService.getGameById(roomId);
            // A corrupt/unparseable snapshot counts as absent - it can never be restored
            boolean noRestorableSnapshot = snapshotJson == null
                    || YanivGameEngine.fromSnapshot(snapshotJson) == null;
            return noRestorableSnapshot && dbGame != null
                    && dbGame.getStatus() == Game.GameStatus.IN_PROGRESS;
        } catch (Exception e) {
            System.err.println("Skipping lobby-abort check for room " + roomId
                    + " (storage unreachable): " + e.getMessage());
            return false;
        }
    }

    /**
     * Return a room whose live state was lost to LOBBY so players can start fresh,
     * instead of silently re-dealing an in-progress game.
     */
    private void abortStaleGame(String roomId) {
        try {
            Game game = gameService.getGameById(roomId);
            if (game != null && game.getStatus() == Game.GameStatus.IN_PROGRESS) {
                gameService.updateGameStatus(roomId, Game.GameStatus.LOBBY);
                // Remove any stale snapshot - it must not resurrect this game later
                try {
                    gameService.deleteGameState(roomId);
                } catch (Exception e) {
                    System.err.println("Could not delete stale snapshot for room " + roomId + ": " + e.getMessage());
                }
                gameEngines.remove(roomId);
                disconnectedInGame.remove(roomId);
                engineLastTouched.remove(roomId);
                System.err.println("Game state lost for room " + roomId + "; returned to lobby");
            }
        } catch (Exception e) {
            System.err.println("Error aborting stale game " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Complete a successful engine mutation: persist the snapshot, handle round/game
     * completion bookkeeping, schedule the next turn timer, and broadcast.
     *
     * @param autoPlayedPlayerId non-null when this mutation was performed by auto-play
     */
    private void finishMutation(YanivGameEngine engine, String roomId) {
        finishMutation(engine, roomId, null);
    }

    private void finishMutation(YanivGameEngine engine, String roomId, String autoPlayedPlayerId) {
        // Persist snapshot (best-effort: a Redis outage must not fail the action)
        try {
            gameService.saveGameState(roomId, engine.toSnapshot());
        } catch (Exception e) {
            System.err.println("Failed to persist game snapshot for room " + roomId + ": " + e.getMessage());
        }

        // The round that ends the game transitions straight to GAME_OVER, never
        // ROUND_OVER, so it must be persisted here too or round_histories is
        // permanently missing the deciding round of every game.
        if (engine.isRoundOver() || engine.isGameOver()) {
            persistRoundHistory(engine, roomId);
        }

        if (engine.isGameOver()) {
            cancelTurnTimer(roomId);
            yanivTimers.computeIfPresent(roomId, (k, f) -> {
                f.cancel(false);
                return null;
            });
            boolean resultPersisted = false;
            try {
                gameService.completeGame(roomId, engine.getWinnerId(),
                        engine.getFinishingOrder(), engine.getPlayerScores());
                resultPersisted = true;
            } catch (Exception e) {
                System.err.println("Failed to record final result for room " + roomId + ": " + e.getMessage());
            }

            if (resultPersisted) {
                try {
                    gameService.deleteGameState(roomId);
                } catch (Exception e) {
                    System.err.println("Failed to delete game snapshot for room " + roomId + ": " + e.getMessage());
                }
            } else {
                // Keep the snapshot: the row is still IN_PROGRESS, so the next touch
                // restores this finished game and retries persisting the result.
                System.err.println("Keeping snapshot for room " + roomId + " so the result can be recovered");
            }
            gameEngines.remove(roomId);
            disconnectedInGame.remove(roomId);
            engineLastTouched.remove(roomId);
        } else if (engine.isYanivCalled()) {
            // Contest window: someone must be able to resolve it even if the
            // caller was auto-played and every human is idle
            scheduleYanivContestTimer(engine, roomId);
        } else if (engine.isRoundOver()) {
            // Show results briefly, then advance automatically so an all-AFK
            // game can keep flowing; a human "next round" click replaces this timer
            scheduleRoundOverAdvance(engine, roomId);
        } else {
            scheduleTurnTimerIfNeeded(engine, roomId);
        }

        broadcastGameState(engine, roomId, autoPlayedPlayerId);
    }

    /**
     * Schedule auto-resolution of the current Yaniv call when the contest window closes.
     */
    private void scheduleYanivContestTimer(YanivGameEngine engine, String roomId) {
        ScheduledFuture<?> old = yanivTimers.remove(roomId);
        if (old != null) {
            old.cancel(false);
        }
        int timerSeconds = engine.getYanivContestTimerSeconds();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                synchronized (engine) {
                    // Only act if this engine is still the room's live one
                    if (gameEngines.get(roomId) != engine || !engine.isYanivCalled()) {
                        return;
                    }
                    engine.resolveYanivCall();
                    finishMutation(engine, roomId);
                    System.out.println("Yaniv contest timer expired, round resolved for room: " + roomId);
                }
            } catch (Exception e) {
                System.err.println("Error auto-resolving Yaniv: " + e.getMessage());
            }
        }, timerSeconds, TimeUnit.SECONDS);
        yanivTimers.put(roomId, future);
    }

    /**
     * Schedule automatic advancement past the ROUND_OVER results screen - but only
     * when every active player is disconnected. Connected players advance manually.
     */
    private void scheduleRoundOverAdvance(YanivGameEngine engine, String roomId) {
        if (!autoPlayEnabled) {
            return;
        }
        Set<String> disconnected = disconnectedInGame.getOrDefault(roomId, java.util.Set.of());
        boolean allActivePlayersGone = engine.getAllPlayerIds().stream()
                .filter(p -> !engine.getEliminatedPlayers().contains(p))
                .allMatch(disconnected::contains);
        if (!allActivePlayersGone) {
            ScheduledFuture<?> old = turnTimers.remove(roomId);
            if (old != null) {
                old.cancel(false);
            }
            return;
        }
        ScheduledFuture<?> old = turnTimers.remove(roomId);
        if (old != null) {
            old.cancel(false);
        }
        final int expectedRoundNumber = engine.getRoundNumber();
        long delayMs = turnTimerSeconds * 1000L;
        ScheduledFuture<?> future = scheduler.schedule(() -> runAutoNextRound(roomId, expectedRoundNumber),
                delayMs, TimeUnit.MILLISECONDS);
        turnTimers.put(roomId, future);
    }

    /**
     * Auto-advance from ROUND_OVER to the next round.
     */
    private void runAutoNextRound(String roomId, int expectedRoundNumber) {
        YanivGameEngine engine = gameEngines.get(roomId);
        if (engine == null) {
            return;
        }
        try {
            synchronized (engine) {
                if (gameEngines.get(roomId) != engine
                        || !engine.isRoundOver()
                        || engine.getRoundNumber() != expectedRoundNumber) {
                    return; // players advanced already
                }
                engine.startNextRound();
                finishMutation(engine, roomId);
            }
            System.out.println("Auto-started round " + engine.getRoundNumber() + " in room " + roomId);
        } catch (Exception e) {
            System.err.println("Error auto-starting next round in room " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Persist one RoundHistory row when a round completes.
     */
    private void persistRoundHistory(YanivGameEngine engine, String roomId) {
        if (engine.getCallerId() == null) {
            return;
        }
        try {
            gameService.saveRoundHistory(
                    roomId,
                    engine.getRoundNumber(),
                    engine.getCallerId(),
                    engine.isAsaf(),
                    engine.getAsafByUserId(),
                    JSON_MAPPER.writeValueAsString(engine.getRoundScores()));
        } catch (Exception e) {
            System.err.println("Failed to save round history for room " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Schedule auto-play for the current player's turn - only when that player is
     * disconnected. Cancels any pending timer first.
     */
    private void scheduleTurnTimerIfNeeded(YanivGameEngine engine, String roomId) {
        ScheduledFuture<?> old = turnTimers.remove(roomId);
        if (old != null) {
            old.cancel(false);
        }
        turnDeadlines.remove(roomId);

        // BONUS_DISCARD is included: a player who drops while the engine waits for
        // their bonus decision would otherwise stall the room permanently.
        YanivGameEngine.GameState state = engine.getCurrentState();
        boolean awaitingPlayer = state == YanivGameEngine.GameState.WAIT_FOR_TURN
                || state == YanivGameEngine.GameState.BONUS_DISCARD;
        if (!autoPlayEnabled || engine.isGameOver() || engine.isRoundOver() || !awaitingPlayer) {
            return;
        }

        Set<String> disconnected = disconnectedInGame.get(roomId);
        boolean currentPlayerDisconnected = disconnected != null && disconnected.contains(engine.getCurrentPlayer());
        if (!currentPlayerDisconnected) {
            return; // connected players keep unlimited thinking time
        }

        // Disconnected player: auto-play immediately (no wait)
        long delayMs = 800L; // Small delay to allow reconnect handling to complete
        long deadline = System.currentTimeMillis() + delayMs;
        turnDeadlines.put(roomId, deadline);

        final String expectedPlayer = engine.getCurrentPlayer();
        ScheduledFuture<?> future = scheduler.schedule(() -> runAutoPlay(roomId, expectedPlayer),
                delayMs, TimeUnit.MILLISECONDS);
        turnTimers.put(roomId, future);
    }

    private void cancelTurnTimer(String roomId) {
        ScheduledFuture<?> old = turnTimers.remove(roomId);
        if (old != null) {
            old.cancel(false);
        }
        turnDeadlines.remove(roomId);
    }

    /**
     * Auto-play the current turn on behalf of a player whose timer expired.
     * Re-validates under the engine lock so a human action racing the timer wins safely.
     */
    private void runAutoPlay(String roomId, String expectedPlayer) {
        YanivGameEngine engine = gameEngines.get(roomId);
        if (engine == null) {
            return;
        }
        try {
            synchronized (engine) {
                Set<String> disconnected = disconnectedInGame.get(roomId);
                if (disconnected == null || !disconnected.contains(expectedPlayer)) {
                    return; // player came back before the timer fired
                }
                YanivGameEngine.GameState state = engine.getCurrentState();
                if (gameEngines.get(roomId) != engine
                        || !expectedPlayer.equals(engine.getCurrentPlayer())
                        || (state != YanivGameEngine.GameState.WAIT_FOR_TURN
                            && state != YanivGameEngine.GameState.BONUS_DISCARD)) {
                    return; // human acted first, or game moved on
                }

                if (state == YanivGameEngine.GameState.BONUS_DISCARD) {
                    // They dropped mid-decision: decline and let the turn finish.
                    engine.processBonusDiscard(expectedPlayer, false);
                    finishMutation(engine, roomId, expectedPlayer);
                    return;
                }

                AutoPlayStrategy.Decision decision = AutoPlayStrategy.decide(
                        engine.getPlayerHand(expectedPlayer), engine.getDiscardPile(), engine.getYanivThreshold());

                if (decision.type() == AutoPlayStrategy.ActionType.CALL_YANIV) {
                    engine.callYaniv(expectedPlayer);
                } else {
                    engine.processDiscard(expectedPlayer, decision.discardCards());
                    Card drawnCard = null;
                    if ("DISCARD_PILE".equals(decision.drawSource())) {
                        drawnCard = engine.getDiscardPile().getDrawableCard(decision.drawnCardId()).orElse(null);
                        if (drawnCard == null) {
                            throw new IllegalStateException("Auto-play picked undrawable card: " + decision.drawnCardId());
                        }
                    }
                    engine.processDraw(expectedPlayer, decision.drawSource(), drawnCard);

                    // Handle bonus discard if triggered - auto-play chooses to keep the card (not discard)
                    if (engine.getCurrentState() == YanivGameEngine.GameState.BONUS_DISCARD) {
                        engine.processBonusDiscard(expectedPlayer, false);
                    }
                }

                System.out.println("Auto-played turn for player " + expectedPlayer + " in room " + roomId);
                finishMutation(engine, roomId, expectedPlayer);
            }
        } catch (Exception e) {
            System.err.println("Error auto-playing turn in room " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Broadcast player disconnected/reconnected status to other players in the room.
     */
    private void broadcastPlayerDisconnected(String roomId, String userId, boolean disconnected) {
        var players = gameService.getGamePlayers(roomId);
        for (var player : players) {
            String playerId = player.getId().getUserId();
            if (!playerId.equals(userId)) {
                GameStateMessage message = new GameStateMessage();
                message.gameId = roomId;
                message.playerDisconnected = userId;
                message.playerDisconnectedStatus = disconnected;
                messagingTemplate.convertAndSendToUser(
                    playerId,
                    "/queue/game-state",
                    message
                );
            }
        }
    }

    /**
     * Request DTOs
     */
    public static class GameActionMessage {
        public String actionType;       // DISCARD_AND_DRAW, CALL_YANIV, BONUS_DISCARD
        public String playerId;
        public List<String> discardedCardIds;
        public String drawSource;       // DECK or DISCARD_PILE
        public String drawnCardId;
        public String actionId;         // For deduplication (client-generated unique ID)
        public Boolean bonusDiscard;    // For BONUS_DISCARD action: true to discard, false to keep
    }

    public static class YanivCallMessage {
        public String playerId;
    }

    public static class ContestYanivMessage {
        public String playerId;
    }

    /**
     * Player info for lobby display
     */
    public static class PlayerInfo {
        public String userId;
        public String displayName;
        public boolean isHost;
        public String status;
    }

    /**
     * Response DTO for game state
     */
    public static class GameStateMessage {
        public String gameId;
        public String roomCode;
        public int roundNumber;
        public String currentState;
        public String currentTurnPlayerId;
        public Map<String, Integer> scores;
        public Map<String, String> playerNames;
        public List<PlayerInfo> players;
        public List<String> eliminatedPlayers;
        public int deckCount;
        public Map<String, Object> topDiscardCard;
        public List<Map<String, Object>> topDiscardCards;
        public List<Map<String, Object>> hand;
        public List<Map<String, Object>> drawableDiscardCards;
        public Map<String, Integer> opponentCounts;
        public Map<String, Integer> roundScores;
        public String roundWinner;
        public List<String> roundWinners;
        public boolean isAsaf;
        public String asafByUserId;
        public String error;

        // Yaniv contest timer fields
        public String yanivCallerId;
        public String yanivCallerName;
        public long yanivCalledAt;          // Server epoch ms
        public int yanivContestTimerSeconds; // Total allowed seconds (15)

        // Max players in room
        public Integer maxPlayers;
        public Integer targetScore;

        // All player hands revealed on ROUND_OVER
        public Map<String, List<Map<String, Object>>> allPlayerHands;

        // Player reconnection status
        public String playerDisconnected;
        public boolean playerDisconnectedStatus;

        // Turn timer / auto-play
        public int turnTimerSeconds;          // Total allowed seconds per turn
        public long turnEndsAt;               // Server epoch ms when the current turn expires
        public String autoPlayedPlayerId;     // Set when this state change was played by auto-play

        // Bonus discard state
        public boolean bonusDiscardActive;    // True when player can do bonus discard
        public Map<String, Object> pendingBonusCard; // The card drawn that matches discarded rank
    }
}