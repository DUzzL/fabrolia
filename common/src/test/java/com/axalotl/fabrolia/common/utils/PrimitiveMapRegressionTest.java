package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.fastutil.Long2ByteConcurrentHashMap;
import com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap;
import com.axalotl.fabrolia.api.fastutil.Long2LongConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class PrimitiveMapRegressionTest {
    private static final ThreadLocal<Pause> PAUSE = new ThreadLocal<>();

    private static final class Pause {
        final CountDownLatch[] reached = {new CountDownLatch(1), new CountDownLatch(1)};
        final CountDownLatch[] proceed = {new CountDownLatch(1), new CountDownLatch(1)};
        final boolean[] used = new boolean[2];
    }

    /** Invoked by the instrumented real map, with its original operand stack intact. */
    public static void pause(int phase) {
        Pause pause = PAUSE.get();
        if (pause == null || pause.used[phase]) return;
        pause.used[phase] = true;
        pause.reached[phase].countDown();
        try {
            if (!pause.proceed[phase].await(10, TimeUnit.SECONDS)) throw new AssertionError("Reader not resumed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Only inserts two scheduling hooks; executes the production lookup/writer code. */
    private static final class PausingLoader extends ClassLoader {
        PausingLoader() { super(PrimitiveMapRegressionTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.axalotl.fabrolia.api.fastutil.Long2")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> existing = findLoadedClass(name);
                if (existing == null) {
                    String baseline = System.getProperty("fabrolia.mapRegressionBaselineJar");
                    try (var jar = baseline == null ? null : new java.util.jar.JarFile(baseline);
                         var input = jar == null ? getParent().getResourceAsStream(name.replace('.', '/') + ".class")
                                 : jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"))) {
                        if (input == null) throw new ClassNotFoundException(name);
                        ClassReader reader = new ClassReader(input.readAllBytes());
                        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                                       String signature, String[] exceptions) {
                                MethodVisitor original = super.visitMethod(access, method, descriptor, signature, exceptions);
                                if (!method.equals("get") && !method.equals("getOrDefault")) return original;
                                return new MethodVisitor(Opcodes.ASM9, original) {
                                    String handle;
                                    @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                                        super.visitFieldInsn(opcode, owner, field, desc);
                                        if (field.endsWith("_VH")) handle = field;
                                    }
                                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                                          String desc, boolean itf) {
                                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                                        if (owner.equals("java/lang/invoke/VarHandle") && method.equals("getAcquire")) {
                                            int phase = "KEYS_VH".equals(handle) ? 0 : "VALS_VH".equals(handle) ? 1 : -1;
                                            if (phase >= 0) {
                                                super.visitLdcInsn(phase);
                                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                                        Type.getInternalName(PrimitiveMapRegressionTest.class),
                                                        "pause", "(I)V", false);
                                            }
                                        }
                                    }
                                };
                            }
                        }, 0);
                        byte[] bytes = writer.toByteArray();
                        existing = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                }
                if (resolve) resolveClass(existing);
                return existing;
            }
        }
    }

    @Test void abaCannotReturnAnotherKeysValue() throws Exception {
        for (String kind : new String[]{"Int", "Long", "Byte"}) {
            for (boolean withDefault : new boolean[]{false, true}) verifyAba(kind, withDefault);
        }
    }

    private static void verifyAba(String kind, boolean withDefault) throws Exception {
        var loader = new PausingLoader();
        Class<?> implementation = loader.loadClass("com.axalotl.fabrolia.api.fastutil.Long2" + kind + "ConcurrentHashMap");
        Object map = implementation.getConstructor(int.class).newInstance(8);
        Class<?> primitive = kind.equals("Long") ? long.class : kind.equals("Byte") ? byte.class : int.class;
        Method put = implementation.getMethod("put", long.class, primitive);
        Method remove = implementation.getMethod("remove", long.class);
        Method get = withDefault ? implementation.getMethod("getOrDefault", long.class, primitive)
                : implementation.getMethod("get", long.class);
        Class<?> intMap = loader.loadClass("com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap");
        var bitsField = intMap.getDeclaredField("NSEG_BITS"); bitsField.setAccessible(true);
        int bits = bitsField.getInt(null);
        long a = 1, b = 2;
        while ((hash(a) >>> (32 - bits)) != (hash(b) >>> (32 - bits)) || (hash(a) & 15) != (hash(b) & 15)) b++;
        put.invoke(map, a, value(kind, 11));
        Pause pause = new Pause();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Long> result = executor.submit(() -> {
            PAUSE.set(pause);
            try {
                return ((Number) (withDefault ? get.invoke(map, a, value(kind, 0)) : get.invoke(map, a))).longValue();
            } finally { PAUSE.remove(); }
        });
        try {
            assertTrue(pause.reached[0].await(10, TimeUnit.SECONDS), kind + ": key not read");
            remove.invoke(map, a); put.invoke(map, b, value(kind, 22));
            pause.proceed[0].countDown();
            assertTrue(pause.reached[1].await(10, TimeUnit.SECONDS), kind + ": value not read");
            remove.invoke(map, b); put.invoke(map, a, value(kind, 11));
            pause.proceed[1].countDown();
            assertEquals(11L, result.get(10, TimeUnit.SECONDS), kind + ": foreign value after A/B/A");
        } finally {
            for (var latch : pause.proceed) latch.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static int hash(long key) { key *= 0xC6BC279692B5C323L; return (int) (key ^ (key >>> 32)); }
    private static Number value(String kind, int value) {
        if (kind.equals("Long")) return Long.valueOf(value);
        if (kind.equals("Byte")) return Byte.valueOf((byte) value);
        return Integer.valueOf(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void mutableViewsAndDefaultValueRemovalMatchMapContract() {
        for (String kind : new String[]{"Int", "Long", "Byte"}) {
            Map<Long, Number> map = (Map) switch (kind) {
                case "Int" -> new Long2IntConcurrentHashMap();
                case "Long" -> new Long2LongConcurrentHashMap();
                default -> new Long2ByteConcurrentHashMap();
            };
            Number zero = value(kind, 0);
            map.put(1L, zero);
            assertTrue(map.remove(1L, zero), kind);
            assertFalse(map.containsKey(1L), kind);
            var keys = map.keySet(); var values = map.values();
            map.put(2L, value(kind, 12));
            assertTrue(keys.contains(2L), kind);
            assertTrue(keys.remove(2L), kind);
            assertTrue(map.isEmpty(), kind);
            map.put(3L, value(kind, 12));
            map.entrySet().iterator().next().setValue(value(kind, 19));
            assertEquals(value(kind, 19), map.get(3L), kind);
            map.replaceAll((key, old) -> value(kind, 21));
            assertEquals(value(kind, 21), map.get(3L), kind);
            var iterator = values.iterator(); iterator.next(); iterator.remove();
            assertTrue(map.isEmpty(), kind);
            map.put(4L, zero); values.clear();
            assertTrue(map.isEmpty(), kind);
        }
    }

    @Test void computeCallbackCanReadMapWithoutWaitingForItsOwnStamp() {
        var map = new Long2IntConcurrentHashMap();
        map.put(1L, 11);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                assertEquals(12, map.compute(1L, (key, old) -> map.get(key.longValue()) + 1)));
    }
}
