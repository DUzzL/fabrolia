package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaNeighborUpdaterState;
import com.axalotl.fabrolia.common.parallelised.utils.FabroliaThreadNeighborUpdaters;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** See {@link FabroliaThreadNeighborUpdaters}. */
@Mixin(Level.class)
public abstract class LevelThreadNeighborUpdaterMixin implements FabroliaThreadNeighborUpdaters {

    @Shadow @Final
    protected CollectingNeighborUpdater neighborUpdater;

    @Shadow @Final
    private Thread thread;

    /** Instance of the level's construction thread (Main for server levels); only that thread touches it. */
    @Unique
    private CollectingNeighborUpdater fabrolia$ownerThreadUpdater;

    @Unique
    private final ThreadLocal<FabroliaThreadNeighborUpdaters.Slot> fabrolia$otherThreadSlots = new ThreadLocal<>();

    @Unique
    private final java.util.Queue<FabroliaThreadNeighborUpdaters.Slot> fabrolia$allOtherThreadSlots =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    @Override
    public CollectingNeighborUpdater fabrolia$threadNeighborUpdater() {
        if (Thread.currentThread() == this.thread) {
            CollectingNeighborUpdater own = this.fabrolia$ownerThreadUpdater;
            if (own == null) {
                own = fabrolia$newThreadUpdater();
                this.fabrolia$ownerThreadUpdater = own;
            }
            return own;
        }
        FabroliaThreadNeighborUpdaters.Slot slot = this.fabrolia$otherThreadSlots.get();
        if (slot == null) {
            slot = new FabroliaThreadNeighborUpdaters.Slot();
            this.fabrolia$otherThreadSlots.set(slot);
            this.fabrolia$allOtherThreadSlots.add(slot);
        }
        CollectingNeighborUpdater updater = slot.updater;
        if (updater == null) {
            updater = fabrolia$newThreadUpdater();
            slot.updater = updater;
        }
        return updater;
    }

    @Override
    public void fabrolia$releaseThreadNeighborUpdaters() {
        // The slots themselves stay in the workers' ThreadLocalMaps until those
        // expunge them, but no longer reach this level.
        for (FabroliaThreadNeighborUpdaters.Slot slot : this.fabrolia$allOtherThreadSlots) {
            slot.updater = null;
        }
    }

    @Unique
    private CollectingNeighborUpdater fabrolia$newThreadUpdater() {
        CollectingNeighborUpdater primary = this.neighborUpdater;
        if (primary.getClass() != CollectingNeighborUpdater.class) {
            throw new IllegalStateException("Fabrolia runs one vanilla CollectingNeighborUpdater per thread;"
                    + " cannot replicate subclass " + primary.getClass().getName());
        }
        CollectingNeighborUpdater updater = new CollectingNeighborUpdater((Level) (Object) this,
                ((FabroliaNeighborUpdaterState) primary).fabrolia$maxChainedNeighborUpdates());
        ((FabroliaNeighborUpdaterState) updater).fabrolia$followDebugListenerOf(primary);
        return updater;
    }

    @ModifyExpressionValue(method = "neighborShapeChanged",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/Level;neighborUpdater:"
                            + "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;",
                    opcode = Opcodes.GETFIELD),
            require = 1,
            allow = 1)
    private CollectingNeighborUpdater fabrolia$shapeUpdateOnThreadUpdater(CollectingNeighborUpdater primary) {
        return fabrolia$threadNeighborUpdater();
    }
}
