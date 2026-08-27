# Issue #520 — feat(library): Ordner in Dokumentbibliotheken
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, epic, backend, frontend
- PRs: keine eigener PR (Epic mit sechs Sub-Issues #819–#824)

**Laut Issue:** Upload-Bibliotheken sollen Ordner erhalten, durch die man wie in einer Dateiablage navigiert: anlegen (auch leer), umbenennen, löschen, Dateien hineinladen. FILESYSTEM-Bibliotheken sollen ihre echte Verzeichnisstruktur als read-only Ordner abbilden. Konzeptentscheidung: echte Ordner-Entität (`library_folders`, `documents.folder_id`) statt virtueller Pfade; Ordner sind Navigation, keine Rechtegrenze (Grants bleiben bibliotheksweit); Retrieval bleibt im ersten Wurf ordnerunabhängig. Vier Phasen: Konzept/Spezifikation, Backend-Fundament, Frontend, Ausbau (Drag&Drop, FILESYSTEM-Abbildung).

**Geliefert:** Vollständig, laut Abschlusskommentar über sechs Sub-Issues: #819→PR #825 (ADR-0020 "Ordner als Navigation, keine Rechtegrenze" + Spezifikation), #820→PR #827 (Tabelle `library_folders`, `documents.folder_id`, Ordner-CRUD-API), #821→PR #828 (ordnerbewusste Dokumentliste, Breadcrumb, Upload mit `folderId`), #822→PR #830 (Frontend-Ordner-Navigation, `?folder`-URL-State, Anlegen/Umbenennen/Löschen mit Bestätigungsdialog), #824→PR #829 (FILESYSTEM-Struktur wird beim Indexierungslauf idempotent als read-only Ordner materialisiert), #823→PR #831 (Ordner-Upload per Drag&Drop/`webkitdirectory`, idempotente Zwischenordner). Das Issue wurde zwischenzeitlich aus Epic #458 herausgelöst und eigenständig weitergeführt, ohne den Epic-Abschluss zu blockieren.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryFolder.java`, `LibraryFolderChild.java`, `LibraryFolderDetail.java`, `LibraryFolderPaths.java`, `LibraryFolderRepository.java`, `LibraryFolderService.java` existieren im Worktree — konsistent mit der beschriebenen Lieferung.

**Themen:** wissensbibliotheken, ordner, backend, frontend, epic
