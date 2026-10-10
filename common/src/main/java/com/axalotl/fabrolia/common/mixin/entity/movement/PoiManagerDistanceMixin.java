package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.utils.FabroliaPoiTracker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Task #38 — the {@code PoiManager} half of the tracker deferral.
 *
 * <p>All the reasoning lives in {@link PoiDistanceTrackerMixin}; this class only
 * rewires the four call sites. The tracker is a package-private inner class, so
 * every receiver is typed through the {@link FabroliaPoiTracker} duck interface
 * that mixin implements.
 *
 * <p>Bytecode note: javac emits the static receiver type at these call sites, so
 * the INVOKE owner is {@code PoiManager$DistanceTracker} — not
 * {@code SectionTracker} — even for {@code update}, which is declared on the
 * parent. Verified with {@code javap -c} against the 26.2 jar.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerDistanceMixin {

    /**
     * {@code setDirty} → enqueue instead of notifying the graph.
     *
     * <p>{@code super.setDirty} (the dirty-chunk marking) stays inline — only the
     * tracker call is deferred, so chunk persistence is untouched. The wrapped
     * {@code level} argument is discarded on purpose: the caller computed it
     * before this point via {@code getLevelFromSource}, and it is exactly the
     * stale value the drain exists to avoid.
     */
    @WrapOperation(
            method = "setDirty(J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker;update(JIZ)V"))
    private void fabrolia$deferSetDirty(@Coerce FabroliaPoiTracker tracker,
                                        long sectionPos, int level, boolean decrease,
                                        Operation<Void> original) {
        tracker.fabrolia$enqueueTrackerUpdate(sectionPos);
    }

    /** Same deferral for the section-load notification. */
    @WrapOperation(
            method = "onSectionLoad(J)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker;update(JIZ)V"))
    private void fabrolia$deferSectionLoad(@Coerce FabroliaPoiTracker tracker,
                                           long sectionPos, int level, boolean decrease,
                                           Operation<Void> original) {
        tracker.fabrolia$enqueueTrackerUpdate(sectionPos);
    }

    /**
     * Drain immediately before vanilla's own {@code runAllUpdates()} in
     * {@code tick} — deliberately NOT at method HEAD. {@code super.tick} runs
     * {@code unpackPendingLoads} first, and the {@code onSectionLoad} enqueues it
     * produces must be applied by the very {@code runAllUpdates} that follows
     * them; draining at HEAD would defer them a whole tick.
     */
    @WrapOperation(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker;runAllUpdates()V"))
    private void fabrolia$drainOnTick(@Coerce FabroliaPoiTracker tracker, Operation<Void> original) {
        tracker.fabrolia$drainTrackerUpdates();
        original.call(tracker);
    }

    /**
     * Query-time drain, so a worker that mutated a POI and immediately asks for
     * village distance sees its own update in the SAME tick. Vanilla runs
     * {@code runAllUpdates()} at query time; a main-only drain would silently
     * turn every village query one tick stale.
     *
     * <p>In 26.3 this is a graph query, not a POI load request. Forcing
     * getOrLoad here introduced disk joins and village sources that vanilla
     * had not loaded. Apply accepted notifications, then run the original
     * graph relaxation; normal POI loading remains in the storage lifecycle.
     */
    @WrapOperation(
            method = "sectionsToVillage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker;runAllUpdates()V"))
    private void fabrolia$drainOnQuery(@Coerce FabroliaPoiTracker tracker, Operation<Void> original,
                                       net.minecraft.core.SectionPos pos) {
        tracker.fabrolia$drainTrackerUpdates();
        original.call(tracker);
    }
}
