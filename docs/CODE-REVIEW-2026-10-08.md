# Fabrolia: Codeprüfung und Portal-Farmen — 08.10.2026

Dieser Bericht beschreibt den Stand **vor den anschließenden Reparaturen**.
R1–R6 wurden danach bearbeitet; Umsetzung und aktuelle Nachweise stehen im
[Fixbericht](CRITICAL-FIXES-2026-10-08.md). Die ursprünglichen Befunde bleiben als
Ausgangspunkt der Prüfung erhalten.

Geprüft wurde der aktuelle Arbeitsstand auf Basis von Commit `8f57123d7b11594d377994aefcccbe900e64d2de`, einschließlich der bereits vorgenommenen Carpet-Absicherung. Schwerpunkt: End-Sand-Duper, Portal-Chunkloader, Farmen über Dimensionsgrenzen und allgemeine Fehler in Fabrolia.

`api`, `common`, `fabric` und `neoforge` wurden nach Referenzen, Konfigurationsnutzung, Registrierung und auffälligen Fehlerpfaden durchsucht. Portal-, Speicher-, Collection-, Scheduler- und Diagnosepfade wurden gezielt gelesen. Bei Kandidaten für unbenutzte öffentliche APIs wurden zusätzlich Rustmi, nalim, MinecraftFunctionCompiler und adaptivesleepy nach Aufrufern durchsucht. Das ist eine gezielte Codeprüfung; es ist keine Aussage, dass jede mögliche Modkombination oder Farm getestet wurde.

Die drei [Diagnoseprogramme](reviews/2026-10-08/probes) wurden direkt mit den aktuellen Java-Quelldateien und fastutil 8.5.18 kompiliert. An der Laufzeitimplementierung wurde für diese Prüfung nichts geändert.

## Prioritäten und Nachweis

P1: zuerst beheben, weil Spielzustände oder technische Farmen betroffen sein können. P2: bestätigte Funktions- oder Diagnosefehler, deren konkrete Auswirkung vom Aufrufer abhängt. P3: Bereinigung und Dokumentation.

| ID | Priorität | Befund | Nachweis |
| --- | --- | --- | --- |
| R1 | P1 | Concurrent-Maps können den Wert eines fremden Schlüssels liefern | Belastungstest mit tatsächlichen Implementierungen; alle drei Varianten betroffen |
| R2 | P1 | Speichern umgeht die Wartebedingung des End-Plattform-Handoffs | Tatsächlicher Speicherpfad und ausführbare Prüfung des echten Handoff-Helfers; kein Live-Duper-Test |
| R3 | P1 | Verschobene Portal-Tickets hängen nachträglich von der Entity ab | Quellcode plus Vergleich mit Minecraft-26.3-Bytecode; kein Live-Chunkloader-Ausfall reproduziert |
| R4 | P2 | `remove(key, value)` meldet erfolgreiche Löschung als `false` | Direkter Vergleich mit fastutil |
| R5 | P2 | Änderungen über `keySet()` / `values()` ändern die Map nicht | Direkter Vergleich mit fastutil |
| R6 | P2 | Byte-Map ignoriert Änderungen über `Entry.setValue` / `replaceAll` | Direkter Vergleich mit fastutil |
| R7 | P2 | Zwei angebotene Schalter haben keine Laufzeitwirkung | Vollständige Referenzsuche und explizite Kommentare im Code |
| R8 | P2 | HUD kann bei einem hängenden Tick alte, niedrige MSPT anzeigen | Vergleich von Messung, Anzeige und dokumentierter Anforderung |
| R9 | P3 | Dokumentierter Mindestabstand zwischen Inseln stimmt nicht | BFS-Implementierung gegen Dokumentation |
| R10 | P3 | Drei größere Utilities ohne Aufrufer im Workspace | Referenzsuche; öffentliche API-Nutzung außerhalb des Workspace bleibt offen |

## R1 — Falscher Wert bei gleichzeitigem Löschen und Einfügen

