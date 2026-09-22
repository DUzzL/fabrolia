package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Vault state-machine timestamps must live on the CONSUMER's clock
 * (2026-08-07 trial-chambers vault stall).
 *
 * <p>Vanilla's vault state machine is clock-driven:
 * {@code VaultBlockEntity$Server.unlock} (the key click) stamps
 * {@code VaultServerData.pauseStateUpdatingUntil(level.getGameTime() + 14)}
 * and the block-entity tick gates every state transition on
 * {@code getGameTime() >= stateUpdatingResumesAt}. Under async islands the
 * click runs on MAIN (player interaction handlers are main-owned and claim
 * the target island — PlayerInteractionOwnerMixin), where the redirected
 * {@code getGameTime()} answers the WALL clock, while the vault's block
 * entity ticks on the island worker reading that island's logical clock L.
 * A lagging island (L ≪ W) then reads the stamped resume time as far in the
 * future and the vault sits in UNLOCKING until L catches up — a visible
 * delay normally, a PERMANENT stall under sustained lag. (The eject-cadence
 * stamps inside {@code VaultState.tickAndGetNext} run on the island worker
 * and are self-consistent; only the click path crosses clocks.)
 *
 * <p>Two complementary rules, both vanilla-exact for every healthy stamp:
 *
 * <ol>
 *   <li><b>Stamp on the owner's clock.</b> {@link #fabrolia$unlockStampOnOwnerClock}
 *       resolves the vault chunk's owning island and stamps its L — the same
 *       "the island IS the level from its own clock's perspective" mapping
 *       the scheduled-tick domains use. Resolution is by CHUNK, not by the
 *       caller's context, so a worker-bound interaction (regional player
 *       ticks) landing on a different island's vault is still stamped
 *       correctly. Global-lane chunks / sync mode / async-off keep the
 *       vanilla wall value bit-for-bit.</li>
 *   <li><b>Heal corrupt deadlines at the only read site.</b>
 *       {@link #fabrolia$clampCrossClockDeadline}: the largest legal pause
 *       this state machine ever stamps is 20 ticks (14 unlock, 20 eject —
 *       verified against the 26.2 bytecode constants), so a deadline more
 *       than 20 ahead of the CONSUMING clock is provably cross-clock
 *       corruption: a pre-fix wall-stamped value persisted to NBT, or an
 *       island merge/absorb handing the vault to an island with an older L.
 *       Vanilla's own bias is toward acting (a vanilla vault can never
 *       legitimately wait longer), so the deadline is treated as due. A
 *       healthy same-domain stamp is always ≤ now+20 and passes through
 *       unchanged.</li>
 * </ol>
 */
@Mixin(VaultBlockEntity.Server.class)
public abstract class VaultServerClockMixin {

    /** The largest legal relative pause in the vault state machine (26.2:
     *  14-tick unlock delay, 20-tick eject cadence). */
    private static final long MAX_LEGAL_PAUSE = 20L;

    @WrapOperation(method = "unlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getGameTime()J"))
    private static long fabrolia$unlockStampOnOwnerClock(ServerLevel level, Operation<Long> original,
                                                         ServerLevel lvl, BlockState state, BlockPos pos,
                                                         VaultConfig config, VaultServerData serverData,
                                                         VaultSharedData sharedData, List<ItemStack> items) {
        long now = original.call(level);
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) {
            return now;
        }
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(ChunkPos.pack(pos));
        if (owner == null || owner.retired) {
            return now;
        }
        return owner.logicalTick;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/vault/VaultServerData;stateUpdatingResumesAt()J"))
    private static long fabrolia$clampCrossClockDeadline(VaultServerData data, Operation<Long> original,
                                                         ServerLevel level, BlockPos pos, BlockState state,
                                                         VaultConfig config, VaultServerData serverData,
                                                         VaultSharedData sharedData) {
        long deadline = original.call(data);
        // level.getGameTime() here is the CONSUMING clock (island L on the
        // owning worker, wall on main / in sync mode) — exactly the clock the
        // comparison below in tick() evaluates against.
        if (deadline - level.getGameTime() > MAX_LEGAL_PAUSE) {
            return level.getGameTime();
        }
        return deadline;
    }
}
