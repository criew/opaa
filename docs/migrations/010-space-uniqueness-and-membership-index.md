# Migration 010: Eindeutigkeit persönlicher Spaces und Index auf space_memberships.space_id

Begleitdokument zu
`backend/src/main/resources/db/changelog/changes/010-space-uniqueness-and-membership-index.yaml`
([Issue #265](https://github.com/criew/opaa/issues/265),
[Issue #266](https://github.com/criew/opaa/issues/266)). Zwei unabhängige, kleine Changesets an
den Space-Tabellen, die zusammen in einem PR landen, weil beide vorbestehende Lücken aus dem
Review zu PR #254 sind.

## Was migriert wird

| Changeset | Zweck |
|---|---|
| `010-cleanup-duplicate-personal-spaces` | Entfernt doppelte persönliche Spaces vor Anlage des Index unten |
| `010-create-personal-space-unique-index` | Partieller Unique-Index `uk_spaces_personal_owner` auf `spaces (owner_id) WHERE kind = 'PERSONAL'` |
| `010-create-space-memberships-space-id-index` | Eigenständiger Index `idx_space_memberships_space_id` auf `space_memberships (space_id)` |

### Warum ein partieller Index statt einer zusammengesetzten Unique-Constraint (#265)

Eine zusammengesetzte Unique-Constraint auf `(owner_id, kind)` würde auch `PROJECT`- und
`TEAM`-Spaces auf einen pro Eigentümer begrenzen — das ist falsch, Nutzer dürfen beliebig viele
davon besitzen. Nur `kind = 'PERSONAL'`-Zeilen dürfen der Eindeutigkeit unterliegen, deshalb ein
partieller Index statt einer Tabellen-weiten Constraint.

### Bereinigung vorhandener Duplikate

Der partielle Index lässt sich nicht anlegen, solange bereits doppelte persönliche Spaces
existieren (`CREATE UNIQUE INDEX` schlägt in diesem Fall fehl). `010-cleanup-duplicate-personal-spaces`
löscht deshalb vor der Indexanlage für jeden Eigentümer alle bis auf den ältesten persönlichen
Space (`created_at`, `id` als stabiler Tiebreaker). Mitgliedschaften der gelöschten Duplikate
werden automatisch über `fk_space_memberships_space` (`ON DELETE CASCADE`) mit entfernt — es
bleiben keine verwaisten Mitgliedschaftszeilen zurück. Ein `RAISE NOTICE` meldet die Anzahl der
entfernten Zeilen im Migrationslog.

**Warum Löschen statt Zusammenführen eine vertretbare Entscheidung ist:** Zum jetzigen Zeitpunkt
in der Schema-Historie referenziert noch kein Dokument- oder Asset-Modell Spaces — das führen erst
#201 (Wissensbibliothek) und #202 (Asset-Rechte) ein, und genau vor diesen beiden muss diese
Migration liegen (siehe #265). Es gibt also nichts, was an einem doppelten persönlichen Space
hängen könnte und beim Löschen verloren ginge. Ein Zusammenführen der Mitgliedschaften wäre für
persönliche Spaces ohnehin trivial (immer nur der Eigentümer als `ADMIN`), fügt aber unnötige
Komplexität hinzu, wenn Löschen bereits verlustfrei ist.

### Race in `SpaceService.ensurePersonalSpace`

`ensurePersonalSpace` prüfte bisher mit `existsByOwnerIdAndKind` und legte danach an — ohne
Absicherung auf Datenbankebene. Der Index oben schließt die Lücke, verlagert das Problem aber auf
den Anwendungscode: Der Verlierer eines gleichzeitigen ersten Logins erhält jetzt eine
`DataIntegrityViolationException` beim Insert. `ensurePersonalSpace` fängt diese ab und liest den
bereits vom Gewinner angelegten Space, statt einen 500 zu werfen. Der Insert-Versuch läuft dazu in
einer eigenen `REQUIRES_NEW`-Transaktion: Auf Postgres bricht eine fehlgeschlagene Anweisung die
gesamte umschließende Transaktion ab, sodass ein Auffangen der Verletzung innerhalb derselben
Transaktion, die den Insert ausgeführt hat, jede nachfolgende Anweisung in dieser Transaktion
ebenfalls scheitern ließe. Details siehe Klassenkommentar auf
`SpaceService.ensurePersonalSpace`.

### Fehlender Index auf `space_memberships.space_id` (#266)

Der vorhandene Unique-Index `uk_space_memberships_user_space` führt mit `(user_id, space_id)` und
bedient nur Abfragen, die über `user_id` einsteigen. Abfragen über `space_id` — insbesondere das
Laden der Mitglieder eines Space (`SpaceMembershipRepository#findBySpaceId`) — profitieren nicht
vom führenden Spaltenteil. `010-create-space-memberships-space-id-index` fügt einen
eigenständigen Index auf `space_id` hinzu.

## Reihenfolge der Changesets (Anwendung)

1. `010-cleanup-duplicate-personal-spaces`
2. `010-create-personal-space-unique-index`
3. `010-create-space-memberships-space-id-index`

Die Reihenfolge zwischen den ersten beiden ist zwingend — der Index kann nicht vor der Bereinigung
angelegt werden. Der dritte Changeset ist von den ersten beiden unabhängig.

## Rollback-Reihenfolge

```bash
liquibase --changelog-file=backend/src/main/resources/db/changelog/db.changelog-master.yaml \
  --url=<jdbc-url> --username=<user> --password=<pass> \
  rollback-count 3
```

Rollback in umgekehrter Anwendungsreihenfolge:

1. `010-create-space-memberships-space-id-index` — löscht `idx_space_memberships_space_id`, verlustfrei
2. `010-create-personal-space-unique-index` — löscht `uk_spaces_personal_owner`, verlustfrei
3. `010-cleanup-duplicate-personal-spaces` — **irreversibel**

**Der Rollback von `010-cleanup-duplicate-personal-spaces` ist ein bewusster No-op** (`SELECT 1;`),
kein tatsächliches Wiederherstellen der gelöschten Zeilen. Gelöschte Duplikate und ihre
kaskadierten Mitgliedschaften lassen sich aus dieser Migration heraus nicht rekonstruieren. Wer sie
zwingend braucht, muss aus einem Backup vor dieser Migration wiederherstellen. Ein echter Rollback
ohne Datenverlust ist bei einer Löschoperation grundsätzlich nicht möglich — die Alternative wäre,
den Rollback fehlschlagen zu lassen, was den Rollback der beiden nachfolgenden, verlustfreien
Changesets unnötig blockieren würde.

## Test

`Migration010SpaceUniquenessTest`
(`backend/src/test/java/io/opaa/migration/Migration010SpaceUniquenessTest.java`) folgt dem in
`docs/migrations/008-rename-workspace-to-space.md` beschriebenen Muster: Es wendet die echten,
versionierten Changesets 001–008 über die Fixture `test-master-through-008.yaml` an, sät
Alt-Zeilen mit zwei persönlichen Spaces für denselben Eigentümer, wendet Changelog 010 in Isolation
an und prüft, dass der ältere Space erhalten bleibt, der jüngere entfernt wird, seine Mitgliedschaft
mit entfernt wurde, der Index tatsächlich existiert und ein zweiter Insert-Versuch für denselben
Eigentümer daran scheitert. Eine zweite Testmethode prüft den Index auf `space_memberships.space_id`.

**Unterschied zu `Migration008RenameWorkspaceToSpaceTest`:** Diese Testklasse hat mehr als eine
Testmethode. Der statische Testcontainer wird zwischen Testmethoden nicht zurückgesetzt, und
Liquibase vermerkt jeden angewendeten Changeset in `DATABASECHANGELOG` — ein zweiter Testlauf hätte
Changelog 010 sonst stillschweigend als "bereits angewendet" übersprungen, statt gegen die
Alt-Zeilen der jeweiligen Testmethode zu laufen. `Migration010SpaceUniquenessTest` löst das, indem
es das `public`-Schema nach jeder Testmethode droppt und neu anlegt (`resetSchema()`), sodass jede
Methode bei einer leeren Datenbank inklusive leerem `DATABASECHANGELOG` startet. Dieses Muster ist
für künftige Migrationstests mit mehreren Testmethoden zu übernehmen (siehe #237, #238).

Der race-sichere Anwendungscode-Pfad in `SpaceService.ensurePersonalSpace` ist separat in
`SpaceServiceTest` abgedeckt (reiner Mockito-Test, kein Testcontainer): Er simuliert den
gleichzeitigen ersten Login, indem `existsByOwnerIdAndKind` und `saveAndFlush` so gestubbt werden,
wie sie sich für den Verlierer des Rennens verhalten würden — deterministisch statt über echte
Threads und Timing.
