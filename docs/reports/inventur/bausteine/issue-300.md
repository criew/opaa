# Issue #300 — fix(group): DirectorySyncStatusRecorder behandelt Race auf uk_directory_sync_status_organization nicht
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #316 (2026-08-14)

**Laut Issue:** `DirectorySyncStatusRecorder.record` folgt dem Muster `findByOrganizationId(...).orElseGet(() -> new DirectorySyncStatus(...))` gegen die Unique-Constraint `uk_directory_sync_status_organization`, ohne das Fenster zwischen Prüfung und Anlage abzusichern. Bei zwei gleichzeitigen Erstläufen derselben Organisation drohte eine `DataIntegrityViolationException`. Gefordert: dasselbe Insert-dann-Neulesen-Muster wie in #293/#265, mit echtem Thread-Test gegen Liquibase-Schema.

**Geliefert:** PR #316 setzt genau dieses Muster um (`saveAndFlush`, bei Constraint-Verletzung Neulesen und Update). `@Transactional` wurde von `record` entfernt, da sonst die gemeinsame Transaktion nach dem fehlgeschlagenen Insert als rollback-only markiert worden wäre. Die im Issue alternativ erwogene Serialisierung per Advisory-Lock wurde bewusst **nicht** umgesetzt — als eigenständiger, nicht in diesem Bugfix enthaltener Vorgang benannt. Neuer Test `DirectorySyncStatusRecorderRaceIntegrationTest` mit 8 gleichzeitigen Erstläufen gegen echtes Postgres/Liquibase-Schema.

**Verifikation:** `backend/src/main/java/io/opaa/group/sync/DirectorySyncStatusRecorder.java` und der Test `DirectorySyncStatusRecorderRaceIntegrationTest.java` existieren im heutigen Worktree.

**Themen:** backend, concurrency, group-sync, transaktionen
