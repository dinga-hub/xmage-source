# Multiplayer AI Improvements for Commander (4-player)

This document describes a series of improvements made to the XMage AI (`ComputerPlayer6`/`ComputerPlayer7`)
to produce better and more realistic decision-making in Commander (multiplayer) games.

The original AI was designed and tuned for 1v1 games. Several of its core components explicitly note this
limitation (e.g. `GameStateEvaluator2` carries the comment *"This evaluator is only good for two player games"*).
These changes address the most impactful behavioral problems observed in 4-player Commander games.

---

## 1. Multiplayer Threat Assessment (`GameStateEvaluator2`)

**Problem:** `GameStateEvaluator2.evaluate()` uses `findFirst()` to select the opponent, which is meaningless
in a 4-player game. The AI had no concept of which opponent was the biggest threat — it treated all opponents
equally when deciding whom to attack and what to remove.

**Root cause:** The evaluator was never updated for multiplayer. Attack target selection and removal targeting
both relied on the raw board score of a single hardcoded opponent.

**Solution:** Added `evaluatePlayerThreat(UUID targetPlayerId, Game game)`, a dedicated method that scores
each player as a threat using multiple signals:
- **Board presence** (sum of permanent scores × 3) — the strongest signal
- **Ramp** (mana sources beyond a baseline of 4 → +120 per extra source) — detects explosive mana bases
- **Hand size** (× 50) — hidden potential
- **High life (≥ 30)** (+200) — player has not been pressured yet, still at full resources
- **Low life (≤ 10)** (-400) — discourages piling on an already-dying player in non-lethal situations
  (the +1,000,000 lethal-kill override in `declareAttackers` still fires when the kill is actually available)

**Files:** `Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/score/GameStateEvaluator2.java`

---

## 2. Attack the Biggest Threat (`ComputerPlayer6.declareAttackers`)

**Problem:** The bot attacked opponents in iteration order (effectively random), ignoring who was actually
winning the game. A dominant player with 15 permanents received the same attack priority as a player with 2.

**Solution:** Before the attacker-assignment loop, opponents are sorted descending by `evaluatePlayerThreat()`.
Attackers are assigned to the highest-threat opponent first. If that opponent has lethal blockers, the bot
falls through to the next opponent — so the sort is a preference, not a hard constraint.

**Files:** `ComputerPlayer6.java` — `declareAttackers()`

---

## 3. Reserve a Blocker When Under Threat (`ComputerPlayer6.declareAttackers`)

**Problem:** The bot committed all safe attackers to offense even when a threatening opponent had a large
board. In Commander this is dangerous — leaving zero blockers invites an alpha strike next turn.

**Solution:** If the highest `evaluatePlayerThreat()` score among opponents exceeds a threshold (3000 ≈ a
player with a few relevant permanents), and there are at least 2 safe attackers, the creature with the
highest combined power+toughness is withheld from the attack and kept as a blocker.

The threshold (3000) was chosen as a rough midpoint: a single 3/3 + a 2/2 yields roughly 4800 threat score;
we trigger the reserve at 3000 so the bot starts protecting itself before the threat is overwhelming.

**Files:** `ComputerPlayer6.java` — `declareAttackers()`

---

## 4. Threat-Weighted Removal Targeting (`PossibleTargetsComparator`, `ComputerPlayer`)

**Problem:** When choosing removal targets, the AI scored permanents in isolation. A 3/3 belonging to the
dominant player and a 3/3 belonging to a struggling player received identical scores, despite the former
being a much more dangerous threat to address.

**Solution (targeting score):** `PossibleTargetsComparator.getScoreFromBattlefield()` now applies a threat
multiplier to opponent permanents: `adjustedScore = permScore + permScore * threatScore(controller) / 5000`.
A permanent controlled by a dominant player scores higher and floats to the top of the removal priority list.

**Solution (skip weak targets):** In `ComputerPlayer.makeChoice()`, when the outcome is destructive
(`DestroyPermanent`, `Exile`, `Detriment`) and targeting is optional (`minTargets == 0`), permanents with a
raw score below 800 are skipped. This prevents the bot from removing a 1/1 token or a trivial artifact just
because it can.

**Files:**
- `Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/PossibleTargetsComparator.java`
- `Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/ComputerPlayer.java`

---

## 5. Utility Creature Scoring (`ArtificialScoringSystem`)

