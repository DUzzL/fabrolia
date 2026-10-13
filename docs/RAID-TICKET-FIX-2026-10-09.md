# Raid-Ticket-Fix und Farm-Parität, 09.10.2026

Die vorangegangene [Codeprüfung](RAID-FARM-REVIEW-2026-10-09.md) hat zusätzliche
Raid-Regeln und unzureichende Abdeckung der damaligen Raid-Tickets gefunden.
Die finale Änderung entfernt diese Abweichungen. Maßstab ist Minecraft 26.3:
Farmen, die in dieser Vanilla-Version nicht funktionieren, werden durch
Fabrolia nicht wieder aktiviert.

## Finale Änderung: Vanilla-Parität

Nach der Anforderung „nur Vanilla Parity“ bleibt die zuerst geprüfte
Ticket-Vergrößerung **nicht** im fertigen Mod. Entfernt wurden stattdessen:

- `RaidVillageDebounceMixin` und die zugehörige Hilfsklasse: keine zusätzlichen
  100 Raid-Ticks Schonfrist bei fehlendem Dorf. Vor der ersten Welle stoppt
  der Raid, danach verliert er, wie im echten 26.3-Code.
- `RaidRelocationColumnMixin`: keine zusätzliche ±8-Section-Suche. Die originale
  Suche im ±2-Section-Würfel bleibt erhalten. Die alten Kommentare über eine
  Vanilla-Raidphase nach Blockevents/Entities waren für 26.3 falsch;
  `ServerLevel.tick` und die Inselphase haben dieselbe Reihenfolge:
  Scheduled Ticks → Raid → Chunks → Blockevents → Entities.
- `RaidIslandMerge` und seine beiden Dispatch-Aufrufe: keine automatischen
  PORTAL-Tickets für Raids. Spieler-, echte Portal- und Forceload-Tickets
  bestimmen wieder, welche Positionen entity-ticken und wann der Raid ruht.
  ServerCore kann deshalb weiter die normale Spieler-Simulationsdistanz
  bestimmen; der Mod setzt sie nicht für Raid-Farmen außer Kraft.

Die produktive Topologie verwendet 8 × 8 Chunks pro Zelle und verbindet
populierte Zellen im Chebyshev-Radius 2. Der vollständige Spawnring mit
Zufallsversatz und ±10-Block-Ladeprüfung bleibt höchstens eine Zelle vom
Zentrum entfernt. Alle bereits geladenen beteiligten Zellen sind deshalb
auch mit leeren Zwischenräumen eine Insel. Zusätzliche Ladetickets sind für
diese Zusammenführung unnötig. Ownership, die Raid-Registry und einmaliges
Ticking je Besitzer-Einheit bleiben erhalten.

Ein Standort außerhalb der Vanilla-Simulationsdistanz wird nun absichtlich
nicht für die Farm nachgeladen. Ein höherer Ertrag unter solchen Bedingungen
wäre keine Vanilla-Parität. Alte PORTAL-Leases einer vorherigen Mod-Version
können noch bis zu ihrem normalen Ablauf wirken; es werden keine fremden
Portal-Tickets gelöscht. PORTAL ist in 26.3 persistent und seine Ablaufzählung
kann bis zur Speicherbereitschaft des Chunks pausieren.

## Validierung

`bash gradlew :common:test :fabric:build :neoforge:build` war erfolgreich:
1.398 bestandene Tests, 0 Fehler, 8 optionale Prüfungen übersprungen
(1.406 insgesamt). Beide Loader enthalten 881 byte-identische gemeinsame
Klassen und 95 byte-identische Klassen der eingebetteten API. Die fünf
entfernten Produktionsklassen sind in keinem der beiden Jars enthalten.

Der abschließende allgemeine Stand `raid-parity-verify-v3` mit 40 echten
Clients besteht alle 11 Checks. Er verwendet den fertigen Fabric-Jar mit
SHA256 `fe0eada020517241a437f6fb36166fa32f7db7078ff532fedcaeb34e57df82db`.
Der NeoForge-Jar hat SHA256
`e2c4ec30fc4dabe7786010f54da3b0d85cc7a678ef90158ded8c02189615fdc4`.
NeoForge ist gebaut und auf gleiche gemeinsame Klassen geprüft, aber nicht
in einem eigenen Live-Server getestet.

