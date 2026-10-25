package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit D-20: vanilla callers check a POI before {@code release} outside the
 * stripe; the existence check that decides whether to release runs under it.
 */
final class PoiReleaseUnderStripeTest {

    @Test
    void releaseChecksExistenceInsideTheColumnStripe() throws Exception {
        String text = source("common/mixin/entity/movement/PoiManagerMixin.java");
        int wrap = text.indexOf("@WrapMethod(method = \"release\")");
        int stripe = text.indexOf("PoiStripes.mutateColumn(", wrap);
        int check = text.indexOf("self.exists(pos, type -> true) && original.call(pos)", stripe);
        int next = text.indexOf("@WrapMethod", wrap + 1);
        assertTrue(wrap >= 0 && stripe > wrap && check > stripe && next > check,
                "a POI removed by another island before release must answer false under the stripe");
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        throw new AssertionError("source tree not available");
    }
}
