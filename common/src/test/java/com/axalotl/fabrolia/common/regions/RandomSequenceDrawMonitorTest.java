package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.RandomSequenceDrawMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit C-04: every draw of a shared loot sequence holds the shared source's monitor. */
final class RandomSequenceDrawMonitorTest {

    @Test
    void everyVanillaDrawMethodIsWrapped() throws Exception {
        ClassNode node = new ClassNode();
        try (var input = RandomSource.class.getClassLoader()
                .getResourceAsStream("net/minecraft/world/RandomSequences$DirtyMarkingRandomSource.class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        Set<String> vanilla = node.methods.stream().map(m -> m.name + m.desc).collect(Collectors.toSet());
        for (String draw : List.of("fork()Lnet/minecraft/util/RandomSource;",
                "forkPositional()Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;",
                "setSeed(J)V", "nextInt()I", "nextInt(I)I", "nextLong()J", "nextBoolean()Z",
                "nextFloat()F", "nextDouble()D", "nextGaussian()D")) {
            assertTrue(vanilla.contains(draw), "vanilla draw method " + draw);
        }
    }

    @Test
    void drawRunsUnderTheSharedSourceMonitor() throws Exception {
        RandomSource shared = RandomSource.create(42L);
        RandomSequenceDrawMixin wrapper = new RandomSequenceDrawMixin() {};
        Field random = RandomSequenceDrawMixin.class.getDeclaredField("random");
        random.setAccessible(true);
        random.set(wrapper, shared);
        Method next = RandomSequenceDrawMixin.class.getDeclaredMethod("fabrolia$nextLong", Operation.class);
        next.setAccessible(true);
        Operation<Long> draw = arguments -> {
            assertTrue(Thread.holdsLock(shared), "the draw must hold the shared source");
            return 7L;
        };
        assertEquals(7L, next.invoke(wrapper, draw));
    }
}
