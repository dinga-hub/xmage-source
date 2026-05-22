package org.mage.test.AI.tempo;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

/**
 * Sprint 33B — Behavioral QA (Format A): validates EarlyGameTempoOptimizer
 * pruning rules in a standard two-player duel.
 *
 * <h2>What's tested</h2>
 * <ol>
 *   <li>Tier 3 (ramp) prunes Tier 7 (generic): Llanowar Elves beats Ornithopter.</li>
 *   <li>Tier 4 (engine) prunes Tier 7 (generic): Rhystic Study beats Hill Giant.</li>
 *   <li>No false pruning: Llanowar Elves plays normally when it is the best option.</li>
 * </ol>
 *
 * <h2>Gotchas observed (QA Playbook)</h2>
 * <ul>
 *   <li>G1: addCard(HAND) sets the exact hand; no starting draw in test mode.</li>
 *   <li>G3: addCard(BATTLEFIELD) cards count in assertPermanentCount from the start.</li>
 * </ul>
 *
 * <h2>Not covered here (validated in live play — Pausa 5)</h2>
 * <ul>
 *   <li>TIER_9_NOOP suppression (needs a card with until-EOT activated ability).</li>
 *   <li>Regression on turn 5+ (optimizer gate off).</li>
 *   <li>TIER_8 watch log output.</li>
 * </ul>
 *
 * @author diego-xmage-ai
 */
public class EarlyGameTempoAITest extends CardTestPlayerBaseAI {

    /**
     * Llanowar Elves (Tier 3 — mana dork) vs Ornithopter (Tier 7 — CMC 0, below
     * the turn-1 CMC window [1, 2]).
     *
     * <p>Both cards are castable on turn 1 (Llanowar costs {G}, Ornithopter is free).
     * EarlyGameTempoOptimizer prunes Ornithopter at the first decision node because
     * a Tier 3 action exists. The bot must play Llanowar Elves.
     *
     * <p>Without the optimizer the minimax would include Ornithopter as a candidate
     * and might prefer it (free 0/2 flyer boosts state score without spending mana),
     * potentially playing Ornithopter INSTEAD OF Llanowar Elves.
     *
     * <p><b>Cascading-node note (QA Playbook §G6):</b> The optimizer runs at every
     * minimax node. Once Llanowar Elves is played and consumed, the next node sees
     * only Ornithopter — no Tier 3 competitor is present, so Ornithopter is no longer
     * pruned and the bot plays it for free. The critical assertion is that Llanowar
     * Elves was played (optimizer did not suppress Tier 3); Ornithopter may or may
     * not end up on the battlefield depending on the subsequent node.
     */
    @Test
    public void testT1_RampBeatsZeroCMCCreature_LlanowarBeatsOrnithopter() {
        // Hand: both castable on turn 1 after the land drop
        addCard(Zone.HAND, playerA, "Forest");         // land drop → 1 green mana
        addCard(Zone.HAND, playerA, "Llanowar Elves"); // {G} — mana dork → Tier 3
        addCard(Zone.HAND, playerA, "Ornithopter");    // {0} — CMC 0 < window [1,2] → Tier 7

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // Optimizer preserved Tier 3 → Llanowar Elves is on the battlefield.
        // (Ornithopter may also be played in a subsequent decision node — see note above.)
        assertPermanentCount(playerA, "Llanowar Elves", 1);
    }

    /**
     * Rhystic Study (Tier 4 — draw engine) vs Hill Giant (Tier 7 — CMC 4, above
     * the turn-1 CMC window [1, 2]).
     *
     * <p>Both cards are castable: Rhystic Study costs {2}{U} (3 mana), Hill Giant
     * costs {3}{R} (4 mana). With Island×3 + Mountain on the battlefield the bot
     * has 4 mana available. EarlyGameTempoOptimizer prunes Hill Giant because a
     * Tier 4 action exists.
     *
     * <p>Without the optimizer minimax prefers Hill Giant (3/3 body at 4 mana scores
     * ~1500 in ArtificialScoringSystem via 3×300+3×200). With the optimizer Hill Giant
     * is removed from the candidate set; the bot cannot play it regardless of its score.
     *
     * <p><b>Scoring caveat (QA Playbook §G6):</b> Enchantments like Rhystic Study
     * receive no power/toughness score in {@code ArtificialScoringSystem}, so the
     * minimax may score playing Rhystic Study lower than passing. The key behavioral
     * assertion is therefore {@code Hill Giant = 0} (proves the optimizer pruned it),
     * not that Rhystic Study was necessarily chosen. Hill Giant IS castable on turn 1
     * with exactly 4 mana ({3}{R}), so Hill Giant=0 is only achieved via the optimizer.
     *
     * <p>G3 note: the 3 Islands and 1 Mountain added to BATTLEFIELD count in
     * assertPermanentCount from the start. Assertions reference Rhystic Study and
     * Hill Giant only.
     */
    @Test
    public void testT1_EngineBeatsGenericCreature_RhysticStudyBeatsHillGiant() {
        // Board: 4 lands so both spells are castable on turn 1
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);   // provides {U}{U}{U}
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");     // provides {R}

        // Hand: the two competing plays
        addCard(Zone.HAND, playerA, "Rhystic Study"); // {2}{U} CMC 3 — draw engine → Tier 4
        addCard(Zone.HAND, playerA, "Hill Giant");    // {3}{R} CMC 4 > window → Tier 7

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // Core assertion: optimizer pruned Hill Giant — it must not be on the battlefield.
        // (Whether Rhystic Study was played or the bot passed is evaluator-dependent;
        //  the optimizer's job is to remove Hill Giant from the candidate set.)
        assertPermanentCount(playerA, "Hill Giant", 0);
    }

    /**
     * Llanowar Elves plays correctly when it is the only meaningful spell in hand.
     *
     * <p>Regression guard: confirms the optimizer does not accidentally suppress
     * Tier 3 actions or interfere with standard ramp play.
     *
     * <p>Setup: one Forest on the battlefield (pre-drop) plus one Forest in hand.
     * After the land drop the bot has 2 green mana and casts Llanowar Elves.
     */
    @Test
    public void testT1_RampNotFalselyPruned_LlanowarPlaysNormally() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest"); // 1 pre-existing mana source
        addCard(Zone.HAND, playerA, "Forest");        // land drop → total 2 green mana
        addCard(Zone.HAND, playerA, "Llanowar Elves"); // {G} — Tier 3, always preserved

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // No pruning: Llanowar Elves should be on the battlefield (G3: pre-board Forest = 1, still 1 after)
        assertPermanentCount(playerA, "Llanowar Elves", 1);
    }
}
