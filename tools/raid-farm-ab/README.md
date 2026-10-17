# Raid farm A/B (real protocol client)

Runs an original farm world on a local Fabric 26.3 server, operated by a real
26.3 protocol client instead of a Carpet bot, and logs raid, omen and POI events.
Use it to compare vanilla (optionally with Lithium) with the same server plus a Fabrolia jar.
Background and results: [RAID-FARM-OMEN-2026-10-17](../../docs/RAID-FARM-OMEN-2026-10-17.md).

Why a real client: Carpet bots tick in the entity list (vanilla) or in an early
player body (Fabrolia), while real players tick after the world tick. This farm
converts Bad Omen in a one-tick village window and only works with real-player
timing.

## Files

- `prepare_world.py`: extract one variant from the TMC world zip (not redistributed).
- `probe/`: observational Fabric mod; writes `raidprobe.log` in the server directory
  (raid created/stopped/waves/village state of the center, Bad Omen conversion,
  Raid Omen expiry, omen effects added/removed, POI add/remove and tickets, villager
  job-site memories, path candidates, activities and selected raid). Codec loads
  compare registry encounter order with a fresh native fastutil table populated
  from the same serialized IDs. It changes no behaviour.
- `wireclient.py`: thread-based client on top of `tools/stand/perf-wire-client.py`
  (adds attack, interact, punch and entity tracking).
- `run_farm_ab.py`: builds `build/raid-farm-ab/NAME`, flips the farm lever with a
  short-lived Carpet bot, logs the client in, operates the farm and samples
  scoreboard statistics every 30 s into `report.json`.
  New reports include the initial scoreboard and input-world manifest hash.
- `summarize.py`: compares completed reports and probe logs, including accepted
  attacks, sweep counts, ground state, bell-release clocks and loaded jar hashes.
- `profile_costs.py`: compares the changed raid frames in saved Spark profiles;
  uses the protobuf requirements from `tools/spark/requirements.txt`.
- `bell_gap_control.py`: a deliberate fixture intervention, separate from the
  observational probe. After the first raid is observed, it waits 18 Main ticks,
  removes the bell and fallback fletching table for six ticks, then restores
  both blocks. It checks the raid's response to an absent village, not the cause
  or frequency of natural villager POI releases. Run it alongside a fresh farm
  driver with at least three minutes of operating time:

  ```bash
  python -I tools/raid-farm-ab/bell_gap_control.py build/raid-farm-ab/NAME --port 26380
  ```

  Its report is `NAME/bell-gap-control.json`. Probe timestamps can use island
  time; the intervention schedules its relative duration on Main's raw clock.

## Steps (Windows paths work the same)

```bash
python -I tools/raid-farm-ab/prepare_world.py "<TMC zip>" <world-dir>
bash gradlew -p tools/raid-farm-ab/probe build
python -I tools/raid-farm-ab/run_farm_ab.py vanilla-1 --port 26200 --minutes 10 \
  --world <world-dir> --template <fabric-26.3-server-dir> --carpet <carpet.jar> \
  --fabric-api <fabric-api.jar> --lithium <lithium.jar>
python -I tools/raid-farm-ab/run_farm_ab.py fabrolia-1 --port 26210 --minutes 10 ... \
  --fabrolia fabric/build/libs/fabrolia-alpha_<version>.jar [--parallel]
```

The template is a plain Fabric server directory (`fabric-server-launch.jar`,
`server.jar`, `libraries/`), e.g. from the Fabric installer's `server` command.
Ports `PORT` (game) and `PORT+1` (RCON) must be free. Compare `rtrig` (raids
triggered), `rwin`, kills and `bott` (bottles drunk) in the two `report.json`
files, and the event order in the two `raidprobe.log` files.

Omit `--lithium` to compare with Minecraft's own AI implementation. `--disabled`
loads Fabrolia with the global kill switch and all island/player threading off;
it isolates collection/AI mixins from the scheduler.

For the same farm scenario with an all-thread profile, add `--spark <spark.jar>`.
It starts profiling after 180 seconds of operator warmup and saves locally after
120 seconds (`--profile-from` / `--profile-for` override these). Profiles and the
actual duration are recorded in the report. Compare changed frames as well as
the amount of raid work; this is a local farm profile, not the production stand.

To check saved raids after a clean stop, use the preceding run's `world/` as
`--world` and add `--resume`. This preserves the enabled farm lever instead of
toggling it. The usual operator equipment and Bad Omen setup still run; existing
Raid Omen and saved raid state are retained. `score_delta` subtracts the initial
scoreboard. Check nonempty `REGISTRY_DECODE` records for `orderMatches=true`,
continued waves and clean shutdown.

```bash
python -I tools/raid-farm-ab/summarize.py build/raid-farm-ab/vanilla-1 build/raid-farm-ab/fabrolia-1
```

Check actual `PLAYER_ATTACK` / `PLAYER_SWEEP` events before interpreting kills:
sent attack packets do not prove that the player was grounded and sweeping.

## Client behaviour (1-player variant)

Right button held: while the farm's armor stand is in front of the player the
client interacts with it (a real client predicts success and never reaches the
off-hand); without it, it sends the block use on the tripwire and drinks the
off-hand Ominous Bottle (40 ticks). Left button every `--attack-interval` ticks
on the armor stand, never while drinking (a real client cannot), and never on the
bare tripwire (that would break the farm). Position is `(0.43, 76.0, 14.55)` facing
`92.37/49.18`. The original save has Y=76.12, above the closed trapdoor's top;
a real client falls to Y=76.0. The protocol client must use that floor height:
holding Y=76.12 without gravity made `onGround` and sweep attacks depend on packet
timing, even in vanilla. Attack/sweep observations are logged by the probe;
samples also record the main-hand item and selected target.
