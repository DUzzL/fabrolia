# TNT und World Eater: Codeprüfung vom 10.10.2026

Es gibt bestätigte Abweichungen von Minecraft 26.3, die TNT-Flugbahnen,
Kettenzündungen und Kolbenereignisse verändern. Sie benötigen weder einen
überlasteten Server noch ein bestimmtes World-Eater-Design. Der Bericht,
dass wiederholt dasselbe Modul betroffen ist, passt zu einem Grenzproblem.
Welcher Fehler den konkreten Schaden im Spieler-Replay auslöst, ist damit
noch nicht bewiesen.

Die Prüfung änderte keinen Produktionscode und keinen Mod-Jar. Maßstab war
der tatsächliche, unveränderte 26.3-Jar aus dem lokalen Loom-Cache, SHA256
`e59f412609beb8c3b42ce8a23d9958a709720455004e6f95fccccf93105d917b`.

## 1. Unterschiedliche TNT-Flugbahn durch Regionsreihenfolge

**Bestätigt; für den gemeldeten Grenzfall besonders relevant.**

Vanilla iteriert `EntityTickList` in ihrer Einfügereihenfolge. Eine Explosion
wendet den Impuls auf andere Entities sofort an. `PrimedTnt.tick` bewegt die
TNT vor dem Fuse-Abzug und der gegebenenfalls folgenden Explosion.

Fabrolia arbeitet dagegen eine Region nach der anderen ab:
[RegionizedServer.java](../common/src/main/java/com/axalotl/fabrolia/common/regions/RegionizedServer.java)
`tickOwned`, Zeilen 3931–3944. Die
[ColorGate.java](../common/src/main/java/com/axalotl/fabrolia/common/regions/ColorGate.java)
ordnet benachbarte Regionen nach ihrer Farbe, Zeilen 67–86. Diese Reihenfolge
kennt weder das Alter der TNT noch die Vanilla-Einfügereihenfolge. Die interne
ID-Sortierung in [Region.java](../common/src/main/java/com/axalotl/fabrolia/common/regions/Region.java)
stellt die Reihenfolge über eine Regionsgrenze nicht wieder her.

Konkreter Ablauf: ältere TNT A liegt bei X=128,5 in Farbe 1, jüngere TNT B
bei X=127,5 in Farbe 0. Beide gehören derselben Insel an und ihre Chunks
können vollständig geladen sein.

- Vanilla: A explodiert → B erhält den Impuls → B bewegt sich.
- Fabrolia: B bewegt sich → A explodiert → B erhält den Impuls.

Der Test verwendet echtes `PrimedTnt.tick` und `Entity.pushFromExplosion`
mit einem in beiden Fällen identischen, vorgegebenen Impuls. Nur die freie
Luftbewegung und die Portal-/Fluid-Umgebung werden minimal ersetzt. Die
Positionen unterscheiden sich nach dem ersten Tick um 0,875 Blöcke und nach
40 Entity-Ticks noch um 0,397947091 Blöcke. Die Fuse beträgt dann in beiden
Fällen 40. Dies beweist eine andere Flugbahn ohne verlängerte Zündschnur;
die konkrete Explosionsstreuung des World Eaters wird dabei nicht simuliert.

Auch innerhalb einer Region ist ID-Sortierung nur näherungsweise Vanilla:
Nach Entfernen und Wiederaufnahme einer älteren Entity ergibt die echte
Vanilla-Liste `[20,10]`, die Regionmenge `[10,20]`. Frisch erzeugte, durchgehend
geladene Entities haben normalerweise gleiche ID- und Einfügereihenfolge.

## 2. Derselbe TNT-Block kann an einer Grenze zweimal gezündet werden

**Bestätigt; zusätzliche TNT-Entities statt einer bloßen Verzögerung.**

[LevelMixin.java](../common/src/main/java/com/axalotl/fabrolia/common/mixin/world/LevelMixin.java),
Zeilen 73–82, verschiebt eine Blockänderung außerhalb der aktuellen Region
und meldet sofort Erfolg. Die Änderung wird erst nach der Entity-Phase
ausgeführt: [IslandTickUnit.java](../common/src/main/java/com/axalotl/fabrolia/common/regions/IslandTickUnit.java),
Zeile 1265.

Der tatsächliche Vanilla-Explosionscallback macht dagegen eine zusammenhängende
Operation: `BlockBehaviour.onExplosionHit` ersetzt den TNT-Block durch Luft
und ruft anschließend `TntBlock.wasExploded` auf. Dieser erzeugt sofort eine
gezündete TNT-Entity. Der Rückgabewert des Luft-Schreibens wird nicht geprüft.

