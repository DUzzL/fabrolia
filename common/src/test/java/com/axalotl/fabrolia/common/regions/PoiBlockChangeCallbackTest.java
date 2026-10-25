package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.ServerLevelPortalPreparationMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Executes the production wrap of the {@code server.execute} call in
 * {@code ServerLevel.updatePOIOnBlockStateChange}. Vanilla 26.3 runs that
 * callback inline on the ticking thread, so a block change has published its
 * POI change when {@code setBlock} returns. A raid's village check and the
 * villagers' claims on the same island depend on that.
 */
class PoiBlockChangeCallbackTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void islandWorkerPublishesJobSiteChangeBeforeSetBlockReturns() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        onWorker(() -> {
            publish(PoiTypes.FLETCHER,
                    () -> events.add("callback on " + Thread.currentThread().getName()),
                    queued -> events.add("queued for Main"));
            events.add("setBlock returned");
        });
        assertEquals(List.of("callback on poi-callback-worker", "setBlock returned"), events);
    }

    @Test
    void islandWorkerPublishesBedChangeBeforeSetBlockReturns() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        onWorker(() -> {
            publish(PoiTypes.HOME, () -> events.add("callback"), queued -> events.add("queued for Main"));
            events.add("setBlock returned");
        });
        assertEquals(List.of("callback", "setBlock returned"), events);
    }

    @Test
    void otherThreadsKeepVanillaExecute() throws Exception {
        // Physical Main (and any foreign thread) keeps MinecraftServer.execute,
        // which itself decides between inline and queued exactly as vanilla.
        List<Runnable> executed = new ArrayList<>();
        Runnable callback = () -> fail("the wrap must not run the callback off a simulation thread");
        publish(PoiTypes.FLETCHER, callback, executed::add);
        assertEquals(List.of(callback), executed);
    }

    private static void publish(net.minecraft.resources.ResourceKey<PoiType> type, Runnable callback,
                                Consumer<Runnable> vanillaExecute) throws Exception {
        Holder<PoiType> holder = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(type);
        Method hook = ServerLevelPortalPreparationMixin.class.getDeclaredMethod(
                "fabrolia$trackPortalPublication",
                MinecraftServer.class, Runnable.class, Operation.class, net.minecraft.core.BlockPos.class, Holder.class);
        hook.setAccessible(true);
        Operation<Void> original = arguments -> {
            vanillaExecute.accept((Runnable) arguments[1]);
            return null;
        };
        hook.invoke(new Level(), null, callback, original, net.minecraft.core.BlockPos.ZERO, holder);
    }

    private static void onWorker(CheckedRunnable body) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { body.run(); return null; });
        Thread worker = new RegionWorkerThread(task, "poi-callback-worker");
        worker.start();
        try { task.get(5, TimeUnit.SECONDS); }
        finally { worker.join(5_000); }
        assertFalse(worker.isAlive());
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }

    /** The mixin body alone; no ServerLevel is needed for the callback routing. */
    private static final class Level extends ServerLevelPortalPreparationMixin {}
}