**Problem:** Hatebears, stax pieces, and combo enablers (e.g. a 1/1 with a powerful static ability) were
heavily undervalued because ability scores were multiplied by `(power + 1) / 2`. A 0/1 with an Indestructible
+ tap ability scored nearly zero for its abilities, even though it's a high-value piece.

**Root cause:** The formula treated all ability value as combat-relevant, scaling it with power.

**Solution:** Split the ability score into two components:
- `combatAbilityScore = abilityScore * (power + 1) / 2` — scales with power, rewards evasion and combat
  keywords on aggressive creatures
- `baseAbilityScore = abilityScore * 3 / 10` — 30% of ability value always counted regardless of P/T

Final score: `power * 300 + toughness * 200 + combatAbilityScore + baseAbilityScore`

**Files:** `Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/score/ArtificialScoringSystem.java`

---

## 6. Don't Use Boardwipes When Winning (`BoardwipeOptimizer`)

**Problem:** The bot cast boardwipes (Wrath of God, Blasphemous Act, etc.) even when it had the best board
on the table, erasing its own advantage. In Commander, a boardwipe should only be a catch-up tool.

**Solution:** A new `TreeOptimizer` subclass (`BoardwipeOptimizer`) is registered in `ComputerPlayer6`'s
static optimizer list. It inspects each candidate action's effects for `DestroyAllEffect`, `ExileAllEffect`,
or `SacrificeAllEffect`. If found, it compares the bot's board score against the combined board score of all
opponents. If the opponents' combined board is less than 1.5× the bot's board, the boardwipe is removed from
the action list before minimax evaluation.

The 1.5× multiplier and the minimum board threshold (2000) prevent over-suppression in early-game or
lopsided situations.

**Files:** `Mage.Server.Plugins/Mage.Player.AI.MA/src/mage/player/ai/ma/optimizers/impl/BoardwipeOptimizer.java` *(new)*

---

## 7. Better Instant and Tap-Ability Timing (`InstantTimingOptimizer`)

**Problem:** The bot cast instants and flash spells during its own main phase, wasting the flexibility of
instant speed. It also activated tap-cost abilities during opponents' turns before combat was declared,
tapping potential blockers for zero gain (e.g. giving a creature flying when no attack was incoming).

**Root cause:** The minimax does not look far enough ahead to see that holding mana open for an opponent's
turn is superior. Any action with a positive immediate score gets taken.

**Solution:** A new `TreeOptimizer` subclass (`InstantTimingOptimizer`) applies two rules:

- **Rule 1 — Instant/flash spells on own main phase:** During `PRECOMBAT_MAIN` and `POSTCOMBAT_MAIN` on the
  bot's own turn, all `SpellAbility` actions from instant or flash cards are suppressed. The minimax
  naturally rediscovers them during opponent turns, where the end step before the bot's turn provides the
  most information and is the optimal casting window.

- **Rule 2 — Tap-cost abilities during opponent's non-combat phases:** During `UPKEEP`, `DRAW`, main phases,
  and `END_TURN` of an opponent, activated abilities whose cost includes `TapSourceCost` or `TapTargetCost`
  are suppressed. These would tap creatures that could block, for no combat benefit. Combat phases
  (`BEGIN_COMBAT` through `END_COMBAT`) are excluded from suppression to allow combat tricks.

**Known limitation:** This does not prevent tap-cost activations on the bot's own turn when they have no
combat relevance (e.g. giving a non-attacking creature flying). Addressing that cleanly requires
end-of-turn effect detection (see section 10 — planned).

**Files:** `Mage.Server.Plugins/Mage.Player.AI.MA/src/mage/player/ai/ma/optimizers/impl/InstantTimingOptimizer.java` *(new)*

---

## 8. Don't Attack With No Offensive Value (`ComputerPlayer6.declareAttackers`)

