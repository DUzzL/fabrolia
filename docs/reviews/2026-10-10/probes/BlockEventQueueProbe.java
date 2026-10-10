import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.List;

/** Queue-only executable witness: MC 26.3 removeFirst loop versus the current Fabrolia drains.
 * Strings stand in for equal BlockEventData records; event A posts B during execution.
 * This does not claim a complete running world-eater reproduction.
 */
public class BlockEventQueueProbe {
    public static void main(String[] args) {
        List<String> vanilla = vanilla(false);
        List<String> island = currentIsland(false);
        List<String> main = currentMain(false);
        System.out.println("26.3 removeFirst, A requeues pending B: " + vanilla);
        System.out.println("Fabrolia island waves, same input:      " + island);
        System.out.println("Fabrolia main waves, same input:        " + main);
        if (!vanilla.equals(List.of("A", "B"))) throw new AssertionError(vanilla);
        if (!island.equals(List.of("A", "B", "B"))) throw new AssertionError(island);
        if (!main.equals(List.of("A", "B", "B"))) throw new AssertionError(main);
        System.out.println("26.3 removeFirst, A clears pending B:   " + vanilla(true));
        System.out.println("Fabrolia island, A clears shared B:     " + currentIsland(true));
        System.out.println("Fabrolia main waves, A clears B:        " + currentMain(true));
        System.out.println("PASS: deterministic queued-event duplicate and cancellation divergence");
    }
    static ObjectLinkedOpenHashSet<String> pending() {
        ObjectLinkedOpenHashSet<String> queue = new ObjectLinkedOpenHashSet<>();
        queue.add("A"); queue.add("B"); return queue;
    }
    static List<String> vanilla(boolean clear) {
        ObjectLinkedOpenHashSet<String> queue = pending();
        List<String> ran = new ArrayList<>();
        while (!queue.isEmpty()) {
            String event = queue.removeFirst();
            ran.add(event);
            if (event.equals("A")) {
                if (clear) queue.removeIf("B"::equals); else queue.add("B");
            }
        }
        return ran;
    }
    static List<String> currentIsland(boolean clear) {
        ObjectLinkedOpenHashSet<String> local = pending();
        ObjectLinkedOpenHashSet<String> shared = new ObjectLinkedOpenHashSet<>();
        List<String> ran = new ArrayList<>();
        while (!local.isEmpty()) {
            List<String> mine = new ArrayList<>(local);
            local.clear(); // IslandBlockEvents.drainUnitLocalEntered
            for (String event : mine) {
                ran.add(event);
                if (event.equals("A")) {
                    if (clear) shared.removeIf("B"::equals); else local.add("B");
                }
            }
        }
        return ran;
    }
    static List<String> currentMain(boolean clear) {
        ObjectLinkedOpenHashSet<String> shared = pending();
        List<String> ran = new ArrayList<>();
        while (!shared.isEmpty()) {
            List<String> wave = new ArrayList<>(shared);
            shared.clear(); // IslandBlockEvents.runPartitionedEntered
            for (String event : wave) {
                ran.add(event);
                if (event.equals("A")) {
                    if (clear) shared.removeIf("B"::equals); else shared.add("B");
                }
            }
        }
        return ran;
    }
}
