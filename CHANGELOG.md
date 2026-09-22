## Unreleased

### Changed

- Admit independently owned island units concurrently instead of serializing
  all mutable gameplay. Retain real coordinator claims, generation receipts,
  per-island entity order and local clocks; acquire global ownership before
  shared or unproven producers enter. Native server/chunk orchestration is not
  a whole-tick gameplay owner, and no self-requeued gameplay driver remains.
- Match complete advancement listeners eagerly in native matcher-then-player
  order under pre-admitted producer ownership. Preserve callback results, RNG
  and uncached native NBT reads; retain only matched criterion keys for deferred
  awards/rewards. Prepare conservative requirements from the native carrier
  without prematurely resolving registry references or tags.
- Run complete Nether portal transfers, including players and passenger trees,
  on async island workers under exact source and destination claims. Retain
  ordered Main-only player/global metadata; static transfers run at a completed
  Main boundary with the active worker pool paused.
- Serialize missing Nether portal creation on physical Main. Retain the native
  decision while source and destination POI publications complete; preserve vanilla
  nearest-portal, radius, border and axis semantics for overlapping requests.
- Cache nonterminal chunk-send candidates and reuse unchanged distance order.
  Preserve lifecycle records, stable distance ties, concurrent publication
  invalidation and already-entered snapshots during reentrant preparation.
- Publish block-entity registration sequences with immutable cell snapshots.
  Merge them with reusable heads and a sequence-only heap while preserving
  registration order, sleeping/removal behavior and same-tick carry handling.
- Reduce advancement frame/deferred-batch copying and spawn-query callback
  allocation. The complete fire-time matching change above supersedes this
  optimization's earlier predicate timing; deferred rewards and tick cadence remain.
  Reuse MainSync queue entries as their claimed bodies and initialize the
  claim watchdog only when acquisition is contended; lease lifetimes stay unchanged.
- Execute complete use-on-block packets, including placement and opening containers,
  on physical Main under the existing owner claims and listener FIFO. Player ticks,
  movement and air item use retain their asynchronous lanes.
- Tick every region's entity set serially on its claim owner, including dense
  populations and TNT, removing collision-group planning, entity helper batches
  and their controls and metrics. Independent islands can overlap, but members
  of one island do not fan out entity ordinals. Dense single-region farms can
  lose throughput; no speedup is claimed.

### Fixed

- Bound optional exclusive gameplay by Main's native deadline while retaining
  finite queue cuts across waits. Preserve eligible native-FIFO checkpoints
  between terminal grants, without artificial empty-queue sleeps or capping
  required nested/topology/shutdown progress.
- Keep the initial native control drain separate from optional gameplay and
  retire redundant island-ready notifications without global admission. Empty
  hard-collider rebuilds no longer fence unrelated local island work.
- Remove Vanish warm-cache monitor contention only for a certified final native
  getter/setter graph. Publish the actual cache fields, preserve locked dirty
  refresh and invalidation, and leave unknown transformations unchanged.
  Reuse positive exact-player type certificates in hot observation queries
  without changing callbacks, geometry or unsupported-player authority checks.
- Report inclusive completed Main-loop cost separately from unfinished active
  work. Island metrics distinguish completed cost from queue/claim delays and
  stale progress instead of presenting an old cheap tick as current healthy work.
- Wake Main's selected-turn monitor when native control work is published, and
  service eligible routed requests before sleeping. Remove the artificial
  millisecond-per-request cadence loss without changing native FIFO order,
  selected-source ownership or logical-clock/deadline admission checks.
- Defer tracked Main-control publication through healthy requested/paused exact
  turns instead of rejecting packets as work from a dead pool generation. Preserve
  accounting, native FIFO order and fatal cancellation; preparation yields to
  pending controls, while mandatory quiescence drains their causal continuations.
- Preserve retained region FIFO when Main-safe work arrives after entity completion.
- Drain entered island units before async OFF/shutdown collapses their clocks.
  During topology rebasing, service only published Main-safe receipts under global
  Main ownership without stealing native controls or starving chunk progress.
- Run the complete native ender-pearl impact on globally owned physical Main,
  before owner admission, RNG or effects. Retain the source claim/clock through
  teleport, damage and discard so a second impact observes the first one's death.
  Ownerless/global-domain pearl trees use a complete pre-entry Main fallback.
- Retain complete Main producer ownership for fenced entity ticks, commands and
  disconnects when async islands and the player actor are OFF, including a
  disconnect before the listener first acquires an A5 binding.
- Classify idle player ticks and player passengers as global producers when
  advancement dependencies require it, including after resource reload.
