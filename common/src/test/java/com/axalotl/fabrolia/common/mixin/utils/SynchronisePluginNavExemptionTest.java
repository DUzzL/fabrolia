package com.axalotl.fabrolia.common.mixin.utils;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #32 regression pin, run against the REAL {@link SynchronisePlugin} and
 * the REAL vanilla {@code PathNavigation} bytecode from the test classpath.
 *
 * <p>The plugin's SyncAllMixin pass runs in {@code postApply} — AFTER
 * FabroliaPathNavigationMixin merged {@code fabrolia$navMob} /
 * {@code fabrolia$shouldRecomputePathSafe} into the target — so without the
 * exemption the blanket ACC_SYNCHRONIZED landed on the mod's own monitor-free
 * accessors. That turned the {@code sendBlockUpdated} nav scan into a BLOCKING
 * monitor entry and closed the production deadlock cycle (main parked on a
 * nav monitor held by a worker parked in the chunk main-bridge). If someone
 * reverts the exemption in {@code SynchronisePlugin.onLoad}, this test fails.
 */
class SynchronisePluginNavExemptionTest {

    private static final String TARGET = "net.minecraft.world.entity.ai.navigation.PathNavigation";
    private static final String SYNC_ALL_MIXIN =
            "com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin";

    private static ClassNode loadPathNavigationWithMergedFabroliaMethods() throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = SynchronisePluginNavExemptionTest.class.getResourceAsStream(
                "/net/minecraft/world/entity/ai/navigation/PathNavigation.class")) {
            assertNotNull(in, "vanilla PathNavigation bytecode must be on the test classpath");
            new ClassReader(in).accept(node, 0);
        }
        // Simulate the post-merge state: the mixin's members are ordinary
        // public methods on the target by the time postApply runs.
        node.methods.add(stub("fabrolia$navMob", "()Lnet/minecraft/world/entity/Mob;"));
        node.methods.add(stub("fabrolia$shouldRecomputePathSafe", "(Lnet/minecraft/core/BlockPos;)Z"));
        return node;
    }

    private static MethodNode stub(String name, String desc) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        InsnList insns = new InsnList();
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new InsnNode(Opcodes.ARETURN));
        m.instructions = insns;
        return m;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name + " on PathNavigation"));
    }

    private static boolean isSynchronized(MethodNode m) {
        return (m.access & Opcodes.ACC_SYNCHRONIZED) != 0;
    }

    @Test
    void blanketSyncStillApplies_ButFabroliaNavAccessorsStayMonitorFree() throws IOException {
        ClassNode node = loadPathNavigationWithMergedFabroliaMethods();

        SynchronisePlugin plugin = new SynchronisePlugin();
        plugin.onLoad("com.axalotl.fabrolia.common.mixin");
        plugin.postApply(TARGET, node, SYNC_ALL_MIXIN, null);

        // The protective blanket must NOT silently vanish: the owner-side
        // entry points stay serialized on the nav monitor.
        assertTrue(isSynchronized(method(node, "tick")),
                "PathNavigation.tick must keep the sync bit (owner-side serialization)");
        assertTrue(isSynchronized(method(node, "recomputePath")),
                "PathNavigation.recomputePath must keep the sync bit");
        assertTrue(isSynchronized(method(node, "shouldRecomputePath")),
                "vanilla shouldRecomputePath keeps the sync bit (non-scan callers)");

        // The task #32 exemption: the scan accessors are monitor-free BY
        // DESIGN — a sync bit here re-arms the production deadlock (main
        // blocked on a nav monitor held by a bridge-parked worker).
        assertTrue(!isSynchronized(method(node, "fabrolia$navMob")),
                "fabrolia$navMob must stay monitor-free — the sendBlockUpdated scan "
                        + "calls it from main/other islands' workers while the owner may "
                        + "hold the monitor across a chunk main-bridge (task #32 deadlock)");
        assertTrue(!isSynchronized(method(node, "fabrolia$shouldRecomputePathSafe")),
                "fabrolia$shouldRecomputePathSafe must stay monitor-free (task #32)");
    }
}
