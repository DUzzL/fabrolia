# Globale MSPT-Schwankungen am 2026-10-10

Die Schwankungen und der TPS-Einbruch sind belegt. Die neueste Aufnahme enthält
jetzt alle Threads mit 4-ms-Intervall. Sie belegt regelmäßige Autosave-Kosten,
häufige Young-GCs und erhebliche Fabrolia-Synchronisierung, besonders bei der
Portalvorbereitung. Der ursprüngliche mehrminütige Einbruch auf ungefähr
7–10 TPS ist in den gespeicherten Stapelfenstern weiterhin nicht enthalten.
Keine Laufzeitänderung aufgrund dieser Aufnahmen.

## Aufnahme aller Threads: Speicher-Spitzen und Portal-Pausepfad

Quelle: <https://spark.lucko.me/rOM0K82a4R>, **20:18:44–20:20:46 CEST**,
etwa 122 Sekunden, `ALL`, 4 ms, `ASYNC / EXECUTION`, Wall-Time-Sampling.
100 gespeicherte Thread-Gruppen, darunter **17 Region-Worker**. Zwei erhaltene
Minutenfenster mit zusammen 2.367 Ticks; Gesamtmetadaten: 2.364 Ticks.
Alle Werte je Main-Tick verwenden die Fensterzahl. Ein Wall-Time-Profil
enthält auch wartende Threads; inklusive Aufrufe überschneiden sich.

Während der Aufnahme liegen die 10-Sekunden-MSPT-Mittel zwischen **22,3 und
32,9 ms**. Ein TPS-Messpunkt fällt auf **17,83**; am Ende stehen wieder etwa
20 TPS. Das erfasst kurze Schwankungen, aber nicht den ursprünglichen starken
Einbruch. Die beiden Minuten unterscheiden sich vor allem in Arbeit außerhalb
des gemessenen Tickkörpers:

| Gesampelter Main-Pfad, inklusive ms je Main-Tick | Erstes Fenster | Zweites Fenster |
| --- | ---: | ---: |
| `MinecraftServer.tickServer` | 25,35 | 25,62 |
| Portalvorbereitung `serviceMain` | 7,10 | 5,61 |
| Davon `pauseExactTurnAndAwait` | 6,65 | 5,26 |
| Spielerpakete `drainMainIngress` | 3,89 | 2,78 |
| C2ME inkrementelles Speichern | 3,23 | 2,52 |
| VMP-Tracking mit Fabrolia-Lock | 6,93 | 7,25 |

Das Profil ordnet Main-Wartezeit konkreten Pfaden zu. Es enthält keine
Zuordnung jeder wartenden Main-Anfrage zum jeweiligen Insel-ID-/Worker-Tick.
Eine bestimmte Farm oder Insel ist deshalb damit nicht als Auslöser benannt.

### Regelmäßige hohe MSPT-Maxima

Die MSPT-Messpunkte um **20:19:19 und 20:20:19** enthalten Maxima von **513,8
und 521,0 ms**. In beiden Stapelfenstern sind jeweils **416 ms gesampelte
Zeit unter `MinecraftServer.autoSave()`** enthalten. Darin liegen
`PlayerList.saveAll()` sowie das vollständige `saveAllChunks()`; der
letztgenannte Aufruf enthält im ersten/zweiten Fenster 284/320 ms.

Damit ist Autosave als wiederkehrender großer Kostenblock tatsächlich
erfasst. Abstand und Größenordnung passen zu diesen beiden Spitzen.
Die Minutenaggregation liefert jedoch keinen eigenen Stack pro einzelnem
Tick: Eine millisekundengenaue Zuordnung jeder Spitze ausschließlich zum
Autosave wäre stärker als die Daten. Auf zwei Minuten gemittelt kostet
`autoSave` nur 0,35 ms je Tick und verschwindet daher leicht in einer
prozentualen Gesamtansicht. Inkrementelles C2ME-Speichern kommt separat mit
2,87 ms je Tick vor, überwiegend zwischen den Tickkörpern.

