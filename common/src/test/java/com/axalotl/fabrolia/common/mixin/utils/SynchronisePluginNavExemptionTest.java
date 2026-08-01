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
        // Task #40: the routed-stop @Inject handler is merged under a Mixin-mangled
        // name (handler$<tag>$<originalName>) — simulate it too; whether Mixin marks
        // it synthetic is an implementation detail the plugin must not depend on.
        node.methods.add(stub("handler$abc123$fabrolia$routedStop",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"));
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

    /**
     * Task #40 (audit finding #2): the stale-tolerant reads and the owner-routed
     * stop are monitor-free BY DESIGN — each re-armed a main-side blocking entry
     * on a nav monitor a worker can hold across an unbounded ChunkBridgeWait
     * (debug ENTITY_PATHS poll iterates every tracked mob's getPath /
     * getMaxDistanceToWaypoint from MAIN; TeleportCommand / PiglinAi.stopWalking /
     * randomTeleport reach stop from MAIN). Reverting any exemption in
     * SynchronisePlugin.onLoad fails here.
     */
    @Test
    void staleTolerantReadsAndRoutedStopStayMonitorFree() throws IOException {
        ClassNode node = loadPathNavigationWithMergedFabroliaMethods();

        SynchronisePlugin plugin = new SynchronisePlugin();
        plugin.onLoad("com.axalotl.fabrolia.common.mixin");
        plugin.postApply(TARGET, node, SYNC_ALL_MIXIN, null);

        for (String read : new String[]{
                "getPath", "isDone", "isInProgress", "isStuck", "getTargetPos",
                "getMaxDistanceToWaypoint", "canFloat", "getNodeEvaluator"}) {
            assertTrue(!isSynchronized(method(node, read)),
                    read + " is a stale-tolerant read and must stay monitor-free "
                            + "(task #40 — cross-context polls must never block on a "
                            + "monitor held across the chunk bridge)");
        }

        assertTrue(!isSynchronized(method(node, "stop")),
                "stop is owner-routed (fabrolia$routedStop) and must stay "
                        + "monitor-free — a sync bit here re-arms the task-#32 cycle "
                        + "for the main-side doors (TeleportCommand et al.)");
        assertTrue(!isSynchronized(method(node, "handler$abc123$fabrolia$routedStop")),
                "the Mixin-mangled routed-stop handler must be matched by the "
                        + "'$<name>' suffix rule — if it carries the monitor, the "
                        + "cross-context guard itself blocks and the exemption is void");

        // The mutators keep the owner-side serialization — the exemptions must
        // not overreach.
        for (String mutator : new String[]{
                "moveTo", "createPath", "trimPath", "followThePath", "doStuckDetection"}) {
            assertTrue(isSynchronized(method(node, mutator)),
                    mutator + " must keep the sync bit (owner-side serialization)");
        }
    }

    /**
     * The skip mask (0x1548) does NOT exclude final or private methods — that is
     * load-bearing, not an accident: {@code DynamicGraphMinFixedPoint.runUpdates}
     * is {@code protected final} and is the fixpoint driver the whole monitor
     * exists for (and the monitor the POI DistanceTracker's 7e769ee deferral
     * drain relies on). The constant's 2026-08-01 rename (it was misnamed
     * FINAL_STATIC_PRIVATE_ABSTRACT) must never come with a mask "fix".
     */
    @Test
    void blanketStillCoversFinalAndPrivateMethods() throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = SynchronisePluginNavExemptionTest.class.getResourceAsStream(
                "/net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint.class")) {
            assertNotNull(in, "vanilla DynamicGraphMinFixedPoint bytecode must be on the test classpath");
            new ClassReader(in).accept(node, 0);
        }

        SynchronisePlugin plugin = new SynchronisePlugin();
        plugin.onLoad("com.axalotl.fabrolia.common.mixin");
        plugin.postApply("net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint",
                node, SYNC_ALL_MIXIN, null);

        MethodNode runUpdates = method(node, "runUpdates");
        assertTrue((runUpdates.access & Opcodes.ACC_FINAL) != 0,
                "precondition: runUpdates is final in vanilla 26.2");
        assertTrue(isSynchronized(runUpdates),
                "protected final runUpdates must receive the blanket bit — the mask "
                        + "must not skip ACC_FINAL");
        assertTrue(isSynchronized(method(node, "calculatePriority")),
                "private calculatePriority must receive the blanket bit — the mask "
                        + "must not skip ACC_PRIVATE");
    }
}
