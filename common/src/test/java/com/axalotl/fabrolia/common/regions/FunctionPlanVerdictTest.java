package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.axalotl.fabrolia.common.regions.FunctionPlan.LineKind.ESCALATE;
import static com.axalotl.fabrolia.common.regions.FunctionPlan.LineKind.GLOBAL;
import static com.axalotl.fabrolia.common.regions.FunctionPlan.LineKind.PARTITION;
import static com.axalotl.fabrolia.common.regions.FunctionPlan.LineKind.REARM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analyzer verdicts on the production datapack shapes (BACAP tick_timer, track
 * statistics, armor statues, afk display), parsed by the real 26.3 command tree.
 */
class FunctionPlanVerdictTest {

    private static final String ON_GROUND = """
            {"type":"minecraft:entity_properties","entity":"this","predicate":{"flags":{"is_on_ground":true}}}""";
    private static final String FLYING = """
            {"type":"minecraft:entity_properties","entity":"this","predicate":{"flags":{"is_flying":true}}}""";
    private static final String IN_LIQUID = """
            {"type":"minecraft:entity_properties","entity":"this","predicate":{"location":{"fluid":{}}}}""";

    private static String dimension(String dim) {
        return "{\"type\":\"minecraft:entity_properties\",\"entity\":\"this\",\"predicate\":"
                + "{\"location\":{\"dimension\":\"" + dim + "\"}}}";
    }

    /** BACAP: #minecraft:tick root and its closure, rewards, the 1s timer. */
    private static FunctionPlanFixture bacap(FunctionPlanFixture f) {
        return f
                .function("blazeandcave:tick_timer",
                        "# Function runs once per tick",
                        "execute as @a[scores={bac_event_death=1..}] run function blazeandcave:advancement/event_horizon_fail",
                        "",
                        "# # Dive Bomb - stand on ground fail",
                        "execute as @a[advancements={blazeandcave:technical/dive_bomb_check=true},predicate=blazeandcave:is_on_ground] run advancement revoke @s only blazeandcave:technical/dive_bomb_check",
                        "",
                        "# # Gas",
                        "execute as @e[type=area_effect_cloud,tag=!aec_checked] run function blazeandcave:advancement/check/aec",
                        "",
                        "# # Dimension Penetration",
                        "execute as @e[type=arrow] at @s run function blazeandcave:advancement/dimpen_tag",
                        "",
                        "# # Whack-a-mole and Diver's Dozen counting",
                        "execute as @a[scores={bac_whack_a_mole_count=1..}] if predicate blazeandcave:is_on_ground run scoreboard players set @s bac_whack_a_mole_count 0",
                        "execute as @a[scores={bac_divers_dozen_count=1..}] unless predicate blazeandcave:is_flying run scoreboard players set @s bac_divers_dozen_count 0")
                .function("blazeandcave:advancement/event_horizon_fail",
                        "scoreboard players set @s bac_eventhorizon 0",
                        "scoreboard players set @s bac_event_death 0",
                        "advancement revoke @s only blazeandcave:technical/below_void")
                .function("blazeandcave:advancement/check/aec",
                        "execute unless entity @s[nbt={custom_particle:{type:\"minecraft:dragon_breath\"}}] run tag @s add aec_player_created",
                        "tag @s add aec_checked")
                .function("blazeandcave:advancement/dimpen_tag",
                        "tag @s[predicate=blazeandcave:in_the_overworld] add dimpen_overworld",
                        "tag @s[predicate=blazeandcave:in_the_nether] add dimpen_nether",
                        "tag @s[predicate=blazeandcave:in_the_end] add dimpen_end")
                .function("blazeandcave:advancement/count/whack_a_mole",
                        "scoreboard players add @s bac_whack_a_mole_count 1",
                        "execute if score @s bac_whack_a_mole_count matches 8.. run advancement grant @s only blazeandcave:enchanting/whack_a_mole")
                .function("bacap_rewards:advancement_made_macro",
                        "$scoreboard players add $(adv_id) bac_obtained 1")
                .function("bacap_rewards:challenges/immortal",
                        "function bacap_rewards:advancement_made_macro {adv_id:\"blazeandcave:challenges/immortal\",reward_id:\"challenges/immortal\",tier:\"super_challenge\"}")
                .function("blazeandcave:one_second_timer",
                        "schedule function blazeandcave:one_second_timer 1s",
                        "execute as @a[gamemode=!spectator,advancements={blazeandcave:technical/return_from_void=true},scores={bac_eventhorizon=1}] run advancement grant @s only blazeandcave:end/event_horizon",
                        "execute as @a[scores={bac_divers_dozen_count=1..}] if predicate blazeandcave:in_liquid run scoreboard players set @s bac_divers_dozen_count 0")
                .tag("minecraft:tick", "blazeandcave:tick_timer")
                .predicate("blazeandcave:is_on_ground", ON_GROUND)
                .predicate("blazeandcave:is_flying", FLYING)
                .predicate("blazeandcave:in_liquid", IN_LIQUID)
                .predicate("blazeandcave:in_the_overworld", dimension("overworld"))
                .predicate("blazeandcave:in_the_nether", dimension("the_nether"))
                .predicate("blazeandcave:in_the_end", dimension("the_end"))
                .reward("blazeandcave:advancement/count/whack_a_mole")
                .reward("bacap_rewards:challenges/immortal");
    }

