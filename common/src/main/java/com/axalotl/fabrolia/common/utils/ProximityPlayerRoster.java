package com.axalotl.fabrolia.common.utils;

import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;

import java.util.Iterator;

/** Installed only on an unmodified native PlayerMap. The caller uses next/hasNext only. */
public interface ProximityPlayerRoster {
    Iterator<ServerPlayer> fabrolia$proximityIterator();

    static Iterator<ServerPlayer> iterator(PlayerMap map) {
        // Access wideners can make the vanilla final class extensible. A subclass's
        // getter must still run, even when it inherits our marker from its parent.
        if (map.getClass() == PlayerMap.class && (Object) map instanceof ProximityPlayerRoster roster) {
            return roster.fabrolia$proximityIterator();
        }
        return map.getAllPlayers().iterator();
    }
}
