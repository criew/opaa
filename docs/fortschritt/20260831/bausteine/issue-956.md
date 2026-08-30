# Issue #956 — fix(frontend): Branding-Vorschau versteckt fokussierbare Elemente vor Screenreadern

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1012 (2026-08-28)

**Laut Issue:** Befund aus dem Barrierefreiheits-Abschluss-Audit (#598, axe-Regel
`aria-hidden-focus`, Schweregrad hoch): Die mit `aria-hidden` ausgeblendeten Vorschau-Panels
der Branding-Seite enthalten fokussierbare Elemente — Tastaturnutzer tabben in Inhalte, die für
Screenreader nicht existieren.

**Geliefert:** PR #1012 nimmt die Branding-Vorschau per `inert` vollständig aus der
Tab-Reihenfolge, sodass Fokusreihenfolge und Accessibility-Baum wieder übereinstimmen.

**Verifikation:** Commit `066b8e97` auf `main`; `BrandingPreview.tsx` verwendet `inert`.

**Themen:** Barrierefreiheit, Audit-Befund, Branding
