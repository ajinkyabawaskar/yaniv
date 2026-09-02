package shop.abwork.yanif.game;

import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Deck;
import shop.abwork.yanif.game.model.DiscardCombination;
import shop.abwork.yanif.game.model.DiscardPile;
import shop.abwork.yanif.game.model.Hand;
import shop.abwork.yanif.game.validator.CardCombinationValidator;

import java.util.*;

/**
 * YanivGameEngine implements the complete Yaniv game state machine.
 * Manages players, turns, card plays, scoring, and game completion.
 */
public class YanivGameEngine {

    public enum GameState {
        WAIT_FOR_TURN, DISCARD_CARDS, DRAW_CARD, BONUS_DISCARD, YANIV_CALLED, EVALUATE_HANDS, APPLY_SCORES, CHECK_ELIMINATIONS, ROUND_OVER, GAME_OVER
    }

    private String gameId;
    private List<String> playerIds;
    private Map<String, Hand> playerHands;
    private Map<String, Integer> playerScores;
    /** Insertion-ordered: placement is derived from elimination order. */
    private Set<String> eliminatedPlayers;
    private Deck deck;
    private DiscardPile discardPile;
    private List<Card> pendingDiscard; // Cards discarded this turn, not yet added to pile
    private int pendingDiscardHandSize; // Hand size before discard (for mixed-sequence validation)
    private GameState currentState;
    private int currentPlayerIndex;
    private int roundNumber;
    private Integer yanivThreshold; // Default 7, can be customized
    private Integer targetScore;
    private String callerId; // Player who called Yaniv
    private Map<String, Integer> roundScores; // Scores for current round
    private boolean isAsaf; // If Asaf occurred
    private String asafByUserId; // Who caused the Asaf
    private String winnerId; // Final winner
    private long yanivCalledTimestamp; // When Yaniv was called (epoch ms)
    private int yanivContestTimerSeconds = 15; // Configurable via game.yaniv-contest-timer-seconds

    // Bonus discard: track the rank of the card that was just discarded
    // and the drawn card that could be bonus discarded
    private Card.Rank lastDiscardedRank; // Rank of the card discarded this turn
    private Card pendingBonusCard; // The card drawn from deck that matches lastDiscardedRank

    public YanivGameEngine(String gameId, List<String> playerIds, Integer yanivThreshold, Integer targetScore) {
        this.gameId = gameId;
        this.playerIds = new ArrayList<>(playerIds);
        this.yanivThreshold = yanivThreshold != null ? yanivThreshold : 7;
        this.targetScore = targetScore != null ? targetScore : 100;

        initializeGame();
    }

    /**
     * Restore an engine from a snapshot (no re-deal).
     * Used to survive server restarts; see {@link #toSnapshot()}.
     */
    private YanivGameEngine(GameSnapshot snapshot) {
        this.gameId = snapshot.gameId;
        this.playerIds = new ArrayList<>(snapshot.playerIds);
        this.yanivThreshold = snapshot.yanivThreshold;
        this.targetScore = snapshot.targetScore;
        this.yanivContestTimerSeconds =
                snapshot.yanivContestTimerSeconds > 0 ? snapshot.yanivContestTimerSeconds : 15;

        this.playerHands = new HashMap<>();
        for (Map.Entry<String, List<GameSnapshot.CardDto>> entry : snapshot.playerHands.entrySet()) {
            this.playerHands.put(entry.getKey(), new Hand(GameSnapshot.toCards(entry.getValue())));
        }
        this.playerScores = new HashMap<>(snapshot.playerScores);
        this.eliminatedPlayers = new LinkedHashSet<>(snapshot.eliminatedPlayers);
        this.deck = new Deck(GameSnapshot.toCards(snapshot.deckRemaining));
        this.discardPile = new DiscardPile();
        if (snapshot.discardCombinations != null) {
            for (GameSnapshot.DiscardCombinationDto dto : snapshot.discardCombinations) {
                this.discardPile.addCombination(
                        GameSnapshot.toCards(dto.cards),
                        DiscardCombination.Type.valueOf(dto.type),
                        dto.handSizeAtDiscard);
            }
        }
        this.pendingDiscard = new ArrayList<>(GameSnapshot.toCards(snapshot.pendingDiscard));
        this.pendingDiscardHandSize = snapshot.pendingDiscardHandSize;
        this.currentState = GameState.valueOf(snapshot.currentState);
        this.currentPlayerIndex = snapshot.currentPlayerIndex;
        this.roundNumber = snapshot.roundNumber;
        this.callerId = snapshot.callerId;
        this.roundScores = snapshot.roundScores != null ? new HashMap<>(snapshot.roundScores) : null;
        this.isAsaf = snapshot.isAsaf;
        this.asafByUserId = snapshot.asafByUserId;
        this.winnerId = snapshot.winnerId;
        this.yanivCalledTimestamp = snapshot.yanivCalledTimestamp;
        this.lastDiscardedRank = snapshot.lastDiscardedRank != null ? Card.Rank.valueOf(snapshot.lastDiscardedRank) : null;
        this.pendingBonusCard = snapshot.pendingBonusCard != null ? GameSnapshot.toCard(snapshot.pendingBonusCard) : null;
    }

