package com.axalotl.fabrolia.common.regions;

import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable execution plan of one activated function generation.
 *
 * <p>Per-tick roots ({@code #minecraft:tick} members and unconditional 1t
 * self-rearming loops) are split per root line:
 * <ul>
 *   <li>{@link LineKind#PARTITION}: {@code execute ... as <@a|@e[...]> ...} with
 *       an owner-local body — each partition (island head / Main remainder)
 *       runs it for its own entities;</li>
 *   <li>{@link LineKind#ESCALATE}: same fan-out, global body — the partition
 *       evaluates the fan-out and, only on a match, runs the line on fenced
 *       Main restricted to its own entities (product decision Q1);</li>
 *   <li>{@link LineKind#REARM}: the loop's own {@code schedule function <self> 1t}
 *       re-arm, executed on Main by the TimerQueue dispatch;</li>
 *   <li>{@link LineKind#GLOBAL}: anything else — the whole root stays a
 *       Main-resident fenced function.</li>
 * </ul>
 */
public final class FunctionPlan {

    public enum LineKind { PARTITION, ESCALATE, REARM, GLOBAL }

    public enum Role { TICK, LOOP }

    /** One root line with everything the executor needs. */
    public record LinePlan(int index, int sourceLine, String text, LineKind kind, String reason,
                           int fanOutStage, EntitySelector fanOut,
                           List<FunctionEffects.FakeScore> fakeReads, boolean playerSnapshot,
                           List<String> acceptedReads,
                           ContextChain<CommandSourceStack> chain,
                           UnboundEntryAction<CommandSourceStack> entry) {}

    public record RootPlan(Identifier id, CommandFunction<CommandSourceStack> function, Role role,
                           boolean partitioned, String blocking, List<LinePlan> lines) {
        public boolean hasEscalation() {
            for (LinePlan l : lines) if (l.kind() == LineKind.ESCALATE) return true;
            return false;
        }
    }

    static final FunctionPlan EMPTY = new FunctionPlan(List.of(), Map.of(),
            Collections.newSetFromMap(new IdentityHashMap<>()), Set.of(), List.of());

    /** Every {@code #minecraft:tick} root, in tag order (partitioned or not). */
    public final List<RootPlan> tickRoots;
    /** Self-rearming 1t loops, partitioned or not. */
    public final Map<Identifier, RootPlan> loops;
    private final Set<CommandFunction<?>> ownerLocalFunctions;
    private final Set<Identifier> ownerLocalIds;
    /** Plan diagnostics (Main-resident roots, conflicts) for the plan command. */
    public final List<String> notes;

    FunctionPlan(List<RootPlan> tickRoots, Map<Identifier, RootPlan> loops,
                 Set<CommandFunction<?>> ownerLocalFunctions, Set<Identifier> ownerLocalIds,
                 List<String> notes) {
        this.tickRoots = tickRoots;
        this.loops = loops;
        this.ownerLocalFunctions = ownerLocalFunctions;
        this.ownerLocalIds = ownerLocalIds;
        this.notes = notes;
    }

    /** Exact-object proof: this function body (this generation) is owner-local. */
    public boolean ownerLocal(CommandFunction<?> function) {
        return ownerLocalFunctions.contains(function);
    }

    public boolean ownerLocal(Identifier id) {
        return ownerLocalIds.contains(id);
    }

    /** The partitioned #tick root executing {@code function}, or null. */
    public RootPlan partitionedTickRoot(CommandFunction<?> function) {
        for (RootPlan r : tickRoots) {
            if (r.function() == function) return r.partitioned() ? r : null;
        }
        return null;
    }

    public RootPlan partitionedLoop(Identifier id) {
        RootPlan r = loops.get(id);
        return r != null && r.partitioned() ? r : null;
    }

    public boolean anyMainResidentTickRoot() {
        for (RootPlan r : tickRoots) if (!r.partitioned()) return true;
        return false;
    }

    /** Human-readable plan for {@code /fabrolia functions plan}. */
    public List<String> describe() {
        ArrayList<String> out = new ArrayList<>();
        for (RootPlan r : tickRoots) describeRoot(out, "#minecraft:tick", r);
        for (RootPlan r : loops.values()) describeRoot(out, "1t loop", r);
        if (tickRoots.isEmpty() && loops.isEmpty()) out.add("no per-tick roots");
        out.addAll(notes);
        return out;
    }

    private static void describeRoot(List<String> out, String role, RootPlan r) {
        out.add(role + " " + r.id() + ": "
                + (r.partitioned() ? "PARTITIONED (island heads + Main remainder, no fence)"
                : "MAIN-RESIDENT (fenced): " + r.blocking()));
        for (LinePlan l : r.lines()) {
            StringBuilder sb = new StringBuilder("  L").append(l.sourceLine()).append(' ')
                    .append(l.kind().name().toLowerCase(java.util.Locale.ROOT));
            if (l.reason() != null) sb.append(" [").append(l.reason()).append(']');
            if (!l.acceptedReads().isEmpty()) {
                sb.append(" {≤1-tick reads of Main-written ").append(String.join(", ", l.acceptedReads())).append('}');
            }
            sb.append(": ").append(l.text());
            out.add(sb.toString());
        }
    }
}
