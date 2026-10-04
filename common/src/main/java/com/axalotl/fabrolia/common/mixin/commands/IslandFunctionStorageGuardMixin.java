package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.CommandStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Command storage is Main-written state; owner-local function bodies only read it. */
@Mixin(CommandStorage.class)
public abstract class IslandFunctionStorageGuardMixin {

    @Inject(method = "set", at = @At("HEAD"))
    private void fabrolia$guardStorageWrite(Identifier id, CompoundTag tag, CallbackInfo ci) {
        IslandFunctionScope.forbid("command storage write " + id);
    }
}
