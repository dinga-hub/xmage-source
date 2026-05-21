package mage.player.ai.land;

import mage.abilities.ActivatedAbility;
import mage.abilities.PlayLandAbility;
import mage.cards.Card;
import mage.game.Game;
import mage.player.ai.land.LandRanker.RankContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sprint 32 — entry point for "which land should the bot play from hand?".
 *
 * Used as a pre-filter in {@code SimulatedPlayer2.simulateOptions}: when
 * multiple {@link PlayLandAbility} entries are present, we reduce them to
 * the single best choice before the minimax tree expands. This both guides
 * the strategic decision and reduces branching factor.
 */
public final class LandSelector {

    private LandSelector() {}

    /**
     * Returns a copy of {@code playables} with all {@link PlayLandAbility}
     * entries replaced by a single entry for the best land, according to
     * {@link LandRanker}. If there is only one land option, or if
     * {@link LandRanker} cannot decide, returns the original list unchanged.
     *
     * @param playables all currently playable abilities in the simulation
     * @param game      the simulation game state
     * @param playerId  the AI player's UUID
     * @return filtered list (new object)
     */
    public static List<ActivatedAbility> filterToSingleBestLand(
            List<ActivatedAbility> playables, Game game, UUID playerId) {

        List<ActivatedAbility> landPlays = playables.stream()
                .filter(a -> a instanceof PlayLandAbility)
                .collect(Collectors.toList());

        if (landPlays.size() <= 1) {
            return playables; // nothing to decide
        }

        // Resolve source cards from the player's hand directly.
        // game.getCard() can return null for hand cards in simulation copies because
        // card registration differs between the real game and the simulation game.
        // Looking up by UUID through the player's hand collection is always reliable.
        Set<UUID> landPlayIds = landPlays.stream()
                .map(ActivatedAbility::getSourceId)
                .collect(Collectors.toSet());
        List<Card> hand = new ArrayList<>(
                game.getPlayer(playerId).getHand().getCards(game));
        List<Card> landCards = hand.stream()
                .filter(c -> landPlayIds.contains(c.getId()))
                .collect(Collectors.toList());

        if (landCards.isEmpty()) {
            return playables;
        }
        Card commander = LandRanker.getCommanderFromZone(game, playerId);

        RankContext ctx = new RankContext(game, playerId, hand, true, commander);
        List<Card> ranked = LandRanker.sortByDesirability(landCards, ctx);
        Card best = ranked.get(0);


        // Log is only useful on the real game; simulation copies fire on the wrong instance.
        // The real-game log entry is emitted by ComputerPlayer6.act() when the land is played.
        if (!game.isSimulation()) {
            String reason = buildReason(best, ctx);
            game.fireStatusEvent(
                    "[AI] [LAND-SELECT] turn=" + game.getTurnNum()
                            + " from=hand picked=" + best.getName()
                            + " score=" + LandRanker.scoreLand(best, ctx)
                            + " reason=" + reason,
                    false, false);
        }

        UUID bestId = best.getId();
        List<ActivatedAbility> result = new ArrayList<>();
        boolean landAdded = false;
        for (ActivatedAbility a : playables) {
            if (a instanceof PlayLandAbility) {
                if (!landAdded && Objects.equals(a.getSourceId(), bestId)) {
                    result.add(a);
                    landAdded = true;
                }
                // other PlayLandAbility entries are dropped
            } else {
                result.add(a);
            }
        }
        return result;
    }

    private static String buildReason(Card land, RankContext ctx) {
        if (LandRanker.isUtilityLand(land, ctx.game)) {
            return "UTILITY_TRIGGER_ACTIVE";
        }
        if (!LandRanker.entersBattlefieldTapped(land, ctx.game)
                && LandRanker.colorIdentitySet(land.getColorIdentity()).isEmpty()) {
            return "TIE";
        }
        if (!LandRanker.entersBattlefieldTapped(land, ctx.game)) {
            return "UNTAPPED_COLOR_FIX";
        }
        if (ctx.turn <= LandRanker.EARLY_GAME_TURN_CAP) {
            return "TAPPED_EARLY_OK";
        }
        return "COLOR_FIX";
    }
}
