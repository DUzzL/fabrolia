# Unabhängiger Review der Performance-Änderungen

Stand: 25. Oktober 2026. Geprüft wurde der uncommittete Arbeitsstand mit Gruppenindex für
Scheduled-Tick-Chunks, Autosave-Vorfilter und geänderten ThreadLocal-Aufräumpfaden. Dies ist kein
vollständiger Review aller älteren, ebenfalls uncommitteten Spawn-/NeoForge-Änderungen. Build,
Laufzeittests und Artefakte dokumentiert der [Hauptreport](README.md).

## Gefundener Fehler: Tick-Reihenfolge bei gleichen Vergleichsschlüsseln

Der neue `scheduledChunksInCell`-Pfad übernahm die Iterationsreihenfolge eines `LongOpenHashSet`. Zuvor
wurden die Chunkpositionen jeder Zelle nach X und dann nach Z aufsteigend besucht. Dieser Unterschied kann
die tatsächliche Ausführung von Scheduled Ticks verändern:

1. Die Container gelangen in Besuchsreihenfolge in eine `PriorityQueue`.
2. `containerOrder()` verwendet `ScheduledTick.INTRA_TICK_DRAIN_ORDER`, also Priorität und `subTickOrder`.
   Bei gleichen Werten liefert der Vergleich 0.
3. Vanilla `LevelChunkTicks.unpack` nummeriert gespeicherte Ticks je Chunk unabhängig ab
   `-pendingTicks.size()`. Zwei Chunks mit je einem gespeicherten Tick erhalten daher beide
   `subTickOrder=-1`.
4. Bei solchen Gleichständen beeinflusst die Einfügereihenfolge den Drain. Betroffen sein können
   miteinander interagierende Redstone- oder Fluid-Ticks.

Die Gleichstände wurden am lokalen Minecraft-26.3-Bytecode mit `javap` geprüft. Eine isolierte Gegenprobe
mit Fastutil 8.5.18 und gleich vergleichenden Queue-Einträgen zeigte für vier Chunks derselben Zelle:

```text
alter Walk: [4294967305, 8589934601, 4294967306, 8589934602]
Hash-Walk:  [8589934602, 4294967306, 8589934601, 4294967305]
erster Drain: vorher 4294967305, ungeordnet 8589934602
```

**Behoben:** `IslandScheduledTicks.scheduledChunksInCell` sortiert ausschließlich die gefundenen
Indexeinträge nach Chunk-X, dann Chunk-Z. Bei der festen Produktionsgröße sind höchstens 64 Chunks je
Zelle möglich. Der Index vermeidet weiterhin die bisherigen vollständigen Positionsabfragen; die
ursprüngliche Besuchsreihenfolge bleibt erhalten.

**Regression:** Der neue Test
`ScheduledTickGlobalCellDiscoveryTest.indexedDiscoveryPreservesCellWalkAndTiedLoadedTickDrainOrder` ruft
die echte Discovery-Methode auf, prüft positive und negative Zellen sowie die Ursprungszelle und den
Ausschluss einer fremden Zelle. Echte Vanilla- `SavedTick`/`LevelChunkTicks` erzeugen die gleichen
geladenen Subtick-Schlüssel; der Produktionskomparator und eine Queue prüfen den resultierenden Drain. Der
geprüfte JUnit-XML-Bericht enthält drei Tests, keine Fehler und keine Skips.

## Weitere geprüfte Invarianten

- **Gruppenindex:** Neue Keys werden in `putLocked` eingetragen; alle Entfernungen, `compute`/`merge` mit
  `null` und die Map-Views führen über `groupRemove`. Reine Wertänderungen verändern die Mitgliedschaft
  nicht. `clear` entfernt jeden belegten Key unter dessen Segmentmonitor; Rehashing verändert keine Keys.
  Nach abgeschlossenen Schreibern entspricht der Index der Map. Bestehende Tests prüfen viele
  Mutationspfade und mehrere Schreiber.
- **Lockreihenfolge:** Schreiber nehmen Segmentmonitor, dann den CHM-Gruppenlock. `collectKeysInGroup`
  kopiert unter dem Gruppenlock nur primitive Keys und greift nicht auf Segmentmonitore zu. Keine inverse
  Reihenfolge gefunden.
- **Snapshot-Grenze:** Map-Slot und Gruppenindex werden nacheinander verändert. Ein gleichzeitig
  schreibender Key kann in einer Discovery enthalten sein oder fehlen. Es gibt keine global atomare
  Map-/Index-Momentaufnahme. Nach dem Kopieren freigegebene Keys werden vor Nutzung erneut über die Map
  geprüft; neu hinzukommende Keys können bis zur nächsten Sammlung warten. Dieser Review belegt die
  Invarianten nach Quieszenz, keine vollständige formale Korrektheit aller gleichzeitigen
  Chunk-Lebenszyklen.
- **Gridgröße:** Produktionsstart verwendet stets `RegionGrid.DEFAULT_SHIFT=3`; nachträglich angelegte
  Dimensionen übernehmen dieselbe Grid-Instanz. `shift` ist final, eine Größen-Config oder
  `getCellShift()` existiert nicht. Reload und Ein-/Ausschalten ändern diese Größe nicht. Der feste
  Index-Shift ist damit für die aktuelle Produktion passend; abweichende Testgrids oder eine zukünftige
  konfigurierbare Größe benötigen eine abgestimmte Änderung.
- **Autosave:** `saveMayBeDue` entspricht den Vanilla-26.3-Ablehnungen vor `save()` und
  `refreshAccessibility()`. Chunktyp, Dirty-Flag und Cooldown werden geprüft; `ChunkAccess.unsaved` ist
  `volatile`. Ein positives Ergebnis lässt weiterhin den ursprünglichen Save-Pfad unter Ownership
  entscheiden.
