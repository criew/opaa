# Issue #95 — URL-based document indexing via Apache mod_autoindex crawling
- Geschlossen: 2026-03-06 (completed)
- Labels: enhancement, backend, size:L
- PRs: #96 (2026-03-06)

**Laut Issue:** Möglichkeit ergänzen, Dokumente von HTTP-Servern mit Apache-mod_autoindex-Verzeichnislisten zu indexieren — Crawler-Service, Datei-Downloader, asynchroner Indexierungs-Executor, erweiterte Trigger-API mit optionalem URL/Proxy/Credentials/SSL-Body, neue `source_type`-Spalte, `lastModified` als Änderungsindikator statt Download bei unveränderten Dateien. Plus Frontend-UI-Felder und Doku-Updates.

**Geliefert:** PR #96 liefert alle genannten Bausteine: `AutoindexCrawlerService` (regelbasierter HTML-Parser statt Regex), `UrlFileDownloader`, `UrlIndexingExecutor`, erweiterte `IndexingController`, Migration `004-add-source-type-to-documents.yaml`, Frontend-Accordion in `AdminDrawer`, OpenAPI- und Feature-Spec-Updates, umfangreiche Tests. Keine erkennbare Abweichung vom Issue-Umfang.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AutoindexCrawlerService.java` existiert im heutigen Worktree weiterhin.

**Themen:** indexing, crawler, backend, frontend
