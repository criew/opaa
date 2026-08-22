# Issue #9 — chore: set up PostgreSQL schema with pgvector and Liquibase
- Geschlossen: 2026-02-20 (completed)
- Labels: mvp, backend, setup, size:M
- PRs: #28 (2026-02-20)

**Laut Issue:** Liquibase-Changesets für pgvector-Extension, Tabellen `documents`, `document_chunks` (mit Vektor-Embeddings) und `indexing_jobs` sowie HNSW-Index auf `document_chunks.embedding`; `docker-compose.yml` nur mit PostgreSQL für lokale Entwicklung.

**Geliefert:** PR #28 liefert `docker-compose.yml` mit PostgreSQL 18 + pgvector, Liquibase-Changesets für alle drei Tabellen inkl. HNSW-Index, Liquibase-Startup-Konfiguration mit deaktivierter Spring-AI-Auto-Schema-Initialisierung sowie Testcontainers-Umstellung auf `pgvector/pgvector:pg18`. Deckt die Anforderung vollständig ab.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/001-enable-pgvector-extension.yaml`, `002-create-documents-table.yaml` und `docker-compose.yml` existieren weiterhin im Worktree. Anzumerken: Die ursprüngliche `document_chunks`-Tabelle aus #9 wurde in #10 (PR #34) durch Spring AIs `VectorStore`-Abstraktion (`vector_store`-Tabelle, autogeneriert) ersetzt — das dortige Changeset 003 wurde entfernt und die Nummer für `indexing_jobs` wiederverwendet. Das Grundschema aus #9 (pgvector-Extension, `documents`-Tabelle, Docker-Compose-Fundament) besteht fort, die konkrete Chunk-Speicherung wurde architektonisch verändert.

**Themen:** backend, datenbank, pgvector, liquibase, deployment
