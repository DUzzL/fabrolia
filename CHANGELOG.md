## Unreleased

### Fixes

- Crash fix (production 2026-10-06, `ArrayIndexOutOfBoundsException` in
  `LevelChunk.getBlockEntity` from `ChunkHolder.broadcastChanges`): Main's
  end-of-chunk-tick block-update broadcast also published chunks of islands
  that were running their tick, reading the chunk's block-entity map while the
  island's pistons rewrote it. Main now publishes an island chunk only while it
  holds that island (non-blocking, yielding to a queued island tick); a busy
  island publishes the chunk itself at the end of its tick, as it already does
  for its own changes. Stand with 225 piston clocks: 38,281 such concurrent
  reads in 2.5 min before, 0 after; clients receive the same piston events and
  half as many section-update packets (an island chunk was published twice per
  island tick, now once, as in vanilla). Block changes Main makes in a busy
  island's chunk (commands, functions) reach other players at that island's
  next tick end instead of Main's chunk tick; a Main-side player interaction
  publishes its island's changes before the player's block-change ACK.
- Freeze fix (permanent Main spin after a console/RCON `/tp` of a Carpet bot,
  e.g. `tp <bot> 6000 -40 6000` between two ticks): a Carpet fake player's
  connection is a Netty `EmbeddedChannel` whose event loop runs queued tasks
  only when the channel is next operated on, which vanilla does at the end of
  `tickChildren` (`resumeFlushing`). The bot's chunk-forget publications from
  the teleport were pool obligations queued on that loop, and the next tick's
  exact player turn waited for them before the only thread that could run them
  got there. Every Main pool fixed point (exact/preparation turn, stop) now runs
  the queues of such passive channels itself once no worker step is live
  (`PassiveConnectionLoops`), which is what vanilla's next flush would run; the
  fail-stop sweep runs them to cancel their permits. A bot logout
  (`player <bot> kill`) used the same path. Stand (production mod set, RCON
  `/tp` of a Carpet bot): with `asyncIslands = false` the 0.2.38 jar froze in
  2 of 2 attempts, the fixed jar in 0 of 10; with `asyncIslands = true` neither
  froze (2 and 3 attempts). The bot ends up at the target, its old chunks unload.
- An exact player turn that cannot reach its fixed point now logs the pending
  obligation counters every 15 s (as pool stop already did) instead of
  spinning silently.
- Memory leak fix (production heap dump 2026-10-08, 19.4 h uptime: 379
  `ServerPlayer` objects for 25 online, ≈ 600 MB, 109 410 map holders): Lithium's
  `mixin.entity.framed_maps` makes a framed map's `tickCarriedBy` look only at the
  ticking player, so a player who left or died is never pruned from the
  `carriedByPlayers` of any map in an item frame (map-art walls) and keeps the
  whole old `ServerPlayer` alive. Fabrolia now disables that Lithium option
  (`lithium:options` in both loader manifests): vanilla prunes them again.
  Stand (map in two frames, 20 bot logins/logouts, one player online): vanilla
  + Carpet 1 holder; + Lithium 22; production mods without Fabrolia 20; with
  this jar 1–2.

### Whole-mod review (memory, CPU, vanilla parity)