- **ThreadLocals:** Die geänderten Variablen verwenden `new ThreadLocal<>()` ohne Initializer. `set(null)`
  behält einen leeren Slot, aber keine Referenz auf die Nutzdaten; die bisherigen Null-Antworten bleiben
  erhalten.

Über den behobenen Reihenfolgefehler hinaus wurde in den neuen Optimierungen kein weiterer Fehler nachgewiesen. Der spätere Laufzeitbefund wird
unten gesondert bewertet. Die historischen Paket-1-Performancezahlen stammen aus einem Stand vor der zusätzlich
eingefügten Sortierung. Sie ersetzen keinen Vergleich des endgültigen Artefakts und keinen Lauf auf dem
Produktionsstand.

## Folia: geprüfte Primärquellen und Schlussfolgerungen

- [Overview](https://docs.papermc.io/folia/reference/overview/): Regionen haben eigene Deadlines und
  laufen auf einem EDF-ähnlichen Pool. Eine langsame Region verschiebt den Zeitplan anderer Regionen nicht
  unmittelbar; die Skalierung setzt freie Poolkapazität voraus. Die globale Region verwaltet unter anderem
  Regeln, Zeit, Wetter, Weltgrenze und Konsolenbefehle und besitzt keine Regiondaten. Regionsübergreifende
  Arbeit nutzt Aufgabenwarteschlangen; Teleports bestehen aus Transform und asynchronem Platzieren. Die
  beschriebene Portalimplementierung verändert ausdrücklich Vanilla-Verhalten für Nichtspieler. Folia ist
  deshalb kein pauschal mechanikneutraler Bauplan.
- [Region Logic](https://docs.papermc.io/folia/reference/region-logic/): Ein Chunkhalter hat genau einen
  Owner. Randpuffer und Merge-Radius trennen tickende Regionen; tickende Regionen dürfen nicht wachsen.
  Neue Randbereiche können bis zum Tickende transient bleiben, anschließend erfolgen Merge/Split.
- [README, ver/1.21.8](https://github.com/PaperMC/Folia/blob/ver/1.21.8/README.md): Der Nutzen hängt von
  verteilten Spielern und ausreichenden CPU-Ressourcen ab. Die genannten ungefähr 330 Spieler waren ein
  konkreter Testserver, keine allgemeine Kapazitätsgarantie. Die Empfehlungen berücksichtigen Chunkarbeit,
  Netty, GC und CPU-Reserve. Spieler-/Entitybefehle laufen auf ihrer Region, Konsolenbefehle global.
  Dieser Versionslink ist keine Aussage zur neuesten verfügbaren Folia-Version.

Für Fabrolia folgt daraus: unnötige Claims und übergreifende Scans reduzieren. Ein vollständiges
Verschieben bisher globaler Packet-/Befehlswege braucht eine eigene Paritätsprüfung. Die Quellen belegen
keinen Absolutsatz, dass jeder globale Folia-Pfad unter allen Umständen ohne Warten arbeitet.

## Zusatzbefund: Claim-Forensik im Fabric-Funktionstest bleibt offen

Trotz 42 bestandener Funktionsprüfungen enthält `log_errors_run1` des Fabric-Reports ein echtes `ERROR`:
`RETAINED CLAIM`, Worker 5, `gen=552`, `lastRelease gen=553`, `acquired via stealOne`, Alter 0 ms,
`holderRegion=-`. Der Lauf ist **nicht logrein**. Originalquellen ab Repositorywurzel:
`build/codex-validation-2026-10-25/functest-fabric-report.json` und
`../fabrolia-release/runs/ft-3113-codex/logs/2026-10-25-1.log.gz` (erster Lauf; `console.log` wurde beim
Neustart ersetzt). [Gesicherte Auszüge](claim-forensics-excerpt.txt).

`healRegion` validiert den Holder erneut, aber nicht die Generation; dasselbe Threadobjekt kann zwischen
Reads freigeben und neu claimen (ABA). Zudem liest die Logzeile Metadaten erst nach `getStackTrace()`
erneut live. Generation 552 zusammen mit Release 553 ist keine kohärente Aufnahme eines fortbestehenden
Claims. `stealReadyEntryBody` setzt im Gegensatz zu `workReadyEntryBody` kein `currentRegion`;
`ExternalTask` bindet nur `CAUSAL_STEP`. Der erfasste Stack arbeitete aktiv in
`SpreadingSnowyBlock.randomTick → tickChunk → ExternalTask.run → RegionizedTaskQueue.drain`. Ein fehlender
Primärkontext beweist daher keinen verlorenen Claim. Vergleichbare `stealOne`-Meldungen existieren bereits
in 3.1.10 unter `../fabrolia-release/runs/st-neo-sync-fix1/logs/latest.log` (23.10., 18:37:43/53). Dieser
Vorbestand ist keine Entwarnung. Dass `currentRegion` hier absichtlich nur den primären Owner benennt,
wurde nicht nachgewiesen.

**Offene Reviewaufgabe:** Snapshot vor Korrelation und nach der Stackprobe anhand Holder, Generation,
Erwerbszeit und ungepaartem Release validieren; nur erfasste Werte ausgeben und die Diagnose
qualifizieren. Keine pauschale `stealOne`-Ausnahme und kein blindes Setzen von `currentRegion`:
verschachtelte Hilfe und äußere Ownership-/Clock-Kontexte müssen zuvor geprüft werden. Kein verlorener
Claim nachgewiesen; kein Claim-/Ownership-Patch in diesem Batch vorgenommen.
