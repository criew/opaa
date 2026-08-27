# Issue #824 — feat(indexing): FILESYSTEM-Verzeichnisstruktur als read-only Ordner abbilden
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #829 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 4). FILESYSTEM-Bibliotheken sollen ihre Verzeichnisstruktur als read-only Ordner statt flacher Dateiliste abbilden; verschwundene Verzeichnisse werden aufgeräumt; Ordner-CRUD bleibt für FILESYSTEM gesperrt.

**Geliefert:** Wie gefordert. `AsyncIndexingExecutor` materialisiert die Ordnerkette relativ zu `sourcePath` idempotent über `LibraryFolderService#materializeFolderPath`; `pruneOrphanedFolders` entfernt nicht mehr gesehene, leere Ordner nach jedem Lauf — bewusst konservativ, ein Ordner mit einem verwaisten, aber noch existierenden Dokument bleibt stehen (ADR-0017 zur Löschung-durch-Abwesenheit bei Dokumenten selbst noch nicht gebaut). Read-only-Sperre für `renameFolder`/`deleteFolder` zusätzlich abgesichert. ADR-0020 Entscheidung 6 (dieselbe Datei in zwei Unterverzeichnissen bleibt zwei Dokumente) bleibt unverändert gültig.

**Verifikation:** `AsyncIndexingExecutor.java`, `FileProcessingService.java`, `IndexingConfiguration.java` im Worktree vorhanden; `FilesystemFolderMappingIntegrationTest.java` existiert unter `backend/src/test/java/io/opaa/indexing/`.

**Themen:** indexing, library, ordner, filesystem
