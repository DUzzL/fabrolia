package com.axalotl.fabrolia.common.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The armor stand's double-hit window is converted on the attacker's thread. */
final class ArmorStandHitClockWiringTest {

    @Test
    void theHitHandlerMovesTheStandsTimersFirst() throws IOException {
        String mixin = Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/mixin/timer/ArmorStandHitClockMixin.java"));
        assertTrue(mixin.contains("@Mixin(ArmorStand.class)"));
        assertTrue(mixin.contains("@Inject(method = \"hurtServer\", at = @At(\"HEAD\"))"),
                "before vanilla reads lastHit");
        assertTrue(mixin.contains("TimerClocks.ensureCurrent((TimerClocks.Stamped) (Object) this, level);"));
    }

    @Test
    void theMixinIsRegistered() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/fabrolia.common.mixins.json"));
        assertTrue(config.contains("\"timer.ArmorStandHitClockMixin\""));
    }
}
