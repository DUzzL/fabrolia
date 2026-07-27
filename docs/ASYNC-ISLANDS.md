# Fabrolia async islands — independent per-island ticking

Status: **Stage A1 implemented and live** (persistent island identity, merge/split
event transactions in shadow form, per-island shadow clocks, diagnostics).
Stages A2–A6 designed here, each with its flip gate. Async execution stays OFF
until every gate below is satisfied — an unsafe partial flip is worse than the
global tick.

## 0. Ground rules inherited from the current architecture

- Islands = connected components of populated 8×8-chunk cells with a **≥3-cell
  (≥24-chunk, ≥384-block) unloaded gap** between distinct islands (BFS radius-2
  merges anything closer). This gap is the load-bearing safety margin for the
  whole design: nothing in vanilla observes, mutates, or traverses 384 blocks of
  unloaded chunks within one tick — not explosions (≤~16 blocks), not pistons
  (≤13), not redstone (wire ≤15/update batch), not entity motion (≪64/t), not
  sensors (≤32), not tracking (≤ view distance… see §6 for the one exception).
- Region grid, color gate, per-region queues, island-partitioned scheduled/BE
  ticks, the persistent BE cell index, LPT + fan-out + steal all carry over
  unchanged: an island's internal tick keeps today's exact intra-island
  execution model. Async time changes WHEN an island ticks, never HOW.

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
| scoreboards/advancements/bossbars | event-driven | n/a | grants/updates already funnel through main drains; stays on the global lane |
| portal cooldown (`portalCooldown`) | relative | L | none |
| world border | W-anchored lerp | W | global |
| raids | per-level, W-anchored timers | W + owner island execution | raid state machine reads W; its entities tick in L — accepted skew equals vanilla-under-lag |

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
  simulation but must not deliver interactions: attack/interact packets against
  an entity owned by a different island are queued as a merge trigger — the
  interaction executes only after the domains share one timeline (bounded by
  one merge latency, ≤2 ticks; identical UX to today's off-by-a-tick hit
  registration under latency). Same lane handles cross-island commands and
  `@e` selectors (A5).

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
  reads the island timeline (fixes itself — the counters are relative);
  cross-island packets (attacks, commands) queue through the merge-trigger
  lane. Clients experience a slow island exactly as they experience today's
  lagging server (fewer movement updates), with no protocol changes. Flip
  gate: two-client differential (one on a lagged island, one on a fast one)
  with packet-order assertions, plus the anti-cheat suite.
- **A6 — cross-dimension transfer**: portals get an explicit two-phase
  handoff: (source island) entity serialized + removed under its L, ticket
  placed on destination, handoff record journaled → (destination island, its
  own clock) entity deserialized with relative timers intact (all portal
  state is relative — §2), destination chunk guaranteed loaded via the ticket
  BEFORE the record is consumed; the record is the single ownership token
  (exists in exactly one place; crash between phases = record replays on
  boot, entity restores at destination — never duplicated because the source
  save happens after removal, never lost because the journal is fsynced with
  the region save). Nether portal linking/End portals/mod portals all funnel
  through the same `PortalTeleportationManager` seam Fabrolia already defers
  through today.

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

Adopted directly:
- **Serialized handoff record** (their `TeleportedEntityInfo`: NBT + portal
  processor state + position snapshot + recursive passengers, queued to the
  destination's receive phase, with a RECOVER_FAILED_TELEPORTS phase restoring
  from NBT when the destination rejects) — A6 adopts this shape verbatim,
  adding the fsynced journal for crash windows their model lacks.
- **`getAllLevels()`/`getLevel(key)` interception as THE tripwire** for code
  that needs multi-world access (their exclusive_world_access mixins): this is
  the exact detection point Fabrolia's mod-compat mode will use — route to the
  compat ladder (owner-routing → queue → merge → exclusive domain → reject
  with diagnostic) instead of their always-exclusive fallback.
- **Exclusive-access implementation template**: their semaphore-permit yield/
  reacquire system (adopted after their park/unpark polling missed wakeups —
  commits 19e9373, 5300f4a) is the correct shape for Fabrolia's LAST-resort
  "temporarily global" tier; Fabrolia's wake protocol is already permit-style.
- **Portal-creation race** (their duplicate-nether-portal bug, 03ae1c4):
  portal CREATION is a cross-domain write — in Fabrolia it rides the
  merge-trigger lane before the portal can link.
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
- **Global barrier per tick** — their two-Phaser-per-tick model is what the
  island architecture exists to avoid; localized merge domains replace it.
- **Exclusive-on-write scoreboards** — Fabrolia keeps scoreboards/advancements
  /bossbars on the W lane entirely (stricter and simpler than their CHM +
  atomic-score hybrid, which their own TODO calls unsolved).

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

- **EpochDomain** — one scheduling timeline per barrier scope: epoch,
  dispatchedEpoch, entity barrier + pending counter, per-domain entity tick
  token, L-clock (`unitGameTime`), deferral queue, PIsland owner backref.
  Every Region carries `domain`; ALL eligibility/completion/barrier
  bookkeeping compares in the region's own timeline. ColorGate skips
  cross-domain neighbors (separation invariant: no same-tick causality
  crosses the gap). Domain reassignment only at safe points, with all five
  epoch stamps translated (RegionizedServer.assignDomain).
- **IslandTickUnit** — the unit is a worker-run COORDINATOR:
  [scheduled block/fluid ticks collected at the island's L] → [its block
  events] → [entity phase via the R5 event machinery on its own barrier,
  coordinator help-pumps the pool (never other units) with a domain-scoped
  liveness backstop] → [island-internal deferred cross-region replay] →
  [BE phase: persistent cell index + mid-unit registrations same-tick] →
  [L++, real cost, deadline roll]. Deadline roll re-anchors only when ≥1
  FULL tick behind (sub-tick drift keeps phase — re-anchoring on ε starved
  idle islands against the 20 Hz drive grid).
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
- **Routed (fenced batch)** — everything else. Exactly ONE execution on the
  server thread inside the per-tick routed batch: one fence (quiesce of all
  islands; PARKED waiters count as quiesced via their waiting flag), all
  queued commands executed in order, fence released, THEN waiter futures
  completed — a resuming coordinator can never observe another command's
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
acquisition converges. Command fences never wait on parked waiters at all.

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
