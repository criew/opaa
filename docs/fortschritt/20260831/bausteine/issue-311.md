# Issue #311 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, evaluation
- PRs: #315 (2026-08-14)

**Laut Issue:** Automatisch von `app/github-actions` erzeugter Alarm — der nächtliche Retrieval-Regressionslauf schlug fehl, kein Report erzeugt, vermutlich Abbruch vor der Baseline-Prüfung oder durch Zeitlimit. Kein Feature-Issue, sondern ein CI-Alarm ohne inhaltliche Forderung.

**Geliefert:** PR #315 identifiziert die Ursache: `actions/cache` speicherte den Ollama-Modell-Cache nur im Post-Job-Schritt, den GitHub Actions bei `cancelled` (durch `timeout-minutes` ausgelöst) überspringt — der Cache blieb dauerhaft leer, jeder Lauf startete kalt und lief erneut ins Limit (sich selbst erhaltender Fehler). Fix: `actions/cache` in `restore`/`save` aufgeteilt, `save` läuft jetzt mit `if: always()`; `timeout-minutes` von 30 auf 60 angehoben, da die ursprüngliche Zeitmessung nachweislich nicht von einem echten GitHub-Actions-Runner stammte (Workflow lief dort noch nie erfolgreich durch). Eine echte Verlangsamung durch #201/#202 wurde geprüft und ausgeschlossen — die Runner-CPU selbst wurde als Ursache identifiziert, aber bewusst nicht mitbehoben (reine CI-Kapazitätsfrage, separat gemeldet).

**Verifikation:** `.github/workflows/retrieval-regression.yml` im Worktree enthält `actions/cache/save`.

**Themen:** ci, evaluation, retrieval, automatisierung
