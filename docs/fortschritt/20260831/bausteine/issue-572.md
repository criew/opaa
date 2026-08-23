# Issue #572 — Umlaut-Ersatzschreibweisen in weiteren nutzerseitigen Backend-Meldungen bereinigen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #620 (2026-08-20)

**Laut Issue:** Beim Review von PR #571 (#551) aufgefallen: Nach der Korrektur von `SourceConnectionTestService` divergiert der Wortlaut gespiegelter Meldungen — derselbe Dialog zeigt je nach Pfad „nicht zulässig“ und „nicht zulaessig“. Betroffen: `KnowledgeLibraryService#validateConfigurationForType`, `GlobalExceptionHandler`, `LibraryDocumentService`, `GroupService`, `AsyncIndexingExecutor`, sowie eine veraltete OpenAPI-Prosa-Stelle. Gefordert: echte Umlaute überall, gespiegelte Wortlaute wieder zeichengleich, sowie eine systematische Suche nach verbleibenden Ersatzschreibweisen im gesamten Backend.

**Geliefert:** Deckt die genannten Stellen ab und geht darüber hinaus: zusätzlich korrigiert wurden `UrlIndexingExecutor`, `DocumentIndexingService`, `IndexingJobService`, `RssFeedIndexingExecutor`, `AuditQueryService`, `DirectorySyncPlanExecutor`, `CredentialsEncryptor` sowie ein Nachtrag aus dem Review zu PR #576 (`AssetGrantService#upsertGrant`, das zusätzlich API-Feldnamen aus Nutzermeldungen entfernte). MSW-Mocks und Tests, die Wortlaute festschreiben, wurden mitgezogen. Laut PR-Beschreibung wurde das gesamte Backend systematisch nach verbleibenden Ersatzschreibweisen durchsucht.

**Verifikation:** Grep nach `zulaessig`, `gueltig`, `ausserhalb` in `KnowledgeLibraryService.java` liefert keine Treffer mehr — die Korrektur ist im aktuellen Stand sichtbar.

**Themen:** backend, i18n, bugfix, doku
