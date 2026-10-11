# Fabrolia — agent guide

Fabrolia is a Minecraft 26.3 server mod (Fabric; NeoForge builds too) that ticks the world
in parallel: it splits each dimension into regions grouped into **islands**, and every
island runs its own tick (**unit**) on a `Fabrolia-Region-Worker` thread while the Server
thread (**Main**) keeps the global lane, commands and fences. It runs a ~35-player
production server at 20 TPS. Branch `main` is the production build.

## The contract

- **Vanilla parity.** Players, redstone, farms and datapacks must behave exactly as in
  vanilla. TPS gained by skipping, reordering or duplicating vanilla work is a bug. The
  owner's requirements: `docs/USER-REQUIREMENTS.md`. Any accepted timing deviation
  (e.g. "≤1 island tick later") is written in `CHANGELOG.md`.
- **Ownership.** A thread touches a chunk, entity or block entity only while it owns it:
  an island unit owns the chunks of its cells (core + halo); Main owns global-lane chunks
  and an island's chunks only while it holds that island's claim
  (`IslandCommands.mainClaim` / `tryMainClaim`) or all claims (a quiesce). Vanilla
  structures inside a chunk (block-entity maps, sections) are not thread-safe; the
  2026-10-06 production crash was Main reading a running island's chunk.
- **Fail loud.** Fix causes. Stuck or impossible states end in the fail-stop path
  (`RegionScheduler.publishFatalAndSelectWinner`); never swallow an exception, retry to
  hide a race, or special-case the symptom.
- **Proof.** A threading or performance change is proven on the stand (below), not by
  unit tests alone. Performance claims compare the changed frames between the old and the
  new jar.

## Build and test

```bash
export JAVA_HOME=/path/to/jdk-25              # Java 25 is required (production: Temurin 25)
./gradlew :common:test :fabric:build :neoforge:build
./gradlew :common:test --tests '*GlobalBlockUpdateDrainTest'      # while iterating
```

Jar: `fabric/build/libs/fabrolia-alpha_<major>.<minor>.<patch>.jar`; current release
`fabrolia-alpha_3.0.1.jar`. Metadata version: `3.0.1-alpha` in `gradle.properties`.
The owner's 2026-10-10 versioning rule supersedes the old fixed `0.2.38+alpha-26.3`:
minor releases increment the middle number and reset patch; bugfixes increment patch.
Use `tools/release/release_version.py`; see `docs/RELEASES.md`. ~1400 tests, all must
pass; `:fabric:build` runs `:common:test` first, so a red test also blocks the jar.

About 57 test classes are **wiring tests**: they read Java source or bytecode and assert
on exact strings, method names, call counts or instruction order (e.g. `*WiringTest`,
`ResidualFailStopWiringTest`, `PlayerTickV2WiringTest`). They pin invariants that are hard
to test behaviourally (fail-stop shape, lock order, publication order). When a refactor
breaks one, check that the invariant it names still holds, then update the test in the
same commit; a red wiring test after a pure rename is expected, after a logic change it is
a warning. Tests that need external jars (Carpet, Lithium, C2ME, VMP, a mixin export) skip
when the jar is absent. `FullModpackMixinExportVerifierTest` checks the mixin-composed
classes of the real mod set: `tools/stand/stand.sh export exp-1 <jar>`, then
`./gradlew :common:verifyFullModpackMixinExport -PfabroliaMixinExportRoot=$PWD/stand/runs/exp-1/server/.mixin.out`.

## Verify at runtime

`tools/stand/stand.sh` builds a fresh production-like server with 40 real clients
(details, scenarios and how to read results: `docs/STAND.md`):

```bash
tools/stand/stand.sh verify vfy-1 fabric/build/libs/fabrolia-alpha_3.0.1.jar   # must print ALL PASS
tools/stand/stand.sh perf perf-1 <jar> 120 [--scenario items|pistons] [--heap]
python3 tools/spark/frames.py <old>.sparkprofile <new>.sparkprofile
```

Every change touching runtime code: tests + `verify`. Threading/performance changes: also
`perf` on the old and new jar with the same scenario.

## Diagnose production

- Lag: ask for a spark profile of all threads covering the event
  (`/spark profiler start --thread * --interval 4`, then `/spark profiler stop` after the
  lag; add `--save-to-file` when the bytebin upload times out, as it did during the
  2026-10-10 lag), then `python3 tools/spark/spark2md.py FILE --only summary` and compare the lag
  windows with the windows before them using `tools/spark/sparkwin.py` (`tools/spark/README.md`).
- Crash: read the first `fabrolia$…` frame and the `Suppressed:` traces (other threads
  that observed the same fatal). Find the code via `docs/CODEMAP.md` → "Where to look when".
- In-game: `/fabrolia regions top verbose 100` (per-island TPS/MSPT/lag), `/fabrolia regions`,
  Carpet `/log tps` (Main + Local island lines).

## Repository map

- `release/` (not in git): the production jars — Fabrolia (fabric) and MCFC — with `SHA256SUMS`.
- `stand/` (not in git): stand data (server template, datapacks, config, runs); see `docs/STAND.md`.

- `docs/CODEMAP.md`: threads, tick walkthrough, subsystems → files → tests, "where to look when".
- `docs/ASYNC-ISLANDS.md`: the island design and its invariants (sections 0, 2, 12–17 are the
  ones edits usually need).
- `CHANGELOG.md`: what changed and why, newest first; every user-visible change goes here.
- `docs/README.md`: documentation index. `docs/RELEASES.md` and `tools/release/`:
  release versions, exact artifact naming and archive checks.
