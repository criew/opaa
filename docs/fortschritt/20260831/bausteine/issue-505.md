# Issue #505 — feat(indexing): RSS-Executor nutzt hinterlegte Zugangsdaten nicht
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S
- PRs: #642 (2026-08-20)

**Laut Issue:** `RssFeedIndexingExecutor` liest `sourceCredentials`/`sourceProxy` nicht, obwohl das Schema (#476) und die Doku deren Nutzung für `RSS_FEED`-Bibliotheken bereits in Aussicht stellen — nur `UrlIndexingExecutor` tut das. Entscheidung: entweder umsetzen oder die Felder für `RSS_FEED` ausdrücklich ausschließen.

**Geliefert:** Entscheidung für die Umsetzung. `RssFeedIndexingExecutor` wendet Basic Auth und Proxy jetzt auf Feed-Abruf, Detailseiten und Anlagen-Downloads an, analog zu `UrlIndexingExecutor`. Der `Authorization`-Header wird an mehreren Stellen (Redirect-Handling, `UrlFileDownloader#downloadBounded`) explizit vor der Weitergabe an einen fremden Host geschützt. `sourceInsecureSsl` bleibt bewusst außerhalb dieses Issues (Follow-up #637).

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedIndexingExecutor.java` und `ProxyAndCredentials.java` existieren im heutigen Code.

**Themen:** backend, feeds, sicherheit, retrieval
