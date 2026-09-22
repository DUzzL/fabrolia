# Correctness-аудит `/fabrolia regions playerticks`

Дата актуализации архитектуры и runtime evidence: 2026-08-25
Цель: Minecraft 26.2, Fabric, production modpack (~160 модов)
Статус режима: persistent operator preference, default `OFF`

## Итог

Текущая exact-actor архитектура переносит только ранний
`ServerPlayer.tick()` и сохраняет его исходный ordinal внутри canonical
`EntityTickList`. Она не строит второй список игроков и не переносит player body
в конец entity phase.

Механика одного eligible player turn:

1. Physical main thread вызывает настоящий `EntityTickList.forEach` в его
   исходном месте внутри `ServerLevel.tick`.
2. Настоящая synthetic lambda `ServerLevel.lambda$tick$0` выполняет все vanilla
   gates до `Level.guardEntityTick`.
3. В точке вызова `guardEntityTick` exact-class standalone `ServerPlayer`
   передаётся одному serial `PlayerTickActor`.
4. Main синхронно ждёт завершения actor turn; следующий ordinal списка не
   начинается раньше возврата или исключения текущего.
5. Actor вызывает захваченную исходную operation, поэтому
   `guardEntityTick(level::tickNonPassenger, player)` и весь transformed
   `ServerPlayer.tick()` остаются частью canonical consumer.
6. На время actor turn region pool находится за строгим pause fence. Fence
   уровня удерживается до полного tail `ServerLevel.tick`; отдельный fence
   connection-фазы удерживается до tail `MinecraftServer.tickChildren`.

`ServerGamePacketListenerImpl.tickPlayer()`, `ServerPlayer.doTick()` и
serverbound packet handlers остаются на physical main thread. Это соответствует
двум разным vanilla-фазам, а не «частичному player tick».

Команды `/fabrolia regions playerticks on|off` атомарно меняют runtime gate и
сохраняют operator preference `enablePlayerTickActor`. Значение по умолчанию —
`false`. После старта region scheduler сохранённый запрос применяется через те
же fail-closed safety checks; config reload применяет его повторно.

`regions off` — полезный вторичный Fabrolia baseline, но не vanilla oracle:
общие Fabrolia mixin'ы всё равно присутствуют. Строгий oracle требует отдельного
build/run без Fabrolia либо с доказанным bypass всех общих замен.

Current verdict: **`RUNTIME-VALIDATED RELEASE CANDIDATE`** для точного
проверенного modpack. Финальный JAR прошёл semantic archive verification и три
чистых full-pack JVM lifecycle с 152 components; transformed actor seams были
отдельно проверены full-pack verifier. Реальный
TCP `ServerPlayer` actor smoke, negative eligibility transition, teleport и
save/restart cycle. Для этого состава сервер подходит для controlled deploy и
persistent `ON` без постоянного наблюдения.

Это не универсальная гарантия совместимости с произвольным новым модом.
Необёрнутый synchronous handoff на foreign executor остаётся за пределами
контракта; strict vanilla oracle, performance/load soak не выполнялись. Все
runtime проверки шли в изолированном fresh clone; production world не
копировался и не затрагивался.

## 1. Exact vanilla call graph

### 1.1 Авторитетный mapped JAR

Основной исследованный JAR:

`/home/furrymileon/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`

SHA-256:

`060aab9f24a2cd1c38f00f29b58913d70131ae11d479dd43fae1975cd796adb0`

Проверка выполнялась по bytecode (`javap -p -c -l`) и decompiler output.
Ключевой инвариант не зависит от декомпиляции: у `ServerLevel.tick` есть один
canonical invoke `EntityTickList.forEach(Consumer)`, а у synthetic
`ServerLevel.lambda$tick$0` — один авторитетный invoke
`Level.guardEntityTick(Consumer,Entity)`.

### 1.2 Один vanilla server tick

