package shop.abwork.yanif.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.game.YanivGameEngine;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.PresenceService;
import shop.abwork.yanif.service.UserService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for turn-timer auto-play and snapshot persistence wiring in
 * GameStateController. Uses mocked services (no Spring context / Redis needed).
 */
class GameStateControllerTurnTimerTest {

    private static final String ROOM = "room-timer-1";
    private static final String HOST = "player-1";
    private static final String OTHER = "player-2";

    private GameService gameService;
    private PresenceService presenceService;
    private UserService userService;
    private SimpMessagingTemplate messagingTemplate;
    private GameStateController controller;

    // Stub of the Redis snapshot store
    private Map<String, String> snapshotStore;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        presenceService = mock(PresenceService.class);
        userService = mock(UserService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        snapshotStore = new ConcurrentHashMap<>();

        // Snapshot persistence backed by an in-memory map
        doAnswer(inv -> {
            snapshotStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(gameService).saveGameState(anyString(), anyString());
        when(gameService.getGameState(anyString())).thenAnswer(inv -> snapshotStore.get(inv.getArgument(0)));
        doAnswer(inv -> snapshotStore.remove(inv.getArgument(0))).when(gameService).deleteGameState(anyString());

        // Two-player room in LOBBY; status transitions mutate the entity
        Game game = new Game("ABC123", 200, HOST, 6);
        game.setStatus(Game.GameStatus.LOBBY);
        when(gameService.getGameById(ROOM)).thenReturn(game);
        doAnswer(inv -> {
            game.setStatus(inv.getArgument(1));
            return game;
        }).when(gameService).updateGameStatus(eq(ROOM), any());
        when(gameService.getGamePlayers(ROOM)).thenReturn(List.of(
                new GamePlayer(ROOM, HOST),
                new GamePlayer(ROOM, OTHER)));

        when(userService.getUserById(HOST)).thenReturn(Optional.of(new User("fp-host", "Host", "AAAAAA")));
        when(userService.getUserById(OTHER)).thenReturn(Optional.of(new User("fp-other", "Other", "BBBBBB")));

        controller = new GameStateController(gameService, presenceService, userService,
                messagingTemplate, 1 /* turn timer seconds */, true /* auto-play */,
                1 /* yaniv contest window */, 7 /* yaniv threshold */);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Stop any pending timers so they don't fire during other tests
        clearEngines();
    }

    private void clearEngines() throws Exception {
        Field enginesField = GameStateController.class.getDeclaredField("gameEngines");
        enginesField.setAccessible(true);
        ((Map<?, ?>) enginesField.get(controller)).clear();
    }

    @SuppressWarnings("unchecked")
    private void markDisconnected(String userId) throws Exception {
        Field field = GameStateController.class.getDeclaredField("disconnectedInGame");
        field.setAccessible(true);
        Map<String, java.util.Set<String>> map = (Map<String, java.util.Set<String>>) field.get(controller);
        map.computeIfAbsent(ROOM, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    private Authentication auth(String userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId);
        return auth;
    }

    private List<GameStateController.GameStateMessage> messagesFor(String userId) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeast(0))
                .convertAndSendToUser(eq(userId), eq("/queue/game-state"), captor.capture());
        return captor.getAllValues().stream()
                .filter(v -> v instanceof GameStateController.GameStateMessage)
                .map(v -> (GameStateController.GameStateMessage) v)
                .toList();
    }

    private YanivGameEngine engineFromSnapshot() {
        return YanivGameEngine.fromSnapshot(snapshotStore.get(ROOM));
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private GameStateController.GameActionMessage discardFirstCardAction(String playerId, String cardId) {
        GameStateController.GameActionMessage action = new GameStateController.GameActionMessage();
        action.actionType = "DISCARD_AND_DRAW";
        action.playerId = playerId;
        action.discardedCardIds = List.of(cardId);
        action.drawSource = "DECK";
        return action;
    }

    private GameStateController.GameActionMessage bonusDiscardAction(String playerId, boolean shouldDiscard) {
        GameStateController.GameActionMessage action = new GameStateController.GameActionMessage();
        action.actionType = "BONUS_DISCARD";
        action.playerId = playerId;
        action.bonusDiscard = shouldDiscard;
        return action;
    }

    private String otherPlayer(String playerId) {
        return HOST.equals(playerId) ? OTHER : HOST;
    }

    @Test
    void startGamePersistsInitialSnapshot() {
        controller.startGame(ROOM, auth(HOST));

        assertTrue(snapshotStore.containsKey(ROOM), "initial deal should be snapshotted");
        YanivGameEngine restored = engineFromSnapshot();
        assertNotNull(restored);
        assertEquals(YanivGameEngine.GameState.WAIT_FOR_TURN, restored.getCurrentState());
        assertEquals(2, restored.getAllPlayerIds().size());
    }

    @Test
    void turnTimerAutoPlaysWhenExpired() throws Exception {
        markDisconnected(HOST);
        markDisconnected(OTHER);
        controller.startGame(ROOM, auth(HOST));

        String firstPlayer = engineFromSnapshot().getCurrentPlayer();

        boolean autoPlayed = waitFor(() -> messagesFor(firstPlayer).stream()
                .anyMatch(m -> firstPlayer.equals(m.autoPlayedPlayerId)), 5000);
        assertTrue(autoPlayed, "expected a state push marking the first player's move as auto-played");

        assertNotEquals(firstPlayer, engineFromSnapshot().getCurrentPlayer(),
                "auto-play should advance to the next player after expiry");
    }

    @Test
    void connectedPlayersAreNeverAutoPlayed() throws Exception {
        // Nobody disconnected: timers must never arm, game waits indefinitely
        controller.startGame(ROOM, auth(HOST));

        try {
            Thread.sleep(2000); // > 2x the 1s timer
        } catch (InterruptedException ignored) {
        }

        long autoPlayedMessages = messagesFor(HOST).stream()
                .filter(m -> m.autoPlayedPlayerId != null)
                .count();
        assertEquals(0, autoPlayedMessages, "connected players must never be auto-played");

        YanivGameEngine.GameState stillWaiting = engineFromSnapshot().getCurrentState();
        assertEquals(YanivGameEngine.GameState.WAIT_FOR_TURN, stillWaiting,
                "game should sit waiting on the connected player's turn");
    }

    @Test
    void humanActionBeforeExpiryIsRespected() throws Exception {
        markDisconnected(HOST);
        markDisconnected(OTHER);
        controller.startGame(ROOM, auth(HOST));

        String firstPlayer = engineFromSnapshot().getCurrentPlayer();
        String cardId = messagesFor(firstPlayer).get(0).hand.get(0)
                .get("id").toString();

        controller.handleGameAction(ROOM, discardFirstCardAction(firstPlayer, cardId), auth(firstPlayer));

        // Handle potential bonus discard after the action
        YanivGameEngine engineAfterAction = engineFromSnapshot();
        if (engineAfterAction.getCurrentState() == YanivGameEngine.GameState.BONUS_DISCARD) {
            controller.handleGameAction(ROOM, bonusDiscardAction(firstPlayer, false), auth(firstPlayer));
        }

        // Human action advanced the turn immediately
        assertNotEquals(firstPlayer, engineFromSnapshot().getCurrentPlayer());

        // Wait past the original 1s expiry: the stale timer must never fire for firstPlayer
        waitFor(() -> !otherPlayer(firstPlayer).equals(engineFromSnapshot().getCurrentPlayer()), 2000);

        long autoPlayedForFirstPlayer = messagesFor(firstPlayer).stream()
                .filter(m -> firstPlayer.equals(m.autoPlayedPlayerId))
                .count();
        assertEquals(0, autoPlayedForFirstPlayer,
                "human acted before expiry - their move must never be marked auto-played");
    }

    @Test
    void restartRestoresEngineFromSnapshotInsteadOfRedealing() throws Exception {
        controller.startGame(ROOM, auth(HOST));

        String firstPlayer = engineFromSnapshot().getCurrentPlayer();
        String cardId = engineFromSnapshot().getPlayerHand(firstPlayer).getCards().get(0).getId();

        controller.handleGameAction(ROOM, discardFirstCardAction(firstPlayer, cardId), auth(firstPlayer));

        String nextPlayer = engineFromSnapshot().getCurrentPlayer();
        List<String> expectedHandIds = engineFromSnapshot().getPlayerHand(nextPlayer).getCards()
                .stream().map(c -> c.getId()).sorted().toList();

        // Simulate server restart: memory wiped, snapshots survive
        clearEngines();

        int messagesBeforeRestart = messagesFor(nextPlayer).size();
        controller.getGameState(ROOM, auth(nextPlayer));

        // Take the message sent in direct response to the state request (index
        // beforeCount), not whatever a subsequent auto-play timer pushes later
        GameStateController.GameStateMessage restoredState =
                messagesFor(nextPlayer).get(messagesBeforeRestart);
        assertNotNull(restoredState.hand);

        List<String> restoredHandIds = restoredState.hand.stream()
                .map(c -> c.get("id").toString()).sorted().toList();
        assertEquals(expectedHandIds, restoredHandIds,
                "restored hand must be identical to pre-restart hand (no re-deal)");
    }

    @Test
    void lostSnapshotReturnsRoomToLobby() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        snapshotStore.clear(); // simulate unrecoverable state
        clearEngines();

        Game inProgress = new Game("ABC123", 200, HOST, 6);
        inProgress.setStatus(Game.GameStatus.IN_PROGRESS);
        when(gameService.getGameById(ROOM)).thenReturn(inProgress);

        controller.handleGameAction(ROOM, discardFirstCardAction(HOST, "card_1"), auth(HOST));

        verify(gameService).updateGameStatus(ROOM, Game.GameStatus.LOBBY);
    }
}
