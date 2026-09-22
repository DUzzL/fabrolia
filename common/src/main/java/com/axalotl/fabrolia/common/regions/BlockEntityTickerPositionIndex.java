package com.axalotl.fabrolia.common.regions;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Registration-time position fallback for rebindable block-entity tickers.
 *
 * <p>Lithium puts a fully sleeping block entity behind a shared no-op ticker
 * whose {@code getPos()} returns {@code null}. The outer vanilla rebindable
 * wrapper is still the same object, however, and its position was observable
 * when {@code Level.addBlockEntityTicker} registered it. Keeping that stable
 * value lets a later cold index rebuild route the sleeping wrapper to its
 * owning cell instead of mistaking it for a truly positionless main-lane
 * ticker.
 *
 * <p>The index is active even while region threading is disabled, because a
 * runtime {@code regions on} is precisely when already-sleeping wrappers need
 * the fallback. Weak keys keep that dormant-mode bookkeeping bounded by the
 * vanilla ticker list's own lifetime. The synchronized view covers c2me chunk
 * registration racing a main-thread bulk rebuild; both operations are rare.
 *
 * <p>Pure Java by design so the sleep/rebuild policy is unit-testable without
 * Minecraft objects.
 */
final class BlockEntityTickerPositionIndex<K, P> {

    private final Map<K, P> registrationPositions =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Remember only complete registration observations. */
    void remember(K ticker, P position) {
        if (ticker != null && position != null) {
            registrationPositions.put(ticker, position);
        }
    }

    /** A live position is authoritative; registration is only a null fallback. */
    P resolve(K ticker, P livePosition) {
        return livePosition != null ? livePosition : registrationPositions.get(ticker);
    }
}
