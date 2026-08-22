# Issue #149 — fix(workspace-ui): state leak on logout, member display names, collapsible sections, remove redundant info alert
- Geschlossen: 2026-03-08 (completed)
- Labels: bug, enhancement
- PRs: #151 (2026-03-08)

**Laut Issue:** Vier gebündelte Punkte: (1) Workspace-State wird beim Logout nicht zurückgesetzt (Cross-User-Leak), (2) Mitglieder werden als rohe UUIDs statt Anzeigenamen angezeigt, (3) Dokumente/Mitglieder-Abschnitte sollen einklappbar sein, (4) redundanter „Persönlicher Workspace"-Hinweis auf der WorkspacePage soll entfernt werden.

**Geliefert:** PR #151 setzt alle vier Punkte um: `workspaceStore` wird bei Logout zurückgesetzt, Backend löst Anzeigenamen über `UserRepository` auf (Fallback auf UUID), Dokumente/Mitglieder als einklappbare MUI-Accordions, redundanter Alert entfernt.

**Verifikation:** `WorkspacePage.tsx` und `workspaceStore.ts` existieren im heutigen Code nicht mehr — durch die Workspace→Space-Umbenennung (Commit `75abc6d3`, Epic #198) in `SpacePage.tsx`/`spaceStore.ts` überführt. Die hier gelieferte Logik (State-Reset bei Logout, Anzeigenamen, Accordions) ist damit vermutlich in die Nachfolgekomponenten übergegangen; eine Detailprüfung des heutigen Verhaltens wurde im Rahmen dieser Recherche nicht vorgenommen.

**Themen:** workspaces, spaces, frontend, bugfix, ux, migration
