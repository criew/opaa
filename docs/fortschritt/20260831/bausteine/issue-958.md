# Issue #958 — fix(frontend): Übersprungene Überschriftenebenen auf Chat-, Branding- und Modelle-Seite

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1015 (2026-08-28)

**Laut Issue:** Befund aus dem Barrierefreiheits-Abschluss-Audit (#598, axe-Regel
`heading-order`, Schweregrad mittel): Auf Chat-Leerzustand, Branding- und Modelle-Seite wird
eine Überschriftenebene übersprungen (WCAG 1.3.1).

**Geliefert:** PR #1015 korrigiert die Überschriftenhierarchie der drei Seiten (semantische
Ebene von der Typography-Variante entkoppelt). Der beim Review entdeckte, datenabhängige
Folgefall in Chat-Antworten wurde als #1016 nachgezogen.

**Verifikation:** Commit `ee01047d` auf `main`.

**Themen:** Barrierefreiheit, Audit-Befund, Überschriftenstruktur
