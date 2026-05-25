package org.mage.test.AI.complexity;

import mage.player.ai.perf.TargetEnumerationCap;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Sprint 34.6 — Format 0 (pure unit): validates the ThreadLocal cap override
 * mechanism in {@link TargetEnumerationCap}.
 *
 * Critical safety property: {@code clearCapOverride()} must reset
 * {@code effectiveCap()} to the default (50) so that the override cannot
 * leak between turns or between simulated players.
 *
 * @author diego-xmage-ai (Sprint 34.6)
 */
public class TargetEnumerationCapOverrideTest {

    /** Always clean up the ThreadLocal after each test, even on failure. */
    @After
    public void cleanup() {
        TargetEnumerationCap.clearCapOverride();
    }

    /**
     * Default state: no override set → effectiveCap() returns MAX_TARGET_OPTIONS_PER_ABILITY (50).
     */
    @Test
    public void testDefaultCapIsMax() {
        assertEquals("Default effective cap should be MAX_TARGET_OPTIONS_PER_ABILITY (50)",
                TargetEnumerationCap.MAX_TARGET_OPTIONS_PER_ABILITY,
                TargetEnumerationCap.effectiveCap());
    }

    /**
     * After setCapOverride(20) → effectiveCap() returns 20 (REDUCED regime value).
     */
    @Test
    public void testOverrideReducesCap() {
        TargetEnumerationCap.setCapOverride(20);
        assertEquals("After setCapOverride(20), effectiveCap() should return 20",
                20, TargetEnumerationCap.effectiveCap());
    }

    /**
     * Core safety: after clearCapOverride() → effectiveCap() returns 50 again.
     * This is the finally-block contract: a leaked override would silently
     * reduce quality for all subsequent turns of this thread.
     */
    @Test
    public void testClearResetsToDefault() {
        TargetEnumerationCap.setCapOverride(20);
        TargetEnumerationCap.clearCapOverride();
        assertEquals("After clearCapOverride(), effectiveCap() must return default (50)",
                TargetEnumerationCap.MAX_TARGET_OPTIONS_PER_ABILITY,
                TargetEnumerationCap.effectiveCap());
    }

    /**
     * clearCapOverride() on a never-set ThreadLocal is safe (no NPE or exception).
     */
    @Test
    public void testClearOnCleanThreadIsNoop() {
        // ThreadLocal was never set in this test — clear should be a no-op
        TargetEnumerationCap.clearCapOverride();
        assertEquals("effectiveCap() should still be default after clear on clean thread",
                TargetEnumerationCap.MAX_TARGET_OPTIONS_PER_ABILITY,
                TargetEnumerationCap.effectiveCap());
    }
}
