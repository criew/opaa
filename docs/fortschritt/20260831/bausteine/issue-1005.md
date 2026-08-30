# Issue #1005 — chore(ci): Tika-4-Major in Renovate aussetzen — inkompatibel zu Spring AIs Tika-3-Parsern

- Geschlossen: 2026-08-28 (completed)
- Labels: backend, ci
- PRs: #1006 (2026-08-28, gemeinsam mit #1007)

**Laut Issue:** Renovate-PR #992 hob nur `tika-core` auf 4.0.0; `tika-parsers` kommt transitiv
über die Spring-AI-BOM und bleibt auf 3.x — die Kombination bricht die Format-Erkennung breit
(backend- und e2e-Job rot).

**Geliefert:** PR #1006 deaktiviert das Tika-Major per packageRule (Minor/Patch in 3.x laufen
weiter) mit Begründungskommentar und Wiedervorlage: Regel entfernen, sobald Spring AI seine
Tika-Abhängigkeit auf 4.x hebt.

**Verifikation:** `renovate.json5` führt die Tika-Regel samt Begründung.

**Themen:** Renovate, Tika, Spring AI, Abhängigkeitsverwaltung
