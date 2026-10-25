import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Mini profiler report: per thread group self/inclusive CPU samples, allocation, parks. */
public class JfrAnalyze {
    static String group(RecordedThread t) {
        if (t == null) return "?";
        String n = t.getJavaName() != null ? t.getJavaName() : t.getOSName();
        if (n == null) return "?";
        return n.replaceAll("[-#\\s]*\\d+$", "").replaceAll("\\d+$", "");
    }

    static String frame(RecordedFrame f) {
        RecordedMethod m = f.getMethod();
        return m.getType().getName() + "." + m.getName();
    }

    static <K> void top(String title, Map<K, Long> m, long total, int n) {
        System.out.println("  -- " + title);
        m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(n)
                .forEach(e -> System.out.printf("  %7d %5.1f%%  %s%n", e.getValue(), 100.0 * e.getValue() / Math.max(1, total), e.getKey()));
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        Set<String> focus = new HashSet<>(Arrays.asList(args.length > 1 ? args[1].split(",") : new String[]{"Server thread", "Fabrolia-Region-Worker"}));
        Map<String, Long> samples = new HashMap<>();
        Map<String, Map<String, Long>> self = new HashMap<>();
        Map<String, Map<String, Long>> incl = new HashMap<>();
        Map<String, Map<String, Long>> allocClass = new HashMap<>();
        Map<String, Map<String, Long>> allocSite = new HashMap<>();
        Map<String, Long> allocTotal = new HashMap<>();
        Map<String, Map<String, Long>> parkSite = new HashMap<>();
        Map<String, Long> parkTotal = new HashMap<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                RecordedThread th = e.hasField("sampledThread") ? e.getThread("sampledThread") : e.getThread();
                String g = group(th);
                RecordedStackTrace st = e.getStackTrace();
                switch (type) {
                    case "jdk.ExecutionSample" -> {
                        samples.merge(g, 1L, Long::sum);
                        if (st == null || st.getFrames().isEmpty()) break;
                        List<RecordedFrame> fr = st.getFrames();
                        self.computeIfAbsent(g, k -> new HashMap<>()).merge(frame(fr.get(0)), 1L, Long::sum);
                        Set<String> seen = new HashSet<>();
                        for (RecordedFrame f : fr) {
                            String s = frame(f);
                            if (seen.add(s)) incl.computeIfAbsent(g, k -> new HashMap<>()).merge(s, 1L, Long::sum);
                        }
                    }
                    case "jdk.ObjectAllocationSample" -> {
                        long w = e.getLong("weight");
                        allocTotal.merge(g, w, Long::sum);
                        RecordedClass c = e.getClass("objectClass");
                        allocClass.computeIfAbsent(g, k -> new HashMap<>()).merge(c == null ? "?" : c.getName(), w, Long::sum);
                        if (st != null && !st.getFrames().isEmpty()) {
                            // first non-JDK frame = allocation site
                            String site = null;
                            for (RecordedFrame f : st.getFrames()) {
                                String s = frame(f);
                                if (!(s.startsWith("java.") || s.startsWith("jdk.") || s.startsWith("sun."))) { site = s; break; }
                            }
                            allocSite.computeIfAbsent(g, k -> new HashMap<>()).merge(site == null ? frame(st.getFrames().get(0)) : site, w, Long::sum);
                        }
                    }
                    case "jdk.ThreadPark" -> {
                        long d = e.getDuration().toNanos() / 1000;
                        parkTotal.merge(g, d, Long::sum);
                        if (st != null) {
                            String site = null;
                            for (RecordedFrame f : st.getFrames()) {
                                String s = frame(f);
                                if (!(s.startsWith("java.") || s.startsWith("jdk.") || s.startsWith("sun."))) { site = s; break; }
                            }
                            parkSite.computeIfAbsent(g, k -> new HashMap<>()).merge(site == null ? "?" : site, d, Long::sum);
                        }
                    }
                    default -> {}
                }
            }
        }
        System.out.println("== CPU samples by thread group");
        top("samples", samples, samples.values().stream().mapToLong(Long::longValue).sum(), 25);
        for (String g : focus) {
            long tot = samples.getOrDefault(g, 0L);
            System.out.println("\n== " + g + " (" + tot + " samples)");
            top("self", self.getOrDefault(g, Map.of()), tot, 40);
            Map<String, Long> inc = new HashMap<>(incl.getOrDefault(g, Map.of()));
            top("inclusive (fabrolia frames)", filter(inc, k -> k.contains("axalotl") || k.contains("fabrolia$")), tot, 60);
            top("inclusive (all)", inc, tot, 50);
            long at = allocTotal.getOrDefault(g, 0L);
            System.out.printf("  allocation sampled weight: %.1f MB%n", at / 1e6);
            top("alloc by class (bytes)", allocClass.getOrDefault(g, Map.of()), at, 20);
            top("alloc by site (bytes)", allocSite.getOrDefault(g, Map.of()), at, 30);
            long pt = parkTotal.getOrDefault(g, 0L);
            System.out.printf("  park total: %.1f s%n", pt / 1e6);
            top("park by site (us)", parkSite.getOrDefault(g, Map.of()), pt, 20);
        }
        System.out.println("\n== allocation by thread group (bytes)");
        top("alloc", allocTotal, allocTotal.values().stream().mapToLong(Long::longValue).sum(), 15);
    }

    static Map<String, Long> filter(Map<String, Long> m, Predicate<String> p) {
        Map<String, Long> r = new HashMap<>();
        m.forEach((k, v) -> { if (p.test(k)) r.put(k, v); });
        return r;
    }
}
