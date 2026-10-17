# Raid-Farm: Bad Omen bleibt bei der Umwandlung erhalten (17.10.2026)

Fortsetzung: Die unten noch offenen 3.1.5-Unterschiede bei späten Dorf-Lücken
wurden auf die abweichende Raid-Registry- und Tick-Reihenfolge zurückgeführt.
3.1.6 korrigiert diese; Ursache, Tests und neue Originalläufe stehen in
[Raid-Reihenfolge und Dorf-Verlegung](RAID-REGISTRY-PARITY-2026-10-17.md).
Dieser Bericht bewahrt die vorherigen Diagnose- und Kontrollläufe.

## Bericht

Produktion, 16.10.2026, Fabrolia 3.1.3: Lemon_Iced's „11k–25k Self-Sustaining Raid
Farm“ (TMC-Katalog, 1-Spieler-Variante, eigener Client) ist auf dem Server dauerhaft
langsam, im Singleplayer erreicht sie die Tabellenrate. Kein Lag (Main und Insel
19,2 TPS). Laut Spieler verschwindet die Raid-Leiste bei etwa 5 %.

Die Vermutung des Spielers („die Stufe des Fläschchens ändert die Wellen nicht“)
ist nicht die Ursache: In 26.3 entscheidet die Raid-Omen-Stufe nur über die
Bonuswelle (ab Stufe 2), die Verzauberungschance und die Stufe von „Held des
Dorfes“; Stufe 2–4 ergeben auch in Vanilla dieselben Wellen.

## Wie die Farm in Vanilla arbeitet (gemessen)

Original-Welt („[1.21.2+]24.54k self_sustaining 1D RaidFarm (1 player)“), lokal auf
26.3 mit Lithium 0.26.2, bedient von einem echten Protokoll-Client
(`tools/raid-farm-ab/`), Ereignisse aus dem Diagnose-Mod:

- Eine Minecart-Uhr (Hebel an der Redstone-Lampe) schiebt alle 602 Ticks vier
  Fletching Tables per Kolben hin und zurück. Der Villager neben dem Bedienplatz
  nimmt den hergeschobenen Tisch bei (-1, 80, 19) als Job-Site; einen Tick später
  ist er wieder weg. In genau diesem Tick ist der Bedienplatz ein Dorf.
- Der Spieler tickt nach der Welt. Sein Bad Omen II wird in diesem Fenster zu Raid
  Omen. **Bad Omen bleibt dabei erhalten**: `BadOmenMobEffect` fügt Raid Omen neu in
  die `HashMap` der Effekte ein und gibt `false` zurück; `iterator.remove()` wirft
  deshalb `ConcurrentModificationException`, `LivingEntity.tickEffects` fängt sie,
  Bad Omen bleibt registriert. Im nächsten Tick ist der Platz kein Dorf mehr, Bad
  Omen läuft einfach weiter.
- 600 Ticks später läuft Raid Omen ab, genau beim nächsten Uhrenpuls. Der neue Raid
  entsteht am Fletching Table (-17, 80, 18); drei Ticks später ist dort kein Dorf
  mehr, und `moveRaidCenterToNearbyVillageSection` (±2 Sections pro Tick) zieht das
  Zentrum über die Job-Sites des zweiten Villagers bis zur dauerhaft beanspruchten
  Glocke (Zentrum (-8, 184, 8)). Die Wellen spawnen auf der Plattform bei y = 94.
- Zwei Ticks danach wandelt das weiterhin vorhandene Bad Omen erneut um. Ergebnis:
  ein neuer Raid alle ~30 s, viele Raids laufen parallel, und nach dem ersten
  Fläschchen wird keines mehr getrunken („self-sustaining“).

## Ursache in Fabrolia

`LivingEntityMixin` ersetzt die Effekt-Map durch `EffectMap` (Thread-Sicherheit).
Deren Iterator wich absichtlich von `HashMap` ab: „An insertion during tickServer
must not retain the expiring old effect.“ Bad Omen wurde daher bei der ersten
Umwandlung entfernt. Danach entstand ein Raid nur noch, wenn der Spieler ein neues
Fläschchen trank. Mit dem Client der Farm geschieht das kaum, denn der
Rüstungsständer fängt seinen Rechtsklick ab.

