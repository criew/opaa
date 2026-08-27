# Issue #823 — feat(library): Ordner-Upload per Drag & Drop mit Strukturübernahme
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #831 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 4). Ganze Ordner per Drag & Drop bzw. `webkitdirectory`-Dialog hochladen, Struktur wird unterhalb des geöffneten Ordners übernommen; Backend legt Zwischenordner idempotent an; bestehende Limits gelten je Datei unverändert.

**Geliefert:** Wie gefordert. Backend: `folderPath`-Multipart-Parameter, `LibraryFolderService#resolveOrCreateFolderPath` legt Zwischenordner idempotent an (Unique-Constraint-Race abgefangen), teilt `materializeSingleFolder` mit dem parallelen FILESYSTEM-Pfad aus #824, erzwingt aber zusätzlich Berechtigung/Bibliothekstyp/Validierung/Tiefenlimit. Frontend: rekursive Auflösung über `DataTransferItem.webkitGetAsEntry()` inkl. wiederholter `readEntries()`-Aufrufe (Seitenweise-Problem), zusätzlicher Button „Ordner hochladen".

**Verifikation:** `frontend/src/utils/directoryEntries.ts` existiert im Worktree; `LibraryFolderService.java` und `LibraryDocumentService.java` vorhanden.

**Themen:** library, ordner, upload, frontend, backend
