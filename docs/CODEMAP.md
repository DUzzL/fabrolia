# Fabrolia code map

Start here to find where something lives. Every path and method named below
exists in the tree. Line numbers are left out because they drift; use grep.

Path abbreviations:
- `C/` = `common/src/main/java/com/axalotl/fabrolia/common/`
- `T/` = `common/src/test/java/com/axalotl/fabrolia/common/`. A test listed without a directory is in `T/regions/`.

Design doc: `docs/ASYNC-ISLANDS.md`. It covers the clocks (§2), A2 units (§11), commands (§12), the chunk pass (§13), autosave (§14), tracking (§15), datapacks (§16) and Main trims (§17). Recent changes are in `CHANGELOG.md`.

Module layout:
- `api/`: concurrent fastutil-style collections (`api/src/main/java/com/axalotl/fabrolia/api/fastutil/`) and spawn views.
- `common/`: all of the logic.
- `fabric/`, `neoforge/`: loader entrypoints, config persistence, a few loader-specific mixins.
- `buildSrc/`: shared Java/release conventions (`fabrolia-java`, `fabrolia-release`)
  and verification tasks, e.g. `VerifyPlayerTickArchitectureTask`.
- `tools/release/`: release version bumps and exact Fabric archive validation;
  `gradle.properties` owns the version. See `docs/RELEASES.md`.
- `docs/README.md`: the architecture, stand, release and dated review index.

## 1. Threads and execution model

| Thread | Created by | Runs |
|---|---|---|
| `Server thread` (physical **Main**, vanilla) | vanilla | Server loop, level ticks, the global lane, every Main FIFO, fences. Use `RegionalPlayerEntityTicksV2.isPhysicalServerThread` to check for it. `isSameThread()` can return true on the V2 actor too: `MinecraftServerMixin.fabrolia$logicalPlayerActorThread` overrides it. |
| `Fabrolia-Region-Worker-N` | `RegionWorkerPool.start` → `new RegionWorkerThread(...)`. Pool size is `FabroliaConfig.getRegionWorkerCount()`. Started from `RegionScheduler.start`. | Island units, entity-phase region tasks, global/main-safe group tasks. Main loop is `RegionWorkerPool.workerLoop` → `workOnceBody`. |
| `Fabrolia-Tick-Pool-Thread-N` | `C/ParallelProcessor.java` (`setupThreadPool`, `FabroliaForkJoinWorkerThread`, ThreadGroup `Fabrolia-Tick-Pool`) | ForkJoin helper pool. Runs the flat (regions-off) entity path and parallel helpers such as `TrackingViewerPass`, the tracker index apply and the parallel spawn census. |
| `Fabrolia-Rate-Pacer` | `TemporalControl` (`Pacer.ensureStarted`) | Fires ordinary async island units at each island's rate. |
| `Fabrolia-Player-Region-Actor` | `C/regions/PlayerTickActor.java` (name constant in `RegionalPlayerEntityTicksV2`) | V2 exact-ordinal player turns: the "logical Main". |
| `Fabrolia-Save-IO-N` (≤4) | `C/save/AsyncSaveWriter.java` | Player/level/advancement/stats file writes. |
| `fabrolia-tick-repair-scan` | `C/regions/TickRepairRun.java` | Scan for `/fabrolia regions ticks repair-all`. |
| Netty / c2me threads | vanilla / c2me | Fabrolia only publishes onto them. Netty: `FabroliaConnectionEventLoop.fabrolia$enqueueEventLoopTask`, `ConnectionOutbox`. c2me: `C/mixin/c2me/FabroliaC2me*ActorThreadMixin`. |

Core concepts and the classes that own them:
- **Region** (`C/regions/Region.java`): one fixed 8×8-chunk grid cell (`RegionGrid`, `DEFAULT_SHIFT=3`) with a task queue drained by one worker at a time. Claimed with `Region.tryClaim` / `release`. The grid never merges. Regions are created lazily and reclaimed by `ThreadedRegionizer` (`regionForGridKey`, `evictIdle`). Lifecycle is in `RegionState`.
- **Island** (`C/regions/IslandRegistry.java`, `PIsland`): persistent identity for a connected group of populated cells. Islands are separated by ≥3 unloaded cells. The BFS result is `RegionizedServer.Island`. Merges and splits happen in `IslandRegistry.applyRebuild` / `applyRebuildCells`.
- **Domain / timeline** (`C/regions/EpochDomain.java`): epoch, entity barrier and L-clock (`unitGameTime`). There is one domain per island plus the level's `RegionizedServer.globalDomain`. Domain reassignment happens only at safe points (`RegionizedServer.assignDomain`, `rebaseDomainEpochAtSafePoint`).
- **Unit** (`C/regions/IslandTickUnit.java`): one island tick, run as a coordinator task on a worker. Chain: `submitUnit` → `submitStampedEntered` → `runCoordinated`. It runs on the pool's `unitTasks` lane and never nests another unit.
- **unitClaim**: the `PIsland.unitClaim` AtomicBoolean. Only one holder at a time: a running unit, or Main/topology/save quiescing the island. Workers take it with `PIsland.tryClaimAutonomousWork`.
- **mainClaim / tryMainClaim** (`C/regions/IslandCommands.java`): Main takes one island's claim.
  - `mainClaim` spins and keeps pumping `drainMainChunkServices` / `drainRoutedBatch` while it waits. It is not reentrant.
  - `tryMainClaim` never waits. A false result owns nothing, and the caller must leave the body unentered.
  - Related: `mainHolds`, `mainRelease`, `mainClaimScoped`.
- **Claim interest**: `PIsland.announceClaimInterest` / `withdrawClaimInterest` keep a waiter count. Units, chaining and the pacer yield on `PIsland.mainInterest()`.
- **Fence**: `IslandCommands.quiesceAllExcept(null[, budgetNanos])` takes every island's claim on every level. It is Main only, and `except` must be null. Release with `releaseAllExcept` / `releaseAllExceptAndPropagate`. Use `fenceWindowHeld` to detect nesting. Per-level variant: `RegionizedServer.quiesceAllIslands` / `releaseAllIslands` (topology, save).
- **Global lane**: `RegionizedServer.globalDomain`, i.e. gap and unpopulated regions. Main dispatches it once per level tick and waits only for this lane. Do not confuse it with the pool queues in `RegionWorkerPool` (`unitTasks`, `globalTasks`, `mainSafeTasks`, `mainOnlyTasks`; workers never poll `mainOnlyTasks`).
- **Clocks** (docs §2):
  - **W** = world/day time: global and real-time anchored. Read with `IslandClock.wallTime` (`RawGameTime`).
  - **L** = island logical tick: `PIsland.logicalTick` / `EpochDomain.unitGameTime`. `PrimaryLevelDataMixin` serves it from `getGameTime()` via `IslandClock.contextTime()`.
  - **G** = `server.getTickCount()`, for infrastructure only.
  - Rates and the pacer live in `C/regions/TemporalControl.java` (`Pacer`, `ensurePaced`). Rates never touch clocks.
  - **Naming trap:** in fail-stop comments, "W" means the winning fatal Throwable, not the world clock.

## 2. Tick walkthrough

### One Main tick (physical Main)
1. Server loop: `C/mixin/server/MinecraftServerMixin.java`.
   - `pollTask` HEAD → `RegionScheduler.throwWorkerFailureIfAny`.
   - After `tickServer`: `RegionScheduler.drainDeferredLifecycleTransitionsAtPostTickBoundary`.
