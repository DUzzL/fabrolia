package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.CatSpawnerTickAccessor;
import com.axalotl.fabrolia.common.mixin.world.PatrolSpawnerTickAccessor;
import com.axalotl.fabrolia.common.mixin.world.PhantomSpawnerTickAccessor;
import com.axalotl.fabrolia.common.mixin.world.WanderingTraderSpawnerTickAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Vanilla's custom spawners tick on Main. On the tick their countdown reaches
 * zero they pick players, read blocks around them and add mobs, in chunks that
 * a running island may own (audit C-02). That one tick runs under the global
 * fence; every other tick only counts down and keeps its vanilla place.
 * Spawners Fabrolia does not know keep running unfenced.
 */
public final class CustomSpawnerFence {
    private CustomSpawnerFence() {}

    public static void tick(ServerLevel level, CustomSpawner spawner, boolean spawnEnemies, Runnable body) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive || rs.currentThreadHoldsAllClaims()
                || IslandCommands.fenceWindowHeld() || !worksThisTick(level, spawner, spawnEnemies)) {
            body.run();
            return;
        }
        AsyncIslandPlayerTicks.runFencedMainMutation("custom spawner world work", body);
    }

    /** Mirrors each spawner's own gates: true when this call decrements its countdown to zero. */
    static boolean worksThisTick(ServerLevel level, CustomSpawner spawner, boolean spawnEnemies) {
        if (spawner instanceof PhantomSpawnerTickAccessor phantom) {
            return spawnEnemies && phantom.fabrolia$nextTick() <= 1
                    && level.getGameRules().get(GameRules.SPAWN_PHANTOMS);
        }
        if (spawner instanceof PatrolSpawnerTickAccessor patrol) {
            return spawnEnemies && patrol.fabrolia$nextTick() <= 1
                    && level.getGameRules().get(GameRules.SPAWN_PATROLS);
        }
        if (spawner instanceof CatSpawnerTickAccessor cat) {
            return cat.fabrolia$nextTick() <= 1;
        }
        if (spawner instanceof WanderingTraderSpawnerTickAccessor trader) {
            return trader.fabrolia$tickDelay() <= 1
                    && level.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS);
        }
        return false;
    }
}
