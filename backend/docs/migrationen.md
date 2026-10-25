# Datenbankmigrationen (Flyway): ein Verzeichnis je Modul

Herleitung und Prüfungen zu den Migrationsregeln in [backend/AGENTS.md](../AGENTS.md#datenbankmigrationen).
Das Werkzeug ist Flyway Community (Apache-2.0); warum nicht Liquibase, steht in
[ADR-0056](../../docs/decisions/0056-lizenz-der-migrationsbibliothek.md).

## Baselines

Die Historie wurde mehrfach zu einer Baseline zusammengefasst, zuletzt beim Umstieg von Liquibase
auf Flyway (ADR-0056): Jedes Modul hat seitdem genau eine Baseline `V1.<n>__<modul>_baseline.sql`,
die den Endstand der vorherigen Historie wiedergibt. Schema, Eigentümer, Rechte, Partitionen und
gelieferte Zeilen einer frischen Datenbank sind dabei unverändert geblieben. Jede bestehende
Installation wird danach neu aufgesetzt; Liquibases `databasechangelog` nimmt Flyway nicht als
Historie an.

**Eine weitere Baseline gibt es nur an einer ausgewiesenen [Versionsgrenze ohne Rolling Update](#versionsgrenzen-ohne-rolling-update).**
Eine Zusammenfassung schreibt die Historie um; die Vorgängerversion läuft danach nicht neben der
neuen.

## Ablage

Unter `backend/src/main/resources/db/migration/` liegt je logischem Modul (siehe
[backend/AGENTS.md](../AGENTS.md#logische-module-und-schichtung)) ein Verzeichnis: `foundation/`,
`identity/`, `rights/`, `knowledge/`, `connectors/`, `connections/`, `workspace/`, `assistant/`,
`external/`. `format`, `library`, `retrieval` und `app` besitzen keine Tabelle und damit kein
Verzeichnis. Flyway liest alle Verzeichnisse (`spring.flyway.locations: classpath:db/migration`) und
ordnet die Skripte über alle Verzeichnisse hinweg nach ihrer Version:

- **Baselines:** `V1.<n>__<modul>_baseline.sql`, `<n>` ist die Stelle des Moduls in der Reihenfolge
  von `ModularArchitecture.Module` (1 `foundation` bis 9 `external`). Damit läuft jedes Modul nach
  allen, von denen es abhängen darf.
- **Jede spätere Migration:** `V<JJJJ>.<MM>.<TT>.<n>__<thema>.sql` mit dem Tag, an dem die Datei
  entsteht, einer laufenden Nummer ab 1 und dem Thema in Kleinbuchstaben mit Unterstrichen, etwa
  `rights/V2026.11.02.1__library_share_cap.sql`. Zwei Skripte desselben Tags, auch in
  verschiedenen Modulen, bekommen verschiedene Nummern; eine Version gibt es nur einmal.

`MigrationLayoutTest` erzwingt Verzeichnisse, Namen, Nummerierung der Baselines und eindeutige
Versionen.

## Neue Migration: eine Migration pro Datenbankänderung

- **Wohin:** in das Verzeichnis des Moduls, dem die Tabelle gehört. Eine Tabelle gehört dem Modul,
  dessen Paket ihre Entity trägt; die Zuordnung steht in `MigrationModuleBoundaryTest.TABLE_MODULES`.
  Eine neue Tabelle wird dort eingetragen.
- **Ausgeführte Skripte bleiben unverändert.** Flyway prüft die Prüfsumme jedes ausgeführten Skripts
  beim Start und bricht bei einer Abweichung ab; ein Fehler wird mit einer neuen Migration behoben.
- **Modulgrenze:** Eine Migration legt nur Tabellen ihres Moduls an, ändert und entfernt nur diese.
  Dazu zählen Spalten und ihre Rechte, Constraints, Indexe, Eigentümer, Rechte, Row-Level-Security
  und Policies. Fremdschlüssel, Trigger und die Tabellen, die eine Funktion nennt, reichen nur in
  das eigene Modul oder in eines, von dem es nach `ALLOWED_MODULE_EDGES` abhängen darf; schreiben
  (`INSERT`, `UPDATE`, `DELETE`) darf eine Funktion nur in Tabellen des eigenen Moduls. Eine
  Migration entfernt oder ersetzt nur Trigger und Funktionen, die ihr eigenes Modul angelegt hat.
- **Trigger auf fremden Tabellen:** Ein Modul darf einen Trigger auf eine Tabelle eines tieferen
  Moduls legen, von dem es abhängen darf, etwa rights `trg_organizations_seed_capability_grants` auf
  `organizations` (foundation). Trigger und Funktion gehören dem höheren Modul und stehen in dessen
  Migration; die Funktion schreibt nur in Tabellen des eigenen Moduls (geprüft über die
  Schreibanweisungen im Funktionsrumpf, dynamisches SQL sieht die Prüfung nicht).
- **Reihenfolge: Eine Migration muss in beiden Reihenfolgen korrekt sein**, bei einer
  Neuinstallation und angehängt an eine bestehende Installation. Eine Neuinstallation wendet die
  Skripte nach Version an. Eine bestehende Installation wendet ein neues Skript dagegen nach allem
  an, was sie schon hat, auch nach Skripten höherer Version, etwa wenn ein langlebiger Branch nach
  einem neueren Skript gemergt wird (`spring.flyway.out-of-order: true`). Eine Migration entfernt
  oder ändert deshalb nichts, worauf ein anderes Skript verweist, und setzt nichts voraus, was erst
  ein Skript höherer Version anlegt. Hängt ein Skript von einem anderen ab, muss es eine höhere
  Version tragen; im Zweifel das Datum beim Merge auf den Merge-Tag setzen.
- **Tests:** Jede Migration prüfen ohne weiteres Zutun `MigrationLayoutTest`,
  `MigrationModuleBoundaryTest` (Modulgrenze auf der Datenbank nach jedem Skript, dazu der Abgleich
  der Zuordnung mit den Paketen der Entities) und `MigrationOrderTest`: Er hängt jedes Skript nach
  den Baselines an alle übrigen an, lässt Skripte, die ohne es scheitern, ihm folgen und verlangt
  denselben Katalog-Fingerabdruck wie eine Neuinstallation. Alle drei laufen **auf leerem Schema**;
  was eine Änderung mit vorhandenen Zeilen macht, sehen sie nicht. Ohne Datenbank prüft
  `ComplianceManifestTest`, dass jede Tabelle und Spalte im Fakten-Manifest steht
  ([compliance-manifest.md](compliance-manifest.md)).
  - **Ohne eigenen Migrationstest** kommt nur rein additive DDL aus, die vorhandene Zeilen nicht
    berührt: neue Tabelle; neue nullable Spalte ohne Default und ohne `GENERATED`; neuer nicht
    eindeutiger Index; neuer Fremdschlüssel oder `CHECK` auf einer neuen Tabelle. Ob ein solcher
    Constraint fachlich greift, gehört in den Repository- oder Service-Test der Funktion.
  - **Alles andere braucht einen eigenen Delta-Test**, insbesondere Datenmigration oder Backfill
    (`UPDATE`, `INSERT … SELECT`, `DELETE`); Spalte oder Tabelle umbenennen oder entfernen;
    Typänderung; `NOT NULL`, `UNIQUE`, `CHECK` oder Fremdschlüssel auf einer bestehenden Tabelle;
    neue Spalte mit Default, `NOT NULL` oder `GENERATED`; Rollen und Rechte (`GRANT`/`REVOKE`,
    `CREATE ROLE`); Row-Level-Security und Policies; Trigger und Funktionen. Im Zweifel gilt eine
    Migration als nicht additiv.
  - **Aufbau des Delta-Tests:** unter `backend/src/test/java/io/opaa/migration/` nach dem Muster in
    `AbstractMigrationTest`/`package-info.java`. Seine Fixture ist `Migrations.except("db/migration/<modul>/<skript>")`,
    also alle Skripte ohne das neue: der Stand einer bestehenden Installation, die es erhält. Bringt
    ein PR mehrere voneinander abhängige Skripte, werden alle genannt. `Migrations.before(...)`
    liefert den Stand einer Neuinstallation. Danach legt der Test repräsentative Zeilen an, wendet
    mit `applyMigrations(connection, script)` nur das neue Skript an und prüft Schema und Daten.
  - **Vorbehalt:** Die Ausnahme für additive DDL gilt, solange es keine produktive Installation mit
    Altbestand gibt. Ab einem Pilotbetrieb mit echten Daten braucht wieder jede Migration einen
    eigenen Delta-Test mit vorhandenen Zeilen.
  - Ein **Bugfix** in einer Migration braucht unabhängig davon den Reproduktionsnachweis aus der
    Wurzel-`AGENTS.md`; bei additiver DDL steht er im Repository- oder Service-Test.

## Rolling Update: Expand/Contract über zwei Releases

Bei einem Rolling Update migriert die neue Version, während die Vorgängerversion weiterläuft: in
Kubernetes mit mehreren Pods, aber auch im Aktiv/Passiv-Fall, solange der alte Pod angenommene
Anfragen zu Ende führt. **Eine Migration darf deshalb nichts tun, was die Vorgängerversion bricht.**
Was sie bricht, wird auf zwei Releases verteilt:

1. **Expand (Release N):** ergänzen statt entfernen, also neue Tabelle, neue Spalte nullable oder
   mit bleibendem Default, breiterer Typ, zusätzliche Werte in einer `CHECK`-Werteliste. Der Code von
   N schreibt so, dass N-1 daneben weiterläuft: keine `NULL` in eine Spalte, die N-1 als Pflicht
   liest, kein neuer Enum-Wert ([Enum-Werte](#enum-werte)). Eine Umbenennung ist in N eine neue
   Spalte, die N befüllt und liest, während es die alte weiter mitschreibt.
2. **Contract (Release N+1):** entfernen, umbenennen oder einschränken, was N nicht mehr nutzt. Die
   Beschreibung im Dateinamen beginnt mit `contract_`, etwa
   `V2026.12.01.1__contract_drop_library_listed.sql`. Läuft das Update von N auf N+1, nutzt die
   noch laufende Version N den entfernten Teil schon nicht mehr.

Was die Vorgängerversion bricht und deshalb nur als Contract erlaubt ist:

| Änderung | warum die Vorgängerversion daran scheitert |
|---|---|
| `DROP TABLE`, `DROP COLUMN`, `RENAME` einer Tabelle oder Spalte | sie liest und schreibt das Entfernte |
| `SET NOT NULL` ohne bleibenden Default, neue `NOT NULL`-Spalte ohne Default | ihr `INSERT` kennt die Spalte nicht oder schreibt `NULL` |
| `DROP NOT NULL` | sie liest `NULL`, wo sie einen Wert erwartet (`temperature`, NPE) |
| `DROP DEFAULT` | ihr `INSERT` verlässt sich auf den Default |
| Typwechsel außer Verbreiterung (`varchar(n)` → längeres `varchar` oder `text`) | ihre Werte passen nicht mehr |
| neuer oder geänderter `CHECK` auf bestehenden Spalten, außer er ergänzt nur Werte einer Werteliste | sie schreibt, was er jetzt ablehnt |

**Der Wächter.** `RollingUpdateGuardTest` wendet jede Migration nach der Versionsgrenze einzeln in
Versionsreihenfolge an und vergleicht davor und danach den Katalog des Schemas: Tabellen (eine
Partition zählt als ihre Elterntabelle), Spalten mit Typ, `NOT NULL` und Default sowie die
`CHECK`-Constraints. Was eine Migration bewirkt, entscheidet damit die Datenbank selbst und nicht
die Schreibweise ihres SQL: Kommentare, Zeichenketten und Funktionsrümpfe zählen nicht, dynamisches
SQL in einem `DO`-Block, Kaskaden und Umbenennungen dagegen schon. Eine Migration, die die
Vorgängerversion bricht, ohne als Contract benannt zu sein, lässt ihn scheitern. Die Fixtures unter
`src/test/resources/db/rolling-update/` zeigen, was er fängt und was er durchlässt. Nicht geprüft
werden neue Fremdschlüssel, Eindeutigkeit und Trigger auf bestehenden Tabellen und was eine
Migration mit vorhandenen Zeilen tut; das bleibt Review und Delta-Test.

**Warum im Dateinamen.** Flyway kennt keine Labels. Die Beschreibung steht in
`flyway_schema_history.description` (`contract drop library listed`) und wirkt nicht auf die
Ausführung; ein eigenes Verzeichnis widerspräche der Ablage je Modul. Dass N den entfernten Teil
nicht mehr nutzt, prüft der Wächter nicht: Das sichert der Review, im Zweifel mit einem Blick auf
den Code des Vorgänger-Tags.

## Versionsgrenzen ohne Rolling Update

Eine Versionsgrenze ohne Rolling Update ist ein Release, auf das nur mit angehaltenen Instanzen
aktualisiert wird. Nur dort sind Migrationen erlaubt, die die Vorgängerversion brechen, ohne
Contract zu sein, und nur dort Baseline-Zusammenfassungen. Eine Grenze wird vom Maintainer
entschieden, in `RollingUpdateBoundary` (Testquellen) eingetragen, in den Release-Notizen genannt und
im Handbuch (`datenbank.md`, „Migration als eigener Schritt") ausgewiesen.

Bisher gibt es eine Grenze: **`v0.1.0`**, das erste Release mit dieser Regel. Vor ihr liegen die
Baselines (Version 1.x); sie enthalten alles, was bis dahin geschrieben wurde, auch die Änderungen,
die eine laufende Vorgängerversion gebrochen hätten. Eine weitere Grenze schreibt diese Festlegung
nicht fort, sondern ersetzt sie: Was vor ihr liegt, ist mit ihr abgegolten.

## lock_timeout

**Jede Migration nach der Versionsgrenze beginnt mit `SET LOCAL lock_timeout = '5s';`** (höchstens
zehn Sekunden). Ein `ALTER TABLE` wartet hinter einer laufenden Abfrage auf seine exklusive Sperre,
und solange es wartet, reihen sich alle späteren Abfragen auf die Tabelle hinter ihm ein, von allen
Instanzen. Mit dem Timeout gibt die Migration auf, die Warteschlange löst sich, und der Start oder
Migrationsschritt scheitert mit `lock timeout` und lässt sich wiederholen. Flyway führt jedes Skript
in einer eigenen Transaktion aus; `SET LOCAL` gilt nur für sie, die Verbindung geht ohne die
Einstellung zurück. Ein Skript mit `CREATE INDEX CONCURRENTLY` führt Flyway außerhalb einer
Transaktion aus; es beginnt mit `SET lock_timeout` und endet mit `RESET lock_timeout`, und es enthält
nichts, was eine Transaktion braucht.

Gewählt ist die Anweisung im Skript, nicht eine Einstellung der Flyway-Verbindung: Sie wirkt in
jedem Ausführungsweg (Start, Migrationsmodus, Migrationstests, Flyway-CLI) und trägt keine
Sitzungseinstellung in den Verbindungspool. `MigrationLockTimeoutTest` verlangt sie für jede
Migration nach der Versionsgrenze; die Baselines laufen nur auf einer leeren Datenbank und brauchen
sie nicht. `LockTimeoutMigrationTest` belegt die Wirkung: Mit Timeout gehen die Abfragen hinter dem
wartenden `ALTER TABLE` nach einer Sekunde weiter, ohne bleiben sie stehen, bis die blockierende
Abfrage endet.

## Enum-Werte

Ein neuer Wert in einem Enum, das per `@Enumerated(STRING)` oder Converter in einer Spalte steht,
braucht keine Migration und bricht die Vorgängerversion trotzdem: Sie scheitert an jeder Zeile mit
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

**Bis zur Versionsgrenze `v0.1.0`** darf ein neuer Wert sofort unter `readable` stehen und
geschrieben werden: Auf `v0.1.0` wird nur mit angehaltenen Instanzen aktualisiert
([Versionsgrenzen ohne Rolling Update](#versionsgrenzen-ohne-rolling-update)), keine frühere Version
läuft daneben. Das gilt nur, solange `v0.1.0` noch nicht erschienen ist; ab dann für jeden neuen Wert
der Weg über `pending`.

Ein neu persistiertes Enum (eine neue Spalte) steht sofort ganz unter `readable`: Die
Vorgängerversion kennt die Spalte nicht. Ein entfernter Wert ist ein Contract: Zuerst schreibt eine
Migration die Zeilen um, dann verlässt der Wert Enum und Verzeichnis.

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
startet statt der Anwendung nur Datenquelle, die Startwächter vor Flyway (`DatabaseSchemaGuard`,
`DatabasePrerequisiteCheck`), die Platzhalter der Baseline-Vektortabelle und Flyway, wendet die Migrationen an
und endet mit Exit-Code 0 (`io.opaa.config.DatabaseMigrationMode`). Flyway läuft dort auch bei
`SPRING_FLYWAY_ENABLED=false`, damit Migrationsschritt und Instanzen dieselben Variablen teilen
können. Instanzen starten danach mit `SPRING_FLYWAY_ENABLED=false`. Ohne den Modus migriert jeder
Start. `OPAA_MODE=await-migration` startet nur die Datenquelle und ein Flyway, das beim Start nichts
ausführt, und wartet, bis `flyway info` keine offene Migration dieser Version mehr meldet, ohne zu
schreiben; `flyway_schema_history` legt dabei niemand an. Der Helm-Chart startet die Migration als
Job und diesen Modus als Init-Container jedes Backend-Pods, `docker-compose.multi.yml` den
Migrationsdienst `backend-migrate`. `InstanceStartTest` startet das Backend als eigene Prozesse:
Migrationsmodus mit Exit-Code 0, der Wartemodus endet erst danach, eine Instanz ohne Migration
startet, und zwei gleichzeitige Kaltstarts gegen eine leere Datenbank. Gleichzeitige Migrationen
mehrerer Instanzen serialisiert Flyway über eine Advisory-Sperre auf der Schema-Historie; ihr
Schlüssel (`pg_advisory_lock(bigint)`) liegt in einem anderen Schlüsselraum als die Sperren mit zwei
Schlüsseln aus `src/test/resources/db/advisory-lock-namespaces.yaml`.

Gleichzeitig startende Instanzen seeden unter einer Advisory-Sperre (Namensraum `211`) nacheinander;
ein Passwort erscheint erst nach dem Commit seiner Transaktion im Log. `OPAA_LOCAL_ADMIN_RESET=force`
wirkt einmal je Setzen: Der Reset steht in `local_admin_seed_marker.forced_reset_at`, erst ein Start
ohne die Variable löscht den Vermerk.

## vector_store entsteht bei der Installation

`vector_store` gehört keiner Migration: Die Tabelle entsteht, sobald das Embedding-Modell feststeht,
mit der Dimension seiner Probeeinbettung ([ADR-0060](../../docs/decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md),
Entscheidung 10). `io.opaa.knowledge.VectorTableCreator` legt sie mit dem Vektorindex
`spring_ai_vector_index` und den beiden Ausdrucksindexen auf `library_id` und `document_id` an, in der
Transaktion, die `embedding_model` schreibt, und unter der Schemasperre der Vektortabelle
(Namensraum `213`); zwei Instanzen legen sie so genau einmal an (`VectorStoreColdStartTest`).
`spring.ai.vectorstore.pgvector.initialize-schema` bleibt `false`, damit Spring AI beim Start keiner
Instanz DDL ausführt. Indextyp und Distanz kommen aus `spring.ai.vectorstore.pgvector`.

Die knowledge-Baseline legt `vector_store` aus der Zeit davor weiterhin an; ein ausgeführtes Skript
wird nicht geändert. Ihre Dimension ist ein fester Flyway-Platzhalter
(`VectorStoreMigrationPlaceholders`), Indextyp und Distanz bleiben Platzhalter. Danach entfernt
`knowledge/V2026.10.25.5__contract_vector_store_at_installation.sql` die leere Tabelle wieder, aber
nur auf einer Datenbank, auf der noch keine Instanz gestartet ist (keine Zeile in
`embedding_index`, die jeder Start schreibt) und die keinen Abschnitt hält. Auf einer solchen
Datenbank läuft keine Vorgängerversion, daher bricht das Entfernen keine; als `contract_` ist es
benannt, weil `RollingUpdateGuardTest` das Entfernen einer Tabelle sonst ablehnt. Eine bestehende
Installation behält Tabelle, Spalten und Abschnitte unverändert (`VectorStoreAtInstallationMigrationTest`).
`MigrationSchema` liest dieses Entfernen als Ausnahme und führt `vector_store` als vorhanden.

Eine bestehende Installation übernimmt beim ersten Start ihr bisher konfiguriertes Embedding-Modell
einmalig nach `embedding_model` (`EmbeddingModelService#adoptIfMissing`, aufgerufen von der
Startentscheidung der Installation). Mit vorhandener Tabelle nur, wenn die Konfiguration das Modell
nennt, das `embedding_index` verzeichnet (bei laufender Neueinbettung das Ziel), und mit der
Dimension der vorhandenen Spalte; ohne Tabelle mit der Dimension einer Probeeinbettung, und die
Tabelle entsteht dabei.

## Skript-Stil (ADR-0034)

Migrationen sind reines PostgreSQL-SQL. Eigene Objekte werden **nie** schemaqualifiziert (kein
`public.`) — das Zielschema kommt aus `opaa.database.schema` (`OPAA_DB_SCHEMA`) über den
`search_path` der Verbindung und `spring.flyway.default-schema`. Braucht SQL den Schemanamen
zwingend, dann über `current_schema()` (z. B. `format('GRANT … ON SCHEMA %I …', current_schema())`)
bzw. für den gepinnten `search_path` von Funktionen über `${flyway:defaultSchema}`. Flyway ersetzt
jedes `${name}` im Skript; ein solcher Text als Literal braucht deshalb einen anderen Weg (etwa
`'$' || '{…}'`). Tests fragen Kataloge über `current_schema()` ab, nie über das Literal `'public'`;
`SchemaPortabilityTest` wendet alle Migrationen in ein anderes Schema an.

## Baseline-Tests

Die Baseline-Tests prüfen den Endstand aller Migrationen (`AbstractBaselineTest`, Fixture
`Migrations.all()`): `MigrationBaselineTest` das Modulübergreifende (eine Baseline je Modul in
Modulreihenfolge, Tabelleninventar, pgvector, Seed-Zeilen, Partitionierung,
Organisationsgrenzen-Regel), je eine Unterklasse von `AbstractBaselineTest` die Zustandsinvarianten
eines Moduls (`IdentityBaselineTest`, `RightsBaselineTest`, `KnowledgeBaselineTest`,
`ConnectorsBaselineTest`, `WorkspaceBaselineTest`, `AssistantBaselineTest`, `ExternalBaselineTest`),
dazu die Privilegienmodelle nach ADR-0015 (`AuditPrivilegeModelTest`,
`ConnectionLogPrivilegeModelTest`, `DiagnosticContextPrivilegeModelTest`),
`ProtocolRetentionDeletionTest` (Löschlauf der Protokolle) und die Tests der Trigger und Wächter des
Endstands (`OwnerOnlyDatabaseGuardTest`, `CacheInvalidationTriggerTest`, `SessionGenerationTest`
u. a.).

## Flyway Community

- **Lizenz:** `flyway-core` und `flyway-database-postgresql` stehen unter Apache-2.0; die Version
  bringt die Spring-Boot-BOM mit (`spring-boot-starter-flyway`).
- **Keine Verbindung zum Hersteller:** Die Community-Ausgabe enthält nur einen leeren
  Telemetrie-Dienst (`NullFlywayTelemetryManager`); anders als bei Liquibase ist nichts abzuschalten.
- **Funktionsumfang:** OPAA nutzt nur versionierte SQL-Migrationen, Platzhalter, `out-of-order` und
  `validate`. Undo-Migrationen, Dry-Run und Skript-Konfigurationsdateien der kostenpflichtigen
  Ausgaben braucht es nicht; `clean` ist in Spring Boot abgeschaltet (`spring.flyway.clean-disabled`).
