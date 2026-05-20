package mage.player.ai.ma.optimizers.impl;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.CounterTargetEffect;
import mage.abilities.effects.common.CounterTargetWithReplacementEffect;
import mage.abilities.effects.common.CounterUnlessPaysEffect;
import mage.cards.Card;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.score.GameStateEvaluator2.SelfPosition;
import mage.player.ai.stack.SpellCategory;
import mage.player.ai.stack.StackThreatClassifier;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 19: gate counter activations behind a Commander-style priority hierarchy.
 *
 * Hierarchy (Diego's wisdom, 2026-05-20):
 *   1. Me proteger        — defensive counter is always justified
 *   2. Impedir alguém de ganhar  — counter scary spells only when opponent is leading and we are not trailing
 *   3. Atrasar game plan  — never, unless we have a redundant answer in hand
 *
 * The bot's minimax was already willing to fire a counter for any negative-outcome spell,
 * but it had no notion of "save the counter for the wipe". This optimizer suppresses
 * counter activations that aren't justified — the saved counter stays on top of the
 * library of options for the next priority window.
 *
 * @author diego-xmage-ai (Sprint 19)
 */
public class CounterOptimizer extends BaseTreeOptimizer {

    // WHY 4: Diego — below CMC 4 (Sol Ring, Llanowar Elves, Lightning Bolt in mid-creature)
    // it isn't worth a 2-mana counter unless the spell falls into an always-counter category
    // (boardwipe, removal of our engine piece, GC). Above CMC 4 the spell is presumed
    // expensive enough to be a finisher / engine drop.
    private static final int MIN_CMC_TO_COUNTER_NON_CATEGORICAL = 4;

    @Override
    public void filter(Game game, List<Ability> actions, List<Ability> actionsToRemove) {
        if (game.getStack().isEmpty()) {
            return;
        }

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

        // Identify the spell on top of the stack — that's what any counter on our action
        // list will be aimed at (cards on the stack resolve LIFO).
        StackObject top = game.getStack().getFirst();
        if (top == null) {
            return;
        }

        // Sanity: never counter our own spells. The minimax can occasionally produce this
        // when simulating; suppress at the optimizer layer so simulations don't waste nodes.
        if (botId.equals(top.getControllerId())) {
            return;
        }

        SpellCategory category = StackThreatClassifier.classify(top, game);
        boolean isGameChanger = StackThreatClassifier.isGameChanger(top);
        int topCmc = top.getManaValue();
        SelfPosition position = GameStateEvaluator2.classifySelfPosition(botId, game);

        // Apply only to abilities that actually counter a spell. Other actions pass through.
        for (Ability ability : actions) {
            if (!isCounterSpellAbility(ability)) {
                continue;
            }

            CounterDecision decision = decide(category, isGameChanger, topCmc, position, top, botId, game);
            if (!decision.use) {
                actionsToRemove.add(ability);
                String botName = playerName(game, botId);
                game.fireStatusEvent("[AI:" + botName + "] [COUNTER-SKIP] " + safeName(top)
                        + " on stack → " + decision.reason + " (category=" + category
                        + ", cmc=" + topCmc + ", position=" + position + ")", false, false);
            } else {
                String botName = playerName(game, botId);
                game.fireStatusEvent("[AI:" + botName + "] [COUNTER] " + abilityCardName(ability, game)
                        + " vs " + safeName(top) + " (" + decision.reason + ", category=" + category
                        + ", position=" + position + ")", false, false);
            }
        }
    }

    // ── Decision matrix ───────────────────────────────────────────────────────────

    private static final class CounterDecision {
        final boolean use;
        final String reason;

        CounterDecision(boolean use, String reason) {
            this.use = use;
            this.reason = reason;
        }
    }

    private CounterDecision decide(SpellCategory category, boolean isGameChanger, int topCmc,
                                   SelfPosition position, StackObject top, UUID botId, Game game) {
        // Defensive top priority: boardwipe targeting our side of the table → always counter
        // (Diego selected explicitly). Same goes for mass damage that could wipe us out.
        if (category == SpellCategory.BOARDWIPE) {
            // Exception: when we are TRAILING, a wipe actually helps us — let it resolve.
            if (position == SelfPosition.TRAILING) {
                return new CounterDecision(false, "wipe helps trailing player");
            }
            return new CounterDecision(true, "defensive: boardwipe incoming");
        }

        // Targeted removal aimed at our engine piece / commander / GC permanent → counter.
        if (category == SpellCategory.TARGETED_REMOVAL) {
            if (removalTargetsOurEnginePiece(top, botId, game)) {
                return new CounterDecision(true, "defensive: removal on engine/commander");
            }
            // Plain removal on a mid-creature — not worth the counter.
            return new CounterDecision(false, "removal on non-engine permanent");
        }

        // Game Changer override: any GC spell bypasses the CMC floor (covers Mana Vault,
        // Mox Diamond, Grim Monolith, The One Ring, etc.). Subject to self-position gates below.
        if (isGameChanger) {
            if (position == SelfPosition.TRAILING) {
                return new CounterDecision(false, "GC but we're trailing");
            }
            return new CounterDecision(true, "Game Changer on stack");
        }

        // Offensive counter targets (extra turn, draw engine, tutor): only counter when
        // (a) we are not trailing, AND
        // (b) the opponent is in a position to capitalize (proxy: their threat ≥ avg ⇒
        //     they are leading or archenemy). Without (b) the spell is just inconvenient,
        //     not game-deciding, and the counter is better saved.
        if (category == SpellCategory.EXTRA_TURN
                || category == SpellCategory.DRAW_ENGINE
                || category == SpellCategory.TUTOR) {
            if (position == SelfPosition.TRAILING) {
                return new CounterDecision(false, "offensive counter but we're trailing");
            }
            if (!opponentIsAhead(top.getControllerId(), botId, game)) {
                return new CounterDecision(false, "opponent not in winning position");
            }
            return new CounterDecision(true, "offensive: stop leader from snowballing");
        }

        // Counter-on-counter: only worth it when the spell being countered is our own.
        // Detecting "they're trying to counter MY spell" requires inspecting the second
        // item on the stack. Cheap heuristic: if the next stack object is ours, counter
        // their counter.
        if (category == SpellCategory.COUNTER) {
            if (theirCounterTargetsOurSpell(top, botId, game)) {
                return new CounterDecision(true, "counter-war: protect our spell");
            }
            return new CounterDecision(false, "their counter targets someone else");
        }

        // Everything else: apply CMC floor. Cheap utility spells / mana rocks / pumps /
        // damage at a creature aren't worth burning a counter on in Commander.
        if (topCmc < MIN_CMC_TO_COUNTER_NON_CATEGORICAL) {
            return new CounterDecision(false, "cmc " + topCmc + " < " + MIN_CMC_TO_COUNTER_NON_CATEGORICAL);
        }

        // CMC ≥ 4 and uncategorised: probably an expensive bomb. Counter if leading or
        // archenemy, hold otherwise.
        if (position == SelfPosition.ARCHENEMY || position == SelfPosition.LEADING) {
            return new CounterDecision(true, "expensive uncategorised spell, we're ahead");
        }
        return new CounterDecision(false, "expensive but parity — save counter for clearer threat");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isCounterSpellAbility(Ability ability) {
        if (!(ability instanceof SpellAbility)) {
            return false;
        }
        for (Effect effect : ability.getEffects()) {
            if (effect instanceof CounterTargetEffect
                    || effect instanceof CounterTargetWithReplacementEffect
                    || effect instanceof CounterUnlessPaysEffect) {
                return true;
            }
        }
        return false;
    }

    private boolean removalTargetsOurEnginePiece(StackObject top, UUID botId, Game game) {
        // A target on the stack object is meaningful only when present. If we can't see
        // the target, fall back to "treat as defensive worth countering" only when GC —
        // handled by isGameChanger upstream.
        if (top.getStackAbility() == null) {
            return false;
        }
        for (mage.target.Target target : top.getStackAbility().getTargets()) {
            for (UUID targetId : target.getTargets()) {
                Permanent perm = game.getPermanent(targetId);
                if (perm == null || !botId.equals(perm.getControllerId())) {
                    continue;
                }
                // Engine = our commander OR Game Changer on our side OR high-value permanent
                mage.players.Player botPlayer = game.getPlayer(botId);
                if (botPlayer != null && game.isCommanderObject(botPlayer, perm)) return true;
                if (mage.cards.repository.GameChangerRegistry.isGameChanger(perm.getName())) return true;
                if (GameStateEvaluator2.evaluatePermanent(perm, game, false) >= 1200) return true;
            }
        }
        return false;
    }

    private boolean theirCounterTargetsOurSpell(StackObject theirCounter, UUID botId, Game game) {
        if (theirCounter.getStackAbility() == null) {
            return false;
        }
        for (mage.target.Target target : theirCounter.getStackAbility().getTargets()) {
            for (UUID targetId : target.getTargets()) {
                StackObject targeted = game.getStack().getStackObject(targetId);
                if (targeted != null && botId.equals(targeted.getControllerId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean opponentIsAhead(UUID opponentId, UUID botId, Game game) {
        // Proxy for "in a position to win": their threat is at least the average of the
        // remaining opponents. Cheap and reuses Sprint 1+8 evaluatePlayerThreat.
        if (opponentId == null) return false;
        int oppThreat = GameStateEvaluator2.evaluatePlayerThreat(opponentId, game);
        int total = 0;
        int count = 0;
        for (UUID id : game.getOpponents(botId, true)) {
            total += GameStateEvaluator2.evaluatePlayerThreat(id, game);
            count++;
        }
        if (count == 0) return false;
        int avg = total / count;
        return oppThreat >= avg;
    }

    private String safeName(StackObject o) {
        return o == null ? "?" : o.getName();
    }

    private String playerName(Game game, UUID id) {
        return game.getPlayer(id) != null ? game.getPlayer(id).getName() : "AI";
    }

    private String abilityCardName(Ability ability, Game game) {
        if (ability instanceof SpellAbility) {
            Spell s = game.getStack().getSpell(ability.getId());
            if (s != null) return s.getName();
        }
        Card card = game.getCard(ability.getSourceId());
        return card != null ? card.getName() : "counter";
    }
}
