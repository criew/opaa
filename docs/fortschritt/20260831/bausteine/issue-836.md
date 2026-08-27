# Issue #836 — fix(indexing): Autoindex-Crawler ohne Tiefen- und Besuchslimit — Zyklen terminieren nicht
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: #851 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund B7). `AutoindexCrawlerService.crawlRecursive` hat kein Tiefen-/Besuchslimit; Zyklen (z. B. Symlink-Schleifen) führen zu endloser Rekursion, StackOverflow-Risiko, hängendem Indexing-Thread.

**Geliefert:** Visited-Set über normalisierte URLs, konfigurierbare maximale Rekursionstiefe (Default 10, `opaa.indexing.crawl.max-depth`), Obergrenze für Gesamtanzahl gecrawlter Einträge und besuchter Verzeichnisse (Default 5000, `opaa.indexing.crawl.max-entries`). Review-Nachbesserungen behoben zusätzlich einen reinen Verzeichnis-Zyklus-Blindspot (Visited-Set griff dort nie) und getrennte Truncation-Flags pro Grund. Mitgenommen: ein Bug, bei dem `staysUnderBase`-Vergleiche vor statt nach der Normalisierung liefen und dadurch für relative Hrefs wirkungslos waren.

**Verifikation:** `AutoindexCrawlerService.java`, `CrawlProperties.java` im Worktree vorhanden; `AutoindexCrawlerServiceCrawlLimitsTest.java` existiert. Reproduktionsnachweis mit Timeout-Fehlermeldung in PR-Beschreibung dokumentiert.

**Themen:** indexing, bugfix, sicherheit, crawler
