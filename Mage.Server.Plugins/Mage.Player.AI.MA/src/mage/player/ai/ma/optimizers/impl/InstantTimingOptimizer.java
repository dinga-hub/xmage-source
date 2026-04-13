package mage.player.ai.ma.optimizers.impl;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.SpellAbility;
import mage.abilities.costs.Cost;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.common.TapTargetCost;
import mage.abilities.keyword.FlashAbility;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.game.Game;

import java.util.List;
import java.util.UUID;

/**
 * AI: delays instant/flash spells and tap-cost activated abilities to better timing windows.
 *
 * Rule 1 — Instant/flash spells on the bot's own main phase:
 *   Suppress casting during PRECOMBAT_MAIN and POSTCOMBAT_MAIN. During opponent turns the
 *   minimax finds the best moment naturally (preferring end step for maximum information).
 *
 * Rule 2 — Tap-cost activated abilities during opponent's non-combat phases:
 *   Abilities that cost {T} (tap self) or tap another creature (TapTargetCost) remove
 *   those creatures as potential blockers. Using them during the opponent's upkeep, main
 *   phase, or end step — before attackers are even declared — is almost always wasteful.
 *   Exception: combat phases (blockers may still be declared after) and the bot's own turn.
 *
 * @author diego-xmage-ai
 */
public class InstantTimingOptimizer extends BaseTreeOptimizer {

    @Override
    public void filter(Game game, List<Ability> actions, List<Ability> actionsToRemove) {
        UUID botId = null;
        for (Ability ability : actions) {
            if (ability.getControllerId() != null) {
                botId = ability.getControllerId();
                break;
            }
        }
        if (botId == null) {
            return;
        }

        PhaseStep step = game.getTurnStepType();
        boolean isBotsTurn = game.isActivePlayer(botId);

        // Rule 1: suppress instant/flash casts on bot's own main phases
        if (isBotsTurn && (step == PhaseStep.PRECOMBAT_MAIN || step == PhaseStep.POSTCOMBAT_MAIN)) {
            for (Ability ability : actions) {
                if (!(ability instanceof SpellAbility)) {
                    continue;
                }
                Card card = game.getCard(ability.getSourceId());
                if (card == null) {
                    continue;
                }
                if (card.isInstant(game) || card.hasAbility(FlashAbility.getInstance(), game)) {
                    actionsToRemove.add(ability);
                }
            }
        }

        // Rule 2: suppress tap-cost activated abilities during opponent's non-combat phases.
        // These tap the bot's creatures before combat, removing potential blockers for no gain.
        // Only applies when: not the bot's turn AND we are NOT in a combat phase.
        if (!isBotsTurn && isNonCombatPhase(step)) {
            for (Ability ability : actions) {
                if (!(ability instanceof ActivatedAbility)) {
                    continue;
                }
                if (hasTapCost(ability)) {
                    actionsToRemove.add(ability);
                }
            }
        }
    }

    /**
     * Returns true for phases that are not part of the combat sequence.
     * During combat (BEGIN_COMBAT through END_COMBAT) we allow tap abilities
     * because they might be relevant as combat tricks or responses.
     */
    private boolean isNonCombatPhase(PhaseStep step) {
        return step == PhaseStep.UPKEEP
                || step == PhaseStep.DRAW
                || step == PhaseStep.PRECOMBAT_MAIN
                || step == PhaseStep.POSTCOMBAT_MAIN
                || step == PhaseStep.END_TURN;
    }

    /**
     * Returns true if the ability's costs include tapping (self or another creature).
     * Mana abilities are already filtered out upstream and will not reach this optimizer.
     */
    private boolean hasTapCost(Ability ability) {
        for (Cost cost : ability.getCosts()) {
            if (cost instanceof TapSourceCost || cost instanceof TapTargetCost) {
                return true;
            }
        }
        return false;
    }
}
