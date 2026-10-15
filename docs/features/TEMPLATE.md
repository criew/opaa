# Feature-Titel

> **Status: Früher Entwurf — wesentliche offene Fragen verbleiben.**
<!--
Jede Spezifikation trägt diesen Status-Blockquote dauerhaft. Gelebte Statuswerte: „Entwurf“,
„Zielbild, in Teilen gebaut“, „umgesetzt“ (mit Ausnahmen) und „Ist-Stand“ (beschreibt nur Gebautes).
Beschreibt eine Spec Zielbild und Gebautes gemischt, trägt jeder Abschnitt oder Satz die Marke
**(gebaut, #Issue)** oder **(Zielbild)** — fett, in der Überschrift oder in der ersten Zeile darunter
(bestehende Anker nicht ändern). Ohne Marke gilt, was der Statuskopf oder ein Lesehinweis festlegt.
-->

## Motivation

Warum dieses Feature existiert: das Problem, wer es hat und warum OPAA es lösen sollte.

---

## Überblick

Die Kernpunkte des Designs, nummeriert:

1. ...
2. ...

---

## <Domänen-Kapitel>

<!--
Der Hauptteil der Spezifikation sind domänenspezifische Kapitel (so viele wie nötig), auf
Produkt-Konzeptebene geschrieben: Verhalten, Rollen, Abläufe und Optionen mit Abwägungen
("Option 1/2/3" + Empfehlung) — keine Klassen oder Dateinamen. Konfigurationsausschnitte
(YAML/env), Tabellen (z. B. Berechtigungsmatrizen) und ASCII-Diagramme (Abläufe, UI-Mockups)
verwenden, wo sie Klarheit schaffen. Abschnitte mit `---` trennen.
Siehe `access-control.md` als Referenzbeispiel.
-->

---

## Integrationspunkte

Wie dieses Feature mit anderen Features/Spezifikationen verbunden ist.

---

## Wirkung auf Behördenunterlagen

<!--
Pflicht in jeder Spezifikation, die Verhalten oder Konfiguration festlegt. Die Punkte sind dieselben
wie im Abschnitt „Wirkung auf Daten und Behördenunterlagen“ des PR-Templates (Label `data-impact`),
ergänzt um die KI-Einstufung; die Stufe folgt den Stufenregeln in `behoerdenunterlagen.md`.
Bei Stufe Zustimmung läuft vor dem Schneiden der Issues ein Stakeholder-Review mit Personalrat und Betrieb.
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

**Betroffene Vorlagen in `docs/behoerden/`** (Verzeichnis, z. B. `04-datenschutz/`, oder „keine“):

---

## Offene Fragen / Zukünftige Erweiterungen

- Frage oder zurückgestellte Idee 1?

---

## Erfolgs-Metriken (optional)

Wie wir wissen würden, dass das Feature für Benutzer funktioniert.
