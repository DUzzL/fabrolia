package com.axalotl.fabrolia.common.utils;

import com.llamalad7.mixinextras.utils.MixinInternals;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.mixin.transformer.ext.extensions.ExtensionCheckClass;

import java.util.Arrays;
import java.util.List;

/** Fuse just getAllPlayers().iterator(); never substitute a shared Set into mod code. */
public final class ProximityRosterTransform implements IExtension {
    private static final String MAP = "net.minecraft.server.level.PlayerMap";
    private static final String OWNER = "net/minecraft/server/level/PlayerMap";
    private static final String ROSTER = "com/axalotl/fabrolia/common/utils/ProximityPlayerRoster";
    private static final String BOOLEAN_MAP = "it/unimi/dsi/fastutil/objects/Object2BooleanMap";
    private static final String OPEN_MAP = "it/unimi/dsi/fastutil/objects/Object2BooleanOpenHashMap";
    private static final String ITERATOR = "java/util/Iterator";
    private static volatile boolean sawNativeMixin, sawForeignMixin;

    private final ClassNode target;

    private ProximityRosterTransform(ClassNode target) { this.target = target; }

    /** Plugin postApply is too early: MixinExtras still holds references to injection points.
     * Its registration utility appends to a copy of the active list, ahead of the class verifier. */
    public static void afterInjections(ClassNode chunkMap) {
        MixinInternals.registerExtension(new ProximityRosterTransform(chunkMap));
    }

    @Override public boolean checkActive(MixinEnvironment env) { return true; }
    @Override public void preApply(ITargetClassContext ctx) {}
    @Override public void export(MixinEnvironment env, String name, boolean force, ClassNode node) {}

    @Override public void postApply(ITargetClassContext ctx) {
        if (ctx.getClassNode() != target) return;
        try {
            var active = MixinInternals.getExtensions().getActiveExtensions();
            int i = active.indexOf(this);
            if (i < 0) return;
            for (i++; i < active.size(); i++) {
                if (active.get(i).getClass() != ExtensionCheckClass.class) return;
            }
            fuseRead(target);
        } finally {
            MixinInternals.unregisterExtension(this);
        }
    }

    /** MixinSquared visits the actual target list before preApply, including late plugin-supplied mixins. */
    public static void observeTargets(List<String> targets, String mixin) {
        if (!targets.contains(MAP)) return;
        if (mixin.equals("com.axalotl.fabrolia.common.mixin.server.PlayerMapMixin")) sawNativeMixin = true;
        else sawForeignMixin = true;
    }

