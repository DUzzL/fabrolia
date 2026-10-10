# Raid-Farmen: Codeprüfung vom 09.10.2026

Dieser Bericht dokumentiert die Prüfung **vor dem Fix**. Der anschließend
beauftragte Fix und seine Validierung stehen in
[RAID-TICKET-FIX-2026-10-09.md](RAID-TICKET-FIX-2026-10-09.md).

Geprüft wurden der aktuelle Fabrolia-Code und die tatsächlichen Minecraft-26.3-
Klassen aus dem lokalen Loom-Jar. Die konkrete Farm, ihre Position, Bauweise,
Ausbeute und aktuelle Serverkonfiguration liegen nicht vor. Ein Befund ist
isoliert reproduziert; weitere Ursachen sind anhand des Codes plausibel, aber
nicht als Ursache der gemeldeten Farm bewiesen. Für diese Prüfung wurden weder
Runtime-Code noch Produktions-Jars geändert.

## 1. Raid-Ticket deckt den Entity-Tickbereich des Spawn-Rings nicht ab

**Bestätigte Abweichung mit bedingter Auswirkung auf die Farm.**

`RaidIslandMerge.TICKET_RADIUS = 6` wird als Radius der geladenen FULL-Chunks
verwendet. In 26.3 erzeugt `TicketStorage.addTicketWithRadius` das Ticketlevel
`33 - radius`, hier 27. Entity-Ticking braucht ein Level von höchstens 31.
Nach Ausbreitung des Tickets ergibt sich:

| Abstand in Chunks zum Ticketzentrum | Status durch dieses Ticket |
| --- | --- |
| 0–4 | Entity-Ticking |
| 5 | Block-Ticking |
| 6 | FULL, ohne Entity-Ticking |

`Raid.findRandomSpawnPos` verlangt jedoch ausdrücklich
`level.isPositionEntityTicking(spawnPos)`. Ein geladener Chunk allein genügt
nicht. Der frühe Ring bei 300 Cooldown-Ticks liegt ungefähr 98 Blöcke entfernt;
mit dem Zufallsversatz kann eine Koordinate etwa 103–104 Blöcke entfernt liegen.
Je nach Lage des Zentrums im Chunk sind bis zu sieben Chunks Abstand möglich.
Die Aussage im Kommentar, Radius sechs decke den Ring mit Reserve ab, gilt für
Entity-Ticking deshalb nicht.

Der [Probe](reviews/2026-10-09/RaidTicketCoverageProbe.java) führt die unveränderte
26.3-Methode `Raid.findRandomSpawnPos` und die echte `TicketStorage` aus. Der
`ServerLevel` ist ein kontrolliertes Modell einer ebenen Spawnfläche, der
Ticket-Ausbreitung und der Chunk-/Entity-Bereitschaft. Er hat keine Spieler-
oder konkurrierenden Tickets. Bei derselben gültigen Fläche nahe x=97 ergibt
sich:

```text
radius=6 ticketLevel=27 entityRadius=4 earlySpawn=null
radius=8 ticketLevel=25 entityRadius=6 earlySpawn=MutableBlockPos{x=97, y=80, z=0}
radius=9 ticketLevel=24 entityRadius=7 earlySpawn=MutableBlockPos{x=97, y=80, z=0}
```

Das reproduziert die Ablehnung einer gültigen Fläche aufgrund des zu kleinen
Entity-Tickbereichs. Es ist kein Live-Test der vollständigen Besitzer-Farm.
Wenn der Spieler oder andere Tickets die Fläche bereits tickbar halten, bleibt
dieser Fehler unsichtbar. Bei kleiner Simulationsdistanz, versetztem Zentrum
oder einer weiter entfernten Spawnfläche kann die frühe Positionssuche aber
scheitern. Der Ring schrumpft während des Cooldowns; findet die Suche auf der
vorgesehenen Fläche nie eine Position, kann die Welle beziehungsweise der Raid
ausfallen. Ein bloßes Plus an geladenen Chunks behebt die Eligibility nicht.

