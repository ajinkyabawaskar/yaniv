package shop.abwork.yanif.websocket;

import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.game.YanivGameEngine;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Hand;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.PresenceService;
import shop.abwork.yanif.service.UserService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

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

    // In-memory game engines (in production, would use Redis or database)
    private final Map<String, YanivGameEngine> gameEngines = new HashMap<>();

    // Scheduled executor for Yaniv contest timers
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> yanivTimers = new ConcurrentHashMap<>();

    public GameStateController(GameService gameService,
                              PresenceService presenceService,
                              UserService userService,
                              SimpMessagingTemplate messagingTemplate) {
        this.gameService = gameService;
        this.presenceService = presenceService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Handle player discard and draw action.
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

            // Get or create game engine
            YanivGameEngine engine = gameEngines.computeIfAbsent(roomId, k -> {
                var game = gameService.getGameById(roomId);
                if (game == null) {
                    throw new RuntimeException("Game not found: " + roomId);
                }
                var players = gameService.getGamePlayers(roomId).stream()
                        .map(gp -> gp.getId().getUserId())
                        .toList();
                return new YanivGameEngine(roomId, (List<String>) players, 7, 200);
            });

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

            // Broadcast game state to all players
            broadcastGameState(engine, roomId);
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
                sendErrorToUser(userId, "Game not found");
                return;
            }

            engine.callYaniv(userId);

            // Broadcast YANIV_CALLED state to all players
            broadcastGameState(engine, roomId);

            // Schedule auto-resolve after contest timer expires
            int timerSeconds = engine.getYanivContestTimerSeconds();
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                try {
                    synchronized (engine) {
                        if (engine.isYanivCalled()) {
                            engine.resolveYanivCall();
                            broadcastGameState(engine, roomId);
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
                sendErrorToUser(userId, "Game not found");
                return;
            }

            synchronized (engine) {
                engine.contestYaniv(userId);
            }

            // Cancel the scheduled auto-resolve timer
            ScheduledFuture<?> future = yanivTimers.remove(roomId);
            if (future != null) {
                future.cancel(false);
            }

            // Broadcast resolved state to all players
            broadcastGameState(engine, roomId);
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
            YanivGameEngine engine = new YanivGameEngine(roomId, (List<String>) playerIds, 5, 200);
            gameEngines.put(roomId, engine);

            for (String playerId : playerIds) {
                presenceService.setUserInGame(playerId);
            }

            broadcastGameState(engine, roomId);

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
     * Request DTOs
     */
    public static class GameActionMessage {
        public String actionType;       // DISCARD_AND_DRAW, CALL_YANIV
        public String playerId;
        public List<String> discardedCardIds;
        public String drawSource;       // DECK or DISCARD_PILE
        public String drawnCardId;
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

        // All player hands revealed on ROUND_OVER
        public Map<String, List<Map<String, Object>>> allPlayerHands;
    }
}
