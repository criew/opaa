# Issue #611 — test(indexing): Fremd-Host-Tests scheitern auf macOS — 127.0.0.2 ist auf lo0 nicht konfiguriert

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, backend, size:S
- PRs: #1018 (2026-08-28, gemeinsam mit #966)

**Laut Issue:** `UrlFileDownloaderTest` und `RssFeedIndexingExecutorTest` binden für ihre
Fremd-Host-Szenarien einen Testserver an `127.0.0.2`. Linux bindet den gesamten Bereich
`127.0.0.0/8`, macOS standardmäßig nur `127.0.0.1` — auf macOS scheitern die Tests mit
`BindException`, in der Linux-CI sind sie grün.

**Geliefert:** PR #1018 stellt die betroffenen Tests auf `localhost` als Bind-Adresse um; die
Fremd-Host-Eigenschaft wird portabel hergestellt statt über eine zweite Loopback-Adresse.
Behebt zusammen mit #966 alle 127.0.0.2-Bindings der Testsuite.

**Verifikation:** Commit `aeee12b2` auf `main`; die Suite läuft plattformübergreifend.

**Themen:** Testinfrastruktur, Portabilität, macOS
