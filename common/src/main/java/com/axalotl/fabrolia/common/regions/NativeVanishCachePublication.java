package com.axalotl.fabrolia.common.regions;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.utils.MixinInternals;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.util.Bytecode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishes Vanish's existing cache, not a second, potentially stale copy.
 *
 * <p>A clean read needs only two volatile loads. Cold refresh and invalidation
 * still execute the complete original methods under the same private monitor.
 * The cached write precedes dirty=false, so a clean reader acquires the published
 * value; an overlapping invalidation may linearize after that read.
 *
 * <p>This optimization deliberately recognizes one complete final method graph.
 * Unknown providers, getter/dirty hooks, extra calls into the private leaves, or
 * a later extension keep the original serialized wrappers. In particular, a
 * plugin's postApply is too early: MixinExtras applies WrapMethod afterwards.
 */
public final class NativeVanishCachePublication implements IExtension {
    private static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final int FORBIDDEN_METHOD_FLAGS = Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED
            | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);
    private final Extensions extensions;

    private NativeVanishCachePublication(Extensions extensions) {
        this.extensions = extensions;
    }

    public static void register() {
        // Called from our optional mixin's postApply, after config plugins have
        // registered their extensions (including Spark). MixinExtras updates
        // the already-selected active list as well as future selections.
        MixinExtrasBootstrap.init();
        Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        if (!(transformer instanceof IMixinTransformer mixin)
                || !(mixin.getExtensions() instanceof Extensions extensions)) return;
        for (IExtension extension : extensions.getExtensions()) {
            if (extension instanceof NativeVanishCachePublication) return;
        }
        MixinInternals.registerExtension(new NativeVanishCachePublication(extensions));
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
    }

    @Override
    public void export(MixinEnvironment environment, String name, boolean force, ClassNode target) {
        if (!PLAYER.equals(target.name) || environment.getOption(MixinEnvironment.Option.HOT_SWAP)) return;
        List<IExtension> active = extensions.getActiveExtensions();
        if (active.isEmpty() || active.getLast() != this) return;
        int occurrences = 0;
        for (IExtension extension : active) if (extension == this) occurrences++;
        if (occurrences != 1) return;
        // Mixin calls this after all postApply callbacks and coprocessors, and
        // serializes this same ClassNode afterwards. Do not move to postApply.
        publish(target);
    }

    static boolean publish(ClassNode target) {
        if (!PLAYER.equals(target.name)) return false;
        Chain getter = chain(target, "vanish$isVanished", "Z", "Boolean", "$fabrolia$readVanishObserverCache");
        Chain dirty = chain(target, "vanish$setDirty", "V", "Void", "$fabrolia$dirtyVanishObserverCache");
        FieldNode dirtyField = field(target, "vanished$dirty", "Z", false);
        FieldNode cachedField = field(target, "vanished$vanished", "Z", false);
        FieldNode lock = field(target, "fabrolia$vanishObserverCacheLock", "Ljava/lang/Object;", true);
        if (getter == null || dirty == null || dirtyField == null || cachedField == null || lock == null) return false;
        Map<String, String> leaves = Map.of(getter.leaf.name + getter.leaf.desc, "getLeaf",
                dirty.leaf.name + dirty.leaf.desc, "dirtyLeaf");
        if (!GET_HANDLER.equals(body(target, getter.handler, leaves))
                || !DIRTY_HANDLER.equals(body(target, dirty.handler, leaves))
                || !GET_LEAF.equals(body(target, getter.leaf, leaves))
                || !DIRTY_LEAF.equals(body(target, dirty.leaf, leaves))
                || !GET_BRIDGE.equals(body(target, getter.bridge, leaves))
                || !DIRTY_BRIDGE.equals(body(target, dirty.bridge, leaves))
                || !privateEntriesAreClosed(target, getter, dirty)) return false;
        // The new branch has the method's initial locals and an empty stack.
        // Refuse unexpected pre-existing frames rather than guessing their state.
        for (AbstractInsnNode insn : getter.wrapper.instructions) if (insn instanceof FrameNode) return false;

        LabelNode cold = new LabelNode();
        InsnList warm = new InsnList();
        warm.add(new VarInsnNode(Opcodes.ALOAD, 0));
        warm.add(new FieldInsnNode(Opcodes.GETFIELD, target.name, dirtyField.name, "Z"));
        warm.add(new JumpInsnNode(Opcodes.IFNE, cold));
        warm.add(new VarInsnNode(Opcodes.ALOAD, 0));
        warm.add(new FieldInsnNode(Opcodes.GETFIELD, target.name, cachedField.name, "Z"));
        warm.add(new InsnNode(Opcodes.IRETURN));
        warm.add(cold);
        warm.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        // All certification finishes before changing any field or instruction.
        dirtyField.access |= Opcodes.ACC_VOLATILE;
        cachedField.access |= Opcodes.ACC_VOLATILE;
        getter.wrapper.instructions.insert(warm);
        return true;
    }

    private record Chain(MethodNode wrapper, MethodNode handler, MethodNode bridge, MethodNode leaf,
                         InvokeDynamicInsnNode operation, MethodInsnNode handlerCall, MethodInsnNode leafCall) {
    }

    private static Chain chain(ClassNode target, String name, String result, String boxed, String handlerSuffix) {
        MethodNode wrapper = method(target, name, "()" + result, false);
        if (wrapper == null || (wrapper.tryCatchBlocks != null && !wrapper.tryCatchBlocks.isEmpty())) return null;
        List<AbstractInsnNode> code = executable(wrapper);
        if (code.size() != 5 || !load(code.get(0), 0) || !load(code.get(1), 0)
                || !(code.get(2) instanceof InvokeDynamicInsnNode operation)
                || !(code.get(3) instanceof MethodInsnNode call)
                || code.get(4).getOpcode() != (result.equals("Z") ? Opcodes.IRETURN : Opcodes.RETURN)
                || !METAFACTORY.equals(operation.bsm) || !operation.name.equals("call")
                || !operation.desc.equals("(L" + target.name + ";)L" + OPERATION + ";")
                || operation.bsmArgs.length != 3
                || !Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;").equals(operation.bsmArgs[0])
                || !(operation.bsmArgs[1] instanceof Handle bridgeHandle)
                || !Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/" + boxed + ";").equals(operation.bsmArgs[2])
                || bridgeHandle.getTag() != Opcodes.H_INVOKESPECIAL || bridgeHandle.isInterface()
                || !bridgeHandle.getOwner().equals(target.name)
                || !bridgeHandle.getDesc().equals("([Ljava/lang/Object;)Ljava/lang/" + boxed + ";")
                || call.getOpcode() != Opcodes.INVOKESPECIAL || call.itf || !call.owner.equals(target.name)
                || !call.name.endsWith(handlerSuffix) || !call.desc.equals("(L" + OPERATION + ";)" + result)) return null;
        MethodNode handler = method(target, call.name, call.desc, true);
        MethodNode bridge = method(target, bridgeHandle.getName(), bridgeHandle.getDesc(), true);
        if (handler == null || bridge == null) return null;
        List<AbstractInsnNode> bridgeCode = executable(bridge);
        if (bridgeCode.size() < 8 || !(bridgeCode.get(7) instanceof MethodInsnNode leafCall)
                || leafCall.getOpcode() != Opcodes.INVOKESPECIAL || leafCall.itf
                || !leafCall.owner.equals(target.name) || !leafCall.desc.equals("()" + result)) return null;
        MethodNode leaf = method(target, leafCall.name, leafCall.desc, true);
        return leaf == null ? null : new Chain(wrapper, handler, bridge, leaf, operation, call, leafCall);
    }

    private static MethodNode method(ClassNode target, String name, String descriptor, boolean privateMethod) {
        for (MethodNode method : target.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return (method.access & FORBIDDEN_METHOD_FLAGS) == 0
                        && (method.access & (privateMethod ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC)) != 0 ? method : null;
            }
        }
        return null;
    }

    private static FieldNode field(ClassNode target, String name, String descriptor, boolean finalField) {
        for (FieldNode field : target.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) {
                return (field.access & Opcodes.ACC_STATIC) == 0 && (field.access & Opcodes.ACC_PRIVATE) != 0
                        && ((field.access & Opcodes.ACC_FINAL) != 0) == finalField ? field : null;
            }
        }
        return null;
    }

    private static boolean load(AbstractInsnNode insn, int slot) {
        return insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == slot;
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
        return code;
    }

    private static boolean privateEntriesAreClosed(ClassNode target, Chain getter, Chain dirty) {
        Set<String> protectedMethods = new HashSet<>();
        Set<AbstractInsnNode> allowed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Chain chain : List.of(getter, dirty)) {
            for (MethodNode method : List.of(chain.handler, chain.bridge, chain.leaf)) {
                protectedMethods.add(method.name + method.desc);
            }
            allowed.add(chain.operation);
            allowed.add(chain.handlerCall);
            allowed.add(chain.leafCall);
        }
        boolean constructorFound = false;
        for (MethodNode method : target.methods) {
            boolean constructor = method.name.equals("<init>");
            if (constructor) {
                constructorFound = true;
                if (!nativeInitializers(target, method)) return false;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (allowed.contains(insn)) continue;
                if (insn instanceof FieldInsnNode field && field.owner.equals(target.name)
                        && cacheField(field.name) && (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)
                        && !constructor && method != getter.leaf && method != dirty.leaf) return false;
                if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)
                        && protectedMethods.contains(call.name + call.desc)) return false;
                if (insn instanceof LdcInsnNode constant && refersToPrivate(constant.cst, target.name, protectedMethods)) return false;
                if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    if (refersToPrivate(dynamic.bsm, target.name, protectedMethods)) return false;
                    for (Object argument : dynamic.bsmArgs) if (refersToPrivate(argument, target.name, protectedMethods)) return false;
                }
            }
        }
        return constructorFound;
    }

    private static boolean cacheField(String name) {
        return name.equals("vanished$dirty") || name.equals("vanished$vanished")
                || name.equals("fabrolia$vanishObserverCacheLock");
    }

    /** Only native straight-line field initializers may write these fields outside the certified leaves. */
    private static boolean nativeInitializers(ClassNode target, MethodNode constructor) {
        List<AbstractInsnNode> code = executable(constructor);
        MethodInsnNode parent = null;
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>") && (call.owner.equals(target.name) || call.owner.equals(target.superName))) {
                parent = call;
                break;
            }
        }
        if (parent == null) return false;
        boolean delegating = parent.owner.equals(target.name);
        int dirtyWrites = 0, lockWrites = 0;
        AbstractInsnNode lastInitializer = null;
        for (int i = 0; i < code.size(); i++) {
            AbstractInsnNode insn = code.get(i);
            if (!delegating && (dirtyWrites == 0 || lockWrites == 0)
                    && (insn instanceof JumpInsnNode || insn.getType() == AbstractInsnNode.TABLESWITCH_INSN
                    || insn.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN || insn.getOpcode() == Opcodes.RETURN)) return false;
            if (!(insn instanceof FieldInsnNode field) || !field.owner.equals(target.name) || !cacheField(field.name)
                    || (field.getOpcode() != Opcodes.PUTFIELD && field.getOpcode() != Opcodes.PUTSTATIC)) continue;
            if (delegating || field.getOpcode() != Opcodes.PUTFIELD) return false;
            if (field.name.equals("vanished$dirty")) {
                if (++dirtyWrites != 1 || !field.desc.equals("Z") || i < 2
                        || !load(code.get(i - 2), 0) || code.get(i - 1).getOpcode() != Opcodes.ICONST_1) return false;
            } else if (field.name.equals("fabrolia$vanishObserverCacheLock")) {
                if (++lockWrites != 1 || !field.desc.equals("Ljava/lang/Object;") || i < 4
                        || !load(code.get(i - 4), 0)
                        || !(code.get(i - 3) instanceof TypeInsnNode allocation)
                        || allocation.getOpcode() != Opcodes.NEW || !allocation.desc.equals("java/lang/Object")
                        || code.get(i - 2).getOpcode() != Opcodes.DUP
                        || !(code.get(i - 1) instanceof MethodInsnNode creation)
                        || creation.getOpcode() != Opcodes.INVOKESPECIAL || creation.itf
                        || !creation.owner.equals("java/lang/Object") || !creation.name.equals("<init>")
                        || !creation.desc.equals("()V")) return false;
            } else {
                return false;
            }
            lastInitializer = insn;
        }
        if (delegating) return true;
        if (dirtyWrites != 1 || lockWrites != 1) return false;
        int end = constructor.instructions.indexOf(lastInitializer);
        if (constructor.tryCatchBlocks != null) {
            for (var block : constructor.tryCatchBlocks) {
                if (constructor.instructions.indexOf(block.start) <= end
                        || constructor.instructions.indexOf(block.handler) <= end) return false;
            }
        }
        for (AbstractInsnNode insn : code) {
            if (insn instanceof JumpInsnNode jump && constructor.instructions.indexOf(jump.label) <= end) return false;
            if (insn instanceof TableSwitchInsnNode branch) {
                if (constructor.instructions.indexOf(branch.dflt) <= end) return false;
                for (LabelNode label : branch.labels) if (constructor.instructions.indexOf(label) <= end) return false;
            }
            if (insn instanceof LookupSwitchInsnNode branch) {
                if (constructor.instructions.indexOf(branch.dflt) <= end) return false;
                for (LabelNode label : branch.labels) if (constructor.instructions.indexOf(label) <= end) return false;
            }
        }
        return true;
    }

    private static boolean refersToPrivate(Object value, String owner, Set<String> methods) {
        if (value instanceof Handle handle) {
            return handle.getOwner().equals(owner) && (methods.contains(handle.getName() + handle.getDesc())
                    || ((handle.getTag() == Opcodes.H_PUTFIELD || handle.getTag() == Opcodes.H_PUTSTATIC)
                    && cacheField(handle.getName())));
        }
        if (value instanceof ConstantDynamic dynamic) {
            if (refersToPrivate(dynamic.getBootstrapMethod(), owner, methods)) return true;
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                if (refersToPrivate(dynamic.getBootstrapMethodArgument(i), owner, methods)) return true;
            }
        }
        return false;
    }

    /** Exact executable instructions, jump destinations, and exception regions; debug metadata is irrelevant. */
    private static String body(ClassNode owner, MethodNode method, Map<String, String> leaves) {
        Map<LabelNode, Integer> offsets = new IdentityHashMap<>();
        int offset = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode label) offsets.put(label, offset);
            if (insn.getOpcode() >= 0) offset++;
        }
        List<String> rows = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() < 0) continue;
            String operand = switch (insn) {
                case VarInsnNode var -> " " + var.var;
                case FieldInsnNode field -> " " + ownerName(owner, field.owner) + " " + field.name + " " + field.desc;
                case MethodInsnNode call -> " " + ownerName(owner, call.owner) + " "
                        + (call.owner.equals(owner.name) ? leaves.getOrDefault(call.name + call.desc, call.name) : call.name)
                        + " " + call.desc + " " + call.itf;
                case TypeInsnNode type -> " " + type.desc;
                case JumpInsnNode jump -> " " + offsets.get(jump.label);
                case IntInsnNode value -> " " + value.operand;
                case LdcInsnNode constant when constant.cst instanceof String -> " " + constant.cst;
                case InsnNode plain -> "";
                default -> null;
            };
            if (operand == null) return null;
            rows.add(Bytecode.getOpcodeName(insn) + operand);
        }
        if (method.tryCatchBlocks != null) {
            for (var block : method.tryCatchBlocks) {
                rows.add("TRY " + offsets.get(block.start) + " " + offsets.get(block.end) + " "
                        + offsets.get(block.handler) + " " + block.type);
            }
        }
        return String.join("\n", rows);
    }

    private static String ownerName(ClassNode owner, String name) {
        return owner.name.equals(name) ? "P" : name;
    }

    // Vanish 1.6.15 and MixinExtras 0.5.4. Readable certificates include the
    // wrappers themselves: hooks into our handlers must not be skipped either.
    private static final String GET_HANDLER = """
            ALOAD 0
            GETFIELD P fabrolia$vanishObserverCacheLock Ljava/lang/Object;
            DUP
            ASTORE 2
            MONITORENTER
            ALOAD 1
            ICONST_0
            ANEWARRAY java/lang/Object
            INVOKEINTERFACE com/llamalad7/mixinextras/injector/wrapoperation/Operation call ([Ljava/lang/Object;)Ljava/lang/Object; true
            CHECKCAST java/lang/Boolean
            INVOKEVIRTUAL java/lang/Boolean booleanValue ()Z false
            ALOAD 2
            MONITOREXIT
            IRETURN
            ASTORE 3
            ALOAD 2
            MONITOREXIT
            ALOAD 3
            ATHROW
            TRY 5 13 14 null
            TRY 14 17 14 null""";
    private static final String DIRTY_HANDLER = """
            ALOAD 0
            GETFIELD P fabrolia$vanishObserverCacheLock Ljava/lang/Object;
            DUP
            ASTORE 2
            MONITORENTER
            ALOAD 1
            ICONST_0
            ANEWARRAY java/lang/Object
            INVOKEINTERFACE com/llamalad7/mixinextras/injector/wrapoperation/Operation call ([Ljava/lang/Object;)Ljava/lang/Object; true
            POP
            ALOAD 2
            MONITOREXIT
            GOTO 18
            ASTORE 3
            ALOAD 2
            MONITOREXIT
            ALOAD 3
            ATHROW
            RETURN
            TRY 5 12 13 null
            TRY 13 16 13 null""";
    private static final String GET_LEAF = """
            ALOAD 0
            GETFIELD P vanished$dirty Z
            IFEQ 24
            ALOAD 0
            GETFIELD P server Lnet/minecraft/server/MinecraftServer;
            ALOAD 0
            GETFIELD P uuid Ljava/util/UUID;
            GETSTATIC me/drex/vanish/util/VanishManager VANISH_DATA_STORAGE Leu/pb4/playerdata/api/storage/PlayerDataStorage;
            INVOKESTATIC eu/pb4/playerdata/api/PlayerDataApi getCustomDataFor (Lnet/minecraft/server/MinecraftServer;Ljava/util/UUID;Leu/pb4/playerdata/api/storage/PlayerDataStorage;)Ljava/lang/Object; false
            CHECKCAST me/drex/vanish/util/VanishData
            ASTORE 1
            ALOAD 0
            ALOAD 1
            IFNULL 19
            ALOAD 1
            GETFIELD me/drex/vanish/util/VanishData vanished Z
            IFEQ 19
            ICONST_1
            GOTO 20
            ICONST_0
            PUTFIELD P vanished$vanished Z
            ALOAD 0
            ICONST_0
            PUTFIELD P vanished$dirty Z
            ALOAD 0
            GETFIELD P vanished$vanished Z
            IRETURN""";
    private static final String DIRTY_LEAF = """
            ALOAD 0
            ICONST_1
            PUTFIELD P vanished$dirty Z
            RETURN""";
    private static final String GET_BRIDGE = """
            ALOAD 1
            BIPUSH 0
            LDC []
            INVOKESTATIC com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime checkArgumentCount ([Ljava/lang/Object;ILjava/lang/String;)V false
            ALOAD 0
            ALOAD 1
            POP
            INVOKESPECIAL P getLeaf ()Z false
            INVOKESTATIC java/lang/Boolean valueOf (Z)Ljava/lang/Boolean; false
            ARETURN""";
    private static final String DIRTY_BRIDGE = """
            ALOAD 1
            BIPUSH 0
            LDC []
            INVOKESTATIC com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime checkArgumentCount ([Ljava/lang/Object;ILjava/lang/String;)V false
            ALOAD 0
            ALOAD 1
            POP
            INVOKESPECIAL P dirtyLeaf ()V false
            ACONST_NULL
            CHECKCAST java/lang/Void
            ARETURN""";
}