| 5 Minuten, gleiche Welt und Bedienung | Vanilla + Lithium | Fabrolia 3.1.3 + Lithium |
| --- | --- | --- |
| Raids ausgelöst (`raid_trigger`) | 8 | 1 |
| Pillager / Vindicator getötet | 177 / 111 | 41 / 25 |
| Fläschchen getrunken | 0 | 0 |
| Bad Omen nach der Umwandlung | bleibt | entfernt |

Der einzelne Raid unter 3.1.3 lief normal über acht Wellen zum Sieg (Dorf-Leiter,
Spawnfläche und Ticking sind in Ordnung). Mit `islandEntityParallel = true` ergab
sich dasselbe Bild.

## Fix (3.1.5)

`EffectMap` hält für den Iterator, der den Monitor besitzt (der `tickEffects`-Lauf),
jetzt den Fail-fast-Vertrag von `HashMap` ein: Nach einer strukturellen Änderung
(Effekt hinzugefügt oder entfernt) werfen `next()` und `remove()`
`ConcurrentModificationException`, das aktuelle Element bleibt. `remove()` entfernt
wie `HashIterator.remove` per Schlüssel. Fremde Leser behalten die schwach
konsistente Iteration der `ConcurrentHashMap`; alle Schreiber nehmen weiter den
Monitor.

Tests: `EffectMapTest` und `LivingEntityEffectIterationTest` vergleichen jetzt
dieselben Abläufe mit Vanillas `HashMap` (darunter Bad Omen → Raid Omen über zwei
Ticks); die neuen Vergleiche schlagen mit dem alten `EffectMap` fehl. Zwei alte
Tests hatten die Abweichung festgeschrieben und wurden ersetzt.

## Nachweis mit 3.1.5 (10 Minuten, drei Server gleichzeitig)

| | Vanilla + Lithium | 3.1.5 | 3.1.5, `islandEntityParallel` |
| --- | --- | --- | --- |
| Raids ausgelöst | 18 | 18 | 17 |
| Bad Omen entfernt | 0 | 0 | 0 |
| Fläschchen getrunken | 0 | 1 | 0 |
| Wellen gespawnt | 118 | 89 | 105 |
| Siege / Niederlagen | 10 / 0 | 3 / **7** | 8 / 0 |
| Kills des Spielers (Pillager) | 488 | 386 | **0** |

Der Omen-Fix wirkt: 3.1.5 löst genauso viele Raids aus wie Vanilla. Die
Killzahlen dieser historischen Läufe sind als Leistungsvergleich ungeeignet:
Der Client hielt den Spieler über dem Boden fest (Diagnose unten).

## Glocken-Freigabe: auch in Vanilla nachgewiesen

Die dauerhafte Dorf-Quelle der Farm ist die Glocke bei (-1, 192, -1), die ein
Villager als `MEETING_POINT` hält. Unter
3.1.5 (seriell) gab der Villager sie ab Minute ~6 alle ~200 Ticks frei
(22-mal in 10 Minuten; parallel 8-mal). Die erste Lücke dauerte drei Ticks,
die übrigen 21 Übernahmen erfolgten im selben Tick wie die Freigabe. In der
ersten Lücke war das Raid-Zentrum (-8, 184, 8) kein Dorf: sieben laufende Raids
gingen gleichzeitig verloren (`LOSS` bei t=8833579), ein gerade gestarteter Raid
stoppte bei Countdown ~295. Das ist ein möglicher Mechanismus für
„Leiste verschwindet nach wenigen Prozent“.

