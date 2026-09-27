## Unreleased

### Changed

- Roll the region runtime back to the stable 0.2.38 behavior (the compiled
  reference build). The uncommitted "native admission" rewrite that routed
  most entity, block-entity and player work through the Main thread is
  removed: 100-player load runs at 20 TPS again (0.5 TPS on the rewrite).
  The rewrite's sources remain on branch `wip-gpt-0.2.38-admission-layer`.

### Fixed

- Deferred entity-chunk status transitions no longer block head-of-line: one
  island whose claim Main cannot take stalled every other island's chunk
  loading (entities never marked loaded, scheduled ticks held, chunk delivery
  stuck) and paused all entity unloads level-wide. Entries are now deferred
  per chunk while the rest of the queue drains.
- Island scheduled-tick holds are bounded: after ~10 s of consecutive holds at
  one logical tick the unit falls back to vanilla's per-container refusal and
  logs SCHEDULED-HOLD-EXHAUSTED with chunk readiness details.
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

