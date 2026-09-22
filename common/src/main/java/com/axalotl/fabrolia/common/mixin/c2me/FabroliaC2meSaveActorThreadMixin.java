package com.axalotl.fabrolia.common.mixin.c2me;

import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Extends C2ME's two asynchronous-save catchers to Fabrolia's authenticated
 * exact-player actor without weakening them for any other worker.
 *
 * <p>C2ME injects independent HEAD handlers into {@code saveAllChunks} and
 * {@code saveEverything}.  Each compares {@link Thread#currentThread()} with
 * the physical {@code serverThread} and throws when they differ.  Physical main
 * is synchronously waiting while an exact actor turn runs, so that actor is the
 * one additional logical owner.  Targeting the two upstream handlers preserves
 * C2ME's exception and injection semantics; only the compared thread value is
 * substituted, and only while the authenticated actor scope is active.</p>
 */
@Mixin(value = MinecraftServer.class, priority = 2000)
public abstract class FabroliaC2meSaveActorThreadMixin {

    @Shadow
    @Final
    private Thread serverThread;

    @Dynamic("C2ME saveAllChunks injection handler selected through MixinSquared")
    @TargetHandler(
            mixin = "com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers.MixinMinecraftServer",
            name = "preventAsyncSave",
            prefix = "handler")
    @ModifyExpressionValue(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1,
            remap = false)
    private Thread fabrolia$logicalC2meSaveAllChunksThread(Thread original) {
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                ? this.serverThread
                : original;
    }

    @Dynamic("C2ME saveEverything injection handler selected through MixinSquared")
    @TargetHandler(
            mixin = "com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers.MixinMinecraftServer",
            name = "preventAsyncSaveAll",
            prefix = "handler")
    @ModifyExpressionValue(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1,
            remap = false)
    private Thread fabrolia$logicalC2meSaveEverythingThread(Thread original) {
        return RegionalPlayerEntityTicksV2.inExactPlayerActor()
                ? this.serverThread
                : original;
    }
}
