package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Scope of one trigger call's evaluation phase: the matcher and {@code player:}
 * conditions of every listener, which vanilla runs before the first award.
 * Nothing mutates a player between two listeners of that phase, so a player's
 * {@code saveWithoutId} tag is computed once per scope and reused; awards and
 * reward functions run outside the scope and therefore always see fresh NBT.
 *
 * <p>A fire-time scope runs on the player's owner (or an off-owner producer)
 * while other islands tick. Full player serialization walks the player's ender
 * pearls, which may live on any island or level. Inside a fire-time scope that
 * part is omitted: {@code NbtUtils.compareNbt} is a subset match, so a compare
 * whose expected tag has no top-level {@code ender_pearls} key cannot observe
 * it. A compare that does reference it raises {@link RouteSignal} before any
 * serialization; the trigger call is then routed whole to the fenced Main lane,
 * where the quiescent scope serializes vanilla's complete tag.
 */
public final class AdvancementCacheContext {
   private static final ScopedValue<Scope> CURRENT = ScopedValue.newInstance();

   private static final String ENDER_PEARLS = "ender_pearls";

   private AdvancementCacheContext() {
   }

   private static final class Scope {
      final IdentityHashMap<ServerPlayer, CompoundTag> cache = new IdentityHashMap<>(4);
      final boolean fireTime;
      boolean routeRequested;

      Scope(boolean fireTime) {
         this.fireTime = fireTime;
      }
   }

   /**
    * Raised inside a fire-time scope by a compare that needs foreign state.
    * Preallocated, stackless and suppression-free: vanilla's try-with-resources
    * around entity serialization may call addSuppressed on it.
    */
   static final class RouteSignal extends RuntimeException {
      static final RouteSignal INSTANCE = new RouteSignal();

      private RouteSignal() {
         super("advancement evaluation needs the fenced Main lane", null, false, false);
      }
   }

   /**
    * Evaluates one trigger call's listeners at fire time.
    * @return the body's result, or {@code null} with {@code routed[0] = true} when the
    *         evaluation needs foreign state and must be repeated under the fence
    */
   static <T> T evaluateAtFireTime(Supplier<T> body, boolean[] routed) {
      Scope scope = new Scope(true);
      T result;
      try {
         result = ScopedValue.where(CURRENT, scope).call(body::get);
      } catch (RouteSignal signal) {
         routed[0] = true;
         return null;
      }
      // A predicate implementation that swallowed the signal still cannot
      // turn its foreign compare into a fire-time result.
      if (scope.routeRequested) {
         routed[0] = true;
         return null;
      }
      return result;
   }

   /** Evaluates under quiescent ownership (held fence, or no live island). */
   static <T> T evaluateQuiescent(Supplier<T> body) {
      requireSerializationOwnership();
      return ScopedValue.where(CURRENT, new Scope(false)).call(body::get);
   }

   static void requireSerializationOwnership() {
      if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()
            && !RegionalPlayerEntityTicksV2.inExactPlayerActor()
            && !RegionalPlayerEntityTicksV2.inWorkerPlayerTick()) {
         throw new IllegalStateException("full advancement NBT evaluation requires the exact quiescent owner");
      }
   }

   /**
    * Called by the NBT predicate wraps before {@code getEntityTagToCompare}.
    * Throws {@link RouteSignal} when a fire-time compare of a player's tag
    * references its ender pearls.
    */
   public static void checkFireTimeCompare(Entity entity, CompoundTag expected) {
      if (!(entity instanceof ServerPlayer) || !CURRENT.isBound()) return;
      Scope scope = CURRENT.get();
      if (scope.fireTime && expected.contains(ENDER_PEARLS)) {
         scope.routeRequested = true;
         throw RouteSignal.INSTANCE;
      }
   }

   /** True while a fire-time scope serializes a player: omit its ender pearls. */
   public static boolean excludesEnderPearls() {
      return CURRENT.isBound() && CURRENT.get().fireTime;
   }

   public static CompoundTag getOrComputePlayerNbt(ServerPlayer player, Supplier<CompoundTag> vanillaFetch) {
      if (!CURRENT.isBound()) {
         return null;
      }
      return CURRENT.get().cache.computeIfAbsent(player, ignored -> vanillaFetch.get());
   }
}
