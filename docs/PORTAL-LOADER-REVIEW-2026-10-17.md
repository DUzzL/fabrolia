# Claude-Review: Insel-Takt, 2D-Farmen und Portal-Chunkloader

Geprüft wurden die Änderungen aus dem bereitgestellten Claude-Chat:
`87da3ff` (3.1.3, Info-Befehle/TPS-Fenster) und insbesondere `a550b3e`
(3.1.4, Anzeige und CrossDimLinks-Phasenheilung), außerdem ihr Zusammenspiel
mit dem aktuellen Raid-Fix in `0cdd1dd` (3.1.6). „2D-Farmen“ bezeichnet hier
wie im Chat Farmen zwischen Overworld und Nether.

## Codebefund

Die eigentliche Korrektur ist schlüssig: Ein überfälliger Termin wird beim
Vergleich als jetzt fällig behandelt. Ein gemeinsamer neuer Termin wird auf
`max(frühester Termin, jetzt)` begrenzt. Dadurch zieht eine wartende Insel
einen gesunden Partner nicht allein wegen ihres alten Termins vorzeitig in
einen Tick. Ein tatsächlich weit vorausliegender Termin wird weiterhin
angeglichen. `tryAnchor` erwirbt weiterhin die beteiligten Claims, bevor es
Termine oder Pacing-Generationen schreibt; die Korrektur führt keine
Chunk-/POI-Zugriffe auf fremde Inseln ein.

Die Änderung schreibt weder Insel-Uhren noch Portal-Cooldowns, Ankunftsregeln
oder Chunk-Tickets um. Registry, Projektionsgeometrie, gemeinsame Rate und
die Suspendierung durch Sprint/Coherence bleiben erhalten. Die TPS-Fenster
und die Klassifizierung arbeiten auf Zählern beziehungsweise Anzeigen.

Die Aussage im Chat, die Transferstrecke habe „keinerlei Bezug“ zur Kopplung,
ist zu weitgehend: Es gibt dort keinen direkten Zugriff auf das
Coupling-Objekt, aber die Taktung bestimmt natürlich, wann die beteiligten
Chunks und Entities ticken. Deshalb genügt der unveränderte Quelltext der
Portalstrecke allein nicht als Funktionsnachweis.

Ein **Anzeigefehler in 3.1.4 bis 3.1.6** wurde zur Laufzeit bestätigt:
`IslandMetrics.row` verwendete für `top verbose`
`TemporalControl.effectiveTargetNanos` statt der gemeinsamen Zielrate.
Bei `rate 10` auf der Overworld-Hälfte zeigt der Nether-Partner deshalb
`tps=10.0/20.0`, obwohl er korrekt mit 10 TPS tickt und `crossdim` korrekt
`sharedTps=10.0` meldet. Die normalen Info-/HUD-Zeilen verwenden bereits
`IslandMetrics.targetTps`. Der anschließende Fix in **3.1.7** verwendet nun
auch für die ausführliche Zeile `targetNanos(rs, p)`, einschließlich der
Markierung `RATE-OVERRIDE`. Der ergänzte Verhaltenstest prüft die tatsächliche
Formatierung bei geerbten 10 TPS und nach der Rückkehr auf 20 TPS.

Die großen geometrischen Kopplungsgruppen bestehen weiterhin: Eine
Nether-Zelle kann mehrere Overworld-Inseln verbinden. Ein Rate-Befehl wirkt
auf die ganze Gruppe. Die Änderung begrenzt Gruppen nicht auf echte Portale.

## Laufzeitprüfung

Reproduzierbarer Windows-/Linux-Treiber:
[`tools/stand/portal-parity-driver.py`](../tools/stand/portal-parity-driver.py).
Er startet isolierte Minecraft-26.3-Fabric-Server auf Loopback mit Java 25.
Fabric API und Carpet sind in allen Vergleichsläufen gleich; die aktuellen
3.1.6-Läufe enthalten zusätzlich Lithium 0.26.2. Die Sampler-Vergleiche
zwischen 3.1.3 und 3.1.4 verwenden denselben Spark-Mod und dieselbe Last.

Der Aufbau besteht aus echten Portalblöcken bei Overworld `(4096,65,4096)`
und Nether `(512,65,512)`. Je zwei sich gegenseitig beobachtende Observer
treiben einen Dropper, der Items durch das Portal schießt. Hopper und Kisten
fangen die Ankünfte auf. Insgesamt liegen 2304 Items in den beiden
Versorgungen. Die beiden Richtungen verwenden verschiedene Itemtypen, sodass
die Inventarausgaben auch die tatsächliche Ziel-Dimension nachweisen.

