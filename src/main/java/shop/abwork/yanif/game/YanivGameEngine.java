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
        WAIT_FOR_TURN, DISCARD_CARDS, DRAW_CARD, YANIV_CALLED, EVALUATE_HANDS, APPLY_SCORES, CHECK_ELIMINATIONS, ROUND_OVER, GAME_OVER
    }

    private String gameId;
    private List<String> playerIds;
    private Map<String, Hand> playerHands;
    private Map<String, Integer> playerScores;
    private Set<String> eliminatedPlayers;
    private Deck deck;
    private DiscardPile discardPile;
    private List<Card> pendingDiscard; // Cards discarded this turn, not yet added to pile
    private int pendingDiscardHandSize; // Hand size before discard (for mixed-sequence validation)
    private GameState currentState;
    private int currentPlayerIndex;
    private int roundNumber;
    private Integer yanivThreshold; // Default 5, can be customized
    private Integer targetScore;
    private String callerId; // Player who called Yaniv
    private Map<String, Integer> roundScores; // Scores for current round
    private boolean isAsaf; // If Asaf occurred
    private String asafByUserId; // Who caused the Asaf
    private String winnerId; // Final winner
    private long yanivCalledTimestamp; // When Yaniv was called (epoch ms)
    private static final int YANIV_CONTEST_TIMER_SECONDS = 15;

    public YanivGameEngine(String gameId, List<String> playerIds, Integer yanivThreshold, Integer targetScore) {
        this.gameId = gameId;
        this.playerIds = new ArrayList<>(playerIds);
        this.yanivThreshold = yanivThreshold != null ? yanivThreshold : 5;
        this.targetScore = targetScore != null ? targetScore : 200;

        initializeGame();
    }

    /**
     * Initialize the game: shuffle deck, deal hands, set initial state.
     */
    private void initializeGame() {
        this.playerHands = new HashMap<>();
        this.playerScores = new HashMap<>();
        this.eliminatedPlayers = new HashSet<>();
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

        currentState = GameState.DRAW_CARD;
    }

    /**
     * Process a player's draw action.
     * Player can draw from deck or from drawable cards in discard pile.
     * After drawing, the pending discard is added to the discard pile.
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
            if (deck.isEmpty()) {
                throw new IllegalArgumentException("Deck is empty");
            }
            Card topCard = deck.drawCard();
            hand.addCard(topCard);
        } else if ("DISCARD_PILE".equalsIgnoreCase(drawSource)) {
            if (drawnCard == null || !discardPile.isDrawable(drawnCard.getId())) {
                throw new IllegalArgumentException(
                        "Card not drawable from discard pile: " + (drawnCard != null ? drawnCard.getId() : "null"));
            }
            hand.addCard(drawnCard);
        } else {
            throw new IllegalArgumentException("Invalid draw source: " + drawSource);
        }

        // Add pending discard to discard pile AFTER drawing
        if (!pendingDiscard.isEmpty()) {
            String combinationType = CardCombinationValidator.getCombinationType(pendingDiscard, pendingDiscardHandSize);
            DiscardCombination.Type type = DiscardCombination.Type.valueOf(combinationType);
            discardPile.addCombination(pendingDiscard, type, pendingDiscardHandSize);
            pendingDiscard.clear();
            pendingDiscardHandSize = 0;
        }

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
        return YANIV_CONTEST_TIMER_SECONDS;
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

        for (Map.Entry<String, Integer> entry : handScores.entrySet()) {
            String playerId = entry.getKey();
            if (!playerId.equals(callerId)) {
                int score = entry.getValue();
                if (score < minOpponentScore) {
                    minOpponentScore = score;
                    minOpponentId = playerId;
                }
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
     * Advance to next active player.
     */
    private void advanceToNextPlayer() {
        currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
        while (eliminatedPlayers.contains(getCurrentPlayer())) {
            currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
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
     * Apply halving rule: if score is a multiple of 50, reduce total score by 25
     * points.
     */
    private void applyHalvingRule(String playerId) {
        int score = playerScores.getOrDefault(playerId, 0);
        if (score > 0 && score % 50 == 0) {
            playerScores.put(playerId, score - 25);
        }
    }

    /**
     * Check for eliminations (score >= target score).
     * Determine winner if only one player left.
     */
    private void checkEliminations() {
        for (String playerId : playerIds) {
            if (!eliminatedPlayers.contains(playerId)) {
                applyHalvingRule(playerId);

                if (playerScores.get(playerId) >= targetScore) {
                    eliminatedPlayers.add(playerId);
                }
            }
        }

        // Check if only one player left
        long activePlayers = playerIds.stream()
                .filter(p -> !eliminatedPlayers.contains(p))
                .count();

        if (activePlayers <= 1) {
            winnerId = playerIds.stream()
                    .filter(p -> !eliminatedPlayers.contains(p))
                    .findFirst()
                    .orElse(null);
            currentState = GameState.GAME_OVER;
        } else {
            // Set state to ROUND_OVER for UI to show results
            currentState = GameState.ROUND_OVER;
        }
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

        // Find next active player to start the round
        currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
        while (eliminatedPlayers.contains(getCurrentPlayer())) {
            currentPlayerIndex = (currentPlayerIndex + 1) % playerIds.size();
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
}