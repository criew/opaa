# Issue #170 — fix(indexing): StackOverflowError when indexing URLs with long query strings
- Geschlossen: 2026-03-09 (completed)
- Labels: bug, backend
- PRs: #171 (2026-03-09)

**Laut Issue:** `UrlIndexingExecutor` verwendete `url.matches(".*\.[a-zA-Z0-9]+$")` zur Erkennung von Dateiendungen in URLs; der `.*`-Präfix führte bei langen URLs (z. B. langen Query-Strings) zu katastrophalem Backtracking und `StackOverflowError`.

**Geliefert:** PR #171 ersetzt den Regex durch `hasFileExtension(url)`: String-basierte Prüfung ohne Regex/Rekursion (Query-String/Fragment abtrennen, letztes Pfadsegment auf Punkt prüfen). Deckt den Fix exakt wie beschrieben ab.

**Verifikation:** `UrlIndexingExecutor.java` enthält `hasFileExtension` (Zeilen 87, 244) im heutigen Worktree — der Fix besteht fort.

**Themen:** indexing, bugfix, performance, url-indexer
