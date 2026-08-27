# Issue #834 — feat(audit): Indizes für byTimeRange- und byIncidentScope-Abfragepfade ergänzen
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:S
- PRs: #846 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1. Zwei der fünf Audit-Zugriffspfade (`byTimeRange`, `byIncidentScope`) haben keinen passenden Index auf der monatspartitionierten `audit_log`-Tabelle und laufen auf Partition-Scans hinaus.

**Geliefert:** Migration 063 fügt `idx_audit_log_time_range (organization_id, recorded_at)` und `idx_audit_log_incident_scope (organization_id, actor_ref, recorded_at)` hinzu. Da `audit_log` seit Migration 017 `opaa_audit_owner` gehört (ADR-0015), läuft `CREATE INDEX` über denselben temporären GRANT/SET ROLE/REVOKE-Bracket wie Migration 022.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/063-audit-log-time-range-and-incident-scope-indexes.yaml` und `Migration063AuditLogTimeRangeAndIncidentScopeIndexesTest.java` im Worktree vorhanden.

**Themen:** audit, datenbank, performance, migration