Ein echter Protokollclient bleibt bei `(0,80,0)` in der Overworld. Er hält den
Server aktiv und lädt keine Farm-Chunks. Forceload-Tickets dienen nur dem
Aufbau und werden vor der Messung in beiden Dimensionen vollständig entfernt.
Danach erzeugen ausschließlich die physischen Portaltransfers neue
Portal-Tickets; weder Funktionen noch Befehle füttern den Loader weiter.

Geprüfte Bedingungen:

- Acht echte Schweine pro Richtung müssen durch Berührung der Portalblöcke
  in der jeweils anderen Dimension ankommen. Kein `/tp` ersetzt den Transfer.
- Nach Entfernen aller Forceloads wächst der Item-Eingang auf beiden Seiten
  auch nach 25 und 50 Sekunden weiter, über mehrere Portal-Ticket-Laufzeiten.
- Die Fabrolia-Gruppe muss aktiv gekoppelt und verankert sein. Ein `rate 10`
  auf einem Mitglied muss beide Hälften erreichen; beide transportieren weiter
  und kehren nach dem Zurückstellen auf 20 TPS zurück.
- Die Stressläufe belasten genau ein Gruppenmitglied über den bestehenden
  Debug-Hook mit 200 ms pro Einheit. Der Loader darf nicht auslaufen und muss
  nach dem Entfernen dieser Last wieder weiterarbeiten.
- Nach Abschalten beider Observer-Uhren werden Vorräte, Empfangsinventare
  und lose Item-Stacks addiert: exakt 2304, ohne Verlust oder Duplikation.
  Auch zusammengeführte Stacks zählen mit ihrem tatsächlichen `Item.count`.
  Die Schlusszählung erfolgt bei eingefrorener Simulation; anschließend wird
  für die Ticket-Ablaufprüfung wieder normal weitergetickt.
- Anschließend werden lose Items entfernt, damit keines mehr ein Portal
  berühren kann. 40 Sekunden später müssen beide Farm-Chunks ungeladen sein.
  Diese negative Kontrolle erkennt versteckte dauerhafte Ladetickets.
- Sauberer Server-Stopp, keine Crash-Reports und keine Runtime-Exceptions.

Alle Entity-Selektoren haben eine explizite räumliche Einschränkung. Nur
`execute in <Dimension>` reicht nicht: Ein `@e` ohne räumliches Prädikat kann
auch Entities anderer Dimensionen finden und einen falschen Erfolg melden.

Ein Vorlauf meldete 2303 statt 2304 Items, weil ein Versorgungshopper während
der getrennten RCON-Abfragen ein Item in den bereits gelesenen Dropper schob.
Die offline gelesenen Region-/Entity-NBT-Dateien der sauber gespeicherten Welt
enthalten exakt 1152 Quartz und 1152 Glowstone Dust. Der Inventarvergleich
lokalisierte das vermeintlich fehlende Item im Dropper. Deshalb verwenden
alle abschließenden Vergleichsläufe dieselbe eingefrorene Schlusszählung.

## Abschließende Ergebnisse

Alle fünf Läufe verwenden dieselbe Treiberversion v2 und bestehen vollständig:

| Server | Entity-Modus | Items OW → Nether | Items Nether → OW | Bilanz | Beide Chunks entladen |
| --- | --- | ---: | ---: | --- | --- |
| Vanilla | Vanilla | 184/184 | 184/184 | 2304/2304 | ja |
| 3.1.3 vor Claudes Taktkorrektur | parallel | 308/308 | 327/327 | 2304/2304 | ja |
| Claudes 3.1.4 | parallel | 306/306 | 327/327 | 2304/2304 | ja |
| 3.1.6 + Lithium | parallel | 308/308 | 328/328 | 2304/2304 | ja |
| 3.1.6 + Lithium | seriell | 306/306 | 325/325 | 2304/2304 | ja |

