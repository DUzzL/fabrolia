package com.axalotl.fabrolia.common.parallelised.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.PlayerAdvancements;

import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Collections;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Immutable, conservative resource requirements; contains no live contexts or callbacks. */
public final class AdvancementRequirements {
    private final boolean global;
    private final boolean playerUse;
    private final Map<CriterionTrigger<?>, Map<CriterionTriggerInstance, AdvancementRequirements>> criteria;
    private final CriterionTrigger<?>[][] nonlocalTriggers;

    private AdvancementRequirements(boolean global, boolean playerUse) {
        this(global, playerUse, null);
    }

    private AdvancementRequirements(boolean global, boolean playerUse,
                                    Map<CriterionTrigger<?>, Map<CriterionTriggerInstance, AdvancementRequirements>> criteria) {
        this.global = global;
        this.playerUse = playerUse;
        this.criteria = criteria;
        this.nonlocalTriggers = criteria == null ? null : nonlocalTriggers(criteria);
    }

    public boolean global() {
        return global;
    }

    /** Native listener view. Read only while holding the owning PlayerAdvancements monitor. */
    public interface ActiveListeners {
        Map<CriterionTrigger<?>, Map<PlayerAdvancements.TriggerInstanceKey, ? extends CriterionTriggerInstance>>
                fabrolia$activeTriggers();

        /** Without insertion tracking, establish the witness by inspecting the actual map. */
        default boolean fabrolia$hasUnindexedListeners(AdvancementRequirements requirements) {
            if (requirements.criteria == null) return true;
            for (var entry : fabrolia$activeTriggers().entrySet()) {
                for (CriterionTriggerInstance instance : entry.getValue().values()) {
                    if (!requirements.contains(entry.getKey(), instance)) return true;
                }
            }
            return false;
        }
    }

    /** Per-player insertion witness, read and changed under the native listener monitor. */
    public static final class ListenerIndex {
        private AdvancementRequirements indexedRequirements;
        private boolean unindexed;

        public void observeInsertion(AdvancementRequirements requirements, CriterionTrigger<?> trigger,
                                     CriterionTriggerInstance instance) {
            if (indexedRequirements != null && indexedRequirements != requirements
                    || !requirements.contains(trigger, instance)) {
                unindexed = true;
            }
            indexedRequirements = requirements;
        }

        public boolean hasUnindexedListeners(AdvancementRequirements requirements, Map<?, ?> activeTriggers) {
            return !activeTriggers.isEmpty() && (unindexed || indexedRequirements != requirements);
        }

        /** A partial removal cannot certify that every unknown listener has gone. */
        public void clearIfEmpty(Map<?, ?> activeTriggers) {
            if (activeTriggers.isEmpty()) {
                indexedRequirements = null;
                unindexed = false;
            }
        }
    }

    enum Producer {
        ANY, PLAYER_USE, IDLE_PLAYER_OR_ENTITY_INTERACTION
    }

    public interface Provider {
        AdvancementRequirements fabrolia$advancementRequirements();

        default boolean fabrolia$tracksListenerInsertions() {
            return false;
        }
    }

    /** Exact preparation result, so overlapping reload preparations cannot exchange policy. */
    public static final class Prepared extends HashMap<Identifier, Advancement> {
        private final AdvancementRequirements requirements;

        public Prepared(Map<Identifier, Advancement> values, AdvancementRequirements requirements) {
            super(values);
            this.requirements = requirements;
        }

        public AdvancementRequirements requirements() {
            return requirements;
        }
    }

    public static final AdvancementRequirements UNKNOWN = new AdvancementRequirements(true, false);
    private static final AdvancementRequirements LOCAL = new AdvancementRequirements(false, false);
    private static final AdvancementRequirements PLAYER_USE = new AdvancementRequirements(false, true);

