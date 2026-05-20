package mage.cards.repository;

import mage.cards.Card;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Sprint 19: official Commander "Game Changer" list (mtg.wiki/page/Game_Changers).
 *
 * Source of the list: same hard-coded list previously duplicated in
 * Mage.Client BracketLegalityLabel.java. Moved here so the AI (and any other
 * module) can consume it. BracketLegalityLabel now delegates to this registry.
 *
 * WHY a registry instead of effect-based detection: Game Changer is an explicit,
 * curated list maintained by WotC for the Commander brackets system. Effect-based
 * detection cannot reproduce the curation (e.g. Aura Shards, Drannith Magistrate
 * are GC for political/lockdown reasons no generic Effect signature can capture).
 *
 * Used by:
 *  - Sprint 19 CounterOptimizer: spells named in this list bypass the CMC floor
 *    when deciding whether to counter (game-deciding spells are always worth a counter).
 *  - Sprint 19 ProtectionOptimizer: targeted removal aimed at a GC permanent we
 *    control triggers mass protection (engine piece worth saving).
 *  - Future (Sprint 20+ / Sprint 26): GC-tagged permanents get a threat-score buff,
 *    making them priority targets for our own removal.
 */
public final class GameChangerRegistry {

    // Official Game Changer list per mtg.wiki/page/Game_Changers (as of 2026-05).
    // Keep alphabetised. When the official list changes, update here only —
    // BracketLegalityLabel reads through this constant.
    private static final Set<String> GAME_CHANGERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Ad Nauseam",
            "Ancient Tomb",
            "Aura Shards",
            "Biorhythm",
            "Bolas's Citadel",
            "Braids, Cabal Minion",
            "Chrome Mox",
            "Coalition Victory",
            "Consecrated Sphinx",
            "Crop Rotation",
            "Cyclonic Rift",
            "Demonic Tutor",
            "Drannith Magistrate",
            "Enlightened Tutor",
            "Farewell",
            "Field of the Dead",
            "Fierce Guardianship",
            "Force of Will",
            "Gaea's Cradle",
            "Gamble",
            "Gifts Ungiven",
            "Glacial Chasm",
            "Grand Arbiter Augustin IV",
            "Grim Monolith",
            "Humility",
            "Imperial Seal",
            "Intuition",
            "Jeska's Will",
            "Lion's Eye Diamond",
            "Mana Vault",
            "Mishra's Workshop",
            "Mox Diamond",
            "Mystical Tutor",
            "Narset, Parter of Veils",
            "Natural Order",
            "Necropotence",
            "Notion Thief",
            "Opposition Agent",
            "Orcish Bowmasters",
            "Panoptic Mirror",
            "Rhystic Study",
            "Seedborn Muse",
            "Serra's Sanctum",
            "Smothering Tithe",
            "Survival of the Fittest",
            "Teferi's Protection",
            "Tergrid, God of Fright",
            "Thassa's Oracle",
            "The One Ring",
            "The Tabernacle at Pendrell Vale",
            "Underworld Breach",
            "Vampiric Tutor",
            "Worldly Tutor"
    )));

    private GameChangerRegistry() {
    }

    public static boolean isGameChanger(String cardName) {
        return cardName != null && GAME_CHANGERS.contains(cardName);
    }

    public static boolean isGameChanger(Card card) {
        return card != null && isGameChanger(card.getName());
    }

    /** Read-only view of the full list. Used by UI (BracketLegalityLabel) for deck-validation membership tests. */
    public static Set<String> getAll() {
        return GAME_CHANGERS;
    }
}
