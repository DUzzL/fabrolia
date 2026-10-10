import com.axalotl.fabrolia.common.regions.EndPlatformHandoff;
import java.util.List;

public class HandoffFlushProbe {
    static final long PLATFORM = 1;
    static class Sand { boolean settled; }
    record Op(long tick, boolean regen, Sand sand) {}

    static EndPlatformHandoff.Ops view(List<Op> ops) {
        return new EndPlatformHandoff.Ops() {
            public int size() { return ops.size(); }
            public long sourceDomain(int i) { return 1; }
            public long sourceTick(int i) { return ops.get(i).tick(); }
            public long chunkKey(int i) { return PLATFORM; }
            public boolean isRegen(int i) { return ops.get(i).regen(); }
            public Object landingHandle(int i) { return ops.get(i).sand(); }
        };
    }

    public static void main(String[] args) {
        Sand first = new Sand();
        Sand next = new Sand();
        var tracker = new EndPlatformHandoff.Tracker(h -> ((Sand) h).settled);
        var firstBatch = view(List.of(new Op(100, true, null), new Op(100, false, first)));
        var nextBatch = view(List.of(new Op(107, true, null), new Op(107, false, next)));
        boolean[] firstRelease = EndPlatformHandoff.selectReleasable(firstBatch, tracker);
        if (!firstRelease[0] || !firstRelease[1]) throw new AssertionError("first batch refused");
        boolean[] nextRelease = EndPlatformHandoff.selectReleasable(nextBatch, tracker);
        if (nextRelease[0] || nextRelease[1]) throw new AssertionError("normal gate failed");
        System.out.println("Normal publication: next batch correctly held while first sand is airborne.");
        // publishLevelPending invokes trackAll, then publishes every queued op.
        EndPlatformHandoff.trackAll(nextBatch, tracker);
        if (first.settled) throw new AssertionError("unexpected landing");
        System.out.println("Save-flush registration: first sand remains airborne; no gate decision blocks next regeneration.");
        System.out.println("This checks the real helper and the save call sequence, not an in-game farm.");
    }
}
