# Issue #538 — security(indexing): HTTP-Client folgt Redirects und sendet dabei den Authorization-Header weiter
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, size:S, security
- PRs: #579 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #537 stammender Befund: Die über `AutoindexCrawlerService.buildHttpClient` gebauten HTTP-Clients folgten Redirects (`Redirect.NORMAL`), während der `Authorization`-Header aus hinterlegten Quell-Zugangsdaten selbst gesetzt wird — bei einer Umleitung auf einen fremden Host würden die Zugangsdaten mitgeschickt. Betroffen: `UrlIndexingExecutor`, `RssFeedIndexingExecutor`, `UrlFileDownloader`, `SourceConnectionTestService`. Gefordert: Redirects nicht mehr blind folgen (`Redirect.NEVER` mit kontrollierter manueller Behandlung oder Header nur bei gleichem Host/Origin weitergeben).

**Geliefert:** `buildHttpClient` nutzt jetzt `Redirect.NEVER`; ein neuer Helfer `AutoindexCrawlerService.sendFollowingRedirects` folgt manuell, höchstens fünf Hops, und reicht `Authorization` nur bei gleichem Origin (Schema+Host+Port) weiter. Alle vier Aufrufstellen sind umgestellt. Nach einem Security-Review-Nachtrag wurden zwei zusätzliche Lücken geschlossen: Der Origin-Vergleich ignorierte ursprünglich den Port, und ein Protokoll-Downgrade (https→http) wurde nicht abgelehnt — beides per gemeinsamer `sameOrigin`-/`isSchemeDowngrade`-Methode korrigiert. Bemerkenswert: Der PR-Body hält fest, dass JDK 21 den `Authorization`-Header bei automatischem `Redirect.NORMAL` bei Host-Wechsel bereits selbst entfernte — das ursprüngliche Leck ließ sich auf dieser JDK-Version nicht wörtlich reproduzieren; der tatsächlich nachgewiesene Fehler war, dass eine naive Umstellung auf `Redirect.NEVER` ohne begleitende manuelle Redirect-Behandlung legitime Same-Host-Redirects gebrochen hätte.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AutoindexCrawlerService.java` existiert im Worktree und enthält `sameOrigin`/`Redirect.NEVER`-Logik (15 Treffer für die Suchbegriffe). Umsetzung nachvollziehbar vorhanden.

**Themen:** security, indexing, retrieval, http-client
