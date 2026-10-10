# Fabrolia: Reparaturen und Nachweise — 08.10.2026

Ausgangspunkt ist die [Codeprüfung](CODE-REVIEW-2026-10-08.md). Behoben wurden die
drei kritischen Befunde R1–R3 sowie die sieben bestätigten Collection-Abweichungen
aus R4–R6. Die bereits ergänzte Carpet-Absicherung bleibt enthalten.

## Änderungen

- **R1, falsche Map-Werte:** Int- und Long-Maps markieren jede Änderung eines
  veröffentlichten Tabellenplatzes mit einer Schreibgeneration. Leser prüfen
  diese vor und nach Schlüssel/Wert; auch A→B→A-Wiederverwendung wird erkannt.
  Byte delegiert an die korrigierte Int-Map. Alle Schreibpfade einschließlich
  Wertänderungen, Entfernen, Wiederverwendung und `clear` benutzen dieselbe
  Markierung. Resize veröffentlicht eine neue, vollständig aufgebaute Tabelle.
  Lesezugriffe erhalten keine zusätzlichen Locks oder Objekt-Allokationen.
  Dafür kommt ein `long` pro reserviertem Tabellenplatz hinzu, also 8 Byte.
- **R2, End-Speicherpfad:** Save und Scheduler-Abbau beachten denselben
  Landezustand wie normale Ankünfte. Zurückgehaltene Plattform-Regenerationen
  und Entity-Ankünfte werden geordnet in der Zielwelt gespeichert, einschließlich
  Entity-NBT und UUID/Chunk der noch fliegenden Vorgänger. Ein vollständiger
  Serversave schließt alle Quell-Domänen vor dem ersten Welt-Snapshot.
  Wiederherstellung läuft vor dem ersten Welt-Tick, auch bei erst später
  angelegten Dimensionen. Geladene, noch unsichtbare Vorgänger öffnen das Gate
  nicht. Portal-Tickets sorgen für das Laden der benötigten Entity-Chunks.
  Beim Ausschalten des Schedulers übernimmt Main die Warteschlange und dieselbe
  Landeprüfung; neue Ankünfte werden dort ebenfalls eingeordnet.
- **R3, Portal-Tickets:** Der Auftrag hält die ursprüngliche Zielwelt und den
  unveränderlichen Chunk fest. Main setzt dort das Vanilla-Portal-Ticket, auch
  wenn die Entity inzwischen entfernt, vereinigt oder weiterteleportiert wurde.
  Der bestehende Schutz der Scheduler-Generation bleibt zuständig.
- **R4–R6, Map-Verträge:** Bedingtes Entfernen meldet auch beim Defaultwert
  korrekt Erfolg; `keySet` und `values` schreiben Änderungen in die Map zurück;
  Iterator-Entfernung, `Entry.setValue` und `replaceAll` funktionieren für die
  geprüften Int-/Long-/Byte-Varianten.

Die Integrationseinstiegspunkte für Rustmi und die Modversion bleiben erhalten.
R7–R10 (unwirksame alte Schalter, HUD-Anzeige, Abstands-Dokumentation und mögliche
unbenutzte öffentliche Utilities) sind nicht Teil dieser Reparatur.

## Nachweise

`bash gradlew :common:test :fabric:build :neoforge:build` besteht: **1.399 Tests,
0 Fehler, 8 optionale externe Prüfungen übersprungen**, beide Loader-Jars gebaut.

Der neue Map-Test erzwingt das A→B→A-Interleaving im tatsächlichen kompilierten
Map-Code. Der alte API-Jar liefert dabei 22 für einen Schlüssel, der ausschließlich
11 enthält. Der korrigierte Code besteht für `get` und `getOrDefault` in allen
drei Varianten. Die Collection-Diagnose zeigt anschließend **0 statt 7
Abweichungen**. Ein zusätzlicher Test verhindert einen Selbst-Deadlock, wenn ein
`compute`-Callback die Map liest.

Der endgültige Fabric-Jar besteht alle **11 Stand-Prüfungen mit 40 Clients**
(`critical-fixes-verify-v3`): Carpet-Guard, Funktionen, Advancements, Disconnect,
Nether-Portale, Saves, Dateien und Crash-/Ownership-Scan. Der Server beendet sich
sauber mit Exitcode 0.

Der End-Stand `critical-fixes-end-new-v6` besteht **37 Prüfungen**: Quelle 20 TPS,
Ziel 5 TPS, Save bei offenem Flug des Vorgängers, gespeicherter Gravel-Zustand,
zwei saubere Neustarts, Wiederherstellung ohne End-Forceloads oder Spieler,
async→sync, vollständiger Scheduler-Stopp und Start sowie neue Ankünfte während
des Stopps. Anschließend landet ein wiederhergestellter Sandblock tatsächlich;
die nächste Portal-Regeneration entfernt ihn und erzeugt das Sand-Item. Ein
Nachfolger wird nach dem Neustart genau einmal gefunden. Alle drei Serverstarts
enden sauber; es gibt keine Crash-Reports.

