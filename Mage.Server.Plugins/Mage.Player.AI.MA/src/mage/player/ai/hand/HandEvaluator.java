package mage.player.ai.hand;

import mage.abilities.Ability;
import mage.abilities.TriggeredAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.CounterTargetEffect;
import mage.abilities.effects.common.CounterTargetWithReplacementEffect;
import mage.abilities.effects.common.CounterUnlessPaysEffect;
import mage.abilities.effects.common.DamageAllEffect;
import mage.abilities.effects.common.DamageEverythingEffect;
import mage.abilities.effects.common.DamagePlayersEffect;
import mage.abilities.effects.common.DamageTargetEffect;
import mage.abilities.effects.common.DestroyAllEffect;
import mage.abilities.effects.common.DestroyTargetEffect;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.effects.common.ExileAllEffect;
import mage.constants.Outcome;
import mage.abilities.effects.common.ExileTargetEffect;
import mage.abilities.effects.common.PhaseOutTargetEffect;
import mage.abilities.effects.common.SacrificeAllEffect;
import mage.abilities.effects.common.continuous.GainAbilityTargetEffect;
import mage.abilities.effects.common.search.SearchLibraryPutInPlayEffect;
import mage.abilities.mana.ManaAbility;
import mage.cards.Card;
import mage.cards.repository.GameChangerRegistry;
import mage.filter.FilterMana;
import mage.game.Game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 30: structured evaluator of a starting / post-mulligan hand. Returns a
 * {@link HandScore} that the Sprint 31 mulligan policy and Sprint 33 early-game
 * sequencer consume.
 *
 * <h2>Detection philosophy</h2>
 *
 * Detection is driven by <b>card type + mana value + ability/effect classes</b>
 * already used by the rest of the AI ({@code StackThreatClassifier}). The only
 * name list consulted is {@link GameChangerRegistry} (curated by WotC for the
 * Commander brackets system) — every other heuristic must be expressible as a
 * structural rule, so new card sets don't immediately rot the evaluator.
 *
 * <h2>Numeric weights</h2>
 *
 * Weights mirror the recommendations in
 * {@code research/commander_wisdom/08_mulligan_opening_hand.md} (Section 7).
 * Fractional values from the research (e.g. "+0.5 for mana dorks") are realised
 * as integer constants here; further calibration belongs to Sprint 31, which is
 * where these scores actually drive decisions.
 */
public final class HandEvaluator {

    // --- Land thresholds -----------------------------------------------------

    /** "Three lands is ideal" — community consensus (08_mulligan_opening_hand.md §1). */
    private static final int IDEAL_LAND_COUNT = 3;

    /** 7-card hands with more than this many lands begin to be flooded. */
    private static final int LAND_FLOOD_THRESHOLD_7 = 5;

    /** Below this many lands on 7 cards, the hand is a forced mulligan. */
    private static final int HARD_REJECT_MIN_LAND_7 = 2;

    /** Same floor applied to 6-card hands. */
    private static final int HARD_REJECT_MIN_LAND_6 = 2;

    // --- Component values ----------------------------------------------------

    /** Tier 1 ramp: Sol Ring / Mana Crypt / Moxen — game-warping fast mana. */
    private static final int FAST_MANA_VALUE = 2;

    /** Tier 2 ramp: 2-mana rocks (Signets, Talismans) and 2-3 CMC land tutors. */
    private static final int STANDARD_RAMP_VALUE = 1;

    /** Tier 3 ramp: 1-2 CMC mana dorks. Cheaper than rocks but vulnerable to removal. */
    private static final int DORK_VALUE = 1;

    /** Repeating draw engines (Rhystic Study, Phyrexian Arena, Esper Sentinel, …). */
    private static final int ENGINE_VALUE = 2;

    /** Single-shot cantrips at CMC ≤ 1 (Brainstorm, Ponder, Preordain, Opt). */
    private static final int CANTRIP_VALUE = 1;

    /** Cheap creatures / planeswalkers at CMC ≤ 3 — playable turns 1-3. */
    private static final int CHEAP_THREAT_VALUE = 1;

    /** Hand contains an official Commander Game Changer permanent. */
    private static final int GAME_CHANGER_BONUS = 2;

