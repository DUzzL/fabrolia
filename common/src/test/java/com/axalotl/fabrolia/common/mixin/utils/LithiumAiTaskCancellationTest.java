package com.axalotl.fabrolia.common.mixin.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the narrowed lithium {@code ai.task} cancellation against the REAL
 * {@link MixinCanceller}, using class names that are verified to exist in the
 * lithium jar on the test classpath.
 *
 * <p>Until 2026-08-01 the canceller killed the whole
 * {@code net.caffeinemc.mods.lithium.mixin.ai.task.} package — four sub-packages —
 * to work around one bug in {@code memory_changes}, and its comment blamed a
 * visibility problem that was not the defect. {@code memory_changes} is now
 * repaired in {@code mixin/lithium/FabroliaLithiumBrainMemoryCounterMixin} +
 * {@code FabroliaLithiumBrainMemoryBumpMixin} (see
 * {@code BrainMemoryModCountLatchTest}); {@code replace_streams} and {@code run}
 * are stateless and were never implicated. Only {@code launch} stays cancelled,
 * and that one is a real target conflict — Fabrolia's own
 * {@code mixin/entity/BrainMixin} already {@code @WrapMethod}s
 * {@code startEachNonRunningBehavior} without calling original, and lithium's
 * {@code MaskedList} has no Fabrolia guard.
 *
 * <p>If someone re-broadens the cancel, or drops one of the two repair mixins from
 * the config / from the {@code !LITHIUM} gate, this fails.
 */
class LithiumAiTaskCancellationTest {

    private static final List<String> ANY_TARGET = List.of("net.minecraft.world.entity.ai.Brain");

    private static final String LITHIUM_AI_TASK = "net.caffeinemc.mods.lithium.mixin.ai.task.";
    private static final String FABROLIA_LITHIUM = "com.axalotl.fabrolia.common.mixin.lithium.";

    private static final String COUNTER_MIXIN = FABROLIA_LITHIUM + "FabroliaLithiumBrainMemoryCounterMixin";
    private static final String BUMP_MIXIN = FABROLIA_LITHIUM + "FabroliaLithiumBrainMemoryBumpMixin";

    /** Only cancelled sub-package. */
    private static final List<String> LAUNCH = List.of(LITHIUM_AI_TASK + "launch.BrainMixin");

    /** Everything that must now be allowed to apply. */
    private static final List<String> ENABLED = List.of(
            LITHIUM_AI_TASK + "memory_changes.BrainMixin",
            LITHIUM_AI_TASK + "memory_changes.BehaviorMixin",
            LITHIUM_AI_TASK + "memory_changes.MemorySlotMixin",
            LITHIUM_AI_TASK + "replace_streams.GateBehaviorMixin",
            LITHIUM_AI_TASK + "replace_streams.RunningPolicyRunOneMixin",
            LITHIUM_AI_TASK + "replace_streams.RunningPolicyTryAllMixin",
            LITHIUM_AI_TASK + "replace_streams.ShufflingListMixin",
            LITHIUM_AI_TASK + "run.long_jump_weighted_choice.LongJumpToRandomPosMixin");

    private static boolean onClasspath(String className) {
        return LithiumAiTaskCancellationTest.class.getResource(
                "/" + className.replace('.', '/') + ".class") != null;
    }

    /** The names above are the real 0.25.1 class names, not a stale transcription. */
    @Test
    void everyNamedLithiumMixinExistsOnTheTestClasspath() {
        for (String name : LAUNCH) {
            assertTrue(onClasspath(name), name + " must exist in the lithium jar");
        }
        for (String name : ENABLED) {
            assertTrue(onClasspath(name), name + " must exist in the lithium jar");
        }
        assertTrue(onClasspath("net.caffeinemc.mods.lithium.common.ai.brain.memories.MemoryModificationCounter"),
                "FabroliaLithiumBrainMemoryBumpMixin casts to this interface");
    }

    @Test
    void onlyLaunchIsCancelledFromAiTask() {
        MixinCanceller canceller = new MixinCanceller();
        for (String name : LAUNCH) {
            assertTrue(canceller.shouldCancel(ANY_TARGET, name),
                    name + " is a genuine target conflict with Fabrolia's entity/BrainMixin");
        }
        for (String name : ENABLED) {
            assertFalse(canceller.shouldCancel(ANY_TARGET, name),
                    name + " must apply: the blanket ai.task cancel was a crutch");
        }
    }