Mit Fabrolia bleibt der Block bis zum Drain stehen, während die gezündete
Entity bereits existiert. Eine zweite Explosion aus derselben Quellregion
liest vor dem Drain erneut denselben TNT-Block und erzeugt eine zweite Entity.
Zwei nacheinander ausgeführte Explosionen auf einem Worker genügen; ein
gleichzeitiger Datenzugriff ist dafür nicht erforderlich. Die Aufnahme der
Entities prüft UUIDs, keine gemeinsame TNT-Blockposition.

Mit echten 26.3-Callbacks und tatsächlicher `PrimedTnt`-Erzeugung reproduziert:

| Luft-Schreiben | Gezündete TNT-Entities | Block vor dem Drain |
| --- | --- | --- |
| Vanilla unmittelbar | 1 | Luft |
| Wie Fabrolia verschoben | 2, verschiedene UUIDs | TNT |

Die isolierte Level-Implementierung bildet nur die vorhandene
`setBlock`-Verschiebung nach. Der vollständige transformierte Modpack- und
RegionScheduler-Ablauf wurde nicht gestartet; dessen Guard und
Drain-Reihenfolge sind zusätzlich im Produktionscode geprüft.

## 3. Neue TNT kann in der Nachbarregion einen Tick zu früh beginnen

**Bestätigter Aufnahmefehler.**

Vanillas `EntityTickList` besucht während ihrer Iteration neu hinzugefügte
Entities erst im nächsten Durchlauf.
[RegionizedServer.indexAdd](../common/src/main/java/com/axalotl/fabrolia/common/regions/RegionizedServer.java),
Zeilen 2711–2742, verhindert das nur, wenn die **Zielregion** bereits einen
Worker hat. Wird TNT während der Entity-Phase in eine erst später laufende
Nachbarregion eingefügt, ist deren Worker noch `null`. Ihre lebende
Entity-Menge enthält den Neuling beim späteren Start bereits. Die Markierung
für den laufenden Epoch wurde dann nicht verbraucht.

Der TNT-Pfad ist erreichbar: eine Explosion erzeugt die Nachbar-TNT sofort
über `TntBlock.wasExploded → addFreshEntity`, auch wenn das Luft-Schreiben
noch wartet. Ein zusätzlicher Test ruft echte `indexAdd`/`admitEntity` auf
und bestätigt die Tickberechtigung des Neulings im selben Epoch. Ein
einfacher Teststub stellt dort nur die `RegionKeyed`-Markierung bereit.

Dies ändert den Beginn der Kurz-Fuse und ihrer Bewegung. Es beweist keine
allgemein verlängerte 4,1-Sekunden-Fuse. Normales Verschieben einer bereits
tickenden Entity innerhalb derselben Insel behält dagegen seinen Tick-Token;
der Marker-Probe ergab 80 Tickberechtigungen in 80 Epochs und keinen doppelten
Tick durch den normalen Übergang.

## 4. Kolben-Blockevents verlieren wartende Deduplication und Löschung

**Bestätigte Queue-Abweichung; konkrete Schadenswirkung nicht nachgewiesen.**

[IslandBlockEvents.java](../common/src/main/java/com/axalotl/fabrolia/common/regions/IslandBlockEvents.java)
entnimmt ganze Wellen und leert ihre Mengen vor der Ausführung, sowohl auf
Main als auch im Insel-Drain. Vanilla entfernt jeweils nur das erste Event;
die übrigen bleiben zum Erkennen von Duplikaten in derselben Menge.

Ausführbarer Test mit tatsächlichem Fastutil:

- Queue `[A,B]`; A postet B erneut: Vanilla `[A,B]`, Fabrolia `[A,B,B]`.
- A löscht das wartende B: Vanilla `[A]`, Fabrolia `[A,B]`.

`ServerLevelMixin.clearBlockEvents` betrifft nur die geteilte Menge, keine
bereits entnommenen bzw. zur Insel verschobenen Events. Die Event-Klassen
und Parameter werden durch die Queue-Modelle repräsentiert; dies ist kein
kompletter Piston-Welt-Test. Eine zweite Extension ruft Vanilla grundsätzlich
wieder `moveBlocks` auf, kann aber am frisch erzeugten `moving_piston`
scheitern. Deshalb wird die doppelte Queue-Verarbeitung nicht automatisch
mit doppelter Kolbenbewegung gleichgesetzt.

