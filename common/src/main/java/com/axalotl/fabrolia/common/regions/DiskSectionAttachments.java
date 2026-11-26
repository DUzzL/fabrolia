package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;

import java.util.function.LongConsumer;

/** One storage's structural guard. A disk attachment reserves a key before the native lookup,
 * then pins exactly the returned section until native add finishes. The native map operations
 * retain their structural guard; section add and tracking/listener callbacks do not take it. */
public final class DiskSectionAttachments {
    public interface Access {
        DiskSectionAttachments fabrolia$diskSectionAttachments();
    }

    /** One invocation of the native disk consumer; scopes may nest, including for the same key. */
    public static final class Pin implements AutoCloseable {
        public final EntityAccess entity;
        private DiskSectionAttachments guard;
        private Pin next;
        private long key;
        private EntitySection<?> section;
        private EntitySection<?> deferredRemoval;

        public Pin(EntityAccess entity) {
            this.entity = entity;
        }

        public void bind(EntitySection<?> returned) {
            synchronized (guard) {
                section = returned;
            }
        }

        @Override public void close() {
            if (guard != null) guard.release(this);
        }

        /** A modded prune may throw the very same Throwable as add. try-with-resources
         * would replace it with IllegalArgumentException from Throwable.addSuppressed. */
        public void close(Throwable primary) {
            try {
                close();
            } catch (Throwable cleanup) {
                FailStops.<RuntimeException>rethrow(FailStops.mergeFailure(primary, cleanup));
            }
        }
    }

    private final EntitySectionStorage<?> storage;
    private final LongConsumer nativeRemove;
    private static final ScopedValue<Request> REQUEST = ScopedValue.newInstance();

    private static final class Request {
        final DiskSectionAttachments guard;
        final long key;
        final EntitySection<?> expected;
        final Thread thread = Thread.currentThread();
        boolean insideStorage;

        Request(DiskSectionAttachments guard, long key, EntitySection<?> expected) {
            this.guard = guard;
            this.key = key;
            this.expected = expected;
        }
    }
    private Pin pins;
    // Only concurrent emptiness observations need invalidation, and only for their exact key.
    // These small lists are bounded by concurrent/reentrant callers, never by loaded sections.
    private Check checks;
    private Check spare;

    private static final class Check {
        long key;
        boolean invalidated;
        Check next;
    }

    public DiskSectionAttachments(EntitySectionStorage<?> storage) {
        this.storage = storage;
        this.nativeRemove = storage::remove;
    }

    /** The native call-site chain runs now on its caller, even on contention. Only the
     * physical empty-section retirement may be deferred; no Mixin Operation escapes. */
    public void requestRemoval(long key, EntitySection<?> expected, Runnable callSite) {
        ScopedValue.where(REQUEST, new Request(this, key, expected)).run(callSite);
    }

    /** Entry from the native storage.remove wrapper. Unconditional removals retain their
     * old behavior. A nested call inside a modded predicate does not inherit its caller's
     * condition; a nested conditional call site supplies its own scope. */
    public void remove(long key, LongConsumer body) {
        Request r = enterCallSite(key);
        if (r != null) {
            try {
                removeIfEmpty(key, r.expected, body);
            } finally {
                r.insideStorage = false;
            }
        } else {
            synchronized (this) { body.accept(key); }
        }
    }

    private Request enterCallSite(long key) {
        Request r = REQUEST.isBound() ? REQUEST.get() : null;
        if (r == null || r.guard != this || r.key != key || r.thread != Thread.currentThread() || r.insideStorage) return null;
        r.insideStorage = true;
        return r;
    }

    public synchronized void reserve(Pin pin, long key) {
        if (pin.guard != null) throw new IllegalStateException("disk entity reserved two sections");
        pin.guard = this;
        pin.key = key;
        pin.next = pins;
        pins = pin;
        for (Check c = checks; c != null; c = c.next) {
            if (c.key == key) c.invalidated = true;
        }
    }

    private void release(Pin pin) {
        EntitySection<?> deferred;
        synchronized (this) {
            Pin previous = null;
            for (Pin p = pins; p != pin; p = p.next) previous = p;
            if (previous == null) pins = pin.next;
            else previous.next = pin.next;
            pin.guard = null;
            deferred = pin.deferredRemoval;
            pin.next = null;
            pin.section = null;
            pin.deferredRemoval = null;
        }
        // On an exception, vanilla may or may not have inserted the entity. Never replay add,
        // and never prune just because it failed: settle only an actual conditional removal.
        if (deferred != null) removeIfEmpty(pin.key, deferred);
    }

    /** Called only for a conditional native/Lithium prune, never for an unconditional remove.
     * isEmpty includes Lithium's listener state. It can reenter mod code, so evaluate it outside
     * the structural guard. A disk reservation crossing that observation invalidates it, even
     * if the entire lookup/add has already returned. Recheck identity before native removal. */
    public void removeIfEmpty(long key, EntitySection<?> expected) {
        // A listener or a releasing pin can run reentrantly within a caller's wrapper.
        // This is a distinct native retirement, not another invocation of that call site.
        Request outer = enterCallSite(key);
        try {
            removeIfEmpty(key, expected, nativeRemove);
        } finally {
            if (outer != null) outer.insideStorage = false;
        }
    }

    private void removeIfEmpty(long key, EntitySection<?> expected, LongConsumer remove) {
        while (true) {
            Check check;
            synchronized (this) {
                if (storage.getSection(key) != expected) return;
                for (Pin p = pins; p != null; p = p.next) {
                    if (p.key == key && (p.section == null || p.section == expected)) {
                        p.deferredRemoval = expected;
                        return;
                    }
                }
                check = spare;
                if (check == null) check = new Check();
                else spare = null;
                check.key = key;
                check.invalidated = false;
                check.next = checks;
                checks = check;
            }
            boolean empty;
            try {
                empty = expected.isEmpty();
            } catch (Throwable failure) {
                synchronized (this) { finishCheck(check); }
                throw failure;
            }
            synchronized (this) {
                try {
                    if (!empty || storage.getSection(key) != expected) return;
                    if (check.invalidated) continue;
                    remove.accept(key);
                    return;
                } finally {
                    finishCheck(check);
                }
            }
        }
    }

    private void finishCheck(Check check) {
        Check previous = null;
        for (Check c = checks; c != check; c = c.next) previous = c;
        if (previous == null) checks = check.next;
        else previous.next = check.next;
        check.next = null;
        spare = check;
    }
}