Die Brüche zeigen angekommen/abgeschickt, einschließlich loser Ankunfts-Stacks.
Zusätzlich kommen in jedem Lauf acht Schweine pro Richtung korrekt an. Alle
Fabrolia-Läufe bestehen Überlast und Erholung sowie die Rate-Wechsel 20 → 10
→ 20. Alle Server stoppen sauber, ohne Runtime-Exceptions oder Crash-Reports.
Die Mengen zwischen Vanilla und Fabrolia sind kein Ertragsvergleich:
Die Fabrolia-Läufe enthalten zusätzliche Überlast- und Rate-Phasen.

Während der Überlastphase steigt die Anzahl der Anker im 3.1.3-Vergleich
von 1 auf 20. In 3.1.4 und beiden aktuellen Modi bleibt sie bei 1: Die alte
Wiederverankerung durch einen lediglich überfälligen Partner entfällt.
Die erwarteten Tick-Verluste der absichtlich überlasteten Insel werden
weiterhin nicht aufgeholt und nicht durch Umschreiben ihrer Uhr verborgen.

Im getesteten Aufbau beeinträchtigt die Taktkorrektur weder Portaltransfers
noch den autonomen Chunkloader. Eine weitere Runtime-Korrektur ergibt sich
daraus nicht. Der oben beschriebene Anzeigefehler wurde anschließend in 3.1.7
behoben; die ursprünglichen fünf Vergleichsläufe und ihre Hashes bleiben als
historische Nachweise erhalten.

## Nachprüfung des Anzeigefixes in 3.1.7

Der abschließende Serverlauf `display317` verwendet Lithium und
`--expect-shared-target`. Beide gekoppelten Zeilen zeigen nach `rate 10`
korrekt `10.0/10.0` und `RATE-OVERRIDE`; nach dem Zurückstellen korrekt
20 Ziel-TPS ohne Markierung. Mob-Transfers, alle 2304 Items und das Entladen
beider Farm-Chunks bestehen erneut. Der Server stoppt ohne Runtime-Fehler.

1486 Tests bestehen, acht optionale werden übersprungen; Fabric-/NeoForge-Build
und Fabric-Artefaktprüfung bestehen. Im Vergleich zum getesteten 3.1.6-Jar
ändert sich ausschließlich `IslandMetrics.class`; die übrigen 905 Klassen
sind bytegleich. Nachweise, beide Jar-Hashes und die tatsächlichen Zeilen:
[`verbose-target-2026-10-17.json`](evidence/verbose-target-2026-10-17.json).

## Nachweise und Grenzen

Der Vergleich der geänderten Main-Frames im identischen anfänglichen
Loader-Szenario verwendet Spark mit allen Threads und 4-ms-Intervall:
`CrossDimLinks.mainTick` enthält bei 3.1.3 insgesamt 4 ms in 55,216 s
(0,0724 ms/s), bei 3.1.4 ebenfalls 4 ms in 59,282 s (0,0675 ms/s).
Das ist jeweils nur ein Sample. Die übrigen geänderten Anzeige-/Heal-Frames
liegen in diesem ruhigen Fenster unter der Auflösung. Diese Werte belegen
keinen Performancegewinn und keine Kostenobergrenze; die Überlastkorrektur
wird durch die separaten Funktionsphasen und `CrossDimLinksTest` geprüft.

Ergebnisse, Befehlsantworten und Hashes werden in
[`evidence/portal-loader-2026-10-17.json`](evidence/portal-loader-2026-10-17.json)
gesichert. Rohdaten liegen unter `build/portal-parity/`; unfertige oder wegen
Treiberfehlern verworfene Vorläufe werden ausdrücklich getrennt ausgewiesen.

Der vollständige Java-Testlauf wurde ohne Build-Cache erneut ausgeführt:
1486 bestanden, acht optionale übersprungen, keine Fehler. Fabric- und
NeoForge-Build sowie die Prüfung des verpackten Player-Tick-Codes bestanden.

Die Prüfung deckt Portaltransport und Chunkloader als zentrale Mechanik der
genannten 2D-Farmen ab. Sie ist kein Ertragsvergleich eines beliebigen
konkreten Farmdesigns und kein Nachweis für den kompletten Produktions-Modsatz
oder Langzeitbetrieb. Die Produktionsdaten für den 40-Client-Stand und WSL
fehlen lokal; `stand.sh verify`, dessen Perf-Kampagne und der vollständige
Modpack-Mixin-Export wurden hier nicht ausgeführt. Es wird kein allgemeiner
Performancegewinn behauptet. Produktions-Tag und `release/` bleiben unverändert.
