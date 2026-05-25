package mage.player.ai.complexity;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.common.PassAbility;
import mage.game.Game;
import mage.player.ai.perf.AiPerformanceLog;
import mage.player.ai.tempo.TempoCategory;
import mage.player.ai.tempo.TempoClassifier;
import mage.players.Player;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 34.6 — Board Complexity Circuit Breaker.
 *
 * WHY THIS EXISTS: Sprint 34.5 (TargetEnumerationCap) caps combinatorial explosion per-ability.
 * But on extreme boards (60+ permanents, cascading stack triggers, multiple multi-target abilities
 * ready) the aggregate cost across all abilities can still overflow addActionsTimed(). This guard
 * detects those extreme states in simulatePriority() BEFORE simulation starts and degrades
 * gracefully instead of timing out.
 *
 * REGIMES:
 *   NORMAL  (score ≤ 80):  full minimax, default cap (50)
 *   REDUCED (80 < ≤ 150):  reduced cap (20) + maxDepth capped at 2
 *   BYPASS  (> 150):       skip minimax entirely; pick top-1 via TempoClassifier heuristic
 *
 * Thresholds calibrated from Sprint 34 empirical data (2026-05-24, board=69, 135 timeout events).
 * Refine with CSVs from AiPerformanceLog after deploy; see circuitBreakerReduced/Bypass columns.
 *
 * CALL SITE: SimulatedPlayer2.simulatePriority() — computed on the raw (non-sim) game BEFORE
 * createSimulationForAI() to avoid the overhead of cloning a game just for the score.
 *
 * PACKAGE: mage.player.ai.complexity (AI.MA module) rather than mage.player.ai.perf (AI module)
 * because this class imports TempoClassifier, which lives in AI.MA. Split-package avoided.
 */
public final class BoardComplexityGuard {

    private static final Logger logger = Logger.getLogger(BoardComplexityGuard.class);

    // ── Thresholds ────────────────────────────────────────────────────────────
    // Calibrate with post-deploy CSVs. Starting values derived from Sprint 34 data:
    // - "normal" game T30 board ≈ 30-45 permanents, stack usually 0 → score ≈ 30-45 (NORMAL)
    // - "stressed" game T34 board=69, stack≈2-4 → score ≈ 89-109 (REDUCED or boundary)
    // - "extreme" cascade/swarm: board=80, stack=8, 5 multi-target → score = 80+80+25=185 (BYPASS)
    public static final int THRESHOLD_REDUCED = 80;
    public static final int THRESHOLD_BYPASS  = 150;

    // Cap override and maxDepth for REDUCED regime
    public static final int REDUCED_CAP       = 20;
    public static final int REDUCED_MAX_DEPTH = 2;

    // ── Regime ────────────────────────────────────────────────────────────────
    public enum Regime { NORMAL, REDUCED, BYPASS }

    private BoardComplexityGuard() {}

    // ── Score & regime ────────────────────────────────────────────────────────

    /**
     * Compute a cheap board complexity score.
     *
     * score = boardPermanents + (stackSize × 10) + (multiTargetReadyCount × 5)
     *
     * multiTargetReady: count of non-mana playable abilities where at least one Target
     * slot has maxNumberOfTargets ≥ 2. Intentionally over-counts X-based targets (safe
     * direction: may push into REDUCED unnecessarily, never causes incorrect BYPASS).
     *
     * @param game     current game state (NOT the sim clone)
     * @param playerId the bot's player UUID
     */
    public static int computeComplexityScore(Game game, UUID playerId) {
        int board = game.getBattlefield().getAllPermanents().size();
        int stack = game.getStack().size();
        int multiTargetReady = countMultiTargetReady(game, playerId);
        return board + (stack * 10) + (multiTargetReady * 5);
    }

    public static Regime getRegime(int complexityScore) {
        if (complexityScore > THRESHOLD_BYPASS)  return Regime.BYPASS;
        if (complexityScore > THRESHOLD_REDUCED) return Regime.REDUCED;
        return Regime.NORMAL;
    }

    // ── Bypass selection ──────────────────────────────────────────────────────

    /**
     * Bypass mode: skip full minimax. Ranks non-mana playable abilities by TempoCategory
     * ordinal (TIER_2 best → TIER_OTHER worst) and returns [top-1, PassAbility].
     *
     * The PassAbility is always included so ComputerPlayer6's picker always has a valid
     * fallback. If no playable non-mana ability exists, returns [PassAbility] only.
     *
     * @param game        current game state (NOT the sim clone)
     * @param playerId    the bot's player UUID
     * @param score       pre-computed complexity score (for logging only)
     * @return action list for simulatePriority() — already in the correct format (reversed order)
     */
    public static List<Ability> bypassSelect(Game game, UUID playerId, int score) {
        Player player = game.getPlayer(playerId);
        List<Ability> result = new ArrayList<>();

        if (player != null) {
            List<ActivatedAbility> playables = player.getPlayable(game, true);
            ActivatedAbility best = null;
            TempoCategory bestTier = null;

            for (ActivatedAbility ab : playables) {
                if (ab.isManaAbility()) continue;
                TempoCategory tier = TempoClassifier.classify(ab, game, playerId);
                if (best == null || tier.ordinal() < bestTier.ordinal()) {
                    best = ab;
                    bestTier = tier;
                }
            }

            if (best != null) {
                logBypass(score, best, bestTier, game);
                result.add(best);
            } else {
                logBypass(score, null, null, game);
            }
        }

        result.add(new PassAbility());
        return result;
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    /** Log REDUCED activation and record telemetry counter. */
    public static void logReduced(int score, Game game, UUID playerId) {
        int board = game.getBattlefield().getAllPermanents().size();
        int stack = game.getStack().size();
        int multi = countMultiTargetReady(game, playerId);
        String msg = String.format(
                "[CIRCUIT-BREAKER] regime=REDUCED score=%d board=%d stack=%d multiTarget=%d (cap→%d, depth→%d)",
                score, board, stack, multi, REDUCED_CAP, REDUCED_MAX_DEPTH);
        logger.warn(msg);
        AiPerformanceLog.recordCircuitBreakerReduced();
    }

    private static void logBypass(int score, Ability chosen, TempoCategory tier, Game game) {
        int board = game.getBattlefield().getAllPermanents().size();
        int stack = game.getStack().size();
        String abilityName = chosen != null ? chosen.getRule() : "PassAbility";
        String tierName    = tier    != null ? tier.name()     : "NONE";
        String msg = String.format(
                "[CIRCUIT-BREAKER] regime=BYPASS score=%d board=%d stack=%d — top-1=%s tier=%s",
                score, board, stack, abilityName, tierName);
        logger.warn(msg);
        AiPerformanceLog.recordCircuitBreakerBypass();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int countMultiTargetReady(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        if (player == null) return 0;
        int count = 0;
        for (ActivatedAbility ab : player.getPlayable(game, true)) {
            if (ab.isManaAbility()) continue;
            for (Target target : ab.getTargets()) {
                if (target.getMaxNumberOfTargets() >= 2) {
                    count++;
                    break; // one multi-target slot found for this ability — move to next
                }
            }
        }
        return count;
    }
}