2. `tickChildren` is wrapped by `MinecraftServerMixin.fabrolia$regionalPlayerServerPhase`.
   - V2 on: `RegionalPlayerEntityTicksV2.beginServerTick` → body → `RegionScheduler.commitSuccessIfHealthy` → `finishServerTick` / `abortServerTick`.
   - V2 off: the body runs in `runSuccessBodyIfHealthy`.
   - Connection tick: `throwWorkerFailureIfAny` → `RegionalPlayerEntityTicksV2.beforeConnections` → `ConsoleCommandFenceScope.runDedicatedTickConnection`.
   - Function tick: `ServerFunctionManagerMixin.fabrolia$markTickTag` → `AdvancementBuffer.beginTickHeadWindow` → `IslandFunctionHead.publishTickRoots`. The tick head is the only place that takes the global fence for routed advancements.
3. Per `ServerLevel.tick`:
   1. HEAD, `C/mixin/world/ServerLevelNearestPlayerMixin.java` `fabrolia$rebuildPlayerIndex`, in this order:
      - `PlayerChunkIndex.rebuild`
      - `VmpTrackerIndexApply.scheduleFor`
      - `RegionizedServer.onLevelTickStart` (flips the async latch, `globalDomain.epoch++`)
      - `evictIdleRegions`
      - `rebuildIslandsIfDirty`
      - In async mode: `IslandCommands.drainRoutedBatch`, then `RegionScheduler.drainMainSyncOutOfPhase`.
   2. Vanilla sub-phases. Each one is wrapped in `runSuccessBodyIfHealthy` (`C/mixin/world/ServerLevelMixin.java`). Main does only the global/ownerless part of each:
      - scheduled ticks: `fabrolia$islandScheduledTicks` → `IslandScheduledTicks.slotPartitionedAsync` (async islands; otherwise the vanilla scheduler runs)
      - raids: `RaidsTickMixin.fabrolia$dispatchRaidLifecycle` → `RaidIslandDispatch.dispatch`
      - chunk source tick
      - block events: `fabrolia$islandBlockEvents` → `IslandBlockEvents.runPartitioned`
   3. Entity phase: `ServerLevelMixin.overwriteEntityTicking`.
      1. `ServerChunkCacheMixin.fabrolia$armDeferredChunkWork` (arms the global-lane spawn and random ticks).
      2. `RegionScheduler.dispatchPreEntityWork`, which reaches `RegionizedServer.dispatchOwnedEntityTicks(false)`:
         - `CrossDimArrivals.publishGlobal`, `TpsHud.tick`
         - async only: `TemporalControl.tickAcks`, `CrossDimLinks.mainTick`, `ChunkDelivery.liveness`, `IslandBlockEntityTicks.preUnitMaintenance`
         - per island: `IslandTickUnit.submitUnit` (sprint/coherent-behind islands) or `TemporalControl.ensurePaced` (normal islands, which the pacer drives)
         - global lane: `ThreadedRegionizer.snapshotAtEntityDispatchCut` → `RegionizedServer.dispatchEntityPhase(globalDomain, …, false)` (queues only)
      3. `ParallelProcessor.pumpUntilDone`: helps with `drainReadyMainSyncTicks`, `drainReadyMainTracking` and region tasks. It never drains `MinecraftServer.pollTask`.
      4. Drain the accepted Main-sync work, then `RegionizedServer.tickGlobalEntitiesInVanillaOrder(consumer)` visits the global remainder of a finite linked-list snapshot (skipped when no global-domain region indexes an entity, the normal async state). Exact player mode retains the canonical whole-level iteration.
      5. Then:
         - `RegionalPlayerEntityTicksV2.finishLevel`
         - `RegionizedServer.drainDeferredCrossRegion`
         - `drainOverdueMainSyncTicks` / `drainOverdueMainTracking` (`RegionScheduler.IslandBuckets`, `MainSyncService`)
         - `ParallelProcessor.onEntityTickBatchEnd` → `AdvancementBuffer.applyDeferred`
   4. Block entities: `LevelMixin` → `IslandBlockEntityTicks.tickPartitioned` (global part only). Then entity-section tick and broadcast (`ServerChunkCacheMixin.fabrolia$losslessGlobalBlockUpdateDrain` → `GlobalBlockUpdateDrain.drainLevel`).
4. Autosave and `save-all`:
   - `MinecraftServerMixin.fabrolia$quiesceIslandsForServerSave`: `quiesceAllExcept(null)` → `SaveDurability.callDeferred` → `releaseAllExceptAndPropagate`. With flush it also calls `AsyncSaveWriter.awaitAll`.
   - `PlayerListMixin.fabrolia$deferPlayerFileWrites` → `IslandSaveGroup`.

### One island unit (`IslandTickUnit.runCoordinated`, on a region worker)
Admission:
- `NetherPortalTransfers.beforeUnitAdmission`
- claim via `PIsland.tryClaimAutonomousWork`. The unit yields with no body run if `mainInterest()` is set or a Main fence is active.
- freshness check (`IslandUnitAdmission`).

Body (L = `logicalTick + 1` is written into `d.unitGameTime`):
1. `IslandScheduledTicks.preflightIslandScheduled`. A false result is a HOLD, and the body is skipped.
2. `IslandBlockEvents.beginUnitCapture`
3. Unit head:
   1. `CommandShards.serviceIslandTasks`
   2. `CrossDimArrivals.publishIsland`
   3. `IslandFunctionHead.runIslandHead` (datapack `#tick` roots). Gated by `ScheduledTickIslandAdmission.shouldApplyUnitHead`.
4. Scheduled ticks: `IslandScheduledTicks.runIslandScheduled` (false → HOLD).
5. Raids: `RaidIslandDispatch.service`.
6. Chunk pass: `ServerChunkCacheMixin.fabrolia$tickIslandChunks` (spawning, thunder, random ticks; docs §13).
7. Block events: `IslandBlockEvents.runIslandEvents`, then `endUnitCapture`.
8. Membership cut: `epoch++` under `ThreadedRegionizer.lockDomainCreationCut`.
9. Entity phase. Mode: `FabroliaConfig.islandEntityParallel` (default false; `/fabrolia regions islandentities vanilla|parallel`), read once per unit; a unit whose members hold primed TNT or a TNT minecart (`RegionizedServer.holdsOrderSensitiveEntity`) always takes the serial pass. Parallel: `dispatchEntityPhase(d, members, true)` runs each member's `tickOwned` under the ColorGate (the 0.2.38 path), the coordinator helps in `awaitEntityBarrier`, and there is no serial pass. Serial (default): `RegionizedServer.dispatchEntityPhase(d, members, false)` joins member queues only. The coordinator waits in `IslandTickUnit.awaitEntityBarrier` (`EntityBarrierWatchdog`), then `tickIslandEntitiesInVanillaOrder` visits a finite snapshot of the actual `EntityTickList` in insertion order, filtered to its domain (one region lookup per grid key). `IslandEntityPhaseScope` authenticates the exact successful join and unit claim; block effects inside that island and its halo run inline. Ordinary TNT remains in this pass; a final TNT tick whose blast or movement leaves its owned footprint uses an all-island Main fence. Independent islands remain parallel.
10. `RegionizedServer.drainDeferredOps` (deferred cross-region replay)
11. Block entities: `IslandBlockEntityTicks.tickIslandCells`
12. Block-update flush: `ServerChunkCacheMixin.fabrolia$flushIslandBlockUpdates`
13. Tracking flush: `IslandEntityTracking.flush`
14. Player late turn: `AsyncIslandPlayerTicks.runLate` (also `sendPredictedBlockCorrections`)
15. `ChunkDelivery.sealPending`

