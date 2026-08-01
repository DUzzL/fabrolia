package com.axalotl.fabrolia.common.mixin.utils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class SynchronisePlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(SynchronisePlugin.class);

    // 0x1548 = SYNTHETIC(0x1000) | ABSTRACT(0x400) | NATIVE(0x100) | BRIDGE(0x40) | STATIC(0x8).
    // The constant's old name claimed it excluded final/static/private/abstract
    // — a lie that cost real debugging time (task #38): PRIVATE (0x2) and FINAL
    // (0x10) are NOT in this mask, so any fabrolia$/lithium$/c2me$ method merged
    // onto a class in the blanket set silently inherits ACC_SYNCHRONIZED too —
    // a feature for coverage, but a trap for handler methods that must stay
    // monitor-free (see mixin2MethodsExcludeMap and the task-#32 nav exemptions).
    private static final int SYNTHETIC_ABSTRACT_NATIVE_BRIDGE_STATIC = 0x1548;
    private static final int SYNCHRONIZED = 0x20; // synchronized
    private final Multimap<String, String> mixin2MethodsMap = ArrayListMultimap.create();
    private final Multimap<String, String> mixin2MethodsExcludeMap = ArrayListMultimap.create();
    private final TreeSet<String> syncAllSet = new TreeSet<>();

    private static final Map<String, String> CONCURRENT_REPLACEMENTS = Map.of(
            "it/unimi/dsi/fastutil/objects/Reference2ReferenceOpenHashMap",
            "com/axalotl/fabrolia/api/fastutil/Reference2ReferenceConcurrentHashMap",
            "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap",
            "com/axalotl/fabrolia/api/fastutil/Int2ObjectConcurrentHashMap",
            "it/unimi/dsi/fastutil/objects/Reference2ByteOpenHashMap",
            "com/axalotl/fabrolia/api/fastutil/Reference2ByteConcurrentHashMap"
    );

    private static final Set<String> CONCURRENT_OWN_CLASSES_DOTTED;
    static {
        Set<String> s = new HashSet<>();
        for (String v : CONCURRENT_REPLACEMENTS.values()) s.add(v.replace('/', '.'));
        CONCURRENT_OWN_CLASSES_DOTTED = Set.copyOf(s);
    }

    @Override
    public void onLoad(String mixinPackage) {
        // Exclusions match by PLAIN method name (postApply compares method.name).
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "isOrAfter");
        // Task #32 (2026-07-28 production watchdog deadlock): postApply runs after
        // FabroliaPathNavigationMixin merged its members, so the blanket bit also
        // landed on the FabroliaSafeNavigation accessors — turning the
        // sendBlockUpdated nav scan (main/any worker) into a BLOCKING entry on the
        // nav monitor a region worker holds for its whole synchronized tick(),
        // including the getGroundY→getChunk main-thread bridge. Cycle: main parked
        // on the monitor cannot pump mainThreadProcessor; the worker parked on the
        // chunk future never releases the monitor. These two are monitor-free BY
        // DESIGN: fabrolia$navMob returns the @Final mob field; fabrolia$
        // shouldRecomputePathSafe reads a local Path snapshot with defensive
        // bounds/null checks and tolerates stale values (worst case = one missed
        // or spurious recompute request, vanilla's delayed-recompute class).
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "fabrolia$navMob");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "fabrolia$shouldRecomputePathSafe");
        syncAllSet.add("com.axalotl.fabrolia.common.mixin.utils.FastUtilSynchronizeMixin");
        syncAllSet.add("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        Collection<String> targetMethods = mixin2MethodsMap.get(mixinClassName);
        Collection<String> excludedMethods = mixin2MethodsExcludeMap.get(mixinClassName);

        if (!targetMethods.isEmpty()) {
            applySynchronizeBit(targetClass, targetMethods, targetClassName);
        } else if (syncAllSet.contains(mixinClassName)) {
            for (MethodNode method : targetClass.methods) {
                if ((method.access & SYNTHETIC_ABSTRACT_NATIVE_BRIDGE_STATIC) == 0 && !method.name.equals("<init>") && !excludedMethods.contains(method.name)) {
                    method.access |= SYNCHRONIZED;
                    logSynchronize(method.name, targetClassName, mixinClassName);
                }
            }
        }
        rewriteFastutilInstantiations(targetClassName, targetClass);
    }

    private void applySynchronizeBit(ClassNode targetClass, Collection<String> targetMethods, String targetClassName) {
        for (MethodNode method : targetClass.methods) {
            for (String targetMethod : targetMethods) {
                if (method.name.equals(targetMethod)) {
                    method.access |= SYNCHRONIZED;
                    logSynchronize(method.name, targetClassName, null);
                }
            }
        }
    }

    private void rewriteFastutilInstantiations(String targetClassName, ClassNode cls) {
        if (CONCURRENT_OWN_CLASSES_DOTTED.contains(targetClassName)) return;

        Map<String, Integer> replacedByClass = null;
        for (MethodNode m : cls.methods) {
            if (m.instructions == null) continue;
            Map<String, Deque<TypeInsnNode>> pendingByOwner = new HashMap<>();
            AbstractInsnNode insn = m.instructions.getFirst();
            while (insn != null) {
                if (insn instanceof TypeInsnNode t
                        && t.getOpcode() == Opcodes.NEW
                        && CONCURRENT_REPLACEMENTS.containsKey(t.desc)) {
                    pendingByOwner.computeIfAbsent(t.desc, k -> new ArrayDeque<>()).push(t);
                } else if (insn instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(mi.name)
                        && CONCURRENT_REPLACEMENTS.containsKey(mi.owner)) {
                    Deque<TypeInsnNode> stack = pendingByOwner.get(mi.owner);
                    if (stack != null && !stack.isEmpty()) {
                        TypeInsnNode pending = stack.pop();
                        String replacement = CONCURRENT_REPLACEMENTS.get(mi.owner);
                        pending.desc = replacement;
                        mi.owner = replacement;
                        if (replacedByClass == null) replacedByClass = new HashMap<>();
                        replacedByClass.merge(mi.owner, 1, Integer::sum);
                    }
                }
                insn = insn.getNext();
            }
        }
        if (replacedByClass != null) {
            for (Map.Entry<String, Integer> e : replacedByClass.entrySet()) {
                String simple = e.getKey().substring(e.getKey().lastIndexOf('/') + 1);
                LOGGER.info("Rewrote {} fastutil instantiation(s) -> {} in {}",
                        e.getValue(), simple, targetClassName);
            }
        }
    }

    private void logSynchronize(String methodName, String targetClassName, String mixinClassName) {
        if (mixinClassName == null || !mixinClassName.equals("com.axalotl.fabrolia.mixin.utils.FastUtilSynchronizeMixin")) {
            String message = "Setting synchronize bit for " + methodName + " in " + targetClassName + ".";
            LOGGER.debug(message);
        }
    }
}