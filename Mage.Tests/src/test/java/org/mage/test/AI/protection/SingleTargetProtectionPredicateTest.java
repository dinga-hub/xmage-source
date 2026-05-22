package org.mage.test.AI.protection;

import mage.player.ai.hand.HandEvaluator;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import static org.junit.Assert.*;

/**
 * Sprint 19B — Format 0: pure predicate unit tests for the three single-target
 * protection predicates in HandEvaluator.
 *
 * No AI loop, no Game simulation — just verify that each predicate fires on the
 * correct card and is silent on the others.
 *
 * Cards under test:
 *   - Slip Out the Back   → isPhaseOutProtection=true,  isHexproofGrantProtection=false
 *   - Tamiyo's Safekeeping→ isPhaseOutProtection=false, isHexproofGrantProtection=true
 *   - Blossoming Defense  → isPhaseOutProtection=false, isHexproofGrantProtection=true
 *
 * G13 rule (QA_PLAYBOOK §3): validate predicate per card BEFORE any Format A simulation.
 */
public class SingleTargetProtectionPredicateTest extends CardTestPlayerBase {

    // ── Slip Out the Back (phase-out) ────────────────────────────────────────

    @Test
    public void slipOutTheBack_isPhaseOut_true() {
        addCard(mage.constants.Zone.HAND, playerA, "Slip Out the Back");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Slip Out the Back".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull("Slip Out the Back should be in hand", card);
        assertTrue("isPhaseOutProtection should be true for Slip Out the Back",
                HandEvaluator.isPhaseOutProtection(card, currentGame));
    }

    @Test
    public void slipOutTheBack_isHexproofGrant_false() {
        addCard(mage.constants.Zone.HAND, playerA, "Slip Out the Back");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Slip Out the Back".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull(card);
        assertFalse("isHexproofGrantProtection should be false for Slip Out the Back",
                HandEvaluator.isHexproofGrantProtection(card, currentGame));
    }

    @Test
    public void slipOutTheBack_isSingleTargetProtection_true() {
        addCard(mage.constants.Zone.HAND, playerA, "Slip Out the Back");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Slip Out the Back".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull(card);
        assertTrue("isSingleTargetProtectionSpell should be true for Slip Out the Back",
                HandEvaluator.isSingleTargetProtectionSpell(card, currentGame));
    }

    // ── Tamiyo's Safekeeping (hexproof + indestructible) ─────────────────────

    @Test
    public void tamiyosSafekeeping_isPhaseOut_false() {
        addCard(mage.constants.Zone.HAND, playerA, "Tamiyo's Safekeeping");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Tamiyo's Safekeeping".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull("Tamiyo's Safekeeping should be in hand", card);
        assertFalse("isPhaseOutProtection should be false for Tamiyo's Safekeeping",
                HandEvaluator.isPhaseOutProtection(card, currentGame));
    }

    @Test
    public void tamiyosSafekeeping_isHexproofGrant_true() {
        addCard(mage.constants.Zone.HAND, playerA, "Tamiyo's Safekeeping");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Tamiyo's Safekeeping".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull(card);
        assertTrue("isHexproofGrantProtection should be true for Tamiyo's Safekeeping",
                HandEvaluator.isHexproofGrantProtection(card, currentGame));
    }

    @Test
    public void tamiyosSafekeeping_isSingleTargetProtection_true() {
        addCard(mage.constants.Zone.HAND, playerA, "Tamiyo's Safekeeping");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Tamiyo's Safekeeping".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull(card);
        assertTrue("isSingleTargetProtectionSpell should be true for Tamiyo's Safekeeping",
                HandEvaluator.isSingleTargetProtectionSpell(card, currentGame));
    }

    // ── Blossoming Defense (hexproof + +2/+2) ────────────────────────────────

    @Test
    public void blossomingDefense_isPhaseOut_false() {
        addCard(mage.constants.Zone.HAND, playerA, "Blossoming Defense");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Blossoming Defense".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull("Blossoming Defense should be in hand", card);
        assertFalse("isPhaseOutProtection should be false for Blossoming Defense",
                HandEvaluator.isPhaseOutProtection(card, currentGame));
    }

    @Test
    public void blossomingDefense_isHexproofGrant_true() {
        addCard(mage.constants.Zone.HAND, playerA, "Blossoming Defense");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Blossoming Defense".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull(card);
        assertTrue("isHexproofGrantProtection should be true for Blossoming Defense",
                HandEvaluator.isHexproofGrantProtection(card, currentGame));
    }

    @Test
    public void blossomingDefense_isSingleTargetProtection_true() {
        addCard(mage.constants.Zone.HAND, playerA, "Blossoming Defense");
        setStopAt(1, mage.constants.PhaseStep.PRECOMBAT_MAIN);
        execute();

        mage.cards.Card card = currentGame.getPlayer(playerA.getId())
                .getHand().getCards(currentGame).stream()
                .filter(c -> "Blossoming Defense".equals(c.getName()))
                .findFirst().orElse(null);
        assertNotNull(card);
        assertTrue("isSingleTargetProtectionSpell should be true for Blossoming Defense",
                HandEvaluator.isSingleTargetProtectionSpell(card, currentGame));
    }
}
