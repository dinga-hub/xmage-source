package mage.player.ai.ma.optimizers.impl;

import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.DestroyAllEffect;
import mage.abilities.effects.common.ExileAllEffect;
import mage.abilities.effects.common.SacrificeAllEffect;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.score.GameStateEvaluator2;

import java.util.List;
import java.util.UUID;

/**
 * AI: prevents the bot from using boardwipes when it has a favorable board position.
 *
 * In Commander, boardwipes should only be used when opponents' combined board is
 * significantly stronger than the bot's own board (threshold: opponents >= bot * 1.5).
 * Using a boardwipe while winning erases the bot's own advantage.
 *
 * @author diego-xmage-ai
 */
public class BoardwipeOptimizer extends BaseTreeOptimizer {

    // Only use boardwipe if opponents' total board score >= bot board score * this multiplier.
    // 1.5 means: opponents collectively need 50% more board presence than the bot.
    private static final double BOARDWIPE_THRESHOLD_MULTIPLIER = 1.5;

    // Minimum combined board score on the table before considering a boardwipe.
    // Prevents skipping boardwipes in the early game when everyone has small boards.
    private static final int MIN_BOARD_TO_CONSIDER = 2000;

    @Override
    public void filter(Game game, List<Ability> actions, List<Ability> actionsToRemove) {
        UUID botId = null;
        // find the AI player controlling actions
        for (Ability ability : actions) {
            if (ability.getControllerId() != null) {
                botId = ability.getControllerId();
                break;
            }
        }
        if (botId == null) {
            return;
        }

        // lazily compute board scores only if there's a boardwipe candidate
        boolean hasBoardwipeCandidate = false;
        for (Ability ability : actions) {
            if (isBoardwipe(ability)) {
                hasBoardwipeCandidate = true;
                break;
            }
        }
        if (!hasBoardwipeCandidate) {
            return;
        }

        int myBoardScore = evaluateBoardScore(botId, game);
        int opponentsTotalBoardScore = 0;
        for (UUID opponentId : game.getOpponents(botId, true)) {
            opponentsTotalBoardScore += evaluateBoardScore(opponentId, game);
        }

        int combinedBoard = myBoardScore + opponentsTotalBoardScore;

        // if the combined board is tiny (early game), don't suppress boardwipes
        if (combinedBoard < MIN_BOARD_TO_CONSIDER) {
            return;
        }

        // suppress boardwipe if opponents don't have a significant advantage
        if (opponentsTotalBoardScore < myBoardScore * BOARDWIPE_THRESHOLD_MULTIPLIER) {
            for (Ability ability : actions) {
                if (isBoardwipe(ability)) {
                    actionsToRemove.add(ability);
                }
            }
        }
    }

    /**
     * Returns true if the ability contains a mass-removal effect (boardwipe).
     */
    private boolean isBoardwipe(Ability ability) {
        for (Effect effect : ability.getEffects()) {
            if (effect instanceof DestroyAllEffect
                    || effect instanceof ExileAllEffect
                    || effect instanceof SacrificeAllEffect) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sums the permanent scores for all permanents controlled by a player.
     * Uses creature/permanent score without combat modifier for a pure board-state measure.
     */
    private int evaluateBoardScore(UUID playerId, Game game) {
        int score = 0;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
            score += GameStateEvaluator2.evaluatePermanent(permanent, game, false);
        }
        return score;
    }
}
