package shop.abwork.yanif.game;

import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.Hand;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializable snapshot of a complete {@link YanivGameEngine} state.
 * Persisted to Redis after every game mutation so an in-progress game
 * survives a server restart (hands, deck order, discard pile, scores).
 */
public class GameSnapshot {

    /** Bump when the layout changes; older snapshots are discarded (lobby abort). */
    public static final int CURRENT_VERSION = 1;

    // Jackson 3 does not fail on unknown properties by default
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public int version = CURRENT_VERSION;

    public String gameId;
    public List<String> playerIds;
    public Map<String, List<CardDto>> playerHands;
    public Map<String, Integer> playerScores;
    public Set<String> eliminatedPlayers;
    /** Ordered remaining deck, index 0 is the next card drawn. */
    public List<CardDto> deckRemaining;
    public List<DiscardCombinationDto> discardCombinations;
    public List<CardDto> pendingDiscard;
    public int pendingDiscardHandSize;
    /** Name of {@link YanivGameEngine.GameState}. */
    public String currentState;
    public int currentPlayerIndex;
    public int roundNumber;
    public Integer yanivThreshold;
    public Integer targetScore;
    public String callerId;
    public Map<String, Integer> roundScores;
    public boolean isAsaf;
    public String asafByUserId;
    public String winnerId;
    public long yanivCalledTimestamp;

    public static class CardDto {
        public String id;
        public String suit;
        public String rank;

        public CardDto() {}

        public CardDto(String id, String suit, String rank) {
            this.id = id;
            this.suit = suit;
            this.rank = rank;
        }
    }

    public static class DiscardCombinationDto {
        public List<CardDto> cards;
        /** Name of {@link shop.abwork.yanif.game.model.DiscardCombination.Type}. */
        public String type;
        public int handSizeAtDiscard;
    }

    /**
     * Serialize to JSON.
     */
    public String toJson() {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Deserialize from JSON. Returns null for missing or incompatible snapshots.
     */
    public static GameSnapshot fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            GameSnapshot snapshot = MAPPER.readValue(json, GameSnapshot.class);
            if (snapshot.version != CURRENT_VERSION) {
                return null;
            }
            return snapshot;
        } catch (Exception e) {
            return null;
        }
    }

    // Convenience builders used by the engine

    public static CardDto of(Card card) {
        return new CardDto(card.getId(), card.getSuit().name(), card.getRank().name());
    }

    public static List<CardDto> ofCards(List<Card> cards) {
        List<CardDto> dtos = new ArrayList<>();
        for (Card card : cards) {
            dtos.add(of(card));
        }
        return dtos;
    }

    public static Card toCard(CardDto dto) {
        return new Card(
                dto.id,
                Card.Suit.valueOf(dto.suit),
                Card.Rank.valueOf(dto.rank));
    }

    public static List<Card> toCards(List<CardDto> dtos) {
        List<Card> cards = new ArrayList<>();
        if (dtos != null) {
            for (CardDto dto : dtos) {
                cards.add(toCard(dto));
            }
        }
        return cards;
    }

    public static Map<String, List<CardDto>> ofHands(Map<String, Hand> hands) {
        Map<String, List<CardDto>> result = new HashMap<>();
        for (Map.Entry<String, Hand> entry : hands.entrySet()) {
            result.put(entry.getKey(), ofCards(entry.getValue().getCards()));
        }
        return result;
    }
}
