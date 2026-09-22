package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MC-free regression coverage for region snapshot allocation and semantics.
 *
 * <p>The former path materialised a CHM values array, copied it into an
 * {@code ArrayList}, copied that into an {@code ObjectArrayList}, and then
 * {@link ThreadedRegionizer#snapshot()} copied it once more. The bytecode pin
 * deliberately complements the behavioural test: timing or heap-allocation
 * counters would be JVM-dependent, while the unwanted {@code addAll/toArray}
 * and second list constructor are exact structural regressions.
 */
class RegionSnapshotAllocationPinTest {

    private static final String MAP =
            "com/axalotl/fabrolia/api/fastutil/Long2ObjectConcurrentHashMap";
    private static final String REGIONIZER =
            "com/axalotl/fabrolia/common/regions/ThreadedRegionizer";
    private static final String OBJECT_ARRAY_LIST =
            "it/unimi/dsi/fastutil/objects/ObjectArrayList";

    @Test
    void valuesSnapshotIsDetachedAndKeepsBackingTraversalOrder() {
        Long2ObjectConcurrentHashMap<String> map = new Long2ObjectConcurrentHashMap<>(128);
        for (long key = -64; key < 64; key++) {
            map.put(key, "value-" + key);
        }

        List<String> encounterOrder = new ArrayList<>();
        map.long2ObjectEntrySet().forEach(entry -> encounterOrder.add(entry.getValue()));

        ObjectArrayList<String> snapshot = map.valuesSnapshot();
        assertEquals(encounterOrder, snapshot,
                "the direct snapshot must retain ConcurrentHashMap traversal order");

        ObjectCollection<String> publicSnapshot = map.values();
        List<String> publicOrder = new ArrayList<>();
        publicSnapshot.forEach(publicOrder::add);
        assertEquals(encounterOrder, publicOrder,
                "values() must retain its detached-snapshot contract");

        map.remove(-64L);
        map.put(1_000L, "late-map-value");
        assertEquals(encounterOrder, snapshot,
                "later map mutations must not alter an existing snapshot");
        assertEquals(encounterOrder, new ArrayList<>(publicSnapshot),
                "the interface-returned snapshot must be detached too");

        snapshot.set(0, "snapshot-only-value");
        assertFalse(map.containsValue("snapshot-only-value"),
                "the returned list must not write through to the map");
    }

    @Test
    void oneDirectValuesListReplacesTheFormerCopyChain() throws IOException {
        MethodNode builder = method(readClass(MAP), "valuesSnapshot");
        assertEquals(List.of(OBJECT_ARRAY_LIST), allocatedTypes(builder),
                "valuesSnapshot must allocate only its returned list explicitly");
        assertEquals(1, callCount(builder, OBJECT_ARRAY_LIST, "add"),
                "the backing CHM values must be appended directly to the returned list");
        assertEquals(0, callCount(builder, null, "addAll"),
                "addAll(Collection) recreates the intermediate CHM values array");
        assertEquals(0, callCount(builder, null, "toArray"),
                "valuesSnapshot must not materialise an intermediate values array");

        MethodNode values = method(readClass(MAP), "values",
                "()Lit/unimi/dsi/fastutil/objects/ObjectCollection;");
        assertEquals(List.of(), allocatedTypes(values),
                "the public values() adapter must not copy the concrete snapshot");
        assertEquals(1, callCount(values, MAP, "valuesSnapshot"));
    }

    @Test
    void regionizerReturnsTheConcreteSnapshotWithoutAnotherList() throws IOException {
        MethodNode snapshot = method(readClass(REGIONIZER), "snapshot");
        assertEquals(List.of(), allocatedTypes(snapshot),
                "ThreadedRegionizer.snapshot must not allocate a wrapper list");
        assertEquals(1, callCount(snapshot, MAP, "valuesSnapshot"),
                "the regionizer must return the map's already-detached list directly");
        assertEquals(0, callCount(snapshot, null, "<init>"),
                "a second list constructor restores the hot-path copy");
    }

    private static ClassNode readClass(String internalName) throws IOException {
        String resource = internalName + ".class";
        try (InputStream in = RegionSnapshotAllocationPinTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "compiled class resource missing: " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name) {
        return method(owner, name, null);
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(name)
                        && (descriptor == null || method.desc.equals(descriptor)))
                .toList();
        assertEquals(1, matches.size(), "expected one " + owner.name + '.' + name
                + (descriptor == null ? "" : descriptor));
        return matches.getFirst();
    }

    private static List<String> allocatedTypes(MethodNode method) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.NEW) {
                types.add(((TypeInsnNode) insn).desc);
            }
        }
        return types;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && (owner == null || call.owner.equals(owner))
                    && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }
}
