# Issue #165 — fix: URL indexer stores temp filename instead of original filename in document DB
- Geschlossen: 2026-03-09 (completed)
- Labels: bug
- PRs: #169 (2026-03-09)

**Laut Issue:** Beim Indexieren via URL wurde der Dateiname der lokalen temporären Downloaddatei (z. B. `opaa-1234567890.pdf`) statt des Original-Dateinamens vom Remote-Server in der Dokumenten-DB gespeichert.

**Geliefert:** PR #169 ergänzt `processUrlFile()` um einen `originalFileName`-Parameter, `UrlIndexingExecutor` übergibt `entry.name()`; Regressionstest `processUrlFileUsesOriginalFilenameNotTempFilename` ergänzt. Deckt den Fix exakt wie beschrieben ab.

**Verifikation:** `FileProcessingService.java` enthält den Parameter `originalFileName` an mehreren Stellen (u. a. Zeilen 146, 154, 176) im heutigen Worktree — der Fix besteht fort.

**Themen:** indexing, bugfix, url-indexer
