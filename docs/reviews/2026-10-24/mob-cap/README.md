# Tunnel-bore mob-cap investigation, 2026-10-24

The investigation found two reproducible accounting failures in the current
3.1.10 code. Neither experiment establishes which failure caused the reported
Turtle incident or reproduces its 50-mob burst in the original world. No runtime
source, configuration, version, or deployed jar was changed by this investigation.

## Report and scope

- A remote tunnel bore carries 80–85 hostile mobs. Unexpected groups of about
  50 mobs appear behind the player, sometimes 5–10 minutes apart. Logging out
  removes the extra mobs.
- The owner supplied the production mod list: Fabrolia 3.1.10, VMP
  `0.2.0+beta.7.238`, Rustmi 1.2.0, Lithium 0.26.2, C2ME 0.4.2, ServerCore
  1.5.20, Carpet, Vanish and phantom-spawning, among others.
- The owner confirmed that the mobs stand freely and are pushed/pulled. They
  are not passengers in minecarts or boats. This removes one vanilla exclusion;
  mob types, persistence flags, positions and distances remain unverified.
- Inspected commit: `d92b2f4` (3.1.10) with pre-existing unrelated local changes.
  The accounting files discussed below have no local source changes.
- The original world, production configuration and an event-time trace were not
  available. Existing local Minecraft 26.3 and pinned VMP jars supplied the
  vanilla/mod bytecode used for comparison.

## Finding 1: proximity propagation can omit a whole mob chunk from local counts

`DistanceManagerMixin.fabrolia$noEagerWaveOnReads` removes inline propagation
from `hasPlayersNearby`. `FixedPlayerDistanceChunkTrackerMixin` installs a
concurrent live map, without a completed-wave view. Concurrent access is safe
for the map structure, but its answers can describe an intermediate propagation
state rather than either completed player position.

The real private vanilla `FixedPlayerDistanceChunkTracker` reproduces this with
a one-chunk move from `(0,0)` to `(1,0)`. After removing the old source, adding
the new source and processing one work item, the old chunk has tracker level
10, despite being only one chunk from the player. After draining the wave its
level is 1. A reader paused against the first state can receive a definite
negative proximity answer.

Fabrolia's weighted seed (`LocalMobCapCalculatorMixin.fabrolia$addMobs`) calls
vanilla `getPlayersNear`, which caches the result of
`ChunkMap.getPlayersCloseForSpawning`. A false answer omits every mob in the
affected chunk from that player's initial counts. The empty result remains
cached for that calculator's spawn pass; completion of the wave does not repair
those already omitted counts.

The overlap is not prevented by the ChunkMap monitor:
`ServerChunkCacheMixin.fabrolia$serializeDistanceUpdatesWithPlayerMove` guards
the wave with that monitor, while
`ChunkMapMixin.fabrolia$snapshotPlayersCloseForSpawning` deliberately avoids it.
Island spawn passes run independently on workers. Each pass creates a new
serial calculator; that bounds the bad seed to a pass, but cannot undo spawns
already accepted during it.

VMP does not close this path. Its `MixinSpawnDensityCapper` overwrites `addMob`
and `canSpawn` to use its general-area player index and an X/Z distance test.
Fabrolia's weighted seed bypasses that `addMob` implementation and still uses
vanilla `getPlayersNear`. Seed and approval therefore consume different
proximity paths. The installed VMP mixin is not cancelled by Fabrolia. The
island serial lane bypasses native pack reservations, so Rustmi's native arena
does not repair its seed.

`ExactSpawnWaveProbe.java` exercises the real private tracker and real vanilla
`LocalMobCapCalculator`, with synthetic outer objects. Its fake ChunkMap
models the actual definite-false proximity shortcut and one nearby player:

```text
Exact fixed tracker before: oldChunkLevel=0
Exact fixed tracker after first wave step: oldChunkLevel=10 actualPlayerDistance=1 => hasPlayersNearby FALSE
Exact fixed tracker after full wave: oldChunkLevel=1
Real LocalMobCapCalculator seeded with 80 mobs MID-WAVE allows spawn=true
Real LocalMobCapCalculator seeded with same 80 mobs AFTER-WAVE allows spawn=false
```

This proves the accounting mechanism with an explicitly selected interleaving.
It is not a full mixin-composed server or a natural-spawn world reproduction.
The experiment checks the local cap only. Additional spawns still require the
global category gate to be open. Other distant players can provide global
budget even when this player is alone locally; their actual budget and
ServerCore settings were not supplied.

## Finding 2: audit removal and worker re-add can disagree about tracked state

The second-strike branch of `SpawnCensus.audit` separately performs:

1. Remove the entity from `SpawnIndex`.
2. Bump the index version.
3. Call `SpawnCensus.update(level, entity, false)`.

A worker's `EntitySection.add` hook can run `SpawnIndex.ensurePresent` between
steps 2 and 3. That puts the entity back into the index and marks its ledger
tracked. The audit then overwrites the ledger as untracked, leaving a live
index member with no category contribution.

The ledger's per-entity lock serializes each individual update, but not this
whole index/ledger transaction. `runSuccessBodyIfHealthy` is a failure admission
gate, not mutual exclusion between the audit and the worker.

Further `ensurePresent` calls return early because the entity is already in
the index. Movement calls `update(..., null)`, which preserves the false tracked
flag. The same audit's `reconcile` checks only entities missing from the index
and cannot repair this disagreement. A subsequent healthy audit can restore
the contribution; audits run every 256 Main game ticks (12.8 seconds at 20 TPS).

