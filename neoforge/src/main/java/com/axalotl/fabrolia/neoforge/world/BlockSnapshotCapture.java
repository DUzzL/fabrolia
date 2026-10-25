package com.axalotl.fabrolia.neoforge.world;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.BlockSnapshot;

import java.util.ArrayList;

/**
 * NeoForge's block-snapshot capture state of one level, kept per thread.
 *
 * <p>NeoForge adds three public fields to {@code Level}: {@code captureBlockSnapshots},
 * {@code capturedBlockSnapshots} and {@code restoringBlockSnapshots}.
 * {@code CommonHooks.onPlaceItemIntoWorld} raises the capture flag for one item use;
 * while it is raised, every {@code Level.setBlock} of that level appends a snapshot to
 * an unsynchronized {@code ArrayList} and {@code LevelChunk.setBlockState} skips
 * {@code onPlace} for block-entity blocks. The use's thread then fires a place event
 * for every snapshot and replays or, when cancelled, reverts them. The restoring flag
 * suppresses item and experience drops of the whole level.
 *
 * <p>With Fabrolia, other islands of the same level tick on other threads inside that
 * window, so their writes landed in the user's list, lost {@code onPlace}, and were
 * replayed or reverted by a thread that does not own their chunks. Single-threaded
 * NeoForge only ever captures the writes of the item use itself; a per-thread state
 * keeps exactly that. The {@code neoforge.mixin.world} mixins route every NeoForge
 * access of the three fields here, so the fields on {@code Level} stay unused.
 */
public final class BlockSnapshotCapture {
    public boolean capturing;
    public boolean restoring;
    public final ArrayList<BlockSnapshot> captured = new ArrayList<>();

    /** Implemented on {@code Level} by {@code LevelBlockSnapshotCaptureMixin}. */
    public interface Holder {
        BlockSnapshotCapture fabrolia$blockSnapshotCapture();
    }

    public static BlockSnapshotCapture of(Level level) {
        return ((Holder) level).fabrolia$blockSnapshotCapture();
    }
}
