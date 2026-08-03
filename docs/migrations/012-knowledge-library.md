# Migration 012: Wissensbibliothek als Dokumentencontainer

Begleitdokument zu
`backend/src/main/resources/db/changelog/changes/012-knowledge-libraries.yaml`
([Issue #201](https://github.com/criew/opaa/issues/201)). Führt `knowledge_libraries` als ersten
Asset-Typ ein und weist jedem bestehenden Dokument eine Bibliothek zu — der Rechteanker wird damit
zum ersten Mal eingezogen, nicht verschoben (siehe
[docs/features/spaces-and-assets.md](../features/spaces-and-assets.md#dokumente-liegen-in-bibliotheken)
und [ADR-0008](../decisions/0008-space-and-asset-model.md)).

## Was migriert wird

| Changeset | Zweck |
|---|---|
| `012-create-knowledge-libraries` | Neue Tabelle `knowledge_libraries` mit Eigentümer-, Sichtbarkeits- und Auffindbarkeitsfeldern |
| `012-seed-system-library` | Seedet die eine System-Bibliothek (`00000000-0000-0000-0000-000000000002`), Migrationsziel für Altbestand |
| `012-add-library-id-to-documents` | `documents.library_id` und `documents.organization_id`, zunächst nullable |
| `012-backfill-document-library-id` | Weist jedem bestehenden Dokument die System-Bibliothek zu — batched, resumierbar |
| `012-enforce-documents-library-id` | `NOT NULL` auf beiden Spalten + zusammengesetzter Fremdschlüssel `fk_documents_library_organization` |

**Günstige Ausgangslage:** Dokumente tragen heute keine Container-Zuordnung, und die Suche filtert
nicht. Es gibt also nichts zu verlieren — nur eine erstmalige Zuweisung.

### Warum `owner_user_id` und `owner_group_id` statt einer polymorphen `owner_id`

Eine Bibliothek gehört einem Nutzer, einer Gruppe, oder — nur für die System-Bibliothek — niemandem.
Eine einzelne `owner_id`-Spalte könnte keinen echten Fremdschlüssel tragen, weil sie je nach
`owner_type` auf `users` oder `groups` zeigen müsste. Stattdessen trägt `knowledge_libraries` zwei
separate, jeweils nullable Spalten mit je einem echten Fremdschlüssel:

- `owner_user_id` → `fk_knowledge_libraries_owner_user` gegen `users(id)` (wie `fk_spaces_owner` bei
  Spaces — dort ebenfalls ohne Organisationsabgleich auf Datenbankebene, weil `users` keine
  `(id, organization_id)`-Eindeutigkeit trägt, gegen die eine zusammengesetzte Fremdschlüssel-Bedingung
  aufsetzen könnte; der Organisationsabgleich für einen Nutzer-Eigentümer bleibt Anwendungscode in
  `KnowledgeLibraryService`, exakt wie bei `SpaceService`).
- `owner_group_id` → `fk_knowledge_libraries_owner_group_organization`, zusammengesetzt gegen
  `groups(id, organization_id)` — dasselbe Muster wie `fk_group_memberships_group_organization`
  (Migration 009). Eine Bibliothek kann damit nie einer Gruppe aus einer fremden Organisation gehören,
  durchgesetzt auf Datenbankebene.

Der Check-Constraint `chk_knowledge_libraries_owner` erzwingt, dass genau die zu `owner_type`
passende Spalte gesetzt ist: `USER` nur `owner_user_id`, `GROUP` nur `owner_group_id`, `SYSTEM`
keine von beiden.

### Die System-Bibliothek — fail-closed ohne Einzelfallentscheidung

Bestehende Dokumente haben heute keinerlei Zuständigen. Am Migrationstag lässt sich für keines davon
seriös entscheiden, wem es gehören soll — das wäre eine Einzelfallentscheidung, die diese Migration
bewusst nicht trifft. Stattdessen bekommen **alle** bestehenden Dokumente dieselbe System-Bibliothek
zugewiesen:

- `owner_type = SYSTEM`, kein individueller Eigentümer.
- `visibility = PRIVATE`, `listed = false`.
- Lesbar **ausschließlich für Systemadministratoren** — durchgesetzt in
  `KnowledgeLibraryService#canRead`/`#canManage`, die für eine `SYSTEM`-Bibliothek unabhängig von
  jeder anderen Prüfung `systemAdmin` verlangen.
- Kann nicht gelöscht werden (`KnowledgeLibraryService#deleteLibrary`).
- Kann nicht über die öffentliche API angelegt werden (`LibraryOwnerType.SYSTEM` wird in
  `KnowledgeLibraryService#createLibrary` mit `400` abgelehnt) — nur diese Migration erzeugt sie.

Eine organisationsweit lesbare Voreinstellung wäre in einer Verwaltungsumgebung nicht vertretbar; das
ist die explizite Vorgabe aus #198s Migrationsabschnitt und den Abnahmekriterien von #201.

## Reihenfolge der Changesets (Anwendung)

1. `012-create-knowledge-libraries`
2. `012-seed-system-library`
3. `012-add-library-id-to-documents`
4. `012-backfill-document-library-id`
5. `012-enforce-documents-library-id`

Die Reihenfolge ist zwingend: Die System-Bibliothek muss existieren, bevor der Backfill sie
referenzieren kann; die Spalten müssen nullable angelegt sein, bevor sie befüllt werden können;
`NOT NULL` und der Fremdschlüssel dürfen erst gesetzt werden, wenn jede Zeile einen Wert trägt.

**Kontext-Labels:** Die fünf Changesets tragen zusätzlich die Labels `lib-schema` (1–3),
`lib-backfill` (4) und `lib-enforce` (5). Ein normaler Lauf ohne Kontextfilter — wie ihn
`spring-boot-starter-liquibase` beim Anwendungsstart automatisch ausführt — wendet alle fünf
unverändert in Reihenfolge an; die Labels dienen ausschließlich
`Migration012KnowledgeLibrariesTest`, um einen unterbrochenen und wiederaufgenommenen Lauf echt zu
simulieren, statt ihn nur zu behaupten (siehe unten).

## Resumierbarkeit

Jedes Changeset ist eine eigene Postgres-Transaktion (Liquibase-Standard). Bricht ein Lauf mitten in
der Datei ab, sind bereits abgeschlossene Changesets in `DATABASECHANGELOG` vermerkt; ein erneuter
Lauf setzt exakt beim ersten nicht abgeschlossenen Changeset fort.

Der Backfill selbst (`012-backfill-document-library-id`) geht darüber hinaus: Er ist **innerhalb**
seines eigenen Changesets resumierbar, nicht nur zwischen Changesets. Statt eines einzelnen
`UPDATE`-Statements über die gesamte Tabelle verarbeitet ein `DO`-Block Batches von 5 000 Zeilen in
einer Schleife (`WHERE library_id IS NULL ... LIMIT batch_size FOR UPDATE SKIP LOCKED`), bis keine
Zeile mehr fehlt. Das hat zwei Gründe:

1. **Skalierung.** Ein einzelnes `UPDATE` über eine sehr große Tabelle hielte eine lange laufende
   Transaktion offen. Batches vermeiden das, ohne die Garantie zu schwächen — jeder Batch committet
   für sich (implizit, da `DO`-Blöcke innerhalb der äußeren Changeset-Transaktion laufen und diese am
   Ende des Changesets committet; die Batches selbst sind idempotent, siehe Punkt 2).
2. **Wiederaufsetzbarkeit.** Jeder Batch filtert erneut auf `library_id IS NULL` — bereits migrierte
   Zeilen werden nie wieder angefasst. Ein Abbruch mitten im Lauf (Verbindungsabbruch, Neustart der
   Anwendung während der Migration) hinterlässt einen Teil der Zeilen bereits zugewiesen und den Rest
   unverändert `NULL`; ein erneuter Lauf des Changesets — oder des gesamten Changelogs beim nächsten
   Anwendungsstart — führt exakt dort fort, wo der vorherige aufgehört hat, ohne Duplikate oder
   übersprungene Zeilen.

Jeder Batch meldet sein Ergebnis über `RAISE NOTICE` im Migrationslog — das Mengengerüst des
Trockenlaufs (siehe unten) lässt sich damit auch während eines echten Laufs live beobachten.

`Migration012KnowledgeLibrariesTest#backfillIsResumableAcrossPartialProgress` prüft die
Wiederaufsetzbarkeit nicht nur behauptend, sondern tatsächlich: Es wendet zunächst nur die
`lib-schema`-Changesets an, weist ein Dokument von Hand so zu, wie ein teilweise abgeschlossener
Backfill es hinterlassen hätte, und wendet dann die verbleibenden Changesets als echten zweiten
`liquibase.update()`-Aufruf an — nicht als Simulation innerhalb eines einzigen Laufs.

## Trockenlauf mit Mengengerüst

Wie in [Migration 008](./008-rename-workspace-to-space.md#trockenlauf) beschrieben, läuft der
Trockenlauf über eine Wegwerf-Datenbank, da dieses Projekt keinen eigenständigen
Liquibase-Gradle-Plugin-Task besitzt:

1. Kopie der Zieldatenbank in einen frischen `docker compose up postgres`-Container einspielen.
2. Die Anwendung gegen diese Kopie starten. Liquibase wendet automatisch alle ausstehenden
   Changesets an, einschließlich `012-knowledge-libraries.yaml`. Das `RAISE NOTICE` des Backfills
   erscheint im Anwendungslog mit der Anzahl migrierter Dokumente je Batch.
3. Das Mengengerüst vor und nach dem Lauf vergleichen:

   Vor der Migration:

   ```sql
   SELECT count(*) FROM documents;
   SELECT count(*) FILTER (WHERE library_id IS NULL) FROM documents;
   ```

   Nach der Migration:

   ```sql
   SELECT count(*) FROM documents;
   SELECT count(*) FILTER (WHERE library_id = '00000000-0000-0000-0000-000000000002') FROM documents;
   SELECT count(*) FILTER (WHERE library_id IS NULL) FROM documents;  -- muss 0 sein
   ```

   `count(*)` vor und nach muss übereinstimmen (keine verlorene Zeile); die zweite Abfrage nach der
   Migration muss der ersten Gesamtzahl entsprechen (jedes Dokument der System-Bibliothek
   zugewiesen); die dritte muss `0` sein.
4. Den Wegwerf-Container verwerfen.

`Migration012KnowledgeLibrariesTest#backfillAssignsEveryPreExistingDocumentToTheSystemLibraryWithoutLosingRows`
deckt genau dieses Mengengerüst automatisiert ab, gegen eine mit drei Alt-Dokumenten befüllte
Testcontainer-Datenbank — nicht gegen eine leere.

**Einschränkung dieses PRs:** Wie bei Migration 008 war ein Abgleich gegen eine echte Kopie eines
produktiven Datenbestands im Rahmen dieser Änderung nicht möglich (Projekt vor 1.0, kein produktiver
Bestand). Der Trockenlauf wurde gegen eine lokale Testcontainer-Datenbank mit synthetischen,
realistischen Alt-Zeilen durchgeführt. Sobald ein produktiver Datenbestand existiert, ist das oben
beschriebene Verfahren unverändert darauf anzuwenden.

## Rollback-Reihenfolge

```bash
liquibase --changelog-file=backend/src/main/resources/db/changelog/db.changelog-master.yaml \
  --url=<jdbc-url> --username=<user> --password=<pass> \
  rollback-count 5
```

Rollback in umgekehrter Anwendungsreihenfolge:

1. `012-enforce-documents-library-id` — löscht `NOT NULL` und `fk_documents_library_organization`,
   verlustfrei.
2. `012-backfill-document-library-id` — **irreversibler No-op** (`SELECT 1;`). Ein rückgängig
   gemachter Backfill könnte nicht zuverlässig zwischen Zeilen unterscheiden, die dieser Changeset
   ursprünglich zugewiesen hat, und Zeilen, die zwischenzeitlich (nach einem Teil-Rollback) neu mit
   derselben System-Bibliothek angelegt wurden — ein Zurücksetzen auf `NULL` würde beide gleich
   behandeln. Dieselbe Begründung wie bei `010-cleanup-duplicate-personal-spaces`.
3. `012-add-library-id-to-documents` — löscht `library_id` und `organization_id`, verlustfrei (die
   Spalten waren nullable, ihr Inhalt ist mit Punkt 2 ohnehin nicht mehr sauber trennbar).
4. `012-seed-system-library` — löscht die eine System-Bibliothek-Zeile, verlustfrei.
5. `012-create-knowledge-libraries` — löscht die gesamte Tabelle, verlustfrei (keine andere Tabelle
   referenziert `knowledge_libraries` mehr, sobald Schritt 1 zurückgerollt ist).

## Test

`Migration012KnowledgeLibrariesTest`
(`backend/src/test/java/io/opaa/migration/Migration012KnowledgeLibrariesTest.java`) folgt dem in
`backend/src/test/java/io/opaa/migration/package-info.java` verbindlichen Muster: `test-master-through-011.yaml`
für den Vorzustand, `connection.setAutoCommit(true)` nach jedem `liquibase.update(...)`, Schema-Reset
zwischen Testmethoden. Er deckt:

- Die System-Bibliothek wird mit `owner_type = SYSTEM`, ohne individuellen Eigentümer, `PRIVATE` und
  ungelistet geseedet.
- `chk_knowledge_libraries_owner` weist jede der fünf möglichen Fehlkombinationen aus `owner_type`
  und den beiden Eigentümerspalten zurück.
- `fk_knowledge_libraries_owner_user` und `fk_knowledge_libraries_owner_group_organization` weisen
  einen nicht existierenden Eigentümer zurück, statt ihn stillschweigend zu übernehmen.
- `uk_knowledge_libraries_personal_owner` lässt genau eine persönliche Bibliothek je Eigentümer zu,
  beschränkt aber nicht die Anzahl nicht-persönlicher Bibliotheken.
- Der Backfill weist jedes bestehende Dokument der System-Bibliothek und ihrer Organisation zu, ohne
  eine Zeile zu verlieren.
- Der Backfill ist über einen echten zweiten `liquibase.update()`-Aufruf hinweg wiederaufsetzbar
  (siehe oben).
- `fk_documents_library_organization` weist ein Dokument zurück, dessen `organization_id` nicht zur
  Organisation der referenzierten Bibliothek passt — der eigentliche Cross-Tenant-Fall, den diese
  Migration verhindern soll, nicht nur ein fehlendes `library_id`.

Die anwendungsseitige Zugriffslogik (Eigentümer, Gruppenmitgliedschaft, `ORGANIZATION`-Sichtbarkeit,
das Fail-closed-Verhalten der System-Bibliothek, das Zusammenspiel mit
`GroupMembershipResolver`s Cache-Invalidierung) ist separat in
`backend/src/test/java/io/opaa/library/KnowledgeLibraryServiceIntegrationTest.java` abgedeckt (echtes
Postgres-Schema über Liquibase, `ddl-auto=none`, nach demselben Muster wie
`SpaceServiceIntegrationTest`). Die Race-Behandlung von
`KnowledgeLibraryService#ensurePersonalLibrary` ist in
`backend/src/test/java/io/opaa/library/KnowledgeLibraryServiceTest.java` abgedeckt (reiner
Mockito-Test, kein Testcontainer, spiegelt `SpaceServiceTest`). Das Zusammenspiel von persönlichem
Space und persönlicher Bibliothek bei der Nutzeranlage — beide werden immer gemeinsam versucht, auch
wenn einer der beiden Aufrufe fehlschlägt — ist in
`backend/src/test/java/io/opaa/auth/UserServiceTest.java` und
`backend/src/test/java/io/opaa/auth/UserServicePersonalSpaceIntegrationTest.java` abgedeckt.
