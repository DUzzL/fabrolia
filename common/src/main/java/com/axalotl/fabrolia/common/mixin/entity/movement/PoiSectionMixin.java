package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.utils.FabroliaPoiSectionReadAccess;
import com.axalotl.fabrolia.common.utils.PoiSectionReadSnapshot;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Defence-in-depth: PoiSection's records ({@code Short2ObjectOpenHashMap})
 * and byType ({@code HashMap}) are vanilla non-thread-safe collections. The
 * {@link com.axalotl.fabrolia.common.mixin.entity.movement.PoiManagerMixin}
 * stripe lock covers the public {@code PoiManager} entry points
 * (add/remove/take/release/checkConsistency); this mixin adds a per-section
 * monitor on the matching {@code PoiSection} writers so ANY caller (a
 * chunk-system mod that bypassed PoiManager, a future internal call site,
 * a Lithium mixin we didn't notice) still serialises mutations against the
 * same section's records. Each completed structural mutation publishes one
 * immutable copy-on-write read view before its vanilla dirty notification.
 *
 * <p>Sync is on the {@code this} reference — each {@code PoiSection} is a
 * separate monitor. Different sections never serialise against each other.
 * Reads are lock-free volatile snapshot reads. In particular, {@code pack}
 * must never take this monitor: storage save/unload paths may hold locks that
 * section mutation reaches through {@code setDirty}. Lazy streams traverse
 * immutable lists and evaluate all caller predicates after publication.
 */
@Mixin(value = PoiSection.class, priority = 1500)
public abstract class PoiSectionMixin implements FabroliaPoiSectionReadAccess {

    @Shadow
    @Final
    private Short2ObjectMap<PoiRecord> records;

    @Shadow
    @Final
    private Map<Holder<PoiType>, Set<PoiRecord>> byType;

    @Shadow
    private boolean isValid;

    @Unique
    private volatile PoiSectionReadSnapshot fabrolia$readSnapshot;

    @Inject(
            method = "<init>(Ljava/lang/Runnable;ZLjava/util/List;)V",
            at = @At("RETURN"),
            require = 1,
            allow = 1)
    private void fabrolia$publishInitialReadSnapshot(CallbackInfo ci) {
        synchronized (this) {
            this.fabrolia$publishReadSnapshotLocked();
        }
    }

    @Unique
    private void fabrolia$publishReadSnapshotLocked() {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException(
                    "PoiSection read snapshot publication requires its mutation monitor");
        }
        this.fabrolia$readSnapshot = PoiSectionReadSnapshot.capture(
                this, this.byType, this.records, this.isValid);
    }

    @Override
    public PoiSectionReadSnapshot fabrolia$poiReadSnapshot() {
        PoiSectionReadSnapshot snapshot = this.fabrolia$readSnapshot;
        if (snapshot == null) {
            throw new IllegalStateException("PoiSection read snapshot was not initialized");
        }
        return snapshot;
    }

    @WrapMethod(method = "pack")
    private PoiSection.Packed fabrolia$snapshotSectionPack(
            Operation<PoiSection.Packed> original) {
        PoiSectionReadSnapshot snapshot = this.fabrolia$poiReadSnapshot();
        return new PoiSection.Packed(
                snapshot.isValid(),
                snapshot.records().stream().map(PoiRecord::pack).toList());
    }

    @WrapMethod(method = "getRecords")
    private Stream<PoiRecord> fabrolia$snapshotSectionRecords(
            Predicate<Holder<PoiType>> typePredicate,
            PoiManager.Occupancy occupancy,
            Operation<Stream<PoiRecord>> original) {
        return this.fabrolia$poiReadSnapshot().groupedRecords()
                .stream(typePredicate, occupancy.getTest());
    }

    @WrapMethod(method = "getPoiRecord")
    private Optional<PoiRecord> fabrolia$snapshotSectionRecordLookup(
            BlockPos pos, Operation<Optional<PoiRecord>> original) {
        return Optional.ofNullable(this.fabrolia$poiReadSnapshot().recordAt(pos));
    }

    @WrapMethod(method = "isValid")
    private boolean fabrolia$snapshotSectionValidityRead(Operation<Boolean> original) {
        return this.fabrolia$poiReadSnapshot().isValid();
    }

    @WrapMethod(method = "add(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;")
    private PoiRecord fabrolia$syncSectionAdd(BlockPos pos, Holder<PoiType> type, Operation<PoiRecord> original) {
        synchronized (this) {
            return original.call(pos, type);
        }
    }

    @WrapMethod(method = "remove")
    private void fabrolia$syncSectionRemove(BlockPos pos, Operation<Void> original) {
        synchronized (this) {
            original.call(pos);
        }
    }

    @WrapMethod(method = "release")
    private boolean fabrolia$syncSectionRelease(BlockPos pos, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(pos);
        }
    }

    @WrapMethod(method = "refresh")
    private void fabrolia$syncSectionRefresh(
            Consumer<BiConsumer<BlockPos, Holder<PoiType>>> visitor,
            Operation<Void> original) {
        synchronized (this) {
            original.call(visitor);
        }
    }

    /**
     * The publication point is deliberately before vanilla's exact dirty
     * callback. Publishing after the outer WrapMethod returned would let a
     * tracker/save drainer consume that notification and still observe the old
     * view, permanently losing the structural update from its derived state.
     */
    @WrapOperation(
            method = {
                    "add(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)"
                            + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;",
                    "remove(Lnet/minecraft/core/BlockPos;)V",
                    "refresh(Ljava/util/function/Consumer;)V"
            },
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
            require = 3,
            allow = 3)
    private void fabrolia$publishBeforeDirty(
            Runnable dirtyCallback, Operation<Void> original) {
        this.fabrolia$publishReadSnapshotLocked();
        original.call(dirtyCallback);
    }
}
