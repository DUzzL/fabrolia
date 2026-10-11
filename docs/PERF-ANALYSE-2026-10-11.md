# Main-Lag bei 40 Spielern am 2026-10-11

Produktion lief mit `3.0.3-alpha`. Ab etwa 37 Spielern fiel die Main-TPS
regelmäßig ab, bei 40–42 Spielern auf 5–10 TPS. Die Inseln selbst liefen dabei
mit 16–20 TPS. Main wartete auf laufende Insel-Ticks, und diese Ticks sind seit
3.0.0 lang, weil jede Insel ihre Entities in einem seriellen Pass tickt.

## Quellen

| Quelle | Zeitraum (CEST) | Inhalt |
| --- | --- | --- |
| <https://spark.lucko.me/LVmfpOt8MC> | bis 21:21 | `/spark health`: 10-s-Reihen der letzten Stunde |
| <https://spark.lucko.me/pksCJr0YZ9> | 20:30–21:30 (gespeichert) | Profiler, nur `Server thread`, 4 ms |
| <https://spark.lucko.me/izYVnZB7Im> | 21:30:40–21:33:41 | Profiler, alle Threads, 4 ms, TPS 10 |
| `latest.log` (Downloads, 21:53) | 21:51 | `fabrolia regions top verbose 100` |

## Verlauf

Bei 32 Spielern hielt der Server 20 TPS (MSPT-Mittel 16–30 ms; die Spitzen von
600–900 ms einmal pro Minute sind das bekannte Autosave). Ab 37 Spielern gab es
Minuten mit 10–14 TPS, ab 40 Spielern 5–7 TPS mit MSPT-Mitteln von 100–144 ms.
Die Prozess-CPU blieb in allen Phasen bei 60–75 % von 19 Threads: Es fehlte
keine Rechenleistung.

## Was Main tut

Im Stundenprofil ist Main 47 % der Zeit aktiv und 49 % geparkt. Langsamste
gegen schnellste Minuten (123,7 gegen 50,0 ms Wandzeit pro Tick):

| Wartestelle | langsam | schnell | Differenz (ms/Tick) |
| --- | ---: | ---: | ---: |
| `IslandCommands.mainClaim` | 29,4 | 7,0 | +22,4 |
| `IslandBuckets.waitForRemaining` | 15,7 | 1,2 | +14,4 |
| `IslandCommands.quiesceAllExcept` | 5,5 | 1,4 | +4,2 |

In den schlimmsten Minuten (21:20, 21:26) kostet `drainMainIngress` 64–72 ms
und `drainOverdueMainTracking` 37–42 ms pro Tick. Beide sind fast nur Warten.

- `mainClaim` (258 ms/s über die Stunde): Main-Ingress 120 ms/s,
  Listener-Ticks 73, `UseItemOn` 21, `EnderDragonFight` 20, Chunk-Versand 14.
  Im Main-Ingress (224 ms/s) entfallen 150 ms/s auf das Warten und 60 ms/s auf
  `UseItemOn` (Truhe öffnen, Block setzen; darin 39 ms/s
  BACAP-`InventoryChangeTrigger`). `ServerboundUseItemOnPacket` ist absichtlich
  `MAIN_FIFO`, Main muss dafür die Insel des Spielers anhalten.
- `waitForRemaining`: Tracking-Veröffentlichungen gelten nach 50 ms als
  überfällig, dann wartet Main auf die betroffenen Inseln.
- Im 3-Minuten-Profil kommt die globale Fence dazu (27,6 ms/Tick):
  `IslandFunctionHead.runMainPass` für `/schedule`-Schleifen (145 ms/s) und
  die `#tick`-Main-Partition (110 ms/s). Die Funktionsarbeit unter der Fence
  kostet nur 0,5 ms/s, der Rest ist Warten.

Jede dieser Stellen wartet, bis der gerade laufende Tick der Zielinsel endet.
Am 2026-10-10 (0.2.38, 35 Spieler, 20 TPS) lagen `mainClaim` bei 3,9–5,6 und
`quiesceAllExcept` bei 1,2 ms pro Tick; die Wartestellen selbst sind seitdem im Kern unverändert.