`CensusAuditRaceRepro.java` invokes the actual production index/ledger methods,
with a synthetic eligible mob and level, in the exact interleaving above:

```text
Initial: indexPresent=true tracked=true state=5 monsterCount=1
Main audit after remove: indexPresent=false tracked=true state=5 monsterCount=1
Worker ensurePresent: indexPresent=true tracked=true state=5 monsterCount=1
Main audit update(false): indexPresent=true tracked=false state=0 monsterCount=0
Subsequent add/move: indexPresent=true tracked=false state=0 monsterCount=0
Next healthy audit: indexPresent=true tracked=true state=5 monsterCount=1
```

This proves a lost contribution once the purge/re-add interleaving is reached.
It does not reproduce the two consecutive failed section-membership checks
needed to enter that purge branch, or establish their frequency during a bore
run. Losing one contribution alone would not open a local cap already holding
80–85 monsters. Enough contributions would have to be omitted.

## Other checks and limits

- A 32×32 vanilla storage-region boundary is not a key used by either failure.
  Fabrolia uses 8×8-chunk grid cells; a bore's moving loaded area may change
  topology, but the cited vanilla region border is not evidence of that.
- Ordinary movement updates the ledger after every `onMove`, including moves
  that stay within one entity section. No missing normal movement hook was found.
- Both vanilla and VMP proximity predicates use horizontal X/Z distance; Y is
  not the cause of their difference here. At exactly 128 blocks, VMP accepts
  equality and vanilla does not, so the weighted seed also has a boundary
  compatibility discrepancy. That does not explain a compact nearby mob group.
- Vanilla excludes mobs requiring persistence or custom persistence. Base
  `Mob.requiresCustomPersistence()` is `isPassenger() || isLeashed()`;
  `PersistenceRequired:0b` alone is not proof of cap eligibility. The owner's
  free-standing confirmation rules out the reported group being passengers,
  but does not establish every other eligibility condition.
- `/fabrolia regions debug spawncap` inspects overworld `lastSpawnState`, which
  is Main's global remainder calculator during async islands. It does not
  inspect the independently created calculator of the player's island, so that
  command alone cannot validate this incident's local count.

## Validation and repeat commands

All 26 selected existing tests passed, with no skips or failures:
`SpawnCandidateProximityWiringTest`, `SpawnProximityReroutePinTest`,
`LocalCapPackReservationTest`, `SpawnCensusQueryAcceptanceTest`,
`SpawnIndexStrikeLifecycleTest`, `NaturalSpawnChunksTest`, `SpawnCapLanesTest`.
These tests do not cover either interleaving reproduced above.

From the repository root, run both isolated probes with JDK 25:

```powershell
.\gradlew.bat -I docs/reviews/2026-10-24/mob-cap/repro.init.gradle :common:runMobcapInvestigation --console=plain
```

The probes use reflection/Unsafe to construct minimal test objects and do not
start a server, modify a world, or apply mixins. `probe-output.txt` retains their
combined output. A verified fix still needs regression coverage and a real
server comparison, ideally using the original bore world and Turtle's modpack.

Compatibility note for 3.1.13: the race probe omits the former `bumpVersion()`
call because the unused precompute version counter was removed. That counter
did not participate in the index/ledger race. The probe deliberately replays
the old split operations, rather than calling the fixed atomic `purge`.

## Repair direction for subsequent implementation

Local-cap seeding and approval need one coherent nearby-player rule for a spawn
pass, preserving the installed mod hooks. Worker readers must not drive the
propagator or acquire ChunkMap in a way that restores the documented holder /
ChunkMap deadlock. Merely making the map concurrent does not make a propagation
result semantically complete.

Index membership and the tracked ledger flag must be changed as one per-entity
transaction across all start/end/ensure/purge paths, with section eligibility
validated under the appropriate ownership. Reversing two statements alone is
not a proof: concurrent add and remove paths must agree on the same transaction.

## Resolution in 3.1.12-alpha

Both findings were checked against the 26.3 and VMP bytecode and fixed in
3.1.12 (`CHANGELOG.md`). The probes above replay the 3.1.10 code paths step by
step, so they still print their old results; the regression tests are:

- Finding 1: `DistanceManagerMixin` answers DEFAULT (vanilla's exact per-player
  test) unless a write epoch proves the tracker settled: no write in flight, no
  queued update, unchanged epoch around the level read. The only writers are
  the `update` calls in `addPlayer`/`removePlayer` and the wave in
  `runAllUpdates(ChunkMap)`; `SpawnProximityReroutePinTest` pins that set, the
  level-8 DEFAULT ring and the mid-wave drop on the real tracker. The tracker is
  also read by `anyPlayerCloseEnoughForSpawning` (Nether-portal piglins), which
  gets the same settled answer.
- Seed and approval: the weighted seed calls the installed `addMob` once per
  chunk and category and replays the other copies onto the counts that call
  touched (`LocalCapSeedBridge` recorder), so VMP's or any other installed rule
  decides both. Pinned in `SpawnLocalCapReservationPinTest`, the run collapse in
  `LocalCapArenaAtomicAdmissionTest`.
- Finding 2: `SpawnIndex.purge` re-checks and removes membership and the ledger
  contribution under the entity's census lock; `onTrackingStart/End` and
  `ensurePresent` take the same lock. `SpawnIndexStrikeLifecycleTest` races
  20,000 purge/ensurePresent rounds and checks membership = tracked = counted
  after each; with the 3.1.10 sequence substituted it failed in round 127.

Still open: a natural-spawn comparison in the original bore world with the
production mod set, and the stand run AGENTS.md requires.
