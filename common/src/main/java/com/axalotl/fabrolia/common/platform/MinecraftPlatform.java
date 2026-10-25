package com.axalotl.fabrolia.common.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public interface MinecraftPlatform {
    boolean hasPermission(CommandSourceStack source, String node, int level);

    /**
     * The loader's landing decision for a pearl whose owner teleport Fabrolia
     * runs later on Main. {@code hitPosition} is the pearl's position when it hit,
     * {@code previousPosition} vanilla's teleport target.
     */
    default PearlLanding onEnderPearlLand(ServerPlayer player, ThrownEnderpearl pearl, Vec3 hitPosition,
                                          HitResult hitResult, Vec3 previousPosition) {
        return PearlLanding.vanilla(previousPosition);
    }
}
