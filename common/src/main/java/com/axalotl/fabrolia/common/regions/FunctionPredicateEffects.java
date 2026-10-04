package com.axalotl.fabrolia.common.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Allowlist walk of a loot predicate (the registry object re-encoded to JSON)
 * evaluated in a selector / {@code execute if predicate} context, where only
 * {@code this} (the bound entity) and {@code origin} exist.
 *
 * <p>Owner-local conditions read the bound entity or its own block column, the
 * world clock/weather, scores and storage. Anything else (other entities,
 * offset world reads, unknown condition types) marks the body global.
 */
final class FunctionPredicateEffects {

    private FunctionPredicateEffects() {}

    private static final Set<String> NO_CONTEXT_CONDITIONS = Set.of(
            "minecraft:killed_by_player", "minecraft:survives_explosion",
            "minecraft:match_tool", "minecraft:table_bonus",
            "minecraft:damage_source_properties", "minecraft:block_state_property",
            "minecraft:enchantment_active_check",
            "minecraft:random_chance", "minecraft:random_chance_with_enchanted_bonus",
            "minecraft:time_check", "minecraft:weather_check", "minecraft:value_check");

    private static final Set<String> ENTITY_KEYS_LOCAL = Set.of(
            "type", "flags", "equipment", "effects", "location", "stepping_on",
            "movement_affected_by", "movement", "periodic_tick", "slots", "components",
            "predicates", "distance", "team", "type_specific", "nbt");

    /**
     * @param resolver predicate id → encoded JSON (null when absent)
     * @param selfPlayerPossible the bound entity may be a player
     * @param originAtSelf the loot origin is the bound entity's own position
     */
    static void classify(String id, Function<String, JsonElement> resolver,
                         boolean selfPlayerPossible, boolean originAtSelf,
                         FunctionEffects fx, String where) {
        classifyRef(id, resolver, selfPlayerPossible, originAtSelf, fx, where, new HashSet<>());
    }

    private static void classifyRef(String id, Function<String, JsonElement> resolver,
                                    boolean selfPlayerPossible, boolean originAtSelf,
                                    FunctionEffects fx, String where, Set<String> visiting) {
        String key = id.contains(":") ? id : "minecraft:" + id;
        if (!visiting.add(key)) {
            fx.global(where, "recursive predicate " + key);
            return;
        }
        JsonElement json = resolver.apply(key);
        if (json == null) {
            fx.global(where, "unknown predicate " + key);
            return;
        }
        condition(json, resolver, selfPlayerPossible, originAtSelf, fx,
                where + " predicate " + key, visiting);
        visiting.remove(key);
    }

