package com.axalotl.fabrolia.fabric;

import com.axalotl.fabrolia.common.FabroliaCommon;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.commands.FabroliaCommand;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FabroliaFabric extends FabroliaCommon implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger(FabroliaFabric.class);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Fabrolia...");
        this.initialize();
        com.axalotl.fabrolia.fabric.config.FabroliaConfig.init();
        com.axalotl.fabrolia.fabric.config.FabroliaConfig.registerPersister();

        // Key the item-pickup monitor by the ITEM's region (not the current thread): every
        // toucher of an ItemEntity — region worker, help-fanout helper, main's barrier-pump
        // stolen batches, main-sync-lane entities — resolves the SAME region.lock, so the
        // exclusion the old JVM-wide static lock provided survives main/worker mixing (the
        // mixed-monitor dupe window, 2026-07-01). On a region worker it is the same lock as
        // the sibling MinecartHopper.suckInItems / Mob.tryEquip tickLockOr monitors →
        // reentrant, so the AB-BA wedge fix (2026-07-01) stays dead. Scheduler inactive →
        // the JVM-wide fallback (original global behaviour).
        com.axalotl.fabrolia.api.utils.SyncItemPickup.setMonitorResolver(
                (level, item) -> com.axalotl.fabrolia.common.regions.FabroliaLocks.itemLockOr(
                        level, item, com.axalotl.fabrolia.api.utils.SyncItemPickup.fallback()));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("Fabrolia Setting up thread-pool...");
            ParallelProcessor.setServer(server);
            ParallelProcessor.setupThreadPool(FabroliaConfig.getParallelism());
            // Region scheduler is a no-op when FabroliaConfig.enableRegionThreading=false (default).
            RegionScheduler.start(server);
        });

        // Reliable, event-driven per-region FULL-chunk tracking — a LevelChunk load/unload
        // IS the FULL gain/loss. Replaces the c2me-bypassed updateChunkScheduling seam whose
        // unload decrement was lost (regions never went empty → eviction reclaimed nothing).
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, newChunk) ->
                RegionScheduler.onChunkFullChange(world, chunk.getPos().pack(), true, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                RegionScheduler.onChunkFullChange(world, chunk.getPos().pack(), false, chunk));

        // Free the level's Rust local-cap arena handle on unload — without this the handle
        // map retains the ServerLevel (and its native arena) forever, which matters with
        // dynamic-dimension mods. No-op when no provider is registered.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.UNLOAD.register(
                (server, world) -> com.axalotl.fabrolia.common.parallelised.utils
                        .ParallelSpawnHelper.onLevelUnload(world));

        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> FabroliaCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> {
            // Stop region scheduler FIRST so any in-flight cross-region tasks can drain
            // before the fabrolia pool they may submit follow-up work to goes away.
            RegionScheduler.stop();
            ParallelProcessor.stop();
        });

        LOGGER.info("Fabrolia Initialized Successfully!");
    }
}