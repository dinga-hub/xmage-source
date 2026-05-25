package mage.player.ai.perf;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sprint 34 — Scalability Foundation: lightweight performance telemetry for the minimax engine.
 *
 * WHY THIS EXISTS: each AI sprint adds work per minimax node (classifiers, optimizers, evaluator
 * lookups). In Commander late game (board 40-50 permanents) this causes addActionsTimed() to
 * timeout and the bot defaults to PASS — observed at T24/T28 (2026-05-24, game 8ed15204).
 * Before deciding between a full architectural refactor (MainPhasePolicy) or a tactical guard
 * (BoardComplexityGuard), we need empirical data: WHERE does the cost go?
 *
 * USAGE:
 *   1. Flip AI_PERF_LOG = true in this file.
 *   2. Build + deploy: build-and-deploy-ai.bat
 *   3. Play 3 Commander 4p games to completion (T25+ each).
 *   4. CSVs appear in mage-server/ as ai_perf_<gameId>.csv
 *   5. Send CSVs to Claude for Sprint 34 Etapa 3 analysis.
 *   6. Flip AI_PERF_LOG = false, rebuild before any release.
 *
 * OVERHEAD:
 *   - Flag false (default / prod): 1 boolean check per hook ≈ 1 ns. Imperceptible.
 *   - Flag true (data collection): ~3-5% per turn. Measurable but acceptable for diagnostics.
 *
 * THREAD SAFETY NOTE: designed for single-game local use. Uses a static volatile accumulator
 * that would be clobbered if two games ran addActionsTimed() concurrently. Acceptable for
 * Diego's local setup; do not use in multiplayer servers.
 */
public class AiPerformanceLog {

    // ── Feature flag ──────────────────────────────────────────────────────────
    // WHY false: zero overhead in prod. Flip to true only for Sprint 34 data-collection
    // sessions. Reset to false before any release — same discipline as AI_DEBUG_LOG.
    // If you forget: build will still work, but the CSV will be written every game,
    // growing the mage-server/ directory indefinitely.
    public static final boolean AI_PERF_LOG = false;

    // ── Per-call accumulator ───────────────────────────────────────────────────
    /**
     * Holds all counters for one addActionsTimed() call.
     * Context fields (turn, phase, etc.) are immutable after construction.
     * Counter fields are AtomicLong for safe cross-thread increment:
     * the simulation FutureTask runs on a different thread than the game loop.
     */
    public static final class PerfAccumulator {
        // Context — captured from root.game before the FutureTask is submitted
        public final String gameId;
        public final int    turn;
        public final String phase;        // e.g. "MAIN1", "MAIN2", "DECLARE_ATTACKERS"
        public final String player;       // bot name
        public final int    boardSize;    // total permanents (all players)
        public final int    handSize;     // bot's hand
        public final int    stackSize;
        public final long   startNanos;

        // Counters — written by simulation thread, read by game thread after task.get()
        // evaluator: GameStateEvaluator2.evaluate() calls (one per node in the α-β tree)
        public final AtomicLong evalCalls       = new AtomicLong();
        // classifiers: called inside optimize() on every node expansion
        public final AtomicLong stackClassCalls = new AtomicLong();  // StackThreatClassifier
        public final AtomicLong tempoClassCalls = new AtomicLong();  // TempoClassifier
        public final AtomicLong tier8ClassCalls = new AtomicLong();  // Tier8Subcategorizer
        // optimizer pipeline total time across all node expansions (ms)
        // WHY separate: optimizer time = classifiers + TreeOptimizer.optimize() overhead.
        // Comparing optimizeMs to totalMs shows what fraction the pipeline costs.
        public final AtomicLong optimizeMs      = new AtomicLong();
        // Sprint 34.5: number of times TargetEnumerationCap fired during this addActionsTimed() call.
        // Non-zero = combinatorial explosion was detected and capped; zero = board small enough.
        public final AtomicLong targetCapHits          = new AtomicLong();
        // Sprint 34.6: circuit breaker activations. Non-zero = board was extreme enough to
        // trigger REDUCED or BYPASS regime (see BoardComplexityGuard for thresholds).
        public final AtomicLong circuitBreakerReduced  = new AtomicLong();
        public final AtomicLong circuitBreakerBypass   = new AtomicLong();

        PerfAccumulator(String gameId, int turn, String phase, String player,
                        int boardSize, int handSize, int stackSize) {
            this.gameId    = gameId;
            this.turn      = turn;
            this.phase     = phase;
            this.player    = player;
            this.boardSize = boardSize;
            this.handSize  = handSize;
            this.stackSize = stackSize;
            this.startNanos = System.nanoTime();
        }
    }

