package org.mage.test.AI.complexity;

import mage.player.ai.complexity.BoardComplexityGuard;
import mage.player.ai.complexity.BoardComplexityGuard.Regime;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Sprint 34.6 — Format 0 (pure unit): validates {@link BoardComplexityGuard#getRegime(int)}
 * threshold logic without requiring a Game object.
 *
 * Three synthetic boards cover each regime boundary:
 *   - Small board (score=10)  → NORMAL
 *   - Medium board (score=90) → REDUCED (just above threshold 80)
 *   - Extreme board (score=175) → BYPASS (above threshold 150)
 *
 * The formula is: board + (stack × 10) + (multiTargetReady × 5).
 * These tests validate the regime assignment, not the formula arithmetic.
 *
 * @author diego-xmage-ai (Sprint 34.6)
 */
public class BoardComplexityGuardTest {

    /**
     * Small board: 10 permanents, empty stack, no multi-target abilities ready.
     * score = 10 + 0 + 0 = 10  →  ≤ 80  →  NORMAL
     */
    @Test
    public void testSmallBoardIsNormal() {
        int score = 10;
        assertEquals("score=10 should be NORMAL", Regime.NORMAL, BoardComplexityGuard.getRegime(score));
    }

    /**
     * Boundary: score exactly at THRESHOLD_REDUCED (80) is still NORMAL (inclusive lower bound).
     */
    @Test
    public void testScoreAtThresholdIsNormal() {
        assertEquals("score=80 (boundary) should be NORMAL",
                Regime.NORMAL, BoardComplexityGuard.getRegime(BoardComplexityGuard.THRESHOLD_REDUCED));
    }

    /**
     * Medium board: 50 permanents, 3-card stack, 2 multi-target abilities ready.
     * score = 50 + 30 + 10 = 90  →  80 < 90 ≤ 150  →  REDUCED
     */
    @Test
    public void testMediumBoardIsReduced() {
        int score = 90;
        assertEquals("score=90 should be REDUCED", Regime.REDUCED, BoardComplexityGuard.getRegime(score));
    }

    /**
     * Extreme board: 70 permanents, 8-card stack, 5 multi-target abilities ready.
     * score = 70 + 80 + 25 = 175  →  > 150  →  BYPASS
     */
    @Test
    public void testExtremeBoardIsBypass() {
        int score = 175;
        assertEquals("score=175 should be BYPASS", Regime.BYPASS, BoardComplexityGuard.getRegime(score));
    }

    /**
     * Score exactly at THRESHOLD_BYPASS (150) is still REDUCED (not BYPASS yet).
     */
    @Test
    public void testScoreAtBypassThresholdIsReduced() {
        assertEquals("score=150 (boundary) should be REDUCED",
                Regime.REDUCED, BoardComplexityGuard.getRegime(BoardComplexityGuard.THRESHOLD_BYPASS));
    }

    /**
     * Constants sanity: verify thresholds match the calibrated values from Sprint 34 analysis.
     */
    @Test
    public void testThresholdConstants() {
        assertEquals("THRESHOLD_REDUCED should be 80",  80,  BoardComplexityGuard.THRESHOLD_REDUCED);
        assertEquals("THRESHOLD_BYPASS should be 150",  150, BoardComplexityGuard.THRESHOLD_BYPASS);
        assertEquals("REDUCED_CAP should be 20",        20,  BoardComplexityGuard.REDUCED_CAP);
        assertEquals("REDUCED_MAX_DEPTH should be 2",   2,   BoardComplexityGuard.REDUCED_MAX_DEPTH);
    }
}