### Konkreter Fabrolia-Optimierungskandidat

Die Portalvorbereitung kostet im Mittel **6,34 ms je Main-Tick**. Davon
entfallen **5,95 ms** auf `pauseExactTurnAndAwait()`, während die eigentlichen
`Request.prepareMain()`-Körper nur **0,37 ms** beanspruchen. Der Pausepfad
enthält unter anderem 3,25 ms `Object.wait`, 0,27 ms Parken und 2,29 ms als
Ausführung klassifizierte Zeit; letzteres umfasst Worker-Wecken und erlaubte
Main-Fortschrittsarbeit, nicht nur die Prüfung der Pause.

Der Code bestätigt den bereits in der Main-only-Aufnahme gefundenen
Mechanismus: `serviceMain()` fordert bei `needsMain` eine Pause des gesamten
Worker-Pools an. Unfertige Chunk-Futures und POI-Veröffentlichungen setzen
`needsMain` wieder. Ein Request kann daher in folgenden Durchläufen erneut
den globalen Pausepfad auslösen. Das ist ein konkreter Ansatzpunkt, um
unnötige erneute Pausen während des Wartens auf bereits angeforderte Daten
zu vermeiden. Anzahl und Anlass jeder einzelnen Wiederholung lassen sich
aus dem aggregierten Sampling nicht zählen.

Diese Vorbereitung läuft am Post-Tick-Rand, nach `tickServer()`. Ebenso
liegen Teile der Spielerpaketbearbeitung zwischen Tickkörpern. Die globale
MSPT-Zahl bildet deshalb nicht die ganze Arbeit ab, die den nächsten Tick
verzögern kann. Die tatsächliche Eigentumsgrenze muss erhalten bleiben;
eine Optimierung müsste bloß ausstehende Daten von neuer notwendiger
Main-Arbeit unterscheiden, ohne Portalmechanik oder Reihenfolge zu ändern.

**Umgesetzt (Nachtrag 10.10.2026, abends).** Die Ursache der Wiederholung
liegt in `Request.require()`: Worker rufen bei jedem Transferversuch
`footprint()` auf; solange die Zielzelle noch keiner Insel gehört, fordert
`promote()` dort erneut denselben FULL-Anker an. `require()` setzte dafür
bedingungslos `needsMain`, auch wenn Ticket und Lade-Future längst existierten.
Damit pausierte Main bis zum Abschluss des Ladens in jedem Tick den gesamten
Worker-Pool, ohne dass `prepareMain()` etwas Neues tun konnte. Jetzt meldet eine
unveränderte Anforderung mit laufendem Ladevorgang nur noch dieses Warten
(`awaitLoad`); `serviceMain()` pausiert erst, wenn ein solcher Ladevorgang
fertig ist oder echte neue Main-Arbeit vorliegt (neue Anforderung,
Veröffentlichung, Grenzänderung, Generierung). Tickets, Ladereihenfolge und
Transferablauf bleiben unverändert. Der Produktionseffekt ist erst mit einer
neuen Aufnahme bezifferbar.

Weitere Main-Kosten: Tracking inklusive 7,09 ms, darin 1,33 ms Lock-Warten
und 1,74 ms Fanout-Warten; `mainClaim` inklusive 3,85 ms, davon 3,30 ms
Parken, vorwiegend bei Spielerzugriffen; Topologie-Quiesce inklusive 1,42 ms,
davon 1,26 ms Parken. Diese Werte überlappen teilweise mit den jeweiligen
aufrufenden Phasen und dürfen nicht pauschal addiert werden.

### Was die Worker zeigen

