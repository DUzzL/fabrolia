package com.axalotl.fabrolia.common.regions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.TargetedConditionalEffect;
import net.minecraft.world.item.enchantment.effects.AllOf;
import net.minecraft.world.item.enchantment.effects.RunFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vanilla's enchantment {@code run_function} effect ({@code RunFunction.apply};
 * tick, post_attack, location_changed, hit_block and projectile_spawned slots)
 * executes its function synchronously inside the entity tick, exactly once, at
 * that point of the tick. On an island coordinator the function body therefore
 * runs on a region worker:
 * <ul>
 *   <li>a body the active plan proves owner-local runs right here, under its
 *       partition's ownership guards (the C3 path of
 *       {@code ServerFunctionManagerMixin.fabrolia$fenceFunction});</li>
 *   <li>any other body (fake-player scores, {@code say}, {@code tellraw @a},
 *       storage, foreign entities, ...) is handed to fenced Main as one routed
 *       island body: the coordinator parks at this exact point of the entity
 *       tick, Main runs the vanilla call under the all-island fence with this
 *       island's clock bound, and the tick continues with its result applied.
 *       This is the exclusive-coordinator pattern of
 *       {@link RaidOwnership#runFenced}.</li>
 * </ul>
 * Without that route the worker branch of the function fence fail-stopped on the
 * first island tick (no save). A worker that is not its island's exclusive
 * coordinator (an {@code islandEntityParallel} helper, a global-lane region) cannot
 * park for Main and keeps the fail-stop, with its venue named.
 */
public final class EnchantmentFunctionRouting {

    private EnchantmentFunctionRouting() {}

    /**
     * @param execute vanilla's {@code ServerFunctionManager.execute(function, source)}
     */
    public static void execute(ServerLevel level, ServerFunctionManager manager,
                               CommandFunction<CommandSourceStack> function,
                               CommandSourceStack source, Runnable execute) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread)
                || IslandFunctionScope.current() != null) {
            // Physical Main: the function fence quiesces (or reuses a held
            // window). Inside an owner-local pass vanilla queues the call into
            // the pass's ExecutionContext; the fence keeps its proof check.
            execute.run();
            return;
        }
        // The proof and an owner-local body share one read lease: a reload
        // cannot replace the function between them. Never park under it.
        boolean ranOwnerLocal = FunctionAnalyzer.withExactOwnerLocalProof(manager, function, proven -> {
            if (proven) execute.run();
            return proven;
        });
        if (ranOwnerLocal) return;
        RegionizedServer runtime = RegionScheduler.forLevel(level);
        if (runtime == null || !runtime.asyncActive) {
            // No autonomous islands on this level: unchanged vanilla call (the
            // fence's worker branch decides).
            execute.run();
            return;
        }
        IslandRegistry.PIsland owner = IslandCommands.exclusiveCoordinatorIsland(level);
        if (owner == null) {
            IslandCommands.publishAndPropagate(new IllegalStateException(
                    "enchantment function " + function.id() + " is not proven owner-local and "
                            + Thread.currentThread().getName()
                            + " is not its island's exclusive coordinator (parallel entity helper"
                            + " or global lane), so it cannot run on fenced Main at this point"));
            return;
        }
        RegionScheduler.commitSuccessIfHealthy(FunctionFenceDiag.ENCHANTMENT_FUNCTIONS_ROUTED::increment);
        IslandCommands.routeFencedIslandBody(runtime, owner, "enchantment function " + function.id(), () -> {
            IslandFunctionScope.runUnscoped(execute);
            return true;
        });
    }

    /**
     * Every {@code run_function} effect of {@code enchantments} (registry order):
     * function id to its "enchantment slot" sites, for {@code /fabrolia functions plan}.
     * Walks the effect lists, their conditional wrappers and {@code all_of} groups.
     */
    public static Map<Identifier, List<String>> functionSites(
            Iterable<? extends Map.Entry<Identifier, Enchantment>> enchantments) {
        LinkedHashMap<Identifier, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Enchantment> e : enchantments) {
            for (TypedDataComponent<?> component : e.getValue().effects()) {
                Identifier slot = BuiltInRegistries.ENCHANTMENT_EFFECT_COMPONENT_TYPE.getKey(component.type());
                String site = e.getKey() + " " + (slot == null ? "?" : slot.getPath());
                collectFunctions(component.value(), site, out);
            }
        }
        return out;
    }

    private static void collectFunctions(Object effect, String site, Map<Identifier, List<String>> out) {
        switch (effect) {
            case RunFunction run -> {
                List<String> sites = out.computeIfAbsent(run.function(), k -> new ArrayList<>());
                if (!sites.contains(site)) sites.add(site);
            }
            case List<?> list -> {
                for (Object element : list) collectFunctions(element, site, out);
            }
            case ConditionalEffect<?> conditional -> collectFunctions(conditional.effect(), site, out);
            case TargetedConditionalEffect<?> targeted -> collectFunctions(targeted.effect(), site, out);
            case AllOf.EntityEffects all -> collectFunctions(all.effects(), site, out);
            case AllOf.LocationBasedEffects all -> collectFunctions(all.effects(), site, out);
            case null, default -> { }
        }
    }
}
