## Unreleased

### Minecraft 26.3

- Ported to Minecraft 26.3 (Java 25): Fabric Loader 0.19.5, Fabric API
  0.161.0+26.3, NeoForge 26.3.0.38-beta (FML 12: the config is registered as
  `ModConfig.Type.LOCAL`, the renamed `COMMON`), Loom 1.17, Gradle 9.6.0.
  Audited against Lithium mc26.3-0.26.2, C2ME 0.4.2-alpha.0.88+26.3, VMP
  0.2.0+beta.7.238+26.3 and Carpet 26.3+v260915.
- Natural spawning follows 26.3: spawn costs come from the
  `minecraft:natural_mob_spawns` environment attribute at the full-resolution
  biome (plus the dimension overlay); the quart-biome cost cache is gone.
  `inhabitedTime` gains 1 per chunk pass on both lanes, as in 26.3.
- New 26.3 packet surface: `CHANGE_DESTROY_DIRECTION` is a destroy action
  (same island ownership and sequence check as the other three);
  `ServerboundPunchPacket` replaces the swing packet on the island lane;
  command suggestions (now processed by `ServerCommandSuggestionsProvider`
  from the listener tick) are parsed on Main.
- 26.3 lets spectators walk through a clicked portal. A spectator's block-use
  packet runs under the all-island fence, and a spectator never generates an
  exit portal (vanilla's `getExitPortal` rule) in the async Nether transfer.
  Portal teleports go through `Entity#teleportToPortalDestination`.
- `/data … from|string|compute` lines are no longer treated as island-local
  (they read outside the target block).
- `fabrolia tickrepair` datafixes unloaded chunk NBT before classifying it
  (26.3 saves block states as `id`/`properties`).
- C2ME 0.88: the mid-tick hook is applied only when C2ME's own mid-tick
  chunk tasks are enabled (off by default in this build), and the exact
  player actor passes C2ME's new "Async update chunks" check. With C2ME on
  26.3, 0.4.2-alpha.0.88 or later is required (earlier 26.3 builds lack that
  check); on Fabric this is declared in `breaks`.

### Changed

- Async islands: natural spawning, thunder and random/fluid block ticks for
  island-owned chunks now run inside the island's own tick unit at the
  island's cadence on its logical clock (vanilla's chunkSource.tick slot:
  after the island's scheduled ticks and raids, before its block events).
  Main no longer arms island regions once per Main tick (the ArmGate is
  gone) and no longer builds the level-wide spawn census; it censuses and
  ticks only chunks with no island owner. The global mob cap is the sum of
  every lane's published census (`SpawnCapLanes`), so a saturated island
  blocks the others exactly like vanilla's level total. Deviations from
  vanilla, all bounded: the spawn-potential calculator is island-local
  (other islands' charges are ≥256 blocks away); `inhabitedTime` and the
  400-tick persistent-category phase follow island time instead of wall
  time; another lane's census contribution can be up to one of that lane's
  periods old; a chunk whose owner changes between two islands at a
  topology rebuild can miss or repeat at most one chunk cycle. Full table:
  docs/ASYNC-ISLANDS.md §13.
- Main takes one island claim per drain instead of one per entity for its
  Main-owned entity ticks (wither, dragon, player fallbacks) and
  Main tracker publications, and no longer parks for them inside the entity
  barrier: idle islands are served at once, busy ones stay queued (same order
  per island), and once per level tick Main waits at most one running unit
  for islands whose work is older than 50 ms or holds a player tick. Before,
  Main parked behind every island's running unit per entity (33-60 % of Main
  on the production profiles) and each wait made that island yield a unit.
  Fixture (100 bots, 4000 item frames, 4000 zombies): Main 23.9 -> 18.4
  ms/tick, islands 20 TPS in both. `-Dfabrolia.mainClaimBatching=false`
  restores the per-entity drains; `flightrec status` shows claim counts,
  contention, wait time and Main's average tick time.
- Deferred advancement predicates are evaluated in parallel on the tick pool
  while Main holds the award fence (one task per player, awards applied
  serially in the original order, later pendings re-evaluated after a real
  grant). Island players' deferred awards, frameless Main awards and the
  `#minecraft:tick` functions share one fence window at the function tick
  head instead of quiescing every island once per level and once more for
  the functions. Island players' awards therefore apply at the next tick
  head, before that tick's functions - the same point where vanilla's tick
  functions first observe an award made during the previous tick's level
  pass.
- Region workers: removing or re-pairing a viewer on a tracked entity is
  O(1) (identity-keyed ordered map instead of a list scan), and the
  portal-custody check on every entity push/collision no longer takes a
  global monitor when the entity is not in a portal transfer.
- Item frames tick and publish their tracker on their island again instead
  of on Main. A framed map's shared data is guarded per map, the saved-data
  cache is concurrent and map ids are allocated under a lock.

- Roll the region runtime back to the stable 0.2.38 behavior (the compiled
  reference build). The uncommitted "native admission" rewrite that routed
  most entity, block-entity and player work through the Main thread is
  removed: 100-player load runs at 20 TPS again (0.5 TPS on the rewrite).
  The rewrite's sources remain on branch `wip-gpt-0.2.38-admission-layer`.

### Fixed

- Neighbour updates (redstone, block updates): Fabrolia replaced vanilla's
  `CollectingNeighborUpdater.addAndRun` body with its own per-thread copy and
  never called the original, so other mods' injections into it (e.g. Rustmi
  Nalim's cascade `drainEnter`/`drainExit`) silently never ran, and the
  vanilla "Too many chained neighbor updates" message was lost. Each thread
  now drives its own unmodified vanilla updater; the level's own instance
  only carries the debug listener. Main no longer pays a ThreadLocal lookup.
  Carpet's `updateSuppressionBlock` rule still has no effect, as before: it
  resets the count on the level's own updater, which runs no updates.
- With Lithium, entity pushing (`LivingEntity.pushEntities`) visited
  candidates in identity-hash order instead of vanilla's insertion order.
  Lithium's class-group lists are now insertion-ordered copy-on-write
  snapshots (lock-free, faster to iterate than the previous concurrent set).
  `Level.getPushableEntities` is no longer replaced wholesale: only its
  inner `getEntities` call is routed, so other injections into it run.
- With Rustmi Nalim's collision fast path, a boat, shulker or happy ghast
  that teleported (or moved more than 4 blocks within a tick, or was wider
  than 8 blocks via the scale attribute) could be missed by the
  hard-collider check until the next tick, letting entities pass through it
  or lose their support. The index is now maintained exactly from
  `Entity.setBoundingBox` instead of a per-tick snapshot; Main no longer
  rebuilds it every level tick.
- NeoForge: the server did not start. NeoForge replaces the sleep clock
  advance in `ServerLevel.tick` (`ClockAdjustment.apply` instead of
  `moveToTimeMarker`), so that stage is now admitted by a platform mixin on
  each loader; and the bundled `fabrolia-api` jar (now 1.0.4) is marked
  `FMLModType: GAMELIBRARY`, so FML loads it on the game layer (it was a
  plugin-layer library that could not see Minecraft classes).
- Server crash "Chunk not there when requested: Unloaded chunk" when an
  island tick needed a chunk that was not loaded yet (reproduced: a player's
  first tick after a Nether-portal crossing reads its old supporting block
  position in the new dimension, and vanilla loads that chunk). Vanilla's
  1-tick request ticket expired while Main kept ticking; island requests now
  hold a non-expiring load ticket until the chunk resolves, then leave
  vanilla's request ticket armed.
- Server crash (`ArrayIndexOutOfBoundsException` in `ChunkMap.setChunkUnsaved`)
  when an island marked a chunk unsaved while Main (vanilla or C2ME's idle
  autosave) iterated the eager-save set. The set's iterator is now safe
  against concurrent adds; a chunk re-dirtied during its save is re-queued.
- Watchdog crash (Main parked forever in `IslandCommands.mainClaim` from an
  attack/interact packet): a batched Main drain ran a player's Main fallback
  under that island's claim, the fallback's completion drained the player's
  packets and a packet claimed the same island again. Completions of batched
  Main entity bodies now run after the island claim is released.
- Islands no longer stop while Main is stalled (reported: an island at 0 TPS
  for about a minute during heavy world generation, nothing in the log). A
  non-blocking Main drain that found a busy island with old work raised the
  island's claim interest and left it raised; an island with the flag up runs
  no unit, so it waited until Main came back to claim it. Interest is now
  raised only while Main is actually waiting for the island.
- A player tick queued for Main no longer waits for its island to go idle:
  it is caught at the level-tick tail, so the fallback cannot repeat every
  island unit until the player's owed entity FIFO overflows ("A5 owed Main
  entity ordinal FIFO exceeded 4096 entries"). Main never parks inside the
  entity-barrier pump, where it may not run routed commands. An interaction
  packet reached from a batched drain inherits the island claim Main already
  holds, and a re-entrant `mainClaim` now fails loudly instead of parking
  Main forever.
- "raid tick requires its quiescent island coordinator" crash: a raid whose
  center chunk was not loaded (a saved raid after restart, or a player coming
  back into range) was dispatched to the island owning that cell in the
  committed topology, but the ownership check only knew cells with a loaded
  region and refused it. Region-less cells now resolve through the same
  committed topology; a remaining refusal names the failing condition.

- Mobs, items and carts crossing a Nether portal no longer wait for Main.
  The worker teleported them under both island claims but the arrival stayed
  untickable and untracked until Main's once-per-tick metadata tail drained
  (30-45 ms at 20 TPS; the whole Main stall under lag - 1.94 s measured under
  a 2 s Main park, which broke portal farms). A transfer with no player and no
  transmitting waypoint now finishes on the worker right after the vanilla
  teleport (0-7 ms under the same park). Player transfers keep the ordered
  Main tail. The portal preparation POI fence now tracks only `nether_portal`
  POI callbacks, so bed/job-site/hive churn cannot make a transfer wait for
  Main's preparation pass. `/fabrolia regions debug flightrec status` reports
  transfer counts/latency; `-Dfabrolia.netherTransferTrace=true` logs each.
- Region workers no longer tick an entity whose own chunk has already left
  FULL (vanilla removes it from the tick list synchronously; here the removal
  arrives asynchronously from C2ME). Ticking it forced a bridged chunk reload
  while the unit held its island claim and Main waited for that claim inside
  the per-tick function fence, a livelock of minutes per chunk under load.
- Main's fence spin pumps the chunk executor like vanilla managedBlock
  (bounded per call) instead of one task per 20 microsecond iteration.
- Entity status callbacks no longer walk the whole deferred queue per call;
  the full per-chunk pass runs once per tick.
- A sleeping entity whose bed lies outside its island (teleported villager)
  ticks on Main under the global fence, so vanilla's remote bed write on
  waking no longer trips the cross-domain write detector and crashes.
- Deferred entity-chunk status transitions no longer block head-of-line: one
  island whose claim Main cannot take stalled every other island's chunk
  loading (entities never marked loaded, scheduled ticks held, chunk delivery
  stuck) and paused all entity unloads level-wide. Entries are now deferred
  per chunk while the rest of the queue drains.
- An island that chains units back-to-back no longer starves its OWN chunks'
  entity status transitions (Main's single non-blocking claim attempt per
  tick almost never won): after 20 consecutive deferred ticks Main announces
  claim interest, the unit yields exactly as for a blocking Main claim, the
  transition applies and the interest is dropped
  (`[entity-status] escalating claim ...` at INFO).
- Island scheduled-tick holds that do not heal are reported loudly instead of
  degraded: every 200 consecutive holds at one logical tick the unit logs
  SCHEDULED-HOLD-STUCK with the refused chunk's readiness legs, and keeps
  holding (the periodic SCHEDULED-HOLD line carries the same legs).
- A due redstone (pulse-sensitive) scheduled tick in a chunk on the
  simulation-distance edge (block-ticking but not entity-ticking) no longer
  holds the whole island: vanilla never runs such a container either, so it
  is skipped; the island now waits only for chunks the distance manager
  already places in entity-ticking range. A stationary player near such a
  contraption saw their island at 0 TPS for minutes.
- Worldgen no longer crashes the server with "success-only body refused by a
  closed or exclusive pool generation" when a structure piece or spawn
  finalizer mounts entities that are not yet published to the level (ocean
  ruin drowned, jockeys): an unpublished, player-free ride component runs
  its ride mutation directly instead of through the lifecycle fence.
- Nether portal creation no longer fails with "generation returned before its
  POI publication" when a destination POI publication is queued during
  generation; preparation re-requests Main until both levels' publications
  land, and POI callbacks issued during generation are queued in order.
- Trackers and viewer pairing for an entity arriving through a Nether portal
  wait for its arrival metadata to settle instead of publishing a stale state.
- Player save entered while Main already holds the island claim no longer
  spins forever ("mainClaim stuck").
- A packet body whose player session changed while waiting for its island
  claim is skipped instead of running against the replaced player/listener.
- Physical-Main disconnect no longer reads the server through player/level
  state.