Stellen: [Long2IntConcurrentHashMap](../api/src/main/java/com/axalotl/fabrolia/api/fastutil/Long2IntConcurrentHashMap.java), `get` ab Zeile 84 und `getOrDefault` ab Zeile 206; [Long2LongConcurrentHashMap](../api/src/main/java/com/axalotl/fabrolia/api/fastutil/Long2LongConcurrentHashMap.java), `get` ab Zeile 87; [Long2ByteConcurrentHashMap](../api/src/main/java/com/axalotl/fabrolia/api/fastutil/Long2ByteConcurrentHashMap.java), Delegation ab Zeile 27.

Die primitiven Maps speichern Schlüssel und Werte in getrennten Arrays. Ein Leser prüft den Schlüssel, liest den Wert und prüft den Schlüssel erneut. Diese zweite Prüfung verhindert einen einfachen Wechsel auf einen anderen Schlüssel, übersieht aber einen Wechsel zurück auf den ursprünglichen Schlüssel, das sogenannte ABA-Problem:

1. Leser sieht Schlüssel A.
2. Schreiber löscht A und verwendet denselben Platz für B.
3. Leser liest den Wert von B.
4. Schreiber löscht B und fügt A wieder ein.
5. Leser sieht wieder A und gibt den zuvor gelesenen B-Wert zurück.

A hatte diesen Wert zu keinem Zeitpunkt. Das ist kein zulässiger alter Zwischenstand eines Concurrent-Reads.

[MapRecycleProbe.java](reviews/2026-10-08/probes/MapRecycleProbe.java) arbeitet ausschließlich über `put`, `remove` und `get`; Reflexion liest nur die Segmentanzahl für die Suche nach kollidierenden Schlüsseln. Ein Schreiber setzt Schlüssel 1 ausschließlich auf 11, den kollidierenden Schlüssel 19 ausschließlich auf 22. Sechs Leser fragen Schlüssel 1 ab. Beobachtet:

```text
int: get(1) returned foreign value 22; colliding key=19
long: get(1) returned foreign value 22; colliding key=19
byte: get(1) returned foreign value 22; colliding key=19
```

Es gab auch Int-Läufe ohne beobachteten Fehler innerhalb von acht Sekunden; ein direkt anschließender Lauf reproduzierte ihn wieder. Die Reproduktion ist vom Thread-Timing abhängig. Ein einzelner unauffälliger Lauf widerlegt den Fehler nicht.

Die Byte-Map ersetzt reale Minecraft-Maps in [FixedPlayerDistanceChunkTrackerMixin](../common/src/main/java/com/axalotl/fabrolia/common/mixin/server/FixedPlayerDistanceChunkTrackerMixin.java), Zeile 41, und [SimulationChunkTrackerMixin](../common/src/main/java/com/axalotl/fabrolia/common/mixin/server/SimulationChunkTrackerMixin.java), Zeile 91. Weitere primitive Maps werden bei Entity-Ticking und Scheduled-Ticks eingesetzt. Für diese konkreten Minecraft-Aufrufer wurde noch kein falscher Weltzustand live nachgestellt; der Fehler in ihren Collection-Bausteinen ist reproduziert.

**Reparatur:** Schlüssel und Wert müssen gemeinsam konsistent gelesen werden. Mögliche Ansätze sind atomar veröffentlichte Einträge oder eine vollständige Schreibgeneration pro Segment, die der Leser vor und nach dem Zugriff prüft. Diese Generation muss jede Mutation einschließlich Löschen, Wiederverwendung, `clear`, Resize und Wertänderungen erfassen. Ein synchronisierter Lesezugriff kann eine einfachere Zwischenlösung sein, braucht aber eine Prüfung zusätzlicher Lock-Abhängigkeiten und der Kosten. Ein weiterer reiner Schlüsselvergleich reicht nicht. `getOrDefault` und andere Paar-Lesezugriffe gehören in dieselbe Prüfung.

