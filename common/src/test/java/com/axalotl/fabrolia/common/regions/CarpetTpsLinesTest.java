package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Carpet's tab footer: the TPS entry becomes Main + Local lines whose columns line up on the client. */
class CarpetTpsLinesTest {
    /**
     * Client advances (26.3 {@code font/ascii.png}, space provider; bold adds 1 px) of
     * the glyphs these lines use. The separator is a unifont glyph of the same width in
     * both lines; its exact width does not matter.
     */
    private static final Map<Character, Integer> ADVANCE = Map.ofEntries(
            Map.entry(' ', 4), Map.entry('.', 2), Map.entry(':', 2), Map.entry('│', 7),
            Map.entry('M', 6), Map.entry('a', 6), Map.entry('i', 2), Map.entry('n', 6),
            Map.entry('L', 6), Map.entry('o', 6), Map.entry('c', 6), Map.entry('l', 3),
            Map.entry('T', 6), Map.entry('P', 6), Map.entry('S', 6));

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
        List<Component> two = lines(19.6, 12.3, 9.4, 3.1);

        assertTrue(CarpetTpsLines.replaceCarpetTpsLine(footer, two.get(0), two.get(1)));

        List<String> text = footer.stream().map(Component::getString).toList();
        assertEquals(List.of("scarpet footer", "\n", "\n", "\n", "Mobcaps: 70/70"),
                List.of(text.get(0), text.get(1), text.get(3), text.get(5), text.get(6)));
        assertEquals("Main│TPS:19.6MSPT:12.3", text.get(2).replace(" ", ""));
        assertEquals("Local│TPS:9.4MSPT:3.1", text.get(4).replace(" ", ""));
    }

    @Test
    void aFooterWithoutCarpetsTpsEntryIsLeftAlone() {
        List<Component> footer = new ArrayList<>(List.of(Component.literal("Mobcaps: 70/70")));
        List<Component> two = lines(20, 1, 20, 1);
        assertFalse(CarpetTpsLines.replaceCarpetTpsLine(footer, two.get(0), two.get(1)));
        assertEquals(List.of("Mobcaps: 70/70"), footer.stream().map(Component::getString).toList());
    }

    /** The tab list centres each line: equal widths and equal column offsets make the lines coincide. */
    @Test
    void bothLinesHaveTheSameWidthAndEveryColumnStartsAtTheSameOffset() {
        double[][] cases = {
                {20.0, 0.4, 20.0, 3.6}, {19.6, 12.3, 9.4, 3.1}, {20.0, 999.9, 0.0, 0.1},
                {20.0, 45.0, 20.0, 45.1}, {7.5, 1234.5, 20.0, 8.0}};
        for (double[] c : cases) {
            List<Component> two = lines(c[0], c[1], c[2], c[3]);
            List<Integer> main = columnOffsets(two.get(0)), local = columnOffsets(two.get(1));
            assertEquals(main, local, () -> "misaligned for " + java.util.Arrays.toString(c)
                    + ": " + two.get(0).getString() + " / " + two.get(1).getString());
        }
    }

    @Test
    void columnsHoldStillWhileValuesKeepTheirDigitCount() {
        List<Integer> first = columnOffsets(lines(20.0, 0.4, 20.0, 3.6).get(0));
        assertEquals(first, columnOffsets(lines(19.9, 9.9, 9.4, 1.2).get(0)));
        assertEquals(first, columnOffsets(lines(10.0, 2.7, 0.1, 9.8).get(1)));
    }

    @Test
    void paddableIsExactlyWhatSpacesAndBoldSpacesReach() {
        // 4a + 5b reaches 0, 4, 5, 8, 9, 10 and everything from 12 up.
        for (int px = 0; px < 64; px++) {
            boolean reachable = false;
            for (int b = 0; 5 * b <= px; b++) reachable |= (px - 5 * b) % 4 == 0;
            assertEquals(reachable, CarpetTpsLines.paddable(px), "px=" + px);
        }
    }

    private static List<Component> lines(double mainTps, double mainMspt, double localTps, double localMspt) {
        return CarpetTpsLines.lines(
                new CarpetTpsLines.Reading("Main", mainTps, 20, mainMspt, 50),
                new CarpetTpsLines.Reading("Local", localTps, 20, localMspt, 50));
    }

    /** Client x of every fixed label ("│ ", "TPS:", "  MSPT:") plus the line's total width. */
    private static List<Integer> columnOffsets(Component line) {
        List<Integer> out = new ArrayList<>();
        int[] x = {0};
        line.visit((FormattedText.StyledContentConsumer<Void>) (style, text) -> {
            if (text.startsWith("│") || text.equals("TPS:") || text.equals("  MSPT:")) out.add(x[0]);
            for (char ch : text.toCharArray()) {
                Integer advance = ADVANCE.get(ch);
                if (advance == null && Character.isDigit(ch)) advance = 6;
                assertNotNull(advance, "unexpected glyph " + ch);
                x[0] += advance + (style.isBold() ? 1 : 0);
            }
            return Optional.empty();
        }, Style.EMPTY);
        out.add(x[0]);
        assertEquals(4, out.size(), line.getString());
        return out;
    }
}
