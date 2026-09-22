package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.advancement.NbtPredicateMixin;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.TestDedicatedServer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.MapCodec;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.MobEffectsPredicate;
import net.minecraft.advancements.triggers.ConsumeItemTrigger;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.PlayerTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Native trigger capture and deferred award delivery, not a simulated gameplay/server tick. */
final class AdvancementEvalFanOutFailStopTest {
    private Object previousPool;
    private Object previousFatal;
    private Field pool;
    private Field fatal;
    private ConcurrentLinkedQueue<Object> pending;
    private MinecraftServer server;
    private ServerLevel level;
    private Object previousServer;
    private Object previousSchedulerServer;
    private AtomicBoolean drainScheduled;
    private ReloadableServerResources resources;
    private boolean previousUnindexedListener;
    private boolean previouslyScheduled;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void isolate() throws Exception {
        pool = field(RegionScheduler.class, "pool");
        fatal = field(RegionScheduler.class, "orphanFatalFailure");
        previousPool = pool.get(null);
        previousFatal = fatal.get(null);
        pool.set(null, null);
        fatal.set(null, null);
        previousServer = field(ParallelProcessor.class, "server").get(null);
        previousUnindexedListener = field(AdvancementCaptureOwnership.class, "unindexedListenerObserved").getBoolean(null);
        field(AdvancementCaptureOwnership.class, "unindexedListenerObserved").setBoolean(null, false);
        previousSchedulerServer = field(RegionScheduler.class, "server").get(null);
        server = allocate(TestDedicatedServer.class);
        level = allocate(ServerLevel.class);
        field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
        field(MinecraftServer.class, "levels").set(server, Map.of());
        field(BlockableEventLoop.class, "pendingRunnables").set(server, new ConcurrentLinkedQueue<>());
        field(ServerLevel.class, "server").set(level, server);
        field(ParallelProcessor.class, "server").set(null, server);
        resources = allocate(ReloadableServerResources.class);
        field(ReloadableServerResources.class, "fullRegistryHolder").set(resources,
                new ReloadableServerRegistries.Holder(HolderLookup.Provider.create(java.util.stream.Stream.empty())));
        Field resourceField = field(MinecraftServer.class, "resources");
        var resourceConstructor = resourceField.getType().getDeclaredConstructors()[0];
        resourceConstructor.setAccessible(true);
        resourceField.set(server, resourceConstructor.newInstance(null, resources));
        field(RegionScheduler.class, "server").set(null, server);
        // This fixture is serial physical Main, with no live gameplay workers.
        // Unknown/custom predicates therefore retain real global producer authority.
        assertFalse(RegionScheduler.hasLiveGameplayWorkers());
        drainScheduled = (AtomicBoolean) field(AdvancementBuffer.class, "MAIN_DRAIN_SCHEDULED").get(null);
        previouslyScheduled = drainScheduled.getAndSet(false);
        pending = (ConcurrentLinkedQueue<Object>) field(AdvancementBuffer.class,
                "DEFERRED_PENDING").get(null);
        assertTrue(pending.isEmpty());
    }

    @AfterEach
    void restore() throws Exception {
        pending.clear();
        ((java.util.Queue<?>) field(AdvancementBuffer.class, "DIRECT_MAIN").get(null)).clear();
        ((java.util.Queue<?>) field(AdvancementBuffer.class, "PENDING_FLUSH").get(null)).clear();
        pool.set(null, previousPool);
        fatal.set(null, previousFatal);
        field(ParallelProcessor.class, "server").set(null, previousServer);
        field(RegionScheduler.class, "server").set(null, previousSchedulerServer);
        drainScheduled.set(previouslyScheduled);
        field(AdvancementCaptureOwnership.class, "unindexedListenerObserved")
                .setBoolean(null, previousUnindexedListener);
    }

    @Test
    void emptyNativeListenerSnapshotDoesNotConstructSelfContext() throws Exception {
        Fixture fixture = new Fixture();
        // Native context creation would reject this absent origin. With no listeners
        // vanilla must return without observing that context or entering the matcher.
        field(Entity.class, "position").set(fixture.player, null);
        assertNull(fixture.capture(RandomSource.create(1), instance -> {
            fail("an empty native listener map must not evaluate any matcher");
            return false;
        }));
    }

