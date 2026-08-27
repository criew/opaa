# Issue #877 — fix(indexing): Dokumentidentität auf (Bibliothek, Quelle) scopen — Dokument-Stehlen zwischen Bibliotheken beenden
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:M
- PRs: #885 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 4 (Befund B6), vorgezogen per Maintainer-Entscheidung, nach dem Quellenzugriff-Schnitt (#876) umzusetzen. `DocumentRepository.findByFilePath` war global statt bibliotheksgescopt — indizieren zwei Bibliotheken dieselbe URL/denselben Pfad, „stiehlt" jeder Lauf das Dokument der anderen inkl. Chunk-Löschung.

**Geliefert:** Identität auf `(library_id, file_path)` gescopt (`findByLibraryIdAndFilePath`) an allen Dedup-/Change-Detection-Stellen; alte Move-/Steal-Semantik vollständig entfernt. Migration 067 mit Unique-Constraint `uk_documents_library_path`, ergänzt um einen selbstheilenden Cleanup-Changeset (Review-Nachbesserung) als Absicherung für real gewachsene Instanzen. `existsBySourceEntryUrl` (RSS-Anlagen-Backfill) ebenfalls nachträglich auf die Bibliothek gescopt, da dort ein weiterer, im Issue nicht benannter Cross-Library-Leak gefunden wurde. `file_path`-Polymorphie bewusst nur dokumentiert, nicht aufgelöst (wie im Issue vorgegeben).

**Verifikation:** `backend/src/main/resources/db/changelog/changes/067-scope-document-identity-to-library.yaml` und `Migration067ScopeDocumentIdentityToLibraryTest.java` im Worktree vorhanden. Reproduktionsnachweis (roter Test mit „expected: 1L but was: 0L") in PR-Beschreibung dokumentiert.

**Themen:** indexing, bugfix, datenmodell, migration, library
