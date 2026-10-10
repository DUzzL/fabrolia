# Documentation index

## Development and architecture

- [Requirements](USER-REQUIREMENTS.md): Vanilla 26.3 behaviour and accepted boundaries.
- [Code map](CODEMAP.md): modules, execution phases and the classes responsible for them.
- [Island design](ASYNC-ISLANDS.md): ownership, clocks, barriers and publication rules.
- [Test stand](STAND.md): functional, mechanics and performance checks.
- [Release workflow](RELEASES.md): versions, artifact names and release validation.
- [Profiler tools](../tools/spark/README.md): decode and compare Spark captures.

## Reviews and regression evidence

These are dated investigations, not additional runtime specifications. Current
behaviour is described by the requirements, architecture and [changelog](../CHANGELOG.md).

| Topic | Review or result |
| --- | --- |
| General bugs and crash fixes | [Code review](CODE-REVIEW-2026-10-08.md), [critical fixes](CRITICAL-FIXES-2026-10-08.md) |
| Raids | [Farm review](RAID-FARM-REVIEW-2026-10-09.md), [Vanilla ticket parity](RAID-TICKET-FIX-2026-10-09.md) |
| TNT and world eaters | [Code review](TNT-CODE-REVIEW-2026-10-10.md), [fixes and verification](TNT-FIX-2026-10-10.md) |
| MSPT | [Production profile analysis](MSPT-ANALYSE-2026-10-10.md) |
| Alpha 3.0.0 | [Build and cleanup verification](ALPHA-3.0.0-RELEASE.md) |
| C2ME strand recovery | [Known C2ME issue](C2ME-FLAG_BROKEN-STRAND.md) |

Machine-readable evidence lives under `reviews/YYYY-MM-DD/`. Runtime stand runs
and server inputs live in the ignored `stand/` directory, outside the source tree.
