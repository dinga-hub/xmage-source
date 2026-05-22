package mage.player.ai.ma.optimizers.impl;

import mage.abilities.Ability;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.game.Game;
import mage.player.ai.tempo.TempoCategory;
import mage.player.ai.tempo.TempoClassifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 33B: Early Game Tempo Priority.
 *
 * <p>During turns 1–4, on the bot's own pre- or post-combat main phase, the
 * minimax tree should focus on high-tempo plays (ramp, draw engines, curve
 * fillers) rather than wasting resources on until-EOT activations or generic
 * creatures when better options exist.
 *
 * <h2>Pruning rules</h2>
 * <ol>
 *   <li><b>TIER_9_NOOP always removed</b>: activated abilities whose every
 *       effect is {@code Duration.EndOfTurn} (pump, manland, keyword grant)
 *       have zero lasting value during your own main phase in turns 1–4.
 *       Whether better spells are available or not, passing is strictly
 *       better than burning resources on a transient effect.</li>
 *   <li><b>TIER_7_OTHER removed when Tier 2/3/4 is castable</b>: a generic
 *       creature or utility spell loses out to fast mana / ramp / engine.
 *       The minimax tree handles the best choice among the survivors.</li>
 *   <li><b>TIER_8_USEFUL_ACTIVATION watch-logged</b>: useful activations
 *       (card draw, removal, counters) are preserved but logged when better
 *       spells exist. Data collection feeds Sprint 33A calibration.</li>
 * </ol>
 *
 * <h2>Scope</h2>
 * <ul>
 *   <li>Gate: {@code turnNum ≤ 4}, active player == bot, step == PRECOMBAT_MAIN
 *       or POSTCOMBAT_MAIN. Outside this context the method returns immediately.</li>
 *   <li>Opponent turns: partially handled by {@link InstantTimingOptimizer}
 *       Rule 2 (tap-cost activations). Remaining cases → Sprint 33A.</li>
 *   <li>Archetype-specific sequencing (Aggro, Combo, Stax) → sprint future.</li>
 * </ul>
 *
 * @author diego-xmage-ai
 */
public class EarlyGameTempoOptimizer extends BaseTreeOptimizer {

    /** Maximum turn number where this optimizer is active. */
    private static final int EARLY_GAME_TURN_LIMIT = 4;

    @Override
    public void filter(Game game, List<Ability> actions, List<Ability> actionsToRemove) {
        // --- Gate -------------------------------------------------------
        UUID botId = getBotId(actions);
        if (botId == null) {
            return;
        }
        if (!isApplicable(game, botId)) {
            return;
        }

        String botName = game.getPlayer(botId) != null ? game.getPlayer(botId).getName() : "AI";
        int turn = game.getTurnNum();

        // --- Classify all actions ---------------------------------------
        Map<Ability, TempoCategory> cats = new HashMap<>();
        for (Ability a : actions) {
            cats.put(a, TempoClassifier.classify(a, game, botId));
        }

        // Best non-noop, non-other category available in this action set
        TempoCategory bestNonNoop = findBestNonNoop(cats);

        // --- Rule 1: always prune TIER_9_NOOP ---------------------------
        // Until-EOT activations in own main phase turns 1-4 are never correct.
        // Passing is always preferable to spending mana on a vanishing effect.
        for (Ability a : actions) {
            if (cats.get(a) == TempoCategory.TIER_9_NOOP) {
                actionsToRemove.add(a);
                game.fireStatusEvent(
                        "[AI:" + botName + "] [TEMPO-PRUNE] Tier9(noop) removed: "
                                + getCardName(a, game) + " (turn " + turn + ")",
                        false, false);
            }
        }

        // --- Rule 2: prune TIER_7_OTHER when Tier 2/3/4 is castable ----
        // Generic creature or utility spell loses out to ramp / engine.
        if (bestNonNoop != null
                && bestNonNoop.ordinal() <= TempoCategory.TIER_4_ENGINE.ordinal()) {
            for (Ability a : actions) {
                if (cats.get(a) == TempoCategory.TIER_7_OTHER
                        && !actionsToRemove.contains(a)) {
                    actionsToRemove.add(a);
                    game.fireStatusEvent(
                            "[AI:" + botName + "] [TEMPO-PRUNE] Tier7(generic) removed: "
                                    + getCardName(a, game)
                                    + " — better ramp/engine available (turn " + turn + ")",
                            false, false);
                }
            }
        }

        // --- Rule 3: watch-log TIER_8 when Tier ≤ 6 is available -------
        // Useful activations are preserved but flagged for Sprint 33A analysis.
        if (bestNonNoop != null
                && bestNonNoop.ordinal() <= TempoCategory.TIER_6_CMC_MATCH.ordinal()) {
            for (Ability a : actions) {
                if (cats.get(a) == TempoCategory.TIER_8_USEFUL_ACTIVATION) {
                    game.fireStatusEvent(
                            "[AI:" + botName + "] [TEMPO-WATCH] Tier8 activation with Tier≤6 spell available: "
                                    + getCardName(a, game) + " (turn " + turn + ")",
                            false, false);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true when the optimizer should activate: bot's own turn, main
     * phase, within the early-game turn window.
     */
    private boolean isApplicable(Game game, UUID botId) {
        if (game.getTurnNum() > EARLY_GAME_TURN_LIMIT) {
            return false;
        }
        if (!game.isActivePlayer(botId)) {
            return false;
        }
        PhaseStep step = game.getTurnStepType();
        return step == PhaseStep.PRECOMBAT_MAIN || step == PhaseStep.POSTCOMBAT_MAIN;
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

    /**
     * Finds the highest-priority (lowest ordinal) category among all actions,
     * excluding {@link TempoCategory#TIER_9_NOOP} and {@link TempoCategory#TIER_OTHER}.
     * Returns null if every action is TIER_9 or TIER_OTHER.
     */
    private TempoCategory findBestNonNoop(Map<Ability, TempoCategory> cats) {
        TempoCategory best = null;
        for (TempoCategory cat : cats.values()) {
            if (cat == TempoCategory.TIER_9_NOOP || cat == TempoCategory.TIER_OTHER) {
                continue;
            }
            if (best == null || cat.ordinal() < best.ordinal()) {
                best = cat;
            }
        }
        return best;
    }

    /** Returns the source card name for logging, or the ability class name as fallback. */
    private String getCardName(Ability ability, Game game) {
        Card card = game.getCard(ability.getSourceId());
        if (card != null && card.getName() != null) {
            return card.getName();
        }
        return ability.getClass().getSimpleName();
    }
}
