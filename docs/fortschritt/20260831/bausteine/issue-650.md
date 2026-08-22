# Issue #650 — fix(library): deleteLibrary lehnt Löschung bei laufendem Indizierungsjob mit 409 ab
- Geschlossen: 2026-08-20 (not planned)
- Labels: bug, backend
- PRs: keine

**Laut Issue:** Review-Befund aus PR #503/#501: `deleteLibrary` scheitert bei parallelem Indizierungslauf mit 500 statt 409 (FK-Verletzung), und bereits geschriebene Chunks können den Vector Store verwaisen. Geforderte Behebung: `DELETE /api/v1/libraries/{id}` mit 409 abweisen, solange `IndexingJobService#isJobRunning` `true` liefert.

**Geliefert:** Nichts im Rahmen dieses Issues — laut Abschlusskommentar des Maintainers (`gh issue view 650 --comments`) war der Guard bereits vorher über PR #602 (Umfangserweiterung von #433) umgesetzt: 409 mit deutscher Meldung bei laufendem Indizierungslauf, inklusive Unit-/Integrationstest mit Reproduktionsnachweis und OpenAPI-409-Dokumentation. Issue #650 wurde als Duplikat geschlossen, kein eigener PR nötig.

**Verifikation:** `KnowledgeLibraryService.java` existiert im Worktree; die 409-Logik stammt laut Kommentar aus PR #602, nicht separat nachgeprüft (außerhalb des Chunk-Umfangs).

**Themen:** knowledge-libraries, indexing, duplikat, backend
