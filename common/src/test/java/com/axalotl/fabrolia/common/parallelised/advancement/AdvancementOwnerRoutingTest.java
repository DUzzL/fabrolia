package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.ImpossibleTrigger;
import net.minecraft.commands.CacheableFunction;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.PlainTextFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner-thread advancement contract with one live async level: awards/revokes return
 * vanilla's synchronous result on the owner and are rejected off it; routing decisions
 * of the C2 point; routed work survives removal; no fence without routed work.
 */
@Isolated
@Timeout(20)
final class AdvancementOwnerRoutingTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ownerAwardAndRevokeReturnVanillaResultsSynchronouslyAndOffOwnerIsRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            AdvancementHolder holder = advancement("pair", AdvancementRewards.EMPTY, "a", "b");
            ServerPlayer player = fixture.player();
            PlayerAdvancements advancements = fixture.advancements(player);

            assertTrue(AdvancementOwnership.ownsPlayer(player),
                    "physical Main owns a player no island session can reach");
            assertTrue(award(advancements, player, holder, "a"), "first grant changes progress");
            assertTrue(advancements.getOrStartProgress(holder).getCriterion("a").isDone(),
                    "the grant is visible when award returns");
            assertFalse(award(advancements, player, holder, "a"), "a repeated grant is vanilla's false");
            assertTrue(revoke(advancements, player, holder, "a"));
            assertFalse(advancements.getOrStartProgress(holder).getCriterion("a").isDone());
            assertFalse(revoke(advancements, player, holder, "a"), "a repeated revoke is vanilla's false");

            AtomicBoolean entered = new AtomicBoolean();
            AtomicReference<Throwable> offOwner = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    AdvancementOwnership.mutateOnOwner("award", player, holder.id(), () -> {
                        entered.set(true);
                        return advancements.award(holder, "a");
                    });
                } catch (Throwable thrown) {
                    offOwner.set(thrown);
                }
            }, "off-owner-producer");
            producer.start();
            producer.join();
            assertInstanceOf(IllegalStateException.class, offOwner.get(),
                    "an off-owner award is rejected loudly, never answered with a fabricated false");
            assertFalse(entered.get(), "vanilla is not entered off the owner");
            assertFalse(advancements.getOrStartProgress(holder).getCriterion("a").isDone());
            assertEquals(0, fixture.routed.size(), "nothing is deferred for a rejected award");
        }
    }

    @Test
    void noFenceAndNoScopeWorkWhenNothingIsRouted() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            assertTrue(IslandCommands.requiresMainFence());
            long quiesces = AdvancementDiag.QUIESCE_TAKES.sum();
            long routedWindows = AdvancementDiag.HEAD_WINDOWS_ROUTED.sum();

            assertTrue(AdvancementBuffer.beginTickHeadWindow(false));
            assertFalse(IslandCommands.fenceWindowHeld(), "an empty window holds no fence");
            assertNull(AdvancementBuffer.endTickHeadWindow(null));
            AdvancementBuffer.applyDeferred();
            AdvancementBuffer.drainBeforeRemoval(fixture.player());

            assertEquals(quiesces, AdvancementDiag.QUIESCE_TAKES.sum(), "no advancement site quiesced");
            assertEquals(routedWindows, AdvancementDiag.HEAD_WINDOWS_ROUTED.sum());
            assertFalse(AdvancementBatchScope.active());
        }
    }

    @Test
    void routedWorkKeepsFifoOrderNeverFencesAtBatchEndAndIsAppliedBeforeRemoval() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            AdvancementHolder holder = advancement("pair", AdvancementRewards.EMPTY, "a", "b");
            ServerPlayer player = fixture.player();
            PlayerAdvancements advancements = fixture.advancements(player);
            List<String> effects = new ArrayList<>();
            RecordingPlayerList chat = fixture.playerList(effects, advancements, holder);

            AdvancementBuffer.publish(new AdvancementBuffer.RoutedAward(advancements,
                    new PlayerAdvancements.TriggerInstanceKey(holder, "a")),
                    AdvancementDiag.Route.AWARD_GLOBAL_REWARD);
            AdvancementBuffer.publish(new AdvancementBuffer.RoutedAnnouncement(chat,
                    Component.literal("made it"), false), AdvancementDiag.Route.ANNOUNCEMENT);
            assertTrue(AdvancementBuffer.hasBacklog(advancements));

            AdvancementBuffer.applyDeferred();
            assertEquals(2, fixture.routed.size(), "the level batch end never acquires the fence;"
                    + " the fence-free announcement stays behind the award");
            assertFalse(advancements.getOrStartProgress(holder).getCriterion("a").isDone());

            assertThrows(IllegalStateException.class, () -> AdvancementBuffer.drainBeforeRemoval(player),
                    "removal without quiescent ownership must not save around routed work");
            assertEquals(2, fixture.routed.size());

            long quiesces = AdvancementDiag.QUIESCE_TAKES.sum();
            fixture.holdAllClaims();
            AdvancementBuffer.drainBeforeRemoval(player);
            assertTrue(advancements.getOrStartProgress(holder).getCriterion("a").isDone(),
                    "the routed award reached the PlayerAdvancements before its save");
            assertEquals(List.of("award-applied:made it"), effects, "FIFO order is kept");
            assertTrue(fixture.routed.isEmpty());
            assertFalse(AdvancementBuffer.hasBacklog(advancements));
            assertEquals(quiesces, AdvancementDiag.QUIESCE_TAKES.sum(), "the removal fence was borrowed");
            assertFalse(AdvancementBatchScope.active());
        }
    }

    @Test
    void completionNeedsTheFenceOnlyWhenItRunsAGlobalReward() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            ServerPlayer player = fixture.player();
            PlayerAdvancements advancements = fixture.advancements(player);
            AdvancementRewards function = new AdvancementRewards(0, HolderSet.empty(), List.of(),
                    Optional.of(new CacheableFunction(Identifier.fromNamespaceAndPath("test", "reward"))));
            AdvancementHolder single = advancement("single", function, "a");
            AdvancementHolder pair = advancement("pair", function, "a", "b");
            AdvancementHolder plain = advancement("plain", AdvancementRewards.EMPTY, "a");

            assertTrue(AdvancementRewardRouting.completionNeedsFence(advancements, single, "a"),
                    "completing it runs a reward function no plan proved owner-local");
            assertFalse(AdvancementRewardRouting.completionNeedsFence(advancements, pair, "a"),
                    "a partial grant runs no reward");
            assertTrue(award(advancements, player, pair, "b"));
            assertTrue(AdvancementRewardRouting.completionNeedsFence(advancements, pair, "a"),
                    "the last open criterion completes it");
            assertFalse(AdvancementRewardRouting.completionNeedsFence(advancements, pair, "b"),
                    "an already granted criterion changes nothing");
            assertFalse(AdvancementRewardRouting.completionNeedsFence(advancements, plain, "a"),
                    "a completion without rewards stays inline");
        }
    }

    @Test
    void rewardFunctionsGrantsAndAnnouncementsRouteOnlyOutsideQuiescentMain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publishAsyncLevel();
            ServerPlayer player = fixture.player();
            PlayerAdvancements advancements = fixture.advancements(player);
            CommandFunction<CommandSourceStack> reward =
                    new PlainTextFunction<>(Identifier.fromNamespaceAndPath("test", "reward"), List.of());
            CommandSourceStack source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO,
                    null, LevelBasedPermissionSet.GAMEMASTER, fixture.server, player);
            AtomicBoolean executed = new AtomicBoolean();
            List<String> effects = new ArrayList<>();
            RecordingPlayerList chat = fixture.playerList(effects, null, null);
            AdvancementRewards loot = new AdvancementRewards(0,
                    HolderSet.direct(Holder.direct(LootTable.EMPTY)), List.of(), Optional.empty());
            AdvancementRewards xp = new AdvancementRewards(5, HolderSet.empty(), List.of(), Optional.empty());

            assertFalse(AdvancementRewardRouting.routeAnnouncement(chat, Component.empty(), false, player),
                    "physical Main owns the player list");
            assertFalse(AdvancementRewardRouting.routeGrant(xp, player), "xp is owner-safe");

            AdvancementRewardRouting.dispatchRewardFunction(reward, source, () -> executed.set(true));
            assertFalse(executed.get(), "a function no plan proved owner-local never runs unfenced");
            assertInstanceOf(AdvancementBuffer.RoutedReward.class, fixture.routed.peek());
            assertTrue(AdvancementBuffer.hasBacklog(advancements));

            assertTrue(AdvancementRewardRouting.routeGrant(xp, player),
                    "behind routed work the whole grant follows the player's FIFO");
            assertTrue(AdvancementRewardRouting.routeAnnouncement(chat, Component.empty(), false, player),
                    "behind routed work the announcement follows the player's FIFO");
            assertEquals(3, fixture.routed.size());
            fixture.clearRouted();

            assertTrue(AdvancementRewardRouting.routeGrant(loot, player), "loot needs the fence");
            fixture.clearRouted();

            AtomicReference<Boolean> islandAnnouncement = new AtomicReference<>();
            Thread island = new Thread(() -> islandAnnouncement.set(
                    AdvancementRewardRouting.routeAnnouncement(chat, Component.empty(), false, player)));
            island.start();
            island.join();
            assertTrue(islandAnnouncement.get(), "only Main iterates the player list");
            fixture.clearRouted();

            fixture.holdAllClaims();
            AdvancementRewardRouting.dispatchRewardFunction(reward, source, () -> executed.set(true));
            assertTrue(executed.get(), "under the held fence the reward runs as vanilla");
            assertFalse(AdvancementRewardRouting.routeGrant(loot, player));
            assertTrue(fixture.routed.isEmpty());
        }
    }

    private static boolean award(PlayerAdvancements advancements, ServerPlayer player,
                                 AdvancementHolder holder, String criterion) {
        return AdvancementOwnership.mutateOnOwner("award", player, holder.id(),
                () -> advancements.award(holder, criterion));
    }

    private static boolean revoke(PlayerAdvancements advancements, ServerPlayer player,
                                  AdvancementHolder holder, String criterion) {
        return AdvancementOwnership.mutateOnOwner("revoke", player, holder.id(),
                () -> advancements.revoke(holder, criterion));
    }

    private static AdvancementHolder advancement(String path, AdvancementRewards rewards, String... criteria) {
        Map<String, Criterion<?>> map = new LinkedHashMap<>();
        for (String name : criteria) {
            map.put(name, new Criterion<>(CriteriaTriggers.IMPOSSIBLE, new ImpossibleTrigger.TriggerInstance()));
        }
        Advancement advancement = new Advancement(Optional.empty(), Optional.empty(), rewards, map,
                AdvancementRequirements.allOf(List.of(criteria)), false);
        return new AdvancementHolder(Identifier.fromNamespaceAndPath("test", path), advancement);
    }

    /** Records the announcement together with the award state it observed. */
    static class RecordingPlayerList extends PlayerList {
        List<String> effects;
        PlayerAdvancements advancements;
        AdvancementHolder holder;

        private RecordingPlayerList() {
            super(null, null, null, null);
        }

        @Override
        public void broadcastSystemMessage(Component message, boolean overlay) {
            boolean applied = advancements != null
                    && advancements.getOrStartProgress(holder).getCriterion("a").isDone();
            effects.add((applied ? "award-applied:" : "award-missing:") + message.getString());
        }
    }

    /** Real vanilla PlayerAdvancements and schedule semantics, one fake async level. */
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server;
        private final sun.misc.Unsafe unsafe;
        private final Queue<TickTask> tasks = new ConcurrentLinkedQueue<>();
        private final Object previousServer = get(ParallelProcessor.class, null, "server");
        private final Object previousPool = get(RegionScheduler.class, null, "pool");
        private final Object previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
        private final Object previousFence = get(IslandCommands.class, null, "ACTIVE_FENCE");
        private final Map<Object, Object> levels;
        private final Map<Object, Object> previousLevels;
        final Queue<?> routed;
        private final Map<?, ?> backlog;
        private RegionizedServer runtime;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            server = (MinecraftServer) unsafe.allocateInstance(
                    com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(MinecraftServer.class, server, "levels", Map.of());
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            levels = (Map<Object, Object>) get(RegionScheduler.class, null, "PER_LEVEL");
            previousLevels = new HashMap<>(levels);
            routed = (Queue<?>) get(AdvancementBuffer.class, null, "ROUTED");
            backlog = (Map<?, ?>) get(AdvancementBuffer.class, null, "BACKLOG");
            if (!routed.isEmpty() || !backlog.isEmpty()) {
                throw new IllegalStateException("another test retained advancement work");
            }
            levels.clear();
            set(ParallelProcessor.class, null, "server", server);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
            set(IslandCommands.class, null, "ACTIVE_FENCE", null);
        }

        /** An async level whose fence classification is live; no claims held yet. */
        void publishAsyncLevel() throws Exception {
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            runtime.asyncActive = true;
            levels.put(new Object(), runtime);
        }

        /** Physical Main owns every claim of the fake level: the fence window is held. */
        void holdAllClaims() throws Exception {
            Class<?> ownership = Class.forName(RegionizedServer.class.getName() + "$MainAllClaimsOwnership");
            Constructor<?> constructor = ownership.getDeclaredConstructor(Object.class, Thread.class, boolean.class);
            constructor.setAccessible(true);
            set(RegionizedServer.class, runtime, "mainAllClaimsOwnership",
                    constructor.newInstance(new Object(), Thread.currentThread(), false));
        }

        /** A player no listener or island session has reached (constructor-time shape). */
        ServerPlayer player() throws Exception {
            ServerPlayer player = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
            set(net.minecraft.world.entity.player.Player.class, player, "gameProfile",
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Owner"));
            return player;
        }

        PlayerAdvancements advancements(ServerPlayer player) throws Exception {
            PlayerAdvancements advancements =
                    (PlayerAdvancements) unsafe.allocateInstance(PlayerAdvancements.class);
            set(PlayerAdvancements.class, advancements, "progress", new LinkedHashMap<>());
            set(PlayerAdvancements.class, advancements, "visible", new HashSet<>());
            set(PlayerAdvancements.class, advancements, "progressChanged", new HashSet<>());
            set(PlayerAdvancements.class, advancements, "rootsToUpdate", new HashSet<>());
            set(PlayerAdvancements.class, advancements, "activeTriggers", new IdentityHashMap<>());
            set(PlayerAdvancements.class, advancements, "tree", new AdvancementTree());
            set(PlayerAdvancements.class, advancements, "player", player);
            set(ServerPlayer.class, player, "advancements", advancements);
            return advancements;
        }

        RecordingPlayerList playerList(List<String> effects, PlayerAdvancements advancements,
                                       AdvancementHolder holder) throws Exception {
            RecordingPlayerList list = (RecordingPlayerList) unsafe.allocateInstance(RecordingPlayerList.class);
            list.effects = effects;
            list.advancements = advancements;
            list.holder = holder;
            return list;
        }

        void clearRouted() {
            routed.clear();
            backlog.clear();
        }

        @Override
        public void close() {
            try {
                if (AdvancementBatchScope.active()) AdvancementBatchScope.end();
            } finally {
                tasks.clear();
                clearRouted();
                levels.clear();
                levels.putAll(previousLevels);
                set(IslandCommands.class, null, "ACTIVE_FENCE", previousFence);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
                set(ParallelProcessor.class, null, "server", previousServer);
            }
        }
    }

    private static Object get(Class<?> type, Object target, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void set(Class<?> type, Object target, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
