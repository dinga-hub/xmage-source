package mage.player.ai.stack;

import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.CounterTargetEffect;
import mage.abilities.effects.common.CounterTargetWithReplacementEffect;
import mage.abilities.effects.common.CounterUnlessPaysEffect;
import mage.abilities.effects.common.DamageAllEffect;
import mage.abilities.effects.common.DamageEverythingEffect;
import mage.abilities.effects.common.DamagePlayersEffect;
import mage.abilities.effects.common.DamageTargetEffect;
import mage.abilities.effects.common.DestroyAllEffect;
import mage.abilities.effects.common.DestroyTargetEffect;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.effects.common.ExileAllEffect;
import mage.abilities.effects.common.ExileTargetEffect;
import mage.abilities.effects.common.SacrificeAllEffect;
import mage.abilities.effects.common.continuous.BoostTargetEffect;
import mage.abilities.effects.common.turn.AddExtraTurnTargetEffect;
import mage.cards.Card;
import mage.cards.repository.GameChangerRegistry;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.game.Game;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.player.ai.perf.AiPerformanceLog;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Sprint 19: classifies a stack object into a {@link SpellCategory} so the AI can
 * decide whether to counter / protect against it based on category, not minimax outcome.
 *
 * Detection strategy mirrors Sprint 18's mass-protection detection (Option C):
 *   primary: scan the spell's effects for known Effect classes
 *   fallback: small supplementary name lists for cards whose effect class is too generic
 *             (e.g. Cyclonic Rift overload uses ReturnToHandFromBattlefieldAll, not Destroy)
 *
 * The supplementary lists are deliberately tiny — add only when an effect-class scan
 * cannot reproduce the categorisation. Otherwise the list rots with every new set.
 */
public final class StackThreatClassifier {