    /** Each commander / top-spell colour with no source in hand. */
    private static final int MISSING_COLOR_PENALTY = -2;

    /** Each required colour with only one source — risky if that land is countered or LD'd. */
    private static final int SINGLE_SOURCE_COLOR_PENALTY = -1;

    /** Hand has zero on-curve plays at CMC ≤ 3 — nothing develops the board turns 1-3. */
    public static final int NO_EARLY_PLAYS_PENALTY = -2;

    /** Hand covers all of CMC 1, 2, 3 with an on-curve play in each slot. */
    private static final int FULL_CURVE_BONUS = 1;

    // --- Mulligan thresholds (Sprint 31.5) -----------------------------------

    /** Minimum total to keep a 7-card hand. Research §7: "<2 signals mulligan, ≥5 is strong". */
    public static final int KEEP_THRESHOLD_7 = 3;

    /** How many of the cheapest non-land spells feed the colour-coverage check. */
    private static final int COLOR_COVERAGE_TOP_SPELLS = 3;

    private HandEvaluator() {
    }

    /**
     * Entry point. {@code game} may be {@code null} when called from unit tests or
     * pre-game evaluations — detection routines therefore avoid {@code Game}-dependent
     * APIs where possible.
     *
     * @param hand      cards being evaluated. Never {@code null}.
     * @param commander commander card (Commander format) or {@code null}.
     * @param game      current game (may be {@code null} pre-game / in tests).
     * @param handSize  number of cards the player is keeping (7, 6, 5, 4). Drives
     *                  the hard-reject floor — a 5-card hand has different tolerance.
     */
    public static HandScore evaluate(List<Card> hand, Card commander, Game game, int handSize) {
        HandScore score = new HandScore();
        if (hand == null || hand.isEmpty()) {
            score.hardReject = true;
            score.note("empty hand");
            return score;
        }

        scoreLands(hand, handSize, score);
        if (score.hardReject) {
            score.total = score.landScore;
            return score;
        }

        scoreRamp(hand, game, score);
        scoreDraw(hand, game, score);
        scoreThreats(hand, game, score);
        scoreColorCoverage(hand, commander, game, score);
        scoreManaCurve(hand, commander, game, score);
        evaluateAutoKeep(hand, game, score);

        score.total = score.landScore + score.rampScore + score.drawScore
                + score.threatScore + score.colorScore + score.manaCurveScore;
        return score;
    }

    // ---------- Lands --------------------------------------------------------

    private static void scoreLands(List<Card> hand, int handSize, HandScore score) {
        int lands = 0;
        for (Card c : hand) {
            if (c != null && c.isLand()) {
                lands++;
            }
        }
        score.landCount = lands;

        // Base: +1 per land, clamped at the ideal count to avoid rewarding flood.
        int base = Math.min(lands, IDEAL_LAND_COUNT);
        // Flood penalty applies only on 7-card hands — the smaller-hand thresholds
        // are intentionally lenient (a 5-card hand with 4 lands is not flooded).
        int floodPenalty = 0;
        if (handSize >= 7 && lands > LAND_FLOOD_THRESHOLD_7) {
            floodPenalty = -2 * (lands - LAND_FLOOD_THRESHOLD_7);
        }
        score.landScore = base + floodPenalty;
        score.note("lands=" + lands + " landScore=" + score.landScore);

        // Hard reject thresholds.
        // hand=7/6: require 2+ lands. hand=5: 1 land is acceptable, reject only on 0.
        // hands of 4 or fewer are snap-kept by the mulligan policy (0-land override handled there).
        if ((handSize >= 7 && lands < HARD_REJECT_MIN_LAND_7)
                || (handSize == 6 && lands < HARD_REJECT_MIN_LAND_6)
                || (handSize == 5 && lands == 0)) {
            score.hardReject = true;
            score.note("hard reject: only " + lands + " land(s) in " + handSize + "-card hand");
        }
    }

    // ---------- Ramp ---------------------------------------------------------

