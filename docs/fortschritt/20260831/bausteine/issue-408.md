# Issue #408 — fix(indexing): Vor #202 indizierte Chunks tragen kein library_id und sind dauerhaft unauffindbar
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:M
- PRs: keine (linkedPRs leer im Datensatz)

**Laut Issue:** Der Issue-Body ist mit „@-" leer; aus dem Titel geht hervor, dass Chunks, die vor Einführung der Bibliotheks-Metadaten (#202) im Vektorspeicher landeten, kein `library_id`-Metadatum tragen und dadurch dauerhaft unauffindbar sind — ein Backfill fehlt.

**Geliefert:** Kein PR ist im Chunk-Datensatz mit #408 verknüpft, das Issue wurde aber tatsächlich erledigt. Git-Historie zeigt PR #412 vom Branch `feature/408_vector-store-bibliotheks-metadaten`, gemerged am 15.08.2026 (Commit `8c01fa52`, Merge `11946dea`), mit dem Titel „fix(indexing): Bibliothekszuordnung in den Chunk-Metadaten nachtragen". Geliefert wurden eine Liquibase-Migration `016-backfill-vector-store-library-metadata.yaml`, eine zugehörige Migrationstestklasse `Migration016VectorStoreLibraryMetadataTest.java` sowie eine Test-Fixture `test-master-through-015.yaml`. Die Verknüpfung zum Issue fehlt im Rohdaten-Export vermutlich, weil der PR-Titel nicht „Closes #408" im erwarteten Format enthielt oder die Verknüpfung anderweitig nicht erfasst wurde — inhaltlich passt PR #412 exakt zum Issue.

**Verifikation:** Die Migration `016-backfill-vector-store-library-metadata.yaml` sowie die Testklasse `Migration016VectorStoreLibraryMetadataTest.java` existieren im heutigen Worktree. Spätere Commits (`613f6ea4`, `346f2c36`) referenzieren weiterhin die Bibliotheks-Metadaten im Retrieval-Harness, was auf dauerhafte Verankerung des Konzepts hindeutet.

**Themen:** retrieval, indexing, migration, vector-store, datenqualität
