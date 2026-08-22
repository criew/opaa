# Issue #606 — main-Build rot: KnowledgeLibraryServiceDeleteLockTest passt nicht zum erweiterten Konstruktor
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #607 (2026-08-20)

**Laut Issue:** Nach dem Merge von #599 und #602 kompiliert `compileTestJava` auf `main` nicht mehr: #599 hatte dem `KnowledgeLibraryService`-Konstruktor die Abhängigkeit `AssetGrantService` hinzugefügt, während der in #602 neu hinzugekommene `KnowledgeLibraryServiceDeleteLockTest` noch gegen die alte Konstruktorsignatur geschrieben war — ein semantischer Merge-Konflikt zweier für sich grüner PRs ohne Git-Textkonflikt.

**Geliefert:** PR #607 ergänzt den fehlenden `AssetGrantService`-Mock im Test und übergibt ihn an den Konstruktor. Reproduktionsnachweis ist der rote main-CI-Lauf selbst (Lauf 32367485718); mit dem Fix kompiliert `compileTestJava` und die Testklasse läuft grün. Kein Scope-Abweichen erkennbar.

**Verifikation:** `backend/src/test/java/io/opaa/library/KnowledgeLibraryServiceDeleteLockTest.java` existiert im heutigen Worktree.

**Themen:** backend, ci, bugfix, merge-konflikt
