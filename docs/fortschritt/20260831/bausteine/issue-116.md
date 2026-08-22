# Issue #116 — feat(upload): document metadata table and workspace-aware upload
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Eine separate `document_metadata`-Tabelle (Eigentümer, `home_workspace_id`, Originaldatei-Speicherpfad) sowie ein workspace-bewusster Upload-Endpunkt (`POST /api/v1/workspaces/{id}/documents`) mit Editor-Rechteprüfung, Dokumentliste und Download-Endpunkt. Teil von Epic #107, Phase 3.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR, weil das Workspace-Modell durch Space-/Asset-Modell (Epic #198) abgelöst wurde. Laut Schließungskommentar gehört ein Dokument nicht mehr zu einem `home_workspace_id`, sondern zu genau einer Wissensbibliothek; diese Zuordnung wurde in Nachfolge-Issue #201 (Wissensbibliothek als Dokumentencontainer, Migration 012) umgesetzt, die Rechte dazu in #202 (Asset-Rechte).

**Verifikation:** Es existiert im Worktree keine `document_metadata`-Tabelle und kein `workspaces`-Upload-Endpunkt; stattdessen ist `io.opaa.library` mit `LibraryDocumentService` und Upload-Funktionalität vorhanden (bestätigt u. a. über Issue #119/PR #700, das `LibraryDocumentService#uploadDocument` referenziert).

**Themen:** workspaces, spaces, wissensbibliothek, upload, migration, verworfen
