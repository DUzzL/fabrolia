package com.axalotl.fabrolia.fabric.platform;

import com.axalotl.fabrolia.common.FabroliaCommon;
import com.axalotl.fabrolia.common.platform.MinecraftPlatform;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionLevel;

public class FabricMinecraftPlatform implements MinecraftPlatform {

    @Override
    public boolean hasPermission(CommandSourceStack source, String node, int level) {
        String permission = String.format("%s.%s", FabroliaCommon.MODID, node);
        return Permissions.check(source, permission, PermissionLevel.byId(level));
    }
}