```text
MinecraftServer.processPacketsAndTick(Z)V
  PacketProcessor.processQueuedPackets()
  MinecraftServer.tickServer(BooleanSupplier)V
    MinecraftServer.tickChildren(BooleanSupplier)V
      suspendFlushing() for player connections
      global server work
      for every ServerLevel:
        ServerLevel.tick(BooleanSupplier)V
          border / weather / time
          scheduled block and fluid ticks
          raids
          ServerChunkCache.tick()
          block events
          dragon
          EntityTickList.forEach, linked insertion order
            ServerLevel.lambda$tick$0 gates
            Level.guardEntityTick(level::tickNonPassenger, entity)
              ServerLevel.tickNonPassenger(root)
                setOldPosAndRot
                tickCount++
                Entity.tick() virtual dispatch
                  ServerPlayer.tick()          early entity phase
                recursive tickPassenger
          tickBlockEntities()
          entity management and level tail
      MinecraftServer.tickConnection()
        ServerConnectionListener.tick(), connection-list order
          Connection.tick()
            ServerGamePacketListenerImpl.tick()
              tickPlayer()
                resetPosition + xo/yo/zo
                ServerPlayer.doTick()          later connection phase
                absSnapTo + anti-fly bookkeeping
      PlayerList.tick()
      chunk sending / misc
      resumeFlushing()
```

`EntityTickList` имеет linked insertion order. Следовательно,
`ServerPlayer.tick()` имеет точный ordinal среди mobs, items и projectiles.
`ServerPlayer.doTick()` имеет другой total order: после level/entity и
block-entity phases всех dimensions, в порядке connections. Эти две фазы нельзя
склеивать или менять местами.

### 1.3 `tickPlayer()Z`

Exact descriptor: `()Z`; vanilla содержит один target
`ServerPlayer.doTick()V`.

Порядок метода:

1. listener `resetPosition()`;
2. сохранение `player.xo/yo/zo`;
3. `player.doTick()`;
4. `absSnapTo(firstGoodX/Y/Z, rotations)`;
5. movement counters;
6. player anti-fly;
7. vehicle snapshot и vehicle anti-fly;
8. return/disconnect result.

Portal teleport из `doTick()` может повторно вызвать listener
`resetPosition()`. Поэтому `doTick()` должен оставаться внутри этого bracket на
main thread.

### 1.4 `ServerPlayer.tick()` и `doTick()`

`ServerPlayer.tick()` выполняет server-player entity work: game mode и block
breaking, menus, camera/chunk-map movement, criteria, attributes и advancement
flush.

`ServerPlayer.doTick()` позже входит в `Player.tick()` /
`LivingEntity.tick()` / `Entity.baseTick()`: portals, physics, collisions,
block effects, fluids/fire/freeze, effects/equipment, item use, pickup, food,
stats, scoreboard, health/XP и player packets.

Исключение ранней фазы проходит через `Level.guardEntityTick` как
`ReportedException("Ticking entity")`. Исключение поздней фазы формируется в
connection boundary как `ReportedException("Ticking player")`. Exact actor
сохраняет эту границу.

## 2. Текущая exact-actor интеграция

### 2.1 Canonical `EntityTickList` seam

Authoritative seam — sole call:

```text
ServerLevel.tick(BooleanSupplier)
  -> EntityTickList.forEach(Consumer)
```

`ServerLevelMixin` оборачивает invoke через `@WrapOperation`, но в exact mode
обязательно вызывает исходную operation с настоящим transformed consumer.
Поэтому:

- canonical membership и linked insertion order остаются у vanilla
  `EntityTickList`;
- `isRemoved`, `isAlwaysTicking`, freeze и passenger/root gates остаются внутри
  настоящей synthetic lambda;
- ordinary entities проходят в исходном порядке на physical main;
- ineligible player проходит тем же исходным consumer на main;
- исключение ordinal N останавливает обход до N+1 так же, как в canonical loop.

После полного level tail, пока region pool ещё paused,
`RegionizedServer.reconcileCanonicalEntityIndex` перестраивает regional mirror
из canonical `EntityTickList`. Это устраняет накопление index drift после
spawn/remove/move во время actor body.

### 2.2 Synthetic lambda guard

Второй exact seam находится в:

```text
ServerLevel.lambda$tick$0(
    TickRateManager,
    ProfilerFiller,
    Entity
)V
```

Оборачивается именно invoke:

```text
Level.guardEntityTick(Consumer, Entity)
```

До этой точки synthetic lambda уже выполнила vanilla gates. Для eligible
exact-class standalone `ServerPlayer` mixin не выполняет body на main, а
синхронно вызывает `PlayerTickActor.executeExactOrdinal(...)`. Actor выполняет
захваченную исходную guard operation, а main ждёт результат.

Для любого другого entity, failed eligibility либо failed claim вызывается
исходная operation на main. Fallback разрешён только до входа в actor body.
После body entry повторный вызов запрещён: ошибка возвращается на main как тот
же primary throwable.

