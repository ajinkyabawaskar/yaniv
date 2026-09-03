package shop.abwork.yanif.game;

import shop.abwork.yanif.game.model.Card;
import shop.abwork.yanif.game.model.DiscardPile;
import shop.abwork.yanif.game.model.Hand;

import java.util.List;

/**
 * Decides the best available move for a player who cannot act (AFK or disconnected)
 * so the game can continue when their turn timer expires.
 *
 * Heuristic, deterministic:
 * 1. Call Yaniv whenever the hand is at or below the threshold.
 * 2. Otherwise play the turn that leaves the lowest hand score, per {@link TurnOutlook}.
 *
 * The move itself is chosen by {@link TurnOutlook}, which the spectator meters also read,
 * so "what can this hand do next turn" has one answer rather than two that drift apart.
 */
public final class AutoPlayStrategy {

    public enum ActionType { CALL_YANIV, DISCARD_AND_DRAW }

    public record Decision(ActionType type, List<Card> discardCards, String drawSource, String drawnCardId) {
        static Decision yaniv() {
            return new Decision(ActionType.CALL_YANIV, List.of(), null, null);
        }

        static Decision discardAndDraw(List<Card> discardCards, String drawSource, String drawnCardId) {
            return new Decision(ActionType.DISCARD_AND_DRAW, discardCards, drawSource, drawnCardId);
        }
    }

    private AutoPlayStrategy() {}

    /**
     * Decide the move for the current player.
     *
     * @param hand           current player's hand
     * @param pile           current discard pile (for drawable-card evaluation)
     * @param yanivThreshold maximum hand score for which calling Yaniv is legal
     */
    public static Decision decide(Hand hand, DiscardPile pile, int yanivThreshold) {
        if (hand.calculateScore() <= yanivThreshold) {
            return Decision.yaniv();
        }

        TurnOutlook.Move best = TurnOutlook.bestMove(hand, pile);
        return Decision.discardAndDraw(best.discard(), best.drawSource(), best.drawnCardId());
    }
}
