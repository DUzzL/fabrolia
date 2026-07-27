package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import net.minecraft.resources.Identifier;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.RandomSequences;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(RandomSequences.class)
public class RandomSequencesMixin {

    @Shadow
    private final Map<Identifier, RandomSequence> sequences = ConcurrentCollections.newHashMap();
}