Über alle 17 Worker summiert stehen 977,9 Sekunden als Ausführung
klassifizierter Samples, ungefähr 8,1 gleichzeitig laufenden Threads im
120-Sekunden-Fenster entsprechend. Dazu kommen 785,0 Sekunden Parken in
`workerLoop()` und 173,7 Sekunden Parken an der Entity-Barriere.
`awaitEntityBarrier()` enthält auch aktive Hilfe beim Entity-Ticken;
sein inklusiver Wert ist daher keine reine Wartezeit. Diese Summen sind
weder lokale Insel-MSPT noch eine so lange Wartezeit des Serverthreads.
Eine durchgehend voll ausgelastete gesamte Worker-Gruppe ist nicht erfasst.
Parken kann sowohl fehlende Arbeit als auch durch Phasen oder globale
Pausen noch nicht zulässige Arbeit bedeuten; eine einzelne langsame Insel
oder eine Freigabeabhängigkeit bleibt damit möglich.

Ein erheblicher Worker-Kostenpunkt ist Fabrolias
`Long2ObjectConcurrentHashMap.get()`: **79,6 Sekunden eigene Ausführungszeit**,
etwa 8,1 % der Worker-Ausführung. Die Aufrufer betreffen vor allem
Entity-/Chunk-Bereitschaft für Blockentities, regionale Eigentumsabfragen
für Portal-Footprints und Entity-Section-Suche. Das ist allgemeiner
Verwaltungsaufwand, kein Beweis für eine konkrete defekte Farm. Die aktuelle
Map mischt und boxt Long-Schlüssel für `ConcurrentHashMap`; welches Stück
die Allokation verursacht, ist ohne Allokationsprofil nicht bestimmt.

BlueMap hat zusätzlich etwa 87,4 Sekunden Render-Ausführung und 15,1 Sekunden
in seinem ForkJoin-Pool. C2ME-Worker laufen ungefähr 47,8 Sekunden. Das ist
messbare CPU-Mitbenutzung; eine dominante Main-Blockade durch BlueMap oder
Chunk-Generation ist in dieser Aufnahme nicht erfasst.

Die Entity-Stapel enthalten noch `runOwedEntityPhase → tickOwned →
tickNonPassenger`. Der neue Pfad `tickIslandEntitiesInVanillaOrder()` aus
dem fertiggestellten TNT-Fix fehlt. Zusammen mit etwa 26,3 Stunden JVM-Laufzeit
passt die Aufnahme weiterhin zum bisherigen Laufzeitstand, nicht zum
neuen TNT-Fix. Dessen bereits geprüfter Standvergleich ist in
[TNT-FIX-2026-10-10.md](TNT-FIX-2026-10-10.md) dokumentiert; daraus folgt
keine Zusage, dass er diese Produktionsschwankungen beseitigt.

### GC und Node während dieser Aufnahme

35 Young-GCs in etwa 122 Sekunden, zusammen ungefähr **2,26 Sekunden**,
durchschnittlich **64,5 ms**, also etwa alle **3,5 Sekunden**. Keine Old-/Full-GCs.
Das erklärt einen wiederkehrenden zusätzlichen kurzen Pausenanteil und passt
zur Allokation um 1,9 GiB/s. Acht Concurrent-GC-Ereignisse werden nicht als
zusätzliche Stop-the-World-Pausen addiert.

Die neu abgerufenen Node-Minutenpunkte um 20:18/20:19/20:20 CEST nennen
75,31/69,46/62,64 % CPU, 0,08/0,46/0,14 % I/O-Wait und jeweils 0 % Steal.
Sie belegen in dieser Aufnahme keinen dominanten Storage-Engpass.
CPU-Kontention oder Quota-Drosselung lassen sich damit nicht ausschließen;
ein Hoster als eindeutige Ursache ist ebenso wenig belegt.

Messnachweis: [allthread-evidence.json](reviews/2026-10-10/mspt/allthread-evidence.json),
Node-Zeitreihen: [node-allthread-series.json](reviews/2026-10-10/mspt/node-allthread-series.json).

## Nachgereichte Aufnahme: konkrete Main-Kosten

