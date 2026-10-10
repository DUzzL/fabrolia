# Unused-code cleanup: 3.0.1-alpha

Removed the ineffective `chunkheal` command and `chunkBridgeHealBroken` flag.
The C2ME broken-strand forensic/fail-stop probe remains unchanged and active.
Removed `RegionWorkerPool.leaveRootAdmission` and `reserveExternalTask`, which
had no production callers. Shared test-only fixture access now uses the actual
locked reservation and admission cleanup methods. No test helper is packaged.

All 1,434 unit tests passed; eight optional external tests were skipped. Fabric
and NeoForge builds and the exact Fabric artifact check passed. All 136 remaining
worker-pool methods have identical disassembled instructions after normalizing
constant-pool references. A further 55 methods across nine nested worker-pool
classes also retain identical instructions. Third-party mod classes in the stand template contain
no references to the removed members. Public integration classes were preserved.

Fabric SHA-256:
`f76072c2fee6da7b03f49822489d7ea24e3aba07550e60890051b6aa092a8465`.

## Live verification

The candidate's first 40-client campaign failed during player distribution,
before gameplay checks. It hit `A5 topology-gap handoff lost its exact
player/session binding` in unchanged `AsyncIslandPlayerTicks` / entity tracking
code. This failure is preserved in `candidate-verify-report.json` and
`candidate-crash.txt`. It is not dismissed as a successful run or a proven
pre-existing bug. A matched control uses the preceding 3.0.0-alpha artifact.

The preparation-only chunk gate now checks actual test-player locations:
clients request view distance 2, so the old distant town-edge checks always
waited 900 seconds for unrequested chunks. All subsequent gameplay checks are
unchanged. The candidate crash happened before reaching that gate.

The baseline control (`alpha-3-0-0-control-cleanup-v1`) completed all eleven
checks with PASS and a clean native stop. The second unchanged candidate campaign (`alpha-3-0-1-cleanup-v2`) also
completed all eleven checks with PASS and a clean native stop. The first failure
was not reproduced in that run, but remains unresolved. This is not an
unqualified production clearance.
