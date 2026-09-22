package com.axalotl.fabrolia.common.utils;

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable structural view of a {@code PoiSection}.
 *
 * <p>The live vanilla indexes are copied together while holding the section's
 * mutation monitor. The resulting object is then published through one
 * volatile field and is never mutated again. Readers therefore never touch a
 * live {@code HashMap}, {@code HashSet}, or {@code Short2ObjectOpenHashMap},
 * never take the section monitor, and never run caller predicates while a
 * writer lock is held.
 *
 * <p>{@link PoiRecord} instances themselves are deliberately not cloned. Their
 * ticket count is mutable state with its own stripe discipline; every vanilla
 * POI view also exposes those exact record objects. This snapshot freezes only
 * section membership, lookup, encounter order, and validity.
 */
public final class PoiSectionReadSnapshot {

    private final GroupedSnapshot<Holder<PoiType>, PoiRecord> groupedRecords;
    private final List<PoiRecord> records;
    private final Short2ObjectMap<PoiRecord> recordsByPosition;
    private final boolean valid;

    private PoiSectionReadSnapshot(
            GroupedSnapshot<Holder<PoiType>, PoiRecord> groupedRecords,
            List<PoiRecord> records,
            Short2ObjectMap<PoiRecord> recordsByPosition,
            boolean valid) {
        this.groupedRecords = Objects.requireNonNull(groupedRecords, "groupedRecords");
        this.records = List.copyOf(records);
        this.recordsByPosition = recordsByPosition;
        this.valid = valid;
    }

    /**
     * Captures all structural reader surfaces at one section-monitor instant.
     */
    public static PoiSectionReadSnapshot capture(
            Object monitor,
            Map<Holder<PoiType>, ? extends Set<PoiRecord>> byType,
            Short2ObjectMap<PoiRecord> records,
            boolean valid) {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(byType, "byType");
        Objects.requireNonNull(records, "records");
        synchronized (monitor) {
            GroupedSnapshot<Holder<PoiType>, PoiRecord> groupedRecords =
                    GroupedSnapshot.capture(monitor, byType);
            // pack() uses records.values(), so preserve that exact encounter
            // order instead of flattening the independently ordered byType map.
            ArrayList<PoiRecord> orderedRecords =
                    new ArrayList<>(records.values());
            Short2ObjectOpenHashMap<PoiRecord> recordsByPosition =
                    new Short2ObjectOpenHashMap<>(records);
            return new PoiSectionReadSnapshot(
                    groupedRecords, orderedRecords, recordsByPosition, valid);
        }
    }

    public GroupedSnapshot<Holder<PoiType>, PoiRecord> groupedRecords() {
        return this.groupedRecords;
    }

    public List<PoiRecord> records() {
        return this.records;
    }

    public PoiRecord recordAt(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        return this.recordsByPosition.get(SectionPos.sectionRelativePos(pos));
    }

    public boolean isValid() {
        return this.valid;
    }
}
