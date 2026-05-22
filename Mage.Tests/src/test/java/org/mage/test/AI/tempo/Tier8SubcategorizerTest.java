package org.mage.test.AI.tempo;

import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.effects.common.DestroyTargetEffect;
import mage.abilities.effects.common.continuous.BoostSourceEffect;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.player.ai.tempo.Tier8Subcategory;
import mage.player.ai.tempo.Tier8Subcategorizer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Sprint 33E — Format 0 (pure unit): validates that {@link Tier8Subcategorizer}
 * maps activated ability outcomes to the correct {@link Tier8Subcategory}.
 *
 * <p>No Game or Player setup required: Tier8Subcategorizer only inspects
 * the ability's effect outcomes, so game=null is safe.
 *
 * @author diego-xmage-ai (Sprint 33E)
 */
public class Tier8SubcategorizerTest {

    /**
     * DrawCardSourceControllerEffect → Outcome.DrawCard → DRAW.
     * Representative card: Arcanis the Omnipotent ({T}: Draw three cards).
     */
    @Test
    public void testDrawEffectIsDraw() {
        SimpleActivatedAbility ability = new SimpleActivatedAbility(
                new DrawCardSourceControllerEffect(3),
                new GenericManaCost(0));

        Tier8Subcategory result = Tier8Subcategorizer.classify(ability);
        assertEquals("DrawCard outcome must map to DRAW", Tier8Subcategory.DRAW, result);
    }

    /**
     * DestroyTargetEffect has Outcome.DestroyPermanent → REMOVAL.
     * Representative card: any creature with tap-destroy activated ability.
     */
    @Test
    public void testDestroyEffectIsRemoval() {
        SimpleActivatedAbility ability = new SimpleActivatedAbility(
                new DestroyTargetEffect(),
                new GenericManaCost(2));

        Tier8Subcategory result = Tier8Subcategorizer.classify(ability);
        assertEquals("DestroyPermanent outcome must map to REMOVAL", Tier8Subcategory.REMOVAL, result);
    }

    /**
     * BoostSourceEffect with Duration.Custom (persistent) → Outcome.BoostCreature.
     * At this point TIER_9 (until-EOT) has already been pruned by TempoClassifier,
     * so BoostCreature here means a persistent counter effect.
     * Representative card: Mikaeus, the Lunarch ({X}: put +1/+1 counters).
     */
    @Test
    public void testPersistentBoostIsPersistentCounter() {
        SimpleActivatedAbility ability = new SimpleActivatedAbility(
                new BoostSourceEffect(1, 1, Duration.Custom),
                new GenericManaCost(2));

        Tier8Subcategory result = Tier8Subcategorizer.classify(ability);
        assertEquals("Persistent BoostCreature must map to PERSISTENT_COUNTER",
                Tier8Subcategory.PERSISTENT_COUNTER, result);
    }

    /**
     * Until-EOT pump (Outcome.Neutral) → should not match any mapped category → OTHER.
     * (In practice TIER_9 is filtered before reaching Tier8Subcategorizer, but this
     * tests the defensive default for unmapped outcomes.)
     */
    @Test
    public void testNeutralOutcomeIsOther() {
        SimpleActivatedAbility ability = new SimpleActivatedAbility(
                new BoostSourceEffect(1, 1, Duration.EndOfTurn),
                new GenericManaCost(1));
        // Override outcome to Neutral to test the default branch
        ability.addCustomOutcome(Outcome.Neutral);

        Tier8Subcategory result = Tier8Subcategorizer.classify(ability);
        assertEquals("Neutral outcome must fall back to OTHER", Tier8Subcategory.OTHER, result);
    }
}
