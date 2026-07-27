package com.axalotl.fabrolia.neoforge.platform;

import com.axalotl.fabrolia.common.FabroliaCommon;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.Map;

public class NeoForgePermissions {
    private static final Map<String, PermissionNode<Boolean>> PERMISSIONS = NeoForgePermissions.build(
    );

    public static PermissionNode<Boolean> getPermissionNode(String node) {
        return PERMISSIONS.get(node);
    }

    public static void addNodes(PermissionGatherEvent.Nodes event) {
        for (PermissionNode<Boolean> node : PERMISSIONS.values()) {
            event.addNodes(node);
        }
    }

    private static Map<String, PermissionNode<Boolean>> build() {
        Map<String, PermissionNode<Boolean>> permissions = new Object2ObjectOpenHashMap<>();
        for (String node : new String[]{"command.config", "command.statistics"}) {
            permissions.put(node, new PermissionNode<>(FabroliaCommon.MODID, node, PermissionTypes.BOOLEAN, (_, _, _) -> false));
        }
        return permissions;
    }
}