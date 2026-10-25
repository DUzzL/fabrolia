package com.axalotl.fabrolia.neoforge.platform;

import com.axalotl.fabrolia.common.platform.MinecraftPlatform;
import com.axalotl.fabrolia.common.platform.PearlLanding;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;

public class NeoForgeMinecraftPlatform implements MinecraftPlatform {
    @Override
    public boolean hasPermission(CommandSourceStack source, String node, int level) {
        if (source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)))) {
            return true;
        }

        ServerPlayer player = source.getPlayer();
        PermissionNode<Boolean> permission = NeoForgePermissions.getPermissionNode(node);
        if (player == null || permission == null) {
            return false;
        }

        return PermissionAPI.getPermission(player, permission);
    }

    /** NeoForge fires EntityTeleportEvent.EnderPearl at the pearl's hit position (patched onHit). */
    @Override
    public PearlLanding onEnderPearlLand(ServerPlayer player, ThrownEnderpearl pearl, Vec3 hitPosition,
                                         HitResult hitResult, Vec3 previousPosition) {
        EntityTeleportEvent.EnderPearl event = EventHooks.onEnderPearlLand(
                player, hitPosition.x, hitPosition.y, hitPosition.z, pearl, 5.0F, hitResult);
        return new PearlLanding(event.isCanceled(), event.getTarget(), event.getAttackDamage(), true);
    }
}