- `tools/stand/`, `tools/spark/`: stand and profile tooling. `docs/C2ME-FLAG_BROKEN-STRAND.md`:
  the C2ME broken-strand diagnosis (always active when applicable).
- `../Rustmi` (production mod) compiles against and reflects on Fabrolia: `regions.{Region,
  RegionGrid,RegionScheduler,RegionTickHooks,RegionWorkerThread}`, `SensorCandidateBridge`,
  `LocalCapBridge`, `ParallelProcessor.executor`/`pumpUntilDone`; Fabrolia's
  `DistanceSortBridge` calls Rustmi by name. Renaming these breaks Rustmi's build or silently disables its integration: update
  `../Rustmi/libs/fabrolia-compile.jar` and run its harness (`../Rustmi/AGENTS.md`).

## Release and install

1. Tests + `verify` ALL PASS (+ `perf` comparison for performance work).
2. Move the tag `prod/current` to the released commit (`git tag -fa prod/current`) and replace
   the jars in `release/` (Fabrolia fabric jar + MCFC jar), then `sha256sum *.jar > SHA256SUMS`.
3. Install with the server stopped: remove the old Fabrolia jar (including the former
   `fabrolia-fabric-*.jar` name), install `fabrolia-alpha_*.jar`, then start. A jar
   overwritten under a running server breaks its next class load (seen as a crash on
   `/stop`); if the server is live, copy to a temp name in `mods/` and `mv` it over the old
   jar, then restart.

MCFC (the datapack compiler the server uses) is built from `../MinecraftFunctionCompiler`,
branch `local/optimize-get-all-entities-pVriB`.

## Git

`main` = production; tag `prod/current` = the commit of the jar in `release/`.
`origin` is the upstream project (AxalotLDev/Async); this fork is not pushed there.

## Production facts

Ryzen 9 9950X host, 19 CPU threads and up to 31 GB heap for the JVM, Temurin 25.0.3 with
JVMCI; 30–36 players, ~13k entities, ~22k loaded chunks; 17 region workers
(`regionWorkerCount=-1`). Key mods: C2ME 0.4.2, Lithium 0.26.2, VMP, Carpet, ServerCore,
ScalableLux, MCFC, BlueMap, LuckPerms, Simple Voice Chat, WorldEdit, Ledger, Rustmi.
Fabrolia config `config/fabrolia.toml` (`asyncIslands = true`, `enableRegionThreading = true`;
player ticks stay on Main). Datapacks include BlazeandCave's Advancements.

## Known open items

- Islands do not catch up ticks lost to a pause (Main does); left open by the owner.
- Production autosaves once a minute (a ~470 ms tick in the 2026-10-05 profile; check
  ServerCore `autosave-interval-seconds`). The owner's 2026-10-08 fix request changes
  the End-arrival save boundary: held cohorts are persisted in destination SavedData
  instead of bypassing the landing gate. Preserve this rule on save, scheduler stop,
  and lazy dimension creation; see `docs/CRITICAL-FIXES-2026-10-08.md`.
- Carpet/vanilla custom spawners on Main read blocks of island chunks near players
  (reads only; low risk).
- `Long2ObjectConcurrentHashMap.get` is the top worker self-time frame in production
  (2026-10-10: ~8 % of worker CPU, half of it Lithium's per-block-entity
  `areEntitiesLoaded`, a vanilla-equivalent call). Boxing is not the cost: a
  microbenchmark (22.5k chunk keys, 1 and 8 threads) put the primitive
  `Long2IntConcurrentHashMap` only 5–25 % ahead with a warm cache and level or
  behind with a cold one (~50–100 ns both).
  The cost is memory latency per lookup; a map swap alone does not pay.
- Carpet spawn tracking is incompatible with independent/deferred spawning: its
  `NaturalSpawner` hooks use shared, non-thread-safe world-cycle counters (`local_spawns`
  and `first_chunk_marker`). Fabrolia automatically rejects `/spawn tracking start` and
  `/spawn test` while parallel spawning or async islands are enabled, and stops existing
  tracking on a mode change. Other `/spawn` commands remain available; no manual
  `/carpet commandSpawn false` is needed with this guard. True parallel tracking remains
  unsupported.
- Memory leak in Lithium, not Fabrolia (production heap dump 2026-10-08: 379 `ServerPlayer`
  for 25 online, ≈ 600 MB after 19.4 h, ~30 MB/h): Lithium's `mixin.entity.framed_maps`
  makes a framed map's `tickCarriedBy` visit only the ticking player, so players who left or
  died are never pruned from `MapItemSavedData.carriedByPlayers` of maps in item frames.
  Reproduces with vanilla + Lithium only; reported upstream by the owner (CaffeineMC/lithium).
  Fabrolia deliberately does not override it (owner's decision). Workaround on the server:
  `mixin.entity.framed_maps=false` in `config/lithium.properties`, or periodic restarts.
- Intermittent stand crash `A5 topology-gap handoff lost its exact player/session
  binding` (3.0.1 first campaign, during player distribution; the rerun passed). The
  message now lists the broken legs; on the next occurrence fix the named leg at its
  source (likely a teleport lifecycle handoff racing the topology rebuild), do not
  relax the check. See `docs/reviews/2026-10-11/unused-code-cleanup/`.
- `:common:verifyFullModpackMixinExport` fails in `verifyServerFunctionManager` ("the sticky
  cleanup receipt must precede fence acquisition"): the assertion pins an older instruction
  order of `ServerFunctionManager.fabrolia$fenceFunction`; it fails on the production jar as
  well, so the checks after it never run. Re-derive it from the current code before relying
  on this verifier.
