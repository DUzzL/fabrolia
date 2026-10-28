package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.regions.FunctionPlans;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.commands.CacheableFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.PlayerList;

import java.util.Optional;

/**
 * The single routing point for the global side effects of an award (contract C2),
 * checked against vanilla 26.3 {@code PlayerAdvancements.award} and
 * {@code AdvancementRewards.grant}:
 * <ul>
 *   <li>progress, listeners, visibility, xp, recipes (incl. the nested
 *       RECIPE_UNLOCKED trigger), the level-up sound: owner-safe, inline;</li>
 *   <li>the reward function: inline on the owner iff
 *       {@link FunctionPlans#ownerLocal}, otherwise the fenced Main lane;</li>
 *   <li>loot (loot functions such as {@code exploration_map} read far chunks;
 *       drops spawn entities): the whole grant goes to the fenced Main lane;</li>
 *   <li>the chat announcement iterates {@code PlayerList.players} and its
 *       broadcast listeners (chat bridges, Vanish): physical Main only, no
 *       fence needed.</li>
 * </ul>
 * In a quiescent context (held fence, no live island) every piece
 * runs exactly as vanilla. While a player has routed work outstanding, its later
 * grants and announcements follow that work through the same FIFO.
 */
public final class AdvancementRewardRouting {

    private AdvancementRewardRouting() {}

    /** The exact inline owner-local reward call this routing point admitted. */
    private record OwnerLocalCall(CommandFunction<?> function, CommandSourceStack source) {}

    private static final ScopedValue<OwnerLocalCall> OWNER_LOCAL_REWARD = ScopedValue.newInstance();

    /**
     * True only on the thread running this routing point's inline owner-local
     * execution of exactly {@code function} with exactly {@code source}: proven by
     * the active plan's exact object under the live-generation lease and running
     * inside the ownership scope of a partition that owns the player.
     */
    public static boolean isOwnerLocalReward(CommandFunction<?> function, CommandSourceStack source) {
        if (!OWNER_LOCAL_REWARD.isBound()) return false;
        OwnerLocalCall call = OWNER_LOCAL_REWARD.get();
        return call.function() == function && call.source() == source;
    }

    /**
     * {@code AdvancementRewards.grant}'s function dispatch.
     * @param execute the vanilla {@code ServerFunctionManager.execute} call
     */
    public static void dispatchRewardFunction(CommandFunction<CommandSourceStack> function,
                                              CommandSourceStack source, Runnable execute) {
        if (AdvancementOwnership.quiescentOutsidePass()) {
            execute.run();
            return;
        }
        ServerPlayer player = source.getPlayer();
        PlayerAdvancements advancements = player == null ? null : player.getAdvancements();
        if (player != null && !AdvancementBuffer.hasBacklog(advancements)
                && FunctionPlans.ownerLocal(function.id())
                && AdvancementOwnership.ownsPlayer(player)
                && com.axalotl.fabrolia.common.regions.IslandFunctionScope.runOwnerLocalReward(
                        source.getServer(), player, function,
                        () -> ScopedValue.where(OWNER_LOCAL_REWARD, new OwnerLocalCall(function, source))
                                .run(execute))) {
            AdvancementDiag.REWARD_FUNCTIONS_OWNER.increment();
            return;
        }
        AdvancementBuffer.publish(new AdvancementBuffer.RoutedReward(advancements, function, source),
                AdvancementDiag.Route.REWARD_FUNCTION);
    }

    /** The source {@code AdvancementRewards.grant} builds for {@code player}'s reward function. */
    static CommandSourceStack rewardSource(ServerPlayer player) {
        return player.createCommandSourceStack().withSuppressedOutput()
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
    }

    /** @return {@code true} when the whole grant was routed and must not run now. */
    public static boolean routeGrant(AdvancementRewards rewards, ServerPlayer player) {
        if (grantsNothing(rewards) || AdvancementOwnership.quiescentOutsidePass()) return false;
        PlayerAdvancements advancements = player.getAdvancements();
        if (rewards.loot().size() == 0 && !AdvancementBuffer.hasBacklog(advancements)) return false;
        AdvancementBuffer.publish(new AdvancementBuffer.RoutedGrant(advancements, player, rewards),
                AdvancementDiag.Route.REWARD_GRANT);
        return true;
    }

    /**
     * @return {@code true} when the announcement was routed to physical Main. A grant
     * made inside an owner-local function pass (island head or Main partition) is
     * always routed: the pass's ownership guards reject the global chat broadcast.
     */
    public static boolean routeAnnouncement(PlayerList playerList, Component message, boolean overlay,
                                            ServerPlayer player) {
        if (AdvancementOwnership.quiescentOutsidePass()) return false;
        boolean functionPass = com.axalotl.fabrolia.common.regions.IslandFunctionScope.current() != null;
        if (!functionPass && AdvancementOwnership.onPhysicalMain()
                && !AdvancementBuffer.hasBacklog(player.getAdvancements())) {
            return false;
        }
        AdvancementBuffer.publish(new AdvancementBuffer.RoutedAnnouncement(playerList, message, overlay),
                AdvancementDiag.Route.ANNOUNCEMENT);
        return true;
    }

    /**
     * Whether awarding {@code criterion} completes {@code holder} for this player and
     * its rewards then need the fenced Main lane. Such an award is routed whole: its
     * listener stays registered until then, so a same-tick refire is still seen
     * (revoke-and-rerun counters keep every fire).
     */
    static boolean completionNeedsFence(PlayerAdvancements advancements, AdvancementHolder holder,
                                        String criterion) {
        Advancement advancement = holder.value();
        if (!rewardsNeedFence(advancement.rewards())) return false;
        AdvancementProgress progress = advancements.getOrStartProgress(holder);
        if (progress.isDone()) return false;
        CriterionProgress granted = progress.getCriterion(criterion);
        if (granted == null || granted.isDone()) return false;
        return advancement.requirements().test(name -> {
            if (name.equals(criterion)) return true;
            CriterionProgress other = progress.getCriterion(name);
            return other != null && other.isDone();
        });
    }

    private static boolean rewardsNeedFence(AdvancementRewards rewards) {
        if (rewards.loot().size() != 0) return true;
        Optional<CacheableFunction> function = rewards.function();
        return function.isPresent() && !FunctionPlans.ownerLocal(function.get().getId());
    }

    private static boolean grantsNothing(AdvancementRewards rewards) {
        return rewards.experience() == 0 && rewards.loot().size() == 0
                && rewards.recipes().isEmpty() && rewards.function().isEmpty();
    }
}