    // Audited native 26.2 schemas. Anything outside this vocabulary remains supported,
    // but only in a preselected global producer. In particular this is not a namespace
    // heuristic which treats arbitrary registered minecraft/custom predicates as local.
    private static final Set<String> TRIGGERS = Set.of(
            "allay_drop_item_on_block", "avoid_vibration", "bee_nest_destroyed", "bred_animals",
            "brewed_potion", "changed_dimension", "channeled_lightning", "construct_beacon",
            "consume_item", "crafter_recipe_crafted", "cured_zombie_villager", "effects_changed",
            "enchanted_item", "enter_block", "entity_hurt_player", "entity_killed_player",
            "fall_after_explosion", "fall_from_height", "filled_bucket", "fishing_rod_hooked",
            "hero_of_the_village", "impossible", "inventory_changed", "item_durability_changed",
            "item_used_on_block", "kill_mob_near_sculk_catalyst", "killed_by_arrow", "levitation",
            "lightning_strike", "location", "nether_travel", "placed_block",
            "player_generates_container_loot", "player_hurt_entity", "player_interacted_with_entity",
            "player_killed_entity", "player_sheared_equipment", "recipe_crafted", "recipe_unlocked",
            "ride_entity_in_lava", "shot_crossbow", "slept_in_bed", "slide_down_block", "spear_mobs",
            "started_riding", "summoned_entity", "tame_animal", "target_hit",
            "thrown_item_picked_up_by_entity", "thrown_item_picked_up_by_player", "tick",
            "used_totem", "using_item", "villager_trade");
    private static final Set<String> CONDITIONS = Set.of(
            "minecraft:entity_properties", "minecraft:location_check", "minecraft:block_state_property",
            "minecraft:match_tool", "minecraft:all_of", "minecraft:inverted", "minecraft:any_of");
    private static final Set<String> ENTITY_PARTS = Set.of(
            "minecraft:entity_type", "minecraft:distance", "minecraft:equipment", "minecraft:flags",
            "minecraft:location", "minecraft:passenger", "minecraft:stepping_on", "minecraft:vehicle",
            "minecraft:type_specific/player", "minecraft:type_specific/lightning", "minecraft:components");
    private static final Set<String> FIELDS = Set.of(
            "absolute", "axis", "biomes", "block", "blocked", "blocks", "blocks_set_on_fire",
            "body", "bystander", "cause", "charges", "chest", "child", "color", "components",
            "condition", "count", "creaking_heart_state", "damage", "dealt", "dimension",
            "direct_entity", "distance", "effects", "enchantments", "entity", "expected", "facing",
            "feet", "fired_from_weapon", "head", "horizontal", "id", "ingredients", "is_baby",
            "item", "items", "killing_blow", "legs", "level", "levels", "lightning", "lit",
            "location", "looking_at", "loot_table", "mainhand", "max", "min",
            "minecraft:absorption", "minecraft:bad_omen", "minecraft:banner_patterns",
            "minecraft:blindness", "minecraft:breath_of_the_nautilus", "minecraft:cat/variant",
            "minecraft:components", "minecraft:conduit_power", "minecraft:damage", "minecraft:darkness",
            "minecraft:distance", "minecraft:dolphins_grace", "minecraft:enchantments",
            "minecraft:entity_type", "minecraft:equipment", "minecraft:fire_resistance", "minecraft:flags",
            "minecraft:frog/variant", "minecraft:glowing", "minecraft:haste", "minecraft:hero_of_the_village",
            "minecraft:hunger", "minecraft:infested", "minecraft:invisibility", "minecraft:item_name",
            "minecraft:jukebox_playable", "minecraft:jump_boost", "minecraft:levitation", "minecraft:location",
            "minecraft:mining_fatigue", "minecraft:nausea", "minecraft:night_vision", "minecraft:oozing",
            "minecraft:passenger", "minecraft:poison", "minecraft:raid_omen", "minecraft:regeneration",
            "minecraft:resistance", "minecraft:slow_falling", "minecraft:slowness", "minecraft:speed",
            "minecraft:stepping_on", "minecraft:strength", "minecraft:trial_omen",
            "minecraft:type_specific/lightning", "minecraft:type_specific/player", "minecraft:vehicle",
            "minecraft:water_breathing", "minecraft:weakness", "minecraft:weaving", "minecraft:wind_charged",
            "minecraft:wither", "minecraft:wolf/variant", "num_bees_inside", "occupied", "offsetX",
            "offsetY", "offsetZ", "ominous", "parent", "partner", "pattern", "player", "position",
            "predicate", "predicates", "projectile", "properties", "recipe", "recipe_id", "signal_strength",
            "slots", "smokey", "source", "start_position", "state", "structures", "tags", "term", "terms",
            "to", "translate", "type", "unique_entity_types", "victims", "waterlogged", "x", "y", "z");

    public boolean requiresGlobalPlayerUse() {
        return global || playerUse;
    }
    boolean mayRequireGlobalEntityInteraction() {
        return nonlocalTriggers == null
                || nonlocalTriggers[Producer.IDLE_PLAYER_OR_ENTITY_INTERACTION.ordinal()].length != 0;
    }

