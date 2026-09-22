package com.axalotl.fabrolia.common.regions;

import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Task #35 — detector for the c2me FLAG_BROKEN latch
 * (root cause of the 2026-07-28 06:56 production wedge, verified against
 * decompiled c2me 0.4.1-beta.1.0/0.4.2-alpha sources; see
 * docs/C2ME-FLAG_BROKEN-STRAND.md for the full analysis and the upstream
 * report):
 *
 * <p>One exception in a chunk upgrade transaction makes c2me's
 * {@code TheChunkSystem.handleTransactionException} return MARK_BROKEN;
 * flowsched sets {@code ItemHolder.FLAG_BROKEN} WITHOUT failing the live
 * pending status futures, and {@code tickHolder0} silently early-returns for
 * broken holders forever after. Upstream has no recovery: {@code clearFlag}
 * has zero call sites and {@code FLAG_HAVE_RETRIED} is dead code — the retry
 * it was evidently reserved for was never built. Every later vanilla
 * {@code getChunk(FULL)} attaches to the never-completing future and hangs.
 * Fabrolia may diagnose that state, but never starts a replacement upgrade.
 *
 * <p>The old implementation cleared the latch and marked the holder dirty,
 * which retried a chunk-upgrade transaction after its original body had
 * already thrown and may have partially mutated state. The probe is now
 * forensic only until it recognizes the broken latch; that recognized state
 * is a fatal invariant and escapes unchanged. Without c2me (or on a build
 * that renamed the optional surface), the probe declines before entry.
 */
public final class C2meStrandHeal {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-C2meHeal");

    private C2meStrandHeal() {}

    public enum Probe {
        /** Holder is not c2me's, or reflection bootstrap failed — no verdict. */
        NOT_APPLICABLE,
        /** c2me holder inspected; FLAG_BROKEN not set — the stall is NOT the latch. */
        NOT_BROKEN
    }

    // ---- reflective surface (bootstrapped once, fail-closed) ----
    private static volatile boolean bootstrapTried;
    private static volatile boolean bootstrapOk;
    private static Field newHolderField;          // NewChunkHolderVanillaInterface.newHolder
    private static Method getFlags;               // ItemHolder.getFlags()
    private static Method isOpen;                 // ItemHolder.isOpen()
    private static Method getStatus;              // ItemHolder.getStatus()
    private static Method getTargetStatus;        // ItemHolder.getTargetStatus()
    private static int flagBroken = 2;            // ItemHolder.FLAG_BROKEN (read reflectively)

    private static final String VANILLA_IFACE =
            "com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface";

    /**
     * Called from the chunk-bridge timeout paths with the CURRENT visible
     * holder (may be null) and the level's ChunkMap. Optional bootstrap
     * incompatibility declines the probe before entry; a failure after a
     * recognized c2me holder is entered propagates unchanged.
     */
    public static Probe inspectAndMaybeHeal(Object chunkHolder, Object chunkMap, long pos) {
        try {
            if (chunkHolder == null || chunkMap == null) {
                return Probe.NOT_APPLICABLE;
            }
            if (!chunkHolder.getClass().getName().equals(VANILLA_IFACE)) {
                return Probe.NOT_APPLICABLE;
            }
            if (!bootstrap(chunkHolder.getClass())) {
                return Probe.NOT_APPLICABLE;
            }
            Object itemHolder = newHolderField.get(chunkHolder);
            if (itemHolder == null || !(Boolean) isOpen.invoke(itemHolder)) {
                return Probe.NOT_APPLICABLE;
            }
            int flags = (Integer) getFlags.invoke(itemHolder);
            Object status = getStatus.invoke(itemHolder);
            Object target = getTargetStatus.invoke(itemHolder);
            if ((flags & flagBroken) == 0) {
                LOGGER.warn("[fabrolia] c2me holder at [{}, {}] stalled but NOT broken-latched "
                                + "(flags={} status={} target={}) — dormant-busy family, report this",
                        ChunkPos.getX(pos), ChunkPos.getZ(pos), flags, status, target);
                return Probe.NOT_BROKEN;
            }
            IllegalStateException failure = new IllegalStateException(
                    "c2me FLAG_BROKEN after failed chunk upgrade at ["
                            + ChunkPos.getX(pos) + ", " + ChunkPos.getZ(pos)
                            + "] status=" + status + " target=" + target
                            + " (no clearFlag/markDirty retry performed)");
            publishFatalAndRethrow(failure);
            return Probe.NOT_BROKEN;
        } catch (InvocationTargetException targetFailure) {
            Throwable cause = targetFailure.getCause();
            publishFatalAndRethrow(cause == null ? targetFailure : cause);
            return Probe.NOT_APPLICABLE;
        } catch (ReflectiveOperationException reflectionFailure) {
            publishFatalAndRethrow(reflectionFailure);
            return Probe.NOT_APPLICABLE;
        }
    }

    private static synchronized boolean bootstrap(Class<?> vanillaIfaceClass) {
        if (bootstrapTried) {
            return bootstrapOk;
        }
        bootstrapTried = true;
        try {
            newHolderField = vanillaIfaceClass.getDeclaredField("newHolder");
            newHolderField.setAccessible(true);
            Class<?> itemHolder = Class.forName("com.ishland.flowsched.scheduler.ItemHolder",
                    false, vanillaIfaceClass.getClassLoader());
            getFlags = itemHolder.getMethod("getFlags");
            isOpen = itemHolder.getMethod("isOpen");
            getStatus = itemHolder.getMethod("getStatus");
            getTargetStatus = itemHolder.getMethod("getTargetStatus");
            flagBroken = itemHolder.getField("FLAG_BROKEN").getInt(null);
            bootstrapOk = true;
        } catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {
            LOGGER.warn("[fabrolia] c2me strand probe unavailable on this c2me build: {}",
                    unavailable.toString());
            bootstrapOk = false;
        }
        return bootstrapOk;
    }

    private static void publishFatalAndRethrow(Throwable failure) {
        C2meStrandHeal.<RuntimeException>rethrow(
                RegionScheduler.publishFatalAndSelectWinner(failure));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
