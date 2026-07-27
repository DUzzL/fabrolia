package com.axalotl.fabrolia.common.regions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only NBT-side classification for the bulk repair's UNLOADED-chunk scan
 * ({@link TickRepair}). Decodes exactly the pieces vanilla's chunk loader
 * decodes — {@code sections[].block_states} through vanilla's own
 * {@link PalettedContainer} codec and {@code block_ticks}/{@code fluid_ticks}
 * through vanilla's {@link SavedTick} codec — and applies the ONLY test that
 * is decidable on saved data: does the scheduled type still match the saved
 * block/fluid at that position?
 *
 * <p>What saved data can NEVER yield: offset or ambiguity classifications.
 * The domain tag is not persisted; on-disk delays are RELATIVE and are
 * re-anchored on load against the tagged domain's clock (the 0b276ca fix), so
 * there is no clock to be wrong about yet. Delay magnitude is not evidence and
 * is never consulted.
 *
 * <p>Fail-safe by construction: any entry or section that does not decode
 * (modded id no longer registered, foreign format, pre-26.2 data) is SKIPPED
 * and counted — never classified, never repaired. Mutation of a flagged chunk
 * happens only after the chunk is brought through the normal load path and
 * re-classified against live state; this class never writes anything.
 */
public final class TickRepairNbt {

    private TickRepairNbt() {}

    /** Vanilla's block-state paletted-container codec (the exact codec
     *  SerializableChunkData decodes sections with). */
    static final Codec<PalettedContainer<BlockState>> BLOCK_STATES_CODEC =
            PalettedContainer.codecRW(BlockState.CODEC,
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                    Blocks.AIR.defaultBlockState());

