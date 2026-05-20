package mage.player.ai.memory;

import java.util.*;

/**
 * Sprint 18: Per-turn AI memory for mana reservation and action tracking.
 * Cleared at end of each turn. Used by Sprint 18+ for reservation, Sprint 19+ for counter/protection.
 */
public class AiMemory {

    public enum MemoryCategory {
        HELD_MANA_SOURCES_FOR_NEXT_SPELL,
        HELD_MANA_SOURCES_FOR_MAIN2,
        HELD_MANA_SOURCES_FOR_DECLBLK,
        HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK,
        MANDATORY_ATTACKERS,
        TRICK_ATTACKERS,
        CHOSEN_FOG_EFFECT,
        REVEALED_OPP_CARDS,
        ATTACHED_THIS_TURN,
        ANIMATED_THIS_TURN,
        BOUNCED_THIS_TURN
    }

    private final Map<MemoryCategory, Set<UUID>> categories = new EnumMap<>(MemoryCategory.class);

    public void remember(MemoryCategory c, UUID id) {
        categories.computeIfAbsent(c, k -> new HashSet<>()).add(id);
    }

    public boolean isRemembered(MemoryCategory c, UUID id) {
        Set<UUID> set = categories.get(c);
        return set != null && set.contains(id);
    }

    public Set<UUID> get(MemoryCategory c) {
        return categories.getOrDefault(c, Collections.emptySet());
    }

    public boolean isMemorySetEmpty(MemoryCategory c) {
        Set<UUID> set = categories.get(c);
        return set == null || set.isEmpty();
    }

    public void forget(MemoryCategory c, UUID id) {
        Set<UUID> set = categories.get(c);
        if (set != null) {
            set.remove(id);
        }
    }

    public void clearCategory(MemoryCategory c) {
        categories.remove(c);
    }

    public void clearAtEndOfTurn() {
        categories.remove(MemoryCategory.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        categories.remove(MemoryCategory.HELD_MANA_SOURCES_FOR_MAIN2);
        categories.remove(MemoryCategory.CHOSEN_FOG_EFFECT);
        categories.remove(MemoryCategory.ATTACHED_THIS_TURN);
        categories.remove(MemoryCategory.ANIMATED_THIS_TURN);
        categories.remove(MemoryCategory.BOUNCED_THIS_TURN);
        categories.remove(MemoryCategory.MANDATORY_ATTACKERS);
        categories.remove(MemoryCategory.TRICK_ATTACKERS);
    }

    public void clearAll() {
        categories.clear();
    }
}
