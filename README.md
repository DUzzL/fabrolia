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
example a player's base and everything around it. By default each island ticks
on its own clock. Different islands can tick entities, block entities and
scheduled ticks at the same time. Within one island the default entity pass
follows vanilla insertion order.

A copper golem can remember a chest after a teleport to another island. When its
next turn needs both islands, the worker reserves the chest's island before it
starts the turn. The chest's own clock does not advance with the golem. An
unrelated island can keep ticking while a write to that chest waits.
This includes nearby golems that are visible to the chest but are not themselves
ticking. If a required cell belongs to no island, the affected turn waits for
that level's global work instead of treating an island lock as permission to read it.

To compare behavior against a shared clock, start the server with
`-Dfabrolia.syncMode=true`. In this diagnostic mode the server thread visits the
native entity list with region workers paused. Restart without the flag to return
to independent clocks. The old `asyncIslands` config key has no effect.
Vanilla `/tick freeze` pauses autonomous pacing; `/tick step N` runs exactly N
simulation frames. Unfreezing resumes each island's configured rate.

Fabrolia only helps where work can be split: one huge base with all players in it
is still one island. The goal is to preserve vanilla work and its observable order.

Nearby-item searches and serial island entity passes reuse only private working
arrays. Nested searches get separate storage. Neither the lists returned by the
world nor a mob's remembered item are recycled. Buffer sizes limit retained
memory, not how many entities can be checked or ticked.

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
| `/fabrolia regions playerticks [on\|off]` | 4 | Select the player venue for independent-clock mode. |
| `/fabrolia regions islandentities [vanilla\|parallel]` | 4 | Choose entity order inside an autonomous island; does not change the shared-clock pass. |
| `/fabrolia regions on\|off\|toggle` | 4 | Region threading on or off. |
| `/fabrolia regions rate here <tps>` | 4 | Change an autonomous island's rate. With a shared clock use vanilla `/tick rate`. |
| `/fabrolia regions sprint here <ticks>` | 4 | Advance an autonomous island by a tick count. With a shared clock use vanilla `/tick sprint`. |
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
| `enableRegionThreading` | `true` | Enable regional workers; does not select the clock. |
| `enablePlayerTickActor` | `true` | Tick eligible players on their own island in independent-clock mode. |
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

With Fabrolia enabled and region threading off, the entity list is taken after the flat spawn jobs finish.
If that list contains a copper golem (including a passenger), its entire pass runs in
insertion order on the server thread: each entity's despawn check stays with its turn.
Nested checks for the same chest use the vanilla opener list; matching coordinates in
different dimensions do not interfere. Two threads checking the same chest at once still
get Fabrolia's older busy fallback on the contending thread. This is not an inventory lock.

`FabroliaIslandApi.executeOnOwner` runs inline under an existing spatial claim.
With a shared clock and regions enabled, other submissions are queued in FIFO
order for an exclusive server-thread turn after the tick. Turning regions off
finishes accepted shared work before its owner retires; new submissions can be
refused during the switch. Accepted work also finishes before a normal shutdown
save. With regions off, the server thread can run a callback inline and other
threads post to its native queue. Do not block a tick thread waiting for queued work.
Region work submitted without a position through `RegionScheduler.enqueueGlobal`
waits for an exclusive server-thread turn. Use `executeOnOwner` when the target
is known so independent work need not wait for this turn.

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
