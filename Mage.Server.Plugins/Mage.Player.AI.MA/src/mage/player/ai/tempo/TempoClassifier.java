package mage.player.ai.tempo;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.SpellAbility;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.Effect;
import mage.abilities.mana.ManaAbility;
import mage.cards.Card;
import mage.constants.Duration;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.ai.hand.HandEvaluator;

import java.util.UUID;

/**
 * Sprint 33B: classifies an {@link Ability} into a {@link TempoCategory} for
 * early-game sequencing.
 *
 * <p>Pure function — no side effects, no game-state mutations.
 * Called by {@link mage.player.ai.ma.optimizers.impl.EarlyGameTempoOptimizer}
 * and by unit tests (TempoClassifierTest, Format 0).
 *
 * @author diego-xmage-ai
 */
public final class TempoClassifier {

    private TempoClassifier() {}

    /**
     * Classifies {@code ability} into a {@link TempoCategory}.
     *
     * @param ability the candidate action
     * @param game    current game state (read-only)
     * @param botId   the AI player's UUID (used for CMC-match turn lookup)
     * @return the appropriate {@link TempoCategory}; never null
     */
    public static TempoCategory classify(Ability ability, Game game, UUID botId) {
        // Mana abilities are engine internals, not strategic choices
        if (ability instanceof ManaAbility) {
            return TempoCategory.TIER_OTHER;
        }

        if (ability instanceof SpellAbility) {
            return classifySpell((SpellAbility) ability, game, botId);
        }

        if (ability instanceof ActivatedAbility) {
            return classifyActivation((ActivatedAbility) ability);
        }

        return TempoCategory.TIER_OTHER;
    }

    // -----------------------------------------------------------------------
    // Spell classification
    // -----------------------------------------------------------------------

    private static TempoCategory classifySpell(SpellAbility ability, Game game, UUID botId) {
        // Commander cast originates from the Command Zone
        if (Zone.COMMAND.equals(game.getState().getZone(ability.getSourceId()))) {
            return TempoCategory.TIER_5_COMMANDER;
        }

        Card card = game.getCard(ability.getSourceId());
        if (card == null) {
            // Simulation copy or card not found — treat as generic
            return TempoCategory.TIER_7_OTHER;
        }

        // Tier 2: fast mana artifact (Sol Ring, Mana Crypt, Mana Vault, Mox*)
        if (HandEvaluator.isFastMana(card, game)) {
            return TempoCategory.TIER_2_FAST_MANA;
        }

        // Tier 3: ramp — rock, dork, or land-tutor spell
        if (HandEvaluator.isManaRock(card, game)
                || HandEvaluator.isManaDork(card, game)
                || HandEvaluator.isLandTutorSpell(card, game)) {
            return TempoCategory.TIER_3_RAMP;
        }

        // Tier 4: repeating draw engine (Rhystic Study, Phyrexian Arena, …)
        if (HandEvaluator.isDrawEngine(card, game)) {
            return TempoCategory.TIER_4_ENGINE;
        }

        // Tier 6: curve filler — CMC in [turnNum-1, turnNum+1]
        int mv = card.getManaValue();
        int turn = game.getTurnNum();
        int low = Math.max(1, turn - 1);
        int high = turn + 1;
        if (mv >= low && mv <= high) {
            return TempoCategory.TIER_6_CMC_MATCH;
        }

        return TempoCategory.TIER_7_OTHER;
    }

    // -----------------------------------------------------------------------
    // Activated ability classification
    // -----------------------------------------------------------------------

    private static TempoCategory classifyActivation(ActivatedAbility ability) {
        if (isAllEffectsEndOfTurn(ability)) {
            return TempoCategory.TIER_9_NOOP;
        }
        return TempoCategory.TIER_8_USEFUL_ACTIVATION;
    }

    /**
     * Returns {@code true} when every effect on {@code ability} is a
     * {@link ContinuousEffect} with {@link Duration#EndOfTurn}.
     *
     * <p>This is the structural fingerprint of "until end of turn" activated
     * abilities: pump (+N/+N), keyword grants (flying), manland animation
     * ({@code BecomesCreatureSourceEffect}). Abilities with at least one
     * non-continuous or non-EOT effect are classified as useful.
     *
     * <p>Abilities with no effects at all are treated as NOT no-op (defensive
     * default — we'd rather miss a suppression than suppress something real).
     *
     * <p>Package-private for unit testing (TempoClassifierTest).
     */
    static boolean isAllEffectsEndOfTurn(Ability ability) {
        if (ability.getEffects().isEmpty()) {
            return false;
        }
        for (Effect effect : ability.getEffects()) {
            if (!(effect instanceof ContinuousEffect)) {
                return false; // one-shot or non-continuous — not purely EOT
            }
            if (((ContinuousEffect) effect).getDuration() != Duration.EndOfTurn) {
                return false;
            }
        }
        return true;
    }
}