Möglicher Ansatz: den gewünschten Entity-Tickradius ausdrücklich ableiten und
in ein Ticketlevel übersetzen. Für sieben Chunks Entity-Radius wäre das Level
24 beziehungsweise FULL-Ticketradius neun. Das muss einschließlich der
zusätzlichen Chunk-/Ticklast und der realen Farm geprüft werden; Radius acht
ist im gezeigten Einzelfall ausreichend, aber kein Nachweis für den gesamten
Ring.

Nachweis und Minecraft-Jar-Hash:
[raid-ticket-coverage.json](reviews/2026-10-09/raid-ticket-coverage.json).
Reproduktion aus dem Repository-Root mit Java 25:

```bash
bash gradlew :common:raidTicketCoverageAudit \
  -I docs/reviews/2026-10-09/raid-audit.init.gradle \
  -PraidAuditBaselineJar=/path/to/pre-fix-fabrolia.jar
```

Der historische Probe erwartet Radius 6 und benötigt den alten Jar als
Negative-Kontrolle. Der finale Mod erzeugt keine Raid-Tickets mehr;
`RaidSpawnAdmissionParityTest` prüft die Zulassung anhand bestehender
Chunk-Level. Der historische JSON-Nachweis bleibt unverändert.

## 2. Die erweiterte Dorf-Suche kann teuer sein und verändert Zentren

**Bestätigter zusätzlicher Codepfad; Einfluss auf reale TPS noch ungemessen.**

`RaidRelocationColumnMixin` führt zunächst die Vanilla-Suche aus. Wenn diese
das Zentrum nicht verschiebt, prüft der zusätzliche Pfad bei der
Standardeinstellung 17 × 5 × 5 = **425 weitere Sections**. Vanilla prüft
5 × 5 × 5 = 125. Ein Raid ohne gefundenes Dorf kann diesen Pfad in jedem seiner
Ticks durchlaufen. Bei vielen gleichzeitig aktiven Raids vervielfacht sich
die Arbeit. Daraus folgt noch keine gemessene Zeit pro Abfrage.

Jede dieser Abfragen geht über `isVillage → sectionsToVillage`. Fabrolias
`PoiManagerDistanceMixin` fügt dort ein synchrones `getOrLoad` sowie das Drain
der POI-Änderungen hinzu. Im unveränderten 26.3-Code führt `sectionsToVillage`
nur die Graph-Updates und den Level-Lookup aus. Die zusätzliche Laden-/Drain-
Arbeit und der gemeinsame Tracker-Monitor sind deshalb konkrete Frames,
nach denen ein All-Threads-Spark-Profil suchen sollte. Im finalen Fix ist
das zusätzliche `getOrLoad` samt Accessor entfernt; das Drain bleibt für
aktuelle, thread-sichere Graph-Abfragen erhalten.

Der Fallback erweitert die vertikale Suche von ±2 auf ±8 Sections. Er wählt
weiterhin das nächste Dorf, ohne einen dauerhaft beanspruchten Job-Site-POI
oder den Abstand zum auslösenden Spieler zu prüfen. Der Kommentar verspricht
einen Sprung zum stabilen oberen Anker; dieser wird vom Algorithmus nicht
garantiert. Außerdem lehnt Vanilla Raid-Spawnflächen ab, deren Höhe mehr als
96 Blöcke vom Raid-Zentrum abweicht. Eine zusätzliche vertikale Verlagerung
kann dadurch die erlaubten Spawnflächen verändern. Ob das die Besitzer-Farm
trifft, hängt von ihrer Geometrie ab.