- Evaluate deferred Nether metadata under global ownership when predicates or
  disabled buffering require it; finish player End-platform regeneration before
  destination insertion instead of staging it behind the arriving player.
- Keep accepted book-filter continuations in the listener's Main-control lifetime:
  normal disconnect discards stale callbacks without mutating the old item stack.
- Use the authenticated source clock for routed Main scheduled-tick copies,
  preserving remaining delays across island and wall-clock domains.
- Preserve one canonical pending block-event identity and publication order across
  shared, captured and island queues. Transfers and duplicate posts cannot reorder
  or accelerate pending events; dequeue permits native self-reposts, and each
  drain retains its original eligibility snapshot through recursive callbacks.
- Check chunk-population and scheduled-tick discovery confinement against the
  physical server thread, not the temporary logical gameplay worker. Preserve
  off-Main rejection while allowing C2ME FULL completions on Main during worker
  handoffs; do not let a logical worker mutate the Main-only population index.
- Keep topology/save admission closed while entered units finish routed Main
  work. Retrying quiescence releases physical claims, not the new-unit admission
  cut; restore prior intents by identity on success or failure.
- Preserve native E/BE/L order when an island body requires physical Main.
  Typed rendezvous retains the coordinator's claim, member/ordinal and L clock
  until the native body and exact fence acknowledgement have finished.
- Retain an owed player late body across soft lifecycle changes with its original
  early permit, listener identity and source clock. Publish healthy listener-batch
  continuations together, without clearing another player's still-owed body.
- Preserve source time, handling-tick state and phase-local block-event ordering
  in routed command blocks. Keep native polling and advancement preludes outside
  the command's source context; release ownership before publishing its result.
- Exclude proven-owned entities outside the exact stationary-player contact box
  before checking contact callbacks. Keep intersecting, foreign and moving-query
  dependencies conservative instead of forcing unrelated distant contacts.
- Recheck known Carpet action and Fabric custom-fluid state before local NPC
  entry. Active actions, registered custom fluids and retained fluid-exit work
  keep the original global path; this is not certification of arbitrary mod hooks.
- Preserve complete live nearest-player traversal and callback order, including
  selectors that run before distance checks. Spatial index candidates remain
  preflight metadata, not replacement native query execution. Give retained
  sensor targeting conditions source-owned state.
- Fix false closed/exclusive-pool rejection when native world generation builds
  a fresh, silent NPC ride, including ocean-ruin drowned/zombie-nautilus mounts.
  Require construction in the same active generation invocation and revoke that
  provenance before live indexing, transfer custody or waypoint publication.
  Preserve fatal-failure cuts and ordinary protection for players, shared entities,
  event-producing rides and removal hooks.
- Preserve native polling opportunities before servicing residual exclusive
  gameplay. Chunk progress does not borrow gameplay authority or an island clock;
  generation remains on its native executors.
- Rotate residual exclusive grants across ready, unit, global, Main-safe and
  portal work so unclaimable notifications cannot starve coordinators. Suppress
  redundant Main-healer notifications while a real one remains outstanding.
  Retire a bounded obsolete prefix while preserving claim, receipt and fail-stop
  cleanup and units-only filtering.
- Fix `generation returned before its POI publication` when cold portal creation
  triggers independent world-generation POI writes. Wait for publication without
  repeating native creation; enqueue generation's POI changes behind earlier
  worldgen callbacks instead of executing them inline. Denied creation also
  observes preparation, publication and first-failure gates.
- Capture complete advancement predicates before item-use effects change player
  state, fixing poisoned-at-one-heart milk and honey criteria without item-specific
  exceptions.
- Fix ordinary Nether portal disconnects caused by moving a locator waypoint
  UPDATE behind its UNTRACK. Preserve unrelated native send order; hold each
  arriving entity's exact tracking publication until metadata and source custody
  settle. Hold new destination pairings to arriving viewers without delaying ready
  viewers or entity state; retain ridden-boat identity and vehicle control.
- Require a complete owned write halo before newly FULL halo cells execute
  scheduled work. Keep refused fluid ticks pending and preserve whole-unit
  pulse-sensitive block holds. Do not misidentify Java lambdas as external mods
  in cross-domain write diagnostics; illegal writes still fail closed.
- Keep Nether arrivals out of entity and passenger ticks until ordered metadata
  and the preceding source UUID reservation both retire. This fixes repeated
  minecart chunk-loader cycles losing rail height and motion when the next
  portal entry races delayed Main metadata.
- Move island-owned player End/other non-Nether destination lookup and teleport
  together to the fenced Main listener FIFO. Install source cooldown before
  later block contacts; preserve first End-return credits after the owed late
  listener tick, including piston contact, without breaking non-async callers.