Memory (stand live heap after full GC: 2.19 GB → 1.62 GB; ≈ 1 GB less expected
at production's ~20k loaded chunks):
- Each loaded chunk's game-event listener table was a 32-stripe concurrent map
  (~38 KB per chunk); it is now a small copy-on-write map. Chunk-section
  palettes keep their read/write lock state in two fields instead of a
  `ReentrantReadWriteLock` (~120 B) per palette.
- `Int2ObjectConcurrentHashMap` creates its stripes on first write (an empty
  map is ~0.7 KB instead of 20–38 KB); `ConcurrentList` allocates no array
  until the first add.
- Datapack function parse IR is released once the function plan is built
  (~15 MB with BACAP).

CPU:
- Held maps: an island player's map is ticked on its own island when the
  island owns every chunk the redraw reads. Before, every map in an island
  player's inventory paused all islands each tick for a Main-side update
  (production ≈ 6 ms/tick of Main; stand with 40 map holders: Main 13.5 → 3.0
  ms/tick). The pixels now reach the client in the same tick, as in vanilla.
- Region workers keep a small exact FULL-chunk cache in front of C2ME's
  off-thread `getChunk` shortcut (per worker, cleared every step, invalidated
  by a per-position seqlock around C2ME's holder status transitions). Item-
  heavy stand: `ServerChunkCache.getChunk` on workers 89.5 → 21.5 ms/s, inside
  `ItemEntity.tick` 25.2 → 3.8 ms/s. Only active with C2ME.
- Packets sent from island workers reach Netty as one event-loop task per
  connection and worker step instead of one wakeup per packet (stand: worker
  eventFdWrite 1.05 → 0.18 ms/tick, Netty task time 4.7 → 1.9 ms/tick).
  Per-connection order, flush flags and send listeners are unchanged; Main
  sends, flushes, chunk/Forget tasks, close and protocol switches share the
  same per-connection FIFO.
- Island entity-tracking flush: island ownership is memoized per flush,
  per-entity type checks are cached on the entity, and the arrival-pending
  and deferred-player checks have zero-cost fast paths (stand: ~66 → ~4 ms/s
  of Fabrolia overhead in the flush).
- Entity AABB queries no longer copy the section-id range into a new skip
  list per section column (production 2.7 ms/tick); `ConcurrentList`
  iterators share one snapshot until the next mutation.
- Brain behaviour start checks the active activity once per (priority,
  activity) entry; mob path start/stop no longer copies the level's
  navigation list under a shared lock; level random draws on workers skip a
  ThreadLocal and a hash lookup; game-event dispatch lookups take no lock;
  island random ticks read eligibility from one snapshot per pass;
  spawn-candidate chunks beyond 128 blocks of every player are dropped before
  the holder lookup; island function heads build their step list once per
  arming.
- Chunk sending: a chunk waiting for its island's snapshot no longer adopts
  the older snapshot the sender had just rejected (three such false failures
  forced a Main-side rebuild that paused the island; production 0.56 ms/tick
  of Main).
- `/fabrolia regions debug spawnrate` starts recording on its first call
  (the recorder ran on every spawn pass and allocated 4 MiB at startup).

Vanilla parity restored:
- Brain mobs start the remaining behaviours of an entry when a behaviour
  changes the active activity mid-start, as vanilla does.
- Trial spawners detect players by vanilla line of sight again (the 20-tick
  per-block line-of-sight cache is removed).
- Attribute modifiers keep vanilla's maps, so values are summed in vanilla's
  order (ConcurrentHashMap order could differ in the last bit).
- Entities take fall damage when landing slowly into a climbable block, as in
  vanilla (an upstream mixin cancelled it).

Fixes and leaks:
- Vanilla scheduled-tick re-indexing through `Long2LongMap` entry `setValue`
  no longer throws `UnsupportedOperationException`.
- With Lithium, a game-event listener (sculk sensor, allay, warden …)
  registered while another thread removed the chunk's last listener could
  land in a section map Lithium was discarding and never receive events until
  the chunk reloaded. Registry get-or-create and removal now run under the
  chunk's monitor.
- Mobs and players that got a spawn-census purge strike and then died,
  despawned, unloaded or left the dimension are no longer kept referenced
  until the level unloads.
- Nether transfer sources of retired islands are dropped once idle; chunk
  delivery drops snapshots that can never be reused (chunk unloaded or
  changed) once a second; a respawned player's delivery state follows the new
  player; the chunk bridge's soft-null memo prunes expired entries; the fox
  double-breed guard expires its entries; finished island sprints leave the
  session list.
- Removed dead weight: the unread per-region entity-ticking holder lists
  (they could keep unloaded chunk holders alive), a write-only thread
  registry, a duplicate mixin, and a per-entity-tick commit lambda.

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

- A raid no island owns whose center chunk is not loaded (for example one left
  behind by an unloaded raid farm; vanilla keeps such raids forever, inactive)
  no longer makes Main pause every island each tick: its vanilla tick is a
  no-op and is skipped. Raids Main still has to tick share one pause per tick
  instead of one each. This cost production Main about 10 ms/tick, and the
  pause also held back every island. An island no longer re-arms the raid-area
  ticket of an inactive raid, so such a raid stays parked until its center
  loads by itself, as in vanilla, instead of being revived by a nearby
  island.
- Carpet `/log tps`: a player standing on an async island now sees two lines
  in the tab footer, `Main │ TPS: … MSPT: …` and `Local │ TPS: … MSPT: …` (their
  island), in Carpet's style. Both TPS values are measured ticks per second;
  Carpet's own figure is derived from MSPT and reads 20.0 whenever MSPT stays
  under 50 ms. Players on Main's lane keep Carpet's line unchanged. The two lines'
  columns are padded to the same pixel widths in vanilla's default font, so they
  line up in the centred tab footer; values are right-aligned.
- Idle global regions are reclaimed inside the topology-rebuild pause (island
  core and halo regions are kept). The region map no longer grows with every
  cell ever visited, which made Main's per-tick global-lane sweep, the
  datapack Main-remainder scan and rebuild snapshots slower with uptime
  (production: entity dispatch 6.5 ms/tick on Main).
- An island core cell losing its last loaded chunk no longer forces an
  all-island topology rebuild; the change is folded into the next real
  rebuild or applied after 5 s, and repopulating before then costs nothing.
- Entity tracking on Main: queued tracker add/remove are applied on the tick
  pool, and the per-tick tracker snapshot runs over a dense mirror instead of
  copying VMP's map (stand: Main tracker tick 1.56 -> 1.20 ms/tick).
- A rider's consecutive vehicle/listener packets on Main share one island
  claim per drain instead of one claim each.
- Region workers: an island's chunk pass resolves its own cells once and
  takes its spawn census through a per-cell index of the mob ledger instead
  of filtering the whole level ledger with a lookup per key (production:
  ~15 ms/tick of map lookups and an 18 ms/tick census lane). The island's
  local mob cap uses vanilla's plain calculator and per-pack check.
- `ServerChunkCache.getChunk`: the Nether-portal read scope is a cheap HEAD
  check instead of a whole-method wrap (stand: 6.7 ms/tick of worker time).
  Island block-entity passes read block-ticking eligibility from one pinned
  snapshot. The rate pacer is event-driven instead of polling every 100 µs
  (production: 0.22 core).
- Main no longer waits at the end of each level's entity phase for a busy
  island's running unit before running Main-owned entity bodies (player
  fallbacks, unsupported or forced-main entities). The island gets a standing
  service request, pauses at its next unit boundary, and Main runs the work
  at its next safe point (barrier pump, level head or tail, post-tick
  boundary, idle wait). A request still standing after 5 s is logged with
  claim forensics. This wait cost Main 18 ms/tick on the 2026-10-03
  production profile and 42 ms/tick on the perf stand.
- The region liveness backstop (heal) checks only regions that can hold a
  pending entity barrier; the full region scan runs once per second (every
  250 ms while a barrier is late). Routine region completions no longer keep
  it at its 2 ms cadence (10-15 ms/tick of Main at 40 players).
- Main's interest in an island claim is a per-waiter count instead of a
  saved/restored flag: an interleaved wait can no longer leave an island
  stopped forever or let a behind island chain units past a waiting Main.
  Island units also yield to a pending global fence.
- Advancements are evaluated and awarded on the player's own thread when the
  criterion fires, as in vanilla. The global island pause they caused
  several times per tick (tick head, worker lane, each level's batch end) is
  gone; only reward work that cannot run there (non-owner-local reward
  functions, loot, chat announcements of island players) is applied by Main
  at the next tick head, and only then is the pause taken.
  `PlayerAdvancements.award/revoke` return vanilla's result on the owner and
  fail loudly on any other thread. `/fabrolia regions debug advstats` shows
  pause counts per site and routing counts per reason. The obsolete
  `enableFabroliaAdvancements` option is removed.
- Datapack `#minecraft:tick` roots and unconditional `schedule … 1t`
  self-rearming loops run per island at island cadence, from a plan built on
  vanilla's parse (`/fabrolia functions plan` shows each root's verdict and
  the blocking line; `/fabrolia functions fences` the per-tick fence causes).
  Only non-partitionable roots and lines that actually match an escalation
  take the global pause. On the production datapacks BACAP `tick_timer`,
  `track_stats:tick` and `armor_statues:tick` are partitioned. A pass runs
  only on the thread that owns each entity's tick: players ticked by Main
  (fallbacks, riding a Main-ticked vehicle, player ticks off) get their
  per-tick roots in their Main-sync body under the island claim, with no
  global fence unless a line escalates. Advancement rewards, loot and
  announcements raised inside a pass are routed to Main unless the exact
  owner-local proof holds. Region workers execute only functions the active
  plan proves owner-local, inside ownership guards; anything else fails
  loudly. Deviations (documented in docs/ASYNC-ISLANDS.md §16): island
  cadence for those roots, per-island order of escalated lines, ≤1 tick skew
  for reads of Main-only state.
- Entity tracking: the per-tick tracker pass no longer re-stages pairings
  whose vanilla outcome is unchanged (static item frames, idle mobs), takes
  one position snapshot per tracker, and lets Main work through the viewer
  batches instead of parking for the tick pool. Non-player tracker add/remove
  on region workers no longer waits for the VMP tracker lock (queued, applied
  at the next tracker tick; the immediate pairing still happens at add).
  Pairing of entities in loaded but non-entity-ticking chunks commits on their
  island instead of Main. Stand: Main `ChunkMap.tick` 4.5 -> 1.5 ms/tick.
- Autosave no longer stalls the tick on player files: player `.dat`,
  advancements, stats and `level.dat` are snapshotted on the owner and written
  by an ordered background writer (per-file FIFO, byte-identical output); the
  scoreboard is encoded in its own write task. `save-all flush`, `/stop` and
  logout still wait until the files are on disk; loads wait for that file's
  queued write. Advancements/stats JSON are replaced atomically. Stand:
  `save-all` Main time 425 -> 125 ms. An unfenced player save pass
  (autosave) holds Main's claim interest on every island with players for
  the whole pass, so it waits for each island's running unit at most once
  instead of once per player (57 -> 39 ms per autosave `saveAll`).
  Recommended production setting: ServerCore
  `features.autosave-interval-seconds: 300` (production runs 60).
- Region workers no longer block on Nether portal queues (per-source
  lock-free attempt gate, only queued sources are polled, bounded retry of a
  head that is not ready); island units close their entity-dispatch
  membership under a per-island creation gate instead of one level-wide lock;
  the per-block-entity ticking gate reads a shared copy-on-write paged view;
  barrier and dispatch waiters are woken instead of polling.
- MCFC (separate jar): compiled functions run only on the server thread;
  island threads use vanilla's interpreter. The score epoch and per-type
  entity index are thread-safe.
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
  per island) and are served at the island's next unit boundary (see the
  service requests above). Before,
  Main parked behind every island's running unit per entity (33-60 % of Main
  on the production profiles) and each wait made that island yield a unit.
  Fixture (100 bots, 4000 item frames, 4000 zombies): Main 23.9 -> 18.4
  ms/tick, islands 20 TPS in both. `-Dfabrolia.mainClaimBatching=false`
  restores the per-entity drains; `flightrec status` shows claim counts,
  contention, wait time and Main's average tick time.
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