Этот seam важен и как ASM pin: redirect по приблизительному
`tickNonPassenger` или отдельный список игроков не эквивалентны canonical
lambda.

### 2.3 Serial actor и logical ownership

`PlayerTickActor` — один serial executor. Одновременно существует не более
одного exact player turn. Это не region-worker ownership:

- actor не получает `Region` claim;
- main остаётся владельцем orchestration и ждёт actor;
- region workers к этому моменту остановлены pause fence;
- actor получает узкий logical-main context только на длительность exact turn;
- context очищается в `finally`, включая exceptional exit.

`executeExactOrdinal` должен удостоверить current phase/session/claim, войти в
actor context, вызвать captured guard operation ровно один раз, записать
результат и завершить claim. Main не начинает следующий entity ordinal до
terminal actor result.

### 2.4 Pool fence и phase lifecycle

Есть два scoped fence:

1. Перед canonical `EntityTickList` pass уровень доводит region pool до fixed
   point и удерживает pause до whole-method tail `ServerLevel.tick`. Внутри fence
   выполняются entity ordinals, actor turns, block entities, deferred level work,
   reconciliation и exceptional cleanup.
2. Перед `MinecraftServer.tickConnection()` main снова получает pause и
   удерживает его через connection tick, `PlayerList.tick`, chunk sending и
   оставшийся tail `tickChildren`.

Pause fixed point означает одновременно:

```text
activeSteps == 0
ordinary region queues empty
externalObligations == 0
```

Во время `REQUESTED` worker может завершить уже начатый causal step и
зарегистрированные obligations. Новые unrelated roots закрываются closing
protocol и откладываются. Во время `PAUSED` scheduler entrypoints, вызванные
physical paused owner либо exact actor, исполняют допустимую работу inline,
иначе root откладывается до resume.

`GlobalPhase` лишь зеркалит реальное состояние pool fence. Release cleanup
после replay exception всё равно проверяет
`!RegionScheduler.isExactPlayerTurnPausedBy(mainThread)` и очищает phase flags,
если pool фактически открыт. Поэтому stale mirrored pause не должен пережить
exceptional resume.

### 2.5 Server/chunk logical-thread compatibility

Actor context узко признаётся там, где vanilla или installed optimization mod
делает physical-thread gate, но операция безопасна при exclusive paused owner:

- `MinecraftServer.getRunningThread()`;
- chunk main executor `getRunningThread()`;
- direct checks в `ServerChunkCache.getChunk`,
  `getChunkFuture` и `getChunkNow`;
- `PacketProcessor.isSameThread`;
- Lithium helper
  `Level#lithium$getLoadedExistingBlockEntity(BlockPos):BlockEntity`;
- два реальных consumer'а Lithium `LevelAccessor#getThread()` в
  `ChunkStatusTracker`;
- C2ME entity-id collision wrapper, chunk shortcut, mid-tick task и два
  asynchronous-save catcher handler'а.

Правило для всех hook'ов одинаково: подмена применяется только внутри
authenticated exact actor context; для всех остальных threads возвращается
original value.

Два типа Lithium hook'ов различны и не заменяют друг друга:

1. actor-aware expression hooks стоят непосредственно в двух consumers
   `LevelAccessor#getThread()` внутри Lithium `ChunkStatusTracker`;
2. отдельный hook внутри
   `lithium$getLoadedExistingBlockEntity(BlockPos)` нужен потому, что этот метод
   читает `Level.thread` напрямую. Он используется hopper/comparator
   cache-invalidation paths.

Попытка инжектироваться в accessor-generated `Level#getThread()` была удалена:
обычный injector не может надёжно выбрать метод, который ещё не существует на
его prepare phase. Current hooks pin'ят реальные upstream consumers и
фактически подтверждены в full-pack transformed export.

C2ME compatibility остаётся actor-only: physical owner и любой другой worker
получают исходное поведение. Upstream `ServerLevel` entity-id wrapper заменён
точной Fabrolia-версией; cancellable HEAD chunk shortcut, merged mid-tick check
и оба save catcher handler'а пропатчены в их конечных transformed seams.

## 3. Feature gate и eligibility

Существует один runtime gate и одна сохранённая operator preference
`enablePlayerTickActor`, default `false`. Успешный command transition сохраняет
новое значение; при ошибке persistence runtime и preference откатываются как
одна транзакция.

