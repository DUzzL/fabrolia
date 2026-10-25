# Functional and regression runs

Scripted dedicated-server runs with protocol-level players (`mc.Bot`). They run on
Windows, Linux and macOS with Python 3 (`pip install psutil`) and the JDK named in
`gradle.properties`. Everything large (downloads, server templates, worlds, runs) lives
in `FABROLIA_BENCH_HOME` (default `build/bench`); `FABROLIA_BENCH_JAVA` picks the `java`
executable (default: `java` on `PATH`), `FABROLIA_JAR` the jar under test (default: the
newest build of this checkout).

## Setup

```bash
./gradlew :fabric:build :neoforge:build
python tools/bench/setup.py             # pinned downloads (inputs.py) and server templates
python tools/bench/prep_world.py        # mob world with eight pens, generated once on vanilla
```

Server configurations (mod sets, Fabrolia modes) are defined in `configs.py`.

## Release check

```bash
python tools/bench/functest.py fabric ft-fabric
python tools/bench/functest.py neoforge ft-neoforge
python tools/bench/neo_stress.py neoforge st-1 neoforge/build/libs/<jar> [--sync]
```

`functest.py` boots a dedicated server with four players on three islands and checks
both scheduling modes, the commands, island-to-island and Nether teleports, a clean
stop with save, and a restart. `neo_stress.py` drives four islands of decaying leaves
and observer clocks while a player places 20 blocks per second (NeoForge's drop and
block-snapshot capture).

Both fail on any ERROR/FATAL server log line (`mc.unexpected_log_errors`). The only
exception is NeoForge's DebugFile appender failing on netty's epoll/kqueue probes on
Windows, which a vanilla NeoForge run (`functest.py neoforge NAME --vanilla`) logs as
well.

## Island regression

```bash
python tools/bench/island_regression.py ir-1        # 100 players, ~15 min
```

100 players in the eight pens of the mob world (one island each, the same mob load in
every pen), then steady load, one island slowed by `debug islanddebug`, Main parked by
`debug wedgemain`, players changing dimension and island, `save-all flush`, stop and
restart. Each pen is measured three ways: the observer clock as its players receive it,
the island's completed ticks per wall second (`L` in `regions top verbose`) next to the
TPS and MSPT the island reports, and Main's game time per wall second. The server runs
with `-Dfabrolia.debugCommands=true`. Report: `runs/NAME/island-regression-report.json`.

## Teleport storm

```bash
python tools/bench/teleport_storm.py ts-1                  # 16 players, 120 s of RCON teleports
python tools/bench/teleport_storm.py ts-2 --anchors        # every target pen keeps an island
python tools/bench/teleport_storm.py ts-3 --no-playerticks # players tick on Main
```

Teleports players back to back between the pens and onto open ground without an
island. Each same-level teleport is an audited A5 lifecycle mutation with its own Main
handoff, so the next island rebuild can race it. The run fails on a crash report or on
any ERROR line (a Fabrolia fail-stop keeps the process alive) and kills the server at
once. Report: `runs/NAME/teleport-storm-report.json`.
