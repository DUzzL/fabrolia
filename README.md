# Fabrolia

Fabrolia is a Minecraft server mod that distributes world ticking across multiple CPU
cores. It is based on [Async](https://github.com/AxalotLDev/Async)
and extends that foundation with region-based scheduling and independently ticking islands.
Builds are available for Fabric and NeoForge.

**Fabrolia is still in alpha development.** Features, configuration and compatibility
are evolving, and bugs or crashes can occur. Preserving vanilla behavior for players,
redstone, farms and datapacks is the goal and remains under active testing.
Back up your worlds and test your modpack before deploying changes.

## Features

- **Parallel world ticking:** Each dimension is divided into regions, grouped into
  islands. Separate islands can tick on worker threads while the main server thread
  handles global work and coordinates interactions between islands.
- **Independent island ticks:** Optional async islands run on their own tick schedules,
  allowing separate areas of the world to progress independently. Shared work still
  requires coordination, so a busy island can affect main-thread tick times.
- **Regional simulation:** Island workers handle local entities, block entities,
  scheduled block and fluid ticks, random ticks and spawning. Optional player ticking
  assigns players to their island's worker.
- **Configurable entity processing:** Choose vanilla entity order within an async
  island or opt into parallel entity ticking across its regions. Parallel mode changes
  ordering across region boundaries; islands containing primed TNT or TNT minecarts
  retain serial processing.
- **Monitoring and diagnostics:** Inspect island TPS, milliseconds per tick (MSPT),
  lag, entity counts and worker activity through in-game commands and a TPS HUD.
  Carpet integration provides separate main-server and local-island TPS lines.
- **Runtime controls:** Manage region threading, async islands and feature switches
  through configuration and commands.

Performance depends on world layout, workload, hardware and the installed mods.

## Installation

1. Choose a Fabrolia build matching your Minecraft version and mod loader.
2. Install the Java version and dependencies required by that build, including Fabric
   API when using Fabric.
3. Stop the server, remove the previous Fabrolia jar from `mods/`, and install the new jar.
4. Start the server and review `config/fabrolia.toml`.

Minecraft, loader and Java requirements are defined by each build's metadata.
Use the Fabric or NeoForge artifact that matches your server's loader.

Compatibility depends on the specific modpack and build. Moonrise and Open Parties
and Claims are declared incompatible. See the [documentation](docs/README.md) for
integration details and known limitations.

## Configuration

Settings are stored in `config/fabrolia.toml`. Key controls include:

| Setting | Purpose |
| --- | --- |
| `enableRegionThreading` | Enable region-based worker scheduling. |
| `asyncIslands` | Enable independently scheduled island ticks. |
| `enablePlayerTickActor` | Enable worker-owned player ticking; the active mode depends on island scheduling. |
| `regionWorkerCount` | Set the region worker count; `-1` selects it automatically. |
| `islandEntityParallel` | Opt into parallel entity processing within async islands. |

For the complete settings and defaults, see
[`FabroliaConfig.java`](common/src/main/java/com/axalotl/fabrolia/common/config/FabroliaConfig.java).

## Commands

Commands use the `/fabrolia` prefix. Configuration changes and administrative controls
require the appropriate operator permissions. Use in-game tab completion for additional
arguments and diagnostic commands.

| Command | Purpose |
| --- | --- |
| `/fabrolia` or `/fabrolia help` | List the commands you may run, with the terms they use. |
| `/fabrolia regions here` | Show the TPS of the spot you stand on: your island's, or Main's. |
| `/fabrolia regions player <name>` | The same for another player (permission level 2). |
| `/fabrolia regions` | Show Main TPS, island health and per-dimension totals. |
| `/fabrolia regions list` | List every island on one line each (hover for all figures). |
| `/fabrolia regions top [count] [mspt\|maxmspt\|lag\|tps]` | Show the slowest islands with TPS, MSPT, size and lag. |
| `/fabrolia regions top verbose 100` | Show raw diagnostic rows for up to 100 of the heaviest islands. |
| `/fabrolia regions tps` | Toggle the player's action-bar TPS display (Main and island). |
| `/fabrolia regions on\|off\|toggle` | Control region threading. |
| `/fabrolia regions async on\|off` | Enable or disable independent island ticking. |
| `/fabrolia regions playerticks on\|off` | Enable or disable worker-owned player ticking. |
| `/fabrolia regions islandentities vanilla\|parallel` | Select serial vanilla entity order or parallel region processing within async islands. |
| `/fabrolia regions crossdim` | Inspect cross-dimension island links and coupling status. |
| `/fabrolia regions debug ...` | Access diagnostics and advanced feature controls. |
| `/fabrolia config setFabroliaEntitySpawn true\|false` | Enable or disable parallel mob spawning. |
| `/fabrolia config setFabroliaRandomTicks true\|false` | Enable or disable Fabrolia random-tick processing. |
| `/fabrolia config reload` | Reload configuration from disk. |
| `/fabrolia config toggle` | Toggle the mod's disabled state. |
| `/fabrolia config synchronizedEntities` | List entity types configured for synchronized processing. |
| `/fabrolia config synchronizedEntities add\|remove <entity>` | Change synchronized entity rules. |
| `/fabrolia stats` | Show mod status, Main TPS and features. |
| `/fabrolia stats entity [count] [ticks]` | Count entities per dimension and type; `ticks` also records their tick times. |

In the table, `on|off` and similar alternatives mean choose one argument.
With Carpet installed, `/log tps` also shows `Main` and `Local` tick information
for players on an island.

## Building and contributing

Use the JDK required by the checked-out source and set `JAVA_HOME` accordingly.

```bash
./gradlew :common:test :fabric:build :neoforge:build
```

On Windows, use `./gradlew.bat` instead. Artifacts are written to
`fabric/build/libs/` and `neoforge/build/libs/`.

Read [`AGENTS.md`](AGENTS.md) before changing the code. Runtime changes require tests
and verification on the [test stand](docs/STAND.md); threading and performance changes
also require comparable measurements before and after the change.

- [Documentation index](docs/README.md)
- [Code map](docs/CODEMAP.md)
- [Async island design](docs/ASYNC-ISLANDS.md)
- [Behavior requirements](docs/USER-REQUIREMENTS.md)
- [Changelog](CHANGELOG.md)
- [Build and release workflow](docs/RELEASES.md)
- [Profiling tools](tools/spark/README.md)

## Credits and license

Fabrolia is based on [Async by Axalotl](https://github.com/AxalotLDev/Async).
Thanks also to Grider and jediminer543 for the earlier MCMTFabric and JMT-MCMT
projects in its lineage.

See [`LICENSE`](LICENSE) for the license terms.
