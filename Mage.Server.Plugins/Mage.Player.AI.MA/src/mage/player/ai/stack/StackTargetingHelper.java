package mage.player.ai.stack;

import mage.cards.repository.GameChangerRegistry;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.player.ai.score.GameStateEvaluator2;
import mage.players.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Sprint 19B: shared utility for inspecting which permanents on the stack are
 * "critical pieces" (commander, Game Changer, or score ≥ 1500) that belong to
 * a given player.
 *
 * Extracted from {@code ProtectionOptimizer.removalTargetsCriticalPiece()} so
 * that {@code SingleTargetProtectionOptimizer} can reuse the same logic without
 * duplication.
 */
public final class StackTargetingHelper {

    private StackTargetingHelper() {}

    /**
     * Returns the UUIDs of permanents controlled by {@code botId} that are
     * targeted by the top stack object AND are considered critical (commander,
     * Game Changer, or evaluatePermanent score ≥ 1500).
     *
     * Returns an empty set when: stack is empty, no targets, targets don't
     * belong to the bot, or no target qualifies as critical.
     */
    public static Set<UUID> findCriticalTargetsOnStack(Game game, UUID botId) {
        Set<UUID> critical = new HashSet<>();
        if (game.getStack().isEmpty()) {
            return critical;
        }
        StackObject top = game.getStack().getFirst();
        if (top == null || top.getStackAbility() == null) {
            return critical;
        }
        Player botPlayer = game.getPlayer(botId);
        for (mage.target.Target target : top.getStackAbility().getTargets()) {
            for (UUID targetId : target.getTargets()) {
                Permanent perm = game.getPermanent(targetId);
                if (perm == null || !botId.equals(perm.getControllerId())) {
                    continue;
                }
                if (isCritical(perm, botPlayer, game)) {
                    critical.add(targetId);
                }
            }
        }
        return critical;
    }

    /** True if {@code perm} is a commander, a registered Game Changer, or scores ≥ 1500. */
    public static boolean isCritical(Permanent perm, Player botPlayer, Game game) {
        if (botPlayer != null && game.isCommanderObject(botPlayer, perm)) return true;
        if (GameChangerRegistry.isGameChanger(perm.getName())) return true;
        return GameStateEvaluator2.evaluatePermanent(perm, game, false) >= 1500;
    }
}
