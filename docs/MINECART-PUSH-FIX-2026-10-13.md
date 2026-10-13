# Minecart pushing regression — 2026-10-13

## Report and source baseline

A player reported that minecarts no longer push one another as before the last
Fabrolia update, affecting a sugar-cane collector and a world eater. The supplied
`Downloads/latest(2).log` identifies Fabrolia `3.1.1-alpha`, Minecraft 26.3,
17 region workers and active async islands/player ownership. It does not record
the value of `islandEntityParallel`, and no minecart collision exception appears.
The log separately contains a world-eater failure report at 05:23:57 and earlier
cross-region explosion warnings; those do not establish the minecart cause or
identify the reported farm's configuration.

The GitHub comparison found `origin/main` and local `main` at
`de95f41b94f50921cda97f4934b8707ae470b53e` (`3.1.0-alpha`). The newer
`origin/fix/raid-start` commit
`ec12327ab7aaf087137f0f45fa43b1f2ac3128f5` is `3.1.1-alpha`, matching the
logged version. Its runtime change concerns non-portal POI callbacks, not cart
physics. The fix branch starts from `ec12327`, retaining the raid fix. A version
string alone cannot prove the exact source commit of the production jar.

## Cause and change

With `islandEntityParallel=true`, an island's regions tick entities in ColorGate
waves instead of the actual Vanilla `EntityTickList` insertion order. The 3.1.1
admission check selected the serial path for primed TNT and TNT minecarts only.

Vanilla `AbstractMinecart.push` updates both carts' current momentum immediately.
The order of cart ticks and collisions therefore changes the subsequent movement
and collisions. The actual 26.3 push body demonstrates this directly: a three-cart
chain produces x velocities `[0.09, 0.055, 0.125]` in one collision order and
`[0.065, 0.115, 0.05]` in the reverse order. No impulse formula, movement body or
ordinary collision predicate needed changing.

3.1.2 extends `RegionizedServer.holdsOrderSensitiveEntity` from `MinecartTNT` to
`AbstractMinecart`. A unit containing any cart at entity-mode selection now uses
the existing queue-only join followed by `tickIslandEntitiesInVanillaOrder` under
the exclusive island claim. All seven Vanilla types and mod subclasses inherit
this policy. The existing portal-reservation guards and composed Vanilla cart
methods remain intact.

## Runtime differential

The isolated fixture uses actual Minecraft 26.3 servers, Fabric Loader 0.19.5,
Fabric API 0.162.0 and Carpet `26.3+v260915`. The Vanilla reference has no Fabrolia;
baseline/candidate runs add their respective Fabric jar. The initial 24-case
runs exclude Lithium; the subsequent 32-case candidate run includes Lithium
0.26.2. Rustmi, ServerCore and the complete production modpack are absent.

The fixture pins one island to the global tick phase, freezes the game and steps
five physical ticks. An independent item's `Age` must advance exactly once per
step. It compares each cart's `Pos` and `Motion` after every step, with absolute
tolerance `1e-7`, and includes no TNT that could mask the parallel path.

There are 24 cases: rideable/rideable, rideable/hopper and hopper/hopper pairs;
both insertion orders; motion into a stationary cart or an approaching cart;
and positions around x=64 within one region or x=128 across the region border.

| Recorded run | Mode | Scalar differences from Vanilla | Matching reference |
| --- | --- | ---: | --- |
| `build/minecart-parity/vanilla-1/report.json` | Vanilla reference | — | Reference |
| `build/minecart-parity/baseline-parallel-1/report.json` | 3.1.1 parallel | 120 | No |
| `build/minecart-parity/baseline-serial-1/report.json` | 3.1.1 serial | 0 | Yes |
| `build/minecart-parity/candidate-parallel-1/report.json` | 3.1.2 parallel selected | 0 | Yes |

The 120 differences occur in six cases: all three pair types at x=128, with
reverse insertion order, for both initial motion settings. The x=64 controls
and forward insertion cases match. The changed position/momentum values persist
across all five measured ticks. For example, in the first reverse-order
rideable/rideable stationary-cart case, the first cart's x position after one
tick is `127.75` on 3.1.1 parallel versus `127.63999999925494` on Vanilla.

All four report files have `status: "PASS"`, exit code 0 and no crash reports.
That status means the fixture completed successfully, **not** that Vanilla
parity passed. The baseline parallel report explicitly has
`matches_reference: false` and 120 entries in `differences`; only baseline serial
and candidate parallel have `matches_reference: true` and empty differences.

