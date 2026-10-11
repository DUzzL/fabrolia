# Produktionslog-Review (Log vom 2026-10-10, ausgewertet am 2026-10-11)

Quelle: `latest.log` des Produktionsservers, **68.691 Zeilen**, Serverzeit
00:00:00–20:06:22 (UTC−4; die Datei wurde am 11.10. um 02:07 MESZ geschrieben).
Es ist dieselbe Tagesdatei, von der [MSPT-ANALYSE-2026-10-10.md](MSPT-ANALYSE-2026-10-10.md)
einen Ausschnitt bis 13:58 ausgewertet hat; dieses Review deckt den ganzen Tag ab.
Laufzeitverhalten wurde nicht geändert, außer wo unten ausdrücklich genannt.

## Zusammensetzung des Logs

| Zeilen | Anteil | Meldung | Herkunft | Bewertung |
| ---: | ---: | --- | --- | --- |
| 26.383 | 38,4 % | `Player X standing on air - force-sending blocks below` | anderer Mod (nicht im Fabrolia-Code) | Rauschen; Loglevel dort senken |
| 8.422 | 12,3 % | `<Boot> (vehicle of X) moved too quickly!` | Vanilla | siehe unten |
| 5.861 | 8,5 % | `[entity-unload] restored N deferred chunk unload request(s)` | Fabrolia | **behoben** (Rauschen → gezielte Warnung) |
| 5.615 | 8,2 % | `[pearl-ticket] chunk … LEFT entity-ticking` | Fabrolia | **behoben** (überwiegend Fehlalarme) |
| 3.880 | 5,6 % | `[entity-status] escalated claim interest` | Fabrolia | gedrosselt, korreliert mit Lag |
| 2.864 + 1.754 | 6,7 % | `[pearl-ticket] holding …` / `hold released …` | Fabrolia | **behoben** (Zusammenfassung) |
| 2.015 | 2,9 % | `[scheduled-ticks] SCHEDULED-HOLD … readiness=probe-failed:NPE` | Fabrolia | **behoben** (Diagnosefehler) |
| 1.786 | 2,6 % | `[region-evict]` | Fabrolia | gedrosselter Heartbeat, ok |
| 996 | 1,4 % | `[async-islands] discarded stale queued unit` | Fabrolia | gewollte Schutzlogik, gedrosselt |
| 877 | 1,3 % | `Mismatch in destroy block pos` | Vanilla | siehe unten |
| 349 | 0,5 % | `[delivery-liveness]` | Fabrolia | erwartbar bei schneller Bewegung |
| 185 | 0,3 % | `Can't keep up!` | Vanilla | echte Lags, siehe unten |
| 69 | 0,1 % | `save/topology quiesce took N ms` | Fabrolia | Folge langer Insel-Units |

Die vier behobenen Fabrolia-Meldungen machten zusammen rund 18.000 Zeilen
(≈ 26 % des Logs) aus. Nach den Änderungen bleiben davon nur echte Anomalien
und eine Zusammenfassung alle fünf Minuten.

## Behobene Probleme

### 1. SCHEDULED-HOLD: Readiness-Probe scheiterte immer (Diagnosefehler)

Alle 2.015 HOLD-Zeilen endeten mit
`readiness=probe-failed:java.lang.NullPointerException: … "this.loadingChunkTracker" is null`.
`IslandScheduledTicks.describeChunkReadiness` las den Lade-Level über
`DistanceManager.getChunkLevel(pos, false)`; das liest `loadingChunkTracker`,
das unter C2ME `null` ist (C2ME ersetzt das Vanilla-Ticket-Tracking). Weil alle
Werte in einem einzigen `try` standen, gingen damit auch Simulationslevel,
Ticket-Level, Entity-Status usw. verloren – die Ursache jedes HOLDs blieb
unsichtbar.

