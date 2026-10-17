# Raid-Reihenfolge und Dorf-Verlegung (17.10.2026)

Fortsetzung der [Omen-Diagnose](RAID-FARM-OMEN-2026-10-17.md). Die ursprüngliche
Farm-ZIP aus Downloads wird unverändert als Eingabe verwendet; jeder Versuch
läuft auf einer eigenen Kopie mit einem echten 26.3-Protokoll-Client.

## Ursache

Vanillas `Raids.raidMap` ist eine `Int2ObjectOpenHashMap` mit der normalen
Anfangsgröße 16 (32 Tabellenslots). `getNearbyRaid` ersetzt den bisherigen
Treffer nur bei **kleinerem** Abstand. Bei gleichem Abstand gewinnt der zuerst
durchlaufene Eintrag. Diese Farm hat viele Raids am selben Zentrum (-8,184,8),
aber in unterschiedlichen Phasen. Die Auswahl ist deshalb sichtbar: Sie
bestimmt unter anderem die Villager-Aktivität `raid` oder `pre_raid`.

Fabrolia ersetzte die Registry durch eine `Int2ObjectConcurrentHashMap`, deren
Streifen eine andere Reihenfolge liefern. Zusätzlich tickten Inseln ihre
`assignedRaids` in der Reihenfolge eines nebenläufigen Sets. Beide Reihenfolgen
wichen von Vanilla ab. Ein gestoppter Raid wurde außerdem sofort nach seinem
Tick gelöscht; Vanilla löscht erst, wenn er am Anfang der nächsten Raid-Phase
bereits gestoppt ist. Auch das verändert die Tabelle und spätere Gleichstände.

Der Glocken-Villager darf seinen Meeting-Point nach einem alten
`CANT_REACH_WALK_TARGET_SINCE` freigeben. Das passiert auch ohne Lithium in
Vanilla. Der entscheidende Unterschied war die Phase relativ zur bewegten
Arbeitsplatz-Leiter: Die alten Fabrolia-Läufe hatten bei der Glocken-Lücke kein
besetztes Ersatzdorf und verloren mehrere Raids. In den korrigierten Läufen
verlegen dieselben Vanilla-Regeln die Raids nach (-8,152,8), an den besetzten
Arbeitsplatz (0,143,18). Es wurde keine Schonfrist oder künstliche POI-Belegung
eingebaut.

## Änderung in 3.1.6

- Die Registry verwendet eine veröffentlichte `CopyOnWriteInt2ObjectMap` mit
  Vanillas Anfangsgröße. Änderungen klonen die native Tabelle einschließlich
  ihrer Kapazität und Slot-Reihenfolge. Ein Map-Kopierkonstruktor würde neu
  hashen und reicht deshalb nicht aus.
- Die Installation erfolgt direkt nach der Feldinitialisierung, bevor der
  Codec gespeicherte Raids einfügt. Eine Kopie der schon gefüllten,
  umgeschriebenen Map am Konstruktorende hätte die falsche Reihenfolge geerbt.
- Eine Insel durchläuft einen privaten nativen Registry-Cursor und filtert
  ihre Abonnements. Das Set entscheidet über Zuständigkeit, die Registry über
  Reihenfolge. Main verwendet dieselbe Reihenfolge in seinem bestehenden Fence.
  Die Tabelle wird erst beim ersten tatsächlichen Löschen kopiert; der Cursor
  stellt dabei seine Position durch den unveränderten gelesenen Präfix wieder
  her. Reine Tick-Phasen brauchen keine Tabellenkopie.
- Der Cursor erhält auch Fastutils Iterator-Löschung über mehrere Einträge
  hinweg, einschließlich der Sonderbehandlung über den Tabellenrand
  verschobener Schlüssel. Fremde Registrierungen werden bei unabhängigen
  Insel-Phasen nicht mit einer älteren Kopie überschrieben.
- Ein Stop während `Raid.tick` bleibt bis zur nächsten Raid-Phase registriert.
  Ein Topologiewechsel erhält das nötige Aufräum-Abonnement.
- Verspätete Main-Abonnements bereits entfernter, gestoppter Raids werden weiter
  bereinigt, auch wenn der Registry-Cursor sie nicht mehr erreichen kann.

Registry-Auswahl und strukturelle Löschungen verwenden die Lock-Reihenfolge
Manager → Map. Raid-Ticks und Welt-Callbacks laufen außerhalb dieser Monitore
unter dem jeweiligen Insel-Claim. Andere Inseln behalten ihre eigene Kadenz.

## Regressionstests

