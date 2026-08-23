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

                // Persist snapshot and broadcast YANIV_CALLED state to all players
                finishMutation(engine, roomId);

                // Schedule auto-resolve after contest timer expires
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

                // Cancel any previous timer for this room and store the new one
                ScheduledFuture<?> oldFuture = yanivTimers.put(roomId, future);
                if (oldFuture != null) {
                    oldFuture.cancel(false);
                }
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
                Game game = gameService.getGameById(roomId);
                if (game == null) {
                    sendErrorToUser(userId, "Game not found");
                    return;
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
                    game.getYanivThreshold() != null ? game.getYanivThreshold() : 7,
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

            engine.startNextRound();

            broadcastGameState(engine, roomId);

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
        var players = gameService.getGamePlayers(roomId);
        for (var player : players) {
            String playerId = player.getId().getUserId();
            GameStateMessage stateMessage = buildGameStateForPlayers(engine, roomId, playerId);
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
     * Rebuild game engine from database for a room.
     * Used when game engine was cleaned up from memory but game is still active.
     */
    private YanivGameEngine rebuildGameEngine(String roomId) {
        try {
            Game game = gameService.getGameById(roomId);
            if (game == null || game.getStatus() == Game.GameStatus.LOBBY
                    || game.getStatus() == Game.GameStatus.FINISHED) {
                return null;
            }

            var players = gameService.getGamePlayers(roomId);
            List<String> playerIds = players.stream()
                    .map(gp -> gp.getId().getUserId())
                    .toList();

            // Create new engine - note: this won't restore exact game state (hands, discard pile, etc.)
            // In production, you'd persist full game state to database/Redis
            return new YanivGameEngine(roomId, (List<String>) playerIds, 7, 200);
        } catch (Exception e) {
            System.err.println("Error rebuilding game engine for room " + roomId + ": " + e.getMessage());
            return null;
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
    }
}