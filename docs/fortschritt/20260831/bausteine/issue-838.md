# Issue #838 — refactor(indexing): VectorStore-Delete-Filter über gemeinsamen Helfer statt String-Konkatenation
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:S
- PRs: #849 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1. VectorStore-Delete-Filter wurden an ~10 Stellen per String-Konkatenation gebaut statt über die vorhandene typsichere `FilterExpressionBuilder`-API — Wartbarkeitsrisiko.

**Geliefert:** Neuer Helfer `VectorChunkStore` (`io.opaa.indexing`) mit `deleteByDocumentId(UUID)`/`deleteByLibraryId(UUID)`, intern über `FilterExpressionBuilder`. Alle ~10 Aufrufstellen in `FileProcessingService`, `LibraryDocumentService`, `KnowledgeLibraryService` umgestellt. Bewusst in `io.opaa.indexing` platziert, um keine neue Abhängigkeit `library`→`indexing` zu schaffen. Reines Refactoring ohne Verhaltensänderung.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/VectorChunkStore.java` und `VectorChunkStoreTest.java` im Worktree vorhanden.

**Themen:** indexing, refactoring, vectorstore, wartbarkeit
