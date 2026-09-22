package com.axalotl.fabrolia.common.parallelised.advancement;

import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.predicates.DataComponentMatchers;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.advancements.predicates.entity.EntityNbtPredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.ChangeDimensionTrigger;
import net.minecraft.advancements.triggers.ConsumeItemTrigger;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.InventoryChangeTrigger;
import net.minecraft.advancements.triggers.PlayerTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.AbstractMap;
import java.util.Set;

import static com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRequirements.Producer.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

final class AdvancementRequirementsTest {
    private static final TagKey<Item> PENDING_TAG =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fabrolia", "pending"));
    private static final ResourceKey<Item> STONE =
            ResourceKey.create(Registries.ITEM, Identifier.withDefaultNamespace("stone"));

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rayRequiresItsWholeProducerGloballyAdmitted() {
        String ray = """
                {"player":[{"condition":"minecraft:entity_properties","entity":"this",
                  "predicate":{"minecraft:type_specific/player":{"looking_at":{
                    "minecraft:location":{"offsetX":2}}}}}]}
                """;
        var use = classify("using_item", ray);
        assertFalse(use.global());
        assertTrue(use.requiresGlobalPlayerUse());
        assertTrue(classify("tick", ray).global());
    }

    @Test
    void nonzeroNativeOffsetIsNonlocalEvenWhenSmall() {
        String offset = """
                {"location":[{"condition":"minecraft:location_check","offsetY":-1,
                  "predicate":{"block":{"blocks":"minecraft:campfire"}}}]}
                """;
        for (String trigger : new String[]{"placed_block", "item_used_on_block"}) {
            var use = classify(trigger, offset);
            assertFalse(use.global());
            assertTrue(use.requiresGlobalPlayerUse());
        }
        assertTrue(classify("location", offset).global());
        assertTrue(classify("example:using_item", offset).global());
    }

    @Test
    void zeroOffsetAndLocalEntityFieldsDoNotGlobalizeIdleUnits() {
        var local = classify("tick", """
                {"player":[{"condition":"minecraft:entity_properties","entity":"this",
                  "predicate":{"minecraft:flags":{"is_baby":false},
                    "minecraft:location":{"offsetX":0,"offsetY":0,"offsetZ":0}}}]}
                """);
        assertFalse(local.global());
        assertFalse(local.requiresGlobalPlayerUse());
    }

    @Test
    void fullNbtNamedReferencesAndOpaqueExtensionsGlobalizeEvenPlayerUse() {
        assertTrue(classify("using_item", "{\"minecraft:nbt\":\"{EnderPearls:[]}\"}").global());
        assertTrue(classify("using_item", "{\"condition\":\"minecraft:reference\",\"name\":\"pack:remote\"}").global());
        assertTrue(classify("using_item", "{\"player\":[\"pack:remote\"]}").global());
        assertTrue(classify("using_item", "{\"minecraft:registered_extension\":{}}").global());
        // An entity extension cannot borrow an unrelated native effect name.
        assertTrue(classify("using_item", "{\"minecraft:poison\":{}}").global());
        assertFalse(classify("using_item", "{\"effects\":{\"minecraft:poison\":{}}}").global());
    }

    @Test
    void nativeEntityComponentsAreDataButUnknownComponentsRemainGlobal() {
        assertFalse(classify("tame_animal", """
                {"entity":{"minecraft:components":{
                  "minecraft:cat/variant":"minecraft:tabby"}}}
                """).global());
        assertTrue(classify("tame_animal", """
                {"entity":{"minecraft:components":{
                  "extension:cat/variant":"minecraft:tabby"}}}
                """).global());
    }

    @Test
    void unknownPreparationRequiresEveryProducerGlobal() {
        assertTrue(AdvancementRequirements.UNKNOWN.global());
        assertTrue(AdvancementRequirements.UNKNOWN.requiresGlobalPlayerUse());
    }

    @Test
    void pendingRegistryReferencesUseTheirActualOwner() {
        var lookup = pendingItems();
        var advancement = itemAdvancement(HolderSet.direct(lookup.getOrThrow(STONE)));
        assertFalse(prepare(advancement, lookup).global());
    }

