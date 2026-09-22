package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRootAdmissionWiringTest {
    private static final String SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String WORKER_POOL =
            "com/axalotl/fabrolia/common/regions/RegionWorkerPool";
    private static final String PLAYER_TICKS =
            "com/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2";
    private static final String REGION =
            "com/axalotl/fabrolia/common/regions/Region";
    private static final String INLINE_HELPER = "runRootInlineIfHealthy";
    private static final String REGION_INLINE_HELPER =
            "runRegionRootInlineIfHealthy";


    @Test
    void authenticatedActorRoutesBothGenericGateShapesThroughPausedCut()
            throws Exception {
        ClassNode scheduler = readClass(SCHEDULER);
        MethodNode voidGate = method(scheduler, "runSuccessBodyIfHealthy",
                "(Ljava/lang/Runnable;)V");
        assertTrue(calls(voidGate, PLAYER_TICKS,
                "exactActorPhysicalOwnerOrNull") >= 1);
        assertEquals(1, calls(voidGate, SCHEDULER,
                "runPausedExactActorBodyIfHealthy"));
        assertCallBefore(voidGate, PLAYER_TICKS, "exactActorPhysicalOwnerOrNull",
                WORKER_POOL, "currentThreadCausalOwnerOrNull");

        MethodNode booleanGate = method(scheduler, "callSuccessBooleanIfHealthy",
                "(Ljava/util/function/BooleanSupplier;)Z");
        assertTrue(calls(booleanGate, PLAYER_TICKS,
                "exactActorPhysicalOwnerOrNull") >= 1);
        assertEquals(1, calls(booleanGate, SCHEDULER,
                "callPausedExactActorBooleanIfHealthy"));
        assertCallBefore(booleanGate, PLAYER_TICKS, "exactActorPhysicalOwnerOrNull",
                WORKER_POOL, "currentThreadCausalOwnerOrNull");

        MethodNode commitGate = method(scheduler, "commitSuccessIfHealthy",
                "(Ljava/lang/Runnable;)V");
        assertEquals(1, calls(commitGate, WORKER_POOL,
                        "currentThreadCausalOwnerOrNull"),
                "a pinned causal commit must bypass both lifecycle monitors");
        assertCallBefore(commitGate, WORKER_POOL, "currentThreadCausalOwnerOrNull",
                SCHEDULER, "failureGateLocked");
    }

    private static void assertInlineCalls(ClassNode owner, String name, String desc,
                                          int expected) {
        MethodNode method = method(owner, name, desc);
        assertEquals(expected, calls(method, SCHEDULER, INLINE_HELPER),
                name + desc + " lost an inline root admission");
        assertEquals(0, calls(method, "java/lang/Runnable", "run"),
                name + desc + " contains a check-then-raw-body W race");
    }

    private static void assertRegionInlineCall(
            ClassNode owner, String name, String desc) {
        MethodNode method = method(owner, name, desc);
        assertEquals(1, calls(method, SCHEDULER, REGION_INLINE_HELPER),
                name + desc + " lost its domain-rebase body receipt");
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static void assertCallBefore(MethodNode method,
                                         String firstOwner, String firstName,
                                         String secondOwner, String secondName) {
        int first = callIndex(method, firstOwner, firstName);
        int second = callIndex(method, secondOwner, secondName);
        assertTrue(first >= 0, method.name + " lost " + firstName);
        assertTrue(second >= 0, method.name + " lost " + secondName);
        assertTrue(first < second,
                method.name + " must retain exact-actor priority over causal fast-path");
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + desc));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = InlineRootAdmissionWiringTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(input, internalName);
            ClassNode node = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(node, 0);
            return node;
        }
    }
}
