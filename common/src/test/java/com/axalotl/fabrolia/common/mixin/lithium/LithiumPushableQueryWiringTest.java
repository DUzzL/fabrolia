package com.axalotl.fabrolia.common.mixin.lithium;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class LithiumPushableQueryWiringTest {

    private static final String LEVEL_WRAPPER =
            "com/axalotl/fabrolia/common/mixin/entity/movement/LevelPushableEntitiesMixin";
    private static final String LITHIUM_LEVEL =
            "com/axalotl/fabrolia/common/mixin/lithium/LithiumServerLevel";
    private static final String QUERY =
            "com/axalotl/fabrolia/common/parallelised/utils/FabroliaPushableEntityQuery";
    private static final String WORLD_HELPER =
            "net/caffeinemc/mods/lithium/common/world/WorldHelper";
    private static final String PUSHABLE_GROUP =
            "net/caffeinemc/mods/lithium/common/entity/pushable/PushableEntityClassGroup";

    @Test
    void alwaysLoadedLevelWrapperUsesAnOptionalDuckAndKeepsItsGenericFallback()
            throws Exception {
        MethodNode method = method(readClass(LEVEL_WRAPPER), "fabrolia$getPushableEntities");

        assertEquals(1, callCount(method, QUERY, "fabrolia$getPushableEntities"));
        assertEquals(1, callCount(method, "net/minecraft/world/level/Level", "getEntities"));
        assertEquals(0, callCount(method, WORLD_HELPER, null),
                "the always-loaded mixin must not link an optional Lithium class");
    }

    @Test
    void LithiumDuckQueriesTheConcurrentMaybePushableClassGroup() throws Exception {
        MethodNode method = method(readClass(LITHIUM_LEVEL), "fabrolia$getPushableEntities");
        int group = fieldIndex(method, PUSHABLE_GROUP, "MAYBE_PUSHABLE");
        int query = callIndex(method, WORLD_HELPER,
                "getEntitiesOfEntityGroupWithoutDragonPieces");

        assertTrue(group >= 0 && query > group,
                "the exact per-instance predicate must run over the concurrent class-group subset");
    }

    @Test
    void firstGroupBuildSerializesWithMutationsButEstablishedReadsStayLockFree()
            throws Exception {
        ClassNode node = readClass(
                "com/axalotl/fabrolia/common/mixin/lithium/"
                        + "FabroliaLithiumClassGroupFilterableListMixin");
        MethodNode query = method(node, "lithium$getAllOfGroupType");
        int firstGet = callIndex(query, "java/util/Map", "get");
        int monitor = opcodeIndex(query, Opcodes.MONITORENTER);

        assertTrue(firstGet >= 0 && monitor > firstGet,
                "an already-published class-group set must return before initialization locking");
        assertEquals(1, opcodeCount(method(node, "fabrolia$onAdd"), Opcodes.MONITORENTER));
        assertEquals(1, opcodeCount(method(node, "fabrolia$onRemove"), Opcodes.MONITORENTER));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream in = LithiumPushableQueryWiringTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(candidate -> candidate.name.equals(name))
                .findFirst().orElseGet(() -> {
                    fail(node.name + "." + name);
                    return null;
                });
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && (name == null || call.name.equals(name))) {
                count++;
            }
        }
        return count;
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

    private static int fieldIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.owner.equals(owner) && field.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int opcodeIndex(MethodNode method, int opcode) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) return index;
            index++;
        }
        return -1;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }
}