    public static void markNativeMap(ClassNode node) {
        if (!sawNativeMixin || sawForeignMixin || node.fields.size() != 1 || node.methods.size() != 8) return;
        var field = node.fields.getFirst();
        if (!field.name.equals("players") || !field.desc.equals("L" + BOOLEAN_MAP + ";")
                || (field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) return;
        if (!node.methods.stream().allMatch(ProximityRosterTransform::nativeOperation)) return;
        // TargetClassContext rewrites Signature from ClassInfo after the plugin hooks.
        // Keep getGenericInterfaces() consistent with the interface used by instanceof.
        ClassInfo info = ClassInfo.forName(node.name);
        if (info == null) return;
        info.getSignature().addInterface(ROSTER);
        node.interfaces.add(ROSTER);
    }

    /** In particular, ignore/unIgnore must only replace the flag on an existing key. */
    private static boolean nativeOperation(MethodNode m) {
        if (!m.tryCatchBlocks.isEmpty()) return false;
        var ops = Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        int n = ops.size();
        if (m.name.equals("<init>")) {
            return n == 8 && m.desc.equals("()V") && load(ops.get(0), Opcodes.ALOAD, 0)
                    && call(ops.get(1), Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V")
                    && load(ops.get(2), Opcodes.ALOAD, 0) && ops.get(3) instanceof TypeInsnNode t
                    && t.getOpcode() == Opcodes.NEW && t.desc.equals(OPEN_MAP) && ops.get(4).getOpcode() == Opcodes.DUP
                    && call(ops.get(5), Opcodes.INVOKESPECIAL, OPEN_MAP, "<init>", "()V")
                    && players(ops.get(6), Opcodes.PUTFIELD) && ops.get(7).getOpcode() == Opcodes.RETURN;
        }
        if (n < 4 || !load(ops.getFirst(), Opcodes.ALOAD, 0) || !players(ops.get(1), Opcodes.GETFIELD)) return false;
        if (m.name.equals("getAllPlayers")) {
            return n == 4 && m.desc.equals("()Ljava/util/Set;") && ops.getLast().getOpcode() == Opcodes.ARETURN
                    && call(ops.get(2), Opcodes.INVOKEINTERFACE, BOOLEAN_MAP, "keySet", "()Lit/unimi/dsi/fastutil/objects/ObjectSet;");
        }
        if (!load(ops.get(2), Opcodes.ALOAD, 1)) return false;
        boolean writer = ops.getLast().getOpcode() == Opcodes.RETURN && ops.get(n - 2).getOpcode() == Opcodes.POP;
        boolean reader = ops.getLast().getOpcode() == Opcodes.IRETURN;
        String flag = "(Ljava/lang/Object;Z)Z", key = "(Ljava/lang/Object;)Z";
        return switch (m.name) {
            case "addPlayer" -> n == 7 && writer && load(ops.get(3), Opcodes.ILOAD, 2)
                    && call(ops.get(4), Opcodes.INVOKEINTERFACE, BOOLEAN_MAP, "put", flag);
            case "removePlayer" -> n == 6 && writer && call(ops.get(3), Opcodes.INVOKEINTERFACE, BOOLEAN_MAP, "removeBoolean", key);
            case "ignorePlayer", "unIgnorePlayer" -> n == 7 && writer
                    && ops.get(3).getOpcode() == (m.name.equals("ignorePlayer") ? Opcodes.ICONST_1 : Opcodes.ICONST_0)
                    && call(ops.get(4), Opcodes.INVOKEINTERFACE, BOOLEAN_MAP, "replace", flag);
            case "ignoredOrUnknown" -> n == 6 && reader && ops.get(3).getOpcode() == Opcodes.ICONST_1
                    && call(ops.get(4), Opcodes.INVOKEINTERFACE, BOOLEAN_MAP, "getOrDefault", flag);
            case "ignored" -> n == 5 && reader && call(ops.get(3), Opcodes.INVOKEINTERFACE, BOOLEAN_MAP, "getBoolean", key);
            default -> false;
        };
    }

    private static boolean players(AbstractInsnNode i, int opcode) {
        return i instanceof FieldInsnNode f && f.getOpcode() == opcode && f.owner.equals(OWNER)
                && f.name.equals("players") && f.desc.equals("L" + BOOLEAN_MAP + ";");
    }

    private static boolean load(AbstractInsnNode i, int opcode, int slot) {
        return i instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
    }

    private static void fuseRead(ClassNode chunkMap) {
        for (MethodNode m : chunkMap.methods) {
            if (!m.name.equals("anyPlayerCloseEnoughTo") || !m.desc.equals("(Lnet/minecraft/core/BlockPos;I)Z")) continue;
            for (AbstractInsnNode n : m.instructions) {
                if (!call(n, Opcodes.INVOKEVIRTUAL, OWNER, "getAllPlayers", "()Ljava/util/Set;")) continue;
                // Raw adjacency: no alternate entry, saved Set or hook on the returned Set.
                var iterator = n.getNext();
                if (!call(iterator, Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;")) continue;
                if (!(instruction(iterator.getNext()) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
                if (!forwardOnly(m, store)) continue;
                m.instructions.set(n, new MethodInsnNode(Opcodes.INVOKESTATIC, ROSTER, "iterator",
                        "(L" + OWNER + ";)Ljava/util/Iterator;", true));
                m.instructions.remove(iterator);
                return;
            }
        }
    }

    private static boolean forwardOnly(MethodNode m, VarInsnNode store) {
        for (AbstractInsnNode i : m.instructions) {
            if (!(i instanceof VarInsnNode v) || v == store || v.var != store.var) continue;
            if (v.getOpcode() != Opcodes.ALOAD) return false;
            var use = instruction(v.getNext());
            if (!call(use, Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z")
                    && !call(use, Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;")) return false;
        }
        return true;
    }

    private static AbstractInsnNode instruction(AbstractInsnNode n) {
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
    }

    private static boolean call(AbstractInsnNode n, int opcode, String owner, String name, String desc) {
        return n instanceof MethodInsnNode m && m.getOpcode() == opcode && m.owner.equals(owner)
                && m.name.equals(name) && m.desc.equals(desc);
    }
}