    private static CriterionTrigger<?>[][] nonlocalTriggers(
            Map<CriterionTrigger<?>, Map<CriterionTriggerInstance, AdvancementRequirements>> criteria) {
        Producer[] producers = Producer.values();
        CriterionTrigger<?>[][] result = new CriterionTrigger<?>[producers.length][];
        for (Producer producer : producers) {
            var triggers = new ArrayList<CriterionTrigger<?>>();
            for (var entry : criteria.entrySet()) {
                if (excluded(entry.getKey(), producer)) continue;
                for (AdvancementRequirements requirement : entry.getValue().values()) {
                    if (!requirement.requiresGlobalPlayerUse()) continue;
                    triggers.add(entry.getKey());
                    break;
                }
            }
            result[producer.ordinal()] = triggers.toArray(CriterionTrigger<?>[]::new);
        }
        return result;
    }

    private static boolean excluded(CriterionTrigger<?> trigger, Producer producer) {
        return producer != Producer.ANY
                && (trigger == CriteriaTriggers.CHANGED_DIMENSION || trigger == CriteriaTriggers.NETHER_TRAVEL
                    || producer == Producer.IDLE_PLAYER_OR_ENTITY_INTERACTION && isPlayerUseOnlyTrigger(trigger));
    }


    private static boolean isPlayerUseOnlyTrigger(CriterionTrigger<?> trigger) {
        return trigger == CriteriaTriggers.USING_ITEM || trigger == CriteriaTriggers.CONSUME_ITEM
                || trigger == CriteriaTriggers.PLACED_BLOCK || trigger == CriteriaTriggers.ITEM_USED_ON_BLOCK;
    }

    private static boolean isSpatialPlayerUseTrigger(CriterionTrigger<?> trigger) {
        return trigger == CriteriaTriggers.USING_ITEM || trigger == CriteriaTriggers.PLACED_BLOCK
                || trigger == CriteriaTriggers.ITEM_USED_ON_BLOCK;
    }

    /** Mutable listener membership is observed at admission, never cached in resource policy. */
    boolean requiresGlobal(PlayerAdvancements advancements, Producer producer) {
        if (criteria == null || !(advancements instanceof ActiveListeners active)) return true;
        synchronized (advancements) {
            if (active.fabrolia$hasUnindexedListeners(this)) return true;
            for (CriterionTrigger<?> trigger : nonlocalTriggers[producer.ordinal()]) {
                var listeners = advancements.getTriggerMapForType(trigger);
                if (listeners == null) continue;
                for (CriterionTriggerInstance instance : listeners.values()) {
                    AdvancementRequirements requirement = requirement(trigger, instance);
                    if (requirement == null || requirement.requiresGlobalPlayerUse()) return true;
                }
            }
        }
        return false;
    }

    boolean requiresGlobal(PlayerAdvancements advancements, CriterionTrigger<?> trigger) {
        if (criteria == null) return true;
        synchronized (advancements) {
            var listeners = advancements.getTriggerMapForType(trigger);
            if (listeners == null || listeners.isEmpty()) return false;
            if (advancements instanceof ActiveListeners active && !active.fabrolia$hasUnindexedListeners(this)
                    && !hasNonlocalTrigger(trigger)) return false;
            for (CriterionTriggerInstance instance : listeners.values()) {
                AdvancementRequirements requirement = requirement(trigger, instance);
                if (requirement == null || requirement.requiresGlobalPlayerUse()) return true;
            }
        }
        return false;
    }

    private boolean hasNonlocalTrigger(CriterionTrigger<?> trigger) {
        for (CriterionTrigger<?> nonlocal : nonlocalTriggers[Producer.ANY.ordinal()]) {
            if (trigger == nonlocal) return true;
        }
        return false;
    }

    /** Exact identity witness only; never invokes user predicates or codecs. */
    public boolean contains(CriterionTrigger<?> trigger, CriterionTriggerInstance instance) {
        return criteria != null && requirement(trigger, instance) != null;
    }

    private AdvancementRequirements requirement(CriterionTrigger<?> trigger, CriterionTriggerInstance instance) {
        Map<CriterionTriggerInstance, AdvancementRequirements> instances = criteria.get(trigger);
        return instances == null ? null : instances.get(instance);
    }