`RaidRegistryOrderTest` führt die echte Minecraft-Raid-Suche und Fabrolias
veröffentlichte Suche auf denselben Raids aus. Die alte gestreifte Map wählt
nachweislich andere Gleichstände; die neue Tabelle stimmt bei Einfügungen,
Vergrößerungen und Löschungen mit Vanilla überein.

`CopyOnWriteInt2ObjectMapTest` prüft die Tabellenreihenfolge, normales Löschen,
Iterator-Löschen und erneutes Einfügen nach einer leeren Registry. Der erste
neue Reihenfolgetest schlug vor der Korrektur bereits beim dritten Eintrag fehl.
Vier gleichzeitige Eigentümer räumen ihre Einträge auf und registrieren neue,
ohne fremde Änderungen zu verlieren oder veröffentlichte Leser zu verändern.

`RegionPreparationRoutedCommandTest` prüft die echte Insel-Dispatch-Strecke:
Reihenfolge unabhängig vom Abonnement-Set, Übergabe bei Topologieänderung und
Löschung erst in der Phase nach dem Stop. Die vorhandenen Tests zur sofortigen
Vanilla-STOP/LOSS-Regel und zur Spawn-Zulassung bleiben bestehen.

## Laufzeitnachweis

Erste frische Vergleichskampagne, jeweils zwölf Minuten Bedienzeit, korrigierter
Client. Die drei `order-*`-Läufe verwenden den Vorabbuild der Korrektur
(Jar-Metadaten noch 3.1.5; SHA256 beginnt mit `36c2f9e4`). Sie sind vom finalen
3.1.6-Artefakt zu unterscheiden.

| Lauf | Neue Raids | Wellen | Siege / LOSS im Probe | Pillager-Kills |
| --- | ---: | ---: | ---: | ---: |
| `brain-vanilla` (+ Lithium) | 21 | 141 | 14 / 0 | 629 |
| `brain-fab315` (unverändert, + Lithium) | 22 | 123 | 8 / 7 | 559 |
| `order-fab` (Korrektur, + Lithium) | 21 | 145 | 14 / 0 | 634 |
| `order-par` (Korrektur parallel, + Lithium) | 21 | 142 | 14 / 0 | 609 |
| `brain-pure` (ohne Lithium) | 21 | 137 | 13 / 0 | 586 |
| `order-pure` (Korrektur ohne Lithium) | 21 | 145 | 15 / 0 | 619 |

Jeder tatsächlich akzeptierte Angriff löst einen Rundumschlag aus; Bad Omen
bleibt erhalten und es wird kein weiteres Fläschchen getrunken. Der Probe zählt
auch die letzten Server-Ticks beim Beenden; ein dabei erst nach dem Spieler-
Disconnect abgeschlossener Raid kann im Spieler-Scoreboard fehlen.

Alle korrigierten Varianten durchlaufen die natürliche Glocken-Lücke ohne
Niederlage. In `order-fab` bei Inselzeit 8833528, `order-par` bei 8833588 und
`order-pure` bei 8833563 verlegen die Raids ihr Zentrum im folgenden Raid-Tick
nach (-8,152,8). Auch der junge Raid bei Countdown 278/300 bleibt aktiv.
Der Parallel-Lauf übersteht eine 19 Ticks lange erste Glocken-Lücke. Der Fix
verhindert somit keine Freigabe; er stellt die vorausgehende Auswahl und die
erfolgreiche Vanilla-Verlegung wieder her.

Abschließende Läufe mit der unveränderten finalen Jar, SHA256
`133fff29ce71f44e1e657c79639b9a4fb99bd69e553fd0aae32ca354c36dd898`:

| Lauf | Minuten | Neue Raids | Wellen | Siege / LOSS | Stop vor erster Welle |
| --- | ---: | ---: | ---: | ---: | ---: |
| `release-fab316` (seriell) | 8 | 13 | 86 | 7 / 0 | 0 |
| `release-par316` (parallel) | 12 | 21 | 146 | 14 / 0 | 0 |
| `perf-old315` (unveränderte 3.1.5) | 8 | 14 | 82 | 6 / 7 | 1 (Countdown 287) |
| `repeat-vanilla` | 20 | 37 | 270 | 28 / 0 | 0 |

Die frischen Vergleiche verwenden dasselbe Eingabemanifest (115 Dateien,
SHA256 `656a3f9f496ea9b30aaf27f17a84d74d66f6ca04624b77639e7c264099d98bfd`).
Der Endzähler kann wegen des Startfensters um einen Raid schwanken; das ist
kein deterministischer RNG-Replay. Alle erfolgreichen Raids haben acht Wellen.
486/486 akzeptierte Angriffe im seriellen und 725/725 im parallelen Lauf sind
Rundumschläge. Bad Omen wird nie entfernt. Im ersten Bedienintervall des finalen
Parallel-Laufs wird ein Fläschchen verwendet, danach keines mehr; die restlichen
Umwandlungen und Raids sind selbsttragend. Es wird keine Items-pro-Stunde-Rate
aus diesen kurzen Läufen hochgerechnet.

