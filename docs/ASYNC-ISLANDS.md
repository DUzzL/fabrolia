# Fabrolia async islands — independent per-island ticking

Status: persistent island identity and asynchronous island/player execution are
implemented. **Independently owned island units may execute concurrently.** Each
unit retains its real coordinator, island claim, generation and local clock;
entity ordinals within an island remain serial. Producers that can touch shared
or foreign gameplay state acquire exclusive ownership before their native body.
Physical Main controls lifecycle and services Main-only work, but does not hold
a gameplay permit across the complete server tick or native task loop.

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
  and its pre-producer dependency contract determine WHO may execute.

### Current execution and advancement contract

`RegionWorkerPool` admits generation-bound island publications independently.
Members of one unit do not fan out their entity ordinals. A global producer first
closes local admission, lets entered work reach its owned terminal boundary and
acquires the real global fence. An entered producer cannot upgrade after an
arbitrary predicate has already observed foreign state.

`AdvancementRequirements` prepares conservative producer requirements from the
native advancement carrier before publication. Declarative registry references
and tags need not be resolved during preparation; pending lookups retain their
native owner. Unsupported predicates remain unknown and require global entry.
Certified owner-local producers keep eager matching on their island; producers
with global requirements enter exclusively. A policy change is revalidated before
the body, not compensated by replaying callbacks later.

Ordinary residual exclusive grants rotate across ready notifications, unit roots,
global roots, Main-safe roots, portal work and Main-gameplay roots. Refused
notifications advance the rotation; detached notifications keep their normal
cleanup receipts. Redundant island-ready notifications retire without acquiring
global gameplay ownership. Neither a notification count nor a causal handoff is
claim authority.

Native server/chunk polling remains native orchestration. A chunk-service scope
is a progress venue, not global gameplay ownership, and does not inherit an
island's L clock. The exact outer native `runAllTasks` drains controls before
optional gameplay. After its control census releases claim intents, Main resumes
a finite FIFO publication cut rather than draining an ever-growing queue.

Optional terminal grants yield at `MinecraftServer.getNextTickTime()` in the
native `Util.getNanos()` clock. One terminal grant is permitted per wait root
even if already overdue; a ready/portal attempt need not execute an island body.
The unfinished cut and its owed eligible native-FIFO checkpoint survive later
waits. New publications cannot borrow old slots, and an unavailable grant returns
without discarding the cut or spinning. Eligible checkpoints do not add an empty
native idle wait.

The exact outer `managedBlock` poll obeys the same budget. Its literal callsite
receipt and actual `blockingCount` distinguish it from predicate/direct polls
and deeper waits. Required nested dependency, topology and shutdown pumps remain
uncapped. A deadline never interrupts an entered native producer or skips accepted
work. No self-requeued whole-tick gameplay driver remains; generation stays on
its native executors.

An empty hard-collider rebuild at the native level boundary clears the empty
index and checks failure without taking a global fence. A nonempty rebuild keeps
its ownership and rereads membership under that ownership. Certified native
Vanish cache warm reads use the actual published cache fields without the cold
refresh monitor; dirty refresh/invalidation retain their original private lock.
Unknown transformed cache graphs retain the original wrappers.

Main timing includes completed-loop work and required waits, excluding only
native deadline idle. The completed-loop mean and unfinished active work are
separate values. Island telemetry distinguishes completed cost, completion age
and queued/claimed/overdue state; a stale cheap completion is not a healthy live
measurement. Shared W-domain clocks retain their shared clock source.

Tracked Main controls retain pool-generation accounting through a healthy exact
turn's REQUESTED/PAUSED window; their executor publication resumes afterward.
That deferral is not a gameplay grant and never masks a fatal generation cut.
Preparation yields while earlier controls still need native FIFO service.
Mandatory exact quiescence instead drains accepted controls and their causal
continuations before entering the exact body.

Native control publication also signals the selected-turn lifecycle monitor:
the native queue's `unpark` alone cannot wake Main's `Object.wait`. Main checks
routed readiness under that same monitor before sleeping. Only a request allowed
by the current selected-source and outer-fence rules suppresses the wait; an
unrelated queued writer does not authorize busy polling or borrowing the selected
root. The native control task remains in its FIFO, and notification grants no
gameplay ownership.