- Admit cold chunk tickets inline for the exact player actor, preventing
  `No chunk holder after ticket has been added` during native blocking loads.
- Linearize item/XP pickup, damage, merging and portal reservation on stable
  per-entity guards. Source/camera-custody capture declines busy identities
  instead of deadlocking a Main lifecycle fence; preserve native XP selection,
  complete transactions, reentrant own-tick capture and failure cleanup.
- Preserve chunk-delivery generations across packet materialization, complete
  entity-index and saved-data transaction boundaries, and exact lifecycle
  ownership during Main publication, shutdown and packet continuations.
- Retain POI column residency during iteration and publish consistent spawn,
  navigation, topology and portal-source snapshots to concurrent readers.
- Refresh NeoForge synchronization configuration caches on initial load and
  reload; preserve collection view and iterator mutation contracts.
- Defer Nether preparation and static transfer batches when a routed command
  needs Main. Unwind the pool-pause request and replay accepted publications
  before the command executes, preserving live causal handoffs and queued
  next-unit work without granting premature exclusive ownership.
- Bind the canonical Nether listener FIFO before the first Main player body,
  including when player actors are disabled. Keep the complete entered body
  ahead of portal capture, later packets and listener callbacks; idle Main-only
  bindings retain vanilla packet admission and terminal listeners stay closed.
- Defer whole incremental entity saves and unloads while portal capture or
  arrival metadata overlaps the saved ride component. Prevent Main from fencing
  its own entity-save claim during unload without granting additional ownership.
- Keep static Nether transfers out of the inline Main path while the worker
  pool is active. Avoid impossible static-island promotion and retain queued
  next-unit work behind the existing portal-preparation pause.
- End Main's tick-local departure view before post-tick portal validation.
  With asynchronous player ticks disabled, a live queued player no longer
  appears removed and stalls the transfer.
- Initialize raid ownership views and the concurrent raid registry on both live
  creation and saved-data loading. Prevent async startup crashes when a world
  already contains raids, without skipping or discarding the saved raids.
- Preserve per-instance seeded random sequences across player workers and Main.
  Enchantment results now contain the advertised enchantment and level with
  asynchronous player ticks enabled. Keep worker-local random streams only for
  shared world entropy; explicit reseeding restores canonical replay.
- Keep acquired Nether transfer claims valid when Main closes admission.
  Refuse the still-reversible cohort before teleporting, restore its source
  poses under the held claims, and retain strict passenger ownership checks
  instead of crashing on a late fence.
- Remove Bad Omen when its tick adds Raid Omen, without losing iterator removal
  after an insertion-only effect-map mutation. Start eligible raids on the
  player's island and assign them immediately to the owning unit cadence.
- Prevent portal preparation from waiting for regional tasks that belong to a
  later island unit. Keep running helpers and foreign obligations fenced; do
  not weaken ordinary exact-player pauses or pool shutdown.
- Distinguish arriving entity clones from their old source reservations despite
  shared UUIDs. Order subsequent waypoint changes behind the arrival metadata,
  including removal before Main resumes, without quarantining the new clone.
- Preserve mounted player/spectator custody during Nether transfer and retire
  stale source-dimension tracker tasks without retargeting their execution lease.
- Marshal unowned foreign command API calls to physical Main before taking
  command capabilities or island fences. Preserve exact arguments, synchronous
  completion and the canonical failure; cancel only unentered work on shutdown.
- Keep portal-reserved minecarts and their riders out of later cart collisions,
  including the experimental movement queries. Retain a live reserved source
  in the regional tick index so a refused transition resumes normal ticking.
- Route cross-dimension arrivals by their actual destination owners using one
  topology snapshot per level. Preserve cohort receipts and inherited FIFO
  through migration, and await acceptance outside publication queue monitors.
- Allocate each server dragon and its parts as one contiguous identity group
  before publication and destination remint, matching the client's head/part
  offsets without changing damage rules or sitting projectile immunity.
- Complete tracker work at the unconditional chunk-source boundary, including
  idle levels whose entity iteration is skipped, before any later exact-player
  pause. Preserve entered-task cleanup and the original failure.
- Keep an accepted entity dispatch's event/legacy protocol bound to its domain
  epoch when the desired event-scheduler setting changes. Preserve worker,
  dependent-color and healer completion instead of stranding its barrier or
  running a legacy entity phase through READY. A pre-dispatch queue claimant
  must observe the new plan's acceptance before entering its entity body.
- Fix chunk delivery permanently stalling when every selected snapshot becomes
  stale or cancelled before admission. Release the exact empty batch's sender
  slot in its Main publication commit, before EventLoop consumption; retain
  successor delivery, packet order and exactly-once ACK accounting.
