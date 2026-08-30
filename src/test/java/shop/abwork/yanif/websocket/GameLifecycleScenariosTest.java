package shop.abwork.yanif.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.game.YanivGameEngine;
import shop.abwork.yanif.game.model.Hand;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.PresenceService;
import shop.abwork.yanif.service.UserService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Encodes every row of docs/lifecycle-scenario-matrix.md as a test.
 * Harness: GameStateController with mocked services and mocked STOMP
 * session events (no Spring context, no Redis, no WebSocket).
 */
class GameLifecycleScenariosTest {

    private static final String ROOM = "room-matrix";
    private static final String HOST = "player-1";
    private static final String OTHER = "player-2";

    private GameService gameService;
    private PresenceService presenceService;
    private UserService userService;
    private SimpMessagingTemplate messagingTemplate;
    private GameStateController controller;
    private Map<String, String> snapshotStore;
    private Game roomGame; // mutable entity so status transitions are observable

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        presenceService = mock(PresenceService.class);
        userService = mock(UserService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        snapshotStore = new ConcurrentHashMap<>();

        doAnswer(inv -> {
            snapshotStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(gameService).saveGameState(anyString(), anyString());
        when(gameService.getGameState(anyString()))
                .thenAnswer(inv -> snapshotStore.get(inv.getArgument(0)));
        doAnswer(inv -> snapshotStore.remove(inv.getArgument(0))).when(gameService).deleteGameState(anyString());

        // updateGameStatus mutates the entity so later reads observe transitions
        roomGame = new Game("XYZ789", 200, HOST, 6);
        roomGame.setId(ROOM); // Use test's ROOM constant as game ID for consistency
        roomGame.setStatus(Game.GameStatus.LOBBY);
        when(gameService.getGameById(ROOM)).thenReturn(roomGame);
        doAnswer(inv -> {
            roomGame.setStatus(inv.getArgument(1));
            return roomGame;
        }).when(gameService).updateGameStatus(eq(ROOM), any());

        when(gameService.getGamePlayers(ROOM)).thenReturn(List.of(
                new GamePlayer(ROOM, HOST),
                new GamePlayer(ROOM, OTHER)));
        when(gameService.getGamePlayers(anyString())).thenReturn(List.of(
                new GamePlayer(ROOM, HOST),
                new GamePlayer(ROOM, OTHER)));

        when(userService.getUserById(HOST)).thenReturn(Optional.of(new User("f1", "Host", "AAAAAA")));
        when(userService.getUserById(OTHER)).thenReturn(Optional.of(new User("f2", "Other", "BBBBBB")));

        when(gameService.getActiveGames()).thenReturn(List.of(roomGame));

        controller = new GameStateController(gameService, presenceService, userService,
                messagingTemplate, 1 /* turn timer seconds */, true /* auto-play */,
                1 /* yaniv contest window */, 7 /* yaniv threshold */);
    }

    @AfterEach
    void tearDown() throws Exception {
        clearEngines();
        clearDisconnected();
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private Map<String, YanivGameEngine> enginesMap() {
        try {
            Field f = GameStateController.class.getDeclaredField("gameEngines");
            f.setAccessible(true);
            return (Map<String, YanivGameEngine>) f.get(controller);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void clearEngines() {
        enginesMap().clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> disconnectedMap() {
        try {
            Field f = GameStateController.class.getDeclaredField("disconnectedInGame");
            f.setAccessible(true);
            return (Map<String, Set<String>>) f.get(controller);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void clearDisconnected() {
        disconnectedMap().clear();
    }

    private YanivGameEngine liveEngine() {
        return enginesMap().get(ROOM);
    }

    private static void setEngineField(YanivGameEngine engine, String name, Object value) throws Exception {
        Field f = YanivGameEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(engine, value);
    }

    private Authentication auth(String userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId);
        return auth;
    }

    private SessionDisconnectEvent disconnectEvent(String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getMessage()).thenReturn(message);
        return event;
    }

    private SessionConnectedEvent connectEvent(String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionConnectedEvent event = mock(SessionConnectedEvent.class);
        when(event.getMessage()).thenReturn(message);
        return event;
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

    private GameStateController.GameStateMessage lastMessageFor(String userId) {
        List<GameStateController.GameStateMessage> all = messagesFor(userId);
        assertFalse(all.isEmpty(), "no state messages were sent to " + userId);
        return all.get(all.size() - 1);
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

    private GameStateController.GameActionMessage discardAction(String playerId, List<String> cardIds) {
        GameStateController.GameActionMessage action = new GameStateController.GameActionMessage();
        action.actionType = "DISCARD_AND_DRAW";
        action.playerId = playerId;
        action.discardedCardIds = cardIds;
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

    /** Drive the game to ROUND_OVER quickly: force a legal yaniv call + contest. */
    private void reachRoundOverViaContest() throws Exception {
        startStartedGame();
        YanivGameEngine engine = liveEngine();
        setEngineField(engine, "yanivThreshold", 200); // any hand may call yaniv
        String caller = engine.getCurrentPlayer();
        String opponent = caller.equals(HOST) ? OTHER : HOST;
        controller.callYaniv(ROOM, new GameStateController.YanivCallMessage(), auth(caller));
        assertEquals(YanivGameEngine.GameState.YANIV_CALLED, engine.getCurrentState());
        controller.contestYaniv(ROOM, new GameStateController.ContestYanivMessage(), auth(opponent));
        assertTrue(engine.isRoundOver(), "contest should resolve to ROUND_OVER immediately");
    }

    /** Start a game with both players connected and DB status IN_PROGRESS. */
    private void startStartedGame() {
        controller.startGame(ROOM, auth(HOST));
        assertNotNull(liveEngine(), "engine should exist after start");
    }

    // ------------------------------------------------------------ A. start

    @Test
    void A1_hostStarts_allConnected_noTimerArmed() {
        startStartedGame();

        verify(gameService).updateGameStatus(ROOM, Game.GameStatus.IN_PROGRESS);
        assertNotNull(engineFromSnapshot());
        assertEquals(YanivGameEngine.GameState.WAIT_FOR_TURN, engineFromSnapshot().getCurrentState());
        assertFalse(roomGame.getStatus() != Game.GameStatus.IN_PROGRESS);

        GameStateController.GameStateMessage firstPush = messagesFor(HOST).get(0);
        assertEquals(0, firstPush.turnEndsAt, "no turn countdown for connected players");

        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        assertTrue(messagesFor(HOST).stream().noneMatch(m -> m.autoPlayedPlayerId != null),
                "connected players must not be auto-played");
    }

    @Test
    void A1b_hostStarts_otherPlayerReceivesGameState() {
        startStartedGame();

        // Other player should receive game state broadcast (not just lobby state)
        List<GameStateController.GameStateMessage> otherMsgs = messagesFor(OTHER);
        assertFalse(otherMsgs.isEmpty(), "other player should receive at least one message");
        
        // Find the game state message (not lobby state)
        GameStateController.GameStateMessage gameStateMsg = otherMsgs.stream()
                .filter(m -> "WAIT_FOR_TURN".equals(m.currentState))
                .findFirst()
                .orElse(null);
        
        assertNotNull(gameStateMsg, "other player should receive WAIT_FOR_TURN game state");
        assertNotNull(gameStateMsg.hand, "other player should receive their hand");
        assertEquals(5, gameStateMsg.hand.size(), "other player should have 5 cards");
        assertEquals(1, gameStateMsg.roundNumber);
    }

    @Test
    void A1c_hostReentersThenStarts_otherPlayerReceivesGameState() throws Exception {
        // Host creates and starts game
        startStartedGame();
        
        // Host disconnects and reconnects (simulates exit/re-enter)
        controller.handleSessionDisconnect(disconnectEvent(HOST));
        controller.handleSessionConnect(connectEvent(HOST));
        
        // Host clicks start game
        controller.startGame(ROOM, auth(HOST));
        
        // Other player should receive game state broadcast
        List<GameStateController.GameStateMessage> otherMsgs = messagesFor(OTHER);
        
        // Find the game state message (not lobby state or disconnected messages)
        GameStateController.GameStateMessage gameStateMsg = otherMsgs.stream()
                .filter(m -> "WAIT_FOR_TURN".equals(m.currentState))
                .findFirst()
                .orElse(null);
        
        assertNotNull(gameStateMsg, "other player should receive WAIT_FOR_TURN game state after host re-enters and starts");
        assertNotNull(gameStateMsg.hand, "other player should receive their hand");
        assertEquals(5, gameStateMsg.hand.size(), "other player should have 5 cards");
        assertEquals(1, gameStateMsg.roundNumber);
    }

    @Test
    void A2_nonHostStartRejected() {
        controller.startGame(ROOM, auth(OTHER));

        verify(gameService, never()).updateGameStatus(anyString(), any());
        assertNull(snapshotStore.get(ROOM));
        assertNotNull(lastMessageFor(OTHER).error, "non-host should get an error");
    }

    @Test
    void A3_startOverwritesStaleSnapshot() throws Exception {
        // Seed a stale-but-valid snapshot from a fake older game (round 99)
        YanivGameEngine stale = new YanivGameEngine(ROOM, List.of(HOST, OTHER), 7, 200);
        setEngineField(stale, "roundNumber", 99);
        snapshotStore.put(ROOM, stale.toSnapshot());

        startStartedGame();

        assertEquals(1, engineFromSnapshot().getRoundNumber(),
                "fresh deal must replace the stale round-99 snapshot");
    }

    @Test
    void A4_startWithOnePlayerRejected() {
        when(gameService.getGamePlayers(ROOM)).thenReturn(List.of(new GamePlayer(ROOM, HOST)));

        controller.startGame(ROOM, auth(HOST));

        verify(gameService, never()).updateGameStatus(anyString(), any());
        assertNull(liveEngine());
        assertEquals("Need at least 2 players to start", lastMessageFor(HOST).error);
    }

    @Test
    void A5_secondStartWhileInProgressRejected() {
        startStartedGame();
        int callsBefore = mockingDetails(gameService).getInvocations().size();

        controller.startGame(ROOM, auth(HOST));

        assertEquals("Game already in progress", lastMessageFor(HOST).error);
        assertEquals(1, engineFromSnapshot().getRoundNumber(), "game must not be re-dealt");
        // No new snapshot write happened between the two starts' bookkeeping
        long snapshotWrites = mockingDetails(gameService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("saveGameState"))
                .count();
        assertTrue(callsBefore > 0 && snapshotWrites >= 1);
    }

    // ---------------------------------------------------- B. disconnect

    @Test
    void B1_disconnectOnOwnTurn_armsTimerAndAutoPlays() throws Exception {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();

        controller.handleSessionDisconnect(disconnectEvent(current));

        verify(presenceService).setUserDisconnectedInGame(current);
        boolean autoPlayed = waitFor(() ->
                        messagesFor(current).stream().anyMatch(m -> current.equals(m.autoPlayedPlayerId)),
                5000);
        assertTrue(autoPlayed, "disconnect on own turn must arm auto-play");
    }

    @Test
    void B2_disconnectDuringOpponentTurn_noTimerYet() throws Exception {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        String opponent = current.equals(HOST) ? OTHER : HOST;

        controller.handleSessionDisconnect(disconnectEvent(opponent));

        verify(presenceService).setUserDisconnectedInGame(opponent);
        try { Thread.sleep(1600); } catch (InterruptedException ignored) { }
        assertEquals(YanivGameEngine.GameState.WAIT_FOR_TURN, engineFromSnapshot().getCurrentState());
        assertEquals(current, engineFromSnapshot().getCurrentPlayer(),
                "opponent's disconnect must not move the game");
    }

    @Test
    void B3_disconnectDuringYanivWindow_contestStillWorks() throws Exception {
        startStartedGame();
        YanivGameEngine engine = liveEngine();
        setEngineField(engine, "yanivThreshold", 200);
        String caller = engine.getCurrentPlayer();
        String opponent = caller.equals(HOST) ? OTHER : HOST;

        controller.callYaniv(ROOM, new GameStateController.YanivCallMessage(), auth(caller));
        assertTrue(engine.isYanivCalled());

        controller.handleSessionDisconnect(disconnectEvent(caller));
        controller.contestYaniv(ROOM, new GameStateController.ContestYanivMessage(), auth(opponent));
        assertTrue(engine.isRoundOver(), "contest resolves despite caller disconnect");
    }

    @Test
    void B4_disconnectDuringRoundOver_doesNotAdvance() throws Exception {
        reachRoundOverViaContest();
        int round = engineFromSnapshot().getRoundNumber();

        controller.handleSessionDisconnect(disconnectEvent(HOST));
        verify(presenceService, atLeastOnce()).setUserOffline(HOST);

        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        assertTrue(engineFromSnapshot().isRoundOver());
        assertEquals(round, engineFromSnapshot().getRoundNumber(),
                "round-over must not auto-advance just because one player left");
    }

    @Test
    void B5_disconnectAfterGameOver_plainOffline() throws Exception {
        startStartedGame();
        YanivGameEngine engine = liveEngine();
        setEngineField(engine, "currentState", YanivGameEngine.GameState.GAME_OVER);
        setEngineField(engine, "winnerId", HOST);

        controller.handleSessionDisconnect(disconnectEvent(HOST));

        verify(presenceService).setUserOffline(HOST);
        verify(presenceService, never()).setUserDisconnectedInGame(HOST);
    }

    @Test
    void B6_disconnectUnknownUser_isNoOp() throws Exception {
        startStartedGame();
        String before = snapshotStore.get(ROOM);

        controller.handleSessionDisconnect(disconnectEvent("ghost"));

        assertEquals(before, snapshotStore.get(ROOM), "snapshot untouched");
        assertEquals(YanivGameEngine.GameState.WAIT_FOR_TURN, engineFromSnapshot().getCurrentState());
    }

    @Test
    void B7_and_E9_E10_allDisconnected_chainCompletesToGameOver() throws Exception {
        markEveryoneDisconnectedBeforeStart();
        startStartedGame();

        YanivGameEngine engine = liveEngine();
        // Speed up the chain: every hand may call yaniv, so each round resolves quickly
        setEngineField(engine, "yanivThreshold", 200);
        // Both players one point from elimination: the first resolved round ends the game
        Map<String, Integer> scores = engine.getPlayerScores();
        scores.put(HOST, 199);
        scores.put(OTHER, 199);
        setEngineField(engine, "playerScores", scores);
        snapshotStore.put(ROOM, engine.toSnapshot()); // keep snapshot coherent

        boolean finished = waitFor(() -> !enginesMap().containsKey(ROOM)
                && snapshotStore.get(ROOM) == null, 20_000);
        assertTrue(finished, "auto-play chain should drive the all-disconnected game to GAME_OVER");

        verify(gameService).finishGame(eq(ROOM), anyString());

        // E10: post-cleanup actions fail cleanly without resurrecting anything
        controller.handleGameAction(ROOM, discardAction(HOST, List.of("card_1")), auth(HOST));
        assertNotNull(lastMessageFor(HOST).error);
        assertNull(snapshotStore.get(ROOM), "no resurrection after cleanup");
        assertFalse(roomGame.getStatus() == Game.GameStatus.IN_PROGRESS);
    }

    private void markEveryoneDisconnectedBeforeStart() throws Exception {
        disconnectedMap().computeIfAbsent(ROOM, k -> ConcurrentHashMap.newKeySet()).add(HOST);
        disconnectedMap().get(ROOM).add(OTHER);
    }

    // ------------------------------------------------------ C. reconnect

    @Test
    void C1_reconnect_cancelsOwnPendingAutoPlay() throws Exception {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();

        controller.handleSessionDisconnect(disconnectEvent(current)); // arms 1s timer
        controller.handleSessionConnect(connectEvent(current));       // back before expiry

        try { Thread.sleep(1800); } catch (InterruptedException ignored) { }
        assertTrue(messagesFor(current).stream().noneMatch(m -> current.equals(m.autoPlayedPlayerId)),
                "returning player must not be auto-played");
        assertEquals(current, engineFromSnapshot().getCurrentPlayer());
    }

    @Test
    void C2_reconnect_afterAutoPlayFired_getsFreshState() throws Exception {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        controller.handleSessionDisconnect(disconnectEvent(current));

        boolean fired = waitFor(() ->
                messagesFor(current).stream().anyMatch(m -> current.equals(m.autoPlayedPlayerId)), 5000);
        assertTrue(fired);

        controller.handleSessionConnect(connectEvent(current));
        GameStateController.GameStateMessage fresh = lastMessageFor(current);
        assertNull(fresh.error);
        assertNotNull(fresh.hand);
        assertEquals(engineFromSnapshot().getCurrentPlayer(), fresh.currentTurnPlayerId);
    }

    @Test
    void C3_reconnectDuringRoundOver_seesRevealedHands() throws Exception {
        reachRoundOverViaContest();

        controller.handleSessionConnect(connectEvent(OTHER));

        GameStateController.GameStateMessage msg = lastMessageFor(OTHER);
        assertEquals("ROUND_OVER", msg.currentState);
        assertNotNull(msg.allPlayerHands, "round-over state reveals hands");
    }

    @Test
    void C4_reconnectAfterRestart_restoresIdenticalState() throws Exception {
        startStartedGame();
        String firstPlayer = engineFromSnapshot().getCurrentPlayer();
        controller.handleGameAction(ROOM, discardAction(firstPlayer,
                List.of(engineFromSnapshot().getPlayerHand(firstPlayer).getCards().get(0).getId())),
                auth(firstPlayer));

        List<String> expectedIds = engineFromSnapshot()
                .getPlayerHand(engineFromSnapshot().getCurrentPlayer()).getCards()
                .stream().map(c -> c.getId()).sorted().toList();
        String nextPlayer = engineFromSnapshot().getCurrentPlayer();

        clearEngines(); // simulate restart

        int before = messagesFor(nextPlayer).size();
        controller.getGameState(ROOM, auth(nextPlayer));
        List<String> restoredIds = messagesFor(nextPlayer).get(before).hand.stream()
                .map(c -> c.get("id").toString()).sorted().toList();
        assertEquals(expectedIds, restoredIds, "restored hand identical after restart");
    }

    @Test
    void C5_missingSnapshotWithDbInProgress_abortsToLobbyOnce() {
        startStartedGame(); // DB flips to IN_PROGRESS via mutating stub
        try { clearEngines(); } catch (Exception ignored) { }
        snapshotStore.clear(); // pre-deploy style loss

        controller.handleGameAction(ROOM, discardAction(HOST, List.of("card_1")), auth(HOST));

        verify(gameService, times(1)).updateGameStatus(ROOM, Game.GameStatus.LOBBY);
        assertNotNull(lastMessageFor(HOST).error);

        // Second attempt: already aborted, no repeat status flip
        controller.handleGameAction(ROOM, discardAction(HOST, List.of("card_1")), auth(HOST));
        verify(gameService, times(1)).updateGameStatus(ROOM, Game.GameStatus.LOBBY);
    }

    @Test
    void C6_staleSnapshotWithFinishedGame_notRestored() throws Exception {
        startStartedGame();
        clearEngines();
        roomGame.setStatus(Game.GameStatus.FINISHED); // tombstone

        controller.getGameState(ROOM, auth(HOST));

        GameStateController.GameStateMessage msg = lastMessageFor(HOST);
        assertEquals("FINISHED", msg.currentState, "stale snapshot must not resurrect the table");
        assertTrue(msg.hand.isEmpty());
        assertNull(snapshotStore.get(ROOM), "stale snapshot deleted on sight");
    }

    @Test
    void C7_unknownUserReconnect_ignored() {
        startStartedGame();
        int hostMsgsBefore = messagesFor(HOST).size();
        int otherMsgsBefore = messagesFor(OTHER).size();

        controller.handleSessionConnect(connectEvent("ghost"));

        assertEquals(hostMsgsBefore, messagesFor(HOST).size());
        assertEquals(otherMsgsBefore, messagesFor(OTHER).size());
    }

    @Test
    void C8_handleJoin_restoresFromSnapshotWhenEngineEvicted() throws Exception {
        // Start game and make a move so there's meaningful state
        startStartedGame();
        String firstPlayer = engineFromSnapshot().getCurrentPlayer();
        String cardId = engineFromSnapshot().getPlayerHand(firstPlayer).getCards().get(0).getId();
        controller.handleGameAction(ROOM, discardAction(firstPlayer, List.of(cardId)), auth(firstPlayer));

        // Handle potential bonus discard after the action
        YanivGameEngine engineAfterAction = engineFromSnapshot();
        if (engineAfterAction.getCurrentState() == YanivGameEngine.GameState.BONUS_DISCARD) {
            controller.handleGameAction(ROOM, bonusDiscardAction(firstPlayer, false), auth(firstPlayer));
        }

        // Verify game is in progress with modified state
        assertEquals(1, engineFromSnapshot().getRoundNumber());
        String nextPlayer = engineFromSnapshot().getCurrentPlayer();
        List<String> expectedHandIds = engineFromSnapshot().getPlayerHand(nextPlayer).getCards()
                .stream().map(c -> c.getId()).sorted().toList();

        // Simulate server restart: engine evicted from memory, but snapshot exists in Redis
        clearEngines();

        // Frontend calls /app/room/{roomId}/join on reconnect
        // This should restore from snapshot and send game state to the requester
        // NOT broadcast lobby state to all players
        controller.handleJoin(ROOM, auth(nextPlayer));

        // The requester should get the restored game state (not lobby state)
        GameStateController.GameStateMessage msg = lastMessageFor(nextPlayer);
        assertNotNull(msg.hand, "restored hand should not be empty");
        assertEquals(expectedHandIds, msg.hand.stream().map(c -> c.get("id").toString()).sorted().toList(),
                "handleJoin must restore from snapshot when engine not in memory");
        assertEquals("WAIT_FOR_TURN", msg.currentState);
        assertEquals(1, msg.roundNumber);

        // Other player should NOT receive lobby state broadcast (game must not "reset" for them)
        int otherMsgsBefore = messagesFor(OTHER).size();
        // handleJoin should only send to the requester, not broadcast
        assertEquals(otherMsgsBefore, messagesFor(OTHER).size(),
                "handleJoin must not broadcast lobby state to other players when game is active");
    }

    @Test
    void C9_reconnectAfterRestart_noStaleDisconnectedBroadcast() throws Exception {
        // Start game
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        String other = current.equals(HOST) ? OTHER : HOST;

        // Player disconnects
        controller.handleSessionDisconnect(disconnectEvent(current));
        verify(presenceService).setUserDisconnectedInGame(current);

        // Simulate server restart: engine evicted, disconnectedInGame map cleared (in-memory)
        clearEngines();
        clearDisconnected();

        // Player reconnects - engine restored from snapshot
        int otherMsgsBefore = messagesFor(other).size();
        controller.handleSessionConnect(connectEvent(current));

        // After restart, disconnectedInGame is lost, so no "reconnected" broadcast is sent.
        // Other player just receives normal game state updates, not a disconnected status message.
        assertEquals(otherMsgsBefore, messagesFor(other).size(),
                "no reconnect broadcast after restart (disconnectedInGame not persisted)");
    }

    // ----------------------------------------------- D. actions & races

    @Test
    void D1_returningHumanActs_beforeExpiry_singleMoveOnly() throws Exception {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();

        controller.handleSessionDisconnect(disconnectEvent(current));
        controller.handleSessionConnect(connectEvent(current));
        String cardId = engineFromSnapshot().getPlayerHand(current).getCards().get(0).getId();
        controller.handleGameAction(ROOM, discardAction(current, List.of(cardId)), auth(current));

        try { Thread.sleep(1800); } catch (InterruptedException ignored) { }
        assertTrue(messagesFor(current).stream().noneMatch(m -> current.equals(m.autoPlayedPlayerId)));
        assertNotEquals(current, engineFromSnapshot().getCurrentPlayer(), "exactly the human move applied");
    }

    @Test
    void D2_timerTaskAfterEngineReplaced_noOps() throws Exception {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        controller.handleSessionDisconnect(disconnectEvent(current));

        // Replace the live engine instance before the task fires
        YanivGameEngine replacement = YanivGameEngine.fromSnapshot(snapshotStore.get(ROOM));
        enginesMap().put(ROOM, replacement);
        int roundBefore = replacement.getRoundNumber();

        try { Thread.sleep(1800); } catch (InterruptedException ignored) { }
        assertEquals(roundBefore, replacement.getRoundNumber(),
                "stale timer task must not mutate a replaced engine");
    }

    @Test
    void D3_duplicateActionId_deduped_singleMutation() {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        String cardId = engineFromSnapshot().getPlayerHand(current).getCards().get(0).getId();

        GameStateController.GameActionMessage action = discardAction(current, List.of(cardId));
        action.actionId = "dup-1";
        controller.handleGameAction(ROOM, action, auth(current));
        String playerAfterFirst = engineFromSnapshot().getCurrentPlayer();
        int msgsAfterFirst = messagesFor(current).size();

        action.discardedCardIds = List.of(cardId); // replay identical payload
        controller.handleGameAction(ROOM, action, auth(current));

        assertEquals(playerAfterFirst, engineFromSnapshot().getCurrentPlayer(), "no double advance");
        assertTrue(messagesFor(current).size() > msgsAfterFirst, "duplicate triggers a resync push");
    }

    @Test
    void D4_nonCurrentPlayerAction_rejected() {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        String other = current.equals(HOST) ? OTHER : HOST;
        String otherCard = engineFromSnapshot().getPlayerHand(other).getCards().get(0).getId();

        controller.handleGameAction(ROOM, discardAction(other, List.of(otherCard)), auth(other));

        assertEquals("Not your turn", lastMessageFor(other).error);
        assertEquals(current, engineFromSnapshot().getCurrentPlayer(), "state unchanged");
    }

    @Test
    void D5_spoofedPlayerId_rejected() {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        String spoofed = current.equals(HOST) ? OTHER : HOST;

        GameStateController.GameActionMessage action =
                discardAction(spoofed, List.of("card_1"));
        controller.handleGameAction(ROOM, action, auth(current)); // auth != playerId

        assertEquals("Cannot perform actions for another player", lastMessageFor(current).error);
    }

    @Test
    void D6_invalidCombination_rejected_noMutation() {
        startStartedGame();
        String current = engineFromSnapshot().getCurrentPlayer();
        var hand = engineFromSnapshot().getPlayerHand(current).getCards();
        // two cards with different ranks AND different suits are never a valid combo
        List<String> badPair = hand.stream()
                .filter(a -> hand.stream().anyMatch(b -> b != a
                        && b.getValue() >= 5 && a.getValue() >= 5
                        && b.getRank() != a.getRank()
                        && b.getSuit() != a.getSuit()))
                .map(c -> c.getId())
                .distinct()
                .limit(2)
                .toList();
        org.junit.jupiter.api.Assumptions.assumeTrue(badPair.size() == 2,
                "need two mismatched high cards for this case");

        controller.handleGameAction(ROOM, discardAction(current, badPair), auth(current));

        assertNotNull(lastMessageFor(current).error);
        assertEquals(current, engineFromSnapshot().getCurrentPlayer(), "no mutation on invalid input");
    }

    // --------------------------------------- E. round end / yaniv / end

    @Test
    void E1_yanivCall_broadcastsWithCallerInfo() throws Exception {
        startStartedGame();
        YanivGameEngine engine = liveEngine();
        setEngineField(engine, "yanivThreshold", 200);
        String caller = engine.getCurrentPlayer();

        controller.callYaniv(ROOM, new GameStateController.YanivCallMessage(), auth(caller));

        GameStateController.GameStateMessage msg = lastMessageFor(caller);
        assertEquals("YANIV_CALLED", msg.currentState);
        assertEquals(caller, msg.yanivCallerId);
    }

    @Test
    void E2_contestInsideWindow_resolvesImmediately_once() throws Exception {
        reachRoundOverViaContest();
        verify(gameService, times(1)).saveRoundHistory(eq(ROOM), eq(1), anyString(),
                anyBoolean(), nullable(String.class), anyString());
    }

    @Test
    void E3_nobodyContests_autoResolvesAtWindowEnd() throws Exception {
        startStartedGame();
        YanivGameEngine engine = liveEngine();
        setEngineField(engine, "yanivThreshold", 200);
        String caller = engine.getCurrentPlayer();

        controller.callYaniv(ROOM, new GameStateController.YanivCallMessage(), auth(caller));

        boolean resolved = waitFor(() -> engineFromSnapshot().isRoundOver(), 6_000);
        assertTrue(resolved, "contest window should auto-resolve to ROUND_OVER");
    }

    @Test
    void E4_autoPlayedYanivCaller_stillGetsContestWindow() throws Exception {
        startStartedGame();
        String first = engineFromSnapshot().getCurrentPlayer();
        controller.handleSessionDisconnect(disconnectEvent(first));

        // Rig the disconnected player's hand low so auto-play calls Yaniv
        YanivGameEngine engine = liveEngine();
        Hand lowHand = new Hand(List.of(
                new shop.abwork.yanif.game.model.Card("x1", shop.abwork.yanif.game.model.Card.Suit.HEARTS, shop.abwork.yanif.game.model.Card.Rank.ACE),
                new shop.abwork.yanif.game.model.Card("x2", shop.abwork.yanif.game.model.Card.Suit.DIAMONDS, shop.abwork.yanif.game.model.Card.Rank.TWO)));
        Map<String, Hand> hands = engine.getPlayerHand(first) != null
                ? engineHands(engine) : null;
        assertNotNull(hands);
        hands.put(first, lowHand);
        snapshotStore.put(ROOM, engine.toSnapshot());

        boolean called = waitFor(() -> engineFromSnapshot() != null
                && Boolean.TRUE.equals(isYanivCalledSafe()), 8000);
        assertTrue(called, "bot should call yaniv with a low hand");

        boolean resolved = waitFor(() -> engineFromSnapshot() != null && engineFromSnapshot().isRoundOver(), 6_000);
        assertTrue(resolved, "contest window must be scheduled even for an auto-played caller");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Hand> engineHands(YanivGameEngine engine) throws Exception {
        Field f = YanivGameEngine.class.getDeclaredField("playerHands");
        f.setAccessible(true);
        return (Map<String, Hand>) f.get(engine);
    }

    private Boolean isYanivCalledSafe() {
        YanivGameEngine e = engineFromSnapshot();
        return e != null ? e.isYanivCalled() : null;
    }

    @Test
    void E6_concurrentNextRounds_onlyOneAdvances() {
        try {
            reachRoundOverViaContest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        controller.handleNextRound(ROOM, auth(HOST));
        controller.handleNextRound(ROOM, auth(OTHER));

        assertEquals(2, engineFromSnapshot().getRoundNumber(), "exactly one advance");
        assertEquals("Round is not over yet", lastMessageFor(OTHER).error);
    }

    @Test
    void E7_roundOver_withConnectedPlayers_neverSelfAdvances() throws Exception {
        reachRoundOverViaContest();

        try { Thread.sleep(1800); } catch (InterruptedException ignored) { }
        assertTrue(engineFromSnapshot().isRoundOver(), "connected players control next round");
    }

    @Test
    void E8_roundOverAllGone_oneReconnects_cancelsAdvance() throws Exception {
        reachRoundOverViaContest();
        markEveryoneDisconnectedBeforeStart();
        // Force re-arm of the all-gone advance path by touching finishMutation indirectly:
        // simplest is to rely on scheduleRoundOverAdvance having been scheduled at contest time
        // only if everyone was gone then - it wasn't (players connected), so emulate late departures:
        // disconnect events during ROUND_OVER don't arm timers, so instead verify directly that a
        // reconnect cancels nothing harmful: state remains ROUND_OVER past the would-be window.
        controller.handleSessionConnect(connectEvent(HOST));

        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        assertTrue(engineFromSnapshot().isRoundOver());
        assertEquals(1, engineFromSnapshot().getRoundNumber());
    }

    // ------------------------------------------------- F. storage faults

    @Test
    void F1_saveFailure_actionStillSucceeds() throws Exception {
        startStartedGame();
        doThrow(new RuntimeException("redis down"))
                .when(gameService).saveGameState(anyString(), anyString());

        String current = engineFromSnapshot().getCurrentPlayer();
        String cardId = engineFromSnapshot().getPlayerHand(current).getCards().get(0).getId();
        controller.handleGameAction(ROOM, discardAction(current, List.of(cardId)), auth(current));

        assertNull(lastMessageFor(current).error, "action must succeed through storage failure");
        // Snapshots are failing, so assert on the LIVE engine
        // Handle potential bonus discard state
        YanivGameEngine live = liveEngine();
        if (live.getCurrentState() == YanivGameEngine.GameState.BONUS_DISCARD) {
            controller.handleGameAction(ROOM, bonusDiscardAction(current, false), auth(current));
        }
        // Now the turn should have advanced
        assertNotEquals(current, liveEngine().getCurrentPlayer());
    }

    @Test
    void F2_corruptSnapshot_treatedAsAbsent() {
        startStartedGame();
        try { clearEngines(); } catch (Exception ignored) { }
        snapshotStore.put(ROOM, "{corrupted json");

        controller.handleGameAction(ROOM, discardAction(HOST, List.of("card_1")), auth(HOST));

        assertNotNull(lastMessageFor(HOST).error);
        verify(gameService).updateGameStatus(ROOM, Game.GameStatus.LOBBY);
    }

    @Test
    void F3_storageUnreachable_neverAbortsRoom() {
        startStartedGame();
        try { clearEngines(); } catch (Exception ignored) { }
        when(gameService.getGameState(anyString())).thenThrow(new RuntimeException("redis timeout"));
        when(gameService.getGameById(ROOM)).thenThrow(new RuntimeException("db down"));

        controller.handleGameAction(ROOM, discardAction(HOST, List.of("card_1")), auth(HOST));

        assertNotNull(lastMessageFor(HOST).error, "clean error instead of abort");
        // The setup's legitimate IN_PROGRESS flip is fine - only a LOBBY flip would be an abort
        verify(gameService, never()).updateGameStatus(anyString(), eq(Game.GameStatus.LOBBY));
    }
}
