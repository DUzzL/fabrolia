# Test stand

A fresh production-like Minecraft 26.3 server (the production mod set, datapacks and
Fabrolia config) driven by 40 real protocol clients over loopback. It is how changes are
proven at runtime; unit tests alone do not prove a threading change.

## Layout

| Path | What |
|---|---|
| `tools/stand/stand.sh` | entry point (perf / verify / export / build / run) |
| `tools/stand/*.py` | harness copied into every run: transport (`native-transport.py`), clients (`perf-wire-client.py`), drivers, scenarios, host settings (`standenv.py`) |
| `stand/` (or `$FABROLIA_STAND_HOME`) | data, not in git: `template/` (server inputs), `datapacks/`, `fabrolia.toml`, `extra-mods/` (MCFC), `runs/` |

Each run is `runs/<NAME>/` with `server/` (a real server dir), `wire-<client>.jsonl`
(client logs), `<NAME>.out` (driver output) next to it, and the driver report.

## Retaining results and freeing disk space

Old run terrain (`region/`, `entities/`, `poi/`), generated caches, BlueMap output
and copied server libraries can be removed once no stand process is running.
Keep reports, client traces, logs, Spark profiles, saved NBT/player state and the
tested mod artifacts as evidence. Keep `template/`, `datapacks/`, `extra-mods/`
and the stand config to build future runs. Original imported farm downloads are
retained in `stand/fixtures/`.

Runs marked `ARCHIVED.md` have had generated server data removed. They are
evidence archives, not runnable servers; create a fresh run with a new name.
Some immutable files share hard links, so replace those files rather than editing
them in place. Removing a run's hard link does not remove the template's copy.

## Commands

```bash
J=fabric/build/libs/fabrolia-alpha_3.1.5.jar
tools/stand/stand.sh verify vfy-1 "$J"                           # functional checks, ~5 min
tools/stand/stand.sh perf   perf-1 "$J" 120                      # load + spark profile, ~4 min
tools/stand/stand.sh perf   items-1 "$J" 60 --scenario items     # + 3000 items in flowing water
tools/stand/stand.sh perf   pist-1 "$J" 60 --scenario pistons    # + 225 piston clocks
tools/stand/stand.sh perf   heap-1 "$J" 60 --heap                # + two GC.class_histogram snapshots
tools/stand/stand.sh export exp-1 "$J"                           # mixin export: runs/exp-1/server/.mixin.out
tools/stand/stand.sh build end-1 "$J" --rcon                     # fresh isolated End fixture
tools/stand/stand.sh run end-1 end-handoff-driver.py              # save/restart and live scheduler handoff
tools/stand/stand.sh build raid-1 "$J" --raid-fixture              # sim distance 2, view distance 10
tools/stand/stand.sh run raid-1 raid-ticket-driver.py --expect-no-wave # vanilla low-sim admission + spark
tools/stand/stand.sh build raid-2 "$J"                            # ordinary simulation distance 10
tools/stand/stand.sh run raid-2 raid-ticket-driver.py --loaded-ring # actual outer-ring wave
tools/stand/stand.sh build farm-1 "$J" --world-zip OriginalWorld.zip --vanilla-reference
tools/stand/stand.sh run farm-1 raid-design-driver.py compact 480 --require-victory
```

The raid fixture creates an occupied job-site village and four spawn platforms
80–106 blocks out, removes all forceloads, and triggers a raid with vanilla Bad
Omen. Simulation distance 2 must reject the unloaded outer wave; distance 10
must admit it. Compare the same scenarios on a fresh `--vanilla-reference`
stand (only Fabric API, Carpet and spark) and on the full modpack.
`--loaded-ring --remove-village-after-start` additionally removes the only
workstation after the raid starts and checks that it stops within one second.
All runs record SavedData and an all-thread spark profile.

The historical radius-6/radius-9 comparison used `--expect-old-failure` for
the old jar. Radius 9 was not retained after the owner required vanilla parity:
Fabrolia no longer creates automatic raid tickets.