    // ── Active accumulator ─────────────────────────────────────────────────────
    // volatile: ensures the simulation thread sees the reference written by the game thread
    // before the FutureTask starts executing. Safe because addActionsTimed() blocks (task.get())
    // and only one bot thinks at a time in XMage Commander turns.
    private static volatile PerfAccumulator current = null;

    // ── API: called from ComputerPlayer6.addActionsTimed() ────────────────────

    /** Initialize accumulator for one addActionsTimed() call. Call before executing the FutureTask. */
    public static void beginCall(String gameId, int turn, String phase, String player,
                                 int boardSize, int handSize, int stackSize) {
        if (!AI_PERF_LOG) return;
        current = new PerfAccumulator(gameId, turn, phase, player, boardSize, handSize, stackSize);
    }

    /**
     * Record time spent in the optimizer pipeline for one node expansion.
     * Call wrapping optimize(game, allActions) in simulatePriority().
     * Accumulates across all nodes in the α-β tree for this addActionsTimed() call.
     */
    public static void recordOptimizerTime(long nanos) {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.optimizeMs.addAndGet(nanos / 1_000_000L);
    }

    /**
     * Flush the accumulated row to CSV and clear the accumulator.
     * Call at the very end of addActionsTimed(), after task.get() resolves (or times out).
     *
     * @param timedOut true if the call ended via TimeoutException
     * @param decision short label for what was decided: "TIMEOUT", "COMPLETED", or "ERROR"
     */
    public static void endCall(boolean timedOut, String decision) {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        current = null;   // clear before writing so recursive/concurrent calls don't interfere
        if (acc == null) return;
        long totalMs = (System.nanoTime() - acc.startNanos) / 1_000_000L;
        writeRow(acc, totalMs, timedOut, decision);
    }

    // ── API: called from classifier static methods ─────────────────────────────
    // These are one-liners in each classifier's classify() method.
    // With flag false: cost = 1 boolean check + null check ≈ 2 ns.

    public static void recordEvaluatorCall() {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.evalCalls.incrementAndGet();
    }

    public static void recordStackClassifierCall() {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.stackClassCalls.incrementAndGet();
    }

    public static void recordTempoClassifierCall() {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.tempoClassCalls.incrementAndGet();
    }

    public static void recordTier8ClassifierCall() {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.tier8ClassCalls.incrementAndGet();
    }

    public static void recordTargetCapHit() {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.targetCapHits.incrementAndGet();
    }

    public static void recordCircuitBreakerReduced() {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.circuitBreakerReduced.incrementAndGet();
    }

    public static void recordCircuitBreakerBypass() {
        if (!AI_PERF_LOG) return;
        PerfAccumulator acc = current;
        if (acc != null) acc.circuitBreakerBypass.incrementAndGet();
    }

    // ── CSV output ─────────────────────────────────────────────────────────────

    private static final String CSV_HEADER =
            "gameId,turn,phase,player,boardSize,handSize,stackSize," +
            "totalMs,timedOut,optimizeMs," +
            "evalCalls,stackClassCalls,tempoClassCalls,tier8ClassCalls," +
            "targetCapHits,circuitBreakerReduced,circuitBreakerBypass," +
            "decision\n";

    private static void writeRow(PerfAccumulator acc, long totalMs, boolean timedOut, String decision) {
        // WHY user.dir: XMage server's working directory is mage-server/, same place as
        // mageserver.log — easy to find. No hardcoded path, survives XMage version changes.
        String fileName = "ai_perf_" + acc.gameId + ".csv";
        File file = new File(System.getProperty("user.dir"), fileName);
        boolean needsHeader = !file.exists();

        try (FileWriter fw = new FileWriter(file, true /*append*/)) {
            if (needsHeader) {
                fw.write(CSV_HEADER);
            }
            fw.write(String.format("%s,%d,%s,%s,%d,%d,%d,%d,%b,%d,%d,%d,%d,%d,%d,%d,%d,%s\n",
                    acc.gameId,
                    acc.turn,
                    acc.phase,
                    acc.player,
                    acc.boardSize,
                    acc.handSize,
                    acc.stackSize,
                    totalMs,
                    timedOut,
                    acc.optimizeMs.get(),
                    acc.evalCalls.get(),
                    acc.stackClassCalls.get(),
                    acc.tempoClassCalls.get(),
                    acc.tier8ClassCalls.get(),
                    acc.targetCapHits.get(),
                    acc.circuitBreakerReduced.get(),
                    acc.circuitBreakerBypass.get(),
                    decision.replace(",", ";")  // escape commas in card/action names
            ));
        } catch (IOException e) {
            // Silent fail — telemetry must never crash the game.
            // If CSV is missing after a session: check write permissions in mage-server/.
            // Common cause: server started from a read-only directory.
        }
    }
}
