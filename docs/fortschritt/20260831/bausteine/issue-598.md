# Issue #598 — test(frontend): Barrierefreiheits-Audit nach Abschluss der Design-Migration

- Geschlossen: 2026-08-28 (completed)
- Labels: frontend, size:M
- PRs: #960 (2026-08-28)

**Laut Issue:** Nach Abschluss der Design-Migration eine Gesamtabnahme der Barrierefreiheit über
die automatisierten Prüfungen hinaus: vollständiger Tastatur-Durchgang der Kernabläufe,
Screenreader-Stichproben, Kontrastprüfung in beiden Farbschemata — auch mit konfiguriertem
Branding, weil die frei wählbare Primärfarbe Kontraste brechen kann.

**Geliefert:** Das Abschluss-Audit wurde durchgeführt und sein Prüfprotokoll unter
`docs/design/` abgelegt (PR #960). Die Befunde wurden als eigene Issues erfasst und behoben:
`aria-hidden` mit fokussierbaren Elementen in der Branding-Vorschau (#956), Kontrast der
Rollen-Chips im Dunkelschema (#957), übersprungene Überschriftenebenen (#958), Fokusverlust
beim Inline-Umbenennen (#959); im Review-Nachgang zusätzlich die Markdown-Überschriften in
Chat-Antworten (#1016).

**Verifikation:** Prüfprotokoll liegt unter `docs/design/` (PR #960); alle Befund-Issues sind
mit gemergten PRs geschlossen.

**Themen:** Barrierefreiheit, Audit, Design-Migration, Abnahme
