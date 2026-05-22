package mage.player.ai;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.abilities.StaticAbility;
import mage.abilities.common.AttacksTriggeredAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.SearchEffect;
import mage.abilities.keyword.*;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.counters.CounterType;
import mage.filter.StaticFilters;
import mage.cards.Card;
import mage.constants.CommanderCardType;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.player.ai.hand.HandEvaluator;
import mage.player.ai.hand.HandScore;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.player.ai.ma.optimizers.TreeOptimizer;
import mage.player.ai.ma.optimizers.impl.*;
import mage.player.ai.memory.AiMemory;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.util.CombatInfo;
import mage.player.ai.util.CombatUtil;
import mage.players.Player;
import mage.filter.common.FilterLandCard;
import mage.player.ai.land.LandSearchSelector;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.target.common.TargetCardInHand;
import mage.target.common.TargetCardInLibrary;
import mage.util.CardUtil;
import mage.util.RandomUtil;
import mage.util.ThreadUtils;
import mage.util.XmageThreadFactory;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * AI: server side bot with game simulations (mad bot, part of implementation)
 *
 * @author nantuko, JayDi85
 */
public class ComputerPlayer6 extends ComputerPlayer {

    private static final Logger logger = Logger.getLogger(ComputerPlayer6.class);

    // TODO: add and research maxNodes logs, is it good to increase from 5000 to 50000 for better results?
    // TODO: increase maxNodes due AI skill level like max depth?
    private static final int MAX_SIMULATED_NODES_PER_CALC = 5000;
    private static final int MAX_SIMULATED_NODES_PER_ERROR = 5100; // TODO: debug only, set low value to find big calculations

    // Sprint Debug: set to true to log AI decisions to the game log for tuning/validation
    private static final boolean AI_DEBUG_LOG = true;

    // Sprint 16 — cross-opponent chump reserve (declareAttackers)
    // A bot that is "defending" against a threatening opponent should keep cheap creatures
    // back as blockers rather than sending them all as attackers.
    //
    // DEFENDER_THRESHOLD: opponent threat score above which the bot enters "defender mode".
    // 3000 = a player with a mid-sized board + some hand cards. Below this the bot attacks freely.
    // Why 3000: a 3/3 + 3/3 + 3 lands ≈ 2×~900 + ramp bonus ≈ 2700; rounding up to 3000 catches
    // anyone who has developed a real board.
    private static final int DEFENDER_THRESHOLD = 3000;

    // HIGH_VALUE_THRESHOLD: permanent score above which a creature is considered "too valuable
    // to sacrifice as a chump blocker". Engine pieces, commanders, and bombers fall here.
    // 1200 = a 3/3 with flying + haste, or a 4/4 vanilla. Below this = expendable chump.
    // Why separate from CHUMP_RESERVE_MAX_SCORE: HIGH_VALUE_THRESHOLD guards ATTACK decisions
    // (Sprint 7/15), CHUMP_RESERVE_MAX_SCORE guards which creatures we PRE-RESERVE as chumps.
    private static final int HIGH_VALUE_THRESHOLD = 1200;

    // CHUMP_RESERVE_MAX_SCORE: creatures scored at or below this are considered "expendable"
    // and eligible to be held back as cross-opponent chump blockers (Sprint 16).
    // 900 = roughly a 2/2 vanilla (~800–900 pts). A 2/2 with a keyword ability (~1100) is excluded.
    private static final int CHUMP_RESERVE_MAX_SCORE = 900;

    // CHUMP_THREAT_MIN_POWER: minimum power on an opponent's attacker to trigger cross-opponent
    // chump reservation. A 4/4 is a real threat; a 2/2 is not worth pre-reserving blockers for.
    private static final int CHUMP_THREAT_MIN_POWER = 5;

    // MAX_CHUMPS_RESERVED: cap on how many creatures we hold back as chumps vs a single opponent.
    // Holding too many back loses tempo; 3 is enough to survive most alpha strikes.
    private static final int MAX_CHUMPS_RESERVED = 3;

    // Sprint 17 — coordinated risk/reward attack pass
    // TRIGGER_RELEVANT_MIN: if an attacker's "attack trigger value" (ETB-on-attack, damage triggers)
    // is at least this, the bot always attacks with it even if the math is risky.
    // 400 = roughly the value of drawing a card or creating a 1/1 token. Below this = not worth it.
    private static final int TRIGGER_RELEVANT_MIN = 400;

    // DISPOSABLE_BLOCKER_MAX_SCORE: blockers at or below this score are considered "disposable"
    // when evaluating whether an attack is safe. Tokens and vanilla 1/1s score ~250–350 pts.
    // The bot will attack into a blocker it can race if that blocker is disposable.
    private static final int DISPOSABLE_BLOCKER_MAX_SCORE = 350;

    // same params as Executors.newFixedThreadPool
    // no needs errors check in afterExecute here cause that pool used for FutureTask with result check already
    private static final ExecutorService threadPoolSimulations = new ThreadPoolExecutor(
            COMPUTER_MAX_THREADS_FOR_SIMULATIONS,
            COMPUTER_MAX_THREADS_FOR_SIMULATIONS,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new XmageThreadFactory(ThreadUtils.THREAD_PREFIX_AI_SIMULATION_MAD)
    );
    protected int maxDepth;
    protected int maxNodes;
    protected int maxThinkTimeSecs;
    protected LinkedList<Ability> actions = new LinkedList<>();
    protected List<UUID> targets = new ArrayList<>();
    protected List<String> choices = new ArrayList<>();
    protected Combat combat;
    protected int currentScore;
    protected SimulationNode2 root;
    List<Permanent> attackersList = new ArrayList<>();
    List<Permanent> attackersToCheck = new ArrayList<>();

    protected Set<String> actionCache;
    protected AiMemory memory = new AiMemory(); // Sprint 18: per-turn memory for mana reservation and action tracking
    private static final List<TreeOptimizer> optimizers = new ArrayList<>();
    protected int lastLoggedTurn = 0; // for debug logs: mark start of the turn
    protected static final String BLANKS = "...............................................";

    static {
        optimizers.add(new WrongCodeUsageOptimizer());
        optimizers.add(new LevelUpOptimizer());
        optimizers.add(new EquipOptimizer());
        optimizers.add(new DiscardCardOptimizer());
        optimizers.add(new OutcomeOptimizer());
        optimizers.add(new EarlyGameTempoOptimizer()); // Sprint 33B: turns 1-4 ramp/engine priority + suppress noop activations
        optimizers.add(new BoardwipeOptimizer());     // multiplayer: suppress boardwipes when bot has board advantage
        optimizers.add(new InstantTimingOptimizer()); // multiplayer: hold instants for opponent turns; no tap-cost waste before combat
        optimizers.add(new CounterOptimizer());       // Sprint 19: gate counter activations by stack category + self-position
        optimizers.add(new ProtectionOptimizer());    // Sprint 19: gate mass-protection by stack threat + board strength
    }

    public ComputerPlayer6(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        if (skill < 4) {
            maxDepth = 4; // TODO: can be increased to support better calculations? (example = 8, skill * 2)
        } else {
            maxDepth = skill;
        }
        maxThinkTimeSecs = skill * 3;
        maxNodes = MAX_SIMULATED_NODES_PER_CALC;
        this.actionCache = new HashSet<>();
    }

    public ComputerPlayer6(final ComputerPlayer6 player) {
        super(player);
        this.maxDepth = player.maxDepth;
        this.currentScore = player.currentScore;
        if (player.combat != null) {
            this.combat = player.combat.copy();
        }
        this.actions.addAll(player.actions);
        this.targets.addAll(player.targets);
        this.choices.addAll(player.choices);
        this.actionCache = player.actionCache;
        // memory is not copied: each real player has its own memory, not shared with simulations
    }

    public AiMemory getMemory() {
        return memory;
    }

    public void clearTurnMemory() {
        memory.clearAtEndOfTurn();
    }

    /**
     * Change simulation timeout - used for AI stability tests only
     */
    public void setMaxThinkTimeSecs(int maxThinkTimeSecs) {
        this.maxThinkTimeSecs = maxThinkTimeSecs;
    }

    @Override
    public ComputerPlayer6 copy() {
        return new ComputerPlayer6(this);
    }

    protected void printBattlefieldScore(Game game, String info) {
        if (logger.isInfoEnabled()) {
            logger.info("");
            logger.info("=================== " + info + ", turn " + game.getTurnNum() + ", " + game.getPlayer(game.getPriorityPlayerId()).getName() + " ===================");
            logger.info("[Stack]: " + game.getStack());
            printBattlefieldScore(game, playerId);
            for (UUID opponentId : game.getOpponents(playerId)) {
                printBattlefieldScore(game, opponentId);
            }
        }
    }

