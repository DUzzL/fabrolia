---
description: Mehrstufiges Audit von Fabrolia mit Subagents (Bugs, Vanilla Parity, Overengineering)
---

# Rolle
Du bist der Orchestrator für ein Audit von Fabrolia. Du koordinierst
Subagents, führst ihre Ergebnisse zusammen und setzt am Ende freigegebene
Fixes selbst um. Die eigentliche Audit- und Prüfarbeit delegierst du, damit
dein Kontext für Synthese und Fixes frei bleibt.

Die fachlichen Regeln (Checkliste, Bericht-Format, Fix-Regeln) stehen in
docs/audit/AUDIT-PROMPT.md. AGENTS.md und CLAUDE.md haben Vorrang vor allem.

# Ablage und Git
- Alle Audit-Dateien liegen in docs/audit/<YYYY-MM-DD>/ (Datum des
  Audit-Starts, im Folgenden <DIR>). So überschreiben spätere Audits keine
  älteren Berichte.
- Subagents schreiben nur ihre eigene Berichtsdatei und führen keine
  git-Befehle aus. Commits machst ausschließlich du.
- Commit-Messages enthalten niemals Co-Authored-By-Zeilen, Session-Links
  oder Hinweise wie "Generated with Claude Code". Autor ist immer die in
  git konfigurierte Identität; ändere niemals user.name oder user.email.
- Kein git push, kein Pull Request, kein Rewrite bestehender History ohne
  meine ausdrückliche Freigabe.
- Phasen 0–3 laufen auf dem Branch audit/<YYYY-MM-DD>. Committe nach jeder
  Phase: "audit: overview", "audit: subsystem reports",
  "audit: verification", "audit: final report".
- Fix-Branches audit/<ID> zweigen von main ab. Jede Commit-Message nennt
  die ID, z. B. "fix(B-03): ...". Trag nach jedem Fix in <DIR>/30-report.md
  Status und Branch ein und committe das auf dem Audit-Branch.
- Berichte enthalten nur Fakten über den Code. Keine Pfade außerhalb des
  Repos, keine lokalen Umgebungsdetails.

# Phase 0 – Gemeinsame Grundlage (du selbst)
1. Lies AGENTS.md, CLAUDE.md, docs/audit/AUDIT-PROMPT.md und die dort
   genannte Pflichtlektüre.
2. Falls es frühere Audits in docs/audit/ gibt, lies deren 30-report.md,
   um später zu prüfen, ob behobene Funde wieder auftauchen.
3. Erstelle <DIR>/00-overview.md mit:
   - welche Arbeit auf welchem Thread läuft (Main, Region-Worker,
     Island-Worker, Player-Tick)
   - Island-Lebenszyklus: Bildung, Merge, Split, async vs. sync
   - wie Island-übergreifende Interaktionen an den Main-Thread gehen
   - Liste aller Mixins (common, fabric, neoforge) mit Ziel und Zweck
   - zentrale Klassen je Subsystem mit Pfaden
   Halte dich an Fakten aus dem Code. Unklares markierst du als offen.
4. Ändere in dieser Phase keinen Code.

# Phase 1 – Parallele Audits (Subagents)
Starte diese vier Subagents gleichzeitig:
- A: Entity-Ticking, Spieler-Ticking, Grenzübergänge, Dimensionswechsel
- B: Scheduled Block-/Fluid-Ticks, Neighbor-Updates, Redstone, Kolben,
     Hopper, Explosionen/TNT
- C: Spawning, Random Ticks, RNG-Nutzung, Mob-Caps
- D: Globaler State (Scoreboard, Stats, Advancements, Raids, POI, Weather,
     Daytime, GameRules, WorldBorder), Commands, /tick, Config-Reload,
     Toggle, Server-Stop, Carpet-Integration

Jeder Subagent bekommt diesen Auftrag:
- Lies <DIR>/00-overview.md und docs/audit/AUDIT-PROMPT.md.
- Prüfe nur dein Subsystem anhand der Audit-Checkliste, inklusive
  Overengineering im eigenen Subsystem.
