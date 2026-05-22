package org.mage.test.AI.tempo;

import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.common.continuous.BecomesCreatureSourceEffect;
import mage.abilities.effects.common.continuous.BoostSourceEffect;
import mage.constants.Duration;
import mage.game.permanent.token.custom.CreatureToken;
import mage.player.ai.tempo.TempoCategory;
import mage.player.ai.tempo.TempoClassifier;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Sprint 33A — Format 0 (pure unit): validates that {@link TempoClassifier}
 * correctly classifies until-end-of-turn activated abilities as
 * {@link TempoCategory#TIER_9_NOOP}.
 *
 * <p>Tests the structural fingerprint in {@code isAllEffectsEndOfTurn}: every
 * effect must be a {@link mage.abilities.effects.ContinuousEffect} with
 * {@link Duration#EndOfTurn}.
 *
 * <p>No Game object required: {@link TempoClassifier#classify} only consults
 * the game for SpellAbility classification. ActivatedAbility classification
 * relies purely on the ability's effect list, so game=null, botId=null is safe.
 *
 * @author diego-xmage-ai (Sprint 33A)
 */
public class TempoClassifierActivationTest {

    // -----------------------------------------------------------------------
    // TIER_9_NOOP positive cases (should be suppressed)
    // -----------------------------------------------------------------------

    /**
     * Manland animation: BecomesCreatureSourceEffect with Duration.EndOfTurn.
     * Real-world example: Mutavault {1} → 2/2 creature until end of turn.
     * Expected: TIER_9_NOOP.
     */
    @Test
    public void testManlandAnimationIsTier9Noop() {
        SimpleActivatedAbility ability = new SimpleActivatedAbility(
                new BecomesCreatureSourceEffect(
                        new CreatureToken(2, 2, "2/2 creature"),
                        /* retainType */ null,   // null = loses previous types (valid per Javadoc)
                        Duration.EndOfTurn),
                new GenericManaCost(1));

        TempoCategory result = TempoClassifier.classify(ability, /* game */ null, /* botId */ null);

        assertEquals("Manland animation (until EOT) must be TIER_9_NOOP",
                TempoCategory.TIER_9_NOOP, result);
    }

    /**
     * Pump +N/+N until end of turn: BoostSourceEffect with Duration.EndOfTurn.
     * Real-world example: {R}: +1/+1 until end of turn.
     * Expected: TIER_9_NOOP.
     */
    @Test
    public void testPumpUntilEotIsTier9Noop() {
        SimpleActivatedAbility ability = new SimpleActivatedAbility(
                new BoostSourceEffect(2, 2, Duration.EndOfTurn),
                new GenericManaCost(1));

        TempoCategory result = TempoClassifier.classify(ability, /* game */ null, /* botId */ null);

        assertEquals("Pump until EOT must be TIER_9_NOOP",
                TempoCategory.TIER_9_NOOP, result);
    }

    // -----------------------------------------------------------------------
    // TIER_8_USEFUL_ACTIVATION negative case (must NOT be suppressed)
    // -----------------------------------------------------------------------

    /**
     * Pump with WhileOnBattlefield duration (persistent).
     * Represents activations whose effects outlast the turn — should not be suppressed.
     * Expected: TIER_8_USEFUL_ACTIVATION (not TIER_9_NOOP).
     */
    @Test
    public void testPersistentBoostIsNotTier9Noop() {
        SimpleActivatedAbility ability = new SimpleActivatedAbility(
                new BoostSourceEffect(1, 1, Duration.WhileOnBattlefield),
                new GenericManaCost(2));

        TempoCategory result = TempoClassifier.classify(ability, /* game */ null, /* botId */ null);

        assertNotEquals("Persistent pump (WhileOnBattlefield) must NOT be TIER_9_NOOP",
                TempoCategory.TIER_9_NOOP, result);
        assertEquals("Persistent pump must be TIER_8_USEFUL_ACTIVATION",
                TempoCategory.TIER_8_USEFUL_ACTIVATION, result);
    }
}
