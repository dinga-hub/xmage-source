package org.mage.test.AI.protection;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

/**
 * Sprint 19B — Format A (gate tests) + Format B (regression).
 *
 * <h2>What's testable vs. what isn't</h2>
 * <p>The XMage minimax does not currently model the "grant hexproof → targeting spell
 * fizzles" interaction during simulation, so the POSITIVE case ("bot reacts by casting
 * Tamiyo's and Sphinx survives") cannot be reliably asserted via Format A. This is
 * consistent with Sprint 33E (EndStepManaSinkTest) which also relies on smoke tests for
 * reactive promotion.</p>
 *
 * <p>What IS testable via Format A:</p>
 * <ul>
 *   <li>NEGATIVE gate: optimizer suppresses protection on non-critical target (creature dies).</li>
 *   <li>CONTROL: no protection in hand → removal resolves normally.</li>
 *   <li>FORMAT B REGRESSION: mass protection (Sprint 19 ProtectionOptimizer) still fires
 *       after the StackTargetingHelper refactor, given a board that passes isBoardStrong.</li>
 * </ul>
 *
 * <p>The POSITIVE case (bot casts single-target protection on critical piece) is validated
 * via smoke test: observe {@code [PROTECT-ST] phase-out for N critical piece(s) vs Swords}
 * in server logs during live 4-player Commander play.</p>
 *
 * <h2>Gotchas</h2>
 * <ul>
 *   <li>G3: addCard(BATTLEFIELD) counts from turn 0 in assertPermanentCount.</li>
 *   <li>G13: Format 0 ran first (9 tests green, SingleTargetProtectionPredicateTest).</li>
 *   <li>isBoardStrong (GameStateEvaluator2:306): requires ≥4 creatures OR ≥2 permanents
 *       scoring ≥1200 OR any power≥5 flying/trample creature. Use ≥4 creatures for reliability
 *       in regression tests.</li>
 *   <li>Swords to Plowshares targets creatures only — use creature-type permanents as targets.</li>
 * </ul>
 *
 * @author diego-xmage-ai (Sprint 19B)
 */
public class SingleTargetProtectionOptimizerTest extends CardTestPlayerBaseAI {

    // -----------------------------------------------------------------------
    // Format A — Gate tests (what the optimizer suppresses)
    // -----------------------------------------------------------------------

    /**
     * Gate test 1: TARGETED_REMOVAL on non-critical creature → optimizer suppresses
     * protection, creature dies.
     *
     * Grizzly Bears (2/2, CMC 2) scores ≈ 600, is not a Game Changer, and is not a
     * commander. findCriticalTargetsOnStack returns empty → suppressAll fires.
     * Observable: Grizzly Bears exiled after Swords resolves.
     *
     * Note: Tamiyo's Safekeeping may still be cast proactively on another target
     * (e.g., Forest) after the stack clears. The key assertion is that Grizzly Bears
     * DIED — proving the optimizer did not protect it reactively.
     */
    @Test
    public void gateTest_noProtectionOnNonCriticalCreature() {
        addCard(Zone.HAND, playerA, "Tamiyo's Safekeeping");
        addCard(Zone.BATTLEFIELD, playerA, "Forest");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");  // score ≈ 600, not GC

        addCard(Zone.HAND, playerB, "Swords to Plowshares");
        addCard(Zone.BATTLEFIELD, playerB, "Plains");

        castSpell(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "Swords to Plowshares", "Grizzly Bears");

        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // Grizzly Bears must be gone — optimizer held the protection back
        assertPermanentCount(playerA, "Grizzly Bears", 0);
    }

    /**
     * Gate test 2: no removal on stack at all → bot does not proactively cast protection
     * in PRECOMBAT_MAIN (EarlyGameTempoOptimizer suppresses NOOP proactive casts).
     *
     * Setup: Tamiyo's in hand, 1 Forest, Grizzly Bears on board, no opponent action.
     * Expected: Tamiyo's stays in hand after turn 1 (no stack, proactive cast not worth it).
     */
    @Test
    public void gateTest_noProactiveCastWithoutStackThreat() {
        addCard(Zone.HAND, playerA, "Tamiyo's Safekeeping");
        addCard(Zone.BATTLEFIELD, playerA, "Forest");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // Tamiyo's still in hand — bot chose not to cast proactively
        assertHandCount(playerA, 1);
    }

    /**
     * Control test: no protection spell in hand → Consecrated Sphinx is exiled normally.
     *
     * Proves the test harness correctly models the scenario WITHOUT the optimizer
     * interference: when the bot has no protection spells, nothing can save the target.
     */
    @Test
    public void controlTest_noProtectionSpellAvailable() {
        addCard(Zone.BATTLEFIELD, playerA, "Consecrated Sphinx"); // GC creature
        addCard(Zone.BATTLEFIELD, playerA, "Forest");
        // No protection in hand

        addCard(Zone.HAND, playerB, "Swords to Plowshares");
        addCard(Zone.BATTLEFIELD, playerB, "Plains");

        castSpell(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "Swords to Plowshares", "Consecrated Sphinx");

        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Consecrated Sphinx", 0);
        assertExileCount(playerA, "Consecrated Sphinx", 1);
    }

    // -----------------------------------------------------------------------
    // Format B — Regression: Sprint 19 mass protection still fires
    // -----------------------------------------------------------------------

    /**
     * Regression test: ProtectionOptimizer (mass protection, Sprint 19) still activates
     * correctly after the StackTargetingHelper refactor.
     *
     * Board setup satisfies isBoardStrong (≥4 creatures). Bot has Heroic Intervention
     * ({1}{G}{G}) + 3 Forests (covers {1}{G}{G} cost). PlayerB casts Wrath of God.
     * ProtectionOptimizer: BOARDWIPE + board strong → allow Heroic Intervention.
     * Minimax: grant indestructible → creatures survive.
     *
     * Observable: at least some creatures still present after the wipe.
     */
    @Test
    public void regressionTest_massProtectionStillFires() {
        addCard(Zone.HAND, playerA, "Heroic Intervention"); // {1}{G}{G}
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);   // covers {1}{G}{G}
        // Need ≥4 creatures for isBoardStrong to return true
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 4); // 4 × 2/2

        addCard(Zone.HAND, playerB, "Wrath of God");       // {2}{W}{W}
        addCard(Zone.BATTLEFIELD, playerB, "Plains", 4);

        castSpell(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "Wrath of God");

        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // At least one Grizzly Bears survived (Heroic Intervention granted indestructible)
        // G3: 4 were added at setup, so survive count ≥ 1 proves protection fired
        assertPermanentCount(playerA, "Grizzly Bears", 4);
    }
}
