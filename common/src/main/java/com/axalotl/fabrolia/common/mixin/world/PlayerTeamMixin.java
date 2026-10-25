package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

/** Member set of a team: see the teamsByPlayer note in {@link ScoreboardMixin}. */
@Mixin(PlayerTeam.class)
public class PlayerTeamMixin {

    @Shadow
    private final Set<String> players = ConcurrentCollections.newHashSet();
}