Book-filter completions use this listener-scoped Main-control lifetime, not an
accepted routed world mutation. Disconnect or listener replacement discards a
stale callback before it can mutate the captured item stack.

Independent generation may build a private NPC ride while gameplay is paused.
`WorldGenEntityConstruction` witnesses entities created in the same synchronous
`generateFeatures` or `generateSpawn` invocation and the same level. Scopes end
in `finally`, do not propagate through returned futures, and cannot adopt an older
unindexed entity. Every reachable ride identity must remain nonplayer, unpublished
and outside lease/arrival/portal custody. Live indexing, callback publication,
retained transfers and waypoint/deferred-metadata publication revoke the witness
permanently; a null callback or absent region key alone proves nothing.

Only silent initial mounting without an existing vehicle and private passenger
link bookkeeping use this path; an empty `removeVehicle` is a native no-op.
Native half-updated ride links are traversed by identity, not rejected as cycles.
Removal hooks, ejection and event-producing rides retain ordinary gameplay
admission. Private construction still checks the authoritative failure before
and after the body, without retaining the failure-publication monitor across it.

Main-only entity E, block-entity and player L work uses typed rendezvous receipts.
The coordinator parks before the native body while retaining its source unit,
member/ordinal where applicable, generation and L clock. Main must receive the
exact acknowledgement and hold the real all-island fence before entering that
body. Fence and acknowledgement cleanup finish before the result becomes visible
to the coordinator; E(N+1), the unit tail and L++ cannot overtake an owed E(N).
Native failures retain their original identity through cleanup.
An already-owed player late body survives a soft lifecycle change with the exact
early permit, listener identity and original source clock. Healthy listener-batch
publication settles the whole batch before exposing continuations; one player's
successful finish cannot discard another player's pending late body.

The complete native ender-pearl `onHit` producer runs on globally owned physical
Main before owner admission, RNG, teleport or effects. An island worker uses the
typed entity writer rendezvous and retains its source claim and L clock until
the original native invocation returns. Nested teleport ownership cannot end
the outer producer's fall reset, damage, sound or discard. No copied continuation
detaches an admitted owner branch from its current life state: a second impact
observes any death completed by the first.

A pearl tree without an island owner instead selects a complete globally owned
MAIN_SYNC entity tick before entry; a claimless worker cannot park for a writer
ACK it does not own. Fenced MAIN_SYNC bodies, physical-Main commands and native
disconnect producers keep explicit global authority even with async and the
player actor OFF. Absence of autonomous claims is not itself ownership.

Topology/save quiescence keeps new-unit admission closed while already-entered
units finish through routed Main work. Physical claims may be released for that
progress, but the admission intent is retained across retries and extended for
new island identities. Prior intents are restored exactly, including on failure.

Live async OFF and shutdown reach terminal quiescence before changing async
state or collapsing island clocks. Rebase waits may drain already-published
Main-safe receipts under actual global Main ownership, but not unrelated global
roots or native controls; chunk-service progress remains available.

Global advancement requirements classify the complete native player tick tree
as a global producer even when the player is idle. The same preflight governs
worker admission and Main's publication of future island eligibility. A current
global owner cannot certify a future local venue under a global resource policy.
Use-only requirements retain their narrower active-use/Carpet-action fallback.

No arbitrary entered-producer migration, transfer of JVM monitors/scoped context,
logical Main alias or foreign-thread world authority is implied.
`FabroliaIslandApi.causalHandoff` carries the exact parent's lifetime accounting,
not transferable world access. `executeOnOwner` queues callbacks when a foreign
caller lacks owner authority.

`AdvancementBuffer` snapshots registered listener entries, then completes matching
at trigger time outside the listener-map monitor, in native order: each listener's
trigger/item matcher first, then its player condition if the matcher passes.
Neither phase is deferred or replayed. Each trigger observes its live producer
state; native entity NBT fetches are uncached, including between predicates that
may mutate state. The existing direct-field NBT fast path is retained.
Only matched criterion keys and their player survive in the buffer, not listener
predicates or live loot contexts. Awards, rewards and their cascades still use
the existing monitor-free drains and owned Main batch boundary. Global drain
fences protect award execution; they no longer define player-predicate timing.

