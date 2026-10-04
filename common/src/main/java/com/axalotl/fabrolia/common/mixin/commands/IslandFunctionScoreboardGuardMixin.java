package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionPartition;
import com.axalotl.fabrolia.common.regions.FunctionPlayerSnapshot;
import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ownership guards for owner-local function bodies ({@link IslandFunctionScope}):
 * scores are touched only for partition-owned entities or for existing
 * fake-player scores (reads the plan proved), and objectives, display slots
 * and teams are Main-only state.
 */
@Mixin(Scoreboard.class)
public abstract class IslandFunctionScoreboardGuardMixin {

    @Shadow
    public abstract ReadOnlyScoreInfo getPlayerScoreInfo(ScoreHolder name, Objective objective);

    @Inject(method = "getOrCreatePlayerScore(Lnet/minecraft/world/scores/ScoreHolder;"
            + "Lnet/minecraft/world/scores/Objective;Z)Lnet/minecraft/world/scores/ScoreAccess;",
            at = @At("HEAD"))
    private void fabrolia$guardScoreAccess(ScoreHolder holder, Objective objective, boolean forceWritable,
                                           CallbackInfoReturnable<ScoreAccess> cir) {
        FunctionPartition scope = IslandFunctionScope.current();
        if (scope == null) return;
        if (holder instanceof Entity entity) {
            IslandFunctionScope.checkEntity(entity, "score access " + objective.getName());
            return;
        }
        String name = holder.getScoreboardName();
        if (FunctionPlayerSnapshot.isOnlinePlayerName(name)
                || this.getPlayerScoreInfo(holder, objective) == null) {
            throw IslandFunctionScope.violation(scope,
                    "score access of holder " + name + " / " + objective.getName());
        }
    }

    /** Name-only holders are read-only inside owner-local bodies. */
    @Inject(method = "getOrCreatePlayerScore(Lnet/minecraft/world/scores/ScoreHolder;"
            + "Lnet/minecraft/world/scores/Objective;Z)Lnet/minecraft/world/scores/ScoreAccess;",
            at = @At("RETURN"), cancellable = true)
    private void fabrolia$readOnlyFakeScore(ScoreHolder holder, Objective objective, boolean forceWritable,
                                            CallbackInfoReturnable<ScoreAccess> cir) {
        FunctionPartition scope = IslandFunctionScope.current();
        if (scope == null || holder instanceof Entity) return;
        cir.setReturnValue(new com.axalotl.fabrolia.common.regions.FunctionReadOnlyScoreAccess(
                cir.getReturnValue(), scope, holder.getScoreboardName() + " / " + objective.getName()));
    }

    @Inject(method = "resetAllPlayerScores", at = @At("HEAD"))
    private void fabrolia$guardResetAll(ScoreHolder holder, CallbackInfo ci) {
        fabrolia$guardHolderWrite(holder, "score reset");
    }

    @Inject(method = "resetSinglePlayerScore", at = @At("HEAD"))
    private void fabrolia$guardResetSingle(ScoreHolder holder, Objective objective, CallbackInfo ci) {
        fabrolia$guardHolderWrite(holder, "score reset " + objective.getName());
    }

    @Unique
    private static void fabrolia$guardHolderWrite(ScoreHolder holder, String what) {
        FunctionPartition scope = IslandFunctionScope.current();
        if (scope == null) return;
        if (holder instanceof Entity entity) {
            IslandFunctionScope.checkEntity(entity, what);
        } else {
            throw IslandFunctionScope.violation(scope, what + " of holder " + holder.getScoreboardName());
        }
    }

    @Inject(method = {"removeObjective", "setDisplayObjective", "removePlayerTeam",
            "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V"},
            at = @At("HEAD"))
    private void fabrolia$guardMainOnlyState(CallbackInfo ci) {
        IslandFunctionScope.forbid("objective/display/team mutation");
    }

    @Inject(method = "addObjective", at = @At("HEAD"))
    private void fabrolia$guardAddObjective(CallbackInfoReturnable<Objective> cir) {
        IslandFunctionScope.forbid("objective creation");
    }

    @Inject(method = "addPlayerTeam", at = @At("HEAD"))
    private void fabrolia$guardAddTeam(CallbackInfoReturnable<PlayerTeam> cir) {
        IslandFunctionScope.forbid("team creation");
    }

    @Inject(method = {"addPlayerToTeam", "removePlayerFromTeam(Ljava/lang/String;)Z"}, at = @At("HEAD"))
    private void fabrolia$guardTeamMembership(CallbackInfoReturnable<Boolean> cir) {
        IslandFunctionScope.forbid("team membership change");
    }
}
