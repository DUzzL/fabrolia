package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRequirements;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

/** Preparation is immutable/offline; apply publishes metadata with its resource generation. */
@Mixin(ServerAdvancementManager.class)
public abstract class ServerAdvancementManagerMixin extends SimpleJsonResourceReloadListener<Advancement>
        implements AdvancementRequirements.Provider {
    @Shadow @Final private HolderLookup.Provider registries;
    @Unique private volatile AdvancementRequirements fabrolia$requirements = AdvancementRequirements.UNKNOWN;

    protected ServerAdvancementManagerMixin() {
        super(Advancement.CODEC, FileToIdConverter.json("advancement"));
    }

    @Override
    protected Map<Identifier, Advancement> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<Identifier, Advancement> prepared = super.prepare(resources, profiler);
        var requirements = AdvancementRequirements.prepare(prepared.values(), registries);
        return new AdvancementRequirements.Prepared(prepared, requirements);
    }

    @WrapMethod(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V")
    private void fabrolia$publishRequirements(Map<Identifier, Advancement> prepared,
                                              ResourceManager resources, ProfilerFiller profiler,
                                              Operation<Void> original) {
        AdvancementRequirements requirements = prepared instanceof AdvancementRequirements.Prepared generation
                ? generation.requirements() : AdvancementRequirements.UNKNOWN;
        original.call(prepared, resources, profiler);
        fabrolia$requirements = requirements;
    }

    @Override
    public AdvancementRequirements fabrolia$advancementRequirements() {
        return fabrolia$requirements;
    }

    @Override
    public boolean fabrolia$tracksListenerInsertions() {
        return true;
    }

}
