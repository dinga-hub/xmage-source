package mage.player.ai.ma.optimizers.impl;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.cards.Card;
import mage.game.Game;
import mage.game.stack.StackObject;
import mage.player.ai.hand.HandEvaluator;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.score.GameStateEvaluator2.SelfPosition;
import mage.player.ai.stack.SpellCategory;
import mage.player.ai.stack.StackTargetingHelper;
import mage.player.ai.stack.StackThreatClassifier;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sprint 19B: gate single-target protection instants (phase-out, hexproof/indestructible
 * grant) so the bot only activates them when a TARGETED_REMOVAL on the stack is aimed
 * at the bot's commander or a high-value engine piece.
 *
 * Tie-breaker when multiple protection types are available in the action list:
 *   phase-out > hexproof grant
 * Phase-out is preferred because it also removes auras/equipment attached to the target
 * and fully dodges all targeting (destruction, exile, damage, etc.).
 *
 * Scope deliberately narrow (Sprint 19B):
 *   - Only reacts to TARGETED_REMOVAL (not damage spells, not edict effects).
 *   - Does NOT handle mass protection (that is ProtectionOptimizer's domain).
 *   - Does NOT handle self-bounce of commander (zone-change replacement makes it moot;
 *     consider Sprint 19D if this gap matters in practice).
 *
 * @author diego-xmage-ai (Sprint 19B)
 */
public class SingleTargetProtectionOptimizer extends BaseTreeOptimizer {

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

        // Short-circuit if no single-target protection spell is among the actions.
        boolean hasProtection = false;
        for (Ability ability : actions) {
            if (isSingleTargetProtectionAbility(ability, game)) {
                hasProtection = true;
                break;
            }
        }
        if (!hasProtection) {
            return;
        }

        StackObject top = game.getStack().getFirst();
        if (top == null || botId.equals(top.getControllerId())) {
            return; // stack empty or it's our own spell — no reason to protect
        }

        SpellCategory category = StackThreatClassifier.classify(top, game);

        // Only react to targeted removal. Other threats (boardwipes, extra turns) are
        // handled by ProtectionOptimizer and CounterOptimizer respectively.
        if (category != SpellCategory.TARGETED_REMOVAL) {
            suppressAll(actions, actionsToRemove, game, botId,
                    "stack threat is " + category + ", not targeted removal");
            return;
        }

        Set<UUID> criticalTargets = StackTargetingHelper.findCriticalTargetsOnStack(game, botId);
        if (criticalTargets.isEmpty()) {
            suppressAll(actions, actionsToRemove, game, botId,
                    "removal targets non-critical piece");
            return;
        }

        // Position gate: if we're leading and the threatened piece is not a Game Changer
        // or commander, let it die to reduce threat level in the pod. Matches Diego's
        // "policy reversal" in research/commander_wisdom/05_counter_strategy.md.
        SelfPosition position = GameStateEvaluator2.classifySelfPosition(botId, game);
        if (position == SelfPosition.LEADING && !criticalTargetsIncludeCommanderOrGC(criticalTargets, botId, game)) {
            suppressAll(actions, actionsToRemove, game, botId,
                    "leading — letting non-GC piece die to reduce threat level");
            return;
        }

        // Situation warrants protection. Apply tie-breaker: prefer phase-out over hexproof
        // grant. If both are in the action list, suppress hexproof grant so the bot doesn't
        // choose the weaker option when phase-out is available.
        boolean hasPhaseOut = hasProtectionType(actions, game, true);
        boolean hasHexproof = hasProtectionType(actions, game, false);

        String botName = game.getPlayer(botId) != null ? game.getPlayer(botId).getName() : "AI";
        String targetDesc = criticalTargets.size() + " critical piece(s)";

        if (hasPhaseOut && hasHexproof) {
            // Suppress hexproof grant — phase-out is strictly stronger here.
            for (Ability ability : actions) {
                if (isSingleTargetProtectionAbility(ability, game) && isHexproofGrantAbility(ability, game)) {
                    actionsToRemove.add(ability);
                    game.fireStatusEvent("[AI:" + botName + "] [PROTECT-ST-SKIP] "
                            + abilityCardName(ability, game) + " → phase-out available, prefer it", false, false);
                }
            }
            game.fireStatusEvent("[AI:" + botName + "] [PROTECT-ST] phase-out for " + targetDesc
                    + " vs " + top.getName(), false, false);
        } else {
            // Only one type available — use it.
            String typeLabel = hasPhaseOut ? "phase-out" : "hexproof-grant";
            game.fireStatusEvent("[AI:" + botName + "] [PROTECT-ST] " + typeLabel + " for " + targetDesc
                    + " vs " + top.getName(), false, false);
        }
    }

    // ── Decision helpers ──────────────────────────────────────────────────────

    private void suppressAll(List<Ability> actions, List<Ability> actionsToRemove,
                             Game game, UUID botId, String reason) {
        String botName = game.getPlayer(botId) != null ? game.getPlayer(botId).getName() : "AI";
        for (Ability ability : actions) {
            if (isSingleTargetProtectionAbility(ability, game)) {
                actionsToRemove.add(ability);
                game.fireStatusEvent("[AI:" + botName + "] [PROTECT-ST-SKIP] "
                        + abilityCardName(ability, game) + " → " + reason, false, false);
            }
        }
    }

    private boolean criticalTargetsIncludeCommanderOrGC(Set<UUID> criticalTargets, UUID botId, Game game) {
        mage.players.Player botPlayer = game.getPlayer(botId);
        for (UUID id : criticalTargets) {
            mage.game.permanent.Permanent perm = game.getPermanent(id);
            if (perm == null) continue;
            if (botPlayer != null && game.isCommanderObject(botPlayer, perm)) return true;
            if (mage.cards.repository.GameChangerRegistry.isGameChanger(perm.getName())) return true;
        }
        return false;
    }

    private boolean hasProtectionType(List<Ability> actions, Game game, boolean wantPhaseOut) {
        for (Ability ability : actions) {
            if (!(ability instanceof SpellAbility)) continue;
            Card card = game.getCard(ability.getSourceId());
            if (card == null) continue;
            if (wantPhaseOut && HandEvaluator.isPhaseOutProtection(card, game)) return true;
            if (!wantPhaseOut && HandEvaluator.isHexproofGrantProtection(card, game)) return true;
        }
        return false;
    }

    // ── Type checks ───────────────────────────────────────────────────────────

    private boolean isSingleTargetProtectionAbility(Ability ability, Game game) {
        if (!(ability instanceof SpellAbility)) {
            return false;
        }
        Card card = game.getCard(ability.getSourceId());
        return card != null && HandEvaluator.isSingleTargetProtectionSpell(card, game);
    }

    private boolean isHexproofGrantAbility(Ability ability, Game game) {
        if (!(ability instanceof SpellAbility)) {
            return false;
        }
        Card card = game.getCard(ability.getSourceId());
        return card != null && HandEvaluator.isHexproofGrantProtection(card, game);
    }

    private String abilityCardName(Ability ability, Game game) {
        Card card = game.getCard(ability.getSourceId());
        return card != null ? card.getName() : "protection";
    }
}