Jetzt kommt der Lade-Level aus dem Ticket-Level des Holders (funktioniert mit
und ohne C2ME), und jedes Bein wird einzeln abgefragt; ein fehlschlagendes Bein
erscheint als `name=probe-failed:Typ`, die übrigen bleiben lesbar.

Die HOLDs selbst sind gewolltes Fail-closed-Verhalten: `L` schreitet fort, es
gab kein `SCHEDULED-HOLD-STUCK`, insgesamt ~3.800 HOLDs in 20 h zu je ~50 ms.
Wiederkehrende Stellen (z. B. `island#1258 [260,-182]` Crafter, 999 Meldungen
über 17 h) sind offene Schreibgrenzen von Halo-Zellen. Mit der reparierten
Probe zeigt der nächste Log, welches Bein jeweils verweigert.

### 2. Perlen-Freeze-Trace meldete Zwischenstände (Fehlalarme)

`SimulationChunkTrackerMixin` meldete „LEFT entity-ticking“ in `setLevel`.
`setLevel` sieht aber auch die Zwischenzustände einer Propagationswelle: Bei
einer Level-Erhöhung hebt der Fixpunkt-Algorithmus den Knoten zuerst auf das
Maximum und relaxiert ihn danach wieder. Ein Stasis-Chunk, dessen Ticketmenge
sich nur änderte (Spielerticket bewegt, Erneuerung), „verließ“ so kurz das
Entity-Ticking, ohne es am Ende der Welle tatsächlich zu verlassen. Beispiele:
`[-1918, 194]` 1.027-mal, `[-1918, 193]` 947-mal, mehrmals pro Sekunde, ohne
dass je ein Ticket-Hold griff.

Jetzt werden Kandidaten in `setLevel` nur vorgemerkt und am Ende von
`runAllUpdates` gegen den vorher veröffentlichten Stand geprüft: Gemeldet wird
nur, was vorher entity-ticking war und nach der Welle nicht mehr ist.

### 3. Perlen-Ticket-Holds als Einzelzeilen

`holding ENDER_PEARL ticket` und `hold released … (self-heal OK)` beschreiben
den funktionierenden Self-Heal: Das Ticket bleibt bestehen, der Chunk verlässt
das Entity-Ticking nicht (1.754 Heilungen, im Mittel nach 5,7 Purge-Durchläufen,
maximal 43). Diese Ereignisse werden jetzt gezählt und alle fünf Minuten in
einer Zeile zusammengefasst (Anzahl, geheilt, maximale Durchläufe, letzter
Hold mit Probe). Echte Anomalien – Budget erschöpft, Ablauf mit lebender Perle,
abgeschlossener Freeze – bleiben Einzelzeilen.

### 4. `[entity-unload]`-Wiederholungen alle 10 s als WARN

Der Zähler stieg von 2,95 auf 7,50 Mio. (≈ 62 Wiederholungen/s). Ein `false`
beim Speichern der Entity-Sektion ist normal (Insel besitzt den Chunk, Entities
laden noch); Vanilla wiederholt das ebenfalls jeden Tick, nur still. Die alte
Meldung sagte nicht, ob ein Chunk tatsächlich hängt.

Jetzt wird für jeden ausstehenden Retry der Zeitpunkt des ersten Fehlschlags
geführt (auch über Durchläufe, in denen C2MEs begrenzte Schleife den Schlüssel
nicht erreicht). WARN erscheint nur, wenn ein Chunk seit **60 s** nicht entladen
werden kann, mit Koordinaten und Anzahl ausstehender Chunks – das ist das
Signal für eine gestrandete Entity-Sektion. Die Routinezahl gibt es auf DEBUG.

## Weitere Befunde ohne Codeänderung

