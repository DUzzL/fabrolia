package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaSafeNavigation;
import com.axalotl.fabrolia.common.regions.NavRecompute;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PathNavigation.class)
public abstract class FabroliaPathNavigationMixin implements FabroliaSafeNavigation {

    @Shadow
    protected Path path;

    @Shadow
    protected boolean hasDelayedRecomputation;

    @Shadow
    @Final
    protected Mob mob;

    @Shadow
    @Final
    protected Level level;

    @Override
    public net.minecraft.world.entity.Mob fabrolia$navMob() {
        return this.mob;
    }

    /**
     * Task #40: owner-routed {@code stop()}. The method is EXEMPTED from the
     * blanket monitor (SynchronisePlugin — both "stop" and this handler's
     * mangled name), so this guard runs WITHOUT entering the nav monitor; a
     * cross-context caller (TeleportCommand / PiglinAi.stopWalking /
     * randomTeleport — all reach here from MAIN) is deferred to the owner's
     * island lane instead of blocking on a monitor the owner worker may hold
     * across an unbounded ChunkBridgeWait (the task-#32 cycle) or racing the
     * owner's synchronized tick with an unguarded {@code path = null}. Owner
     * context falls through to the vanilla body.
     */
    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void fabrolia$routedStop(CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel
                && NavRecompute.stopCrossContextDeferred(
                        serverLevel, this.mob, (PathNavigation) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * MONITOR-FREE by contract (SynchronisePlugin exclusion, task #32): callers
     * are the sendBlockUpdated nav scan on main and on region workers — they
     * must never block on the nav monitor the owner holds across its whole
     * synchronized tick() (which may park on the getGroundY→getChunk
     * main-thread bridge). All reads are snapshot-local and stale-tolerant;
     * the one racy structural mutation the owner can make in place is
     * {@code Path.truncateNodes} (trimPath), which can shrink {@code nodes}
     * between our size read and {@code getEndNode()}'s get — caught below and
     * answered {@code false} (the owner is mid-repath already; a request
     * would be redundant, matching vanilla's delayed-recompute tolerance).
     */
    @Override
    public boolean fabrolia$shouldRecomputePathSafe(BlockPos pos) {
        if (this.hasDelayedRecomputation) {
            return false;
        }
        Path currentPath = this.path;
        if (currentPath == null || currentPath.isDone()) {
            return false;
        }
        int nodeCount = currentPath.getNodeCount();
        if (nodeCount == 0) {
            return false;
        }
        Node endNode;
        try {
            endNode = currentPath.getEndNode();
        } catch (IndexOutOfBoundsException raceWithTruncate) {
            return false;
        }
        if (endNode == null) {
            return false;
        }
        int remaining = nodeCount - currentPath.getNextNodeIndex();
        if (remaining <= 0) {
            return false;
        }
        double cx = (endNode.x + this.mob.getX()) * 0.5;
        double cy = (endNode.y + this.mob.getY()) * 0.5;
        double cz = (endNode.z + this.mob.getZ()) * 0.5;
        double dx = pos.getX() + 0.5 - cx;
        if (dx > remaining || dx < -remaining) return false;
        double dy = pos.getY() + 0.5 - cy;
        if (dy > remaining || dy < -remaining) return false;
        double dz = pos.getZ() + 0.5 - cz;
        if (dz > remaining || dz < -remaining) return false;
        return dx * dx + dy * dy + dz * dz < (double) remaining * remaining;
    }
}