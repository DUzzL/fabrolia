# Alpha 3.0.0 build and cleanup

The Fabric artifact is `fabric/build/libs/fabrolia-alpha_3.0.0.jar`.
Metadata uses `3.0.0-alpha`; Minecraft stays at 26.3 and the nested API at 1.0.4.
SHA-256: `44f71921a1ccffb4b5ec60e705e881ba09f7c3a50e7b8dfdb4d03cd7730b276f`.

The cleanup centralizes Java 25/compiler settings and loader archive naming in
build conventions, adds explicit version bump and artifact validation tools,
binds upload tasks to the current jar, removes template links from Fabric metadata,
and indexes architecture/review documentation. Existing integration class names
and APIs remain stable. The stand preserves the exact jar filename and excludes
Finder metadata from input fingerprints.

No existing runtime Java implementation was changed by this cleanup. All 898
Fabrolia runtime classes and all nested dependency classes are byte-identical to
Claude's preceding artifact (`a500514f97a672547cbb9eb1dbe5364da5360a6b53104cb32047ad5c61cc86f0`).
The preceding raid/TNT/portal fixes are therefore retained.

## Build validation

- Java 25: `:common:test :fabric:build :neoforge:build` succeeded.
- 1,442 tests: 1,434 passed, 8 optional external tests skipped, zero failures/errors.
- Fabric architecture checks and exact-jar metadata, mixin, access-widener,
  duplicate-entry and Java class-version validation passed.
- Version bump fixtures verified minor reset, patch increment, dry-run and
  preservation of other properties and CRLF line endings.

Evidence: [unit tests](reviews/2026-10-10/alpha-3.0.0/unit-test-summary.json),
[runtime bytecode comparison](reviews/2026-10-10/alpha-3.0.0/runtime-bytecode-comparison.json).

A supplementary fresh 40-client campaign was stopped during fixture chunk-load
preparation; it did not complete and is not claimed as a new runtime PASS. The
preceding artifact's completed runtime checks remain applicable to the identical
runtime bytecode. Earlier setup attempts stopped on macOS-specific harness issues
(missing Linux CPU pinning and Finder metadata); neither was a mod crash.

## Local disk usage

The ignored `stand/runs/` directory held 89 separate test servers, taking roughly
32 GB before cleanup. Each copied Minecraft, mods, libraries, datapacks and its
generated world. This is test infrastructure; the release jar is about 2.7 MB.

Fourteen old generated worlds were removed while retaining their reports, logs,
profiles and mod artifacts. Subsequent cleanup deduplicates immutable jars and
datapack files by SHA-256 using hard links. Remaining mutable worlds, configs,
logs and active runs are excluded. Old runs retain their file paths and contents;
shared immutable files should be replaced, rather than edited in place.
The complete working directory measured approximately 11 GB after cleanup.
See the [additional jar deduplication audit](reviews/2026-10-10/alpha-3.0.0/disk-deduplication.json).

The new artifact has not been uploaded, installed, committed or tagged.
