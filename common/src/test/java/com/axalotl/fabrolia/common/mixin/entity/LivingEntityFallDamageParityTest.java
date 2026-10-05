package com.axalotl.fabrolia.common.mixin.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Fabrolia's own causeFallDamage handlers, applied to an entity landing in a ladder cell. */
@Isolated
class LivingEntityFallDamageParityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void landingIntoClimbableWithinOneMoveKeepsVanillaFallDamage() throws Exception {
        // Vanilla 26.3 clears fallDistance for climbables in handleOnClimbable,
        // before the move and from the cell the entity starts in (spectator,
        // glide and trapdoor rules live in onClimbable), and in Entity.move only
        // through the FALLDAMAGE_RESETTING clip of a move of length >= 1. An
        // entity falling slower than that into a ladder cell from a non-climbable
        // cell above lands there and takes the fall damage; the landing cell's
        // block must not cancel causeFallDamage.
        Field tags = field(Holder.Reference.class, "tags");
        Holder.Reference<Block> ladder = Blocks.LADDER.builtInRegistryHolder();
        Object previousTags = tags.get(ladder);
        tags.set(ladder, Set.of(BlockTags.CLIMBABLE));
        try {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            Subject subject = (Subject) unsafe.allocateInstance(Subject.class);
            subject.testLevel = (LadderLevel) unsafe.allocateInstance(LadderLevel.class);
            field(Entity.class, "position").set(subject, new Vec3(0.5D, 64.0D, 0.5D));
            for (Method handler : LivingEntityMixin.class.getDeclaredMethods()) {
                Inject inject = handler.getAnnotation(Inject.class);
                if (inject == null || !targetsCauseFallDamage(inject)) continue;
                CallbackInfoReturnable<Boolean> callback =
                        new CallbackInfoReturnable<>("causeFallDamage", true);
                handler.setAccessible(true);
                try {
                    handler.invoke(subject, 20.0D, 1.0F, null, callback);
                } catch (InvocationTargetException failure) {
                    throw new AssertionError(handler + " failed", failure.getCause());
                }
                assertFalse(callback.isCancelled(),
                        handler + " cancelled vanilla fall damage for a landing in a ladder cell");
            }
        } finally {
            tags.set(ladder, previousTags);
        }
    }

    private static boolean targetsCauseFallDamage(Inject inject) {
        for (String target : inject.method()) {
            if (target.startsWith("causeFallDamage")) return true;
        }
        return false;
    }

    private static final class Subject extends LivingEntityMixin {
        ServerLevel testLevel;
        private Subject() { super(null, null); }
        @Override public ServerLevel level() { return testLevel; }
        @Override public float getHealth() { return 20.0F; }
        @Override public boolean hurtServer(ServerLevel level,
                net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }
        @Override protected void defineSynchedData(
                net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(
                net.minecraft.world.level.storage.ValueInput input) {}
        @Override protected void addAdditionalSaveData(
                net.minecraft.world.level.storage.ValueOutput output) {}
    }

    private static final class LadderLevel extends ServerLevel {
        private LadderLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return Blocks.LADDER.defaultBlockState();
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