    @Test
    void unpreparedNativeInsertionPermanentlyInvalidatesDynamicVictimCertificate() throws Exception {
        Fixture fixture = new Fixture();
        var criterion = PlayerTrigger.TriggerInstance.tick();
        var advancement = Advancement.Builder.advancement().addCriterion("tick", criterion)
                .build(net.minecraft.resources.Identifier.withDefaultNamespace("tracked")).value();
        PreparedAdvancements manager = allocate(PreparedAdvancements.class);
        manager.requirements = AdvancementRequirements.prepare(List.of(advancement),
                HolderLookup.Provider.create(java.util.stream.Stream.empty()));
        field(ReloadableServerResources.class, "advancements").set(resources, manager);
        assertTrue(AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope(),
                "prepared resource metadata alone is not an insertion-tracking witness");
        manager.tracks = true;
        assertFalse(AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope());
        var key = new PlayerAdvancements.TriggerInstanceKey(null, "tracked");
        var tracked = new AdvancementListenerMap<PlayerTrigger.TriggerInstance>(fixture.advancements, instance -> {
            AdvancementCaptureOwnership.observeListenerInsertion(fixture.player, CriteriaTriggers.TICK, instance);
            if (instance != criterion.triggerInstance()) {
                assertTrue(AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope(),
                        "the coarse certificate must be invalid before publication");
                assertSame(criterion.triggerInstance(),
                        fixture.advancements.getTriggerMapForType(CriteriaTriggers.TICK).get(key),
                        "the old value must still be visible at the insertion witness");
            }
        });
        field(PlayerAdvancements.class, "activeTriggers").set(fixture.advancements,
                Map.of(CriteriaTriggers.TICK, tracked));
        tracked.put(key, criterion.triggerInstance());
        assertFalse(AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope());

        fixture.advancements.getTriggerMapForType(CriteriaTriggers.TICK).compute(key,
                (ignored, previous) -> PlayerTrigger.TriggerInstance.tick().triggerInstance());
        assertTrue(AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope(),
                "an equal but unprepared native instance must poison the negative certificate");
        manager.requirements = AdvancementRequirements.prepare(List.of(),
                HolderLookup.Provider.create(java.util.stream.Stream.empty()));
        assertTrue(AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope(),
                "resource replacement alone cannot prove an unknown listener was removed");
    }

    @Test
    void poisonRemovedAfterConsumeCaptureStillAwardsAtDrain() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger evaluations = new AtomicInteger();
        fixture.listen("miracle_drink", fixture.poisonPredicate(evaluations));
        fixture.poison(true);

        fixture.captureAndPublish();
        assertEquals(1, evaluations.get(), "player condition belongs to the consume invocation");
        assertEquals(List.of(), fixture.advancements.awards, "capture cannot grant early");
        fixture.poison(false);
        AdvancementBuffer.applyDeferred();