Die übergeordnete Phase ist korrekt: Events → Entities → verschobene
Blockänderungen → Blockentities. Ein genereller Fehler „Kolben-BEs vor TNT“
wurde nicht gefunden.

## Weitere Explosionseingriffe

Rustmis normaler TNT-Sweep entspricht beim Block-/Fluid-Widerstand, den
1.352 Zufallsziehungen und der Float-Strahlenrechnung dem geprüften 26.3-Code.
Ein eigener Widerstandsfehler für `moving_piston` wurde nicht gefunden.

Ein bedingter Fehler bleibt in
[`ExplosionEngine.trySweepTakeover`](../../Rustmi/src/main/java/com/github/tatercertified/nalim/explosion/ExplosionEngine.java),
Zeilen 686–712: Nach Verbrauch der Zufallswerte kann `catch(Throwable)` den
Original-Sweep erneut starten lassen. Das verbraucht die Zufallswerte nochmals.
Hierzu müsste `[explosion-engine] takeover failed` im Log erscheinen; ohne
diesen Beleg wird das nicht als Ursache dieses World-Eater-Schadens bewertet.

Lithiums übersprungene Luftpositionen verändern Shuffle-Reihenfolge und
Zufallsverbrauch gegenüber vollständiger Vanilla. Rustmi übernimmt dieses
Verhalten. Das existiert auch ohne Rustmi und ist von den eigenen Fabrolia-
Grenzfehlern getrennt. Rustmis bisheriger `orderOnly`-Paritätsnachweis prüft
außerdem keine vollständige Zünd- oder Kolbenreaktionsfolge.

Carpets `optimizedTNT` zusammen mit festem `tntRandomRange` kann von Rustmis
Sweep umgangen werden. Keine Live-Regelkonfiguration wurde bereitgestellt;
dieser Konflikt wird deshalb nicht als aktiviert behauptet.

## Zusammenhang mit dem letzten Update und erforderliche Korrektur

Die sieben relevanten Produktionsklassen (`ColorGate`, `Region`,
`RegionizedServer`, `IslandBlockEvents`, `LevelMixin`, `EntityTickListMixin`,
`NeighborUpdaterMixin`) sind im alten Jar vor den Raid-Änderungen und im
fertigen Jar vom 09.10. byte-identisch. Die gezeigten Fehler wurden durch
dieses Update nicht neu eingebaut. Eine Änderung anderer Last oder
Ladebedingungen kann einen vorhandenen Fehler trotzdem sichtbar machen.

Eine Korrektur muss die Entity-Einfügereihenfolge über interagierende
Regionsgrenzen erhalten und Neulinge gegen den Beginn der gesamten
Entity-Phase abgrenzen. Nur alle TNTs zu sortieren reicht nicht, weil
Explosionen auch andere Entities bewegen und deren spätere Ticks beeinflussen.
Blockentfernung und ihre Explosionreaktion benötigen eine gemeinsame
Besitzer-Transaktion. Die Blockevent-Menge muss wartende Events bis zu ihrer
jeweiligen Ausführung zur Deduplication und Löschung behalten. Einfach den
Grenzschutz oder das ColorGate abzuschalten würde deren Ownership-Schutz
entfernen und ist keine geprüfte Korrektur.

## Nachweise und Reproduktion

[Maschinenlesbarer Nachweis](reviews/2026-10-10/tnt-code-review.json),
[ausgeführte Probes](reviews/2026-10-10/probes/) und
[Runner](reviews/2026-10-10/run-probes.py). Die vier im Repository enthaltenen
Probes wurden mit Java 25 und jeweils 256 MB Heap erfolgreich ausgeführt:

```bash
python3 docs/reviews/2026-10-10/run-probes.py
```

Der Runner benötigt einen lokalen unveränderten 26.3-Jar und dessen
Bibliotheken (`--minecraft-jar` / `--libraries`). Minecraft und seine
dekompilierten Quellen werden nicht mit dem Bericht verteilt. Weitere
[Marker-/Aufnahme-Probes](reviews/2026-10-10/additional-probes/) sind mit
Quellen, Logs und Classpath-Nachweisen im Repository und Audit-Manifest
gesichert. Der [gesamte Runner-Log](reviews/2026-10-10/run-probes.log)
enthält den erfolgreichen Durchlauf der vier Haupttests. Die Prüfung ersetzt keinen Modpack-Stand nach einer späteren
Produktionskorrektur.
