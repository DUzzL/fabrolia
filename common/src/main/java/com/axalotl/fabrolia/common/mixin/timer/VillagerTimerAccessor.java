package com.axalotl.fabrolia.common.mixin.timer;

import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Timer clock fix: villager restock and gossip times ({@code TimerClocks}). */
@Mixin(Villager.class)
public interface VillagerTimerAccessor {
    @Accessor("lastRestockGameTime")
    long fabrolia$lastRestockGameTime();

    @Accessor("lastRestockGameTime")
    void fabrolia$setLastRestockGameTime(long value);

    @Accessor("lastGossipDecayTime")
    long fabrolia$lastGossipDecayTime();

    @Accessor("lastGossipDecayTime")
    void fabrolia$setLastGossipDecayTime(long value);

    @Accessor("lastGossipTime")
    long fabrolia$lastGossipTime();

    @Accessor("lastGossipTime")
    void fabrolia$setLastGossipTime(long value);
}
