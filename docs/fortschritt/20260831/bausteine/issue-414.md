# Issue #414 — ci(eval): evaluateRetrieval führt BaselineRegressionTest ohne Report aus und schlägt fehl
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:S, ci, evaluation
- PRs: #415 (2026-08-15)

**Laut Issue:** Der nächtliche Workflow „Retrieval-Regression" schlug fehl, weil `tasks.register<Test>("evaluateRetrieval")` in `backend/build.gradle.kts` keinen `filter`-Block hatte und dadurch alle Klassen des `evalTest`-Sourcesets ausführte, einschließlich `BaselineRegressionTest` — dessen Wächter „No report found" fehlschlägt, wenn der Report noch nicht erzeugt wurde. Keine echte Retrieval-Regression, reiner Task-Konfigurationsfehler. Gefordert: Ausschluss von `*BaselineRegressionTest` in `evaluateRetrieval`, analog zu `evalUnitTest`, mit erläuterndem Kommentar.

**Geliefert:** PR #415 ergänzt genau diesen Ausschluss (eine Zeile plus Kommentar). Der PR-Body dokumentiert zusätzlich einen zweiten, unabhängigen Blocker, der beim ersten CI-Lauf danach sichtbar wurde: ein zu knapper Awaitility-Timeout (30 Minuten) in `RetrievalEvaluationHarnessTest`, der im Widerspruch zum bereits auf 60 Minuten angehobenen Job-Timeout stand — auf 45 Minuten korrigiert. Beides ist im selben PR/Issue erledigt, obwohl der zweite Teil im ursprünglichen Issue-Text nicht stand (im PR als „Nachtrag" ausgewiesen, keine verdeckte Abweichung).

**Verifikation:** `backend/build.gradle.kts` enthält heute im `evaluateRetrieval`-Task einen Kommentar, der die Rollenverteilung von `evalUnitTest`/`evaluateRetrieval`/`checkRetrievalBaseline` erklärt („Produces the report, and only the report: BaselineRegressionTest is excluded because it consumes …"), passend zur beschriebenen Lieferung.

**Themen:** ci, evaluation, retrieval, gradle, build-konfiguration
