## 3.1.9-alpha (2026-10-19)

Status: 1502 tests pass (8 optional skip), Fabric/NeoForge builds and the Fabric
artifact check pass. The 40-client stand (`verify`) has not been run.

- Fix: the region access detector reported a cross-region write as `UNSAFE
  … (target running)` whenever any thread held the target region, including
  the writing worker itself. The serial island entity pass claims every region
  of its island on its own worker. A write across a region border inside the
  same island is vanilla behaviour, and it is not concurrent. The production log
  of 2026-10-18 shows 8 such reports between 10:33 and 10:38 server time: a bat
  stepping on redstone ore, a wind charge toggling trapdoors and a player
  breaking a block. None of them crossed an island: the log has no
  `CROSS-DOMAIN` report, and that check fails closed. These reports used up the
  detector's budget of 8 stack logs 28 minutes after start, so a real concurrent
  write later that day would not have been logged.
- `UNSAFE` (log and `crossUNSAFE` counter) now means a claim held by another
  thread, and the message names that thread. The cross-domain check and its
  fail-closed path are unchanged.
- Regression coverage: `RegionAccessDetectorTest` classifies a write into a
  region held by the writer (not unsafe), by another thread (unsafe) and into an
  idle region (not unsafe).

## 3.1.8-alpha (2026-10-18)

Status: 1499 tests pass (8 optional skip), Fabric/NeoForge builds and the Fabric
artifact check pass. The 40-client stand (`verify`) has not been run.

- Fix: production crash 2026-10-18 15:21 (server time, UTC−4, 43 players):
  `PlayerAdvancements.flushDirty(true) off the player's owner thread` for a
  rider on `Fabrolia-Region-Worker-13`, session phase MAIN, reached through two
  nested `tickPassenger` frames. A vehicle ticking on its island, such as a moving
  minecart, picked up a mob that a player was riding. Vanilla's passenger loop
  then ticked the Main-owned rider on the worker. The root visit routes player
  trees to MAIN_SYNC before their tick, but the vehicle's own tick attached the
  subtree after that decision. The lifecycle lease skips the rider by design,
  because ordinary MAIN players have no island body to exclude. Riders are never
  A5-owned, so this rider always belonged to Main.
- `fabrolia$guardTickPassenger` now hands such a subtree to MAIN_SYNC before it
  takes the tick lease. The condition is a ServerPlayer in the subtree that the
  region worker does not own, the same authority `flushDirty` checks. Main runs
  vanilla's `tickPassenger(vehicle, passenger)` in the same tick, under its
  island's claim and its own "Ticking entity" crash boundary. It skips the body
  if the passenger has been remounted or has dismounted since the handover. From
  the next tick on, the root visit routes the whole tree to MAIN_SYNC, as before.
  The MAIN_SYNC function pass of a passenger subtree owns that subtree, not the
  vehicle above it.
- Accepted timing deviation: only in the tick in which the pickup happens, the
  picked-up subtree ticks on Main after the island's entity pass, instead of
  directly after its new vehicle. This is the lane every player-ridden tree
  already takes. Vanilla's tick count per entity is unchanged.
- Regression coverage: `ForeignPlayerPassengerDeferralTest` (hand-over before
  the lease, ownership authority, passenger body without checkDespawn, no
  hand-over off region workers, subtree ownership of the function pass).

## 3.1.7-alpha (2026-10-17)

Status: 1486 tests pass (8 optional skip), Fabric/NeoForge builds and artifact
checks pass. The local portal-loader run validates both verbose rows at 10/20
TPS, item conservation and ticket expiry. The production stand is still pending.

- Fix: `/fabrolia regions top verbose` reports the shared target TPS for
  portal-linked islands. At a shared 10 TPS, the Nether partner now shows
  `tps=10.0/10.0` and `RATE-OVERRIDE`; both return to the global target and
  clear the marker when the group is restored to 20 TPS. The diagnostic uses
  the same target resolver as the regular info and HUD displays.
- Regression coverage checks the actual formatted row when the partner inherits
  a rate and when the override is removed. The physical portal-loader driver
  can also validate both verbose rows with `--expect-shared-target`.

## 3.1.6-alpha (2026-10-17)

Status: 1486 tests pass (8 optional skip), Fabric/NeoForge builds and the Fabric
artifact check pass. Original-world runs and a matched saved-world restart pass
locally. The 40-client production stand has not been run.

- Fix: stacked raids select and tick in vanilla's native registry order again.
  `getNearbyRaid` keeps the first raid at an equal distance. The striped
  concurrent registry changed that tie, and island ticks separately used an
  unordered subscription set. This changed villagers' `raid`/`pre_raid`
  activity and the timing of the farm's bell release relative to its moving
  workstations. The corrected original-world runs relocate to the fallback
  village as vanilla does, instead of losing the overlapping raids.
