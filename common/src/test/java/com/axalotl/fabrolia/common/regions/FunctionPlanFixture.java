package com.axalotl.fabrolia.common.regions;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real vanilla parse of datapack functions (the dedicated-server command tree
 * and {@code CommandFunction.fromLines}, exactly like a reload) plus a
 * {@link FunctionPlanner.Sources} over them. Predicates go through the 26.3
 * loot condition codec both ways, like the registry path at activation.
 */
final class FunctionPlanFixture implements FunctionPlanner.Sources {

    private static HolderLookup.Provider lookup;
    private static Commands commands;

    static Commands commandsForTest() {
        bootstrap();
        return commands;
    }

    static synchronized void bootstrap() {
        if (commands != null) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        lookup = VanillaRegistries.createWorldLookup();
        commands = new Commands(Commands.CommandSelection.DEDICATED,
                CommandBuildContext.simple(lookup, FeatureFlags.VANILLA_SET));
    }

    static CommandSourceStack compileSource() {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                LevelBasedPermissionSet.GAMEMASTER, CommonComponents.EMPTY, null);
    }

    private final LinkedHashMap<Identifier, CommandFunction<CommandSourceStack>> functions = new LinkedHashMap<>();
    private final HashMap<Identifier, FunctionIR.Function> irs = new HashMap<>();
    private final HashMap<Identifier, List<Identifier>> tags = new HashMap<>();
    private final HashMap<String, JsonElement> predicates = new HashMap<>();
    private final ArrayList<Identifier> rewards = new ArrayList<>();

    FunctionPlanFixture() {
        bootstrap();
    }

    FunctionPlanFixture function(String id, String... lines) {
        Identifier key = Identifier.parse(id);
        List<String> source = Arrays.asList(lines);
        CommandFunction<CommandSourceStack> fn =
                CommandFunction.fromLines(key, commands.getDispatcher(), compileSource(), source);
        functions.put(key, fn);
        irs.put(key, FunctionIR.of(key, fn, source));
        return this;
    }

    FunctionPlanFixture tag(String id, String... members) {
        ArrayList<Identifier> ids = new ArrayList<>();
        for (String m : members) ids.add(Identifier.parse(m));
        tags.put(Identifier.parse(id), ids);
        return this;
    }

    /** Pack JSON decoded and re-encoded by the loot condition codec. */
    FunctionPlanFixture predicate(String id, String packJson) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        LootItemCondition condition = LootItemCondition.DIRECT_CODEC
                .parse(ops, JsonParser.parseString(packJson)).getOrThrow();
        predicates.put(id, LootItemCondition.DIRECT_CODEC.encodeStart(ops, condition).getOrThrow());
        return this;
    }

    FunctionPlanFixture reward(String functionId) {
        rewards.add(Identifier.parse(functionId));
        return this;
    }

    FunctionPlan plan() {
        return FunctionPlanner.build(this);
    }

    CommandFunction<CommandSourceStack> fn(String id) {
        return functions.get(Identifier.parse(id));
    }

    @Override
    public FunctionIR.Function ir(Identifier id) {
        return irs.get(id);
    }

    @Override
    public CommandFunction<CommandSourceStack> function(Identifier id) {
        return functions.get(id);
    }

    @Override
    public Iterable<Identifier> functionIds() {
        return functions.keySet();
    }

    @Override
    public List<Identifier> tag(Identifier id) {
        return tags.get(id);
    }

    @Override
    public JsonElement predicate(String id) {
        return predicates.get(id);
    }

    @Override
    public List<Identifier> rewardFunctions() {
        return rewards;
    }

    static Map<Integer, FunctionPlan.LineKind> kinds(FunctionPlan.RootPlan root) {
        LinkedHashMap<Integer, FunctionPlan.LineKind> out = new LinkedHashMap<>();
        for (FunctionPlan.LinePlan l : root.lines()) out.put(l.sourceLine(), l.kind());
        return out;
    }
}
