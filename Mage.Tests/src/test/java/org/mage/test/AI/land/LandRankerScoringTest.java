package org.mage.test.AI.land;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

/**
 * Sprint 32 — Option A QA layer: fast behavioral tests for LandRanker heuristics.
 *
 * Each test sets up a controlled hand + board and verifies which land the full-AI
 * player plays, indirectly validating LandRanker.scoreLand() ranking. Tests use a
 * standard two-player duel (no commander zone needed here; commander scenarios are
 * in LandSelectionAITest).
 *
 * Run after each sprint to catch regressions in land-selection logic before real-game
 * validation.
 */
public class LandRankerScoringTest extends CardTestPlayerBaseAI {

    /**
     * Island must be preferred over Forest when the hand contains a blue spell
     * (Counterspell {U}{U}). Island fixes the missing blue color; Forest provides
     * green which Counterspell doesn't need.
     *
     * Expected scores (rough):
     *   Island  : +200 (bucket-3 color-fix for U, 0 sources on board) + 10 (diversity) = 210
     *   Forest  : +0   (no spell needs G) + 10 (diversity) = 10
     */
    @Test
    public void testIslandPreferredOverForestForBlueSpell() {
        addCard(Zone.HAND, playerA, "Island");
        addCard(Zone.HAND, playerA, "Forest");
        addCard(Zone.HAND, playerA, "Counterspell"); // {U}{U}

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Island", 1);
        assertPermanentCount(playerA, "Forest", 0);
    }

    /**
     * Plains (untapped) must be preferred over Temple Garden ({W}{G} shock land,
     * ETB-tapped unless pay 2 life) when Savannah Lions ({W}) is in hand.
     *
     * Plains unlocks Savannah Lions this turn (untapped → UNTAPPED_SPELL_CASTABLE_BONUS
     * fires). LandRanker treats conditional-ETB-tapped lands pessimistically (no untapped
     * bonus for Temple Garden). Both fix white, but Plains wins on the untapped bonus.
     *
     * Observable: both Plains AND Savannah Lions end up on the battlefield.
     */
    @Test
    public void testPlainsPreferredOverETBTappedDualForImmediateCast() {
        addCard(Zone.HAND, playerA, "Plains");
        addCard(Zone.HAND, playerA, "Temple Garden"); // {W}{G}, ETB-tapped-unless
        addCard(Zone.HAND, playerA, "Savannah Lions"); // {W}, CMC 1

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // AI plays Plains (untapped → casts Savannah Lions), not Temple Garden
        assertPermanentCount(playerA, "Plains", 1);
        assertPermanentCount(playerA, "Savannah Lions", 1);
        assertPermanentCount(playerA, "Temple Garden", 0);
    }

    /**
     * Strip Mine must be held (not played) when the opponent controls only basic lands.
     * isUtilityLand(Strip Mine) = true; hasActiveUtilityTrigger = false (no nonbasics
     * on opponent board) → score = UTILITY_NO_TRIGGER_SCORE (-1000).
     * Forest scores +10 (diversity), so Forest is played instead.
     */
    @Test
    public void testStripMineHeldWhenNoNonbasicTargets() {
        addCard(Zone.HAND, playerA, "Strip Mine");
        addCard(Zone.HAND, playerA, "Forest");

        // Opponent board: only basic lands → Strip Mine has no valid target
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 2);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Forest", 1);
        assertPermanentCount(playerA, "Strip Mine", 0);
    }

    /**
     * Exotic Orchard must be preferred over Forest when the opponent controls a Plains
     * and the hand contains Savannah Lions {W}.
     *
     * AnyColorLandsProduceManaAbility.getNetMana(game) resolves dynamically from the
     * battlefield. Before this fix, getProducedColors() fell back to card.getColorIdentity()
     * which is empty for Exotic Orchard → score 0. After the fix, it calls getNetMana(game)
     * and sees W from the opponent's Plains → color-fix bonus for Savannah Lions fires.
     *
     * Scores (approx):
     *   Exotic Orchard: +200 (W fixes Savannah Lions, 0 W sources on board)
     *                   + untapped bonus if unlocks Lions this turn
     *                   + 10 diversity = 210+
     *   Forest         : +10 diversity only (G doesn't fix Savannah Lions {W}) = 10
     */
    @Test
    public void testExoticOrchardPreferredOverForestWhenOpponentHasWhiteLand() {
        addCard(Zone.HAND, playerA, "Exotic Orchard");
        addCard(Zone.HAND, playerA, "Forest");
        addCard(Zone.HAND, playerA, "Savannah Lions"); // {W} — signals white need

        // Opponent controls a Plains → Exotic Orchard can produce W
        addCard(Zone.BATTLEFIELD, playerB, "Plains");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Exotic Orchard", 1);
        assertPermanentCount(playerA, "Forest", 0);
    }

    /**
     * Command Tower without a commander in the command zone produces no mana
     * (getProducedColors returns empty). Without colors, its color-fix score is 0
     * and it has no utility trigger → scores 0.
     * Forest scores +10 (diversity), so Forest is played instead.
     *
     * This protects against accidental "Command Tower always wins" bugs.
     */
    @Test
    public void testCommandTowerScoresZeroWithoutCommander() {
        addCard(Zone.HAND, playerA, "Command Tower");
        addCard(Zone.HAND, playerA, "Forest");
        // No Zone.COMMAND card → getCommanderFromZone returns null

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Forest", 1);
        assertPermanentCount(playerA, "Command Tower", 0);
    }
}