`--world-zip` safely imports only saved Minecraft world files into a fresh run,
letting 26.3 convert older author worlds. It preserves farm blocks, entities and
POI data, and omits old players and raids. `--vanilla-reference` removes the
production mods and datapacks. The design driver controls operator/gear,
output chest contents and the author's on/off control; Nash's Bad Omen input
is driven separately from Carpet's attack action because Carpet gives USE
priority over ATTACK. The compact driver records operator position, pose,
look vector and attack ray, and `--require-victory` requires a complete raid
including the Hard bonus wave. Loot totals are smoke checks, not hourly-rate
measurements. Nash's operator setup has not produced a valid matched reference
comparison; its diagnostic runs cannot certify or reject that design's 26.3
compatibility. Shared raid mechanics are checked independently by the fixtures
and actual-Minecraft regression tests.

Run names are unique (`runs/<NAME>` must not exist). Commands take
`$FABROLIA_STAND_HOME/stand.lock`, so two stands never overlap (they share port 25935
and ~7 GB of RAM). A run takes 4–8 minutes; give your shell tool a long timeout.

## Reading the result

- `verify` prints `verify DONE ALL PASS` or the failing checks; details in
  `runs/<NAME>/verify-report.json`. Checks: automatic Carpet spawn-tracking/test refusal
  (other spawn commands stay available), datapack function fences, advancement
  grants (story root, inventory trigger, BACAP reward, `grant everything`), disconnect
  durability, Nether portals both ways, saves and player files, log and crash scan.
- `perf`: `runs/<NAME>/perf-report.json`, spark profile in
  `runs/<NAME>/server/config/spark/*.sparkprofile`. Compare two jars on the same
  scenario with `python3 tools/spark/frames.py A.sparkprofile B.sparkprofile`
  (ms/tick of the hot Fabrolia frames). The stand's Main load is light (~2 ms/tick):
  compare the frames you changed, not TPS.
- Island state at warm-up and end: `before` / `after` in `perf-report.json`
  (`fabrolia regions top verbose 100`: per-island TPS, MSPT, lag, missed deadlines).
- End fixture: `end-handoff-report.json`. Real End-portal falling-block transfers,
  source at 20 TPS and destination at 5 TPS; a NoGravity predecessor holds the gate
  deterministically. Checks queue NBT, two clean restarts, async-to-sync and scheduler
  off/on, new arrivals while stopped, recovery without End forceloads, delayed
  regeneration, one published successor, real sand placement and the next
  regeneration's item drop. It does not build a complete sand duper. With an old
  jar, `--expect-save-bypass` proves the original
  save bug instead of running the recovery checks. Lifecycle changes use local RCON
  because console commands run inside the server tick's open phase.
- Block packets seen by the static client next to the scenarios: last `block_packets`
  event in `runs/<NAME>/wire-Far05.jsonl`.
- Any `crash-reports/` entry is a failure. The log always contains ~2800
  `Block-attached entity at invalid position` lines (stand item frames): benign.

## Host requirements

- Linux, Python 3, `taskset`, a JDK 25 (`FABROLIA_STAND_JAVA`, default `java` on PATH;
  production runs Temurin 25 with JVMCI).
- ~8 GB free RAM, 16 GB server heap by default (`FABROLIA_STAND_XMX`).
- CPU pinning: server on ~60 % of the CPUs, clients on the rest (`standenv.py`;
  override `FABROLIA_STAND_SERVER_CPUS` / `FABROLIA_STAND_CLIENT_CPUS`, empty = no pinning).
- Sandbox: with passwordless `sudo` + `systemd-run`, the server runs in a transient
  unit with no network except loopback and a read-only home. Without it set
  `FABROLIA_STAND_UNSANDBOXED=1`; the server still binds only `127.0.0.1` and runs
  offline-mode with fake clients, but it can reach the internet (e.g. Carpet resolving
  bot names to real Mojang profiles), so results can differ slightly.

## Updating the template

`template/server/` is the production mod set minus Fabrolia (the jar under test is
added per run) and minus the production world. To follow a production mod update,
replace jars in `template/server/mods/` and configs in `template/server/config/`;
datapacks live in `datapacks/`. Keep MCFC in `extra-mods/`.

### TNT mechanics against Vanilla 26.3

`tnt-parity-driver.py` uses a fresh RCON-enabled run. It freezes fixture creation,
checks an independent TNT fuse clock, pins the test island to the wall clock
for reproducible step counts, and repeats four cases with `/tick step`
and `/tick sprint`: blast-before-motion order across x=128, next-phase first
visit of explosion-created TNT, one priming per exploded TNT block, and
one movement/fuse step per tick during region migration.