## Warum die Insel-Ticks lang sind

`fabrolia regions top verbose 100` um 21:51:

| Insel | Spieler | Zellen | MSPT Mittel / Max | Insel-TPS |
| --- | ---: | --- | --- | ---: |
| ow #535 | 5 | 65 + 50 Halo | 52,1 / 101,0 | 16,0 |
| ow #457 | 5 | 41 + 32 | 37,4 / 90,6 | 16,4 |
| ow #582 | 2 | 20 + 22 | 36,4 / 75,9 | 18,4 |
| ne #1 | 2 | 50 + 71 | 31,0 / 75,5 | 18,5 |

Die 17 Region-Worker sind im 3-Minuten-Profil nur zu 6,6 Kernen aktiv und zu
49 % untätig geparkt (`workerLoop`). Von ihrer Insel-Zeit entfallen 58 % auf
`tickIslandEntitiesInVanillaOrder`. Seit 3.0.0 tickt jede Insel alle ihre
Entities nacheinander auf einem Thread (Vanilla-Einfügereihenfolge, TNT-Fix);
bis 0.2.38 tickten die Regionen einer Insel parallel unter dem ColorGate. Der
Stand-Vergleich zum TNT-Fix zeigte bereits 20–25 % weniger Insel-TPS auf einem
8-GB-Mac; mit 17 Workern und dichten Mehrspieler-Inseln ist der Verlust größer.

Eine Aufteilung nur nach räumlich getrennten Gruppen (≥ 256 Blöcke ohne
Entities dazwischen, Reihenfolge innerhalb jeder Gruppe exakt) hilft hier
nicht: In der Spawn-Insel liegen 1101 von 1283 tickenden Entities in einem
zusammenhängenden Bereich. Exakte Vanilla-Reihenfolge und Parallelität
schließen sich für dichte Inseln aus.

## Änderung in 3.1.0

`islandEntityParallel` (Standard `false` = bisheriges Verhalten). Mit `true`
tickt eine Insel ihre Regionen wieder parallel wie in 0.2.38; Inseln mit
gezündetem TNT oder TNT-Lore bleiben im exakten seriellen Pass.

```
/fabrolia regions islandentities parallel   # einschalten (wird gespeichert)
/fabrolia regions islandentities vanilla    # zurück zum seriellen Pass
/fabrolia regions islandentities            # Status
```

Abweichung im Modus `parallel` (wie bis 0.2.38): Innerhalb einer Insel ticken
Entities benachbarter Regionen in ColorGate-Wellen statt in
Einfügereihenfolge; Blockänderungen außerhalb der tickenden Region werden nach
der Entity-Phase nachgeholt. TNT ist davon ausgenommen.

Nicht geprüft: Stand (`verify`) und ein `perf`-Vergleich; beides ist auf dem
Windows-Rechner nicht ausführbar. Wirkung und Stabilität müssen sich in
Produktion zeigen; mit `vanilla` ist der alte Zustand ohne Neustart zurück.
Zum Messen nach dem Umschalten: `fabrolia regions top verbose 100` (MSPT der
großen Inseln) und ein Profil mit `/spark profiler start --thread * --interval 4`.

## Weitere Ansatzpunkte (nicht umgesetzt)

- `UseItemOn` auf der Insel statt auf Main: größter einzelner Main-Wartepunkt;
  braucht eine Prüfung aller Block-Interaktionen und Mod-Hooks (WorldEdit,
  Ledger, Betten/`SleepStatus`).
- Überfällige Tracking-Veröffentlichungen ohne blockierendes Warten auf Main
  bedienen (Service-Request statt `waitForRemaining`).
- Die Insel-Ausschnitte kopieren und durchsuchen pro Unit die ganze
  `EntityTickList` des Levels (16 ms/Tick Monitor-Warten über alle Worker).
