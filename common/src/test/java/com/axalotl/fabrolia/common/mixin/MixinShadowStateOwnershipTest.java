package com.axalotl.fabrolia.common.mixin;

import com.axalotl.fabrolia.common.mixin.world.LevelTicksMixin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @Shadow} field initializer is merged into the target constructor.
 * Writing {@code = null} here therefore destroys vanilla's already-created
 * runtime queue/list instead of merely satisfying javac. Keep vanilla-owned
 * scheduler state declaration-only in the mixin.
 */
final class MixinShadowStateOwnershipTest {

    @Test
    void levelTicksMixinDoesNotOverwriteVanillaRuntimeQueues() throws IOException {
        ClassNode owner = new ClassNode();
        new ClassReader(LevelTicksMixin.class.getName()).accept(owner, 0);
        MethodNode constructor = owner.methods.stream()
                .filter(method -> method.name.equals("<init>"))
                .findFirst()
                .orElseThrow();
        Set<String> vanillaOwned = Set.of("toRunThisTick", "alreadyRunThisTick");

        List<String> overwritten = new ArrayList<>();
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction.getOpcode() != Opcodes.PUTFIELD) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.owner.equals(owner.name) && vanillaOwned.contains(field.name)) {
                overwritten.add(field.name);
            }
        }

        assertTrue(overwritten.isEmpty(), () ->
                "Mixin constructor overwrites vanilla-owned LevelTicks state: "
                        + overwritten);
    }

    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    /**
     * Every {@code @Shadow} field of a mixin that has an initializer, as
     * {@code MixinSimpleName#field}. Mixin merges the initializer into the target
     * constructors, so each entry REPLACES the value vanilla (or Lithium) just
     * constructed. An entry is only allowed when the replacement is deliberate:
     * a thread-safe collection that keeps the vanilla collection's contract. The
     * set is closed in both directions: a new initializer must be added here with
     * its justification, and an entry whose initializer is gone must be removed.
     * Never initialize a shadowed field with {@code null}, a semantically different
     * type, or a value that drops vanilla's contents (a {@code LevelTicks} queue,
     * for example); see {@link #levelTicksMixinDoesNotOverwriteVanillaRuntimeQueues}.
     */
    private static final Set<String> ALLOWED_SHADOW_INITIALIZERS = Set.of(
            // Baseline of the 2026-10-25 audit (A-27): each replaces a freshly constructed
            // empty collection (or a lock/semaphore) by its thread-safe counterpart.
            // GoalSelectorMixin#availableGoals is pending review with audit A-05.
            "AttributeMapMixin#attributes",
            "BeehiveBlockEntityBeeMixin#stored",
            "ChunkMapTrackedEntityMixin#seenBy",
            "CombatTrackerMixin#entries",
            "DistanceManagerMixin#chunksToUpdateFutures",
            "DistanceManagerMixin#ticketsToRelease",
            "GoalSelectorMixin#availableGoals",
            "GossipContainerMixin#gossips",
            "LevelTicksMixin#allContainers",
            "LevelTicksMixin#nextTickForContainer",
            "LivingEntityMixin#activeEffects",
            "PlayerTeamMixin#players",
            "RaidMixin#groupRaiderMap",
            "RandomSequencesMixin#sequences",
            "SavedDataStorageMixin#cache",
            "ScoreboardMixin#playerScores",
            "ScoreboardMixin#teamsByPlayer",
            "SectionStorageMixin#dirtyChunks",
            "SectionStorageMixin#loadedChunks",
            "SectionStorageMixin#pendingLoads",
            "SensingMixin#seen",
            "SensingMixin#unseen",
            "ShowTradesToPlayerMixin#displayItems",
            "ThreadingDetectorMixin#lock"
    );

    @Test
    void shadowFieldInitializersMatchTheReviewedAllowlist() throws Exception {
        Path root = Path.of(LevelTicksMixin.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path mixinRoot = root.resolve("com/axalotl/fabrolia/common/mixin");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(mixinRoot)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                ClassNode cls = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(cls, 0);
                if (!isMixin(cls)) continue;
                Set<String> shadowFields = new TreeSet<>();
                for (FieldNode field : cls.fields) {
                    if (hasAnnotation(field.visibleAnnotations, SHADOW)
                            || hasAnnotation(field.invisibleAnnotations, SHADOW)) {
                        shadowFields.add(field.name);
                    }
                }
                if (shadowFields.isEmpty()) continue;
                for (MethodNode method : cls.methods) {
                    if (!method.name.equals("<init>")) continue;
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getOpcode() != Opcodes.PUTFIELD) continue;
                        FieldInsnNode put = (FieldInsnNode) insn;
                        if (put.owner.equals(cls.name) && shadowFields.contains(put.name)) {
                            found.add(simpleName(cls.name) + "#" + put.name);
                        }
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(ALLOWED_SHADOW_INITIALIZERS), found,
                "@Shadow field initializers are merged into the target constructors and "
                        + "replace the value vanilla built. Declare vanilla-owned state without an "
                        + "initializer, or add the entry to ALLOWED_SHADOW_INITIALIZERS with the "
                        + "reason the replacement keeps vanilla's contract (and remove entries whose "
                        + "initializer is gone).");
    }

    private static boolean isMixin(ClassNode cls) {
        return hasAnnotation(cls.visibleAnnotations, MIXIN) || hasAnnotation(cls.invisibleAnnotations, MIXIN);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(desc)) return true;
        }
        return false;
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