Quelle: <https://spark.lucko.me/dbcRnmT3Mo>, erfasst am 10.10.2026
**20:09:58–20:10:49 CEST**, etwa 51 Sekunden, Intervall **10 ms**, Auswahl
`SPECIFIC [168]`, ein gespeicherter Thread: `Server thread`.
Das sind weder alle Threads noch das angefragte 4-ms-/120-Sekunden-Profil.
Ein Profilfenster mit 1.019 gezählten Ticks; die Gesamtmetadaten nennen 1.018.
Die Normierung unten verwendet die Fensterzahl.

Der Server erreicht im erfassten Zeitraum ungefähr **20 TPS**. Die während
der Aufnahme gespeicherten 10-Sekunden-MSPT-Punkte liegen zwischen **23,8 und
34,1 ms im Mittel**, ihre Maxima zwischen 117,6 und 198,8 ms. Der in der
abschließenden Ein-Minuten-Statistik sichtbare 557,1-ms-Tick liegt dagegen
im 20:09:56-Messpunkt **vor** dem Beginn der Stapelaufnahme. Auch für diesen
einzelnen Tick enthält dieser Link somit keinen nachgewiesenen Stapel.
Ein Vergleich langsamer und schneller Stapelfenster ist mit einem einzigen
51-Sekunden-Fenster nicht möglich.

### Belegte Aufrufe und Wartepfade

Inklusive Werte umfassen Arbeit und Wartezeit. Unteraufrufe überschneiden
sich und dürfen nicht zusätzlich zum jeweiligen Gesamtaufruf summiert werden.

| Pfad auf Main | Inklusive Zeit je Main-Tick | Erfasster Warteanteil |
| --- | ---: | --- |
| VMP-Entity-Tracking mit Fabrolia-Synchronisierung | 8,55 ms | 1,59 ms Lock-Warten und 1,93 ms Fanout-Warten, beide im Gesamtwert enthalten |
| `IslandCommands.mainClaim` | 5,58 ms | 4,71 ms Parken |
| `NetherPortalPreparation.serviceMain` | 3,94 ms | Pausepfad 3,68 ms insgesamt; darin 2,03 ms `Object.wait` |
| `RegionizedServer.quiesceAllIslandsInner` | 1,27 ms | 1,15 ms Parken, vor allem beim Topologie-Neuaufbau |
| `IslandCommands.quiesceAllExcept` | 1,23 ms | 1,18 ms Parken |
| C2ME inkrementelles Chunk-Speichern | 2,90 ms | kleiner Warteanteil; überwiegend zusätzliche Main-Arbeit zwischen Ticks |

Der eigentliche `MinecraftServer.tickServer`-Aufruf liegt bei **28,27 ms je
Tick** an gesampelter inklusiver Zeit. Main verbringt über den gesamten
Serverloop zusätzlich Zeit in Portalvorbereitung und Spielerpaketen außerhalb
dieses Aufrufs. Das erklärt den Mechanismus, weshalb niedrigerer TPS-Wert
und vergleichsweise moderate globale MSPT gleichzeitig auftreten können.
Die normale Ruhezeit in `waitForTasks` beträgt 9,25 ms je Tick und wird nicht
als Lagursache gezählt.

**Spielerzugriff koppelt Main an die Insel.** Der größte Teil des Claim-Wartens
kommt aus `AsyncIslandPlayerTicks.runMainListenerTick` und
`AsyncIslandPlayerTicks.drainMainIngress`. Im zweiten Fall liegt die Arbeit
zum Teil in `MinecraftServer.waitUntilNextTick/runAllTasks`, also zwischen den
gemessenen Tickkörpern. Wird die Spielerinsel langsam, kann Main bei
Spieleraktionen auf deren vollständige Freigabe warten. Die Worker-Stapel
fehlen, daher ist weder eine bestimmte Farm noch eine konkrete Insel als
Verursacher dieses Wartens identifiziert.