    @Test
    void tickTimerPartitionsAndEscalatesOnlyLinesConflictingWithTheOneSecondTimer() {
        FunctionPlan plan = bacap(new FunctionPlanFixture()).plan();

        FunctionPlan.RootPlan root = plan.tickRoots.getFirst();
        assertTrue(root.partitioned(), root.blocking());
        assertEquals(Map.of(
                2, ESCALATE,   // writes bac_eventhorizon, read by Main-resident one_second_timer
                5, PARTITION,  // advancement revoke @s (C1), is_on_ground predicate
                8, PARTITION,  // AEC nbt read is not a player read
                11, PARTITION, // dimension predicates on the arrow itself
                14, ESCALATE,  // the whack_a_mole reward grants (Main-resident) and writes the count
                15, ESCALATE), // one_second_timer also writes bac_divers_dozen_count
                FunctionPlanFixture.kinds(root));
        assertTrue(root.lines().get(0).reason().contains("bac_eventhorizon"));
        assertTrue(root.lines().get(5).reason().contains("bac_divers_dozen_count"));
    }

    @Test
    void ownerLocalFollowsTheWholeClosure() {
        FunctionPlanFixture f = bacap(new FunctionPlanFixture());
        FunctionPlan plan = f.plan();

        assertFalse(plan.ownerLocal(f.fn("blazeandcave:advancement/count/whack_a_mole")),
                "advancement grant announces and runs rewards: not owner-local");
        assertTrue(plan.ownerLocal(f.fn("blazeandcave:advancement/event_horizon_fail")),
                "@s scores + advancement revoke @s are owner-local");
        assertFalse(plan.ownerLocal(f.fn("bacap_rewards:challenges/immortal")),
                "macro-argument call is never owner-local");
        assertFalse(plan.ownerLocal(f.fn("bacap_rewards:advancement_made_macro")));
    }

    private static FunctionPlanFixture trackStatsAndAfk(FunctionPlanFixture f) {
        return f
                .function("track_stats:tick",
                        "# Desc: Converts scores to usable units",
                        "#",
                        "# Called by: #minecraft:load",
                        "",
                        "execute as @a as @s run function track_stats:math",
                        "",
                        "schedule function track_stats:tick 1t")
                .function("track_stats:math",
                        "scoreboard players operation @s hc_playTime = @s hc_playTick",
                        "scoreboard players operation @s hc_playTime /= 1200 hc_constant",
                        "execute unless entity @s[team=afkDis.afk] run scoreboard players operation @s hc_playTimeShow = @s hc_playTime",
                        "scoreboard players operation @s hc_elytraKm = @s hc_elytraCm",
                        "scoreboard players operation @s hc_elytraKm /= 100000 hc_constant",
                        "scoreboard players operation @s hc_MineOre += @s hc_MineCoal")
                .function("track_stats:load",
                        "scoreboard objectives add hc_constant dummy",
                        "scoreboard players set 1200 hc_constant 1200",
                        "scoreboard players set 100000 hc_constant 100000",
                        "schedule function track_stats:tick 1t")
                .function("afk_display:second",
                        "schedule function afk_display:second 1s",
                        "scoreboard players add @a afkDis.checkAFK 1",
                        "execute as @a[scores={afkDis.checkAFK=150..},team=!tg_tagged] at @s run function afk_display:check_afk",
                        "execute as @a[team=afkDis.afk] at @s run function afk_display:remove_afk")
                .function("afk_display:check_afk",
                        "execute store result score @s afkDis.x2 run data get entity @s Pos[0] 100",
                        "execute if score @s afkDis.x2 = @s afkDis.x1 run team join afkDis.afk @s",
                        "execute if entity @s[team=afkDis.afk] run scoreboard players operation @s hc_afkTimeStop = @s hc_playTick")
                .function("afk_display:remove_afk",
                        "execute unless score @s afkDis.x2 = @s afkDis.x1 run team leave @s",
                        "execute unless entity @s[team=afkDis.afk] run scoreboard players operation @s hc_playTick = @s hc_afkTimeStop")
                .tag("minecraft:load", "track_stats:load", "afk_display:second");
    }

