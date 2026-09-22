package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.utils.FabroliaPoiSectionReadAccess;
import com.axalotl.fabrolia.common.utils.GroupedSnapshot;
import com.axalotl.fabrolia.common.utils.PoiSectionReadSnapshot;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.lithium.common.util.Distances;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Makes every Lithium POI reader consume an immutable per-section membership
 * snapshot instead of iterating the live vanilla HashMap/HashSet indexes.
 *
 * <p>Lithium merges eager readers and one escaping iterator directly onto
 * {@link PoiSection}; all of them bypass vanilla {@code getRecords}. The
 * view is published by Fabrolia's mutation wrappers before the exact vanilla
 * dirty notification. Queries only read that volatile view: no section monitor
 * is acquired and all type/record predicates and consumers run lock-free.
 */
@Mixin(value = PoiSection.class, priority = 2000)
public abstract class FabroliaLithiumPoiSectionSnapshotMixin {

    @Unique
    private PoiSectionReadSnapshot fabrolia$lithiumPoiSnapshot() {
        return ((FabroliaPoiSectionReadAccess) (Object) this)
                .fabrolia$poiReadSnapshot();
    }

    @WrapMethod(method = "lithium$collectMatchingPoints", remap = false)
    private void fabrolia$snapshotLithiumMatchingPoints(
            Predicate<Holder<PoiType>> typePredicate,
            PoiManager.Occupancy occupancy,
            Consumer<PoiRecord> consumer,
            Operation<Void> original) {
        this.fabrolia$lithiumPoiSnapshot()
                .groupedRecords().stream(typePredicate, occupancy.getTest())
                .forEach(consumer);
    }

    @WrapMethod(
            method = "lithium$getL2ClosestMatchingPoint"
                    + "(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;"
                    + "Ljava/util/function/Predicate;)"
                    + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;",
            remap = false)
    private PoiRecord fabrolia$snapshotLithiumClosestPoint(
            BlockPos center,
            Predicate<Holder<PoiType>> typePredicate,
            Predicate<? super PoiRecord> recordPredicate,
            Operation<PoiRecord> original) {
        PoiRecord closest = null;
        long closestDistance = Long.MAX_VALUE;
        for (GroupedSnapshot.Group<Holder<PoiType>, PoiRecord> group
                : this.fabrolia$lithiumPoiSnapshot().groupedRecords().groups()) {
            if (!typePredicate.test(group.key())) {
                continue;
            }
            for (PoiRecord record : group.values()) {
                long distance = Distances.distanceSq(center, record.getPos());
                if (distance < closestDistance && recordPredicate.test(record)) {
                    closestDistance = distance;
                    closest = record;
                }
            }
        }
        return closest;
    }

    @WrapMethod(
            method = "lithium$collectMatchingPointsL2Limited"
                    + "(Lnet/minecraft/core/BlockPos;JLjava/util/function/Predicate;"
                    + "Ljava/util/function/Predicate;Ljava/util/function/Consumer;I)V",
            remap = false)
    private void fabrolia$snapshotLithiumLimitedPoints(
            BlockPos center,
            long maxDistanceSquared,
            Predicate<Holder<PoiType>> typePredicate,
            Predicate<? super PoiRecord> recordPredicate,
            Consumer<PoiRecord> consumer,
            int maxCount,
            Operation<Void> original) {
        int remaining = maxCount;
        for (GroupedSnapshot.Group<Holder<PoiType>, PoiRecord> group
                : this.fabrolia$lithiumPoiSnapshot().groupedRecords().groups()) {
            if (!typePredicate.test(group.key())) {
                continue;
            }
            for (PoiRecord record : group.values()) {
                long distance = Distances.distanceSq(center, record.getPos());
                if (distance <= maxDistanceSquared && recordPredicate.test(record)) {
                    consumer.accept(record);
                    remaining--;
                    if (remaining == 0) {
                        return;
                    }
                }
            }
        }
    }

    @WrapMethod(method = "lithium$getFirstMatchingPoint", remap = false)
    private PoiRecord fabrolia$snapshotLithiumFirstPoint(
            BlockPos center,
            long maxDistanceSquared,
            Predicate<Holder<PoiType>> typePredicate,
            Predicate<BlockPos> positionPredicate,
            PoiManager.Occupancy occupancy,
            Operation<PoiRecord> original) {
        Predicate<? super PoiRecord> occupancyPredicate = occupancy.getTest();
        for (GroupedSnapshot.Group<Holder<PoiType>, PoiRecord> group
                : this.fabrolia$lithiumPoiSnapshot().groupedRecords().groups()) {
            if (!typePredicate.test(group.key())) {
                continue;
            }
            for (PoiRecord record : group.values()) {
                if (Distances.distanceSq(center, record.getPos()) <= maxDistanceSquared
                        && positionPredicate.test(record.getPos())
                        && occupancyPredicate.test(record)) {
                    return record;
                }
            }
        }
        return null;
    }

    @WrapMethod(method = "lithium$getAt", remap = false)
    private PoiRecord fabrolia$snapshotLithiumRecordLookup(
            BlockPos pos, Operation<PoiRecord> original) {
        return this.fabrolia$lithiumPoiSnapshot().recordAt(pos);
    }

    @WrapMethod(method = "lithium$iterate", remap = false)
    private Iterator<PoiRecord> fabrolia$snapshotLithiumIterator(
            Predicate<Holder<PoiType>> typePredicate,
            Operation<Iterator<PoiRecord>> original) {
        return this.fabrolia$lithiumPoiSnapshot()
                .groupedRecords().stream(typePredicate, record -> true)
                .iterator();
    }
}