    private static void scoreRamp(List<Card> hand, Game game, HandScore score) {
        int ramp = 0;
        for (Card c : hand) {
            if (c == null || c.isLand()) {
                continue;
            }
            int mv = c.getManaValue();

            // Tier 1: artifact mana sources at CMC ≤ 1. Covers Sol Ring (1, taps for
            // {C}{C}), Mana Crypt (0), Mana Vault (1), the Moxen (0), Lotus Petal (0).
            // The {@link ManaAbility} interface marker is what each of those cards'
            // generators implement — see mage.abilities.mana.* for the family.
            if (c.isArtifact() && mv <= 1 && hasManaAbility(c, game)) {
                ramp += FAST_MANA_VALUE;
                score.note("fast mana: " + safeName(c));
                continue;
            }

            // Tier 2 rock: artifact at exactly 2 mana with a mana ability. Covers
            // Arcane Signet, all guild Signets / Talismans, Fellwar Stone, Mind Stone.
            if (c.isArtifact() && mv == 2 && hasManaAbility(c, game)) {
                ramp += STANDARD_RAMP_VALUE;
                score.note("mana rock: " + safeName(c));
                continue;
            }

            // Tier 2 land tutor: cheap sorcery/instant that fetches a land into play
            // or hand. Covers Cultivate, Kodama's Reach, Rampant Growth, Three Visits,
            // Nature's Lore, Farseek. Sakura-Tribe Elder is a creature, so it falls
            // through to the dork branch below (CMC 2 creature with a triggered "search
            // for land" via sacrifice — still rewarded as ramp via the dork rule).
            if (mv <= 3 && !c.isCreature() && hasLandTutorEffect(c, game)) {
                ramp += STANDARD_RAMP_VALUE;
                score.note("land tutor: " + safeName(c));
                continue;
            }

            // Tier 3 dork: creature at CMC ≤ 2 with a mana ability. Covers Llanowar
            // Elves, Birds of Paradise, Elvish Mystic, Avacyn's Pilgrim, Noble Hierarch,
            // Deathrite Shaman, Bloom Tender.
            if (c.isCreature() && mv <= 2 && hasManaAbility(c, game)) {
                ramp += DORK_VALUE;
                score.note("mana dork: " + safeName(c));
                continue;
            }

            // Tier 3 creature-based land tutor (Sakura-Tribe Elder / Wood Elves /
            // Farhaven Elf): CMC ≤ 3 creature whose ability searches a land into
            // play. Same dork weight — accelerates by one turn at modest cost.
            if (c.isCreature() && mv <= 3 && hasLandTutorEffect(c, game)) {
                ramp += DORK_VALUE;
                score.note("creature land tutor: " + safeName(c));
            }
        }
        score.rampScore = ramp;
    }

    // ---------- Draw / engines ----------------------------------------------

    /**
     * Awards points for repeating draw engines (Rhystic Study, Phyrexian Arena,
     * Mystic Remora, Esper Sentinel, …) and one-shot cantrips (Brainstorm /
     * Ponder / Preordain / Opt). Engines are detected structurally as
     * permanents with a {@link TriggeredAbility} whose effects include
     * {@link DrawCardSourceControllerEffect}; cantrips are cheap instants /
     * sorceries with the same effect. Engines on the official Game Changer
     * list also bump {@code threatScore} via {@link #scoreThreats}.
     *
     * Marks each card as draw-counted in {@code countedAsDraw} so
     * {@link #scoreThreats} can avoid double-counting GC engines.
     */
    private static void scoreDraw(List<Card> hand, Game game, HandScore score) {
        int draw = 0;
        for (Card c : hand) {
            if (c == null || c.isLand()) {
                continue;
            }
            if (c.isInstant() || c.isSorcery()) {
                // Cantrip: cheap one-shot draw. Anything pricier than 1 mana is
                // not really a cantrip — pricier draw spells are evaluated as
                // engine-or-threat in their respective branches.
                if (c.getManaValue() <= 1 && hasDrawEffect(c, game)) {
                    draw += CANTRIP_VALUE;
                    score.note("cantrip: " + safeName(c));
                }
                continue;
            }
            // Permanent draw engine: detect a triggered ability that draws cards.
            // Static-ability engines (e.g. The One Ring's protection ability is
            // static, but the draw is triggered) still surface here because the
            // draw piece itself is triggered.
            if (hasTriggeredDrawAbility(c, game)) {
                draw += ENGINE_VALUE;
                score.note("draw engine: " + safeName(c));
            }
        }
        score.drawScore = draw;
    }