Die neuen Tests verwenden den echten 26.3-Code: sofortiger STOP/LOSS ohne
Dorf, keine Wiederbelebung nach späterer Dorf-Rückkehr, zulässige Verlagerung
am Vanilla-Suchrand und Ablehnung außerhalb des effektiven Dorf-Würfels.
Ein Wiring-Test prüft zusätzlich die aktive Mixin-Konfiguration und den
Dispatch-Bytecode, weil die Verhaltenstests Minecraft ohne Mixin-Transformation
aufrufen. Die Spawn-Suche respektiert die bestehenden Chunk-Level; alle
Chunk- und Zellrandpositionen des Spawnrings sind geometrisch geprüft.

### POI-Abfragen und Entity-Ladefortschritt

`PoiManagerDistanceMixin` lud bei jeder Dorf-Distanzabfrage zusätzlich die
angefragte POI-Section synchron. Der echte 26.3-Code führt dort nur ausstehende
Graph-Updates und den Distanz-Lookup aus. Der zusätzliche `getOrLoad`-Aufruf
ist entfernt, ebenso der dadurch unbenutzte `SectionStorageGetOrLoadAccessor`
und sein Konfigurationseintrag. Angenommene POI-Änderungen werden weiterhin
vor der Antwort angewendet. Tests prüfen die Sichtbarkeit von Anlage und
Entfernung in derselben Abfrage, die ausbleibende Ladung einer fehlenden
Spalte und die aktive Mixin-Verdrahtung.

Ein früher Farm-Stand hing bereits beim Login in
`ServerLevel.waitForEntities`, bevor die Farm lief. Vanilla verarbeitet in
dieser Warteschleife Entity-Ladeantworten, während der Welt-Tick pausiert.
Ein auf den nächsten Welt-Tick verschobener Chunk-Status konnte deshalb den
Ladeauftrag zurückhalten. `processPendingLoads` verarbeitet nun auch die
bereits vorhandenen, nicht blockierenden Status-Transaktionen unter dem
jeweiligen Chunk-Besitzer. Dabei werden keine Welt-, Entity- oder Insel-Ticks
ausgeführt. Ein Regressionstest stellt diese geschlossene Fortschrittsschleife
mit dem echten 26.3-Entity-Manager nach; ein Wiring-Test prüft den Hook.

Der Stackdump des beobachteten Hängers belegt die Wartestelle, nicht den Inhalt
der ausstehenden Status-Queue. Die Zuordnung dieses konkreten Hängers zum
nachgewiesenen Schleifenfehler bleibt daher eine begründete Vermutung.
Die betroffene Klasse war im alten und im ersten Raid-Paritäts-Jar identisch;
der Fehler wurde nicht durch das Entfernen der Raid-Sonderregeln eingeführt.

### Historischer Zwischenstand

Die ursprüngliche Prüfung bewies korrekt, dass FULL-Ticketradius 6 nur
ENTITY_TICKING-Radius 4 abdeckt und Radius 9 für den ganzen äußeren Ring
benötigt würde. Alte/neue Stand-Läufe `raid-ticket-old-v4` /
`raid-ticket-new-v4` bestätigten 0 bzw. 1 Welle bei Simulationsdistanz 2;
beide sauber beendet, keine Crashes. Der Zwischenstand bestand 1.403 Tests
und alle 11 allgemeinen Modpack-Checks mit 40 echten Clients.

Das ist ein Nachweis der zusätzlichen Ladefunktion, **kein Nachweis von
Vanilla-Parität**: Vanilla ohne solche Tickets scheitert am selben ungeladenen
Ring ebenfalls. Deshalb wurde diese Lösung mit der engeren Anforderung
verworfen. Ihre Artefakthashes und Messdaten sind zur Nachvollziehbarkeit in
[Zwischenstand](reviews/2026-10-09/raid-ticket-intermediate-validation.json)
und [Profilen](reviews/2026-10-09/raid-ticket-profiles.json) erhalten.

## Farm-Auswahl aus Originalquellen

