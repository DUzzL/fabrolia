package com.axalotl.fabrolia.neoforge.mixin.world;

import com.axalotl.fabrolia.neoforge.world.BlockSnapshotCapture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * NeoForge's drop capture, per thread.
 *
 * <p>NeoForge's {@code Block.dropResources} collects the item entities that
 * {@code popResource} creates in the STATIC field {@code capturedDrops}
 * ({@code beginCapturingDrops} / {@code stopCapturingDrops}) and hands the list to
 * {@code BlockDropsEvent}. Fabrolia's dropResources monitor is per region, so two
 * regions dropping at once shared that field: the first to finish took both lists'
 * drops and the other got {@code null} (NullPointerException in
 * {@code CommonHooks.handleBlockDrops}, 2026-10-23 on leaf decay), or a drop was spawned
 * by the wrong thread. Single-threaded NeoForge captures only the drops of the
 * current dropResources call; a thread-local field keeps exactly that.
 *
 * <p>Drops are also suppressed while this thread restores block snapshots
 * ({@link BlockSnapshotCapture}), not while any thread of the level does.
 */
@Mixin(Block.class)
public abstract class BlockDropCaptureMixin {

    @Unique
    private static final ThreadLocal<List<ItemEntity>> fabrolia$capturedDrops = new ThreadLocal<>();

    @Redirect(method = {"beginCapturingDrops()V", "stopCapturingDrops()Ljava/util/List;"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;",
                    opcode = Opcodes.PUTSTATIC),
            require = 2, allow = 2)
    private static void fabrolia$setThreadDrops(List<ItemEntity> drops) {
        fabrolia$capturedDrops.set(drops);
    }

    @Redirect(method = {"stopCapturingDrops()Ljava/util/List;",
            "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;"
                    + "Lnet/minecraft/world/item/ItemStack;)V"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;",
                    opcode = Opcodes.GETSTATIC),
            require = 3, allow = 3)
    private static List<ItemEntity> fabrolia$threadDrops() {
        return fabrolia$capturedDrops.get();
    }

    @Redirect(method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;"
            + "Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;restoringBlockSnapshots:Z",
                    opcode = Opcodes.GETFIELD),
            require = 1, allow = 1)
    private static boolean fabrolia$threadRestoringItems(Level level) {
        return BlockSnapshotCapture.of(level).restoring;
    }

    @Redirect(method = "popExperience(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;restoringBlockSnapshots:Z",
                    opcode = Opcodes.GETFIELD),
            require = 1, allow = 1)
    private boolean fabrolia$threadRestoringExperience(ServerLevel level) {
        return BlockSnapshotCapture.of(level).restoring;
    }
}