    // WHY supplementary: these wipes don't subclass DestroyAllEffect/ExileAllEffect/SacrificeAllEffect.
    // Cyc Rift overload bounces. Toxic Deluge / Crux of Fate use damage-based or destroy-with-filter.
    // Farewell uses a multi-zone exile combination. Keep tight.
    private static final Set<String> BOARDWIPE_SUPPLEMENTARY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Cyclonic Rift",       // overload mode = bounce all nonland
            "Toxic Deluge",        // -X/-X to all (kills creatures)
            "Crux of Fate",        // destroy all dragons or all non-dragons
            "Farewell",            // up to four modes wiping different zones
            "Supreme Verdict",     // can't be countered destroy all creatures
            "Damnation",           // destroy all creatures (mirror Wrath)
            "Wrath of God",        // canonical anchor (effect uses generic mechanism in some impls)
            "Living Death",        // exile graveyards / battlefield swap
            "Plague Wind"          // destroy all creatures you don't control
    )));

    // Engine pieces that generate recurring card advantage. Diego's wisdom: "draw is the most
    // important mechanic in Magic" — these always warrant a counter when on the stack.
    // GameChangerRegistry already covers Rhystic Study / Smothering Tithe / Necropotence /
    // The One Ring / Consecrated Sphinx. We add a few well-known draw engines that aren't on
    // the official Game Changer list but behave identically from the AI's perspective.
    private static final Set<String> DRAW_ENGINE_SUPPLEMENTARY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Mystic Remora",
            "Esper Sentinel",
            "Sylvan Library",
            "Black Market Connections",
            "Trouble in Pairs",
            "Phyrexian Arena"
    )));

    // Extra-turn cards. AddExtraTurnTargetEffect catches most but a few historic ones
    // use bespoke effects (e.g. Beacon of Tomorrows recurs from library).
    private static final Set<String> EXTRA_TURN_SUPPLEMENTARY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Time Walk",
            "Time Warp",
            "Temporal Manipulation",
            "Temporal Mastery",
            "Nexus of Fate",
            "Beacon of Tomorrows",
            "Karn's Temporal Sundering",
            "Alrund's Epiphany"
    )));

    // Tutors. Hard to detect purely by effect class (SearchLibraryPutInHandEffect catches
    // most but mill-tutors and conditional tutors miss). Small anchor list of the cards
    // that consistently win games when resolved (most are already GC).
    private static final Set<String> TUTOR_SUPPLEMENTARY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Demonic Tutor",
            "Vampiric Tutor",
            "Mystical Tutor",
            "Enlightened Tutor",
            "Worldly Tutor",
            "Imperial Seal",
            "Gamble",
            "Diabolic Intent",
            "Grim Tutor",
            "Eladamri's Call",
            "Survival of the Fittest"
    )));

    private StackThreatClassifier() {
    }

    /**
     * Classifies the given stack object. Never returns null.
     *
     * @param stackObject the spell or ability sitting on the stack
     * @param game current game state (used to inspect card type / supertype)
     */
    public static SpellCategory classify(StackObject stackObject, Game game) {
        // Sprint 34: count calls per addActionsTimed() — classifiers run once per node
        // expansion that has a stack object, so this is a proxy for stack-reactive decisions.
        AiPerformanceLog.recordStackClassifierCall();
        if (stackObject == null) {
            return SpellCategory.UNKNOWN;
        }

        String name = stackObject.getName();

        // Supplementary lists FIRST — they exist precisely because effect-class detection misses these.
        if (name != null) {
            if (BOARDWIPE_SUPPLEMENTARY.contains(name)) return SpellCategory.BOARDWIPE;
            if (EXTRA_TURN_SUPPLEMENTARY.contains(name)) return SpellCategory.EXTRA_TURN;
            if (TUTOR_SUPPLEMENTARY.contains(name)) return SpellCategory.TUTOR;
            if (DRAW_ENGINE_SUPPLEMENTARY.contains(name)) return SpellCategory.DRAW_ENGINE;
        }

        Ability stackAbility = stackObject.getStackAbility();
        if (stackAbility == null) {
            return classifyByCardTypeOnly(stackObject, game);
        }

        // Effect-class detection in priority order: highest-impact categories first so a
        // multi-effect spell (Cryptic Command-style) gets classified by its scariest mode.
        for (Effect effect : stackAbility.getEffects()) {
            // Mass effects → BOARDWIPE
            if (effect instanceof DestroyAllEffect
                    || effect instanceof ExileAllEffect
                    || effect instanceof SacrificeAllEffect
                    || effect instanceof DamageEverythingEffect
                    || effect instanceof DamageAllEffect
                    || effect instanceof DamagePlayersEffect) {
                return SpellCategory.BOARDWIPE;
            }
            // Extra turn
            if (effect instanceof AddExtraTurnTargetEffect) {
                return SpellCategory.EXTRA_TURN;
            }
            // Counter target spell
            if (effect instanceof CounterTargetEffect
                    || effect instanceof CounterTargetWithReplacementEffect
                    || effect instanceof CounterUnlessPaysEffect) {
                return SpellCategory.COUNTER;
            }
            // Targeted removal
            if (effect instanceof DestroyTargetEffect || effect instanceof ExileTargetEffect) {
                return SpellCategory.TARGETED_REMOVAL;
            }
            // Damage spell (direct damage)
            if (effect instanceof DamageTargetEffect) {
                return SpellCategory.DAMAGE_SPELL;
            }
            // Pump / combat trick
            if (effect instanceof BoostTargetEffect) {
                return SpellCategory.PUMP;
            }
            // Repeatable card draw on our own permanent → engine (covers Rhystic clones etc.
            // that are not in the supplementary list).
            if (effect instanceof DrawCardSourceControllerEffect) {
                return SpellCategory.DRAW_ENGINE;
            }
        }

        return classifyByCardTypeOnly(stackObject, game);
    }

    /**
     * Convenience: returns true if the stack object is on the official Commander Game Changer
     * list. Cards on this list bypass the CMC floor in CounterOptimizer and elevate priority
     * in ProtectionOptimizer when targeted.
     */
    public static boolean isGameChanger(StackObject stackObject) {
        return stackObject != null && GameChangerRegistry.isGameChanger(stackObject.getName());
    }

    // Last-resort classification by card type (used when effects are inconclusive).
    // Catches cheap mana rocks / aura / utility creatures.
    private static SpellCategory classifyByCardTypeOnly(StackObject stackObject, Game game) {
        if (!(stackObject instanceof Spell)) {
            return SpellCategory.UNKNOWN;
        }
        Card card = ((Spell) stackObject).getCard();
        if (card == null) {
            return SpellCategory.UNKNOWN;
        }

        // Mana rocks: artifact with mana ability and CMC ≤ 2 (Sol Ring, Mana Vault, Arcane Signet,
        // Talisman cycle). Cards with CMC 3+ that happen to make mana (Commander's Sphere) still
        // qualify but are less of an immediate threat — keep the CMC bound tight.
        if (card.isArtifact(game) && card.getManaValue() <= 2) {
            for (Ability ability : card.getAbilities()) {
                for (Effect effect : ability.getEffects()) {
                    // ActivatedManaAbilityImpl produces mana — but checking subclass is expensive
                    // here. Cheap-artifact heuristic is good enough: any CMC≤2 artifact spell on
                    // the stack is treated as MANA_ROCK for AI purposes.
                    if (effect != null) {
                        return SpellCategory.MANA_ROCK;
                    }
                }
            }
            return SpellCategory.MANA_ROCK;
        }

        if (card.hasSubtype(SubType.AURA, game) || card.getCardType().contains(CardType.ENCHANTMENT)
                && card.hasSubtype(SubType.AURA, game)) {
            return SpellCategory.AURA;
        }

        return SpellCategory.UTILITY;
    }
}
