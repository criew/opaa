---
name: Epic
about: Tracking-Issue für eine Gruppe zusammengehöriger Issues (Muster aus Epic #107)
title: 'feat: '
labels: ['enhancement', 'epic']
assignees: ''
---

Einleitungsabsatz: Was dieses Epic liefert und warum jetzt.

### Spezifikation

<!--
Die Spezifikation lebt hier im Epic und wird nicht eingecheckt. Auf Produkt-Konzeptebene schreiben:
Verhalten, Rollen, Abläufe, Optionen mit Abwägungen ("Option 1/2/3" + Empfehlung) — keine Klassen
oder Dateinamen. Tabellen und ASCII-Skizzen, wo sie Klarheit schaffen. Nach dem Bau steht das
Verhalten im Handbuch (`docs/handbuch/`), jede Entscheidung mit Begründung in einem ADR
(`docs/decisions/`); die Abnahmekriterien unten nennen beides.
-->

**Motivation:** Welches Problem, wer hat es, warum löst OPAA es.

**Kernpunkte:**

1. ...

**Verhalten und Abläufe:** ...

### Wirkung auf Behördenunterlagen

<!--
Pflicht, wenn das Epic Verhalten oder Konfiguration festlegt. Die Punkte entsprechen dem Abschnitt
„Wirkung auf Daten und Behördenunterlagen“ des PR-Templates, ergänzt um die KI-Einstufung; die Stufe
folgt den Stufenregeln (docs/handbuch/betrieb/nachvollziehbarkeit.md, „Die Stufenregeln“). Bei
Stufe Zustimmung läuft vor dem Schneiden der Sub-Issues ein Stakeholder-Review mit Personalrat und
Betrieb (docs/AGENT-ORGANIZATION.md).
-->

- [ ] Neue oder geänderte personenbezogene Daten oder Protokollereignisse
- [ ] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar
- [ ] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten
- [ ] Geänderte Aufbewahrungs- oder Löschfrist
- [ ] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen)
- [ ] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Voraussichtliche Stufe** (entfällt bei „Keine Wirkung“, im Zweifel Zustimmung): Bagatelle / Anzeige / Zustimmung

**Betroffene Vorlagen in `docs/behoerden/`** (Verzeichnis oder „keine“):

### Phasen

<!--
Die Tickets selbst werden als Sub-Issues eingetragen (Seitenleiste „Create sub-issue"
bzw. bestehendes Issue verknüpfen), nicht als Checkliste im Body. GitHub führt damit
Status und Fortschritt selbst; der Tagesreport liest dieselbe Beziehung.

Sind alle Sub-Issues geschlossen, wird das Epic mit einem kurzen Abschlusskommentar
geschlossen (Geliefertes mit PR-Verweisen, entstandene Folge-Issues) — siehe AGENTS.md,
Abschnitt „GitHub-Issues".

Hier steht nur, was die Sub-Issue-Liste nicht ausdrücken kann: warum in dieser
Reihenfolge geschnitten wurde. Die Reihenfolge der Sub-Issues lässt sich passend
sortieren.
-->

**Phase 1 — <name>.** Was diese Phase liefert und warum sie zuerst kommt.

**Phase 2 — <name>.** Worauf sie aufbaut.

### Abhängigkeiten

```
#A ──> #B ──> #D
        └──> #C
```

### Abnahmekriterien (Epic-Ebene)

- [ ] ...
- [ ] Handbuch: betroffene Kapitel nachgezogen (oder „kein Handbuchkapitel betroffen“)
- [ ] ADR: jede Entscheidung mit Begründung festgehalten (oder „keine Architekturentscheidung“)

### Außerhalb des Umfangs (separate Epics)

- ...

### Referenzen

- ADRs, Handbuchkapitel, verwandte Epics
