package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task #35 — detector + gated one-shot heal for the c2me FLAG_BROKEN latch
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
 * {@code getChunk(FULL)} attaches to the never-completing future and hangs
 * (bounded by Fabrolia's chunk bridge since task #34, fatal before it).
 *
 * <p>This class is that missing retry, from the outside: when the bridge
 * times out on a chunk, probe the holder — if it is provably in the latch
 * state (FLAG_BROKEN set, status below target, holder open), log the
 * definitive diagnosis and, if enabled ({@code /fabrolia regions chunkheal}),
 * clear the flag and mark the holder dirty through flowsched's PUBLIC API so
 * the scheduler re-attempts the upgrade. Per-chunk attempt budget: a chunk
 * whose upgrade genuinely cannot succeed re-latches and stays latched after
 * {@link #MAX_HEALS_PER_POS} tries (the pre-heal behavior, minus the
 * mystery). Everything is reflective and fail-closed — without c2me (or on a
 * c2me that renamed things) the probe reports and does nothing.
 */
public final class C2meStrandHeal {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-C2meHeal");

    static final int MAX_HEALS_PER_POS = 3;

    private C2meStrandHeal() {}

    public enum Probe {
        /** Holder is not c2me's, or reflection bootstrap failed — no verdict. */
        NOT_APPLICABLE,
        /** c2me holder inspected; FLAG_BROKEN not set — the stall is NOT the latch. */
        NOT_BROKEN,
        /** Latch confirmed; heal disabled or budget exhausted — logged only. */
        BROKEN_LATCHED,
        /** Latch confirmed and a re-attempt was kicked off. */
        BROKEN_HEALED
    }

    // ---- reflective surface (bootstrapped once, fail-closed) ----
    private static volatile boolean bootstrapTried;
    private static volatile boolean bootstrapOk;
    private static Field newHolderField;          // NewChunkHolderVanillaInterface.newHolder
    private static Method getFlags;               // ItemHolder.getFlags()
    private static Method clearFlag;              // ItemHolder.clearFlag(int)
    private static Method markDirty;              // ItemHolder.markDirty(StatusAdvancingScheduler)
    private static Method isOpen;                 // ItemHolder.isOpen()
    private static Method getStatus;              // ItemHolder.getStatus()
    private static Method getTargetStatus;        // ItemHolder.getTargetStatus()
    private static Method getTheChunkSystem;      // ChunkMap.c2me$getTheChunkSystem()
    private static int flagBroken = 2;            // ItemHolder.FLAG_BROKEN (read reflectively)

    private static final String VANILLA_IFACE =
            "com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface";

    /** Per-chunk heal attempts (session-lifetime; strands are rare). */
    private static final ConcurrentHashMap<Long, Integer> ATTEMPTS = new ConcurrentHashMap<>();

    /** Budget gate, extracted for the unit test. */
    static boolean tryConsumeAttempt(long pos) {
        return ATTEMPTS.merge(pos, 1, Integer::sum) <= MAX_HEALS_PER_POS;
    }

    static void resetForTest() {
        ATTEMPTS.clear();
    }

    /**
     * Called from the chunk-bridge timeout paths with the CURRENT visible
     * holder (may be null) and the level's ChunkMap. Never throws.
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
            // The latch, confirmed live. This chunk has been silently dead
            // since the original upgrade exception (grep the log for
            // 'Error upgrading chunk' at this pos).
            if (!FabroliaConfig.chunkBridgeHealBroken || !tryConsumeAttempt(pos)) {
                LOGGER.error("[fabrolia] c2me FLAG_BROKEN latch CONFIRMED at [{}, {}] "
                                + "(status={} target={}) — chunk abandoned by the chunk system; "
                                + "heal {} (/fabrolia regions chunkheal)",
                        ChunkPos.getX(pos), ChunkPos.getZ(pos), status, target,
                        FabroliaConfig.chunkBridgeHealBroken ? "budget exhausted" : "disabled");
                return Probe.BROKEN_LATCHED;
            }
            Object scheduler = getTheChunkSystem.invoke(chunkMap);
            clearFlag.invoke(itemHolder, flagBroken);
            markDirty.invoke(itemHolder, scheduler);
            LOGGER.error("[fabrolia] c2me FLAG_BROKEN latch CONFIRMED at [{}, {}] "
                            + "(status={} target={}) — cleared the flag and re-marked dirty "
                            + "(attempt {}/{}): the upgrade the chunk system abandoned is retried",
                    ChunkPos.getX(pos), ChunkPos.getZ(pos), status, target,
                    ATTEMPTS.get(pos), MAX_HEALS_PER_POS);
            return Probe.BROKEN_HEALED;
        } catch (Throwable t) {
            LOGGER.warn("[fabrolia] c2me strand probe failed (disabling): {}", t.toString());
            bootstrapOk = false;
            bootstrapTried = true;
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
            Class<?> scheduler = Class.forName("com.ishland.flowsched.scheduler.StatusAdvancingScheduler",
                    false, vanillaIfaceClass.getClassLoader());
            getFlags = itemHolder.getMethod("getFlags");
            clearFlag = itemHolder.getMethod("clearFlag", int.class);
            markDirty = itemHolder.getMethod("markDirty", scheduler);
            isOpen = itemHolder.getMethod("isOpen");
            getStatus = itemHolder.getMethod("getStatus");
            getTargetStatus = itemHolder.getMethod("getTargetStatus");
            flagBroken = itemHolder.getField("FLAG_BROKEN").getInt(null);
            // the duck interface c2me mixes into ChunkMap
            Class<?> access = Class.forName(
                    "com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess",
                    false, vanillaIfaceClass.getClassLoader());
            getTheChunkSystem = access.getMethod("c2me$getTheChunkSystem");
            bootstrapOk = true;
        } catch (Throwable t) {
            LOGGER.warn("[fabrolia] c2me strand probe unavailable on this c2me build: {}", t.toString());
            bootstrapOk = false;
        }
        return bootstrapOk;
    }
}
