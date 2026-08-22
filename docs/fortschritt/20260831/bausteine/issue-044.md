# Issue #44 — feat(indexing): Asynchrone Dokument-Indizierung mit konfigurierbarem ThreadPool
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend
- PRs: #52 (2026-02-27)

**Laut Issue:** Die Indizierung lief synchron im HTTP-Thread und blockierte den REST-Call bis zum Abschluss. Gefordert war ein `@Async`-Umbau mit konfigurierbarem `ThreadPoolTaskExecutor` (`opaa.indexing.thread-pool.*`), sofortige HTTP-202-Antwort, HTTP 409 bei bereits laufendem Job, sowie MVP-Ansatz "ein Thread pro Job" statt Parallelisierung pro Dokument.

**Geliefert:** PR #52 (gemeinsam mit #41) setzt praktisch alle Punkte um: `AsyncIndexingExecutor` für die Hintergrundarbeit, konfigurierbarer ThreadPool über `opaa.indexing.thread-pool`-Properties, HTTP 202 beim Trigger, HTTP 409 bei Duplikat-Läufen, neue Liquibase-Migration für `documents_total`. Zusätzlich zum Issue-Umfang: inkrementelle Fortschrittszählung über `REQUIRES_NEW`-Transaktionen für sofortige Polling-Sichtbarkeit sowie verbessertes Logging pro Datei.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AsyncIndexingExecutor.java` und `IndexingConfiguration.java` existieren weiterhin. Die konkrete `IndexingController.java` aus der PR-Dateiliste ist im heutigen Baum nicht mehr vorhanden — das Indexing-Subsystem wurde seither auf ein bibliotheksbezogenes Modell mit Executor-Registry umgebaut (`git log` zeigt u.a. #500, #473); die asynchrone Grundarchitektur (ThreadPool, Job-Tracking) besteht aber fort.

**Themen:** backend, indexing, async, threadpool, mvp
