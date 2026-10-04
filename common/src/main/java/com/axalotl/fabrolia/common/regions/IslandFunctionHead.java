package com.axalotl.fabrolia.common.regions;

import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.CommandQueueEntry;
import net.minecraft.commands.execution.CustomModifierExecutor;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick datapack roots executed per ownership partition instead of on Main
 * under a global quiesce (docs/ASYNC-ISLANDS.md §12, "Datapacks").
 *
 * <p>Main publishes a snapshot of ARMED roots each tick: the partitioned
 * {@code #minecraft:tick} roots while the tick rate runs normally, and the
 * partitioned 1t self-rearming loops whose TimerQueue callback fired this
 * tick. Every island unit runs the armed roots once per island tick at its
 * function head (after cross-dimension arrivals, before scheduled ticks), for
 * its own entities; Main runs them for the global remainder at vanilla's
 * function point. Lines whose body is not owner-local escalate on match: the
 * partition evaluates the fan-out and only a non-empty match runs the line on
 * fenced Main, still restricted to that partition's entities.
 */
public final class IslandFunctionHead {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-Functions");

    /** Armed roots of exactly one plan generation. */
    record Armed(FunctionPlan plan, List<FunctionPlan.RootPlan> tickRoots,
                 List<FunctionPlan.RootPlan> loops) {
        boolean isEmpty() {
            return tickRoots.isEmpty() && loops.isEmpty();
        }
    }

    private static volatile Armed ARMED = new Armed(FunctionPlan.EMPTY, List.of(), List.of());

    /** Main-confined: loops fired by the TimerQueue tick in progress. */
    private static final ArrayList<FunctionPlan.RootPlan> FIRED = new ArrayList<>(4);
    private static FunctionPlan firedPlan;

    private IslandFunctionHead() {}

    // ------------------------------------------------------------------
    // Arming (physical Main)
    // ------------------------------------------------------------------

    /** Tick tag point: arm the partitioned #tick roots (or nothing). */
    public static void publishTickRoots(FunctionPlan plan, boolean runsNormally) {
        if (!runsNormally) {
            ARMED = new Armed(plan, List.of(), List.of());
            return;
        }
        ArrayList<FunctionPlan.RootPlan> roots = new ArrayList<>(plan.tickRoots.size());
        for (FunctionPlan.RootPlan r : plan.tickRoots) if (r.partitioned()) roots.add(r);
        Armed previous = ARMED;
        List<FunctionPlan.RootPlan> loops = previous.plan() == plan ? previous.loops() : List.of();
        ARMED = new Armed(plan, List.copyOf(roots), loops);
    }

    /** Start of the server TimerQueue tick. */
    public static void beginTimerTick() {
        FIRED.clear();
        firedPlan = null;
    }

    /** End of the server TimerQueue tick: the loops that fired are armed. */
    public static void endTimerTick() {
        FunctionPlan plan = FunctionAnalyzer.plan();
        List<FunctionPlan.RootPlan> loops =
                firedPlan == plan ? List.copyOf(FIRED) : List.of();
        Armed previous = ARMED;
        List<FunctionPlan.RootPlan> tick = previous.plan() == plan ? previous.tickRoots() : List.of();
        ARMED = new Armed(plan, tick, loops);
        FIRED.clear();
        firedPlan = null;
    }

    /**
     * A due {@code FunctionCallback} of a partitioned loop (physical Main,
     * inside TimerQueue.tick). Instead of executing the function under a
     * fence: the exact re-arm line runs through vanilla (TimerQueue is Main
     * state; {@code schedule … replace} semantics), the loop is armed for the
     * island heads, and Main runs its remainder partition.
     *
     * @return false when the callback must take vanilla's path
     */
    public static boolean dispatchLoopCallback(MinecraftServer server, Identifier id) {
        if (!IslandCommands.requiresMainFence()) return false;
        FunctionPlan plan = FunctionAnalyzer.plan();
        FunctionPlan.RootPlan root = plan.partitionedLoop(id);
        if (root == null) return false;
        CommandFunction<CommandSourceStack> current = server.getFunctions().get(id).orElse(null);
        if (current != root.function()) return false;
        CommandSourceStack source = server.getFunctions().getGameLoopSender();
        for (FunctionPlan.LinePlan line : root.lines()) {
            if (line.kind() == FunctionPlan.LineKind.REARM) runRearm(line, source);
        }
        RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.LOOP_REARMS::increment);
        if (firedPlan != plan) {
            FIRED.clear();
            firedPlan = plan;
        }
        if (!FIRED.contains(root)) FIRED.add(root);
        runMainPartition(root, server);
        return true;
    }

    /** True when {@code id} is a loop the TimerQueue dispatch re-arms without a fence. */
    public static boolean islandLoop(MinecraftServer server, Identifier id) {
        if (!IslandCommands.requiresMainFence()) return false;
        FunctionPlan.RootPlan root = FunctionAnalyzer.plan().partitionedLoop(id);
        return root != null && server.getFunctions().get(id).orElse(null) == root.function();
    }

    // ------------------------------------------------------------------
    // Island head (coordinator, under the unit claim)
    // ------------------------------------------------------------------

    static void runIslandHead(RegionizedServer rs, IslandRegistry.PIsland island) {
        Armed armed = ARMED;
        if (armed.isEmpty() || FunctionAnalyzer.plan() != armed.plan()) return;
        MinecraftServer server = rs.level.getServer();
        FunctionPartition partition = FunctionPartition.island(rs, island);
        for (FunctionPlan.RootPlan root : armed.tickRoots()) {
            runIslandRoot(rs, island, armed.plan(), root, server, partition);
        }
        for (FunctionPlan.RootPlan root : armed.loops()) {
            runIslandRoot(rs, island, armed.plan(), root, server, partition);
        }
    }

    private static void runIslandRoot(RegionizedServer rs, IslandRegistry.PIsland island,
                                      FunctionPlan plan, FunctionPlan.RootPlan root,
                                      MinecraftServer server, FunctionPartition partition) {
        RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.ISLAND_ROOT_PASSES::increment);
        CommandSourceStack source = server.getFunctions().getGameLoopSender();
        try {
            for (FunctionPlan.LinePlan line : root.lines()) {
                if (!escalates(line, source, partition, server)) continue;
                String label = root.id() + ":" + line.sourceLine();
                RegionScheduler.commitSuccessIfHealthy(() -> {
                    FunctionFenceDiag.ISLAND_ESCALATIONS.increment();
                    FunctionFenceDiag.escalated(label);
                });
                IslandCommands.routeFencedIslandBody(rs, island, "function " + label, () -> {
                    if (FunctionAnalyzer.plan() != plan) {
                        FunctionFenceDiag.STALE_ESCALATIONS.increment();
                        return true;
                    }
                    runLine(line, server.getFunctions().getGameLoopSender(),
                            FunctionPartition.island(rs, island));
                    return true;
                });
            }
        } catch (RuntimeException failure) {
            functionFailure(root, failure);
        }
        RegionScheduler.throwWorkerFailureIfAny();
    }

    // ------------------------------------------------------------------
    // Main remainder partition (physical Main)
    // ------------------------------------------------------------------

    /** Main's share of an island root, at the vanilla function point. */
    public static void runMainPartition(FunctionPlan.RootPlan root, MinecraftServer server) {
        RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.MAIN_ROOT_PASSES::increment);
        CommandSourceStack source = server.getFunctions().getGameLoopSender();
        FunctionPartition partition = FunctionPartition.main(server);
        try {
            for (FunctionPlan.LinePlan line : root.lines()) {
                if (!escalates(line, source, partition, server)) continue;
                String label = root.id() + ":" + line.sourceLine();
                RegionScheduler.commitSuccessIfHealthy(() -> {
                    FunctionFenceDiag.MAIN_ESCALATIONS.increment();
                    FunctionFenceDiag.escalated(label);
                });
                runFencedOnMain(() -> runLine(line, source, partition));
            }
        } catch (RuntimeException failure) {
            functionFailure(root, failure);
        }
    }

    private static void runFencedOnMain(Runnable body) {
        if (!IslandCommands.requiresMainFence() || IslandCommands.fenceWindowHeld()) {
            IslandFunctionScope.runUnscoped(body);
            return;
        }
        if (!IslandCommands.quiesceAllExcept(null)) {
            if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()) {
                IslandCommands.publishAndPropagate(new IllegalStateException(
                        "could not fence an escalated function line"));
            }
            IslandFunctionScope.runUnscoped(body);
            return;
        }
        Throwable failure = null;
        try {
            RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.MAIN_ESCALATION_FENCES::increment);
            RegionScheduler.runSuccessBodyIfHealthy(() -> IslandFunctionScope.runUnscoped(body));
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        IslandCommands.releaseAllExceptAndPropagate(failure);
    }

    // ------------------------------------------------------------------
    // One line of one partition
    // ------------------------------------------------------------------

    /**
     * Runs PARTITION lines in place (owner-local scope) and reports whether the
     * line must instead run fenced on Main for this partition: an ESCALATE line
     * whose fan-out matches here, or a PARTITION line whose fake-player score
     * inputs do not exist yet (vanilla would create them).
     */
    private static boolean escalates(FunctionPlan.LinePlan line, CommandSourceStack source,
                                     FunctionPartition partition, MinecraftServer server) {
        switch (line.kind()) {
            case PARTITION -> {
                if (!fakeScoresPresent(line, server.getScoreboard())) {
                    return fanOutMatchesScoped(line, source, partition);
                }
                IslandFunctionScope.run(partition, () -> runLine(line, source, partition));
                return false;
            }
            case ESCALATE -> {
                return fanOutMatchesScoped(line, source, partition);
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean fanOutMatchesScoped(FunctionPlan.LinePlan line, CommandSourceStack source,
                                               FunctionPartition partition) {
        boolean[] match = new boolean[1];
        IslandFunctionScope.run(partition, () -> match[0] = fanOutMatches(line, source, partition));
        return match[0];
    }

    /** Fake-player scores the line reads already exist and name no online player. */
    static boolean fakeScoresPresent(FunctionPlan.LinePlan line, Scoreboard scoreboard) {
        for (FunctionEffects.FakeScore fake : line.fakeReads()) {
            Objective objective = scoreboard.getObjective(fake.objective());
            if (objective == null) return false;
            if (FunctionPlayerSnapshot.isOnlinePlayerName(fake.holder())) return false;
            if (scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly(fake.holder()), objective) == null) {
                return false;
            }
        }
        return true;
    }

    /** Whether the line's fan-out selects any entity of this partition. */
    public static boolean fanOutMatches(FunctionPlan.LinePlan line, CommandSourceStack root,
                                        FunctionPartition partition) {
        boolean[] match = new boolean[1];
        execute(root, (context, frame) -> {
            List<CommandSourceStack> sources = prefixSources(line, root, context);
            if (sources == null) return;
            for (CommandSourceStack s : sources) {
                try {
                    if (!partition.select(line.fanOut(), s).isEmpty()) {
                        match[0] = true;
                        return;
                    }
                } catch (CommandSyntaxException e) {
                    // The real execution reports it; a failing selector selects nothing.
                }
            }
        });
        return match[0];
    }

    /** Sources after the prefix stages (before the fan-out); null = stop. */
    private static List<CommandSourceStack> prefixSources(FunctionPlan.LinePlan line,
                                                          CommandSourceStack root,
                                                          ExecutionContext<CommandSourceStack> context) {
        ChainModifiers modifiers = ChainModifiers.DEFAULT;
        ContextChain<CommandSourceStack> stage = line.chain();
        List<CommandSourceStack> sources = List.of(root);
        for (int i = 0; i < line.fanOutStage(); i++) {
            CommandContext<CommandSourceStack> c = stage.getTopContext();
            if (c.isForked()) modifiers = modifiers.setForked();
            sources = runModifier(c, sources, modifiers, root, context, context.forkLimit());
            if (sources == null) return null;
            stage = stage.nextStage();
        }
        return sources;
    }

    /**
     * Vanilla {@code BuildContexts.execute} for one root line with the fan-out
     * stage evaluated over {@code partition}: prefix modifiers with the
     * game-loop source, then {@code execute as} semantics
     * ({@code source.withEntity(e)} per selected entity, forked), then the rest
     * of the exact parsed chain as a vanilla continuation.
     */
    public static void runLine(FunctionPlan.LinePlan line, CommandSourceStack root,
                               FunctionPartition partition) {
        execute(root, (context, frame) -> runLineEntered(line, root, partition, context, frame));
    }

    private static void runLineEntered(FunctionPlan.LinePlan line, CommandSourceStack root,
                                       FunctionPartition partition,
                                       ExecutionContext<CommandSourceStack> context, Frame frame) {
        ChainModifiers modifiers = ChainModifiers.DEFAULT;
        ContextChain<CommandSourceStack> stage = line.chain();
        List<CommandSourceStack> sources = List.of(root);
        int forkLimit = context.forkLimit();
        for (int i = 0; i < line.fanOutStage(); i++) {
            CommandContext<CommandSourceStack> c = stage.getTopContext();
            if (c.isForked()) modifiers = modifiers.setForked();
            sources = runModifier(c, sources, modifiers, root, context, forkLimit);
            if (sources == null) return;
            stage = stage.nextStage();
        }
        CommandContext<CommandSourceStack> fan = stage.getTopContext();
        if (fan.isForked()) modifiers = modifiers.setForked();
        context.incrementCost();
        ArrayList<CommandSourceStack> next = new ArrayList<>();
        boolean forked = modifiers.isForked();
        for (CommandSourceStack s : sources) {
            try {
                List<Entity> selected = partition.select(line.fanOut(), s);
                if (next.size() + selected.size() >= forkLimit) {
                    root.handleError(BuildContexts.ERROR_FORK_LIMIT_REACHED.create(forkLimit),
                            forked, context.tracer());
                    return;
                }
                for (Entity e : selected) next.add(s.withEntity(e));
            } catch (CommandSyntaxException e) {
                s.handleError(e, forked, context.tracer());
                if (!forked) return;
            }
        }
        if (next.isEmpty()) return;
        new BuildContexts.Continuation<>(line.text(), stage.nextStage(), modifiers, root, next)
                .execute(context, frame);
    }

    /** {@code BuildContexts.execute}'s per-stage modifier step; null = stop. */
    private static List<CommandSourceStack> runModifier(
            CommandContext<CommandSourceStack> c, List<CommandSourceStack> sources,
            ChainModifiers modifiers, CommandSourceStack root,
            ExecutionContext<CommandSourceStack> context, int forkLimit) {
        RedirectModifier<CommandSourceStack> modifier = c.getRedirectModifier();
        if (modifier instanceof CustomModifierExecutor<?>) {
            throw new IllegalStateException("custom modifier before a function fan-out: " + c.getInput());
        }
        if (modifier == null) return sources;
        context.incrementCost();
        boolean forked = modifiers.isForked();
        ArrayList<CommandSourceStack> next = new ArrayList<>();
        for (CommandSourceStack s : sources) {
            try {
                var produced = ContextChain.runModifier(c, s, (ctx, ok, r) -> { }, forked);
                if (next.size() + produced.size() >= forkLimit) {
                    root.handleError(BuildContexts.ERROR_FORK_LIMIT_REACHED.create(forkLimit),
                            forked, context.tracer());
                    return null;
                }
                next.addAll(produced);
            } catch (CommandSyntaxException e) {
                s.handleError(e, forked, context.tracer());
                if (!forked) return null;
            }
        }
        return next.isEmpty() ? null : next;
    }

    private static void execute(CommandSourceStack root,
                                net.minecraft.commands.execution.EntryAction<CommandSourceStack> action) {
        Commands.executeCommandInContext(root, context -> context.queueNext(new CommandQueueEntry<>(
                new Frame(0, CommandResultCallback.EMPTY, context.frameControlForDepth(0)), action)));
    }

    /** The loop's own {@code schedule function <self> 1t} through vanilla. */
    private static void runRearm(FunctionPlan.LinePlan line, CommandSourceStack source) {
        execute(source, line.entry().bind(source));
    }

    /** Vanilla {@code ServerFunctionManager.execute} logs and drops a failing function. */
    private static void functionFailure(FunctionPlan.RootPlan root, RuntimeException failure) {
        RegionScheduler.throwWorkerFailureIfAny();
        LOGGER.warn("Failed to execute function {}", root.id(), failure);
    }
}
