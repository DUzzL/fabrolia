package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;

/** Authority for vanilla raid callbacks that can reach several islands. */
public final class RaidOwnership {
    private RaidOwnership() {}
    /** Physical-Main compatibility execution, retaining the canonical first-W fence exit. */
    public static void runMainFenced(Runnable body) {
        boolean acquired = false;
        Throwable failure = null;
        try {
            acquired = IslandCommands.quiesceAllExcept(null);
            RegionScheduler.runSuccessBodyIfHealthy(body);
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        if (acquired) {
            IslandCommands.releaseAllExceptAndPropagate(failure);
        } else if (failure != null) {
            IslandCommands.publishAndPropagate(failure);
        } else {
            RegionScheduler.commitSuccessIfHealthy(() -> {});
        }
    }

    /** Vanilla uses the saved village position on the final Raid Omen effect tick. */
    public static boolean expiresThisTurn(ServerPlayer player) {
        if (player.getRaidOmenPosition() == null) return false;
        var omen = player.getEffect(MobEffects.RAID_OMEN);
        return omen != null && (omen.getDuration() == 1 || omen.isInfiniteDuration() && player.tickCount == 1);
    }

    /** The coordinator lends its claim only at an exclusive point, before taking any body lock. */
    public static void runFenced(ServerLevel level, String label, Runnable body) {
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(level.getServer())) {
            runMainFenced(body);
            return;
        }
        var runtime = RegionScheduler.forLevel(level);
        // A region worker without async islands keeps the former direct raid tick; a
        // thread holding every claim already excludes all islands. Foreign threads never enter.
        if (runtime != null && runtime.currentThreadHoldsAllClaims()
                || Thread.currentThread() instanceof RegionWorkerThread
                        && (runtime == null || !runtime.asyncActive)) {
            RegionScheduler.runSuccessBodyIfHealthy(body);
            return;
        }
        var owner = IslandCommands.exclusiveCoordinatorIsland(level);
        if (runtime != null && owner != null) {
            IslandCommands.routeFencedIslandBody(runtime, owner, label, () -> {
                body.run();
                return true;
            });
            return;
        }
        throw new IllegalStateException(label + " requires physical Main or its exclusive island coordinator");
    }
}
