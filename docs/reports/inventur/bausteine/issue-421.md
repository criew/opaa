# Issue #421 — feat(frontend): Wissensbibliotheken auflisten und verwalten
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, frontend, size:M, workspace
- PRs: #437 (2026-08-17)

**Laut Issue:** Das Frontend kannte Bibliotheken überhaupt nicht (kein API-Aufruf, kein Store, keine Seite), obwohl das Backend den CRUD-Weg seit #201/#202 bereitstellte. Gefordert: Seite „Wissensbibliotheken" nach Vorbild `SpaceManagementPage`/`GroupManagementPage`, Anlegen-Dialog nach Vorbild `CreateSpaceDialog`, Bedienelemente gestaffelt nach `myRole` (Bearbeiten ab MANAGER, Löschen ab OWNER), Gruppen-Eigentümerwahl nur aus Gruppen, in denen der Nutzer tatsächlich Mitglied ist.

**Geliefert:** PR #437 liefert `LibraryManagementPage`, `libraryStore`, `CreateLibraryDialog`, Sidebar-Eintrag und Route `/libraries`. Abweichung/Erweiterung: Laut PR-Body stellte sich im Code-Review heraus, dass das Abnahmekriterium „nur Gruppen anbieten, in denen der Nutzer Mitglied ist" ohne Backend-Änderung nicht erfüllbar war — daraufhin wurde zusätzlich ein neuer, nicht admin-beschränkter Endpunkt `GET /api/v1/me/groups` (`MeController`, `GroupService#listMyGroups`) eingeführt. Damit wurde aus dem reinen Frontend-Issue ein Fullstack-PR. Drei Annahmen/Einschränkungen wurden als Follow-up-Issues ausgelagert: #438 (fehlender aufgelöster Gruppenname und Dokumentanzahl in der Liste), #439 (System-Bibliothek bleibt über die Seite unerreichbar), #440 (weiteres, store-übergreifendes Follow-up).

**Verifikation:** `frontend/src/pages/LibraryManagementPage.tsx` existiert im heutigen Worktree wie im PR beschrieben.

**Themen:** spaces, workspace, frontend, epic-198, gruppen
