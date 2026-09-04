package shop.abwork.yanif.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import shop.abwork.yanif.entity.Game;
import shop.abwork.yanif.entity.GamePlayer;
import shop.abwork.yanif.entity.User;
import shop.abwork.yanif.game.YanivGameEngine;
import shop.abwork.yanif.presence.Presence;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.PresenceService;
import shop.abwork.yanif.service.UserService;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The host chooses what the table is played to, before the deal.
 *
 * Harness: GameStateController with mocked services and a mocked STOMP template
 * (no Spring context, no Redis, no WebSocket), matching GameLifecycleScenariosTest.
 */
class WaitingRoomScoreLimitTest {

    private static final String ROOM = "room-limit";
    private static final String HOST = "player-1";
    private static final String GUEST = "player-2";
    private static final String DESTINATION = "/queue/room/" + ROOM + "/game-state";

    private GameService gameService;
    private SimpMessagingTemplate messagingTemplate;
    private GameStateController controller;
    private Game roomGame;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        PresenceService presenceService = mock(PresenceService.class);
        UserService userService = mock(UserService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        Presence presence = new Presence(java.time.Clock.systemUTC());
        Map<String, String> snapshotStore = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            snapshotStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(gameService).saveGameState(anyString(), anyString());
        when(gameService.getGameState(anyString())).thenAnswer(inv -> snapshotStore.get(inv.getArgument(0)));

        roomGame = new Game("LMT", 100, HOST, 6);
        roomGame.setId(ROOM);
        roomGame.setStatus(Game.GameStatus.LOBBY);
        when(gameService.getGameById(ROOM)).thenReturn(roomGame);
        doAnswer(inv -> {
            roomGame.setStatus(inv.getArgument(1));
            return roomGame;
        }).when(gameService).updateGameStatus(eq(ROOM), any());
        doAnswer(inv -> {
            roomGame.setTargetScore(inv.getArgument(1));
            return roomGame;
        }).when(gameService).updateTargetScore(eq(ROOM), any());

        when(gameService.getGamePlayers(ROOM)).thenReturn(List.of(
                new GamePlayer(ROOM, HOST), new GamePlayer(ROOM, GUEST)));
        when(userService.getUserById(HOST)).thenReturn(Optional.of(new User("f1", "Host", "AAAAAA")));
        when(userService.getUserById(GUEST)).thenReturn(Optional.of(new User("f2", "Guest", "BBBBBB")));
        when(userService.getUsersByIds(any())).thenAnswer(inv -> {
            Map<String, User> byId = new HashMap<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                if (HOST.equals(id)) byId.put(HOST, new User("f1", "Host", "AAAAAA"));
                if (GUEST.equals(id)) byId.put(GUEST, new User("f2", "Guest", "BBBBBB"));
            }
            return byId;
        });

