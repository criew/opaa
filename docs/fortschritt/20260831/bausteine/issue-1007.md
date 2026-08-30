# Issue #1007 — chore(ci): TypeScript-7-Major in Renovate aussetzen — typescript-eslint unterstützt TS 7.0 nicht

- Geschlossen: 2026-08-28 (completed)
- Labels: frontend, ci
- PRs: #1006 (2026-08-28, gemeinsam mit #1005)

**Laut Issue:** Renovate-PR #995 (TypeScript 7.0.2) scheiterte im frontend-Job:
`typescript-eslint` 8.68.0 bricht mit „typescript-eslint does not support TS 7.0" ab
(Upstream-Support ab TS ≥ 7.1 geplant).

**Geliefert:** PR #1006 deaktiviert das TypeScript-Major per packageRule (6.x-Updates laufen
weiter) mit Begründungskommentar, Upstream-Verweis und Wiedervorlage.

**Verifikation:** `renovate.json5` führt die TypeScript-Regel samt Upstream-Tracking-Verweis.

**Themen:** Renovate, TypeScript, typescript-eslint, Abhängigkeitsverwaltung
