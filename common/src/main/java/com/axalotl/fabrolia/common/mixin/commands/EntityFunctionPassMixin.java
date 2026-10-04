package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionPassMark;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicLong;

/** {@link FunctionPassMark} state; new entities owe a pass (vanilla passes them next tick). */
@Mixin(Entity.class)
public abstract class EntityFunctionPassMixin implements FunctionPassMark {

    @Unique
    private final AtomicLong fabrolia$functionPass = new AtomicLong(FunctionPassMark.OWED);

    @Override
    public Claim fabrolia$claimFunctionPass(long passId) {
        long observed = fabrolia$functionPass.get();
        if (observed == passId) return Claim.SAME;
        if (observed != FunctionPassMark.OWED) return Claim.TAKEN;
        return fabrolia$functionPass.compareAndSet(FunctionPassMark.OWED, passId)
                ? Claim.NEW
                : (fabrolia$functionPass.get() == passId ? Claim.SAME : Claim.TAKEN);
    }

    @Override
    public void fabrolia$functionPassTick() {
        fabrolia$functionPass.set(FunctionPassMark.OWED);
    }
}