Heruntergeladen wurden die vom Autor verlinkten Worlds, ohne Rotation oder
Rekonstruktion ihrer Farmen:

| Design | Quelle | World-SHA256 |
| --- | --- | --- |
| Easy Compact Raid Farm, Couronne6 | [Autor-Seite, 1.21.11+](https://funcube.websr.fr/easycompactraidfarm) | `fcb8ad2ebaf2daffc4f0b01e5aeb9ac7fe7eb223edda4e9e4d9e3408fdd8e050` |
| NashRaid V1 | [Original-Tutorial, 1.21.5–26.3+](https://www.youtube.com/watch?v=OAS8qN5P6C4) | `f0f72730bb289359f1b6f570c19e19946b809e2f529c849d2ca8db5177795a27` |
| NashRaid V2 | [Original-Video, 1.21.2+](https://www.youtube.com/watch?v=MCJQGgV6AqE) | `bd87904083acb75a1d0871502d5cd543f173be927f50bc9a32fac59f52360062` |

Nash beschreibt die Bedienung als durchgehendes Benutzen mit Angriffen alle
30 Spielticks ([Bedienungs-Tutorial](https://www.youtube.com/watch?v=1W4KKfCyU9M)).
Die Worlds stammen aus 1.21.5 bzw. 1.21.11 und werden durch Minecraft selbst
auf 26.3 konvertiert. Farm-Blöcke, Villager und POI-Dateien bleiben erhalten;
alte gespeicherte Raids und alte Spielerdateien werden nicht übernommen.
Die Testbedienung entfernt anfängliche Raid-Mobs und leert Ausgabe-Kisten.
Die kompakte Farm wird mit einem echten Fläschchen ausgelöst; der Operator
duckt sich am originalen Bedienplatz und greift alle 30 Spielticks an.
Die geprüfte Vanilla-Referenz durchläuft alle acht Wellen bis zum Sieg.

Die Nash-Bedienversuche ergaben keinen belastbaren Vergleich mit einer
funktionierenden Vanilla-Referenz. Sie sind deshalb keine Nachweise für
oder gegen die Kompatibilität dieser Designs. Nach der Klarstellung des
Nutzers konzentriert sich die Prüfung auf den gemeinsamen Raid-Code und
die Mechaniken. Individuelle Nash-Zertifizierung und beworbene Stundenraten
sind nicht Bestandteil des Ergebnisses.

Mojang hat ab 1.21.2 die Spawn-Suche und die zulässige Höhendifferenz geändert
([Release Notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-2)).
Der aktuelle 26.3-Code bleibt maßgeblich; ein älteres Tutorial mit anderen
Raid-Regeln ist kein Paritätsmaßstab.

## Reproduktion

Siehe [STAND.md](STAND.md) und die beiden Driver in `tools/stand/`.
Original-World-ZIPs werden aus den oben verlinkten Autoren-Downloads benötigt
und nicht mit dem Repository verteilt.

Die kompakte Original-Farm besteht in `raid-compact-vanilla-v5` und
`raid-compact-final-v4` jeweils alle acht Checks: echtes Fläschchen, neuer Raid,
Wellen, neuer Loot, Sieg nach acht Wellen, sauberer Stop, keine Crashreports
und keine kritischen Logzeilen. Vanilla erzeugt 141, der finale Mod 134 neue
Ausgabe-Items. Das sind ungesetzte Zufallsabläufe und keine Messung einer
garantierten Stundenrate. Die Vanilla-Referenz und der vollständige Modpack
haben außerdem unterschiedliche Last; ihre Laufzeiten sind kein isolierter
Geschwindigkeitsvergleich.

Die Mechanik-Vergleiche mit tatsächlichem Vanilla 26.3 bestehen ebenfalls:

| Fall | Vanilla | Finaler Mod | Ergebnis |
| --- | --- | --- | --- |
| Bestehende Simulationsdistanz 10, äußerer Ring geladen | `raid-loaded-vanilla-v1` | `raid-loaded-final-v2` | Erste Welle erscheint |
| Simulationsdistanz 2, äußerer Ring ohne Entity-Ticking | `raid-low-vanilla-v1` | `raid-low-final-v2` | Keine äußere Welle; Raid stoppt |
| Einziger Dorf-POI nach Raidbeginn entfernt | `raid-village-vanilla-v1` | `raid-village-final-v3` | Raid stoppt innerhalb einer Sekunde |

Alle sechs Server stoppen sauber ohne Crashreport. Der erste Versuch
`raid-village-final-v2` hatte eine widersprüchliche Testoption und wurde vor
dem Serverstart abgewiesen; der korrigierte Lauf ist `v3`.

### Gleicher Lastvergleich

`raid-parity-perf-before-v1` und `raid-parity-perf-final-v1` verwenden dieselben
Driver und Weltbefehle, 90 Sekunden Aufwärmzeit und 120 Sekunden angeforderte
Profiler-Laufzeit mit Piston-Szenario. Vorher ist der strikte Raid-Paritäts-Jar
vor Login-/POI-Korrektur, nachher der fertige Jar. Jeweils melden sich 40 echte
Protokollclients an; dieselben fünf Walker werden in beiden Läufen wegen
ungültiger Bewegungspakete getrennt. Während der Messung bleiben jeweils
dieselben 35 Spieler online. Dieser Stand-Transportfehler ist kein Nachweis
einer Regression durch den Patch; die Messung ist ein Vergleich der gleichen
verbleibenden Last, kein erfolgreicher 40-Spieler-Bewegungstest.

Die tatsächlichen Capture-Dauern betragen 124,679 bzw. 121,920 Sekunden
(Async-Sampler 4.5, Intervall 4 ms). Die Dorfabfrage
`PoiManager.sectionsToVillage` sinkt von 36,734 auf 2,362 inklusive
Sample-Millisekunden pro Sekunde. `processPendingLoads` auf Main liegt bei
0,032 bzw. 0,033. Inklusive Methodenwerte überlappen und werden nicht addiert;
fehlende Frames beweisen keine Kosten von null. Die gesamte als RUNNING
eingestufte Sample-Zeit steigt auf den sechs Region-Workern von 2.290,94 auf
2.372,44 ms/s und auf Main von 279,73 auf 283,33 ms/s. Ein einzelnes kurzes
Profil belegt daher keinen allgemeinen TPS-Gewinn oder eine Produktionsrate.

Beide Lastläufe speichern und stoppen sauber ohne Crash oder kritischen
Serverfehler. Der alte Lauf meldet beim Schreiben der Spark-Datei eine
5-Sekunden-Warnung; der Trace zeigt `Files.write` in `SamplerModule.handleUpload`,
die Datei wird vollständig geschrieben. SkinRestorer-404/429-Antworten für
Testnamen und Python-BrokenPipe-Meldungen beim Client-Abbau werden getrennt
von Serverfehlern erfasst.

Alle Artefakthashes, Rohbericht-Pfade, Prüfergebnisse und geänderten Frames
stehen im [Validierungsnachweis](reviews/2026-10-09/raid-fix-validation.json)
und in den [Profilen](reviews/2026-10-09/raid-parity-profiles.json). Der
historische Zwischenstand bleibt ausdrücklich getrennt davon.

## Nachtrag 13.10.2026: POI-Zeitpunkt auf Inseln

Nach dem Rollout (3.0.2/3.0.3) meldete die Produktion Raids, die etwa eine
Sekunde nach Ablauf des Raid-Omens stoppen: Die Leiste erscheint kurz, es
kommt keine Welle und keine Niederlage. Ursache war eine verbleibende
Abweichung außerhalb des Raid-Codes. POI-Änderungen durch Blockwechsel auf
einer Insel liefen über `server.execute` in die Task-Queue von Main und wurden
erst beim nächsten Drain angewendet. Vanilla wendet sie im selben Tick sofort
an. Die alte Debounce-Begründung („main-side block events“) traf die
Phasenreihenfolge nicht; eine Main-seitige Verzögerung gab es aber tatsächlich,
bei der POI-Veröffentlichung. Die Fixtures oben entfernen Dorf-POIs per
Konsolenbefehl auf Main und konnten sie deshalb nicht zeigen. Fix und
Nachweis: CHANGELOG `3.1.1-alpha`.