    private static void condition(JsonElement json, Function<String, JsonElement> resolver,
                                  boolean selfPlayerPossible, boolean originAtSelf,
                                  FunctionEffects fx, String where, Set<String> visiting) {
        if (json.isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray()) {
                condition(e, resolver, selfPlayerPossible, originAtSelf, fx, where, visiting);
            }
            return;
        }
        if (!json.isJsonObject()) {
            fx.global(where, "unreadable predicate");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        // 26.x encodes the condition kind under "type" (dispatch codec);
        // older packs wrote "condition".
        String type = string(o, "type");
        if (type == null) type = string(o, "condition");
        if (type == null) {
            fx.global(where, "predicate without condition type");
            return;
        }
        if (!type.contains(":")) type = "minecraft:" + type;
        switch (type) {
            case "minecraft:inverted" -> condition(o.get("term"), resolver,
                    selfPlayerPossible, originAtSelf, fx, where, visiting);
            case "minecraft:all_of", "minecraft:any_of" -> {
                JsonElement terms = o.get("terms");
                if (terms == null) {
                    fx.global(where, type + " without terms");
                } else {
                    condition(terms, resolver, selfPlayerPossible, originAtSelf, fx, where, visiting);
                }
            }
            case "minecraft:reference" -> classifyRef(string(o, "name"), resolver,
                    selfPlayerPossible, originAtSelf, fx, where, visiting);
            case "minecraft:entity_properties" -> {
                if (!"this".equals(string(o, "entity"))) {
                    fx.global(where, "entity_properties of " + string(o, "entity"));
                    return;
                }
                JsonElement predicate = o.get("predicate");
                if (predicate != null && predicate.isJsonObject()) {
                    entityPredicate(predicate.getAsJsonObject(), selfPlayerPossible, fx, where);
                }
            }
            case "minecraft:entity_scores" -> {
                if (!"this".equals(string(o, "entity"))) {
                    fx.global(where, "entity_scores of " + string(o, "entity"));
                    return;
                }
                JsonElement scores = o.get("scores");
                if (scores != null && scores.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : scores.getAsJsonObject().entrySet()) {
                        fx.read("score:" + e.getKey());
                        numberProviders(e.getValue(), fx, where);
                    }
                }
            }
            case "minecraft:location_check" -> {
                if (!originAtSelf) {
                    fx.global(where, "location_check at a non-entity origin");
                    return;
                }
                for (String axis : new String[]{"offsetX", "offsetY", "offsetZ"}) {
                    JsonElement off = o.get(axis);
                    if (off != null && off.getAsInt() != 0) {
                        fx.global(where, "location_check with an offset");
                        return;
                    }
                }
            }
            default -> {
                if (!NO_CONTEXT_CONDITIONS.contains(type)) {
                    fx.global(where, "predicate condition " + type);
                    return;
                }
                numberProviders(o, fx, where);
            }
        }
    }

    private static void entityPredicate(JsonObject p, boolean selfPlayerPossible,
                                        FunctionEffects fx, String where) {
        boolean playerPossible = selfPlayerPossible;
        JsonElement type = p.get("type");
        if (type != null && type.isJsonPrimitive()) {
            String t = type.getAsString();
            playerPossible = playerPossible && ("minecraft:player".equals(t) || "player".equals(t)
                    || t.startsWith("#"));
        }
        for (Map.Entry<String, JsonElement> e : p.entrySet()) {
            // 26.3 encodes entity sub-predicates with namespaced keys.
            String key = e.getKey().startsWith("minecraft:")
                    ? e.getKey().substring("minecraft:".length()) : e.getKey();
            if (!ENTITY_KEYS_LOCAL.contains(key)) {
                fx.global(where, "entity predicate field " + key);
                return;
            }
            switch (key) {
                case "nbt" -> {
                    if (playerPossible) {
                        fx.global(where, "full NBT read of a player");
                        return;
                    }
                    fx.read("entitydata");
                }
                case "team" -> fx.read("team");
                case "type_specific" -> {
                    if (e.getValue().isJsonObject()) {
                        JsonObject ts = e.getValue().getAsJsonObject();
                        String tsType = string(ts, "type");
                        if ("minecraft:lightning".equals(tsType) || "lightning".equals(tsType)) {
                            fx.global(where, "lightning sub-predicate");
                            return;
                        }
                        if (ts.has("looking_at")) {
                            fx.global(where, "player looking_at sub-predicate");
                            return;
                        }
                        if (ts.has("advancements")) fx.read("adv:*");
                    }
                }
                default -> { }
            }
        }
    }

    /** Score/storage number providers anywhere below {@code json}. */
    private static void numberProviders(JsonElement json, FunctionEffects fx, String where) {
        if (json == null) return;
        if (json.isJsonArray()) {
            for (JsonElement e : (JsonArray) json) numberProviders(e, fx, where);
            return;
        }
        if (!json.isJsonObject()) return;
        JsonObject o = json.getAsJsonObject();
        String type = string(o, "type");
        if ("minecraft:score".equals(type) || "score".equals(type)) {
            String objective = string(o, "score");
            JsonElement target = o.get("target");
            if (target != null && target.isJsonPrimitive()) {
                if (!"this".equals(target.getAsString())) {
                    fx.global(where, "score provider of " + target.getAsString());
                } else {
                    fx.read("score:" + objective);
                }
            } else if (target != null && target.isJsonObject()) {
                JsonObject t = target.getAsJsonObject();
                String tType = string(t, "type");
                if ("minecraft:fixed".equals(tType) || "fixed".equals(tType)) {
                    fx.fakeRead(string(t, "name"), objective);
                } else if (("minecraft:context".equals(tType) || "context".equals(tType))
                        && "this".equals(string(t, "target"))) {
                    fx.read("score:" + objective);
                } else {
                    fx.global(where, "score provider target " + t);
                }
            }
        } else if ("minecraft:storage".equals(type) || "storage".equals(type)) {
            fx.read("storage:" + string(o, "storage"));
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonObject() || e.getValue().isJsonArray()) {
                numberProviders(e.getValue(), fx, where);
            }
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