Die frühere Behauptung „in Vanilla nie“ war falsch. Die anschließenden
neunminütigen Läufe `clock-vanilla` / `clock-fab315` zeigen **je sechs**
Glocken-Freigaben und **keine** Niederlage; beide lösen 15 Raids aus. Die erste
Lücke dauerte sechs (Vanilla) bzw. fünf Ticks (Fabrolia), weitere Übernahmen
erfolgten jeweils im selben Tick. Eine Freigabe allein belegt also keine
sichtbare Dorf-Lücke oder verlorenen Raid.
Der vollständige Stack nennt in beiden Fällen
`SetWalkTargetFromBlockMemory` → `Villager.releasePoi(MEETING_POINT)`.
Die Vanilla-Aufgabe verwirft den POI, wenn
`getGameTime() - CANT_REACH_WALK_TARGET_SINCE > tooLongUnreachableDuration`.
`VillagerGoalPackages.getPreRaidPackage` setzt dieses Limit für den Meeting-Point
auf 200 Ticks. Die Zeitprüfung steht vor der Prüfung der Entfernung zur Glocke:
Auch ein direkt daneben stehender Villager kann einen alten Zeitstempel deshalb
nicht durch seine Nähe unschädlich machen.
Nach einer Freigabe lagen wiederholte Abstände bei 201 Ticks (Vanilla und
Fabrolia). Unter Fabrolia lagen Kontextuhr und reale Uhr konstant drei Ticks
auseinander. Nach jeder beobachteten Freigabe wurde der Zeitstempel auf die
Kontextuhr gesetzt; die spätere Freigabe verglich damit wieder auf einem Worker.
Diese Messung liefert keinen Beleg für eine Vermischung der Uhren und keinen
Anlass, Villager-AI oder POI-Freigaben zu ändern.

## Fehlende Spieler-Kills: Fehler im Testclient

Null Kills traten nicht nur im Parallel-Modus auf: `clock-vanilla` hatte ebenfalls
null Spieler-Kills, `clock-fab315` dagegen 443 Pillager-Kills. Gesendete Pakete
belegen daher keine korrekten Rundumschläge.

`PlayerAttackProbeMixin` protokolliert jetzt den tatsächlichen Eintritt in
`Player.attack`, Ziel, Schwert, Angriffsstärke, `onGround` und `doSweepAttack`.
Die Diagnoseläufe `attack-*` zeigen: Das Ziel ist der richtige Rüstungsständer,
das Schwert ist vorhanden und die Angriffsstärke voll. Der alte Client hielt aber
den gespeicherten Spielerstand Y=76,12 ohne Schwerkraft fest. Der Boden (obere,
geschlossene Falltür bei (0,75,14)) liegt bei Y=76,0. Abhängig vom Paketzeitpunkt
stand `onGround` beim Angriff auf `false`; dann gibt es keinen Rundumschlag.
Das erklärt, warum ein Lauf trotz hunderter empfangener Angriffe null Kills
erzielen konnte, und widerlegt die Zuordnung allein zum Parallel-Modus.

Der Client verwendet nun Y=76,0, wo ein echter Client nach dem Fallen steht,
und sendet seine periodische Position vor dem Angriff. Die Farmblöcke und das
Kampfverhalten des Servers werden nicht verändert. Die neuen Berichte enthalten
auch das gehaltene Item und das ausgewählte Angriffsziel. Ein Auswerter
`tools/raid-farm-ab/summarize.py` zählt empfangene Angriffe und Rundumschläge
aus dem Diagnose-Log und erfasst die SHA256-Summen der tatsächlich geladenen Jars.

## Vergleich mit korrigiertem Client

Neue Läufe `grounded-vanilla`, `grounded-fab315`, `grounded-fab315-par` sowie
`grounded-fab313`: gleiche Original-Welt, Schwert und Eingaben, jetzt auf dem
Boden. Vergleich der ersten fünf Minuten Bedienzeit (je ca. 6.030 Client-Ticks;
3.1.3 endet nach fünf Minuten, die anderen Läufe laufen neun Minuten):