The reusable `tools/stand/minecart-parity-driver.py` adds 32 cases: slow or
stationary pairs across both candidate-query paths, plus three-cart chains,
again at x=64/x=128 and in both insertion orders. The actual Vanilla reference
is `build/minecart-parity/vanilla-extra-2/minecart-parity-report.json`. The
candidate with Lithium 0.26.2 in
`build/minecart-parity/candidate-extra-lithium-1/minecart-parity-report.json`
passes with zero differences, exit code 0 and no crash reports. The reused
driver fails on unexpected trajectory differences; its `--expect-old-failure`
option explicitly requires the old border regression to reproduce.

## Build and regression checks

`build/minecart-candidate-build.log` records a successful Java 25
`:common:test :fabric:build :neoforge:build`: 1,462 tests pass and eight optional
external-artifact/export checks skip. Both loader builds and the packaged
player-tick architecture verifier pass. `tools/release/check_jar.py` passes for
the tested 3.1.2 Fabric artifact (metadata `3.1.2-alpha`, 899 classes, Java 25).

`build/minecart-regression-before.log` records ten focused tests with three
failures against the old admission check: all Vanilla carts, a modded subclass
and the collision-chain admission. The actual impulse demonstration already
passes against the baseline because Vanilla physics is unchanged.
`build/minecart-regression-after.log` records all ten focused tests passing with
the fix. `build/minecart-documentation-check.log` separately records the source
reference documentation check passing.

The exact Fabric artifacts used for the differential are preserved under
`build/minecart-parity/artifacts/`:

| Artifact | SHA256 |
| --- | --- |
| `fabrolia-alpha_3.1.1.jar` | `a233b5aaf8a79a51727096470fba368e4135c0f1ad07a5f109ced8caa06ed570` |
| `fabrolia-alpha_3.1.2.jar` | `ab33f5ddef946b39b42249ee599336960ab11e20b76cc44858c5747361ee520a` |

## Scope, cost and remaining verification

One cart, including a stationary cart, selects the serial entity phase for the
**whole island**. Islands still execute concurrently with other islands. Dense
player/mob islands containing carts can therefore have longer units and longer
Main waits than 3.1.1 parallel. This is the existing serial ownership path;
the fix does not introduce a global entity fence or parallel cart momentum writes.

The decision is taken once before dispatch. Arrivals, functions, scheduled ticks
and block events before that point are covered. A cart first created after the
mode decision is detected at the next unit's decision, as with the existing TNT
admission policy; a birth in a later member-queue or entity body can still
encounter the inherited opt-in ColorGate path in its first unit. These
differential cases place the carts before the decision. No such birth was
established as the cause of the reported farm failure; changing admission after
the queue drain would require a separate phase-generation design change.

Two matched 60-second captures use the same fresh flat-world template and
Fabric API/Carpet/Lithium/spark set, four powered loops with 160 mixed carts,
two region workers, `-XX:ActiveProcessorCount=4` and a 3 GB server heap.
Both runs use the parallel option; the candidate selects its serial cart pass.
After warm-up the coherence pin is removed, so the island runs autonomously.
Each capture records 1,200 global ticks; both finish at 20 island TPS, without
crashes and with a clean exit. The reports and profiles are preserved in
`build/minecart-parity/perf-baseline-1/` and `perf-candidate-1/`.
Saved entity NBT independently confirms 160 carts on rail height and 160 moving
carts in both runs (`saved-cart-population.json`); cart loss or derailment did
not reduce either load.

`tools/spark/frames.py` produces
`build/minecart-parity/perf-frames-comparison.txt`:

| Measurement | 3.1.1 | 3.1.2 |
| --- | ---: | ---: |
| Sampled worker running time, ms/tick | 1.48 | 2.99 |
| Worker `IslandTickUnit.runCoordinated`, ms/tick | 1.26 | 2.63 |
| Worker `RegionizedServer.tickOwned`, ms/tick | 1.13 | 0.00 |
| Worker `tickIslandEntitiesInVanillaOrder`, ms/tick | 0.00 | 2.34 |
| Main `MinecraftServer.tickServer`, ms/tick | 1.34 | 1.38 |
| Island reported mean MSPT | 1.76 | 3.07 |
| Island reported maximum MSPT | 3.17 | 3.64 |

This measures the cost of restoring order in this cart-load case. Inclusive
frames overlap and must not be added together. The baseline's cart trajectories
are incorrect, so equal scenario inputs do not imply identical subsequent
physics. Spark uses its built-in Java sampler because async-profiler is not
supported on this Windows host. The small one-player/two-worker fixture does
not quantify dense production islands' extra Main waits. No production
performance improvement or 40-player performance guarantee is claimed.

The production-like 40-client `tools/stand/stand.sh verify` campaign has not run:
this Windows host lacks WSL/Linux and the production stand template. The isolated
differential is a real runtime regression/fix check, but it does not supply the
required full-modpack stand `ALL PASS`, a fresh composed-mixin export check or a
replay of the player's exact world eater/sugar-cane farm. The production log also
does not prove that the opt-in parallel entity mode was enabled there.
