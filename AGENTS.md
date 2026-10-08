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

Jar: `fabric/build/libs/fabrolia-fabric-<version>.jar` (the version string stays
`0.2.38+alpha-26.3`; the released commit is tagged `prod/current`). ~1400 tests, all must
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
tools/stand/stand.sh verify vfy-1 fabric/build/libs/fabrolia-fabric-0.2.38+alpha-26.3.jar   # must print ALL PASS
tools/stand/stand.sh perf perf-1 <jar> 120 [--scenario items|pistons] [--heap]
python3 tools/spark/frames.py <old>.sparkprofile <new>.sparkprofile
```

Every change touching runtime code: tests + `verify`. Threading/performance changes: also
`perf` on the old and new jar with the same scenario.

## Diagnose production

- Lag: ask for a spark profile of all threads covering the event
  (`/spark profiler start --thread * --interval 4`, then `/spark profiler stop` after the
  lag), then `python3 tools/spark/spark2md.py FILE --only summary` and compare the lag
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
- `tools/stand/`, `tools/spark/`: stand and profile tooling. `docs/C2ME-FLAG_BROKEN-STRAND.md`:
  a C2ME bug Fabrolia contains (`/fabrolia regions chunkheal`).
- `../Rustmi` (production mod) compiles against and reflects on Fabrolia: `regions.{Region,
  RegionGrid,RegionScheduler,RegionTickHooks,RegionWorkerThread}`, `SensorCandidateBridge`,
  `LocalCapBridge`, `ParallelProcessor.executor`/`pumpUntilDone`; Fabrolia's
  `DistanceSortBridge` calls Rustmi by name. Renaming these breaks Rustmi's build or silently disables its integration: update
  `../Rustmi/libs/fabrolia-compile.jar` and run its harness (`../Rustmi/AGENTS.md`).

## Release and install

1. Tests + `verify` ALL PASS (+ `perf` comparison for performance work).
2. Move the tag `prod/current` to the released commit (`git tag -fa prod/current`) and replace
   the jars in `release/` (Fabrolia fabric jar + MCFC jar), then `sha256sum *.jar > SHA256SUMS`.
3. Install with the server stopped: replace `mods/fabrolia-fabric-*.jar`, start. A jar
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
  ServerCore `autosave-interval-seconds`, 300 is recommended). The save path itself is
  deliberately left as it is.
- Carpet/vanilla custom spawners on Main read blocks of island chunks near players
  (reads only; low risk).
- `Long2ObjectConcurrentHashMap` boxes keys (~1–2 ms/tick of worker CPU).
- Carpet `/spawn tracking start` crashes the server under async islands: Carpet's
  `NaturalSpawner` hook writes `SpawnReporter.local_spawns`, which is null on island threads
  (`NullPointerException` from `ServerChunkCache.fabrolia$tickIslandChunks`, seen 2026-10-07).
  Production should keep `/carpet commandSpawn false` until this is fixed.
- `:common:verifyFullModpackMixinExport` fails in `verifyServerFunctionManager` ("the sticky
  cleanup receipt must precede fence acquisition"): the assertion pins an older instruction
  order of `ServerFunctionManager.fabrolia$fenceFunction`; it fails on the production jar as
  well, so the checks after it never run. Re-derive it from the current code before relying
  on this verifier.