**Validierung:** kontrollierte A→B→A-Interleavings, wiederholte Paralleltests und Vergleich mit einer Referenzimplementierung; anschließend vollständige Tests, Stand und Vorher/Nachher-Profil für Chunk-/Spawn-Last.

## R2 — End-Schutz wird beim Speichern umgangen

Stellen: [EndPlatformHandoff](../common/src/main/java/com/axalotl/fabrolia/common/regions/EndPlatformHandoff.java); [CrossDimArrivals](../common/src/main/java/com/axalotl/fabrolia/common/regions/CrossDimArrivals.java), normale Auswahl um Zeile 635, `flushLevel` ab Zeile 1109 und erzwungene Veröffentlichung ab Zeile 1133; [ServerChunkCacheMixin](../common/src/main/java/com/axalotl/fabrolia/common/mixin/server/ServerChunkCacheMixin.java), Save-Wrapper ab Zeile 1620.

Der normale End-Pfad gruppiert Plattform-Regeneration und Entity-Ankunft in zusammengehörige Batches. Solange Falling-Block-Entities des vorherigen Batches noch nicht beendet sind, hält `selectReleasable` den nächsten Batch zurück. Das schützt einen schnellen Sender vor einer langsameren End-Insel. Die Prüfung folgt dem tatsächlichen Entity-Zustand, nicht einer fest angenommenen Tickzahl.

Beim Speichern werden dagegen alle wartenden Einträge kopiert, aus der Warteschlange entfernt, mit `trackAll` registriert und vollständig ausgeführt. Der Code kommentiert ausdrücklich, dass auch vom Gate gehaltene Batches veröffentlicht werden. Die Registrierung merkt sich ihre Falling-Blocks für spätere Batches; sie verhindert keine vorzeitige Regeneration des jetzt ausgeführten Batches.

Folge: Während alter Sand noch fällt, kann ein Save die nächste Plattform-Regeneration ausführen. Genau die Wartebedingung des normalen Pfads wird damit verletzt. [HandoffFlushProbe.java](reviews/2026-10-08/probes/HandoffFlushProbe.java) zeigt mit dem echten Helfer, dass der normale Pfad Batch B hält, die im Save verwendete Registrierung aber keine Freigabeentscheidung erzwingt. Der Probe bildet die gelesene Save-Aufrufsequenz nach; er startet keinen Minecraft-Server und misst keinen Duper-Ertrag.

Die vorhandenen `EndPlatformHandoffFlushTrackingTest`-Tests behandeln die Registrierung eines geflushten Batches und die Blockade des danach kommenden Batches. Der Fall „vorheriger Batch fällt noch, Save erzwingt den nächsten“ ist dadurch nicht abgesichert.

**Warum eine zusätzliche `if`-Abfrage nicht ausreicht:** Die Warteschlange enthält ausführbare Java-Aufgaben im RAM. Der ursprüngliche Entity-Transfer hat seine Quelle bereits entfernt; die Zielkopie kann noch in keiner gespeicherten Welt enthalten sein. Würde man wartende Batches beim Save einfach überspringen, fehlte ihre zuverlässige Wiederherstellung nach einem Neustart.

**Bevorzugte Reparatur:** wartende Transfers als typisierte, speicherbare Datensätze führen: Entity-NBT, Zieldimension und Position, stabile Quellen-/Batchidentität, Reihenfolge und Regenerationsabsicht. Die normale Wartebedingung muss auch beim Autosave gelten. Wartende Datensätze werden gespeichert und vor Wiederaufnahme der Simulation wiederhergestellt. Veröffentlichung und Bestätigung brauchen eine klare Zuständigkeit sowie Schutz vor doppelter Zustellung; eine belastbare Crash-Garantie verlangt zusätzlich ein passendes Journal-/Commit-Verfahren. Beliebige `Runnable`-Closures können nicht einfach als NBT gespeichert werden, daher ist das ein Umbau des Transferpfads.

