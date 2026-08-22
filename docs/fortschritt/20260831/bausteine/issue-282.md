# Issue #282 — fix(eval): Sentinel-Feldbezogenheit und Ground-Truth-Fingerabdruck im Golden Dataset
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, evaluation
- PRs: #284 (2026-08-02)

**Laut Issue:** Zweite Review-Runde zu PR #277 (#274/#226) — PR #277 wurde gemergt, bevor zwei letzte Korrekturen gepusht werden konnten. Zwingend: (1) `Entity.is_scored` war ein einziges Prädikat, das sowohl die Cross-Field-Regel auf den fünf Attributwerten als auch die `overall_score`-Sentinel-Regel gate­te — dadurch schlossen die 18 `"∞"`-Figuren fälschlich auch aus Fragen zu anderen Feldern aus, entgegen der vom Product Manager geforderten Feldbezogenheit (aktuell ohne Datenwirkung, aber im README für künftige Domänen als verbindlich deklariert). Fix: zwei getrennte Prädikate `is_rated`/`has_numeric_overall`. (2) Der `(natural_key, query)`-Fingerabdruck erfasste keine Änderung der Trefferliste (`expected_documents`) — nachgestellt: eine Fähigkeit ergänzt, Generator lief durch (`EXITCODE=0`) und schrieb das Dataset still neu. Fix: dritte Komponente `sha256(expected_documents)` im Fingerabdruck. Blockiert weiterhin #227/#228.

**Geliefert:** PR #284 liefert exakt die zwei beschriebenen Fixes — laut PR-Body ein Cherry-Pick des bereits fertigen, aber nicht mehr mergbaren zweiten Commits vom alten #277-Branch auf aktuelles `main`. Reproduktion des nachgestellten Falls auf dem tatsächlich committeten Code bestätigt: Abbruch mit `EXITCODE=1`, Datei nicht überschrieben. Keine Abweichung vom Issue.

**Verifikation:** `eval/generator/generate_golden_dataset.py` enthält `is_rated` (Zeile 146) und `has_numeric_overall` (Zeile 164) als getrennte Prädikate, mit Docstrings, die exakt die im Issue beschriebene Unterscheidung dokumentieren — Umsetzung bestätigt.

**Themen:** evaluation, retrieval