    public static AdvancementRequirements prepare(Collection<Advancement> advancements,
                                                   HolderLookup.Provider registries) {
        var ops = serializationOps(registries);
        AdvancementRequirements result = LOCAL;
        var index = new IdentityHashMap<CriterionTrigger<?>, Map<CriterionTriggerInstance, AdvancementRequirements>>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (Advancement advancement : advancements) {
            for (Criterion<?> criterion : advancement.criteria().values()) {
                // The traversal's visited set is not a memoized safe verdict. A
                // shared opaque child must taint every criterion which contains it.
                seen.clear();
                AdvancementRequirements requirement = UNKNOWN;
                // Extension instances must not acquire local authority by encoding
                // a familiar native schema. Never execute their codec or matcher.
                if (nativePredicateStructure(criterion.trigger().getClass())
                        && !hasOpaquePredicate(criterion.triggerInstance(), seen)) {
                    var encoded = Criterion.CODEC.encodeStart(ops, criterion).result();
                    if (encoded.isPresent()) {
                        JsonObject json = encoded.get().getAsJsonObject();
                        requirement = classify(json.get("trigger").getAsString(), json.get("conditions"));
                        if (requirement.playerUse && !isSpatialPlayerUseTrigger(criterion.trigger())) {
                            requirement = UNKNOWN;
                        }
                    }
                }
                index.computeIfAbsent(criterion.trigger(), ignored -> new IdentityHashMap<>())
                        .put(criterion.triggerInstance(), requirement);
                result = result.union(requirement);
            }
        }
        index.replaceAll((trigger, instances) -> Collections.unmodifiableMap(instances));
        return new AdvancementRequirements(result.global, result.playerUse, Collections.unmodifiableMap(index));
    }

