package mage.player.ai.land;

import mage.cards.Card;
import mage.game.Game;
import mage.player.ai.land.LandRanker.RankContext;
import mage.players.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 32 — entry point for "which land should the bot fetch from the library?".
 *
 * Called from the {@code ComputerPlayer6.chooseTarget} override when the target is
 * a {@code TargetCardInLibrary} whose candidates are all land cards (i.e. the
 * source ability is a fetchland, Cultivate, Farseek, Rampant Growth, etc.).
 *
 * Unlike {@link LandSelector}, {@code forImmediateUse=false} is used so that the
 * untapped bonus is disabled — the fetched land usually enters tapped anyway, and
 * what matters is long-term colour fixing.
 */
public final class LandSearchSelector {

    private LandSearchSelector() {}

    /**
     * Selects the UUID of the best land to fetch from a list of candidates.
     *
     * @param candidates lands available to choose from (already filtered by the
     *                   spell's {@code TargetCardInLibrary} filter)
     * @param game       current game state
     * @param playerId   the AI player's UUID
     * @param sourceName name of the source spell/ability (for log only)
     * @return UUID of the chosen card, or {@code null} if the list is empty
     */
    public static UUID selectBestLandFromLibrary(
            List<Card> candidates, Game game, UUID playerId, String sourceName) {

        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0).getId();
        }

        Player player = game.getPlayer(playerId);
        if (player == null) return candidates.get(0).getId();

        List<Card> hand = new ArrayList<>(player.getHand().getCards(game));
        Card commander = LandRanker.getCommanderFromZone(game, playerId);

        RankContext ctx = new RankContext(game, playerId, hand, false, commander);
        List<Card> ranked = LandRanker.sortByDesirability(candidates, ctx);
        Card best = ranked.get(0);

        game.fireStatusEvent(
                "[AI] [LAND-SELECT] turn=" + game.getTurnNum()
                        + " from=library source=" + sourceName
                        + " picked=" + best.getName()
                        + " score=" + LandRanker.scoreLand(best, ctx),
                false, false);

        return best.getId();
    }
}
