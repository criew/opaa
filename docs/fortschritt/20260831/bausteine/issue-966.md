# Issue #966 — test(backend): Redirect-/Downloader-Tests binden 127.0.0.2 und scheitern auf macOS

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, backend, size:S
- PRs: #1018 (2026-08-28, gemeinsam mit #611)

**Laut Issue:** Beim lokalen Durchlauf der Pre-Push-Checkliste auf macOS schlagen 14
Backend-Tests mit `BindException` fehl (`RedirectFollowingFetcherTest`, `BoundedDownloaderTest`,
Teile von `RssFeedIndexingExecutorTest` und `LibraryDocumentServiceIntegrationTest`): Sie
starten einen „fremden Host" als lokalen `HttpServer` auf `127.0.0.2`, das macOS standardmäßig
nicht bindet.

**Geliefert:** PR #1018 bindet die Fremdhost-Testserver portabel als `localhost` (gemeinsame
Behebung mit #611).

**Verifikation:** Commit `aeee12b2` auf `main`.

**Themen:** Testinfrastruktur, Portabilität, macOS
