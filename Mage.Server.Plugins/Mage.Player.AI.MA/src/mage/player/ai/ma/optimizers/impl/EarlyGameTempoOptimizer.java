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
 * Sprint 33B+33A: Early Game Tempo Priority — generalized activation gate.
 *
 * <h2>Pruning rules</h2>
 * <ol>
 *   <li><b>TIER_9_NOOP always removed (Rule 1 — 33A)</b>: activated abilities
 *       whose every effect is {@code Duration.EndOfTurn} (pump, manland animation,
 *       keyword grants) are pruned during the pre- or post-combat main phase of
 *       <em>any</em> player, on <em>any</em> turn. Animating a manland or granting
 *       flying in main phase is never correct — the effect vanishes before the next
 *       untap, and the mana/tap cost is a real loss. Combat phases are excluded
 *       (combat tricks are legitimate). END_STEP is excluded (future Sprint 33E).</li>
 *   <li><b>TIER_7_OTHER removed when Tier 2/3/4 is castable (Rule 2 — 33B scope)</b>:
 *       gate is own turn + turns 1–4 only. Generic creatures lose out to ramp/engine
 *       in the early game.</li>
 *   <li><b>TIER_8_USEFUL_ACTIVATION watch-logged (Rule 3 — 33B scope)</b>:
 *       same early-game gate. Useful activations are preserved but logged when
 *       better spells exist.</li>
 * </ol>
 *
 * <h2>Phase exclusions for Rule 1</h2>
 * <ul>
 *   <li>BEGINNING_OF_COMBAT, DECLARE_ATTACKERS, DECLARE_BLOCKERS, COMBAT_DAMAGE,
 *       END_COMBAT: manland animation and pump are legitimate combat tricks — not pruned.</li>
 *   <li>END_STEP: "no-downside" mana sink case deferred to Sprint 33E.</li>
 *   <li>UPKEEP, DRAW: rare legitimate activations — not pruned.</li>
 * </ul>
 *
 * @author diego-xmage-ai (Sprint 33B + 33A)
 */
public class EarlyGameTempoOptimizer extends BaseTreeOptimizer {

    /** Maximum turn number for Rules 2 + 3 (own-turn ramp/engine gates). */
    private static final int EARLY_GAME_TURN_LIMIT = 4;

    @Override
    public void filter(Game game, List<Ability> actions, List<Ability> actionsToRemove) {
        UUID botId = getBotId(actions);
        if (botId == null) {
            return;
        }

        boolean noopContext = isApplicableForNoopPrune(game);
        boolean rampContext = isApplicableForRamp(game, botId);
        if (!noopContext && !rampContext) {
            return;
        }

        String botName = game.getPlayer(botId) != null ? game.getPlayer(botId).getName() : "AI";
        int turn = game.getTurnNum();

        // --- Classify all actions ---------------------------------------
        Map<Ability, TempoCategory> cats = new HashMap<>();
        for (Ability a : actions) {
            cats.put(a, TempoClassifier.classify(a, game, botId));
        }

        // --- Rule 1: prune TIER_9_NOOP — main phase of any player, any turn ----
        // Until-EOT activations (manland, pump, keyword grants) waste mana in main
        // phase. The effect vanishes before next untap; passing is strictly better.
        // Opponent-turn pruning is the 33A extension; own-turn was already in 33B.
        if (noopContext) {
            boolean opponentTurn = !game.isActivePlayer(botId);
            for (Ability a : actions) {
                if (cats.get(a) == TempoCategory.TIER_9_NOOP) {
                    actionsToRemove.add(a);
                    game.fireStatusEvent(
                            "[AI:" + botName + "] [TEMPO-PRUNE] Tier9(noop) removed"
                                    + (opponentTurn ? " (opponent turn)" : "")
                                    + ": " + getCardName(a, game) + " (turn " + turn + ")",
                            false, false);
                }
            }
        }

        // --- Rules 2 + 3: own turn, early game (turns 1–4) only --------
        if (rampContext) {
            TempoCategory bestNonNoop = findBestNonNoop(cats);

            // Rule 2: prune TIER_7_OTHER when Tier 2/3/4 is castable
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

            // Rule 3: watch-log TIER_8 when Tier ≤ 6 is available
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
    }

    // -----------------------------------------------------------------------
    // Gate helpers
    // -----------------------------------------------------------------------

    /**
     * Rule 1 gate (TIER_9_NOOP): main phase of any player, any turn number.
     *
     * <p>Combat phases are excluded — until-EOT activations are legitimate
     * combat tricks (pump to make a trade lethal, manland to chump-block).
     * END_STEP is excluded — mana-sink case deferred to Sprint 33E.
     */
    private boolean isApplicableForNoopPrune(Game game) {
        PhaseStep step = game.getTurnStepType();
        return step == PhaseStep.PRECOMBAT_MAIN || step == PhaseStep.POSTCOMBAT_MAIN;
    }

    /**
     * Rules 2 + 3 gate (TIER_7 prune + TIER_8 watch): own turn only, turns 1–4.
     *
     * <p>Ramp/engine prioritization is an early-game concern specific to the
     * bot's own turn. Opponent-turn ramp sequencing is out of scope here.
     */
    private boolean isApplicableForRamp(Game game, UUID botId) {
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