- Keep unowned chat/filter continuation registration on vanilla's executor path
  during async disable and exclusive player turns. Preserve callback ordering,
  atomic body admission and canonical registration/body failures.
- Restore enclosing Main claim interest after nested routed fences and local
  quiesce cleanup, so an outstanding waiter does not lose its chaining/pacer guard.
- Keep island coordinators from taking unrelated ready-region bodies while
  waiting on their own entity barrier. Preserve own-frontier helping, global
  and Main-safe dependency progress, and ordinary workers' accepted ready work.
- Resolve published cold-cell ownership before Region materialization instead of
  treating an owned halo as a Main gap. Use the same answer for chunk snapshot
  leasing, fire-time validation and deduplicated mailbox migration.
- Preserve exact player-tracker publication authority across repeated topology
  rebuilds and spatial changes during an attached A5 handoff. Bind the tracker,
  source session and authorized cadence together; retiring cadences require
  participation in the current topology transaction's immutable BEGIN snapshot.
  Keep real-player source publication intact until its post-physics or Main close.
- Fix owner-initiated disconnect crashes when the queued vanilla callback attempt
  runs before the network channel closes. Retain the original owner and pending
  admission cut until physical `onDisconnect` commits; preserve exact terminal
  receipts, existing handoff ownership and failure propagation.
- Fix null status-effect expiration callbacks after reentrant removal. Preserve
  vanilla-style fail-fast traversal under the effect mutation monitor without
  blocking cross-entity effect reads; direct map and view mutations share that monitor.
- Keep suspicious sand/gravel brush cooldowns and reset deadlines on the block
  owner's clock, including with player ticks disabled. Preserve ordinary cooldown
  and inactivity boundaries and recover stale future cooldowns after clock changes.
- Preserve shulker-box contents when placing across region boundaries with player
  ticks enabled. Keep block creation, block-entity component import and item
  consumption synchronous instead of deferring only the block write.
- Preserve owed Main listener ticks behind queued packets and entity ordinals
  instead of dropping them, fixing stalled item use for mounted players.
  Retain FIFO order through player replacement and cancel unentered work on
  exact terminal disconnect without masking an entered listener failure.
- Fix player deaths caused by melee across region boundaries. Authenticate the
  claimed color-excluded neighborhood against both indexed and live player
  positions; retain active-player exclusion and the owed post-early listener tick.
- Queue frameless advancement drains instead of executing them inline inside
  packet/inventory producers; preserve retained rewards across fence transitions.
- Drain tasks retained behind a completed global entity epoch during shutdown without
  advancing the simulation clock. Keep the region FIFO, including late admissions
  and causal children, serialized on physical Main after the old claim tail finishes.
- Fix an autonomous player-tick crash while that player's disconnect is queued or
  waiting for its existing Main handoff. Authenticate the exact listener and
  disconnect origin before cancelling a new, unentered ordinal; preserve the
  original teardown task and reject overlapping entered bodies or foreign sessions.
- Serialize cold typed entity-index publication with complete additions and
  removals on Fabric and NeoForge; keep cached index reads concurrent.
- Fix inherited packet-listener server access so the interaction ownership mixin
  boots on Minecraft 26.2. Preserve raw network requeueing and vanilla interaction
  order without observing player state before the correct execution venue.
- Bind cold cross-island tasks to the destination domain when claimed, and make
  public island-ownership queries require affirmative current authority.
- Preserve deferred POI notifications across drains without section/graph lock
  inversion; preserve scheduled-tick cloning and outstanding work on failure.
- Publish block-entity registration/index snapshots in a consistent order and
  retain replacement registrations when old holders retire.
- Protect primitive-map reads during rehash and publish append-list entries before
  exposing their prefix. Grow beyond the former 16,384-entry capacity.
- Compute accepted spawn potential from the actual mob position and biome rather
  than a shared candidate memo. Preserve accepted counts and energy-budget rules.
- Make native local-cap arena acquisition atomic with retirement and retain the
  creating provider for the arena's entire lifetime.
- Publish navigation snapshots consistently and preserve notifications across
  nested explosion drains. Execute vanilla explosions when Lithium is absent.
- Fence complete server-global saved-data encoding and dirty retirement, not only
  per-level saves. Retain an existing outer fence and persist accepted later updates.
- Previously isolated advancement contexts and kept deferred player-condition
  evaluation serial under its existing safe owner without replaying an entered
  prefix. Superseded by complete fire-time matching above: player conditions
  are no longer deferred.
- Preserve portal departure reservations through destructive entity paths and
  distinguish physical Main from logical thread aliases during reload completion.