| | Vanilla + Lithium | 3.1.3 | 3.1.5 | 3.1.5 parallel |
| --- | --- | --- | --- | --- |
| Raids ausgelöst | 8 | 1 | 8 | 8 |
| Pillager-Kills | 152 | 34 | 169 | 166 |
| Vindicator-Kills | 105 | 30 | 122 | 106 |
| Siege | 1 | 1 | 1 | 1 |
| Fläschchen getrunken | 0 | 0 | 0 | 0 |

Unter 3.1.3 wird Bad Omen bei der ersten Umwandlung entfernt; unter Vanilla und
beiden 3.1.5-Modi bleibt es erhalten. Das bestätigt den Effekt-Map-Fehler auch
mit dem korrigierten Client. Die Zahlen sind ein Funktionsvergleich, keine
Messung von Items pro Stunde; Raid-Spawns und AI-Zufall variieren zwischen Läufen.

Endstand nach neun Minuten Bedienzeit:

| | Vanilla + Lithium | 3.1.5 | 3.1.5 parallel |
| --- | --- | --- | --- |
| Raids ausgelöst | 15 | 16 | 16 |
| Wellen | 92 | 85 | 85 |
| Siege / Niederlagen | 8 / 0 | 7 / 6 | 7 / 6 |
| Pillager-Kills | 392 | 371 | 368 |
| Tatsächliche Angriffe / Rundumschläge | 542 / 542 | 542 / 542 | 542 / 542 |
| Glocken-Freigaben | 12 | 7 | 7 |

Alle drei Läufe halten Bad Omen und brauchen kein weiteres Fläschchen. Der
Kampf-Befund „Parallelmodus hat keine Spieler-Kills“ ist damit widerlegt.
Die späten Niederlagen treten dagegen auch mit korrigiertem Client auf.

Die erste Glocken-Lücke in `grounded-vanilla` dauert drei Ticks ab t=8833654.
Die Raids sehen das fehlende Dorf, verlegen ihr Zentrum aber von (-8,184,8)
nach (-8,152,8), in die Nähe des besetzten Arbeitsplatzes (0,143,18), und laufen
weiter. In `grounded-fab315` beginnt die erste Lücke bei t=8835377 (12 Ticks),
parallel bei t=8835389 (ein Tick). Die sechs Niederlagen folgen jeweils im
nächsten Raid-Tick; ein neuer Raid stoppt bei Countdown 281 bzw. 286 von 300
(nach etwa 5–6 %). Der Arbeitsplatz (0,143,18) wird erst in der anschließenden
Entity-Phase beansprucht, also nach der gescheiterten Verlegung.

Damit sind Mechanismus und die maßgebliche Reihenfolge bekannt. Die reine
Glocken-Freigabe ist weiterhin kein Beleg für eine falsche Freigabe: In Vanilla
gibt es sie ebenfalls. Noch zu trennen sind der Zeitpunkt der Freigabe relativ
zur bewegten Arbeitsplatz-Leiter und eine mögliche Abweichung in dieser
Reihenfolge. Keine Schonfrist, erzwungene POI-Belegung oder Änderung der
Vanilla-Verlustregel wurde eingebaut.

## Kontrollversuch: frühes Stoppen bei fehlendem Ersatzdorf ist Vanilla

`bell_gap_control.py` erzeugt in den **kopierten Testwelten** gezielt den
fehlenden Dorfzustand: Nach Beobachtung des ersten Raids wartet es 18 Main-Ticks,
entfernt Glocke und den Ersatz-Arbeitsplatz (0,143,18) für sechs Main-Ticks und
stellt deren ursprüngliche Blockzustände wieder her. Die Dauer wird allein auf
der realen Weltuhr geplant; Worker-Zeitstempel aus dem Probe-Log werden nicht
mit Main-Zeitstempeln verglichen. Das ist ein Eingriff, kein unveränderter
Referenzlauf und keine Messung natürlicher Villager-Freigaben.
Die Wiederherstellung der Blöcke stellt frühere POI-Tickets nicht automatisch
wieder her; ausgewertet wird der erste Raid-Abbruch nach dem Eingriff.

