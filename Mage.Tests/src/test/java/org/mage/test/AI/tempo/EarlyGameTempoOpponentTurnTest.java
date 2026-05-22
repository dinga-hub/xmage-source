package org.mage.test.AI.tempo;

import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

/**
 * Sprint 33A — Format A (behavioral duel): validates the generalized
 * TIER_9_NOOP suppression introduced in 33A.
 *
 * <h2>What is tested</h2>
 * <ol>
 *   <li><b>Opponent turn (turn 2)</b>: bot has Mutavault; during playerB's
 *       PRECOMBAT_MAIN the bot must not animate it. Validating that
 *       {@code isApplicableForNoopPrune} fires independently of active player.</li>
 *   <li><b>Turn beyond EARLY_GAME_TURN_LIMIT (game turn 5)</b>: bot has Mutavault
 *       on its own PRECOMBAT_MAIN. 33B's old gate ({@code turnNum ≤ 4}) would have
 *       let this through; Rule 1 now fires on every main phase regardless of turn.</li>
 *   <li><b>Regression (game turn 3)</b>: 33B behaviour on own early-game turn
 *       must be preserved — Mutavault still suppressed.</li>
 * </ol>
 *
 * <h2>Assert strategy</h2>
 * Mutavault animated = CardType.CREATURE added.
 * Each test asserts {@code assertType("Mutavault", CardType.CREATURE, false)}
 * (i.e., Mutavault must NOT be a creature at the stop point).
 *
 * <h2>Gotchas</h2>
 * <ul>
 *   <li>G3: {@code addCard(Zone.BATTLEFIELD)} cards count in
 *       {@code assertPermanentCount} from t=0.</li>
 *   <li>G12: no assertions on CMC=0 cards (not used in this test).</li>
 *   <li>Turn numbering: game turn 1/3/5/7 = playerA's turns; 2/4/6 = playerB's.</li>
 * </ul>
 *
 * @author diego-xmage-ai (Sprint 33A)
 */
public class EarlyGameTempoOpponentTurnTest extends CardTestPlayerBaseAI {

    /**
     * Test 1 — Opponent turn (game turn 2, playerB's PRECOMBAT_MAIN).
     *
     * <p>Bot (playerA) has Mutavault + Plains on the battlefield.
     * It is playerB's turn; playerB does nothing. The bot should not
     * animate Mutavault during playerB's main phase even if it receives
     * priority (empty stack). Rule 1 ({@code isApplicableForNoopPrune})
     * fires for PRECOMBAT_MAIN of any player.
     *
     * <p>Stop point: turn 2 BEGIN_COMBAT (after playerB's PRECOMBAT_MAIN).
     * Mutavault animated during turn 2 PRECOMBAT_MAIN would still show
     * CardType.CREATURE at BEGIN_COMBAT.
     */
    @Test
    public void testOpponentTurn_MutavaultNotAnimated() {
        // PlayerA board: Mutavault + 1 Plains (enough mana to activate {1})
        addCard(Zone.BATTLEFIELD, playerA, "Mutavault");
        addCard(Zone.BATTLEFIELD, playerA, "Plains"); // taps for {W} → generic {1}

        // Stop at the END of playerB's PRECOMBAT_MAIN window (at BEGIN_COMBAT)
        setStopAt(2, PhaseStep.BEGIN_COMBAT);
        execute();

        // Mutavault must NOT be a creature — bot must not have animated it
        assertType("Mutavault", CardType.CREATURE, false);
    }

    /**
     * Test 2 — Own turn beyond EARLY_GAME_TURN_LIMIT (game turn 5).
     *
     * <p>This is the primary 33A regression: Sprint 33B's old gate was
     * {@code turnNum ≤ EARLY_GAME_TURN_LIMIT (4)}. On game turn 5
     * (playerA's third player turn) the optimizer was INACTIVE — Mutavault
     * could be animated freely.
     *
     * <p>After 33A, {@code isApplicableForNoopPrune} fires regardless of
     * turn number. Bot must suppress Mutavault animation on game turn 5.
     *
     * <p>Setup: only Mutavault + Plains available. Bot has no castable
     * spells in hand — the only actionable option is to animate Mutavault
     * or pass. Without the fix the bot animates; with the fix it passes.
     */
    @Test
    public void testBeyondTurnLimit_MutavaultNotAnimated() {
        // PlayerA board: only Mutavault (2/2 if animated) + 1 mana source
        addCard(Zone.BATTLEFIELD, playerA, "Mutavault");
        addCard(Zone.BATTLEFIELD, playerA, "Plains"); // generic {1} to activate

        // Game turn 5 = playerA's 3rd player turn (playerA acts on odd turns)
        // Stop at BEGIN_COMBAT: any PRECOMBAT_MAIN animation would be visible here
        setStopAt(5, PhaseStep.BEGIN_COMBAT);
        execute();

        // Without 33A: optimizer OFF on turn 5 → bot would animate Mutavault → FAIL here
        // With 33A:    Rule 1 always active in main phase → bot passes → PASS here
        assertType("Mutavault", CardType.CREATURE, false);
    }

    /**
     * Test 3 — Regression: own turn within early game (game turn 3).
     *
     * <p>Ensures that Sprint 33B suppression on own early-game turns is
     * preserved. The split of {@code isApplicable()} must not have broken
     * the original Rule 1 path for game turns ≤ 4.
     *
     * <p>Setup: identical to test 2 but stops at game turn 3 (playerA's
     * second player turn).
     */
    @Test
    public void testEarlyGameOwnTurn_MutavaultStillSuppressed() {
        // PlayerA board: Mutavault + 1 Plains
        addCard(Zone.BATTLEFIELD, playerA, "Mutavault");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");

        // Game turn 3 = playerA's 2nd player turn
        setStopAt(3, PhaseStep.BEGIN_COMBAT);
        execute();

        // Rule 1 was active here in 33B and must still be active after 33A refactor
        assertType("Mutavault", CardType.CREATURE, false);
    }
}
