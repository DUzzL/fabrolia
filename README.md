# Fabrolia

Fabrolia is a performance mod for Minecraft servers that ticks the world on several
CPU cores instead of one. It runs on **Fabric** and **NeoForge**, on dedicated
servers and in singleplayer, and keeps farms, redstone and datapacks working
like in vanilla.

> Fabrolia changes how the game is scheduled at its core. Back up your world and
> test your modpack before you switch.

## How it works

Vanilla Minecraft ticks everything on one thread, so a server with many players
in different places is limited by a single core. Fabrolia splits every dimension
into regions of 8×8 chunks. Loaded regions that touch form an **island**, for
example a player's base and everything around it. Islands that are far enough
apart cannot affect each other within one tick, so Fabrolia ticks them at the
same time on worker threads: entities, block entities, redstone and scheduled
ticks, random ticks and mob spawning. The main thread keeps everything global:
commands, players joining and leaving, and work that crosses islands.

By default every island also keeps its own clock (**async islands**): a lagging
base no longer slows down the rest of the server, and players tick on their own
island. For diagnosis, the JVM option `-Dfabrolia.syncMode=true` starts the server in
synchronous region threading, where the whole world finishes each tick together.

Fabrolia only helps where work can be split: one huge base with all players in it
is still one island. It does not skip or reorder vanilla work to gain TPS.

## When it helps

Fabrolia gains where work is spread over separate areas, for example players in
different bases. One huge base with everyone in it stays one island. World generation
is C2ME's field, and with piston-heavy redstone the single-threaded light engine
becomes the limit (ScalableLux helps there). Fabrolia combines with Lithium and C2ME.

## Installation

1. Download the Fabrolia jar that matches your loader and Minecraft version (each
   release names the versions it supports).
2. Fabric: also install [Fabric API](https://modrinth.com/mod/fabric-api).
   NeoForge needs nothing else.
3. Put the jar into `mods/` of the server (or of your client for singleplayer) and
   start it. Players joining a server do not need Fabrolia.

Fabrolia works together with Lithium and C2ME. It is not compatible with Moonrise
and Open Parties and Claims. When updating, stop the server and replace the jar;
never keep two Fabrolia jars in `mods/`.

## Commands

Everyone:

| Command | What it does |
| --- | --- |
| `/fabrolia` | Lists the commands you may use. |
| `/fabrolia regions here` | TPS of the island you stand on. |
| `/fabrolia regions tps` | Shows main thread and island TPS above the hotbar (toggle). |
| `/fabrolia regions` | Overview: main thread TPS, islands, regions and entities per dimension. |
| `/fabrolia regions list` | Every island on one line. |
| `/fabrolia regions top [count] [mspt\|maxmspt\|lag\|tps]` | The slowest islands. |
| `/fabrolia stats` | Mod status and enabled features. |
| `/fabrolia stats entity [count] [ticks]` | Entities per dimension and type; `ticks` also records their tick times. |

Operators:

| Command | Level | What it does |
| --- | --- | --- |
| `/fabrolia regions player <player>` | 2 | TPS where another player is. |
| `/fabrolia regions playerticks [on\|off]` | 4 | Players tick on their own island. |
| `/fabrolia regions islandentities [vanilla\|parallel]` | 4 | Entities of one island in vanilla order (default) or in parallel per region. |
| `/fabrolia regions on\|off\|toggle` | 4 | Region threading on or off. |
| `/fabrolia regions rate here <tps>` | 4 | Slow down or speed up the island you stand on (also `/tick rate island here`). |
| `/fabrolia regions top verbose [count]` | 4 | Raw island figures for diagnosis. |
| `/fabrolia regions crossdim [on\|off]` | 4 | Overworld/Nether island coupling. |
| `/fabrolia config reload` | 4 | Reloads `config/fabrolia.toml`. |
| `/fabrolia config toggle` | 4 | Disables or enables Fabrolia. |
| `/fabrolia config synchronizedEntities [add\|remove <entity>]` | 4 | Entity types that always tick on the main thread. |

Diagnostics live under `/fabrolia regions debug`. On Fabric, a permissions mod
such as LuckPerms can grant the nodes `fabrolia.command.regions`,
`fabrolia.command.regions.player`, `fabrolia.command.regions.toggle`,
`fabrolia.command.statistics` and `fabrolia.command.config` instead of the levels.
With Carpet, `/log tps` shows the main thread and the island you are on.

## Configuration

`config/fabrolia.toml`; the commands above write the same settings.

| Setting | Default | Meaning |
| --- | --- | --- |
| `disabled` | `false` | Turns Fabrolia off without removing it. |
| `enableRegionThreading` | `true` | Tick islands on worker threads. |
| `enablePlayerTickActor` | `true` | Players tick on their own island. |
| `regionWorkerCount` | `-1` | Worker threads; `-1` uses the core count minus two. |
| `islandEntityParallel` | `false` | Parallel entity ticking inside one island. |
| `fabroliaCrossDimCoupling` | `true` | Overworld and Nether islands that overlap share one clock anchor. |
| `synchronizedEntities` | TNT, item, XP orb | Entity types ticked serially; used only with `enableRegionThreading = false`. |
| `maxThreads` | `-1` | Threads of the helper pool; `-1` uses the core count. |
| `threadPriority` | `4` | OS priority of the worker threads (1 to 10). |
| `fabroliaSpawnLocalCapReservation` | `true` | Parallel spawning reserves a pack's slots in the per-player mob cap. |
| `enableFabroliaVmpTracking` | `true` | Runs VMP's entity tracking on the Fabrolia pool; no effect without VMP. |

Internal switches are not config keys. The `/fabrolia regions debug` toggles and
`/fabrolia config setFabroliaEntitySpawn|setFabroliaRandomTicks|setFabroliaDespawn` change the
running server only and are never saved.

## Building

Requires the JDK named in `gradle.properties` (`java_version`).

```bash
./gradlew :common:test :fabric:build :neoforge:build
```

Jars are written to `fabric/build/libs/` and `neoforge/build/libs/`. Before
changing code, read [AGENTS.md](AGENTS.md): the contract, the build and test rules
and a map of the code.

## Credits and license

Fabrolia is based on [Async by AxalotL](https://github.com/AxalotLDev/Async), with
thanks to Grider and jediminer543 for MCMTFabric and JMT-MCMT before it.
Licensed under the GPL-3.0, see [LICENSE](LICENSE). Report bugs in the
[issue tracker](https://github.com/DUzzL/fabrolia/issues).