```bash
bash tools/stand/stand.sh build tnt-van-1 OLD_JAR --rcon --vanilla-reference
bash tools/stand/stand.sh run tnt-van-1 tnt-parity-driver.py
bash tools/stand/stand.sh build tnt-fix-1 NEW_JAR --rcon
bash tools/stand/stand.sh run tnt-fix-1 tnt-parity-driver.py \
  --compare-report stand/runs/tnt-van-1/tnt-parity-report.json
```

The reference retains only Fabric API, Carpet and spark; it excludes other
gameplay mods and production datapacks. Deterministic trajectories are compared
against that actual reference. Random priming fuse/motion is checked using
Vanilla ranges and the next-tick relation. This is a mechanics regression,
not a replay of a player's world eater.

Starting a sprint from frozen in the actual 26.3 reference admits one thaw
tick before the requested sprint ticks. The driver waits for sprint completion
and checks that physical count explicitly. The unpinned autonomous load is
covered separately by the normal `verify` and matched `perf` runs.

Use `--sync` on the candidate driver to exercise the global/Main entity lane
with async islands off against the same reference report.

### Minecart pushing against Vanilla 26.3

`minecart-parity-driver.py` runs on Linux or Windows with a prepared, world-free
Fabric server template (launcher, Minecraft server bundle and libraries),
explicit Fabric API/Carpet jars and Java 25. It binds the new server to loopback
and controls it through local RCON. Run directories must be fresh.

```bash
python tools/stand/minecart-parity-driver.py build/cart-van \
  --template TEMPLATE --mods FABRIC_API_JAR CARPET_JAR --suite all
python tools/stand/minecart-parity-driver.py build/cart-fix \
  --template TEMPLATE --mods FABRIC_API_JAR CARPET_JAR LITHIUM_JAR \
  --jar fabric/build/libs/fabrolia-alpha_3.1.5.jar --mode parallel --suite all \
  --compare-report build/cart-van/minecart-parity-report.json
```

The 56 cases cover moving, slow and stationary rideable/hopper pairs, both
insertion orders and three-cart chains, within one region and across x=128.
`Item.Age` independently verifies each physical tick; no TNT masks the mode
choice. Each trajectory difference fails the run. Use `--expect-old-failure`
only for an old jar that must reproduce the border regression.

Without `--template`, the driver accepts an already prepared `RUN/server`
with loopback RCON. `--jar` must match its single deployed Fabrolia artifact;
a Vanilla reference must contain only Fabric API, Carpet and optionally spark.
`--mode serial` checks the control path; `--mode sync` disables async islands.
`--perf-only --perf-seconds 60` with the same template and a spark jar records
four powered loops with 160 mixed carts. Compare old/new profiles with
`tools/spark/frames.py`. This focused fixture supplements the full production
modpack's 40-client `verify` and matched `perf`; it does not replace them.

### Nether portal transport and autonomous chunkloaders

`portal-parity-driver.py` uses the same world-free Fabric template on Windows
or Linux, but builds actual Overworld/Nether portals and physical observer /
dropper loaders. A real protocol client stays far away; all bootstrap forceload
tickets are removed before the measurement. It checks bidirectional mob and
item transfers, continued loading, shared 10/20 TPS changes, exact item
conservation and chunk unload after the clocks stop.

```bash
python tools/stand/portal-parity-driver.py build/portal-van \
  --template TEMPLATE --mods FABRIC_API_JAR CARPET_JAR \
  --java JAVA25 --server-port 26600
python tools/stand/portal-parity-driver.py build/portal-candidate \
  --template TEMPLATE --mods FABRIC_API_JAR CARPET_JAR LITHIUM_JAR \
  --jar fabric/build/libs/fabrolia-alpha_3.1.7.jar --mode parallel \
  --java JAVA25 --server-port 26610 --stress --expect-shared-target
```

`--mode serial` checks the vanilla-order island entity lane. `--stress` adds
200 ms artificial unit cost to one coupled member for eight seconds, then
requires recovery. `--expect-shared-target` checks the target TPS and override
marker in both verbose rows after the 10/20 TPS transitions (3.1.7+).
Add the same Spark jar to both old/new `--mods` lists and
use `--profile` for a capture of the initial steady workload. Each run needs
a fresh directory and an unused adjacent RCON port. Results and limits:
[2026-10-17 portal-loader review](PORTAL-LOADER-REVIEW-2026-10-17.md).
