# Migration 012: Wissensbibliothek als Dokumentencontainer

Begleitdokument zu
`backend/src/main/resources/db/changelog/changes/012-knowledge-libraries.yaml`
([Issue #201](https://github.com/criew/opaa/issues/201)). Führt `knowledge_libraries` als ersten
Asset-Typ ein und weist jedem bestehenden Dokument eine Bibliothek zu — der Rechteanker wird damit
zum ersten Mal eingezogen, nicht verschoben (siehe
[docs/features/spaces-and-assets.md](../features/spaces-and-assets.md#dokumente-liegen-in-bibliotheken)).

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
- Damit für niemanden lesbar außer für Systemadministratoren — **das ergibt sich aus diesem Zustand**
  unter der gewöhnlichen Rechteformel, seit [#406](https://github.com/criew/opaa/issues/406) nicht
  mehr aus einer Sonderregel in `LibraryAccessService#effectiveRole`.
- Kann nicht gelöscht werden (`KnowledgeLibraryService#deleteLibrary`).
- Kann nicht über die öffentliche API angelegt werden (`LibraryOwnerType.SYSTEM` wird in
  `KnowledgeLibraryService#createLibrary` mit `400` abgelehnt) — nur diese Migration erzeugt sie.

Eine organisationsweit lesbare Voreinstellung wäre in einer Verwaltungsumgebung nicht vertretbar; das
ist die explizite Vorgabe aus #198s Migrationsabschnitt und den Abnahmekriterien von #201.

**Voreinstellung, nicht Einbahnstraße.** Die ursprüngliche Fassung setzte das als Sonderregel um: Für
eine `SYSTEM`-Bibliothek verlangte `effectiveRole` `systemAdmin`, unabhängig von Grants und
Sichtbarkeit. Das ging über die Vorgabe hinaus und hatte eine Folge, die erst im Betrieb sichtbar
wurde: Die Indexierung legt **alles** in dieser Bibliothek ab (`FileProcessingService`), und die
Suche liest immer mit den Rechten des Fragenden, ohne Admin-Umgehung. Ein Bestand, den niemand mehr
öffnen konnte, war damit für niemanden auffindbar — auch nicht für Administratoren.

Seit #406 gilt die Formel einheitlich. Der Ausgangszustand bleibt geschlossen; das Öffnen ist eine
bewusste Entscheidung, die nur ein Systemadministrator treffen kann, weil `MANAGER` auf einer
Bibliothek ohne Eigentümer und ohne Grants sonst niemand hält.

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

Jedes Changeset ist eine eigene Postgres-Transaktion (Liquibase-Standard, `runInTransaction: true`).
Bricht ein Lauf mitten in der Datei ab, sind bereits abgeschlossene Changesets in
`DATABASECHANGELOG` vermerkt; ein erneuter Lauf setzt exakt beim ersten nicht abgeschlossenen
Changeset fort. Das ist die Ebene, auf der diese Migration tatsächlich resumierbar ist — nicht
innerhalb eines einzelnen Changesets.

**Korrektur gegenüber einer früheren Fassung dieses Dokuments:** Der Backfill
(`012-backfill-document-library-id`) war ursprünglich als Batch-Schleife in einem PL/pgSQL-`DO`-Block
umgesetzt, mit der Behauptung, jeder Batch committe für sich und ein Abbruch hinterlasse einen Teil
bereits zugewiesen. **Das ist falsch und wurde im Review widerlegt:** Ein `DO`-Block läuft vollständig
innerhalb der einen Transaktion des umschließenden Changesets; er kann darin nicht committen. Ein
Abbruch nach N Batches rollt alle N zurück, nicht nur den unfertigen Rest — empirisch nachgestellt mit
`batch_size = 1` und einem simulierten Fehler nach zwei Batches: beide `RAISE NOTICE`-Meldungen
erscheinen im Log, aber `rows assigned after the interruption = 0`.

Der Changeset ist deshalb jetzt ein einzelnes `UPDATE documents SET library_id = ..., organization_id
= ... WHERE library_id IS NULL` — eine Transaktion, alle passenden Zeilen oder keine. Das ist beim
aktuellen Datenbestand (Projekt vor 1.0, kein produktiver Bestand in relevanter Größenordnung) die
ehrlichere und einfachere Garantie, ohne die Batch-Schleife, die keine der ihr zugeschriebenen
Eigenschaften tatsächlich hatte. Sollte die Dokumentenzahl irgendwann eine echte
Batch-Wiederaufsetzbarkeit erfordern, braucht das `runInTransaction: false` auf dem Changeset und
explizite Commits je Batch — dann, aber erst dann, ist `FOR UPDATE SKIP LOCKED` das richtige Werkzeug
gegen konkurrierende Sperren statt eine Gefahrenquelle (eine Zeile, die eine andere Session sperrt,
würde sonst übersprungen; blieben nur gesperrte Zeilen übrig, endete die Schleife vorzeitig mit
`ROW_COUNT = 0`, und der nachfolgende `NOT NULL`-Changeset schlüge auf halb migriertem Bestand fehl).

`Migration012KnowledgeLibrariesTest#backfillHandlesInterruptedSchemaOnlyProgressOnResume` prüft, was
diese Fassung tatsächlich leistet: Nach den `lib-schema`-Changesets (Tabelle, Seed, nullable Spalten)
angewendet und einem Dokument von Hand so zugewiesen, wie es der Fall wäre, wenn zwischen zwei
`liquibase.update()`-Läufen jemand manuell eingegriffen hätte, läuft der Backfill-Changeset als echter
zweiter Aufruf und lässt das bereits zugewiesene Dokument unverändert, während die übrigen migriert
werden — das idempotente `WHERE library_id IS NULL`-Prädikat, nicht Wiederaufsetzbarkeit innerhalb
einer unterbrochenen Transaktion, die dieser Changeset nie erzeugen kann.

## Trockenlauf mit Mengengerüst

Wie in [Migration 008](./008-rename-workspace-to-space.md#trockenlauf) beschrieben, läuft der
Trockenlauf über eine Wegwerf-Datenbank, da dieses Projekt keinen eigenständigen
Liquibase-Gradle-Plugin-Task besitzt:

1. Kopie der Zieldatenbank in einen frischen `docker compose up postgres`-Container einspielen.
2. Die Anwendung gegen diese Kopie starten. Liquibase wendet automatisch alle ausstehenden
   Changesets an, einschließlich `012-knowledge-libraries.yaml`.
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
- Ein zweiter `liquibase.update()`-Aufruf, der die `lib-schema`-Changesets bereits angewendet
  vorfindet, lässt eine von Hand zugewiesene Zeile unverändert und migriert nur den Rest — das
  idempotente `WHERE library_id IS NULL`-Prädikat, die tatsächliche Garantie dieses Changesets
  (siehe die Korrektur im Abschnitt Resumierbarkeit oben).
- `fk_documents_library_organization` weist ein Dokument zurück, dessen `organization_id` nicht zur
  Organisation der referenzierten Bibliothek passt — der eigentliche Cross-Tenant-Fall, den diese
  Migration verhindern soll, nicht nur ein fehlendes `library_id`.

Die anwendungsseitige Zugriffslogik (Eigentümer, Gruppenmitgliedschaft, `ORGANIZATION`-Sichtbarkeit,
das Fail-closed-Verhalten der System-Bibliothek, das Zusammenspiel mit
`GroupMembershipResolver`s Cache-Invalidierung) ist separat in
`backend/src/test/java/io/opaa/library/KnowledgeLibraryServiceIntegrationTest.java` abgedeckt (echtes
Postgres-Schema über Liquibase, `ddl-auto=none`, nach demselben Muster wie
`SpaceServiceIntegrationTest`). Diese Klasse enthält seit #201/#305 auch
`insertPersonalLibraryIfAbsentWithANonExistentOwnerFailsInsteadOfSilentlyPersisting`: den Nachweis
gegen das echte Schema, dass `ON CONFLICT ... DO NOTHING` ausschließlich den benannten partiellen
Unique-Index abfängt und eine echte Fremdschlüsselverletzung (hängender Eigentümer) weiterhin normal
wirft.

Die eigentliche Race-Behandlung von `KnowledgeLibraryService#ensurePersonalLibrary` liegt seit
#201/#305 vollständig in der Datenbank
(`KnowledgeLibraryRepository.insertPersonalLibraryIfAbsent`, `ON CONFLICT ... DO NOTHING` gegen
`uk_knowledge_libraries_personal_owner`) — ein Verlierer löst keine Exception mehr aus, die
`ensurePersonalLibrary` abfangen müsste. `KnowledgeLibraryServiceTest` (reiner Mockito-Test, kein
Testcontainer, spiegelt `SpaceServiceTest`) prüft die eine Entscheidung, die die Methode noch selbst
trifft: den Insert-Versuch bei bereits vorhandener persönlicher Bibliothek zu überspringen, und dass
eine echte Repository-Verletzung unverändert durchgereicht wird.

Das Zusammenspiel von persönlichem Space und persönlicher Bibliothek bei der Nutzeranlage — beide
werden immer gemeinsam versucht, auch wenn einer der beiden Aufrufe fehlschlägt, über ein
prozesslokales Lock je Nutzer serialisiert (`UserService#provisioningLockFor`) — ist in
`backend/src/test/java/io/opaa/auth/UserServiceTest.java` und
`backend/src/test/java/io/opaa/auth/UserServicePersonalSpaceIntegrationTest.java` abgedeckt; die
12-Thread-Regression gegen echten Verbindungspool-Druck in
`backend/src/test/java/io/opaa/auth/UserServiceCreationRaceIntegrationTest.java`.
