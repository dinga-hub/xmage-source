package org.mage.test.AI.land;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer7;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestCommander4Players;

/**
 * Sprint 32 — Option B QA layer: full AI integration tests for land-selection logic.
 *
 * Uses a Commander 4-player game so Zone.COMMAND is available for commander setup.
 * PlayerA is a full-simulation AI (auto-plays). PlayerB/C/D are passive test players
 * used only to provide board/graveyard state that influences the AI's decisions.
 *
 * Tests cover: Command Tower with commander, Strip Mine gating, Bojuka Bog gating,
 * and the library-search hook (Cultivate chooses missing-color land).
 *
 * Run after each sprint alongside LandRankerScoringTest to catch regressions.
 */
public class LandSelectionAITest extends CardTestCommander4Players {

    /** PlayerA runs as a full-simulation AI; all others use passive test behaviour. */
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            TestPlayer p = new TestPlayer(new TestComputerPlayer7(name, rangeOfInfluence, 6));
            p.setAIPlayer(true);
            return p;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    // -------------------------------------------------------------------------
    // Command Tower tests
    // -------------------------------------------------------------------------

    /**
     * Command Tower must be preferred over Forest when a {W}{U}{B} commander
     * (Oloro, Ageless Ascetic) is in the command zone.
     *
     * getProducedColors detects CommanderColorIdentityManaAbility at runtime and
     * returns Oloro's three colors {W, U, B}. Each of those colors scores +400 in
     * bucket-2 (commander colors, < 2 sources on board), giving Command Tower ≈ 1200.
     * Forest (produces G, not in commander colors) scores ≈ -20 (basic in multicolor
     * deck penalty) and loses.
     */
    @Test
    public void testCommandTowerPreferredWithCommander() {
        // Oloro {3}{W}{U}{B}: W, U, B commander identity
        addCard(Zone.COMMAND, playerA, "Oloro, Ageless Ascetic");

        // Board: provides G + B but not W or U → Command Tower fills those gaps
        addCard(Zone.BATTLEFIELD, playerA, "Forest");
        addCard(Zone.BATTLEFIELD, playerA, "Swamp");

        // Hand: only one land choice per turn — Command Tower vs Forest
        addCard(Zone.HAND, playerA, "Command Tower");
        addCard(Zone.HAND, playerA, "Forest");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // Command Tower played: BF has 1 Command Tower + 1 pre-existing Forest + 1 Swamp.
        // The hand Forest was NOT played (stays in hand) → Forest count on BF remains 1.
        assertPermanentCount(playerA, "Command Tower", 1);
        assertPermanentCount(playerA, "Forest", 1); // only the pre-existing setup Forest
    }

    // -------------------------------------------------------------------------
    // Strip Mine gating tests
    // -------------------------------------------------------------------------

    /**
     * Strip Mine must be played (not held) when an opponent controls a nonbasic land.
     *
     * hasActiveUtilityTrigger detects SacrificeSourceCost in Strip Mine's activated
     * ability AND confirms a nonbasic land (Command Tower) on the opponent's board →
     * returns UTILITY_TRIGGER_BONUS (400). Forest scores ≈ 10 → Strip Mine wins.
     */
    @Test
    public void testStripMinePlaysWhenOpponentHasNonbasic() {
        addCard(Zone.HAND, playerA, "Strip Mine");
        addCard(Zone.HAND, playerA, "Forest");

        // PlayerB controls a nonbasic land → Strip Mine trigger is active
        addCard(Zone.BATTLEFIELD, playerB, "Command Tower");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Strip Mine", 1);
        assertPermanentCount(playerA, "Forest", 0);
    }

    // -------------------------------------------------------------------------
    // Bojuka Bog gating tests
    // -------------------------------------------------------------------------

    /**
     * Bojuka Bog must be held (scored at UTILITY_NO_TRIGGER_SCORE = -1000) when the
     * opponent's graveyard is below the GRAVEYARD_TRIGGER_THRESHOLD (4 cards).
     * Forest scores +10 → Forest is played instead.
     */
    @Test
    public void testBojukaBogHeldWhenSmallGraveyard() {
        addCard(Zone.HAND, playerA, "Bojuka Bog");
        addCard(Zone.HAND, playerA, "Forest");

        // PlayerB graveyard: 3 cards (< threshold of 4)
        addCard(Zone.GRAVEYARD, playerB, "Balduvian Bears");
        addCard(Zone.GRAVEYARD, playerB, "Grizzly Bears");
        addCard(Zone.GRAVEYARD, playerB, "Llanowar Elves");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Forest", 1);
        assertPermanentCount(playerA, "Bojuka Bog", 0);
    }

    /**
     * Bojuka Bog must be played when the opponent's graveyard reaches the
     * GRAVEYARD_TRIGGER_THRESHOLD (≥ 4 cards). hasActiveUtilityTrigger returns true →
     * score = UTILITY_TRIGGER_BONUS (400) + diversity (10) = 410.
     * Forest scores ≈ 10 → Bojuka Bog wins.
     */
    @Test
    public void testBojukaBogPlayedWhenLargeGraveyard() {
        addCard(Zone.HAND, playerA, "Bojuka Bog");
        addCard(Zone.HAND, playerA, "Forest");

        // PlayerB graveyard: 4 cards (≥ threshold)
        addCard(Zone.GRAVEYARD, playerB, "Balduvian Bears");
        addCard(Zone.GRAVEYARD, playerB, "Grizzly Bears");
        addCard(Zone.GRAVEYARD, playerB, "Llanowar Elves");
        addCard(Zone.GRAVEYARD, playerB, "Elvish Mystic");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Bojuka Bog", 1);
        assertPermanentCount(playerA, "Forest", 0);
    }

    // -------------------------------------------------------------------------
    // Library-search hook: Cultivate
    // -------------------------------------------------------------------------

    /**
     * When the AI resolves Cultivate and must choose a basic land from the library,
     * the ComputerPlayer6.chooseTarget override fires LandSearchSelector, which ranks
     * Plains highest (Soul Warden {W} needs white; no white source on board).
     *
     * Setup: library has exactly Plains and Forest to make the choice clear.
     * The AI has GG on board to cast Cultivate (2G). After resolution, Plains should
     * be on the battlefield tapped (one-land form of Cultivate effect when only 1
     * land is chosen by the hook).
     */
    @Test
    public void testCultivateChoosesMissingColor() {
        // Library: the two candidates Cultivate will search among
        addCard(Zone.LIBRARY, playerA, "Plains");
        addCard(Zone.LIBRARY, playerA, "Forest");
        skipInitShuffling();

        // Hand: Cultivate + a white spell to signal W need
        addCard(Zone.HAND, playerA, "Cultivate"); // {2}{G}
        addCard(Zone.HAND, playerA, "Soul Warden"); // {W} — indicates white is needed

        // Board: 2 Forests provide GG to cast Cultivate
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // Plains should have been fetched onto the battlefield tapped
        assertPermanentCount(playerA, "Plains", 1);
    }
}
