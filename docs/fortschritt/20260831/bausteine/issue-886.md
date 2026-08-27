# Issue #886 — feat(indexing): Dokumente verschwundener Quellen aufräumen — veralteter Bestand wächst unbegrenzt
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M
- PRs: #900 (2026-08-25)

**Laut Issue:** Kein Indexlauf löschte Dokumente, deren Datei/URL in der Quelle nicht mehr existierte — Zeilen und Chunks blieben dauerhaft bestehen. Gefordert war ein Aufräummechanismus je Quellentyp (FILESYSTEM, HTTP_DIRECTORY, RSS) am Ende eines erfolgreichen Volllaufs, inklusive Chunk-Löschung und unter Beachtung der Truncation-Flags aus #836/#851, damit ein gekappter Lauf nicht fälschlich löscht.

**Geliefert:** Neuer `StaleDocumentCleanupService` entfernt für FILESYSTEM/HTTP_DIRECTORY Dokumente samt Chunks, deren Pfad/URL im aktuellen Lauf nicht mehr vorkommt, skopiert auf Bibliothek+Quelle. Mehrere Sicherungen: Aufräumen nur auf dem Erfolgspfad, `UrlIndexingExecutor` gated zusätzlich auf `!truncated() && !incomplete()`, `AsyncIndexingExecutor` wirft jetzt eine `IOException` bei fehlendem/kein-Verzeichnis-`sourcePath`, und eine leere Ist-Menge löscht grundsätzlich nichts. RSS räumt bewusst **nicht** auf (ADR-0017, Entscheidung 5) — nur ein Regressionstest und ein struktureller Test wurden ergänzt. Neues Ereignis `IndexingRunEventCategory.REMOVED` protokolliert jede Löschung. Deckt sich mit der Forderung; ADR-0017 bleibt bewusst auf „Vorgeschlagen“.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/StaleDocumentCleanupService.java` existiert im Worktree.

**Themen:** indexing, retrieval, datenbereinigung, rss
