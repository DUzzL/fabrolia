package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaPushableEntityQuery;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(value = Level.class, priority = 1500)
public abstract class LevelPushableEntitiesMixin {

    @WrapMethod(method = "getPushableEntities")
    private List<Entity> fabrolia$getPushableEntities(
            Entity pusher, AABB boundingBox, Operation<List<Entity>> original) {
        Level level = (Level) (Object) this;
        if (level instanceof FabroliaPushableEntityQuery indexed) {
            return indexed.fabrolia$getPushableEntities(pusher, boundingBox);
        }
        return level.getEntities(pusher, boundingBox, EntitySelector.pushableBy(pusher));
    }
}