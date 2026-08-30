# Issue #957 — fix(frontend): Rollen-Chips unterschreiten im Dunkelschema den Mindestkontrast

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1017 (2026-08-28)

**Laut Issue:** Befund aus dem Barrierefreiheits-Abschluss-Audit (#598, axe-Regel
`color-contrast`, Schweregrad hoch): Die Chips „Administrator" (Spaces-Übersicht) und
„Eigentümer" (Wissensbibliotheken) unterschreiten im Dunkelschema den Mindestkontrast von
4,5:1 (WCAG 1.4.3).

**Geliefert:** PR #1017 macht die Rollen-Badges schemafest: Farben über das Theme
(`primary.main`) statt hart codierter Palette (`blue[700]`), womit beide Schemata die
Kontrastanforderung erfüllen.

**Verifikation:** Commit `9acdc3da` auf `main`.

**Themen:** Barrierefreiheit, Audit-Befund, Kontrast, Dunkelmodus