Der alte Build reproduziert im Live-Stand `critical-fixes-end-old-v2` den
Save-Bypass: vor dem Save bleibt B zurückgehalten, danach wird die Plattform
trotz noch fliegendem A regeneriert und B eingefügt. Dieser alte Kontrollfall
lief bei den normalen 20 TPS; die neue Wiederherstellungsprüfung senkt das
End-Ziel ausdrücklich auf 5 TPS.

Der Portal-Test prüft den kompilierten Ticket-Callback: Er erfasst `ServerLevel`
und `ChunkPos`, führt direkt die Ticket-Operation aus und hängt nicht mehr vom
späteren Entity-Zustand ab. Das ist ein Nachweis der Auftragsbindung, keine
separate Reproduktion eines versagenden Portal-Chunkloaders.

## Performance

Alter und endgültiger neuer Fabric-Build wurden im selben `items`-Szenario
verglichen: gleicher Seed, 6 Worker, 3 GiB Heap, 40 gestartete Clients und
jeweils 35 Clients beim abschließenden `/list`. Die Szene enthält rund 11.000
Entities, 356 Regionen und 3.000 Items mit Wasserströmung. Ein Paar läuft 120
Sekunden, ein zusätzliches Kontrollpaar 60 Sekunden. Alle vier Server beenden
sich sauber mit Exitcode 0. Die Profile werden über ihre tatsächliche Laufzeit
ausgewertet; verschachtelte inklusive Frames werden nicht addiert.

| Messung | Alter Build | Neuer Build |
| --- | ---: | ---: |
| 120 s: Main, betroffene Frames, eigene CPU-Zeit pro globalem Tick | 0,0348 ms | 0,0585 ms |
| 120 s: alle Worker, betroffene Frames, eigene CPU-Zeit pro globalem Tick | 0,1638 ms | 0,2650 ms |
| 60 s: Main, betroffene Frames, eigene CPU-Zeit pro globalem Tick | 0,0764 ms | 0,0856 ms |
| 60 s: alle Worker, betroffene Frames, eigene CPU-Zeit pro globalem Tick | 0,1296 ms | 0,1844 ms |
| 120 s: Far / Town, abschließende Insel-TPS | 3,6 / 7,7 | 3,2 / 6,4 |
| 60 s: Far / Town, abschließende Insel-TPS | 3,3 / 6,7 | 3,1 / 6,3 |

Die zusätzlichen Kosten der betroffenen Frames liegen in diesen Stichproben bei
rund 0,009–0,024 ms auf Main und 0,055–0,101 ms über alle Worker pro globalem Tick.
Die Sicherheit kostet somit messbar CPU und zusätzlichen Map-Speicher.

Die Insel-TPS sind in beiden neuen Läufen niedriger. Der lokale Rechner ist in
allen vier Läufen stark ausgelastet; die Mob-Verteilung unterscheidet sich
ebenfalls: am Ende 89 gegenüber 106 beziehungsweise 84 gegenüber 104 nicht leere
Regionen. Die Profile zeigen kleine direkte Mehrkosten, erlauben aber weder,
die gesamte TPS-Differenz diesen Änderungen zuzuschreiben, noch allgemeine
Performance-Gleichheit zu behaupten. Es wurde kein 20-TPS-Betrieb unter dieser
Last nachgewiesen. Rohwerte, Profile und Build-Zuordnung stehen im
[maschinenlesbaren Nachweis](reviews/2026-10-08/critical-fixes-validation.json).

## Gebaute Artefakte

- `fabric/build/libs/fabrolia-fabric-0.2.38+alpha-26.3.jar`:
  SHA-256 `643af1157e22d6e69b1fd7b62585c2012f45a999981c5d8d03a28037c969465a`.
- `neoforge/build/libs/fabrolia-neoforge-0.2.38+alpha-26.3.jar`:
  SHA-256 `dee1d34250186ccd902f288f6b7f6bd168f875e4d255af037e64b60e4b5b53a7`.

Alle neuen Live-Nachweise oben verwenden genau diesen Fabric-Jar. Die Jars
wurden gebaut; eine laufende Installation wurde nicht ersetzt.

## Grenzen

Der End-Test verwendet echte Portal-Transfers mit FallingBlockEntities und hält
den kritischen Zustand zunächst mit `NoGravity` gezielt offen. Die konkrete
Farm des Besitzers wurde nicht nachgebaut. Der allgemeine Live-Stand läuft auf
Fabric; NeoForge ist durch Build und gemeinsame Tests geprüft.

Die gespeicherte Warteschlange ist ein Welt-Snapshot, kein Transaktionsjournal
über Entity-/Chunk-Dateien und SavedData. Der geprüfte Wiederherstellungspunkt
ist ein erfolgreich abgeschlossener Save mit sauberem Neustart. Ein harter
Prozessabbruch zwischen Veröffentlichungen und Dateischreibvorgängen hat keine
Exactly-once-Garantie; ein veralteter Queue-Snapshot kann dabei erneut gelesen
werden. Auch das bestehende Vanilla-Speichermodell ist nicht atomar über alle
Weltdateien.

Der bekannte Full-Modpack-Mixin-Export-Verifier mit der veralteten
`ServerFunctionManager`-Assertion wurde nicht als erfolgreicher Nachweis gewertet;
siehe `AGENTS.md`. Der normale Build und der Live-Stand ersetzen diese spezielle
Prüfung nicht vollständig.
