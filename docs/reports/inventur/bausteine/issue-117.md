# Issue #117 — feat(workspace): connector-workspace integration
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Source-Mappings (1:N) sollten Konnektor-Quellen auf Workspaces abbilden, inkl. Admin-API zum Anlegen/Ändern/Löschen von Mappings und Indexierungs-Integration. Teil von Epic #107, Phase 3.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR wegen Ablösung durch das Space-/Asset-Modell (Epic #198). Laut Schließungskommentar entfällt die 1:N-Zuordnung Quelle→Workspaces; eine Konnektor-Quelle indiziert jetzt in genau eine Wissensbibliothek. Nachfolger ist #207 (Connector sources target exactly one knowledge library).

**Verifikation:** Kein `source_mappings`-Konzept im heutigen Code auffindbar; Konnektoren sind an Wissensbibliotheken gebunden (`io.opaa.library`, `KnowledgeLibraryService`), konsistent mit dem Schließungskommentar.

**Themen:** workspaces, spaces, konnektoren, wissensbibliothek, migration, verworfen