    private static RegistryOps<JsonElement> serializationOps(HolderLookup.Provider registries) {
        Map<ResourceKey<?>, RegistryOps.RegistryInfo<?>> info = new HashMap<>();
        registries.listRegistries().forEach(lookup -> info.put(lookup.key(), serializationInfo(lookup)));
        return RegistryOps.create(JsonOps.INSTANCE, new RegistryOps.RegistryInfoLookup() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(
                    ResourceKey<? extends Registry<? extends T>> key) {
                return Optional.ofNullable((RegistryOps.RegistryInfo<T>) info.get(key));
            }
        });
    }

    private static <T> RegistryOps.RegistryInfo<T> serializationInfo(HolderLookup.RegistryLookup<T> lookup) {
        // Pending tag lookups retain native holders owned by their parent registry.
        // Keep the pending getter, but do not mistake that view for a holder owner.
        HolderLookup.RegistryLookup<T> owner = lookup;
        while (owner instanceof HolderLookup.RegistryLookup.Delegate<T> delegate) owner = delegate.parent();
        return new RegistryOps.RegistryInfo<>(owner, lookup, lookup.registryLifecycle());
    }

    // Read only immutable declarative fields, never record accessors or compiled
    // matcher/supplier closures. This also catches an extension that advertises a
    // native codec while its actual predicate implementation is not native.
    private static final ClassValue<Field[]> DECLARED_FIELDS = new ClassValue<>() {
        @Override protected Field[] computeValue(Class<?> type) {
            ArrayList<Field> fields = new ArrayList<>();
            for (Class<?> cursor = type; cursor != null && nativePredicateStructure(cursor);
                 cursor = cursor.getSuperclass()) {
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    String name = field.getName();
                    if (name.equals("combinedPart") || name.equals("compositePredicates")
                            || field.getType() == java.util.function.Predicate.class
                            || field.getType() == java.util.function.Supplier.class) continue;
                    if (!field.trySetAccessible()) return null;
                    fields.add(field);
                }
            }
            return fields.toArray(Field[]::new);
        }
    };

    private static boolean nativePredicateStructure(Class<?> type) {
        String name = type.getName();
        if (!(name.startsWith("net.minecraft.advancements.predicates.")
                || name.startsWith("net.minecraft.advancements.triggers.")
                || name.startsWith("net.minecraft.world.level.storage.loot.predicates.")
                || name.startsWith("net.minecraft.core.component.predicates."))) return false;
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && type.getClassLoader() == Criterion.class.getClassLoader()
                && source.equals(Criterion.class.getProtectionDomain().getCodeSource());
    }

    private static boolean hasOpaquePredicate(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) return false;
        Class<?> type = value.getClass();
        if (value instanceof HolderSet<?>) {
            // Named sets are declarations here; their contents are not bound yet.
            if (type == HolderSet.Named.class) return false;
            if (type != HolderSet.Direct.class) return true;
        }
        if (value instanceof Holder<?>) {
            if (type == Holder.Reference.class) return false;
            if (value instanceof Holder.Direct<?> direct) return hasOpaquePredicate(direct.value(), seen);
            return true;
        }
        boolean nativeStructure = nativePredicateStructure(type);
        if (!nativeStructure && (value instanceof net.minecraft.advancements.CriterionTriggerInstance
                || value instanceof net.minecraft.advancements.predicates.ContextAwarePredicate
                || value instanceof net.minecraft.advancements.predicates.entity.EntitySubPredicate
                || value instanceof net.minecraft.world.level.storage.loot.predicates.LootItemCondition
                || value instanceof net.minecraft.core.component.predicates.DataComponentPredicate)) return true;
        if (value instanceof Optional<?> optional) return hasOpaquePredicate(optional.orElse(null), seen);
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) if (hasOpaquePredicate(child, seen)) return true;
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) if (hasOpaquePredicate(child, seen)) return true;
            return false;
        }
        if (!nativeStructure) return false;
        Field[] fields = DECLARED_FIELDS.get(type);
        if (fields == null) return true;
        try {
            for (Field field : fields) if (hasOpaquePredicate(field.get(value), seen)) return true;
        } catch (IllegalAccessException inaccessible) {
            return true;
        }
        return false;
    }

    /** Structural analysis only: never invokes a matcher, serializes an entity or draws RNG. */
    static AdvancementRequirements classify(String trigger, JsonElement conditions) {
        if (!trigger.startsWith("minecraft:") || !TRIGGERS.contains(trigger.substring(10))) return UNKNOWN;
        Footprint footprint = classifyFootprint(conditions, false);
        if (footprint == Footprint.GLOBAL) return UNKNOWN;
        if (footprint == Footprint.LOCAL) return LOCAL;
        return switch (trigger) {
            case "minecraft:using_item", "minecraft:placed_block", "minecraft:item_used_on_block" -> PLAYER_USE;
            default -> UNKNOWN;
        };
    }

    private enum Footprint {
        LOCAL, SPATIAL, GLOBAL;

        Footprint union(Footprint other) {
            return ordinal() >= other.ordinal() ? this : other;
        }
    }

    private static Footprint classifyFootprint(JsonElement value, boolean dataKeys) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return Footprint.LOCAL;
        Footprint result = Footprint.LOCAL;
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) result = result.union(classifyFootprint(child, dataKeys));
            return result;
        }
        for (var entry : value.getAsJsonObject().entrySet()) {
            String field = entry.getKey();
            JsonElement child = entry.getValue();
            // Full NBT, references and opaque extensions need all-producer admission,
            // even when encountered in a known player-use trigger.
            if (!FIELDS.contains(field)) return Footprint.GLOBAL;
            if (field.indexOf(':') >= 0 && !dataKeys && !ENTITY_PARTS.contains(field)) return Footprint.GLOBAL;
            if (field.equals("condition") && (!child.isJsonPrimitive()
                    || !CONDITIONS.contains(child.getAsString()))) return Footprint.GLOBAL;
            if ((field.equals("player") || field.equals("predicate"))
                    && (child.isJsonPrimitive() || containsPrimitiveElement(child))) return Footprint.GLOBAL;
            // Grid width cannot bound a ray's native broad candidates, or an offset
            // from an origin already in the claimed halo. Admit the entire producer.
            if (field.equals("looking_at")) result = result.union(Footprint.SPATIAL);
            if (field.equals("offsetX") || field.equals("offsetY") || field.equals("offsetZ")) {
                if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isNumber()) return Footprint.GLOBAL;
                double distance = child.getAsDouble();
                if (!Double.isFinite(distance)) return Footprint.GLOBAL;
                if (distance != 0) result = result.union(Footprint.SPATIAL);
            }
            result = result.union(classifyFootprint(child, field.equals("effects") || field.equals("components")
                    || field.equals("minecraft:components") || field.equals("predicates")));
        }
        return result;
    }

    private static boolean containsPrimitiveElement(JsonElement value) {
        if (!value.isJsonArray()) return false;
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonPrimitive()) return true;
        }
        return false;
    }

    private AdvancementRequirements union(AdvancementRequirements other) {
        return new AdvancementRequirements(global || other.global, playerUse || other.playerUse);
    }
}
