# Issue #876 — refactor(indexing): Quellenzugriff als eigenes Paket — eine Redirect-Policy, RssFeedIndexingExecutor zerlegen
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L
- PRs: #883 (2026-08-24, PR 2 von 2 — PR 1 ist #882, nicht separat in diesem Chunk gelistet)

**Laut Issue:** Teil von Epic #826, Phase 4 (Befund B7), vorgezogen per Maintainer-Entscheidung. Quellenzugriff (HTTP-Client, Redirect-Verfolgung, Proxy/Credentials, Downloads) verstreut über `indexing`, teils als statische Aufrufe auf `AutoindexCrawlerService`. Vier divergierende Redirect-Loops; `RssFeedIndexingExecutor` als ~1300-Zeilen-Gottobjekt. Neues Paket `io.opaa.sourceaccess`, eine Redirect-Implementierung, Zerlegung des Executors.

**Geliefert:** Laut verlinktem PR #883 (2 von 2) wurde das neue Paket `io.opaa.sourceaccess` bereits in PR #882 angelegt (`RedirectFollowingFetcher` u. a.); #883 ergänzt `RedirectFollowingFetcherTest` (7 Fälle je Policy-Zweig) und zerlegt `RssFeedIndexingExecutor` in `RssFeedRunContext`, `FeedFetcher`, `DetailPageExtractor`, `AttachmentIndexer` (alle package-intern, kein neues öffentliches API). Ergebnis: `RssFeedIndexingExecutor.java` auf 462 Zeilen reduziert (<500 ✓), keine Methode über 6 Parameter. Politeness-`Thread.sleep` bewusst nicht in dieser Runde angefasst (separater Befund).

**Verifikation:** `backend/src/main/java/io/opaa/sourceaccess/RedirectFollowingFetcher.java`, `backend/src/main/java/io/opaa/indexing/{RssFeedIndexingExecutor,RssFeedRunContext,FeedFetcher,DetailPageExtractor,AttachmentIndexer}.java` im Worktree vorhanden.

**Themen:** indexing, refactoring, sicherheit, quellenzugriff, backend