**Problem:** The bot attacked with creatures that were "safe" (wouldn't die to a single blocker) but
completely useless offensively — for example, a 1/2 attacking into a 0/4 defender. The attack deals no
damage and kills no blocker, providing zero value while leaving a potential blocker tapped.

**Solution:** After the existing safety checks, an additional filter is applied: if an attacker is safe but
cannot kill any available blocker (power < all blockers' toughness) and has neither trample nor lifelink,
`safeToAttack` is set to false. Trample and lifelink are exempt because they derive value even without
killing a blocker (excess damage / life gain).

**Files:** `ComputerPlayer6.java` — `declareAttackers()`

---

## 9. Avoid Unfavorable Gang-Block Trades (`ComputerPlayer6.declareAttackers`)

**Problem:** The existing single-blocker safety check (can any one blocker kill the attacker?) did not
account for multiple blockers acting together. A valuable creature (e.g. the commander) would attack into
a wall of 2/2 tokens, each unable to kill it alone, but collectively lethal.

**Solution:** After the single-blocker and offensive-value checks, a multi-block simulation is performed:
blockers are sorted by power (descending), and their cumulative power is summed until it meets or exceeds
the attacker's toughness. If 2+ blockers are needed to kill the attacker, a score comparison is run: the
attacker's score is compared to the total score of the blockers it can kill in return (damage assigned to
lowest-toughness blockers first). If the attacker is worth more than what it kills, the attack is suppressed.

Exceptions: Trample (excess damage is the goal), Indestructible (won't die), Deathtouch (kills each blocker
with 1 damage, making the math favor the attacker).

**Files:** `ComputerPlayer6.java` — `declareAttackers()`

---

## 10. No X=0 for Variable-Cost Abilities (`SimulatedPlayer2`)

**Problem:** When generating candidate actions for minimax evaluation, `addVariableXOptions()` started the
X loop at `variableManaCost.getMinX()`, which is 0 by default. This caused the bot to consider X=0 as a
valid play — e.g. Fireball dealing 0 damage, or Mirror Entity setting all creatures to 0/0 (wiping its own
board).

**Solution:** The loop now starts at `Math.max(1, variableManaCost.getMinX())`. Cards that legitimately
require X=0 (rare) can set `minX = 0` explicitly and will still generate that option, but the common case
of "X defaults to 0" is eliminated.

**Files:** `Mage.Server.Plugins/Mage.Player.AI.MA/src/mage/player/ai/SimulatedPlayer2.java`

---

## 11. Floating Mana as a Resource (Opportunity Cost) (`GameStateEvaluator2`)

**Problem:** The scoring system placed no value on unspent mana. As a result, spending {1}{B} on a trivial
effect (e.g. activating Regenerate on a creature not under attack, gaining +3 score) was always preferred
over passing (gaining 0 score). The bot wasted mana on low-value activations because the cost of doing so
was invisible to the evaluator.

**Root cause:** Mana is a resource with opportunity cost — holding it open enables counterspells, removal,
and combat tricks. Burning it on useless effects is a real disadvantage that the score did not reflect.

**Solution:** The bot's current floating mana (mana pool count) is added to the game state score at
+100 per mana. A 2-mana activation now needs to provide more than 200 score points to be worth casting
over passing. Most trivial activations (Regenerate, minor pump) score well below this threshold, so the
bot now preserves mana for relevant uses.

**Calibration note:** The value of 100/mana is intentionally conservative to avoid over-suppressing
legitimate early-turn plays. Adjustment may be needed based on observed behavior.

**Files:** `Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/score/GameStateEvaluator2.java`

---

## Known Limitations and Future Work

### Temporary effects not discounted in scoring
Effects with `Duration.EndOfTurn` (e.g. Mutavault animating itself, Mothdust Changeling granting flying)
add their full permanent score to the game state, but that score evaporates at end of turn. The minimax
at typical depth (4-6) rarely simulates far enough to see the effect expire, so it overvalues these actions.

**Proposed fix:** Compare `permanent.getAbilities()` (base) vs `permanent.getAbilities(game)` (with active
effects). Abilities present only in the latter are temporary. Their contribution to score should be discounted
or zeroed unless the current phase provides a clear way to exploit them (e.g. attacking in combat).

### Scoring is still fundamentally 1v1
`GameStateEvaluator2.evaluate()` still compares the bot against a single opponent (first active opponent
found). The `evaluatePlayerThreat()` method improves attack and removal targeting, but the core minimax
score function doesn't model 3-way politics, threat assessment across all opponents, or "don't help the
leader" considerations.

### No understanding of "do nothing" value
The bot has no concept of passing priority being strategically correct. Even with the floating mana score,
some low-value activations will still be taken because the minimax depth is too shallow to see that
preserving the action for a future turn is superior.
