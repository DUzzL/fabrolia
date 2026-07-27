package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to {@code ServerLevel.entityManager} for the spawn-gate precompute
 * ({@code ServerChunkCacheMixin.fabrolia$buildSpawnGate}): vanilla's
 * {@code canSpawnEntitiesInChunk} calls {@code getWorldBorder()} PER CHUNK, and in 26.2
 * that is a {@code SavedDataStorage.computeIfAbsent} lookup — 1.1s of the server thread
 * at 51 players (xMDQKeDhXO profile, 2026-07-02). The gate replicates the vanilla body
 * ({@code entityManager.canPositionTick(pos) && worldBorder.isWithinBounds(pos)}) with
 * the border resolved ONCE per tick — bit-identical semantics, one lookup instead of
 * one per spawning chunk.
 */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManagerAccessor {

    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> fabrolia$entityManager();
}