    /**
     * Serialize the complete engine state to JSON for persistence.
     */
    public String toSnapshot() {
        GameSnapshot snapshot = new GameSnapshot();
        snapshot.gameId = gameId;
        snapshot.playerIds = new ArrayList<>(playerIds);
        snapshot.playerHands = GameSnapshot.ofHands(playerHands);
        snapshot.playerScores = new HashMap<>(playerScores);
        snapshot.eliminatedPlayers = new LinkedHashSet<>(eliminatedPlayers);
        snapshot.deckRemaining = GameSnapshot.ofCards(deck.getRemainingCards());
        snapshot.discardCombinations = new ArrayList<>();
        // Rebuild combinations from the pile via its public API
        for (DiscardCombination combo : discardPile.getCombinations()) {
            GameSnapshot.DiscardCombinationDto dto = new GameSnapshot.DiscardCombinationDto();
            dto.cards = GameSnapshot.ofCards(combo.getCards());
            dto.type = combo.getType().name();
            dto.handSizeAtDiscard = combo.getHandSizeAtDiscard();
            snapshot.discardCombinations.add(dto);
        }
        snapshot.pendingDiscard = GameSnapshot.ofCards(pendingDiscard);
        snapshot.pendingDiscardHandSize = pendingDiscardHandSize;
        snapshot.currentState = currentState.name();
        snapshot.currentPlayerIndex = currentPlayerIndex;
        snapshot.roundNumber = roundNumber;
        snapshot.yanivThreshold = yanivThreshold;
        snapshot.targetScore = targetScore;
        snapshot.yanivContestTimerSeconds = yanivContestTimerSeconds;
        snapshot.callerId = callerId;
        snapshot.roundScores = roundScores != null ? new HashMap<>(roundScores) : null;
        snapshot.isAsaf = isAsaf;
        snapshot.asafByUserId = asafByUserId;
        snapshot.winnerId = winnerId;
        snapshot.yanivCalledTimestamp = yanivCalledTimestamp;
        snapshot.lastDiscardedRank = lastDiscardedRank != null ? lastDiscardedRank.name() : null;
        snapshot.pendingBonusCard = pendingBonusCard != null ? GameSnapshot.of(pendingBonusCard) : null;
        return snapshot.toJson();
    }

    /**
     * Restore an engine from a JSON snapshot.
     *
     * @return engine, or null if the snapshot is missing/corrupt/from a different schema version
     */
    public static YanivGameEngine fromSnapshot(String json) {
        GameSnapshot snapshot = GameSnapshot.fromJson(json);
        return snapshot != null ? new YanivGameEngine(snapshot) : null;
    }

