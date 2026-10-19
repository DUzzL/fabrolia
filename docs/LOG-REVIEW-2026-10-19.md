# Produktionslog-Review (Logs 16.–19.10.2026, ausgewertet am 2026-10-19)

Quelle: `archive-2026-10-19T014128-0400.tar.gz` mit zwölf Logs des
Produktionsservers, Serverzeit 16.10. 10:11 bis 19.10. 01:41 (UTC−4). Es liefen
nacheinander `3.1.2-alpha`, `3.1.3-alpha` und `3.1.7-alpha`. Gesucht wurden alle
ERROR-Zeilen, Crashes, Fehler beim Stoppen und auffällige Warnungen.

## Ergebnis

| Fund | Häufigkeit | Ursache | Stand |
| --- | --- | --- | --- |
| Welt beim Stoppen nicht gespeichert | 4 von 5 Stopps | A5-Ingress nach `stopServer` (3×), Discord-Mod (1×) | A5-Teil behoben in 3.1.10 |
| Crash `flushDirty off the player's owner thread` | 18.10. 15:21 | Reiter auf einem Insel-Fahrzeug | behoben in 3.1.8 |
| Kein Speichern nach einem Fabrolia-Crash | jeder Fatal | Fatal wird in `SERVER_STOPPING` erneut geworfen | offen, Entscheidung nötig |
| Watchdog-Abschuss | 18.10. 20:18 | Main dauerhaft unter 4 TPS (Vanilla-Regel) | Lag-Thema, Crash-Report fehlt |
| `UNSAFE cross-region write` | 8 (18.10.) | Fehlalarm des Detektors | behoben in 3.1.9 |
| `escalation is not firing (actionable)` | 920 | Fehldiagnose, Kontingent-Knappheit | Text korrigiert in 3.1.10 |
| `UUID of added entity already exists` | 164 Perlen, 8 Guardians | Folge der ungespeicherten Stopps | entfällt mit dem Shutdown-Fix |
| `POI data mismatch: never registered` | 3 | Weltdaten an zwei festen Positionen | harmlos, nicht geklärt |
| `Name may not be empty` (Chat) | 1 | discord-mc-chat (leerer Emoji-Name) | Fremd-Mod |
| `handleDisconnection() called twice` | 106 | Vanilla, auch in der Login-Phase | harmlos |
| `Sending unknown packet ... disconnect` | 23 | Vanilla `exceptionCaught` im Handshake | harmlos |
| `Incompatible mods found!` | 2 (16.10.) | World Time 1.1.1 brauchte Fabric API ≥ 0.163.0 | vom Betreiber behoben |

## Welt beim Stoppen nicht gespeichert

Nur der Stopp vom 17.10. 10:10 lief bis „Saving chunks for level …“ und bis zum
Ende der C2ME-Storage-Threads. Am 16.10. 16:29, 17.10. 06:21 und 18.10. 10:10
brach `stopServer` mitten in „waiting for N chunks to unload“ mit
`A5 accepted Main entity ordinal lost its live region pool` ab. Alles seit dem
letzten Autosave ging verloren.

Ablauf: Fabrolia stoppt Region-Pool und Scheduler in `SERVER_STOPPING`, also vor
Vanillas eigentlichem Stopp. Vanilla 26.3 schließt danach als erste Anweisung von
`stopServer` den `PacketProcessor`; ab dann wird kein eingereihtes Spielerpaket
mehr abgearbeitet (`processQueuedPackets` prüft `closed`). Fabrolias A5-Main-Ingress
läuft dagegen als eigene Server-Aufgabe und wurde während des Chunk-Entladens weiter
abgearbeitet. Betroffen waren Spieler, deren Kanal beim `removeAll` noch offen war:
Vanillas `handleDisconnection` tut dann nichts, es gibt keine Zeile „lost connection“,
und die A5-Sitzung blieb MAIN. Der Drain verarbeitete ihre Pakete (WorldEdit meldete
„Currently invalid“) und wollte danach ihren geschuldeten Entity-Tick an den bereits
gestoppten Pool übergeben.

Die A5-Paketverarbeitung kannte außerdem Vanillas zweite Prüfung nicht:
`ListenerAndPacket.handle` überspringt ein Paket, dessen Listener keine Nachrichten
mehr annimmt (`shouldHandleMessage`: getrennt oder beim Wechsel in die
Konfigurationsphase).

