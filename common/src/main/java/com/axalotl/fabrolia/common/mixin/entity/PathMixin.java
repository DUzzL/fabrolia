package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.utils.PathDoorAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(Path.class)
public abstract class PathMixin implements PathDoorAccess {
    @Shadow @Final private List<Node> nodes;
    @Shadow private int nextNodeIndex;
    @Unique private volatile PathDoorAccess.Step fabrolia$doorStep;

    @Override
    public PathDoorAccess.Step fabrolia$doorStep() {
        return fabrolia$doorStep;
    }

    @Override
    public void fabrolia$publishDoorStep() {
        // Path.copy shares nodes. Serialize only the bounded list operations,
        // not navigation (which can wait for chunks). Foreign readers do not
        // acquire this monitor and cannot participate in a chunk-wait cycle.
        synchronized (nodes) {
            int index = nextNodeIndex;
            if (index <= 0 || index >= nodes.size()) {
                fabrolia$doorStep = null;
                return;
            }
            Node previous = nodes.get(index - 1);
            Node next = nodes.get(index);
            long previousPos = BlockPos.asLong(previous.x, previous.y, previous.z);
            long nextPos = BlockPos.asLong(next.x, next.y, next.z);
            PathDoorAccess.Step old = fabrolia$doorStep;
            if (old == null || old.previous() != previousPos || old.next() != nextPos) {
                fabrolia$doorStep = new PathDoorAccess.Step(previousPos, nextPos);
            }
        }
    }

    @WrapMethod(method = "advance")
    private void fabrolia$advanceAndPublish(Operation<Void> original) {
        synchronized (nodes) {
            original.call();
            fabrolia$publishDoorStep();
        }
    }

    @WrapMethod(method = {"truncateNodes", "setNextNodeIndex"})
    private void fabrolia$changeIndexAndPublish(int value, Operation<Void> original) {
        synchronized (nodes) {
            original.call(value);
            fabrolia$publishDoorStep();
        }
    }

    @WrapMethod(method = "replaceNode")
    private void fabrolia$replaceAndPublish(int index, Node node, Operation<Void> original) {
        synchronized (nodes) {
            original.call(index, node);
            fabrolia$publishDoorStep();
        }
    }

    @WrapMethod(method = "copy")
    private Path fabrolia$copyAndPublish(Operation<Path> original) {
        synchronized (nodes) {
            Path copy = original.call();
            ((PathDoorAccess) (Object) copy).fabrolia$publishDoorStep();
            return copy;
        }
    }
}