**Portalvorbereitung hat einen konkreten Kostenpunkt im Code.**
`NetherPortalPreparation.serviceMain()` nimmt bei `needsMain` eine
`pauseMainPreparationTurn()` des gesamten Worker-Pools. In
`Request.prepareMain()` setzen noch unfertige Chunk-Futures beziehungsweise
POI-Snapshots wieder `needsMain=true`. Somit kann in folgenden Main-Ticks
erneut die globale Pause angefordert werden, obwohl zunächst noch auf das
Laden gewartet werden muss. Der erfasste eigentliche `prepareMain()`-Körper
kostet nur **0,24 ms je Tick**, während der Pausepfad **3,68 ms** kostet.
Das ist ein belegter teurer Pfad und ein konkreter Optimierungskandidat,
aber kein Beweis, dass diese Wiederholung den gesamten früheren Einbruch
verursacht hat. Der Kommentar und die Implementierung des Pausepfads verlangen
eine echte Grenze ohne laufende Worker-Körper; die Sperre darf nicht einfach
entfernt werden. Eine mögliche Verbesserung müsste vor der Pause zwischen
neuer Main-Arbeit und bloß ausstehenden bereits angeforderten Futures
unterscheiden, ohne Portalbereitschaft, Reihenfolge oder Thread-Eigentum
zu verändern.

**Tracking ist ein weiterer klarer Schwerpunkt.** Main nimmt den
`NearbyEntityTrackingMixin.fabrolia$lockTick`-Lock und wartet anschließend bei
Snapshots und Viewer-Verarbeitung auf gestartete Fanout-Aufgaben. Der
Snapshot-Aufruf allein liegt bei 3,30 ms je Tick. Der Lockschutz und der
Abschluss des Viewer-Passes sichern die Konsistenz; diese Schritte zu
überspringen wäre keine gültige Optimierung. Welcher parallele Aufruf den
Lock hält oder einen Fanout verzögert, ist im Main-only-Profil nicht enthalten.

**GC trägt kurze Pausen bei.** Während der Aufnahme stehen 13 Young-GCs mit
zusammen 775 ms und durchschnittlich 59,6 ms im Bericht, ungefähr alle
3,9 Sekunden; keine Old-/Full-GCs. Das kann einzelne kurze Ruckler erklären,
ohne dass eine Speicherknappheit vorliegt.

Die zeitgleich erneut abgefragte Node zeigt um 20:09/20:10 CEST 71,23/75,17 %
CPU, 2,77/1,42 % I/O-Wait und 0 % Steal. Hohe Node-Last besteht somit auch
bei wieder stabilen TPS; aus Gesamtauslastung allein folgt keine eindeutige
Hoster-Ursache. Das beigefügte `latest.log` endet um 19:58:59 CEST und deckt
diese spätere Aufnahme nicht ab.

Messnachweis: [followup-evidence.json](reviews/2026-10-10/mspt/followup-evidence.json),
Node-Zeitreihen: [node-followup-series.json](reviews/2026-10-10/mspt/node-followup-series.json).

## Quellen und Zeitabgleich

- Spark: <https://spark.lucko.me/pEilsh6zeG>, heruntergeladen als
  `application/x-spark-sampler`; Start 17:41:45.244 UTC, Ende
  17:41:53.188 UTC, 76 Ticks, Thread-Auswahl `SPECIFIC [168]`, Live-Kanal
  `QGRYC4TIkk7RqMY`, 0 gespeicherte Threads. Beide Downloads, mit und ohne
  `?raw=1`, enthalten dieselben 311.465 Bytes.
- Log: `/Users/noahwill/Downloads/latest.log`, 38.779 Zeilen,
  00:00:00–13:58:59. Der Zeitversatz UTC−04:00 ist aus dem zeitnahen Posting
  des Spark-Links im Log um 13:42:17 und dessen UTC-Metadaten abgeleitet.
  13:34 im Log entspricht somit ungefähr 19:34 deutscher Sommerzeit.
