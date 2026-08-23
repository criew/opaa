# Issue #86 — chore: Liquibase Changesets konsolidieren (Pre-Production Cleanup)
- Geschlossen: 2026-03-01 (completed)
- Labels: mvp, backend, size:S
- PRs: #87 (2026-03-01)

**Laut Issue:** Da die Software noch nicht produktiv war, sollten mehrere Liquibase-Changesets, die dieselben Tabellen betreffen, zusammengeführt werden: `checksum`-Spalte direkt in die `documents`-CREATE-TABLE, `documents_total`/`documents_skipped` direkt in die `indexing_jobs`-CREATE-TABLE. Ziel: 5 statt 3 Dateien, Master-Changelog bereinigt, lokale DB neu aufsetzen, Build/Tests grün.

**Geliefert:** PR #87 setzt die Konsolidierung exakt wie beschrieben um — `checksum` in `002-create-documents-table.yaml`, `documents_total`/`documents_skipped` in `003-create-indexing-jobs-table.yaml`, die beiden ALTER-TABLE-Dateien gelöscht, Master-Changelog aktualisiert. Keine Abweichung.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/002-create-documents-table.yaml` enthält die `checksum`-Spalte, `003-create-indexing-jobs-table.yaml` enthält `documents_total` und `documents_skipped` im heutigen Worktree. Der Changelog ist seither auf 20 Dateien angewachsen (bis `020-add-upload-metadata-to-documents.yaml`) — die Konsolidierung war also, wie im Issue selbst vermerkt, nur vor Produktivsetzung sinnvoll und wurde seither nicht wiederholt.

**Themen:** backend, liquibase, datenbank, cleanup