Die alten Kommentare zum Dorf-Debounce behaupten außerdem, Vanilla prüfe Raids
nach Block-Events und Entities. Der tatsächliche 26.3-Ablauf ist
`scheduled ticks → raids → chunk tick → block events → entities`;
`IslandTickUnit` übernimmt genau diese Reihenfolge. Die alte Begründung reicht
daher nicht aus, um die heutigen Sonderregeln als notwendig zu betrachten.
Sie sollten gegen denselben Farm-Aufbau in Vanilla und Fabrolia geprüft werden,
bevor man sie entfernt oder weiter ausweitet.

## 3. Raid-Timer hängen ausdrücklich an den lokalen Insel-TPS

**Bestätigte Taktregel; aktuelle TPS der betroffenen Farm unbekannt.**

`RaidIslandDispatch.service` tickt die zugeordneten Raids einmal pro
`IslandTickUnit`, an der vorgesehenen Phase. Der aktuelle Pfad zählt keine
Main-Ticks herunter und enthält keine feste Halbierung des Raid-Takts.
Ein früherer coalescender Dispatch ist nur noch in historischen Kommentaren
beschrieben; er ist nicht der aktuelle Implementierungspfad.

Bei lokalen 10 statt 20 TPS dauern die 300 Raid-Cooldown-Ticks 30 statt 15
Sekunden. Ebenso laufen Transport, Villager und andere lokale Farmarbeit
langsamer. Main beziehungsweise Spark können dabei weiterhin ungefähr
20 TPS melden. Die Ursache niedriger lokaler TPS muss deshalb von einem
Raid-Mechanikfehler getrennt werden.

Relevant sind `tps=gemessen/ziel`, `L`, `mspt`, `lag` und `missed` der Farm-Insel
in `/fabrolia regions top verbose 100`. Zwei Beobachtungen mit Abstand zeigen
über die Differenz von `L` die tatsächlich abgeschlossenen lokalen Ticks.
Ein Spark-Profil aller Threads kann danach zeigen, ob Dorf-Suche, POI-Laden,
Claim-Wartezeiten, Entity-AI oder eine andere Arbeit die Insel aufhält.

## Weitere bedingte Ursache im Modpack

Die lokal vorhandene ServerCore-Version 1.5.20 besitzt eine optionale
Activation-Range-Funktion. Außerhalb des Aktivierungsbereichs kann sie volle
Mob-/Villager-Ticks ausdünnen. Ein aktiver Raid allein ist in den geprüften
Immunitäts-/Ausschlussprüfungen kein genereller Freibrief für Raider; Wasser,
Bewegung, Kampf und andere Zustände können jedoch Immunität herstellen.
Der inaktive Villager-Tick führt keine normale Brain-/Job-Site-Arbeit aus.
Falls diese Funktion auf dem Produktionsserver eingeschaltet ist, sind deshalb
auch vertikal entfernte Villager und Raider zu prüfen. Die aktuelle
Produktionskonfiguration ist unbekannt; daraus wird kein konkreter Fehlerfall
oder erforderlicher Konfigurationswechsel abgeleitet.

Für Raid-Wellen habe ich dagegen keinen direkten neuen natürlichen Mobcap-
Grenzwert im Fabrolia-/Rustmi-Code gefunden. Die untersuchten Raid-Spawnpfade
sind kein gewöhnlicher `NaturalSpawner`-Zyklus.

## Was zur Zuordnung zur gemeldeten Farm fehlt

Farmtyp/Bauplan, tatsächliche und erwartete Ausbeute, Position des Spielers und
der Spawnfläche, aktuelle Simulationsdistanz und lokale TPS. Diese Angaben
entscheiden, ob vor allem Ticket-Abdeckung, verschobene Raid-Zentren,
zusätzliche POI-Arbeit, lokale Überlastung oder ServerCore-Aktivierung in Frage
kommen. Die Codeprüfung bestätigt keinen Durchsatzverlust in einer nachgebauten
Besitzer-Farm; sie liefert einen reproduzierten Eligibility-Fehler und konkrete
weitere Diagnosepunkte.
