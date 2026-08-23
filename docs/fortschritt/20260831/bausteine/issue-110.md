# Issue #110 — feat(auth): System-Admin role and API authorization
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: #136 (2026-03-07)

**Laut Issue:** System-Admin-Rolle und API-Autorisierung umsetzen — `@PreAuthorize`-Schutz für Admin-Endpunkte, Bootstrap-Mechanismus für den ersten Admin per Umgebungsvariable, Endpunkt zum Befördern/Degradieren, 403 bei fehlender Berechtigung.

**Geliefert:** PR #136 liefert `SystemRole`-Enum, Admin-Bootstrap über `OPAA_INITIAL_ADMIN_EMAIL`, `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` auf Indexing-Trigger und Nutzerverwaltung, Admin-API (`GET /api/v1/admin/users`, `POST /api/v1/admin/users/{id}/role`), korrektes 403-Mapping im `GlobalExceptionHandler`. Deckt die Anforderung vollständig ab.

**Verifikation:** `backend/src/main/java/io/opaa/auth/SystemRole.java` existiert im heutigen Worktree weiterhin; Migration `006-add-system-role-to-users.yaml` ebenfalls.

**Themen:** auth, rbac, backend, admin
