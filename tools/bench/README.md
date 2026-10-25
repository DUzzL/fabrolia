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
