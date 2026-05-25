package mage.player.ai.perf;

import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.score.GameStateEvaluator2;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Sprint 34.5 — Target Enumeration Cap.
 *
 * WHY THIS EXISTS: PlayerImpl.addTargetOptions() generates the cartesian product of all
 * valid target combinations before the minimax tree expands any node. With 50+ permanents,
 * "choose up to 3 target permanents" yields C(60,3)=34,220 Ability copies — enough to stall
 * the FutureTask for 60s before a single node is scored. Observed as 135 timeout events
 * crashing a Commander game at T34 (2026-05-24, board=69). Humans discard 99%+ of those
 * combinations in <1s by scanning for relevant targets; this class does the same.
 *
 * DESIGN: called from SimulatedPlayer2.getPlayableOptions() after super generates the full
 * list. If size > MAX_TARGET_OPTIONS_PER_ABILITY, rank by threat (bad abilities) or permanent
 * value (good abilities) and return top-K. No core Mage change required.
 *
 * THREAD SAFETY: same single-bot-at-a-time assumption as AiPerformanceLog. pendingChatLogs
 * is synchronized for the FutureTask→game-thread boundary; all other fields are read-only.
 */
public class TargetEnumerationCap {

    private static final Logger logger = Logger.getLogger(TargetEnumerationCap.class);

    // Cap: max target-combination options per ability before ranking kicks in.
    // WHY 50: 99% of spells have ≤10 relevant targets on any board. Above 50 = combinatorial
    // explosion of "choose up to N" on large boards. Top-50 ranked by threat >>> 0 (timeout).
    // Calibrate down if logs show cap firing on small boards; up if quality degrades in tests.
    public static final int MAX_TARGET_OPTIONS_PER_ABILITY = 50;

    // Threat normalizer: same constant as PossibleTargetsComparator (Sprint 4).
    // At 5000 threat (decent board), a permanent scores 2x. At 10000 (dominant), 3x.
    private static final int THREAT_NORMALIZER = 5000;

    // Sprint 34.6: ThreadLocal cap override for REDUCED regime (BoardComplexityGuard).
    // WHY ThreadLocal: SimulatedPlayer2 runs inside a FutureTask on a separate thread.
    // The override must be invisible to the game thread and to other bot simulations.
    // Must be cleared in a finally block to avoid leaking between turns.
    private static final ThreadLocal<Integer> CAP_OVERRIDE = new ThreadLocal<>();

    /** Override the effective cap for this thread. Call from SimulatedPlayer2 before simulateOptions(). */
    public static void setCapOverride(int cap) {
        CAP_OVERRIDE.set(cap);
    }

    /** Clear the cap override. Must be called in a finally block matching setCapOverride(). */
    public static void clearCapOverride() {
        CAP_OVERRIDE.remove();
    }

    /** Returns the active cap: override (if set) or the default MAX_TARGET_OPTIONS_PER_ABILITY. */
    public static int effectiveCap() {
        Integer override = CAP_OVERRIDE.get();
        return (override != null) ? override : MAX_TARGET_OPTIONS_PER_ABILITY;
    }

    // Chat log queue: filled during simulation thread (FutureTask), flushed to real game
    // chat by CP6.addActionsTimed() after task.get() returns. Uses synchronizedList because
    // simulation runs on a different thread than the game loop.
    private static final List<String> pendingChatLogs =
            Collections.synchronizedList(new ArrayList<>());

    // ── Lifecycle hooks (called from CP6) ─────────────────────────────────────

    /** Clear stale logs before each addActionsTimed() call. */
    public static void beginTurn() {
        pendingChatLogs.clear();
    }

    /**
     * Flush accumulated cap messages to game chat.
     * Call from CP6 with the real game after FutureTask resolves (success or timeout).
     */
    public static void flushToGameLog(Game game, String playerName) {
        if (pendingChatLogs.isEmpty()) return;
        List<String> snapshot;
        synchronized (pendingChatLogs) {
            snapshot = new ArrayList<>(pendingChatLogs);
            pendingChatLogs.clear();
        }
        for (String msg : snapshot) {
            game.fireStatusEvent("[AI:" + playerName + "] " + msg, false, false);
        }
    }

    // ── Core cap logic ────────────────────────────────────────────────────────

    /**
     * Cap options to MAX_TARGET_OPTIONS_PER_ABILITY ranked entries.
     * Must only be called when options.size() > MAX_TARGET_OPTIONS_PER_ABILITY.
     *
     * @param options    full list from super.getPlayableOptions()
     * @param ability    the source ability (used for effect-outcome detection)
     * @param game       simulation game (for scoring)
     * @param playerId   the bot's player ID
     * @return ranked top-K list (size == MAX_TARGET_OPTIONS_PER_ABILITY or less)
     */
    public static List<Ability> cap(List<Ability> options, Ability ability,
                                    Game game, UUID playerId) {
        int originalSize = options.size();

        boolean bad = true;
        boolean good = true;
        for (Effect effect : ability.getEffects()) {
            if (effect.getOutcome().isGood()) {
                bad = false;
            } else {
                good = false;
            }
        }

        List<Ability> capped;
        if (bad) {
            capped = topKByScore(options, game, playerId, true);
        } else if (good) {
            capped = topKByScore(options, game, playerId, false);
        } else {
            // Neutral/mixed effects: no meaningful ranking available; keep first K.
            // These are unusual (most spells are clearly bad or good); quality impact is low.
            capped = new ArrayList<>(options.subList(0, Math.min(effectiveCap(), options.size())));
        }

        AiPerformanceLog.recordTargetCapHit();

        String type = bad ? "bad" : (good ? "good" : "neutral");
        String msg = String.format("[TARGET-CAP] %d → %d [%s] (%s)",
                originalSize, capped.size(), type, ability.getRule());
        logger.warn(msg);
        pendingChatLogs.add(msg);

        return capped;
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private static List<Ability> topKByScore(List<Ability> options, Game game,
                                              UUID playerId, boolean bad) {
        return options.stream()
                .sorted(Comparator.comparingInt(
                        (Ability o) -> scoreOption(o, game, playerId, bad)).reversed())
                .limit(effectiveCap())
                .collect(Collectors.toList());
    }

    private static int scoreOption(Ability option, Game game, UUID playerId, boolean bad) {
        int score = 0;
        for (UUID targetId : getAllTargetIds(option)) {
            Permanent perm = game.getPermanent(targetId);
            if (perm != null) {
                int permScore = GameStateEvaluator2.evaluatePermanent(perm, game, false);
                if (bad && !perm.getControllerId().equals(playerId)) {
                    // Weight by controller threat (same formula as PossibleTargetsComparator Sprint 4).
                    int threatScore = GameStateEvaluator2.evaluatePlayerThreat(
                            perm.getControllerId(), game);
                    permScore += permScore * threatScore / THREAT_NORMALIZER;
                }
                score += permScore;
            } else {
                // Target is a player (e.g. damage spell targeting opponent)
                if (bad) {
                    Player player = game.getPlayer(targetId);
                    if (player != null && !targetId.equals(playerId)) {
                        score += GameStateEvaluator2.evaluatePlayerThreat(targetId, game);
                    }
                }
            }
        }
        return score;
    }

    private static List<UUID> getAllTargetIds(Ability option) {
        List<UUID> ids = new ArrayList<>();
        option.getTargets().forEach(target -> ids.addAll(target.getTargets()));
        return ids;
    }
}