        assertEquals(List.of("miracle_drink"), fixture.advancements.awards);
        assertEquals(1, evaluations.get(), "drain must not revisit the now-unpoisoned player");
    }

    @Test
    void poisonAddedAfterConsumeCaptureCannotCreateAMatch() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger evaluations = new AtomicInteger();
        fixture.listen("miracle_drink", fixture.poisonPredicate(evaluations));

        fixture.captureAndPublish();
        assertEquals(1, evaluations.get());
        fixture.poison(true);
        AdvancementBuffer.applyDeferred();

        assertEquals(List.of(), fixture.advancements.awards);
        assertEquals(1, evaluations.get(), "a rejected trigger is not a later evaluation obligation");
    }

    @Test
    void captureEntersRandomPrefixOnceAndDrainOnlyDeliversItsMatch() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger draws = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        RandomSource random = (RandomSource) Proxy.newProxyInstance(
                RandomSource.class.getClassLoader(), new Class<?>[]{RandomSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("nextFloat")) {
                        return draws.getAndIncrement() == 0 ? 0.1F : 0.9F;
                    }
                    throw new AssertionError(method);
                });
        fixture.listen("random", ContextAwarePredicate.create(
                LootItemRandomChanceCondition.randomChance(0.5F).build(),
                condition(ctx -> guardedFetch(fixture.player, () -> {
                    saves.incrementAndGet();
                    return new CompoundTag();
                }).isEmpty())));

        fixture.publish(fixture.capture(random, instance -> instance.matches(ItemStack.EMPTY)));
        assertEquals(1, draws.get());
        assertEquals(1, saves.get());
        assertEquals(List.of(), fixture.advancements.awards);
        AdvancementBuffer.applyDeferred();

        assertEquals(List.of("random"), fixture.advancements.awards,
                "a second random draw would lose this criterion");
        assertEquals(1, draws.get());
        assertEquals(1, saves.get());
        assertTrue(pending.isEmpty());
    }

    @Test
    void eachListenerRunsMatcherThenPlayerConditionBeforeTheNextMatcher() throws Exception {
        Fixture fixture = new Fixture();
        List<String> events = new ArrayList<>();
        ConsumeItemTrigger.TriggerInstance first = fixture.listen("first", ContextAwarePredicate.create(condition(ctx -> {
            events.add("player:first");
            fixture.poison(true);
            return true;
        })));
        ConsumeItemTrigger.TriggerInstance rejected = fixture.listen("rejected", ContextAwarePredicate.create(condition(ctx -> {
            fail("a failed item matcher must short-circuit its player condition");
            return true;
        })));
        fixture.listen("last", ContextAwarePredicate.create(condition(ctx -> {
            events.add("player:last");
            return fixture.player.hasEffect(MobEffects.POISON);
        })));

        fixture.publish(fixture.capture(RandomSource.create(4), instance -> {
            String name = instance == first ? "first" : instance == rejected ? "rejected" : "last";
            events.add("matcher:" + name);
            if (name.equals("last")) {
                assertTrue(fixture.player.hasEffect(MobEffects.POISON),
                        "the first listener's player predicate must already have run");
            }
            return instance != rejected && instance.matches(ItemStack.EMPTY);
        }));
        assertEquals(List.of("matcher:first", "player:first", "matcher:rejected",
                "matcher:last", "player:last"), events);
        AdvancementBuffer.applyDeferred();
        assertEquals(List.of("first", "last"), fixture.advancements.awards);
        assertEquals(5, events.size(), "no listener may re-enter while awarding");
    }

    @Test
    void differentTriggerMomentsCannotReuseAPlayerStateSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        List<Boolean> observedPoison = new ArrayList<>();
        fixture.listen("poison_snapshot", ContextAwarePredicate.create(condition(ctx -> {
            CompoundTag snapshot = guardedFetch(fixture.player, () -> {
                CompoundTag tag = new CompoundTag();
                if (fixture.player.hasEffect(MobEffects.POISON)) tag.putBoolean("poison", true);
                return tag;
            });
            observedPoison.add(snapshot.contains("poison"));
            return snapshot.contains("poison");
        })));

        fixture.poison(true);
        fixture.captureAndPublish();
        fixture.poison(false);
        fixture.captureAndPublish();
        assertEquals(List.of(true, false), observedPoison,
                "each trigger must serialize the player state from its own moment");
        AdvancementBuffer.applyDeferred();

        assertEquals(List.of("poison_snapshot"), fixture.advancements.awards,
                "the second trigger must observe the effect removal, not the first snapshot");
        assertEquals(List.of(true, false), observedPoison, "drain cannot enter serialization again");
    }

    @Test
    void capturedAwardsDrainBeforeTheirDirectReplayCascade() throws Exception {
        List<String> events = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            Fixture fixture = new Fixture();
            fixture.listen(name, ContextAwarePredicate.create(condition(ctx -> true)));
            fixture.advancements.onAward = criterion -> {
                events.add(criterion);
                if (criterion.equals("first")) AdvancementBuffer.deferDirect(() -> events.add("replay"));
            };
            fixture.captureAndPublish();
        }
        assertEquals(List.of(), events, "capture must not enter reward bodies");

        AdvancementBuffer.applyDeferred();

        assertEquals(List.of("first", "second", "replay"), events);
        assertFalse(AdvancementBatchScope.active());
    }

    @Test
    void enteredCaptureFailureIsExactAndCannotPublishAPartialMatchOrRetry() throws Exception {
        Fixture fixture = new Fixture();
        RuntimeException primary = new RuntimeException("entered composite failure");
        List<String> events = new ArrayList<>();
        for (String name : List.of("first", "failure", "unentered")) {
            fixture.listen(name, ContextAwarePredicate.create(condition(ctx -> {
                events.add(name);
                if (name.equals("failure")) throw primary;
                return true;
            })));
        }

        assertSame(primary, assertThrows(RuntimeException.class, () ->
                RegionScheduler.runSuccessBodyIfHealthy(fixture::captureAndPublish)));
        assertEquals(List.of("first", "failure"), events);
        assertTrue(pending.isEmpty(), "a partial capture is not an accepted award obligation");
        assertEquals(List.of(), fixture.advancements.awards);
        assertSame(primary, assertThrows(Throwable.class, RegionScheduler::throwWorkerFailureIfAny));
    }

    @Test
    void healthyShutdownDeliversAcceptedAwardsWithoutReenteringPredicates() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger evaluated = new AtomicInteger();
        fixture.listen("shutdown", fixture.poisonPredicate(evaluated));
        fixture.poison(true);
        fixture.captureAndPublish();
        fixture.poison(false);

        AdvancementBuffer.drainShutdownPassIfPending();

        assertEquals(List.of("shutdown"), fixture.advancements.awards);
        assertEquals(1, evaluated.get());
        assertEquals(1, fixture.advancements.flushes);
        assertTrue(pending.isEmpty());
        assertFalse(AdvancementBatchScope.active());
        AdvancementBuffer.verifyAndResetAfterShutdown();
    }

    @Test
    void acquiringFenceRetainsFlushesAndTheirNativeArgumentsInOrder() throws Exception {
        Fixture fixture = new Fixture();
        AdvancementBuffer.queueFlush(fixture.player, false);
        AdvancementBuffer.queueFlush(fixture.player, true);
        Class<?> commands = com.axalotl.fabrolia.common.regions.IslandCommands.class;
        Class<?> frameType = Class.forName(commands.getName() + "$GlobalFenceFrame");
        var constructor = frameType.getDeclaredConstructor(Thread.class,
                com.axalotl.fabrolia.common.regions.IslandRegistry.PIsland.class);
        constructor.setAccessible(true);
        Field active = field(commands, "ACTIVE_FENCE");
        Object previous = active.get(null);
        try {
            active.set(null, constructor.newInstance(Thread.currentThread(), null));
            AdvancementBuffer.drainPendingFlushes();
            assertEquals(List.of(), fixture.advancements.flushArguments,
                    "an acquiring fence must not detach an undeliverable native flush");
        } finally {
            active.set(null, previous);
        }
        AdvancementBuffer.drainPendingFlushes();
        assertEquals(List.of(false, true), fixture.advancements.flushArguments);
    }

    @Test
    void laterAwardFailureStopsRemainingAwardsAndDirectReplay() throws Exception {
        RuntimeException failure = new RuntimeException("later award");
        List<String> events = new ArrayList<>();
        for (String name : List.of("first", "failure", "unentered")) {
            Fixture fixture = new Fixture();
            fixture.listen(name, ContextAwarePredicate.create(condition(ctx -> true)));
            fixture.advancements.onAward = criterion -> {
                events.add(criterion);
                if (criterion.equals("first")) AdvancementBuffer.deferDirect(() -> events.add("replay"));
                if (criterion.equals("failure")) throw failure;
            };
            fixture.captureAndPublish();
        }

        assertSame(failure, assertThrows(RuntimeException.class, AdvancementBuffer::applyDeferred));
        assertEquals(List.of("first", "failure"), events);
        assertSame(failure, assertThrows(Throwable.class, RegionScheduler::throwWorkerFailureIfAny));
        assertFalse(AdvancementBatchScope.active());
    }

    @Test
    void captureRejectsForeignProducerBeforeEvaluatingAnyPredicate() throws Exception {
        field(MinecraftServer.class, "serverThread").set(server, new Thread());
        AtomicInteger prefix = new AtomicInteger();
        Fixture fixture = new Fixture();
        fixture.listen("unsafe", ContextAwarePredicate.create(condition(ctx -> {
            prefix.incrementAndGet();
            return true;
        })));
        try {
            assertFalse(AdvancementCaptureOwnership.ownsGlobalProducerEnvelope());
            assertThrows(IllegalStateException.class, () ->
                    fixture.publish(fixture.capture(RandomSource.create(1), instance -> {
                        prefix.incrementAndGet();
                        return instance.matches(ItemStack.EMPTY);
                    })));
            assertEquals(0, prefix.get(),
                    "an unowned producer must be rejected before predicate side effects");
            assertTrue(pending.isEmpty(), "a rejected producer cannot publish an award obligation");
        } finally {
            field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
        }
    }

    private final class Fixture {
        final ServerPlayer player = allocate(ServerPlayer.class);
        final RecordingAdvancements advancements = allocate(RecordingAdvancements.class);
        final ConsumeItemTrigger trigger = new ConsumeItemTrigger();
        final Map<PlayerAdvancements.TriggerInstanceKey, ConsumeItemTrigger.TriggerInstance> listeners =
                new LinkedHashMap<>();

        Fixture() throws Exception {
            advancements.awards = new ArrayList<>();
            advancements.flushArguments = new ArrayList<>();
            advancements.onAward = criterion -> {};
            field(ServerPlayer.class, "advancements").set(player, advancements);
            field(Entity.class, "level").set(player, level);
            field(Entity.class, "blockPosition").set(player, BlockPos.ZERO);
            field(Entity.class, "position").set(player, Vec3.ZERO);
            field(LivingEntity.class, "activeEffects").set(player, new HashMap<>());
            Map<CriterionTrigger<?>, Map<PlayerAdvancements.TriggerInstanceKey, ConsumeItemTrigger.TriggerInstance>> triggers =
                    new IdentityHashMap<>();
            triggers.put(trigger, listeners);
            field(PlayerAdvancements.class, "activeTriggers").set(advancements, triggers);
        }

        ConsumeItemTrigger.TriggerInstance listen(String criterion, ContextAwarePredicate predicate) {
            var instance = new ConsumeItemTrigger.TriggerInstance(Optional.of(predicate), Optional.empty());
            listeners.put(new PlayerAdvancements.TriggerInstanceKey(null, criterion), instance);
            return instance;
        }

        void poison(boolean present) {
            if (present) player.getActiveEffectsMap().put(MobEffects.POISON, new MobEffectInstance(MobEffects.POISON, 200));
            else player.getActiveEffectsMap().remove(MobEffects.POISON);
        }

        ContextAwarePredicate poisonPredicate(AtomicInteger evaluated) {
            MobEffectsPredicate effects = MobEffectsPredicate.Builder.effects().and(MobEffects.POISON).build();
            return ContextAwarePredicate.create(condition(ctx -> {
                evaluated.incrementAndGet();
                return effects.matches(ctx.getParameter(LootContextParams.THIS_ENTITY));
            }));
        }

        Object capture(RandomSource random, Predicate<ConsumeItemTrigger.TriggerInstance> matcher) {
            try {
                field(Level.class, "random").set(level, random);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
            return invoke("buildPending", new Class<?>[]{ServerPlayer.class, Predicate.class,
                    CriterionTrigger.class}, player, matcher, trigger);
        }

        void publish(Object captured) {
            if (captured == null) return;
            Object batch = Array.newInstance(captured.getClass(), 1);
            Array.set(batch, 0, captured);
            invoke("drainOnWorker", new Class<?>[]{batch.getClass()}, batch);
        }

        void captureAndPublish() {
            publish(capture(RandomSource.create(1), instance -> instance.matches(ItemStack.EMPTY)));
        }
    }

    private static final class PreparedAdvancements extends ServerAdvancementManager
            implements AdvancementRequirements.Provider {
        private AdvancementRequirements requirements;
        private boolean tracks;

        private PreparedAdvancements() {
            super(null);
        }

        @Override public AdvancementRequirements fabrolia$advancementRequirements() {
            return requirements;
        }

        @Override public boolean fabrolia$tracksListenerInsertions() {
            return tracks;
        }
    }

    /** Observes the real buffer's award boundary; does not emulate vanilla progress/rewards. */
    private static final class RecordingAdvancements extends PlayerAdvancements {
        List<String> awards;
        Consumer<String> onAward;
        int flushes;
        List<Boolean> flushArguments;

        private RecordingAdvancements() {
            super(null, null, null, null, null);
        }

        @Override public boolean award(AdvancementHolder advancement, String criterion) {
            onAward.accept(criterion);
            awards.add(criterion);
            return true;
        }

        @Override public void flushDirty(ServerPlayer player, boolean showAdvancements) {
            flushes++;
            flushArguments.add(showAdvancements);
        }
    }

    private static LootItemCondition condition(Predicate<LootContext> body) {
        return new LootItemCondition() {
            @Override public MapCodec<? extends LootItemCondition> codec() {
                throw new UnsupportedOperationException("runtime-only test condition");
            }
            @Override public boolean test(LootContext context) { return body.test(context); }
        };
    }

    private static CompoundTag guardedFetch(ServerPlayer player, java.util.function.Supplier<CompoundTag> original) {
        try {
            Method guard = NbtPredicateMixin.class.getDeclaredMethod(
                    "fabrolia$guardedTagFetch", net.minecraft.world.entity.Entity.class, Operation.class);
            guard.setAccessible(true);
            Operation<CompoundTag> fetch = args -> original.get();
            return (CompoundTag) guard.invoke(null, player, fetch);
        } catch (InvocationTargetException failure) {
            AdvancementEvalFanOutFailStopTest.<RuntimeException>rethrow(failure.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object invoke(String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = AdvancementBuffer.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            AdvancementEvalFanOutFailStopTest.<RuntimeException>rethrow(failure.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T { throw (T) failure; }
}
