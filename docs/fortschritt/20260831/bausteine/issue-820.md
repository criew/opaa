# Issue #820 — feat(library): Schema und CRUD-API für Bibliotheksordner
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #827 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 2 — Backend-Fundament), aufbauend auf der Spezifikation aus #819. Verlangt eine Liquibase-Migration für `library_folders` (inkl. partiellem Unique-Index für den NULL-Parent-Fall) und `documents.folder_id`, eine `LibraryFolder`-Entität mit Namens-Validierung, Tiefenlimit und Zyklen-Schutz, sowie eine OpenAPI-first CRUD-API (`POST`/`PATCH`/`DELETE` unter `/api/v1/libraries/{libraryId}/folders`) mit EDITOR-Rechteschwelle, ausschließlich für UPLOAD-Bibliotheken, rekursivem Löschen über den bestehenden Dokument-Service-Pfad (keine DB-Kaskade) und Tests für Rechte-, Konflikt- und Rekursionsfälle.

**Geliefert:** Migration 062 mit `library_folders` (zwei partiellen Unique-Indexen für Wurzel- vs. verschachtelte Ebene) und `documents.folder_id`; Entität/Repository/Service mit Tiefenlimit 10 und Zyklen-Schutz; API wie gefordert plus einem zusätzlichen `GET`-Endpunkt auf einen einzelnen Ordner (im Issue nur als Option vorgeschlagen, jetzt umgesetzt, um die rekursive Dokumentanzahl für den Bestätigungsdialog bereitzustellen). Rekursives Löschen läuft über `LibraryDocumentService#deleteDocument`. Laut PR bewusst außerhalb des Umfangs belassen: `documents.folder_id` wird in diesem PR nur als Spalte/FK eingeführt, noch nicht von einem Upload-Pfad gesetzt (folgt laut Epic in #821). Unit- und Integrationstests wie gefordert vorhanden.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryFolder.java` und `backend/src/main/resources/db/changelog/changes/062-create-library-folders.yaml` existieren im Worktree.

**Themen:** backend, ordner, spaces, datenmodell, api