Der Neustart-Probe rekonstruiert die native Referenz außerhalb eines Mixin-Ziels.
Ein `new Int2ObjectOpenHashMap` direkt im injizierten Diagnose-Handler wurde
ebenfalls von Fabrolias globalem Transformer ersetzt und meldete fälschlich
eine abweichende Reihenfolge. Nach dieser Diagnosekorrektur stimmen die sieben
geladenen Registrierungen mit der echten nativen Referenz überein. Der erste
Neustart-Versuch prüfte zudem den Hebel vor dem Laden durch einen Spieler und
endete vor der Bedienung mit „position is not loaded“; er zählt nicht als
Farm-Vergleich. Der korrigierte Treiber wartet nach dem Beitritt auf den Chunk.

`restart-fab316b` und `restart-vanilla` laden exakt dieselben 134 gespeicherten
Dateien (Manifest-SHA256
`01c163ec930714531c215044c21197b5eb17336422d45b7fa5b8d4c98ab35522`).
Beide verlieren beim ersten Laden dieselben drei alten Raids im Welt-Tick
8835944: Wellenstand 8/8/6, Countdown 0/0/39 und aktive Ticks 4785/4183/3581.
Beide stoppen außerdem einen am Bedienplatz gestarteten Raid sofort bei
Countdown 300 und null Wellen. Diese Vanilla-Ergebnisse werden erhalten.
Danach entstehen in vier Minuten jeweils acht neue Raid-Objekte; beide Läufe
erreichen drei weitere Siege, mit 41 bzw. 43 Wellen und ohne weitere Verluste.
Beim Einlesen sind alle sieben gespeicherten Registrierungen vorhanden und
stimmen in ihrer Reihenfolge mit der nativen Referenz überein.

## Profile und Prüfung

Alle Thread-Profile laufen nach 180 Sekunden Bedienzeit für etwa 120 Sekunden,
mit 4-ms-Intervall und lokalem Export. Windows verwendet Sparks Java-Engine;
die inklusiven Werte enthalten auch Wartezeit. Vergleich derselben seriellen
Farm-Szene, Millisekunden pro gezähltem Main-Tick:

| Worker-Frame | 3.1.5 (`perf-old315`) | Finale 3.1.6 (`release-fab316`) |
| --- | ---: | ---: |
| `RaidIslandDispatch.service`, inklusive | 0,52405 | 0,77248 |
| davon `tickOwned` mit Vanilla-Raid-Arbeit | 0,41459 | 0,66308 |
| Differenz außerhalb des Raid-Bodys | 0,10945 | 0,10941 |
| Native Cursor-Erstellung | entfällt | 0,01160 |

Die Raid-Arbeit variiert mit Spawn- und AI-Zufall; die Verwaltung außerhalb
dieser Arbeit liegt im gemessenen Fenster praktisch gleichauf. Im parallelen
Vergleich fällt der Cursor-Anteil nach dem verzögerten Kopieren von 0,07794
auf 0,00664 ms/Tick. Das sind lokale Sampler-Messungen mit einem Farm-Spieler,
keine allgemeine Performancezusage für den Produktionsserver.

Reproduzierbare Auswertung:

```bash
python -I tools/raid-farm-ab/summarize.py build/raid-farm-ab/release-fab316 build/raid-farm-ab/release-par316
python tools/raid-farm-ab/profile_costs.py OLD.sparkprofile NEW.sparkprofile
```

`gradlew.bat :common:test :fabric:build :neoforge:build`: 1486 Tests bestanden,
acht optionale übersprungen, beide Builds erfolgreich. Fabric-Artefaktprüfung
und Architekturprüfung bestanden (913 getestete Dateien bytegleich im Jar).
Alle abschließenden Server speichern und stoppen sauber; keine Crash-Reports
oder Fail-Stop-Meldungen. Maschinenlesbare Ergebnisse, Jar- und Profil-Hashes:
[raid-registry-2026-10-17.json](evidence/raid-registry-2026-10-17.json).

## Grenzen

Der lokale Farm-Vergleich enthält Fabric API, Carpet und wahlweise Lithium
0.26.2. Er ersetzt keine Prüfung aller Produktions-Mods. Linux/WSL und die
Produktionsdaten unter `stand/` fehlen hier; der vollständige 40-Client-Stand
und sein Modpack-Mixin-Export sind deshalb nicht gelaufen. Produktions-Tag
und `release/` bleiben unverändert.
