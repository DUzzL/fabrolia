package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Class-group members must iterate exactly like vanilla's allInstances ArrayList. */
final class OrderedGroupSnapshotTest {

    @Test
    void addAndRemoveMirrorVanillaListOrder() {
        Object a = new Object(), b = new Object(), c = new Object(), d = new Object();
        List<Object> vanilla = new ArrayList<>();
        OrderedGroupSnapshot<Object> group = OrderedGroupSnapshot.empty();
        Object[][] script = {{"+", a}, {"+", b}, {"+", c}, {"+", a}, {"-", a}, {"+", d}, {"-", c}, {"-", d},
                {"-", new Object()}, {"+", c}};
        for (Object[] step : script) {
            if (step[0].equals("+")) {
                vanilla.add(step[1]);
                group = group.with(step[1]);
            } else {
                vanilla.remove(step[1]);
                group = group.without(step[1]);
            }
            assertEquals(vanilla, new ArrayList<>(group));
        }
    }

    @Test
    void readerKeepsItsSnapshotWhileWritersPublishNewOnes() {
        Object a = new Object(), b = new Object();
        OrderedGroupSnapshot<Object> published = OrderedGroupSnapshot.<Object>empty().with(a);
        List<Object> seen = new ArrayList<>();
        for (Object member : published) {
            OrderedGroupSnapshot<Object> next = published.with(b).without(a);
            seen.add(member);
            assertEquals(List.of(b), next);
        }
        assertEquals(List.of(a), seen);
    }
}
