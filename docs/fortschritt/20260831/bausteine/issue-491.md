# Issue #491 — fix(indexing): Skip-Prüfung des URL-Wegs ignoriert die Zielbibliothek
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #645 (2026-08-20)

**Laut Issue:** `UrlIndexingExecutor.isUnchanged` überspringt ein unverändertes Dokument, ohne die Zielbibliothek zu prüfen — dieselbe Quelle in eine andere Bibliothek indiziert bleibt fälschlich in der alten liegen. Der RSS-Weg hatte dieselbe Lücke bereits behoben (#467/PR #490); dieser Altbestand im URL-Weg blieb offen.

**Geliefert:** Wie gefordert. `isUnchanged` berücksichtigt jetzt zusätzlich `targetLibrary.getId()`, spiegelbildlich zum RSS-Fix. Reproduktionsnachweis mit rotem/grünem Testlauf im PR dokumentiert (`UrlIndexingExecutorTest#isUnchanged_returnsFalseWhenTargetLibraryDiffersFromTheExistingDocuments`).

**Verifikation:** `backend/src/main/java/io/opaa/indexing/UrlIndexingExecutor.java` und der zugehörige Test existieren im heutigen Code.

**Themen:** backend, bugfix, indexing, retrieval
