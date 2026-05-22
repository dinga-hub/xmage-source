package mage.player.ai.tempo;

/**
 * Sprint 33B: action priority tiers for early-game (turns 1-4) sequencing.
 *
 * Ordinals express priority: lower ordinal = higher priority tier.
 * {@link #TIER_OTHER} (pass, land plays, unknown) is never pruned.
 *
 * @author diego-xmage-ai
 */
public enum TempoCategory {

    /** Artifact mana at CMC ≤ 1: Sol Ring, Mana Crypt, Mana Vault, Moxen, Lotus Petal. */
    TIER_2_FAST_MANA,

    /** Mana dorks (creature CMC ≤ 2 with mana ability), 2-CMC rocks (Arcane Signet,
     *  Talismans), and land-tutor spells CMC ≤ 3 (Cultivate, Rampant Growth). */
    TIER_3_RAMP,

    /** Repeating draw engines: Rhystic Study, Phyrexian Arena, Mystic Remora,
     *  Esper Sentinel — any permanent with a triggered draw ability. */
    TIER_4_ENGINE,

    /** Commander cast from the Command Zone. Always preserved; never pruned. */
    TIER_5_COMMANDER,

    /** Creature or spell whose CMC falls in [turnNum-1, turnNum+1] — fills the curve. */
    TIER_6_CMC_MATCH,

    /** Other playable spells: utility creatures, non-engine enchantments, instants
     *  being cast on the bot's own turn (rare; InstantTimingOptimizer normally
     *  suppresses those). */
    TIER_7_OTHER,

    /** Activated ability with a lasting (non-EOT) benefit: card draw, removal,
     *  persistent +1/+1 counters. Not pruned in 33B; watch-logged only. */
    TIER_8_USEFUL_ACTIVATION,

    /** Activated ability whose every effect has Duration.EndOfTurn: pump, keyword
     *  grants, manland animation. Zero strategic value during main phase turns 1-4;
     *  always pruned by {@link mage.player.ai.ma.optimizers.impl.EarlyGameTempoOptimizer}. */
    TIER_9_NOOP,

    /** Pass action, land plays, or anything the classifier cannot categorise.
     *  Never pruned — pass is always a valid fallback. */
    TIER_OTHER
}
