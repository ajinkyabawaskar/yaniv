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
import shop.abwork.yanif.game.GameSnapshot;
import shop.abwork.yanif.game.YanivGameEngine;
import shop.abwork.yanif.presence.Presence;
import shop.abwork.yanif.service.GameService;
import shop.abwork.yanif.service.PresenceService;
import shop.abwork.yanif.service.UserService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The leak boundary of the spectator meters.
 *
 * The readings are computed from hidden hands. Handing them to anyone still holding
 * cards would show them the table. The gate therefore lives where the per-player payload
 * is built, so a player in the game has no field to leak -- and that is what these
 * assertions check, on the built message rather than on anything the UI does with it.
 */
class SpectatorMetersGateTest {

    private static final String ROOM = "room-spectator";
    private static final String STILL_IN = "player-in";
    private static final String ALSO_IN = "player-also-in";
    private static final String KNOCKED_OUT = "player-out";

    private GameService gameService;
    private SimpMessagingTemplate messagingTemplate;
    private Map<String, String> snapshotStore;
    private Game game;

    private GameStateController build(boolean metersEnabled) {
        UserService userService = mock(UserService.class);
        PresenceService presenceService = mock(PresenceService.class);
        Presence presence = new Presence(new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId z) { return this; }
            @Override public Instant instant() { return Instant.parse("2026-09-04T12:00:00Z"); }
        });

        for (String id : List.of(STILL_IN, ALSO_IN, KNOCKED_OUT)) {
            when(userService.getUserById(id)).thenReturn(Optional.of(new User("fp-" + id, id, "AAAAAA")));
        }
        when(userService.getUsersByIds(any())).thenAnswer(inv -> {
            Map<String, User> byId = new HashMap<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                byId.put((String) id, new User("fp-" + id, (String) id, "AAAAAA"));
            }
            return byId;
        });

        return new GameStateController(gameService, presenceService, userService,
                messagingTemplate, presence, 1, false, 1, 7, 2, 1, metersEnabled);
    }

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        snapshotStore = new ConcurrentHashMap<>();

        doAnswer(inv -> {
            snapshotStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(gameService).saveGameState(anyString(), anyString());
        when(gameService.getGameState(anyString())).thenAnswer(inv -> snapshotStore.get(inv.getArgument(0)));

        game = new Game("SPEC01", 100, STILL_IN, 6);
        game.setStatus(Game.GameStatus.IN_PROGRESS);
        when(gameService.getGameById(ROOM)).thenReturn(game);
        when(gameService.getGamePlayers(ROOM)).thenReturn(List.of(
                new GamePlayer(ROOM, STILL_IN),
                new GamePlayer(ROOM, ALSO_IN),
                new GamePlayer(ROOM, KNOCKED_OUT)));
    }

    /** A live mid-round game where KNOCKED_OUT is already out. */
    private void seedMidRoundGameWithOnePlayerOut() {
        YanivGameEngine seed = new YanivGameEngine(ROOM,
                List.of(STILL_IN, ALSO_IN, KNOCKED_OUT), 7, 100);
        GameSnapshot snap = GameSnapshot.fromJson(seed.toSnapshot());
        snap.eliminatedPlayers = new LinkedHashSet<>(List.of(KNOCKED_OUT));
        snap.playerHands.put(KNOCKED_OUT, new ArrayList<>());
        snap.playerScores = new HashMap<>(Map.of(STILL_IN, 10, ALSO_IN, 20, KNOCKED_OUT, 100));
        snap.currentPlayerIndex = 0;
        snap.currentState = YanivGameEngine.GameState.WAIT_FOR_TURN.name();
        snapshotStore.put(ROOM, snap.toJson());
    }

    private Authentication auth(String userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId);
        return auth;
    }

    private GameStateController.GameStateMessage lastMessageFor(String userId) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeast(0))
                .convertAndSendToUser(eq(userId), eq("/queue/room/" + ROOM + "/game-state"), captor.capture());
        List<GameStateController.GameStateMessage> messages = captor.getAllValues().stream()
                .filter(v -> v instanceof GameStateController.GameStateMessage)
                .map(v -> (GameStateController.GameStateMessage) v)
                .toList();
        assertFalse(messages.isEmpty(), "no game state was sent to " + userId);
        return messages.get(messages.size() - 1);
    }

    @Test
    @DisplayName("A knocked-out player is given readings on everyone still in the game")
    void aKnockedOutPlayerGetsTheReadings() {
        seedMidRoundGameWithOnePlayerOut();
        GameStateController controller = build(true);

        controller.getGameState(ROOM, auth(KNOCKED_OUT));

        Map<String, YanivGameEngine.SpectatorReading> readings =
                lastMessageFor(KNOCKED_OUT).spectatorReadings;
        assertNotNull(readings, "a player who is out is the whole audience for this");
        assertEquals(java.util.Set.of(STILL_IN, ALSO_IN), readings.keySet());
        assertEquals(90, readings.get(STILL_IN).pointsFromElimination());
        assertEquals(80, readings.get(ALSO_IN).pointsFromElimination());
    }

    @Test
    @DisplayName("A player still holding cards is sent no spectator field at all")
    void aPlayerStillInTheGameIsSentNothing() {
        seedMidRoundGameWithOnePlayerOut();
        GameStateController controller = build(true);

        controller.getGameState(ROOM, auth(STILL_IN));
        controller.getGameState(ROOM, auth(ALSO_IN));

        assertNull(lastMessageFor(STILL_IN).spectatorReadings,
                "readings are derived from hidden hands: sending them would show the table");
        assertNull(lastMessageFor(ALSO_IN).spectatorReadings);
    }

    @Test
    @DisplayName("Nothing is sent while a Yaniv call is being contested, so the Asaf is not spoiled")
    void theContestWindowGivesNothingAway() {
        seedMidRoundGameWithOnePlayerOut();
        GameStateController controller = build(true);

        // Put the room in the contest window. A reading here would tell the spectator
        // which opponent is holding under the caller -- that is the Asaf, announced
        // before the reveal, at exactly the moment this feature exists to keep tense.
        GameSnapshot snap = GameSnapshot.fromJson(snapshotStore.get(ROOM));
        snap.playerHands.put(STILL_IN, List.of(new GameSnapshot.CardDto("ace1", "HEARTS", "ACE")));
        YanivGameEngine engine = YanivGameEngine.fromSnapshot(snap.toJson());
        engine.callYaniv(STILL_IN);
        assertTrue(engine.isYanivCalled(), "precondition: the contest window is open");
        snapshotStore.put(ROOM, engine.toSnapshot());

        controller.getGameState(ROOM, auth(KNOCKED_OUT));

        assertNull(lastMessageFor(KNOCKED_OUT).spectatorReadings);
    }

    @Test
    @DisplayName("The kill switch removes the field even for a knocked-out player")
    void theKillSwitchTurnsItOffEntirely() {
        seedMidRoundGameWithOnePlayerOut();
        GameStateController controller = build(false);

        controller.getGameState(ROOM, auth(KNOCKED_OUT));

        assertNull(lastMessageFor(KNOCKED_OUT).spectatorReadings);
    }
}
