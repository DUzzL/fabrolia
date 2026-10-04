package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionPassMark;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FunctionPassMark} state; new entities owe a pass (vanilla passes them
 * before their first tick). An AtomicLong rather than a VarHandle on a mixin
 * field: {@code @Unique} field names may be remapped when merged.
 */
@Mixin(Entity.class)
public abstract class EntityFunctionPassMixin implements FunctionPassMark {

    @Unique
    private final AtomicLong fabrolia$functionPass = new AtomicLong(FunctionPassMark.OWED);

    @Override
    public Claim fabrolia$claimFunctionPass(long passId) {
        long observed = fabrolia$functionPass.get();
        if (observed == passId) return Claim.SAME;
        if (observed != FunctionPassMark.OWED) return Claim.TAKEN;
        long witness = fabrolia$functionPass.compareAndExchange(FunctionPassMark.OWED, passId);
        if (witness == FunctionPassMark.OWED) return Claim.NEW;
        return witness == passId ? Claim.SAME : Claim.TAKEN;
    }

    @Override
    public boolean fabrolia$functionPassOwed() {
        return fabrolia$functionPass.get() == FunctionPassMark.OWED;
    }

    @Override
    public void fabrolia$functionPassTick() {
        fabrolia$functionPass.set(FunctionPassMark.OWED);
    }
}
