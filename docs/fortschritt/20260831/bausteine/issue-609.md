# Issue #609 — fix(library): CI auf main rot — KnowledgeLibraryServiceDeleteLockTest passt nicht zur neuen KnowledgeLibraryService-Signatur
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, ci
- PRs: keine

**Laut Issue:** Beschreibt denselben roten main-Build wie #606 (Konstruktorerweiterung um `AssetGrantService` durch #599 kollidiert mit dem in #602 hinzugekommenen `KnowledgeLibraryServiceDeleteLockTest`, der noch die alte Signatur nutzt). Fordert, den Test analog zum Schwester-Test `KnowledgeLibraryServiceFilesystemAllowlistTest` zu reparieren.

**Geliefert:** Kein PR verknüpft. Laut Kommentar von @criew im Issue: „Duplikat von #606 — bereits behoben durch PR #607 (gemergt); der main-CI-Lauf danach ist wieder grün.“ Issue #609 wurde damit als Duplikat geschlossen, ohne eigenen PR — die Behebung erfolgte über #607 (siehe Baustein zu #606).

**Verifikation:** Siehe Verifikation zu Issue #606 — `KnowledgeLibraryServiceDeleteLockTest.java` existiert und passt heute zur aktuellen Konstruktorsignatur.

**Themen:** backend, ci, bugfix, duplikat