| Lauf | Erster Raid | Beobachtung |
| --- | --- | --- |
| `village-gap-vanilla` | `22b47a56` | `STOP`, `village=false`, Zentrum (-8,184,8), 0 Wellen, Countdown 282, t=8827553 |
| `village-gap-fab315` | `5e63c440` | `STOP`, `village=false`, gleiches Zentrum, 0 Wellen, Countdown 280, L=8827513 |

Beide Kontrollen wurden vollständig ausgeführt und beide Blöcke wiederhergestellt
(`bell-gap-control.json`). Vanilla stoppt somit bei demselben fehlenden Dorfzustand
ebenfalls nach etwa 6 % der Vorbereitungsanzeige. Das ist kein Beleg dafür, dass
die natürliche Freigabe unter Fabrolia immer zum richtigen Zeitpunkt erfolgt;
es belegt die gemeinsame Verlustregel und verbietet, sie als Farm-Fix zu umgehen.

## Nachweis und Übergabe

- Maschinenlesbare Ergebnisse und SHA256-Summen der geladenen Jars:
  [raid-farm-2026-10-17.json](evidence/raid-farm-2026-10-17.json).
- Vollständige lokale Reports, Eingaben und Logs:
  `build/raid-farm-ab/<Lauf>/report.json`, `raidprobe.log`, `console.log`.
  `grounded-*` sind die korrigierten natürlichen Läufe; `village-gap-*` die
  absichtlich veränderten Kontrollen. Die früheren `floor-*` brachen wegen eines
  inzwischen behobenen Fehlers im Driver vor der Bedienung ab; `gap-*` waren
  Vorversuche mit anderer Intervention und ersetzen diese Kontrollen nicht.
- `:common:test :fabric:build :neoforge:build`: erfolgreich (1480 Tests bestanden,
  8 optionale Tests übersprungen). `check_jar.py`: erfolgreich, Java 25.
- Weiter untersuchen: Freigabe des Glocken-Meeting-Points relativ zum Verlust
  und zur erneuten Belegung von (0,143,18). In den späten Verlustfällen erfolgt
  dessen nächste Belegung **nach** dem Raid-Tick. Dafür zuerst `AcquirePoi`,
  `GoToPotentialJobSite` und die Aktivitätswechsel mit den natürlichen Logs
  vergleichen; keine Zeitstempel-Migration oder fehlerhafte POI-Belegung ist
  bislang nachgewiesen. Die gemeinsame Vanilla-STOP/LOSS-Regel beibehalten.

## Offen

- Der Mechanismus des „5 %“-Verschwindens ist nachgestellt: Glocken-Lücke ohne
  erreichbares Ersatzdorf, dann die Vanilla-STOP-Regel. Offen bleibt, ob der
  unterschiedliche Freigabezeitpunkt in natürlichen Läufen eine weitere
  Fabrolia-Abweichung oder unterschiedliche Vanilla-AI-Zufallsverläufe darstellt.
  Andere Läufe mit demselben Jar hatten keine Niederlagen; auch Vanilla gibt die
  Glocke frei. Weitere Vermutung, nicht nachgestellt: Raid Omen läuft nicht im
  Takt der Uhr ab, etwa
  wenn der Spieler mit Bad Omen anderswo in der Farm ein Dorf-Fenster erwischt; die
  Dorf-Leiter zur Glocke besteht nur kurz nach dem Puls. Bis 3.1.0 verzögerte
  zudem die POI-Veröffentlichung auf Inseln genau diese Leiter (behoben in 3.1.1).
  Der konkrete Produktions-Villager und seine gespeicherten Erinnerungen sind
  nicht Teil der heruntergeladenen Autorenwelt; die Produktionsursache ist damit
  noch nicht vollständig zertifiziert.
- Nicht lokal enthalten: ServerCore, C2ME, VMP, Rustmi und die übrigen
  Produktions-Mods; kein 40-Client-Stand-Lauf (Linux).
- Der Client bildet das Verhalten eines echten Clients nach (Regeln im README des
  Werkzeugs), ohne eigenen Raytrace; Vanilla und Fabrolia bekommen identische
  Eingaben.