3.1.10 wendet beide Vanilla-Prüfungen dort an, wo A5 ein dekodiertes Paket abarbeitet
(Insel-Replay und Main-Ingress). Nach Beginn von `stopServer` verfällt ein geschuldeter
Entity-Tick ohne Body, und die FIFO läuft weiter. Interne Einträge (Main-, Befehls-
und Chat-Fortsetzungen, geroutete Mutationen) behalten ihre eigenen Lebensdauer-Regeln.
Sie entsprechen Vanillas `server.execute`-Aufgaben, die auch beim Stoppen noch laufen.

Am 18.10. 20:18 brach der Stopp nach dem Watchdog an einer anderen Stelle ab:
discord-mc-chat 2.7.1 warf beim Entfernen eines Spielers
`RejectedExecutionException: The Requester has been stopped!`. Das passiert ohne
Fabrolia genauso; der Mod sollte aktualisiert oder der Fehler gemeldet werden.

## Kein Speichern nach einem Fabrolia-Crash

Nach dem Crash vom 18.10. 15:21 liefen nur Fabrolias `SERVER_STOPPING`-Schritte.
`ParallelProcessor.stopServerRuntime()` warf danach die Fatal-Exception der
gescheiterten Generation erneut; Vanillas „Stopping server“, „Saving players“ und
„Saving worlds“ kamen nie. Vanilla speichert nach einem Tick-Crash. Das erneute
Werfen ist im Fail-Stop-Konzept gewollt und durch Tests abgesichert, die Nebenwirkung
(Verlust seit dem letzten Autosave) ist aber nirgends entschieden. Eine Änderung
müsste alle Speicherpfade unter einer gescheiterten Generation prüfen (die
Erfolgs-Gates verweigern danach Arbeit) und auf dem Stand verifiziert werden.

## Watchdog-Abschuss am 18.10. um 20:18

Der Vanilla-Watchdog misst nicht die Dauer eines Ticks, sondern den Rückstand des
Tick-Plans (`now − getNextTickTime()`). Vanilla holt verpasste Ticks erst nach, wenn
der Plan seit der letzten Warnung um mindestens 15 s weitergelaufen ist. Läuft Main
dauerhaft unter 4 TPS, erreicht der Rückstand deshalb 60 s, bevor aufgeholt wird.
Letzte Aufholung war 20:17:42, der Abschuss kam 72 s später: Main lief in dieser
Minute mit etwa 3,3 TPS, in den beiden Minuten davor mit 9–11 TPS. Seit 17:50 lagen
bei 35–44 Spielern 100–350 s Rückstand pro 10 Minuten an. Main lebte bis zuletzt
(Login um 20:18:52). Was Main in der letzten Minute tat, zeigt nur der Crash-Report
`crash-2026-10-18_20.18.56-server.txt`; er war nicht im Archiv.

## Weitere Funde

- **`UNSAFE cross-region write`:** siehe 3.1.9. Der serielle Insel-Pass claimt alle
  Regionen seiner Insel auf dem eigenen Worker; die Fehlalarme verbrauchten das
  Stacktrace-Kontingent nach 28 Minuten.
- **`escalation is not firing (actionable)`:** In 835 von 920 Fällen hatte der Sender
  weniger als 1 s vorher einen Batch verschickt. `selectCandidate` eskaliert einen
  REQUESTED-Chunk, sobald es ihn erreicht. Die nach Entfernung sortierte Auswahl
  endet aber am Batch-Kontingent und läuft nicht, solange unbestätigte Batches den
  Sender pausieren. Ein ferner Chunk wartet also, während nähere jeden Batch füllen,
  wie in Vanilla. Die Diagnose heißt jetzt „not reselected since — batch
  quota/unacked-cap starvation“.
- **Doppelte Enderperlen und Guardians:** Seit 1.21.2 speichert Vanilla geworfene
  Perlen auch in den Spielerdaten und entfernt sie beim Logout aus der Welt. Fehlt
  das Speichern beim Stopp, liegen sie nach dem Neustart im Chunk (letzter Autosave)
  und in den Spielerdaten. Die Warnungen kamen gesammelt beim Wiedereinloggen nach
  Neustarts, die meisten nach dem abgebrochenen Stopp vom 18.10. morgens.
- **POI-Inkonsistenz:** zweimal `x=3887, y=69, z=-16851` (16.10. und 18.10., jeweils
  beim Login eines Spielers in der Nähe) und einmal `x=5405, y=87, z=15253`. Vanilla
  protokolliert das nur. Ob Fabrolia beteiligt ist, lässt sich ohne die Welt nicht klären.
