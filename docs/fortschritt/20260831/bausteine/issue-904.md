# Issue #904 — chore(db): Liquibase-Historie zu einer Baseline zusammenfassen (257 Changesets → logisch gruppierte Baseline)
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L
- PRs: #906 (2026-08-25)

**Laut Issue:** Maintainer-Entscheidung, die Liquibase-Historie einmalig vor Produktionsbetrieb zu einer Baseline zusammenzufassen, da jede Installation in dieser Phase neu aufgesetzt werden kann. Gefordert: eine Baseline-Datei mit wenigen logisch gruppierten Changesets, Äquivalenznachweis (leerer Schema-Diff + Seed-Datenabgleich) gegen die alte Kette, Löschung der historischen Migrationstests bei Erhalt von `AbstractMigrationTest`, und die Wiedereinführung „ein Changeset pro Änderung“ ab der Baseline.

**Geliefert:** `001-baseline.yaml` mit 8 logisch gruppierten Changesets (Extensions, Auth/Org, Spaces/Gruppen, Bibliotheken/Indexing, Chat/Query, Audit/History, Sonstiges, Seeds). **Zahl korrigiert gegenüber dem Issue-Titel:** tatsächlich 134 Changesets (nicht 257) wurden zusammengefasst — im PR selbst als Review-Nachbesserung (W1) richtiggestellt. Äquivalenznachweis per `pg_dump --schema-only` inklusive Owner/GRANT-Diff, beide leer. 52 Migrationstest-Klassen und 18 Fixture-Ketten gelöscht, `AbstractMigrationTest` bleibt; zwei neue schlanke Klassen (`MigrationBaselineTest`, `AuditPrivilegeModelTest`) sichern Kerninvarianten (Organisationsgrenzen-Regel, ADR-0015-Privilegienmodell, Zustandsinvarianten wie Unique-Constraints). Testlaufzeit `io.opaa.migration`: 2 m 42 s → ~14 s. `docs/migrations/` komplett gelöscht (Maintainer-Entscheidung während der Nachbesserung, über den ursprünglichen Issue-Umfang hinaus).

**Verifikation:** `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` existiert im Worktree.

**Themen:** datenbank, liquibase, migration, projektsetup, refactoring
