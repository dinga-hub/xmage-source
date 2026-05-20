package mage.player.ai.ma.optimizers.impl;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.cards.Card;
import mage.cards.repository.GameChangerRegistry;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.players.Player;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.stack.SpellCategory;
import mage.player.ai.stack.StackThreatClassifier;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 19: gate mass-protection (Heroic Intervention, Teferi's Protection, Boros Charm
 * indestructible mode, Eerie Interlude, etc.) so the bot doesn't burn the best protection
 * spell against a single Doom Blade.
 *
 * Triggers (dispara mass protection):
 *   - BOARDWIPE on the stack AND our board is "strong" (Sprint 18 definition).
 *   - TARGETED_REMOVAL on the stack aimed at our commander, GC permanent, or other engine.
 *     (Diego confirmed: also fire mass protection on single-target if target is engine.)
 *
 * Suppresses (não dispara):
 *   - Stack is empty (proactive cast — let minimax decide; usually wrong tempo).
 *   - Targeted removal on a mid-creature.
 *   - We are trailing — a wipe actually helps us catch up.
 *
 * @author diego-xmage-ai (Sprint 19)
 */
public class ProtectionOptimizer extends BaseTreeOptimizer {

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

        // Identify protection spells in the action list. Lazy work — most turns won't have one.
        boolean hasProtectionCandidate = false;
        for (Ability ability : actions) {
            if (isMassProtectionAbility(ability, game)) {
                hasProtectionCandidate = true;
                break;
            }
        }
        if (!hasProtectionCandidate) {
            return;
        }

        StackObject top = game.getStack().isEmpty() ? null : game.getStack().getFirst();
        SpellCategory category = (top == null) ? null : StackThreatClassifier.classify(top, game);

        ProtectionDecision decision = decide(category, top, botId, game);

        for (Ability ability : actions) {
            if (!isMassProtectionAbility(ability, game)) {
                continue;
            }
            String spellName = abilityCardName(ability, game);
            String botName = playerName(game, botId);
            if (!decision.use) {
                actionsToRemove.add(ability);
                game.fireStatusEvent("[AI:" + botName + "] [PROTECT-SKIP] " + spellName + " → "
                        + decision.reason, false, false);
            } else {
                game.fireStatusEvent("[AI:" + botName + "] [PROTECT] activating " + spellName
                        + " (" + decision.reason + ")", false, false);
            }
        }
    }

    // ── Decision ──────────────────────────────────────────────────────────────

    private static final class ProtectionDecision {
        final boolean use;
        final String reason;

        ProtectionDecision(boolean use, String reason) {
            this.use = use;
            this.reason = reason;
        }
    }

    private ProtectionDecision decide(SpellCategory category, StackObject top, UUID botId, Game game) {
        // Empty stack → proactive activation almost always wastes the spell. The exception
        // (anticipating an attack with a combat trick) belongs to a different optimizer.
        if (top == null || category == null) {
            return new ProtectionDecision(false, "stack empty — proactive protection wastes the spell");
        }

        // Don't protect against our own spell (sanity).
        if (botId.equals(top.getControllerId())) {
            return new ProtectionDecision(false, "spell is ours");
        }

        GameStateEvaluator2.SelfPosition position = GameStateEvaluator2.classifySelfPosition(botId, game);

        // Trailing → wipe is beneficial, hold the protection.
        if (position == GameStateEvaluator2.SelfPosition.TRAILING && category == SpellCategory.BOARDWIPE) {
            return new ProtectionDecision(false, "trailing, wipe helps us");
        }

        if (category == SpellCategory.BOARDWIPE) {
            if (GameStateEvaluator2.isBoardStrong(botId, game)) {
                return new ProtectionDecision(true, "boardwipe on stack + board strong");
            }
            return new ProtectionDecision(false, "boardwipe on stack but board not worth protecting");
        }

        if (category == SpellCategory.TARGETED_REMOVAL) {
            // Save the protection for big targets only — engine / GC / commander.
            if (removalTargetsCriticalPiece(top, botId, game)) {
                return new ProtectionDecision(true, "removal on engine/commander/GC");
            }
            return new ProtectionDecision(false, "removal on non-critical target");
        }

        // Mass damage at a player — last-ditch defense if it could kill the bot.
        if (category == SpellCategory.DAMAGE_SPELL || category == SpellCategory.BOARDWIPE) {
            // Already covered above; keep here as guard.
            return new ProtectionDecision(false, "damage spell — combat trick layer handles this");
        }

        return new ProtectionDecision(false, "stack threat (" + category + ") doesn't warrant mass protection");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isMassProtectionAbility(Ability ability, Game game) {
        if (!(ability instanceof SpellAbility)) {
            return false;
        }
        Card card = game.getCard(ability.getSourceId());
        if (card == null) {
            return false;
        }
        // Reuses the Sprint 18 effect-based detector (GainAbilityAllEffect indestructible /
        // PhaseOutAllEffect / supplementary list).
        return GameStateEvaluator2.isMassProtectionCard(card);
    }

    private boolean removalTargetsCriticalPiece(StackObject top, UUID botId, Game game) {
        if (top.getStackAbility() == null) {
            return false;
        }
        Player botPlayer = game.getPlayer(botId);
        for (mage.target.Target target : top.getStackAbility().getTargets()) {
            for (UUID targetId : target.getTargets()) {
                Permanent perm = game.getPermanent(targetId);
                if (perm == null || !botId.equals(perm.getControllerId())) {
                    continue;
                }
                if (botPlayer != null && game.isCommanderObject(botPlayer, perm)) return true;
                if (GameChangerRegistry.isGameChanger(perm.getName())) return true;
                if (GameStateEvaluator2.evaluatePermanent(perm, game, false) >= 1500) return true;
            }
        }
        return false;
    }

    private String playerName(Game game, UUID id) {
        return game.getPlayer(id) != null ? game.getPlayer(id).getName() : "AI";
    }

    private String abilityCardName(Ability ability, Game game) {
        Card card = game.getCard(ability.getSourceId());
        return card != null ? card.getName() : "protection";
    }
}