    @Test
    void trackStatsLoopPartitionsWithAcceptedReadsOfAfkDisplayState() {
        FunctionPlan plan = trackStatsAndAfk(new FunctionPlanFixture()).plan();

        FunctionPlan.RootPlan loop = plan.partitionedLoop(net.minecraft.resources.Identifier.parse("track_stats:tick"));
        assertNotNull(loop, "track_stats:tick is a partitioned 1t loop");
        assertEquals(Map.of(5, PARTITION, 7, REARM), FunctionPlanFixture.kinds(loop));
        FunctionPlan.LinePlan math = loop.lines().getFirst();
        assertTrue(math.acceptedReads().stream().anyMatch(r -> r.startsWith("team")),
                "team membership is written only by fenced afk_display: an accepted <=1-tick read");
        assertTrue(math.acceptedReads().stream().anyMatch(r -> r.startsWith("score:hc_playTick")));
        assertEquals(2, math.fakeReads().size(), "constants 1200/100000 hc_constant are prechecked");
        assertTrue(plan.loops.containsKey(net.minecraft.resources.Identifier.parse("track_stats:tick")));
        assertFalse(plan.loops.containsKey(net.minecraft.resources.Identifier.parse("afk_display:second")),
                "1s loops stay Main-resident");
    }

    private static FunctionPlanFixture armorStatues(FunctionPlanFixture f) {
        return f
                .function("armor_statues:tick",
                        "#",
                        "# Description:\tProcessing every tick",
                        "# Called by:\t#main:tick",
                        "# Entity @s:\tNone",
                        "#",
                        "# Particle at shrine position",
                        "#",
                        "schedule function armor_statues:tick 1t",
                        "",
                        "execute as @e[tag=as_shrine] at @s run particle minecraft:enchant ~ ~ ~ 0.5 0.5 0.5 0.25 5",
                        "#",
                        "# If book crafting is enabled, check for players holding a written book titled \"Statues\"",
                        "#",
                        "execute as @a[tag=as_craft] run function armor_statues:replace_book",
                        "#",
                        "# Display help in chat and enable for all players",
                        "#",
                        "execute as @a[scores={as_help=1..}] at @s run function armor_statues:help")
                .function("armor_statues:replace_book",
                        "execute store result score #as_success as_help run clear @s minecraft:written_book[minecraft:written_book_content~{title:\"Statues\"}] 1",
                        "execute if score #as_success as_help matches 1.. run function armor_statues:give",
                        "",
                        "tag @s remove as_craft")
                .function("armor_statues:give", "loot give @s loot armor_statues:book")
                .function("armor_statues:help",
                        "execute if score @s as_help matches 2 if data storage customizable_armor_stands:settings as_admin{book_help:\"Enabled\"} run function armor_statues:help/get_book",
                        "execute if score @s as_help matches 1..3 unless score @s as_help matches 2 run function armor_statues:help/main",
                        "scoreboard players set @s as_help 0",
                        "scoreboard players enable @s as_help")
                .function("armor_statues:help/get_book", "function armor_statues:give")
                .function("armor_statues:help/main",
                        "tellraw @s [{\"text\":\"Customizable Armor Stands - Help\",\"color\":\"yellow\",\"bold\":true}]",
                        "execute if score #as_shrine_x as_help matches 1.. run tellraw @s [{\"text\":\" - Going to the shrine at \",\"color\":\"aqua\"},{\"score\":{\"name\":\"#as_shrine_x\",\"objective\":\"as_help\"},\"color\":\"aqua\"}]");
    }

