package com.axalotl.fabrolia.common.utils;

import com.axalotl.fabrolia.api.utils.ConcurrentList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Iterators are snapshots, and every kind of mutation is visible to the next iterator. */
final class ConcurrentListIterationTest {

    private record Mutation(Consumer<List<String>> apply) {}

    @Test
    void everyMutationIsVisibleToTheNextIterationAndNotToAnOpenOne() {
        List<Mutation> mutations = List.of(
                new Mutation(l -> l.add("a")),
                new Mutation(l -> l.add(0, "b")),
                new Mutation(l -> l.addAll(List.of("c", "d"))),
                new Mutation(l -> l.addAll(1, List.of("e"))),
                new Mutation(l -> l.set(0, "f")),
                new Mutation(l -> l.remove("c")),
                new Mutation(l -> l.remove(0)),
                new Mutation(l -> l.removeIf("d"::equals)),
                new Mutation(l -> l.subList(0, 1).clear()),
                new Mutation(List::clear),
                new Mutation(l -> l.add("g")));
        ConcurrentList<String> list = new ConcurrentList<>();
        List<String> reference = new ArrayList<>();
        for (int i = 0; i < mutations.size(); i++) {
            List<String> before = iterate(list);
            Iterator<String> open = list.iterator();
            mutations.get(i).apply().accept(list);
            mutations.get(i).apply().accept(reference);
            assertEquals(reference, iterate(list), "iteration after mutation #" + i);
            List<String> fromOpen = new ArrayList<>();
            open.forEachRemaining(fromOpen::add);
            assertEquals(before, fromOpen, "an iterator opened before mutation #" + i + " keeps its snapshot");
        }
    }

    private static List<String> iterate(ConcurrentList<String> list) {
        List<String> out = new ArrayList<>();
        for (String s : list) out.add(s);
        return out;
    }
}
