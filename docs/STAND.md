# Test stand

A fresh production-like Minecraft 26.3 server (the production mod set, datapacks and
Fabrolia config) driven by 40 real protocol clients over loopback. It is how changes are
proven at runtime; unit tests alone do not prove a threading change.

## Layout

| Path | What |
|---|---|
| `tools/stand/stand.sh` | entry point (perf / verify / export / build / run) |
| `tools/stand/*.py` | harness copied into every run: transport (`native-transport.py`), clients (`perf-wire-client.py`), drivers, scenarios, host settings (`standenv.py`) |
| `$FABROLIA_STAND_HOME` (default `../Fabrolia-stand`) | data, not in git: `template/` (server inputs), `datapacks/`, `fabrolia.toml`, `extra-mods/` (MCFC), `runs/` |

Each run is `runs/<NAME>/` with `server/` (a real server dir), `wire-<client>.jsonl`
(client logs), `<NAME>.out` (driver output) next to it, and the driver report.

## Commands

```bash
J=fabric/build/libs/fabrolia-fabric-0.2.38+alpha-26.3.jar
tools/stand/stand.sh verify vfy-1 "$J"                           # functional checks, ~5 min
tools/stand/stand.sh perf   perf-1 "$J" 120                      # load + spark profile, ~4 min
tools/stand/stand.sh perf   items-1 "$J" 60 --scenario items     # + 3000 items in flowing water
tools/stand/stand.sh perf   pist-1 "$J" 60 --scenario pistons    # + 225 piston clocks
tools/stand/stand.sh perf   heap-1 "$J" 60 --heap                # + two GC.class_histogram snapshots
tools/stand/stand.sh export exp-1 "$J"                           # mixin export: runs/exp-1/server/.mixin.out
```

Run names are unique (`runs/<NAME>` must not exist). Commands take
`$FABROLIA_STAND_HOME/stand.lock`, so two stands never overlap (they share port 25935
and ~7 GB of RAM). A run takes 4–8 minutes; give your shell tool a long timeout.

## Reading the result

- `verify` prints `verify DONE ALL PASS` or the failing checks; details in
  `runs/<NAME>/verify-report.json`. Checks: datapack function fences, advancement
  grants (story root, inventory trigger, BACAP reward, `grant everything`), disconnect
  durability, Nether portals both ways, saves and player files, log and crash scan.
- `perf`: `runs/<NAME>/perf-report.json`, spark profile in
  `runs/<NAME>/server/config/spark/*.sparkprofile`. Compare two jars on the same
  scenario with `python3 tools/spark/frames.py A.sparkprofile B.sparkprofile`
  (ms/tick of the hot Fabrolia frames). The stand's Main load is light (~2 ms/tick):
  compare the frames you changed, not TPS.
- Island state at warm-up and end: `before` / `after` in `perf-report.json`
  (`fabrolia regions top verbose 100`: per-island TPS, MSPT, lag, missed deadlines).
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
