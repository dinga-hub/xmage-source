package mage.player.ai.score;

import mage.abilities.effects.common.PhaseOutAllEffect;
import mage.abilities.effects.common.continuous.GainAbilityAllEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.TrampleAbility;
import mage.cards.Card;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.constants.Outcome;
import mage.constants.Zone;
import mage.player.ai.perf.AiPerformanceLog;

/**
 * @author nantuko
 * <p>
 * This evaluator is only good for two player games
 */
public final class GameStateEvaluator2 {

    private static final Logger logger = Logger.getLogger(GameStateEvaluator2.class);

    public static final int WIN_GAME_SCORE = 100000000;
    public static final int LOSE_GAME_SCORE = -WIN_GAME_SCORE;

    public static final int HAND_CARD_SCORE = 5;

    // Each mana floating in the pool = opportunity to cast an instant, counter a spell,
    // or use a combat trick. Scoring it positively deters wasting mana on trivial effects.
    // Value: spending {1}{B} (2 mana) on a useless ability must provide >200 score to justify it.
    private static final int FLOATING_MANA_VALUE = 100;

    // Sprint 18: context-sensitive reservation bonus for untapped mana when bot has answers in hand.
    // The bonus is added per mana that could cover the cheapest instant/protection in hand.
    // Scale by self-position: archenemy protects lead; trailing focuses on development.
    // Calibration: ARCHENEMY=300 means a 2-mana Counterspell in hand makes tapping out cost 600 pts —
    // enough to outweigh most non-essential spells when you're already ahead.
    private static final int RESERVE_ARCHENEMY_BONUS_PER_MANA = 300;
    private static final int RESERVE_LEADING_BONUS_PER_MANA = 200;
    private static final int RESERVE_PARITY_BONUS_PER_MANA = 100;
    private static final int RESERVE_MASS_PROTECTION_BONUS_PER_MANA = 400;

