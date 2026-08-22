# Issue #121 — feat(ui): workspace view and workspace-filtered search
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, frontend, size:L, workspace
- PRs: #141 (2026-03-07)

**Laut Issue:** Sidebar-Umbau mit „Workspaces"- und „Chats"-Abschnitten, workspace-gefilterte Suche im Chat-Input, Workspace-Kontext auf Quellenkarten, Workspace-Detailansicht mit Dokumentliste.

**Geliefert:** PR #141 setzte den Umfang vollständig um: neue Sidebar-Struktur, Workspace-Detailseite, workspace-bewusste Query-Filterung im Chat-Input, Workspace-Badges auf Source-Cards, dazu Store/API/MSW-Anbindung.

**Verifikation:** Die im PR genannten Dateien (`WorkspacePage.tsx`, `WorkspaceManagementPage.tsx`, `workspaceStore.ts`) existieren im heutigen Worktree nicht mehr. `git log` zeigt für `frontend/src/pages/WorkspacePage.tsx` als letzten Commit `75abc6d3` „feat(space)!: Workspace in Space umbenennen, Organisationsgrenze und neue Space-Rollen einführen" — die Funktionalität wurde also nicht ersatzlos entfernt, sondern im Zuge der Workspace→Space-Umbenennung (Epic #198) in `SpacePage.tsx`/`spaceStore.ts` überführt. Die gelieferte Funktion besteht damit unter neuem Namen fort.

**Themen:** workspaces, spaces, frontend, suche, migration
