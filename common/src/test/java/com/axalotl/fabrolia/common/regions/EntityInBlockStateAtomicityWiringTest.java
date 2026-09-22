package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.utils.MixinCanceller;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EntityInBlockStateAtomicityWiringTest {

    private static final String ENTITY_MIXIN =
            "com/axalotl/fabrolia/common/mixin/entity/EntityInBlockStateMixin";
    private static final String LITHIUM_COMPAT_MIXIN =
            "com/axalotl/fabrolia/common/mixin/lithium/FabroliaLithiumInBlockStateMixin";
    private static final String LITHIUM_UPSTREAM_MIXIN =
            "net.caffeinemc.mods.lithium.mixin.entity.collisions.unpushable_cramming.EntityMixin";

    @Test
    void cacheMissLookupRunsBetweenCriticalSectionsAndPublicationIsRevisionGuarded()
            throws Exception {
        ClassNode owner = readClass(ENTITY_MIXIN);
        MethodNode getter = method(owner, "getInBlockState");

        List<Integer> monitorEnters = opcodeIndices(getter, Opcodes.MONITORENTER);
        List<Integer> monitorExits = opcodeIndices(getter, Opcodes.MONITOREXIT);
        int lookup = callIndex(getter, "net/minecraft/world/level/Level", "getBlockState");
        int publish = fieldIndex(getter, Opcodes.PUTFIELD, "inBlockState");
        int hitRead = fieldIndex(getter, Opcodes.GETFIELD, "inBlockState");

        assertEquals(2, monitorEnters.size(),
                "snapshot and conditional publication need distinct monitors");
        assertTrue(monitorEnters.get(0) < lookup && lookup < monitorEnters.get(1),
                "world lookup must execute after snapshot unlock and before publication lock");
        assertTrue(monitorExits.stream()
                        .anyMatch(index -> monitorEnters.get(0) < index && index < lookup),
                "snapshot monitor must be released before the world lookup");
        assertTrue(monitorEnters.get(0) < hitRead && hitRead < monitorExits.get(0),
                "cache-hit read must remain inside the snapshot monitor");
        assertTrue(publish > monitorEnters.get(1),
                "publication must occur under the second monitor");
        assertTrue(monitorExits.stream().anyMatch(index -> publish < index),
                "publication monitor must be released after the field write");

        assertEquals(1, callCount(getter, "net/minecraft/world/level/Level", "getBlockState"));
        assertEquals(1, callCount(getter, "java/util/Objects", "requireNonNull"));
        assertEquals(2, fieldCount(getter, Opcodes.GETFIELD, "level"),
                "raw level identity must be snapshotted and revalidated under the monitor");
        assertEquals(0, callCount(getter, owner.name, "level"),
                "cache monitor must not invoke an overridable level getter");
        assertEquals(2, fieldCount(
                getter, Opcodes.GETFIELD, "fabrolia$inBlockStateRevision"));
        assertEquals(0, fieldCount(
                getter, Opcodes.PUTFIELD, "fabrolia$inBlockStateRevision"));
        assertEquals(2, fieldCount(getter, Opcodes.GETFIELD, "inBlockState"));
        assertEquals(1, fieldCount(getter, Opcodes.PUTFIELD, "inBlockState"));

        assertEquals(0, fieldCount(getter, Opcodes.GETSTATIC, "AIR"),
                "cache protocol must never synthesize a fallback block state");
        assertEquals(0, callCount(getter,
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable",
                "setReturnValue"),
                "the hot getter must not allocate a cancellable injection callback");
        assertEquals(0, callCount(getter,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"),
                "the world lookup must stay at the exact vanilla invoke site");
        assertEquals(0, opcodeCount(getter, Opcodes.ANEWARRAY));
    }

    @Test
    void everyInvalidationWriterAdvancesRevisionInsideOneMonitor() throws Exception {
        ClassNode owner = readClass(ENTITY_MIXIN);
        MethodNode movement = method(owner, "fabrolia$publishBlockPositionAndInvalidate");
        MethodNode levelChange = method(owner, "fabrolia$publishLevelAndInvalidate");
        MethodNode relocated = method(owner, "fabrolia$consumeRelocatedSetPosInvalidation");
        MethodNode baseInvalidation =
                method(owner, "fabrolia$serializeBaseTickInBlockStateInvalidation");

        assertSerialWriter(movement);
        assertSerialWriter(levelChange);
        assertEquals(1, fieldCount(levelChange, Opcodes.PUTFIELD, "level"));
        assertEquals(0, fieldCount(levelChange, Opcodes.PUTFIELD, "blockPosition"));
        assertEquals(1, fieldCount(movement, Opcodes.PUTFIELD, "blockPosition"));
        assertSerialWriter(baseInvalidation);
        assertEquals(0, fieldCount(baseInvalidation, Opcodes.PUTFIELD, "blockPosition"));

        assertEquals(0, opcodeCount(relocated, Opcodes.MONITORENTER));
        assertEquals(0, fieldCount(relocated, Opcodes.PUTFIELD, "inBlockState"),
                "setPosRaw's trailing null store must be consumed after atomic publication");
        assertEquals(0, fieldCount(
                relocated, Opcodes.PUTFIELD, "fabrolia$inBlockStateRevision"));
        assertEquals(1, callCount(
                relocated, "java/lang/IllegalStateException", "<init>"),
                "vanilla setPosRaw invalidation must remain null-only");

        for (MethodNode method :
                new MethodNode[]{movement, levelChange, relocated, baseInvalidation}) {
            assertEquals(0, callCount(method,
                    "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"),
                    method.name + " must not route through varargs Operation.call");
            assertEquals(0, opcodeCount(method, Opcodes.ANEWARRAY),
                    method.name + " must not allocate a hot-path argument array");
            assertEquals(0, fieldCount(method, Opcodes.GETSTATIC, "AIR"));
        }
    }

    @Test
    void lithiumObserverIsPinnedToAConservativeNullState() throws Exception {
        MethodNode hook = method(
                readClass(LITHIUM_COMPAT_MIXIN), "fabrolia$conservativeLithiumCacheState");

        AnnotationNode target = annotation(
                hook, "Lcom/bawnorton/mixinsquared/TargetHandler;");
        assertNotNull(target);
        assertEquals(LITHIUM_UPSTREAM_MIXIN, value(target, "mixin"));
        assertEquals("onBlockCached", value(target, "name"));
        assertEquals("handler", value(target, "prefix"));

        AnnotationNode modify = annotation(
                hook, "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
        assertNotNull(modify);
        assertSelector(modify, "@MixinSquared:Handler");
        assertEquals(1, value(modify, "require"));
        assertEquals(1, value(modify, "allow"));
        assertEquals(Boolean.FALSE, value(modify, "remap"));

        AnnotationNode at = firstAnnotation(value(modify, "at"));
        assertEquals("FIELD", value(at, "value"));
        assertEquals("Lnet/minecraft/world/entity/Entity;inBlockState:"
                        + "Lnet/minecraft/world/level/block/state/BlockState;",
                value(at, "target"));
        assertEquals(Opcodes.GETFIELD, value(at, "opcode"));
        assertEquals(1, opcodeCount(hook, Opcodes.ACONST_NULL));
        assertEquals(0, fieldCount(hook, Opcodes.GETFIELD, "inBlockState"),
                "compatibility hook must not re-read the racing cache field");

        MethodNode feetHook = method(
                readClass(LITHIUM_COMPAT_MIXIN),
                "fabrolia$conservativeLithiumFeetBlockState");
        AnnotationNode modifyReturn = annotation(
                feetHook, "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
        assertNotNull(modifyReturn);
        assertSelector(modifyReturn, "lithium$getCachedFeetBlockState()"
                + "Lnet/minecraft/world/level/block/state/BlockState;");
        assertEquals(1, value(modifyReturn, "require"));
        assertEquals(Boolean.FALSE, value(modifyReturn, "remap"));
        assertEquals("RETURN", value(firstAnnotation(value(modifyReturn, "at")), "value"));
        assertEquals(1, opcodeCount(feetHook, Opcodes.ACONST_NULL));
        assertEquals(1, opcodeCount(feetHook, Opcodes.ARETURN));
        assertEquals(0, fieldCount(feetHook, Opcodes.GETFIELD, "inBlockState"),
                "feet-cache compatibility hook must discard rather than reread stale state");

        String compatibilityClass = LITHIUM_COMPAT_MIXIN.replace('/', '.');
        MixinCanceller withoutLithium = new MixinCanceller();
        assertTrue(withoutLithium.shouldCancel(List.of(), compatibilityClass));
        MixinCanceller withLithium = new MixinCanceller();
        assertFalse(withLithium.shouldCancel(List.of(), LITHIUM_UPSTREAM_MIXIN));
        assertFalse(withLithium.shouldCancel(List.of(), compatibilityClass));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream in = EntityInBlockStateAtomicityWiringTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        ArrayList<MethodNode> matches = new ArrayList<>();
        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(name)) matches.add(candidate);
        }
        if (matches.size() != 1) {
            fail(node.name + "." + name + " expected one method, found " + matches.size());
        }
        return matches.getFirst();
    }
    private static void assertSerialWriter(MethodNode method) {
        assertEquals(1, fieldCount(method, Opcodes.GETFIELD, "fabrolia$inBlockStateLock"),
                method.name + " must acquire the exact shared cache monitor");
        assertEquals(1, opcodeCount(method, Opcodes.MONITORENTER),
                method.name + " lost monitor entry");
        assertEquals(2, opcodeCount(method, Opcodes.MONITOREXIT),
                method.name + " must release on normal and exceptional exits");
        assertEquals(1, fieldCount(method, Opcodes.PUTFIELD, "inBlockState"));
        assertEquals(1, fieldCount(
                method, Opcodes.GETFIELD, "fabrolia$inBlockStateRevision"));
        assertEquals(1, fieldCount(
                method, Opcodes.PUTFIELD, "fabrolia$inBlockStateRevision"));
    }


    private static int fieldCount(MethodNode method, int opcode, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && field.name.equals(name)) count++;
        }
        return count;
    }

    private static int fieldIndex(MethodNode method, int opcode, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && field.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static List<Integer> opcodeIndices(MethodNode method, int opcode) {
        ArrayList<Integer> indices = new ArrayList<>();
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) indices.add(index);
            index++;
        }
        return indices;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        AnnotationNode found = annotation(method.visibleAnnotations, desc);
        return found != null ? found : annotation(method.invisibleAnnotations, desc);
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(desc)) return annotation;
            }
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    private static AnnotationNode firstAnnotation(Object value) {
        if (value instanceof AnnotationNode annotation) return annotation;
        return (AnnotationNode) ((List<?>) value).getFirst();
    }

    private static void assertSelector(AnnotationNode annotation, String expected) {
        Object selector = value(annotation, "method");
        if (selector instanceof List<?> selectors) {
            assertEquals(List.of(expected), selectors);
        } else {
            assertEquals(expected, selector);
        }
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }
}