- Node: <https://status.foliumhosting.net/report/uptime/de11b0a44a37eb18cdd6fc0ecb3300f2/>,
  Monitor `n-nyc-19`. Die öffentlich geladenen CPU/RAM-Zeitreihen wurden
  zusätzlich abgerufen, nicht nur die Uptime-Anzeige. Die Seite erklärt
  ausdrücklich UTC+03:00; ihre Diagramm-Zeitstempel enthalten diesen
  Darstellungsversatz. Für den Vergleich wurden 10.800.000 ms abgezogen.
  Die letzte Minutenmessung liegt danach in der tatsächlichen Abrufminute.
- Reproduzierbare, von Spieleridentitäten und Serverkonfiguration bereinigte
  Messwerte: [metrics-evidence.json](reviews/2026-10-10/mspt/metrics-evidence.json).

## Gemessene Veränderung

Arithmetische Mittel der verfügbaren Messpunkte, **keine** tickgewichteten
Gesamtmittel. Spark liefert ungefähr 10-Sekunden-Punkte, die Node Minutenpunkte.

| Messwert | 19:20–19:30 CEST | 19:34:30–19:41:54 CEST |
| --- | ---: | ---: |
| Globale MSPT, Mittel der Punktmittel | 26,1 ms | 49,9 ms |
| TPS, Mittel der Messpunkte | 19,95 | 13,81 |
| Niedrigster TPS-Messpunkt | 16,90 | 7,25 |
| Höchster MSPT-Punktmittelwert | 40,3 ms | 82,6 ms |
| Höchster erfasster einzelner Tick | 851,0 ms | 867,8 ms |
| Java-Prozess-CPU laut Spark | 53,5 % | 64,6 % |
| Node-CPU | 65,0 % | 79,0 % |
| Node-I/O-Wait | 0,16 % | 0,08 % |
| Node-Steal | 0 % | 0 % |
| Node-RAM | 69,2 % | 69,1 % |
| Entities | 12.970 | 13.039 |
| Geladene Chunks | 22.377 | 22.953 |
| Spieler | 32,1 | 34,3 |

Die Node erreicht um 19:42 CEST 83,42 % CPU. Das belegt hohe gleichzeitige
Hostlast, aber nicht, ob andere Kunden, dieser Server oder beides die Last
verursachen. Gesamtauslastung und Steal messen auch keine CPU-Quota-Drosselung
oder Auslastung des einzelnen Serverkerns. Spark meldet 19 verfügbare
Prozessoren; seine CPU-Prozente dürfen nicht einfach von den Node-Prozenten
abgezogen werden.

## Was der Log und der Code belegen

1. **Main wartet tatsächlich auf Inseln.** In der betroffenen Phase stehen
   unter anderem 180 ms Quiesce-Wartezeit im Overworld um 13:37:37,
   110 ms im Nether um 13:39:10 und 140 ms im Overworld um 13:39:39.
   `RegionizedServer.quiesceAllIslands()` misst diese Wartezeit selbst.
   Topologieänderungen nehmen diese Sperre in `rebuildIslandsIfDirty()`;
   Speichervorgänge nehmen ebenfalls Insel-Sperren, unter anderem in
   `MinecraftServerMixin.fabrolia$quiesceIslandsForServerSave()`.
   Deshalb können lange lokale Einheiten globale MSPT-Spitzen verursachen.
   Die Warnung unterscheidet Save und Topologie nicht. Sie beweist weder
   einen Deadlock noch den dominanten Aufruf des gesamten Einbruchs.
2. **Chunk-Auslieferung stockt.** Um 13:36:59 bleibt eine Anfrage 11.954 ms
   im Zustand REQUESTED, um 13:43:12 eine weitere 11.109 ms. Andere Chunks
   liegen trotz geladenem Zustand über zehn Sekunden in TRACKED.
   Das passt zu den zeitgleichen Beschwerden über den Nether-Highway.
   Ursache und Folge des Main-Lags lassen sich aus diesen Warnungen allein
   nicht trennen.
