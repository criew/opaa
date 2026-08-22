# Issue #200 — Introduce groups as permission subjects
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: #283 (2026-08-02)

**Laut Issue:** `Group`-Entität organisationsgebunden mit Mitgliedern, `Group.kind` (`ORG_UNIT` aus dem Verzeichnis vs. `AD_HOC` im System angelegt), Group-Management-API und Admin-UI (anlegen, umbenennen, löschen, Mitglieder pflegen), eine Permission-Subject-Abstraktion (Nutzer oder Gruppe), gecachte Gruppenmitgliedschaftsauflösung mit sofortiger Invalidierung. Löschen einer Gruppe mit Asset-Eigentum soll blockiert sein; Mitgliedschaft nicht nach unten vererbt.

**Geliefert:** PR #283 liefert `Group`/`GroupMembership`, `PermissionSubject`, `GroupMembershipResolver` (Caffeine-Cache, Invalidierung nach Commit), `GroupService`/`GroupController` unter `/api/v1/admin/groups` (nur System-Admins, nur `AD_HOC`-Gruppen editierbar), Liquibase-Migration 009, Frontend-Seite „Gruppen". Ausdrücklich als nicht umgesetzt benannt: „Löschen einer Gruppe mit Asset-Eigentum blockieren" — es gibt zum Zeitpunkt dieses PRs noch kein Asset-Modell, dafür ein `TODO(#202)` in `GroupService#deleteGroup`. Zwei Review-Runden korrigierten u. a. eine Race-Bedingung bei der Cache-Invalidierung (mit Reproduktionsnachweis) und eine fehlende Organisationsgrenzprüfung im USER-Zweig von `resolveUserIds`. Ein Nit (asymmetrische DB-Grenze) wurde bewusst ausgelagert nach #289.

**Verifikation:** `backend/src/main/java/io/opaa/group/GroupService.java` und `frontend/src/pages/GroupManagementPage.tsx` existieren im heutigen Worktree.

**Themen:** auth, spaces, gruppen, security, migration
