package mage.player.ai.stack;

/**
 * Sprint 19: high-level classification of what is currently on the stack.
 *
 * The classifier maps a StackObject to one of these buckets so the counter /
 * protection optimizers can decide on Magic-level intent ("Wrath of God is a
 * BOARDWIPE") rather than minimax outcome alone.
 *
 * Categories cover ~90% of relevant Commander spells via effect-class detection
 * plus a small supplementary name list for cards whose effect signature is too
 * generic (e.g. Cyclonic Rift's overload mode is a bounce-all, not destroy-all).
 */
public enum SpellCategory {

    /** Wipes 3+ of our permanents (Wrath, Damnation, Toxic Deluge, Cyc Rift overload, Farewell). */
    BOARDWIPE,

    /** Single-target destroy/exile (Swords, Path, Doom Blade, Beast Within). */
    TARGETED_REMOVAL,

    /** Recurring card-advantage engine (Rhystic Study, Smothering Tithe, Mystic Remora, Esper Sentinel, Necropotence). */
    DRAW_ENGINE,

    /** Take-an-extra-turn (Time Walk, Nexus of Fate, Temporal Manipulation). */
    EXTRA_TURN,

    /** Search-library tutor (Demonic, Vampiric, Mystical, Imperial Seal, Worldly, Enlightened, Gamble). */
    TUTOR,

    /** Direct damage at a player or creature (Lightning Bolt, Comet Storm). */
    DAMAGE_SPELL,

    /** Pump / combat trick (Giant Growth, Boros Charm mode 1). */
    PUMP,

    /** Cheap accelerator (Sol Ring, Mana Crypt, Mana Vault, Arcane Signet). */
    MANA_ROCK,

    /** Another counterspell on the stack — relevant for counter wars. */
    COUNTER,

    /** Aura permanent (Lignify, Imprisoned in the Moon, Pacifism). */
    AURA,

    /** Anything that didn't match the rules above. Most utility spells / creatures fall here. */
    UTILITY,

    /** Couldn't determine — e.g. token-generating abilities. */
    UNKNOWN
}
