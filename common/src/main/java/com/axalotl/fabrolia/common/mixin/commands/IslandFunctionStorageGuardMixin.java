package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.CommandStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command storage is Main-written state; owner-local function bodies only read
 * it. A read is not pure: the first access of a namespace loads it from saved
 * data into a plain HashMap, so concurrent owner-local readers (several islands,
 * Main's remainder) serialize on the storage while doing it.
 */
@Mixin(CommandStorage.class)
public abstract class IslandFunctionStorageGuardMixin {

    @Inject(method = "set", at = @At("HEAD"))
    private void fabrolia$guardStorageWrite(Identifier id, CompoundTag tag, CallbackInfo ci) {
        IslandFunctionScope.forbid("command storage write " + id);
    }

    @WrapMethod(method = "get")
    private CompoundTag fabrolia$serializeOwnerLocalReads(Identifier id, Operation<CompoundTag> original) {
        if (IslandFunctionScope.current() == null) return original.call(id);
        synchronized (this) {
            return original.call(id);
        }
    }
}
