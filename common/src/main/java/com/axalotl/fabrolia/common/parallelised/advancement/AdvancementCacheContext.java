package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Drain-scoped player NBT cache for {@code NbtPredicate.getEntityTagToCompare}: one
 * {@code saveWithoutId} per player per evaluation scope.
 *
 * <p>Serialization ownership is proven once, by physical Main, before any scope opens
 * ({@link #requireSerializationOwnership()}): full player serialization walks
 * inventories, vehicles and ender pearls, which only a quiescent owner may read.
 * Evaluation tasks fanned out by {@link AdvancementPredicateEval} bind their own cache
 * under that same proof — Main stays parked in the fan-in, so the held window cannot
 * close under a task. Each task owns every pending of its players, so the per-task
 * cache still serializes each player at most once per drain.
 */
public final class AdvancementCacheContext {
   private static final ScopedValue<IdentityHashMap<ServerPlayer, CompoundTag>> CURRENT = ScopedValue.newInstance();

   private AdvancementCacheContext() {
   }

   /** Bind a fresh cache for one evaluation task; the caller proved ownership on Main. */
   static void runScoped(Runnable body) {
      ScopedValue.where(CURRENT, new IdentityHashMap<>(4)).run(body);
   }

   public static void requireSerializationOwnership() {
      if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld() && !RegionalPlayerEntityTicksV2.inWorkerPlayerTick()) {
         throw new IllegalStateException("full advancement NBT evaluation requires the exact quiescent owner");
      }
   }

   public static CompoundTag getOrComputePlayerNbt(ServerPlayer player, Supplier<CompoundTag> vanillaFetch) {
      if (!CURRENT.isBound()) {
         return null;
      }
      return CURRENT.get().computeIfAbsent(player, ignored -> vanillaFetch.get());
   }
}
