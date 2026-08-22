# Issue #111 — feat(workspace): workspace and membership entities
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #131 (2026-03-07)

**Laut Issue:** `Workspace`- und `WorkspaceMembership`-Entities mit Datenbankschema — `workspaces`-Tabelle (Typ `PERSONAL`/`SHARED`), `workspace_memberships`-Tabelle (Rolle `VIEWER`/`EDITOR`/`ADMIN`/`OWNER`, eindeutig je Nutzer/Workspace), JPA-Entities, Repositories, `WorkspaceService` mit Basis-Lookups, Kaskadenverhalten beim Löschen.

**Geliefert:** PR #131 liefert alles Geforderte plus Review-Nachbesserungen (unveränderliche Mitgliederliste, Vermeidung von N+1-Queries, konsistente `systemAdmin`-Flag-Behandlung). Der PR merged zusätzlich Issue #110 hinein (Abhängigkeit auf das `users`-Schema) und behebt dabei zwei technische Integrationsprobleme (Liquibase-`addCheckConstraint` durch SQL-`CHECK`-Constraints ersetzt, `WorkspaceService` bedingt geladen im Mock-Profil ohne JPA).

**Verifikation:** Abweichung im heutigen Code: Das Java-Paket `io.opaa.workspace` existiert nicht mehr. Es wurde später (Migration `008-rename-workspace-to-space.yaml`) zu `io.opaa.space` umbenannt und um Bibliotheken (`012-knowledge-libraries.yaml`), Asset-Grants (`013-asset-grants.yaml`) und Gruppen (`009-create-groups.yaml`) erweitert. Die hier gelieferte Grundstruktur (Entity + Membership + Rollenhierarchie) lebt konzeptionell im Space-Modell fort.

**Themen:** workspace, spaces, backend, datenbank
