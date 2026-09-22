<div align="center">

# Fabrolia - Minecraft Entity Multi-Threading Mod ⚙️

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/fabrolia?style=for-the-badge&logo=modrinth)](https://modrinth.com/mod/fabrolia)
[![Discord](https://img.shields.io/discord/YOUR_DISCORD_ID?style=for-the-badge&logo=discord&label=Discord)](https://discord.com/invite/scvCQ2qKS3)
[![GitHub Issues](https://img.shields.io/github/issues/AxalotLDev/Fabrolia?style=for-the-badge)](https://github.com/AxalotLDev/Fabrolia/issues)
</div>



**Fabrolia** is a Fabric and NeoForge mod that schedules entity simulation across independently eligible regions and island timelines.


## Important❗
**Fabrolia** is currently in alpha testing and is experimental. Its use may lead to incorrect entity behavior and crashes.

## Region execution model

With region threading enabled, each region ticks its entities serially on its
claim owner, in the existing entity-set order, at every population size. Items,
AI-enabled mobs and TNT use the same ordered traversal; dense collision groups
and entity/TNT helper batches are no longer execution paths.

Independently admitted island units may run concurrently. Each retains its real
coordinator claims, generation and local clock. Producers with shared or unproven
dependencies acquire global ownership before entering their native body; full
NBT advancement predicates and opaque hooks can therefore serialize complete
units. Distance between islands alone does not make those callbacks safe.

Physical Main controls lifecycle and Main-only work, not the entire gameplay
tick. After the native control census, optional exclusive grants use a finite
publication cut and yield between whole grants at Main's native deadline.
Unfinished cuts survive subsequent waits; required nested dependency pumps remain
unbounded. No accepted work or native callback is skipped to maintain Main TPS.

A concentrated farm or large TNT population still has serial entity traversal
within its island. Independent clocks and spare cores do not guarantee 20 TPS
when the required work exceeds the available service time. Persisted parallel
preferences do not override ownership checks.

Use `/fabrolia regions debug regtop` for region timings. Main's completed-loop
MSPT includes required coordination and worker waits, excluding native deadline
idle; unfinished active work is shown separately. A completed island tick's cost
is not the cost of its current queue/ownership delay and does not imply healthy TPS.



### 💡 Key Benefits:
- **Independent island timelines**: Eligible, independently owned units can progress concurrently.
- **Worker execution**: Local units and selected exclusive roots run on actual workers under their required claims.
- **Experimental random ticks**: Follow their configured scheduling path without bypassing ownership.

The villager measurements below are **historical Minecraft 1.21.4 results**,
not measurements of the current Minecraft 26.2 implementation.
They do not support a current speedup claim.

### 📊 Performance Comparison (9000 Villagers)
| Configuration           | TPS  | MSPT   |
| ----------------------- | ---- | ------ |
| **Lithium + Fabrolia**     | 20   | 41.8   |
| **Lithium (without Fabrolia)** | 4.4  | 225.4  |
| **Purpur**              | 5.72 | 176.18 |

### 🛠️ Test Configuration
- **Processor**: AMD Ryzen 9 7950X3D
- **RAM**: 64 GB (16 GB allocated to the server)
- **Minecraft Version**: 1.21.4
- **Number of Entities**: 9000
- **Entity Type**: Villagers

<details>
<summary>Mod List</summary>
Concurrent Chunk Management Engine, Fabric API, FerriteCore, Lithium, ScalableLux, ServerCore, StackDeobfuscator, TT20 (TPS Fixer), Tectonic, Very Many Players, Fabric Carpet.
</details>

## ⚠️ Incompatible Mods
- ❌ Moonrise - Known incompatibility
- ⚠️ ...and there may be conflicts with other mods.

*If you encounter issues with other mods, please report them on our [GitHub](https://github.com/AxalotLDev/Fabrolia/issues) or [Discord](https://discord.com/invite/scvCQ2qKS3).*

## 🔧 Commands
- `/fabrolia config toggle` — Enables or disables the mod in-game (no server restart required). Compare behavior and timings on your workload; a speedup is not promised.
- `/fabrolia config setFabroliaEntitySpawn` — Enables or disables Fabrolia mob spawn processing (disabled by default); it does not override serial gameplay ownership. **Warning: Not compatible with Carpet mod lagFreeSpawning rule.**
- `/fabrolia config setFabroliaRandomTicks` — Enables or disables fabrolia random ticks processing (experimental feature).
- `/fabrolia config synchronizedEntities add` — Adds an entity type to synchronized processing in the regions-off flat pool.
- `/fabrolia config synchronizedEntities remove` — Removes an entity type from that flat-pool policy. Neither command changes serial ordering inside a region.
- `/fabrolia stats` — Displays the number of threads in use.
- `/fabrolia stats entity` — Shows the number of entities processed by Fabrolia in various worlds.
- `/fabrolia stats entity [number]` — Shows the top [number] entity types by count in descending order. For example, `/fabrolia stats entity 10` displays the top 10 most numerous entity types.

## Stability verification and limits

The exclusive-gameplay change passed the common suite: 1,323 discovered tests,
1,319 passed and four conditional checks skipped. Fabric and NeoForge builds
completed. A matched, bounded Fabric 26.2 workload with eight islands and
256 AI-enabled pigs maintained about 20 local ticks/s before and after the
change; server process CPU rose from 0.269 to 0.814 core-equivalents.
This single roughly 20-second sample, after a short warmup, does not measure
saturated throughput or establish a general performance ratio.

Real TCP players passed milk and honey consumption with poison, `Health:1.0f`
and health score 1 in ordinary and async modes; the async producer was an actual
claimed island worker. This used a faithful minimal datapack, not the full BACAP.
Regressions also passed 12 Nether crossings, native 26.2 waypoint-handler replay
of 163 received packets across three client lifetimes, and six ridden-boat
crossings with accepted vehicle control. No graphical client verification was
performed.

The subsequent portal-publication fix was checked with a cold Overworld fixture
whose native terrain feature creates POIs during `createPortal`: the preceding
build reproduced the reported exception; the corrected build waited for publication
and completed the transfer. Cold creation in both Overworld and Nether passed
native POI/block checks, return travel and reuse without a second creation.
These runs used a frozen local mod stack and disposable worlds, not the affected
server's exact world or complete modpack.
The portal-fix common suite passed 1,322 tests, with four conditional checks skipped
out of 1,326 discovered tests; Fabric and NeoForge builds completed.

The subsequent scheduler-liveness fix passed 1,328 common tests, with four
conditional checks skipped out of 1,332 discovered; both loader builds completed.
A matched Fabric 26.2 replay used 100 Carpet fake players in fresh flat worlds,
frozen local mods, and the server's Fabrolia/C2ME configuration. The old build
stopped responding during joins after 32 join log entries; the corrected build
retained all 100 players and advanced game time from 54 to 606 over 30.2 seconds.
Six live ready-queue samples ranged from 0 to 1,378 entries, not millions.
The corrected `RegionWorkerPool` stopped before native world saving began.
This was **not a clean-shutdown pass**: C2ME chunk unloading exceeded the
90-second stop allowance and the replay JVM was forcibly terminated.
These results establish bounded in-game progress for this fixture, not general
throughput, original-world recovery, complete shutdown or NeoForge runtime parity.

The generation/lifecycle correction passed 1,344 common tests, with four
conditional checks skipped out of 1,348 discovered; Fabric and NeoForge builds
completed. A disposable normal-terrain world used the reported world's seed and
requested crash-area chunk. A temporary observer recorded the actual
`OceanRuin → Drowned.finalizeSpawn → startRiding` body returning successfully on
a C2ME worker. That focused one-player run maintained 20 local ticks/s and stopped
normally with exit code 0. Regression tests separately cover the healthy
exclusive cut, nested native ride mutation, expired/private/shared identity
boundaries and authoritative failure selection.

Matched normal-terrain runs with 100 Carpet players measured all 100 island
clocks over roughly one minute: 10.27 local ticks/s before and 11.30 after.
During the final 25 seconds, sampled generation frames on C2ME went from zero
to 3,055, and JFR JVM CPU share rose from 5.3% to 12.1%. The native server-queue
regression also verifies chunk completion while gameplay continually replenishes.
**This is restored generation progress, not 20-TPS performance acceptance.**
Both matched runs exceeded a 30-second shutdown allowance. A separate instrumented
100-player run on the reported seed averaged 7.04 local ticks/s over 179 seconds;
its observation deadline and subsequent 120-second chunk-unload allowance expired.
The remaining throughput loss has not been isolated. The focused crash replay
does not establish a clean mass-load shutdown, original-world recovery or
NeoForge runtime parity.

Earlier verification, **before the exclusive-gameplay change**, recorded an
integrated common suite with 1,163 discovered tests: 1,159 passed and four
conditional runtime/modpack checks were skipped. Fabric and NeoForge builds
completed; live-server verification used Fabric 26.2 with two protocol clients,
Fabric API and Carpet, both with and without Lithium.

That earlier disposable real-entity fixture exercised 258 roots in one region
and 96 in a separate region. Event and independently selected legacy dispatch
showed cross-region body overlap under the then-current policy, with one active
tracked body per region, preserved entity order and single tick/fuse advancement.
**That overlap is no longer the execution contract.** Controlled real-method
probes also reproduced and fixed lost spawn potential and saved-data dirty
retirement. These historical correctness observations are not current performance
evidence or a full-modpack compatibility certification.

### Advancement timing and authority

Complete listener matching is eager at trigger time: for each listener in native
order, evaluate its trigger/item matcher first, then its player condition only
if that matcher passes. Both observe the live producer state under exclusive
gameplay ownership established **before producer entry**. The buffer retains
only matched criterion keys and their player, not predicates or live contexts.
Native entity NBT fetches are uncached so later predicates observe current state;
the existing direct-field NBT fast path remains. Awards and rewards still wait
for the existing monitor-free drains. Per-drain criterion deduplication is
unchanged: this fixes predicate timing, not vanilla per-fire reward timing
for counter-style reward functions.

Queued work retains its lifecycle receipts throughout worker grants. A selected,
parked worker can delegate only the exact command or chunk-service boundary to
physical Main. This is not a way to migrate an arbitrary entered producer with
its live arguments, JVM monitors or scoped context. Public `causalHandoff`
propagates lifetime accounting, **not transferable world access**;
`executeOnOwner` queues work when a foreign caller lacks owner authority.
Neither a logical Main alias nor a foreign executor gains world access from
that handoff.

Main-side frameless advancement drains are queued rather than run inline. A
separate Fabric 26.2 crafting/inventory probe, with async islands enabled and
Main-owned player packets, observed a reward inside the packet handler before
the change and outside it afterwards, with its function applied exactly once.
The full-modpack watchdog itself was not reproduced.

## 📥 Download
The mod is available on [Modrinth](https://modrinth.com/mod/fabrolia)

## 🔄 Minecraft Version Support
Full support is provided only for the latest version of Minecraft. Older versions receive critical fixes only. Support for older Minecraft snapshots is not planned.

## 📭 Feedback
Our tracker for feedback and bug reports is available on GitHub:
[![Report issues on GitHub](https://img.shields.io/badge/Report%20issues%20on-GitHub-lightgrey)](https://github.com/AxalotLDev/Fabrolia/issues)

You can also chat with us on Discord:
[![Chat with us on Discord](https://img.shields.io/badge/Chat%20with%20us%20on-Discord-blue)](https://discord.com/invite/scvCQ2qKS3)

## 🙌 Acknowledgements
This mod is based on code from [MCMTFabric](https://modrinth.com/mod/mcmtfabric), which in turn was based on [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT). Huge thanks to Grider and jediminer543 for their invaluable contributions!