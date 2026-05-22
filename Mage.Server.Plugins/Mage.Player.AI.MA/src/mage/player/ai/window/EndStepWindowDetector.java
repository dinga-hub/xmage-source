package mage.player.ai.window;

import mage.constants.PhaseStep;
import mage.game.Game;
import mage.players.Player;
import mage.players.PlayerList;

import java.util.UUID;

/**
 * Detects "end step windows" — phases of an opponent's turn where the bot
 * should consider spending floating mana rather than passing.
 *
 * Standalone helper shared by EndStepManaSinkOptimizer (Sprint 33E)
 * and the future Flash Pseudo-Haste optimizer (Sprint 33F).
 */
public final class EndStepWindowDetector {

    private EndStepWindowDetector() {}

    /**
     * Returns true when the current END_TURN belongs to the opponent sitting
     * immediately before the bot in turn order — the "golden window" where
     * any mana left open will be lost on cleanup.
     *
     * Skips eliminated players when determining turn order.
     * Handles reversed turn order (e.g. Scrambleverse effects).
     */
    public static boolean isLastOpponentEndStepBeforeBot(Game game, UUID botId) {
        if (game.getTurnStepType() != PhaseStep.END_TURN) {
            return false;
        }
        UUID activeId = game.getActivePlayerId();
        if (activeId == null || activeId.equals(botId)) {
            return false;
        }
        // Copy so we don't mutate the shared cursor in game.getPlayerList()
        PlayerList list = game.getPlayerList().copy();
        list.setCurrent(activeId);
        Player nextAlive = list.getNext(game, false);
        return nextAlive != null && nextAlive.getId().equals(botId);
    }

    /**
     * Returns true for ANY opponent's END_TURN (not just the last before the
     * bot). Reserved for comparison use and Sprint 33F.
     */
    public static boolean isAnyOpponentEndStep(Game game, UUID botId) {
        return game.getTurnStepType() == PhaseStep.END_TURN
                && !botId.equals(game.getActivePlayerId());
    }
}
