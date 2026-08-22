# Issue #400 — fix(db): Übergeordnete Gruppe an die Organisation binden
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, security
- PRs: #675 (2026-08-20)

**Laut Issue:** Aus #289/#356 herausgelöster Einzelfall: `groups.parent_group_id` verwies nur auf `groups(id)`, nicht auf `(id, organization_id)` — eine Gruppe konnte eine übergeordnete Gruppe aus einer anderen Organisation haben. Anders als die übrigen #289-Fälle sofort lösbar, weil `uk_groups_id_organization` bereits existiert. Verlangt: Fremdschlüssel zusammensetzen, Reproduktionsnachweis rot/grün, struktureller Prüflauf aus #390 wird an dieser Tabelle grün.

**Geliefert:** Migration 046 (zwei ChangeSets): räumt zunächst bestehende organisationsübergreifende Elternverweise auf (`UPDATE ... SET parent_group_id = NULL`), dann Umstellung auf zusammengesetzten Fremdschlüssel mit `ON DELETE SET NULL` beschränkt auf `parent_group_id` (rohes SQL statt `addForeignKeyConstraint`, weil Liquibase das Spaltenlisten-`SET NULL` nicht abbildet). `GroupService` erzwingt die Bindung weiterhin nicht auf Anwendungsebene — die Migration ist die einzige Absicherung, wie im Issue vorgesehen. Ein Abnahmekriterium im Issue war zum Zeitpunkt dieses PRs nicht erfüllbar und das im PR auch offen benannt: „Der strukturelle Prüflauf aus #390 wird an dieser Tabelle grün" — #390 existierte zu diesem Zeitpunkt noch nicht im Repository. Reproduktionsnachweis rot/grün erbracht, Review-Nachbesserung ergänzte eine Rollback-Prüfung.

**Verifikation:** Migration `046-bind-groups-parent-group-to-organization.yaml` existiert im Worktree.

**Themen:** organisationsgrenze, security, migration, gruppen, backend