**Lag-Spitzen.** 185 `Can't keep up`, zusammen ~817 s Rückstand, Spitze
43,9 s (19:49). Main loggt dabei weiter jede Sekunde: Es sind minutenlang zu
langsame Ticks, kein einzelner Hänger. Die Fenster (13:34–13:42, 14:04, 15:34,
16:04, 17:11, 19:49) fallen mit sehr hohen `escalated claim interest`-Zahlen
(800–1.660 Chunks/10 s statt sonst 150–400) und mit `delivery-liveness` von
Spielern zusammen, die schnell viele neue Chunks laden. Ein Spark-Profil
wurde um 19:50 versucht, scheiterte aber am Upload (`BytebinClient`
`SocketTimeoutException`). Ursache und Wirkung lassen sich aus dem Log allein
nicht trennen. Kandidat im Code: Die Status-Übergangs-Queue
(`PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions`) nimmt pro
angewendetem Eintrag einen eigenen Insel-Claim (`tryMainClaim` + Commit +
`mainRelease`); bei Tausenden Einträgen pro Tick summiert sich das. Eine
Bündelung pro Insel wäre eine Threading-Änderung und braucht laut `AGENTS.md`
einen Stand-Nachweis (verify + perf) – nicht ohne Stand umgesetzt.

Für den nächsten Einbruch: alle Threads profilieren und lokal speichern, falls
der Upload wieder scheitert:

```text
/spark profiler start --thread * --interval 4 --timeout 120
/spark profiler stop --save-to-file
/fabrolia regions top verbose 100
```

**Boots-Warnungen.** Die Meldungen kommen in Kaskaden (−10,0 → −13,4 → −16,8 →
−20,2 Blöcke: +3,36 je Paket, die Bootgeschwindigkeit auf Blaueis). Eine erste
Ablehnung setzt das Boot zurück, der Client fährt weiter bis zur Korrektur.
Nur 89 von 603 Kaskaden liegen in Lag-Fenstern (die 6 % der Zeit abdecken);
sie sind also überwiegend nicht lag-bedingt. Vanilla erzeugt dieses Muster auf
Eisbahnen ebenfalls. Offene Frage für den Stand: ob die erste Ablehnung durch
eine veraltete Server-Position des Fahrzeugs entsteht (Fahrzeugpaket auf der
Insel des Bootes vs. Spielertick).

**`Mismatch in destroy block pos`** (777 von 877 auf Region-Workern). Vanilla
meldet das, wenn der Server ein START als Sofortabbau wertet, der Client aber
weiter abbaut, oder wenn ein START verworfen wurde. Typische Ursache ist ein
abweichender `onGround`-Zustand zwischen Client und Server – passend zu den
26.383 „standing on air“-Meldungen des anderen Mods. Ein Fabrolia-Bezug ist
nicht belegt.

**Weitere externe Meldungen.** Mojang-Sessionserver-Fehler beim Login (28,
extern), `Tried to load unrecognized recipe … vivecraft` (Client-Mod-Rezepte),
`Sending unknown packet 'clientbound/minecraft:disconnect'` (Vanilla: beim
Lesefehler im Handshake-Zustand existiert kein Disconnect-Paket; Fabrolias
`netherOrderedSend`-Wrapper reicht nur durch), `handleDisconnection() called
twice` (Vanilla-Warnung).

## Prüfung

- `:common:test` 1.439 bestanden (Ausgangsstand 1.434 plus fünf neue Tests),
  8 übersprungen (externe Jars), 0 fehlgeschlagen.
- Version 3.0.2-alpha; `tools/release/check_jar.py` PASS für
  `fabrolia-alpha_3.0.2.jar`. Alle Änderungen dieser Sitzung stehen im
  [CHANGELOG](../CHANGELOG.md) unter 3.0.2-alpha.
- `:fabric:build` inklusive Byte-Vergleich des Jars mit dem getesteten Output
  und `:neoforge:build` erfolgreich.
- Kein Stand-Lauf: Der Teststand (`tools/stand/stand.sh`) braucht Linux und 40
  echte Clients. Die Änderungen dieses Reviews betreffen nur Diagnose/Logging
  bzw. entfernen unerreichbaren Code; vor einem Produktionsrelease trotzdem
  `stand.sh verify` laufen lassen.