    protected void printBattlefieldScore(Game game, UUID playerId) {
        // hand
        Player player = game.getPlayer(playerId);
        GameStateEvaluator2.PlayerEvaluateScore score = GameStateEvaluator2.evaluate(playerId, game);
        logger.info(new StringBuilder("[").append(game.getPlayer(playerId).getName()).append("]")
                .append(", life = ").append(player.getLife())
                .append(", score = ").append(score.getTotalScore())
                .append(" (").append(score.getPlayerInfoFull()).append(")")
                .toString());
        String cardsInfo = player.getHand().getCards(game).stream()
                .map(card -> card.getName() + ":" + GameStateEvaluator2.HAND_CARD_SCORE) // TODO: add card score here after implement
                .collect(Collectors.joining("; "));
        StringBuilder sb = new StringBuilder("-> Hand: [")
                .append(cardsInfo)
                .append("]");
        logger.info(sb.toString());

        // battlefield
        sb.setLength(0);
        String ownPermanentsInfo = game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isOwnedBy(player.getId()))
                .map(p -> p.getName()
                        + (p.isTapped() ? ",tapped" : "")
                        + (p.isAttacking() ? ",attacking" : "")
                        + (p.getBlocking() > 0 ? ",blocking" : "")
                        + ":" + GameStateEvaluator2.evaluatePermanent(p, game, true))
                .collect(Collectors.joining("; "));
        sb.append("-> Permanents: [").append(ownPermanentsInfo).append("]");
        logger.info(sb.toString());
    }

    protected void act(Game game) {
        if (actions == null
                || actions.isEmpty()) {
            pass(game);
        } else {
            boolean usedStack = false;
            while (actions.peek() != null) {
                Ability ability = actions.poll();
                // example: ===> SELECTED ACTION for PlayerA: Play Swamp
                logger.info(String.format("===> SELECTED ACTION for %s: %s",
                        getName(),
                        getAbilityAndSourceInfo(game, ability, true)
                ));
                // Sprint 32: log land plays in real game chat with scores for all candidates
                if (AI_DEBUG_LOG && ability instanceof PlayLandAbility) {
                    Card playedLand = game.getCard(ability.getSourceId());
                    if (playedLand != null) {
                        List<Card> handLands = hand.getCards(game).stream()
                                .filter(c -> c != null && c.isLand(game))
                                .collect(Collectors.toList());
                        // Include played land in case it was already removed from hand reference
                        if (handLands.stream().noneMatch(c -> c.getId().equals(playedLand.getId()))) {
                            handLands.add(playedLand);
                        }
                        List<Card> handAll = new ArrayList<>(hand.getCards(game));
                        Card commander = mage.player.ai.land.LandRanker.getCommanderFromZone(game, getId());
                        mage.player.ai.land.LandRanker.RankContext ctx =
                                new mage.player.ai.land.LandRanker.RankContext(game, getId(), handAll, true, commander);
                        String scores = handLands.size() > 1
                                ? mage.player.ai.land.LandRanker.describeScores(handLands, ctx)
                                : playedLand.getName() + " (only option)";
                        game.fireStatusEvent(
                                "[AI:" + getName() + "] [LAND-SELECT] turn=" + game.getTurnNum()
                                        + " played=" + playedLand.getName()
                                        + " | " + scores,
                                false, false);
                    }
                }
                if (!ability.getTargets().isEmpty()) {
                    for (Target target : ability.getTargets()) {
                        for (UUID id : target.getTargets()) {
                            target.updateTarget(id, game);
                            if (!target.isNotTarget()) {
                                game.addSimultaneousEvent(GameEvent.getEvent(GameEvent.EventType.TARGETED, id, ability, ability.getControllerId()));
                            }
                        }
                    }
                }
                this.activateAbility((ActivatedAbility) ability, game);
                if (ability.isUsesStack()) {
                    usedStack = true;
                }
            }
            if (usedStack) {
                pass(game);
            }
        }
    }

    protected int addActions(SimulationNode2 node, int depth, int alpha, int beta) {
        boolean stepFinished = false;
        int val;
        if (logger.isTraceEnabled()
                && node != null
                && node.getAbilities() != null
                && !node.getAbilities().toString().equals("[Pass]")) {
            logger.trace("Add Action [" + depth + "] " + node.getAbilities().toString() + "  a: " + alpha + " b: " + beta);
        }
        Game game = node.getGame();
        if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS && Thread.currentThread().isInterrupted()) {
            logger.debug("AI game sim interrupted by timeout");
            return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
        }
        // Condition to stop deeper simulation
        if (SimulationNode2.nodeCount > MAX_SIMULATED_NODES_PER_ERROR) {
            // how-to fix: make sure you are disabled debug mode by COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS = false
            throw new IllegalStateException("AI ERROR: too much nodes (possible actions)");
        }
        if (depth <= 0
                || SimulationNode2.nodeCount > maxNodes
                || game.checkIfGameIsOver()) {
            val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
            if (logger.isTraceEnabled()) {
                StringBuilder sb = new StringBuilder("Add Actions -- reached end state  <").append(val).append('>');
                SimulationNode2 logNode = node;
                do {
                    sb.append(new StringBuilder(" <- [" + logNode.getDepth() + ']' + (logNode.getAbilities() != null ? logNode.getAbilities().toString() : "[empty]")));
                    logNode = logNode.getParent();
                } while ((logNode.getParent() != null));
                logger.trace(sb);
            }
        } else if (!node.getChildren().isEmpty()) {
            if (logger.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                        .append("] -- something added children ")
                        .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                        .append(" added children: ").append(node.getChildren().size()).append(" (");
                for (SimulationNode2 logNode : node.getChildren()) {
                    sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                }
                sb.append(')');
                logger.debug(sb);
            }
            val = minimaxAB(node, depth - 1, alpha, beta);
        } else {
            logger.trace("Add Action -- alpha: " + alpha + " beta: " + beta + " depth:" + depth + " step:" + game.getTurnStepType() + " for player:" + game.getPlayer(game.getActivePlayerId()).getName());
            if (allPassed(game)) {
                if (!game.getStack().isEmpty()) {
                    resolve(node, depth, game);
                } else {
                    stepFinished = true;
                }
            }

            if (game.checkIfGameIsOver()) {
                val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
            } else if (stepFinished) {
                logger.debug("Step finished");
                int testScore = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
                if (game.isActivePlayer(playerId)) {
                    if (testScore < currentScore) {
                        // if score at end of step is worse than original score don't check further
                        //logger.debug("Add Action -- abandoning check, no immediate benefit");
                        val = testScore;
                    } else {
                        val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
                    }
                } else {
                    val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
                }
            } else if (!node.getChildren().isEmpty()) {
                if (logger.isDebugEnabled()) {
                    StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                            .append("] -- trigger ")
                            .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                            .append(" added children: ").append(node.getChildren().size()).append(" (");
                    for (SimulationNode2 logNode : node.getChildren()) {
                        sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                    }
                    sb.append(')');
                    logger.debug(sb);
                }
                val = minimaxAB(node, depth - 1, alpha, beta);
            } else {
                val = simulatePriority(node, game, depth, alpha, beta);
            }
        }
        node.setScore(val);
        logger.trace("returning -- score: " + val + " depth:" + depth + " step:" + game.getTurnStepType() + " for player:" + game.getPlayer(node.getPlayerId()).getName());
        return val;

    }

    protected boolean getNextAction(Game game) {
        if (root != null
                && !root.children.isEmpty()) {
            SimulationNode2 test = root;
            root = root.children.get(0);
            while (!root.children.isEmpty()
                    && !root.playerId.equals(playerId)) {
                test = root;
                root = root.children.get(0);
            }
            logger.trace("Sim getNextAction -- game value:" + game.getState().getValue(true) + " test value:" + test.gameValue);
            if (root.playerId.equals(playerId)
                    && root.abilities != null
                    && game.getState().getValue(true).hashCode() == test.gameValue) {
                logger.info("simulating -- continuing previous actions chain");
                actions = new LinkedList<>(root.abilities);
                combat = root.combat;
                return true;
            } else {
                if (root.abilities == null || root.abilities.isEmpty()) {
                    logger.info("simulating -- need re-calculation (no more actions)");
                } else if (game.getState().getValue(true).hashCode() != test.gameValue) {
                    logger.info("simulating -- need re-calculation (game state changed between actions)");
                } else if (!root.playerId.equals(playerId)) {
                    // TODO: need research, why need playerId and why it taken from stack objects as controller
                    logger.info("simulating -- need re-calculation (active controller changed)");
                } else {
                    logger.info("simulating -- need re-calculation (unknown reason)");
                }
                return false;
            }
        }
        return false;
    }

    protected int minimaxAB(SimulationNode2 node, int depth, int alpha, int beta) {
        logger.trace("Sim minimaxAB [" + depth + "] -- a: " + alpha + " b: " + beta + " <" + (node != null ? node.getScore() : "null") + '>');
        UUID currentPlayerId = node.getGame().getPlayerList().get();
        SimulationNode2 bestChild = null;
        for (SimulationNode2 child : node.getChildren()) {
            Combat _combat = child.getCombat();
            if (alpha >= beta) {
                break;
            }
            if (SimulationNode2.nodeCount > MAX_SIMULATED_NODES_PER_ERROR) {
                throw new IllegalStateException("AI ERROR: too much nodes (possible actions)");
            }
            if (SimulationNode2.nodeCount > maxNodes) {
                break;
            }
            int val = addActions(child, depth - 1, alpha, beta);
            if (!currentPlayerId.equals(playerId)) {
                if (val < beta) {
                    beta = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.LOSE_GAME_SCORE) {
                    logger.debug("lose - break");
                    break;
                }
            } else {
                if (val > alpha) {
                    alpha = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.WIN_GAME_SCORE) {
                    logger.debug("win - break");
                    break;
                }
            }
        }
        node.children.clear();
        if (bestChild != null) {
            node.children.add(bestChild);
        }
        if (!currentPlayerId.equals(playerId)) {
            return beta;
        } else {
            return alpha;
        }
    }

    protected SearchEffect getSearchEffect(StackAbility ability) {
        for (Effect effect : ability.getEffects()) {
            if (effect instanceof SearchEffect) {
                return (SearchEffect) effect;
            }
        }
        return null;
    }

    protected void resolve(SimulationNode2 node, int depth, Game game) {
        StackObject stackObject = game.getStack().getFirstOrNull();
        if (stackObject == null) {
            throw new IllegalStateException("Catch empty stack on resolve (something wrong with sim code)");
        }
        if (stackObject instanceof StackAbility) {
            // AI hint for search effects (calc all possible cards for best score)
            SearchEffect effect = getSearchEffect((StackAbility) stackObject);
            if (effect != null
                    && stackObject.getControllerId().equals(playerId)) {
                Target target = effect.getTarget();
                if (!target.isChoiceCompleted(getId(), (StackAbility) stackObject, game, null)) {
                    for (UUID targetId : target.possibleTargets(stackObject.getControllerId(), stackObject.getStackAbility(), game)) {
                        Game sim = game.createSimulationForAI();
                        StackAbility newAbility = (StackAbility) stackObject.copy();
                        SearchEffect newEffect = getSearchEffect(newAbility);
                        newEffect.getTarget().addTarget(targetId, newAbility, sim);
                        sim.getStack().push(sim, newAbility);
                        SimulationNode2 newNode = new SimulationNode2(node, sim, depth, stackObject.getControllerId());
                        node.children.add(newNode);
                        newNode.getTargets().add(targetId);
                        logger.trace("Sim search -- node#: " + SimulationNode2.getCount() + " for player: " + sim.getPlayer(stackObject.getControllerId()).getName());
                    }
                    return;
                }
            }
        }
        stackObject.resolve(game);
        if (stackObject instanceof StackAbility) {
            game.getStack().remove(stackObject, game);
        }
        game.applyEffects();
        game.getPlayers().resetPassed();
        game.getPlayerList().setCurrent(game.getActivePlayerId());
    }

    /**
     * Base call for simulation of AI actions
     *
     * @return
     */
    protected Integer addActionsTimed() {
        // TODO: all actions added and calculated one by one,
        //  multithreading do not supported here
        // run new game simulation in parallel thread
        FutureTask<Integer> task = new FutureTask<>(() -> addActions(root, maxDepth, Integer.MIN_VALUE, Integer.MAX_VALUE));
        threadPoolSimulations.execute(task);
        try {
            int maxSeconds = maxThinkTimeSecs;
            if (COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS) {
                maxSeconds = 3600;
            }
            logger.debug("maxThink: " + maxSeconds + " seconds ");
            Integer res = task.get(maxSeconds, TimeUnit.SECONDS);
            if (res != null) {
                return res;
            }
        } catch (TimeoutException | InterruptedException e) {
            // AI thinks too long
            // how-to fix: look at stack info - it can contain bad ability with infinite choose dialog
            logger.warn("");
            logger.warn("AI player thinks too long (report it to github):");
            logger.warn(" - player: " + getName());
            logger.warn(" - battlefield size: " + root.game.getBattlefield().getAllPermanents().size());
            logger.warn(" - stack: " + root.game.getStack());
            logger.warn(" - game: " + root.game);
            printFreezeNode(root);
            logger.warn("");
            task.cancel(true);
        } catch (ExecutionException e) {
            // game error
            logger.error("AI player catch game error in simulation - " + getName() + " - " + root.game + ": " + e, e);
            task.cancel(true);
            // real games: must catch and log
            // unit tests: must raise again for fast fail
            if (this.isTestMode() && this.isFastFailInTestMode()) {
                throw new IllegalStateException("One of the simulated games raise the error: " + e, e);
            }
        } catch (Throwable e) {
            // ?
            logger.error("AI simulation catch unknown error: " + e, e);
            task.cancel(true);
        }
        //TODO: timeout handling
        return 0;
    }

    private void printFreezeNode(SimulationNode2 root) {
        // print simple tree - there are possible multiple child nodes, but ignore it - same for abilities
        List<String> chain = new ArrayList<>();
        SimulationNode2 node = root;
        while (node != null) {
            if (node.abilities != null && !node.abilities.isEmpty()) {
                Ability ability = node.abilities.get(0);
                String sourceInfo = CardUtil.getSourceIdName(node.game, ability);
                chain.add(String.format("%s: %s",
                        (sourceInfo.isEmpty() ? "unknown" : sourceInfo),
                        ability
                ));
            }
            node = node.children == null || node.children.isEmpty() ? null : node.children.get(0);
        }
        logger.warn("Possible freeze chain:");
        if (root != null && chain.isEmpty()) {
            logger.warn(" - unknown use case (too many possible targets?)"); // maybe can't finish any calc, maybe related to target options
        }
        chain.forEach(s -> {
            logger.warn(" - " + s);
        });
    }

    protected int simulatePriority(SimulationNode2 node, Game game, int depth, int alpha, int beta) {
        if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS && Thread.currentThread().isInterrupted()) {
            logger.debug("AI game sim interrupted by timeout");
            return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
        }
        node.setGameValue(game.getState().getValue(true).hashCode());
        SimulatedPlayer2 currentPlayer = (SimulatedPlayer2) game.getPlayer(game.getPlayerList().get());
        SimulationNode2 bestNode = null;
        List<Ability> allActions = currentPlayer.simulatePriority(game);
        optimize(game, allActions);
        int startedScore = GameStateEvaluator2.evaluate(this.getId(), node.getGame()).getTotalScore();
        if (logger.isInfoEnabled()
                && !allActions.isEmpty()
                && depth == maxDepth) {
            logger.info(String.format("POSSIBLE ACTION CHAINS for %s (%d, started score: %d)%s",
                    getName(),
                    allActions.size(),
                    startedScore,
                    (actions.isEmpty() ? "" : ":")
            ));
            for (int i = 0; i < allActions.size(); i++) {
                // print possible actions with detailed targets
                Ability possibleAbility = allActions.get(i);
                logger.info(String.format("-> #%d (%s)", i + 1, getAbilityAndSourceInfo(game, possibleAbility, true)));
            }
        }
        int actionNumber = 0;
        int bestValSubNodes = Integer.MIN_VALUE;
        for (Ability action : allActions) {
            actionNumber++;
            if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS && Thread.currentThread().isInterrupted()) {
                logger.info("Sim Prio [" + depth + "] -- interrupted");
                break;
            }
            Game sim = game.createSimulationForAI();
            if (!(action instanceof StaticAbility) //for MorphAbility, etc
                    && sim.getPlayer(currentPlayer.getId()).activateAbility((ActivatedAbility) action.copy(), sim)) {
                sim.applyEffects();
                if (checkForRepeatedAction(sim, node, action, currentPlayer.getId())) {
                    logger.debug("Sim Prio [" + depth + "] -- repeated action: " + action);
                    continue;
                }
                if (!sim.checkIfGameIsOver()
                        && (action.isUsesStack() || action instanceof PassAbility)) {
                    // skip priority for opponents before stack resolve
                    UUID nextPlayerId = sim.getPlayerList().get();
                    do {
                        sim.getPlayer(nextPlayerId).pass(game);
                        nextPlayerId = sim.getPlayerList().getNext();
                    } while (!Objects.equals(nextPlayerId, this.getId()));
                }
                SimulationNode2 newNode = new SimulationNode2(node, sim, action, depth, currentPlayer.getId());
                sim.checkStateAndTriggered();
                int finalScore;
                if (action instanceof PassAbility && sim.getStack().isEmpty()) {
                    // no more next actions, it's a final score
                    finalScore = GameStateEvaluator2.evaluate(this.getId(), sim).getTotalScore();
                } else {
                    // resolve current action and calc all next actions to find best score (return max possible score)
                    finalScore = addActions(newNode, depth - 1, alpha, beta);
                }
                logger.debug("Sim Prio " + BLANKS.substring(0, 2 + (maxDepth - depth) * 3) + '[' + depth + "]#" + actionNumber + " <" + finalScore + "> - (" + action + ") ");

                // Hints on data:
                // * node - started game with executed command (pay and put on stack)
                // * newNode - resolved game with resolved command (resolve stack)
                // * node.children - rewrites to store only best tree (e.g. contains only final data)
                // * node.score - rewrites to store max score (e.g. contains only final data)
                if (logger.isInfoEnabled()
                        && depth >= maxDepth) {
                    // show final calculated score and best actions chain from it
                    List<SimulationNode2> fullChain = new ArrayList<>();
                    fullChain.add(newNode);
                    SimulationNode2 finalNode = newNode;
                    while (!finalNode.getChildren().isEmpty()) {
                        finalNode = finalNode.getChildren().get(0);
                        fullChain.add(finalNode);
                    }

                    // example: Sim Prio [6] #1 <diff -19, +4444> (Lightning Bolt [aa5]: Cast Lightning Bolt -> Balduvian Bears [c49])
                    // total
                    logger.info(String.format("Sim Prio [%d] #%d <total score diff %s (from %s to %s)>",
                            depth,
                            actionNumber,
                            printDiffScore(finalScore - startedScore),
                            printDiffScore(startedScore),
                            printDiffScore(finalScore)
                    ));

                    // details
                    for (int chainIndex = 0; chainIndex < fullChain.size(); chainIndex++) {
                        SimulationNode2 currentNode = fullChain.get(chainIndex);
                        SimulationNode2 prevNode;
                        if (chainIndex == 0) {
                            prevNode = node;
                        } else {
                            prevNode = fullChain.get(chainIndex - 1);
                        }

                        int currentScore = GameStateEvaluator2.evaluate(this.getId(), currentNode.getGame()).getTotalScore();
                        int prevScore = GameStateEvaluator2.evaluate(this.getId(), prevNode.getGame()).getTotalScore();

                        if (currentNode.getAbilities() != null) {
                            // ON PRIORITY

                            // runtime check
                            if (currentNode.getAbilities().size() != 1) {
                                throw new IllegalStateException("AI's simulated game must contains only one selected action, but found: " + currentNode.getAbilities());
                            }
                            if (!currentNode.getTargets().isEmpty() || !currentNode.getChoices().isEmpty()) {
                                throw new IllegalStateException("WTF, simulated abilities with targets/choices");
                            }
                            logger.info(String.format("Sim Prio [%d] -> next action: [%d]<diff %s> (%s)",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore),
                                    getAbilityAndSourceInfo(currentNode.getGame(), currentNode.getAbilities().get(0), true)
                            ));
                        } else if (!currentNode.getTargets().isEmpty()) {
                            // ON TARGETS
                            String targetsInfo = currentNode.getTargets()
                                    .stream()
                                    .map(id -> {
                                        Player player = game.getPlayer(id);
                                        if (player != null) {
                                            return player.getName();
                                        }
                                        MageObject object = game.getObject(id);
                                        if (object != null) {
                                            return object.getIdName();
                                        }
                                        return "unknown";
                                    })
                                    .collect(Collectors.joining(", "));
                            logger.info(String.format("Sim Prio [%d] -> with possible choices: [%d]<diff %s> (%s)",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore),
                                    targetsInfo)
                            );
                        } else if (!currentNode.getChoices().isEmpty()) {
                            // ON CHOICES
                            String choicesInfo = String.join(", ", currentNode.getChoices());
                            logger.info(String.format("Sim Prio [%d] -> with possible choices (must not see that code): [%d]<diff %s> (%s)",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore),
                                    choicesInfo)
                            );
                        } else {
                            logger.info(String.format("Sim Prio [%d] -> with do nothing: [%d]<diff %s>",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore))
                            );
                        }
                    }
                }

                if (currentPlayer.getId().equals(playerId)) {
                    if (finalScore > bestValSubNodes) {
                        bestValSubNodes = finalScore;
                    }
                    if (depth == maxDepth
                            && action instanceof PassAbility) {
                        finalScore = finalScore - PASSIVITY_PENALTY; // passivity penalty
                    }
                    if (finalScore > alpha
                            || (depth == maxDepth
                            && finalScore == alpha
                            && RandomUtil.nextBoolean())) { // Adding random for equal value to get change sometimes
                        alpha = finalScore;
                        bestNode = newNode;
                        bestNode.setScore(finalScore);
                        if (!newNode.getChildren().isEmpty()) {
                            // TODO: wtf, must review all code to remove shared objects
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }

                        // keep only best node
                        if (depth == maxDepth) {
                            logger.info("Sim Prio [" + depth + "] -* BEST actions chain so far: <final score " + bestNode.getScore() + ">");
                            node.children.clear();
                            node.children.add(bestNode);
                            node.setScore(bestNode.getScore());
                        }
                    }

                    // no need to check other actions
                    if (finalScore == GameStateEvaluator2.WIN_GAME_SCORE) {
                        logger.debug("Sim Prio -- win - break");
                        break;
                    }
                } else {
                    if (finalScore < beta) {
                        beta = finalScore;
                        bestNode = newNode;
                        bestNode.setScore(finalScore);
                        if (!newNode.getChildren().isEmpty()) {
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }
                    }

                    // no need to check other actions
                    if (finalScore == GameStateEvaluator2.LOSE_GAME_SCORE) {
                        logger.debug("Sim Prio -- lose - break");
                        break;
                    }
                }
                if (alpha >= beta) {
                    break;
                }
                if (SimulationNode2.nodeCount > MAX_SIMULATED_NODES_PER_ERROR) {
                    throw new IllegalStateException("AI ERROR: too many nodes (possible actions)");
                }
                if (SimulationNode2.nodeCount > maxNodes) {
                    logger.debug("Sim Prio -- reached end-state");
                    break;
                }
            }
        } // end of for (allActions)

        if (depth == maxDepth) {
            // TODO: buggy? Why it ended with depth limit 6 on one Pass action?!
            logger.info("Sim Prio [" + depth + "] ## Ended due max actions chain depth limit (" + maxDepth + ") -- Nodes calculated: " + SimulationNode2.nodeCount);
        }
        if (bestNode != null) {
            node.children.clear();
            node.children.add(bestNode);
            node.setScore(bestNode.getScore());
            if (logger.isTraceEnabled()
                    && !bestNode.getAbilities().toString().equals("[Pass]")) {
                logger.trace(new StringBuilder("Sim Prio [").append(depth).append("] -- Set after (depth=").append(depth).append(")  <").append(bestNode.getScore()).append("> ").append(bestNode.getAbilities().toString()).toString());
            }
        }

        if (currentPlayer.getId().equals(playerId)) {
            return bestValSubNodes;
        } else {
            return beta;
        }
    }

    protected String getAbilityAndSourceInfo(Game game, Ability ability, boolean showTargets) {
        // ability
        // TODO: add modal info
        // + (action.isModal() ? " Mode = " + action.getModes().getMode().toString() : "")
        if (ability.isModal()) {
            //throw new IllegalStateException("TODO: need implement");
        }
        MageObject sourceObject = ability.getSourceObject(game);
        String abilityInfo = (sourceObject == null ? "" : sourceObject.getIdName() + ": ") + CardUtil.substring(ability.toString(), 30, "...");
        // targets
        String targetsInfo = "";
        if (showTargets) {
            List<String> allTargetsInfo = new ArrayList<>();
            ability.getAllSelectedTargets().forEach(target -> {
                target.getTargets().forEach(selectedId -> {
                    String xInfo = "";
                    if (target instanceof TargetAmount) {
                        xInfo = "x" + target.getTargetAmount(selectedId) + " ";
                    }

                    String targetInfo = null;
                    Player player = game.getPlayer(selectedId);
                    if (player != null) {
                        targetInfo = player.getName();
                    }
                    if (targetInfo == null) {
                        MageObject object = game.getObject(selectedId);
                        if (object != null) {
                            targetInfo = object.getIdName();
                        }
                    }
                    if (targetInfo == null) {
                        StackObject stackObject = game.getState().getStack().getStackObject(selectedId);
                        if (stackObject != null) {
                            targetInfo = CardUtil.substring(stackObject.toString(), 20, "...");
                        }
                    }
                    if (targetInfo == null) {
                        targetInfo = "unknown";
                    }
                    allTargetsInfo.add(xInfo + targetInfo);
                });
            });
            targetsInfo = String.join(" + ", allTargetsInfo);
        }
        return abilityInfo + (targetsInfo.isEmpty() ? "" : " -> " + targetsInfo);
    }

    private String printDiffScore(int score) {
        if (score >= 0) {
            return "+" + score;
        } else {
            return "" + score;
        }
    }

    /**
     * Various AI optimizations for actions.
     *
     * @param game
     * @param allActions
     */
    protected void optimize(Game game, List<Ability> allActions) {
        for (TreeOptimizer optimizer : optimizers) {
            optimizer.optimize(game, allActions);
        }
        Collections.sort(allActions, new Comparator<Ability>() {
            @Override
            public int compare(Ability ability1, Ability ability2) {
                String rule1 = ability1.toString();
                String rule2 = ability2.toString();

                // pass
                boolean pass1 = rule1.startsWith("Pass");
                boolean pass2 = rule2.startsWith("Pass");
                if (pass1 != pass2) {
                    if (pass1) {
                        return 1;
                    } else {
                        return -1;
                    }
                }

                // play
                boolean play1 = rule1.startsWith("Play");
                boolean play2 = rule2.startsWith("Play");
                if (play1 != play2) {
                    if (play1) {
                        return -1;
                    } else {
                        return 1;
                    }
                }

                // cast
                boolean cast1 = rule1.startsWith("Cast");
                boolean cast2 = rule2.startsWith("Cast");
                if (cast1 != cast2) {
                    if (cast1) {
                        return -1;
                    } else {
                        return 1;
                    }
                }

                // default
                return ability1.getRule().compareTo(ability2.getRule());
            }
        });
    }

    protected boolean allPassed(Game game) {
        for (Player player : game.getPlayers().values()) {
            if (!player.isPassed()
                    && !player.hasLost()
                    && !player.hasLeft()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean chooseMulligan(Game game) {
        if (isTestMode() || game.getClass().getName().contains("Momir")) {
            return false;
        }
        // Snap-keep at 4 cards or fewer, unless 0 lands — never keep a land-less hand.
        if (hand.size() < 5) {
            int landCount = 0;
            for (Card c : hand.getCards(game)) {
                if (c != null && c.isLand()) {
                    landCount++;
                }
            }
            if (landCount == 0) {
                game.fireStatusEvent(
                        "[AI:" + getName() + "] [MULLIGAN] hand=" + hand.size() + " → MULLIGAN [noLands]",
                        false, false);
                return true;
            }
            game.fireStatusEvent(
                    "[AI:" + getName() + "] [MULLIGAN] hand=" + hand.size() + " → KEEP [snapKeep]",
                    false, false);
            return false;
        }

        try {
            Card commander = game.getCommanderCardsFromCommandZone(this, CommanderCardType.COMMANDER_OR_OATHBREAKER)
                    .stream().findFirst().orElse(null);

            HandScore score = HandEvaluator.evaluate(
                    new ArrayList<>(hand.getCards(game)),
                    commander,
                    game,
                    hand.size()
            );

            boolean mulligan;
            String reason;
            if (score.hardReject) {
                mulligan = true;
                reason = "hardReject";
            } else if (score.autoKeep) {
                mulligan = false;
                reason = "autoKeep";
            } else if (hand.size() < 7) {
                // Hands of 5–6: hardReject already handled above. Everything else is acceptable.
                mulligan = false;
                reason = "ok";
            } else if (score.landCount < 2 || score.landCount > 5) {
                mulligan = true;
                reason = "landCount=" + score.landCount;
            } else if (score.manaCurveScore <= HandEvaluator.NO_EARLY_PLAYS_PENALTY) {
                mulligan = true;
                reason = "noEarlyPlays";
            } else if (score.total < HandEvaluator.KEEP_THRESHOLD_7) {
                mulligan = true;
                reason = "belowThreshold7";
            } else {
                mulligan = false;
                reason = "ok";
            }

            game.fireStatusEvent(
                    "[AI:" + getName() + "] [MULLIGAN] hand=" + hand.size()
                            + " score=" + score.total + " lands=" + score.landCount
                            + " curve=" + score.manaCurveScore
                            + " → " + (mulligan ? "MULLIGAN" : "KEEP") + " [" + reason + "]",
                    false, false);

            if (mulligan) {
                // Hint marker: next chooseTarget(put-on-bottom) should log a matching
                // [put-on-bottom] line. If it does not, the override detection broke
                // (e.g. upstream changed the target message). See CLAUDE.md sync notes.
                game.fireStatusEvent(
                        "[AI:" + getName() + "] [MULLIGAN] expecting put-on-bottom call",
                        false, false);
            }

            return mulligan;
        } catch (Throwable e) {
            // Fallback: HandEvaluator unavailable (e.g. mage.jar missing GameChangerRegistry).
            // Use simple land-count heuristic to avoid crashing the game thread.
            logger.warn("[AI:" + getName() + "] [MULLIGAN] HandEvaluator error (" + e.getClass().getSimpleName()
                    + "), using land-count fallback: " + e.getMessage());
            int lands = hand.getCards(StaticFilters.FILTER_CARD_LAND, game).size();
            return lands < 2 || lands > hand.size() - 2;
        }
    }

    /**
     * Override target selection to fix XMage's London Mulligan "put on bottom of library"
     * step: by default the bot scores cards by power/toughness so lands (zero score) are
     * picked first → bot ends up keeping a no-land hand. Here we detect the mulligan
     * put-back call and route to {@link #choosePutOnBottom} which keeps lands.
     *
     * <p>Detection is multi-signal so it survives upstream message changes: turn=0
     * (pre-game), TargetCardInHand, Outcome.Discard, plus the string match as final
     * filter. If 3 of the 4 signals match, still activates with a WARN log so the
     * regression is visible. See CLAUDE.md "Sync com magefree upstream" for the rebase
     * checklist.
     */
    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        boolean signalTurn = game.getTurnNum() == 0;
        boolean signalType = target instanceof TargetCardInHand;
        boolean signalOutcome = outcome == Outcome.Discard;
        String msg = target.getMessage(game);
        boolean signalMessage = msg != null && msg.contains("bottom of your library");

        int signalsMatched = (signalTurn ? 1 : 0) + (signalType ? 1 : 0)
                + (signalOutcome ? 1 : 0) + (signalMessage ? 1 : 0);

        if (signalsMatched >= 3) {
            if (signalsMatched < 4) {
                logger.warn("[AI:" + getName() + "] [put-on-bottom] partial signal match ("
                        + signalsMatched + "/4): turn=" + signalTurn + " type=" + signalType
                        + " outcome=" + signalOutcome + " msg=" + signalMessage
                        + " — upstream message may have changed, see CLAUDE.md sync notes");
            }
            return choosePutOnBottom(target, game);
        }

        // Sprint 32: intercept library land searches (fetchlands, Cultivate, Farseek, etc.)
        // Detection: TargetCardInLibrary whose filter is FilterLandCard OR all matching
        // candidates in the library are land cards — covers both FilterLandCard subtypes
        // and SubType-based filters like Farseek ("Plains, Island, Swamp, or Mountain").
        if (target instanceof TargetCardInLibrary) {
            TargetCardInLibrary libTarget = (TargetCardInLibrary) target;
            if (libTarget.getFilter() instanceof FilterLandCard
                    || allCandidatesAreLands(libTarget, game)) {
                List<Card> candidates = game.getPlayer(getId()).getLibrary().getCards(game)
                        .stream()
                        .filter(c -> libTarget.getFilter().match(c, game))
                        .collect(Collectors.toList());
                if (!candidates.isEmpty()) {
                    String sourceName = (source != null) ? source.getRule() : "unknown";
                    UUID bestId = LandSearchSelector.selectBestLandFromLibrary(
                            candidates, game, getId(), sourceName);
                    if (bestId != null) {
                        target.add(bestId, game);
                        return true;
                    }
                }
            }
        }

        return super.chooseTarget(outcome, target, source, game);
    }

    /**
     * Smart selection for London Mulligan's "put N cards on the bottom of your library":
     * dump non-lands by descending CMC first, preserving up to 2 lands in hand. Only
     * sends lands to the bottom when there are no non-lands left or there are more than
     * 2 lands available.
     */
    private boolean choosePutOnBottom(Target target, Game game) {
        int needed = target.getMinNumberOfTargets() - target.getTargets().size();
        if (needed <= 0) {
            return false;
        }

        List<Card> nonLands = new ArrayList<>();
        List<Card> lands = new ArrayList<>();
        for (Card c : hand.getCards(game)) {
            if (c == null || target.contains(c.getId())) {
                continue;
            }
            if (c.isLand()) {
                lands.add(c);
            } else {
                nonLands.add(c);
            }
        }
        // Dump highest-CMC non-lands first (most likely to be uncastable early).
        nonLands.sort((a, b) -> Integer.compare(b.getManaValue(), a.getManaValue()));

        int landsToKeep = Math.min(2, lands.size());
        int landsAvailableToDump = lands.size() - landsToKeep;

        int chosen = 0;
        int landsDumped = 0;

        for (Card c : nonLands) {
            if (chosen >= needed) break;
            target.add(c.getId(), game);
            chosen++;
        }
        for (int i = 0; i < landsAvailableToDump && chosen < needed; i++) {
            target.add(lands.get(i).getId(), game);
            chosen++;
            landsDumped++;
        }
        // Forced floor: only lands left in hand, must send some.
        for (int i = landsAvailableToDump; i < lands.size() && chosen < needed; i++) {
            target.add(lands.get(i).getId(), game);
            chosen++;
            landsDumped++;
        }

        int landsKept = lands.size() - landsDumped;
        game.fireStatusEvent(
                "[AI:" + getName() + "] [put-on-bottom] needed=" + needed
                        + " chose=" + chosen + " lands_kept=" + landsKept
                        + " lands_dumped=" + landsDumped,
                false, false);
        return chosen > 0;
    }

    /**
     * Sprint 32: returns true if every card in the library that matches the target's
     * filter is a land. Used to detect SubType-based land searches like Farseek
     * ("Plains, Island, Swamp, or Mountain") that don't extend {@code FilterLandCard}.
     */
    private boolean allCandidatesAreLands(TargetCardInLibrary target, Game game) {
        List<Card> sample = game.getPlayer(getId()).getLibrary().getCards(game)
                .stream()
                .filter(c -> target.getFilter().match(c, game))
                .collect(Collectors.toList());
        return !sample.isEmpty() && sample.stream().allMatch(c -> c.isLand(game));
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (choices.isEmpty()) {
            return super.choose(outcome, choice, game);
        }
        if (!choice.isChosen()) {
            if (!choice.setChoiceByAnswers(choices, true)) {
                choice.setRandomChoice();
            }
        }
        return true;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (targets.isEmpty()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        }

        UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());
        if (!target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
            for (UUID targetId : targets) {
                target.addTarget(targetId, source, game);
                if (target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (targets.isEmpty()) {
            return super.choose(outcome, cards, target, source, game);
        }

        UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());
        if (!target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
            for (UUID targetId : targets) {
                target.add(targetId, game);
                if (target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Logs an AI decision to the game log when AI_DEBUG_LOG is enabled.
     * Use this to validate and tune heuristic behaviour during local play.
     */
    private void aiLog(Game game, String msg) {
        if (AI_DEBUG_LOG) {
            game.fireStatusEvent("[AI:" + getName() + "] " + msg, false, false);
        }
    }

    /** High-value engine / commander — not ideal as planned sacrifice blockers. */
    private static boolean isEnginePiece(Permanent permanent, Game game) {
        return GameStateEvaluator2.evaluatePermanent(permanent, game, false) >= HIGH_VALUE_THRESHOLD;
    }

    /**
     * Expendable chump (tokens, vanilla) — good to hold vs cross-opponent ground threats.
     * Mirrors Sprint 13b ratio when board has a scored threat permanent.
     */
    private static boolean isExpendableChump(Permanent permanent, Game game, int maxBoardThreatPermScore) {
        int score = GameStateEvaluator2.evaluatePermanent(permanent, game, false);
        if (score >= HIGH_VALUE_THRESHOLD) {
            return false;
        }
        if (score <= CHUMP_RESERVE_MAX_SCORE) {
            return true;
        }
        return maxBoardThreatPermScore > 0 && score < (maxBoardThreatPermScore * 25 / 100);
    }

    /** Opponent creature our ground chumps can meaningfully block (no flying on threat). */
    private static boolean isGroundBlockableThreat(Permanent threat, Game game) {
        if (!threat.isCreature(game)) {
            return false;
        }
        if (threat.getPower().getValue() < CHUMP_THREAT_MIN_POWER) {
            return false;
        }
        return !threat.getAbilities().containsKey(FlyingAbility.getInstance().getId());
    }

    private static final class OpponentCombatThreatInfo {
        int maxGroundThreatPower;
        int maxBoardThreatPermScore;
        int blockableThreatCount;
    }

    private static OpponentCombatThreatInfo scanOpponentCombatThreats(Game game, List<UUID> opponents) {
        OpponentCombatThreatInfo info = new OpponentCombatThreatInfo();
        for (UUID opId : opponents) {
            for (Permanent perm : game.getBattlefield().getAllActivePermanents(opId)) {
                if (!perm.isCreature(game)) {
                    continue;
                }
                int permScore = GameStateEvaluator2.evaluatePermanent(perm, game, false);
                if (permScore > info.maxBoardThreatPermScore) {
                    info.maxBoardThreatPermScore = permScore;
                }
                if (isGroundBlockableThreat(perm, game)) {
                    int pow = perm.getPower().getValue();
                    if (pow > info.maxGroundThreatPower) {
                        info.maxGroundThreatPower = pow;
                    }
                    info.blockableThreatCount++;
                }
            }
        }
        return info;
    }

    /**
     * Before attacking, reserve lowest-score expendable creatures as chumps vs other players' boards.
     */
    private Set<UUID> reserveExpendableChumps(Game game, boolean underThreat,
            OpponentCombatThreatInfo threatInfo, List<Permanent> availableAttackers) {
        Set<UUID> reserved = new HashSet<>();
        if (!underThreat || game.isSimulation() || threatInfo.maxGroundThreatPower == 0) {
            return reserved;
        }
        int reservesNeeded = Math.min(MAX_CHUMPS_RESERVED, threatInfo.blockableThreatCount);
        if (reservesNeeded <= 0) {
            return reserved;
        }
        List<Permanent> candidates = availableAttackers.stream()
                .filter(p -> isExpendableChump(p, game, threatInfo.maxBoardThreatPermScore))
                .sorted(Comparator.comparingInt(p -> GameStateEvaluator2.evaluatePermanent(p, game, false)))
                .collect(Collectors.toList());
        for (int i = 0; i < Math.min(reservesNeeded, candidates.size()); i++) {
            Permanent chump = candidates.get(i);
            reserved.add(chump.getId());
            aiLog(game, "[HOLD] " + chump.getName() + " held as expendable chump (score "
                    + GameStateEvaluator2.evaluatePermanent(chump, game, false) + ") vs ground threat power "
                    + threatInfo.maxGroundThreatPower);
        }
        return reserved;
    }

    // ── Sprint 17 helpers ────────────────────────────────────────────────────

    // True if this blocker is disposable — a token or vanilla with very low evaluatePermanent score.
    private boolean isDisposableBlocker(Permanent blk, Game game) {
        return GameStateEvaluator2.evaluatePermanent(blk, game, false) < DISPOSABLE_BLOCKER_MAX_SCORE;
    }

    // Returns a bonus value if this attacker has a "whenever ~ attacks" trigger ability.
    // Intentionally conservative: only detects AttacksTriggeredAbility; other trigger types
    // that happen to need attacking may be missed, but this prevents false positives.
    private static int attackTriggerValue(Permanent attacker, Game game) {
        for (Ability ab : attacker.getAbilities()) {
            if (ab instanceof AttacksTriggeredAbility) {
                return HIGH_VALUE_THRESHOLD / 2; // ~600 pts — triggers are generally worth attacking for
            }
        }
        return 0;
    }

    // Estimates total unblocked damage AI would receive from ALL opponents next rotation,
    // assuming myAttackerIds creatures are tapped (unavailable to block).
    // Greedy assignment: best available blocker covers each threat, respecting flying/reach.
    // Does not model menace, shadow, protection, etc. (intentionally simple).
    private int incomingDamageNextRotation(Game game, Set<UUID> myAttackerIds) {
        Player me = game.getPlayer(playerId);
        if (me == null) return 0;

        // Pool of AI's available blockers (untapped, not in attack)
        List<Permanent> availBlockers = new ArrayList<>();
        for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (!p.isCreature(game) || p.isTapped() || myAttackerIds.contains(p.getId())) continue;
            availBlockers.add(p);
        }

        Set<UUID> usedBlockers = new HashSet<>();
        int totalIncoming = 0;

        for (UUID opId : game.getOpponents(playerId, true)) {
            Player opponent = game.getPlayer(opId);
            if (opponent == null || !opponent.isInGame()) continue;

            List<Permanent> oppCreatures = new ArrayList<>();
            for (Permanent p : game.getBattlefield().getAllActivePermanents(opId)) {
                if (!p.isCreature(game) || p.isTapped() || p.getPower().getValue() <= 0) continue;
                oppCreatures.add(p);
            }
            // Worst threats first
            oppCreatures.sort((a, b) -> Integer.compare(b.getPower().getValue(), a.getPower().getValue()));

            for (Permanent threat : oppCreatures) {
                boolean threatFlying = threat.getAbilities().containsKey(FlyingAbility.getInstance().getId());

                // Find strongest available blocker that can reach this threat
                Permanent bestBlocker = null;
                int bestPow = -1;
                for (Permanent blk : availBlockers) {
                    if (usedBlockers.contains(blk.getId())) continue;
                    boolean blkFlying = blk.getAbilities().containsKey(FlyingAbility.getInstance().getId());
                    boolean blkReach = blk.getAbilities().containsKey(ReachAbility.getInstance().getId());
                    if (threatFlying && !blkFlying && !blkReach) continue;
                    if (blk.getPower().getValue() > bestPow) {
                        bestPow = blk.getPower().getValue();
                        bestBlocker = blk;
                    }
                }

                if (bestBlocker != null) {
                    usedBlockers.add(bestBlocker.getId());
                    // Trample leaks excess damage past blocker
                    if (threat.getAbilities().containsKey(TrampleAbility.getInstance().getId())) {
                        int excess = threat.getPower().getValue() - bestBlocker.getToughness().getValue();
                        if (excess > 0) totalIncoming += excess;
                    }
                } else {
                    // Unblockable → all power goes to face
                    totalIncoming += threat.getPower().getValue();
                }
            }
        }
        return totalIncoming;
    }

    // Estimates the value delivered by attacking with swarm against defenderId.
    // Value = face-damage component (unblocked power × 150) + valuable blocker forced off board
    //         + attack trigger bonuses.
    // Assumes defender blocks with cheapest willing blockers to minimise loss.
    // Face-damage weight (150) is intentionally low — points, not life total; keeps units comparable.
    private int swarmAttackValue(Game game, UUID defenderId, List<Permanent> swarm) {
        if (swarm.isEmpty()) return 0;
        Player defender = game.getPlayer(defenderId);
        if (defender == null || !defender.isInGame()) return 0;

        // Cheapest-first: defender uses disposable chumps first to absorb attackers
        List<Permanent> defBlockers = new ArrayList<>(defender.getAvailableBlockers(game));
        defBlockers.sort(Comparator.comparingInt(p -> p.getToughness().getValue()));

        // Strongest attackers first (maximise pressure on blockers)
        List<Permanent> sortedSwarm = swarm.stream()
                .sorted((a, b) -> Integer.compare(b.getPower().getValue(), a.getPower().getValue()))
                .collect(Collectors.toList());

        Set<UUID> usedBlockers = new HashSet<>();
        int value = 0;

        for (Permanent atk : sortedSwarm) {
            boolean atkFlying = atk.getAbilities().containsKey(FlyingAbility.getInstance().getId());
            boolean atkTrample = atk.getAbilities().containsKey(TrampleAbility.getInstance().getId());

            // Find defender's cheapest willing blocker for this attacker.
            // Defender only blocks if (a) blocker is disposable (cheap, willing to chump), OR
            // (b) blocker survives (no loss), OR (c) trade is profitable (attacker score >= blocker score).
            // This prevents the model from assuming a defender would block a 2/2 with their key 10/10.
            int atkScore = GameStateEvaluator2.evaluatePermanent(atk, game, false);
            Permanent chosenBlocker = null;
            for (Permanent blk : defBlockers) {
                if (usedBlockers.contains(blk.getId())) continue;
                boolean blkFlying = blk.getAbilities().containsKey(FlyingAbility.getInstance().getId());
                boolean blkReach = blk.getAbilities().containsKey(ReachAbility.getInstance().getId());
                if (atkFlying && !blkFlying && !blkReach) continue;
                boolean blkDisposable = isDisposableBlocker(blk, game);
                boolean blkSurvives = blk.getToughness().getValue() > atk.getPower().getValue();
                boolean canKillAttacker = blk.getPower().getValue() >= atk.getToughness().getValue();
                int blkScore = GameStateEvaluator2.evaluatePermanent(blk, game, false);
                boolean profitableTrade = canKillAttacker && atkScore >= blkScore;
                if (blkDisposable || blkSurvives || profitableTrade) {
                    chosenBlocker = blk;
                    break;
                }
            }

            if (chosenBlocker == null) {
                // Unblocked — damage lands on player
                value += atk.getPower().getValue() * 150;
                if (atkTrample) {
                    // Trample: all damage through (no blocker)
                    // already counted above
                }
            } else {
                usedBlockers.add(chosenBlocker.getId());
                if (atkTrample) {
                    int excess = atk.getPower().getValue() - chosenBlocker.getToughness().getValue();
                    if (excess > 0) value += excess * 150;
                }
                // Killing a valuable (non-disposable) blocker has real board value
                if (atk.getPower().getValue() >= chosenBlocker.getToughness().getValue()
                        && !isDisposableBlocker(chosenBlocker, game)) {
                    value += GameStateEvaluator2.evaluatePermanent(chosenBlocker, game, false);
                }
            }

            // Attack-trigger bonus (e.g. draw a card on attack)
            value += attackTriggerValue(atk, game);
        }

        return value;
    }

    // ── end Sprint 17 helpers ─────────────────────────────────────────────────

    private void declareBlockers(Game game, UUID activePlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_BLOCKERS, activePlayerId, activePlayerId))) {
            List<Permanent> attackers = getAttackers(game);
            if (attackers == null) {
                return;
            }

            List<Permanent> possibleBlockers = super.getAvailableBlockers(game);
            possibleBlockers = filterOutNonblocking(game, attackers, possibleBlockers);
            if (possibleBlockers.isEmpty()) {
                return;
            }

            attackers = filterOutUnblockable(game, attackers, possibleBlockers);
            if (attackers.isEmpty()) {
                return;
            }

            CombatUtil.sortByPower(attackers, false); // most powerfull go to first

            CombatInfo combatInfo = CombatUtil.blockWithGoodTrade2(game, attackers, possibleBlockers);
            Player player = game.getPlayer(playerId);

            boolean blocked = false;

            // Build shared tracking sets (used by all blocker logic below)
            Set<UUID> coveredAttackerIds = new HashSet<>();
            Set<UUID> usedBlockerIds = new HashSet<>();

            // Assign good-trade blocks from CombatUtil
            for (Map.Entry<Permanent, List<Permanent>> entry : combatInfo.getCombat().entrySet()) {
                UUID attackerId = entry.getKey().getId();
                List<Permanent> blockers = entry.getValue();
                if (blockers != null && !blockers.isEmpty()) {
                    coveredAttackerIds.add(attackerId);
                    for (Permanent blocker : blockers) {
                        // TODO: buggy or miss on multi blocker requirements?!
                        player.declareBlocker(player.getId(), blocker.getId(), attackerId, game);
                        usedBlockerIds.add(blocker.getId());
                        blocked = true;
                    }
                }
            }

            // Sprint 13a — Deathtouch blocker priority: a single deathtouch blocker kills
            // any attacker regardless of P/T. Assign the smallest available deathtouch
            // blocker to the highest-value unblocked attacker when the trade is favourable.
            {
                List<Permanent> unblockedSorted = attackers.stream()
                        .filter(a -> !coveredAttackerIds.contains(a.getId()))
                        .sorted((a, b) -> Integer.compare(
                                GameStateEvaluator2.evaluatePermanent(b, game, false),
                                GameStateEvaluator2.evaluatePermanent(a, game, false)))
                        .collect(Collectors.toList());

                for (Permanent atk : unblockedSorted) {
                    int atkScore = GameStateEvaluator2.evaluatePermanent(atk, game, false);

                    // Pick smallest (least valuable) available deathtouch blocker
                    Optional<Permanent> dtOpt = possibleBlockers.stream()
                            .filter(b -> !usedBlockerIds.contains(b.getId()))
                            .filter(b -> b.canBlock(atk.getId(), game))
                            .filter(b -> b.getAbilities().containsKey(DeathtouchAbility.getInstance().getId()))
                            .min(Comparator.comparingInt(b ->
                                    GameStateEvaluator2.evaluatePermanent(b, game, false)));

                    if (dtOpt.isPresent()) {
                        int dtScore = GameStateEvaluator2.evaluatePermanent(dtOpt.get(), game, false);
                        if (atkScore > dtScore) {
                            player.declareBlocker(player.getId(), dtOpt.get().getId(), atk.getId(), game);
                            usedBlockerIds.add(dtOpt.get().getId());
                            coveredAttackerIds.add(atk.getId());
                            blocked = true;
                            aiLog(game, "[BLOCK] " + dtOpt.get().getName() + " (deathtouch) blocks " + atk.getName()
                                    + " — kills on contact, risking only " + dtScore + " pts against a " + atkScore + " pt threat.");
                        }
                    }
                }
            }

            // Sprint 13b — Multi-block with expendable creatures: if a high-value attacker is
            // unblocked, gang up with 2+ cheap/expendable blockers to trade up in score.
            // "Expendable" = score < attacker score × 0.40 (cheap creatures worth sacrificing).
            // Skip indestructible attackers (can't be killed by damage).
            {
                final int HIGH_VALUE_ATK_THRESHOLD = 1200;

                List<Permanent> highValueUnblocked = attackers.stream()
                        .filter(a -> !coveredAttackerIds.contains(a.getId()))
                        .filter(a -> GameStateEvaluator2.evaluatePermanent(a, game, false) >= HIGH_VALUE_ATK_THRESHOLD)
                        .sorted((a, b) -> Integer.compare(
                                GameStateEvaluator2.evaluatePermanent(b, game, false),
                                GameStateEvaluator2.evaluatePermanent(a, game, false)))
                        .collect(Collectors.toList());

                for (Permanent atk : highValueUnblocked) {
                    if (coveredAttackerIds.contains(atk.getId())) {
                        continue;
                    }
                    // Can't kill indestructible with damage
                    if (atk.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())) {
                        continue;
                    }

                    int atkScore = GameStateEvaluator2.evaluatePermanent(atk, game, false);
                    final int expThreshold = (int) (atkScore * 0.40);

                    // Gather expendable blockers sorted by power desc (use fewest blockers possible)
                    List<Permanent> expendable = possibleBlockers.stream()
                            .filter(b -> !usedBlockerIds.contains(b.getId()))
                            .filter(b -> b.canBlock(atk.getId(), game))
                            .filter(b -> GameStateEvaluator2.evaluatePermanent(b, game, false) < expThreshold)
                            .sorted((a, b) -> Integer.compare(b.getPower().getValue(), a.getPower().getValue()))
                            .collect(Collectors.toList());

                    // Check if 2+ can gang-kill (combined power >= attacker toughness)
                    int cumulativePow = 0;
                    List<Permanent> gangSet = new ArrayList<>();
                    for (Permanent blk : expendable) {
                        cumulativePow += blk.getPower().getValue();
                        gangSet.add(blk);
                        if (cumulativePow >= atk.getToughness().getValue()) {
                            break;
                        }
                    }

                    if (gangSet.size() >= 2 && cumulativePow >= atk.getToughness().getValue()) {
                        for (Permanent blk : gangSet) {
                            player.declareBlocker(player.getId(), blk.getId(), atk.getId(), game);
                            usedBlockerIds.add(blk.getId());
                            blocked = true;
                        }
                        coveredAttackerIds.add(atk.getId());
                        aiLog(game, "[BLOCK] Throwing " + gangSet.size() + " small creatures at " + atk.getName()
                                + " — their threat (" + atkScore + " pts) is too high to let through, each blocker worth less than " + expThreshold + " pts.");
                    }
                }
            }

            // Sprint 10a — Chump block (survival): blockWithGoodTrade2 only assigns "good trades",
            // so lethal attackers may remain unblocked. If total unblocked power >= our life,
            // sacrifice the least valuable available blockers to survive the turn.
            {
                int unblockedDamage = 0;
                List<Permanent> unblockedAttackers = new ArrayList<>();
                for (Permanent atk : attackers) {
                    if (!coveredAttackerIds.contains(atk.getId())) {
                        unblockedDamage += atk.getPower().getValue();
                        unblockedAttackers.add(atk);
                    }
                }

                if (unblockedDamage >= player.getLife() && player.getLife() > 0) {
                    // Sort biggest threats first; sacrifice smallest-value blockers first
                    unblockedAttackers.sort((a, b) -> Integer.compare(b.getPower().getValue(), a.getPower().getValue()));
                    List<Permanent> chumpCandidates = possibleBlockers.stream()
                            .filter(b -> !usedBlockerIds.contains(b.getId()))
                            .sorted(Comparator.comparingInt(b -> b.getPower().getValue() + b.getToughness().getValue()))
                            .collect(Collectors.toList());

                    int idx = 0;
                    for (Permanent attacker : unblockedAttackers) {
                        if (unblockedDamage < player.getLife()) {
                            break;
                        }
                        if (idx >= chumpCandidates.size()) {
                            break;
                        }
                        Permanent chump = chumpCandidates.get(idx++);
                        if (chump.canBlock(attacker.getId(), game)) {
                            player.declareBlocker(player.getId(), chump.getId(), attacker.getId(), game);
                            unblockedDamage -= attacker.getPower().getValue();
                            blocked = true;
                            aiLog(game, "[BLOCK] " + chump.getName() + " chump-blocks " + attacker.getName()
                                    + " to reduce incoming damage — still " + unblockedDamage + " damage coming, I have " + player.getLife() + " life.");
                        }
                    }
                }
            }

            if (blocked) {
                game.getPlayers().resetPassed();
            }
        }
    }

    private List<Permanent> filterOutNonblocking(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> blockersLeft = new ArrayList<>();
        for (Permanent blocker : blockers) {
            for (Permanent attacker : attackers) {
                if (blocker.canBlock(attacker.getId(), game)) {
                    blockersLeft.add(blocker);
                    break;
                }
            }
        }
        return blockersLeft;
    }

    private List<Permanent> filterOutUnblockable(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> attackersLeft = new ArrayList<>();
        for (Permanent attacker : attackers) {
            if (CombatUtil.canBeBlocked(game, attacker, blockers)) {
                attackersLeft.add(attacker);
            }
        }
        return attackersLeft;
    }

    private List<Permanent> getAttackers(Game game) {
        Set<UUID> attackersUUID = game.getCombat().getAttackers();
        if (attackersUUID.isEmpty()) {
            return null;
        }

        List<Permanent> attackers = new ArrayList<>();
        for (UUID attackerId : attackersUUID) {
            Permanent permanent = game.getPermanent(attackerId);
            attackers.add(permanent);
        }
        return attackers;
    }

    /**
     * Choose attackers based on static information. That means that AI won't
     * look to the future as it was before, but just choose attackers based on
     * current state of the game. This is worse, but at least it is easier to
     * implement and won't lead to the case when AI doesn't do anything -
     * neither attack nor block.
     *
     * @param game
     * @param activePlayerId
     */
    private void declareAttackers(Game game, UUID activePlayerId) {
        attackersToCheck.clear();
        attackersList.clear();
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_ATTACKERS, activePlayerId, activePlayerId))) {
            Player attackingPlayer = game.getPlayer(activePlayerId);

            // check alpha strike first (all in attack to kill a player)
            for (UUID defenderId : game.getOpponents(playerId, true)) {
                Player defender = game.getPlayer(defenderId);
                if (!defender.isInGame()) {
                    continue;
                }

                attackersList = super.getAvailableAttackers(defenderId, game);
                if (attackersList.isEmpty()) {
                    continue;
                }
                List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);
                List<Permanent> killers = CombatUtil.canKillOpponent(game, attackersList, possibleBlockers, defender);
                if (!killers.isEmpty()) {
                    for (Permanent attacker : killers) {
                        attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
                    }
                    return;
                }
            }

            // TODO: add game simulations here to find best attackers/blockers combination

            // Multiplayer improvement: sort opponents by threat score (board + ramp + hand + life)
            // so we prioritize attacking the most dangerous player rather than the first in iteration order.
            // Cache scores to avoid recomputing evaluatePlayerThreat (iterates all permanents) multiple times.
            List<UUID> sortedOpponents = new ArrayList<>(game.getOpponents(playerId, true));
            Map<UUID, Integer> threatCache = new java.util.HashMap<>();
            for (UUID opId : sortedOpponents) {
                threatCache.put(opId, GameStateEvaluator2.evaluatePlayerThreat(opId, game));
            }
            sortedOpponents.sort((a, b) -> Integer.compare(threatCache.get(b), threatCache.get(a)));

            // Log threat order so observers can see who the AI is prioritising
            if (!game.isSimulation()) {
                StringBuilder sbThreat = new StringBuilder("[ATTACK] Threat order: ");
                for (int i = 0; i < sortedOpponents.size(); i++) {
                    UUID opId = sortedOpponents.get(i);
                    sbThreat.append(game.getPlayer(opId).getName())
                            .append(" (").append(threatCache.get(opId)).append(" pts)");
                    if (i < sortedOpponents.size() - 1) {
                        sbThreat.append(" > ");
                    }
                }
                aiLog(game, sbThreat.toString());
            }

            // Sprint 16: if any opponent has significant board presence, reserve expendable chumps
            // (low score) for cross-opponent defense — not high-value engines (see reserveExpendableChumps).
            int maxOpponentThreat = 0;
            for (UUID opponentId : sortedOpponents) {
                int t = threatCache.get(opponentId);
                if (t > maxOpponentThreat) {
                    maxOpponentThreat = t;
                }
            }

            // Threshold: roughly equivalent to an opponent having a decent board
            // (e.g. a 3/3 + 2/2 = ~1600 perm score x3 = ~4800; we use 3000 as trigger)
            final boolean underThreat = maxOpponentThreat > DEFENDER_THRESHOLD;

            OpponentCombatThreatInfo opponentThreatInfo = scanOpponentCombatThreats(game, sortedOpponents);
            List<Permanent> allAvailableAttackers = super.getAvailableAttackers(game);
            Set<UUID> reservedChumpIds = reserveExpendableChumps(game, underThreat, opponentThreatInfo, allAvailableAttackers);

            // find safe attackers (can't be killed by blockers)
            for (UUID defenderId : sortedOpponents) {
                Player defender = game.getPlayer(defenderId);
                if (!defender.isInGame()) {
                    continue;
                }
                attackersList = super.getAvailableAttackers(defenderId, game);
                if (attackersList.isEmpty()) {
                    continue;
                }
                List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);

                // The AI will now attack more sanely.  Simple, but good enough for now.
                // The sim minmax does not work at the moment.
                boolean safeToAttack;
                CombatEvaluator eval = new CombatEvaluator();

                for (Permanent attacker : attackersList) {
                    safeToAttack = true;
                    int attackerValue = eval.evaluate(attacker, game);
                    for (Permanent blocker : possibleBlockers) {
                        int blockerValue = eval.evaluate(blocker, game);

                        // blocker can kill attacker
                        if (attacker.getPower().getValue() <= blocker.getToughness().getValue()
                                && attacker.getToughness().getValue() <= blocker.getPower().getValue()) {
                            safeToAttack = false;
                        }

                        // attacker and blocker have the same P/T, check their overall value
                        if (attacker.getToughness().getValue() == blocker.getPower().getValue()
                                && attacker.getPower().getValue() == blocker.getToughness().getValue()) {
                            if (attackerValue > blockerValue
                                    || blocker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                    || blocker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                    || blocker.getAbilities().contains(new ExaltedAbility())
                                    || blocker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                                    || blocker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())
                                    || !attacker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                    || !attacker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                    || !attacker.getAbilities().contains(new ExaltedAbility())) {
                                safeToAttack = false;
                            }
                        }

                        // attacker can kill by deathtouch
                        if (attacker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                                || attacker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())) {
                            safeToAttack = true;
                        }

                        // attacker has flying and blocker has neither flying nor reach
                        if (attacker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                                && !blocker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                                && !blocker.getAbilities().containsKey(ReachAbility.getInstance().getId())) {
                            safeToAttack = true;
                        }

                        // if any check fails, move on to the next possible attacker
                        if (!safeToAttack) {
                            break;
                        }
                    }

                    // 0 power, don't bother attacking
                    if (attacker.getPower().getValue() == 0) {
                        safeToAttack = false;
                    }

                    // ── Sprints 7, 9, 12, 15 ────────────────────────────────────────────────
                    // These checks call evaluatePermanent() in nested loops and are too expensive
                    // to run inside the minimax simulation (15 000 nodes × N attackers × M blockers).
                    // During simulation the basic P/T check above is sufficient; full heuristics
                    // only apply on the real board (isSimulation() == false).
                    if (!game.isSimulation()) {

                    if (reservedChumpIds.contains(attacker.getId())) {
                        safeToAttack = false;
                    }

                    // Sprint 16 — Cross-opponent: expendable chips vs global ground threats when
                    // this attack target has no blockers (tokens stay home for the 9/9 on another player).
                    if (safeToAttack && underThreat && possibleBlockers.isEmpty()
                            && opponentThreatInfo.maxGroundThreatPower > 0
                            && isExpendableChump(attacker, game, opponentThreatInfo.maxBoardThreatPermScore)
                            && !isEnginePiece(attacker, game)
                            && opponentThreatInfo.maxGroundThreatPower >= attacker.getToughness().getValue()) {
                        safeToAttack = false;
                        aiLog(game, "[HOLD vs " + defender.getName() + "] " + attacker.getName() + " stays back — expendable chump (score "
                                + GameStateEvaluator2.evaluatePermanent(attacker, game, false)
                                + ") vs global ground threat power " + opponentThreatInfo.maxGroundThreatPower
                                + "; target has no blockers.");
                    }

                    // Sprint 7 — Skip attacks with no offensive value: attacker survives but can't
                    // kill any blocker and can't deal direct damage. Tapping a creature for zero
                    // result is a wasted action.
                    // Exception: trample (excess damage passes through), lifelink (life gain has value).
                    if (safeToAttack && !possibleBlockers.isEmpty()) {
                        boolean hasTrample = attacker.getAbilities().containsKey(TrampleAbility.getInstance().getId());
                        boolean hasLifelink = attacker.getAbilities().containsKey(LifelinkAbility.getInstance().getId());
                        if (!hasTrample && !hasLifelink) {
                            // check if attacker can kill at least one possible blocker
                            boolean canKillAnyBlocker = possibleBlockers.stream().anyMatch(b ->
                                    b.getToughness().getValue() <= attacker.getPower().getValue());
                            if (!canKillAnyBlocker) {
                                safeToAttack = false; // safe but useless — keep as blocker instead
                                aiLog(game, "[HOLD vs " + defender.getName() + "] " + attacker.getName() + " stays back — attacking gains nothing (no trample/lifelink, can't kill any blocker).");
                            }
                        }
                    }

                    // Sprint 9 — Multi-block trade check: even if no single blocker kills the attacker,
                    // two or more blockers acting together might. If the combined power of the optimal
                    // blocking set reaches the attacker's toughness, compare scores: attacker score vs.
                    // the score of blockers the attacker can kill in return. Suppress the attack if the
                    // trade is net-negative for us (attacker worth more than what it kills).
                    // Exceptions: trample, indestructible, deathtouch.
                    if (safeToAttack && possibleBlockers.size() >= 2) {
                        boolean hasTrample9 = attacker.getAbilities().containsKey(TrampleAbility.getInstance().getId());
                        boolean hasIndestructible9 = attacker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId());
                        boolean hasDeathtouch9 = attacker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId());
                        if (!hasTrample9 && !hasIndestructible9 && !hasDeathtouch9) {
                            int attackerToughness = attacker.getToughness().getValue();
                            int attackerPow = attacker.getPower().getValue();
                            // Sort by power desc — opponent picks strongest to kill attacker fastest
                            List<Permanent> sortedBlk = possibleBlockers.stream()
                                    .sorted((a, b) -> Integer.compare(b.getPower().getValue(), a.getPower().getValue()))
                                    .collect(Collectors.toList());
                            int cumulativePow = 0;
                            int blockersNeeded = 0;
                            for (Permanent blk : sortedBlk) {
                                cumulativePow += blk.getPower().getValue();
                                blockersNeeded++;
                                if (cumulativePow >= attackerToughness) {
                                    break;
                                }
                            }
                            if (blockersNeeded >= 2 && cumulativePow >= attackerToughness) {
                                // Attacker can die to multi-block. Is the value trade worth it?
                                int attackerScore = GameStateEvaluator2.evaluatePermanent(attacker, game, false);
                                // Count what the attacker kills: assign damage to weakest blockers first
                                List<Permanent> blockingSet = sortedBlk.subList(0, blockersNeeded);
                                List<Permanent> sortedByToughness = blockingSet.stream()
                                        .sorted((a, b) -> Integer.compare(a.getToughness().getValue(), b.getToughness().getValue()))
                                        .collect(Collectors.toList());
                                int damageLeft = attackerPow;
                                int killedScore = 0;
                                for (Permanent blk : sortedByToughness) {
                                    if (damageLeft >= blk.getToughness().getValue()) {
                                        damageLeft -= blk.getToughness().getValue();
                                        killedScore += GameStateEvaluator2.evaluatePermanent(blk, game, false);
                                    } else {
                                        break;
                                    }
                                }
                                if (attackerScore > killedScore) {
                                    safeToAttack = false; // unfavorable trade against gang-block
                                    aiLog(game, "[HOLD vs " + defender.getName() + "] " + attacker.getName() + " stays back — " + blockersNeeded + " blockers could gang-kill it (worth " + attackerScore + " pts), it would only take out " + killedScore + " pts in return. Bad trade.");
                                }
                            }
                        }
                    }

                    // Early game restraint (Sprint 12): in turns 1-4, tapping small expendable ground
                    // creatures for chip damage is net-negative. Engines (high score) may still attack if safe.
                    if (safeToAttack
                            && game.getTurnNum() <= 4
                            && attacker.getPower().getValue() < 3
                            && !attacker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                            && isExpendableChump(attacker, game, opponentThreatInfo.maxBoardThreatPermScore)) {
                        safeToAttack = false;
                        aiLog(game, "[HOLD vs " + defender.getName() + "] " + attacker.getName() + " stays home — turn " + game.getTurnNum()
                                + " early expendable (power " + attacker.getPower().getValue() + "). Better as a blocker.");
                    }

                    // Sprint 15 — High-value piece protection: don't risk attacking with a
                    // high-score piece when 2+ expendable opponent blockers can gang-kill it.
                    // Complements Sprint 9 (which uses all blockers); this specifically protects
                    // valuable pieces (commanders, bombs) from cheap sacrifice trades.
                    // Exceptions: trample (damage leaks through), indestructible, deathtouch.
                    if (safeToAttack) {
                        int atkScore15 = GameStateEvaluator2.evaluatePermanent(attacker, game, false);
                        if (atkScore15 >= HIGH_VALUE_THRESHOLD && possibleBlockers.size() >= 2) {
                            boolean hasTrample15 = attacker.getAbilities().containsKey(TrampleAbility.getInstance().getId());
                            boolean hasIndest15 = attacker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId());
                            boolean hasDT15 = attacker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId());
                            if (!hasTrample15 && !hasIndest15 && !hasDT15) {
                                final int expThreshold15 = (int) (atkScore15 * 0.40);
                                List<Permanent> expBlockers15 = possibleBlockers.stream()
                                        .filter(b -> GameStateEvaluator2.evaluatePermanent(b, game, false) < expThreshold15)
                                        .sorted((a, b) -> Integer.compare(b.getPower().getValue(), a.getPower().getValue()))
                                        .collect(Collectors.toList());
                                int cumPow15 = 0;
                                int expCount15 = 0;
                                for (Permanent blk : expBlockers15) {
                                    cumPow15 += blk.getPower().getValue();
                                    expCount15++;
                                    if (cumPow15 >= attacker.getToughness().getValue()) {
                                        break;
                                    }
                                }
                                if (expCount15 >= 2 && cumPow15 >= attacker.getToughness().getValue()) {
                                    safeToAttack = false;
                                    aiLog(game, "[HOLD vs " + defender.getName() + "] " + attacker.getName() + " is too valuable to risk (" + atkScore15 + " pts) — " + expCount15 + " cheap blockers could pile up and kill it.");
                                }
                            }
                        }
                    }

                    } // end !game.isSimulation() — Sprints 7, 9, 12, 15

                    // add attacker to the next list of all attackers that can safely attack
                    if (safeToAttack) {
                        attackersToCheck.add(attacker);
                    }
                }

                // ── Sprint 17 — Coordinated risk/reward review ─────────────────────────
                // The per-attacker filters above (Sprints 7/9/15/16) evaluated each creature
                // in isolation. This second pass looks at the SWARM holistically vs this
                // defender: does the package deliver enough value to justify the exposure?
                // Operates on attackers slated for THIS defender (not yet declared via alpha-strike).
                if (!game.isSimulation()) {
                    List<Permanent> tentative = attackersToCheck.stream()
                            .filter(p -> !p.isAttacking())
                            .collect(Collectors.toList());

                    if (!tentative.isEmpty()) {
                        // Alpha-strike exemption: if the swing is decisive (kills defender),
                        // never second-guess. The lethal kill outvalues any next-turn risk.
                        List<Permanent> blockersNow17 = defender.getAvailableBlockers(game);
                        boolean isDecisive = !CombatUtil.canKillOpponent(game, tentative, blockersNow17, defender).isEmpty();

                        if (!isDecisive) {
                            Set<UUID> tentativeIds = tentative.stream().map(Permanent::getId).collect(Collectors.toSet());
                            int myLife = game.getPlayer(playerId).getLife();
                            int incoming = incomingDamageNextRotation(game, tentativeIds);

                            // (1) Survival rule — drop lowest-value attackers until AI survives next rotation.
                            // Damage-to-AI is non-negotiable: even a relevant trigger doesn't justify dying.
                            while (incoming >= myLife && !tentative.isEmpty()) {
                                Permanent worst = tentative.stream()
                                        .min(Comparator.comparingInt(p -> GameStateEvaluator2.evaluatePermanent(p, game, false)))
                                        .orElse(null);
                                if (worst == null) break;
                                tentative.remove(worst);
                                tentativeIds.remove(worst.getId());
                                attackersToCheck.remove(worst);
                                aiLog(game, "[HOLD-LETHAL vs " + defender.getName() + "] " + worst.getName() + " stays back — attacking left AI in lethal range (incoming "
                                        + incoming + " vs " + myLife + " HP).");
                                incoming = incomingDamageNextRotation(game, tentativeIds);
                            }

                            // (2) Value rule — drop attackers whose defensive contribution outweighs
                            // what they deliver on offense. Iterates until no more drops happen.
                            // Scale: incoming damage × 150 matches face-damage weight in swarmAttackValue.
                            int delivered = swarmAttackValue(game, defenderId, tentative);
                            boolean changed = true;
                            while (changed) {
                                changed = false;
                                for (Permanent atk : new ArrayList<>(tentative)) {
                                    // Bypass: relevant attack triggers always justify attacking (item v of philosophy).
                                    if (attackTriggerValue(atk, game) >= TRIGGER_RELEVANT_MIN) continue;

                                    Set<UUID> withoutThis = new HashSet<>(tentativeIds);
                                    withoutThis.remove(atk.getId());
                                    List<Permanent> swarmWithout = tentative.stream()
                                            .filter(p -> !p.getId().equals(atk.getId()))
                                            .collect(Collectors.toList());

                                    int incomingWithout = incomingDamageNextRotation(game, withoutThis);
                                    int deliveredWithout = swarmAttackValue(game, defenderId, swarmWithout);

                                    int damagePrevented = incoming - incomingWithout; // raw damage points saved by holding back
                                    int riskDeltaScaled = damagePrevented * 150;      // scale to swarmAttackValue units
                                    int returnDelta = delivered - deliveredWithout;   // value lost by holding back

                                    if (riskDeltaScaled >= returnDelta) {
                                        tentative.remove(atk);
                                        tentativeIds.remove(atk.getId());
                                        attackersToCheck.remove(atk);
                                        incoming = incomingWithout;
                                        delivered = deliveredWithout;
                                        changed = true;
                                        aiLog(game, "[HOLD-VALUE vs " + defender.getName() + "] " + atk.getName() + " stays back — defense saves "
                                                + damagePrevented + " dmg (" + riskDeltaScaled + " pts) > attack adds "
                                                + returnDelta + " pts.");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                // ── end Sprint 17 ───────────────────────────────────────────────────────

                // find possible target for attack (priority: planeswalker -> battle -> player)
                int totalPowerOfAttackers = 0;
                int usedPowerOfAttackers = 0;
                for (Permanent attacker : attackersToCheck) {
                    totalPowerOfAttackers += attacker.getPower().getValue();
                }

                // TRY ATTACK PLANESWALKER + BATTLE
                List<Permanent> possiblePermanentDefenders = new ArrayList<>();
                // planeswalker first priority
                game.getBattlefield().getActivePermanents(StaticFilters.FILTER_PERMANENT_PLANESWALKER, activePlayerId, game)
                        .stream()
                        .filter(p -> p.canBeAttacked(null, defenderId, game))
                        .forEach(possiblePermanentDefenders::add);
                // battle second priority
                game.getBattlefield().getActivePermanents(StaticFilters.FILTER_PERMANENT_BATTLE, activePlayerId, game)
                        .stream()
                        .filter(p -> p.canBeAttacked(null, defenderId, game))
                        .forEach(possiblePermanentDefenders::add);

                for (Permanent permanentDefender : possiblePermanentDefenders) {
                    if (usedPowerOfAttackers >= totalPowerOfAttackers) {
                        break;
                    }
                    int currentCounters;
                    if (permanentDefender.isPlaneswalker(game)) {
                        currentCounters = permanentDefender.getCounters(game).getCount(CounterType.LOYALTY);
                    } else if (permanentDefender.isBattle(game)) {
                        currentCounters = permanentDefender.getCounters(game).getCount(CounterType.DEFENSE);
                    } else {
                        // impossible error (SBA must remove all planeswalkers/battles with 0 counters before declare attackers)
                        throw new IllegalStateException("AI: can't find counters for defending permanent " + permanentDefender.getName(), new Throwable());
                    }

                    // attack anyway (for kill or damage)
                    // TODO: add attackers optimization here (1 powerfull + min number of additional permanents,
                    //  current code uses random/etb order)
                    for (Permanent attackingPermanent : attackersToCheck) {
                        if (attackingPermanent.isAttacking()) {
                            // already used for another target
                            continue;
                        }
                        attackingPlayer.declareAttacker(attackingPermanent.getId(), permanentDefender.getId(), game, true);
                        currentCounters -= attackingPermanent.getPower().getValue();
                        usedPowerOfAttackers += attackingPermanent.getPower().getValue();
                        if (currentCounters <= 0) {
                            break;
                        }
                    }
                }

                // TRY ATTACK PLAYER
                // any remaining attackers go for the player
                for (Permanent attackingPermanent : attackersToCheck) {
                    if (attackingPermanent.isAttacking()) {
                        continue;
                    }
                    attackingPlayer.declareAttacker(attackingPermanent.getId(), defenderId, game, true);
                }
            }
        }
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        logger.debug("selectAttackers");
        declareAttackers(game, playerId);
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        logger.debug("selectBlockers");
        declareBlockers(game, playerId);
    }

    /**
     * Copies game and replaces all players in copy with simulated players
     *
     * @param game
     * @return a new game object with simulated players
     */
    protected Game createSimulation(Game game) {
        Game sim = game.createSimulationForAI();
        for (Player oldPlayer : sim.getState().getPlayers().values()) {
            // replace original player by simulated player and find result (execute/resolve current action)
            Player origPlayer = game.getState().getPlayers().get(oldPlayer.getId()).copy();
            SimulatedPlayer2 simPlayer = new SimulatedPlayer2(oldPlayer, oldPlayer.getId().equals(playerId));
            simPlayer.restore(origPlayer);
            sim.getState().getPlayers().put(oldPlayer.getId(), simPlayer);
        }
        return sim;
    }

    private boolean checkForRepeatedAction(Game sim, SimulationNode2 node, Ability action, UUID playerId) {
        // pass or casting two times a spell multiple times on hand is ok
        if (action instanceof PassAbility || action instanceof SpellAbility || action.isManaAbility()) {
            return false;
        }
        int newVal = GameStateEvaluator2.evaluate(playerId, sim).getTotalScore();
        SimulationNode2 test = node.getParent();
        while (test != null) {
            if (test.getPlayerId().equals(playerId)) {
                if (test.getAbilities() != null && test.getAbilities().size() == 1) {
                    if (action.toString().equals(test.getAbilities().get(0).toString())) {
                        if (test.getParent() != null) {
                            Game prevGame = node.getGame();
                            if (prevGame != null) {
                                int oldVal = GameStateEvaluator2.evaluate(playerId, prevGame).getTotalScore();
                                if (oldVal >= newVal) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            test = test.getParent();
        }
        return false;
    }

    @Override
    public void cleanUpOnMatchEnd() {
        root = null;
        super.cleanUpOnMatchEnd();
    }

}
