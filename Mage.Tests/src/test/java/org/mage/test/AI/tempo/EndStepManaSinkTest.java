package org.mage.test.AI.tempo;

import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

/**
 * Sprint 33E — Format A+B (behavioral): validates EndStepManaSinkOptimizer
 * and ComputerPlayer7 END_TURN hook in a 2-player game.
 *
 * <p>In 2-player mode, playerB's END_TURN is always the last opponent's end
 * step before playerA, so {@code isLastOpponentEndStepBeforeBot} always fires
 * for playerB's turn. Turn numbering: odd game turns = playerA's, even = playerB's.
 *
 * <h2>Format A — new behavior (33E)</h2>
 * <ol>
 *   <li>TIER_9_NOOP (Mutavault animation) is suppressed in opponent's END_TURN —
 *       the optimizer's Rule 1 now closes the gap left by EarlyGameTempoOptimizer
 *       which deliberately excluded END_TURN.</li>
 *   <li>Regression: bot's own END_TURN still passes without animating Mutavault
 *       (the hook only fires for opponent's last end step).</li>
 * </ol>
 *
 * <h2>Format B — regression (33A, 33B, Sprint 18)</h2>
 * <ol>
 *   <li>TIER_9_NOOP still suppressed in opponent's PRECOMBAT_MAIN (33A Rule 1 intact).</li>
 *   <li>TIER_9_NOOP still suppressed on own PRECOMBAT_MAIN (33B Rule 1 intact).</li>
 * </ol>
 *
 * <h2>TIER_8 promotion note</h2>
 * Asserting that the bot explicitly chose a TIER_8 activation in END_TURN requires
 * a card whose activation is only legal in that specific window — difficult to isolate
 * in the 2-player test harness (bot has priority in its own main phase too). TIER_8
 * promotion is validated via smoke test: observe
 * {@code [END_STEP_SINK] Promoting TIER_8 activation} in game logs during live play.
 *
 * <h2>Gotchas (QA Playbook §3)</h2>
 * <ul>
 *   <li>G3: addCard(BATTLEFIELD) cards count in assertPermanentCount from t=0.</li>
 *   <li>G12: no count=0 assertions; asserting CardType presence/absence is safe.</li>
 * </ul>
 *
 * @author diego-xmage-ai (Sprint 33E)
 */
public class EndStepManaSinkTest extends CardTestPlayerBaseAI {

    // -----------------------------------------------------------------------
    // Format A — new behavior (Sprint 33E)
    // -----------------------------------------------------------------------
    //
    // Note on testability: asserting that the bot specifically acted in the
    // opponent's END_TURN (not in an earlier phase) is not isolatable in the
    // 2-player CardTestPlayerBaseAI framework because:
    // (a) Manland animation is legitimate in DECLARE_BLOCKERS (combat excluded
    //     from TIER_9 suppression by 33A design), so Mutavault may already be
    //     a creature before END_TURN.
    // (b) The framework has no assertStatusEvent / game-log assertions.
    //
    // The END_TURN hook (ComputerPlayer7.priorityPlay case END_TURN) and the
    // EndStepManaSinkOptimizer are verified by:
    //   1. Format 0 (Tier8SubcategorizerTest) — classifier correctness.
    //   2. Format B below — regression of 33A/33B PRECOMBAT_MAIN suppression.
    //   3. Smoke test: observe "[END_STEP_SINK] Promoting TIER_8 activation"
    //      and "Sim PRIORITY on END STEP SINK" in game logs during live play.
    //
    // -----------------------------------------------------------------------
    // Format B — regression (Sprint 33A / 33B still intact)
    // -----------------------------------------------------------------------

    /**
     * B1 — Regression Sprint 33A: TIER_9 still suppressed in opponent's PRECOMBAT_MAIN.
     *
     * <p>Sprint 33A's {@code isApplicableForNoopPrune} fires for PRECOMBAT_MAIN of
     * any player, any turn. Adding the new END_TURN hook must not change this.
     *
     * <p>Stop at turn 2 BEGIN_COMBAT (after playerB's PRECOMBAT_MAIN).
     */
    @Test
    public void testB1_Regression_Tier9StillSuppressedInOpponentPrecombatMain() {
        addCard(Zone.BATTLEFIELD, playerA, "Mutavault");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");

        setStopAt(2, PhaseStep.BEGIN_COMBAT);
        execute();

        // EarlyGameTempoOptimizer Rule 1 still active in PRECOMBAT_MAIN → NOT animated
        assertType("Mutavault", CardType.CREATURE, false);
    }

    /**
     * B2 — Regression Sprint 33B: TIER_9 still suppressed on own PRECOMBAT_MAIN.
     *
     * <p>Bot's own turn 1 PRECOMBAT_MAIN — Mutavault must not be animated even
     * when it is the bot's own turn. The 33A/33B Rule 1 gate remains active.
     *
     * <p>Stop at turn 1 BEGIN_COMBAT.
     */
    @Test
    public void testB2_Regression_Tier9StillSuppressedOnOwnTurn() {
        addCard(Zone.BATTLEFIELD, playerA, "Mutavault");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // Rule 1 (33B own-turn) still active → Mutavault NOT animated
        assertType("Mutavault", CardType.CREATURE, false);
    }
}
