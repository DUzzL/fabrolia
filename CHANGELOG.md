## Unreleased

### Changed

- Roll the region runtime back to the stable 0.2.38 behavior (the compiled
  reference build). The uncommitted "native admission" rewrite that routed
  most entity, block-entity and player work through the Main thread is
  removed: 100-player load runs at 20 TPS again (0.5 TPS on the rewrite).
  The rewrite's sources remain on branch `wip-gpt-0.2.38-admission-layer`.

### Fixed

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