- The registry publishes immutable native-table clones with vanilla's initial
  size; the codec constructor installs it before inserting saved raids. Island
  subscriptions still decide ownership, while a private native cursor decides
  tick order and iterator removal, copying only on an actual cleanup. Registry
  monitors cover structural operations, never raid/world callbacks. Independent
  islands keep their cadence.
- Match vanilla's stopped-raid cleanup boundary: a raid stopped during its tick
  remains registered until the next raid phase, including across a topology
  handoff. Stale Main subscriptions to removed raids are still cleaned up.
  Missing villages still cause vanilla's immediate STOP/LOSS; no grace,
  forced POI claim, artificial raid ticket or clock change is introduced.
- Regression coverage compares the actual Minecraft lookup with the published
  lookup, table growth/removal/reuse, concurrent island registration/cleanup,
  dispatch order and the next-phase cleanup subscription. Details and runtime
  evidence: [raid registry parity](docs/RAID-REGISTRY-PARITY-2026-10-17.md).
- Final jar: fresh serial run (8 min) 13 raids, 86 waves, 7 victories, no loss or
  early stop; parallel (12 min) 21 raids, 146 waves, 14 victories, no loss or
  early stop. The 12-minute vanilla reference has 21 raids, 141 waves and 14
  victories without losses; the unchanged 3.1.5 repeats seven losses. All
  completed raids have eight waves. A saved-world restart matches vanilla's
  three initial losses and one immediate unstarted stop, then continues waves.
- Additional local validation: physical Nether portal loaders pass against
  vanilla, 3.1.3, 3.1.4 and 3.1.6 with Lithium in both entity modes. Eight mobs
  cross each way, item stocks remain exactly 2304/2304, slow-member/rate changes
  recover and both portal chunks unload after shutdown of the clocks. This
  reviews the 3.1.4 coupling correction without changing runtime code; the
  inherited `top verbose` target-rate display defect is documented separately.
  See [portal-loader review](docs/PORTAL-LOADER-REVIEW-2026-10-17.md).

## 3.1.5-alpha (2026-10-17)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass. The Omen
fix is verified with the original farm world and a real protocol client (both
with Lithium). Late raid losses still need investigation; this is not a complete
farm-parity certification. The 40-client stand has not been run.

- Fix: Bad Omen survives its conversion to Raid Omen again, as in vanilla
  (production report 2026-10-16: Lemon_Iced's self-sustaining raid farm is slow on
  the server and at full rate in singleplayer). `BadOmenMobEffect` inserts Raid
  Omen into the player's effect map and returns false; vanilla's `HashMap`
  iterator then throws from `remove()`, `LivingEntity.tickEffects` catches it and
  Bad Omen stays. The farm depends on that: one bottle starts a raid every ~30 s
  for the rest of its 100 minutes. Fabrolia's thread-safe `EffectMap` removed the
  expiring effect after an insertion by design, so every conversion consumed the
  Bad Omen and the farm started one raid per bottle. Measured over 5 minutes with
  the same world and corrected client: vanilla 8 raids and 152 pillagers, 3.1.3
  1 raid and 34 pillagers, 3.1.5 8 raids and 169 pillagers (parallel: 8 and 166).
  The one 3.1.3 raid ran normally to victory.
- `EffectMap`'s monitor-owned iterator now keeps `HashMap`'s fail-fast contract:
  after a structural change both `next()` and `remove()` throw and the current key
  stays; `remove()` removes by key. Unowned readers keep the weakly consistent
  `ConcurrentHashMap` traversal; writers still take the monitor.
- Local original-world checks: the initial 10-minute runs triggered 18 raids in
  vanilla and 18 with 3.1.5 (17 with parallel island entities); the subsequent
  9-minute pair triggered 15 each. Combat-rate claims from these runs were
  withdrawn: the protocol client pinned the saved player position above the
  floor, intermittently disabling sweep attacks. The client now stands on the
  actual floor and the probe records accepted attacks and sweeps. Bell POI
  releases also occur in vanilla; they are not evidence of a Fabrolia clock bug.
- Corrected 9-minute runs: all 542 accepted attacks sweep in vanilla and both
  3.1.5 modes, with 392/371/368 pillager kills. Both Fabrolia runs still lose six
  raids during a bell-claim gap, including a new raid at ~5 % of its countdown;
  vanilla relocates to another occupied workstation and loses none in this run.
  The phase of the gap relative to the moving workstations remains under review.
  A controlled six-tick absence of the bell and fallback workstation also stops
  a vanilla raid at ~6 %; the vanilla loss rule is preserved.
- Tests: `EffectMapTest` and `LivingEntityEffectIterationTest` compare the same
  turns against vanilla's `HashMap` (including Bad Omen → Raid Omen over two
  ticks); the new comparisons fail on 3.1.4. Two tests that pinned the deviation
  were replaced. 1480 tests pass, 8 optional skip.
