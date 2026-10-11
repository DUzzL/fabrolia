# Fabrolia (26.3 server fork)

Multithreaded ticking for Minecraft 26.3 servers (Fabric; NeoForge builds too). The world
is split into regions grouped into **islands**; each island ticks on its own worker
thread with its own clock, while the Server thread keeps the global lane, commands and
cross-island work. The goal is vanilla behaviour at 20 TPS on a busy server.

This is the fork that runs one ~35-player production server. It started from
[AxalotLDev/Fabrolia](https://github.com/AxalotLDev/Fabrolia) (itself based on
MCMTFabric / JMT-MCMT) and has been rewritten around async islands.

- **Working on the code (people and AI agents): read [`AGENTS.md`](AGENTS.md) first.**
- Code map: [`docs/CODEMAP.md`](docs/CODEMAP.md). Design: [`docs/ASYNC-ISLANDS.md`](docs/ASYNC-ISLANDS.md).
  Requirements: [`docs/USER-REQUIREMENTS.md`](docs/USER-REQUIREMENTS.md). Changes: [`CHANGELOG.md`](CHANGELOG.md).
- Test stand: [`docs/STAND.md`](docs/STAND.md). Profile tools: [`tools/spark/README.md`](tools/spark/README.md).
- Documentation index: [`docs/README.md`](docs/README.md). Releases and version bumps:
  [`docs/RELEASES.md`](docs/RELEASES.md).

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API, Java 25.
- Declared incompatible: Moonrise, Open Parties and Claims, C2ME older than 0.4.2-alpha.0.88.
- Tested with C2ME, Lithium, VMP (Very Many Players), Carpet, ServerCore, ScalableLux,
  FerriteCore, MCFC and BlueMap.

## Build and install

```bash
bash gradlew :common:test :fabric:build          # JAVA_HOME = JDK 25
python3 tools/release/check_jar.py fabric/build/libs/fabrolia-alpha_3.0.2.jar
```

Current build: **`fabrolia-alpha_3.0.2.jar`** (metadata `3.0.2-alpha`; stand verification pending, production runs 3.0.1). Minor releases
increment the middle number; bugfixes increment the last number. See the
[release workflow](docs/RELEASES.md).

Stop the server, replace the old Fabrolia jar in `mods/`, start. The production jars (Fabrolia + MCFC) are
in `release/` next to this README (not in git).

## Configuration

`config/fabrolia.toml`. The production server uses `enableRegionThreading = true` and
`asyncIslands = true`; every key and its default is in
`common/src/main/java/com/axalotl/fabrolia/common/config/FabroliaConfig.java`.

## Commands

All under `/fabrolia` (operator level):

- `/fabrolia regions` — region and island state; `regions top verbose 100` (per-island TPS,
  MSPT, lag), `regions list`, `regions async on|off`, `regions tps` (action-bar HUD),
  `regions debug …` (diagnostics, e.g. `debug spawnrate`, `debug advstats`).
- `/fabrolia config …` — `reload`, `toggle`, `synchronizedEntities add|remove`, feature switches.
- `/fabrolia stats` — thread and entity counts.

With Carpet installed, `/log tps` shows two lines for a player on an island: `Main` and `Local`.

## License

See [`LICENSE`](LICENSE). Thanks to Grider and jediminer543 (MCMTFabric / JMT-MCMT) and to
the original Fabrolia authors.