- Fatal "Nether player entry lacks exact transfer authority" crash. Short
  region seals or claims raised by Main's heal or by a refused stale
  claimant could revoke a Nether transfer's already-held island authority
  mid-attempt; they now only refuse new admission.
- A unit coordinator's raid/lifecycle authority could read false while a
  stale region claimant briefly held an already-finished region, which
  crashed raid ticks or Nether lifecycle cancellation.
- Advancement work pending for a player who disconnects in the same tick is
  applied before the player's advancements are saved (it was lost), and
  deferred rewards of a respawned player go to the new player object.
- A late tracker cleanup could send a second entity-remove packet after the
  entity was already removed, hiding a respawned player or reloaded entity
  with the same id on that client.
- Revoke-and-rerun advancement counters (BACAP) keep every fire while their
  reward is pending.
- Server crash ("Exception in server tick loop") when another mod's packet
  handler threw, e.g. GroupChat 1.2.0 when a player pressed Tab on its
  `/gc player` command ("No such argument 'group' exists on this command").
  Vanilla logs such an exception ("Failed to handle packet …, suppressing
  error") and keeps running; with async islands Fabrolia stopped the server.
  Packet bodies now follow vanilla's `PacketProcessor` contract on islands
  and on Main, including inside Fabrolia's interaction claim wrappers.
  Failures raised by Fabrolia itself, `Error`s and out-of-memory reports
  still stop the server.
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

