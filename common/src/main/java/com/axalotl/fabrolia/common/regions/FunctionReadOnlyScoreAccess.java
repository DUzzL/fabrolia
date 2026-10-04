package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.world.scores.ScoreAccess;
import org.jspecify.annotations.Nullable;

/**
 * Score of a name-only (fake-player) holder handed to an owner-local function
 * body: the plan proved such scores are only read there, so every mutation is
 * a fail-stop instead of an unsynchronized cross-island write.
 */
public final class FunctionReadOnlyScoreAccess implements ScoreAccess {

    private final ScoreAccess delegate;
    private final FunctionPartition scope;
    private final String what;

    public FunctionReadOnlyScoreAccess(ScoreAccess delegate, FunctionPartition scope, String what) {
        this.delegate = delegate;
        this.scope = scope;
        this.what = what;
    }

    private RuntimeException write(String op) {
        return IslandFunctionScope.violation(scope, op + " of fake-player score " + what);
    }

    @Override
    public int get() {
        return delegate.get();
    }

    @Override
    public void set(int value) {
        throw write("set");
    }

    @Override
    public boolean locked() {
        return delegate.locked();
    }

    @Override
    public void unlock() {
        throw write("unlock");
    }

    @Override
    public void lock() {
        throw write("lock");
    }

    @Override
    public @Nullable Component display() {
        return delegate.display();
    }

    @Override
    public void display(@Nullable Component display) {
        throw write("display");
    }

    @Override
    public void numberFormatOverride(@Nullable NumberFormat numberFormat) {
        throw write("number format");
    }
}