| Regions live | Async islands | Запрос | Результат |
|---|---:|---:|---|
| off | любое | off | runtime `OFF`, preference сохраняется `false` |
| off | любое | on | `REFUSED`, прежняя preference не меняется |
| on | on/configured | on | `REFUSED`: async islands не входят в этот barrier contract |
| on | off | off | runtime `OFF`, preference `false` сохраняется для restart |
| on | off | on | runtime `ON`, preference `true` сохраняется для restart |

Startup restore выполняется только после публикации active scheduler
generation. Если сохранённый `ON` сейчас небезопасен, runtime остаётся
fail-closed `OFF`, причина логируется, а saved request остаётся `ON` для
следующего безопасного reload/restart. Включение async islands проверяет и
effective gate, и saved preference, поэтому не может создать скрытую
несовместимую boot-конфигурацию. `/fabrolia reload` повторно применяет saved
preference тем же safety path.

Snapshot gate снимается в начале `tickChildren`; mid-tick toggle не меняет уже
начатую phase.

Минимальный eligibility envelope:

- exact runtime class `ServerPlayer`;
- текущий `ServerLevel`, listener и connection session совпадают;
- entity не removed, не changing dimension и не в запрещённом portal/respawn
  transition;
- entity не passenger, не vehicle и без passenger subtree;
- camera/menu/item-use/destroy/global sparse state соответствует разрешённому
  профилю;
- async regions выключены;
- known-mod probes прошли fail-closed.

Это controlled-test envelope, а не доказательство безопасности произвольного
modded player state.

## 4. Session, lease и known-mod envelope

### 4.1 Stable session identity

До actor submit и непосредственно перед body сверяются стабильные значения:
server tick/phase identity, `ServerPlayer` object identity, UUID/entity id,
level identity, listener/connection identity и lifecycle epoch.

Respawn создаёт новый `ServerPlayer`; teleport/dimension switch может заменить
level/session bindings. Старый queued turn не имеет права примениться к новому
session.

### 4.2 `EntityTickLease`

Одна per-entity lease покрывает reservation и actor body. Cooperative lifecycle
writers используют ту же lease до первого side effect:

- remove/die;
- mount/passenger changes;
- typed player teleport;
- respawn/restore;
- PlayerList place/remove transitions.

Lifecycle epoch обнаруживает authenticated transition до body. Отдельного
запрета на все последовательные mutation после начала body нет: такой запрет
сломал бы vanilla, потому что `ServerPlayer.tick()` законно вызывает nested
entity/lifecycle APIs. После завершения actor turn candidate/claim state
немедленно снимается.

Lease защищает только cooperative writers. Прямой field write или mod API,
который обходит эти seams, остаётся compatibility risk.

### 4.3 Known mods

Known-mod envelope обязан проверяться по фактическим production версиям и
fail-closed при reflection/descriptor mismatch.

Для FSit production baseline использует поле `fsit$pendingRider` в
`fsit-3.0.1-beta.2+mc26.2.jar`; кроме него проверяются attached/sit/lay
состояния. Для Carpet-подобных fake/shadow player paths exact-class и
known-state probes не должны пропускать automation player в actor.

Envelope не является общей сертификацией мода. Он лишь исключает известные
опасные состояния из controlled actor path.

## 5. Packet и callback semantics

### 5.1 Serverbound packets

Netty ingress и queued packet cutoff не переносятся. Обычные serverbound
handlers выполняются на physical main thread. Exact actor не маршрутизирует
handlers на себя.

Если transformed callback, уже выполняющийся внутри `ServerPlayer.tick()`,
синхронно вызывает `PacketProcessor` helper, mixin трактует authenticated actor
как same logical thread и оставляет вызов inline на исходном entity ordinal.
Этот hook не разрешает произвольному Netty thread стать logical main.

### 5.2 Clientbound sends

`ServerCommonPacketListenerImpl.send` сохраняет vanilla suspended-flush
semantics. Send, инициированный actor body, передаётся той же connection/event
loop. Main ждёт каждый actor ordinal, поэтому следующий canonical entity
ordinal не обгоняет завершение предыдущего actor body; per-connection event-loop
order сохраняет enqueue order.

Current design не добавляет отдельный packet buffer: clientbound order следует
существующей connection/event-loop enqueue sequence.

### 5.3 Global callbacks

