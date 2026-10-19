# Liquibase: Changelog je Modul

Herleitung und Prüfungen zu den Changeset-Regeln in [backend/AGENTS.md](../AGENTS.md#liquibase).
Beispiel für ein Changeset:
[`assistant/2026-10-02-chat-import-key.yaml`](../src/main/resources/db/changelog/assistant/2026-10-02-chat-import-key.yaml),
sein Delta-Test:
[`ChatImportKeyMigrationTest`](../src/test/java/io/opaa/migration/ChatImportKeyMigrationTest.java).

## Baselines

Die Liquibase-Historie wurde dreimal zu einer Baseline zusammengefasst: 08/2026 (#904/PR #906), 09/2026 (#1492/PR #1504) und Ende 09/2026 (#2001/PR #2007). Jedes Mal ein bewusster Einmalvorgang vor Produktionsbetrieb: jede laufende Installation muss danach neu aufgesetzt werden, weil `DATABASECHANGELOG` nicht mehr passt (Handbuch, `docker-compose.md`, „Neu aufsetzen statt aktualisieren"). Dasselbe gilt für die Aufteilung in Verzeichnisse je Modul (#2003): Liquibase erkennt ein Changeset an `id`, `author` und Dateipfad, und der Pfad hat sich geändert.

**Eine weitere Baseline gibt es nur an einer ausgewiesenen [Versionsgrenze ohne Rolling Update](#versionsgrenzen-ohne-rolling-update).** Eine Zusammenfassung schreibt die Historie um; die Vorgängerversion läuft danach nicht neben der neuen.

## Ablage

**Ablage.** Unter `backend/src/main/resources/db/changelog/` liegt je logischem Modul (siehe [backend/AGENTS.md](../AGENTS.md#logische-module-und-schichtung)) ein Verzeichnis: `foundation/`, `identity/`, `rights/`, `knowledge/`, `connectors/`, `connections/`, `workspace/`, `assistant/`, `external/`. `format`, `library`, `retrieval` und `app` besitzen keine Tabelle und damit kein Verzeichnis. `db.changelog-master.yaml` bindet die Verzeichnisse per `includeAll` (nur `.yaml`) in der Reihenfolge von `ModularArchitecture.Module` ein; innerhalb eines Verzeichnisses laufen die Dateien aufsteigend nach Namen (Liquibase vergleicht die Pfade als Zeichenketten, `-` sortiert dabei vor `.`). Ein neues Modulverzeichnis braucht einen Eintrag im Master an seiner Stelle in der Modulreihenfolge. Die Baseline ist je Modul die Datei `2026-09-27-baseline.yaml` mit dem Changeset `<modul>-2026-09-27-baseline`. In `knowledge/` stehen dort zusätzlich die beiden `vector_store`-Ausdrucksindexe (`knowledge-2026-09-27-baseline--vector-store-library-id`, `…--vector-store-document-id`): bewusst eigenständige, precondition-geschützte Changesets, die nicht in ein Modul-Changeset gefaltet werden dürfen, sonst überspringt die Precondition beim ersten Start das ganze Modul. Rollback-Blöcke hat die Baseline bewusst keine. Die Tabelle `vector_store` selbst, ihr Vektorindex und dieselben beiden Ausdrucksindexe entstehen seit `knowledge/2026-10-19-vector-store.yaml` in der Migration ([unten](#vector_store-in-der-migration)).

## Neues Changeset: ein Changeset pro Datenbankänderung

- **Wohin:** in das Verzeichnis des Moduls, dem die Tabelle gehört. Eine Tabelle gehört dem Modul, dessen Paket ihre Entity trägt; die Zuordnung steht in `ChangelogModuleBoundaryTest.TABLE_MODULES`. Eine neue Tabelle wird dort eingetragen.
- **Name:** `<modul>/JJJJ-MM-TT-<thema>.yaml`, Datum des Tages, an dem die Datei entsteht, Thema in Kleinbuchstaben mit einfachen Bindestrichen, etwa `rights/2026-10-01-library-share-cap.yaml`. Die Changeset-ID ist `<modul>-<dateistamm>`, also `rights-2026-10-01-library-share-cap`; weitere Changesets derselben Datei hängen `--<suffix>` an (`…-library-share-cap--backfill-index`). Ein Dateiname enthält nie `--`, deshalb kann keine ID zweier Dateien gleich ausfallen. Autor ist `opaa`, der Master bleibt unverändert. `ChangelogLayoutTest` erzwingt Name und ID.
- **Modulgrenze:** Ein Changeset legt nur Tabellen seines Moduls an, ändert und entfernt nur diese. Dazu zählen Spalten und ihre Rechte, Constraints, Indexe, Eigentümer, Rechte, Row-Level-Security und Policies. Fremdschlüssel, Trigger und die Tabellen, die eine Funktion nennt, reichen nur in das eigene Modul oder in eines, von dem es nach `ALLOWED_MODULE_EDGES` abhängen darf; schreiben (`INSERT`, `UPDATE`, `DELETE`) darf eine Funktion nur in Tabellen des eigenen Moduls. Ein Changeset entfernt oder ersetzt nur Trigger und Funktionen, die sein eigenes Modul angelegt hat.
- **Trigger auf fremden Tabellen:** Ein Modul darf einen Trigger auf eine Tabelle eines tieferen Moduls legen, von dem es abhängen darf, etwa rights `trg_organizations_seed_capability_grants` auf `organizations` (foundation). Trigger und Funktion gehören dem höheren Modul und stehen in dessen Changeset; die Funktion schreibt nur in Tabellen des eigenen Moduls (geprüft über die Schreibanweisungen im Funktionsrumpf, dynamisches SQL sieht die Prüfung nicht). Die Abhängigkeit zeigt damit nach unten wie im Code: Das tiefere Modul kennt den Trigger nicht, das höhere reagiert auf dessen Ereignis.
- **Reihenfolge: Ein Changeset muss in beiden Reihenfolgen korrekt sein**, bei einer Neuinstallation und angehängt an eine bestehende Installation. Eine Neuinstallation wendet die Dateien in Master-Reihenfolge an, also jedes tiefere Modul vor dem höheren und innerhalb eines Moduls nach Namen. Eine bestehende Installation wendet eine neue Datei dagegen nach allem an, was sie schon hat: nach den Dateien höherer Module und nach älter oder neuer datierten Dateien desselben Moduls, etwa wenn ein langlebiger Branch nach einer neueren Datei gemergt wird. Ein Changeset entfernt oder ändert deshalb nichts, worauf eine andere Datei verweist, und setzt nichts voraus, was erst eine später sortierende Datei anlegt. Hängt eine Datei von einer anderen desselben Moduls ab, muss sie hinter ihr sortieren; im Zweifel das Datum beim Merge auf den Merge-Tag setzen.
- **Tests:** Jedes Changeset prüfen ohne weiteres Zutun `ChangelogLayoutTest` (Verzeichnisse, Modulreihenfolge, Datei- und ID-Schema), `ChangelogModuleBoundaryTest` (Modulgrenze auf der Datenbank nach dem Einspielen je Modulverzeichnis, dazu der Abgleich der Zuordnung mit den Paketen der Entities) und `ChangelogOrderTest`: Er hängt jede Datei nach den Baselines an alle übrigen an, lässt Dateien, die ohne sie scheitern, ihr folgen und verlangt denselben Katalog-Fingerabdruck wie eine Neuinstallation. Alle drei laufen **auf leerem Schema**; was eine Änderung mit vorhandenen Zeilen macht, sehen sie nicht. Ohne Datenbank prüft `ComplianceManifestTest`, dass jede Tabelle und Spalte im Fakten-Manifest steht ([compliance-manifest.md](compliance-manifest.md)).
  - **Ohne eigenen Migrationstest** kommt nur rein additive DDL aus, die vorhandene Zeilen nicht berührt: neue Tabelle; neue nullable Spalte ohne Default und ohne `GENERATED`; neuer nicht eindeutiger Index; neuer Fremdschlüssel oder `CHECK` auf einer neuen Tabelle. Ob ein solcher Constraint fachlich greift, gehört in den Repository- oder Service-Test der Funktion.
  - **Alles andere braucht einen eigenen Delta-Test**, insbesondere Datenmigration oder Backfill (`UPDATE`, `INSERT … SELECT`, `DELETE`); Spalte oder Tabelle umbenennen oder entfernen; Typänderung; `NOT NULL`, `UNIQUE`, `CHECK` oder Fremdschlüssel auf einer bestehenden Tabelle; neue Spalte mit Default, `NOT NULL` oder `GENERATED`; Preconditions; Rollen und Rechte (`GRANT`/`REVOKE`, `CREATE ROLE`); Row-Level-Security und Policies; Trigger und Funktionen. Im Zweifel gilt ein Changeset als nicht additiv.
  - **Aufbau des Delta-Tests:** unter `backend/src/test/java/io/opaa/migration/` nach dem Muster in `AbstractMigrationTest`/`package-info.java`. Seine Fixture ist `MasterChangelog.filesExcept("db/changelog/<modul>/<datei>")`, also der ganze Master ohne die neue Datei: der Stand einer bestehenden Installation, die sie erhält. Bringt ein PR mehrere voneinander abhängige Dateien, werden alle genannt. `MasterChangelog.filesBefore(...)` liefert den Stand einer Neuinstallation. Danach legt der Test repräsentative Zeilen an, wendet nur die neue Datei an und prüft Schema und Daten.
  - **Vorbehalt:** Die Ausnahme für additive DDL gilt, solange es keine produktive Installation mit Altbestand gibt. Ab einem Pilotbetrieb mit echten Daten braucht wieder jedes Changeset einen eigenen Delta-Test mit vorhandenen Zeilen.
  - Ein **Bugfix** in einem Changeset braucht unabhängig davon den Reproduktionsnachweis aus der Wurzel-`AGENTS.md`; bei additiver DDL steht er im Repository- oder Service-Test.

## Rolling Update: Expand/Contract über zwei Releases

Bei einem Rolling Update migriert die neue Version, während die Vorgängerversion weiterläuft: in
Kubernetes mit mehreren Pods, aber auch im Aktiv/Passiv-Fall, solange der alte Pod angenommene
Anfragen zu Ende führt. **Ein Changeset darf deshalb nichts tun, was die Vorgängerversion bricht.**
Was sie bricht, wird auf zwei Releases verteilt:

1. **Expand (Release N):** ergänzen statt entfernen, also neue Tabelle, neue Spalte nullable oder
   mit bleibendem Default, breiterer Typ, zusätzliche Werte in einer `CHECK`-Werteliste. Der Code von
   N schreibt so, dass N-1 daneben weiterläuft: keine `NULL` in eine Spalte, die N-1 als Pflicht
   liest, kein neuer Enum-Wert ([Enum-Werte](#enum-werte)). Eine Umbenennung ist in N eine neue
   Spalte, die N befüllt und liest, während es die alte weiter mitschreibt.
2. **Contract (Release N+1):** entfernen, umbenennen oder einschränken, was N nicht mehr nutzt. Das
   Changeset trägt `labels: contract`. Läuft das Update von N auf N+1, nutzt die noch laufende
   Version N den entfernten Teil schon nicht mehr.

Was die Vorgängerversion bricht und deshalb nur als Contract erlaubt ist:

| Änderung | warum die Vorgängerversion daran scheitert |
|---|---|
| `DROP TABLE`, `DROP COLUMN`, `RENAME` einer Tabelle oder Spalte | sie liest und schreibt das Entfernte |
| `SET NOT NULL` ohne bleibenden Default, neue `NOT NULL`-Spalte ohne Default | ihr `INSERT` kennt die Spalte nicht oder schreibt `NULL` |
| `DROP NOT NULL` | sie liest `NULL`, wo sie einen Wert erwartet (`temperature`, NPE) |
| `DROP DEFAULT` | ihr `INSERT` verlässt sich auf den Default |
| Typwechsel außer Verbreiterung (`varchar(n)` → längeres `varchar` oder `text`) | ihre Werte passen nicht mehr |
| neuer oder geänderter `CHECK` auf bestehenden Spalten, außer er ergänzt nur Werte einer Werteliste | sie schreibt, was er jetzt ablehnt |

**Der Wächter.** `RollingUpdateGuardTest` wendet jedes Changeset des Masters einzeln in
Master-Reihenfolge an und vergleicht davor und danach den Katalog des Schemas: Tabellen (eine
Partition zählt als ihre Elterntabelle), Spalten mit Typ, `NOT NULL` und Default sowie die
`CHECK`-Constraints. Was ein Changeset bewirkt, entscheidet damit die Datenbank selbst und nicht die
Schreibweise seines SQL: Kommentare, Zeichenketten und Funktionsrümpfe zählen nicht, dynamisches
SQL in einem `DO`-Block, Kaskaden und Umbenennungen dagegen schon. Ein Changeset, das die
Vorgängerversion bricht, ohne `contract` zu tragen oder vor der Versionsgrenze zu liegen, lässt ihn
scheitern. Die Fixtures unter `src/test/resources/db/rolling-update/` zeigen, was er fängt und was
er durchlässt. Nicht geprüft werden neue Fremdschlüssel, Eindeutigkeit und Trigger auf bestehenden
Tabellen und was ein Changeset mit vorhandenen Zeilen tut; das bleibt Review und Delta-Test.

**Warum ein Label.** `labels: contract` steht am Changeset selbst und in `databasechangelog.labels`,
ändert die Prüfsumme nicht und wirkt ohne `spring.liquibase.label-filter` nicht auf die Ausführung.
Ein Kontext würde die Ausführung steuern; ein eigenes Verzeichnis widerspräche der Ablage je Modul.
Dass N den entfernten Teil nicht mehr nutzt, prüft der Wächter nicht: Das sichert der Review, im
Zweifel mit einem Blick auf den Code des Vorgänger-Tags.

## Versionsgrenzen ohne Rolling Update

Eine Versionsgrenze ohne Rolling Update ist ein Release, auf das nur mit angehaltenen Instanzen
aktualisiert wird. Nur dort sind Changesets erlaubt, die die Vorgängerversion brechen, ohne Contract
zu sein, und nur dort Baseline-Zusammenfassungen. Eine Grenze wird vom Maintainer entschieden, in
`RollingUpdateBoundary` (Testquellen) eingetragen, in den Release-Notizen genannt und im Handbuch
(`datenbank.md`, „Migration als eigener Schritt") ausgewiesen.

Bisher gibt es eine Grenze: **`v0.1.0`**, das erste Release mit dieser Regel. Davor liegen alle
Changesets bis `v0.1.0-rc.4`; `RollingUpdateBoundary.CHANGESETS_BEFORE` friert sie ein. 19 davon
brechen eine laufende Vorgängerversion. Keines ist ein Contract im Sinn der Regel, weil zwischen
Expand und Contract kein Release lag; markiert wird deshalb keines. Sie stehen mit ihrer Bewertung in
`RollingUpdateBoundary.BREAKING_BEFORE` und bleiben unverändert, denn ein ausgeführtes Changeset
ändert man nicht:

| Art | Changesets |
|---|---|
| `DROP COLUMN` (11 Spalten) | `knowledge-2026-10-02-drop-library-listed-cap`, `knowledge-2026-10-11-drop-library-source-proxy`, `knowledge-2026-10-12-drop-library-source-insecure-ssl`, `connections-2026-10-11-drop-profile-source-proxy`, `connections-2026-10-12-drop-profile-source-insecure-ssl`, `rights-2026-10-02-drop-asset-listed` (zwei Spalten), `rights-2026-10-03-drop-grant-member-count`, `workspace-2026-10-03-drop-membership-member-count` (zwei Spalten), `workspace-2026-10-03-drop-space-visibility` |
| `DROP TABLE` | `connections-2026-10-11-drop-profile-requests`, `rights-2026-10-08-drop-succession-reviews` |
| `NOT NULL` ohne bleibenden Default | `connections-2026-10-04-log-owner` (`owner_kind`) |
| entfernter `CHECK`-Wert | `rights-2026-10-02-drop-asset-listed` (`VISIBILITY_CHANGED`) |
| `DROP NOT NULL` | `knowledge-2026-10-11-llm-model-temperature-optional` (`temperature`, dazu `DROP DEFAULT`), `identity-2026-10-15-incident-scope-retention` (Betroffene und Antragsteller; der Jahresbericht der alten Version zählt aus den Genehmigungen), `connections-2026-10-05-mcp-server-profiles` (`source_type`) |
| neuer `CHECK` auf bestehenden Spalten | `rights-2026-10-03-capability-scope` (alte Version vergibt ohne `scope`), `rights-2026-10-04-asset-owner-only` und `rights-2026-10-11-group-deactivation` (gelten für jede Zeile der alten Version, die eine deaktivierte Gruppe aber weiter wirken lässt), `connections-2026-10-04-log-owner` |
| umgeschriebener `CHECK` | `connections-2026-10-05-source-consent` (für jede Zeile der alten Version gleichwertig) |

Eine weitere Grenze schreibt die Liste nicht fort, sondern ersetzt sie: Was vor ihr liegt, ist mit ihr
abgegolten.

## lock_timeout

**Jedes Changeset beginnt mit `SET LOCAL lock_timeout = '5s';`** (höchstens zehn Sekunden). Ein
`ALTER TABLE` wartet hinter einer laufenden Abfrage auf seine exklusive Sperre, und solange es
wartet, reihen sich alle späteren Abfragen auf die Tabelle hinter ihm ein, von allen Instanzen. Mit
dem Timeout gibt die Migration auf, die Warteschlange löst sich, und der Start oder Migrationsschritt
scheitert mit `lock timeout` und lässt sich wiederholen. `SET LOCAL` gilt nur für die Transaktion des
Changesets; die Verbindung geht ohne die Einstellung in den Pool der Anwendung zurück. Ein Changeset
mit `runInTransaction: false` (etwa für `CREATE INDEX CONCURRENTLY`) beginnt mit `SET lock_timeout`
und endet mit `RESET lock_timeout`.

Gewählt ist die Anweisung im Changeset, nicht eine Einstellung der Liquibase-Verbindung: Sie wirkt in
jedem Ausführungsweg (Start, Migrationsmodus, Migrationstests, Liquibase-CLI) und trägt keine
Sitzungseinstellung in den Verbindungspool. `ChangesetLockTimeoutTest` verlangt sie für jedes
Changeset nach der Versionsgrenze; die eingefrorenen Changesets davor bekommen sie nicht, weil das
ihre Prüfsumme änderte. `LockTimeoutMigrationTest` belegt die Wirkung: Mit Timeout gehen die
Abfragen hinter dem wartenden `ALTER TABLE` nach einer Sekunde weiter, ohne bleiben sie stehen, bis
die blockierende Abfrage endet.

## Enum-Werte

Ein neuer Wert in einem Enum, das per `@Enumerated(STRING)` oder Converter in einer Spalte steht,
braucht kein Changeset und bricht die Vorgängerversion trotzdem: Sie scheitert an jeder Zeile mit
dem Wert, und mit ihr die ganze Liste, in der die Zeile steht (eine Benachrichtigungsart,
`DirectorySyncOutcome.FAILED`, `AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED`).

**Verzeichnis statt tolerantem Lesen.** `src/test/resources/db/persisted-enums.yaml` führt jedes
persistierte Enum mit den Feldern, die es speichern, den Werten, die die Vorgängerversion liest
(`readable`), und den Werten, die diese Version kennt, aber noch nirgends schreibt (`pending`).
`PersistedEnumRegistryTest` findet die persistierten Enums selbst (Felder von Entities, Embeddables
und Mapped Superclasses unter `io.opaa`, auch Element-Collections) und scheitert an jedem Wert, der
in keiner der beiden Listen steht. Der Ablauf eines neuen Werts:

1. **Expand (Release N):** Wert anlegen und unter `pending` eintragen. N liefert ihn aus, schreibt
   ihn aber nicht; eine Verwendung `Typ.WERT` im Hauptcode außerhalb des Enums lässt den Test
   scheitern.
2. **Release N+1:** Wert nach `readable` verschieben und schreiben. Die laufende Version N kennt ihn.

Ein neu persistiertes Enum (eine neue Spalte) steht sofort ganz unter `readable`: Die
Vorgängerversion kennt die Spalte nicht. Ein entfernter Wert ist ein Contract: Zuerst schreibt ein
Changeset die Zeilen um, dann verlässt der Wert Enum und Verzeichnis.

**Tolerantes Lesen ist geprüft und verworfen**, für jedes Enum mit einer Bewertung im Verzeichnis:

- **`SECURITY`** (Rollen, Rechte, Konto-, Sperr- und Tokenzustände): Ein Rückfallwert verschiebt,
  was jemand darf.
- **`AUDIT`** (Protokolle und Historien): Ein Rückfallwert verfälscht den Nachweis.
- **`STATE`** (Zustand einer Zeile, die wieder geschrieben wird): Hibernate schreibt beim Speichern
  alle Spalten zurück; die alte Version würde den Rückfallwert über den unbekannten Wert speichern und
  ihn damit verlieren.

Dazu kommt für alle: Die Enums aus `io.opaa.api.types` sind zugleich Typen der API
(`typeMappings`); ein Rückfallwert wäre ein zusätzlicher Wert der Spezifikation, den die Oberfläche
kennen müsste. Die Schlüsseltypen `SourceType` und `AssetType` sind keine Enums, sondern offene
Records, und lesen jeden Schlüssel. Nicht erfasst sind Enum-Werte in JSON-Spalten (etwa
Prompt-Variablen); dort gilt die Regel ohne Prüfung.

## Migration als eigener Schritt

`OPAA_MODE=migrate` (`opaa.mode`, gelesen aus Aufrufargumenten, Systemeigenschaften und Umgebung)
startet statt der Anwendung nur Datenquelle, die Startwächter vor Liquibase (`DatabaseSchemaGuard`,
`DatabasePrerequisiteCheck`), die Parameter der Vektortabelle und Liquibase, wendet den Changelog an
und endet mit Exit-Code 0 (`io.opaa.config.DatabaseMigrationMode`). Liquibase läuft dort auch bei
`SPRING_LIQUIBASE_ENABLED=false`, damit Migrationsschritt und Instanzen dieselben Variablen teilen
können. Instanzen starten danach mit `SPRING_LIQUIBASE_ENABLED=false`. Ohne den Modus migriert jeder
Start wie bisher; Compose und Helm binden den Modus mit #2951 ein. `InstanceStartTest` startet das
Backend als eigene Prozesse: Migrationsmodus mit Exit-Code 0, danach eine Instanz ohne Migration,
und zwei gleichzeitige Kaltstarts gegen eine leere Datenbank.

Gleichzeitig startende Instanzen seeden unter einer Advisory-Sperre (Namensraum `209`) nacheinander;
ein Passwort erscheint erst nach dem Commit seiner Transaktion im Log. `OPAA_LOCAL_ADMIN_RESET=force`
wirkt einmal je Setzen: Der Reset steht in `local_admin_seed_marker.forced_reset_at`, erst ein Start
ohne die Variable löscht den Vermerk.

## vector_store in der Migration

`knowledge/2026-10-19-vector-store.yaml` legt `vector_store`, den Vektorindex `spring_ai_vector_index`
und die beiden Ausdrucksindexe mit `IF NOT EXISTS` an; `spring.ai.vectorstore.pgvector.initialize-schema`
ist `false`. Vorher legte Spring AI die Tabelle beim Start jeder Instanz an, und zwei gleichzeitige
Kaltstarts liefen in `CREATE EXTENSION`/`CREATE TABLE` gegeneinander (`VectorStoreColdStartTest`).
Dimension, Indextyp und Distanz kommen als Changelog-Parameter aus `spring.ai.vectorstore.pgvector`
(`VectorStoreChangelogParameters`); die Vorgaben im Changelog gelten nur, wo niemand sie übergibt,
etwa in den Migrationstests. Das Changeset trägt `runOnChange`, weil die Parameter in die Prüfsumme
eingehen: Eine geänderte Dimension führt es erneut aus, ohne etwas zu ändern; die bestehende Tabelle
passt `EmbeddingIndexReconciler` an. Eine von Spring AI früher angelegte Tabelle bleibt, wie sie ist.
Die beiden precondition-geschützten Ausdrucksindexe der Baseline überspringen auf einer
Neuinstallation den ersten Lauf und bauen die Indexe beim nächsten Lauf neu (`CONCURRENTLY`).

## Changeset-Stil (ADR-0034)

**Changeset-Stil (ADR-0034, #1366):** Changesets sind reines PostgreSQL-SQL in `sql:`-Blöcken, keine nativen Liquibase-Changes. Eigene Objekte werden **nie** schemaqualifiziert (kein `public.`) — das Zielschema kommt aus `opaa.database.schema` (`OPAA_DB_SCHEMA`) über den `search_path` der Verbindung. Braucht SQL den Schemanamen zwingend, dann über `current_schema()` (z. B. `format('GRANT … ON SCHEMA %I …', current_schema())`) bzw. für den gepinnten `search_path` von Funktionen über `${database.defaultSchemaName}`. Tests fragen Kataloge über `current_schema()` ab, nie über das Literal `'public'`; `SchemaPortabilityMigrationTest` wendet den Master-Changelog in ein anderes Schema an.

## Baseline-Tests

**Baseline-Tests.** `backend/src/test/resources/db/changelog/test-master-through-baseline.yaml` wendet die Baseline-Dateien aller Module an, ohne spätere Changesets. `MigrationBaselineTest` prüft das Modulübergreifende (Changeset-Reihenfolge, Tabelleninventar, pgvector, Seed-Zeilen, Partitionierung, Organisationsgrenzen-Regel), je eine Unterklasse von `AbstractBaselineTest` die Zustandsinvarianten eines Moduls (`IdentityBaselineTest`, `RightsBaselineTest`, `KnowledgeBaselineTest`, `ConnectorsBaselineTest`, `WorkspaceBaselineTest`, `AssistantBaselineTest`, `ExternalBaselineTest`), dazu `AuditPrivilegeModelTest` und `DiagnosticContextPrivilegeModelTest` (Privilegienmodelle nach ADR-0015), `ProtocolRetentionDeletionTest` (Löschlauf beider Protokolle) und `VectorStoreExpressionIndexTest` (Precondition-Übersprung und Wiederholung). Welche historischen Prüfungen bewusst entfallen sind, steht in den Beschreibungen von PR #906, PR #1504 und PR #2007.