    @Test
    void armorStatuesLoopPartitionsShrineParticlesAndEscalatesFakePlayerLines() {
        FunctionPlan plan = armorStatues(new FunctionPlanFixture()).plan();

        FunctionPlan.RootPlan loop = plan.partitionedLoop(net.minecraft.resources.Identifier.parse("armor_statues:tick"));
        assertNotNull(loop, String.join("\n", plan.describe()));
        assertEquals(Map.of(8, REARM, 10, PARTITION, 14, ESCALATE, 18, ESCALATE),
                FunctionPlanFixture.kinds(loop));
        assertTrue(loop.lines().get(1).playerSnapshot(), "particle sends to vanilla's default viewers");
        assertTrue(loop.lines().get(2).reason().contains("#as_success"), loop.lines().get(2).reason());
        assertNull(loop.lines().get(1).reason());
    }

    @Test
    void rootGlobalLinesKeepTheRootMainResident() {
        FunctionPlanFixture f = new FunctionPlanFixture()
                .function("pack:tick",
                        "scoreboard players add #timer pack.t 1",
                        "execute as @a run tag @s add seen")
                .function("pack:returns", "execute as @a run return 1")
                .function("pack:nearest", "execute as @p run tag @s add near")
                .function("pack:limited", "execute as @e[type=zombie,limit=3] run tag @s add few")
                .function("pack:fanout_in_body", "execute as @a at @s run tag @e[distance=..2] add near")
                .function("pack:player_nbt", "execute as @a[nbt={OnGround:1b}] run tag @s add g")
                .tag("minecraft:tick", "pack:tick", "pack:returns", "pack:nearest", "pack:limited",
                        "pack:fanout_in_body", "pack:player_nbt");
        FunctionPlan plan = f.plan();

        FunctionPlan.RootPlan tick = plan.tickRoots.get(0);
        assertFalse(tick.partitioned());
        assertEquals(Map.of(1, GLOBAL, 2, PARTITION), FunctionPlanFixture.kinds(tick));
        assertTrue(tick.blocking().startsWith("pack:tick:1: "), tick.blocking());

        assertEquals(GLOBAL, plan.tickRoots.get(1).lines().getFirst().kind(), "return ends the whole root");
        assertEquals(GLOBAL, plan.tickRoots.get(2).lines().getFirst().kind(), "@p needs a global view");
        assertEquals(GLOBAL, plan.tickRoots.get(3).lines().getFirst().kind(), "limit needs a global view");
        assertEquals(ESCALATE, plan.tickRoots.get(4).lines().getFirst().kind(), "@e in a body");
        assertEquals(GLOBAL, plan.tickRoots.get(5).lines().getFirst().kind(), "player NBT");
    }

    @Test
    void selfRearmRecognitionIsExact() {
        FunctionPlanFixture f = new FunctionPlanFixture()
                .function("pack:append", "schedule function pack:append 1t append",
                        "execute as @a run tag @s add a")
                .function("pack:two_ticks", "schedule function pack:two_ticks 2t",
                        "execute as @a run tag @s add b")
                .function("pack:conditional", "execute if entity @a run schedule function pack:conditional 1t",
                        "execute as @a run tag @s add c")
                .function("pack:replace", "execute as @a run tag @s add d",
                        "schedule function pack:replace 1t replace");
        FunctionPlan plan = f.plan();

        assertEquals(1, plan.loops.size(), String.join("\n", plan.describe()));
        FunctionPlan.RootPlan loop = plan.partitionedLoop(net.minecraft.resources.Identifier.parse("pack:replace"));
        assertNotNull(loop);
        assertEquals(Map.of(1, PARTITION, 2, REARM), FunctionPlanFixture.kinds(loop));
    }
}
