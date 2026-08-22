# Issue #109 — feat(auth): user entity and database schema
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: keine direkt verknüpft

**Laut Issue:** User-Entity mit Datenbankschema, Repository und Service-Schicht anlegen — Liquibase-Migration für `users`-Tabelle (inkl. `system_role`-Enum, `auth_provider_id` als eindeutige externe ID), Auto-Anlage bei Erstanmeldung, `UserService.getCurrentUser()`.

**Geliefert:** Kein eigener PR — laut Schließkommentar (criew) wurde der Umfang vollständig durch PR #135 (Issue #108) mitgeliefert: User-Entity, Liquibase-Migration, Auto-Provisionierung über `UserProvisioningFilter`, `UserRepository`, `UserService` samt Tests. Die `system_role`-Erweiterung wurde bewusst in #110 ausgelagert. Kein fachlicher Unterschied zur Forderung, nur eine andere Ticket-Zuordnung des bereits gelieferten Codes.

**Verifikation:** `backend/src/main/java/io/opaa/auth/User.java`, `UserRepository.java`, `UserService.java` sowie Migration `005-create-users-table.yaml` existieren im heutigen Worktree.

**Themen:** auth, backend, datenbank, ohne-eigenen-pr