- Ändere keinen Code, keine Configs, keine Tests. Nur lesen. Keine
  git-Befehle.
- Schreibe den Bericht nach <DIR>/10-<Buchstabe>.md. Jeder Fund bekommt
  eine ID (z. B. B-03) und das Bericht-Format aus dem Audit-Prompt, inkl.
  konkretem Thread-Ablauf mit Datei- und Zeilenangaben.
- Füge einen Abschnitt "Grenzfälle" an: Stellen, an denen dein Subsystem
  mit einem anderen interagiert und du ein Risiko siehst, das du nicht
  abschließend bewerten konntest.
- Antworte nur mit einer kurzen Zusammenfassung (Anzahl Funde je
  Schweregrad), nicht mit dem ganzen Bericht.

# Phase 2 – Verifikation (Subagents)
Starte für jeden Bericht einen neuen Subagent, der ihn nicht geschrieben
hat. Auftrag:
- Lies <DIR>/00-overview.md und den zugehörigen Bericht.
- Versuche jeden Fund zu widerlegen: Existiert der beschriebene
  Thread-Ablauf wirklich? Gibt es Locks, Übergaben an den Main-Thread,
  Serialisierung oder Guards, die ihn verhindern? Weicht Vanilla wirklich
  ab? Prüfe das am Code, nicht am Bericht.
- Bewerte jeden Fund als bestätigt, widerlegt oder unklar, mit Begründung
  und Code-Verweis. Prüfe auch, ob der vorgeschlagene Fix neue Probleme
  erzeugt.
- Schreibe nach <DIR>/20-verify-<Buchstabe>.md. Kein Code ändern, keine
  git-Befehle.
- Antworte nur mit einer kurzen Zusammenfassung.

# Phase 3 – Synthese (du selbst, optional mit Subagents)
1. Führe alle Berichte und Verifikationen zusammen. Widerlegte Funde
   fliegen raus, unklare bleiben als Verdachtsfälle.
2. Entferne Duplikate über Subsysteme hinweg.
3. Werte alle Abschnitte "Grenzfälle" gemeinsam aus. Ergeben sich daraus
   konkrete Wechselwirkungen (z. B. TNT über eine Island-Grenze während
   eines Merges), lass sie von einem eigenen Subagent prüfen wie in
   Phase 1 und 2.
4. Vergleiche mit früheren Audits: Taucht ein bereits behobener Fund
   wieder auf, markiere ihn als Regression.
5. Schreibe <DIR>/30-report.md: priorisierte Liste, Bugs zuerst, dann
   Parity, dann Overengineering. Pro Fund: ID, Schweregrad, Sicherheit,
   Kurzbeschreibung, vorgeschlagener Fix, betroffene Dateien, ob der Fix
   andere Funde berührt, Status (offen).
6. STOPP. Zeig mir eine kurze Zusammenfassung und warte, bis ich die IDs
   freigebe, die behoben werden sollen.

# Phase 4 – Fixes (nur du selbst, strikt nacheinander)
- Keine Subagents für Code-Änderungen, keine parallelen Fixes.
- Nur freigegebene IDs, in der Reihenfolge, die ich vorgebe (sonst nach
  Priorität aus dem Report).
- Halte dich an die Fix-Regeln aus docs/audit/AUDIT-PROMPT.md und an die
  Test- und Messpflichten aus AGENTS.md.
- Wenn du den Test-Stand oder die Messungen nicht selbst durchführen
  kannst, schreib mir ein konkretes Szenario (Aufbau, erwartetes
  Vanilla-Ergebnis, was vorher/nachher gemessen werden soll).
- Nach jedem Fix: kurze Zusammenfassung, was geändert, was getestet und
  was nur kompiliert wurde. Dann STOPP und warte auf mein OK, bevor du
  den nächsten Fund angehst.
- Stellst du beim Fixen fest, dass ein Fund falsch war oder ein anderer
  dadurch hinfällig wird, sag es, statt trotzdem zu ändern.

$ARGUMENTS
