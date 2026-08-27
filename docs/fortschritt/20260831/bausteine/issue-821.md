# Issue #821 — feat(library): Dokumentliste und Upload ordner-bewusst machen
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #828 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 2). `GET`/`POST /api/v1/libraries/{libraryId}/documents` sollten ordner-bewusst werden: optionaler `folderId`-Parameter, Response mit Unterordnern und Breadcrumb, `LibraryDocumentResponse` um `folderId`/`folderPath` ergänzt, Suche bleibt bibliotheksweit mit Pfadanzeige.

**Geliefert:** Wie gefordert umgesetzt. `folderId`-Query-/Multipart-Parameter, `LibraryFolderPaths` leitet Anzeigepfade ohne Speicherung ab (ADR-0020), neue gebündelte Repository-Abfragen gegen N+1. Bewusste, dokumentierte Verhaltensänderung: ein Aufruf ohne `folderId` listet jetzt nur die Wurzel statt des gesamten Bestands — betrifft laut PR keinen heutigen Bestand, da Ordner erst mit dem vorausgesetzten Fundament-PR (#827/#820) möglich wurden.

**Verifikation:** `LibraryFolderPaths.java`, `LibraryFolderRepository.java`, `LibraryFolderService.java` existieren im Worktree unter `backend/src/main/java/io/opaa/library/`. `LibraryController.java` und `LibraryDocumentResponseMapper`/`LibraryDocumentResponses` vorhanden (Response-Mapping mittlerweile per DTO-Leak-Serie #860 zusätzlich in `io.opaa.api` verschoben).

**Themen:** library, ordner, backend, api
