package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.utils.FabroliaPoiTracker;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.SectionTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Task #38 — break the POI deadlock triangle by deferring every notification
 * INTO the village-distance graph.
 *
 * <p>Three production watchdog crashes on 2026-07-29 (07:21:32, 10:08:03,
 * 10:26:57) were one AB-BA: a worker in {@code sectionsToVillage} holds this
 * tracker's monitor (blanket-synchronised via {@code DynamicGraphMinFixedPoint})
 * inside {@code runUpdates → checkEdge → getLevelFromSource → isVillageCenter}
 * and blocks on a {@code PoiSection}; another worker holds that
 * {@code PoiSection} inside {@code release → setDirty.run() →
 * PoiManager.setDirty} and blocks on this tracker.
 *
 * <p>That was only the cycle that happened to fire. The blanket monitors added by
 * 7d58bbb built a COMPLETE bidirectional triangle {@code DistanceTracker ⇄
 * SectionStorage ⇄ PoiSection} — four elementary cycles, of which the dumps show
 * one. That commit's audit ("both classes are leaves, no path back into each
 * other in reverse order") was wrong in both directions: {@code PoiSection}'s
 * setDirty runnable calls back UP into {@code SectionStorage.setDirty} and
 * {@code DistanceTracker.update}, and {@code getLevelFromSource} calls back DOWN
 * into both.
 *
 * <p><b>The fix is structural, not another monitor.</b> Deferring the two
 * {@code SectionTracker.update} call sites deletes every {@code * → tracker}
 * edge. What remains is {@code tracker → storage} (via {@code isVillageCenter →
 * SectionStorage.get}) and {@code section → storage} (via {@code setDirty →
 * super.setDirty}); storage becomes a SINK with no outgoing edge, so the graph is
 * acyclic by construction. The deferral is also what let {@code PoiSection} leave
 * the blanket-sync set: with no {@code section → tracker} edge, the section
 * monitor cannot sit in a cycle.
 *
 * <p><b>Why the drain must be atomic under this monitor.</b> Recomputing
 * {@code getLevelFromSource} outside it would be a lost-update race that corrupts
 * the graph PERMANENTLY, not by one tick. {@code update(pos, level, false)} →
 * {@code checkEdge(SOURCE, …)} → {@code SectionTracker.getComputedLevel} remaps
 * the self-neighbour to {@code Long.MAX_VALUE}, which EQUALS {@code knownParent}
 * and is therefore skipped — the passed level is authoritative and
 * {@code getLevelFromSource} is never re-consulted for that node. So a drainer
 * preempted between its truth-read and its {@code update} overwrites a newer
 * converged value with a stale one, and because no pending entry remains, the
 * village stays erased (or a phantom one stays planted) until some unrelated
 * mutation happens to touch that section. Recompute and update therefore happen
 * inside one {@code synchronized (this)}. A separate pending-set monitor makes
 * snapshot-and-clear atomic against enqueue, and is released before any source
 * read. Producers take only that leaf monitor, never the graph monitor: a
 * mutation after detachment re-queues for the next drain without restoring the
 * section-to-graph lock edge.
 */
@Mixin(targets = "net.minecraft.world.entity.ai.village.poi.PoiManager$DistanceTracker")
public abstract class PoiDistanceTrackerMixin extends SectionTracker implements FabroliaPoiTracker {

    /**
     * Never invoked by Minecraft — Mixin does not merge mixin constructors. It exists so
     * javac accepts extending {@link SectionTracker}, which is what makes
     * {@code update} and {@code getLevelFromSource} reachable. {@code @Shadow}
     * cannot be used for them: both are declared on the SUPERCLASS, and Mixin
     * resolves shadow methods against the target class only ("was not located in
     * the target class", verified at load time).
     */
    protected PoiDistanceTrackerMixin(int levelCount, int expectedUpdates, int expectedLevels) {
        super(levelCount, expectedUpdates, expectedLevels);
    }

    /**
     * Sections whose village-centre status may have changed since the last drain.
     *
     * <p>Set semantics dedupe repeated mutations of one section for free. The
     * level is deliberately NOT stored: it is recomputed from current truth at
     * drain time, which both collapses N mutations into one recompute and avoids
     * carrying a value that was already stale when it was captured.
     *
     * <p>Linked, so drain order matches the order sections were dirtied — the
     * order vanilla would have called {@code update} in.
     */
    @Unique
    private final LongLinkedOpenHashSet fabrolia$pending = new LongLinkedOpenHashSet();

    @Override
    public void fabrolia$enqueueTrackerUpdate(long sectionPos) {
        synchronized (this.fabrolia$pending) {
            this.fabrolia$pending.add(sectionPos);
        }
    }

    @Override
    public void fabrolia$drainTrackerUpdates() {
        synchronized (this) {
            final long[] batch;
            synchronized (this.fabrolia$pending) {
                if (this.fabrolia$pending.isEmpty()) {
                    return;
                }
                batch = this.fabrolia$pending.toLongArray();
                this.fabrolia$pending.clear();
            }
            // No pending-set lock may survive into a source read: a POI section
            // can enqueue while holding its own monitor.
            for (long sectionPos : batch) {
                this.update(sectionPos, this.getLevelFromSource(sectionPos), false);
            }
        }
    }
}