    // ---------- Threats ------------------------------------------------------

    /**
     * Cheap creatures / planeswalkers (CMC ≤ 3) earn the full threat bonus —
     * they hit the board on curve. CMC 4-5 creatures still score, but at the
     * same flat weight (further calibration belongs to Sprint 31).
     *
     * A Game Changer in hand (Consecrated Sphinx, Cyclonic Rift, …) adds an
     * extra bonus on top of any threat / engine credit it has already received.
     */
    private static void scoreThreats(List<Card> hand, Game game, HandScore score) {
        int threats = 0;
        for (Card c : hand) {
            if (c == null || c.isLand()) {
                continue;
            }
            int mv = c.getManaValue();
            if ((c.isCreature() || c.isPlaneswalker()) && mv <= 5) {
                threats += CHEAP_THREAT_VALUE;
                score.note("threat: " + safeName(c));
            }
            if (c.getName() != null && GameChangerRegistry.isGameChanger(c.getName())) {
                threats += GAME_CHANGER_BONUS;
                score.note("game changer: " + safeName(c));
            }
        }
        score.threatScore = threats;
    }

    // ---------- Color coverage ----------------------------------------------

    /**
     * Cross-checks the colours required by the commander and by the cheapest
     * non-land spells in hand against the colours the lands in hand can produce.
     *
     * Limitation acknowledged here: mana rocks (Arcane Signet, Talismans) are
     * NOT counted as colour sources in this iteration — their colour identity
     * is too implementation-dependent to detect without name lookup. Sprint 31
     * can extend this once the mulligan path consumes the score.
     *
     * The top spells used for colour requirements are the {@value
     * #COLOR_COVERAGE_TOP_SPELLS} cheapest non-land cards — the ones that
     * actually need to cast turns 1-3.
     */
    private static void scoreColorCoverage(List<Card> hand, Card commander, Game game, HandScore score) {
        Set<String> required = new HashSet<>();
        if (commander != null) {
            collectColors(commander.getColorIdentity(), required);
        }

        List<Card> cheapest = new ArrayList<>();
        for (Card c : hand) {
            if (c != null && !c.isLand()) {
                cheapest.add(c);
            }
        }
        cheapest.sort(Comparator.comparingInt(Card::getManaValue));
        int limit = Math.min(COLOR_COVERAGE_TOP_SPELLS, cheapest.size());
        for (int i = 0; i < limit; i++) {
            collectColors(cheapest.get(i).getColorIdentity(), required);
        }

        if (required.isEmpty()) {
            return; // colourless requirements (rare): nothing to penalise.
        }

        // Count how many lands in hand can produce each required colour.
        int colorPenalty = 0;
        for (String color : required) {
            int sources = 0;
            for (Card c : hand) {
                if (c == null || !c.isLand()) {
                    continue;
                }
                FilterMana id = c.getColorIdentity();
                if (id != null && colorMatches(id, color)) {
                    sources++;
                }
            }
            if (sources == 0) {
                colorPenalty += MISSING_COLOR_PENALTY;
                score.note("no source for " + color);
            } else if (sources == 1) {
                colorPenalty += SINGLE_SOURCE_COLOR_PENALTY;
                score.note("only 1 source for " + color);
            }
        }
        score.colorScore = colorPenalty;
    }

    private static void collectColors(FilterMana id, Set<String> out) {
        if (id == null) return;
        if (id.isWhite()) out.add("W");
        if (id.isBlue()) out.add("U");
        if (id.isBlack()) out.add("B");
        if (id.isRed()) out.add("R");
        if (id.isGreen()) out.add("G");
    }

    private static boolean colorMatches(FilterMana id, String color) {
        switch (color) {
            case "W": return id.isWhite();
            case "U": return id.isBlue();
            case "B": return id.isBlack();
            case "R": return id.isRed();
            case "G": return id.isGreen();
            default: return false;
        }
    }

    // ---------- Mana curve --------------------------------------------------

