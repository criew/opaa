# Issue #651 — fix(indexing): Redirect-Härtung lässt Host==null als 'nicht fremd' durch und ein Lauf bricht bei ungültiger Eintrags-URL komplett ab
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, security
- PRs: #664 (2026-08-20)

**Laut Issue:** Zwei Befunde aus dem Review zu PR #642: (1) `isForeignHostRedirect` in `RssFeedIndexingExecutor`/`UrlFileDownloader` behandelte einen nicht parsbaren Host (`getHost()==null`) fälschlich als "nicht fremd" statt wie `AutoindexCrawlerService.sameOrigin` als fremd. (2) `RssFeedIndexingExecutor#processEntry` fing keine `IllegalArgumentException` bei ungültiger Eintrags-URL ab, wodurch der gesamte Indizierungslauf statt nur des einzelnen Eintrags abbrach.

**Geliefert:** Beide Methoden delegieren jetzt vollständig an `AutoindexCrawlerService.sameOrigin`; eine neue `isValidUri`-Prüfung fängt ungültige Eintrags-URLs vorab ab und überspringt nur den betroffenen Eintrag. Deckt sich mit der Forderung des Issues, keine Abweichungen.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedIndexingExecutor.java` und `UrlFileDownloader.java` existieren im Worktree. Reproduktionsnachweis mit rotem/grünem Testlauf ist im PR dokumentiert (`UrlFileDownloaderTest`, `RssFeedIndexingExecutorTest`).

**Themen:** indexing, security, redirect-härtung, rss
