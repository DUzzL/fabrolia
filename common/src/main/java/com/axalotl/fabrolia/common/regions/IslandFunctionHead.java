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
import net.minecraft.commands.execution.EntryAction;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tick datapack roots executed per ownership partition instead of on Main
 * under a global quiesce (docs/ASYNC-ISLANDS.md §12, "Datapacks on island heads").
 *
 * <p>Main publishes a snapshot of ARMED roots each tick: the partitioned
 * {@code #minecraft:tick} roots (after {@code #load} ran) while the tick rate
 * runs normally, and the partitioned 1t self-rearming loops whose TimerQueue
 * callback fired. Every island unit runs the armed roots once per island tick
 * at its function head, for the entities whose {@link FunctionPassMark} it
 * claims; Main runs them for the global remainder at vanilla's function point.
 * Lines whose body is not owner-local escalate on match: the first match of a
 * head hands the rest of that head (with the matches already evaluated) to
 * fenced Main in ONE routed body, still restricted to the head's members.
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

    /** One root line to run in a head, in vanilla order. */
    record Step(FunctionPlan.RootPlan root, FunctionPlan.LinePlan line) {}

    private static volatile Armed ARMED = new Armed(FunctionPlan.EMPTY, List.of(), List.of());

    /** Currently armed loops (heads read this snapshot). */
    static List<FunctionPlan.RootPlan> armedLoops() {
        return ARMED.loops();
    }

    /** Main-confined: loops fired by the TimerQueue tick in progress. */
    private static final ArrayList<FunctionPlan.RootPlan> FIRED = new ArrayList<>(4);
    private static FunctionPlan firedPlan;
    /** Main-confined: server tick whose TimerQueue tick completed. */
    private static long timerTickDone = Long.MIN_VALUE;

    /** Island head pass ids are positive; Main uses -(tick + 1). */
    private static final AtomicLong NEXT_ISLAND_PASS = new AtomicLong(1);

    private IslandFunctionHead() {}

    static long mainPassId(MinecraftServer server) {
        return -((long) server.getTickCount()) - 1L;
    }

    // ------------------------------------------------------------------
    // Arming (physical Main)
    // ------------------------------------------------------------------

    /**
     * End of the tick tag: arm the partitioned #tick roots (or nothing). Runs
     * after the tag so a reload's {@code #load} precedes every island's first
     * pass of the new generation.
     */
    public static void publishTickRoots(FunctionPlan plan, boolean arm) {
        if (!arm) {
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

    /** End of the server TimerQueue tick: exactly the loops that fired are armed. */
    public static void endTimerTick(MinecraftServer server) {
        FunctionPlan plan = FunctionAnalyzer.plan();
        List<FunctionPlan.RootPlan> loops = firedPlan == plan ? List.copyOf(FIRED) : List.of();
        Armed previous = ARMED;
        List<FunctionPlan.RootPlan> tick = previous.plan() == plan ? previous.tickRoots() : List.of();
        ARMED = new Armed(plan, tick, loops);
        FIRED.clear();
        firedPlan = null;
        timerTickDone = server.getTickCount();
    }

    /** Main-confined: the loop a {@code remove} just disarmed (a replace-schedule re-adds it). */
    private static FunctionPlan.RootPlan lastDisarmed;

    /** {@code /schedule clear} (or any Main removal) of an armed loop disarms it now. */
    public static void timerEventRemoved(String id) {
        Armed armed = ARMED;
        lastDisarmed = null;
        if (rearming || armed.loops().isEmpty()) return;
        ArrayList<FunctionPlan.RootPlan> loops = new ArrayList<>(armed.loops());
        for (FunctionPlan.RootPlan r : armed.loops()) {
            if (r.id().toString().equals(id)) lastDisarmed = r;
        }
        if (loops.removeIf(r -> r.id().toString().equals(id))) {
            ARMED = new Armed(armed.plan(), armed.tickRoots(), List.copyOf(loops));
        }
    }

    /**
     * Main {@code schedule} of a loop right after its removal (vanilla
     * {@code schedule … replace} = remove + schedule): the loop stays armed.
     */
    public static void timerEventScheduled(String id) {
        FunctionPlan.RootPlan root = lastDisarmed;
        lastDisarmed = null;
        if (root == null || rearming || !root.id().toString().equals(id)) return;
        Armed armed = ARMED;
        if (armed.plan() != FunctionAnalyzer.plan() || armed.loops().contains(root)) return;
        ArrayList<FunctionPlan.RootPlan> loops = new ArrayList<>(armed.loops());
        loops.add(root);
        ARMED = new Armed(armed.plan(), armed.tickRoots(), List.copyOf(loops));
    }

    /** Entity tick boundary ({@link FunctionPassMark}). */
    public static void entityTicked(FunctionPassMark entity) {
        entity.fabrolia$functionPassTick();
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

    private static List<Step> steps(List<FunctionPlan.RootPlan> roots) {
        ArrayList<Step> out = new ArrayList<>();
        for (FunctionPlan.RootPlan root : roots) {
            for (FunctionPlan.LinePlan line : root.lines()) {
                if (line.kind() == FunctionPlan.LineKind.PARTITION
                        || line.kind() == FunctionPlan.LineKind.ESCALATE) {
                    out.add(new Step(root, line));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Island head (coordinator, under the unit claim)
    // ------------------------------------------------------------------

    static void runIslandHead(RegionizedServer rs, IslandRegistry.PIsland island) {
        Armed armed = ARMED;
        if (armed.isEmpty() || FunctionAnalyzer.plan() != armed.plan()) return;
        ArrayList<FunctionPlan.RootPlan> roots = new ArrayList<>(armed.tickRoots());
        roots.addAll(armed.loops());
        List<Step> steps = steps(roots);
        if (steps.isEmpty()) return;
        MinecraftServer server = rs.level.getServer();
        FunctionPartition partition = FunctionPartition.island(rs, island);
        FunctionPartition.Pass pass = partition.open(NEXT_ISLAND_PASS.getAndIncrement(), false);
        if (pass.isEmpty()) return;
        RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.ISLAND_ROOT_PASSES::increment);
        CommandSourceStack source = server.getFunctions().getGameLoopSender();
        FunctionPlan.RootPlan aborted = null;
        for (int i = 0; i < steps.size(); i++) {
            // The library swap holds the global fence while islands are live:
            // a head never straddles it, but a parked one may observe it.
            if (FunctionAnalyzer.plan() != armed.plan()) return;
            Step step = steps.get(i);
            if (step.root() == aborted) continue;
            List<List<Entity>> matches;
            try {
                matches = partitionStep(step.line(), source, pass, server);
            } catch (RuntimeException failure) {
                functionFailure(step.root(), failure);
                aborted = step.root();
                continue;
            }
            if (matches == null) continue;
            int from = i;
            FunctionPlan.RootPlan abortedNow = aborted;
            String label = step.root().id() + ":" + step.line().sourceLine();
            RegionScheduler.commitSuccessIfHealthy(() -> {
                FunctionFenceDiag.ISLAND_ESCALATIONS.increment();
                FunctionFenceDiag.escalated(label);
            });
            IslandCommands.routeFencedIslandBody(rs, island, "function head from " + label, () -> {
                IslandFunctionScope.runUnscoped(() -> runRemainderFenced(
                        armed.plan(), steps, from, matches, abortedNow, pass, server));
                return true;
            });
            RegionScheduler.throwWorkerFailureIfAny();
            return;
        }
        RegionScheduler.throwWorkerFailureIfAny();
    }

    /**
     * One step of a non-fenced pass: PARTITION lines run in place (owner-local
     * scope); returns the fan-out matches when the line must run fenced on
     * Main instead (an ESCALATE match, or a PARTITION line whose fake-player
     * inputs do not exist yet — vanilla would create them), null otherwise.
     */
    private static List<List<Entity>> partitionStep(FunctionPlan.LinePlan line, CommandSourceStack source,
                                                    FunctionPartition.Pass pass, MinecraftServer server) {
        if (line.kind() == FunctionPlan.LineKind.PARTITION
                && fakeScoresPresent(line, server.getScoreboard())) {
            IslandFunctionScope.run(pass.partition, () -> runLine(line, source, fanOutOf(pass)));
            return null;
        }
        Object[] result = new Object[1];
        IslandFunctionScope.run(pass.partition, () -> result[0] = evaluateFanOut(line, source, pass));
        @SuppressWarnings("unchecked")
        List<List<Entity>> matches = (List<List<Entity>>) result[0];
        for (List<Entity> m : matches) if (!m.isEmpty()) return matches;
        return null;
    }

    /**
     * Fenced Main: the escalated line with the matches its partition already
     * evaluated (one evaluation: random predicates are not re-rolled), then
     * the rest of that head's steps over the same members.
     */
    private static void runRemainderFenced(FunctionPlan plan, List<Step> steps, int from,
                                           List<List<Entity>> firstMatches,
                                           FunctionPlan.RootPlan aborted,
                                           FunctionPartition.Pass pass, MinecraftServer server) {
        if (FunctionAnalyzer.plan() != plan) {
            FunctionFenceDiag.STALE_ESCALATIONS.increment();
            return;
        }
        CommandSourceStack source = server.getFunctions().getGameLoopSender();
        FunctionPlan.RootPlan abortedRoot = aborted;
        for (int j = from; j < steps.size(); j++) {
            Step step = steps.get(j);
            if (step.root() == abortedRoot) continue;
            try {
                if (j == from) {
                    runLine(step.line(), source, fixedFanOut(firstMatches));
                } else {
                    runLine(step.line(), source, fanOutOf(pass));
                }
            } catch (RuntimeException failure) {
                functionFailure(step.root(), failure);
                abortedRoot = step.root();
            }
        }
    }

    // ------------------------------------------------------------------
    // Main remainder partition (physical Main)
    // ------------------------------------------------------------------

    /** Main's share of an island root, at the vanilla function point. */
    public static void runMainPartition(FunctionPlan.RootPlan root, MinecraftServer server) {
        runMainPass(List.of(root), FunctionPartition.main(server), mainPassId(server), true, server);
    }

    /**
     * Async OFF flip of {@code level} (islands quiescent): entities that ticked
     * on retired islands after their last island head owe this tick's pass,
     * which Main skipped for them at the tick tag. Loops are included only when
     * this tick's TimerQueue already ran (otherwise its Main remainder covers
     * the now non-async level).
     */
    public static void catchUpRetiredIslands(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Armed armed = ARMED;
        if (armed.isEmpty() || FunctionAnalyzer.plan() != armed.plan()) return;
        ArrayList<FunctionPlan.RootPlan> roots = new ArrayList<>(armed.tickRoots());
        if (timerTickDone == server.getTickCount()) roots.addAll(armed.loops());
        if (roots.isEmpty()) return;
        runMainPass(roots, FunctionPartition.mainLevel(server, level), mainPassId(server), false, server);
    }

    private static void runMainPass(List<FunctionPlan.RootPlan> roots, FunctionPartition.MainPartition partition,
                                    long passId, boolean acceptSame, MinecraftServer server) {
        // Claim first: an entity claimed here cannot be passed by an island
        // head until its next tick. Members that live in an async level (lazy
        // index moves can put an island-ticked entity into a global region;
        // the OFF catch-up runs while retiring units may still finish) run
        // under the global fence, so no body overlaps an island tick.
        FunctionPartition.Pass pass = partition.open(passId, acceptSame);
        if (pass.isEmpty()) return;
        boolean fence = IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()
                && partition.hasAsyncLevelMember(pass);
        if (!fence) {
            runMainPassMembers(roots, pass, server);
            return;
        }
        if (!IslandCommands.quiesceAllExcept(null)) {
            if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()) {
                IslandCommands.publishAndPropagate(new IllegalStateException(
                        "could not fence the Main function remainder pass"));
                return;
            }
            runMainPassMembers(roots, pass, server);
            return;
        }
        Throwable failure = null;
        try {
            RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.MAIN_REMAINDER_FENCES::increment);
            RegionScheduler.runSuccessBodyIfHealthy(() -> runMainPassMembers(roots, pass, server));
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        IslandCommands.releaseAllExceptAndPropagate(failure);
    }

    private static void runMainPassMembers(List<FunctionPlan.RootPlan> roots,
                                           FunctionPartition.Pass pass, MinecraftServer server) {
        RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.MAIN_ROOT_PASSES::increment);
        CommandSourceStack source = server.getFunctions().getGameLoopSender();
        for (FunctionPlan.RootPlan root : roots) {
            try {
                for (FunctionPlan.LinePlan line : root.lines()) {
                    if (line.kind() != FunctionPlan.LineKind.PARTITION
                            && line.kind() != FunctionPlan.LineKind.ESCALATE) continue;
                    List<List<Entity>> matches = partitionStep(line, source, pass, server);
                    if (matches == null) continue;
                    String label = root.id() + ":" + line.sourceLine();
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        FunctionFenceDiag.MAIN_ESCALATIONS.increment();
                        FunctionFenceDiag.escalated(label);
                    });
                    runFencedOnMain(() -> runLine(line, source, fixedFanOut(matches)));
                }
            } catch (RuntimeException failure) {
                functionFailure(root, failure);
            }
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
    // One line of one pass
    // ------------------------------------------------------------------

    /** The fan-out selection of one line, per prefix source. */
    @FunctionalInterface
    public interface FanOut {
        List<Entity> select(int prefixSource, FunctionPlan.LinePlan line, CommandSourceStack source)
                throws CommandSyntaxException;
    }

    public static FanOut fanOutOf(FunctionPartition.Pass pass) {
        return (index, line, source) -> pass.select(line.fanOut(), source);
    }

    /** Matches already evaluated by the partition, replayed by prefix-source index. */
    public static FanOut fixedFanOut(List<List<Entity>> matches) {
        return (index, line, source) -> {
            if (index >= matches.size()) {
                throw new IllegalStateException("escalated line " + line.text()
                        + " produced more prefix sources than its partition evaluated");
            }
            List<Entity> out = new ArrayList<>(matches.get(index).size());
            for (Entity e : matches.get(index)) if (!e.isRemoved()) out.add(e);
            return out;
        };
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

    /** The line's fan-out over this pass, per prefix source (evaluated once). */
    public static List<List<Entity>> evaluateFanOut(FunctionPlan.LinePlan line, CommandSourceStack root,
                                                    FunctionPartition.Pass pass) {
        ArrayList<List<Entity>> out = new ArrayList<>();
        execute(root, (context, frame) -> {
            List<CommandSourceStack> sources = prefixSources(line, root, context, new ChainModifiers[1]);
            if (sources == null) return;
            for (CommandSourceStack s : sources) {
                try {
                    out.add(pass.select(line.fanOut(), s));
                } catch (CommandSyntaxException e) {
                    // The execution reports it; a failing selector selects nothing.
                    out.add(List.of());
                }
            }
        });
        return out;
    }

    /** Sources after the prefix stages (before the fan-out); null = stop. */
    private static List<CommandSourceStack> prefixSources(FunctionPlan.LinePlan line,
                                                          CommandSourceStack root,
                                                          ExecutionContext<CommandSourceStack> context,
                                                          ChainModifiers[] modifiersOut) {
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
        modifiersOut[0] = modifiers;
        return sources;
    }

    /**
     * Vanilla {@code BuildContexts.execute} for one root line with the fan-out
     * stage evaluated by {@code fanOut}: prefix modifiers with the game-loop
     * source, then {@code execute as} semantics ({@code source.withEntity(e)}
     * per selected entity, forked), then the rest of the exact parsed chain as
     * a vanilla continuation.
     */
    public static void runLine(FunctionPlan.LinePlan line, CommandSourceStack root, FanOut fanOut) {
        execute(root, (context, frame) -> runLineEntered(line, root, fanOut, context, frame));
    }

    private static void runLineEntered(FunctionPlan.LinePlan line, CommandSourceStack root, FanOut fanOut,
                                       ExecutionContext<CommandSourceStack> context, Frame frame) {
        ChainModifiers[] modifiersOut = new ChainModifiers[1];
        List<CommandSourceStack> sources = prefixSources(line, root, context, modifiersOut);
        if (sources == null) return;
        ChainModifiers modifiers = modifiersOut[0];
        ContextChain<CommandSourceStack> stage = line.chain();
        for (int i = 0; i < line.fanOutStage(); i++) stage = stage.nextStage();
        CommandContext<CommandSourceStack> fan = stage.getTopContext();
        if (fan.isForked()) modifiers = modifiers.setForked();
        context.incrementCost();
        int forkLimit = context.forkLimit();
        ArrayList<CommandSourceStack> next = new ArrayList<>();
        boolean forked = modifiers.isForked();
        for (int index = 0; index < sources.size(); index++) {
            CommandSourceStack s = sources.get(index);
            try {
                List<Entity> selected = fanOut.select(index, line, s);
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
            // The planner classifies these GLOBAL; reaching here is a plan bug.
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

    /** A root line runs in a function frame (vanilla CallFunction depth 1). */
    private static void execute(CommandSourceStack root, EntryAction<CommandSourceStack> action) {
        Commands.executeCommandInContext(root, context -> context.queueNext(new CommandQueueEntry<>(
                new Frame(1, CommandResultCallback.EMPTY, context.frameControlForDepth(1)), action)));
    }

    /** The loop's own {@code schedule function <self> 1t} through vanilla. */
    static void runRearm(FunctionPlan.LinePlan line, CommandSourceStack source) {
        rearming = true;
        try {
            execute(source, line.entry().bind(source));
        } finally {
            rearming = false;
        }
    }

    /** Main-confined: the loop's own {@code schedule … replace} removal is not a disarm. */
    private static boolean rearming;

    /**
     * Vanilla {@code ServerFunctionManager.execute} logs and drops a failing
     * function. A guard violation already published the generation's fatal,
     * which wins here.
     */
    private static void functionFailure(FunctionPlan.RootPlan root, RuntimeException failure) {
        RegionScheduler.throwWorkerFailureIfAny();
        LOGGER.warn("Failed to execute function {}", root.id(), failure);
    }
}
