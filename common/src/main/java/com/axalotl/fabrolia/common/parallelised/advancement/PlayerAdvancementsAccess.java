package com.axalotl.fabrolia.common.parallelised.advancement;

import net.minecraft.server.level.ServerPlayer;

/** Implemented by {@code PlayerAdvancementsMixin}: the lifecycle object vanilla currently rewards. */
public interface PlayerAdvancementsAccess {
    ServerPlayer fabrolia$player();
}
