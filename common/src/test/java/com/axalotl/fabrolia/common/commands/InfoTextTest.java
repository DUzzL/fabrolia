package com.axalotl.fabrolia.common.commands;

import com.axalotl.fabrolia.common.regions.IslandMetrics;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layout and value formatting of the /fabrolia info commands. */
class InfoTextTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void sectionsAreSeparatedByABlankLineAndLinesAreIndented() {
        String text = InfoText.titled("Fabrolia status")
                .line("Main thread", Component.literal("20.0 TPS"))
                .section("Islands")
                .line("Count", Component.literal("3"))
                .build().getString();
        String[] lines = text.split("\n", -1);
        assertTrue(lines[0].endsWith("Fabrolia status"), text);
        assertEquals("  Main thread: 20.0 TPS", lines[1]);
        assertEquals("", lines[2], "a section starts after a blank line");
        assertEquals("Islands", lines[3]);
        assertEquals("  Count: 3", lines[4]);
    }

    @Test
    void anIslandWithoutACompleteWindowReadsMeasuringNotZero() {
        assertEquals("measuring…", InfoText.tps(Double.NaN, 20).getString());
        assertEquals("0.0 TPS", InfoText.tps(0.0, 20).getString());
        assertEquals("19.7 TPS", InfoText.tps(19.74, 20).getString());
    }

    @Test
    void tpsAndMsptAreColouredAgainstTheirTarget() {
        assertEquals(ChatFormatting.GREEN, InfoText.tpsColor(19.5, 20));
        assertEquals(ChatFormatting.YELLOW, InfoText.tpsColor(16, 20));
        assertEquals(ChatFormatting.RED, InfoText.tpsColor(10, 20));
        // A rated island is judged against its own target, not 20.
        assertEquals(ChatFormatting.GREEN, InfoText.tpsColor(9.8, 10));

        assertEquals(ChatFormatting.GREEN, InfoText.msptColor(12, 50));
        assertEquals(ChatFormatting.YELLOW, InfoText.msptColor(40, 50));
        assertEquals(ChatFormatting.RED, InfoText.msptColor(51, 50));
        assertEquals("12.3 ms avg", InfoText.mspt(12.34, 50, " avg").getString());
    }

    @Test
    void joinSeparatesPartsWithADot() {
        assertEquals("a · b · c", InfoText.join(Component.literal("a"), Component.literal("b"),
                Component.literal("c")).getString());
    }

    @Test
    void countsAndDimensionsReadNaturally() {
        assertEquals("12,345", InfoText.count(12_345));
        assertEquals("1 island", InfoText.plural(1, "island", "islands"));
        assertEquals("2 islands", InfoText.plural(2, "island", "islands"));
        assertEquals("Overworld", InfoText.dimension(Level.OVERWORLD));
        assertEquals("Nether", InfoText.dimension(Level.NETHER));
        assertEquals("End", InfoText.dimension(Level.END));
    }

    @Test
    void everyIslandStateHasALabel() {
        for (IslandMetrics.State state : IslandMetrics.State.values()) {
            assertFalse(InfoText.state(state).getString().isBlank(), state.name());
        }
    }

    @Test
    void aOneShotLocalTpsCheckExistsForPlayersAndAdmins() {
        CommandNode<CommandSourceStack> regions = RegionCommand.register(Commands.literal("fabrolia"))
                .build().getChild("regions");
        CommandNode<CommandSourceStack> here = regions.getChild("here");
        assertNotNull(here, "/fabrolia regions here");
        assertNotNull(here.getCommand());
        CommandNode<CommandSourceStack> player = regions.getChild("player");
        assertNotNull(player, "/fabrolia regions player <target>");
        assertNotNull(player.getChild("target").getCommand());
    }
}
