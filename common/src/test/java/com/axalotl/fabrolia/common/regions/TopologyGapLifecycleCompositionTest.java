package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The legs of the A5 topology-gap binding when a lifecycle mutation is pending.
 * A same-level teleport arms {@code auditedLifecycleMutationPending} together with
 * its own Main handoff; a topology rebuild before that handoff drains (the
 * 2026-10-25 reproduction of the 3.0.1 stand crash) must hand the tracker to it.
 */
class TopologyGapLifecycleCompositionTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void intactBindingWithoutMutationHolds() throws Exception {
        Fixture f = new Fixture();
        assertNull(f.failures(IslandPlayerTurnSession.Phase.ISLAND_READY));
    }

    @Test
    void softAuditedMutationComposesWithItsInFlightMainHandoff() throws Exception {
        Fixture f = new Fixture();
        f.state.lifecycleInvalidated = true;
        f.state.auditedLifecycleMutationPending = true;
        assertNull(f.failures(IslandPlayerTurnSession.Phase.MAIN_HANDOFF),
                "a teleport whose Main handoff is in flight keeps the exact binding");
    }

    @Test
    void auditedMutationWithoutItsHandoffStaysBroken() throws Exception {
        Fixture f = new Fixture();
        f.state.lifecycleInvalidated = true;
        f.state.auditedLifecycleMutationPending = true;
        assertEquals("auditedLifecycleMutationPending lifecycleInvalidated",
                f.failures(IslandPlayerTurnSession.Phase.ISLAND_READY));
    }

    @Test
    void hardExitStaysBrokenInTheHandoff() throws Exception {
        Fixture f = new Fixture();
        f.state.lifecycleInvalidated = true;
        f.state.auditedLifecycleMutationPending = true;
        f.state.hardLifecycleExitPending = true;
        assertEquals("hardLifecycleExitPending",
                f.failures(IslandPlayerTurnSession.Phase.MAIN_HANDOFF));
    }

    @Test
    void identityLegsStillBreakTheSoftComposition() throws Exception {
        Fixture f = new Fixture();
        f.state.lifecycleInvalidated = true;
        f.state.auditedLifecycleMutationPending = true;
        f.state.listenerGate = null;
        f.state.trackingPublication.retire(f.tracker);
        assertEquals("listenerGate trackingPublication(retired)",
                f.failures(IslandPlayerTurnSession.Phase.MAIN_HANDOFF));
    }

    private static final class Fixture {
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final IslandRegistry.PIsland island = new IslandRegistry.PIsland(907L, 0L);
        final ServerPlayer player;
        final ServerGamePacketListenerImpl listener;
        final IslandPlayerTurnSession.OwnerStamp owner;
        final IslandTrackedEntityAccess tracker = (IslandTrackedEntityAccess) Proxy.newProxyInstance(
                IslandTrackedEntityAccess.class.getClassLoader(),
                new Class<?>[]{IslandTrackedEntityAccess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "tracker";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Fixture() throws Exception {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            player = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
            listener = (ServerGamePacketListenerImpl) unsafe.allocateInstance(
                    ServerGamePacketListenerImpl.class);
            listener.player = player;
            state.listenerGate = listener;
            owner = new IslandPlayerTurnSession.OwnerStamp(player, listener,
                    island, island.id, island.gen, new EpochDomain(), 0L, 1L,
                    IslandPlayerTurnSession.PlayerKind.REAL);
            state.trackingPublication.admit(tracker, null, owner, island);
        }

        String failures(IslandPlayerTurnSession.Phase phase) throws Exception {
            Method check = AsyncIslandPlayerTicks.class.getDeclaredMethod(
                    "topologyGapBindingFailuresLocked",
                    AsyncIslandPlayerTicks.PlayerState.class, ServerPlayer.class,
                    ServerGamePacketListenerImpl.class, IslandPlayerTurnSession.OwnerStamp.class,
                    IslandPlayerTurnSession.PlayerKind.class, IslandTrackedEntityAccess.class,
                    IslandPlayerTurnSession.Phase.class);
            check.setAccessible(true);
            synchronized (state) {
                try {
                    return (String) check.invoke(null, state, player, listener, owner,
                            IslandPlayerTurnSession.PlayerKind.REAL, tracker, phase);
                } catch (InvocationTargetException e) {
                    throw (Exception) e.getCause();
                }
            }
        }
    }
}
