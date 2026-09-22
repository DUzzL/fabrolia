package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.AsyncIslandListenerAccess;
import com.axalotl.fabrolia.common.regions.AsyncIslandMovementListenerAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.phys.Vec3;

/**
 * Publishes the exact per-listener A5 session binding and owns physical disconnect
 * before vanilla mutates the listener's player.
 *
 * <p>The actual tick-ownership seam is in {@code Connection.tick}: physical Main
 * keeps flush/disconnect/statistics but skips the complete play-listener tick while
 * the exact session is island-owned. The island late slot invokes that complete
 * listener state machine once, retaining tickPlayer's early return and serializing
 * its throttlers.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerIslandMixin
        implements AsyncIslandListenerAccess, AsyncIslandMovementListenerAccess {

    @Shadow private double lastGoodX;
    @Shadow private double lastGoodY;
    @Shadow private double lastGoodZ;
    @Shadow private Vec3 awaitingPositionFromClient;

    @Override
    public double fabrolia$lastGoodX() {
        return lastGoodX;
    }

    @Override
    public double fabrolia$lastGoodY() {
        return lastGoodY;
    }

    @Override
    public double fabrolia$lastGoodZ() {
        return lastGoodZ;
    }

    @Override
    public boolean fabrolia$hasAwaitingPosition() {
        return awaitingPositionFromClient != null;
    }

    @org.spongepowered.asm.mixin.Unique
    private final java.util.concurrent.atomic.AtomicReference<AsyncIslandPlayerTicks.PlayerState>
            fabrolia$asyncIslandPlayerState = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * {@code onDisconnect} calls {@code ServerPlayer.disconnect()} before
     * {@code PlayerList.remove}; that first call publishes {@code hasDisconnected}
     * before the later generic lifecycle fence. Own the complete method so the
     * existing A5 fence drains an owed late listener before either vanilla write.
     */
    @WrapMethod(
            method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
            require = 1,
            allow = 1)
    private void fabrolia$scopePhysicalMainDisconnect(
            DisconnectionDetails details, Operation<Void> original) {
        ServerGamePacketListenerImpl listener =
                (ServerGamePacketListenerImpl) (Object) this;
        AsyncIslandPlayerTicks.runPhysicalMainDisconnect(
                listener, ((com.axalotl.fabrolia.common.mixin.network.ServerCommonPacketListenerAccessor)
                        this).fabrolia$getServer(),
                () -> original.call(details));
    }

    @Override
    public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() {
        return fabrolia$asyncIslandPlayerState.get();
    }

    @Override
    public boolean fabrolia$bindAsyncIslandPlayerState(
            AsyncIslandPlayerTicks.PlayerState state) {
        AsyncIslandPlayerTicks.PlayerState current = fabrolia$asyncIslandPlayerState.get();
        return current == state
                || current == null && fabrolia$asyncIslandPlayerState.compareAndSet(null, state);
    }

    @Override
    public boolean fabrolia$unbindAsyncIslandPlayerState(
            AsyncIslandPlayerTicks.PlayerState state) {
        return fabrolia$asyncIslandPlayerState.compareAndSet(state, null);
    }

}
