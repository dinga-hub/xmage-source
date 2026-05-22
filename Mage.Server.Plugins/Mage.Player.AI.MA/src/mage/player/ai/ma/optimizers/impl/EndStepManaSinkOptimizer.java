package mage.player.ai.ma.optimizers.impl;

import mage.abilities.Ability;
import mage.cards.Card;
import mage.game.Game;
import mage.player.ai.tempo.TempoCategory;
import mage.player.ai.tempo.TempoClassifier;
import mage.player.ai.tempo.Tier8Subcategory;
import mage.player.ai.tempo.Tier8Subcategorizer;
import mage.player.ai.window.EndStepWindowDetector;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 33E: End Step Mana Sink.
 *
 * <p>Fires exclusively during the END_TURN of the opponent sitting immediately
 * before the bot in turn order — the "golden window" where any mana left open
 * will be lost on cleanup.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li><b>TIER_9_NOOP suppressed</b>: until-EOT activations (manland animation,
 *       pump, keyword grants) are removed. Their effect vanishes before the bot's
 *       own untap, so spending mana on them here is strictly worse than passing.
 *       Note: {@link EarlyGameTempoOptimizer} intentionally skips END_TURN for
 *       TIER_9 (Sprint 33A comment "END_STEP deferred to 33E"); this optimizer
 *       closes that gap.</li>
 *   <li><b>TIER_8 promoted and logged</b>: activated abilities with lasting
 *       effects (draw, removal, persistent counters) are kept and annotated with
 *       their {@link Tier8Subcategory} in the game log for debugging and QA.
 *       Priority order: DRAW &gt; REMOVAL &gt; PERSISTENT_COUNTER &gt; OTHER.</li>
 *   <li><b>Spell casts untouched</b>: instants and flash spells are valid in this
 *       window and scored by the minimax independently of this optimizer.</li>
 * </ol>
 *
 * <p>The mana-reservation release (Sprint 18's {@code HELD_MANA_SOURCES_*}) is
 * handled upstream in {@code ComputerPlayer7.priorityPlay()} before
 * {@code calculateActions()} is called, so this optimizer always sees the full
 * available mana pool.
 *
 * @author diego-xmage-ai (Sprint 33E)
 */
public class EndStepManaSinkOptimizer extends BaseTreeOptimizer {

    @Override
    void filter(Game game, List<Ability> actions, List<Ability> actionsToRemove) {
        UUID botId = getBotId(actions);
        if (botId == null) {
            return;
        }
        if (!EndStepWindowDetector.isLastOpponentEndStepBeforeBot(game, botId)) {
            return;
        }

        String botName = game.getPlayer(botId) != null ? game.getPlayer(botId).getName() : "AI";

        for (Ability a : actions) {
            TempoCategory cat = TempoClassifier.classify(a, game, botId);

            if (cat == TempoCategory.TIER_9_NOOP) {
                actionsToRemove.add(a);
                game.fireStatusEvent(
                        "[AI:" + botName + "] [END_STEP_SINK] Suppressed TIER_9(noop) at last opponent end step: "
                                + getCardName(a, game),
                        false, false);
                continue;
            }

            if (cat == TempoCategory.TIER_8_USEFUL_ACTIVATION) {
                Tier8Subcategory sub = Tier8Subcategorizer.classify(a);
                game.fireStatusEvent(
                        "[AI:" + botName + "] [END_STEP_SINK] Promoting TIER_8 activation ("
                                + sub + "): " + getCardName(a, game),
                        false, false);
            }
        }
    }

    /** Extracts the controlling player ID from the first ability that has one. */
    private UUID getBotId(List<Ability> actions) {
        for (Ability a : actions) {
            if (a.getControllerId() != null) {
                return a.getControllerId();
            }
        }
        return null;
    }

    private String getCardName(Ability ability, Game game) {
        Card card = game.getCard(ability.getSourceId());
        if (card != null && card.getName() != null) {
            return card.getName();
        }
        return ability.getClass().getSimpleName();
    }
}
