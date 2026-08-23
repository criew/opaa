# Issue #115 — feat(indexing): workspace_ids in chunk metadata and query filter
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:L, workspace
- PRs: keine

**Laut Issue:** `workspace_ids` (Liste von UUIDs) sollte als Chunk-Metadatum eingeführt werden, damit die Vektorsuche zur Abfragezeit nach den Workspace-Mitgliedschaften der anfragenden Person filtert — integriert in `VectorStore.similaritySearch()`, nicht als Nachfilter. Teil von Epic #107 (Workspaces & Access Control, Phase 3).

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Als „completed" ohne PR geschlossen, weil das zugrunde liegende Workspace-Modell komplett durch das Space-/Asset-Modell (Epic #198) ersetzt wurde. Laut Schließungskommentar entfällt die n:m-Zuordnung `chunk.workspace_ids` zugunsten einer einwertigen `library_id` je Chunk (n:1), da der Rechteanker jetzt die Wissensbibliothek ist statt der Workspace. Der fachliche Kern — Rechtefilter als Bestandteil der Vektorsuche statt Nachfilter — wurde in Nachfolge-Issue #202 (Asset-Rechte und rechtebewusste Vektorsuche) übernommen und dort tatsächlich umgesetzt.

**Verifikation:** Im heutigen Code gibt es kein Workspace-Modell mehr (Umbenennung/Ablösung durch Space, Commit 75abc6d3 u. a.). Rechte an Chunks laufen über die Wissensbibliothek (`io.opaa.library`), nicht über `workspace_ids`. Deckt sich mit dem Schließungskommentar.

**Themen:** workspaces, retrieval, spaces, migration, verworfen
