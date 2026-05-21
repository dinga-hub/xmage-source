package mage.player.ai.land;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.common.AsEntersBattlefieldAbility;
import mage.abilities.common.EntersBattlefieldTappedAbility;
import mage.abilities.common.EntersBattlefieldTappedUnlessAbility;
import mage.abilities.common.FetchLandActivatedAbility;
import mage.abilities.costs.common.SacrificeSourceCost;
import mage.abilities.mana.AnyColorLandsProduceManaAbility;
import mage.abilities.mana.AnyColorManaAbility;
import mage.abilities.mana.AnyColorPermanentTypesManaAbility;
import mage.abilities.mana.CommanderColorIdentityManaAbility;
import mage.abilities.mana.ManaAbility;
import mage.Mana;
import mage.cards.Card;
import mage.constants.CommanderCardType;
import mage.filter.FilterMana;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.*;

/**
 * Sprint 32: core land-ranking logic shared by {@link LandSelector} (playing a land
 * from hand) and {@link LandSearchSelector} (choosing a land from the library during
 * fetch/ramp/tutor resolution).
 *
 * <h2>Detection philosophy</h2>
 * All heuristics use XMage's ability class hierarchy — no card-name lists.
 * See project feedback: always validate XMage APIs before inventing heuristics.
 *
 * <ul>
 *   <li>ETB-tapped: {@link EntersBattlefieldTappedAbility} /
 *       {@link EntersBattlefieldTappedUnlessAbility} classes.</li>
 *   <li>Utility lands: lands with non-mana {@link ActivatedAbility} or
 *       {@link TriggeredAbility}, excluding {@link FetchLandActivatedAbility}
 *       (fetchlands benefit the caster; they are not "held" like Strip Mine).</li>
 *   <li>Color production: {@code card.getColorIdentity()} used as a proxy.
 *       Known limitation: fetchlands have no color identity symbols in their
 *       text, so they score ~0 for color-fix and rank after direct-producing
 *       lands — which is intentional (play the Island before the Delta when
 *       you need blue mana immediately).</li>
 * </ul>
 *
 * <h2>Scoring weights (see CLAUDE.md §Sprint 32)</h2>
 * Calibration belongs to post-game sessions. Weights are intentionally coarse.
 */
public final class LandRanker {

    // --- Scoring weights ---------------------------------------------------

    static final int UNTAPPED_SPELL_CASTABLE_BONUS = 300;
    static final int COLOR_FIX_IMMEDIATE_MULTIPLIER = 3;  // spell locked this turn
    static final int COLOR_FIX_COMMANDER_MULTIPLIER = 2;  // commander color
    static final int COLOR_FIX_BASE_BONUS = 200;          // top-N spells
    static final int COLOR_SATURATED_PENALTY = -50;
    static final int UTILITY_TRIGGER_BONUS = 400;
    static final int UTILITY_NO_TRIGGER_SCORE = -1000;
    static final int TAPPED_EARLY_OK_BONUS = 50;
    static final int BASIC_IN_MULTICOLOR_PENALTY = -30;
    static final int DIVERSITY_BONUS_PER_COLOR = 10;
    static final int COLORLESS_LAND_BONUS = 150;    // Temple of the False God, Ancient Tomb when ≥4 lands on board

    // --- Thresholds --------------------------------------------------------

    static final int COLOR_SATURATION_THRESHOLD = 3;  // ≥3 sources of a color = saturated
    static final int EARLY_GAME_TURN_CAP = 2;          // ≤T2 tapped land tolerated if no CMC-1
    static final int TOP_SPELLS_COUNT = 2;             // how many cheapest hand spells to check
    static final int MULTICOLOR_DECK_THRESHOLD = 3;    // deck color count for basic penalty
    static final int GRAVEYARD_TRIGGER_THRESHOLD = 4;  // min GY cards for ETB-trigger lands

    // --- Public API --------------------------------------------------------

    /** Context bundle passed to all scoring methods. */
    public static final class RankContext {
        public final Game game;
        public final UUID playerId;
        public final List<Card> hand;       // non-null, may be empty
        public final boolean forImmediateUse; // true = playing from hand now; false = fetching for future
        public final int turn;
        public final Card commander;        // may be null (non-Commander format or no commander in zone)

        public RankContext(Game game, UUID playerId, List<Card> hand,
                           boolean forImmediateUse, Card commander) {
            this.game = game;
            this.playerId = playerId;
            this.hand = hand != null ? hand : Collections.emptyList();
            this.forImmediateUse = forImmediateUse;
            this.turn = (game != null) ? game.getTurnNum() : 0;
            this.commander = commander;
        }
    }

