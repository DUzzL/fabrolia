package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-18 production crash: a minecart picked up a horse with its rider during
 * its own island tick; vanilla's passenger loop then ticked the Main-owned rider on
 * the region worker and {@code PlayerAdvancements.flushDirty} failed loud. The
 * subtree's tick is handed to MAIN_SYNC instead.
 */
final class ForeignPlayerPassengerDeferralTest {

    @Test
    void passengerGuardHandsOverBeforeTakingTheTickLease() throws Exception {
        String level = source("common/mixin/world/ServerLevelMixin.java");
        String passenger = section(level, "private void fabrolia$guardTickPassenger",
                "tickPassenger has removal/vehicle gates");
        int arrival = passenger.indexOf("isArrivalPending()) return;");
        int portal = passenger.indexOf("isSourceMutationBlocked(entity))) return;", arrival);
        int defer = passenger.indexOf(
                "if (RegionScheduler.deferForeignPlayerPassenger(vehicle, entity)) return;", portal);
        int lease = passenger.indexOf("guard.fabrolia$tryBeginTick()", defer);
        int body = passenger.indexOf("original.call(vehicle, entity)", lease);
        assertTrue(arrival >= 0 && portal > arrival && defer > portal
                        && lease > defer && body > lease,
                "a handed-over subtree must not take its tick lease or enter the vanilla body");
    }

    @Test
    void handOverUsesThePlayerOwnershipAuthorityOfTheAdvancementFlush() throws Exception {
        String scheduler = source("common/regions/RegionScheduler.java");
        String defer = section(scheduler,
                "public static boolean deferForeignPlayerPassenger(",
                "static void queueMainSyncGuardedTick(");
        int worker = defer.indexOf("Thread.currentThread() instanceof RegionWorkerThread");
        int foreign = defer.indexOf("containsForeignPlayer(passenger)", worker);
        int queue = defer.indexOf(
                "queueMainSyncTick(passenger, vehicle, () -> true, null, false, false)", foreign);
        assertTrue(worker >= 0 && foreign > worker && queue > foreign);
        assertTrue(defer.contains("!PlayerTurnOwnership.currentThreadOwns(player)"),
                "the hand-over must trigger exactly where flushDirty's owner check would fail");
        assertTrue(source("common/parallelised/advancement/AdvancementOwnership.java")
                .contains("return PlayerTurnOwnership.currentThreadOwns(player);"));
    }

    @Test
    void handedOverBodyIsVanillasPassengerTickWithoutCheckDespawn() throws Exception {
        String scheduler = source("common/regions/RegionScheduler.java");
        String run = section(scheduler, "private record MainSyncTask(",
                "static void queueMainSyncTick(Entity e)");
        int branch = run.indexOf("if (vehicle != null) {");
        int remount = run.indexOf("if (entity.getVehicle() != vehicle) return;", branch);
        int pass = run.indexOf("IslandFunctionHead.runMainSyncEntityPass(entity", remount);
        int tick = run.indexOf(
                "ParallelProcessor.tickPassengerVanillaGuarded(level, vehicle, entity, false)", pass);
        int branchEnd = run.indexOf("return;", tick);
        int despawn = run.indexOf("entity.checkDespawn()");
        assertTrue(branch >= 0 && remount > branch && pass > remount
                        && tick > pass && branchEnd > tick,
                "the passenger branch must run the vanilla passenger tick and return");
        assertTrue(despawn > branchEnd, "vanilla's tickPassenger has no checkDespawn");

        String processor = source("common/ParallelProcessor.java");
        String guarded = section(processor, "public static void tickPassengerVanillaGuarded(",
                "private static void tickVanillaGuarded(");
        assertTrue(guarded.contains("ServerLevelTickPassengerInvoker"));
        assertTrue(guarded.contains(".fabrolia$tickPassenger(vehicle, p)"));
    }

    @Test
    void offWorkerThreadsNeverHandOver() throws Exception {
        // Main (and every non-region thread) owns or fails loud itself; the probe
        // must not even read the subtree there (this allocation has no passenger list).
        Entity vehicle = allocate();
        Entity passenger = allocate();
        assertFalse(RegionScheduler.deferForeignPlayerPassenger(vehicle, passenger));
    }

    @Test
    void mainSyncPassengerSubtreeOwnsItsRidersButNotTheVehicleAbove() throws Exception {
        Entity cart = allocate();
        Entity horse = allocate();
        Entity rider = allocate();
        Entity bystander = allocate();
        setVehicle(horse, cart);
        setVehicle(rider, horse);

        FunctionPartition subtree = FunctionPartition.mainSyncTree(horse, null);
        assertTrue(subtree.owns(horse));
        assertTrue(subtree.owns(rider));
        assertFalse(subtree.owns(cart), "the vehicle's body already ran on its island");
        assertFalse(subtree.owns(bystander));

        FunctionPartition tree = FunctionPartition.mainSyncTree(cart, null);
        assertTrue(tree.owns(cart));
        assertTrue(tree.owns(horse));
        assertTrue(tree.owns(rider));
        assertFalse(tree.owns(bystander));
    }

    private static Entity allocate() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (Entity) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(FallingBlockEntity.class);
    }

    private static void setVehicle(Entity passenger, Entity vehicle) throws Exception {
        Field field = Entity.class.getDeclaredField("vehicle");
        field.setAccessible(true);
        field.set(passenger, vehicle);
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                () -> "source section not found: " + startNeedle + " .. " + endNeedle);
        return text.substring(start, end);
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
