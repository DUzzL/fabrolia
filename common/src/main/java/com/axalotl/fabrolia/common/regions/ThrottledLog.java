package com.axalotl.fabrolia.common.regions;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Failure-log throttle for per-tick hot paths (2026-07-18 production
 * hardening, audit #7): a permanently-failing entity/BE/unit printed a full
 * stack 20x/s per region indefinitely. Policy per SITE: the first failure
 * prints in full; identical failures (same signature = throwable class +
 * message + top frame) are suppressed and counted; a periodic re-print
 * (10 s cooldown) carries the suppressed count; a CHANGED signature prints
 * immediately — distinct failures are never hidden.
 */
public final class ThrottledLog {

    private static final long COOLDOWN_NANOS = 10_000_000_000L;

    private static final class Site {
        volatile String lastSig;
        final AtomicLong lastPrint = new AtomicLong();
        final LongAdder suppressed = new LongAdder();
    }

    private static final ConcurrentHashMap<String, Site> SITES = new ConcurrentHashMap<>();

    private ThrottledLog() {}

    public static void error(Logger log, String siteKey, Throwable t, String fmt, Object... args) {
        Site site = SITES.computeIfAbsent(siteKey, k -> new Site());
        String sig = signature(t);
        long now = System.nanoTime();
        boolean changed = !sig.equals(site.lastSig);
        long last = site.lastPrint.get();
        boolean cooled = now - last > COOLDOWN_NANOS;
        if (changed || cooled) {
            if ((changed || site.lastPrint.compareAndSet(last, now))) {
                if (changed) {
                    site.lastSig = sig;
                    site.lastPrint.set(now);
                }
                long sup = site.suppressed.sumThenReset();
                Object[] full = java.util.Arrays.copyOf(args, args.length + 1);
                full[args.length] = t;
                log.error(fmt + (sup > 0 ? " (" + sup + " similar suppressed in the last 10s)" : ""), full);
                return;
            }
        }
        site.suppressed.increment();
    }

    /** Throwable-free WARN variant (2026-07-27, save-time delay-overflow
     *  observability): {@code sig} plays the role the throwable signature
     *  plays in {@link #error} — a CHANGED sig prints immediately, identical
     *  sigs inside the cooldown are suppressed and counted. */
    public static void warn(Logger log, String siteKey, String sig, String fmt, Object... args) {
        Site site = SITES.computeIfAbsent(siteKey, k -> new Site());
        long now = System.nanoTime();
        boolean changed = !sig.equals(site.lastSig);
        long last = site.lastPrint.get();
        boolean cooled = now - last > COOLDOWN_NANOS;
        if (changed || cooled) {
            if ((changed || site.lastPrint.compareAndSet(last, now))) {
                if (changed) {
                    site.lastSig = sig;
                    site.lastPrint.set(now);
                }
                long sup = site.suppressed.sumThenReset();
                log.warn(fmt + (sup > 0 ? " (" + sup + " similar suppressed in the last 10s)" : ""), args);
                return;
            }
        }
        site.suppressed.increment();
    }

    private static String signature(Throwable t) {
        if (t == null) return "null";
        StackTraceElement[] st = t.getStackTrace();
        return t.getClass().getName() + '|' + String.valueOf(t.getMessage())
                + '|' + (st.length > 0 ? st[0].toString() : "");
    }
}
