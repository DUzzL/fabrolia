package com.axalotl.fabrolia.common.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class EffectMapTest {
    @Test
    void currentEntryCanBeReplacedThenRemovedThroughTheSameIterator() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "old");
        synchronized (map) {
            Iterator<Map.Entry<Integer, String>> iterator = map.entrySet().iterator();
            iterator.next().setValue("replacement");
            iterator.remove();
            assertTrue(map.isEmpty());
        }
    }

    @Test
    void ownedTraversalObservesReplacementAndCanContinueAfterItsOwnRemoval() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "first");
        map.put(2, "second");
        synchronized (map) {
            Iterator<Map.Entry<Integer, String>> iterator = map.entrySet().iterator();
            int first = iterator.next().getKey();
            iterator.remove();
            int remaining = first == 1 ? 2 : 1;
            map.replace(remaining, "replacement");
            Map.Entry<Integer, String> next = iterator.next();
            assertEquals(remaining, next.getKey());
            assertEquals("replacement", next.getValue(), "a prefetched value must not hide nonstructural replacement");
            iterator.remove();
            assertFalse(iterator.hasNext());
            assertTrue(map.isEmpty());
        }
    }

    @Test
    void removeAndReinsertInvalidatesAnOwnedCursorEvenWhenSizeAndValueMatch() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "first");
        map.put(2, "second");
        synchronized (map) {
            Iterator<Integer> iterator = map.keySet().iterator();
            int retained = iterator.next();
            int changed = retained == 1 ? 2 : 1;
            String previous = map.get(changed);
            map.compute(changed, (key, value) -> null);
            map.merge(changed, previous, (left, right) -> right);
            assertThrows(ConcurrentModificationException.class, iterator::next);
            assertThrows(ConcurrentModificationException.class, iterator::remove);
            assertTrue(map.containsKey(retained), "a stale remove must not delete its previously returned key");
        }
    }

    @Test
    void insertionDuringExpirationKeepsTheCurrentRegistrationLikeHashMap() {
        EffectMap<Integer, String> map = new EffectMap<>();
        map.put(1, "old");
        map.put(2, "waiting");
        synchronized (map) {
            Iterator<Integer> iterator = map.keySet().iterator();
            int current = iterator.next();
            assertTrue(iterator.hasNext(), "the original traversal has a prefetched successor");
            map.put(3, "successor");
            assertThrows(ConcurrentModificationException.class, iterator::remove,
                    "HashMap.HashIterator.remove throws after a structural change");
            assertTrue(map.containsKey(current), "the current registration stays, as in vanilla");
            assertEquals(3, map.size());
            assertThrows(ConcurrentModificationException.class, iterator::next,
                    "insertion must not admit a successor into this effect turn");
        }
    }

    /**
     * LivingEntity.tickEffects (26.3): iterate the key set, remove an effect whose
     * tickServer returned false, swallow ConcurrentModificationException.
     */
    private static void vanillaTickEffects(Map<String, String> effects, Predicate<String> tickServer) {
        Iterator<String> iterator = effects.keySet().iterator();
        try {
            while (iterator.hasNext()) {
                String effect = iterator.next();
                if (!tickServer.test(effect)) iterator.remove();
            }
        } catch (ConcurrentModificationException ignored) {
        }
    }

    private static Map<String, String> runOwned(Map<String, String> effects, Predicate<String> tickServer) {
        synchronized (effects) {
            vanillaTickEffects(effects, tickServer);
        }
        return Map.copyOf(effects);
    }

    private static Map<String, String> badOmenTurn(Map<String, String> effects, boolean inVillage) {
        // BadOmenMobEffect.applyEffectTick: in a village it adds RAID_OMEN (600) and
        // returns false; LivingEntity.addEffect replaces an existing instance in place.
        return runOwned(effects, effect -> {
            if (!effect.equals("bad_omen") || !inVillage) return true;
            effects.put("raid_omen", "600");
            return false;
        });
    }

    @Test
    void badOmenSurvivesItsRaidOmenConversionLikeVanilla() {
        Map<String, String> vanilla = new HashMap<>(Map.of("bad_omen", "120000"));
        Map<String, String> fabrolia = new EffectMap<>();
        fabrolia.putAll(vanilla);
        Map<String, String> converted = Map.of("bad_omen", "120000", "raid_omen", "600");
        // Conversion tick: RAID_OMEN is newly inserted, so the expiring Bad Omen stays.
        assertEquals(converted, badOmenTurn(vanilla, true));
        assertEquals(converted, badOmenTurn(fabrolia, true));
        // Next tick outside the village: Bad Omen keeps ticking.
        assertEquals(converted, badOmenTurn(vanilla, false));
        assertEquals(converted, badOmenTurn(fabrolia, false));
        // Next conversion while RAID_OMEN exists: in-place update, Bad Omen is consumed.
        assertEquals(Map.of("raid_omen", "600"), badOmenTurn(vanilla, true));
        assertEquals(Map.of("raid_omen", "600"), badOmenTurn(fabrolia, true));
    }

    @Test
    void ownedTraversalMatchesHashMapForOrderIndependentEffectTurns() {
        record Turn(Map<String, String> start, BiPredicate<Map<String, String>, String> tick) {}
        List<Turn> turns = List.of(
                // An expiring effect installs a new one: both throw from remove() and keep it.
                new Turn(Map.of("a", "1"), (m, e) -> {
                    if (e.equals("a")) { m.put("b", "new"); return false; }
                    return true;
                }),
                // An expiring effect removes itself first: both throw from remove().
                new Turn(Map.of("a", "1"), (m, e) -> { m.remove(e); return false; }),
                // An expiring effect replaces another one in place: no structural change.
                new Turn(Map.of("a", "1", "b", "2"), (m, e) -> {
                    if (e.equals("a")) { m.put("b", "updated"); return false; }
                    return true;
                }),
                // A surviving effect removes another one: the turn ends, contents agree.
                new Turn(Map.of("a", "1", "b", "2"), (m, e) -> {
                    if (e.equals("a")) m.remove("b");
                    return true;
                }),
                // An expiring effect clears the map.
                new Turn(Map.of("a", "1"), (m, e) -> { m.clear(); return false; }),
                // Plain expiry of every effect.
                new Turn(Map.of("a", "1", "b", "2", "c", "3"), (m, e) -> false));
        for (Turn turn : turns) {
            Map<String, String> vanilla = new HashMap<>(turn.start());
            Map<String, String> fabrolia = new EffectMap<>();
            fabrolia.putAll(turn.start());
            Map<String, String> expected = runOwned(vanilla, e -> turn.tick().test(vanilla, e));
            assertEquals(expected, runOwned(fabrolia, e -> turn.tick().test(fabrolia, e)), turn.start().toString());
        }
    }

    @Test
    void conditionalViewRemovalDoesNotDeleteAValueReplacedByItsPredicate() {
        EffectMap<String, String> map = new EffectMap<>();
        map.put("effect", "old");
        assertFalse(map.values().removeIf(value -> {
            map.replace("effect", "updated");
            return true;
        }));
        assertEquals(Map.of("effect", "updated"), map);
        assertFalse(map.entrySet().removeIf(entry -> {
            entry.setValue("updated-again");
            return true;
        }));
        assertEquals(Map.of("effect", "updated-again"), map);
    }

    @Test
    void compoundMutationCannotEnterItsCallbackInsideAnotherOwnedTurn() throws Exception {
        EffectMap<String, String> map = new EffectMap<>();
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicBoolean callbackEntered = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            attempted.countDown();
            try {
                map.computeIfAbsent("effect", key -> {
                    callbackEntered.set(true);
                    return "installed";
                });
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "effect-map-computation");
        try {
            synchronized (map) {
                writer.start();
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!callbackEntered.get() && writer.getState() != Thread.State.BLOCKED
                        && writer.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(callbackEntered.get(), "ConcurrentMap defaults must not run the callback before acquiring the mutation monitor");
                assertEquals(Thread.State.BLOCKED, writer.getState());
            }
        } finally {
            writer.join(5000);
        }
        assertFalse(writer.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(callbackEntered.get());
        assertEquals(Map.of("effect", "installed"), map);
    }
}