Native nearest-player queries traverse the live native player list. Arbitrary
predicates and rich targeting conditions may observe a candidate before checking
distance: an index radius is not a callback boundary. `PlayerChunkIndex` supplies
preflight metadata, not replacement query execution. PlayerSensor's native raw
spectator/distance filter is a separate contract; its retained targeting state
is source-owned rather than shared mutable sensor scratch.

Stationary-player contact preflight may exclude a candidate outside the exact
native contact box only after its index entry proves source ownership. It never
reads foreign bounding boxes to justify exclusion. Intersecting contacts and
moving/non-player queries retain their conservative dependency census; native
query execution itself is unchanged.

Known NPC tick injectors have additional current-state exclusions: Carpet's
source-owned action map must be empty, and Fabric's custom-fluid registry and
the entity's retained fluid-contact set must both be empty. Only verified schemas
are cached; these collections are checked again before each local entry.
This neither freezes concurrent callback registration nor certifies arbitrary
transformed bodies.

**Cost:** global producers, unknown dependencies and Main-only native bodies
still stop conflicting gameplay. Independent clocks and concurrent admission do
not establish a throughput guarantee; historical benchmarks cannot prove one.

## 1. Identity and the transaction spine (A1 — SHIPPED)

`IslandRegistry`: stable island ids across topology rebuilds; explicit
MERGE/SPLIT/BORN/RETIRE events with deterministic survivor rules (merge: most
cells, tie → lowest id; split: largest fragment keeps identity, fragments
inherit the clock); self-check vs the BFS ground truth with reset-on-drift;
shadow deadline clocks (`logicalTick`, `lagNanos`, cost = Σ member-region
entity-phase EWMAs) and `/fabrolia regions islands` diagnostics (per-island
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
| `/schedule` datapack timers, TimerQueue | absolute W ticks | W | global timer queue runs on the W authority; function EXECUTION that targets island-owned state is dispatched to owners (A5) |
| scoreboards/advancements/bossbars | event-driven | n/a | global updates and deferred advancement awards/rewards use owned Main drains; complete advancement listener matching runs eagerly on the pre-admitted producer owner (§0) |
| portal cooldown (`portalCooldown`) | relative | L | none |
| active Nether portal charge | relative | L | advances with the entity owner; the accepted source-unit cohort transfers on a worker holding every source/destination owner claim |
| world border | W-anchored lerp | W | global |
| raids / Raid Omen | relative raid counters and effect duration | L(owner) | eligible expiry creates/extends the raid under exact island and POI authority; new raids are assigned immediately and tick once per owner unit; foreign ownership retains fenced Main compatibility |

Rule for every future mixin: reading "game time" during island execution
returns **L(owner)** via its authenticated source context, including routed Main, for relative-mechanic call
sites that vanilla uses as a monotonic per-tick counter (LevelTicks, hopper
cooldowns), and **W** for calendar call sites (schedules, daylight). The audit
that classifies every `getGameTime()` call site in 26.2 (~400) is the bulk of
A3 and is mechanical but mandatory; misclassification = the "mixed timing
models" failure the design forbids.

Scheduled-tick copies use the same authenticated clock selection as scheduling,
including routed Main and native-service context suspension. Copies across
island and WALL domains preserve the remaining delay.

Block events have one per-level canonical pending key and publication order
across shared input, borrowed captures and island stashes. Queue transfer preserves
both; duplicates cannot change eligibility or move an older event behind newer
input. Dequeue releases the key before tickability checks or callbacks, allowing
native self-reposts while still deduplicating the pending suffix. Each drain takes
one eligibility snapshot and does not pull newly published external input into a
recursive callback wave. Native callbacks run outside the event metadata lock.

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
  simulation but must not deliver mutations: attack/interact against an entity
  outside the source player's claimed island is rejected as stale/foreign client
  input. Same-island interaction remains on the owner lane; cross-island commands
  and `@e` selectors use their explicit A5 routing.

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
  Same-island attack/interact stays on that owner; stale or foreign entity ids
  are rejected rather than creating a Main boundary. Cross-island commands use
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

  Before the metadata tail evaluates native criteria or NBT, global resource
  requirements or disabled advancement buffering establish a global Main producer
  envelope. That envelope encloses the original accepted transfer's metadata,
  including its ordinary footprint/claim lifetime and completion edge.

  Only the transfer worker's own sends enter its metadata FIFO. Unrelated native
  sends keep their original order: recipient-wide capture can move a source
  waypoint UPDATE behind UNTRACK and disconnect the client. An arriving entity's
  tracking publication instead retains its exact schedule token and original
  TickTask, including pending pairing/bulk obligations. After metadata, source
  retirement and listener settlement, with cohort claims released, Main enqueues
  that retained task. Cancellation releases its token without publishing; all
  completion callbacks run outside reservation and camera-registry monitors.
  Existing destination trackers also retain ADD/REVALIDATE for an arriving
  viewer, including bulk and exact pairing commits. One exact tracker/viewer/
  listener wait returns the still-pending obligation to normal publication after
  settlement; entity state and ready viewers continue. A withdrawn unseen ADD
  creates no publication, and canceling a wait cannot erase a newer REMOVE.
  Already-offered chunk transport stays in the original connection EventLoop FIFO.
  Arrival readiness is separate from source custody and tick ownership. A
  destination clone stays indexed but cannot tick, tick riders, or enter the
  despawn pass until its metadata tail and the previous source UUID reservation
  have both retired. Draining callbacks alone does not open this gate: Main
  releases its owner claims before retiring the source. Readiness uses a
  separate volatile bit on the existing entity tick lease, not a removal spoof
  or a player-actor reservation token.
  `NetherPortalPreparation` preserves vanilla POI selection, world-border and
  portal geometry rules. Cold preparation and a genuinely missing portal run on
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

`/fabrolia regions islands`: per island — id, cells, entities, cost ms,
shadow TPS, logical tick, offset vs W, accumulated lag, age; plus the last
128 merge/split/born/retire events with reasons. The same numbers become the
real scheduler's telemetry when A2 flips (shadow → actual).


## 9. Worldthreader findings (studied 2026-07-12, full source + history)

These are historical design findings. The current contract is §0: independent
island admission with conservative global entry before unproven/shared producers,
not a whole-tick global gameplay lock.

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
  "temporarily global" tier. Current global entry is a pre-producer boundary (§0).
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
  distinct from Fabrolia's per-island deadlines. Whole-tick global gameplay
  exclusion is not required for independently admitted island units.
- **Exclusive-on-write scoreboards** — global updates and advancement awards
  stay on the owned Main lane. Advancement matching is not wholly W-lane work:
  it runs eagerly on the admitted producer's timeline (§0).

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
  due pulse-sensitive block ticks hold the complete unit, including the early
  pre-producer admission. Closed FULL halos can progress without core promotion.
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
  ONLY the global remainder lane. Chunk-phase arming (spawn/random/block
  ticks) routes per region through armGate: island work is never awaited by
  main and never accumulates (arm-stamp gate = missed cycles under lag,
  vanilla-lag semantics).
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
  A command originating inside a source unit retains that unit's exact clock,
  phase and effective handling-tick state only during its acknowledged body.
  Scheduled/event-phase posts retain the original same-drain event queue;
  later entity/block-entity posts remain next-unit work. Advancement preludes,
  native polling and result publication do not borrow this source context.
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

**Datapacks (Phase 1).** Tick/schedule functions keep vanilla W-cadence and
main execution, fenced per function body; /schedule stays on the vanilla
game-time queue. Exactly-once holds trivially (vanilla's single invocation,
only fenced). Phase 3 decomposes proven-island-local bodies to island lanes
at island cadence via FabroliaFunctionScopes metadata (MCFC emits it;
runtime validation is mandatory for dynamic selectors/macros/storage/
modded commands — unknown = fenced global path).

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
