## Unreleased

### Changed

- Roll the region runtime back to the stable 0.2.38 behavior (the compiled
  reference build). The uncommitted "native admission" rewrite that routed
  most entity, block-entity and player work through the Main thread is
  removed: 100-player load runs at 20 TPS again (0.5 TPS on the rewrite).
  The rewrite's sources remain on branch `wip-gpt-0.2.38-admission-layer`.

### Fixed

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

