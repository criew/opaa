# Issue #863 — ci: retrieval-regression.yml — Domänen-Jobs über Matrix statt Kopie
- Geschlossen: 2026-08-24 (completed)
- Labels: size:S, ci
- PRs: #866 (2026-08-24)

**Laut Issue:** Teil von Epic #826 (Build-Review-Befund). `retrieval-regression.yml` enthielt zwei fast identische ~285-Zeilen-Jobs je Eval-Domäne inkl. eigener Issue-Melde-Logik; eine dritte Domäne wäre die dritte Kopie.

**Geliefert:** Wie gefordert — beide Jobs zu einer Job-Definition mit `strategy.matrix` (`fail-fast: false`) zusammengeführt; domänenspezifisch bleiben Gradle-Task, Timeout, Report-Dateinamen und Issue-Melde-Texte als Matrix-Variablen, sodass Alarm-Issues je Domäne weiterhin getrennt bleiben. Trigger, Concurrency-Gruppe und geteilter Ollama-Modell-Cache unverändert.

**Verifikation:** `.github/workflows/retrieval-regression.yml` im Worktree vorhanden. Nachweislauf per `workflow_dispatch` in der PR-Beschreibung verlinkt.

**Themen:** ci, workflow, eval, retrieval