    private LandRanker() {}

    /**
     * Returns a compact score summary for all lands, sorted best-first.
     * Used by the real-game log in {@code ComputerPlayer6.act()} so the rationale
     * is visible in the chat without needing to look at internal logs.
     * Format: "LandA=350, LandB=280, LandC=-1000(UTILITY_HOLD)"
     */
    public static String describeScores(List<Card> lands, RankContext ctx) {
        if (lands == null || lands.isEmpty()) return "(none)";
        List<Card> sorted = sortByDesirability(lands, ctx);
        StringBuilder sb = new StringBuilder();
        for (Card land : sorted) {
            if (sb.length() > 0) sb.append(", ");
            int score = scoreLand(land, ctx);
            sb.append(land.getName()).append('=').append(score);
            if (score <= UTILITY_NO_TRIGGER_SCORE) sb.append("(HOLD)");
            else if (entersBattlefieldTapped(land, ctx.game)) sb.append("(tap)");
        }
        return sb.toString();
    }

    /**
     * Sorts {@code lands} from most to least desirable for the given context.
     * Returns a new list; original is not modified.
     */
    public static List<Card> sortByDesirability(List<Card> lands, RankContext ctx) {
        if (lands == null || lands.isEmpty()) {
            return lands;
        }
        List<Card> sorted = new ArrayList<>(lands);
        sorted.sort((a, b) -> Integer.compare(scoreLand(b, ctx), scoreLand(a, ctx)));
        return sorted;
    }

    /** Absolute desirability score for a single land. Higher = better to play. */
    public static int scoreLand(Card land, RankContext ctx) {
        // 1. Utility-land gating — detect via ability structure, not card names
        if (isUtilityLand(land, ctx.game)) {
            if (hasActiveUtilityTrigger(land, ctx)) {
                return UTILITY_TRIGGER_BONUS + colorFixScore(land, ctx)
                        + diversityScore(land, ctx);
            }
            return UTILITY_NO_TRIGGER_SCORE; // hold it until relevant
        }

        int score = 0;

        // 2. Color-fix scoring with three priority buckets
        score += colorFixScore(land, ctx);

        // 3. Untapped bonus: only when playing from hand AND a spell needs this color NOW
        boolean entersTapped = entersBattlefieldTapped(land, ctx.game);
        if (ctx.forImmediateUse && !entersTapped && hasSpellUnlockedByThisLand(land, ctx)) {
            score += UNTAPPED_SPELL_CASTABLE_BONUS;
        }

        // 4. Early-game ETB-tapped tolerance: T1-T2, no CMC-1 spell in hand → not penalised
        if (ctx.forImmediateUse && entersTapped
                && ctx.turn <= EARLY_GAME_TURN_CAP
                && !hasCmc1SpellInHand(ctx)) {
            score += TAPPED_EARLY_OK_BONUS;
        }

        // 5. Basic in multicolor deck: slight penalty (duals usually better for fixing)
        if (land.isBasic() && deckColorCount(ctx) >= MULTICOLOR_DECK_THRESHOLD) {
            score += BASIC_IN_MULTICOLOR_PENALTY;
        }

        // 6. Colorless-producing lands (Temple of the False God, Ancient Tomb, Mishra's
        // Workshop): getProducedColors returns empty → color-fix and diversity are both 0.
        // Give a baseline bonus when the player already has enough lands for them to be
        // useful (Temple requires 5+; 4 on board means this land makes 5). Without this
        // bonus they tie at 0 with utility-lands on hold, which is fine early game but
        // they'd lose to a basic once coloured needs are met.
        if (getProducedColors(land, ctx.game, ctx.playerId).isEmpty() && !land.isBasic()) {
            int boardLands = ctx.game != null
                    ? (int) ctx.game.getBattlefield().getAllActivePermanents(ctx.playerId)
                            .stream().filter(p -> p.isLand(ctx.game)).count()
                    : 0;
            if (boardLands >= 4) score += COLORLESS_LAND_BONUS;
        }

        // 7. Diversity tie-breaker
        score += diversityScore(land, ctx);

        return score;
    }

    // --- Utility land detection --------------------------------------------