    /**
     * Initialize the game: shuffle deck, deal hands, set initial state.
     */
    private void initializeGame() {
        this.playerHands = new HashMap<>();
        this.playerScores = new HashMap<>();
        this.eliminatedPlayers = new LinkedHashSet<>();
        this.roundNumber = 1;
        this.currentPlayerIndex = 0;
        this.deck = new Deck();
        this.discardPile = new DiscardPile();
        this.pendingDiscard = new ArrayList<>();

        deck.shuffle();

        // Deal 5 cards to each player
        for (String playerId : playerIds) {
            Hand hand = new Hand(deck.drawCards(5));
            playerHands.put(playerId, hand);
            playerScores.putIfAbsent(playerId, 0);
        }

        // Turn over the first card for the discard pile
        Card initialDiscard = deck.drawCard();
        discardPile.addCombination(List.of(initialDiscard), DiscardCombination.Type.SINGLE, -1);

        this.currentState = GameState.WAIT_FOR_TURN;
    }

    /**
     * Process a player's discard action.
     * Validates the combination and transitions to DRAW_CARD state.
     * Cards are stored in pendingDiscard and only added to discard pile after draw.
     */
    public void processDiscard(String playerId, List<Card> discardedCards) {
        if (!getCurrentPlayer().equals(playerId)) {
            throw new IllegalArgumentException("Not this player's turn");
        }

        // A second discard would overwrite pendingDiscard, stranding cards that
        // have already left the hand but not yet reached the pile.
        if (currentState != GameState.WAIT_FOR_TURN) {
            throw new IllegalStateException("Cannot discard in current state: " + currentState);
        }

        Hand hand = playerHands.get(playerId);
        int handSizeBeforeDiscard = hand.size();

        if (!CardCombinationValidator.isValidCombination(discardedCards, handSizeBeforeDiscard)) {
            throw new IllegalArgumentException("Invalid card combination");
        }

        // Remove cards from player's hand
        for (Card card : discardedCards) {
            if (!hand.removeCard(card)) {
                throw new IllegalArgumentException("Card not in player's hand: " + card.getId());
            }
        }

        // Store in pending discard (not yet added to discard pile)
        this.pendingDiscard = new ArrayList<>(discardedCards);
        this.pendingDiscardHandSize = handSizeBeforeDiscard;

        // Track the last discarded rank for bonus discard rule (only for single card discards)
        if (discardedCards.size() == 1) {
            this.lastDiscardedRank = discardedCards.get(0).getRank();
        } else {
            this.lastDiscardedRank = null; // No bonus discard for multi-card combinations
        }

        currentState = GameState.DRAW_CARD;
    }

