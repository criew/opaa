# Issue #199 — Rename workspace to space, add organization scope and reshape space roles
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, frontend, size:L, workspace
- PRs: #254 (2026-08-02)

**Laut Issue:** Erster Schritt des Space-and-Asset-Modells (Teil von Epic #198): mechanischer Rename `Workspace`→`Space` über Entity, Tabelle, Repository, Service, Controller, OpenAPI-Spec und Frontend, plus semantische Änderungen — `Space.kind` (PERSONAL/PROJECT/TEAM), `Space.visibility` (PRIVATE/DISCOVERABLE/OPEN), neue `SpaceRole` (MEMBER/CURATOR/ADMIN) mit definierter Rollenabbildung, `organization_id` als harte Mandantengrenze (auch für System-Admins), Wegfall globaler Namenseindeutigkeit, Nutzer dürfen PROJECT-Spaces selbst anlegen, Entfernen von `/api/v1/workspaces/{id}/documents`. Migration muss resumierbar sein, Trockenlauf mit Mengengerüst, Rollback dokumentiert.

**Geliefert:** PR #254 liefert den Rename vollständig über Backend, OpenAPI und Frontend inkl. Liquibase-Changelog `008-rename-workspace-to-space.yaml` mit Migrationsleitfaden (`docs/migrations/008-rename-workspace-to-space.md`). Abweichungen/Annahmen laut PR-Beschreibung: Organisationsgrenzverstoß liefert 404 statt 403 (Existenz nicht preisgeben, war im Issue nicht explizit gefordert); der Trockenlauf lief mangels produktivem Datenbestand gegen die Testcontainer-Datenbank statt gegen eine Kopie echter Produktionsdaten (im Migrationsleitfaden begründet); Sidebar erlaubt jetzt allen Nutzern Space-Erstellung, aber keine UI für TEAM-Spaces (bewusst außerhalb des Scopes). CLA-Checkbox im PR war zum Merge-Zeitpunkt nicht abgehakt.

**Verifikation:** `backend/src/main/java/io/opaa/space/Space.java` existiert im heutigen Worktree; das alte Verzeichnis `backend/src/main/java/io/opaa/workspace/` existiert nicht mehr — konsistent mit dem beabsichtigten Hard-Cut-Rename ohne Kompatibilitätsschicht.

**Themen:** spaces, auth, deployment, refactoring, migration, projektsetup
