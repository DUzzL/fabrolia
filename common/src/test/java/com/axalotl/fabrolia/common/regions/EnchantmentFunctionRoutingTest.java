package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.HolderSet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentTarget;
import net.minecraft.world.item.enchantment.effects.AllOf;
import net.minecraft.world.item.enchantment.effects.RunFunction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3 (night campaign 2026-11-18): a datapack enchantment's {@code run_function}
 * effect whose body is not owner-local (fake-player score, {@code say},
 * {@code tellraw @a}) fail-stopped the server on the first island tick without a
 * save. It now runs exactly once, at that point of the entity tick, on fenced
 * Main while the island coordinator parks.
 */
class EnchantmentFunctionRoutingTest {

    @Test
    void mainThreadCallKeepsTheVanillaCallForTheFunctionFence() {
        AtomicInteger runs = new AtomicInteger();
        EnchantmentFunctionRouting.execute(null, null, null, null, runs::incrementAndGet);
        assertEquals(1, runs.get(), "off a region worker the vanilla call runs once, here");
        assertNull(IslandCommands.exclusiveCoordinatorIsland(null),
                "a non-worker thread is never an island's exclusive coordinator");
    }

    @Test
    void nonOwnerLocalBodyRoutesThroughTheExclusiveCoordinatorAfterTheProofLease() throws Exception {
        String routing = source("common/regions/EnchantmentFunctionRouting.java");
        String execute = section(routing, "public static void execute(", "public static Map<Identifier, List<String>> functionSites");
        int proof = execute.indexOf("FunctionAnalyzer.withExactOwnerLocalProof(");
        int proofEnd = execute.indexOf("if (ranOwnerLocal) return;");
        int route = execute.indexOf("IslandCommands.routeFencedIslandBody(");
        assertTrue(proof > 0 && proofEnd > proof, "an owner-local body runs inside the proof lease");
        assertTrue(route > proofEnd,
                "the coordinator parks for Main only after the generation read lease is released");
        assertEquals(1, count(execute, "IslandCommands.routeFencedIslandBody("));
        assertTrue(execute.contains("IslandFunctionScope.runUnscoped(execute)"),
                "Main runs the vanilla call outside any owner-local scope");
        assertTrue(execute.contains("exclusiveCoordinatorIsland(level)"));
        int failStop = execute.indexOf("IslandCommands.publishAndPropagate(");
        assertTrue(failStop > proofEnd && failStop < route,
                "a worker that cannot park for Main fail-stops instead of running the body unfenced");

        String coordinator = section(source("common/regions/IslandCommands.java"),
                "static IslandRegistry.PIsland exclusiveCoordinatorIsland", "static boolean routeFencedIslandBody");
        assertTrue(coordinator.contains("worker.islandDispatchInFlight"));
        assertTrue(coordinator.contains("owner.unitClaim.get() && owner.claimHolder == worker"));
        assertTrue(coordinator.contains("worker.currentRegion == null && domain.entityPending.get() == 0"));
        assertTrue(coordinator.contains("IslandEntityPhaseScope.owns(worker.currentRegion)"));
        String raid = source("common/regions/RaidOwnership.java");
        assertTrue(raid.contains("IslandCommands.exclusiveCoordinatorIsland(level)"),
                "raids and enchantments require the same exclusive coordinator");
    }

    @Test
    void runFunctionMixinWrapsTheVanillaExecuteCall() throws Exception {
        String mixin = source("common/mixin/commands/RunFunctionMixin.java");
        assertTrue(mixin.contains("@Mixin(RunFunction.class)"));
        assertTrue(mixin.contains("method = \"apply\""));
        assertTrue(mixin.contains("target = \"Lnet/minecraft/server/ServerFunctionManager;execute("));
        assertTrue(mixin.contains("require = 1") && mixin.contains("allow = 1"));
        assertTrue(mixin.contains("EnchantmentFunctionRouting.execute(source.getLevel(), manager, function, source,"));
        assertTrue(resource("fabrolia.common.mixins.json").contains("\"commands.RunFunctionMixin\""));
    }