**Alternative:** vor dem Snapshot eine konsistente Drain-Phase, in der notwendige Ziel-Ticks vollständig laufen dürfen, bis ältere Ankünfte beendet sind. Neue betroffene Transfers müssen währenddessen kontrolliert werden. Ein normales Quiesce mit anschließendem Warten genügt nicht, weil die angehaltenen Falling-Blocks dann nicht landen können. Diese Variante kann lange Saves und neue Fence-Abhängigkeiten verursachen und braucht eigene Laufzeitbelege.

## R3 — Verschobene Portal-Ticket-Anforderung verliert ihren ursprünglichen Zustand

Stelle: [PortalTicketMixin](../common/src/main/java/com/axalotl/fabrolia/common/mixin/utils/PortalTicketMixin.java), Zeilen 30–45.

Off-Main wird `Entity.placePortalTicket(pos)` abgebrochen. Der Ersatz speichert die Blockposition und legt eine Main-Aufgabe an, die später nur bei `!self.isRemoved()` erneut dieselbe Entity-Methode aufruft. Die ursprüngliche Zieldimension wird nicht mitgespeichert.

Das kann eine bereits angeforderte Ticket-Verlängerung verwerfen, wenn das Item vor dem Drain eingesammelt, zusammengeführt oder entfernt wird. Bei einem zwischenzeitlichen Levelwechsel würde die erneute Entity-Methode die dann aktuelle Dimension benutzen. Minecraft 26.3 prüft in `placePortalTicket` kein `isRemoved`: Es fügt direkt am aktuellen ServerLevel ein `PORTAL`-Ticket mit Radius 3 hinzu. Das wurde mit `javap` am vorhandenen Minecraft-Jar geprüft.

Der generische gestagte Arrival-Pfad fügt zwar selbst ein Sustain-Ticket hinzu. Das deckt aber nicht pauschal alle Pfade ab: Die vollständig zugelassenen Nether-Transfers kehren in [CrossDimTeleportMixin](../common/src/main/java/com/axalotl/fabrolia/common/mixin/world/CrossDimTeleportMixin.java), Zeile 53, bereits vor diesem Ersatzpfad zurück. Deshalb sollte der ursprüngliche Ticket-Auftrag seine Bedeutung unabhängig vom späteren Entity-Zustand behalten.

**Reparatur:** beim Anfordern den zuständigen `ServerLevel` und den unveränderlichen `ChunkPos` erfassen; auf Main direkt die konkrete Ticket-Operation ausführen. Entity-Liveness darf die bereits angenommene Ticket-Anforderung nicht nachträglich streichen. Servergeneration und eventuell abgebaute Dimensionen müssen dabei ausdrücklich behandelt werden.

**Validierung:** Portal-Item wird vor Main-Drain entfernt/vereinigt, Übergang zurück durch ein Portal, schneller Chunkloader bei überlastetem Main und auslaufende Tickets ohne anwesenden Spieler. Ein praktischer Chunkloader-Ausfall ist in dieser Prüfung noch nicht live reproduziert.

## R4–R6 — Verletzte Map-Verträge

[CollectionContractProbe.java](reviews/2026-10-08/probes/CollectionContractProbe.java) reproduziert sieben Abweichungen gegenüber fastutil 8.5.18. Die API-Verträge für verknüpfte Views und `replaceAll` stehen auch in der offiziellen [Java-25-Map-Dokumentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Map.html).

### R4: Falsche Rückgabe nach erfolgreicher bedingter Löschung

`Long2IntConcurrentHashMap.remove(key, value)`, Zeilen 146–150, und die Long-Variante, Zeilen 143–147, vergleichen den gelöschten Wert mit dem Default-Rückgabewert. Wenn beide gleich sind, wird die tatsächlich erfolgte Löschung als `false` gemeldet. Byte delegiert an Int und erbt den Fehler.

Beispiel: `put(1L, 0); remove(1L, 0)` löscht den Eintrag, liefert aber `false`. Der Fehler gilt auch für einen angepassten Defaultwert, im Probe `7L`. Folgeoperationen, die ihren Änderungsstatus aus diesem Boolean ableiten, können deshalb falsch reagieren.

