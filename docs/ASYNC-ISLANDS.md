# Fabrolia async islands — independent per-island ticking

Status: persistent island identity and asynchronous island/player execution are
implemented. An island unit is a coordinator task on a region worker; each unit
retains its island claim, generation and local clock, and entity ordinals within
an island remain serial. Independent island roots run concurrently. A producer
that cannot run on its island owner (MAIN_SYNC entity bodies, routed commands,
Main-only listener work) retains its source claim while its exact Main fallback
completes.

Main-owned entity bodies (MAIN_SYNC) and Main tracker publications are drained
per island, not per entity (`RegionScheduler.IslandBuckets`): Main takes each
island's claim once for all of that island's queued work, runs the bucket in
queue order and releases. Completions of those bodies (an A5 fallback's
listener ingress may claim the same island) run after the release. In async
mode no Main-side MAIN_SYNC drain waits for a running unit: the entity-barrier
pump, the level head, the post-tick boundary and an idle-wait wake serve
islands that are idle now; at the level-tick tail a busy island whose work is
overdue (oldest entry older than 50 ms, or a queued player tick) gets a
standing service request (`PIsland.mainSyncWanted`, owned and reconciled by
`RegionScheduler.MainSyncService`): it stops at its next unit boundary, wakes
Main, and Main runs its bucket at the next safe point; the request ends after
the bucket's completion tails, or as soon as the island holds no queued work.
Blocking claim waiters (mainClaim, the global fence, complete drains,
topology/save quiesce) announce a per-island waiter COUNT
(`announceClaimInterest`/`withdrawClaimInterest`), never a saved/restored flag;
unit admission, chaining and the pacer yield on `PIsland.mainInterest()`.
Exact-turn, sprint and shutdown drains are complete (blocking). Islands never
observe each other within a tick, so reordering between islands is not
observable. Main's heal backstop scans only the dispatch members of domains
with a pending barrier and the not-done regions around them (island domains
only once their barrier is 50 ms old); the full region scan runs once per
second, or every 250 ms while a barrier is that late.

Nether transfers retain worker-owned endpoint claims with serialized Main
preparation (§5, A6). Execution remains configuration-gated; historical stage
descriptions retain their original design gates only where not superseded here.

## 0. Ground rules inherited from the current architecture

- Islands = connected components of populated 8×8-chunk cells with a **≥3-cell
  (≥24-chunk, ≥384-block) unloaded gap** between distinct islands (BFS radius-2
  merges anything closer). This gap is a topology and local-interaction
  constraint, not authority for arbitrary callbacks, global reads or foreign
  entity access. Those dependencies still require explicit admission.
- Region grid, color gate, per-region queues, island-partitioned scheduled/BE
  ticks and the persistent BE cell index retain their ordering and membership
  responsibilities. Async time determines WHEN a unit is eligible; actual claims
  and its Main-fallback dependencies determine WHO may execute.

### Current execution and advancement contract

Topology/save quiescence keeps new-unit admission closed while already-entered
units finish through routed Main work. Physical claims may be released for that
progress, but the admission intent is retained across retries and extended for
new island identities. Prior intents are restored exactly, including on failure.

Live async OFF and shutdown reach terminal quiescence before changing async
state or collapsing island clocks. Rebase waits may drain already-published
global/Main-safe dependencies, but not unrelated ready work; chunk-service
progress remains available.

No arbitrary entered-producer migration, transfer of JVM monitors/scoped context,
logical Main alias or foreign-thread world authority is implied.
`FabroliaIslandApi.causalHandoff` carries the exact parent's lifetime accounting,
not transferable world access. `executeOnOwner` queues callbacks when a foreign
caller lacks owner authority.

