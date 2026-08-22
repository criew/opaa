# Issue #637 — fix(indexing): RSS-Executor wendet sourceInsecureSsl nicht an
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #663 (2026-08-20)

**Laut Issue:** Follow-up aus #505 (Review): `RssFeedIndexingExecutor#execute` baute seinen `HttpClient` immer mit `insecureSsl=false` fest verdrahtet, unabhängig vom konfigurierten `sourceInsecureSsl` der Bibliothek — obwohl die Validierung das Flag für `RSS_FEED` genauso wie für `HTTP_DIRECTORY` erlaubt und `docs/deployment.md` es generisch für beide Quelltypen beschreibt. Gefordert: `targetLibrary.isSourceInsecureSsl()` lesen und übergeben, Test mit selbstsigniertem Zertifikat, ggf. Doku-Präzisierung.

**Geliefert:** `RssFeedIndexingExecutor#execute` übergibt jetzt `targetLibrary.isSourceInsecureSsl()` an `AutoindexCrawlerService.buildHttpClient` statt `false`. Neuer Test `RssFeedIndexingExecutorInsecureSslTest` gegen einen echten `HttpsServer` mit per `keytool` erzeugtem, genuin selbstsigniertem Zertifikat — rot vor dem Fix (`WantedButNotInvoked`), grün danach für beide Fälle (Flag true/false). Dokumentation musste laut PR nicht angepasst werden, da `docs/deployment.md` das Verhalten bereits generisch beschrieb.

**Verifikation:** `RssFeedIndexingExecutor.java` Zeile 187 übergibt `targetLibrary.isSourceInsecureSsl()` an den HttpClient-Aufbau — Umsetzung vorhanden.

**Themen:** backend, indexing, knowledge-sources, security, tls
