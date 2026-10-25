# Fabrolia 3.1.13-alpha: Folia-Vergleich, Performance und Review-Übergabe

Stand: 25.10.2026. Ziel dieses Reports ist ein unabhängiges Review.
Der Kandidat ist lokal gebaut; die Freigabe auf dem Produktionsstand steht aus.

3.1.13 benötigt im frischen lokalen A/B-Lauf 18,94 % weniger Prozess-CPU bei
etwa 20 TPS. 1511 Tests bestehen, 8 optionale sind übersprungen; auf beiden
Loadern bestehen jeweils 42 Funktions-Checks. Offen bleiben der synchrone
Claim-Diagnosebefund und die Produktions-/100-Client-Prüfung. Die konkrete
Änderung ist ein Review-Kandidat, keine Produktionsfreigabe.

## Ausgangslage und Umfang

Fortsetzung eines unterbrochenen Arbeitsstands. Auftrag: Folias
Skalierungsansatz recherchieren, geeignete Optimierungen in Fabrolia umsetzen und
unnötige Komplexität abbauen, bei erhaltener Vanilla-Semantik. Der Nutzer ist DUzzL;
es wurden in dieser Fortsetzung keine neuen Klassen unter `com.axalotl` angelegt.

Branch: `main`; HEAD bei Beginn: `d92b2f4f593ab0e23bea90519a850c8153d27cfe`.
Der Workspace war bereits stark verändert. `git diff HEAD` enthält daher auch
frühere, noch nicht committete 3.1.11-/3.1.12-Arbeiten und ist kein isolierter
Diff dieser Fortsetzung. Nichts wurde committet, gepusht, getaggt oder in die
Produktion installiert.

Bereits vorhanden waren:

- 3.1.11: NeoForge-Drop-/Snapshot-Korrekturen, neue Defaults und Benchmark-Werkzeuge.
- 3.1.12: Spawn-Proximity-Absicherung, Seed über die installierte `addMob`-Regel
  sowie atomare SpawnIndex-/Census-Transaktionen; siehe
  [vorheriger Mob-Cap-Review](../../2026-10-24/mob-cap/README.md).
- Performance-Paket 1: Zellindex für Scheduler-Keys, ThreadLocal-Änderungen und
  Autosave-Vorfilter; zwei lokale JFR-Läufe waren bereits abgeschlossen.
- Eine unvollständige Entfernung des Legacy-Census: `ParallelSpawnHelper` war
  gekürzt, andere Dateien referenzierten noch entfernte Methoden/Typen.

Diese Fortsetzung vervollständigt den Cleanup, prüft Paket 1 unabhängig, behebt
einen dabei gefundenen Reihenfolgefehler und validiert den fertigen Kandidaten.

## Was sich aus Folia ableiten lässt

