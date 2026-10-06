# c2me FLAG_BROKEN latch — root cause of the 2026-07-28 06:56 production wedge

Status: root cause confirmed by adversarial code review of decompiled c2me
sources (15 candidate mechanisms, 14 refuted, this one survived seven
independent refutation attempts). Fabrolia-side containment: bounded
chunk-bridge waits, and `C2meStrandHeal` as a fail-stop forensic probe (it no
longer heals; the `/fabrolia regions chunkheal` switch currently has no reader).
Below is the upstream report, ready to post
to RelativityMC/C2ME-fabric.

Decisive follow-up on the production box (not available from this machine):
grep the server log hours before the crash for `Error upgrading chunk`,
`Failed to load chunk .* fully asynchronously`, `Ticket already exists`, or
bare stderr stack bursts. One hit with a ChunkPos at/below `minecraft:full`
confirms the trigger sub-source; total absence would reopen the dormant-busy
family.

---

## Upstream issue draft

**Title:** Broken chunk holder (FLAG_BROKEN) never fails its pending futures
and never retries — any later `getChunk(FULL)` on that pos hangs the server
until watchdog kill

**Versions:** c2me 0.4.1-beta.1.0+26.2 on MC 26.2/Fabric, dedicated server.
The involved classes are byte-identical in 0.4.2-alpha.0.9+26.2 and
semantically identical in 0.4.2-alpha.0.29 — unfixed on all branches.
Verified against FlowSched upstream HEAD
(RelativityMC/FlowSched@fae2126839607b68d4059e3933a492886fe8b8f5):
the broken-gate is `StatusAdvancingScheduler.java:154-156`; MARK_BROKEN sets
the flag without failing futures at lines 243-246, 302-307 and the
catch-Throwable path 330-334 (HEAD's added `consolidateMarkDirty` after
MARK_BROKEN re-enqueues a tickHolder0 pass that dies on the same gate);
`ItemHolder.clearFlag` has zero call sites repo-wide; `FLAG_HAVE_RETRIED`
(`ItemHolder.java:50`) is declared and never used. The core defect therefore
lives in FlowSched; c2me contributes the always-MARK_BROKEN
`TheChunkSystem.handleTransactionException` override and the vanilla-facing
bypass that converts the latch into a synchronous hang.

**Symptom:** after ~12h uptime with heavy permanent-ticket churn (ender-pearl
chunk loaders, notickvd), a vanilla
`ServerChunkCache.getChunkFutureMainThread(x, z, FULL, false)` executed on the
server thread returned a future that stayed pending for >60s; the watchdog
killed the server. Thread dump at kill time: server thread pumping its
executor, ALL c2me workers parked on the flowsched WorkerThread semaphore with
empty queues, c2me-sched idle, all C2ME Storage threads idle, lighting idle —
a live pending chunk future with provably zero work anywhere in the system.

**Mechanism (from decompiled sources, all steps verified):**

1. Any exception in an upgrade transaction reaches
   `TheChunkSystem.handleTransactionException`, which always returns
   `ExceptionHandlingAction.MARK_BROKEN` (it logs
   `Error upgrading chunk {} to "{}"` and schedules `reportChunkLoadFailure`).
2. `StatusAdvancingScheduler.advanceStatus0#onErrorResumeNext`: MARK_BROKEN →
   `holder.setFlag(FLAG_BROKEN)`, `clearDependencies0`, `Completable.error`.
   `setStatus` is never called, so the `CompletableFuture`s that
   `ItemHolder.createFutures` allocated for `[failedStatus..targetStatus]` on
   ticket add remain live and pending.
3. `StatusAdvancingScheduler.tickHolder0`:
   `if (current.ordinal() < nextStatus.ordinal() && (holder.getFlags() & FLAG_BROKEN) != 0) return;`
   — every subsequent markDirty from ticket churn silently no-ops. Nothing is
   queued, logged, or completed.
4. There is no recovery path: `ItemHolder.clearFlag` has no callers,
   `FLAG_HAVE_RETRIED` is unused, `flushUnloadedStatus` is only reachable when
   `nextStatus == current`, and the `setStatus` downgrade branch (the only
   other place that fails futures) requires the target status to drop —
   impossible while a permanent ticket pins the vanilla level at ≤33. The
   holder can only heal by full destruction, which those tickets prevent
   indefinitely.
5. Vanilla interface consequence:
   `NewChunkHolderVanillaInterface.scheduleChunkGenerationTask(FULL)` maps to
   SERVER_ACCESSIBLE, `triggerDeferredLoad` cannot advance a broken holder,
   and `wrapOptionalChunkFuture` attaches to the never-completing
   `futures[SERVER_ACCESSIBLE]`. Because `chunkAbsent`'s `getTicketLevel()` is
   redirected to the managed level, the vanilla `UNLOADED_CHUNK_FUTURE`
   fast-exit is bypassed. Any synchronous access (`getChunk` → `managedBlock`,
   `getChunkForCollisions` during entity ticking on the server thread) then
   blocks forever. Pure vanilla + c2me reproduces the hang; no third-party
   scheduling is required at that point.
6. Coverage gap that makes a single throw fatal: only the DISK read is retried
   (`RxJavaUtils.retryWithExponentialBackoff`) and only DISK honors
   `recoverFromErrors`. Un-retried one-shot surfaces include
   `VanillaWorldGenerationDelegate.upgradeToThis` (LOADING_PYRAMID
   `ChunkStep.apply`, plus a possible synchronous NPE in the
   `StaticCache2D.create` holder lookup),
   `ServerAccessible.upgradeToThis` (`toFullChunk` runs on a c2me worker when
   `asyncSerialization=true`; `invokeFullChunkEvents` runs third-party
   `ServerChunkEvents.CHUNK_LOAD` listeners on main — one throwing listener
   permanently poisons the chunk),
   `ReadFromDisk.finalizeLoading#doOnSuccess` (`triggerDeferredLoad` ticket
   ops), and the `doOnEvent` success block (`rerequestDependencies` — this
   path only `printStackTrace`s, so it isn't even in the log). Supporting dump
   evidence that the retried path was NOT involved here: zero
   RxComputationThreadPool threads existed after 12h (`Flowable.timer` never
   ran), yet the wedge occurred.

Note the chunk is also silently non-functional (stuck below FULL) from the
moment of the original exception until someone touches it synchronously — the
hang is only the visible end.

**Suggested fixes (any one breaks the deadlock):**

- On MARK_BROKEN, complete `futures[status+1..target]` exceptionally with a
  dedicated marker exception, and map it in `wrapOptionalChunkFuture` /
  `wrapOptionalWorldChunkFuture` to `ChunkHolder.UNLOADED_CHUNK` (vanilla
  degrades this way on load failure instead of hanging).
- Implement the retry `FLAG_HAVE_RETRIED` was evidently reserved for: on the
  next `tickHolder0` pass for a broken holder, clear FLAG_BROKEN once, set
  FLAG_HAVE_RETRIED, and re-attempt; latch only after the retry fails.
- At minimum, log at WARN when `tickHolder0` skips a broken holder whose
  futures have waiters, so operators can identify the chunk instead of getting
  a bare watchdog crash.
