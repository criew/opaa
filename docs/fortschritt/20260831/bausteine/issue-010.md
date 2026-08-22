# Issue #10 — feat(indexing): implement complete document indexing pipeline
- Geschlossen: 2026-02-25 (completed)
- Labels: enhancement, mvp, backend, size:L
- PRs: #34 (2026-02-25)

**Laut Issue:** Vollständige Indexing-Pipeline: Verzeichnis scannen, Apache Tika zum Parsen, konfigurierbares Chunking, Embedding-Generierung (OpenAI/Ollama austauschbar), Speicherung in PostgreSQL/pgvector über JPA-Entities `Document`/`DocumentChunk`, Job-Tracking, Fehlerbehandlung mit Skip-und-Weiter sowie Retry-Logik.

**Geliefert:** PR #34 liefert die Pipeline größtenteils wie gefordert, weicht aber bewusst von der Speicherarchitektur des Issues ab: Statt manueller `EmbeddingModel.embed()`-Aufrufe und nativer SQL-Inserts in eine eigene `document_chunks`-Tabelle wird Spring AIs `VectorStore`-Abstraktion genutzt (`VectorStore.add()`/`.delete()`). Die im Issue vorgesehene `DocumentChunk`-Entity und `DocumentChunkRepository` wurden dadurch nicht gebaut bzw. wieder entfernt; die Vektor-Tabelle wird stattdessen von Spring AI automatisch verwaltet (`initialize-schema: true`). Der PR-Body begründet dies mit Austauschbarkeit des Vektor-Backends gemäß ADR-0002. Chunking, Job-Tracking, Fehlerbehandlung und konfigurierbare Parameter über `OPAA_INDEXING_*`-Variablen wurden wie gefordert geliefert, inkl. Unit- und Integrationstests mit Testcontainers.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChunkingService.java`, `DocumentIndexingService.java` und `FileProcessingService.java` existieren weiterhin im Worktree.

**Themen:** backend, indexing, rag, pgvector, tika, embedding