- New tool `tools/raid-farm-ab/`: original farm worlds on a local server with a
  real protocol client and an observational probe mod. Details:
  [`docs/RAID-FARM-OMEN-2026-10-17.md`](docs/RAID-FARM-OMEN-2026-10-17.md).

## 3.1.4-alpha (2026-10-16)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass. Display
only; not yet seen in game.

- Fix: `/fabrolia regions top` called nearly every island "Lagging" at a steady
  19.8 TPS (production, 3.1.3, 110 islands). An island whose clock was more than
  40 ticks behind the level's game time counted as lagging, but islands never make
  up ticks they lost (known open item), so one slow moment, e.g. at a restart,
  leaves a permanent offset of 60-130 ticks. The verdict now uses the current rate
  only: Healthy at 90 % of the island's target or more (18 TPS at 20), Lagging
  below, Stalled when a unit holds the island for over 3 s, Paused while the game
  is frozen (`/tick freeze`; replaces "Idle", which only an empty TPS window
  produced). The offset is no longer shown as "N ticks behind" in the figures;
  hovering the island name explains it as the island clock's distance to the world
  clock. The same verdict feeds the lagging count in `/fabrolia regions`.
- Island and Main TPS average the last five seconds, refreshed every second. A unit
  of 60-100 ms pushes one tick into the next second, so a one-second window read 19
  and then 21 for a steady 20 TPS island ("20.8 TPS" in the same list); over five
  seconds the step is ±0.2. HUD, Carpet `/log tps`, `here`, `list` and `top` all
  read this value.
- `regions top` explains the verdict below the list ("Healthy = within 10 % of its
  target TPS").
- Fix (cross-dimension coupling, `CrossDimLinks`): the phase heal no longer pulls
  healthy linked islands into the past. When one member of a linked
  Overworld/Nether group waited with a deadline 100+ ms overdue (a Main fence, a
  busy pool), the heal saw the group as "more than two periods apart" and anchored
  every member to that stale deadline. A healthy partner then ran an extra early
  unit (briefly above 20 TPS) and was charged a missed deadline and ~105 ms of lag
  it never had (reproduced with the real classes). The pacer fires an island at
  `max(now, deadline)`, so an overdue deadline already means "due now": the heal
  now measures the spread over `max(deadline, now)`, and no anchor is written in
  the past (it is clamped to the moment of anchoring). No member fires later than
  before and the cadence lock is unchanged; deadlines that really are more than two
  periods apart are still re-phased. The portal transfer lane (`CrossDimArrivals`,
  `NetherPortalTransfers`, `EndPlatformHandoff`), which carries items, mobs and
  portal chunk-loader traffic, has no reference to the coupling and is untouched.
  Three new tests; two of them fail on 3.1.3.
- Cross-dimension coupling in the displays: an island's target TPS (colours,
  Healthy/Lagging) is now the rate its units actually roll by, i.e. the coupling's
  shared rate when it is portal-linked; a rate inherited from a linked island reads
  `rate 10.0 (set on a portal-linked island)`. `regions here` counts the whole
  linked group ("linked with 3 islands (2 Overworld, 1 Nether)"), not "1 island in
  the other dimension". The HUD shows `Linked xN` and at most two partner rosters,
  since a group can hold many islands.

