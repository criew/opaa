# Issue #616 — test(query): QueryIntegrationTest flaky — MockitoException durch Stubbing-Race mit asynchronem Chat-Titel-Job
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #621 (2026-08-20)

**Laut Issue:** `QueryIntegrationTest` schlug sporadisch mit `MockitoException` fehl, vermutlich weil der seit #561 asynchrone Chat-Titel-Job im Testthread konkurrierend auf demselben geteilten `chatModel`-Mock stubt/aufruft, während der Testcode gerade selbst stubt. Gefordert: deterministischer Test, entweder durch Synchronisieren/Deaktivieren des Titel-Jobs im Test oder race-freies Stubbing.

**Geliefert:** PR #621 ersetzt in `QueryIntegrationTest` per `@TestBean(name = "chatTitleTaskExecutor", enforceOverride = true)` den asynchronen Executor durch einen synchronen `SyncTaskExecutor`, sodass der Titel-Job vollständig im aufrufenden Thread abläuft, bevor `queryService.query(...)` zurückkehrt. Positiver Nachweis über Thread-Namen-Assertion im betroffenen Test ergänzt. Reproduktion war lokal nicht möglich (wie im Issue selbst erwartet); als Beleg dienen die beiden verlinkten roten CI-Läufe aus #616. PR-Body korrigiert zudem eine falsche Zuordnung aus der ursprünglichen Aufgabenbeschreibung (Bezug war PR #603, nicht #574/#589).

**Verifikation:** `backend/src/test/java/io/opaa/query/QueryIntegrationTest.java` existiert im heutigen Worktree.

**Themen:** backend, testing, flaky-test, mockito, ci