Advancements, scoreboard, criteria, command functions и другие synchronous
callbacks из `ServerPlayer.tick()` выполняются на actor thread, но во время
эксклюзивного pool fence и при main, ожидающем именно этот ordinal. Поэтому
прежний race «player worker одновременно с другими entity workers» к текущей
архитектуре не относится.

Остаётся другой класс риска: callback может проверять physical thread identity
либо синхронно ждать foreign executor. Первый класс требует exact compatibility
hook'ов; второй описан causal boundary ниже.

## 6. Happens-before и lock graph

Нормальный exact turn:

```text
region workers finish admitted steps
        |
        v
M requests pool pause K
        |
        v
K fixed point: activeSteps=0, queues empty, obligations=0
        |
        v
M enters canonical EntityTickList.forEach
        |
        +--> ordinary/ineligible entity: captured consumer on M
        |
        +--> eligible player ordinal:
                M reserves E/session
                M submits A and waits
                A enters authenticated logical context
                A invokes captured guardEntityTick operation exactly once
                A publishes result, releases E/context
                M observes terminal result
        |
        v
M continues remaining ordinals, block entities and ServerLevel tail
        |
        v
M rebuilds regional entity index from canonical list
        |
        v
M releases K and replays deferred roots
```

Перед connection phase та же main thread получает второй `K`, выполняет
connection/PlayerList/chunk-send tail и освобождает fence в `tickChildren`
epilogue.

Ключевые ordering edges:

```text
pause fixed point
  happens-before first canonical entity ordinal

actor body completion
  happens-before next canonical ordinal

last entity ordinal
  happens-before tickBlockEntities

ServerLevel tail + canonical index reconciliation
  happens-before level fence release

all level work
  happens-before connection fence and ServerPlayer.doTick
```

### 6.1 Explicit `causalHandoff`

Worker-to-foreign-executor handoff через pause cut допустим только при явной
обёртке:

```text
FabroliaIslandApi.causalHandoff
  -> RegionScheduler.wrapCausalRegionHandoff
  -> RegionWorkerPool.wrapCausalHandoff
```

`StepToken` действителен лишь для текущего live region worker step. Явно
обёрнутый foreign continuation наследует causal obligation; pause ждёт его
завершения. Unrelated foreign submissions не получают это право автоматически.

### 6.2 Непокрытая causal boundary

Цепочка ниже не представима worker `StepToken`:

```text
main M waits exact actor A
A submits work to foreign executor F and waits F
F submits a new region root R and waits R
pool is PAUSED, so R is deferred until resume
```

Получается цикл `M -> A -> F -> R -> resume -> M`. Простая подмена thread
identity его не исправляет. Нужна отдельная actor mailbox/rendezvous semantics,
которая разрешит authenticated actor-causal continuation пройти fence, либо
такие callbacks должны оставаться вне eligibility envelope.

Это конкретный production blocker для модов, использующих такой synchronous
pattern. Нельзя молча считать любой foreign task causal.

## 7. Compatibility pins

### 7.1 Vanilla pins

Current static test/verifier pass является fail-loud evidence для:

- один `EntityTickList.forEach(Consumer)` в
  `ServerLevel.tick(BooleanSupplier)`;
- один `Level.guardEntityTick(Consumer,Entity)` в exact synthetic
  `ServerLevel.lambda$tick$0(TickRateManager,ProfilerFiller,Entity)`;
- один `ServerPlayer.doTick()V` в
  `ServerGamePacketListenerImpl.tickPlayer()Z`;
- canonical lambda owner/descriptor и captured arguments;
- whole-method tail cleanup для normal и exceptional exits;
- отсутствие второго direct caller `ServerPlayer.doTick()` из Fabrolia.

### 7.2 Logical-thread pins

Current static evidence содержит exact target/ordinal assertions для:

- `PacketProcessor.isSameThread`;
- `MinecraftServer.getRunningThread`;
- chunk main executor running-thread accessor;
- `ServerChunkCache.getChunk/getChunkFuture/getChunkNow`;
- Lithium
  `Level#lithium$getLoadedExistingBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;`;
- оба вызова Lithium `LevelAccessor#getThread()` в
  `ChunkStatusTracker.onChunkAccessible/onChunkInaccessible`;
- C2ME `getNextEntityId` collision check, `shortcutGetChunk`,
  `executeTasksMidTick`, `saveAllChunks` и `saveEverything`.

Production Lithium reference:

`/home/furrymileon/.local/share/atlauncher/servers/Minecraft262withFabric/mods/lithium-fabric-0.25.3+mc26.2.jar`