3. **Die LoadingChunkTracker-NPE ist ein Diagnosefehler.** Alle 1.407
   entsprechenden Logzeilen stammen aus dem Readiness-Bericht bereits
   zurückgehaltener geplanter Ticks. In
   `IslandScheduledTicks.describeChunkReadiness()` wird unter anderem
   `dm.getChunkLevel(chunkPos, false)` abgefragt; die komplette Zusatzabfrage
   fängt ihre Ausnahme und schreibt `readiness=probe-failed`.
   Das tatsächliche HOLD wurde davor entschieden, beispielsweise wegen
   einer offenen Schreibgrenze oder einer verweigerten exakten
   Chunk-Bereitschaftsprüfung. Die NPE verursacht dieses HOLD nicht, macht
   aber die Ursachenanzeige unbrauchbar. HOLDs treten schon ab 00:00 auf,
   nicht erst beim Einbruch. Kein `SCHEDULED-HOLD-STUCK` im gelieferten Log.
4. **C2ME-Unload-Retries laufen dauerhaft.** Der Zähler steigt im Log von
   etwa 2,95 auf 5,57 Millionen Wiederherstellungen. Auch unmittelbar vor
   und nach dem Einbruch laufen die Meldungen. Das ist ein Kandidat für
   zusätzliche Chunk-Verwaltung, kein neuer nachgewiesener Auslöser.
   Wiederherstellungen sind Wiederholungsversuche, nicht Millionen
   verschiedener Chunks oder verlorene Entities.

Die `Can't keep up`-Meldung um 13:40:56 nennt 19.662 ms Rückstand. Das ist
akkumulierter Zeitplanrückstand, **kein** nachgewiesener 19,7-Sekunden-Tick.
Bewegungswarnungen allein beweisen ebenfalls keine geänderte Bootsphysik.

## GC, Speicher und regelmäßige Spitzen

Die Allokationsrate liegt schon vor dem Einbruch bei rund 1,8 GB/s und steigt
in der verglichenen Lagphase auf etwa 2,0 GB/s. In den knapp acht Sekunden
der Aufnahme sind zwei Young-GCs mit zusammen etwa 105 ms erfasst,
keine Old-/Full-GCs. Das kann kurze zusätzliche Pausen erzeugen; eine
dominante GC-Ursache des mehrminütigen Einbruchs ist damit nicht belegt.
Der Heap liegt am Ende bei 13,0 GiB von maximal 30,6 GiB.

Einzelne hohe Maximalwerte kommen bereits bei stabilen TPS vor. Das häufig
annähernd minütliche Muster ist mit den bekannten minütlichen Autosaves
vereinbar. Ohne Aufrufstapel kann in dieser Aufnahme aber weder der
Speicheranteil noch dessen Sperrwartezeit beziffert werden.

## Für einen erneuten starken Einbruch

Die letzte Aufnahme erfüllt die Thread-/Intervallanforderung. Falls der
mehrminütige Einbruch erneut auftritt, dessen Zeitraum mit allen Threads
profilieren und die betroffenen Inseln dazu erfassen:

Alle Threads mit 4 ms Intervall für 120 Sekunden
profilieren und den **nach Abschluss** erzeugten Link verwenden:

```text
/spark profiler start --thread * --interval 4 --timeout 120
/fabrolia regions top verbose 100
```

Wenn bereits ein Profiler läuft, erzeugt `/spark profiler stop` dessen
abgeschlossenen Bericht. Bei einer vorherigen Auswahl nur des Serverthreads
ist danach eine neue Aufnahme aller Threads nötig.
Siehe [Spark-Befehlsdokumentation](https://spark.lucko.me/docs/Command-Usage).

Damit können die hier belegten Pfade speziell während des starken Einbruchs
verglichen und um die Zuordnung zu lokalen Inseln ergänzt werden. Keine Sperre
entfernen oder Tick-Arbeit überspringen, um den Messwert künstlich zu senken.

Die JVM lief beim gelieferten Bericht bereits 25,68 Stunden. Das beweist
keinen genauen Jar-Hash, passt aber nicht zu einem Neustart mit dem erst
am 10.10. fertiggestellten TNT-Fix.
