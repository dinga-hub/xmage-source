package mage.player.ai.hand;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 30: result of {@link HandEvaluator#evaluate} — the AI's structured opinion
 * of an opening (or post-mulligan) hand.
 *
 * Consumed by:
 *  - Sprint 31 (Mulligan Intelligence): threshold check on {@code total}, plus the
 *    fast paths {@code hardReject} / {@code autoKeep}.
 *  - Sprint 33 (Early Game Tempo Priority): reuses the component scores to order
 *    turn-1-to-4 plays.
 *
 * The class is a plain data holder by design — keeping the math in
 * {@link HandEvaluator} and the storage here makes the evaluator trivially testable
 * (no game state needed to inspect a returned score).
 */
public final class HandScore {

    /** Number of lands in the hand (raw count, not weighted). */
    public int landCount;

    /** Weighted land contribution. Penalises both droughts and floods. */
    public int landScore;

    /** Sum of fast-mana / mana-rock / land-tutor / mana-dork tiers found in hand. */
    public int rampScore;

    /** Card draw / repeating engine pieces in hand (Rhystic-style triggers + cantrips). */
    public int drawScore;

    /** Creatures and planeswalkers that can hit the board turns 1-4. */
    public int threatScore;

    /** Penalty for unreachable colours in the hand's top spells / commander. */
    public int colorScore;

    /** Bonus / penalty based on "on-curve" plays at CMC 1, 2, 3. Excludes reactive spells. */
    public int manaCurveScore;

    /** Linear sum of the components above. Threshold logic lives in Sprint 31. */
    public int total;

    /**
     * True if the hand fails a baseline floor (e.g. 0-1 lands in a 7-card hand).
     * The mulligan policy should treat this as a forced mulligan regardless of
     * {@code total} — equivalent to the "hard reject" rules in
     * {@code research/commander_wisdom/08_mulligan_opening_hand.md} (Section 7).
     */
    public boolean hardReject;

    /**
     * True if the hand contains an explosive line (Sol Ring / Mana Crypt + viable
     * mana) that the mulligan policy should keep even when {@code total} is mediocre.
     */
    public boolean autoKeep;

    /** Human-readable component explanations — populated for debugging only. */
    public final List<String> reasons = new ArrayList<>();

    /** Convenience for callers that want a tagged log line per component. */
    public void note(String reason) {
        reasons.add(reason);
    }

    @Override
    public String toString() {
        return "HandScore{total=" + total
                + ", lands=" + landCount + "(" + landScore + ")"
                + ", ramp=" + rampScore
                + ", draw=" + drawScore
                + ", threat=" + threatScore
                + ", color=" + colorScore
                + ", curve=" + manaCurveScore
                + (hardReject ? ", HARD_REJECT" : "")
                + (autoKeep ? ", AUTO_KEEP" : "")
                + '}';
    }
}