Advancements are evaluated and awarded on the thread that owns the player
(`AdvancementBuffer.trigger`, `PlayerTurnOwnership`): the player's own A5 turn, an
island worker of the island attached to the player's resting session (the
lifecycle-mutation authority), physical Main for a player no island session
holds (or under the island's claim / the global fence), the V2 actor, or Main
with no live island. At that point, vanilla's position inside
`SimpleCriterionTrigger.trigger`, every listener's matcher and `player:`
condition are evaluated, then each match is awarded synchronously.
`PlayerAdvancements.award/revoke/flushDirty` run with vanilla's return value on
the owner and throw anywhere else. A fire-time evaluation serializes a player
without its ender pearls (they may live on any island); a compare that
references them routes the call. Player NBT is computed once per trigger call.

Work the owner may not do goes to ONE Main FIFO (`AdvancementBuffer.ROUTED`),
per-player ordered:
- a trigger fired off the owner, or while that player has routed work
  outstanding: the matcher is resolved at fire time; the condition and award run
  at the drain;
- an award whose completion would run a reward the owner may not run. The
  award is routed whole, so its listener stays registered and revoke-and-rerun
  counters see every fire;
- the reward routing point (`AdvancementRewardRouting`, C2):
  - a reward function runs inline on the owner iff
    `FunctionPlans.ownerLocal(id)`;
  - loot grants are routed;
  - completion announcements broadcast inline only on physical Main.

Xp, recipes and progress packets stay owner-local.

Drain points:
- **Tick head** (`beginTickHeadWindow`, before `#minecraft:tick`): the only point
  that acquires the global fence, and only for a routed item that needs it. With
  tick functions queued it still takes the fence those functions share.
- **Level entity-batch end:** borrows a held fence or runs with no live island;
  never quiesces.
- **`PlayerList.remove` HEAD** (under the removal fence): applies the FIFO before
  the leaving player is saved, so no award is lost on disconnect.
- **Server stop.**

With nothing routed, no advancement site quiesces. Per-site quiesce
counts/time and per-reason route counts are in `/fabrolia regions debug advstats`.

**Cost:** Main-only native bodies and unknown dependencies still fall back to
physical Main. Independent clocks and concurrent admission do not establish a
throughput guarantee; historical benchmarks cannot prove one.

## 1. Identity and the transaction spine (A1 — SHIPPED)

`IslandRegistry`: stable island ids across topology rebuilds; explicit
MERGE/SPLIT/BORN/RETIRE events with deterministic survivor rules (merge: most
cells, tie → lowest id; split: largest fragment keeps identity, fragments
inherit the clock); self-check vs the BFS ground truth with reset-on-drift;
shadow deadline clocks (`logicalTick`, `lagNanos`, cost = Σ member-region
entity-phase EWMAs) and `/fabrolia regions debug islandsummary` diagnostics (per-island
cells/entities/cost/shadow-TPS/logical tick/offset/lag/age + event log).
Observable behavior: unchanged — this stage exists so every later stage has an
identity to hang state on and so the merge/split *frequency and shape* can be
measured on production workloads before anything flips.

## 2. Timing model (the clock-source table)

Three clocks, never mixed implicitly:

- **W (world clock)**: day/night, weather, celestial time, `advance_time`.
  Stays GLOBAL per level and real-time anchored (one authority advances it 1/50 ms
  regardless of island load — matching what players and clients perceive; a lagging
  base must not freeze the sun for everyone, and vanilla clients interpolate sky
  from packets anyway). `/time` reads/writes W.
- **L (island logical tick)**: each island's own monotonic counter, advanced
  once per island tick. ALL relative timers tick in L implicitly, because they
  decrement/increment once per tick of their owner (fuse--, age++, cooldown--).
- **G (global server tick counter)**: `server.getTickCount()` — becomes the
  scheduler heartbeat (max of all islands' pace targets); only infrastructure
  (autosave cadence, watchdog, mod global hooks) may use it.

Deadline-encoded state is the only hazard: values stored as **absolute times**
must be tagged with their clock and converted at ownership changes. Complete
inventory from the 26.2 sources:

| state | vanilla encoding | clock | transfer/merge rule |
|---|---|---|---|
| scheduled block/fluid ticks | absolute `triggerTick` | L(owner) | re-key by `Δ = L_new − L_old` at merge/handoff (Folia's redstone-time offset, applied at the per-island LevelTicks container — A3) |
| block entity counters (furnace progress, hopper `cooldownTime` absolute!) | hopper stores absolute game time | L | hopper cooldown re-keyed by Δ like scheduled ticks; furnace litTime etc. are relative — untouched |
| entity `tickCount`, age, item age, TNT fuse, invulnerability, fire, breeding/portal cooldowns, potion durations, item-use remaining | relative counters | L | no conversion — they tick when the owner ticks |
| despawn timers (`noActionTime`) | relative | L | none |
| AI memories with expiry (`ExpirableValue`) | relative ttl | L | none |
| villager schedules / brain activity windows | read W (day time) | W | none — W is global; schedule boundaries hit all islands at the same wall-clock moment |
| crop growth / random ticks | per-tick chance in owner's tick | L | none (a 10 TPS island grows crops at half wall-clock rate — the defined, documented semantic of a slow island; identical to what a globally-lagging vanilla server does today) |
| daylight/weather checks (`isBrightOutside`, thunder) | read W | W | none |
| `/schedule` datapack timers, TimerQueue | absolute W ticks | W | global timer queue runs on the W authority; function EXECUTION that targets island-owned state is dispatched to owners (A5). **Exception (§16):** an unconditional 1t self-rearming loop that the function plan partitions is an island-tick root — re-armed on W, executed per island at L (coalesced) |
| scoreboards/advancements/bossbars | event-driven | n/a | advancement criteria are evaluated and awarded on the player's owner thread at fire time; global reward work (non-owner-local functions, loot, announcements) uses the routed Main FIFO (§0) |
| portal cooldown (`portalCooldown`) | relative | L | none |
| active Nether portal charge | relative | L | advances with the entity owner; the accepted source-unit cohort transfers on a worker holding every source/destination owner claim |
| world border | W-anchored lerp | W | global |
| raids / Raid Omen | relative raid counters and effect duration | L(owner) | eligible expiry creates/extends the raid under exact island and POI authority; new raids are assigned immediately and tick once per owner unit; foreign ownership retains fenced Main compatibility |

Rule for every future mixin: reading "game time" during island execution
returns **L(owner)** via the worker's region context for relative-mechanic call
sites that vanilla uses as a monotonic per-tick counter (LevelTicks, hopper
cooldowns), and **W** for calendar call sites (schedules, daylight). The audit
that classifies every `getGameTime()` call site in 26.2 (~400) is the bulk of
A3 and is mechanical but mandatory; misclassification = the "mixed timing
models" failure the design forbids.

## 3. The merge rule, executed BEFORE interaction is possible

Interaction requires proximity; proximity requires closing the unloaded gap;
closing the gap requires **chunk loading**, which is always visible to the
topology layer before any gameplay can cross:

- Chunk load/gen toward another island flips cells populated → the registry
  sees the gap at 2 cells and merges **while ≥256 blocks of unloaded chunks
  still separate the simulations** — nothing can have interacted yet. The
  trigger is structural (ticket/population), strictly earlier than any
  entity/redstone/fluid/piston/explosion reach, because all of those require
  loaded chunks to propagate through.
- Projectiles/TNT/falling blocks/carts flying outward load chunks ahead of
  themselves (vanilla motion loads/needs target chunks; unloaded ⇒ the entity
  stalls at the border — vanilla behavior). The load event triggers the merge
  before the entity's next tick executes in the new cell.
- Players approaching: their view/simulation tickets populate intermediate
  cells continuously — merge fires when the two ticket fields are still ≥2
  cells apart, i.e. before either player's simulation can touch the other's.
- **The one long-range exception: entity tracking + targeted packets** (spyglass
  view distance can exceed the gap). Cross-island *visibility* is read-only for
  simulation but must not deliver mutations. Same-island interaction remains on
  the owner lane; interaction with another player owes a whole fenced Main
  packet; cross-island commands and `@e` selectors use their explicit A5 routing.

Merge transaction (A4), in order, all on the scheduler thread:
1. **Quiesce**: both islands finish their in-flight tick and are unscheduled
   (safe point = end-of-island-tick; no mid-phase pausing, ever).
2. **Clock election**: survivor per A1 rule; `Δ_i = L_survivor − L_i` computed
   for each absorbed island (never negative for the survivor by the max rule —
   time never runs backward in the merged domain).
3. **Deadline conversion**: absorbed islands' LevelTicks containers, hopper
   absolute cooldowns, and any L-absolute store re-keyed by +Δ (single pass
   over per-island containers — these are already island-partitioned).
4. **Structure adoption**: per-island task queues appended (order preserved),
   BE cell lists and entity region sets are already cell-keyed (no copy — the
   cell→island table repoints), tickets/POI untouched (chunk-keyed).
5. **Index/mirror invalidation**: gridToIsland refresh (exists), RustMI
   region hooks re-fire pre-tick naturally next tick; HardColliderIndex and
   spawn mirrors are level-scoped (unaffected).
6. **Resume** as one island at the survivor's clock.
Failure at any step: the transaction journal (steps are idempotent; the
re-keying pass records `appliedΔ` per container) is replayed or rolled forward
on the next scheduler pass — never rolled back, because quiesce means no
simulation observed a half-state. Crash mid-merge: on boot, islands are
rebuilt from world state (registry is derived data; L clocks restart at W —
all deadline stores are then consistently re-anchored by the same rule, and a
restart already resets relative-to-absolute skew in vanilla).

## 4. Split (A4, conservative by design)

Split only when ALL hold for the candidate partition, checked at a quiesced
safe point: gap ≥3 cells of unpopulated regions; no entity within 2 cells of
the cut (covers projectiles/TNT/carts/leashes/passengers by position); no
pending cross-cut scheduled tick, block event, deferred cross-region write, or
queued task referencing the far side; no chunk ticket held by the far side
(portal tickets, forced chunks — a chunk loader creates a PERMANENT edge in
the island graph by keeping its cells populated, which already prevents both
the gap and the split: dependency is representation, not bookkeeping); no
active raid/POI cluster spanning the cut (village bounds are chunk-local —
checked via POI section ownership). Fragments inherit the parent clock
(Δ = 0 at split — no conversion needed, timers stay coherent). If any check
fails, stay merged; merged-but-idle islands cost nothing extra.

## 5. Ownership migrations (the three hard stages)

- **A2 — island tick loop + scheduler**: islands become deadline tasks
  (`nextDeadline += 50 ms`; missed deadlines accrue lag and report real TPS —
  the shadow model already running in A1, made real). Global phases that
  remain (connection tick, save, mod hooks) run on the W-authority lane. Flip
  gate: per-island differential vs global path on the full scenario suite,
  plus save/shutdown quiesce tests.
- **A3 — time plumbing**: the `getGameTime()` audit + per-island LevelTicks
  keying + hopper absolute-cooldown conversion + TimerQueue split (W lane vs
  island dispatch). Flip gate: redstone/hopper/furnace/crop differential
  under forced island lag (a deliberately slowed island must produce the
  exact block-state sequence of an equally-lagged vanilla server).
- **A5 — player & packet ownership**: connection tick and packet application
  move to the owning island (Folia's model); movement/anti-flying validation
  reads the island timeline (fixes itself — the counters are relative).
  Same-island attack/interact stays on that owner; interaction targeting another
  player takes a fenced Main packet. Cross-island commands use
  the explicit routing lane. Clients experience a slow island exactly as they
  experience today's lagging server (fewer movement updates), with no protocol
  changes. Flip gate: two-client differential (one on a lagged island, one on a fast one)
  with packet-order assertions, plus the anti-cheat suite.
- **A6 — Nether transfer (implemented)**: `NetherPortalTransfers` captures the
  source entity tree and player/camera custody before the source unit seals.
  Consumable capture uses nonblocking exact-identity guards: a busy tick or pickup
  rejects capture rather than waiting while retaining the source claim. An own
  tick may reserve reentrantly; an entered consumption transaction may not.
  A worker executes the complete vanilla transition between units only after
  acquiring the entire source/destination ownership footprint. Contention backs
  out of all claims; no partial-claim wait or Main-deferred teleport queue remains.
  Players retain their instances and listener FIFO exclusion through the ordered
  Main-only metadata tail; non-player arrivals use vanilla destination clones.
  A reservation with no player and no transmitting waypoint anywhere in its
  custody runs **inline**: nothing is deferred to Main, and the worker finishes
  the reservation (source UUID release, portal-pending clear, preparation
  close) right after the vanilla teleport while it still holds both island
  claims. The clone is tickable by the destination island's next unit and its
  tracker takes the ordinary island path, so a lagging Main cannot delay a
  mob, item or cart crossing (portal farms). The paragraph below describes
  the tail mode, used whenever a player is in the cohort.

  An arriving entity's tracking publication retains its exact schedule token and
  original TickTask, including pending pairing/bulk obligations. After metadata,
  source retirement and listener settlement, with cohort claims released, Main
  enqueues that retained task. Cancellation releases its token without publishing;
  all completion callbacks run outside reservation and camera-registry monitors.
  Arrival readiness is separate from source custody and tick ownership. A
  destination clone stays indexed but cannot tick, tick riders, or enter the
  despawn pass until its metadata tail and the previous source UUID reservation
  have both retired. Draining callbacks alone does not open this gate: Main
  releases its owner claims before retiring the source. Readiness uses a
  separate volatile bit on the existing entity tick lease, not a removal spoof
  or a player-actor reservation token.
  `NetherPortalPreparation` preserves vanilla POI selection, world-border and
  portal geometry rules. Its publication fence tracks only `nether_portal` POI
  callbacks (the search reads no other POI type; vanilla defers every POI
  change through `server.execute`).
  Cold preparation and a genuinely missing portal run on
  physical Main under the real pool pause. This pause may retain queued next-unit
  regional tasks, but waits for running helpers, flat/global work and tracked
  causal obligations. It does not stop independent world-generation executors:
  cold native chunk loads can add POI receipts while generation owns Main.
  A completed native creation decision, including denial, is retained separately
  from publication readiness. Main releases the preparation pause and the normal
  server loop completes the receipts before the request can resolve; waiting
  alone never repeats creation. Generation's own POI callbacks join the existing
  TickTask queue so an older worldgen add cannot be overtaken by a portal remove.
  Other POI updates retain native execution policy. Creation keeps explicit
  physical-Main/paused-or-quiescent ownership checks, and a later request searches
  its own fresh snapshot before another portal can be created.
  There is no NBT handoff journal or crash-replay guarantee in this protocol.
  Island-owned players using non-Nether portals defer the complete destination
  lookup and teleport to their existing Main listener FIFO under a full fence.
  Source-turn cooldown prevents repeated contact from recreating the processor.
  Player End-portal entry regenerates the native platform inline before
  `destination.addDuringTeleport`; it cannot wait behind the player's own island
  arrival. Non-player End transfers retain their existing staged cohort.
  First End-return credits bypass that processor, so the complete credits call
  is also ordered after the owed late listener body. Main and non-A5 callers
  retain native routing.

## 6. Explicit non-goals / accepted semantics (documented, not hidden)

- A 10 TPS island IS slower wall-clock: crops, furnaces, mob AI there run at
  half speed relative to W — precisely how that base behaves today when it
  drags the whole server, minus the collateral damage. Cross-island fairness
  exploits (aging items faster by island-hopping) are impossible because all
  transfer conversions preserve relative remaining durations exactly.
- Mods with global-tick assumptions get the W-lane callbacks at 20 Hz
  regardless of island lag; mods touching foreign-island state from those
  callbacks are the same hazard class Folia declares incompatible — detected
  by the existing RegionAccessDetector and routed through the merge-trigger
  lane where possible.

## 7. Rust boundary

Stage-A structures (registry, event log, deadline heap of dozens of islands)
are deliberately Java: coarse-grained, tiny, cold. RustMI earns its keep here
later at exactly two spots, both batch-shaped: (1) the split-safety scan
(§4: entities/tickets/scheduled-ticks near a cut — a compact primitive sweep
over per-cell counters mirrored natively), and (2) deadline wheel + dependency
edges if island counts grow into the hundreds. Neither is justified at
current island counts (measured: single digits per level); the seam is left
explicit in IslandRegistry's doc so the migration is additive.

## 8. Diagnostics (shipped in A1)

`/fabrolia regions debug islandsummary`: per island — id, cells, entities, cost ms,
shadow TPS, logical tick, offset vs W, accumulated lag, age; plus the last
128 merge/split/born/retire events with reasons. The same numbers become the
real scheduler's telemetry when A2 flips (shadow → actual).


## 9. Worldthreader findings (studied 2026-07-12, full source + history)

These are historical design findings; the current contract is §0.

Adopted or adapted:
- **Explicit transfer boundary** (their `TeleportedEntityInfo` and destination
  receive phase): retain a source-unit receipt and one request identity. The
  implemented Nether path instead executes vanilla under joint endpoint claims;
  it does not adopt NBT replay or promise an fsynced cross-level handoff journal.
- **`getAllLevels()`/`getLevel(key)` interception as THE tripwire** for code
  that needs multi-world access (their exclusive_world_access mixins): this is
  the exact detection point Fabrolia's mod-compat mode will use — route to the
  compat ladder (owner-routing → queue → merge → exclusive domain → reject
  with diagnostic) instead of their always-exclusive fallback.
- **Exclusive-access implementation template**: their semaphore-permit yield/
  reacquire system (adopted after their park/unpark polling missed wakeups —
  commits 19e9373, 5300f4a) informed the originally proposed LAST-resort
  "temporarily global" tier.
- **Portal-creation race** (their duplicate-nether-portal bug, 03ae1c4):
  portal creation is a cross-domain write. Nether preparation serializes it on
  physical Main under a real pool pause and publishes POIs before the next lookup.
- **Cross-domain entity references stale-by-one-tick** (their EntityReference
  cache): same rule for cross-island refs, but bounded by merge instead of
  living with permanent staleness.

Explicitly rejected, with their own evidence:
- **ServerPlayer recreation on transfer** — their DESIGN.md documents the
  vanilla breakage it causes (dogs/cats/parrots losing owners, loyalty
  tridents and thorns failing against the swapped instance, mob anger
  persisting wrong). Fabrolia A5 keeps ONE ServerPlayer identity and
  transfers ownership instead; this is why A5 is scheduled as its own stage
  with a two-client differential gate rather than the "easy" swap.
- **Global barrier per tick** — the two-Phaser-per-tick clock model remains
  distinct from Fabrolia's per-island deadlines.
- **Exclusive-on-write scoreboards** — global updates and routed advancement
  rewards stay on the owned Main lane; advancement criteria themselves are
  evaluated and awarded on the player's owner thread (§0).

Failure-derived test additions (all from their TODO/DESIGN/commits, now part
of the A5/A6 gate suites): leashed mobs following a player through a portal
(player-first ordering), ender-pearl owner checks across domains (their
perf bug: pearls waiting for exclusive access), command blocks teleporting
entities across 3+ domains in one tick, two entities entering the same
unlinked portal simultaneously, thorns from a transferred player's arrow,
pet follow-goal across transfer, neutral-mob anger decay across transfer,
console-thread command safety.

## 10. A-blocker — RESOLVED 2026-07-12 (same pass)

**Populated-count decrement gap on ticket-removal unloads**: rig probes prove
chunks unload ≤30 s after `forceload remove` (`execute if loaded` goes
silent), yet region populated counts — and therefore island topology — held
the bridge cells for ≥95 s (registry correctly mirrored the stale topology;
its own split logic is fuzz-proven over 15k transitions). ROOT CAUSE: fabric's CHUNK_UNLOAD event misses 26.2's ticket-only demote path.
FIX (structural, not rescan-based): population is now an IDEMPOTENT per-region
`fullChunks` set (the counter is derived; duplicate or missing events cannot
inflate, go negative, or double-fire topology updates), fed by BOTH the fabric
events and the authoritative vanilla seam `ChunkMap.onFullChunkStatusChange`
(fires on main for every FULL-boundary crossing, promote and demote, all ticket
types — redundant delivery is absorbed by design and COUNTED: the
`ignoredRemoves` counter growing during unloads is the recorded proof that one
source misses what the other catches). Ground-truth self-check:
`/fabrolia regions popcheck` compares the sets against the visible holder map.
PROVEN end-to-end on the rig: two merge/split cycles (merge t=66 → split t=147
~4 s after ticket removal → re-merge t=1046 absorbing the fragment id →
re-split t=1126), popcheck OK (tracked==truth==171, zero mismatched regions)
at every state including through save-all; identity/clock rules held
throughout. Reproducer promoted to test: benchrig islands3.txt.
Stage A2 is UNBLOCKED.

## 11. A2 IMPLEMENTED — coordinator units + deadline drive (2026-07-12)

Shipped in this pass (all default-OFF behind `asyncIslands`; `/fabrolia
regions async` toggles at the next tick head; sync mode is byte-identical by
construction — one global EpochDomain everywhere):

- **EpochDomain** — one scheduling timeline per barrier scope: epoch, exact
  entity-membership cut (`entityDispatchCutEpoch`), final dispatch receipt
  (`dispatchedEpoch`), entity barrier + pending counter, per-domain entity tick
  token, L-clock (`unitGameTime`), deferral queue, PIsland owner backref.
  Every Region carries `domain`; ALL eligibility/completion/barrier
  bookkeeping compares in the region's own timeline. ColorGate skips
  cross-domain neighbors (separation invariant: no same-tick causality
  crosses the gap). Cold Region creation is linearized against the membership
  snapshot by the regionizer READ/WRITE gate: pre-cut births initialize all
  six epoch stamps at E-1; uncounted post-cut births initialize them at E.
  Domain reassignment only occurs at safe points and translates all six stamps
  (RegionizedServer.assignDomain).
- **IslandTickUnit** — the unit is a worker-run COORDINATOR:
  [scheduled block/fluid ticks collected at the island's L] → [its block
  events] → [entity phase via the R5 event machinery on its own barrier,
  coordinator helps its own frontier and global/Main-safe dependencies without
  polling the shared ready queue or entering another unit, with a domain-scoped
  liveness backstop] → [island-internal deferred cross-region replay] →
  [BE phase: persistent cell index + mid-unit registrations same-tick] →
  [L++, real cost, deadline roll]. Deadline roll re-anchors only when ≥1
  FULL tick behind (sub-tick drift keeps phase — re-anchoring on ε starved
  idle islands against the 20 Hz drive grid).
  Scheduled handlers require a protected write frontier: topology supplies the
  one-cell halo for core cells; newly FULL halo cells must also own all eight
  neighboring cells before supplying tick work. Materialized `Region.domain`
  wins over the cold-creation snapshot. Open-frontier fluid ticks stay pending;
  due pulse-sensitive block ticks hold the complete unit. Closed FULL halos can
  progress without core promotion.
- **Tracking** — native VMP tracking and spawn census traversal remain serial;
  island concurrency does not authorize arbitrary foreign-executor fan-out.
  Accepted external work retains generation obligations through execution or
  cancellation, but causal accounting alone is not world authority (§0).
- **L-clock** — PrimaryLevelData.getGameTime overwrite (one choke point for
  every dimension) → IslandClock.contextTime() (claimed region's domain,
  else coordinator's currentDomain). L anchored to gameTime at island birth;
  vanilla's relative/re-anchored persistence makes restart clock-wipe
  equivalent to "chunks were unloaded a while".
- **Deadline drive** — main submits only DUE islands (10 ms slack), awaits
  ONLY the global remainder lane. The chunk phase (natural spawning, thunder,
  random/fluid ticks) is NOT armed from main for island-owned regions: each
  island unit runs its own chunk pass (§13); main arms + awaits only regions
  with no island owner.
- **Integrity** — rebuild and save-all quiesce every unit claim
  (mainHoldsAllClaims reentrancy for nested save paths); incremental chunk
  saves take the owner island's claim opportunistically (vanilla cooldown
  retries); player-data saves take it blocking. beCells is concurrent with
  copy-on-write cell lists; the island registry publishes an immutable
  cell→island snapshot for off-main readers.
  An absent Region resolves through that published destination snapshot without
  materialization; an existing Region's actual domain remains authoritative,
  including during transitions. This lookup grants neither execution authority
  nor chunk readiness.
- **Proven by rig differentials** (benchrig, async OFF vs ON byte-parity of
  settled world state through merge+split cycles): items/armor stands,
  hopper chains, piston block events, repeater lamps + water (scheduled
  ticks) — 36/36 probe lines identical under the sync drive; popcheck +
  beindex OK throughout; offset (gt−L) constant. Deadline-drive lag
  differential (loaded vs quiet island) is the current gate before anything
  further ships.

Still open before default-ON is even considered: A5 packet-heavy edge
soaks, A7 compat ladder soaks (crossDOMAIN=0 certification), A8
command-block/datapack island execution (in progress — see §12), long
soak/crash suites. Tracked in the a2 checkpoint. NOTE: Oxidizium Redpiler
was REMOVED from the architecture entirely (2026-07-12) — it was
experimentally unsafe and altered redstone behavior; vanilla redstone is
the sole source of truth and the only redstone engine consideration.

## 12. A8 — island command execution (in progress, Phase 1 landed)

Vanilla command semantics are the sole source of truth. (Oxidizium Redpiler
was removed entirely — no filter hooks, no claimed-position cadence, no
compatibility surface; vanilla redstone is the only redstone engine.)

**Model.** Every command execution is ONE logical invocation with
exactly-once semantics, identified by a ledger record (id, source identity,
position, island+generation, logical tick, command text, ordering token,
state, result). Execution shapes:

- **Island-local (inline)** — proven to touch only source-island state
  (allowlisted root: setblock/fill/clone/summon/data-block; no selectors;
  every coordinate triple — absolute or ~relative — resolves into the
  island's cells). Runs inline on the island thread at island cadence on
  island time. A command block on a 10 TPS island runs at 10 TPS.
- **Routed (fenced batch)** — everything else. Exactly ONE execution on
  physical Main inside the per-tick routed batch. The exact selected worker
  must acknowledge its parked command delegation before Main receives the
  held fence; an arbitrary waiting flag or an acquiring fence is not authority.
  All queued commands execute in order, the fence is released, THEN waiter futures
  are completed — a resuming coordinator can never observe another command's
  fenced window. The source island's chain blocks for the vanilla boolean
  result, so conditional chains, success counts and comparator outputs are
  exact. Batch points: drive head (unbounded after repeated bounded-fence
  timeouts, so an overloaded island cannot starve commands) and post-pump
  (2 ms bounded attempt → same-tick results for fast islands).
- **Main-context commands** (console, players, RCON, dispatched functions,
  command-block minecarts on the sync lane) are fenced per dispatch
  (CommandsMixin) or per function (ServerFunctionManagerMixin, one fence for
  the whole body — covers MCFC-compiled execution, which replaces the body
  INSIDE that wrap). This closes the main-writes-into-in-flight-islands race.

**Deadlock discipline.** Coordinators waiting on routed results PARK ONLY
(no help-pumping — helping could re-enter their own island). Main-side claim
spins that need a unit to actually FINISH (rebuild, save-all, player save)
break out when they meet a parked waiter and run the routed batch first —
each batch completes at least one waiting command, chains are finite, so
acquisition converges. The selected command waiter is excluded only through its
exact acknowledged delegation, not a blanket exemption for parked producers.
Each Main claim-interest scope restores its prior `claimWanted` value on exit:
a nested fence cannot revoke an enclosing waiter. Local quiesce attempts restore
their exact snapshot once, including on retry/failure; already-admitted units
still complete under their original claims.

**Physical command blocks** tick inside their island unit (impulse via the
scheduled segment, repeating via the BE segment, chains walked inline on the
coordinator — chains cannot cross islands: contiguous loaded blocks are one
island by construction). All block state (success count, previous output,
conditional mode, auto, delay, comparator output, trackOutput) is written
either inline on the owner or on the fenced main batch with happens-before
via the result future.

**Datapacks (Main-resident roots).** Functions the plan does not partition keep
vanilla W-cadence and main execution, fenced per function body; the
Main-resident `#minecraft:tick` functions run inside the tick-head advancement
window (§0) and share the fence that window takes for them, so routed
advancement work in that tick costs no second quiesce. Scheduled
functions (overworld `tickTime`) keep their own fence; a `TimerQueue` tick with
two or more due Main-resident function callbacks takes one fence around the
whole dispatch (`TimerQueueMixin`). /schedule stays on the vanilla game-time
queue. Per-tick roots the plan partitions run on island heads instead (§16).

**Phase 2 (next): invocation shards.** Routed commands with multi-owner
selector targets partition into per-island shards after resolving selectors
against one coherent ownership snapshot under the fence: one shard per
affected owner, applied on that owner's lane, aggregated once into the
source's result; the ledger (invocation id, shard id, source/target
generations, pending/running/committed/redirected) carries exactly-once
through merges (coalesce, never replay committed shards) and splits
(repartition pending shards, preserve invocation identity, validate target
generation before commit, redirect on mismatch). The Phase-1 fence remains
the fallback for unpartitionable commands.

## 13. Island chunk pass — spawning + random ticks at island cadence (2026-09-28)

Before: main armed every island's spawn/random-tick cycle once per MAIN tick
(ArmGate: skip while the island still owed the previous arm), built the
level-wide `SpawnState` on main (createState ≈ 4.7 % of main at production
load) and took the spawnable-chunk set from `DistanceManager`. A lagging main
(10 TPS) therefore gave every 20 TPS island 10 spawn cycles/s.

Now (`IslandTickUnit` → `ServerChunkCacheMixin.fabrolia$tickIslandChunks`):
the unit runs vanilla `ServerChunkCache.tickChunks` restricted to the island,
at vanilla's exact slot (after the island's scheduled ticks and raids, before
its block events), serially on the coordinator, on island L. The ArmGate and
its reservation accounting are gone; main's lane decision is the pure
ownership test `RegionizedServer.islandOwned(region)`.

| vanilla level-wide input | island lane |
|---|---|
| `naturalSpawnChunkCounter` (chunks within Chebyshev 8 of a counted player, loaded or not) | `NaturalSpawnChunks.candidates` over the island's own counted players (`ChunkMap.fabrolia$forEachSpawnCountedPlayer`: PlayerMap members with the ignored bit clear, keyed by last section pos). Islands are ≥16 chunks apart, so the per-lane sets partition vanilla's set exactly and their sizes add to vanilla's count. |
| `collectSpawningChunks` (candidate ∧ ticking chunk ∧ non-spectator player <128 blocks) + `Util.shuffle(level.random)` | same predicate (`playerIsCloseEnoughForSpawning`, mod wraps apply) over the island's candidates, restricted to chunks the island owns; shuffled with the level RNG. |
| `createState` census over all FULL chunks | `SpawnCensus.reduceSerial` through a lane `ChunkGetter` that admits only the island's FULL chunks (same rule for the ledger reduce, potential charges and local-cap seed). |
| `LocalMobCapCalculator` | vanilla's level `LocalMobCapCalculator` (over `ChunkMap.getPlayersCloseForSpawning`), seeded island-locally with the island's census — exact, because the closest two cross-island points are ≥ 2·8·16+16 = 272 blocks apart, beyond the 128-block cap radius. The native pack-reservation arena stays on main's parallel lane only (an island pass is sequential, vanilla's own setting). |
| `PotentialCalculator` | island-local — **accepted deviation**: another island's charges are ≥256 blocks away (vanilla contribution ≈ charge/256 at that range). |
| `canSpawnForCategoryGlobal` (level count < cap × level chunks / 289) | `SpawnCapLanes`: each lane publishes `[counts per category, spawnable chunk count]` as one immutable slot (island: `PIsland.spawnCapSlot`; main's remainder lane: `SpawnCapLanes.globalLane`), and every lane's `SpawnState` is built from the SUM. Staleness ≤ one period of the other lane; no lane ever waits on another or on main. |
| `getGameTime() % 400` persistent categories; `incrementInhabitedTime()` (+1 per pass since 26.3) | island L; +1 per island chunk pass. |
| `forEachBlockTickingChunk → level.tickChunk` | the island's entity-ticking chunks (its cells + adopted halo cells, completed simulation view) through vanilla `tickChunk`. Under ServerCore's `chunkTickDistanceAffectsRandomTicks` the source is ServerCore's Main-mutated ChunkTickManager: Main partitions the real `forEachBlockTickingChunk` result by owner once per Main tick (`PIsland.servercoreRandomTickChunks`) and the island re-resolves that snapshot (≤1 Main tick stale). |
| `customSpawners` | unchanged: level-wide, on main. |

Main's own createState now censuses only the global lane (the lane getter),
publishes its slot, folds the island slots into its counts, and is built with
the summed chunk count — `lastSpawnState` still reads as the level total.
Main's global-lane spawnable count comes from the same player rule
(`NaturalSpawnChunks`), not from `getNaturalSpawnChunkCount`; its candidate
list is filtered to the global lane at `getSpawnCandidateChunks`
(ChunkMapMixin), so Main never resolves/shuffles/gates island chunks. Main
runs `rebuildIslandsIfDirty` before the deferred chunk-work arm, so a region
populated during the chunk phase is adopted before a lane is chosen.

**Serial by design.** The whole island pass runs on the coordinator. The only
in-unit parallel executor is the entity-phase dispatch: island regions are
claimable exclusively inside the dispatched entity window
(`ColorGate.inUnitWindow`: `groupedEpoch == epoch && doneEpoch < epoch`),
help-queue detachment requires the same eligibility plus a live claim holder
(`RegionWorkerPool.detachHelpForLiveClaim`), and the queue-only dispatch
variant (`tickEntities=false`) is hard-wired to the global domain
(`queueOnlyEntityEpoch` / `isQueueOnlyEntityEpoch`). Hosting random ticks
before the island's block events in parallel would need a second epoch per
unit (entity tick-token dedup, dispatch cut, ColorGate completion, receipts)
— a new scheduler layer. Putting them in the entity-phase queues instead would
be cheap but reverses vanilla's random-ticks-before-block-events order within
the island (the pre-A2 relaxation this pass removes). A large single island
therefore pays vanilla's own single-threaded chunk-pass cost at its own TPS.

Not bit-exact vs. vanilla (all bounded, none accumulating):

- iteration order of the candidate/ticking sets before the shuffle (vanilla's
  is hash order too); RNG draw sequence is per island rather than level-wide;
- a chunk changing lane at a topology rebuild can miss or repeat ≤1 chunk
  cycle (main arms before the pre-dispatch rebuild);
- the global cap is evaluated per lane against its own fresh census plus the
  other lanes' last slots: overshoot is bounded by one pack per lane per
  period instead of vanilla's one level-wide evaluation per tick; a lane whose
  unit is held (HOLD, portal fence, long TemporalControl period) keeps its
  last slot pinned for the others until it runs again;
- `inhabitedTime` accrues island ticks, not wall ticks — a slowed island's
  local difficulty grows at its own rate (26.3 vanilla credits +1 per chunk
  pass, so this is the only difference);
- the island's 128-block player test iterates only the island's counted
  players (vanilla iterates every PlayerMap member, ignored spectators
  included — identical under vanilla's predicate, which rejects spectators);
- the island census always comes from the incremental ledger (its 256-gt
  ground-truth audit stays on main: main runs it from its own incremental
  path, or explicitly from the createState wrap when main takes the
  vanilla/legacy scan); the `SpawnRateDiag` pass counter now counts island
  passes too;
- mod wraps on `collectSpawningChunks` / `getSpawnCandidateChunks` /
  `getNaturalSpawnChunkCount` (and the VMP candidate shortcut, which is a
  pure optimisation of the same predicate) do not see island passes;
  ServerCore's `chunkTickDistanceAffectsRandomTicks` is honoured on both
  lanes (the island filters the real `forEachBlockTickingChunk`).

## 14. Autosave: snapshots under the claims, IO on the save writer (2026-10-04)

Owner-side work stays where it was: `PlayerList.save` still runs under the
player's owner claim and `saveAllChunks` under the all-island quiesce, but they
only take snapshots now. `common/save/AsyncSaveWriter` writes the files.

- Player `.dat` / `level.dat`: vanilla's body runs; `NbtIo.writeCompressed` is
  replaced by serializing the NBT (plus the chunk boundaries vanilla's buffered
  stream hands to gzip — deflate output depends on them) on the caller, and
  `Util.safeReplaceFile` by a queued job: gzip, one `SYNC` write of the temp
  file, the vanilla `.dat`/`.dat_old` rotation. Byte-identical to vanilla.
- Advancements: `asData()` detached under the progress monitor (criteria in
  stream order + requirements); encode, pretty print and write on the writer.
  Stats: map copied in `toJson()`'s stream order (fastutil streams ≠ iterates).
  JSON goes to a `.tmp` sibling + atomic move (same bytes, umask permissions,
  never truncated on kill).
- Writer: per-file FIFO, files in parallel (≤4 daemon threads); waiters run
  their own file's queued jobs inline; at most one unstarted job per file (a
  second submit writes the first — backpressure instead of an unbounded queue;
  a write stuck >10 s is logged as an error while the saver keeps waiting, like
  vanilla's blocking write). An exception escaping a job (not vanilla's
  tolerated IO errors) is always published fatal (and rethrown by the await of
  that ticket).
- Durability (`SaveDurability`): `PlayerList.saveAll` (autosave, plain
  `save-all`, reload) and the `saveAllChunks` body are deferred; any other
  `PlayerList.save` — logout above all, also nested in a deferred saveAll —
  waits for its files after the owner claim is released; `saveAllChunks(flush)`
  (`save-all flush`, shutdown) and `stopServer` wait for every queued file
  after the islands run again. `PlayerDataStorage.load`, `PlayerAdvancements`
  (re)load and the stats file load wait for that file's queued write first.
- Claim interest for the whole `saveAll` (`regions/IslandSaveGroup`, F3): an
  unfenced pass (autosave, the `/reload` apply stage, `stopServer`; a fenced
  `save-all` already holds every claim) announces Main's claim interest once
  on every island owning a player, runs the vanilla loop, then withdraws. Each
  save still takes and releases its island's claim (Main holds at most one),
  but no unit is admitted or chained in between, so the pass waits for each
  island's running unit at most once (max, not sum). Islands are not released
  early after their last player: autosave's `saveAllChunks` quiesce follows.
- Scoreboard (`DeferredSavedDataEncodes`, `SavedDataStorageEncodeMixin`):
  `ServerScoreboard.storeToSaveDataIfDirty` still packs the scoreboard under
  the quiesce, but `encodeUnchecked` only captures that immutable `Packed`
  and hands `collectDirtyTagsToSave` an empty placeholder tag (keyed by
  identity); the codec encode runs in the scoreboard's own `tryWrite` task on
  `Util.ioPool`. Dirty-bit retirement, the per-storage write order
  (`pendingWriteFuture`) and `saveAndJoin` (flush, close) stay vanilla, the
  joined task now includes the encode. Same bytes. Only `ScoreboardSaveData`
  qualifies: every other SavedData (maps, raids, command storage, …) encodes
  live mutable state and stays on Main. An encode failure is published fatal
  (vanilla would have thrown on Main), writes nothing and marks the scoreboard
  dirty again (vanilla keeps it dirty), so the stop's flushing save retries;
  it is not rethrown into the storage's write chain, which would stop every
  later server SavedData write. Placeholders are weak keys: a write task
  dropped after an earlier failure in the chain frees its snapshot.

## 15. Entity tracking tick (opt/tracking, 2026-10-04)

The VMP tracker tick (`NearbyEntityTrackingMixin`) is a discovery pass without
gameplay authority; every pairing mutation is staged on the tracker and
committed by its owner (island tail, or the claimed Main publication).

- One position snapshot per indexed tracker per tick (parallel when large):
  the decisions of a tick and the next tick's movement test use the same
  position, so any later move is a move next tick. A re-indexed tracker always
  counts as moved.
- Viewer passes (`TrackingViewerPass`) run on the tick pool and on Main
  (claim counter, unstarted helpers cancelled). A pair is re-staged only when
  the exact vanilla outcome (distance with VMP's always-tracked chunk test,
  `broadcastToPlayer`) differs from the current pairing, or while the tracker
  has staged/committing membership. Removals from retired trackers are dropped
  silently; a retired tracker never sends a second remove packet.
- Already-attached island cadences are not re-routed by the tick (the tail
  publishes them every unit).
- Non-player tracker add/remove never takes the tracker lock
  (`TrackerIndexChanges`): add stages the owner-tail bulk pairing immediately
  and queues the area-index insertion; remove sets a tombstone and queues the
  index removal. VMP's 200-tick staging is no longer used for them. Fresh
  trackers are covered by lifecycle viewer removal and join the viewers'
  tracked sets once their staged membership settled. Player trackers keep the
  locked VMP path; VMP's recursive refresh tick on player removal is skipped.
- Staged membership of island-compatible entities outside entity-ticking
  range commits at the owning island's tail (same chunk claim the Main
  publication used) while the server runs normally; no persistent publication
  clock is kept there.

## 16. Datapacks on island heads (per-tick roots, 2026-10-04)

**Plan.** `FunctionAnalyzer` keeps vanilla's exact parse of every function
(`FunctionIR`: the `ContextChain` stages, node text from the parsed ranges,
typed arguments). At library activation `FunctionPlanner` resolves the call
graph against that generation's tags, predicates (codec-encoded, allowlist) and
advancement rewards, and classifies every line of each per-tick root —
`#minecraft:tick` members and unconditional `schedule function <self> 1t`
loops:
- PARTITION — `execute … as <@a|@e[…]> …` (no limit/sort/@p/@r/@n, no player
  NBT) whose body touches only `@s` (its scores, tags, inventory, effects,
  `advancement grant|revoke @s` via C1 — a grant's announcement, loot and
  non-owner-local reward functions are routed to fenced Main by the C2 routing
  point), reads Main-written state (constants, storage, teams) and sends
  particles near `@s`;
- ESCALATE — same fan-out, global body (fake-player writes, storage/team
  writes, unbounded selectors, entity NBT writes, macros, unknown commands), or
  a write that Main-resident code also reads/writes (conflict rule);
- REARM — the loop's own re-arm; GLOBAL — anything else (the root stays
  Main-resident: fenced, vanilla, as before).
`/fabrolia functions plan` prints every root's verdict with each line's class
and the exact blocking `function:line` reason; `/fabrolia functions fences`
prints per-tick fence causes.

**Execution.** Main arms the partitioned `#tick` roots after the tick tag ran
(so a reload's `#load` precedes them) and arms a loop when its TimerQueue
callback fires — re-armed through its exact vanilla `schedule` line, no
`execute`, no fence; `/schedule clear` disarms at once. Each island unit runs
the armed roots at its function head (after cross-dimension arrivals, before
scheduled ticks; own stamp `functionHeadAppliedL`, inherited on split) at island
cadence L — a 10-TPS island runs its tick functions 10×/s, several W firings of
a loop coalesce into one pass. Main runs the same roots for the global
remainder (non-async levels, global-domain regions) at vanilla's function
point; when an async level's global regions hold members it does so under the
global fence. A line runs vanilla's `BuildContexts` continuation after the
fan-out stage, evaluated with the selector's own predicate over the pass
members; owner-local bodies run inside `IslandFunctionScope` guards
(TimerQueue, storage writes, teams/objectives, fake-player score writes,
foreign entities/chunks, global selectors, chat broadcast → fail-stop;
`getPlayers()` → Main-published snapshot). An ESCALATE match hands the rest of
that head (matches already evaluated, not re-rolled) to fenced Main in ONE
routed body. Failing bodies log vanilla's "Failed to execute function".

**Exactly-once per tick.** `FunctionPassMark`: a pass moves an entity's mark
`OWED → passId` before running it; the entity's next tick resets `OWED`. Two
passes (island heads, Main remainder, the async-OFF catch-up) never both run an
entity between two of its ticks, whatever topology change moved it; Main's
`#tick` roots and loops share one pass id per W tick. Async OFF runs the armed
roots for the retiring islands' still-owed entities at the flip.

**Accepted semantics.** Island cadence L (product decision Q2); escalated lines
run in per-island order (Q1); reads of Main-only state may be one tick early or
late; entities in loaded but not entity-ticking chunks are not visited; command
and fork quotas count per line and pass.

## 17. Main-thread trims (opt2/main2, 2026-10-04)

- **Async region reclamation.** Idle regions (no FULL chunk, no entity, empty
  queues, unclaimed) were never reclaimed in async mode, so every cell ever
  visited stayed in the map and the global lane's per-tick done sweep, the
  Main function remainder scan and every rebuild snapshot walked them all.
  They are now reclaimed inside the topology rebuild's all-island quiesce (the
  one async point where no unit can mutate a region index), only on the global
  domain — island core and halo regions keep their identity — once two
  reclamation passes at least 100 game ticks apart both saw the region idle
  (passes run only at rebuilds in async mode). Idle also requires no open
  claim transaction or rebase cut (claim-free main-safe bodies), and a ColorGate
  re-arm publishes a peeked neighbour only while it is still ACTIVE under its
  admission monitor. The level-tick head stays fail-closed in async mode. A
  reclaimed cell is reinstalled on its next lookup with the topology's creation
  domain.
- **Lazy topology drift.** A committed island core cell losing its last FULL
  chunk no longer forces the all-island quiesce: the island keeps the empty
  cell as core (no FULL chunk means no entity, block or chunk work there; every
  separation and halo invariant was computed over the larger set). The same
  cell repopulating before the next rebuild restores the committed shape
  exactly. Pending drift is folded into the next urgent rebuild, or after
  `LAZY_TOPOLOGY_TICKS` (100) on its own; region-map growth of 64 since the last
  rebuild also makes such a refresh due, so reclamation keeps up. Every other
  boundary — a non-core (halo/gap) cell, or any boundary between the BFS read
  and the registry commit — stays an urgent rebuild as before. A lazily kept
  cell that repopulates runs on its old island's timeline until the next
  rebuild, even if a fresh BFS would have made it a separate island.
- **Main function remainder.** `MainPartition.candidates` walks the region map
  without a snapshot copy and skips empty regions before building a skip-list
  iterator (it runs once per partitioned root and loop callback).
- **Tracker index changes off the tick.** Queued non-player tracker add/remove
  (AreaMap insert/remove of the tracker's whole view square) are applied by a
  one-shot task on the tick pool, published by Main at the level tick head (the
  whole inter-tick interval's changes, applied while Main runs time, raids and
  chunk work) and at the end of the tracker tick, under the tracker lock; the
  tick head applies only what arrived since. Every reader of the index holds
  that lock, so an earlier application is indistinguishable. The task holds its
  tracked obligation only while applying and never idles in the pool.
- **Dense tracker mirror.** The per-tick position pre-pass runs over a dense
  array mirror of VMP's `tracker2ChunkPos` (kept at its only membership sites)
  instead of copying the hash map on Main every tick; parallel batches collect
  the trackers whose area chunk changed, and Main updates the area index for
  exactly those. A size divergence between mirror and map is fail-stop; after a
  VMP-native tick (Fabrolia tracking off) the mirror's chunk values are
  resynchronised from VMP's map.
- **Main ingress venue claim.** A MAIN-phase player's consecutive own-venue
  heads (deferred listener tick: player chunk; MoveVehicle/PaddleBoat: root
  vehicle chunk) run under ONE claim of that island taken by the drain;
  previously each head claimed and released it, and the island could start a
  whole unit in every gap (riders are never A5-owned, so their vehicle packets
  and listener ticks always take this path). Any other head releases the claim
  first, so no body runs holding a claim on a different island than today.