    /** The two repair mixins reference lithium members, so they apply iff lithium does. */
    @Test
    void repairMixinsAreGatedOnLithiumBeingPresent() {
        MixinCanceller withLithium = new MixinCanceller();
        // Observing any lithium mixin is what sets the canceller's LITHIUM flag.
        withLithium.shouldCancel(ANY_TARGET, LITHIUM_AI_TASK + "memory_changes.BrainMixin");
        assertFalse(withLithium.shouldCancel(ANY_TARGET, COUNTER_MIXIN));
        assertFalse(withLithium.shouldCancel(ANY_TARGET, BUMP_MIXIN));

        MixinCanceller withoutLithium = new MixinCanceller();
        assertTrue(withoutLithium.shouldCancel(ANY_TARGET, COUNTER_MIXIN),
                "MixinSquared target class would not exist without lithium");
        assertTrue(withoutLithium.shouldCancel(ANY_TARGET, BUMP_MIXIN),
                "casts to MemoryModificationCounter and calls lithium$onMemoryModified");
    }

    /**
     * The three point-cancels are matched pairs / conflicts with strictly-better
     * Fabrolia implementations, documented as such in {@link MixinCanceller}. They
     * stay.
     */
    @Test
    void pointCancelsWithFabroliaReplacementsStayCancelled() {
        MixinCanceller canceller = new MixinCanceller();
        assertTrue(canceller.shouldCancel(ANY_TARGET,
                "net.caffeinemc.mods.lithium.mixin.collections.attributes.AttributeMapMixin"));
        assertTrue(canceller.shouldCancel(ANY_TARGET,
                "net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking.EntitySectionMixin"));
        assertTrue(canceller.shouldCancel(ANY_TARGET,
                "net.caffeinemc.mods.lithium.mixin.collections.entity_filtering.ClassInstanceMultiMapMixin"));
    }

    /**
     * Un-cancelling {@code memory_changes} re-arms lithium's
     * {@code @ModifyArg(method = "forgetOutdatedMemories", at = INVOKE
     * Ljava/util/Collection;forEach)} — and Fabrolia's {@code entity/BrainMixin}
     * replaces {@code Brain.memories} with {@code AtomicMemoryStorage} from
     * {@code @Inject(<init>, TAIL)}. That is a runtime field assignment, so it cannot
     * move the injection point as long as the field stays declared as {@code Map}
     * and {@code forgetOutdatedMemories} keeps calling {@code values().forEach(...)}.
     * Pinned against real vanilla bytecode so a future MC refactor fails here rather
     * than as an {@code injectors.defaultRequire} failure at load.
     */
    @Test
    void lithiumForgetOutdatedMemoriesInjectionPointSurvivesTheStorageSwap() throws IOException {
        org.objectweb.asm.tree.ClassNode brain = new org.objectweb.asm.tree.ClassNode();
        try (InputStream in = LithiumAiTaskCancellationTest.class
                .getResourceAsStream("/net/minecraft/world/entity/ai/Brain.class")) {
            assertNotNull(in, "vanilla Brain bytecode must be on the test classpath");
            new org.objectweb.asm.ClassReader(in).accept(brain, 0);
        }

        boolean memoriesIsAMap = brain.fields.stream()
                .anyMatch(f -> "memories".equals(f.name) && "Ljava/util/Map;".equals(f.desc));
        assertTrue(memoriesIsAMap,
                "AtomicMemoryStorage is installed into a field typed Map; values() therefore "
                        + "still returns Collection and the INVOKE descriptor is unchanged");

        org.objectweb.asm.tree.MethodNode forget = brain.methods.stream()
                .filter(m -> "forgetOutdatedMemories".equals(m.name) && "()V".equals(m.desc))
                .findFirst().orElse(null);
        assertNotNull(forget, "Brain.forgetOutdatedMemories()V");

        boolean hasCollectionForEach = false;
        for (org.objectweb.asm.tree.AbstractInsnNode insn : forget.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && "java/util/Collection".equals(call.owner)
                    && "forEach".equals(call.name)
                    && "(Ljava/util/function/Consumer;)V".equals(call.desc)) {
                hasCollectionForEach = true;
                break;
            }
        }
        assertTrue(hasCollectionForEach,
                "lithium's @ModifyArg targets INVOKE Collection.forEach inside forgetOutdatedMemories");
    }

    @Test
    void repairMixinsAreRegisteredInTheCommonMixinConfig() throws IOException {
        String config;
        try (InputStream in = LithiumAiTaskCancellationTest.class
                .getResourceAsStream("/fabrolia.common.mixins.json")) {
            assertNotNull(in, "fabrolia.common.mixins.json must be on the test classpath");
            config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(config.contains("\"lithium.FabroliaLithiumBrainMemoryCounterMixin\""),
                "Fix A must be registered or the counter stays a plain long");
        assertTrue(config.contains("\"lithium.FabroliaLithiumBrainMemoryBumpMixin\""),
                "Fix B must be registered or the start gate stays latchable");
    }
}
