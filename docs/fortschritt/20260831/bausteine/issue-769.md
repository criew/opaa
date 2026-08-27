# Issue #769 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, evaluation
- PRs: keine

**Laut Issue:** Automatisch von `github-actions` erzeugter Alert — der nächtliche Retrieval-Regressionslauf ist ohne Report abgebrochen (vermutlich Manifest- oder Ein-Chunk-Invarianten-Verletzung, oder Zeitlimit). Kein inhaltlicher Befund, nur ein Fehlschlag-Signal.

**Geliefert:** Kein PR — laut Kommentar im Issue („Der nächtliche Retrieval-Regressionslauf ist wieder grün“, Link auf Workflow-Lauf 32685658102) hat sich der nächste automatische Lauf ohne Codeänderung selbst erledigt. Vermutlich transienter Abbruch (Zeitlimit oder Ressourcenkonkurrenz), keine tatsächliche Qualitätsregression im Retrieval.

**Verifikation:** Kein Code-Bezug, keine Prüfung im Worktree nötig.

**Themen:** evaluation, retrieval, ci, automatischer-alert