**Reparatur:** unter dem Segment-Lock Anwesenheit und erwarteten Wert prüfen, löschen und den Erfolg als eigenen Boolean zurückgeben. Der Defaultwert darf den Erfolg nicht bestimmen.

### R5: Veränderbare Kopien statt verknüpfter Views

`keySet` / `values` erzeugen in Int ab Zeile 453 und Long ab Zeile 465 Kopien; Byte delegiert bzw. kopiert ebenfalls. `map.keySet().remove(key)` kann `true` liefern, obwohl der Eintrag in der Map bestehen bleibt. `map.values().clear()` leert nur die Liste.

Snapshot-Iteration kann für Parallelzugriffe sinnvoll sein. Die angebotene Set-/Collection-View muss jedoch Änderungen an die Map weitergeben und ihre Abfragen gegen die Map ausführen. Ein ausdrücklich benannter Snapshot kann zusätzlich angeboten werden.

### R6: Byte-Entry schreibt nur in eine lokale Kopie

`Long2ByteConcurrentHashMap`, Zeilen 98–100 und 130–136: Die Iteration erzeugt `BasicEntry`; `setValue` verändert nur dessen eigenes Byte-Feld. Die Map bleibt unverändert. Die geerbte `Map.replaceAll`-Implementierung benutzt diesen Weg und ändert damit ebenfalls keine gespeicherten Werte.

**Reparatur:** Entry mit Rückverbindung zur Map oder eine korrekte eigene `replaceAll`-Implementierung. Bei der Überarbeitung sollten auch Entry-/Set-Gleichheit, Standard-`Map.Entry`-Interoperabilität und Iterator-Änderungen systematisch geprüft werden.

| Diagnose | Referenz | Fabrolia |
| --- | --- | --- |
| Int: vorhandenes `0` bedingt löschen | `true` | `false`, trotzdem gelöscht |
| Long: vorhandenen Defaultwert `7` bedingt löschen | `true` | `false` |
| Byte: vorhandenes `0` bedingt löschen | `true` | `false` |
| Long: Schlüssel über `keySet` entfernen, danach `containsKey` | `false` | `true` |
| Int: `values.clear`, danach Map-Größe | `0` | `1` |
| Byte: Entry-Wert von 12 auf 19 setzen, danach Map lesen | `19` | `12` |
| Byte: `replaceAll` auf 21, danach Map lesen | `21` | `12` |

Das sind bestätigte Bibliotheksfehler. Für R4–R6 wurde keine konkrete Farmbeschädigung über einen Minecraft-Aufrufer nachgewiesen.

## R7 — Schalter ohne Wirkung

- `enableFabroliaChunkSend`: [gemeinsame Konfiguration](../common/src/main/java/com/axalotl/fabrolia/common/config/FabroliaConfig.java), Zeilen 25–28, bezeichnet den Schlüssel ausdrücklich als Legacy ohne Laufzeitleser. Fabric und NeoForge laden/speichern ihn weiterhin. [ConfigCommand](../common/src/main/java/com/axalotl/fabrolia/common/commands/ConfigCommand.java), Zeilen 179–194, bietet ihn an, [StatsCommand](../common/src/main/java/com/axalotl/fabrolia/common/commands/StatsCommand.java), Zeile 102 und folgende, zeigt davon abhängig Enabled/Disabled. Der echte Chunk-Versand richtet sich nicht danach.
- `chunkBridgeHealBroken`: gemeinsame Konfiguration, Zeilen 109–114, dokumentiert ebenfalls keine Laufzeitnutzung. [RegionCommand](../common/src/main/java/com/axalotl/fabrolia/common/commands/RegionCommand.java), Zeilen 213–227, bietet trotzdem `chunkheal on/off` und verspricht Heilung bzw. reines Logging. Der frühere Heal wurde durch eine Fail-Stop-Diagnose ersetzt; der Schalter beeinflusst sie nicht.

