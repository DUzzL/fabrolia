package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.utils.PoiStripes;
import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.lithium.common.world.interests.PointOfInterestSetExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Task #38, review D6 — "one bed, one villager" on the path villagers actually
 * take. Lithium's {@code AcquirePoiMixin} {@code @Redirect}s
 * {@code PoiManager.take} → {@code lithium$takeAt}, which (a) called
 * {@code acquireTicket()} with no lock at all, bypassing the take/release
 * stripes entirely, (b) DISCARDED {@code acquireTicket()}'s boolean —
 * {@code acquireTicket} returns {@code false} on a full record without
 * mutating, so two racing takers both got {@code Optional.of(pos)} and two
 * villagers claimed one bed — and (c) ignored the {@code biPredicate} vanilla
 * {@code take} applies before acquiring.
 *
 * <p>Per the review, the cleanest fix is a tiny reimplementation of the
 * 11-line method rather than a call-site wrap: honouring the boolean requires
 * changing control flow ({@code false} → {@code Optional.empty()}), which no
 * {@code @WrapOperation} on the ignored call can do. The acquire goes through
 * {@link PoiStripes#takeTicketAtomically} — the same resident column lease and
 * stripe ({@code stripe(record.pos)}) that {@code release}/{@code add}/{@code remove}
 * hold, validating that the selected record is still resident before acquiring.
 *
 * <p>The biPredicate call matches vanilla {@code take} exactly; in the only
 * vanilla call site (AcquirePoi) it is a pure position-equality lambda, so
 * this adds correctness for any other caller at zero behavioural risk.
 *
 * <p>Liveness (verified in the review): {@code takeAt} reaches only
 * {@code getOrLoad} → loadLock/storage locks — no nested stripe entry, so the
 * single-stripe invariant holds; {@code lithium$getAt} is a records read.
 *
 * <p>Extends {@code SectionStorage} for {@code getOrLoad} access — the dummy
 * constructor is never invoked (Mixin does not merge mixin constructors), it
 * only exists so javac accepts the supertype; {@code @Shadow} cannot reach
 * {@code getOrLoad} because it is declared on the superclass and shadow
 * resolution stops at the target class. Cancelled when Lithium is absent
 * (references a Lithium interface and a Lithium-merged method).
 */
@Mixin(value = PoiManager.class, priority = 2000)
public abstract class FabroliaLithiumPoiTakeAtMixin extends SectionStorage<PoiSection, PoiSection.Packed> {

    private FabroliaLithiumPoiTakeAtMixin() {
        super(null, null, null, null, null, null, null, null);
    }

    @WrapMethod(method = "lithium$takeAt", remap = false)
    private Optional<BlockPos> fabrolia$atomicTakeAt(Predicate<Holder<PoiType>> typeFilter,
                                                     BiPredicate<Holder<PoiType>, BlockPos> biPredicate,
                                                     BlockPos blockPos,
                                                     Operation<Optional<BlockPos>> original) {
        Optional<PoiSection> poiSection = this.getOrLoad(SectionPos.asLong(blockPos));
        if (poiSection.isEmpty()) {
            return Optional.empty();
        }
        PoiRecord record = ((PointOfInterestSetExtended) poiSection.get()).lithium$getAt(blockPos);
        if (record == null
                || !typeFilter.test(record.getPoiType())
                || !biPredicate.test(record.getPoiType(), record.getPos())) {
            return Optional.empty();
        }
        return PoiStripes.takeTicketAtomically((FabroliaPoiLoadGate) this, record);
    }
}
