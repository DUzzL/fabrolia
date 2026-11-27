package com.axalotl.fabrolia.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProximityRosterReentryTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static ServerPlayer player() throws ReflectiveOperationException {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (ServerPlayer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ServerPlayer.class);
    }

    private static final class Roster extends PlayerMapMixin {
        final ReferenceLinkedOpenHashSet<ServerPlayer> members = new ReferenceLinkedOpenHashSet<>();
        Runnable afterCapture;

        @Override public Set<ServerPlayer> getAllPlayers() {
            var copy = members.clone();
            if (afterCapture != null) {
                var action = afterCapture;
                afterCapture = null;
                action.run();
            }
            return copy;
        }

        void add(ServerPlayer player, Operation<Void> mutation) {
            try {
                var handle = MethodHandles.privateLookupIn(PlayerMapMixin.class, MethodHandles.lookup())
                        .findVirtual(PlayerMapMixin.class, "fabrolia$lockedAddPlayer",
                                MethodType.methodType(void.class, ServerPlayer.class, boolean.class, Operation.class));
                handle.invokeExact((PlayerMapMixin) this, player, false, mutation);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        }
    }

    private static void expect(Iterator<ServerPlayer> cursor, ServerPlayer... expected) {
        var found = new ArrayList<ServerPlayer>();
        cursor.forEachRemaining(found::add);
        assertEquals(expected.length, found.size());
        for (int i = 0; i < expected.length; i++) assertSame(expected[i], found.get(i));
    }

    @Test void nestedReadersSeeTheCurrentPointInAFailingMembershipChange() throws Exception {
        var map = new Roster();
        var old = player();
        var next = player();
        map.members.add(old);
        var retained = map.fabrolia$proximityIterator();
        var failure = new IllegalStateException("after insertion");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> map.add(next, args -> {
            expect(map.fabrolia$proximityIterator(), old);
            map.members.clear();
            map.members.add(next);
            expect(map.fabrolia$proximityIterator(), next);
            throw failure;
        })));
        expect(retained, old);
        expect(map.fabrolia$proximityIterator(), next);
    }

    @Test void olderColdReaderCannotPublishOverAReentrantWrite() throws Exception {
        var map = new Roster();
        var first = player();
        var second = player();
        map.members.add(first);
        map.afterCapture = () -> map.add(second, args -> {
            map.members.add(second);
            return null;
        });
        expect(map.fabrolia$proximityIterator(), first);
        expect(map.fabrolia$proximityIterator(), first, second);
    }
}
