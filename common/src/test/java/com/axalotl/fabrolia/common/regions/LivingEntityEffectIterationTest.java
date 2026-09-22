package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.LivingEntityMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Actual mixin initialization/lock and vanilla effect loop; world and notifications are substituted. */
@Timeout(15)
class LivingEntityEffectIterationTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void convertingEffectExpiresOnceAndReplacementKeepsItsFullOwnTickCountdown() throws Exception {
        Fixture fixture = new Fixture();
        CountingEffect source = new CountingEffect(MobEffects.BAD_OMEN, 1);
        CountingEffect destination = new CountingEffect(MobEffects.RAID_OMEN, 600);
        // Same structural path as BadOmenMobEffect: insert the successor before
        // tickServer returns false. No world/player substitutes choose the cursor.
        source.beforeTick = () -> fixture.effects.put(destination.getEffect(), destination);
        fixture.effects.put(source.getEffect(), source);

        fixture.tick();

        assertFalse(fixture.subject.hasEffect(source.getEffect()));
        assertEquals(List.of(source), fixture.subject.removed);
        assertEquals(Map.of(destination.getEffect(), destination), fixture.effects);
        assertEquals(600, destination.getDuration());
        assertEquals(0, destination.ticks);
        for (int remaining = 599; remaining > 0; remaining--) {
            fixture.tick();
            assertSame(destination, fixture.subject.getEffect(destination.getEffect()));
            assertEquals(remaining, destination.getDuration());
        }
        fixture.tick();
        assertTrue(fixture.effects.isEmpty());
        assertEquals(600, destination.ticks);
        assertEquals(1, source.ticks, "conversion must not refresh the successor every tick");
        assertEquals(List.of(source, destination), fixture.subject.removed);
    }

    @Test
    void replacementOfExpiringRegistrationIsNotRemovedByItsOldTick() throws Exception {
        Fixture fixture = new Fixture();
        CountingEffect old = new CountingEffect(MobEffects.INVISIBILITY, 1);
        CountingEffect replacement = new CountingEffect(old.getEffect(), 2);
        old.beforeTick = () -> fixture.effects.put(old.getEffect(), replacement);
        fixture.effects.put(old.getEffect(), old);

        fixture.tick();

        assertSame(replacement, fixture.subject.getEffect(old.getEffect()));
        assertEquals(2, replacement.getDuration());
        assertTrue(fixture.subject.removed.isEmpty(),
                "the old expiration cannot notify removal of a replacement registration");
        fixture.tick();
        fixture.tick();
        assertEquals(List.of(replacement), fixture.subject.removed);
    }

    @Test
    void removingAndReinsertingSameInstanceDoesNotRepeatItsRemovalCallback() throws Exception {
        Fixture fixture = new Fixture();
        CountingEffect effect = new CountingEffect(MobEffects.INVISIBILITY, 1);
        effect.beforeTick = () -> {
            assertTrue(fixture.subject.removeEffect(effect.getEffect()));
            fixture.effects.put(effect.getEffect(), effect);
        };
        fixture.effects.put(effect.getEffect(), effect);

        fixture.tick();

        assertSame(effect, fixture.subject.getEffect(effect.getEffect()));
        assertEquals(List.of(effect), fixture.subject.removed);
        effect.beforeTick = null;
        fixture.tick();
        assertTrue(fixture.effects.isEmpty());
        assertEquals(List.of(effect, effect), fixture.subject.removed,
                "each registration, rather than each object identity, is removed once");
    }

    @Test
    void reentrantRemovalCannotTickAnAlreadyRemovedEffect() throws Exception {
        Fixture fixture = new Fixture();
        CountingEffect first = new CountingEffect(MobEffects.INVISIBILITY, 1);
        CountingEffect second = new CountingEffect(MobEffects.NIGHT_VISION, 1);
        fixture.effects.put(first.getEffect(), first);
        fixture.effects.put(second.getEffect(), second);
        AtomicReference<CountingEffect> removedBeforeTick = new AtomicReference<>();
        fixture.subject.afterRemoval = () -> {
            CountingEffect other = (CountingEffect) fixture.subject.getActiveEffects().iterator().next();
            removedBeforeTick.set(other);
            assertTrue(fixture.subject.removeEffect(other.getEffect()));
        };

        fixture.tick();

        assertEquals(0, removedBeforeTick.get().ticks,
                "an effect removed by an earlier callback must not receive a phantom tick");
        assertTrue(fixture.subject.getActiveEffects().isEmpty());
        assertEquals(2, fixture.subject.removed.size());
        assertEquals(Set.of(first, second), Set.copyOf(fixture.subject.removed));
        fixture.tick();
        assertEquals(2, fixture.subject.removed.size(), "completed removals must not be repeated next tick");
    }

    @Test
    void reentrantSelfRemovalDefersTheRemainingEffectWithoutRepeatingRemoval() throws Exception {
        Fixture fixture = new Fixture();
        CountingEffect first = new CountingEffect(MobEffects.INVISIBILITY, 1);
        CountingEffect second = new CountingEffect(MobEffects.NIGHT_VISION, 1);
        fixture.effects.put(first.getEffect(), first);
        fixture.effects.put(second.getEffect(), second);
        CountingEffect leading = (CountingEffect) fixture.effects.values().iterator().next();
        CountingEffect following = leading == first ? second : first;
        leading.beforeTick = () -> assertTrue(fixture.subject.removeEffect(leading.getEffect()));

        fixture.tick();

        assertEquals(List.of(leading), fixture.subject.removed);
        assertEquals(0, following.ticks, "a removed registration cannot continue the stale cursor");
        fixture.tick();
        assertEquals(List.of(leading, following), fixture.subject.removed);
        assertTrue(fixture.subject.getActiveEffects().isEmpty());
        fixture.tick();
        assertEquals(2, fixture.subject.removed.size());
    }

    @Test
    void rawRemovalWaitsUntilTheEnteredEffectTickFinishes() throws Exception {
        Fixture fixture = new Fixture();
        CountingEffect first = new CountingEffect(MobEffects.INVISIBILITY, 2);
        CountingEffect second = new CountingEffect(MobEffects.NIGHT_VISION, 2);
        fixture.effects.put(first.getEffect(), first);
        fixture.effects.put(second.getEffect(), second);
        CountingEffect leading = (CountingEffect) fixture.effects.values().iterator().next();
        CountingEffect following = leading == first ? second : first;
        CountDownLatch tickEntered = new CountDownLatch(1);
        CountDownLatch releaseTick = new CountDownLatch(1);
        CountDownLatch removalAttempted = new CountDownLatch(1);
        AtomicReference<Throwable> tickFailure = new AtomicReference<>();
        AtomicReference<MobEffectInstance> removed = new AtomicReference<>();
        leading.beforeTick = () -> {
            tickEntered.countDown();
            await(releaseTick);
        };
        Thread ticker = new Thread(() -> {
            try {
                fixture.tick();
            } catch (Throwable failure) {
                tickFailure.set(failure);
            }
        }, "effect-tick-owner");
        Thread remover = new Thread(() -> {
            removalAttempted.countDown();
            removed.set(fixture.subject.removeEffectNoUpdate(following.getEffect()));
        }, "raw-effect-remover");
        ticker.start();
        try {
            await(tickEntered);
            remover.start();
            await(removalAttempted);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (remover.isAlive() && remover.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, remover.getState(),
                    "raw removal must not invalidate a key retained by the entered tick");
        } finally {
            releaseTick.countDown();
            ticker.join(5000);
            remover.join(5000);
        }
        assertFalse(ticker.isAlive());
        assertFalse(remover.isAlive());
        if (tickFailure.get() != null) throw new AssertionError(tickFailure.get());
        assertSame(following, removed.get());
        assertEquals(1, following.ticks);
        assertEquals(1, following.getDuration(), "removal observes the completed owner's tick");
    }

    @Test
    void effectCallbackCanReadAnotherEntityWhoseEffectTickIsStillEntered() throws Exception {
        Fixture owner = new Fixture();
        Fixture reader = new Fixture();
        CountingEffect ownedEffect = new CountingEffect(MobEffects.INVISIBILITY, 2);
        CountingEffect readingEffect = new CountingEffect(MobEffects.NIGHT_VISION, 2);
        owner.effects.put(ownedEffect.getEffect(), ownedEffect);
        reader.effects.put(readingEffect.getEffect(), readingEffect);
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch readAttempted = new CountDownLatch(1);
        CountDownLatch readFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ownedEffect.beforeTick = () -> {
            ownerEntered.countDown();
            await(releaseOwner);
        };
        readingEffect.beforeTick = () -> {
            readAttempted.countDown();
            assertTrue(owner.subject.hasEffect(ownedEffect.getEffect()));
            assertSame(ownedEffect, owner.subject.getEffect(ownedEffect.getEffect()));
            assertTrue(owner.subject.getActiveEffects().contains(ownedEffect));
            readFinished.countDown();
        };
        Thread ownerThread = new Thread(() -> {
            try {
                owner.tick();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "paused-effect-owner");
        Thread readerThread = new Thread(() -> {
            try {
                reader.tick();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "cross-entity-effect-reader");
        ownerThread.start();
        try {
            await(ownerEntered);
            readerThread.start();
            await(readAttempted);
            assertTrue(readFinished.await(2, TimeUnit.SECONDS),
                    "remote effect reads must not acquire another entity's tick monitor");
        } finally {
            releaseOwner.countDown();
            ownerThread.join(5000);
            readerThread.join(5000);
        }
        assertFalse(ownerThread.isAlive());
        assertFalse(readerThread.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class Fixture {
        final MixinStorage storage;
        final Subject subject;
        final Map<Holder<MobEffect>, MobEffectInstance> effects;
        final Method tickWrapper;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            TestLevel level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            EntityType<?> type = (EntityType<?>) unsafe.allocateInstance(EntityType.class);
            field(EntityType.class, "dimensions").set(type,
                    net.minecraft.world.entity.EntityDimensions.fixed(0.6F, 1.8F));
            // Run the real field initializers: this test must not choose a map or monitor itself.
            storage = new MixinStorage(type, level);
            effects = (Map<Holder<MobEffect>, MobEffectInstance>)
                    field(LivingEntityMixin.class, "activeEffects").get(storage);
            subject = (Subject) unsafe.allocateInstance(Subject.class);
            subject.testLevel = level;
            subject.removed = new ArrayList<>();
            field(LivingEntity.class, "activeEffects").set(subject, effects);
            tickWrapper = LivingEntityMixin.class.getDeclaredMethod("tickStatusEffects", Operation.class);
            tickWrapper.setAccessible(true);
        }

        void tick() throws Exception {
            try {
                tickWrapper.invoke(storage, (Operation<Void>) ignored -> {
                    subject.tickEffects();
                    return null;
                });
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw new AssertionError(failure.getCause());
            }
        }
    }

    private static final class CountingEffect extends MobEffectInstance {
        int ticks;
        Runnable beforeTick;
        CountingEffect(Holder<MobEffect> effect, int duration) { super(effect, duration); }
        @Override
        public boolean tickServer(ServerLevel level, LivingEntity entity, Runnable onUpdate) {
            ticks++;
            if (beforeTick != null) beforeTick.run();
            return super.tickServer(level, entity, onUpdate);
        }
    }

    private static final class Subject extends LivingEntity {
        ServerLevel testLevel;
        List<MobEffectInstance> removed;
        Runnable afterRemoval;
        private Subject() { super(null, null); }
        @Override public ServerLevel level() { return testLevel; }
        @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
        @Override public void tickEffects() { super.tickEffects(); }
        @Override protected void onEffectsRemoved(Collection<MobEffectInstance> effects) {
            effects.forEach(effect -> removed.add(java.util.Objects.requireNonNull(effect)));
            Runnable callback = afterRemoval;
            afterRemoval = null;
            if (callback != null) callback.run();
        }
    }

    private static final class MixinStorage extends LivingEntityMixin {
        MixinStorage(EntityType<?> type, ServerLevel level) { super(type, level); }
        @Override public float getHealth() { return 20.0F; }
        @Override public boolean hurtServer(ServerLevel level,
                net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(ValueInput input) {}
        @Override protected void addAdditionalSaveData(ValueOutput output) {}
    }

    private static final class TestLevel extends ServerLevel {
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public int getNextEntityId() { return 1; }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for effect test participant");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
