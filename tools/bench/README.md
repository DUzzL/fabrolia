# Benchmark and release check

The tools behind [docs/BENCHMARK.md](../../docs/BENCHMARK.md). They run on Windows,
Linux and macOS with Python 3 (`pip install psutil matplotlib`) and a JDK 25.
Everything large (downloads, server templates, worlds, results) lives in
`FABROLIA_BENCH_HOME` (default `build/bench`); `FABROLIA_BENCH_JAVA` picks the
`java` executable (default: `java` on `PATH`).

## Benchmark

```bash
./gradlew :fabric:build                 # the Fabrolia jar under test (or set FABROLIA_JAR)
python tools/bench/setup.py             # pinned downloads (inputs.py) and server templates
python tools/bench/prep_world.py        # mob world, generated once on vanilla
python tools/bench/overnight.py         # every scenario x configuration (~9 h), resumable
python tools/bench/plot2.py "$FABROLIA_BENCH_HOME/results" docs/benchmark
```

`overnight.py` builds the other worlds with `prep2.py`, runs `bench.py` (mob ramp)
and `bench2.py` (thread scaling, loaded chunks, hoppers, redstone, world generation),
archives each run's `bench.json` (all per-second samples), console and log under
`results/<scenario>/`, and skips runs that already finished; `watch.py` blocks until
the queue needs attention. Configurations are defined in `configs.py`. Close other
heavy programs while measuring.

How a run works: protocol-level players (`mc.Bot`) join a world generated once on
vanilla. The load enters every server the same way, without commands, plugins or
helper mods: players spawn mobs with spawn eggs like a creative-mode client, or join
one by one so their bases, chunks or new terrain become active. Above every base an
observer clock changes every 2 game ticks; the server sends one block update per
change, so each player measures the TPS of its own base as 2 x updates per second.
Server CPU use is sampled every second; thread scaling limits the server's CPU
affinity before the JVM starts.

## Release check

```bash
python tools/bench/functest.py fabric ft-fabric      # newest build of this checkout
python tools/bench/functest.py neoforge ft-neoforge
python tools/bench/neo_stress.py neoforge st-1 neoforge/build/libs/<jar> [--sync]
```

`functest.py` boots a dedicated server with four players on three islands and checks
both scheduling modes, the commands, island-to-island and Nether teleports, a clean
stop with save, and a restart. `neo_stress.py` drives four islands of decaying
leaves and observer clocks while a player places 20 blocks per second (NeoForge's
drop and block-snapshot capture). Singleplayer was checked by hand in a launcher
(world load, commands, teleports, save, reload).

Both fail on any ERROR/FATAL server log line (`mc.unexpected_log_errors`). The only
exception is NeoForge's DebugFile appender failing on netty's epoll/kqueue probes on
Windows, which a vanilla NeoForge run (`functest.py neoforge NAME --vanilla`) logs as
well.

## Owner's regression scenario

```bash
python tools/bench/island_regression.py ir-1        # 100 players, ~15 min; FABROLIA_JAR picks the jar
```

`island_regression.py` runs the scenario of [docs/USER-REQUIREMENTS.md](../../docs/USER-REQUIREMENTS.md)
in the mob world (`prep_world.py`): 100 players in the eight pens (one island each,
the same mob load in every pen), then steady load, one island slowed by
`debug islanddebug`, Main parked by `debug wedgemain`, players changing dimension and
island, `save-all flush`, stop and restart. It measures each pen three ways: the clock as
its players receive it, the island's completed ticks per wall second (`L` in
`regions top verbose`) next to the TPS and MSPT the island reports, and Main's game time
per wall second. Report: `runs/NAME/island-regression-report.json`.

## Teleport storm

```bash
python tools/bench/teleport_storm.py ts-1                  # 16 players, 120 s of RCON teleports
python tools/bench/teleport_storm.py ts-2 --anchors        # every target pen keeps an island
python tools/bench/teleport_storm.py ts-3 --no-playerticks # players tick on Main
```

`teleport_storm.py` teleports players back to back between the pens of the mob world
and onto open ground without an island. Each same-level teleport is an audited A5
lifecycle mutation with its own Main handoff, so the next island rebuild can race it.
3.1.12 and the first 3.1.13 build crashed within seconds with
`A5 topology-gap handoff lost its exact player/session binding: auditedLifecycleMutationPending`.
The run fails on a crash report or on any ERROR line (a Fabrolia fail-stop keeps the
process alive) and kills the server at once. Report: `runs/NAME/teleport-storm-report.json`.
