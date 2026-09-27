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

    /**
     * Access-flag mask of methods the blanket pass SKIPS. {@code 0x1548} decodes to
     * SYNTHETIC (0x1000) | ABSTRACT (0x0400) | NATIVE (0x0100) | BRIDGE (0x0040) |
     * STATIC (0x0008).
     *
     * <p>NOTE what is NOT in the mask: {@code private} (0x0002) and {@code final}
     * (0x0010) methods DO receive the synchronized bit. The constant was
     * historically misnamed FINAL_STATIC_PRIVATE_ABSTRACT, claiming the exact
     * opposite, and that lie cost real debugging time on both the 2026-07-29 POI
     * deadlock (task #38, where {@code private unpackChunk} came out synchronized)
     * and the 2026-08-01 audit.
     *
     * <p>The behaviour itself is load-bearing in both directions. It is a feature
     * for coverage — {@code DynamicGraphMinFixedPoint.runUpdates} is
     * {@code protected final} and is the fixpoint driver the monitor exists for —
     * and a trap for merged handlers: any {@code fabrolia$}/{@code lithium$}/
     * {@code c2me$} method merged onto a class in the blanket set silently
     * inherits ACC_SYNCHRONIZED, including handlers that must stay monitor-free
     * (see {@code mixin2MethodsExcludeMap} and the task-#32 nav exemptions).
     */
    private static final int SKIP_SYNTHETIC_ABSTRACT_NATIVE_BRIDGE_STATIC = 0x1548;
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
        // Exclusions match by PLAIN method name (exact, or a Mixin-renamed injector
        // handler ending in "$<name>" — see isExcluded). TWO FOOTGUNS, both audited
        // 2026-08-01:
        //  1. NAME-GLOBAL: an exclusion applies to EVERY SyncAllMixin target class.
        //     Before adding a name here, sweep every class listed in SyncAllMixin
        //     with javap and confirm the name is unique to the class you mean
        //     (all names below verified unique to PathNavigation on 26.2).
        //  2. ORDER-DEPENDENT: postApply sees the target ClassNode at SyncAllMixin
        //     apply time. Members merged by lower-priority/earlier-declared mixins
        //     (FabroliaPathNavigationMixin) ARE seen and need exclusions; members
        //     merged by higher-priority mixins (LevelChunkTicksMixin, prio 2000)
        //     are NOT seen and silently escape the blanket.
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
        // PathNavigation stale-tolerant READS (task #40, audit finding #2): each is
        // a single field read (or one hop through an immutable-after-ctor field)
        // whose caller tolerates a stale answer by design — the same class as
        // fabrolia$shouldRecomputePathSafe above. Keeping the bit on these turned
        // every cross-context poll (debug ENTITY_PATHS iterates ALL tracked mobs
        // from MAIN via getPath/getMaxDistanceToWaypoint; GoalUtils.mobRestricted
        // calls canNavigateGround ~10x per random-stroll candidate) into a blocking
        // entry on a monitor the owner worker may hold across an UNBOUNDED
        // ChunkBridgeWait — the task-#32 deadlock shape through doors the nav
        // routing did not cover. NOTE getPath returns the live mutable Path: a
        // consumer racing the owner's trimPath/advance can observe a mid-mutation
        // Path (the debug serializer may IOOBE on a torn read) — accepted, it is
        // dev-only polling, versus a production watchdog deadlock.
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "getPath");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "isDone");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "isInProgress");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "isStuck");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "getTargetPos");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "getMaxDistanceToWaypoint");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "canFloat");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "getNodeEvaluator");
        // PathNavigation.stop is OWNER-ROUTED, not monitored (task #40): the body is
        // one reference write (path = null), and FabroliaPathNavigationMixin's HEAD
        // inject (fabrolia$routedStop) defers any cross-context call to the owner
        // island's lane via NavRecompute.stopCrossContextDeferred — so a monitor
        // here would only re-arm the task-#32 cycle for the doors that reach stop
        // from MAIN (TeleportCommand.performTeleport, PiglinAi.stopWalking via
        // mobInteract, LivingEntity.randomTeleport // spreadplayers). The handler's
        // Mixin-mangled name (handler$xxx$fabrolia$routedStop) is matched by the
        // "$<name>" suffix rule in isExcluded — the guard must run WITHOUT the
        // monitor or the exemption is void.
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "stop");
        mixin2MethodsExcludeMap.put("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin", "fabrolia$routedStop");
        syncAllSet.add("com.axalotl.fabrolia.common.mixin.utils.FastUtilSynchronizeMixin");
        syncAllSet.add("com.axalotl.fabrolia.common.mixin.utils.SyncAllMixin");
    }

    /**
     * Exclusion match: exact name, or a Mixin-renamed injector handler whose mangled
     * name ends in {@code "$<excluded>"} (Mixin merges {@code @Inject}/MixinExtras
     * handlers as {@code handler$xxxxxx$<originalName>}; whether they carry
     * ACC_SYNTHETIC is a Mixin implementation detail we must not depend on — without
     * the suffix rule the blanket bit can land on a handler that is the un-monitored
     * guard for its own exempted method). The "$" separator keeps ordinary vanilla
     * names from ever matching (none contain '$' outside lambdas, which are
     * synthetic-static and skipped by the mask anyway).
     */
    private static boolean isExcluded(String methodName, Collection<String> excludedMethods) {
        if (excludedMethods.contains(methodName)) {
            return true;
        }
        for (String excluded : excludedMethods) {
            if (methodName.endsWith("$" + excluded)) {
                return true;
            }
        }
        return false;
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
                // isExcluded (not a plain contains) so that Mixin-mangled injector
                // handler names (handler$xxx000$<name>) match their exemption too —
                // otherwise a handler carries the very monitor its exemption removed.
                if ((method.access & SKIP_SYNTHETIC_ABSTRACT_NATIVE_BRIDGE_STATIC) == 0 && !method.name.equals("<init>") && !isExcluded(method.name, excludedMethods)) {
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