    @Test
    void projectileSpawnedEffectsRunAfterTheSharedSpawnMonitorOnRegionWorkers() throws Exception {
        String mixin = source("common/mixin/entity/ProjectileEntityMixin.java");
        String spawn = section(mixin, "private static <T extends Projectile> T spawn(",
                "private static void fabrolia$applyOnSpawnedOutsideMonitor(");
        int monitor = spawn.lastIndexOf("synchronized (FabroliaLocks.tickLockOr(lock))");
        int deferredRun = spawn.indexOf("if (deferred[0] != null) deferred[0].run();");
        assertTrue(monitor > 0 && deferredRun > monitor,
                "the deferred projectile_spawned effects run after the monitor block");
        assertTrue(mixin.contains("target = \"Lnet/minecraft/world/entity/projectile/Projectile;applyOnProjectileSpawned("));
    }

    @Test
    void functionSitesWalkEveryRunFunctionEffect() {
        FunctionPlanFixture.bootstrap();
        Identifier tickFn = Identifier.parse("pack:tick_fn");
        Identifier postFn = Identifier.parse("pack:post_fn");
        Identifier moveFn = Identifier.parse("pack:move_fn");
        Enchantment zap = Enchantment.enchantment(Enchantment.definition(HolderSet.direct(), 1, 1,
                        Enchantment.constantCost(1), Enchantment.constantCost(1), 1, EquipmentSlotGroup.MAINHAND))
                .withEffect(EnchantmentEffectComponents.TICK, new RunFunction(tickFn))
                .withEffect(EnchantmentEffectComponents.POST_ATTACK, EnchantmentTarget.ATTACKER,
                        EnchantmentTarget.VICTIM, new AllOf.EntityEffects(List.of(new RunFunction(postFn),
                                new RunFunction(tickFn))))
                .withEffect(EnchantmentEffectComponents.LOCATION_CHANGED,
                        new AllOf.LocationBasedEffects(List.of(new RunFunction(moveFn))))
                .build(Identifier.parse("pack:zap"));

        Map<Identifier, List<String>> sites = EnchantmentFunctionRouting.functionSites(
                List.of(Map.entry(Identifier.parse("pack:zap"), zap)));

        assertEquals(List.of("pack:zap tick", "pack:zap post_attack"), sites.get(tickFn));
        assertEquals(List.of("pack:zap post_attack"), sites.get(postFn));
        assertEquals(List.of("pack:zap location_changed"), sites.get(moveFn));
        assertEquals(3, sites.size());
    }

    @Test
    void functionPlanListsEnchantmentAndRewardCallbacksWithTheirVenue() {
        FunctionPlan plan = new FunctionPlanFixture()
                .function("pack:self", "scoreboard players add @s hits 1")
                .function("pack:fake", "scoreboard players add #ticks hits 1")
                .function("pack:reward", "tellraw @a \"done\"")
                .enchantmentFunction("pack:self", "pack:zap_self tick")
                .enchantmentFunction("pack:fake", "pack:zap_fake tick")
                .enchantmentFunction("pack:fake", "pack:zap_post post_attack")
                .enchantmentFunction("pack:missing", "pack:zap_missing tick")
                .reward("pack:reward")
                .plan();
        List<String> lines = plan.describe();
        String text = String.join("\n", lines);

        assertTrue(lines.contains("enchantment run_function pack:self (pack:zap_self tick): OWNER-LOCAL,"
                + " on the entity's owner"), text);
        String fake = lines.stream().filter(l -> l.startsWith("enchantment run_function pack:fake")).findFirst()
                .orElseThrow(() -> new AssertionError(text));
        assertTrue(fake.startsWith("enchantment run_function pack:fake (pack:zap_fake tick, pack:zap_post post_attack):"
                + " NOT OWNER-LOCAL, routed"), fake);
        assertTrue(fake.contains("pack:fake:1"), "names the blocking line: " + fake);
        assertTrue(lines.contains("enchantment run_function pack:missing (pack:zap_missing tick):"
                + " not analyzed (unknown or macro function)"), text);
        assertTrue(text.contains("advancement reward pack:reward: NOT OWNER-LOCAL, routed to fenced Main"), text);
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                () -> "source section not found: " + startNeedle + " .. " + endNeedle);
        return text.substring(start, end);
    }

    private static int count(String text, String needle) {
        int matches = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            matches++;
        }
        return matches;
    }

    private static String resource(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/resources").resolve(name);
            Path common = root.resolve("common/src/main/resources").resolve(name);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate resource: " + name);
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path common = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }
}
