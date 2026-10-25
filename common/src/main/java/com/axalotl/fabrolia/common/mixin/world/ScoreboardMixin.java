package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.axalotl.fabrolia.common.utils.ConcurrentObject2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(Scoreboard.class)
public class ScoreboardMixin {

    @Shadow
    private final Map<String, Map<Objective, Score>> playerScores = ConcurrentCollections.newHashMap();

    // Island workers remove dead team members (Scoreboard#entityRemoved) while
    // other threads read getPlayersTeam (collision rules, Entity#getTeam) and Main
    // runs /team; distinct names must not corrupt the open-hash table.
    @Shadow
    private final Object2ObjectMap<String, PlayerTeam> teamsByPlayer = new ConcurrentObject2ObjectMap<>();
}