    /**
     * A land is "utility" if it has a non-mana {@link ActivatedAbility} (with any
     * cost) or a {@link TriggeredAbility}, excluding {@link FetchLandActivatedAbility}
     * (fetchlands are never held back — they fetch rather than destroy/exile).
     */
    static boolean isUtilityLand(Card land, Game game) {
        if (game == null) return false;
        for (Ability a : land.getAbilities(game)) {
            if (a instanceof ManaAbility) continue;
            if (a instanceof FetchLandActivatedAbility) continue;
            if (a instanceof ActivatedAbility) {
                if (!((ActivatedAbility) a).getCosts().isEmpty()) return true;
            }
            if (a instanceof TriggeredAbility) return true;
        }
        return false;
    }

    /**
     * Returns true if the utility land has a meaningful target right now:
     * - ETB-triggered lands (Bojuka Bog type): an opponent has a large graveyard.
     * - Sacrifice-to-destroy lands (Strip Mine, Wasteland): an opponent controls a nonbasic.
     */
    private static boolean hasActiveUtilityTrigger(Card land, RankContext ctx) {
        if (ctx.game == null) return false;

        // ETB-triggered: check for large opponent graveyard
        for (Ability a : land.getAbilities(ctx.game)) {
            if (a instanceof TriggeredAbility) {
                for (UUID oppId : ctx.game.getOpponents(ctx.playerId)) {
                    Player opp = ctx.game.getPlayer(oppId);
                    if (opp != null && opp.getGraveyard().size() >= GRAVEYARD_TRIGGER_THRESHOLD) {
                        return true;
                    }
                }
            }
        }

        // Sac-to-destroy: check for opponent nonbasic land on battlefield
        boolean opponentHasNonbasic = ctx.game.getBattlefield().getAllActivePermanents()
                .stream()
                .anyMatch(p -> p.isLand(ctx.game)
                        && !p.isBasic(ctx.game)
                        && !p.isControlledBy(ctx.playerId));

        if (opponentHasNonbasic) {
            for (Ability a : land.getAbilities(ctx.game)) {
                if (a instanceof ManaAbility || a instanceof FetchLandActivatedAbility) continue;
                if (a instanceof ActivatedAbility) {
                    boolean hasSacCost = ((ActivatedAbility) a).getCosts().stream()
                            .anyMatch(c -> c instanceof SacrificeSourceCost);
                    if (hasSacCost) return true;
                }
            }
        }
        return false;
    }

    // --- ETB-tapped detection ----------------------------------------------

    /**
     * Detects whether a land enters the battlefield tapped using XMage's dedicated
     * ability classes — no text parsing required.
     * Conditional ETB-tapped (shock lands, check lands) is treated as "tapped"
     * (pessimistic) since we cannot evaluate the condition cheaply here.
     */
    static boolean entersBattlefieldTapped(Card card, Game game) {
        if (game == null) return false;
        for (Ability a : card.getAbilities(game)) {
            if (a instanceof EntersBattlefieldTappedAbility) return true;
            if (a instanceof EntersBattlefieldTappedUnlessAbility) return true;
            // Shock lands: AsEntersBattlefieldAbility(TapSourceUnlessPaysEffect(...)).
            // TapSourceUnlessPaysEffect is inside EntersBattlefieldEffect.baseEffects (private),
            // so we conservatively treat ANY AsEntersBattlefieldAbility land as potentially
            // ETB-tapped — this blocks the untapped bonus for shock lands, which is correct.
            // The rare false positive (a land with a non-tapping AsEntersBattlefieldAbility)
            // only loses the untapped bonus; it still scores on color-fix and diversity.
            if (a instanceof AsEntersBattlefieldAbility) return true;
        }
        return false;
    }

    // --- Color-fix scoring -------------------------------------------------

    private static int colorFixScore(Card land, RankContext ctx) {
        if (ctx.game == null) return 0;
        Set<String> landColors = getProducedColors(land, ctx.game, ctx.playerId);
        if (landColors.isEmpty()) return 0;

        int score = 0;

        // Bucket 1 (×3): spells in hand that are CMC-affordable but color-locked
        for (Card spell : getImmediatelyLockedSpells(ctx)) {
            Set<String> needed = colorIdentitySet(spell.getColorIdentity());
            for (String c : landColors) {
                if (needed.contains(c) && countSourcesOnBoard(c, ctx) == 0) {
                    score += COLOR_FIX_BASE_BONUS * COLOR_FIX_IMMEDIATE_MULTIPLIER;
                }
            }
        }

        // Bucket 2 (×2): commander colors
        if (ctx.commander != null) {
            Set<String> cmdColors = colorIdentitySet(ctx.commander.getColorIdentity());
            for (String c : landColors) {
                if (cmdColors.contains(c) && countSourcesOnBoard(c, ctx) < 2) {
                    score += COLOR_FIX_BASE_BONUS * COLOR_FIX_COMMANDER_MULTIPLIER;
                }
            }
        }

        // Bucket 3 (×1): top-N cheapest spells by CMC
        for (Card spell : getTopSpellsByCmc(ctx, TOP_SPELLS_COUNT)) {
            Set<String> needed = colorIdentitySet(spell.getColorIdentity());
            for (String c : landColors) {
                if (needed.contains(c) && countSourcesOnBoard(c, ctx) == 0) {
                    score += COLOR_FIX_BASE_BONUS;
                }
            }
        }

        // Saturation penalty: adding a 4th+ source of the same color is inefficient
        for (String c : landColors) {
            if (countSourcesOnBoard(c, ctx) >= COLOR_SATURATION_THRESHOLD) {
                score += COLOR_SATURATED_PENALTY;
            }
        }

        return score;
    }

