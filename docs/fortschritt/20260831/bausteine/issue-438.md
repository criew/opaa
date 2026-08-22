# Issue #438 — feat(frontend): Eigentümername und Dokumentanzahl in LibraryListResponse ausweisen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, workspace
- PRs: #601 (2026-08-20)

**Laut Issue:** `LibraryListResponse` sollte um `ownerName` (aufgelöster Gruppen-/Nutzername) und `documentCount` ergänzt werden, damit `LibraryManagementPage` in der Liste nicht mehr generisch „Gruppen-Bibliothek" anzeigt und die Dokumentanzahl bereits eingeklappt sichtbar ist.

**Geliefert:** PR #601 setzt nur den `ownerName`-Teil um. Laut PR-Beschreibung war der `documentCount`-Teil zum Zeitpunkt des PRs bereits anderweitig umgesetzt und wurde in der Liste bereits angezeigt — der PR ergänzt ausschließlich das fehlende `ownerName`-Feld (OpenAPI-Erweiterung, gebündelte Auflösung ohne N+1 in `KnowledgeLibraryService#listLibraries`, Fallback auf generische Bezeichnung bei fehlendem Namen). Vollständige Erfüllung des Issues, nur mit geteilter Historie der beiden Teilaspekte.

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` und `frontend/src/pages/LibraryManagementPage.tsx` existieren im heutigen Code.

**Themen:** workspace, spaces, frontend, backend, api