    // Mass protection detection uses Option C (effect-based primary + small name supplementary).
    //
    // Why NOT name-based only: the list is infinite and gets stale with every new set.
    // Why NOT effect-only: some cards protect via exile-and-return (Eerie Interlude) or complex
    //   multi-modal effects that don't reduce to a single GainAbilityAllEffect or PhaseOutAllEffect.
    // Why NOT hexproof: hexproof only blocks targeted removal, not "destroy all" boardwipes.
    //   (Diego confirmed: hexproof = targeted protection, not mass protection.)
    //
    // Primary detection (covers the vast majority of cases automatically):
    //   - GainAbilityAllEffect whose text contains "indestructible" → Heroic Intervention,
    //     Unbreakable Formation, Boros Charm mode 2, Flawless Maneuver, Make a Stand, etc.
    //   - PhaseOutAllEffect → Teferi's Protection (phases out all your permanents)
    //
    // Supplementary list (exile-and-return type, not caught by effect scan):
    //   These achieve the same result (survive a boardwipe) through temporary exile.
    //   Kept intentionally small — add only when a card is confirmed undetectable by effects.
    private static final Set<String> MASS_PROTECTION_SUPPLEMENTARY = new HashSet<>(Arrays.asList(
            "Eerie Interlude",   // exile creatures you control → return after boardwipe resolves
            "Semester's End",    // exile permanents you control with counters → same
            "Scapegoat"          // bounce all your creatures → avoids destroy/exile boardwipes
    ));

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game) {
        return evaluate(playerId, game, true);
    }

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game, boolean useCombatPermanentScore) {
        // Sprint 34: count evaluator calls per addActionsTimed() to measure α-β tree depth/breadth.
        // One call per node = one node scored. Total calls = effective simulated nodes.
        AiPerformanceLog.recordEvaluatorCall();
        // TODO: add multi opponents support, so AI can take better actions
        Player player = game.getPlayer(playerId);
        // must find all leaved opponents
        Player opponent = game.getPlayer(game.getOpponents(playerId, false).stream().findFirst().orElse(null));
        if (opponent == null) {
            return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
        }

        if (game.checkIfGameIsOver()) {
            if (player.hasLost()
                    || opponent.hasWon()) {
                return new PlayerEvaluateScore(playerId, LOSE_GAME_SCORE);
            }
            if (opponent.hasLost()
                    || player.hasWon()) {
                return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
            }
        }

        int playerLifeScore = 0;
        int opponentLifeScore = 0;
        if (player.getLife() <= 0) { // we don't want a tie
            playerLifeScore = ArtificialScoringSystem.LOSE_GAME_SCORE;
        } else if (opponent.getLife() <= 0) {
            playerLifeScore = ArtificialScoringSystem.WIN_GAME_SCORE;
        } else {
            playerLifeScore = ArtificialScoringSystem.getLifeScore(player.getLife());
            opponentLifeScore = ArtificialScoringSystem.getLifeScore(opponent.getLife()); // TODO: minus
        }

        int playerPermanentsScore = 0;
        int opponentPermanentsScore = 0;
        try {
            StringBuilder sbPlayer = new StringBuilder();
            StringBuilder sbOpponent = new StringBuilder();

            // add values of player
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
                int onePermScore = evaluatePermanent(permanent, game, useCombatPermanentScore);
                playerPermanentsScore += onePermScore;
                if (logger.isDebugEnabled()) {
                    sbPlayer.append(permanent.getName()).append('[').append(onePermScore).append("] ");
                }
            }
            if (logger.isDebugEnabled()) {
                sbPlayer.insert(0, playerPermanentsScore + " - ");
                sbPlayer.insert(0, "Player..: ");
                logger.debug(sbPlayer);
            }

            // add values of opponent
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(opponent.getId())) {
                int onePermScore = evaluatePermanent(permanent, game, useCombatPermanentScore);
                opponentPermanentsScore += onePermScore;
                if (logger.isDebugEnabled()) {
                    sbOpponent.append(permanent.getName()).append('[').append(onePermScore).append("] ");
                }
            }
            if (logger.isDebugEnabled()) {
                sbOpponent.insert(0, opponentPermanentsScore + " - ");
                sbOpponent.insert(0, "Opponent: ");
                logger.debug(sbOpponent);
            }
        } catch (Throwable t) {
        }

        // TODO: add card evaluator like permanent evaluator
        // - same card on battlefield must score x2 compared to hand, so AI will want to play it;
        // - other zones must score cards same way, example: battlefield = x, hand = x * 0.1, graveyard = x * 0.5, exile = x * 0.3
        // - possible bug in wrong score: instant and sorcery on hand will be more valuable compared to other zones,
        //   so AI will keep it in hand. Possible fix: look at card type and apply zones multipliers due special
        //   table like:
        //   * battlefield needs in creatures and enchantments/auras;
        //   * hand needs in instants and sorceries
        //   * graveyard needs in anything after battlefield and hand;
        //   * exile needs in nothing;
        //   * commander zone needs in nothing;
        // - additional improve: use revealed data to score opponent's hand:
        //   * known card by card evaluator;
        //   * unknown card by max value (so AI will use reveal to make opponent's total score lower -- is it helps???)
        int playerHandScore = player.getHand().size() * HAND_CARD_SCORE;
        int opponentHandScore = opponent.getHand().size() * HAND_CARD_SCORE;

        // Score floating mana as a resource. Mana represents flexibility — it can be used for
        // a removal spell, counterspell, or combat trick on a future priority window. By giving
        // unspent mana a positive score, the bot pays an opportunity cost when it spends mana
        // on a low-value activation (e.g. Regenerate with no attack coming, minor pump, trivial
        // buff) vs. simply passing and keeping mana open.
        // Calibration: each mana = 100 pts, so a 2-mana activation must provide >200 score to
        // justify being cast over passing.
        int playerManaScore = player.getManaPool().getMana().count() * FLOATING_MANA_VALUE;

        // Sprint 18: bonus for keeping untapped mana when bot has relevant answers in hand.
        // Context-sensitive: higher when archenemy/leading, zero when trailing or early game.
        int reserveBonus = computeReserveManaBonus(playerId, player, game);

        int score = (playerLifeScore - opponentLifeScore)
                + (playerPermanentsScore - opponentPermanentsScore)
                + (playerHandScore - opponentHandScore)
                + playerManaScore  // opportunity cost: preserve mana > waste it on trivial effects
                + reserveBonus;    // Sprint 18: context-sensitive mana reservation incentive
        logger.debug(score
                + " total Score (life:" + (playerLifeScore - opponentLifeScore)
                + " permanents:" + (playerPermanentsScore - opponentPermanentsScore)
                + " hand:" + (playerHandScore - opponentHandScore) + ')');
        return new PlayerEvaluateScore(
                playerId,
                playerLifeScore, playerHandScore, playerPermanentsScore,
                opponentLifeScore, opponentHandScore, opponentPermanentsScore);
    }

    // ── Sprint 18: Mana Reservation Helpers ────────────────────────────────────

    // Promoted to public in Sprint 19 so CounterOptimizer / ProtectionOptimizer can
    // reuse the same self-position thresholds Sprint 18 already calibrated.
    public enum SelfPosition { ARCHENEMY, LEADING, PARITY, TRAILING }

    /**
     * Classifies the AI player's board position relative to opponents.
     * Uses evaluatePlayerThreat scores (board + ramp + hand + life).
     * Thresholds: archenemy >1.4× avg, leading >avg, trailing <0.7× avg.
     */
    public static SelfPosition classifySelfPosition(UUID playerId, Game game) {
        int selfThreat = evaluatePlayerThreat(playerId, game);
        int totalOpp = 0;
        int oppCount = 0;
        for (UUID oppId : game.getOpponents(playerId)) {
            Player opp = game.getPlayer(oppId);
            if (opp != null && opp.isInGame()) {
                totalOpp += evaluatePlayerThreat(oppId, game);
                oppCount++;
            }
        }
        if (oppCount == 0) return SelfPosition.PARITY;
        int avgOpp = totalOpp / oppCount;
        if (avgOpp == 0) return SelfPosition.PARITY;
        if (selfThreat > avgOpp * 140 / 100) return SelfPosition.ARCHENEMY;
        if (selfThreat > avgOpp) return SelfPosition.LEADING;
        if (selfThreat < avgOpp * 70 / 100) return SelfPosition.TRAILING;
        return SelfPosition.PARITY;
    }

    /**
     * Early game = no player has developed a meaningful board yet.
     * Threshold: nobody has 4+ non-land permanents.
     * (More accurate than a turn number: faster decks develop in 2-3 turns.)
     */
    public static boolean isEarlyGame(Game game) {
        for (Player p : game.getState().getPlayers().values()) {
            if (p == null || !p.isInGame()) continue;
            long nonLands = game.getBattlefield().getAllActivePermanents(p.getId()).stream()
                    .filter(perm -> !perm.isLand(game))
                    .count();
            if (nonLands >= 4) return false;
        }
        return true;
    }

    /**
     * Returns the CMC of the cheapest instant in hand, or 0 if none.
     * Flash creatures intentionally excluded for simplicity (refine in Sprint 19).
     */
    private static int findCheapestInstantCMC(Player player, Game game) {
        int cheapest = Integer.MAX_VALUE;
        for (Card card : player.getHand().getCards(game)) {
            if (card.isInstant() && card.getManaValue() > 0) {
                cheapest = Math.min(cheapest, card.getManaValue());
            }
        }
        return cheapest == Integer.MAX_VALUE ? 0 : cheapest;
    }

    /**
     * Returns the CMC of a mass protection spell in hand, or 0 if none.
     *
     * Detection strategy (Option C — see MASS_PROTECTION_SUPPLEMENTARY comment):
     *   Primary: scan card effects for GainAbilityAllEffect containing "indestructible"
     *            or PhaseOutAllEffect (covers Teferi's Protection).
     *   Fallback: supplementary name list for exile-and-return cards.
     *
     * Note: hexproof is intentionally excluded — it protects against targeted removal,
     * not boardwipes. Only indestructible/phase-out survive a "destroy all" or "exile all".
     */
    private static int findMassProtectionCMC(Player player, Game game) {
        for (Card card : player.getHand().getCards(game)) {
            if (card.getManaValue() <= 0) continue;
            if (isMassProtectionCard(card)) {
                return card.getManaValue();
            }
        }
        return 0;
    }

    /**
     * Returns true if the card grants mass protection (survives a boardwipe when cast).
     * See MASS_PROTECTION_SUPPLEMENTARY for the detection rationale.
     * Promoted to public in Sprint 19 so ProtectionOptimizer can identify mass-protection
     * spells in the candidate action list.
     */
    public static boolean isMassProtectionCard(Card card) {
        for (Ability cardAbility : card.getAbilities()) {
            for (Effect effect : cardAbility.getEffects()) {
                // Phase-out = all your permanents become untargetable and survive boardwipes
                if (effect instanceof PhaseOutAllEffect) {
                    return true;
                }
                // Mass indestructible grant: detect via effect type + text (avoids accessing
                // the protected `ability` field inside GainAbilityAllEffect directly).
                // getText(null) returns the staticText or a generated string like
                // "permanents you control gain indestructible until end of turn".
                if (effect instanceof GainAbilityAllEffect) {
                    String text = effect.getText(null);
                    if (text != null && text.toLowerCase().contains("indestructible")) {
                        return true;
                    }
                }
            }
        }
        // Fallback: exile-and-return type (achieves same result, not caught above)
        return MASS_PROTECTION_SUPPLEMENTARY.contains(card.getName());
    }

    /**
     * Board is strong enough to warrant protecting with mass protection.
     * Triggers when: 4+ creatures, OR 2+ high-value permanents (≥1200 pts), OR
     * a big evasive creature (power ≥5 + flying/trample).
     */
    public static boolean isBoardStrong(UUID playerId, Game game) {
        java.util.List<Permanent> perms = game.getBattlefield().getAllActivePermanents(playerId);
        long creatures = perms.stream().filter(p -> p.isCreature(game)).count();
        if (creatures >= 4) return true;
        long highValue = perms.stream()
                .filter(p -> evaluatePermanent(p, game, false) >= 1200)
                .count();
        if (highValue >= 2) return true;
        return perms.stream()
                .filter(p -> p.isCreature(game) && p.getPower().getValue() >= 5)
                .anyMatch(p -> p.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                        || p.getAbilities().containsKey(TrampleAbility.getInstance().getId()));
    }

    /** Counts untapped mana-producing permanents (lands + rocks + dorks). */
    private static int countUntappedMana(UUID playerId, Game game) {
        return (int) game.getBattlefield().getAllActivePermanents(playerId).stream()
                .filter(p -> !p.isTapped())
                .filter(p -> !p.getAbilities()
                        .getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, playerId, game).isEmpty())
                .count();
    }

    /**
     * Sprint 18: context-sensitive bonus for untapped mana when the bot has relevant answers in hand.
     *
     * Logic (from Diego's Commander wisdom):
     *  - Early game → 0: everyone is developing, tapping out is fine.
     *  - Trailing → 0: wipe helps, need to catch up.
     *  - Mass protection in hand + strong board → highest bonus (400/mana): survive a wipe = power swing.
     *  - Archenemy + instant in hand → 300/mana: protect lead.
     *  - Leading + instant in hand → 200/mana: moderate incentive to hold.
     *  - Parity + instant in hand → 100/mana: slight nudge (matches floating mana value).
     *
     * Only the mana covering the cheapest answer CMC gets the bonus (cap = cheapestCMC).
     */
    private static int computeReserveManaBonus(UUID playerId, Player player, Game game) {
        if (isEarlyGame(game)) return 0;

        SelfPosition position = classifySelfPosition(playerId, game);
        if (position == SelfPosition.TRAILING) return 0;

        int untapped = countUntappedMana(playerId, game);
        if (untapped == 0) return 0;

        // Highest priority: mass protection with a strong board to protect
        int massCMC = findMassProtectionCMC(player, game);
        if (massCMC > 0 && isBoardStrong(playerId, game)) {
            int reservable = Math.min(untapped, massCMC);
            logger.debug("[RESERVE] Mass protection mode: " + reservable + " mana locked at 400/mana");
            return reservable * RESERVE_MASS_PROTECTION_BONUS_PER_MANA;
        }

        // Regular instant reservation
        int cheapestCMC = findCheapestInstantCMC(player, game);
        if (cheapestCMC == 0) return 0;

        int bonusPerMana;
        switch (position) {
            case ARCHENEMY: bonusPerMana = RESERVE_ARCHENEMY_BONUS_PER_MANA; break;
            case LEADING:   bonusPerMana = RESERVE_LEADING_BONUS_PER_MANA;   break;
            case PARITY:    bonusPerMana = RESERVE_PARITY_BONUS_PER_MANA;    break;
            default:        return 0;
        }

        int reservable = Math.min(untapped, cheapestCMC);
        logger.debug("[RESERVE] position=" + position + " cheapestInstant=" + cheapestCMC
                + " reservable=" + reservable + " bonus=" + (reservable * bonusPerMana));
        return reservable * bonusPerMana;
    }

    // ── End Sprint 18 ──────────────────────────────────────────────────────────

    /**
     * Evaluates how threatening a player is in a multiplayer game (Commander).
     * Higher score = bigger threat = priority attack/removal target.
     *
     * Factors:
     * - Board presence (permanents score x3) — most important signal
     * - Cards in hand x50 — hidden potential
     * - Life >= 30 bonus — player hasn't been focused yet
     */
    public static int evaluatePlayerThreat(UUID targetPlayerId, Game game) {
        Player target = game.getPlayer(targetPlayerId);
        if (target == null || !target.isInGame()) {
            return 0;
        }

        int score = 0;

        // Board presence is the strongest threat signal
        int rampCount = 0;
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(targetPlayerId)) {
            score += evaluatePermanent(perm, game, false) * 3;
            // Count mana sources: lands, mana rocks, mana dorks, treasures, etc.
            if (!perm.getAbilities().getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, targetPlayerId, game).isEmpty()) {
                rampCount++;
            }
        }

        // Ramp: each mana source beyond the baseline (4) adds threat.
        // A player with 8 mana sources is significantly more dangerous than one with 4.
        int extraRamp = Math.max(0, rampCount - 4);
        score += extraRamp * 120;

        // Cards in hand = hidden potential
        score += target.getHand().size() * 50;

        // Commander starts at 40 life. High life means the player hasn't been targeted yet — still dangerous.
        // 35+ = pristine (threat bonus); 15 or less = under pressure; 8 or less = almost dead (deprioritize).
        if (target.getLife() >= 35) {
            score += 150;
        }

        // Low life = lower threat priority. In Commander, players typically don't pile on a
        // near-dead opponent unless the kill is available — it wastes resources and draws attention.
        // The lethal-kill override (+1,000,000) in declareAttackers() still fires when death is
        // actually reachable, so this penalty only affects non-lethal attacks on weakened players.
        if (target.getLife() <= 15) {
            score -= 200;
        }
        if (target.getLife() <= 8) {
            score -= 400; // cumulative: -600 total when nearly dead
        }

        return score;
    }

    public static int evaluatePermanent(Permanent permanent, Game game, boolean useCombatPermanentScore) {
        // prevent AI from attaching bad auras to its own permanents ex: Brainwash and Demonic Torment (no immediate penalty on the battlefield)
        int value = 0;
        if (!permanent.getAttachments().isEmpty()) {
            for (UUID attachmentId : permanent.getAttachments()) {
                Permanent attachment = game.getPermanent(attachmentId);
                for (Ability a : attachment.getAbilities(game)) {
                    for (Effect e : a.getEffects()) {
                        if (e.getOutcome().equals(Outcome.Detriment)
                                && attachment.getControllerId().equals(permanent.getControllerId())) {
                            value -= 1000;  // seems to work well ; -300 is not effective enough
                        }
                    }
                }
            }
        }
        value += ArtificialScoringSystem.getFixedPermanentScore(game, permanent);
        value += ArtificialScoringSystem.getDynamicPermanentScore(game, permanent);
        if (useCombatPermanentScore) {
            value += ArtificialScoringSystem.getCombatPermanentScore(game, permanent);
        }
        return value;
    }

    public static class PlayerEvaluateScore {

        private UUID playerId;
        private int playerLifeScore = 0;
        private int playerHandScore = 0;
        private int playerPermanentsScore = 0;

        private int opponentLifeScore = 0;
        private int opponentHandScore = 0;
        private int opponentPermanentsScore = 0;

        private int specialScore = 0; // special score (ignore all others, e.g. for win/lose game states)

        public PlayerEvaluateScore(UUID playerId, int specialScore) {
            this.playerId = playerId;
            this.specialScore = specialScore;
        }

        public PlayerEvaluateScore(UUID playerId,
                                   int playerLifeScore, int playerHandScore, int playerPermanentsScore,
                                   int opponentLifeScore, int opponentHandScore, int opponentPermanentsScore) {
            this.playerId = playerId;
            this.playerLifeScore = playerLifeScore;
            this.playerHandScore = playerHandScore;
            this.playerPermanentsScore = playerPermanentsScore;
            this.opponentLifeScore = opponentLifeScore;
            this.opponentHandScore = opponentHandScore;
            this.opponentPermanentsScore = opponentPermanentsScore;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public int getPlayerScore() {
            return playerLifeScore + playerHandScore + playerPermanentsScore;
        }

        public int getOpponentScore() {
            return opponentLifeScore + opponentHandScore + opponentPermanentsScore;
        }

        public int getTotalScore() {
            if (specialScore != 0) {
                return specialScore;
            } else {
                return getPlayerScore() - getOpponentScore();
            }
        }

        public int getPlayerLifeScore() {
            return playerLifeScore;
        }

        public int getPlayerHandScore() {
            return playerHandScore;
        }

        public int getPlayerPermanentsScore() {
            return playerPermanentsScore;
        }

        public String getPlayerInfoFull() {
            return "Life:" + playerLifeScore
                    + ", Hand:" + playerHandScore
                    + ", Perm:" + playerPermanentsScore;
        }

        public String getPlayerInfoShort() {
            return "L:" + playerLifeScore
                    + ",H:" + playerHandScore
                    + ",P:" + playerPermanentsScore;
        }

        public String getOpponentInfoFull() {
            return "Life:" + opponentLifeScore
                    + ", Hand:" + opponentHandScore
                    + ", Perm:" + opponentPermanentsScore;
        }

        public String getOpponentInfoShort() {
            return "L:" + opponentLifeScore
                    + ",H:" + opponentHandScore
                    + ",P:" + opponentPermanentsScore;
        }
    }
}
