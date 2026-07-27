package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionAnalyzer;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * A8 Phase 3 — the analyzer seam: every datapack function passes through
 * {@link CommandFunction#fromLines} with its RAW lines at (re)load. Source
 * analysis here covers interpreted AND MCFC-compiled execution alike (MCFC's
 * contract is observable equivalence with these same lines); MCFC-supplied
 * metadata may overwrite the registration afterwards via the same registry.
 */
@Mixin(CommandFunction.class)
public interface CommandFunctionMixin {

    @Inject(method = "fromLines", at = @At("HEAD"))
    private static <T extends net.minecraft.commands.ExecutionCommandSource<T>> void fabrolia$analyzeFunction(
            Identifier id, com.mojang.brigadier.CommandDispatcher<T> dispatcher,
            T source, List<String> lines,
            CallbackInfoReturnable<CommandFunction<T>> cir) {
        try {
            FunctionAnalyzer.analyze(id, lines);
        } catch (Throwable t) {
            // analysis is best-effort; absent analysis = fenced path
        }
    }
}
