# Issue #122 — feat(ui): workspace management (members and roles)
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, frontend, size:L, workspace
- PRs: #142 (2026-03-07), #150 (2026-03-08)

**Laut Issue:** Workspace-Settings-Seite (Name/Beschreibung bearbeiten, Löschen), Mitgliederverwaltung (hinzufügen/entfernen, Rolle ändern, Eigentümerwechsel), rollenabhängige UI-Sichtbarkeit, Sonderfall Persönlicher Workspace.

**Geliefert:** PR #142 lieferte die Management-Seite mit Rollenänderung, Mitglieder-Add/Remove, Eigentümerwechsel und Löschen/Update-Flows. PR #150 ergänzte separat einen Dialog zum Anlegen neuer geteilter Workspaces (nur System-Admin) — im ursprünglichen Issue-Scope nicht explizit gefordert, aber sachlich naheliegende Ergänzung.

**Verifikation:** `WorkspaceManagementPage.tsx` existiert im Worktree nicht mehr; wie bei #121 durch Commit `75abc6d3` auf `SpaceManagementPage.tsx`/`spaceStore.ts` umbenannt (Space-Modell, Epic #198). Die Funktionalität besteht unter neuem Namen fort, u. a. bestätigt durch Issue #144, das `SpaceManagementPage.tsx` als bestehende, weiterentwickelte Datei referenziert.

**Themen:** workspaces, spaces, frontend, rechteverwaltung, migration