Tail:
- `prepareCompletion` and `CrossDimArrivals.prepareSeal`, then `RegionScheduler.commitSuccessIfHealthy` (`AttemptOutcome.ADVANCED`; L++).
- On HOLD, the tail commits without advancing L.
- Invariant: **L moves only through the unit's successful completion.**
- The early player half `AsyncIslandPlayerTicks.tryRunEarly` runs inside the entity phase, from `RegionizedServer.tickOneAdmitted`.

## 3. Subsystem table

| Subsystem | Key files | Entry points | Invariant | Tests |
|---|---|---|---|---|
| Scheduler / worker pool | `C/regions/RegionScheduler.java`, `RegionWorkerPool.java`, `RegionWorkerThread.java`, `C/ParallelProcessor.java` | `RegionScheduler.start`, `RegionWorkerPool.workerLoop`, `workOnceBody`, `enqueueUnit`, `ParallelProcessor.pumpUntilDone` | Lock order: scheduler `LIFECYCLE_LOCK` → pool monitor. Never join workers while holding it. | `RegionWorkerPool*Test` (Lifecycle, MainOnlyLane, PlayerLane, CausalSuccessBody, RetainedShutdown, ShutdownActivityEpoch), `RegionScheduler*Test`, `QueuedMainTaskFailStopTest` |
| Regionizer / topology / rebuild | `ThreadedRegionizer.java`, `RegionizedServer.java`, `IslandRegistry.java`, `RegionGrid.java`, `TrackerTopologySnapshot.java` | `RegionScheduler.onChunkFullChange` → `RegionizedServer.onChunkFullTransition`; `rebuildIslandsIfDirty` → `rebuildIslandsBody` → `IslandRegistry.applyRebuild`; `evictIdleRegions` | The rebuild runs under `quiesceAllIslands`. The registry is mutated only on Main at the level-tick head. Lazy drift (`LAZY_TOPOLOGY_TICKS`). | `RegionPopulationTopologyTest`, `TopologyRegionCreationGateTest`, `LazyTopologyAndAsyncEvictionTest`, `RegionCreationEpochBaselineTest`, `RegionGridTest`, `RegionDoneSealRaceTest` |
| Temporal control / pacer | `TemporalControl.java`, `IslandClock.java`, `RawGameTime.java`, `IslandUnitAdmission.java` | `TemporalControl.ensurePaced`, `Pacer.fire`, `Pacer.reconcile`, `onTopologyCommitted`; `/tick rate\|sprint island here` | "Rates never touch clocks or deadlines". Main never submits an ordinary unit directly. | `AutonomousPacerTicketStateTest`, `AutonomousPacerNormalStopTest`, `IslandSprintPreparationPauseTest`, `TemporalSessionPruneTest`, `IslandClockHandlingPhaseTest`, `IslandUnitAdmissionTest` |
| Entity ticking & dispatch | `RegionizedServer.java` (`dispatchEntityPhase`, `tickIslandEntitiesInVanillaOrder`, `tickGlobalEntitiesInVanillaOrder`), `IslandEntityPhaseScope.java`, `EntityTickListSnapshotAccess.java`, `TntExplosionFootprint.java`, `EntityTickLease.java`, `EntityBarrierWatchdog.java`, `ColorGate.java`, `C/mixin/entity/EntityTickListMixin.java`, `C/mixin/entity/EntityMixin.java` | `ServerLevelMixin.overwriteEntityTicking`, `fabrolia$guardTickNonPassenger`, `EntityTickLease.tryBegin` | Dispatch requires an exact region-creation cut (it throws otherwise). Each island visits its linked-list snapshot serially in Vanilla insertion order; entity-phase births wait for the next snapshot. Opt-in `islandEntityParallel` ticks member regions in parallel instead, except for units holding TNT. The queue join precedes the exclusive entity pass. Async mode requires `FabroliaConfig.regionEventScheduler`. | `EntityPhaseMembershipParityTest`, `IslandEntityPhaseScopeTest`, `EntityTickLeaseTest`, `IslandBarrierWatchdogTest`, `IslandEntityBarrierWaitTest`, `EntityDispatchModeLifetimeTest`, `IslandEntityPhaseModeTest`, `EntityUnloadStarvationAdmissionTest`, `OwnChunkFullGateTest` |
| Entity tracking | `IslandEntityTracking.java`, `TrackingViewerPass.java`, `TrackerIndexChanges.java`, `TrackingPublicationBinding.java`, `TrackingTopologyAdmissionGate.java`, `VmpTrackerIndexApply.java`, `C/mixin/vmp/NearbyEntityTrackingMixin.java`, `C/mixin/server/ChunkMapTrackedEntityMixin.java`, `ServerEntityIslandTrackingMixin.java` | `IslandEntityTracking.flush` (unit tail), `route`, `beginMainPublication` / `finishMainPublication`; `NearbyEntityTrackingMixin.fabrolia$tickTrackers` | Every pairing mutation is staged and then committed by its owner, under the `TrackedEntity` monitor. Non-player add/remove never takes the tracker lock. | `IslandEntityTrackingWiringTest`, `TrackedEntityPendingPairingTest`, `IslandEntityPairingStateModelTest`, `TrackerIndexChangesTest`, `TrackingPublicationBindingTest`, `TrackingTopologyAdmissionGateTest`, `T/mixin/vmp/VmpPlayerTrackingLockWiringTest` |
| Players: A5 player ticks | `AsyncIslandPlayerTicks.java`, `IslandPlayerTurnSession.java`, `PlayerTurnOwnership.java`, `AsyncIslandPlayerLifecycle.java`, `RegionalPlayerEntityTicksV2.java`, `PlayerTickActor.java`, `PlayerTickPhase.java` | `AsyncIslandPlayerTicks.tryRunEarly` / `runLate`, `runMainListenerTick`, `enqueueRoutedMutation`; `RegionalPlayerEntityTicksV2.executeExactOrdinal` | One venue owns a player at a time (`PlayerTurnOwnership.currentThreadOwns`). Once an island body has started, it is never retried on Main. | `IslandPlayerTurnSessionTest`, `IslandPlayerTurnSessionConcurrencyTest`, `PlayerTurnOwnershipTest`, `PlayerTickActorTest`, `PlayerTickPhaseTest`, `PlayerTickV2WiringTest`, `RegionalPlayerTickWiringTest`, `AsyncIslandPendingDisconnectTest` |
| Players: packets / connection outbox | `AsyncIslandPacketPolicy.java`, `AsyncIslandPacketContinuations.java`, `ConnectionOutbox.java`, `C/mixin/network/ConnectionIslandPlayerMixin.java`, `PacketProcessorPlayerTickMixin.java` | `ConnectionIslandPlayerMixin.fabrolia$enqueueIslandPlayerPacket` → `AsyncIslandPlayerTicks.enqueuePacket` → `AsyncIslandPacketPolicy.classify` (`ISLAND_FIFO` / `MAIN_FIFO`); S2C via `ConnectionOutbox.offer` | Exact-class allow-list (`AUDITED_26_3`): unknown/mod packets fall back to `MAIN_FIFO`. Each connection's packets go out in exactly the order they were appended. | `AsyncIslandPacketPolicyTest`, `VanillaPacketHandlerCoverageTest`, `PacketLaneProductionPathTest`, `ConnectionOutboxTest`, `PacketFailureContainmentTest`, `AsyncIslandPacketContinuationWiringTest` |
| Players: chunk delivery | `ChunkDelivery.java`, `ChunkDeliveryCandidates.java`, `ChunkBatchAckReplay.java`, `FabroliaFullChunkPublisher.java`, `C/mixin/server/FabroliaPlayerChunkSenderMixin.java`, `FabroliaChunkAckMixin.java`, `PlayerMapMixin.java` | `ChunkDelivery.track`, `sealPending` (unit), `sendNextChunks` (Main), `onAck` | Chunk data is captured only by its owner. The sender/Main never takes an island claim and never spins. | `ChunkDeliveryCandidatesTest`, `ChunkDeliveryPublicationProgressTest`, `ChunkDeliverySuccessLinearizationWiringTest`, `ChunkBatchAckOwnershipTest`, `ChunkWriteEnqueueFenceTest` |
| Chunk pass & spawning (census) | `C/mixin/server/ServerChunkCacheMixin.java`, `NaturalSpawnChunks.java`, `SpawnCapLanes.java`, `C/parallelised/utils/SpawnCensus.java`, `SpawnIndex.java`, `ParallelSpawnHelper.java`, `LocalCapPackReservation.java`, `C/mixin/entity/spawn/NaturalSpawnerMixin.java` | `fabrolia$tickIslandChunks` (island); `fabrolia$armDeferredChunkWork` / `fabrolia$interceptTickSpawning` (Main); `ParallelSpawnHelper.censusIslandLane`, `buildState` | Per-lane candidate sets partition vanilla's set exactly. A lane checks the cap against the SUM of all lane slots and never waits. | `NaturalSpawnChunksTest`, `SpawnCapLanesTest`, `T/parallelised/utils/SpawnCensusQueryAcceptanceTest`, `SpawnIndexStrikeLifecycleTest`, `LocalCapPackReservationTest`, `T/mixin/entity/spawn/SpawnLocalCapReservationPinTest` |
| Scheduled ticks / tick domains | `IslandScheduledTicks.java`, `TickDomains.java`, `FabroliaTickDomain.java`, `CausalScheduledTickIndex.java`, `ScheduledTickIslandAdmission.java`, `C/mixin/world/LevelTicksMixin.java`, `LevelChunkTicksMixin.java`, `TickRepair.java` | `preflightIslandScheduled`, `runIslandScheduled`, `slotPartitionedAsync`; `TickDomains.rebaseTo`, `resolveNow` | Every trigger time in a container is in the clock domain its tag names. Lock order: block lane first, then fluid. | `ScheduledTickIslandAdmissionTest`, `ScheduledTickClockDomainTest`, `ScheduledTickSliceRegistryTest`, `TickDomainsProductionPathTest`, `TickSaveRaceTest`, `IslandScheduledHoldStreakTest`, `TickRepairRunTest` |
| Block entities | `IslandBlockEntityTicks.java`, `BlockEntityTickerList.java`, `BlockEntityTickerPositionIndex.java`, `BlockEntitySleepDomains.java`, `BlockEntityIndexRetention.java`, `HopperClockHeal.java`, `C/mixin/world/LevelMixin.java` | `tickIslandCells` (unit), `tickPartitioned` (Main), `registerTicker`, `preUnitMaintenance` | Cell membership is fixed at registration time. Never hold the `BlockEntityTickerAccess` monitor while running ticker bodies. | `IslandBlockEntityFailStopTest`, `BlockEntityTickerListTest`, `BlockEntityTickerPositionIndexTest`, `BlockEntityIndexRetentionTest`, `BlockEntityRegistrationRaceTest`, `T/mixin/lithium/HopperSleepClockDomainTest` |
| Block events | `IslandBlockEvents.java`, `BlockEventQueueAccess.java` | `runIslandEvents`, `runPartitioned`, `captureLocal` (`ServerLevelMixin.overwriteQueueAdd`) | Island events run before its entity phase. A canonical pending-identity set spans shared, active and captured queues. Dequeue removes one identity immediately before its callback; cancellation reaches all stores. | `BlockEventPendingParityTest` |
| Block updates / broadcast | `GlobalBlockUpdateDrain.java`, `IslandBlockUpdateAccess.java`, `ServerChunkCacheMixin.java`, `C/mixin/world/ChunkHolderMixin.java` | `fabrolia$queueIslandBlockUpdate`, `fabrolia$flushIslandBlockUpdates`, `GlobalBlockUpdateDrain.drainLevel`; `AsyncIslandPlayerTicks.sendPredictedBlockCorrections` | Main may publish an island's holder only while it holds that island's claim. Otherwise the island publishes it at its tail. | `GlobalBlockUpdateDrainTest`, `IslandBlockPublicationWiringTest`, `IslandBlockPublicationRaceModelTest`, `PredictedBlockCorrectionModelTest`, `PredictedBlockCorrectionWiringTest` |
| Nether portals & cross-dim | `NetherPortalTransfers.java`, `NetherSourceCohorts.java`, `NetherPortalPreparation.java`, `NetherPortalPlayerAccess.java`, `CrossDimArrivals.java`, `CrossDimLinks.java`, `EndPlatformHandoff.java`, `C/mixin/utils/EntityHandlePortalMixin.java`, `C/mixin/world/CrossDimTeleportMixin.java`, `C/parallelised/utils/PlayerDimensionTransition.java` | `EntityHandlePortalMixin.fabrolia$handlePortal` → `NetherPortalTransfers.submit`, `serviceOne`, `serviceMain`; `CrossDimTeleportMixin.fabrolia$stageArrival` → `CrossDimArrivals.stage` / `publishIsland` / `publishGlobal` | A Nether request leaves its source tick as data, never as a Main teleport. Arrivals are published whole at the destination unit head. Lock order: claims → reservation monitor → guard. `NetherPortalPreparation.serviceMain` pauses the pool only for a request with new Main work or a completed awaited load (`Request.wantsMainTurn`); an unchanged demand whose load runs is `awaitLoad`, not `requestMain`. | `Nether*Test` (12), `CrossDimArrival*Test`, `CrossDimLinksTest`, `CrossDimProjectionTest`, `EndPlatformHandoffTest`, `T/parallelised/utils/PortalDestructiveEntryTest`, `PlayerDimensionTransitionTest`, `Pearl*Test` |
| Datapack functions | `FunctionAnalyzer.java`, `FunctionPlanner.java`, `FunctionPlan.java`, `FunctionIR.java`, `FunctionPartition.java`, `FunctionPassMark.java`, `IslandFunctionHead.java`, `IslandFunctionScope.java`, `C/mixin/commands/ServerFunctionManagerMixin.java`, `TimerQueueMixin.java`, `ServerFunctionLibraryMixin.java`, `Island*GuardMixin.java` | `FunctionAnalyzer.beginReload` / `replaceAndActivate`, `FunctionPlanner.build`, `IslandFunctionHead.runIslandHead` / `runMainPartition` / `dispatchLoopCallback`; `ServerFunctionManagerMixin.fabrolia$fenceFunction` | Classification is an allow-list. Each entity runs at most once per pass (`FunctionPassMark`: OWED → passId). Owner-local bodies run inside `IslandFunctionScope` guards, and any violation fail-stops. | `FunctionPlanVerdictTest`, `FunctionPartitionedExecutionTest`, `FunctionReloadTransactionTest`, `FunctionReloadTransactionWiringTest`, `ScheduledFunctionFenceScopeTest` |
| Commands / fences | `C/mixin/commands/CommandsMixin.java`, `IslandCommands.java`, `CommandShards.java`, `ConsoleCommandFenceScope.java`, `RconCommandFenceScope.java`, `PlayerCommandFenceScope.java`, `DedicatedServerConsoleCommandMixin.java`, `RconClientCommandFenceMixin.java`, `BaseCommandBlockMixin.java` | `CommandsMixin.fabrolia$fenceMainCommands` / `fabrolia$fenceParsedMainCommands`; `IslandCommands.routeCommandBlock`, `drainRoutedBatch` | A clean-root receipt (console, RCON or player) allows an unbounded fence. Otherwise the fence attempt is bounded to 50 ms. If ownership can't be taken, Main fail-stops; it never replays later. | `ConsoleCommandFenceScopeTest`, `RconCommandFenceScopeTest`, `PlayerCommandFenceScopeTest`, `CommandsDirectExecutionFenceTest`, `DedicatedConsoleCommandFenceWiringTest`, `CommandRaidDrainAtomicAdmissionTest` |
| Advancements | `C/parallelised/advancement/AdvancementBuffer.java`, `AdvancementOwnership.java`, `AdvancementRewardRouting.java`, `AdvancementBatchScope.java`, `C/mixin/advancement/SimpleCriterionTriggerMixin.java`, `PlayerAdvancementsMixin.java` | `AdvancementBuffer.trigger`, `applyDeferred`, `beginTickHeadWindow`, `drainBeforeRemoval` | Advancements are evaluated and awarded on the player's owner thread. Everything else goes to ONE Main FIFO (`ROUTED`). Award/revoke anywhere else throws. | `T/parallelised/advancement/AdvancementOwnerRoutingTest`, `AdvancementBatchScopeOwnershipTest` |
| Raids | `RaidIslandDispatch.java`, `RaidOwnership.java`, `C/mixin/world/RaidsTickMixin.java`, `RaidsOmenRouteMixin.java` | `RaidIslandDispatch.service` (unit), `dispatch` (Main), `assignCreated`, `reassignAfterTopology` | Raids tick once per owner unit, after scheduled ticks and before block events. A raid with a foreign owner runs under the Main fence. | `RaidOwnershipTest`, `RaidInertDispatchTest`, `RaidVillageParityTest`, `RaidSpawnAdmissionParityTest` |
| Entity load/unload status | `C/mixin/server/PersistentEntityUnloadRetryMixin.java`, `IslandUnitAdmission.java` | `fabrolia$serializeUnloadStatusMutation`, `fabrolia$applyPendingStatusTransitions`, `fabrolia$applyStatusesBeforePendingLoads` | Visibility changes hold the chunk owner's claim and preserve per-chunk order. Progress polls skip busy chunks during ticks and vanilla managed entity-load waits, so a suspended login does not wait for another world tick. Unavailable claims remain non-blocking. A false entity store is retried every pass (vanilla contract restored over C2ME); `IslandUnitAdmission.EntityUnloadRetryAges` only ages pending retries for the 60 s stranded-chunk WARN. | `T/mixin/server/EntityStatusOwnerTransactionTest`, `EntityUnloadStarvationAdmissionTest`, `EntityUnloadRetryAgesTest` |
| Saves / autosave | `C/save/AsyncSaveWriter.java`, `SaveDurability.java`, `PlayerFileJobs.java`, `DeferredSavedDataEncodes.java`, `IslandSaveGroup.java`, `C/mixin/server/PlayerListMixin.java`, `PlayerDataStorageSaveMixin.java`, `LevelStorageAccessSaveMixin.java`, `SavedDataStorageEncodeMixin.java` | `MinecraftServerMixin.fabrolia$quiesceIslandsForServerSave`, `PlayerListMixin.fabrolia$ownerSafePlayerSave`, `AsyncSaveWriter.submit` / `awaitAll` | Snapshots are taken under the claims, and IO happens on the writer. Each file is a FIFO. A leaving player's save is always durable. Main holds at most one island claim at a time. | `T/save/AsyncSaveWriterTest`, `PlayerFileJobsTest`, `DeferredSavedDataEncodesTest`, `IslandSaveGroupTest`, `IslandIncrementalSaveAdmissionTest`, `TickSaveRaceTest` |
| POI | `C/utils/PoiStripes.java`, `PoiChunkLoadFence.java`, `FabroliaPoiTracker.java`, `FabroliaPoiLoadGate.java`, `PoiPublicationFence.java`, `C/mixin/entity/movement/PoiManagerMixin.java`, `PoiManagerDistanceMixin.java`, `C/mixin/world/SectionStorageMixin.java`, `ServerLevelPortalPreparationMixin.java` | `PoiStripes.takeTicketAtomically`, `PoiChunkLoadFence.runExclusively`, `PoiPublicationFence.submit`, `fabrolia$drainOnQuery`, `fabrolia$trackPortalPublication` | Never take a second stripe while holding one. The tracker is updated holding no POI lock. Distance queries drain accepted notifications before graph relaxation, without requesting additional POI loads. A block change's POI add/remove runs inline on the simulation thread that changed the block (vanilla `server.execute` on the ticking thread); only `nether_portal` callbacks enter the publication fence. | `PoiChunkLoadFenceTest`, `PoiPublicationFenceTest`, `PoiTakeTicketAtomicityTest`, `PoiTrackerDeferralTest`, `PoiBlockChangeCallbackTest`, `T/utils/PoiSectionReadSnapshotTest` |
| c2me / lithium / vmp / carpet / servux | `C/mixin/c2me/`, `C/mixin/lithium/`, `C/mixin/vmp/`, `C/mixin/carpet/`, `C/mixin/servux/ServuxStructureWatchMainRouteMixin.java`, `C2meStrandHeal.java`, `WorkerChunkCache.java`, `C/vmp/PublishedLong2ObjectMap.java`, `CarpetPlayerMutationRouter.java`, `CarpetTpsLines.java`, `C/mixin/utils/MixinCanceller.java` | `MixinCanceller.shouldCancel` (drops Fabrolia's c2me/lithium/vmp mixins when that mod is absent, and cancels conflicting upstream mixins); carpet and servux use `@Pseudo` | Fabrolia diagnoses c2me broken strands but never restarts an upgrade. Carpet mutations go through the A5 Main FIFO. Third-party hooks on `ChunkMap.markChunkPendingToSend` may fire on island workers; Servux's structure tracking is routed to Main. | `T/mixin/c2me/C2me*WiringTest`, `T/mixin/lithium/LithiumLevelActorThreadWiringTest`, `T/mixin/utils/LithiumAiTaskCancellationTest`, `T/mixin/carpet/Carpet*Test`, `C2meStrandHealTest`, `WorkerChunkCacheTest`, `T/vmp/PublishedLong2ObjectMapTest` |
| Metrics / TPS HUD | `TpsHud.java`, `TpsWindow.java`, `IslandMetrics.java`, `FlightRecorder.java`, `C/utils/TickStats.java`, `C/commands/StatsCommand.java` | `TpsHud.tick` / `toggle`, `FlightRecorder.record` / `dump`, `/fabrolia stats`, `/fabrolia regions tps`, `regions debug flightrec` | Island TPS = completed units per real second, never clamped. Reads within one window are idempotent. | `TpsWindowTest`, `TpsHudDimColorTest`, `CarpetTpsLinesTest` |
| Config | `C/config/FabroliaConfig.java` (static fields = live values), `fabric/src/main/java/com/axalotl/fabrolia/fabric/config/FabroliaConfig.java`, `neoforge/src/main/java/com/axalotl/fabrolia/neoforge/config/FabroliaConfig.java`, `C/commands/ConfigCommand.java` | Fields are read directly (`FabroliaConfig.asyncIslands`). Getters exist for derived values (`getRegionWorkerCount`). Reload: `/fabrolia config reload` → `PlatformUtils.reloadConfig` → `RegionalPlayerEntityTicksV2.applyConfiguredPreference`. | `enforceSafetyInvariants` forces the color gate on. Add a new key to the common class AND both loader persisters. | `PlayerTickExperimentRemovalTest` (reads all three sources), `EntitySafetyPolicyTest` |
| Debug commands | `C/commands/RegionCommand.java`, `TickInspector.java`, `RegionViz.java`, `PortalStuckProbe.java`, `C/utils/FabroliaDebug.java` | `RegionCommand.register`: `regions list\|top\|ticks\|async\|tps\|debug <regionlist\|islandsummary\|dump\|show\|temporal\|flightrec\|advstats\|portalstuck\|…>` | `TickInspector` runs on Main under the command fence and deletes only explicitly listed entries. | `CommandMainPublicationFailStopWiringTest`, `CommandEntityCatchEdgeWiringTest` |

Commands are registered in `FabroliaCommand.register` → `ConfigCommand.registerConfig`, `StatsCommand.registerStatus`, `RegionCommand.register`, `FunctionsCommand.register`. Call sites: `fabric/src/main/java/com/axalotl/fabrolia/fabric/FabroliaFabric.java` (`CommandRegistrationCallback`) and `neoforge/src/main/java/com/axalotl/fabrolia/neoforge/FabroliaNeoForge.java` (`registerCommandsEvent`).

## 4. Where to look when…

| Symptom / question | Look here |
|---|---|
| Crash stack shows `fabrolia$X` in vanilla class `Y` | That is a mixin handler. Grep `fabrolia\$X` under `C/mixin/`; the class is usually `YMixin` in `C/mixin/<area>/` (areas: server, world, entity, network, commands, advancement, utils, c2me, lithium, vmp, carpet). Registered in `common/src/main/resources/fabrolia.common.mixins.json` (plus `fabric/src/main/resources/fabrolia.fabric.mixins.json`, `neoforge/src/main/resources/fabrolia.neoforge.mixins.json`). Frames named `handler$xxx$fabrolia$X` are the same handler. |
| Stack shows `synchronized` on a vanilla method, or fastutil maps swapped for concurrent ones | `C/mixin/utils/SynchronisePlugin.java` (the common config's mixin plugin; adds ACC_SYNCHRONIZED via `SyncAllMixin`) |
| A compat mixin did not apply / an upstream mixin is missing | `C/mixin/utils/MixinCanceller.java` |
| Server stopped with a "fatal" / first failure from a worker | `RegionWorkerPool.publishFatalFailure`, `RegionScheduler.publishFatalAndSelectWinner`, `throwWorkerFailureIfAny`. The original stack is the worker's. |
| TPS drop on one island | `/fabrolia regions tps`, `/fabrolia regions debug islandsummary`, `regions top`. Code: `IslandMetrics`, `IslandTickUnit.runCoordinated` phases, `IslandScheduledTicks.recordHold` (HOLD streaks), `TemporalControl`. |
| Island not advancing at all (HOLD / stuck) | `ScheduledTickIslandAdmission.classify`, `IslandScheduledTicks.preflightIslandScheduled`, `PIsland.mainInterest()`, `EntityBarrierWatchdog`, `regions debug flightrec dump` |
| Main MSPT high | `/fabrolia stats`, `C/utils/TickStats.java`, `ParallelProcessor.pumpUntilDone`, MAIN_SYNC drains (`RegionScheduler.IslandBuckets`, `MainSyncService`), the global lane `dispatchEntityPhase(globalDomain…)`, `GlobalBlockUpdateDrain`, `ChunkDelivery.sendNextChunks`, docs §17 |
| Main hangs / deadlock | Lock-order comments (§5). `RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates`, `C/mixin/server/BlockableEventLoopMainWaitGuardMixin.java`, `IslandCommands.mainClaim` (pumps while spinning), watchdog: `fabric/src/main/java/com/axalotl/fabrolia/fabric/mixin/server/ServerWatchdogMixin.java`. Main spinning in `RegionWorkerPool.pauseExactTurnAndAwait` / `stop`: a pool obligation (`externalObligations`) whose only servicer is Main; every such venue must be drained by the progress lambdas (`RegionScheduler.pauseExactPlayerTurn`, stop `drainProgress`) and the fail-stop sweep (`RegionScheduler.cancelFailedGenerationQueues`), e.g. `PassiveConnectionLoops` |
| A datapack behaves differently | `/fabrolia functions plan` (verdict per line) and `/fabrolia functions fences`. Code: `FunctionPlanner`, `IslandFunctionHead`, `IslandFunctionScope`, `C/mixin/commands/Island*GuardMixin.java`, `TimerQueueMixin`. Island functions run at island cadence L (docs §16). |
| A command misbehaves or fail-stops | `CommandsMixin`, `IslandCommands.quiesceAllExcept`, `*CommandFenceScope`. Command blocks: `IslandCommands.routeCommandBlock`. |
| Packets out of order | `ConnectionOutbox` (per-connection FIFO, `WorkerScope`), `ConnectionIslandPlayerMixin.fabrolia$orderedOffLoopSend`, `AsyncIslandPacketPolicy` (one `MAIN_FIFO` packet orders everything after it), `IslandPlayerTurnSession.enqueuePacket` |
| A new or mod packet is handled wrongly | `AsyncIslandPacketPolicy` `AUDITED_26_3` (unknown classes go to `MAIN_FIFO`), `VanillaPacketHandlerCoverageTest` |
| Block not updating for players | `ServerChunkCacheMixin.fabrolia$queueIslandBlockUpdate` / `fabrolia$flushIslandBlockUpdates`, `GlobalBlockUpdateDrain`, `ChunkHolderMixin`, `AsyncIslandPlayerTicks.sendPredictedBlockCorrections` |
| Chunks not arriving / client stuck loading | `ChunkDelivery` (state machine; `liveness`, `sealPending`), `FabroliaPlayerChunkSenderMixin`, `ChunkBatchAckReplay`, `regions debug chunksender` |
| Entity invisible / ghost / not tracked | `IslandEntityTracking` (`route`, `flush`), `NearbyEntityTrackingMixin`, `TrackerIndexChanges`, `ChunkMapTrackedEntityMixin` |
| Mob spawning differs | `ServerChunkCacheMixin.fabrolia$tickIslandChunks`, `NaturalSpawnChunks`, `SpawnCapLanes`, `SpawnCensus`, `LocalCapPackReservation`, `regions debug spawncap\|spawncensus\|spawnrate`. Accepted deviations are listed in docs §13. |
| Redstone / scheduled-tick timing differs | `IslandScheduledTicks`, `TickDomains` (clock domain per container), `LevelTicksMixin`, `CausalScheduledTickIndex`; repair: `regions ticks` (`TickInspector`, `TickRepairRun`) |
| Hopper / furnace / BE timing | `IslandBlockEntityTicks`, `BlockEntitySleepDomains`, `HopperClockHeal`, `C/mixin/lithium/FabroliaLithiumHopperSleepMixin.java` |
| Portal / dimension bug | `EntityHandlePortalMixin` → `NetherPortalTransfers` / `PlayerDimensionTransition.deferPortal`; non-players: `CrossDimTeleportMixin` → `CrossDimArrivals`; end platform: `EndPlatformHandoff`; probe: `regions debug portalstuck` (`PortalStuckProbe`) |
| Ender pearls lost / chunk tickets | `C/parallelised/utils/PearlRegistration.java`, `PearlLoadRecovery.java`, `C/utils/PearlTicketHold.java`, `PearlTrace.java` |
| Raid behaves oddly | `RaidIslandDispatch`, `RaidOwnership`, `RaidsOmenRouteMixin`; village checks: POI timing (`ServerLevelPortalPreparationMixin.fabrolia$trackPortalPublication`, `PoiManagerDistanceMixin`) |
| Advancement not granted / reward late | `AdvancementBuffer` (owner vs `ROUTED`), `AdvancementRewardRouting`, `regions debug advstats` |
| Save/autosave spike | `MinecraftServerMixin.fabrolia$quiesceIslandsForServerSave` (all-island fence), `IslandSaveGroup`, `AsyncSaveWriter` (logs writes stuck >10 s), `DeferredSavedDataEncodes`, docs §14 |
| Memory growth | Region map: `RegionizedServer.evictIdleRegions` / `evictIdleGlobalRegionsAtQuiesce`, `ThreadedRegionizer.evictIdle` (docs §17). Bounded backlogs: `BoundedPendingQueue`. Island state lives on `PIsland`. Check routed FIFOs: `AdvancementBuffer.ROUTED`, `IslandCommands` routed batch, `AsyncIslandPlayerTicks` Main ingress. |
| Island merge/split surprises | `IslandRegistry.applyRebuildCells` (survivor rules), `RegionizedServer.rebuildIslandsBody`, `regions debug islandsummary` |
| A config key | `C/config/FabroliaConfig.java` (static field, read directly). The file is `config/fabrolia.toml`, written by the loader's `FabroliaConfig` (Fabric: flat `ENTRIES`; NeoForge: `ModConfigSpec`). Runtime: `/fabrolia config`. |
| A `/fabrolia ...` command | `C/commands/FabroliaCommand.java` → `RegionCommand`, `StatsCommand`, `ConfigCommand`, `FunctionsCommand`. Permissions: `C/platform/PlatformPermission.java`. |
| Startup / shutdown order | `FabroliaFabric.onInitialize` / `FabroliaNeoForge`: `ParallelProcessor.setupThreadPool`, `RegionScheduler.start`, `ParallelProcessor.stopServerRuntime` |
| Something must run "on the owner" from outside | `C/api/FabroliaIslandApi.java` (`executeOnOwner`, `causalHandoff`), `CommandShards.enqueueIslandTask` |

## 5. Conventions for editing

### Fail-stop helpers
All are in `C/regions/RegionScheduler.java`; the pool-side twins are in `RegionWorkerPool`. A failure on any thread is generation-fatal. The first Throwable wins, and the server stops rather than continuing in a torn state. Do not catch-and-continue.

| Helper | Use when |
|---|---|
| `runSuccessBodyIfHealthy(Runnable)` / `callSuccessBooleanIfHealthy` | Running any fallible stage, such as a vanilla sub-phase or a hook body. It enters the body only if no fatal has won first. It never runs the body while holding the lifecycle monitor. |
| `commitSuccessIfHealthy(Runnable)` | Publishing a prepared success: only fixed, non-blocking field/atomic writes. Do fallible queue publication before it. `commitSuccessIfHealthy(() -> {})` is the post-cleanup success cut. |
| `publishFatalAndSelectWinner(Throwable)` | In a catch on Main: publish the failure and get the authoritative winner to rethrow. A concurrent earlier winner stays primary. |
| `throwWorkerFailureIfAny()` | At poll points (Main task poll, connection tick, pump loops) to surface a worker failure unchanged. Not an admission gate: check-then-run is not atomic (`CommandEntityCatchEdgeWiringTest`). |

Canonical shape: `try { runSuccessBodyIfHealthy(body) } catch (t) { w = publishFatalAndSelectWinner(t); cleanup; commitSuccessIfHealthy(() -> {}); throw w; }`. Examples: `MinecraftServerMixin.fabrolia$regionalPlayerServerPhase`, `RegionizedServer.rebuildIslandsIfDirty`.

### Thread checks
- Physical Main: `RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)`. It is NOT the same as `server.isSameThread()`, which is also true on the V2 actor and during A5 packet replay.
- Worker: `Thread.currentThread() instanceof RegionWorkerThread w`, then `w.currentRegion` / `w.currentDomain`. `currentRegion == null` on a worker means a unit coordinator. Helper: `RegionScheduler.isRegionWorker`.
- Any Fabrolia execution thread: `ParallelProcessor.isServerExecutionThread`. Player ownership: `PlayerTurnOwnership.currentThreadOwns`. Logical actor: `RegionalPlayerEntityTicksV2.inExactPlayerActor`.
- Never block a worker on a Main task: `RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates`.

### Lock / claim order (stated in code)
- `Region.java`: admission lock "region -> pool". The evictor, Main only: region → neighbour → pool (`RegionScheduler.java`).
- `RegionScheduler.java`: `LIFECYCLE_LOCK` → `RegionWorkerPool` lifecycle monitor. Never hold `LIFECYCLE_LOCK` while joining.
- `EpochDomain.java`: level topology READ, then the domain READ. The WRITE side never takes the level lock.
- `RegionizedServer.java` (`globalArrivalsLock`): topology read → this monitor → island queue / leaf tracker. Never wait or mutate the world under them.
- `TickDomains.java` / `TickRepair.java`: "GLOBAL LOCK ORDER: block lane first, fluid second".
- `BlockEntityTickerAccess.java`: acquire after graph/region locks, and never while running ticker bodies.
- `NetherPortalTransfers.java`: claims → reservation monitor → guard.
- `PoiStripes.java`: at most one stripe at a time.
- `FabroliaLocks.itemTickLaneOr`: the outer serial lane comes before either endpoint guard.
- `IslandCommands.java`: every Main claim spin must keep servicing chunk executors, or you get an ABBA deadlock.
- `ParallelProcessor.java`: never drain the `MinecraftServer` task queue from a barrier.
- `ChunkDelivery.java`: the sender/Main never acquires an island claim.

### Mixins
- Class name: `<VanillaClass>Mixin` (`*Accessor` / `*Invoker` for duck interfaces). Integration mixins are named `FabroliaC2me*`, `FabroliaLithium*` or `FabroliaVmp*`.
- Handler, `@Unique` field and duck-method names use the `fabrolia$` prefix. A few legacy handlers lack it, e.g. `ServerLevelMixin.overwriteEntityTicking`, `ChunkHolderMixin.wrapBroadcastChanges` and `LevelTicksMixin.wrapAddContainer`.
- Mixins must not declare non-private static methods (`T/mixin/MixinStaticMethodVisibilityTest`).
- `@Shadow` field initializers must not write vanilla-owned fields (`T/mixin/MixinShadowStateOwnershipTest`).

### Tests (`common/`, JUnit 5; `./gradlew :common:test`)
- **Pure unit / model tests** exercise package-private classes directly. Examples: `IslandPlayerTurnSessionTest`, `ConnectionOutboxTest`, `SpawnCapLanesTest`, `T/save/AsyncSaveWriterTest`.
- **Source-text "wiring" tests** read `src/main/java/...` with `Files.readString` and assert exact strings or call order. Each test has its own private `source()` / `section()` helper. A refactor that renames, reorders or moves a pinned call fails these tests, so update them deliberately and keep the asserted invariant. Main ones:
  - `ResidualFailStopWiringTest`, `VanillaFailurePropagationWiringTest`, `SuccessTailFailStopWiringTest`, `WorldTailFailStopWiringTest`, `MainExecutorFailStopWiringTest`, `CommandMainPublicationFailStopWiringTest` (fail-stop shape)
  - `RegionizedSuccessReceiptWiringTest`, `IslandBarrierWatchdogTest` (RegionizedServer, RegionWorkerPool)
  - `ChunkDeliverySuccessLinearizationWiringTest`, `IslandBlockPublicationWiringTest` (unit tail order)
  - `CommandEntityCatchEdgeWiringTest`, `FunctionReloadTransactionWiringTest`, `ScheduledFunctionFenceScopeTest`
  - `TickContainerWriteShapedApiTest`, `CausalScheduledTickIndexWiringTest`
  - `T/parallelised/utils/PearlLifecycleWiringTest`, `PlayerTickExperimentRemovalTest` (config sources)
- **Bytecode / jar tests** use ASM to read compiled classes, upstream jars or the mixin JSON. Examples: `T/mixin/utils/SynchronisePluginNavExemptionTest`, `T/mixin/utils/LithiumAiTaskCancellationTest`, `T/mixin/c2me/C2meDirectThreadConsumerWiringTest`, `T/mixin/c2me/C2meServerLevelActorThreadWiringTest`, `T/mixin/lithium/LithiumLevelActorThreadWiringTest`, `T/mixin/vmp/VmpPlayerTrackingLockWiringTest`, `PlayerTickV2WiringTest`.
- `FullModpackMixinExportVerifierTest` checks the post-composition mixin export of the real modpack. It is skipped unless `-Dfabrolia.mixinExportRoot` is set; the dedicated task is `:common:verifyFullModpackMixinExport`. `fabric/build.gradle` also runs `verifyPlayerTickArchitecture` on `build`.
- Test helper/fixture files that are not tests: `TestDedicatedServer`, `TestPlayerTracker`, `FunctionPlanFixture`, and the c2me stubs under `common/src/test/java/com/ishland/c2me/`.

## 6. `C/regions/` package index (grouped by topic)

All 153 files are listed here, grouped by topic. Each name is `C/regions/<Name>.java`.

- **Scheduler core:**
  - Scheduling and workers: `RegionScheduler`, `RegionWorkerPool`, `RegionWorkerThread`, `RegionizedServer`, `RegionizedTaskQueue`, `RegionTaskFanIn`, `RegionTickHooks`, `RegionTickingAccess`
  - Regions and grid: `Region`, `RegionState`, `RegionGrid`, `RegionKeyed`, `ChunkRegionKeyed`, `ThreadedRegionizer`, `ColorGate`, `EpochDomain`, `LevelAccess`
  - Guards and locks: `CrossRegionGuard`, `RegionAccessDetector`, `DetectorEpochHolder`, `FabroliaLocks`, `OwnerBusyException`, `RegionMainBlockingWaitGuard`
- **Islands, time, metrics:**
  - Islands and clocks: `IslandRegistry`, `IslandTickUnit`, `IslandUnitAdmission`, `IslandClock`, `RawGameTime`, `TemporalControl`
  - Views: `CompletedSimulationView`, `CompletedChunkLevels`
  - Metrics and logging: `IslandMetrics`, `TpsHud`, `TpsWindow`, `CarpetTpsLines`, `FlightRecorder`, `ThrottledLog`
- **Entities:** `EntityTickLease`, `EntityBarrierWatchdog`, `EntityResidencyAccess`, `AcceptedTickAccess`, `NavRecompute`, `VibrationDomains`, `RedstoneTorchHistory`
- **Tracking:** `IslandEntityTracking`, `IslandTrackedEntityAccess`, `IslandPendingPairing`, `TrackedEntityPassAccess`, `TrackingViewerPass`, `TrackerIndexChanges`, `TrackerTopologySnapshot`, `TrackingPublicationBinding`, `TrackingTopologyAdmissionGate`, `RegionTickingTrackerBind`, `VmpTrackerIndexApply`, `VmpTrackingFanoutGate`
- **Players:**
  - Player ticks and ownership: `AsyncIslandPlayerTicks`, `AsyncIslandPlayerAccess`, `AsyncIslandPlayerLifecycle`, `IslandPlayerTurnSession`, `PlayerTurnOwnership`, `RegionalPlayerEntityTicksV2`, `PlayerTickActor`, `PlayerTickPhase`, `PlayerTickPhaseStamp`, `PlayerTickKnownModEnvelope`
  - Movement: `PlayerMoveLockPolicy`, `PlayerMoveReadSnapshot`
  - Listeners and Carpet: `AsyncIslandListenerAccess`, `CarpetPlayerMutationRouter`, `FabroliaSenderPlayer`
- **Packets / connection:** `AsyncIslandPacketPolicy`, `AsyncIslandPacketContinuations`, `ConnectionOutbox`, `FabroliaConnectionEventLoop`, `PassiveConnectionLoops` (fake-player EmbeddedChannel queues, run by every Main pool fixed point)
- **Chunks:**
  - Delivery: `ChunkDelivery`, `ChunkDeliveryCandidates`, `ChunkBatchAckReplay`, `FabroliaFullChunkPublisher`, `FabroliaChunkForgetPublisher`
  - Chunk access and writes: `ChunkBridgeWait`, `ChunkWriteEnqueueFence`, `FabroliaWriteGen`, `WorkerChunkCache`, `C2meStrandHeal`
  - Tickets: `TicketLockBind`
- **World passes:**
  - Scheduled ticks: `IslandScheduledTicks`, `ScheduledTickIslandAdmission`, `CausalScheduledTickIndex`, `FabroliaTickDomain`, `TickDomains`, `LevelTicksIslandAccess`
  - Tick inspection and repair: `TickInspector`, `TickRepair`, `TickRepairRun`, `TickRepairNbt`
  - Block entities: `IslandBlockEntityTicks`, `BlockEntityTickerAccess`, `BlockEntityTickerList`, `BlockEntityTickerPositionIndex`, `BlockEntitySleepDomains`, `BlockEntityIndexRetention`, `BoundedPendingQueue`, `HopperClockHeal`
  - Block events and updates: `IslandBlockEvents`, `BlockEventQueueAccess`, `IslandBlockUpdateAccess`, `GlobalBlockUpdateDrain`, `BlockTickingPassAccess`
  - Chunk pass and spawning: `IslandChunkPassAccess`, `NaturalSpawnChunks`, `SpawnCapLanes`
  - Raids: `RaidIslandDispatch`, `RaidOwnership`, `RaidOwnerAccess`, `RaidRegistryAccess`
  - POI: `PoiPublicationFence`
- **Portals / cross-dim:** `NetherPortalTransfers`, `NetherSourceCohorts`, `NetherPortalPreparation`, `NetherPortalPlayerAccess`, `PortalBorderSnapshot`, `PortalStuckProbe`, `CrossDimArrivals`, `CrossDimLinks`, `CrossDimProjection`, `ArrivalReleaseRule`, `EndPlatformHandoff`
- **Commands / functions:**
  - Commands and fences: `IslandCommands`, `CommandShards`, `ConsoleCommandFenceScope`, `RconCommandFenceScope`, `PlayerCommandFenceScope`
  - Function planning: `FunctionAnalyzer`, `FunctionPlanner`, `FunctionPlan`, `FunctionPlans`, `FunctionIR`, `FunctionLibraryAnalysisAccess`, `FunctionEffects`, `FunctionPredicateEffects`, `FunctionSelectorOptions`
  - Function execution: `FunctionPartition`, `FunctionPassMark`, `FunctionPlayerSnapshot`, `FunctionReadOnlyScoreAccess`, `FunctionFenceDiag`, `IslandFunctionHead`, `IslandFunctionScope`
- **Saves:** `IslandSaveGroup`
- **Diagnostics:** `RegionViz`
