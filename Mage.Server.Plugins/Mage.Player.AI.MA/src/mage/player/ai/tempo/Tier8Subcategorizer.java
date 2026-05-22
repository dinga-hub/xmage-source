package mage.player.ai.tempo;

import mage.abilities.Ability;
import mage.constants.Outcome;

/**
 * Maps a TIER_8 activated ability to a Tier8Subcategory for prioritization
 * in the End Step window. Assumes the ability has already been classified
 * as TIER_8_USEFUL_ACTIVATION by TempoClassifier (i.e. not until-EOT).
 *
 * Priority: DRAW > REMOVAL > PERSISTENT_COUNTER > OTHER
 */
public final class Tier8Subcategorizer {

    private Tier8Subcategorizer() {}

    public static Tier8Subcategory classify(Ability ability) {
        Outcome outcome = effectiveOutcome(ability);
        if (outcome == null) {
            return Tier8Subcategory.OTHER;
        }
        switch (outcome) {
            case DrawCard:
                return Tier8Subcategory.DRAW;
            case Removal:
            case DestroyPermanent:
            case Exile:
            case Damage:
                return Tier8Subcategory.REMOVAL;
            case BoostCreature:
                // At this point TempoClassifier already filtered out until-EOT pump (TIER_9),
                // so BoostCreature here means a persistent counter effect (e.g. Mikaeus).
                return Tier8Subcategory.PERSISTENT_COUNTER;
            default:
                return Tier8Subcategory.OTHER;
        }
    }

    /** Respects customOutcome override used by AI card-specific tuning. */
    private static Outcome effectiveOutcome(Ability ability) {
        Outcome custom = ability.getCustomOutcome();
        if (custom != null) {
            return custom;
        }
        if (ability.getEffects().isEmpty()) {
            return null;
        }
        return ability.getEffects().getOutcome(ability);
    }
}
