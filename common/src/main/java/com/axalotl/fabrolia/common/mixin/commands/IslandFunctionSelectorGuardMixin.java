package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Owner-local function bodies may evaluate only {@code @s}: a root line's
 * fan-out is evaluated by its partition, never through vanilla's global
 * enumeration (PlayerList / every level's entity lookup).
 */
@Mixin(EntitySelector.class)
public abstract class IslandFunctionSelectorGuardMixin {

    @Shadow
    public abstract boolean isSelfSelector();

    @Inject(method = "findEntities", at = @At("HEAD"))
    private void fabrolia$guardEntities(CommandSourceStack sender,
                                        CallbackInfoReturnable<List<?>> cir) {
        if (!this.isSelfSelector()) {
            IslandFunctionScope.forbid("a global entity selector enumeration");
        }
    }

    @Inject(method = "findPlayers", at = @At("HEAD"))
    private void fabrolia$guardPlayers(CommandSourceStack sender,
                                       CallbackInfoReturnable<List<?>> cir) {
        if (!this.isSelfSelector()) {
            IslandFunctionScope.forbid("a global player selector enumeration");
        }
    }
}
