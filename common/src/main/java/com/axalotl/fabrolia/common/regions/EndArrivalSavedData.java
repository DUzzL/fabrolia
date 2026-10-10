package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snapshot of gate-held End arrivals and their airborne predecessors. Saved
 * under destination ownership BEFORE vanilla collects its SavedData. Queued
 * entities are not inserted just to make them saveable: their typed operations
 * and NBT survive alongside the normal world snapshot instead.
 *
 * Main owns this storage/lifecycle adapter. The handoff tracker itself remains
 * synchronized and usable by island workers. A scheduler stop hands pending
 * work to a Main fallback lane, so disabling/restarting the scheduler neither
 * loses it nor bypasses its landing gate.
 */
public final class EndArrivalSavedData extends SavedData {
    static final SavedDataType<EndArrivalSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("fabrolia", "end_arrivals"), EndArrivalSavedData::new,
            CompoundTag.CODEC.xmap(EndArrivalSavedData::new, data -> data.snapshot), null);
    private static final AtomicLong RECOVERY_DOMAINS = new AtomicLong(-1);

    private CompoundTag snapshot;
    private boolean consumed;
    private Fallback fallback;

    public EndArrivalSavedData() { this(emptySnapshot()); }
    EndArrivalSavedData(CompoundTag snapshot) { this.snapshot = snapshot.copy(); }

    static CompoundTag emptySnapshot() {
        CompoundTag result = new CompoundTag();
        result.putInt("schema", 1);
        result.put("pending", new ListTag());
        result.put("airborne", new ListTag());
        return result;
    }

    private static final class Fallback {
        final ArrayList<CrossDimArrivals.StagedOp> pending;
        final EndPlatformHandoff.Tracker tracker;
        final long domain = RECOVERY_DOMAINS.getAndDecrement();
        Fallback(ArrayList<CrossDimArrivals.StagedOp> pending, EndPlatformHandoff.Tracker tracker) {
            this.pending = pending;
            this.tracker = tracker;
        }
    }

    /** A loaded world entity must be resolved before absence can mean settled. */
    private static final class RestoredLanding {
        final UUID uuid;
        final long entityChunk;
        volatile Entity resolved;
        RestoredLanding(UUID uuid, long entityChunk) {
            this.uuid = uuid;
            this.entityChunk = entityChunk;
        }
    }

    @SuppressWarnings("unchecked")
    private static PersistentEntitySectionManager<Entity> manager(ServerLevel level) {
        return (PersistentEntitySectionManager<Entity>)
                ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                        .fabrolia$entityManager();
    }

    private static Entity lookup(ServerLevel level, UUID uuid) {
        return manager(level).getEntityGetter().get(uuid);
    }

    static boolean settled(ServerLevel level, Object handle) {
        if (handle instanceof Entity entity) return entity.isRemoved();
        RestoredLanding restored = (RestoredLanding) handle;
        Entity entity = restored.resolved;
        if (entity != null) return entity.isRemoved();
        // Entity loading is asynchronous. A missing lookup before LOADED is
        // not a landing; otherwise a restart would immediately open the gate.
        if (!level.areEntitiesLoaded(restored.entityChunk)) return false;
        entity = lookup(level, restored.uuid);
        // The getter exposes accessible entities only. A loaded UUID can still
        // be hidden while its ticket/status transition is pending: keep waiting.
        if (entity == null) return !manager(level).isLoaded(restored.uuid);
        restored.resolved = entity;
        return entity.isRemoved();
    }

    public static void restoreInto(RegionizedServer rs) {
        if (rs.endArrivalsRestored) return;
        EndArrivalSavedData data = rs.level.getDataStorage().get(TYPE);
        rs.endArrivalsRestored = true;
        if (data == null) return;
        Fallback state = data.takeState(rs.level);
        if (state == null) return;
        for (var landing : state.tracker.snapshot()) {
            rs.platformHandoff.track(landing.chunkKey(), landing.handle());
        }
        CrossDimArrivals.appendGlobalLane(rs.globalArrivals, rs.globalArrivalsLock, state.pending);
        if (!state.pending.isEmpty()) org.slf4j.LoggerFactory.getLogger("Fabrolia-XDim").info(
                "Restored {} gate-held End arrival operations for {}", state.pending.size(),
                rs.level.dimension().identifier());
    }

    private Fallback takeState(ServerLevel level) {
        if (fallback != null) {
            Fallback result = fallback;
            fallback = null;
            return result;
        }
        if (consumed) return null;
        consumed = true;
        return decode(level, snapshot);
    }

    /** Called while this level is quiescent, before its SavedData save body. */
    public static void capture(RegionizedServer rs) {
        store(rs.level, CrossDimArrivals.snapshotPending(rs), rs.platformHandoff);
    }

    public static void beforeLevelSave(ServerLevel level) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs != null) {
            restoreInto(rs);
            CrossDimArrivals.flushLevel(rs);
            capture(rs);
        } else {
            EndArrivalSavedData data = level.getDataStorage().get(TYPE);
            if (data == null) return;
            data.ensureFallback(level);
            if (data.fallback != null) {
                store(level, data.fallback.pending, data.fallback.tracker);
            } else {
                data.snapshot = emptySnapshot();
                data.setDirty();
            }
        }
    }

    private static void store(ServerLevel level, List<CrossDimArrivals.StagedOp> pending,
                              EndPlatformHandoff.Tracker tracker) {
        List<EndPlatformHandoff.Landing> airborne = tracker.snapshot();
        EndArrivalSavedData data = level.getDataStorage().get(TYPE);
        if (data == null && pending.isEmpty() && airborne.isEmpty()) return;
        if (data == null) data = level.getDataStorage().computeIfAbsent(TYPE);
        data.snapshot = encode(pending, airborne);
        data.consumed = true;
        data.setDirty();
    }

    /** The stopped runtime is unreachable afterwards; keep its live queue on Main. */
    public static void detachToFallback(RegionizedServer rs) {
        ArrayList<CrossDimArrivals.StagedOp> pending = CrossDimArrivals.snapshotPending(rs);
        if (pending.isEmpty() && rs.platformHandoff.snapshot().isEmpty()) return;
        store(rs.level, pending, rs.platformHandoff);
        EndArrivalSavedData data = rs.level.getDataStorage().get(TYPE);
        data.fallback = new Fallback(pending, rs.platformHandoff);
        if (!pending.isEmpty()) org.slf4j.LoggerFactory.getLogger("Fabrolia-XDim").info(
                "Retained {} gate-held End arrival operations on Main for {}", pending.size(),
                rs.level.dimension().identifier());
        for (var island : rs.islandRegistry.islands()) {
            synchronized (island.pendingArrivals) { island.pendingArrivals.clear(); }
        }
        synchronized (rs.globalArrivalsLock) { rs.globalArrivals.clear(); }
    }

    private void ensureFallback(ServerLevel level) {
        if (fallback == null && !consumed) fallback = takeState(level);
    }

    public static boolean hasFallback(ServerLevel level) {
        if (RegionScheduler.forLevel(level) != null) return false;
        // A fallback lane is Main-owned, never an off-owner substitute.
        if (!level.getServer().isSameThread()) return false;
        EndArrivalSavedData data = level.getDataStorage().get(TYPE);
        if (data == null) return false;
        data.ensureFallback(level);
        return data.fallback != null && (!data.fallback.pending.isEmpty()
                || !data.fallback.tracker.snapshot().isEmpty());
    }

    static boolean stageFallback(ServerLevel level, long chunk, Runnable body, String kind,
                                 Entity entity, BlockPos pos, boolean drops) {
        if (!hasFallback(level)) return false;
        EndArrivalSavedData data = level.getDataStorage().get(TYPE);
        data.fallback.pending.add(new CrossDimArrivals.StagedOp(level, chunk, body, kind,
                level.getServer().getTickCount(), data.fallback.domain, entity,
                null, pos, drops));
        return true;
    }

    public static void tickFallback(ServerLevel level) {
        if (!hasFallback(level)) return;
        EndArrivalSavedData data = level.getDataStorage().get(TYPE);
        Fallback state = data.fallback;
        for (var op : state.pending) lease(level, op.chunkKey());
        rearmRestoredLeases(level, state.tracker);
        ArrayList<CrossDimArrivals.StagedOp> released = CrossDimArrivals.extractForSave(
                state.pending, state.tracker);
        CrossDimArrivals.publishRecovered(released);
    }

    static void rearmRestoredLeases(ServerLevel level, EndPlatformHandoff.Tracker tracker) {
        for (var landing : tracker.snapshot()) {
            if (landing.handle() instanceof RestoredLanding restored) lease(level, restored.entityChunk);
        }
    }

    private static void lease(ServerLevel level, long chunk) {
        level.getChunkSource().addTicketWithRadius(TicketType.PORTAL,
                new ChunkPos(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)), 3);
    }

    static CompoundTag encode(List<CrossDimArrivals.StagedOp> pending,
                              List<EndPlatformHandoff.Landing> airborne) {
        CompoundTag result = emptySnapshot();
        ListTag operations = new ListTag();
        for (var op : pending) {
            CompoundTag entry = new CompoundTag();
            entry.putString("kind", op.kind());
            entry.putLong("chunk", op.chunkKey());
            entry.putLong("tick", op.sourceTick());
            entry.putLong("domain", op.sourceDomain());
            if ("regen".equals(op.kind()) && op.regenPos() != null) {
                entry.putLong("pos", op.regenPos().asLong());
                entry.putBoolean("drops", op.regenDrops());
            } else if ("entity".equals(op.kind()) && op.entity() != null) {
                Entity entity = op.entity();
                var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                        op.dest().registryAccess());
                output.putString("id", EntityType.getKey(entity.getType()).toString());
                entity.saveWithoutId(output);
                CompoundTag nbt = output.buildResult();
                // Every arrival has its own insertion op. Serialize passengers
                // once each, then restore the links across those same UUIDs.
                nbt.remove("Passengers");
                entry.put("entity", nbt);
                if (entity.isRemoved()) entry.putString("removed", entity.getRemovalReason().name());
                if (entity.getVehicle() != null) {
                    entry.putString("vehicle", entity.getVehicle().getUUID().toString());
                }
            } else {
                throw new IllegalStateException("Unsavable gate-held arrival kind: " + op.kind());
            }
            operations.add(entry);
        }
        result.put("pending", operations);
        ListTag landings = new ListTag();
        for (var landing : airborne) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("platform", landing.chunkKey());
            if (landing.handle() instanceof Entity entity) {
                entry.putString("uuid", entity.getUUID().toString());
                entry.putLong("chunk", entity.chunkPosition().pack());
            } else {
                RestoredLanding restored = (RestoredLanding) landing.handle();
                entry.putString("uuid", restored.uuid.toString());
                entry.putLong("chunk", restored.entityChunk);
            }
            landings.add(entry);
        }
        result.put("airborne", landings);
        return result;
    }

    private static Fallback decode(ServerLevel level, CompoundTag snapshot) {
        if (snapshot.getIntOr("schema", -1) != 1) {
            throw new IllegalStateException("Unsupported End arrival snapshot schema");
        }
        var tracker = new EndPlatformHandoff.Tracker(handle -> settled(level, handle));
        for (var tag : snapshot.getListOrEmpty("airborne")) {
            CompoundTag entry = (CompoundTag) tag;
            tracker.track(entry.getLongOr("platform", 0), new RestoredLanding(
                    UUID.fromString(entry.getStringOr("uuid", "")), entry.getLongOr("chunk", 0)));
        }
        ArrayList<CrossDimArrivals.StagedOp> pending = new ArrayList<>();
        HashMap<Long, Long> domains = new HashMap<>();
        HashMap<UUID, Entity> entities = new HashMap<>();
        HashMap<Entity, UUID> vehicles = new HashMap<>();
        for (var tag : snapshot.getListOrEmpty("pending")) {
            CompoundTag entry = (CompoundTag) tag;
            String kind = entry.getStringOr("kind", "");
            long chunk = entry.getLongOr("chunk", 0);
            long tick = entry.getLongOr("tick", -1);
            long domain = domains.computeIfAbsent(entry.getLongOr("domain", 0),
                    key -> RECOVERY_DOMAINS.getAndDecrement());
            Entity entity = null;
            BlockPos pos = null;
            boolean drops = entry.getBooleanOr("drops", false);
            Runnable body;
            if ("regen".equals(kind)) {
                pos = BlockPos.of(entry.getLong("pos").orElseThrow());
                BlockPos captured = pos;
                body = () -> net.minecraft.world.level.levelgen.feature.EndPlatformFeature
                        .createEndPlatform(level, captured, drops);
            } else if ("entity".equals(kind)) {
                entity = EntityType.loadEntityRecursive(entry.getCompound("entity").orElseThrow(),
                        level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                        EntityProcessor.NOP);
                if (entity == null || entities.putIfAbsent(entity.getUUID(), entity) != null) {
                    throw new IllegalStateException("Invalid/duplicate saved End arrival entity");
                }
                String removed = entry.getStringOr("removed", "");
                if (!removed.isEmpty()) entity.setRemoved(Entity.RemovalReason.valueOf(removed));
                String vehicle = entry.getStringOr("vehicle", "");
                if (!vehicle.isEmpty()) vehicles.put(entity, UUID.fromString(vehicle));
                Entity captured = entity;
                body = () -> StagedEntityArrival.publish(level, chunk, captured);
            } else {
                throw new IllegalStateException("Unknown saved End arrival kind: " + kind);
            }
            pending.add(new CrossDimArrivals.StagedOp(level, chunk, body, kind, tick,
                    domain, entity, null, pos, drops));
        }
        vehicles.forEach((rider, uuid) -> {
            Entity vehicle = entities.get(uuid);
            if (vehicle == null) vehicle = lookup(level, uuid);
            if (vehicle == null || !rider.startRiding(vehicle, true, false)) {
                throw new IllegalStateException("Saved End arrival vehicle is missing or invalid: " + uuid);
            }
        });
        return new Fallback(pending, tracker);
    }
}