        controller = new GameStateController(gameService, presenceService, userService,
                messagingTemplate, presence, 1, false, 1, 7, 2, 1, true);
    }

    // ---------------------------------------------------------------- helpers

    private Authentication auth(String userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId);
        return auth;
    }

    private void setLimit(String userId, Integer limit) {
        GameStateController.TargetScoreMessage request = new GameStateController.TargetScoreMessage();
        request.targetScore = limit;
        controller.handleSetTargetScore(ROOM, request, auth(userId));
    }

    /** Every game-state payload pushed to a given player, oldest first. */
    private List<GameStateController.GameStateMessage> messagesTo(String userId) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeast(0))
                .convertAndSendToUser(eq(userId), eq(DESTINATION), payload.capture());
        return payload.getAllValues().stream()
                .map(GameStateController.GameStateMessage.class::cast)
                .toList();
    }

    private GameStateController.GameStateMessage lastMessageTo(String userId) {
        List<GameStateController.GameStateMessage> all = messagesTo(userId);
        assertFalse(all.isEmpty(), "expected " + userId + " to have been sent something");
        return all.get(all.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private YanivGameEngine liveEngine() throws Exception {
        Field f = GameStateController.class.getDeclaredField("gameEngines");
        f.setAccessible(true);
        return ((Map<String, YanivGameEngine>) f.get(controller)).get(ROOM);
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("The host raises the limit to 200 and it sticks to the room")
    void hostSetsTheLimit() {
        setLimit(HOST, 200);

        assertEquals(200, roomGame.getTargetScore(), "the room is now played to 200");
    }

    @Test
    @DisplayName("Everyone seated is told the new limit, not just the host who set it")
    void theWholeTableSeesTheChange() {
        setLimit(HOST, 200);

        assertEquals(200, lastMessageTo(GUEST).targetScore,
                "a guest decides whether to stay based on what the table is played to");
        assertEquals(200, lastMessageTo(HOST).targetScore);
    }

    @Test
    @DisplayName("Pre-start state carries the limit, so the waiting room can show it")
    void lobbyStateCarriesTheLimit() {
        controller.handleJoin(ROOM, auth(GUEST));

        assertEquals(100, lastMessageTo(GUEST).targetScore,
                "without this the client silently falls back to 100 and cannot show a 200 table");
    }

    @Test
    @DisplayName("A guest cannot change what the table is played to")
    void onlyTheHostMaySet() {
        setLimit(GUEST, 200);

        assertEquals(100, roomGame.getTargetScore(), "the limit is unchanged");
        assertNotNull(lastMessageTo(GUEST).error, "the guest is told why nothing happened");
    }

    @Test
    @DisplayName("The limit cannot be changed once the game has been dealt")
    void notAfterTheDeal() {
        roomGame.setStatus(Game.GameStatus.IN_PROGRESS);

        setLimit(HOST, 200);

        assertEquals(100, roomGame.getTargetScore(),
                "moving the finish line mid-game would eliminate players retroactively");
        assertNotNull(lastMessageTo(HOST).error);
    }

    @Test
    @DisplayName("A limit outside the supported set is refused")
    void unsupportedLimitIsRefused() {
        setLimit(HOST, 7);

        assertEquals(100, roomGame.getTargetScore());
        assertNotNull(lastMessageTo(HOST).error);
    }

    @Test
    @DisplayName("A missing limit is refused rather than resetting the table to the default")
    void missingLimitIsRefused() {
        setLimit(HOST, 200);
        setLimit(HOST, null);

        assertEquals(200, roomGame.getTargetScore(), "the host's earlier choice survives");
        assertNotNull(lastMessageTo(HOST).error, "the host is told why the limit was not changed");
    }

    @Test
    @DisplayName("The limit is locked by the deal itself, not only by the status write")
    void lockedOnceAnEngineExists() {
        controller.startGame(ROOM, auth(HOST));

        setLimit(HOST, 200);

        assertEquals(100, roomGame.getTargetScore(),
                "a change landing after the engine was built would leave the row and the "
                        + "engine disagreeing about who is eliminated");
        assertNotNull(lastMessageTo(HOST).error);
    }

    @Test
    @DisplayName("What players are shown is the limit the engine eliminates at")
    void thePushedLimitComesFromTheEngine() {
        setLimit(HOST, 200);
        controller.startGame(ROOM, auth(HOST));

        // Drift the row away from the dealt engine, however that might happen. The engine
        // is what eliminates, so it is what players must be shown.
        roomGame.setTargetScore(100);
        controller.handleJoin(ROOM, auth(GUEST));

        assertEquals(200, lastMessageTo(GUEST).targetScore,
                "the scoreboard must not read correctly while the engine plays to something else");
    }

    @Test
    @DisplayName("The game is dealt to the limit the host chose")
    void theDealUsesTheChosenLimit() throws Exception {
        setLimit(HOST, 200);

        controller.startGame(ROOM, auth(HOST));

        assertEquals(200, liveEngine().getTargetScore(),
                "the choice is meaningless unless the engine eliminates at it");
    }
}