    /**
     * Process a player's draw action.
     * Player can draw from deck or from drawable cards in discard pile.
     * After drawing, the pending discard is added to the discard pile.
     * If drawing from deck and the drawn card matches the rank of the discarded card
     * (different suit), the player enters BONUS_DISCARD state to optionally discard it.
     */
    public void processDraw(String playerId, String drawSource, Card drawnCard) {
        if (!getCurrentPlayer().equals(playerId)) {
            throw new IllegalArgumentException("Not this player's turn");
        }

        if (currentState != GameState.DRAW_CARD) {
            throw new IllegalStateException("Cannot draw in current state: " + currentState);
        }

        Hand hand = playerHands.get(playerId);

        if ("DECK".equalsIgnoreCase(drawSource)) {
            if (deck.isEmpty() && !recycleDeck()) {
                throw new IllegalArgumentException("Deck is empty");
            }
            Card topCard = deck.drawCard();
            hand.addCard(topCard);

            // Check for bonus discard: drawn from deck, single card discarded,
            // and drawn card matches rank but different suit
            if (lastDiscardedRank != null
                    && topCard.getRank() == lastDiscardedRank
                    && !topCard.getSuit().equals(getSuitOfDiscardedCard())) {
                this.pendingBonusCard = topCard;
                currentState = GameState.BONUS_DISCARD;
                return; // Wait for player's bonus discard decision
            }
        } else if ("DISCARD_PILE".equalsIgnoreCase(drawSource)) {
            if (drawnCard == null) {
                throw new IllegalArgumentException("Card not drawable from discard pile: null");
            }
            // Resolve from the pile rather than trusting the caller's object: Card
            // equality is the id alone, so a passed-in card could carry a real id with
            // a different rank or suit.
            Card fromPile = discardPile.getDrawableCard(drawnCard.getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Card not drawable from discard pile: " + drawnCard.getId()));
            hand.addCard(fromPile);
        } else {
            throw new IllegalArgumentException("Invalid draw source: " + drawSource);
        }

        // Normal flow: add pending discard to discard pile and advance
        finalizeTurn();
    }

    /**
     * Process the player's decision on bonus discard.
     * If shouldDiscard is true, the pending bonus card is added to discard pile.
     * Either way, the turn ends and advances to next player.
     */
    public void processBonusDiscard(String playerId, boolean shouldDiscard) {
        if (!getCurrentPlayer().equals(playerId)) {
            throw new IllegalArgumentException("Not this player's turn");
        }

        if (currentState != GameState.BONUS_DISCARD) {
            throw new IllegalStateException("Cannot process bonus discard in current state: " + currentState);
        }

        if (shouldDiscard && pendingBonusCard != null) {
            Hand hand = playerHands.get(playerId);
            // Remove the bonus card from hand (it was added during draw)
            hand.removeCard(pendingBonusCard);
            // The turn's own discard goes down first, so the bonus card ends up on top.
            // Only the top combination is drawable, so pushing them the other way round
            // takes the bonus card out of play the moment it is discarded.
            int handSizeAtDiscard = pendingDiscardHandSize;
            pushPendingDiscardToPile();
            discardPile.addCombination(List.of(pendingBonusCard), DiscardCombination.Type.SINGLE, handSizeAtDiscard);
        }
        // If not discarding, the card stays in hand

        // Clear bonus discard state
        pendingBonusCard = null;
        lastDiscardedRank = null;

        // Finalize the turn (add pending discard to pile if not already done)
        finalizeTurn();
    }

    /**
     * Helper to get the suit of the single card that was discarded this turn.
     * Returns null if no single card was discarded.
     */
    private Card.Suit getSuitOfDiscardedCard() {
        if (pendingDiscard.size() == 1) {
            return pendingDiscard.get(0).getSuit();
        }
        return null;
    }

    /**
     * Move the cards staged by this turn's discard onto the pile. They are staged rather
     * than pushed at discard time so the player cannot draw back the card they just
     * threw. Does nothing once they are already down, so it is safe to call twice.
     */
    private void pushPendingDiscardToPile() {
        if (pendingDiscard.isEmpty()) {
            return;
        }
        String combinationType = CardCombinationValidator.getCombinationType(pendingDiscard, pendingDiscardHandSize);
        discardPile.addCombination(pendingDiscard,
                DiscardCombination.Type.valueOf(combinationType), pendingDiscardHandSize);
        pendingDiscard.clear();
        pendingDiscardHandSize = 0;
    }

    /**
     * Finalize the turn: add pending discard to discard pile and advance to next player.
     */
    private void finalizeTurn() {
        // Add pending discard to discard pile AFTER drawing
        pushPendingDiscardToPile();

        // Clear bonus discard tracking
        pendingBonusCard = null;
        lastDiscardedRank = null;

        // Move to next player
        advanceToNextPlayer();
    }

    /**
     * Handle a Yaniv call by a player.
     * Validates hand score, transitions to YANIV_CALLED state.
     * Other players have a 15-second window to contest (Asaf) before auto-resolution.
     */
    public void callYaniv(String playerId) {
        if (!getCurrentPlayer().equals(playerId)) {
            throw new IllegalArgumentException("Not this player's turn");
        }

        // Without this a player could discard first (excluding those cards from the
        // scored hand while they never reach the pile), or re-send the call to reset
        // the contest window indefinitely.
        if (currentState != GameState.WAIT_FOR_TURN) {
            throw new IllegalStateException("Cannot call Yaniv in current state: " + currentState);
        }

        Hand hand = playerHands.get(playerId);
        int handScore = hand.calculateScore();

        if (handScore > yanivThreshold) {
            throw new IllegalArgumentException(
                    "Hand score " + handScore + " exceeds Yaniv threshold " + yanivThreshold);
        }

        this.callerId = playerId;
        this.yanivCalledTimestamp = System.currentTimeMillis();
        this.currentState = GameState.YANIV_CALLED;
    }

    /**
     * Contest a Yaniv call (any non-caller player can trigger this).
     * Immediately resolves the round (evaluates hands for Asaf).
     */
    public void contestYaniv(String playerId) {
        if (currentState != GameState.YANIV_CALLED) {
            throw new IllegalStateException("Cannot contest: no active Yaniv call");
        }
        if (!playerIds.contains(playerId)) {
            throw new IllegalArgumentException("Only players in this game can contest");
        }
        if (playerId.equals(callerId)) {
            throw new IllegalArgumentException("The Yaniv caller cannot contest their own call");
        }
        if (eliminatedPlayers.contains(playerId)) {
            throw new IllegalArgumentException("Eliminated players cannot contest");
        }
        // Immediately resolve
        resolveYanivCall();
    }

    /**
     * Resolve the Yaniv call (called when timer expires or someone contests).
     * Evaluates hands and transitions to ROUND_OVER.
     */
    public void resolveYanivCall() {
        if (currentState != GameState.YANIV_CALLED) {
            throw new IllegalStateException("Cannot resolve: not in YANIV_CALLED state");
        }
        this.currentState = GameState.EVALUATE_HANDS;
        evaluateHands();
    }

    /**
     * Check if we are in YANIV_CALLED state (contest window open).
     */
    public boolean isYanivCalled() {
        return currentState == GameState.YANIV_CALLED;
    }

    /**
     * Get the timestamp when Yaniv was called.
     */
    public long getYanivCalledTimestamp() {
        return yanivCalledTimestamp;
    }

    /**
     * Get the contest timer duration in seconds.
     */
    public int getYanivContestTimerSeconds() {
        return yanivContestTimerSeconds;
    }

    /**
     * Set the contest timer duration (from game.yaniv-contest-timer-seconds).
     * Applied to newly created engines; restored engines read it from their snapshot.
     */
    public void setYanivContestTimerSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("Contest timer must be positive");
        }
        this.yanivContestTimerSeconds = seconds;
    }

    /**
     * Get the maximum hand score for which calling Yaniv is legal.
     */
    public int getYanivThreshold() {
        return yanivThreshold;
    }

    /**
     * Get all player hands (for revealing after round over).
     * Returns a map of playerId -> list of cards.
     */
    public Map<String, List<Card>> getAllPlayerHands() {
        Map<String, List<Card>> allHands = new HashMap<>();
        for (Map.Entry<String, Hand> entry : playerHands.entrySet()) {
            if (!eliminatedPlayers.contains(entry.getKey())) {
                allHands.put(entry.getKey(), entry.getValue().getCards());
            }
        }
        return allHands;
    }

    /**
     * Evaluate all hands and determine scores for the round.
     * Check for Asaf (opponent with score < caller's score).
     * If opponent ties with caller, both get 0 (no Asaf).
     */
    private void evaluateHands() {
        roundScores = new HashMap<>();
        Hand callerHand = playerHands.get(callerId);
        int callerScore = callerHand.calculateScore();

        // Get all player scores
        Map<String, Integer> handScores = new HashMap<>();
        for (String playerId : playerIds) {
            if (!eliminatedPlayers.contains(playerId)) {
                Hand hand = playerHands.get(playerId);
                handScores.put(playerId, hand.calculateScore());
            }
        }

        // Check for Asaf: any opponent with score STRICTLY LESS than caller's score
        isAsaf = false;
        asafByUserId = null;
        String minOpponentId = null;
        int minOpponentScore = Integer.MAX_VALUE;

        // Iterate playerIds, not the map: HashMap order is arbitrary, so two opponents
        // tied for the lowest score would otherwise be separated by hash order. Seat
        // order makes the winner of a tie deterministic.
        for (String playerId : playerIds) {
            if (playerId.equals(callerId) || !handScores.containsKey(playerId)) {
                continue;
            }
            int score = handScores.get(playerId);
            if (score < minOpponentScore) {
                minOpponentScore = score;
                minOpponentId = playerId;
            }
        }

        // Asaf only triggers if opponent has STRICTLY lower score than caller
        if (minOpponentId != null && minOpponentScore < callerScore) {
            isAsaf = true;
            asafByUserId = minOpponentId;
        }

        applyScores(handScores);
    }

    /**
     * Refill the empty deck with all cards not currently in players' hands.
     * Keeps the top discard combination for draw-from-discard rules.
     *
     * @return true if the deck was refilled
     */
    private boolean recycleDeck() {
        Set<String> heldCardIds = new HashSet<>();

        for (Map.Entry<String, Hand> entry : playerHands.entrySet()) {
            if (!eliminatedPlayers.contains(entry.getKey())) {
                for (Card card : entry.getValue().getCards()) {
                    heldCardIds.add(card.getId());
                }
            }
        }

        List<DiscardCombination> combos = discardPile.getCombinations();
        if (!combos.isEmpty()) {
            DiscardCombination topCombination = combos.get(combos.size() - 1);
            for (Card card : topCombination.getCards()) {
                heldCardIds.add(card.getId());
            }
        }

        // The current player's discard has left their hand but has not reached the
        // pile yet. Without this it would be regenerated into the deck and then
        // pushed onto the pile by finalizeTurn, existing in two places at once.
        for (Card card : pendingDiscard) {
            heldCardIds.add(card.getId());
        }

        Card.Suit[] suits = {Card.Suit.HEARTS, Card.Suit.DIAMONDS, Card.Suit.CLUBS, Card.Suit.SPADES};
        Card.Rank[] ranks = {Card.Rank.ACE, Card.Rank.TWO, Card.Rank.THREE, Card.Rank.FOUR,
                Card.Rank.FIVE, Card.Rank.SIX, Card.Rank.SEVEN, Card.Rank.EIGHT,
                Card.Rank.NINE, Card.Rank.TEN, Card.Rank.JACK, Card.Rank.QUEEN, Card.Rank.KING};

        List<Card> newDeckCards = new ArrayList<>();
        int cardId = 1;
        for (Card.Suit suit : suits) {
            for (Card.Rank rank : ranks) {
                String id = "card_" + cardId++;
                if (!heldCardIds.contains(id)) {
                    newDeckCards.add(new Card(id, suit, rank));
                }
            }
        }

        if (newDeckCards.isEmpty()) {
            return false;
        }

        if (!combos.isEmpty()) {
            discardPile.retainTopCombinations(1);
        }
        this.deck = new Deck(newDeckCards);
        this.deck.shuffle();
        return true;
    }

    /**
     * Advance to next active player.
     */
    private void advanceToNextPlayer() {
        currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
        // Bounded: one full lap means every player is eliminated, which should have
        // ended the game already. Spinning forever here would hang the request thread.
        for (int i = 0; i < playerIds.size() && eliminatedPlayers.contains(getCurrentPlayer()); i++) {
            currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
        }
        if (eliminatedPlayers.contains(getCurrentPlayer())) {
            throw new IllegalStateException("No active players left to take a turn");
        }
        currentState = GameState.WAIT_FOR_TURN;
    }

    /**
     * Apply round scores to player running totals.
     * Handles Yaniv (caller gets 0), tie (all with same score get 0),
     * and Asaf (caller gets +30 penalty, lowest opponent gets 0).
     */
    private void applyScores(Map<String, Integer> handScores) {
        int callerScore = handScores.getOrDefault(callerId, 0);

        // Find all players (excluding caller) who tied with caller's score
        Set<String> tiedPlayers = new HashSet<>();
        if (!isAsaf) {
            for (Map.Entry<String, Integer> entry : handScores.entrySet()) {
                String playerId = entry.getKey();
                if (!playerId.equals(callerId) && entry.getValue() == callerScore) {
                    tiedPlayers.add(playerId);
                }
            }
        }

        for (String playerId : playerIds) {
            if (eliminatedPlayers.contains(playerId)) {
                roundScores.put(playerId, 0);
                continue;
            }

            int score = handScores.getOrDefault(playerId, 0);

            if (playerId.equals(callerId)) {
                if (isAsaf) {
                    // Caller gets penalty: hand total + 30
                    score = handScores.get(callerId) + 30;
                } else {
                    // Caller gets 0 (lowest or tied for lowest)
                    score = 0;
                }
            } else if (isAsaf && playerId.equals(asafByUserId)) {
                // Player who caused Asaf (lowest score) gets 0
                score = 0;
            } else if (!isAsaf && tiedPlayers.contains(playerId)) {
                // Player tied with caller's score - also gets 0 (co-winner)
                score = 0;
            }
            // All other players get their hand score

            roundScores.put(playerId, score);
            playerScores.put(playerId, playerScores.getOrDefault(playerId, 0) + score);
        }

        currentState = GameState.CHECK_ELIMINATIONS;
        checkEliminations();
    }

    /**
     * Halve a score that this round moved ONTO an exact multiple of 50.
     *
     * The round must actually have changed the score: a player parked on a multiple
     * of 50 who then scores 0 keeps their total. Halving on every round end instead
     * would keep re-halving an unchanged score (100 -> 50 -> 25).
     */
    private void applyHalvingRule(String playerId, int roundScore) {
        if (roundScore == 0) {
            return;
        }
        int score = playerScores.getOrDefault(playerId, 0);
        if (score > 0 && score % 50 == 0) {
            playerScores.put(playerId, score / 2);
        }
    }

    /**
     * Check for eliminations (score >= target score).
     * Determine winner if only one player left.
     */
    private void checkEliminations() {
        // Who was still playing before this round's eliminations are applied. If they
        // all cross the target at once, the winner is decided among exactly these.
        List<String> activeBefore = playerIds.stream()
                .filter(p -> !eliminatedPlayers.contains(p))
                .toList();

        for (String playerId : activeBefore) {
            applyHalvingRule(playerId, roundScores.getOrDefault(playerId, 0));

            if (playerScores.get(playerId) >= targetScore) {
                eliminatedPlayers.add(playerId);
                // Hand back their cards. An eliminated player is never dealt to again, so
                // an uncleared hand would sit there forever: shown to clients as a phantom
                // card count, and counted as neither held nor in the deck when the deck is
                // rebuilt, so those ids end up in two places at once.
                playerHands.put(playerId, new Hand());
            }
        }

        List<String> stillActive = playerIds.stream()
                .filter(p -> !eliminatedPlayers.contains(p))
                .toList();

        if (stillActive.size() > 1) {
            // Set state to ROUND_OVER for UI to show results
            currentState = GameState.ROUND_OVER;
            return;
        }

        if (stillActive.size() == 1) {
            winnerId = stillActive.get(0);
        } else {
            // Everyone crossed the target in the same round: the lowest running score
            // among them wins. An exact tie is a genuine draw, and winnerId stays null.
            winnerId = lowestScorerOrNullOnTie(activeBefore);
        }
        currentState = GameState.GAME_OVER;
    }

    /**
     * The single lowest-scoring player among the candidates, or null when two or more
     * share the lowest score.
     */
    private String lowestScorerOrNullOnTie(List<String> candidates) {
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean tied = false;
        for (String playerId : candidates) {
            int score = playerScores.getOrDefault(playerId, 0);
            if (score < bestScore) {
                bestScore = score;
                best = playerId;
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }
        return tied ? null : best;
    }

    /**
     * Finishing order, best first: the winner, then players in reverse elimination
     * order (last knocked out places highest). Used to persist placement.
     *
     * @return empty when the game ended without a winner — a draw has no placements
     */
    public List<String> getFinishingOrder() {
        // No winner means a draw. Reversed elimination order would otherwise put the
        // last player knocked out at index 0 and hand them first place.
        if (winnerId == null) {
            return List.of();
        }

        List<String> order = new ArrayList<>();
        order.add(winnerId);
        List<String> eliminated = new ArrayList<>(eliminatedPlayers);
        Collections.reverse(eliminated);
        for (String playerId : eliminated) {
            if (!playerId.equals(winnerId)) {
                order.add(playerId);
            }
        }
        for (String playerId : playerIds) {
            if (!order.contains(playerId)) {
                order.add(playerId);
            }
        }
        return order;
    }

    /**
     * Start a new round: reset hands and discard pile.
     * Called after ROUND_OVER state is dismissed by players.
     */
    public void startNextRound() {
        if (currentState != GameState.ROUND_OVER) {
            throw new IllegalStateException("Cannot start next round: not in ROUND_OVER state");
        }

        roundNumber++;
        callerId = null;
        isAsaf = false;
        asafByUserId = null;
        if (roundScores != null) {
            roundScores.clear();
        }
        discardPile.clear();
        pendingDiscard.clear();
        // Clear bonus discard state
        lastDiscardedRank = null;
        pendingBonusCard = null;

        this.deck = new Deck();
        this.deck.shuffle();

        // Deal 5 new cards to each active player
        for (String playerId : playerIds) {
            if (!eliminatedPlayers.contains(playerId)) {
                Hand hand = new Hand(deck.drawCards(5));
                playerHands.put(playerId, hand);
            }
        }

        // Turn over the first card for the discard pile
        Card initialDiscard = deck.drawCard();
        discardPile.addCombination(List.of(initialDiscard), DiscardCombination.Type.SINGLE, -1);

        // Find next active player to start the round (bounded: a full lap means
        // nobody is left, which checkEliminations should already have caught)
        currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
        for (int i = 0; i < playerIds.size() && eliminatedPlayers.contains(getCurrentPlayer()); i++) {
            currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
        }
        if (eliminatedPlayers.contains(getCurrentPlayer())) {
            throw new IllegalStateException("No active players left to start a round");
        }

        currentState = GameState.WAIT_FOR_TURN;
    }

    public String getGameId() {
        return gameId;
    }

    /**
     * Get round scores for the last completed round.
     */
    public Map<String, Integer> getRoundScores() {
        return roundScores != null ? new HashMap<>(roundScores) : new HashMap<>();
    }

    /**
     * Get round winners - players who scored 0 this round.
     * In case of tie for lowest score (including caller), all tied players are winners.
     * In case of Asaf, only the Asaf player (lowest opponent) wins.
     */
    public List<String> getRoundWinners() {
        if (roundScores == null || roundScores.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> winners = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : roundScores.entrySet()) {
            if (entry.getValue() == 0) {
                winners.add(entry.getKey());
            }
        }
        return winners;
    }

    /**
     * Get player scores.
     */
    public Map<String, Integer> getPlayerScores() {
        return new HashMap<>(playerScores);
    }

    /**
     * Get player hand.
     */
    public Hand getPlayerHand(String playerId) {
        return playerHands.get(playerId);
    }

    /**
     * Check if round is over (waiting for players to acknowledge).
     */
    public boolean isRoundOver() {
        return currentState == GameState.ROUND_OVER;
    }

    /**
     * Check if game is over.
     */
    public boolean isGameOver() {
        return currentState == GameState.GAME_OVER;
    }

    /**
     * Get winner ID if game is over.
     */
    public String getWinnerId() {
        return winnerId;
    }

    /**
     * Get eliminated players.
     */
    public Set<String> getEliminatedPlayers() {
        return new HashSet<>(eliminatedPlayers);
    }

    /**
     * Get current round number.
     */
    public int getRoundNumber() {
        return roundNumber;
    }

    public DiscardPile getDiscardPile() {
        return discardPile;
    }

    public int getDeckCount() {
        return deck.getRemainingCount();
    }

    /**
     * Get current game state.
     */
    public GameState getCurrentState() {
        return currentState;
    }

    /**
     * Get current player.
     */
    public String getCurrentPlayer() {
        return playerIds.get(currentPlayerIndex);
    }

    public boolean isAsaf() {
        return isAsaf;
    }

    public String getAsafByUserId() {
        return asafByUserId;
    }

    public String getCallerId() {
        return callerId;
    }

    /**
     * Get all player IDs in this game (including eliminated).
     */
    public List<String> getAllPlayerIds() {
        return new ArrayList<>(playerIds);
    }

    /**
     * Check if the game is currently in bonus discard state (waiting for player
     * to decide whether to discard a drawn card matching the rank of the discarded card).
     */
    public boolean isBonusDiscardActive() {
        return currentState == GameState.BONUS_DISCARD;
    }

    /**
     * Get the pending bonus card that the player can optionally discard.
     * Only valid when {@link #isBonusDiscardActive()} returns true.
     */
    public Card getPendingBonusCard() {
        return pendingBonusCard;
    }

    /**
     * Get the rank of the card that was discarded this turn (triggering potential bonus discard).
     */
    public Card.Rank getLastDiscardedRank() {
        return lastDiscardedRank;
    }
}