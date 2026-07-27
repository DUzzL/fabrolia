package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.VibrationDomains;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.gameevent.vibrations.VibrationInfo;
import net.minecraft.world.level.gameevent.vibrations.VibrationSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Vibration-selector hardening for multi-clock delivery (sculk deafness fix,
 * 2026-07-27 — defect write-up on {@link VibrationDomains}):
 *
 * <ol>
 *   <li><b>Cross-thread safety.</b> {@code addCandidate} runs on whatever
 *       thread emitted the game event (main, any island worker, a routed
 *       packet apply) while {@code chosenCandidate}/{@code startOver} run on
 *       the listener's ticking venue. The field swap is a plain Optional
 *       write; the synchronized wraps make candidate hand-off atomic and
 *       visible instead of torn/lost. Uncontended cost: a biased lock.</li>
 *   <li><b>Future-residue heal.</b> A pending candidate stamped AHEAD of the
 *       listener's own clock is impossible in a single-domain history — it is
 *       cross-domain residue (pre-fix wall stamps in an island sensor, saves,
 *       island merges onto an older clock). Vanilla's replacement rule keeps
 *       it forever ({@code tickTime != previousTick} discards the NEWCOMER),
 *       silencing the listener until its clock passes a foreign stamp — on a
 *       lagging island, never. When a new candidate arrives whose (listener
 *       -domain) stamp is BEHIND the pending stamp, the residue is cleared
 *       and the fresh candidate takes the slot: route, never drop.</li>
 * </ol>
 *
 * Same-clock behavior is untouched: with one timeline the pending stamp can
 * never exceed a new stamp, so the heal branch is dead code there and the
 * vanilla replacement rules run bit-identically.
 */
@Mixin(VibrationSelector.class)
public abstract class VibrationSelectorMixin {

    @Shadow public abstract void startOver();

    /** Mirror of the pending candidate's stamp ({@link VibrationDomains#NO_STAMP}
     *  = empty). Kept here because vanilla's {@code VibrationEvent} record is
     *  private and exposes no read path. Guarded by the synchronized wraps. */
    @Unique
    private long fabrolia$pendingStamp = VibrationDomains.NO_STAMP;

    /** Save/load carries the pending stamp through the codec constructor —
     *  a pre-fix save can resurrect a wall-stamped candidate into an island
     *  sensor, which is exactly the residue the heal must see. */
    @Inject(method = "<init>(Ljava/util/Optional;J)V", at = @At("TAIL"))
    private void fabrolia$initPendingStamp(Optional<?> currentVibration, long tick, CallbackInfo ci) {
        if (currentVibration.isPresent()) {
            this.fabrolia$pendingStamp = tick;
        }
    }

    @WrapMethod(method = "addCandidate")
    private synchronized void fabrolia$healThenAdd(VibrationInfo newVibration, long tickTime,
                                                   Operation<Void> original) {
        if (VibrationDomains.pendingIsFutureResidue(this.fabrolia$pendingStamp, tickTime)) {
            // clears the slot AND (via the startOver wrap) the stamp mirror
            this.startOver();
        }
        original.call(newVibration, tickTime);
        if (this.fabrolia$pendingStamp == VibrationDomains.NO_STAMP) {
            // empty slot always accepts; occupied slots only ever keep their
            // existing stamp (same-stamp replacement), so this is the one
            // transition the mirror must record.
            this.fabrolia$pendingStamp = tickTime;
        }
    }

    @WrapMethod(method = "chosenCandidate")
    private synchronized Optional<VibrationInfo> fabrolia$syncChoose(long time,
                                                                     Operation<Optional<VibrationInfo>> original) {
        return original.call(time);
    }

    @WrapMethod(method = "startOver")
    private synchronized void fabrolia$syncStartOver(Operation<Void> original) {
        original.call();
        this.fabrolia$pendingStamp = VibrationDomains.NO_STAMP;
    }
}
