package shop.abwork.yanif.websocket;

import shop.abwork.yanif.entity.Game;
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
    private final Map<String, YanivGameEngine> gameEngines = new HashMap<>();

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
                              @Value("${game.auto-play-enabled:true}") boolean autoPlayEnabled) {
        this.gameService = gameService;
        this.presenceService = presenceService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
        this.turnTimerSeconds = turnTimerSeconds;
        this.autoPlayEnabled = autoPlayEnabled;
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
        YanivGameEngine engine = getOrRestoreEngine(roomId);
        if (engine == null) {
            // No restorable game - if the DB still says IN_PROGRESS the state is lost
            // (e.g. pre-snapshot server restart): return the room to the lobby.
            abortStaleGame(roomId);
            presenceService.setUserOnline(userId);
            broadcastLobbyState(roomId);
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
                abortStaleGame(roomId);
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
                    case "CALL_YANIV" -> {
                        engine.callYaniv(userId);
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
                abortStaleGame(roomId);
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
                abortStaleGame(roomId);
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
                if (game.getStatus() == Game.GameStatus.IN_PROGRESS) {
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

            broadcastLobbyState(roomId);

        } catch (Exception e) {
            System.err.println("Error handling player join: " + e.getMessage());
            e.printStackTrace();
            sendErrorToUser(auth.getName(), e.getMessage());
        }
    }

    /**
     * Broadcast lobby state to all players in a room.
     */
    private void broadcastLobbyState(String roomId) {
        Game game = gameService.getGameById(roomId);
        if (game == null) return;

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
        lobbyState.playerNames = playerNames;
        lobbyState.players = playerInfos;
        lobbyState.drawableDiscardCards = new ArrayList<>();
        lobbyState.topDiscardCards = new ArrayList<>();

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

            var players = gameService.getGamePlayers(roomId);

            gameService.updateGameStatus(roomId, Game.GameStatus.IN_PROGRESS);

            List<String> playerIds = players.stream()
                    .map(gp -> gp.getId().getUserId())
                    .toList();
            YanivGameEngine engine = new YanivGameEngine(roomId, (List<String>) playerIds,
                    7,
                    game.getTargetScore() != null ? game.getTargetScore() : 200);
            gameEngines.put(roomId, engine);

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
    private GameStateMessage buildGameStateForPlayers(YanivGameEngine engine, String roomId, String userId) {
        return buildGameStateForPlayers(engine, roomId, userId, null);
    }

    private GameStateMessage buildGameStateForPlayers(YanivGameEngine engine, String roomId, String userId,
                                                      String autoPlayedPlayerId) {
        GameStateMessage message = new GameStateMessage();
        message.gameId = roomId;
        var game = gameService.getGameById(roomId);
        message.roomCode = game != null ? game.getRoomCode() : "";
        message.maxPlayers = game != null ? game.getMaxPlayers() : 6;
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

        // Add player names and player list
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
                info.isHost = game != null && game.getHostUserId().equals(playerUserId);
                info.status = "ONLINE";
                playerInfos.add(info);
            });
        }
        message.playerNames = playerNames;
        message.players = playerInfos;

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

            YanivGameEngine engine = gameEngines.get(roomId);
            if (engine == null) {
                sendErrorToUser(userId, "Game not found");
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
        var players = gameService.getGamePlayers(roomId);
        for (var player : players) {
            String playerId = player.getId().getUserId();
            GameStateMessage stateMessage = buildGameStateForPlayers(engine, roomId, playerId, autoPlayedPlayerId);
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
     */
    private YanivGameEngine getOrRestoreEngine(String roomId) {
        YanivGameEngine engine = gameEngines.get(roomId);
        if (engine != null) {
            return engine;
        }

        String snapshotJson = null;
        try {
            snapshotJson = gameService.getGameState(roomId);
        } catch (Exception e) {
            System.err.println("Could not read game snapshot for room " + roomId + ": " + e.getMessage());
        }

        YanivGameEngine restored = YanivGameEngine.fromSnapshot(snapshotJson);
        if (restored != null) {
            gameEngines.put(roomId, restored);
            scheduleTurnTimerIfNeeded(restored, roomId);
            System.out.println("Restored game engine for room " + roomId + " from snapshot");
            return restored;
        }
        return null;
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

        if (engine.isRoundOver()) {
            persistRoundHistory(engine, roomId);
        }

        if (engine.isGameOver()) {
            cancelTurnTimer(roomId);
            yanivTimers.computeIfPresent(roomId, (k, f) -> {
                f.cancel(false);
                return null;
            });
            try {
                gameService.finishGame(roomId, engine.getWinnerId());
            } catch (Exception e) {
                System.err.println("Failed to mark game finished for room " + roomId + ": " + e.getMessage());
            }
            try {
                gameService.deleteGameState(roomId);
            } catch (Exception e) {
                System.err.println("Failed to delete game snapshot for room " + roomId + ": " + e.getMessage());
            }
            gameEngines.remove(roomId);
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
                    if (engine.isYanivCalled()) {
                        engine.resolveYanivCall();
                        finishMutation(engine, roomId);
                        System.out.println("Yaniv contest timer expired, round resolved for room: " + roomId);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error auto-resolving Yaniv: " + e.getMessage());
            }
        }, timerSeconds, TimeUnit.SECONDS);
        yanivTimers.put(roomId, future);
    }

    /**
     * Schedule automatic advancement past the ROUND_OVER results screen.
     */
    private void scheduleRoundOverAdvance(YanivGameEngine engine, String roomId) {
        if (!autoPlayEnabled) {
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
     * Schedule auto-play for the current player's turn. Cancels any pending timer first.
     * Applies to all players; disconnected players simply never act before expiry.
     */
    private void scheduleTurnTimerIfNeeded(YanivGameEngine engine, String roomId) {
        ScheduledFuture<?> old = turnTimers.remove(roomId);
        if (old != null) {
            old.cancel(false);
        }
        turnDeadlines.remove(roomId);

        if (!autoPlayEnabled || engine.isGameOver() || engine.isRoundOver()
                || engine.getCurrentState() != YanivGameEngine.GameState.WAIT_FOR_TURN) {
            return;
        }

        long delayMs = turnTimerSeconds * 1000L;
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
                if (gameEngines.get(roomId) != engine
                        || engine.getCurrentState() != YanivGameEngine.GameState.WAIT_FOR_TURN
                        || !expectedPlayer.equals(engine.getCurrentPlayer())) {
                    return; // human acted first, or game moved on
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
        public String actionType;       // DISCARD_AND_DRAW, CALL_YANIV
        public String playerId;
        public List<String> discardedCardIds;
        public String drawSource;       // DECK or DISCARD_PILE
        public String drawnCardId;
        public String actionId;         // For deduplication (client-generated unique ID)
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

        // All player hands revealed on ROUND_OVER
        public Map<String, List<Map<String, Object>>> allPlayerHands;

        // Player reconnection status
        public String playerDisconnected;
        public boolean playerDisconnectedStatus;

        // Turn timer / auto-play
        public int turnTimerSeconds;          // Total allowed seconds per turn
        public long turnEndsAt;               // Server epoch ms when the current turn expires
        public String autoPlayedPlayerId;     // Set when this state change was played by auto-play
    }
}