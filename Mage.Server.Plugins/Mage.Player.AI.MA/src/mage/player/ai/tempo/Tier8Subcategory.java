package mage.player.ai.tempo;

/**
 * Subcategory of TIER_8_USEFUL_ACTIVATION for prioritizing competing
 * activations in the End Step window (Sprint 33E).
 *
 * Priority order (lower ordinal = higher priority):
 *   DRAW > REMOVAL > PERSISTENT_COUNTER > OTHER
 */
public enum Tier8Subcategory {
    DRAW,
    REMOVAL,
    PERSISTENT_COUNTER,
    OTHER
}
