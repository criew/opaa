# Issue #682 — feat(space): Quellen- und Chatzahl in SpaceListResponse für die Übersichtskarten
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:S
- PRs: #754 (2026-08-23)

**Laut Issue:** Die Spaces-Übersicht (#593, Mockup 1c) sollte je Karte "n Quellen · n Chats · n Mitglieder" zeigen; `SpaceListResponse` lieferte bisher nur `memberCount`. Gefordert war die Erweiterung um `libraryCount` und `chatCount` (spec-first), ohne N+1-Abfragen, und der Frontend-Anschluss.

**Geliefert:** Wie gefordert. `SpaceListResponse` um optionale Felder `libraryCount`/`chatCount` erweitert. `chatCount` zählt nur die eigenen Chats des Aufrufers (Chats sind privat, #525). `libraryCount` folgt der Sichtbarkeitsregel der Zuordnungsliste (CURATOR/ADMIN/Owner/Systemadmin sehen alle, MEMBER nur lesbare) — bewusst so gewählt, damit die Zahl keine Rechte verrät, die die gefilterte Liste selbst nicht zeigt. Ohne N+1: eine gruppierte Chat-Zählung über alle gelisteten Spaces plus eine Zuordnungsabfrage. Nebeneffekt: die bisherige Einzelabfrage `existsBySpaceIdAndAuthorId` je archiviertem Space (#543) konnte entfallen. Frontend: `SpacesOverviewPage.spaceFigures` mit Singular/Plural und Fallback ohne die neuen Felder.

**Verifikation:** Nicht erneut geprüft — Änderung ist klein und lokal begrenzt (`SpaceService`, `SpaceListResponse`); Testabdeckung im PR-Body dokumentiert (`SpaceServiceIntegrationTest#listCountsAssignedLibrariesAndOnlyTheCallersOwnChats`).

**Themen:** spaces, api, frontend
