package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaNeighborUpdaterState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * Per-thread instances (see
 * {@link com.axalotl.fabrolia.common.parallelised.utils.FabroliaThreadNeighborUpdaters})
 * run vanilla's {@code addAndRun}/{@code runUpdates} unmodified; this mixin only
 * lets them follow the level primary's debug listener.
 *
 * <p>Vanilla reads {@code debugListener} twice per layer (null check, then use)
 * while {@code ServerLevel.tick} rewrites the primary's field every tick from
 * Main. The first read copies the primary's value into this instance's own
 * field, which only this instance's thread touches, so the second read sees the
 * same value.
 */
@Mixin(CollectingNeighborUpdater.class)
public abstract class CollectingNeighborUpdaterMixin implements FabroliaNeighborUpdaterState {

    @Shadow @Final
    private int maxChainedNeighborUpdates;

    @Shadow
    private Consumer<BlockPos> debugListener;

    @Unique
    private CollectingNeighborUpdater fabrolia$debugListenerSource;

    @Override
    public int fabrolia$maxChainedNeighborUpdates() {
        return this.maxChainedNeighborUpdates;
    }

    @Override
    public Consumer<BlockPos> fabrolia$debugListener() {
        return this.debugListener;
    }

    @Override
    public void fabrolia$followDebugListenerOf(CollectingNeighborUpdater primary) {
        this.fabrolia$debugListenerSource = primary;
    }

    @ModifyExpressionValue(method = "runUpdates",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;"
                            + "debugListener:Ljava/util/function/Consumer;",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0),
            require = 1,
            allow = 1)
    private Consumer<BlockPos> fabrolia$adoptPrimaryDebugListener(Consumer<BlockPos> own) {
        CollectingNeighborUpdater source = this.fabrolia$debugListenerSource;
        if (source == null) return own;
        Consumer<BlockPos> shared = ((FabroliaNeighborUpdaterState) source).fabrolia$debugListener();
        this.debugListener = shared;
        return shared;
    }
}
