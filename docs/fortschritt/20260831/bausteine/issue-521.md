# Issue #521 — chore(library): System-Wissensbibliothek entfernen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #536 (2026-08-19)

**Laut Issue:** Die System-Wissensbibliothek (`LibraryOwnerType.SYSTEM`, `KnowledgeLibrary.SYSTEM_LIBRARY_ID`, gesät durch Migration 012) sollte samt Inhalt ersatzlos gelöscht werden — keine Datenmigration nötig. Ziel: `LibraryOwnerType` kennt kein `SYSTEM` mehr, keine Sonderlogik dafür im Code, Migrationstest für den Lösch-Changelog.

**Geliefert:** PR #536 liefert genau das: Liquibase-Changelog `031-delete-system-library.yaml` löscht Bibliothek und abhängige Zeilen (Vektorspeicher-Chunks, Indizierungsaufträge, Dokumente, Grants) in FK-Reihenfolge; `Migration031DeleteSystemLibraryTest` belegt es. `LibraryOwnerType.SYSTEM`, `SYSTEM_LIBRARY_ID`, `isSystemLibrary()` sowie alle Sonderfälle (Ablehnung in `createLibrary`, Löschsperre, Systemadmin-Bypass) sind entfernt. OpenAPI-Spec, generierte DTOs und Frontend nachgezogen. Doku (`spaces-and-assets.md`, `STATUS.md`) aktualisiert; historische Migrationsdokumente bewusst unverändert gelassen. Migrationsnummer im PR-Body abweichend als „030" benannt, tatsächlich als 031 gemergt (Kollision mit einem parallelen PR).

**Verifikation:** `LibraryOwnerType.java` im Worktree kennt nur noch `USER`/`GROUP`; der Javadoc dokumentiert die Entfernung von `SYSTEM` explizit. `KnowledgeLibraryService.java` enthält `SYSTEM` nur noch in einem historischen Javadoc-Kommentar, keine Codepfade mehr. Deckt sich mit der PR-Beschreibung.

**Themen:** spaces, library, migration, cleanup, epic-458