## 3.1.3-alpha (2026-10-16)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass. Not tried
in game or on the 40-client stand (the change is display-only; no tick path changes
apart from a once-per-second Main read of each island's unit counter).

- Fix: island TPS was missing or 0. Each island's TPS window started at 0 and moved
  only when a command or the HUD read it, so the first `/fabrolia regions top` or
  `list` after an island formed (or that nobody had looked at) showed 0 TPS and
  called the island "Idle". `TpsHud.tick` now samples every async island once per
  second, with or without HUD subscribers, and an island without a complete
  one-second window shows "measuring…" (HUD and Carpet `/log tps`: `--`), never 0.
- Fix: info replies were sometimes not shown. They used `sendSuccess`, which Minecraft
  drops for players while the `sendCommandFeedback` gamerule is off. Read-only
  replies (`/fabrolia stats`, `stats entity`, `regions`, `regions list`/`top`,
  status queries, `functions plan`/`fences`) now always show. Commands that change
  a setting still announce it through `sendSuccess`.
- Readable layout: `/fabrolia stats`, `stats entity`, `regions`, `regions list` and
  `regions top` are now one message each, with a title, blank-line separated
  sections and indented `key: value` lines. TPS and MSPT are coloured against their
  target (green/yellow/red). The old `top` rows padded columns with spaces, which do
  not line up in the chat's proportional font. An island is now two lines: its name
  (hover for its rate/sprint handle) and its state, TPS, average/max MSPT, cells, lag
  and pacing. `regions` shows Main TPS/MSPT, the island count with lagging/stalled
  islands and the slowest island. Dimensions are named (Overworld, Nether, End);
  `stats entity` says "parallel"/"main" instead of "fabrolia"/"sync". Suggested
  follow-up commands are clickable.
- New: `/fabrolia regions here` answers "what TPS does this spot run at" once,
  without subscribing to the HUD: the island under the player (name, state, TPS,
  MSPT, players on it, portal link) or, off any island, that the main thread runs
  it at Main TPS. `/fabrolia regions player <name>` does the same for another
  player (permission level 2, node `command.regions.player`).
- New: `/fabrolia` and `/fabrolia help` list the commands the sender may run, each
  with one line of purpose (clickable), plus what "island", "TPS" and "MSPT" mean.
  Before, `/fabrolia` alone did nothing.
- HUD (`/fabrolia regions tps`): values are coloured like Carpet's; the island is
  named by its players (`Island [Alice Bob] 19.7 TPS 2.1 ms`, alias in front);
  "GLOBAL", "SYNC" and "XDIM" read "Here: main thread", "Islands off" and
  "Portal-linked".
- `regions list` shows one line per island (all figures on hover) so a server with
  many islands fits in the chat history; `stats entity` lists the top 10 types by
  default. An island without players is labelled `No players @x,z` instead of
  `Unowned@x,z` ("unowned" means a spot no island owns).
- Unchanged: `/fabrolia regions top verbose` keeps its one-line-per-island diagnostic
  rows; the stand drivers and the analysis docs parse them (` L=`, `island#id/gen`,
  `[handle]`). An unknown `top` sort key is now rejected instead of silently
  sorting by MSPT.

## 3.1.2-alpha (2026-10-13)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass. The
isolated Minecraft 26.3 minecart differential matches Vanilla. The production
modpack's 40-client stand verification has not been run on this Windows host
(WSL/Linux and the production stand template are absent).

- Fix: with `islandEntityParallel=true`, ordinary and hopper minecarts spanning
  a region border were ticked in colour-wave order instead of Vanilla insertion
  order. `AbstractMinecart.push` changes both carts' current momentum immediately,
  so a different first collision changes the next cart's movement and subsequent
  collisions. Only TNT minecarts had been protected. Every `AbstractMinecart`,
  including storage, furnace, spawner, command-block and modded carts, now selects
  the existing serial entity pass for its island unit, just like primed TNT.
  Cart movement and impulse calculations remain the composed Vanilla methods.
- Scope/cost: a unit containing any cart at entity-mode selection ticks the
  **whole island's entity phase serially**, even for a stationary cart. Independent
  islands still run concurrently. This can lengthen dense islands' units and
  Main's waits for those islands. The opt-in parallel-order deviation no longer
  applies to units containing carts at that selection point.
- Reproduction: actual 26.3 Fabric/Carpet servers, both cart insertion orders,
  rideable/rideable, rideable/hopper and hopper/hopper pairs, same-region x=64 and
  border x=128, moving into stationary or approaching carts. Over 24 cases and
  five physical ticks, 3.1.1 parallel produces 120 position/momentum differences;
  3.1.1 serial and 3.1.2 parallel each match Vanilla within 1e-7. An independent
  item's age checks one physical tick per step. No TNT masks the parallel path.
  The production log names 3.1.1 but does not record `islandEntityParallel`;
  the player's exact farm/configuration was not available for replay.
  Another 32 slow/stationary-pair and three-cart-chain cases with Lithium
  0.26.2 on the candidate also match the actual Vanilla reference exactly
  within that tolerance. `tools/stand/minecart-parity-driver.py` preserves the
  fixture and fails on differences (or explicitly reproduces the old failure).
- Regression tests: all seven Vanilla cart types and a modded subclass select
  the serial pass; actual 26.3 `Minecart.push` calls demonstrate the noncommuting
  impulses of a three-cart chain. Three tests fail before the fix and pass after.
- Scoped performance comparison: two 60-second Spark captures of the same
  160-cart powered-loop fixture with Lithium and two workers both remain at
  20 TPS. Island mean MSPT rises from 1.76 to 3.07; sampled worker running time
  rises from 1.48 to 2.99 ms/tick, while Main `tickServer` changes from 1.34 to
  1.38 ms/tick. The frames confirm the move from `tickOwned` to the serial pass.
  Windows uses Spark's Java sampler; this small fixture does not establish the
  cost on a 40-player production island. Full evidence and limits:
  [`docs/MINECART-PUSH-FIX-2026-10-13.md`](docs/MINECART-PUSH-FIX-2026-10-13.md).

## 3.1.1-alpha (2026-10-13)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass;
verified on a local Fabric dev server with one real protocol client; the
40-client stand verification has not been run.

- Fix: raids stopped about a second after the Raid Omen expired (production
  report 2026-10-13: the raid bar shows for a second and vanishes, no defeat,
  no wave). Cause: a block change on an island published its POI change late.
  `ServerLevel.updatePOIOnBlockStateChange` hands the POI add/remove to
  `server.execute`. Vanilla runs that inline on the ticking server thread
  (26.3 handles packets in `processQueuedPackets` and ticks levels outside any
  event-loop task), so a moved or placed job site, bed or bell is in the POI
  manager before `setBlock` returns. On an island worker `execute` queued the
  callback for Main's next task drain instead: after the island's entity phase,
  so a villager could not claim a re-placed job site in the same tick, and
  several island ticks late while Main lags. A raid's village check then saw
  POI states vanilla never shows and stopped the raid before its first wave
  (vanilla's STOP when the center is no village and relocation finds none).
  Up to 0.2.38 the custom 100-tick village grace hid such samples; 3.0.0
  removed it to restore vanilla raid rules.
  The callback now runs inline on Fabrolia simulation threads
  (`ParallelProcessor.isServerExecutionThread`: region workers, tick pool,
  exact player actor). Physical Main and other threads keep
  `MinecraftServer.execute`; `nether_portal` callbacks keep the portal
  publication fence. Worker POI writes use the column lease and stripe that
  villager claims already use; no new lock order.
- Local evidence (async islands, A5 player ticks): a sticky piston moving a
  fletching table on an island. Before: block change on
  `Fabrolia-Region-Worker-1` at game time 351, POI removal on the server
  thread at 352 (add: 353 → 354). After: both on the worker in the same tick.
  Raids in a one-villager village and in a placed `village_plains` still reach
  their first wave (also with `islandEntityParallel` and with raid creation
  routed to Main). The reporter's farm was not available; that it works again
  still has to be checked on the server.
- Tests: `PoiBlockChangeCallbackTest` executes the production wrap. On a
  region worker the job-site and bed callbacks run before the wrapped call
  returns (both fail without the fix); other threads keep vanilla `execute`.
  1466 tests pass, 8 optional skip.

## 3.1.0-alpha (2026-10-11)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass; the
40-client stand verification and a matched perf comparison have not been run.

### Performance: opt-in parallel island entity phase

Production 3.0.3 with 41 players fell to 5–10 Main TPS while the islands
themselves ran at 16–20 TPS (spark profiles 2026-10-11, details in
[`docs/PERF-ANALYSE-2026-10-11.md`](docs/PERF-ANALYSE-2026-10-11.md)). Main
spent up to 70 ms per tick parked until running island units finished: block
use packets on the Main lane (`IslandCommands.mainClaim`), overdue tracking
publications (`IslandBuckets.waitForRemaining`) and function fences. The
units are long since 3.0.0: each island ticks all its entities in one serial
pass (TNT fix), so dense islands with several players took 37–52 ms per unit,
up to 100 ms, while half of the 17 region workers were idle.

- New `islandEntityParallel` (default `false`, unchanged behavior). With
  `true`, an island unit dispatches its member regions' entity phases in
  parallel under the ColorGate, the path islands used up to 0.2.38, and skips
  the serial pass. A unit whose members hold primed TNT or a TNT minecart keeps
  the serial pass, so the TNT fixes of 3.0.0 stay exact.
- Accepted deviation with `true` (as up to 0.2.38): inside an island, entities
  of neighbouring regions tick in ColorGate waves rather than in Vanilla
  insertion order, and block writes outside the ticking region are replayed
  after the entity phase.
- Runtime: `/fabrolia regions islandentities` (status), `… vanilla`,
  `… parallel`; saved in the Fabric and NeoForge configs. Each unit reads the
  mode once, so a switch takes effect at the next unit of each island.
- Tests: `IslandEntityPhaseModeTest` (TNT detection with the real 26.3 entity
  classes, unit wiring, config persistence).

## 3.0.3-alpha (2026-10-11)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass; the
40-client stand verification has not been run.

- Fix: a mob killing an island player crashed the server (production,
  3.0.2-alpha after 69 minutes: a piglin's melee kill,
  `A5 player lifecycle mutation entered from neither physical Main nor the
  exact island owner`). Since 3.0.0 island entities tick in the serial
  `tickIslandEntitiesInVanillaOrder` pass after the queue-only join, when every
  member region is already done for the epoch; the lifecycle owner check still
  required color-gate eligibility, which is false there by construction. The
  exclusive island entity phase scope now authorizes the lifecycle mutation
  (the same quiescent-owner path the region dispatch used). The same check
  lets player advancement/stat triggers from island entities run on their
  owner again. Regression test: `AsyncIslandPlayerLifecycleWiringTest
  .serialIslandEntityPassAuthorizesAMobKillAfterTheQueueJoin` (fails without
  the fix).
- Production log check of 3.0.2: SCHEDULED-HOLD lines carry the readiness legs
  (no `probe-failed`), no `[entity-unload]` or pearl false-alarm lines, the
  self-heal summary appears, and no Servux error.

## 3.0.2-alpha (2026-10-11)

Status: unit tests, Fabric/NeoForge builds and the artifact check pass; the
40-client stand verification (`tools/stand/stand.sh verify`) has not been run
yet, so production stays on 3.0.1-alpha until it passes.

Findings from the 2026-10-10 production log (20 h, 68.7k lines); details and
numbers in [`docs/LOG-REVIEW-2026-10-11.md`](docs/LOG-REVIEW-2026-10-11.md).

### Fixes

- Servux compatibility (production crash 2026-10-10 23:29,
  `ConcurrentModificationException` in Servux
  `StructureDataProvider.sendAndRefreshExpiredStructures`): Servux records
  structure timeouts in a plain per-player `HashMap` from its
  `ChunkMap.markChunkPendingToSend` hook and iterates it on Main at tick end;
  Fabrolia marks chunks on island workers too. `ServuxStructureWatchMainRouteMixin`
  (only active with Servux installed) runs off-Main calls as Main-only tasks.
  Verified on a Fabric dev server with `servux-fabric-26.3-0.12.2.jar`.

### Diagnostics

- SCHEDULED-HOLD lines print the chunk's readiness again. The probe read the
  loading level through `DistanceManager.getChunkLevel(pos, false)`, whose
  `loadingChunkTracker` is null under C2ME, so all 2,015 hold lines of the log
  said `readiness=probe-failed:NullPointerException`. The loading level now
  comes from the holder's ticket level, and each leg is probed on its own.
- The pearl-freeze trace judges completed propagation waves only. It fired on
  intermediate `setLevel` states (the fixed-point propagation lifts a node to
  the maximum and relaxes it back within one wave), so stasis chunks that never
  left entity-ticking were reported up to ~1,000 times each.
- Ender-pearl ticket holds that engage and heal are summarised once per five
  minutes instead of one INFO line each (4.6k lines, all "self-heal OK").
  Budget exhaustion, expiry with a live pearl and real freezes still log
  individually.
- `[entity-unload]` retries of a false entity store are routine (vanilla retries
  silently every tick) and no longer WARN every 10 s (5.9k lines). The age of
  every pending retry is tracked; WARN names the oldest chunk once it has failed
  to unload for 60 s. The routine count remains at DEBUG.
- The fail-stop `A5 topology-gap handoff lost its exact player/session binding`
  now names every broken leg (listener, owner stamp, tracking publication,
  pending lifecycle handoff, invalidation, …), the player, phase and session
  epoch. The intermittent 3.0.1 stand crash with this message
  (`docs/reviews/2026-10-11/unused-code-cleanup/`) shared one message across
  eleven conditions and could not be attributed. Behaviour is unchanged.

### Performance

- Island entity phases share the whole-level `EntityTickList` snapshot while
  its membership is unchanged. Every island unit took its own copy of the
  complete list (~10k references per level, per island, per tick); islands that
  run between two membership changes now reuse one immutable copy. Content and
  order are identical by construction (only `add`/`remove` change the list and
  both bump the version); covered by `EntityPhaseMembershipParityTest`. The
  effect has not been measured on the stand yet.

### Configuration and cleanup

- Remove the unreachable synchronous scheduled-tick partition
  (`IslandScheduledTicks.tickPartitioned`, ~200 lines with its slice gate and
  snapshot helpers) and its ineffective `fabroliaIslandScheduledTicks` key and
  `regions debug setIslandScheduledTicks` toggle. The method returned early
  unless async islands were active, and with async islands
  `slotPartitionedAsync` always takes the lane first. Without async islands the
  vanilla scheduler runs, as before. Existing configs drop the key on the next
  load (one "Removed unused config key" line).
- NeoForge now persists `regionEventScheduler`, `fabroliaRegionEviction` and
  `fabroliaRedstoneCrossRegionGuard` like Fabric; their runtime toggles were
  lost on restart.
- Config descriptions: correct runtime command paths (`regions debug eviction`,
  `crossredstone`, `eventsched`, `setIslandBlockEntityTicks` take `true|false`),
  the eviction default (on), the legacy `enableFabroliaChunkSend` key (no
  effect) and the synchronous-only scope of `fabroliaIslandBlockEntityTicks`.
- Fabric config: ignore non-string items in list keys instead of storing them
  in String sets; write the file once at startup instead of twice.
- `tools/release/check_jar.py` reads only the 8-byte class header.

### Commands

- `/fabrolia config synchronizedEntities add|remove`: refused requests are
  sent to the sender as failures (return 0) instead of being broadcast to every
  operator as success messages. `remove` of an entity that is only covered by a
  namespace wildcard no longer reports a removal that did not happen; it names
  the wildcard entry to remove. Adding an already present wildcard is refused.
- `/fabrolia stats entity <count> <ticks>` (open to every player) limits the
  recording window to 6000 ticks; an unbounded window kept Main polling for
  the rest of the session.
- Stats and `setFabroliaChunkSend` label the chunk-send key as legacy (no effect).

### Tests

- New: `EntityUnloadRetryAgesTest`, `EntityTickingLossTraceTest` (both policies
  extracted from mixins into plain classes), `ConcurrentLongOpenHashSetTest`
  (the previously untested set behind `PoiManager.loadedChunks`; randomized
  differential and concurrent-resize checks found no defect) and a sharing
  check in `EntityPhaseMembershipParityTest`. 1447 tests pass, 8 optional skip.

### Build and tools

- `FullModpackMixinExportVerifierTest`: the function-fence ordering check used
  the first `publishAndPropagate` of `fabrolia$fenceFunction`, which belongs to
  the worker/owner-local branches added ahead of the fence, so the verifier
  failed on every export (production jar included) and never reached its later
  checks. It now takes the fail-stop after the bounded quiesce.
- CI: `actions/setup-java@v4` with Temurin 25 (v2 ran on the retired Node 12
  runtime), `gradle/actions/setup-gradle`, Ubuntu 24.04 without the obsolete
  `libtinfo5` install, and `check_jar.py` on the built Fabric jar.
- `gradlew` and `tools/stand/stand.sh` are committed executable.
- Build scripts: no Gradle 10 deprecations left (`url = …` assignments,
  single-string `include` notation); the Modrinth upload plugin is pinned to
  2.10.0 instead of the dynamic `2.+`. Dead Loom mixin/refmap settings (26.x is
  unobfuscated) and the unused Lombok processor in common/fabric/neoforge are
  removed (the Fabric jar stays byte-identical); the ASM declaration matches
  the resolved 9.10.1.
- `.vscode/launch.json` is no longer tracked (regenerated with absolute local
  paths on every Gradle configure).
- Spark tools: `sparkwin.py` and `frames.py` print usage instead of a
  traceback; `tools/spark/requirements.txt` pins the protobuf 7.x runtime the
  committed bindings need.
- Bug-report template assigns the fork owner instead of upstream maintainers.

## 3.0.1-alpha (2026-10-11)

- Remove the ineffective `/fabrolia regions chunkheal on/off` command and its
  unread configuration flag. The C2ME broken-strand fail-stop diagnosis remains
  active; it never depended on that flag.
- Remove the private worker-pool wrappers `leaveRootAdmission` and
  `reserveExternalTask`, which had no production callers. Test fixtures now use
  shared test-only access to the existing locked reservation and admission
  cleanup paths; no helper is packaged with the mod.
- Validation: unit tests and a complete second 40-client campaign passed. The
  first campaign hit an intermittent exact player/session topology-handoff
  crash in unchanged code; its cause remains unresolved. The preceding 3.0.0
  control also passed. See `docs/reviews/2026-10-11/unused-code-cleanup/`.

## 3.0.0-alpha (2026-10-10)

### Build and project organization

- Fabric releases are named `fabrolia-alpha_3.0.0.jar`; loader metadata uses
  `3.0.0-alpha`. Minor releases increment the middle number and reset patch;
  bugfixes increment patch. `gradle.properties` is the single source of truth,
  with a version-bump CLI and an explicit artifact check under `tools/release/`.
  The Minecraft target and nested API version remain independent.
- Share Java 25/vector-module and loader release conventions in `buildSrc`;
  remove duplicate compiler and plugin configuration. Archive order/timestamps
  are deterministic. Both upload configurations select their current jar task,
  so leftover jars cannot be accidentally selected. No upload occurs during build.
- Preserve release filenames in the stand and accept the new name in artifact
  discovery. Add a documentation index, release guide and region package map;
  remove the Fabric example-mod contact placeholders.

### Fixes

- Nether portal preparation no longer pauses the whole worker pool on every
  tick while a request only waits for chunk or POI loads Main already
  requested. Worker footprint checks re-required the unchanged FULL anchor of
  a not-yet-island destination cell on every attempt, which re-armed a Main
  turn each tick until the chunk loaded (production profile 2026-10-10:
  ~6 ms/Main tick in `pauseExactTurnAndAwait`, 0.4 ms in the actual
  preparation). A new demand, publication, border change or a completed load
  still takes the Main turn at once; ticket, load and transfer order are
  unchanged. Footprint ownership is resolved once per grid cell instead of
  once per chunk.
- Main skips the whole-level entity snapshot when no global-lane region holds
  an entity (the normal async state: populated cells join islands at the
  level head); stand profile: 1.92 → 0.02 ms per Main tick. Island and global
  cuts resolve each grid key once instead of one concurrent-map probe per
  entity (island cut −25 %, its lookups −88 %). The global pass again
  re-routes a moved entity's region index, as the region tick body did before.
  Both entries verified on one jar: 1,442 tests (8 optional skipped), TNT
  parity 53/53 async and 52/52 sync against the 26.3 reference, all 11 stand
  checks with 40 clients (Nether round trips 5–9 s), the original compact raid
  farm through victory, and the loaded-ring, low-distance and village-removal
  raid checks. See `docs/TNT-FIX-2026-10-10.md` (follow-up).
- Preserve Vanilla 26.3 entity insertion order across an island's regions.
  Join regional pre-entity work, then visit a finite snapshot of the actual
  EntityTickList on that island's coordinator; independent islands remain
  parallel. New entities created during the pass wait for its next snapshot.
  The global remainder uses the canonical Vanilla consumer on Main.
  Exclusive, authenticated island ownership keeps explosion block removal
  and its TNT ignition callback together across internal region boundaries.
  Nonlocal final TNT footprints use the existing all-island Main fence.
  Verified against the actual 26.3 reference in eight TNT cases, both async
  and synchronous modes; the old jar fails the impulse-order regression.
  All 11 general stand checks with 40 clients passed. Build: 1,431 passed,
  8 optional checks skipped. End-portal/save/restart regression: 37 checks
  passed. Matched items+pistons load profiles are recorded in
  `docs/TNT-FIX-2026-10-10.md`, including snapshot overhead and host limits.
- Keep pending block events admitted until their individual dequeue. Reposting
  an already queued event no longer duplicates it, and clearBlockEvents reaches
  shared, captured and island-stashed events. Topology carries preserve the
  identity of each admission and cannot resurrect a cancelled event.
- Restore 26.3 village-distance queries to a resident graph lookup. Remove
  the extra synchronous POI load from every query and its unused accessor.
  Accepted POI notifications still drain before graph relaxation, so creating
  or removing a village source is visible in the same query.
- Let deferred entity visibility changes progress during vanilla's managed
  entity-load wait, including player login. Previously a queued load promotion
  could wait for the world tick suspended by that same login. The existing
  non-blocking chunk-owner transactions now also drain before
  `processPendingLoads`; entity and island clocks are not advanced.
- Restore Minecraft 26.3 raid rules: remove the custom 100-tick village-loss
  grace and extended vertical relocation search. Raids use vanilla's immediate
  STOP/LOSS decision and radius-2 section search. Remove automatic raid PORTAL
  tickets: existing player, portal and forceload tickets determine spawning and
  unloading, as in vanilla. The loaded raid footprint already forms one island
  at the production grid size; dispatch and tick ownership remain intact.
  This also removes unnecessary POI scans and forced chunk ticks. Older designs
  unsupported by vanilla 26.3 are not revived. See
  `docs/RAID-TICKET-FIX-2026-10-09.md` for validation and limitations.
- Prevent primitive concurrent maps from returning another key's value after
  A→B→A slot reuse. Slot generations cover all published key/value writes;
  reads remain lock-free. Correct default-value removal, backed key/value
  views, and write-through snapshot entries for the confirmed collection bugs.
- Preserve the End falling-block landing gate during saves and scheduler stop.
  Save held arrival operations and airborne predecessor identities in destination
  SavedData, restore before the first level tick (including lazy dimensions),
  and retain the same gate on Main while the scheduler is off. Recovery waits
  for loaded/accessible predecessors and renews the necessary portal leases.
  Completed saves and clean restarts are covered; this is not a multi-file crash
  transaction journal. Verified with 1,399 tests (8 optional checks skipped),
  both loader builds, all 11 checks with 40 clients, and 37 real End-portal checks
  including unequal clocks, no End forceloads, two restarts, live off/on,
  new arrivals on Main, normal sand placement and the next regeneration's drop.
  See `docs/CRITICAL-FIXES-2026-10-08.md` for evidence and performance costs.
- Keep deferred portal-ticket requests bound to their original world and chunk.
  Removing, merging, or teleporting the requesting entity again no longer drops
  the accepted request or redirects it to another dimension.

- Automatically refuse Carpet `/spawn tracking start` (including area filters) and
  `/spawn test` while Fabrolia's parallel spawning or async islands are enabled.
  Carpet's shared world-cycle counters are unsafe outside its synchronous chunk pass;
  its tracking hooks now report inactive and end any existing session on a mode change.
  Other `/spawn` commands remain available, and serial spawning retains tracking.
  Verified with 1,392 tests (8 optional external checks skipped), both loader builds,
  live serial/parallel mode changes, startup without Carpet, and all 11 stand checks
  with 40 protocol clients (`carpet-guard-v1`).

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