**Reparatur:** alte Config-Schlüssel für Migration bei Bedarf akzeptieren, aber als veraltet kennzeichnen; unwirksame Schaltflächen/Befehle entfernen oder mit korrekter Statusmeldung versehen. Der sicherheitsrelevante Fail-Stop-Pfad sollte dabei nicht versehentlich als „Heal“ abschaltbar gemacht werden.

## R8 — Alte MSPT können wie aktuelle Tickkosten aussehen

[IslandTickUnit](../common/src/main/java/com/axalotl/fabrolia/common/regions/IslandTickUnit.java), Zeilen 1727–1735 und 1880–1881, schreibt die Kostenstatistik erst nach erfolgreicher Fertigstellung einer Tick-Einheit. [TpsHud](../common/src/main/java/com/axalotl/fabrolia/common/regions/TpsHud.java), Zeile 177, und [CarpetTpsLines](../common/src/main/java/com/axalotl/fabrolia/common/regions/CarpetTpsLines.java), Zeile 152, zeigen diese Statistik ohne laufende Tickdauer bzw. Blockierungsgrund.

Bleibt die nächste Einheit hängen, kann dort weiter der niedrige Wert der vorherigen abgeschlossenen Einheiten stehen. `/regions`-Listen erkennen teilweise ab drei Sekunden einen `Stalled`-Status; das beseitigt die Unklarheit im HUD nicht. Die Messgröße selbst ist als geglättete Kosten abgeschlossener Einheiten sinnvoll, die Darstellung bleibt aber hinter [USER-REQUIREMENTS](USER-REQUIREMENTS.md), Punkt 5, zurück.

**Reparatur:** abgeschlossene Tickkosten klar benennen; daneben aktuelle Ausführungsdauer, letzte Fertigstellung und gegebenenfalls Wartedauer/HOLD-Grund anzeigen. Claim-Alter allein ist keine Tickdauer, weil Main-/Save-Claims andere Zwecke haben. Auch `maxmspt` ist tatsächlich ein abklingender Spitzenwert und sollte entsprechend bezeichnet werden.

## R9 — Inselabstand falsch dokumentiert

[ASYNC-ISLANDS](ASYNC-ISLANDS.md), Zeilen 41–43, und [CODEMAP](CODEMAP.md), Zeile 33, nennen mindestens drei ungeladene Zellen bzw. 24 Chunks / 384 Blöcke Abstand. Tatsächlich verbindet [RegionizedServer](../common/src/main/java/com/axalotl/fabrolia/common/regions/RegionizedServer.java), `ISLAND_BFS_RADIUS = 2` bei Zeile 402, bevölkerte Zellen bis einschließlich Zellkoordinaten-Abstand 2. `RegionGrid.DEFAULT_SHIFT = 3` macht eine Zelle acht Chunks breit.

Entlang einer Achse liegen getrennte bevölkerte Zellen somit mindestens drei Zellkoordinaten auseinander, mit **zwei** leeren Zellen dazwischen: 16 Chunks / 256 Blöcke. Die Dokumentation verwechselt offenbar den Abstand der Zellkoordinaten mit der Anzahl leerer Zwischenzellen. „Leer“ bezeichnet hier fehlende FULL-Chunks in der für den BFS relevanten Population; Chunk-Tickets und spätere Topologieänderungen können die Zuordnung verändern.

**Reparatur:** die Dokumentation an die tatsächliche Topologiedefinition anpassen und ein kleines Zellenbeispiel ergänzen. Ein pauschaler Abstand zwischen konkreten Blockpositionen ersetzt keine Prüfung der belegten Zellen.

## R10 — Kandidaten für unbenutzten Code

Für diese drei öffentlichen Utilities fanden sich außerhalb ihrer eigenen Definition keine Aufrufer in den Java-/Kotlin-/Konfigurationsquellen der fünf Workspace-Projekte:

