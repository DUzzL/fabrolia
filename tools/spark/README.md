# spark2md — decode `.sparkprofile` into readable Markdown + a flame pyramid

Decodes [spark](https://spark.lucko.me/) profiler dumps (`.sparkprofile`, the
`SamplerData` protobuf) into a compact **diagnosis summary**, full-fidelity
**Markdown** (for people and for LLM agents), and a merged-cell **`.xlsx` flame
pyramid** (icicle graph).

No network, no upload, fully local.

## Requirements
- Python 3 with the `protobuf` runtime (`python3 -c "import google.protobuf"`).
- `protoc` only to regenerate the proto bindings; `gen/spark/*_pb2.py` are committed.

## Usage
```bash
python3 spark2md.py FILE [FILE ...] [--out-dir DIR] [options]
```
Example:
```bash
python3 spark2md.py profile.sparkprofile --out-dir analysis/2026-10-06
python3 spark2md.py *.sparkprofile --only summary          # just the compact reports
```

### Outputs (per input file)
| File | For | What |
|---|---|---|
| `<name>.summary.md` | start here (people & agents) | ~70–250 KB. Health, 10 s metrics, per-minute windows, thread overview (CPU cores per pool), **thread states** (running vs park/wait/blocked/io/safepoint), **wait sites**, per-window breakdown, self time by mod, top self / inclusive methods, **slow-vs-fast window diff**, hot call tree ≥1 % with wrapper chains folded, world/entity/chunk stats, server.properties, JVM flags. |
| `<name>.ai.md` | LLMs / agents | Everything, nothing pruned, flat, greppable `## SECTION` markers (`grep -n '^## '`). Full methods tables and the full indented call tree with per-node `ms/tick`, `[mod]`, `{state}`. |
| `<name>.human.md` | people | Same data with emoji headings and a collapsible `<details>` call tree. |
| `<name>[.<thread>].pyramid.xlsx` | spreadsheet | Call tree as a merged-cell icicle, coloured by owning mod. One file per thread. |

### Options
- `--only summary,ai,human,xlsx` — comma-separated subset (default: all).
- `--no-xlsx` — skip the spreadsheet(s).
- `--min-percent P` — hide full call-tree branches below `P`% of the thread (default `0` = keep everything).
- `--summary-min-percent P` — threshold of the summary's hot tree (default `1`).
- `--pyramid-cols N` — xlsx pyramid resolution (default `600`).

## How to read the numbers
- **Wall-clock sampling.** spark's async engine always runs async-profiler with
  `event=wall`, so a thread's samples include the time it was parked or blocked.
  Every frame gets a state from the first wait primitive on its root→leaf path:
  `park` (LockSupport/Unsafe.park), `wait` (Object.wait, JVM condvars/semaphores),
  `blocked` (contended monitor), `sleep`, `io` (poll/epoll/read/write/recv/accept),
  `safepoint`, else `running`. "Self by source" and "top self" count **running** time only.
- **Wait sites** = nearest application frame above the wait (JDK lock plumbing
  skipped). On `Server thread`, `MinecraftServer.waitForTasks` between ticks is idle
  headroom, not lag.
- **ms/tick** = value ÷ ticks counted inside the retained windows. Wall ms/tick of a
  window = window length ÷ ticks (50 = 20 TPS).
- **Slow vs fast windows**: slowest vs fastest quarter of 1-minute windows (by wall
  ms/tick); shows which methods' inclusive ms/tick grew.
- **Attribution**: `method_sources` (mixin-injected methods) → `line_sources` →
  mixin handler name (`handler$abc000$modid$…`) → `class_sources`. Buckets:
  `(native)`, `(jdk)`, `(unattributed: <package>)` for libraries/generated classes.

## Notes on the format (verified against spark source)
- `.sparkprofile` is **raw, uncompressed protobuf** (`spark.SamplerData`), schema from
  `lucko/spark` `spark-common/src/main/proto/spark/` (vendored in `proto/`;
  upstream commit in `proto/UPSTREAM_COMMIT`).
- The call tree is **flattened**: `ThreadNode.children` is a flat pool; `children_refs` index into it.
- Per-node `times[]` align with the sorted `SamplerData.time_windows`. EXECUTION mode:
  milliseconds; ALLOCATION mode: bytes (all output switches to bytes).
- Window ids are `(monotonic ms + random ±30 s offset) / 60000` — not epoch minutes;
  real start/end come from `WindowStatistics.start_time/end_time`. A window still in
  progress at export has zero stats.
- Long/continuous profiles keep only the **last 60 windows** (`spark.continuousProfilingHistorySize`);
  `number_of_ticks` still counts the whole run, so ms/tick uses the retained windows' ticks.
- `PlatformStatistics.gc` is the delta since the profile started; `SystemStatistics.gc` is since JVM start.
- `SamplerMetadata.metrics` (spark ≥ 1.10.18x): 10 s series with 1 h retention.

## Files
- `core.py` — parse, tree reconstruction, total/self, thread states, wait sites, mod
  attribution, per-tick normalisation, recursion-safe inclusive totals, window/metric/world helpers.
  `python3 core.py FILE` prints a quick sanity dump.
- `spark2md.py` — CLI + Markdown renderers (summary / ai / human), text icicle, hot tree.
- `frames.py` — compare selected frames per thread group. Its ms/s denominator
  is the retained window span inside the actual capture; partial minutes count
  only their captured duration. Missing tick counts are reported as unavailable.
- `xlsx_pyramid.py` — zero-dependency `.xlsx` writer for the merged-cell pyramid.
- `proto/`, `gen/` — spark `.proto` sources and compiled Python bindings.

### Update the proto schema from upstream
```bash
git -C ../upstream-spark pull   # clone: git clone https://github.com/lucko/spark ../upstream-spark
cp ../upstream-spark/spark-common/src/main/proto/spark/{spark,spark_sampler}.proto proto/spark/
protoc --proto_path=proto --python_out=gen proto/spark/spark.proto proto/spark/spark_sampler.proto
git -C ../upstream-spark rev-parse HEAD > proto/UPSTREAM_COMMIT
```

## Window and frame tools (Fabrolia)

`spark2md.py` summarises a whole profile. To explain a lag *episode*, compare the
1-minute windows of the episode with the windows before it:

```bash
python3 sparkwin.py FILE groups 54-60 40-53            # per thread group: states + top self/incl by delta
python3 sparkwin.py FILE subtree 'Region-Worker' 'ItemEntity.tick$mixinextras$wrapped' 58-60 53-57
python3 sparkwin.py FILE callers '^Server thread$' 'IslandCommands.mainClaim' 0-60 4
python3 sparkwin.py FILE perwin '^Server thread$' 'MinecraftServer.autoSave'
python3 frames.py A.sparkprofile B.sparkprofile        # fixed list of hot Fabrolia frames, ms/s and ms/tick
```

Window indices `k` and their times come from the `## WINDOWS` table of the summary
(or `perwin`). Values are ms per wall second summed over the matching threads
(1000 = one core). Use `frames.py` to compare two stand runs (base jar vs candidate).
