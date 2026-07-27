package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Defence-in-depth: PoiSection's records ({@code Short2ObjectOpenHashMap})
 * and byType ({@code HashMap}) are vanilla non-thread-safe collections. The
 * {@link com.axalotl.fabrolia.common.mixin.entity.movement.PoiManagerMixin}
 * stripe lock covers the public {@code PoiManager} entry points
 * (add/remove/take/release/checkConsistency); this mixin adds a per-section
 * monitor on the matching {@code PoiSection} methods so ANY caller (a
 * chunk-system mod that bypassed PoiManager, a future internal call site,
 * a Lithium mixin we didn't notice) still serialises mutations against the
 * same section's records.
 *
 * <p>Sync is on the {@code this} reference — each {@code PoiSection} is a
 * separate monitor. Different sections never serialise against each other.
 * Reads (exists, getType, getRecords) stay unlocked: same trade-off as the
 * outer layer; villager AI queries don't take this monitor and rely on
 * the writes being atomic at the entry point.
 */
@Mixin(value = PoiSection.class, priority = 1500)
public abstract class PoiSectionMixin {

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
}