| Klasse | Größe | Einordnung |
| --- | --- | --- |
| [SensorUtils](../api/src/main/java/com/axalotl/fabrolia/api/utils/SensorUtils.java) | 37 Zeilen | unbenutzter Distanz-Comparator |
| [IteratorSafeOrderedReferenceSet](../api/src/main/java/com/axalotl/fabrolia/api/utils/IteratorSafeOrderedReferenceSet.java) | 316 Zeilen | unbenutzte eigene Set-Implementierung |
| [FastUtilHackUtil](../api/src/main/java/com/axalotl/fabrolia/api/fastutil/FastUtilHackUtil.java) | 1011 Zeilen | unbenutzte Collection-Adapter |

Bei `SensorUtils` wären gespeicherte Distanzen nach Bewegung veraltet; der Cache hält Entities während der Lebensdauer des Comparators fest. Weil aktuell keine Aufrufer gefunden wurden, ist das kein belegter aktiver Speicherfehler des Servers.

**Bereinigung:** Veröffentlichung/Abhängigkeiten prüfen, öffentliche APIs zunächst deprecaten oder ausdrücklich entfernen, danach Implementierungen löschen. Allein fehlende interne Referenzen beweisen nicht, dass externe Mods sie nie benutzen. Das gilt auch für die Integration-API `FabroliaFunctionScopes`.

Mixins, Plattform-Serviceprovider und Loader-Entrypoints wurden als separate Registrierungswege berücksichtigt. Beim Abgleich der 255 annotierten Mixin-Klassen mit den Mixin-Konfigurationen fiel keine unregistrierte Klasse auf. Lexikalisch unbenutzte Serviceprovider sind daher keine Löschkandidaten.

## Was für Portal-Farmen sinnvoll wäre

Für den End-Sand-Duper besteht bereits ein gezielter Schutz; dessen Save-Ausnahme sollte zuerst repariert werden. Das End in die vorhandene Dimensionspaar-Liste einzutragen löst das Problem nicht: [CrossDimLinks](../common/src/main/java/com/axalotl/fabrolia/common/regions/CrossDimLinks.java), Zeilen 86–96, unterstützt ausschließlich Overworld↔Nether und lehnt End-Paare ausdrücklich ab.

Auch im Nether synchronisiert diese Kopplung den Zielrhythmus und den Deadline-Anker, keine gemeinsame Tick-Fertigstellung. Die Quellcode-Dokumentation „Honest limits“, Zeilen 98–105, beschreibt selbst, dass eine überlastete Insel bei den abgeschlossenen Ticks zurückbleiben kann. Gleiche konfigurierte TPS reichen somit nicht als Paritätsgarantie für eine Zwei-Dimensionen-Farm.

Eine weitergehende Architektur wäre eine gemeinsame Abschluss-/Tick-Reihenfolge für tatsächlich portalverbundene Inseln. Das koppelt die Fortschrittsrate an den langsameren Teilnehmer und vermindert deren Unabhängigkeit. Der im Code erwähnte `BridgeTickUnit`-Entwurf wurde wegen eines Command-Fence-Deadlocks zurückgestellt; diese Idee ist daher kein kleiner Config-Fix.

Als vorläufige Absicherung gegen unterschiedliche Inselgeschwindigkeiten kann `asyncIslands=false` bzw. `/fabrolia regions async off` dienen. Eine laufende Sand-Maschine sollte dabei zuerst ihren Zulauf stoppen und die bisherigen Ankünfte auslaufen lassen: Auch der Moduswechsel kann eine erzwungene Flush-Phase auslösen. Der synchrone Takt reduziert dieses spezielle Timing-Risiko, ist aber keine hier getestete allgemeine Vanilla-Garantie für die Farm.

Empfohlene Reihenfolge der Arbeit:

1. R1 reparieren und Concurrent-Collection-Verträge R4–R6 mit abdecken.
2. R3 unabhängig vom späteren Entity-Zustand machen.
3. Für R2 eine speicherbare Transferdarstellung und korrekte Wiederherstellung implementieren.
4. R7/R8 korrigieren, damit Konfiguration und Diagnose tatsächliches Verhalten zeigen.
5. Dokumentation und nachgewiesen unbenutzte APIs bereinigen.

## Erforderliche Laufzeittests für Portal-/Save-Änderungen

- Echter End-Sand-Duper: Quelle/Ziel bei 20/20, 20/5 und 5/20 TPS; wiederholtes Speichern genau während Sand fällt und der nächste Batch wartet.
- Gleiche Szene mit sauberem Stop/Neustart; Prüfung auf verlorene oder doppelte Entities und Plattform-Ausgabe.
- Portal-Chunkloader ohne Spieler, mit eingesammelten/vereinigten Items, Endpunkt-Unload und belastetem Main.
- Zwei-Dimensionen-Farmen und Portal-Minecarts bei Merge/Split sowie deaktivierten bzw. pausierten Inseln.
- Vergleich mit dem passenden Vanilla-/Carpet-Referenzsetup, einschließlich der für den Duper benötigten Regel und identischem Aufbau; Ertrag und Entity-/Block-Reihenfolge messen.
- Zusätzlich die Repository-Checks und den vollständigen Stand aus `AGENTS.md`; bei Threading-/Performanceänderungen Vorher/Nachher-Messung.

Die bereits grüne Carpet-Standprüfung enthielt keinen solchen End-Duper-Test. Sie belegt die neue Carpet-Absicherung, nicht die Beseitigung der hier gefundenen Portal-Probleme. Der vorhandene vollständige Mixin-Export-Verifier hat außerdem laut `AGENTS.md` eine veraltete Assertion in `verifyServerFunctionManager`, die auch das Produktions-Jar ablehnt und spätere Prüfungen blockiert. Diese bekannte Prüflücke sollte vor Nutzung als Gesamtbeleg korrigiert werden.

## Diagnoseprogramme erneut ausführen

Vom Fabrolia-Projektverzeichnis mit Java 25 und fastutil 8.5.18. `REVIEW_FASTUTIL_JAR` auf den lokalen Jar-Pfad setzen; alle vier geprüften Implementierungen werden frisch aus dem Quellcode kompiliert:

```sh
REVIEW_FASTUTIL_JAR="/path/to/fastutil-8.5.18.jar"
REVIEW_CLASSES="$(mktemp -d)"
javac -cp "$REVIEW_FASTUTIL_JAR" -d "$REVIEW_CLASSES" \
  api/src/main/java/com/axalotl/fabrolia/api/fastutil/Long2IntConcurrentHashMap.java \
  api/src/main/java/com/axalotl/fabrolia/api/fastutil/Long2LongConcurrentHashMap.java \
  api/src/main/java/com/axalotl/fabrolia/api/fastutil/Long2ByteConcurrentHashMap.java \
  common/src/main/java/com/axalotl/fabrolia/common/regions/EndPlatformHandoff.java \
  docs/reviews/2026-10-08/probes/*.java
java -cp "$REVIEW_CLASSES:$REVIEW_FASTUTIL_JAR" CollectionContractProbe
java -cp "$REVIEW_CLASSES:$REVIEW_FASTUTIL_JAR" MapRecycleProbe int
java -cp "$REVIEW_CLASSES:$REVIEW_FASTUTIL_JAR" MapRecycleProbe long
java -cp "$REVIEW_CLASSES:$REVIEW_FASTUTIL_JAR" MapRecycleProbe byte
java -cp "$REVIEW_CLASSES" HandoffFlushProbe
```

Die Programme sind Diagnosebelege des hier geprüften Zustands, keine vollständige Regressionstestsuite. Insbesondere entscheidet ein einzelner achtsekündiger Map-Lauf ohne Treffer keine Nebenläufigkeitskorrektheit, und der Handoff-Probe ersetzt den tatsächlichen Save-/Neustart-Test nicht.