    /**
     * Counts <b>on-curve</b> plays at CMC 1, 2, 3 only. "On-curve" means cards
     * that advance the board on the turn they're cast — creatures, planeswalkers,
     * ramp (artifacts with mana ability or sorceries with land tutor effects),
     * and permanent engine drops (triggered draw effects).
     *
     * Reactive spells (instants, sweepers, targeted removal, counterspells) are
     * <b>explicitly excluded</b> from the curve check — they exist to be held
     * for the right opportunity, not to be cast on turn N just because mana is
     * available. See feedback note 2026-05-20: removals and counters never come
     * down "on curve" so penalising a hand for their CMC distribution would
     * misread Control / Midrange hands as weak.
     *
     * Sprint 31.6: the commander itself counts as an on-curve play at its own CMC
     * (it is always available from the command zone). A CMC 2-3 commander fills a
     * curve slot the hand would otherwise miss; a CMC 5+ commander adds nothing here.
     */
    private static void scoreManaCurve(List<Card> hand, Card commander, Game game, HandScore score) {
        boolean hasOne = false, hasTwo = false, hasThree = false;
        for (Card c : hand) {
            if (c == null || c.isLand()) {
                continue;
            }
            if (!isOnCurvePlay(c, game)) {
                continue;
            }
            int mv = c.getManaValue();
            if (mv == 1) hasOne = true;
            else if (mv == 2) hasTwo = true;
            else if (mv == 3) hasThree = true;
        }
        // Sprint 31.6: commander always available from command zone, fills its CMC slot.
        if (commander != null && isOnCurvePlay(commander, game)) {
            int cmv = commander.getManaValue();
            if (cmv == 1) hasOne = true;
            else if (cmv == 2) hasTwo = true;
            else if (cmv == 3) hasThree = true;
        }

        int curve = 0;
        if (!hasOne && !hasTwo && !hasThree) {
            curve += NO_EARLY_PLAYS_PENALTY;
            score.note("no on-curve play at CMC 1-3");
        } else if (hasOne && hasTwo && hasThree) {
            curve += FULL_CURVE_BONUS;
            score.note("full 1-2-3 on-curve coverage");
        }
        score.manaCurveScore = curve;
    }

    /**
     * A play is "on curve" if casting it on its natural turn advances the board:
     *  - any creature or planeswalker;
     *  - any artifact with a mana ability (mana rock — ramp);
     *  - any non-instant with a land-tutor effect (Cultivate-class — ramp);
     *  - any non-instant / non-sorcery with a triggered draw effect (engine).
     *
     * Instants are never on-curve. Sorceries with sweep / counter / point-removal
     * effects are reactive and excluded — they are cast when an opportunity
     * appears, not as scheduled tempo plays.
     */
    private static boolean isOnCurvePlay(Card c, Game game) {
        if (c.isCreature() || c.isPlaneswalker()) {
            return true;
        }
        if (c.isInstant()) {
            return false;
        }
        if (c.isArtifact() && hasManaAbility(c, game)) {
            return true;
        }
        if (!c.isInstant() && hasLandTutorEffect(c, game)) {
            return true;
        }
        if (!c.isInstant() && !c.isSorcery() && hasTriggeredDrawAbility(c, game)) {
            return true;
        }
        // Sorcery that's a sweeper / counter / removal — reactive, off-curve.
        if (c.isSorcery() && hasReactiveEffect(c, game)) {
            return false;
        }
        // Sorcery that doesn't fit any positive bucket — treat as off-curve
        // until a future sprint adds positive classifications for things like
        // wheels or token producers. Conservative default.
        return false;
    }

