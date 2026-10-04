package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.save.DeferredSavedDataEncodes;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The scoreboard's encode moves from {@code collectDirtyTagsToSave} (Main,
 * under the all-island quiesce of {@code saveAllChunks}) into its
 * {@code tryWrite} task on {@code Util.ioPool}. Dirty-bit retirement, the
 * per-storage write order ({@code pendingWriteFuture}) and the joins of
 * {@code saveAndJoin} (flush, close) are vanilla's; the joined write task now
 * includes the encode. See {@link DeferredSavedDataEncodes}.
 */
@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageEncodeMixin {
    @Unique
    private final DeferredSavedDataEncodes fabrolia$deferredEncodes = new DeferredSavedDataEncodes();

    @WrapMethod(method = "encodeUnchecked", require = 1, allow = 1)
    private CompoundTag fabrolia$deferSnapshotEncode(
            SavedDataType<?> type, SavedData data, RegistryOps<Tag> ops,
            Operation<CompoundTag> original) {
        return this.fabrolia$deferredEncodes.encodeOrDefer(
                data, snapshot -> original.call(type, snapshot, ops));
    }

    @WrapMethod(method = "tryWrite", require = 1, allow = 1)
    private void fabrolia$encodeDeferredBeforeWrite(
            SavedDataType<?> type, CompoundTag tag, Operation<Void> original) {
        CompoundTag resolved = this.fabrolia$deferredEncodes.resolve(tag);
        if (resolved != null) {
            original.call(type, resolved);
        }
    }
}
