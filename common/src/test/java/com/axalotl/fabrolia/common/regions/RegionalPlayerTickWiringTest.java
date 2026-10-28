package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compatibility test name retained for the operator's established audit command. */
class RegionalPlayerTickWiringTest {

    @Test
    void oneRuntimeGateOwnsTheV2ReplacementAndV1TransformsStayAbsent() throws Exception {
        List<String> mutableBooleans = java.util.Arrays.stream(
                        RegionalPlayerEntityTicksV2.class.getDeclaredFields())
                .filter(field -> field.getType() == boolean.class
                        && !Modifier.isFinal(field.getModifiers()))
                .map(java.lang.reflect.Field::getName).toList();
        assertEquals(List.of("enabled"), mutableBooleans);
        assertFalse(RegionalPlayerEntityTicksV2.enabled(), "v2 must default OFF in tests/boot");

        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("network.ServerCommonPacketListenerPlayerTickFlushMixin"));
            assertFalse(json.contains("server.ServerPlayerGameModeAccessor"));
            assertFalse(json.contains("ServerGamePacketRoutingMixin"));
            assertFalse(json.contains("PacketUtilsWorkerMixin"));
        }
    }
}