SHA-256:

`fdde92e238e8075f89ad7f701f2a3d5854af88ba9a67657184a4407b104ac563`

Relevant exact consumers of the merged accessor:

```text
net/caffeinemc/mods/lithium/common/world/chunk/ChunkStatusTracker
  .onChunkAccessible(ServerLevel,LevelChunk)V

net/caffeinemc/mods/lithium/common/world/chunk/ChunkStatusTracker
  .onChunkInaccessible(ServerLevel,ChunkPos)V
```

Оба сравнивают accessor result с `Thread.currentThread()` и бросают при
несовпадении. Block-entity helper имеет отдельный direct `Level.thread`
comparison и поэтому требует отдельного injection.

Current production pack также проверен по всем 17 Fabrolia Lithium mixin'ам:
13 были загружены на smoke boot, 4 lazy mixin'а имеют exact target pins. Для
C2ME подтверждены 6 загруженных compatibility mixin'ов и новый двух-target
save hook. VMP загрузился в том же transformed graph; его прямые relevant
thread checks проходят через уже покрытые logical-owner seams.

### 7.3 Pause/actor pins

Тесты и verifier должны проверять:

- actor seriality и exact once;
- main synchronous wait на каждом eligible ordinal;
- actor context cleanup после exception;
- no fallback after body entry;
- fixed-point pause до первого actor turn;
- level fence остаётся закрыт через `tickBlockEntities` и level tail;
- connection fence остаётся закрыт до `tickChildren` tail;
- replay failure не оставляет stale mirrored phase;
- deferred-root head/tail closing protocol;
- canonical regional-index reconciliation до resume;
- `causalHandoff` принимает только live `StepToken`.

## 8. Удалённая архитектура v1 — только historical context

Первая реализация переносила `ServerPlayer.doTick()` из connection bracket в
раннюю player phase, маршрутизировала часть packet handlers и проглатывала
ошибки entity task. Она была семантически неверна и удалена.

Промежуточные реализации также больше не описывают current source. Их очереди,
barriers и ordering assumptions нельзя переносить на exact actor path. Current
модель полностью задаётся canonical seams и happens-before из разделов 2–6.

## 9. Остаточные границы и риски

### 9.1 Что больше не является blocker

При условии, что current source соответствует описанным exact seams:

- player entity ordinal сохраняется;
- ordinary entity и eligible player используют один canonical consumer;
- следующий ordinal не стартует до actor completion;
- `tickBlockEntities` не обгоняет actor body;
- pool workers не исполняются одновременно с actor;
- clientbound enqueue order не требует отдельного reorder layer;
- `ServerPlayer.doTick()` остаётся в vanilla connection bracket;
- regional entity mirror восстанавливается из canonical list до resume.

### 9.2 Current audit disposition

В точном проверенном pack не осталось воспроизводимого deterministic `P0/P1`:
full-pack transform, три final persistence boot, actor eligibility/rejection, gameplay,
save/restart и clean stop прошли. Оставшиеся границы не отменяют controlled
deployment, но запрещают трактовать результат как универсальную
мод-совместимость:

1. **Arbitrary foreign executor не поддерживается автоматически.** Необёрнутая
   actor-to-foreign synchronous цепочка к paused region root всё ещё может
   образовать цикл; допустим только явный causal contract.
2. **Unknown direct lifecycle writers.** Lease видит cooperative seams, но не
   прямые writes/Unsafe/accessor mutations нового стороннего мода.
3. **Known-mod envelope version-specific.** Обновление Lithium, C2ME, VMP,
   FSit, Carpet либо другого player-transforming мода требует повторного
   archive + transformed audit и smoke boot.
4. **Strict vanilla oracle не выполнен.** `regions off` остаётся Fabrolia
   baseline, а не отдельным чистым vanilla run.
5. **Performance/load soak не выполнен.** Current evidence доказывает startup,
   transform и bounded functional lifecycle, но не throughput и long-duration
   нагрузку.
6. **VMP/Fab nearest-player overwrite — `P2/perf`.** В текущем порядке VMP
   priority 1050 побеждает Fabrolia priority 900; correctness не ломается, но
   Fabrolia predicate optimization в этом методе не является финальным body.
7. **Saved preference не отменяет safety checks.** `ON` восстанавливается после
   scheduler startup только при выполненных prerequisites; async-islands и
   player actor взаимно исключают друг друга по effective и saved state.