    private static int diversityScore(Card land, RankContext ctx) {
        return getProducedColors(land, ctx.game, ctx.playerId).size() * DIVERSITY_BONUS_PER_COLOR;
    }

    // --- Spell-lock helpers ------------------------------------------------

    private static boolean hasSpellUnlockedByThisLand(Card land, RankContext ctx) {
        Set<String> landColors = getProducedColors(land, ctx.game, ctx.playerId);
        Set<String> boardColors = boardColors(ctx);
        Set<String> available = new HashSet<>(boardColors);
        available.addAll(landColors);

        for (Card spell : ctx.hand) {
            if (spell == null || spell.isLand()) continue;
            Set<String> needed = colorIdentitySet(spell.getColorIdentity());
            // Spell's colors fully covered by board+this land but NOT by board alone
            if (available.containsAll(needed) && !boardColors.containsAll(needed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCmc1SpellInHand(RankContext ctx) {
        for (Card c : ctx.hand) {
            if (c != null && !c.isLand() && c.getManaValue() <= 1) return true;
        }
        return false;
    }

    /**
     * Spells in hand whose CMC is ≤ current turn (rough mana estimate) but whose
     * color requirements are not yet met on the board.
     */
    private static List<Card> getImmediatelyLockedSpells(RankContext ctx) {
        List<Card> result = new ArrayList<>();
        Set<String> boardColors = boardColors(ctx);
        int approxMana = ctx.turn + 1;
        for (Card c : ctx.hand) {
            if (c == null || c.isLand()) continue;
            if (c.getManaValue() <= approxMana) {
                Set<String> needed = colorIdentitySet(c.getColorIdentity());
                if (!needed.isEmpty() && !boardColors.containsAll(needed)) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    private static List<Card> getTopSpellsByCmc(RankContext ctx, int n) {
        List<Card> spells = new ArrayList<>();
        for (Card c : ctx.hand) {
            if (c != null && !c.isLand()) spells.add(c);
        }
        spells.sort(Comparator.comparingInt(Card::getManaValue));
        return spells.subList(0, Math.min(n, spells.size()));
    }

    // --- Board state helpers -----------------------------------------------

    private static Set<String> boardColors(RankContext ctx) {
        Set<String> colors = new HashSet<>();
        if (ctx.game == null) return colors;
        for (Permanent p : ctx.game.getBattlefield().getAllActivePermanents(ctx.playerId)) {
            if (p.isLand(ctx.game)) {
                colors.addAll(colorIdentitySet(p.getColorIdentity()));
            }
        }
        return colors;
    }

    private static int countSourcesOnBoard(String color, RankContext ctx) {
        if (ctx.game == null) return 0;
        int count = 0;
        for (Permanent p : ctx.game.getBattlefield().getAllActivePermanents(ctx.playerId)) {
            if (p.isLand(ctx.game)) {
                FilterMana id = p.getColorIdentity();
                if (id != null && colorMatches(id, color)) count++;
            }
        }
        return count;
    }

    private static int deckColorCount(RankContext ctx) {
        Set<String> colors = new HashSet<>();
        for (Card c : ctx.hand) {
            if (c != null) colors.addAll(colorIdentitySet(c.getColorIdentity()));
        }
        if (ctx.commander != null) {
            colors.addAll(colorIdentitySet(ctx.commander.getColorIdentity()));
        }
        return colors.size();
    }

    // --- Produced colors helper (runtime-aware) ----------------------------

    /**
     * Returns the set of colors this land can produce, accounting for runtime context:
     * - Lands with {@link CommanderColorIdentityManaAbility} (Command Tower, Path of
     *   Ancestry) produce all colors of the commander's identity, not their own card
     *   identity (which is empty).
     * - Everything else falls back to {@code card.getColorIdentity()}.
     */
    static Set<String> getProducedColors(Card land, Game game, UUID playerId) {
        if (game != null) {
            for (Ability a : land.getAbilities(game)) {
                if (a instanceof CommanderColorIdentityManaAbility) {
                    Player ctrl = game.getPlayer(playerId);
                    if (ctrl == null) break;
                    Set<String> cmdColors = new HashSet<>();
                    for (UUID cmdId : game.getCommandersIds(
                            ctrl, CommanderCardType.COMMANDER_OR_OATHBREAKER, false)) {
                        Card cmd = game.getCard(cmdId);
                        if (cmd != null) cmdColors.addAll(colorIdentitySet(cmd.getColorIdentity()));
                    }
                    if (!cmdColors.isEmpty()) return cmdColors;
                }
            }
        }

        // Fast path: most lands declare their colors explicitly in their color identity
        FilterMana id = land.getColorIdentity();
        Set<String> fromIdentity = colorIdentitySet(id);
        if (!fromIdentity.isEmpty()) return fromIdentity;

        // Dynamic resolution for lands with empty color identity that produce colors
        // at runtime by inspecting the battlefield (Exotic Orchard, Reflecting Pool,
        // Mana Confluence, City of Brass, etc.). Uses XMage's existing getNetMana(game)
        // rather than re-implementing the board scan.
        if (game != null) {
            for (Ability a : land.getAbilities(game)) {
                if (a instanceof AnyColorManaAbility) {
                    // Unconditionally produces any of 5 colors (Mana Confluence, City of Brass)
                    return new HashSet<>(Arrays.asList("W", "U", "B", "R", "G"));
                }
                if (a instanceof AnyColorLandsProduceManaAbility) {
                    // Colors depend on other lands on battlefield (Exotic Orchard = opponents,
                    // Reflecting Pool = self). getNetMana() returns empty if no qualifying lands.
                    Set<String> colors = manaListToColors(
                            ((AnyColorLandsProduceManaAbility) a).getNetMana(game));
                    if (!colors.isEmpty()) return colors;
                }
                if (a instanceof AnyColorPermanentTypesManaAbility) {
                    Set<String> colors = manaListToColors(
                            ((AnyColorPermanentTypesManaAbility) a).getNetMana(game));
                    if (!colors.isEmpty()) return colors;
                }
            }
        }
        return Collections.emptySet();
    }

    /** Converts a list of Mana objects (one color each) to a set of color letter strings. */
    private static Set<String> manaListToColors(List<Mana> manaList) {
        Set<String> colors = new HashSet<>();
        for (Mana m : manaList) {
            if (m.getWhite() > 0) colors.add("W");
            if (m.getBlue()  > 0) colors.add("U");
            if (m.getBlack() > 0) colors.add("B");
            if (m.getRed()   > 0) colors.add("R");
            if (m.getGreen() > 0) colors.add("G");
        }
        return colors;
    }

    // --- Color identity helpers --------------------------------------------

    static Set<String> colorIdentitySet(FilterMana id) {
        if (id == null) return Collections.emptySet();
        Set<String> colors = new HashSet<>();
        if (id.isWhite()) colors.add("W");
        if (id.isBlue())  colors.add("U");
        if (id.isBlack()) colors.add("B");
        if (id.isRed())   colors.add("R");
        if (id.isGreen()) colors.add("G");
        return colors;
    }

    private static boolean colorMatches(FilterMana id, String color) {
        switch (color) {
            case "W": return id.isWhite();
            case "U": return id.isBlue();
            case "B": return id.isBlack();
            case "R": return id.isRed();
            case "G": return id.isGreen();
            default:  return false;
        }
    }

    // --- Commander helper --------------------------------------------------

    /**
     * Returns the first available commander card from the command zone for the
     * given player, or {@code null} if none (non-Commander format or commander
     * has been cast and is on the battlefield / in exile).
     */
    public static Card getCommanderFromZone(Game game, UUID playerId) {
        if (game == null) return null;
        Player player = game.getPlayer(playerId);
        if (player == null) return null;
        Set<Card> cmds = game.getCommanderCardsFromCommandZone(
                player, CommanderCardType.COMMANDER_OR_OATHBREAKER);
        return cmds.isEmpty() ? null : cmds.iterator().next();
    }
}
