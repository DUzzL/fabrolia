package com.axalotl.fabrolia.common.parallelised.advancement;

import net.minecraft.commands.Commands;

import java.lang.reflect.Field;

/**
 * Runs routed advancement work as its own top-level command execution.
 *
 * <p>{@code ServerFunctionManager.execute} only queues a function into
 * {@code Commands.CURRENT_EXECUTION_CONTEXT} when one is active, to run after the
 * current command. Routed work belongs to its earlier fire time, not to the
 * command that happens to reach a drain (a kick reaching {@code PlayerList.remove});
 * queued there it would run after the leaving player's advancements were saved.
 * The enclosing context is suspended for the body and restored afterwards.
 */
final class CommandContextIsolation {

    private static final ThreadLocal<Object> CURRENT_EXECUTION_CONTEXT = resolve();

    private CommandContextIsolation() {}

    static void runTopLevel(Runnable body) {
        Object enclosing = CURRENT_EXECUTION_CONTEXT.get();
        if (enclosing == null) {
            body.run();
            return;
        }
        CURRENT_EXECUTION_CONTEXT.set(null);
        try {
            body.run();
        } finally {
            CURRENT_EXECUTION_CONTEXT.set(enclosing);
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> resolve() {
        try {
            Field field = Commands.class.getDeclaredField("CURRENT_EXECUTION_CONTEXT");
            field.setAccessible(true);
            return (ThreadLocal<Object>) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Commands.CURRENT_EXECUTION_CONTEXT is unavailable", failure);
        }
    }
}