8. **Shared region exception policy.** Если pre-entity regional work
   логирует/продолжает там, где strict vanilla abort'ит tick, это общий
   Fabrolia baseline risk, а не дефект exact player ordinal.

## 10. Historical evidence — не доказательство current source

Все результаты этого раздела получены до последней exact-actor /
pool-fence / logical-thread серии изменений. Они оставлены только как
воспроизводимая история. Их нельзя цитировать как green result текущего дерева.

### 10.1 Historical focused tests

Последний записанный historical focused run:

```text
BUILD SUCCESSFUL
65/65 passed
```

Он включал lease/session, phase, ordering, packet policy, lifecycle и regression
suites прежнего состояния.

### 10.2 Historical full test/build

Последний записанный historical full result:

```text
./gradlew test
BUILD SUCCESSFUL
439 tests completed, 0 failed
```

Historical production-legacy subset:

```text
19/19 passed
```

Эти числа не обновляются в документе без нового полного запуска после source
freeze.

### 10.3 Historical artifact snapshot

Старый зафиксированный artifact:

```text
build/libs/fabrolia-1.0.0.jar
size:    420381 bytes
SHA-256: 7d135e41a8f153307a9dff11f62a5b023f93721b9d9da43bbbcc90eac00ccb89
MD5:     52be1209c3bd7d49f761f60444fc502b
```

Этот JAR предшествует current exact-actor changes и не является deployable
evidence для них. Наличие либо отсутствие новых actor, pause, PacketProcessor,
Lithium и causal-handoff классов в нём ничего не доказывает о текущем source.

### 10.4 Historical transformed export

Существовавший на тот момент production `.mixin.out` относился к более старому
deployed artifact и не показывал current Fabrolia injections. Этот historical
пробел закрыт fresh full-pack export и explicit verifier из раздела 11.

## 11. Current final evidence

Final build/persistence результаты ниже относятся к одному artifact и
изолированному full-pack clone. Exact actor gameplay smoke из §11.2 был получен
до persistence-only gate lifecycle change; его transformed seams дополнительно
покрыты текущими tests/verifiers. Historical числа раздела 10 не подмешиваются.

### 11.1 Artifact, tests и transformed verification

```text
path:    /home/furrymileon/OUT/Fabrolia/fabric/build/libs/fabrolia-fabric-0.2.4+alpha-26.2.jar
size:    1841504 bytes
SHA-256: b404d0eaa233eb5073b6b37376dcd21d4924bf034f3260fae07d92fd7870cc42
MD5:     9f8854381387925311ce3ac8c83601ab
```

| Evidence | Final result |
|---|---|
| targeted persistence + wiring | `31/31 PASS` на JDK 25 |
| full `:common:test` | `471` tests, `0` failures, `0` errors, `4` skips на JDK 25 |
| `:fabric:build` + semantic archive verifier | `PASS` на packaged Fabric JAR |
| `:neoforge:compileJava` | `PASS` |
| full-pack persistence bootstrap | все `152` mods прошли три отдельных JVM lifecycle |
| transformed mod audit | Lithium `17` (`13` loaded, `4` lazy exact targets pinned), C2ME `6` loaded + save hook, VMP loaded |

Archive verifier проверил именно emitted JAR; full-pack transformed verifier
проверил конечные классы после композиции modpack. В частности подтверждены
canonical `ServerLevel`/`ServerPlayer` seams, Lithium helper и оба
`ChunkStatusTracker` consumers, C2ME entity-id/shortcut/mid-tick/save hooks,
packet/listener logical-owner hooks и отсутствие отвергнутой v1 relocation.

### 11.2 Реальный network actor smoke

К изолированному серверу подключался настоящий localhost TCP client
`ServerPlayer` для Minecraft 26.2, protocol 776. Первый sustained final run
получил:

```text
actorTicks=2143
mainFallbacks=0
failures=0
eligibility=1/1
```

Carpet action перевёл player в `eligibility=0/1` с точной причиной; actor
counter на этом интервале остановился. После stop состояние вернулось в
`eligibility=1/1`. В том же цикле прошли levitation, same-dimension и
cross-dimension teleport, затем `save-all flush`.

После clean stop второй boot загрузил сохранение до `Done`. В тогдашнем
pre-persistence build gate был повторно включён командой, после чего network run
получил:

```text
actorTicks=546
failures=0
```

