# Issue #266 — perf(space): eigenständiger Index auf space_memberships.space_id fehlt
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, size:S
- PRs: #280 (2026-08-02, gemeinsam mit #265)

**Laut Issue:** Auf `space_memberships` existierte nur der zusammengesetzte Unique-Index `uk_space_memberships_user_space` mit führendem `user_id`, der Abfragen über `space_id` (z. B. das Laden aller Mitglieder eines Space) nicht bedient. Gefordert war ein eigenständiger Index auf `space_id` per Liquibase, mit dokumentiertem Rollback.

**Geliefert:** Wie gefordert, im selben PR wie #265 (beide Changesets betreffen dieselben Space-Tabellen und wurden zusammen im Review zu PR #254 als vorbestehend eingestuft). Neuer Index `idx_space_memberships_space_id` auf `space_memberships(space_id)`, dokumentiert in `docs/migrations/010-space-uniqueness-and-membership-index.md`.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/010-space-uniqueness-and-membership-index.yaml` enthält den Changeset-Block mit `indexName: idx_space_memberships_space_id` und Spalte `space_id`, inklusive Rollback-Eintrag.

**Themen:** spaces, backend, migrations, performance
