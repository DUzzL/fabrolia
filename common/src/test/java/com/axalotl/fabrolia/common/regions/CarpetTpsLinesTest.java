package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Carpet's tab footer keeps every other logger's lines when its TPS entry becomes Main + Local. */
class CarpetTpsLinesTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void carpetsTpsEntryBecomesMainAndLocalAndOtherLoggersKeepTheirPlace() {
        // HUDController.addMessage: entries separated by "\n" literals, in logger order.
        List<Component> footer = new ArrayList<>(List.of(
                Component.literal("scarpet footer"), Component.literal("\n"),
                Component.literal("TPS: 20.0  MSPT: 12.3"), Component.literal("\n"),
                Component.literal("Mobcaps: 70/70")));
        Component main = CarpetTpsLines.line("Main ", 19.6, 20, 12.3, 50);
        Component local = CarpetTpsLines.line("Local", 9.4, 20, 3.1, 50);

        assertTrue(CarpetTpsLines.replaceCarpetTpsLine(footer, main, local));

        assertEquals(List.of("scarpet footer", "\n", "Main  │ TPS: 19.6  MSPT: 12.3", "\n",
                        "Local │ TPS: 9.4  MSPT: 3.1", "\n", "Mobcaps: 70/70"),
                footer.stream().map(Component::getString).toList());
    }

    @Test
    void aFooterWithoutCarpetsTpsEntryIsLeftAlone() {
        List<Component> footer = new ArrayList<>(List.of(Component.literal("Mobcaps: 70/70")));
        assertFalse(CarpetTpsLines.replaceCarpetTpsLine(footer,
                CarpetTpsLines.line("Main ", 20, 20, 1, 50), CarpetTpsLines.line("Local", 20, 20, 1, 50)));
        assertEquals(List.of("Mobcaps: 70/70"), footer.stream().map(Component::getString).toList());
    }
}
