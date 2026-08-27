# Issue #862 — refactor(db): CHECK-Constraints für Enum-Vokabulare ablösen — Enum-Erweiterungen ohne Migration
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #868 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 3 (Befund B4). Enum-Vokabulare sind doppelt geschützt (Java-Enum + CHECK-Constraint); 8 von 63 Migrationen existieren nur, um Wertelisten zu erweitern. Constraints ersatzlos entfernen, Java-Enum bleibt alleiniger Schreibschutz.

**Geliefert:** Migrationen 064–066 droppen `chk_audit_log_event_type`, `chk_indexing_run_events_category`, `chk_notifications_type`. Umfang bewusst auf diese drei Tabellen begrenzt — `chk_documents_source_type` zeigt dasselbe Muster, wurde aber bewusst zurückgestellt (separates Folgeticket angekündigt); `chk_audit_log_object_type` ebenso, da es das Wachstumsmuster (noch) nicht zeigt. Nachbesserung: zwei Migrationstests (017, 040), die zuvor gegen das lebende `AuditEventType.values()` mit Ausschlusslisten prüften, wurden auf eingefrorene Literallisten umgestellt, damit ein künftiger migrationfreier Enum-Wert sie nicht unbemerkt grün lässt.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/064-drop-audit-log-event-type-check.yaml`, `065-...`, `066-...` sowie die zugehörigen Migrationstests im Worktree vorhanden.

**Themen:** datenbank, migration, enum, backend, technische-schulden