Второй цикл включал drown/death lifecycle, повторный save и clean stop. Ни
actor failure, ни fallback, ни startup/transform error не зафиксированы.

### 11.3 Final persistence smoke

Fresh isolated full pack с 152 mods прошёл три отдельных JVM:

1. При отсутствующем новом key первый startup показал `off (saved=off)`.
   Успешная команда `ON` записала `enablePlayerTickActor = true` в TOML.
2. После clean stop новая JVM без повторной команды залогировала
   `restored saved player-tick actor preference ON`; status показал
   `ON (saved=on)`.
3. Успешная команда `OFF` сохранила `false`; после следующего clean stop третья
   JVM показала `off (saved=off)`.

Все три instance были штатно остановлены. Тем самым проверены default migration
для отсутствующего key, command persistence, deferred restore после scheduler
activation и сохранение `OFF` через новый JVM process.

### 11.4 Scope evidence

Smoke выполнялся только в fresh isolated clone с отдельными localhost ports.
Production world не копировался, не запускался и не изменялся. Во время smoke
был замечен отдельный unrelated production server process; к нему не
подключались и его не останавливали. Для текущего pack no deterministic `P0/P1`
найдено; незакрытые универсальные и performance границы перечислены в разделе 9.

## 12. Runtime observability

Для controlled deploy сохраняются bounded counters/diagnostics:

- eligible, rejected и причины rejection;
- exact actor submitted/started/completed/failed;
- current canonical entity ordinal и player identity;
- actor queue depth и максимальное ожидание main;
- pause request/acquire/release/replay counts и latency;
- active steps, ordinary queues и external obligations на cut;
- deferred roots и explicit causal handoffs;
- session/lease rejection;
- fallback-before-entry и forbidden-fallback-after-entry;
- reconciliation additions/removals;
- actor-context leak detector;
- logical-thread hook hits по owner/method;
- exception primary/suppressed chain;
- clean stop/restart state.

Любой pause timeout, actor timeout, leaked context, non-terminal lease либо
descriptor mismatch должен fail closed и выключать новые turns, а не
force-resume started body.

## 13. Безопасная эксплуатация

1. Default preference остаётся `OFF`; успешный `ON` сохраняется и автоматически
   восстанавливается после scheduler startup на следующих JVM runs.
2. `ON` допустим только при активных regions и выключенных async islands;
   взаимное исключение является частью safety contract.
3. Перед заменой Lithium, C2ME, VMP, FSit, Carpet либо Fabrolia повторяются
   archive verifier, full-modpack transformed verifier и isolated smoke boot.
4. После включения проверяются `actorTicks`, `mainFallbacks`, `failures` и
   eligibility reasons; любой failure/context leak/timeout требует `OFF` и
   сохранения лога.
5. Новый mod с direct physical-thread check, lifecycle mutation или
   synchronous foreign executor path не считается поддержанным без отдельного
   pin/probe.
6. Обычный operational cycle завершается `save-all flush` и clean stop; exact
   actor gameplay smoke уже прошёл два таких цикла, а final persistence artifact
   — три clean JVM stop/restart lifecycle.

## 14. Финальный verdict

Exact actor устраняет главные семантические ошибки прежних вариантов:
`ServerPlayer.tick()` теперь выполняется синхронно на своём исходном
`EntityTickList` ordinal, через настоящий synthetic-lambda guard, при
эксклюзивном pool fence. Поздний `ServerPlayer.doTick()` и serverbound packets
остаются на main. PacketProcessor и Lithium требуют узких authenticated
logical-thread hook'ов; worker causal continuation имеет явную API boundary.

Final verdict: **`RUNTIME-VALIDATED RELEASE CANDIDATE FOR THIS EXACT PACK`**.
Current source, final Fabric artifact, semantic archive verifier, full-modpack
transformed graph, три persistence boot, настоящий TCP actor path и
save/restart evidence подтверждены разделом 11. Для зафиксированного состава
это controlled deploy с режимом `ON` без babysitting; после первого успешного
enable preference переживает clean restart.

Verdict не распространяется автоматически на произвольные новые моды или их
версии и не является performance/load-soak заключением. Persisted `ON` не
обходит prerequisites: unsafe startup/reload оставляет runtime fail-closed
`OFF`, сохраняя запрос для следующего безопасного применения. Необёрнутый
actor-to-foreign synchronous handoff остаётся unsupported boundary, а не
скрытым обещанием совместимости.