    @Test
    void pendingTagDeclarationsDoNotResolveUnboundContents() {
        var lookup = pendingItems();
        var tag = lookup.getOrThrow(PENDING_TAG);
        assertFalse(tag.isBound());
        assertFalse(prepare(itemAdvancement(tag), lookup).global());
        assertFalse(tag.isBound());
    }

    @Test
    void unrelatedRegistryOwnerCannotBorrowPendingLookup() {
        var source = pendingItems();
        var unrelated = pendingItems();
        var advancement = itemAdvancement(HolderSet.direct(source.getOrThrow(STONE)));
        assertTrue(prepare(advancement, unrelated).global());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownHolderSetSelectsGlobalBeforeInvokingItsMethods() {
        var unknown = (HolderSet<Item>) Proxy.newProxyInstance(HolderSet.class.getClassLoader(),
                new Class<?>[]{HolderSet.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("unknown holder callback entered: " + method.getName());
                });
        assertTrue(prepare(itemAdvancement(unknown), pendingItems()).global());
    }

    @Test
    void loadedConsumeAndDimensionNbtRemainIsolatedFromLocalTick() throws Exception {
        var nbt = EntityPredicate.wrap(new EntityPredicate(Map.of(
                EntityNbtPredicate.CODEC, new EntityNbtPredicate(new NbtPredicate(new CompoundTag())))));
        var holder = holder(Map.of(
                "consume", CriteriaTriggers.CONSUME_ITEM.createCriterion(
                        new ConsumeItemTrigger.TriggerInstance(Optional.of(nbt), Optional.empty())),
                "dimension", CriteriaTriggers.CHANGED_DIMENSION.createCriterion(
                        new ChangeDimensionTrigger.TriggerInstance(Optional.of(nbt), Optional.empty(), Optional.empty())),
                "tick", PlayerTrigger.TriggerInstance.tick()));
        var requirements = prepare(holder.value(), pendingItems());
        var active = nativeAdvancements();
        active.register(holder);

        assertTrue(requirements.global(), "resource diagnostics must retain installed full NBT");
        assertFalse(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
        assertTrue(requirements.requiresGlobal(active, CriteriaTriggers.CONSUME_ITEM));
        assertTrue(requirements.requiresGlobal(active, CriteriaTriggers.CHANGED_DIMENSION));
        assertFalse(requirements.requiresGlobal(active, IDLE_PLAYER_OR_ENTITY_INTERACTION));
        assertFalse(requirements.mayRequireGlobalEntityInteraction());
        assertTrue(requirements.requiresGlobal(active, PLAYER_USE));
        assertTrue(requirements.requiresGlobal(active, ANY));

        assertTrue(active.award(holder, "consume"));
        assertFalse(requirements.requiresGlobal(active, PLAYER_USE),
                "the remaining dimension-only listener cannot globalize item use");
        assertTrue(requirements.requiresGlobal(active, ANY));
    }

    @Test
    void nativeRegisterCompletionRevokeAndReloadChangeAdmission(@TempDir Path directory) throws Exception {
        var holder = holder(Map.of("remote", remoteInventory(), "tick", PlayerTrigger.TriggerInstance.tick()));
        var requirements = prepare(holder.value(), pendingItems());
        var active = nativeAdvancements();
        assertFalse(requirements.requiresGlobal(active, ANY));
        active.register(holder);
        assertTrue(requirements.requiresGlobal(active, IDLE_PLAYER_OR_ENTITY_INTERACTION));
        assertTrue(requirements.mayRequireGlobalEntityInteraction());
        assertTrue(active.award(holder, "remote"));
        assertFalse(requirements.requiresGlobal(active, ANY));
        assertTrue(active.revoke(holder, "remote"));
        assertTrue(requirements.requiresGlobal(active, IDLE_PLAYER_OR_ENTITY_INTERACTION));

        var replacement = holder(Map.of("tick", PlayerTrigger.TriggerInstance.tick()));
        var replacementRequirements = prepare(replacement.value(), pendingItems());
        var manager = new ServerAdvancementManager(HolderLookup.Provider.create(Stream.empty()));
        field(ServerAdvancementManager.class, "advancements").set(manager, Map.of(replacement.id(), replacement));
        field(PlayerAdvancements.class, "playerSavePath").set(active, directory.resolve("missing-progress.json"));
        active.reload(manager);
        assertTrue(requirements.requiresGlobal(active, ANY),
                "the previous generation cannot certify a new listener instance");
        assertFalse(replacementRequirements.requiresGlobal(active, ANY));
        assertFalse(replacementRequirements.requiresGlobal(active, CriteriaTriggers.INVENTORY_CHANGED));
        assertTrue(requirements.global(), "membership changes must not mutate resource diagnostics");
    }

    @Test
    void unknownInstancesCannotBorrowEqualMetadataOrOperationExclusions() throws Exception {
        var local = PlayerTrigger.TriggerInstance.tick();
        var prepared = prepare(holder(Map.of("tick", local)).value(), pendingItems());
        var equalButUnprepared = PlayerTrigger.TriggerInstance.tick();
        assertEquals(local.triggerInstance(), equalButUnprepared.triggerInstance());
        var active = nativeAdvancements();
        active.register(holder(Map.of("tick", equalButUnprepared)));
        assertTrue(prepared.requiresGlobal(active, CriteriaTriggers.TICK));
        assertTrue(prepared.requiresGlobal(active, ANY));

        active = nativeAdvancements();
        active.register(holder(Map.of("dimension", ChangeDimensionTrigger.TriggerInstance.changedDimension())));
        assertTrue(prepared.requiresGlobal(active, PLAYER_USE));
        assertTrue(prepared.requiresGlobal(active, IDLE_PLAYER_OR_ENTITY_INTERACTION),
                "missing metadata fails closed even for an otherwise excluded native trigger");
        assertTrue(AdvancementRequirements.UNKNOWN.requiresGlobal(active, CriteriaTriggers.TICK));
    }

    @Test
    void unknownTriggerCannotBorrowNativeInstanceAuthorityOrExecuteItsCodec() throws Exception {
        CriterionTrigger<PlayerTrigger.TriggerInstance> unknown = () -> {
            throw new AssertionError("an unknown trigger codec must not execute during preparation");
        };
        var holder = holder(Map.of("unknown", unknown.createCriterion(
                new PlayerTrigger.TriggerInstance(Optional.empty())), "tick", PlayerTrigger.TriggerInstance.tick()));
        var requirements = prepare(holder.value(), pendingItems());
        var active = nativeAdvancements();
        active.register(holder);
        assertTrue(requirements.requiresGlobal(active, IDLE_PLAYER_OR_ENTITY_INTERACTION));
        assertTrue(requirements.requiresGlobal(active, unknown));
        assertTrue(requirements.mayRequireGlobalEntityInteraction());
        assertFalse(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCriterionSharingAnOpaqueChildRemainsNonlocal() throws Exception {
        var unknown = (HolderSet<Item>) Proxy.newProxyInstance(HolderSet.class.getClassLoader(),
                new Class<?>[]{HolderSet.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("opaque metadata callback entered: " + method.getName());
                });
        var shared = new ItemPredicate(Optional.of(unknown), MinMaxBounds.Ints.ANY, DataComponentMatchers.ANY);
        var holder = holder(Map.of(
                "first", InventoryChangeTrigger.TriggerInstance.hasItems(shared),
                "second", InventoryChangeTrigger.TriggerInstance.hasItems(shared),
                "tick", PlayerTrigger.TriggerInstance.tick()));
        var requirements = prepare(holder.value(), pendingItems());
        var active = nativeAdvancements();
        active.register(holder);
        assertTrue(requirements.requiresGlobal(active, ANY));
        assertTrue(active.award(holder, "first"));
        assertTrue(requirements.requiresGlobal(active, ANY),
                "a visited opaque child is not a previously proven safe child");
        assertTrue(active.award(holder, "second"));
        assertFalse(requirements.requiresGlobal(active, ANY));
        assertFalse(requirements.requiresGlobal(active, CriteriaTriggers.TICK),
                "an opaque criterion must not prevent indexing unrelated local criteria");
    }

    @Test
    void absentListenerMetadataProviderNeverCertifiesEmptyResources() throws Exception {
        var empty = AdvancementRequirements.prepare(List.of(), HolderLookup.Provider.create(Stream.empty()));
        assertFalse(empty.requiresGlobal(nativeAdvancements(), ANY));
        assertTrue(empty.requiresGlobal(allocate(PlayerAdvancements.class), ANY));
        assertTrue(AdvancementRequirements.UNKNOWN.requiresGlobal(nativeAdvancements(), ANY));
    }

    @Test
    void publicNativeMapMutationsCannotBorrowPreparedTickAuthority() throws Exception {
        var local = PlayerTrigger.TriggerInstance.tick();
        var holder = holder(Map.of("tick", local));
        var requirements = prepare(holder.value(), pendingItems());
        var key = new PlayerAdvancements.TriggerInstanceKey(holder, "tick");
        var added = new PlayerAdvancements.TriggerInstanceKey(holder, "added");
        List<BiConsumer<Map<PlayerAdvancements.TriggerInstanceKey, PlayerTrigger.TriggerInstance>,
                PlayerTrigger.TriggerInstance>> mutations = List.of(
                (map, unknown) -> map.put(added, unknown),
                (map, unknown) -> map.putAll(Map.of(added, unknown)),
                (map, unknown) -> map.putIfAbsent(added, unknown),
                (map, unknown) -> map.replace(key, unknown),
                (map, unknown) -> map.replace(key, local.triggerInstance(), unknown),
                (map, unknown) -> map.replaceAll((ignored, previous) -> unknown),
                (map, unknown) -> map.compute(added, (ignored, previous) -> unknown),
                (map, unknown) -> map.computeIfAbsent(added, ignored -> unknown),
                (map, unknown) -> map.computeIfPresent(key, (ignored, previous) -> unknown),
                (map, unknown) -> map.merge(key, unknown, (previous, replacement) -> replacement),
                (map, unknown) -> map.entrySet().iterator().next().setValue(unknown),
                (map, unknown) -> map.entrySet().stream().findFirst().orElseThrow().setValue(unknown),
                (map, unknown) -> map.entrySet().toArray(Map.Entry[]::new)[0].setValue(unknown));
        for (var mutation : mutations) {
            var active = nativeAdvancements();
            active.track(requirements);
            active.register(holder);
            var map = active.getTriggerMapForType(CriteriaTriggers.TICK);
            assertFalse(requirements.requiresGlobal(active, ANY),
                    "obtaining the public map must not invalidate prepared authority");
            assertFalse(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
            mutation.accept(map, PlayerTrigger.TriggerInstance.tick().triggerInstance());
            assertTrue(requirements.requiresGlobal(active, ANY));
            assertTrue(requirements.requiresGlobal(active, CriteriaTriggers.TICK),
                    "an unprepared equal native instance must not enter the exact-trigger fast path");
        }
    }

    @Test
    void removalOnlyViewsCannotIntroduceValuesOrClearAnotherUnknown() throws Exception {
        var local = PlayerTrigger.TriggerInstance.tick();
        var holder = holder(Map.of("tick", local));
        var requirements = prepare(holder.value(), pendingItems());
        var active = nativeAdvancements();
        active.track(requirements);
        active.register(holder);
        var map = active.getTriggerMapForType(CriteriaTriggers.TICK);
        var key = new PlayerAdvancements.TriggerInstanceKey(holder, "tick");
        var unknownKey = new PlayerAdvancements.TriggerInstanceKey(holder, "unknown");
        var unknown = PlayerTrigger.TriggerInstance.tick().triggerInstance();
        assertThrows(UnsupportedOperationException.class, () -> map.keySet().add(unknownKey));
        assertThrows(UnsupportedOperationException.class, () -> map.values().add(unknown));
        assertThrows(UnsupportedOperationException.class,
                () -> map.entrySet().add(Map.entry(unknownKey, unknown)));
        assertFalse(requirements.requiresGlobal(active, ANY));
        map.put(unknownKey, unknown);
        map.keySet().remove(key);
        active.index.clearIfEmpty(active.listeners);
        assertTrue(requirements.requiresGlobal(active, ANY));
        assertTrue(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
        map.put(key, local.triggerInstance());
        map.values().removeIf(instance -> instance == local.triggerInstance());
        assertTrue(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
        map.put(key, local.triggerInstance());
        map.entrySet().removeIf(entry -> entry.getKey().equals(key));
        assertTrue(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
        map.clear();
        map.put(key, unknown);
        assertTrue(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
    }

    @Test
    void entryReplacementInvalidatesBeforePublishingAndKeepsRetainedEntriesLive() throws Exception {
        var local = PlayerTrigger.TriggerInstance.tick();
        var holder = holder(Map.of("tick", local));
        var requirements = prepare(holder.value(), pendingItems());
        var index = new AdvancementRequirements.ListenerIndex();
        var key = new PlayerAdvancements.TriggerInstanceKey(holder, "tick");
        var monitor = new Object();
        var reference = new AtomicReference<AdvancementListenerMap<PlayerTrigger.TriggerInstance>>();
        var map = new AdvancementListenerMap<PlayerTrigger.TriggerInstance>(monitor, instance -> {
            assertTrue(Thread.holdsLock(monitor));
            index.observeInsertion(requirements, CriteriaTriggers.TICK, instance);
            if (instance != local.triggerInstance()) {
                assertSame(local.triggerInstance(), reference.get().get(key),
                        "the unknown value must not be visible before invalidation");
                assertTrue(index.hasUnindexedListeners(requirements,
                        Map.of(CriteriaTriggers.TICK, reference.get())));
            }
        });
        reference.set(map);
        map.put(key, local.triggerInstance());
        var entry = map.entrySet().iterator().next();
        var unknown = PlayerTrigger.TriggerInstance.tick().triggerInstance();
        entry.setValue(unknown);
        assertTrue(index.hasUnindexedListeners(requirements, Map.of(CriteriaTriggers.TICK, map)));
        assertSame(unknown, map.get(key));
        map.put(key, local.triggerInstance());
        assertSame(local.triggerInstance(), entry.getValue(),
                "a wrapped native entry must remain a live entry rather than a copied value");
    }

    @Test
    void preparedLocalAdmissionDoesNotEnumerateListenerValues() throws Exception {
        var holder = holder(Map.of("tick", PlayerTrigger.TriggerInstance.tick()));
        var requirements = prepare(holder.value(), pendingItems());
        var active = nativeAdvancements();
        active.track(requirements);
        active.register(holder);
        var nativeMap = active.getTriggerMapForType(CriteriaTriggers.TICK);
        // Keep the actual indexed identities and membership, but fail any traversal:
        // neither whole-player nor exact-trigger local admission may inspect them.
        active.listeners.put(CriteriaTriggers.TICK,
                new AbstractMap<PlayerAdvancements.TriggerInstanceKey, PlayerTrigger.TriggerInstance>() {
                    @Override
                    public int size() {
                        return nativeMap.size();
                    }

                    @Override
                    public PlayerTrigger.TriggerInstance get(Object key) {
                        return nativeMap.get(key);
                    }

                    @Override
                    public Set<Entry<PlayerAdvancements.TriggerInstanceKey, PlayerTrigger.TriggerInstance>> entrySet() {
                        throw new AssertionError("prepared local admission enumerated listener values");
                    }
                });
        assertFalse(requirements.requiresGlobal(active, ANY));
        assertFalse(requirements.requiresGlobal(active, PLAYER_USE));
        assertFalse(requirements.requiresGlobal(active, IDLE_PLAYER_OR_ENTITY_INTERACTION));
        assertFalse(requirements.requiresGlobal(active, CriteriaTriggers.TICK));
    }

    private static Criterion<?> remoteInventory() {
        var nbt = EntityPredicate.wrap(new EntityPredicate(Map.of(
                EntityNbtPredicate.CODEC, new EntityNbtPredicate(new NbtPredicate(new CompoundTag())))));
        return CriteriaTriggers.INVENTORY_CHANGED.createCriterion(new InventoryChangeTrigger.TriggerInstance(
                Optional.of(nbt), InventoryChangeTrigger.TriggerInstance.Slots.ANY, List.of()));
    }

    private static AdvancementHolder holder(Map<String, Criterion<?>> criteria) {
        var builder = Advancement.Builder.advancement();
        criteria.forEach(builder::addCriterion);
        return builder.build(Identifier.fromNamespaceAndPath("fabrolia", "admission"));
    }

    private static NativeAdvancements nativeAdvancements() throws Exception {
        var active = allocate(NativeAdvancements.class);
        active.listeners = new IdentityHashMap<>();
        field(PlayerAdvancements.class, "activeTriggers").set(active, active.listeners);
        field(PlayerAdvancements.class, "progress").set(active, new HashMap<>());
        field(PlayerAdvancements.class, "progressChanged").set(active, new HashSet<>());
        field(PlayerAdvancements.class, "visible").set(active, new HashSet<>());
        field(PlayerAdvancements.class, "rootsToUpdate").set(active, new HashSet<>());
        field(PlayerAdvancements.class, "tree").set(active, new AdvancementTree());
        return active;
    }

    private static final class NativeAdvancements extends PlayerAdvancements
            implements AdvancementRequirements.ActiveListeners {
        private Map<CriterionTrigger<?>, Map<TriggerInstanceKey, ? extends CriterionTriggerInstance>> listeners;
        private AdvancementRequirements trackedRequirements;
        private AdvancementRequirements.ListenerIndex index;

        private NativeAdvancements() {
            super(null, null, null, null, null);
        }

        @Override
        public Map<CriterionTrigger<?>, Map<TriggerInstanceKey, ? extends CriterionTriggerInstance>>
                fabrolia$activeTriggers() {
            assertTrue(Thread.holdsLock(this), "admission must hold the native listener monitor");
            return listeners;
        }

        @Override
        public boolean fabrolia$hasUnindexedListeners(AdvancementRequirements requirements) {
            return index == null
                    ? AdvancementRequirements.ActiveListeners.super.fabrolia$hasUnindexedListeners(requirements)
                    : index.hasUnindexedListeners(requirements, listeners);
        }

        void track(AdvancementRequirements requirements) {
            trackedRequirements = requirements;
            index = new AdvancementRequirements.ListenerIndex();
        }

        void register(AdvancementHolder holder) throws Exception {
            var method = PlayerAdvancements.class.getDeclaredMethod("registerListeners", AdvancementHolder.class);
            method.setAccessible(true);
            synchronized (this) {
                if (index != null) {
                    for (Criterion<?> criterion : holder.value().criteria().values()) {
                        var trigger = criterion.trigger();
                        listeners.computeIfAbsent(trigger, ignored -> new AdvancementListenerMap<>(this,
                                instance -> index.observeInsertion(trackedRequirements, trigger, instance)));
                    }
                }
                method.invoke(this, holder);
            }
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static HolderLookup.RegistryLookup<Item> pendingItems() {
        var registry = new MappedRegistry<Item>(Registries.ITEM, Lifecycle.stable());
        var stone = registry.register(STONE, Items.STONE, RegistrationInfo.BUILT_IN);
        registry.freeze();
        return registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM,
                Map.of(PENDING_TAG, List.of(stone)))).lookup();
    }

    private static Advancement itemAdvancement(HolderSet<Item> items) {
        var predicate = new ItemPredicate(Optional.of(items), MinMaxBounds.Ints.ANY, DataComponentMatchers.ANY);
        return Advancement.Builder.advancement()
                .addCriterion("item", InventoryChangeTrigger.TriggerInstance.hasItems(predicate))
                .build(Identifier.fromNamespaceAndPath("fabrolia", "pending_items")).value();
    }

    private static AdvancementRequirements prepare(Advancement advancement, HolderLookup.RegistryLookup<Item> lookup) {
        return AdvancementRequirements.prepare(List.of(advancement),
                HolderLookup.Provider.create(Stream.of(lookup)));
    }

    private static AdvancementRequirements classify(String trigger, String json) {
        String id = trigger.indexOf(':') < 0 ? "minecraft:" + trigger : trigger;
        return AdvancementRequirements.classify(id, JsonParser.parseString(json));
    }
}
