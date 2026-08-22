# Issue #265 — fix(space): persönlicher Space kann bei gleichzeitiger Erstanmeldung doppelt entstehen
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, backend, size:S
- PRs: #280 (2026-08-02, gemeinsam mit #266)

**Laut Issue:** `SpaceService.ensurePersonalSpace` prüfte nur per `existsByOwnerIdAndKind` und legte danach an, ohne Absicherung auf Datenbankebene. Zwei gleichzeitige Erstanmeldungen desselben Nutzers konnten so zwei persönliche Spaces erzeugen. Gefordert war ein partieller Unique-Index (nur für `kind = 'PERSONAL'`), Bereinigung vorhandener Duplikate, sauberes Abfangen der Constraint-Verletzung statt 500, ein Test für den gleichzeitigen Fall und ein dokumentierter Rollback.

**Geliefert:** Wie gefordert: Liquibase-Changeset `010-space-uniqueness-and-membership-index.yaml` legt den partiellen Unique-Index `uk_spaces_personal_owner` auf `spaces(owner_id) WHERE kind = 'PERSONAL'` an, ein vorgeschaltetes Changeset bereinigt Duplikate (ältester Space je Eigentümer bleibt, `RAISE NOTICE` meldet die Anzahl), `SpaceService.ensurePersonalSpace` fängt die Constraint-Verletzung des Verlierers eines gleichzeitigen Logins ab und liest den bereits angelegten Space statt eines 500-Fehlers. Dokumentiert in `docs/migrations/010-space-uniqueness-and-membership-index.md`. Der PR kombiniert #265 bewusst mit dem unabhängigen #266 (fehlender Index auf `space_memberships.space_id`), da beide dieselben Tabellen betreffen und beide im Review zu PR #254 als vorbestehend eingestuft wurden.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/010-space-uniqueness-and-membership-index.yaml` enthält `CREATE UNIQUE INDEX uk_spaces_personal_owner` mit Rollback `DROP INDEX IF EXISTS uk_spaces_personal_owner`. `backend/src/test/java/io/opaa/migration/Migration010SpaceUniquenessTest.java` existiert im aktuellen Stand.

**Themen:** spaces, backend, migrations