Folia plant Regionen mit eigenen Tick-Deadlines auf einem gemeinsamen Pool.
Eine langsame Region verschiebt fremde Pläne nicht automatisch; die Zusage setzt
ausreichende Poolkapazität voraus. Globale Aufgaben umfassen unter anderem Zeit,
Wetter, Weltgrenze und Konsolenbefehle. Regionenübergreifende Arbeit verwendet
Tasks; Teleports bestehen aus mehreren asynchronen Phasen. Die Dokumentation
beschreibt auch explizite Portal-Abweichungen von Vanilla. Deshalb ist ein
vollständiges Übernehmen dieser Abläufe keine neutrale Optimierung.
[Folia Overview](https://docs.papermc.io/folia/reference/overview/)

Der Regionizer garantiert eindeutigen Besitz, Abstandspuffer und ein festes
Besitzgebiet während eines Ticks. Neu hinzukommende Randdaten können bis zum
Tickende transient bleiben; Merge/Split pflegen anschließend die Regionen.
[Folia Region Logic](https://docs.papermc.io/folia/reference/region-logic/)

Das am 25.10.2026 gelesene Folia-README auf `ver/26.3.x` ordnet die genannten
ungefähr 330 Spieler einem konkreten Testserver zu. Es empfiehlt verteilte
Spieler, vorgenerierte Welten und CPU-Reserve für Netty, Chunkarbeit und GC.
Diese Angaben sind eine konfigurationsabhängige Erfahrung, keine Zusage für
beliebige Server. [Folia README](https://github.com/PaperMC/Folia/tree/ver/26.3.x#readme)

Fabrolia besitzt bereits unabhängige Insel-Ticks, Insel-Player-Ticks,
regionale Tickdaten und Tracking-Publikation. Daraus folgt für diese Änderung:
globale Scans und unnötige Claims reduzieren. Main-Pakete, Befehle und
Portalabläufe großflächig zu verschieben würde eigene Paritätsnachweise brauchen.
Eine bestimmte Spielerzahl ist durch diesen Vergleich nicht belegt.

## Änderungen des Kandidaten

### 1. Scheduler-Discovery nach tatsächlich belegten Chunks

Dateien: `Long2LongConcurrentHashMap`, `LevelTicksMixin`, `IslandScheduledTicks`.

Die `nextTickForContainer`-Map führt optional einen Index
`Zellschlüssel -> vorhandene Chunk-Schlüssel`. Discovery fragt pro eigener Zelle
nur diese Keys ab. Zuvor wurden bei den drei relevanten Durchläufen sämtliche
64 Chunkpositionen einer Zelle geprüft, auch wenn dort nichts geplant war.
Zeit-, Eigentums-, FULL-Status- und Frontier-Prüfungen erfolgen weiterhin auf
den Kandidaten. Der Index ersetzt diese Prüfungen nicht.

Key-Übergänge aktualisieren den Index unter dem Segmentmonitor der Map.
Das Kopieren einer Gruppe nimmt deren CHM-Compute-Lock und führt darunter
keine Map-/Spielwelt-Callbacks aus. Es gibt an diesen Stellen keine inverse
Lockreihenfolge. Snapshots bei gleichzeitigen Schreibern sind schwach konsistent;
nach abgeschlossenen Schreibern entspricht der Index den Keys der Map.

Das Produktionsgrid verwendet unverändert `RegionGrid.DEFAULT_SHIFT = 3`.
Es existiert kein konfigurierbarer Grid-Shift; neu geladene Dimensionen übernehmen
das aktuelle Grid. Der Discovery-Pfad prüft die Übereinstimmung ausdrücklich.

**Im Review gefundener und behobener Fehler:** Der Gruppenindex ist ein
`LongOpenHashSet`. Dessen Hash-Reihenfolge unterscheidet sich vom bisherigen
Zell-Walk, der X aufsteigend und darin Z aufsteigend besuchte. Das ist relevant,
weil `LevelChunkTicks.unpack` die geladenen `subTickOrder` pro Chunk erneut ab
`-pendingTicks.size()` vergibt. Zwei Chunks mit je einem geladenen Tick können
deshalb gleiche Priorität und `subTickOrder = -1` haben. Der Vanilla-Comparator
liefert dann 0; die Reihenfolge, in der Container in die PriorityQueue gelangen,
kann die spätere Ausführungsreihenfolge ändern.

`scheduledChunksInCell` sortiert den kleinen Snapshot deshalb wieder nach X,
dann Z. Das erhält die vorherige Encounter-Reihenfolge. Der neue Test
`indexedDiscoveryPreservesCellWalkAndTiedLoadedTickDrainOrder` ruft den echten
Discovery-Helper auf, prüft positive und negative Zellen sowie Zellfilterung und
verwendet echte Vanilla-Container mit geladenen, gleichrangigen Ticks.

Die älteren Paket-1-Zahlen wurden vor dieser Sortierkorrektur gemessen.
Ein eigener Lauf des fertigen Kandidaten ist deshalb separat ausgewiesen.

### 2. ThreadLocal-Hotpaths

Heiße Scopes behalten ihre leeren ThreadLocal-Slots mit `set(null)`.
Die referenzierten Context-Objekte werden dabei freigegeben; die betroffenen
ThreadLocals besitzen keine Initializer, deren erneute Ausführung dadurch
unterdrückt würde. Scopes mit gespeichertem Vorgänger stellen diesen weiterhin
wieder her. Kalte, anderweitig gepinnte `remove()`-Pfade bleiben bestehen.
`NetherPortalTransfers.gameTimeFor` prüft den Threadtyp vor dem Context-Lookup.

Betroffene Bereiche: RegionWorkerPool, IslandEntityTracking,
RegionalPlayerEntityTicksV2, IslandScheduledTicks, NetherPortalTransfers,
SyncAllMixin und LocalCapSeedBridge. Fehlerausbreitung und Besitzregeln bleiben
unverändert. Diese Änderung reduziert Slot-Löschung/Neuaufbau und
ThreadLocal-Fehlgriffe; sie erzeugt keine neue Context-Lebensdauer.

### 3. Autosave vor unnötigen Claims ablehnen

`ChunkMapMixin` prüft die nebenwirkungsfreien Vanilla-Ablehnungsgründe von
`saveChunkIfNeeded` vor Claim und Admission: fehlende Zugänglichkeit, sauberer
Chunk oder noch laufender Save-Cooldown. Wenn ein Save in Betracht kommt,
verbleibt der Aufruf im bisherigen besitzgesicherten Pfad einschließlich der
Vanilla-Prüfungen. Der Vorfilter setzt keinen Cooldown und quittiert keine
Speicherung. Im geprüften Minecraft-26.3-Bytecode ist das Dirty-Flag `volatile`.

### 4. Unerreichbaren Census-Pfad entfernen

Der interne Schalter `fabroliaIncrementalSpawnCensus` war stets `true`.
Weder Fabric noch NeoForge lasen einen Config-Key dafür; Runtime-Code schrieb
ihn nicht. Der alte Scan-/Precompute-Pfad war dadurch unerreichbar.

Entfernt wurden dessen Snapshot-/Batch-/Task-Typen, Entity-Puffer,
Listen-Seed-Variante, Precompute-Registry und Tick-HEAD-Injection samt gecachtem
Getter, die ausschließlich dafür benötigte globale SpawnIndex-Version und
tote Diagnosefelder. `SpawnFanoutGate` hat jetzt eine konkrete Implementierung
und wird direkt erzeugt; die ungenutzte abstrakte Hülle und Factory entfallen.

Erhalten bleiben:

- inkrementelles Ledger und Census-Fan-out mit First-wins-Fehlerausbreitung;
- Main-Audit alle 256 Game-Ticks, Zwei-Strike-Prüfung und Reconcile;
- gemeinsame Transaktion für Index-Mitgliedschaft und Ledger-Flag;
- Vanilla-`createState`, wenn paralleles Spawning aus oder der Executor nicht
  verfügbar ist, einschließlich Main-Audit für aktive Island-Lanes;
- gewichteter Native-Arena-Seed und Java-Shadow, Reservation-Gate und Cleanup;
- Diagnosewerte `EV_ADD`, `EV_REMOVE`, Heal-/Audit- und Reduce-Kosten.

Die Diagnoseausgabe enthält die entfernten Legacy-Felder nicht mehr. Externe
Parser dieses internen Debug-Formats müssten gegebenenfalls angepasst werden.
Für entfernte APIs wurden keine verbleibenden Workspace-Caller gefunden.
Die historische `CensusAuditRaceRepro` verwendete noch `bumpVersion()`; dieser
für die Race bedeutungslose Aufruf wurde aus der Probe entfernt und dort
dokumentiert. Die Probe simuliert weiterhin absichtlich die alte geteilte
Sequenz; sie ist kein Test eines erneuten Fehlers im aktuellen `purge`.

### 5. Funktionstest berichtigen und Version abgrenzen

`tools/bench/functest.py` behauptete zwei Scheduler-Modi zu testen, begann aber
seit dem Default-Wechsel in 3.1.11 bereits mit Async-Inseln. Der Test schaltet
jetzt zunächst Player-Actor und Async-Inseln explizit aus und prüft beide
Statuswerte. Danach folgt der vorhandene Async-Abschnitt. Die Vanilla-Variante
behält ihren bisherigen Ablauf.

Version mit `tools/release/release_version.py patch` auf `3.1.13-alpha` erhöht.
So bleiben der neue Kandidat und die älteren, bereits unterschiedlich gebauten
Jars mit dem Namen 3.1.12 unterscheidbar.

## Verifikation

### Build und Unit-/Wiring-Tests

```powershell
.\gradlew.bat :common:test :fabric:build :neoforge:build
python tools/release/check_jar.py fabric/build/libs/fabrolia-alpha_3.1.13.jar
git diff --check
```

Gesamtbuild erfolgreich. JUnit-XML: **1519 erfasste Tests, 1511 bestanden,
8 optionale übersprungen, 0 Fehler/Fehlschläge**. Die übersprungenen Checks
benötigen externe Mod-Artefakte oder einen vollständigen Mixin-Export.
Die neue Encounter-Order-Regression ist ausgeführt und grün.
Fabric-Archivprüfung erfolgreich: Java 25, Version, Klassen und Mixins geprüft.
Der paketierte Player-Tick-Verifier meldet 910 bytegleiche geprüfte Dateien.
`git diff --check` meldet keine Whitespace-Fehler.

Logs: `build/codex-validation-2026-10-25/build-final.log` und `check-jar.log`.
Tests: `common/build/test-results/test/`, HTML unter `common/build/reports/tests/test/`.
Nach den Änderungen an AGENTS.md erneut `:common:test` ausgeführt: dieselben
1519/1511/8 Tests, weiterhin 0 Fehler; Log `test-post-docs.log`.
Die kleinen Rohreports und Zusammenfassungen sind zusätzlich dauerhaft im
[evidence-Verzeichnis](evidence/) dieses Reviews abgelegt.

### Laufzeit und Messung des fertigen Kandidaten

Fabric und NeoForge bestehen jeweils **42 Funktions-Checks**: beide tatsächlich
getrennten Scheduler-Modi, vier Protokoll-Clients in drei Gebieten, Befehle,
Insel-/Nether-Teleports, Stop mit Save, Neustart und erhaltene Entities.
Beide Prozesse beenden sich sauber; keine Crashreports. Die Reports liegen in
`build/codex-validation-2026-10-25/functest-{fabric,neoforge}-report.json`.

**Die Fabric-Logs sind dabei nicht fehlerfrei.** Im synchronen Abschnitt um
02:52:29 CEST meldet `healRegion` einmal `RETAINED CLAIM` und behauptet eine echte
Retention. Das ist ein offener Diagnosebefund, der durch die 42 bestandenen
Funktions-Checks nicht erledigt ist. Die ausgewiesene Claim-Generation 552
steht neben einer bereits freigegebenen Generation 553; die Werte sind keine
kohärente Aufnahme. `healRegion` validiert nach dem Publikationslesen nur das
Threadobjekt erneut, nicht die Generation (ABA möglich). Außerdem verwendet
der geloggte Erwerbspfad `stealOne` auch Worker-Threads, ohne deren
`currentRegion` wie der primäre Worker-Pfad zu publizieren. Der archivierte
Stack zeigt aktive Queue-Arbeit in Vanilla-`randomTick`. Daraus ist kein
verlorener Claim nachgewiesen; die definitive Diagnose ist nicht gerechtfertigt.

Ähnliche Meldungen stehen bereits im alten 3.1.10-Lauf
`../fabrolia-release/runs/st-neo-sync-fix1/logs/latest.log`. Dieser Befund
entstand also nicht erst mit dem aktuellen Cleanup. Eine Änderung des
Worker-Kontexts nur zur Beseitigung der Meldung könnte geschachtelte Claims
beeinflussen und wurde hier nicht vorgenommen. Snapshot und Forensik benötigen
ein eigenes Review. Originalauszug:
`build/codex-validation-2026-10-25/fabric-retained-claim.txt`.

NeoForge zeigt keinen solchen Scheduler-Befund. Seine bereits in älteren Läufen
vorhandenen Windows-Appender-/Native-Transport-Meldungen sind in den Rohreports
aufbewahrt; sie sind keine neue Gameplay-Ausnahme dieses Kandidaten.

Die historischen Mobcap-Probes laufen auch nach Anpassung des entfernten
Versionszählers gegen den aktuellen Build. Sie simulieren ausdrücklich die
alten Operationen; ihre alten Fehlerausgaben sind deshalb erwartete
Negativkontrollen und keine Reproduktion eines Fehlers im reparierten Pfad.

Der erste Kandidatenlauf `mobs-3113-codex` blieb nach Schritt 3 stehen: kein
fertiges `bench.json`, JFR-Datei leer, Serverprozess inzwischen beendet.
Dieser Lauf wird nicht ausgewertet und wurde nicht überschrieben.
Für den Neustart war weniger RAM frei als in der Nacht. Deshalb wurde ein
frisches Baseline-/Kandidatenpaar mit jeweils **8 GB Heap** gemessen; alle
übrigen Szenarioparameter blieben gleich. Dieser neue Vergleich ist getrennt
von den historischen 16-GB-Läufen zu lesen.

**Fertiger Vergleich:** `mobs-3112-review8` gegen `mobs-3113-review8`, jeweils
acht Clients, 3840 gespawnte Mobs, 20 Schritte à 30 s, 60 s Warm-up, 30 s Tail.
Fabric API, Lithium und C2ME sind in beiden Runs bytegleich. Beide Runs enden
mit Status `ok`, Exit-Code 0, ohne Clientfehler und ohne ERROR/FATAL im
Serverlog. Die Endzählung aller Entities beträgt 4540 bzw. 4528; identische
Eingaben ergeben hier keinen bitidentischen Weltzustand.

Auswertung jeweils **60 <= t < 690 Sekunden**, 630 Samples:

| Messwert | Baseline 3.1.12 | Kandidat 3.1.13 |
| --- | ---: | ---: |
| Prozess-CPU, Anteil aller 16 logischen CPUs | 10,479 % | 8,494 % |
| Durchschnittliche Client-Uhr-TPS | 20,003 | 20,008 |
| Kleinster 1-s-TPS-Mittelwert über 8 Clients | 18,00 | 19,25 |
| Kleinster 1-s-TPS-Wert eines Clients | 16 | 18 |
| Clientfehler | 0 | 0 |

Die Prozess-CPU sinkt in diesem einzelnen frischen A/B-Vergleich um
**18,94 %** bei ungefähr gleichen 20 TPS. Das belegt einen lokalen Gewinn des
gesamten Kandidaten unter dieser Last, keine allgemeine Kapazitätszusage.
Die Minima sind verrauschte einsekündige Paketmessungen.

Frames und Allokation aus den **vollständigen neuen JFRs**, einschließlich
Boot/Stop, nicht nur aus dem Tabellenfenster:

| Profilwert | Baseline | Kandidat |
| --- | ---: | ---: |
| Worker: `runIslandScheduled`, inklusive Samples | 1953 | 1920 |
| Main: `ThreadLocalMap.getEntryAfterMiss`, Self-Samples | 126 | 40 |
| Main: `ownerSafeIncrementalSave`, inklusive Samples | 692 | 497 |
| Main: geschätztes Allokationsgewicht, MB | 19150,7 | 14089,6 |

Das Allokationsgewicht sinkt um rund 26,4 %. Die historische Abnahme der
Scheduler-Samples um 87 % wird im neuen 8-GB-Lauf dagegen nicht reproduziert;
hier ist dieser inklusive Frame nahezu gleich. Samplezahlen sind keine
Millisekunden. Der Gesamtgewinn lässt sich aus diesen Aufzeichnungen nicht
belastbar auf einzelne Optimierungen verteilen.

Nachrechenbare Zusammenfassung: [final-bench-summary.json](evidence/final-bench-summary.json).
[validation-summary.json](evidence/validation-summary.json) verbindet Testzahlen,
Artefakte und Messung. [Befehle und Laufzeiten](evidence/benchmark-pair-command.json),
komprimierte `bench.json`-Rohdaten, Auswertungsskript und vollständige JFR-
Textreports liegen ebenfalls unter [evidence](evidence/).
Die zwei JFR-Binärdateien werden mit SHA-Manifest in
`build/codex-validation-2026-10-25/final-validation-evidence.zip` gesichert.

Reproduktion aus der Repositorywurzel (vorhandene Run-Verzeichnisse):

```powershell
python docs/reviews/2026-10-25/folia-performance/evidence/analyze_bench.py --names mobs-3112-review8 mobs-3113-review8 --runs "../fabrolia-release/runs" --output build/codex-validation-2026-10-25/recomputed-final-bench-summary.json
```

### Bereits vorhandener Paket-1-Vergleich

Ein lokaler sequentieller Lauf pro Jar, jeweils acht echte Protokoll-Clients,
Lithium + C2ME, 16-GB-Heap, 60 s Warm-up, 20 Schritte à 30 s und 24 neue Mobs pro
Spieler/Schritt, anschließend 30 s Tail. Insgesamt jeweils 3840 gespawnte Mobs.
Beide Läufe: Status `ok`, Exit-Code 0, keine Clientfehler.

Auswertung desselben Fensters **60 <= t < 690 Sekunden**:

| Messwert | 3.1.12-Baseline | Paket 1 vor Reihenfolgefix |
| --- | ---: | ---: |
| Prozess-CPU, Anteil aller 16 logischen CPUs | 12,393 % | 10,331 % |
| Durchschnittliche Client-Uhr-TPS | 19,964 | 20,006 |
| Kleinster 1-s-TPS-Mittelwert über 8 Clients | 11,25 | 18,75 |
| Kleinster 1-s-TPS-Wert eines Clients | 10 | 18 |

Im einmaligen Vergleich sank die Prozess-CPU um **16,64 %**. Minima aus
einsekündigen Netzwerk-Uhrmessungen sind verrauscht und keine verlässliche
untere TPS-Garantie. Über die vollständigen historischen JFR-Aufzeichnungen
(ohne Beschränkung auf das Tabellenfenster): inklusive `runIslandScheduled`-Samples
10872 -> 1439; geschätzte Main-Allokation 19203,5 -> 14063,3 MB.
Das sind Profiling-Indizien, keine unabhängigen addierbaren Zeitgewinne.
Die frühere gruppenweise `ThreadCPULoad`-Umrechnung war falsch normalisiert;
deren Aussagen „Worker -20 % / Main -25 %“ werden hier nicht übernommen.

Die zwei älteren Jars heißen beide `fabrolia-alpha_3.1.12.jar`:

| Build | SHA-256 |
| --- | --- |
| Baseline | `d5477efcf1e28b01eb82194453d7fb4d29613dace348ddffd70d541d8adab685` |
| Paket 1 | `6fd69afcae7d31563ae01f197638b7da992d6a8ed22f380e39d8fee910174905` |

Reproduzierbare historische Auswertung:
`build/codex-validation-2026-10-25/analyze_historical_bench.py`.
Daneben liegen `historical-bench-summary.json`, ein SHA-Manifest und
`historical-bench-evidence.zip` mit Original-JFRs, Rohdaten, Configs und Analyzern.
Die ursprünglichen Runs liegen außerhalb des Repos unter
`../fabrolia-release/runs/mobs-3112-jfr/` und `mobs-b1-jfr/`.

## Artefakte

| Loader | Pfad | SHA-256 |
| --- | --- | --- |
| Fabric | `fabric/build/libs/fabrolia-alpha_3.1.13.jar` | `2f8096a584d697b38948cb533572152aa8b1f5c33aa0bcc8b9c67de3e5c9bfdb` |
| NeoForge | `neoforge/build/libs/fabrolia-neoforge-alpha_3.1.13.jar` | `ba6c9793607b7b633e697fbd74471f28ac094935cf7e4fa387fcca2d8a2dfec7` |

Das sind Review-Kandidaten. Release-Verzeichnis, `prod/current` und Downloads
wurden in dieser Fortsetzung nicht aktualisiert.

## Grenzen und noch erforderliche Arbeit

- Der Produktionsstand mit 40 Clients ist lokal nicht vorhanden: `stand/`,
  Produktionsvorlage, Modset, Commands und Datapacks fehlen. Der Stand ist
  Linux-orientiert; seine Pipe-/`select()`-Clientpfade sind auf Windows nicht
  direkt lauffähig. Lokale Windows-Tests ersetzen diesen Nachweis nicht.
- Die in `docs/USER-REQUIREMENTS.md` verlangte Regression mit 100 Bots und
  stockenden unabhängigen Inseln wurde hier nicht ausgeführt. Eine allgemeine
  Skalierungs- oder Vanilla-Paritätszusage wird daraus nicht abgeleitet.
- Der ursprüngliche Tunnelbohrer-Mobcap-Vorfall braucht weiterhin den Vergleich
  in der Originalwelt. Die früher gefundenen Codefehler allein beweisen dessen
  Ursache nicht.
- Die größeren Main-Wartepfade bestehen weiter: Main-Pakete, überfällige
  Tracking-Publikationen und globale Fences können auf laufende Inseln warten.
  Dieser Kandidat beseitigt deren Architektur nicht.
- Folias CPU-Budget ist ein weiterer Konfigurationskandidat. Die Zahl der
  Region-Worker blind zu reduzieren wäre ohne Messung unter dem konkreten
  C2ME-/GC-/Netty-Lastmix nicht begründet; hier wurde sie nicht geändert.
- Ausstehend: kompletter Modpack-Mixin-Export sowie `verify` und passende
  `perf`-A/B-Läufe auf dem Stand, insbesondere dichte Redstone-/Fluid-Szenarien,
  Chunkload, Split/Merge, Dimensionswechsel und Speichern.
- Die Claim-Diagnose im synchronen Fabric-Lauf ist offen, siehe oben und
  [unabhängiger Review](independent-review.md). Ein bestandener Funktionstest
  darf diese Logmeldung nicht als gelöst erscheinen lassen.

## Konkrete Prüfpunkte für das Review

1. Den Gesamt-Diff gegen HEAD nach den oben genannten Schichten lesen; ältere
   NeoForge-/Mobcap-Änderungen nicht diesem Cleanup zuschreiben.
2. Key-Gruppenindex: sämtliche Mutationswege, Segment-/CHM-Lockreihenfolge,
   Snapshot-Grenzen bei gleichzeitigem Scheduling und Zell-0/negative Keys.
3. Discovery: alte Reihenfolge auf allen drei Pfaden, Ties geladener Ticks und
   unveränderte Zeit-/Besitz-/Frontier-Prüfungen. Die Sortierkosten bei dicht
   belegten Zellen sind auf dem Stand zu vergleichen.
4. Autosave: Vorfilter gegen den tatsächlich zusammengesetzten Minecraft-/C2ME-
   Bytecode prüfen; keine Accessibility-/Cooldown-/Save-Nebenwirkung auslassen.
5. Census: frühere Unerreichbarkeit des entfernten Pfads nachvollziehen;
   Audit bei deaktiviertem Parallel-Spawning, First-wins-Fan-in und Arena-
   Freigabe/Java-Shadow prüfen.
6. ThreadLocal-Scopes: alle Exit- und Nested-Scope-Pfade, insbesondere Throwable-
   und Reentry-Pfade; leere Slot-Erhaltung darf keine Context-Referenz halten.
7. Testergebnisse und Rohdaten zu den exakten SHA-256-Jars zuordnen. Die lokale
   Messung ist ein Hinweis auf den Gewinn, keine Produktionsfreigabe.
8. Den synchronen `RETAINED CLAIM`-Befund prüfen: Claim-Generation/ABA sowie die
   unzulässige Gleichsetzung von `currentRegion` und allen geschachtelten
   `stealOne`-Claims. Ownership nicht ändern, nur um die Meldung zu vermeiden.

Zusätzlicher unabhängiger Source-Review:
[independent-review.md](independent-review.md).

## Nachtrag: Gegenprüfung und Claim-Forensik

Gegengeprüft ohne Abweichung: Unerreichbarkeit des entfernten Census-Pfads,
Sortierfix (der neue Test schlägt ohne Sortierung in allen drei Zellen fehl),
Mutationswege und Lockreihenfolge des Gruppenindex, ThreadLocal-Initializer,
CPU-Werte aus den Rohdaten (−18,94 %) und Jar-Prüfsummen der Messläufe.
Prüfpunkt 4: Der Autosave-Vorfilter entspricht dem 26.3-Bytecode von
`saveChunkIfNeeded`; C2ME 0.4.2 (`enhanced_autosave`) ruft die Methode nur auf,
Lithium 0.26.2 greift nicht ein.

Prüfpunkt 8 ist behoben, und zwar nur in der Diagnose. Der gemeldete Claim war
kein echter Retained Claim: `stealReadyEntryBody` veröffentlicht nie
`currentRegion`, und der Stack zeigt dessen `r.queue().drain(...)`. Zudem
erhöhte nur ein gestempelter Claim `claimGen`, sodass ungestempelte Claims und
ein erneut claimender Thread dieselbe Generation trugen. Jetzt erhält jede
Akquisition eine eigene `claimSeq`. Ein gestempelter Claim ohne Release-Stempel
gilt als „im Body“. Gemeldet wird nur eine Akquisition, die über drei
Sichtungen und mindestens 1 s außerhalb jedes Bodys gehalten wird, mit den
Werten eines validierten Snapshots (`Region.snapshotClaim`). Claims, Ownership
und `currentRegion` sind unverändert. Neuer Test: `RegionClaimLedgerTest`.

Nach dem Fix: 1523 erfasste Tests, 1515 bestanden, 8 optional übersprungen,
0 Fehler; beide Loader bauen, `check_jar.py` besteht
(Log `build/codex-validation-2026-10-25/build-claim-forensics-fix.log`).
Funktionstests und Benchmark wurden mit dem Fix **nicht** erneut ausgeführt.
Alle Laufzeitwerte und die Artefakt-Tabelle oben gelten für den Build davor;
diese Jars liegen unter `build/codex-validation-2026-10-25/measured-jars/`.

| Loader | Build mit Diagnose-Fix, SHA-256 |
| --- | --- |
| Fabric | `fb10cd690d46587055b604cdf405b0de7cf8916a1468b60b2eff7c631037c28e` |
| NeoForge | `39812350c5a9d997a9ebfe8c96ca58567ad99a29ef070f99fcfbfdd3096579b2` |
