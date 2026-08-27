# Issue #839 — fix(indexing): UrlIndexingExecutor parst Proxy inline — NumberFormatException bei ungültigem Port
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: #854 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1. `UrlIndexingExecutor.execute` parst Proxy/Credentials inline statt über `ProxyAndCredentials.parse` — dritte Kopie dieser Logik, fängt `NumberFormatException` bei ungültigem Port nicht ab (derselbe Bug wurde im RSS-Pfad mit #642 bereits behoben).

**Geliefert:** Inline-Parsing durch `ProxyAndCredentials.parse` ersetzt. Laut PR wurde die `NumberFormatException` tatsächlich schon vorher vom äußeren `catch (Exception e)` gefangen (Job scheiterte also schon kontrolliert) — die eigentliche Verbesserung ist die verständliche deutsche Fehlermeldung statt der rohen JDK-Meldung. Meldungstext als package-sichtbare Konstante `ProxyAndCredentials.INVALID_PROXY_MESSAGE` extrahiert.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ProxyAndCredentials.java` und `UrlIndexingExecutor.java` im Worktree vorhanden; `UrlIndexingExecutorExecuteTest.java` enthält den Test `anInvalidSourceProxyPortFailsTheJobWithAGermanMessage`.

**Themen:** indexing, bugfix, proxy