    static final Codec<SavedTick<Block>> BLOCK_TICK_CODEC =
            SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec());
    static final Codec<SavedTick<Fluid>> FLUID_TICK_CODEC =
            SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec());

    /** One definitely-stale saved entry (type no longer matches saved state). */
    public record NbtFinding(String lane, String type, BlockPos pos, int delay,
                             TickPriority priority, String reason) {

        public String line(String dim, int cx, int cz) {
            return dim + " chunk[" + cx + "," + cz + "] " + lane + "(saved) " + type
                    + " @" + pos.toShortString() + " delay=" + delay + " prio=" + priority
                    + " action=" + TickRepair.ACTION_REMOVE + " reason=" + reason;
        }
    }

    public record NbtChunkResult(int cx, int cz, boolean fullStatus,
                                 int entries, int skippedEntries,
                                 List<NbtFinding> mismatches, List<String> notes) {

        public boolean actionable() {
            return fullStatus && !mismatches.isEmpty();
        }
    }

    /**
     * Classify one chunk's raw saved NBT (the compound the chunk-storage IO
     * path returns). Pure read-only function of the tag.
     */
    public static NbtChunkResult classifyChunkNbt(CompoundTag chunk) {
        int cx = chunk.getIntOr("xPos", 0);
        int cz = chunk.getIntOr("zPos", 0);
        String status = chunk.getStringOr("Status", chunk.getStringOr("status", ""));
        boolean full = status.equals("minecraft:full") || status.equals("full");
        List<NbtFinding> mismatches = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!full) {
            // Below-full chunks are never repaired (mutation would force the
            // chunk through generation); don't classify them either.
            notes.add("saved status '" + status + "' below full — skipped");
            return new NbtChunkResult(cx, cz, false, 0, 0, mismatches, notes);
        }
        SectionIndex sections = SectionIndex.parse(chunk, notes);
        int[] entries = {0};
        int[] skipped = {0};
        classifyLane(chunk.getListOrEmpty("block_ticks"), true, cx, cz, sections,
                mismatches, entries, skipped, notes);
        classifyLane(chunk.getListOrEmpty("fluid_ticks"), false, cx, cz, sections,
                mismatches, entries, skipped, notes);
        return new NbtChunkResult(cx, cz, true, entries[0], skipped[0], mismatches, notes);
    }

    private static void classifyLane(ListTag list, boolean blockLane, int cx, int cz,
                                     SectionIndex sections, List<NbtFinding> mismatches,
                                     int[] entries, int[] skipped, List<String> notes) {
        String lane = blockLane ? "block" : "fluid";
        for (Tag el : list) {
            if (!(el instanceof CompoundTag ct)) {
                skipped[0]++;
                continue;
            }
            entries[0]++;
            // per-element parse: one undecodable (e.g. removed-mod) entry must
            // never poison the rest, and undecodable means SKIP, never remove
            SavedTick<?> tick;
            if (blockLane) {
                DataResult<SavedTick<Block>> res = BLOCK_TICK_CODEC.parse(NbtOps.INSTANCE, ct);
                Optional<SavedTick<Block>> okB = res.result();
                if (okB.isEmpty()) {
                    skipped[0]++;
                    continue;
                }
                tick = okB.get();
            } else {
                DataResult<SavedTick<Fluid>> res = FLUID_TICK_CODEC.parse(NbtOps.INSTANCE, ct);
                Optional<SavedTick<Fluid>> okF = res.result();
                if (okF.isEmpty()) {
                    skipped[0]++;
                    continue;
                }
                tick = okF.get();
            }
            BlockPos pos = tick.pos();
            if ((pos.getX() >> 4) != cx || (pos.getZ() >> 4) != cz) {
                // foreign-chunk entry: vanilla filters these on load — leave
                // to the load path, not our business here
                skipped[0]++;
                continue;
            }
            BlockState state = sections.stateAt(pos);
            if (state == null) {
                skipped[0]++;    // undecodable section — no proof, no action
                continue;
            }
            if (blockLane) {
                if (state.getBlock() != tick.type()) {
                    mismatches.add(new NbtFinding(lane, TickRepair.typeName(true, tick.type()),
                            pos, tick.delay(), tick.priority(),
                            "scheduled block no longer matches saved world (position holds "
                                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ")"));
                }
            } else {
                if (state.getFluidState().getType() != tick.type()) {
                    mismatches.add(new NbtFinding(lane, TickRepair.typeName(false, tick.type()),
                            pos, tick.delay(), tick.priority(),
                            "scheduled fluid no longer matches saved world (position holds "
                                    + BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()) + ")"));
                }
            }
        }
    }

    /** Decoded {@code sections[].block_states} by section Y. Absent section =
     *  all air (exactly vanilla's parse default); undecodable section = null
     *  answers (callers skip). */
    static final class SectionIndex {
        private final Map<Integer, PalettedContainer<BlockState>> byY = new HashMap<>();
        private final Set<Integer> undecodable = new HashSet<>();

        static SectionIndex parse(CompoundTag chunk, List<String> notes) {
            SectionIndex idx = new SectionIndex();
            for (Tag el : chunk.getListOrEmpty("sections")) {
                if (!(el instanceof CompoundTag sec)) continue;
                int y = sec.getByteOr("Y", Byte.MIN_VALUE);
                if (y == Byte.MIN_VALUE && sec.getByte("Y").isEmpty()) continue;
                Optional<CompoundTag> bs = sec.getCompound("block_states");
                if (bs.isEmpty()) {
                    continue;   // section saved without block states = air
                }
                Optional<PalettedContainer<BlockState>> decoded =
                        BLOCK_STATES_CODEC.parse(NbtOps.INSTANCE, bs.get()).result();
                if (decoded.isPresent()) {
                    idx.byY.put(y, decoded.get());
                } else {
                    idx.undecodable.add(y);
                    notes.add("section Y=" + y + " block_states undecodable — its entries skipped");
                }
            }
            return idx;
        }

        /** Saved block state at an absolute position, or null when unknown. */
        BlockState stateAt(BlockPos pos) {
            int sectionY = pos.getY() >> 4;
            if (undecodable.contains(sectionY)) {
                return null;
            }
            PalettedContainer<BlockState> c = byY.get(sectionY);
            if (c == null) {
                return Blocks.AIR.defaultBlockState();
            }
            return c.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }
}