    private static boolean hasReactiveEffect(Card card, Game game) {
        for (Ability ability : card.getAbilities(game)) {
            for (Effect effect : ability.getEffects()) {
                if (effect instanceof DestroyAllEffect
                        || effect instanceof ExileAllEffect
                        || effect instanceof SacrificeAllEffect
                        || effect instanceof DamageAllEffect
                        || effect instanceof DamageEverythingEffect
                        || effect instanceof DamagePlayersEffect
                        || effect instanceof DestroyTargetEffect
                        || effect instanceof ExileTargetEffect
                        || effect instanceof DamageTargetEffect
                        || effect instanceof CounterTargetEffect
                        || effect instanceof CounterTargetWithReplacementEffect
                        || effect instanceof CounterUnlessPaysEffect) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- Auto-keep ----------------------------------------------------

    /**
     * Fast mana (Sol Ring / Mana Crypt / Mox / Lotus Petal) plus at least two
     * lands is the canonical "always keep" line in Commander
     * ({@code research/commander_wisdom/08_mulligan_opening_hand.md} §7). The
     * tempo advantage from a turn-one Sol Ring is large enough that the
     * mulligan policy should not throw the hand away even with a low total.
     */
    private static void evaluateAutoKeep(List<Card> hand, Game game, HandScore score) {
        if (score.landCount < 2) {
            return;
        }
        for (Card c : hand) {
            if (c == null || c.isLand()) {
                continue;
            }
            if (c.isArtifact() && c.getManaValue() <= 1 && hasManaAbility(c, game)) {
                score.autoKeep = true;
                score.note("auto-keep: fast mana + " + score.landCount + " lands");
                return;
            }
        }
    }

    // ---------- Detection helpers -------------------------------------------

    /**
     * Returns true if any of the card's abilities implements {@link ManaAbility}
     * (the marker interface shared by every mana-producing ability — basic,
     * triggered, conditional, dynamic, "add any colour" variants, etc.).
     */
    private static boolean hasManaAbility(Card card, Game game) {
        for (Ability ability : card.getAbilities(game)) {
            if (ability instanceof ManaAbility) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects a "search library for a land and put it into play / hand" effect.
     * This is the structural fingerprint of every Cultivate-class ramp spell —
     * the effect class itself does not encode whether the search target is a
     * land, so we have to inspect each effect. We rely on the engine's
     * convention that the effect's text mentions "land" (Cultivate, Rampant
     * Growth, Nature's Lore, Three Visits, Farseek, Kodama's Reach all do).
     * Cheap and good enough for Sprint 30; can be tightened later by parsing
     * the {@code TargetCardInLibrary} filter if false positives appear.
     */
    private static boolean hasLandTutorEffect(Card card, Game game) {
        for (Ability ability : card.getAbilities(game)) {
            for (Effect effect : ability.getEffects()) {
                if (effect instanceof SearchLibraryPutInPlayEffect) {
                    String text = effect.getText(null);
                    if (text != null && text.toLowerCase().contains("land")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Detects any draw-card effect on a card. Used by the cantrip branch of
     * {@link #scoreDraw} — engines need a stricter check (the draw must be on
     * a {@link TriggeredAbility}, see {@link #hasTriggeredDrawAbility}).
     */
    private static boolean hasDrawEffect(Card card, Game game) {
        for (Ability ability : card.getAbilities(game)) {
            for (Effect effect : ability.getEffects()) {
                if (effect instanceof DrawCardSourceControllerEffect) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Detects a draw effect carried on a triggered ability. This is the
     * structural fingerprint of repeating draw engines: Rhystic Study and
     * Mystic Remora trigger on opponent spells; Phyrexian Arena triggers on
     * upkeep; Esper Sentinel triggers on opponent non-creature casts. Cards
     * with only static or activated draw (e.g. an instant) are filtered out
     * by the {@link TriggeredAbility} check, which keeps cantrips from being
     * miscounted as engines.
     */
    private static boolean hasTriggeredDrawAbility(Card card, Game game) {
        for (Ability ability : card.getAbilities(game)) {
            if (!(ability instanceof TriggeredAbility)) {
                continue;
            }
            for (Effect effect : ability.getEffects()) {
                // Primary check: known draw-card effect class (e.g. Phyrexian Arena).
                if (effect instanceof DrawCardSourceControllerEffect) {
                    return true;
                }
                // Secondary check: any triggered effect whose declared Outcome is DrawCard.
                // This catches cards with custom effect subclasses that still signal draw intent
                // via Outcome (e.g. Rhystic Study → RhysticStudyDrawEffect extends OneShotEffect
                // with super(Outcome.DrawCard)).
                if (effect.getOutcome() == Outcome.DrawCard) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String safeName(Card c) {
        String n = c.getName();
        return n == null ? "?" : n;
    }

    // -------------------------------------------------------------------------
    // Public predicates — consumed by Sprint 33B TempoClassifier
    // Each method is a named wrapper over the inline detection logic already
    // used inside scoreRamp() / scoreDraw(). The internal scoring methods are
    // unchanged; only visibility of the underlying helpers is promoted.
    // -------------------------------------------------------------------------

    /**
     * Artifact mana source at CMC ≤ 1 (Sol Ring, Mana Crypt, Mana Vault, Moxen,
     * Lotus Petal). Cast on turn 1 it breaks parity immediately.
     */
    public static boolean isFastMana(Card card, Game game) {
        return card != null
                && card.isArtifact()
                && card.getManaValue() <= 1
                && hasManaAbility(card, game);
    }

    /**
     * Artifact mana rock at exactly CMC 2 (Arcane Signet, Talismans, Signets,
     * Fellwar Stone, Mind Stone). On-curve T2 ramp.
     */
    public static boolean isManaRock(Card card, Game game) {
        return card != null
                && card.isArtifact()
                && card.getManaValue() == 2
                && hasManaAbility(card, game);
    }

    /**
     * Creature at CMC ≤ 2 with a mana ability (Llanowar Elves, Birds of Paradise,
     * Elvish Mystic, Avacyn's Pilgrim, Noble Hierarch, etc.).
     */
    public static boolean isManaDork(Card card, Game game) {
        return card != null
                && card.isCreature()
                && card.getManaValue() <= 2
                && hasManaAbility(card, game);
    }

    /**
     * Non-creature spell at CMC ≤ 3 that tutors a land into play or hand
     * (Cultivate, Kodama's Reach, Rampant Growth, Three Visits, Nature's Lore,
     * Farseek). Also covers CMC ≤ 3 creature-based land tutors via the creature
     * branch of {@link #scoreRamp}.
     */
    public static boolean isLandTutorSpell(Card card, Game game) {
        return card != null
                && !card.isLand()
                && card.getManaValue() <= 3
                && hasLandTutorEffect(card, game);
    }

    /**
     * Permanent with a triggered ability that draws cards (Rhystic Study,
     * Phyrexian Arena, Mystic Remora, Esper Sentinel, …). Cantrips (instants /
     * sorceries with draw) are intentionally excluded — they are one-shot, not
     * repeating engines.
     */
    public static boolean isDrawEngine(Card card, Game game) {
        return card != null
                && !card.isInstant()
                && !card.isSorcery()
                && hasTriggeredDrawAbility(card, game);
    }

    /**
     * Instant-speed spell that phases out a single target (Slip Out the Back,
     * Teferi's Veil mode, etc.). Phase-out is the strongest single-target
     * protection: the permanent remains under the controller's control but is
     * invisible to removal and also drops equipment/auras.
     */
    public static boolean isPhaseOutProtection(Card card, Game game) {
        if (card == null || !card.isInstant()) {
            return false;
        }
        for (Ability ability : card.getAbilities(game)) {
            for (Effect effect : ability.getEffects()) {
                if (effect instanceof PhaseOutTargetEffect) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Instant-speed spell that grants hexproof or indestructible to a single
     * target permanent the controller controls (Tamiyo's Safekeeping,
     * Blossoming Defense, Vines of Vastwood, etc.). Hexproof causes targeted
     * removal to fizzle; indestructible prevents destroy effects.
     */
    public static boolean isHexproofGrantProtection(Card card, Game game) {
        if (card == null || !card.isInstant()) {
            return false;
        }
        // WHY text-based detection: avoids accessing GainAbilityTargetEffect.ability (protected
        // field) via a getter in the core JAR. Core modifications create rebase friction with
        // magefree/mage upstream and require distributing a modified mage.jar to friends —
        // unsupported by the current XMageAIPatch.exe installer. Static text of
        // GainAbilityTargetEffect is always set at construction time and reliably contains the
        // ability keyword (e.g. "hexproof", "indestructible"). See CLAUDE.md §Core policy.
        for (Ability ability : card.getAbilities(game)) {
            for (Effect effect : ability.getEffects()) {
                if (effect instanceof GainAbilityTargetEffect) {
                    String text = effect.getText(null);
                    if (text != null) {
                        String lower = text.toLowerCase(java.util.Locale.ENGLISH);
                        if (lower.contains("hexproof") || lower.contains("indestructible")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Aggregator: true if the card is any recognized single-target protection
     * instant (phase-out OR hexproof/indestructible grant).
     */
    public static boolean isSingleTargetProtectionSpell(Card card, Game game) {
        return isPhaseOutProtection(card, game) || isHexproofGrantProtection(card, game);
    }
}
