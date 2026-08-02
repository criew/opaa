# Migration 008: Workspace zu Space

Begleitdokument zu `backend/src/main/resources/db/changelog/changes/008-rename-workspace-to-space.yaml`
([Issue #199](https://github.com/criew/opaa/issues/199)). Erklärt Reihenfolge, Trockenlauf und
Rollback für die Umbenennung von `Workspace` auf `Space`, die Einführung der Organisationsgrenze
und die Neuordnung der Space-Rollen.

## Was migriert wird

| Alt | Neu | Bemerkung |
|---|---|---|
| `workspaces` | `spaces` | Tabelle umbenannt |
| `workspaces.type` (`PERSONAL`, `SHARED`) | `spaces.kind` (`PERSONAL`, `PROJECT`, `TEAM`) | `SHARED` → `TEAM` (siehe unten), `PERSONAL` bleibt |
| — | `spaces.visibility` | neu, alle bestehenden Spaces erhalten `PRIVATE` |
| — | `spaces.organization_id` | neu, alle bestehenden Spaces erhalten die geseedete Organisation |
| `workspace_memberships` | `space_memberships` | Tabelle umbenannt |
| `workspace_memberships.workspace_id` | `space_memberships.space_id` | Spalte umbenannt |
| `workspace_memberships.role` | `space_memberships.role` | `VIEWER`→`MEMBER`, `EDITOR`→`CURATOR`, `ADMIN`→`ADMIN`, `OWNER`→`ADMIN` |
| — | `space_memberships.organization_id` | neu |
| — | `users.organization_id` | neu, alle bestehenden Nutzer erhalten die geseedete Organisation |
| — | `organizations` | neue Tabelle, genau eine Zeile geseedet (`00000000-0000-0000-0000-000000000001`) |

**`SHARED` → `TEAM`:** Historische `SHARED`-Workspaces konnten ausschließlich von
Systemadministratoren angelegt werden. Das entspricht am ehesten der neuen `TEAM`-Semantik
(ebenfalls System-Admin-only), nicht `PROJECT` (selbstständig durch jeden Nutzer anlegbar). Diese
Zuordnung ist eine bewusste Annahme, dokumentiert hier statt stillschweigend im Code.

**Kein Datenverlust bei der Rollenabbildung:** `OWNER` wird auf die Mitgliedschaftsrolle `ADMIN`
abgebildet, aber wer tatsächlich Eigentümer ist, bleibt vollständig in `spaces.owner_id` (unverändert
seit jeher ein eigenes Attribut, nicht Teil der Mitgliedschaftsrolle) erhalten.

## Reihenfolge der Changesets (Anwendung)

1. `008-create-organizations-table`
2. `008-seed-default-organization`
3. `008-add-users-organization`
4. `008-rename-workspaces-to-spaces`
5. `008-migrate-spaces-kind`
6. `008-add-spaces-visibility`
7. `008-add-spaces-organization`
8. `008-rename-workspace-memberships-to-space-memberships`
9. `008-remap-space-membership-roles`
10. `008-add-space-memberships-organization`

## Rollback-Reihenfolge

Jedes Changeset trägt einen eigenen `rollback`-Block. Da dieses Projekt Liquibase ausschließlich über
`spring-boot-starter-liquibase` einbindet (kein `org.liquibase.gradle`-Plugin mit eigenen Tasks), erfolgt
ein Rollback über die Liquibase-CLI direkt gegen die Datenbank, z. B.:

```bash
liquibase --changelog-file=backend/src/main/resources/db/changelog/db.changelog-master.yaml \
  --url=<jdbc-url> --username=<user> --password=<pass> \
  rollback-count 10
```

Liquibase rollt dabei in umgekehrter Anwendungsreihenfolge zurück. Die Reihenfolge, in der ein
vollständiger Rollback dieser Migration ausgeführt wird:

1. `008-add-space-memberships-organization`
2. `008-remap-space-membership-roles`
3. `008-rename-workspace-memberships-to-space-memberships`
4. `008-add-spaces-organization`
5. `008-add-spaces-visibility`
6. `008-migrate-spaces-kind`
7. `008-rename-workspaces-to-spaces`
8. `008-add-users-organization`
9. `008-seed-default-organization`
10. `008-create-organizations-table`

**Hinweis zum Rollback der Rollenabbildung (`008-remap-space-membership-roles`):** Die Rückabbildung
`MEMBER`→`VIEWER`, `CURATOR`→`EDITOR` ist verlustfrei, weil beides 1:1-Zuordnungen sind. Die
Rückabbildung von `ADMIN` ist es **nicht**: Nach dem Vorwärtslauf lässt sich innerhalb von
`space_memberships.role` nicht mehr unterscheiden, welche `ADMIN`-Zeilen zuvor `OWNER` waren — diese
Information steht weiterhin unverändert in `spaces.owner_id`. Der Rollback belässt alle `ADMIN`-Zeilen
als `ADMIN` und verweist auf `owner_id` als Quelle der Wahrheit für die eigentliche Eigentümerschaft;
das Rollback-SQL kommentiert dies explizit. Wer nach einem Rollback zwingend wieder eine separate
`OWNER`-Mitgliedschaftsrolle in `workspace_memberships` benötigt, muss `owner_id` heranziehen, um sie
gezielt zurückzusetzen.

## Resumierbarkeit

Jedes Changeset ist eine eigene, in PostgreSQL transaktional ausgeführte Einheit (Liquibase-Standard
für DDL unter Postgres). Bricht ein Lauf mitten in der Datei ab, sind bereits angewendete Changesets
in `DATABASECHANGELOG` vermerkt. Dieses Projekt bindet Liquibase über
`spring-boot-starter-liquibase` ein (kein eigenständiges Liquibase-Gradle-Plugin) — Migrationen laufen
automatisch beim Start der Anwendung (`./gradlew bootRun` oder der gepackte JAR). Ein erneuter Start
überspringt bereits angewendete Changesets automatisch und setzt exakt beim ersten nicht
abgeschlossenen Changeset fort. Es gibt keinen Zwischenzustand, in dem eine Tabelle zur Hälfte
umbenannt oder eine Spalte nur teilweise befüllt ist — jedes Changeset schließt entweder vollständig ab
oder wird komplett zurückgerollt, bevor der nächste Lauf beginnt.

## Trockenlauf

Da dieses Projekt keinen eigenständigen Liquibase-Gradle-Plugin-Task besitzt, läuft der Trockenlauf
über eine Wegwerf-Datenbank statt über einen reinen SQL-Preview-Befehl:

1. Kopie der Zieldatenbank in einen frischen `docker compose up postgres`-Container einspielen
   (`pg_dump`/`pg_restore` bzw. `psql < dump.sql`).
2. Die Anwendung gegen diese Kopie starten (`./gradlew bootRun` mit `spring.datasource.url` auf den
   Wegwerf-Container zeigend). Liquibase wendet beim Start automatisch alle ausstehenden Changesets an,
   einschließlich `008-rename-workspace-to-space.yaml`.
3. Das Mengengerüst (siehe unten) vor und nach dem Start vergleichen.
4. Den Wegwerf-Container verwerfen — die echte Umgebung wurde zu keinem Zeitpunkt berührt.

Dasselbe Verfahren deckt `OpaaApplicationTests` (`@SpringBootTest` mit Testcontainers) automatisiert für
jeden CI-Lauf ab: Der Kontextstart schlägt fehl, wenn irgendein Changeset in diesem Changelog nicht
sauber gegen eine frische PostgreSQL/pgvector-Instanz anwendbar ist.

**Mengengerüst vor/nach:** Vor der Migration:

```sql
SELECT count(*) FROM workspaces;
SELECT count(*) FROM workspace_memberships;
SELECT type, count(*) FROM workspaces GROUP BY type;
SELECT role, count(*) FROM workspace_memberships GROUP BY role;
```

Nach der Migration müssen die Zeilenzahlen unverändert sein, und die Verteilung muss sich exakt gemäß
der obigen Abbildungstabelle verschoben haben:

```sql
SELECT count(*) FROM spaces;
SELECT count(*) FROM space_memberships;
SELECT kind, count(*) FROM spaces GROUP BY kind;
SELECT role, count(*) FROM space_memberships GROUP BY role;
```

`count(*)` vor und nach muss für beide Tabellen exakt übereinstimmen; das ist der Beleg, dass die
Migration keine Zeile verliert.

**Einschränkung dieses PRs:** Ein Abgleich gegen eine echte Kopie eines produktiven Datenbestands war
im Rahmen dieser Änderung nicht möglich, weil das Projekt vor 1.0 steht und kein produktiver
Datenbestand existiert. Der Trockenlauf wurde stattdessen gegen die lokale Testcontainer-Datenbank mit
über die Integrationstests erzeugten Beispieldaten durchgeführt (siehe `SpaceServiceIntegrationTest`,
`SpaceRepositoryTest`). Sobald ein produktiver Datenbestand existiert, ist dieses Verfahren unverändert
darauf anzuwenden.
