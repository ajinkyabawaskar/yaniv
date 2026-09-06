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
import shop.abwork.yanif.game.GameSnapshot;
import shop.abwork.yanif.game.YanivGameEngine;
import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.DiscardCombination;
import shop.abwork.yanif.game.model.Hand;
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

    /** A real Presence, not a mock: it is a plain module with no I/O. */
    private Presence presence;
    private java.time.Instant presenceNow;

    // Stub of the Redis snapshot store
    private Map<String, String> snapshotStore;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);
        presenceService = mock(PresenceService.class);
        userService = mock(UserService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        presenceNow = java.time.Instant.parse("2026-09-02T12:00:00Z");
        presence = new Presence(new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            @Override public java.time.Instant instant() { return presenceNow; }
        });
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

        // The broadcast resolves every player's name in one batch call.
        User hostUser = new User("fp-host", "Host", "AAAAAA");
        User otherUser = new User("fp-other", "Other", "BBBBBB");
        when(userService.getUsersByIds(any())).thenAnswer(inv -> {
            Map<String, User> byId = new HashMap<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                if (HOST.equals(id)) byId.put(HOST, hostUser);
                if (OTHER.equals(id)) byId.put(OTHER, otherUser);
            }
            return byId;
        });

        controller = new GameStateController(gameService, presenceService, userService,
                messagingTemplate, presence, 1 /* turn timer seconds */, true /* auto-play */,
                1 /* yaniv contest window */, 7 /* yaniv threshold */,
                2 /* absence grace seconds */, 1 /* bonus discard timeout seconds */, true /* spectator meters */);
        controller.watchForAbsenceChanges();
        // The real composition: Presence is the only writer of the Redis projection.
        new shop.abwork.yanif.presence.PresenceRedisProjection(presence, presenceService).follow();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Stop any pending timers so they don't fire during other tests
        controller.shutdownScheduler();
        clearEngines();
    }

    private void clearEngines() throws Exception {
        Field enginesField = GameStateController.class.getDeclaredField("gameEngines");
        enginesField.setAccessible(true);
        ((Map<?, ?>) enginesField.get(controller)).clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, YanivGameEngine> enginesMap() throws Exception {
        Field enginesField = GameStateController.class.getDeclaredField("gameEngines");
        enginesField.setAccessible(true);
        return (Map<String, YanivGameEngine>) enginesField.get(controller);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Hand> engineHands(YanivGameEngine engine) throws Exception {
        Field handsField = YanivGameEngine.class.getDeclaredField("playerHands");
        handsField.setAccessible(true);
        return (Map<String, Hand>) handsField.get(engine);
    }

    @SuppressWarnings("unchecked")
    /** State absence through Presence's own interface rather than a private field. */
    private void markDisconnected(String userId) throws Exception {
        String sessionId = "session-" + userId;
        presence.sessionOpened(sessionId, userId);
        presence.attachedToRoom(sessionId, ROOM);
        presence.sessionClosed(sessionId);
    }

    private Authentication auth(String userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId);
        return auth;
    }

    private List<GameStateController.GameStateMessage> messagesFor(String userId) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeast(0))
                .convertAndSendToUser(eq(userId), eq("/queue/room/" + ROOM + "/game-state"), captor.capture());
        return captor.getAllValues().stream()
                .filter(v -> v instanceof GameStateController.GameStateMessage)
                .map(v -> (GameStateController.GameStateMessage) v)
                .toList();
    }

    private YanivGameEngine engineFromSnapshotNow() {
        String json = snapshotStore.get(ROOM);
        return json == null ? null : YanivGameEngine.fromSnapshot(json);
    }

    /**
     * Snapshots are persisted async after every mutation, so a read immediately
     * after start/action can observe the previous state. Poll briefly for the
     * write to land; use {@link #engineFromSnapshotNow()} inside {@code waitFor}
     * predicates.
     */
    private YanivGameEngine engineFromSnapshot() {
        String json = awaitSnapshot(5000);
        assertNotNull(json, "timed out waiting for the async snapshot write");
        return YanivGameEngine.fromSnapshot(json);
    }

    private String awaitSnapshot(long timeoutMs) {
        waitFor(() -> snapshotStore.get(ROOM) != null, timeoutMs);
        return snapshotStore.get(ROOM);
    }

    /** Wait until the snapshot differs from {@code beforeJson} (a mutation landed). */
    private void awaitMutation(String beforeJson, long timeoutMs) {
        boolean changed = waitFor(() -> {
            String now = snapshotStore.get(ROOM);
            return now != null && !now.equals(beforeJson);
        }, timeoutMs);
        assertTrue(changed, "timed out waiting for the async snapshot write after mutation");
    }

    /** Start a game and wait for the async initial snapshot before asserting on it. */
    private void startStartedGame() {
        controller.startGame(ROOM, auth(HOST));
        assertNotNull(awaitSnapshot(5000), "initial deal should be snapshotted (async persist)");
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

    private GameStateController.GameActionMessage pileDrawAction(String playerId, String discardId,
                                                                 String pileCardId) {
        GameStateController.GameActionMessage action = new GameStateController.GameActionMessage();
        action.actionType = "DISCARD_AND_DRAW";
        action.playerId = playerId;
        action.discardedCardIds = List.of(discardId);
        action.drawSource = "DISCARD_PILE";
        action.drawnCardId = pileCardId;
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
        startStartedGame();

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
        startStartedGame();

        String firstPlayer = engineFromSnapshot().getCurrentPlayer();
        String cardId = messagesFor(firstPlayer).get(0).hand.get(0)
                .get("id").toString();

        String beforeMove = snapshotStore.get(ROOM);
        controller.handleGameAction(ROOM, discardFirstCardAction(firstPlayer, cardId), auth(firstPlayer));
        awaitMutation(beforeMove, 5000);

        // Handle potential bonus discard after the action
        YanivGameEngine engineAfterAction = engineFromSnapshot();
        if (engineAfterAction.getCurrentState() == YanivGameEngine.GameState.BONUS_DISCARD) {
            String beforeBonus = snapshotStore.get(ROOM);
            controller.handleGameAction(ROOM, bonusDiscardAction(firstPlayer, false), auth(firstPlayer));
            awaitMutation(beforeBonus, 5000);
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
    void wholeHandDiscard_broadcastsFlagToAllPlayers() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        YanivGameEngine engine = enginesMap().get(ROOM);
        assertNotNull(engine);
        String current = engine.getCurrentPlayer();

        // Force a one-card hand: discarding it throws the whole hand, a legal single.
        engineHands(engine).put(current, new Hand(List.of(
                new Card("solo-1", Card.Suit.HEARTS, Card.Rank.SEVEN))));

        GameStateController.GameActionMessage action = discardFirstCardAction(current, "solo-1");
        controller.handleGameAction(ROOM, action, auth(current));

        String other = otherPlayer(current);
        assertTrue(messagesFor(current).stream().anyMatch(m -> current.equals(m.allCardsDiscardedByUserId)),
                "the discarder's own broadcast should carry the whole-hand flag");
        assertTrue(messagesFor(other).stream().anyMatch(m -> current.equals(m.allCardsDiscardedByUserId)),
                "the opponent's broadcast should carry the whole-hand flag too");
    }

    @Test
    void partialDiscard_doesNotBroadcastFlag() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        YanivGameEngine engine = enginesMap().get(ROOM);
        assertNotNull(engine);
        String current = engine.getCurrentPlayer();
        String cardId = messagesFor(current).get(0).hand.get(0)
                .get("id").toString();

        controller.handleGameAction(ROOM, discardFirstCardAction(current, cardId), auth(current));

        assertTrue(messagesFor(HOST).stream().noneMatch(m -> m.allCardsDiscardedByUserId != null),
                "a partial discard must not set the whole-hand flag");
        assertTrue(messagesFor(OTHER).stream().noneMatch(m -> m.allCardsDiscardedByUserId != null),
                "a partial discard must not set the whole-hand flag");
    }

    @Test
    void aceDrawnFromDiscardPile_broadcastsFlagToAllPlayers() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        YanivGameEngine engine = enginesMap().get(ROOM);
        assertNotNull(engine);
        String current = engine.getCurrentPlayer();
        engine.getDiscardPile().addCombination(
                List.of(new Card("pile-ace-1", Card.Suit.HEARTS, Card.Rank.ACE)),
                DiscardCombination.Type.SINGLE, 5);
        String cardId = messagesFor(current).get(0).hand.get(0)
                .get("id").toString();

        controller.handleGameAction(ROOM, pileDrawAction(current, cardId, "pile-ace-1"), auth(current));

        String other = otherPlayer(current);
        assertTrue(messagesFor(current).stream().anyMatch(m -> current.equals(m.acePickedByUserId)),
                "the picker's own broadcast should carry the ace-picked flag");
        assertTrue(messagesFor(other).stream().anyMatch(m -> current.equals(m.acePickedByUserId)),
                "the opponent's broadcast should carry the ace-picked flag too");
    }

    @Test
    void nonAceDrawnFromDiscardPile_doesNotBroadcastFlag() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        YanivGameEngine engine = enginesMap().get(ROOM);
        assertNotNull(engine);
        String current = engine.getCurrentPlayer();
        engine.getDiscardPile().addCombination(
                List.of(new Card("pile-king-1", Card.Suit.HEARTS, Card.Rank.KING)),
                DiscardCombination.Type.SINGLE, 5);
        String cardId = messagesFor(current).get(0).hand.get(0)
                .get("id").toString();

        controller.handleGameAction(ROOM, pileDrawAction(current, cardId, "pile-king-1"), auth(current));

        assertTrue(messagesFor(HOST).stream().noneMatch(m -> m.acePickedByUserId != null),
                "picking a non-ace from the pile must not set the ace-picked flag");
        assertTrue(messagesFor(OTHER).stream().noneMatch(m -> m.acePickedByUserId != null),
                "picking a non-ace from the pile must not set the ace-picked flag");
    }

    @Test
    void deckDraw_neverBroadcastsFlag_evenForAnAce() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        YanivGameEngine engine = enginesMap().get(ROOM);
        assertNotNull(engine);
        String current = engine.getCurrentPlayer();
        String cardId = messagesFor(current).get(0).hand.get(0)
                .get("id").toString();

        // Whatever the deck deals — even an ace — is hidden information and must
        // never be announced to the table.
        controller.handleGameAction(ROOM, discardFirstCardAction(current, cardId), auth(current));

        assertTrue(messagesFor(HOST).stream().noneMatch(m -> m.acePickedByUserId != null),
                "a deck draw must never set the ace-picked flag");
        assertTrue(messagesFor(OTHER).stream().noneMatch(m -> m.acePickedByUserId != null),
                "a deck draw must never set the ace-picked flag");
    }

    @Test
    void restartRestoresEngineFromSnapshotInsteadOfRedealing() throws Exception {
        startStartedGame();

        String firstPlayer = engineFromSnapshot().getCurrentPlayer();
        String cardId = engineFromSnapshot().getPlayerHand(firstPlayer).getCards().get(0).getId();

        String beforeMove = snapshotStore.get(ROOM);
        controller.handleGameAction(ROOM, discardFirstCardAction(firstPlayer, cardId), auth(firstPlayer));
        awaitMutation(beforeMove, 5000);

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

    // ==========================================
    // A parked bonus decision must not stall the room
    // ==========================================

    private static final String TEN_OF_HEARTS = "TEN_of_HEARTS";
    private static final String TEN_OF_SPADES = "TEN_of_SPADES";

    /** Ids are opaque to the engine, so name them after the card and read the test. */
    private static GameSnapshot.CardDto card(Card.Suit suit, Card.Rank rank) {
        return new GameSnapshot.CardDto(rank + "_of_" + suit, suit.name(), rank.name());
    }

    private static List<GameSnapshot.CardDto> allCards() {
        List<GameSnapshot.CardDto> all = new java.util.ArrayList<>();
        for (Card.Suit suit : Card.Suit.values()) {
            for (Card.Rank rank : Card.Rank.values()) {
                all.add(card(suit, rank));
            }
        }
        return all;
    }

    /**
     * A deal where the player to act holds the ten of hearts and the next deck card is
     * the ten of spades: discard that ten alone, draw from the deck, and the engine parks
     * in BONUS_DISCARD every time. The real deal reaches this state about once in fourteen
     * single-card deck draws, which is no way to test it.
     */
    private String dealThatParksOnTheBonus(String toAct) {
        String json = awaitSnapshot(5000);
        assertNotNull(json, "deal requires the async initial snapshot to have landed");
        GameSnapshot snapshot = GameSnapshot.fromJson(json);

        List<GameSnapshot.CardDto> handToAct = List.of(
                card(Card.Suit.HEARTS, Card.Rank.TEN),
                card(Card.Suit.CLUBS, Card.Rank.TWO),
                card(Card.Suit.CLUBS, Card.Rank.FIVE),
                card(Card.Suit.CLUBS, Card.Rank.EIGHT),
                card(Card.Suit.CLUBS, Card.Rank.QUEEN));
        List<GameSnapshot.CardDto> handWaiting = List.of(
                card(Card.Suit.DIAMONDS, Card.Rank.THREE),
                card(Card.Suit.DIAMONDS, Card.Rank.FOUR),
                card(Card.Suit.DIAMONDS, Card.Rank.SIX),
                card(Card.Suit.DIAMONDS, Card.Rank.NINE),
                card(Card.Suit.DIAMONDS, Card.Rank.KING));
        GameSnapshot.CardDto onPile = card(Card.Suit.HEARTS, Card.Rank.ACE);
        GameSnapshot.CardDto bonus = card(Card.Suit.SPADES, Card.Rank.TEN);

        java.util.Set<String> dealt = new java.util.HashSet<>();
        handToAct.forEach(c -> dealt.add(c.id));
        handWaiting.forEach(c -> dealt.add(c.id));
        dealt.add(onPile.id);
        dealt.add(bonus.id);

        List<GameSnapshot.CardDto> deck = new java.util.ArrayList<>();
        deck.add(bonus); // index 0 is the next card drawn
        allCards().stream().filter(c -> !dealt.contains(c.id)).forEach(deck::add);

        GameSnapshot.DiscardCombinationDto pile = new GameSnapshot.DiscardCombinationDto();
        pile.cards = List.of(onPile);
        pile.type = "SINGLE";
        pile.handSizeAtDiscard = 5;

        snapshot.playerHands = new HashMap<>(Map.of(
                toAct, handToAct,
                otherPlayer(toAct), handWaiting));
        snapshot.deckRemaining = deck;
        snapshot.discardCombinations = List.of(pile);
        snapshot.pendingDiscard = List.of();
        snapshot.pendingDiscardHandSize = 0;
        snapshot.lastDiscardedRank = null;
        snapshot.pendingBonusCard = null;
        snapshot.currentState = YanivGameEngine.GameState.WAIT_FOR_TURN.name();
        snapshot.currentPlayerIndex = snapshot.playerIds.indexOf(toAct);
        return snapshot.toJson();
    }

    @Test
    void aBonusDecisionNobodyAnswersIsDeclinedInsteadOfStallingTheRoom() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        String parkedDeal = dealThatParksOnTheBonus(HOST);
        snapshotStore.put(ROOM, parkedDeal);
        clearEngines();

        controller.handleGameAction(ROOM, discardFirstCardAction(HOST, TEN_OF_HEARTS), auth(HOST));
        awaitMutation(parkedDeal, 5000);

        YanivGameEngine parked = engineFromSnapshot();
        assertEquals(YanivGameEngine.GameState.BONUS_DISCARD, parked.getCurrentState(),
                "precondition: this deal parks on the bonus decision");
        assertEquals(HOST, parked.getCurrentPlayer());
        assertTrue(presence.absentSince(ROOM, HOST).isEmpty(),
                "precondition: HOST is connected - the absent case is already covered");

        assertTrue(waitFor(() -> engineFromSnapshotNow().getCurrentState()
                        != YanivGameEngine.GameState.BONUS_DISCARD, 4000),
                "a bonus decision the client never answers must not hold the room forever");

        YanivGameEngine after = engineFromSnapshot();
        assertEquals(OTHER, after.getCurrentPlayer(), "the turn must finish");
        assertTrue(after.getDiscardPile().getAllDiscardedCards().stream()
                        .anyMatch(c -> TEN_OF_HEARTS.equals(c.getId())),
                "the ten they discarded was staged, not piled - it must reach the pile");
        assertTrue(after.getPlayerHand(HOST).getCards().stream()
                        .anyMatch(c -> TEN_OF_SPADES.equals(c.getId())),
                "declining keeps the drawn card; the server must not discard it for them");
    }

    @Test
    void theBonusCardIsSentOnlyToThePlayerDeciding() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        snapshotStore.put(ROOM, dealThatParksOnTheBonus(HOST));
        clearEngines();

        int beforeForOther = messagesFor(OTHER).size();
        controller.handleGameAction(ROOM, discardFirstCardAction(HOST, TEN_OF_HEARTS), auth(HOST));

        GameStateController.GameStateMessage mine = messagesFor(HOST).stream()
                .filter(m -> m.bonusDiscardActive).findFirst().orElse(null);
        assertNotNull(mine, "the player deciding must be told what they drew");
        assertEquals(TEN_OF_SPADES, mine.pendingBonusCard.get("id"));

        List<GameStateController.GameStateMessage> theirs =
                messagesFor(OTHER).subList(beforeForOther, messagesFor(OTHER).size());
        assertFalse(theirs.isEmpty(), "precondition: the others were pushed the new state");
        for (GameStateController.GameStateMessage m : theirs) {
            assertFalse(m.bonusDiscardActive,
                    "a decision only one player can make must not raise a prompt on anyone else's screen");
            assertNull(m.pendingBonusCard,
                    "if they keep it, that card stays in their hand - telling the table what it is "
                            + "hands everyone a card they should not know");
        }
    }

    @Test
    void theBonusDeadlineIsVisibleToThePlayerDeciding() throws Exception {
        controller.startGame(ROOM, auth(HOST));
        snapshotStore.put(ROOM, dealThatParksOnTheBonus(HOST));
        clearEngines();

        controller.handleGameAction(ROOM, discardFirstCardAction(HOST, TEN_OF_HEARTS), auth(HOST));

        GameStateController.GameStateMessage prompt = messagesFor(HOST).stream()
                .filter(m -> m.bonusDiscardActive).findFirst().orElse(null);
        assertNotNull(prompt, "precondition: the prompt was pushed");
        assertTrue(prompt.turnEndsAt > 0,
                "a deadline the player cannot see takes their turn away mid-thought, "
                        + "with the panel just vanishing");
        assertTrue(prompt.turnTimerSeconds > 0, "the arc needs a total to count down from");
    }

    @Test
    void theBonusDeadlineStillRunsWithAutoPlaySwitchedOff() throws Exception {
        controller = new GameStateController(gameService, presenceService, userService,
                messagingTemplate, presence, 1 /* turn timer seconds */, false /* auto-play OFF */,
                1 /* yaniv contest window */, 7 /* yaniv threshold */,
                2 /* absence grace seconds */, 1 /* bonus discard timeout seconds */, true /* spectator meters */);
        controller.watchForAbsenceChanges();

        controller.startGame(ROOM, auth(HOST));
        String parkedDeal = dealThatParksOnTheBonus(HOST);
        snapshotStore.put(ROOM, parkedDeal);
        clearEngines();
        controller.handleGameAction(ROOM, discardFirstCardAction(HOST, TEN_OF_HEARTS), auth(HOST));
        awaitMutation(parkedDeal, 5000);

        assertEquals(YanivGameEngine.GameState.BONUS_DISCARD, engineFromSnapshot().getCurrentState(),
                "precondition: this deal parks on the bonus decision");

        assertTrue(waitFor(() -> engineFromSnapshotNow().getCurrentState()
                        != YanivGameEngine.GameState.BONUS_DISCARD, 4000),
                "the bonus deadline keeps the room alive; it is not a move played for anyone, "
                        + "so turning auto-play off must not bring the stall back");
    }

    @Test
    void lostSnapshotReturnsRoomToLobby() throws Exception {
        startStartedGame();
        // Simulate unrecoverable state: settle the async write first, then clear
        // twice so a write still in flight cannot resurrect the snapshot.
        snapshotStore.clear();
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        snapshotStore.clear();
        clearEngines();

        Game inProgress = new Game("ABC123", 200, HOST, 6);
        inProgress.setStatus(Game.GameStatus.IN_PROGRESS);
        when(gameService.getGameById(ROOM)).thenReturn(inProgress);

        controller.handleGameAction(ROOM, discardFirstCardAction(HOST, "card_1"), auth(HOST));

        verify(gameService).updateGameStatus(ROOM, Game.GameStatus.LOBBY);
    }
}
