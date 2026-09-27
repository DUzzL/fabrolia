package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

public final class AdvancementCacheContext {
   private static final ScopedValue<IdentityHashMap<ServerPlayer, CompoundTag>> CURRENT = ScopedValue.newInstance();

   private AdvancementCacheContext() {
   }

   public static void runScoped(Runnable body) {
      requireSerializationOwnership();
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

      requireSerializationOwnership();
      return CURRENT.get().computeIfAbsent(player, ignored -> vanillaFetch.get());
   }
}
