# Backend-Anweisungen für KI-Agenten

Ergänzt die [AGENTS.md](../AGENTS.md) im Wurzelverzeichnis um die Regeln, die nur für Arbeit unter
`backend/` und `opaa-api/` gelten. Die Regeln dort (Projektsprache, Git-Workflow, Reproduktionsnachweis,
Sicherheit) gelten unverändert weiter.

## Abhängigkeitsverwaltung

- Alle Bibliotheks- und Plugin-Versionen MÜSSEN in `backend/gradle/libs.versions.toml` deklariert werden — niemals eine Version direkt in `build.gradle.kts` eintragen
- Alle Abhängigkeiten MÜSSEN im Abschnitt `[libraries]` als Bibliotheken definiert und über `libs.*` in `build.gradle.kts` referenziert werden
- Verwandte Bibliotheken in `[bundles]` zusammenfassen, wo sinnvoll (z. B. `spring-boot`, `spring-ai`, `test-deps`)
- Versions-Kataloge (`libs.versions.*`, `libs.*`, `libs.bundles.*`) für die Referenzierung von Versionen, Bibliotheken und Bundles verwenden
- Dies gilt für die Abschnitte `[versions]`, `[libraries]`, `[bundles]` und `[plugins]`

## API & DTO-Konvention (Fortsetzung)

Die beiden Kernregeln — DTOs nie von Hand, Änderungen beginnen in der Spec — stehen in der Wurzel-`AGENTS.md`.

- Spec, Generator-Konfiguration und die geteilten Domain-Enums, auf die `typeMappings` zeigt, leben im eigenen Gradle-Modul `opaa-api` (`io.opaa.api.types`); das Backend konsumiert sie über `implementation(project(":opaa-api"))` (#896)
- Domain-Enums in DTOs (z. B. `SpaceRole`, `AssetRole`) werden über `typeMappings`/`importMappings` in `opaa-api/build.gradle.kts` gemappt
- Beim Hinzufügen neuer Domain-Enums zur API genügen Einträge in `typeMappings` und `importMappings`; der `doLast`-Cleanup-Block im `openApiGenerate`-Task leitet die zu löschenden generierten Dateien mechanisch aus `typeMappings` ab
- **Domain-Services kennen keine `io.opaa.api.dto`-Typen** (#860): Service-Methoden nehmen Entities, Einzelparameter oder kleine Domain-Parameter-Records entgegen und geben Entities oder Domain-Records zurück. Das Entity→Response-Mapping lebt in einer package-private Mapper-Klasse im Paket des aufrufenden Controllers (heute meist `io.opaa.api`; Vorbild: `BrandingResponseMapper`, `SpaceResponseMapper`). Für angereicherte Ansichten (Response ≠ Entity, z. B. mit einer zusätzlichen Zählung) trägt ein Domain-Record im jeweiligen Fachpaket die zusätzlichen Felder (z. B. `SpaceOverview(space, libraryCount, chatCount)`) — kein Mapping-Framework, handgeschriebene Mapper genügen bei dieser DTO-Größenordnung
- **Werden Test-Assertions von Response-Feldern auf Entity-Ableitungen umgestellt** (etwa weil ein Service-Test jetzt gegen ein Entity statt ein DTO prüft), **muss die tatsächliche Feldbelegung durch einen Mapper-Unit-Test zugesichert werden** — sonst prüft kein Test mehr, dass der Mapper jedes Feld korrekt befüllt (siehe `SpaceResponseMapperTest`, `SpaceLibraryAssociationResponseMapperTest`)
- **Eine Operation deklariert, was sie selbst entscheidet** (#1781). Was die Infrastruktur für alle Operationen gleich entscheidet — `400` bei unlesbarer Anfrage, `401` ohne brauchbare Sitzung, `403` bei erzwungenem Passwortwechsel oder einem Zugangstoken außerhalb seines Kanals, `404` bei unbekannter Route, `405`, `406`, `415`, `500` — steht einmal in `info.description` der Spezifikation und **in dieser Ausprägung** an keiner Operation. Eigene Entscheidungen bleiben deklariert: die Prüfung des eigenen Nutzinhalts (`400`), die eigene Rechteprüfung (`403`), die eigene Ressource (`404`), der eigene Konflikt (`409`), die eigene Größengrenze (`413`), die eigene Abhängigkeit (`503`) — und `401` dort, wo die Operation selbst eine ihr übergebene Berechtigung abweist (Anmeldung, Refresh-Cookie, Webhook-Signatur, Anbieter-Token der Übergabe). `429` steht an genau den Operationen mit einer eigenen Grenze; die Ratenbegrenzung ist endpunktweise, nicht global. `TransportStatusCodeSpecificationTest` hält beide Hälften maschinell fest und leitet die `429`-Erwartung aus der produktiven Verdrahtung von `RateLimitConfiguration` ab

> Vollständige Begründung: [ADR-0006](../docs/decisions/0006-openapi-dto-generation.md)

## MinIO-Suite des S3-Konnektors

Die MinIO-Suite des S3-Konnektors (io.opaa.indexing.source.s3.*Minio*, ADR-0027, #1382)
läuft innerhalb von test/build, sobald Docker erreichbar ist (sonst übersprungen). Fußabdruck:
ein geteilter MinIO je Test-JVM (MinioFixture) plus je Methode des Ereignisweg-Tests ein
eigener MinIO samt sshd-Sidecar für die Portweiterleitung; die Spring-Klassen teilen sich den
@OpaaIntegrationTest-Kontext (kein zusätzlicher Postgres). Bei maxParallelForks = 2 in
der CI verdoppelt sich das. Alle drei Klassen zusammen unter zwei Minuten.

## Spring-Testkontexte

Die gesamte Backend-Suite läuft auf **neun** Spring-Kontexten und damit neun Testcontainers-Postgres
je Test-JVM (Issues #1481, #1543 und #1563; `TestcontainersConfiguration` deklariert den Container
als gewöhnliches Singleton-`@Bean`, es gilt also exakt: ein Kontext = ein Container). Jeder
Backend-Test, der einen Anwendungskontext startet, trägt genau eine der neun Meta-Annotationen aus
`io.opaa.test` (`backend/src/test/java/io/opaa/test/`) — niemals eine eigene
`@SpringBootTest`/`@ActiveProfiles`/`@Import`/`@Testcontainers`-Kombination. Vier davon bilden die
Familie des `local,dev`-Profils, fünf die des Betriebsmodus `oidc`:

- **`@OpaaIntegrationTest`** — die kanonische Signatur, die drei Viertel der Suite tragen: echtes
  Postgres, ganze Anwendung auf `RANDOM_PORT`, MockMvc (`@AutoConfigureMockMvc`),
  `@ActiveProfiles({"local","dev"})`, die festen Test-Properties der Annotation (Chunking,
  Upload-Grenzen, abgeschaltetes Rate-Limit, OIDC-Allowlist) und die geteilten Ersatz-Beans aus
  `OpaaTestBeans` (`FakeEmbeddingModel`, `ChatModel`-Mock, `FakeDirectoryClient`) sowie die
  `@MockitoSpyBean`-Spies auf `UserRepository`, `ChatMessageRepository` und
  `DirectorySyncStatusRecorder`.
- **`@OpaaMockedChatModelIntegrationTest`** — dieselbe Basis, aber `ActiveChatModelResolver` als
  Mock und der Chat-Titel-Executor synchron. Technischer Grund: zwei Klassen prüfen den **echten**
  Resolver, davon eine innerhalb der Produktionsverdrahtung.
- **`@OpaaMockedDocumentServiceIntegrationTest`** — dieselbe Basis, aber `DocumentService` (und der
  Resolver) als Mock, Chat-Titel-Executor asynchron wie in Produktion. Technischer Grund: zwei
  Klassen brauchen ein gescriptetes `parseDocument`, was für die zwei Dutzend Klassen, die echte
  Fixtures indexieren, nicht neutral ist.
- **`@OpaaPropertyVariantIntegrationTest`** — dieselbe Basis plus die Properties, die selbst
  Prüfgegenstand sind (abweichende Embedding-Basis-URL, feste pgvector-Dimension ohne
  Schema-Initialisierung). Technischer Grund: eine Nachbarklasse prüft genau den Default, und der
  pgvector-Wächter zerstört und erzeugt `vector_store` neu.

Fünf weitere tragen den Betriebsmodus **`oidc`**, den die vier oben nicht liefern können: Unter
`local,dev` authentifiziert `DevAuthFilter` jede Anfrage, bevor überhaupt ein Bearer-Token gelesen
wird — eine lokale Sitzung ist dort nicht fahrbar. Das ist die harte technische Begründung dieser
zweiten Familie (ADR-0033, #1543):

- **`@OpaaLocalAuthMockMvcTest`** — die Basis: Profil `oidc`, MockMvc, geteiltes Postgres, ein
  starkes Test-Secret (sonst verweigert `LocalAuthSecretGuard` den Start) und angehobene
  `opaa.rate-limit.local-auth.*`-Grenzen, weil jede Klasse ihre Anmeldungen von der einen Adresse
  von MockMvc aus fährt.
- **`@OpaaLocalAuthLinkTest`** — dieselbe Basis plus `opaa.public-base-url` und den
  Einstellungs-Schlüssel. Technischer Grund: Ohne Basis-URL sind die Link-Flüsse abgeschaltet, und
  Klassen auf der Basis prüfen genau das.
- **`@OpaaLocalAuthSeedTest`** — dieselbe Basis plus eine zustellbare Erstadministrator-Adresse und
  eine Netzbeschränkung. Technischer Grund: Die Basis trägt den ausgelieferten Vorgabewert, den der
  Seed ablehnt — und eine Klasse prüft diese Ablehnung.
- **`@OpaaLocalAuthRateLimitTest`** — dieselbe Basis mit den **echten** Grenzen. Technischer Grund:
  Die Grenzen sind hier Prüfgegenstand.
- **`@OpaaLocalAuthProviderTest`** — dieselbe Basis plus einen Ersatz für die Decoder-Fabrik der
  Anbieter-Registry (`OidcProviderTokenTestConfiguration`, ein echter `NimbusJwtDecoder` über einen
  lokal erzeugten Schlüssel). Technischer Grund: Die Übergabe eines lokalen Kontos an eine
  Anbieteridentität (ADR-0033, Entscheidung 12) wird mit einem **prüfbaren Anbieter-Token**
  eingelöst; die Produktionsfabrik müsste dafür ein JWK-Set aus dem Netz holen. Diesen Ersatz in
  die Basis zu ziehen, nähme ihn allen Klassen der Familie — auch denen, die unmittelbar daneben
  den Decoder des lokalen Issuers fahren.

Die Varianten sind jeweils über die Basis ihrer Familie meta-annotiert und ergänzen nur ihre
Abweichung. Eine Variante muss **nicht fachlich zusammengehören**: Wo eine Abweichung ohnehin einen
eigenen Kontext kostet, fährt fachlich Unverwandtes bewusst mit (der S3-Upload-Speicher in der
Chat-Modell-Signatur, das Test-Dokumentformat in der `DocumentService`-Signatur). **Ein Kontext mehr
braucht eine harte technische Begründung, nicht eine fachliche Zugehörigkeit** (Maßgabe des
Maintainers vom 11.09.2026).

**Was einen Kontext spaltet — und wohin es stattdessen gehört.** Spring cached Kontexte anhand der
exakten, zusammengeführten Konfiguration (`MergedContextConfiguration`). Eine klassenlokale
`@DynamicPropertySource`, ein klassenlokales `@Import`, ein `@MockitoBean`/`@MockitoSpyBean`/
`@TestBean`-Feld oder eine innere `@TestConfiguration` erzeugt trotz gemeinsamer Meta-Annotation
einen eigenen Kontext und einen eigenen Container. Deshalb:

- **Konstanter Wert** → `properties` der Meta-Annotation.
- **Zur Laufzeit aus einer Ressource gelesener Wert** (Containeradresse, prozessweites
  Basisverzeichnis) → ein `ApplicationContextInitializer` der Meta-Annotation
  (`OpaaTestPathInitializer`, `OpaaTestTargetAllowlistInitializer`, `OpaaS3UploadStoreInitializer`).
  Eine klassenlokale `@DynamicPropertySource` spaltet den Kontext auch bei identischem Wert, weil
  Spring die Methode selbst in den Cache-Schlüssel einbezieht.
- **Ersetzte oder ergänzte Bean** → `OpaaTestBeans` (bzw. die `@MockitoSpyBean`-Liste der
  Meta-Annotation). Ein Spy delegiert an die echte Bean und ist damit für alle anderen Klassen
  verhaltensneutral; ein voller Mock ist es fast nie.
- **Eigenes Verzeichnis, dessen Pfad in eine Property fließt** → `OpaaTestDirectory.subdirectory(name)`
  unter dem einen prozessweiten Basisverzeichnis. Ein `@TempDir` bliebe je Klasse verschieden und
  spaltete damit den Kontext. Ein `@TempDir`, das nur im Test selbst verwendet wird und keine
  Property speist, ist unverändert in Ordnung.

**Jeder geteilte Mock oder Fake braucht einen Reset je Testmethode.** Für `@MockitoBean`/
`@MockitoSpyBean` erledigt das Spring selbst; für die Fakes aus `OpaaTestBeans` tut es
`OpaaTestBeanResetListener`. Ohne ihn erbt eine künftige Klasse stillschweigend den Zustand, den die
vorherige Testmethode hinterlassen hat. `@TestExecutionListeners` gehen nicht in den Cache-Schlüssel
ein, erzeugen also keinen zusätzlichen Kontext.

**Eine Datenbank für die ganze Suite.** Eine Klasse räumt in `@AfterEach` hinter sich auf und prüft
**nie** gegen eine ungefilterte Tabelle, sondern nur gegen Zeilen ihrer eigenen IDs. **Nur** in
`@BeforeEach` aufzuräumen ist unzulässig (#1510): Es verlagert die Arbeit auf die nächste Klasse und
setzt voraus, dass die den fremden Bestand kennt. In beiden Haken aufzuräumen ist dagegen richtig —
der `@BeforeEach`-Teil schützt gegen eine Methode, die auf halbem Weg abgebrochen ist. Ein
pauschales `deleteAll()` über eine Tabelle, in die auch andere Klassen schreiben (`users`,
`knowledge_libraries`), ist ein Fehler, kein Aufräumen — es scheitert spätestens an einer
RESTRICT-Fremdschlüsselbeziehung einer fremden, noch gebrauchten Zeile. Ebenso wenig räumt eine
Klasse vorsorglich hinter einer namentlich genannten anderen her; stattdessen wird die verursachende
Klasse repariert. `LeftoverRowGuard` zählt `documents`, `knowledge_libraries`, `vector_store`,
`diagnostic_impersonation_grants` und `audit_incident_scope_grants` zu Beginn und am Ende jeder
Testklasse, jeweils erst wenn alle Task-Executor des Kontexts leer sind, und nennt die Klasse, nach
der eine Zählung gewachsen ist — nur sie, nicht jede folgende.

**Installationsweite Einstellungszeilen** (`branding_settings`, `audit_retention_settings`,
`diagnostic_context_retention_settings`, `local_auth_settings`, `mail_settings`, `mail_templates`,
die Seed-Marker) haben keine eigene ID, auf die eine Klasse ihr Aufräumen eingrenzen könnte.
`SeededRowRestorer` sichert sie zu Beginn jeder Klasse und stellt sie nach jeder Testmethode wieder
her, nach deren `@AfterEach` — auch wenn dort eine Aufräumanweisung geworfen hat. Eine weitere
solche Tabelle gehört in seine Liste. **Trägt die Tabelle auch Zeilen, die eine Testklasse selbst
schreibt** (`capability_grants`, `capability_grant_history`), wird sie dort als `BY_ID` geführt: Der
Restorer merkt sich die vorgefundenen IDs und fasst nur die an. Ein Prädikat über die Zeilenform
träfe die eigene Zeile einer Klasse mit — und nähme ihr damit das Aufräumen ab, das
`LeftoverRowGuard` einfordert.

**`SpringContextSignatureTest` zieht die Grenze maschinell.** Er baut über
`BootstrapUtils.resolveTestContextBootstrapper(...)` je Testklasse die `MergedContextConfiguration`,
ohne einen Kontext zu starten, gruppiert danach und schlägt fehl, sobald eine Klasse ihre Signatur
verlässt oder eine zehnte Signatur entsteht. Eine neue kanonische Signatur wird dort bewusst
eingetragen; ein Code-Kommentar als Begründung genügt nicht mehr (nach #843 hatte jede der
gewachsenen 23 Kontext-Varianten einen formal regelkonformen Kommentar). Er prüft außerdem je
Klasse, dass `LeftoverRowGuard` und `SeededRowRestorer` als Listener aufgelöst werden — eine neue
Basis-Signatur muss beide registrieren.

Ein neuer Postgres-Container wird nie manuell deklariert; `@ServiceConnection` kommt aus der
Meta-Annotation. Ausnahme: `io.opaa.migration`-Tests booten bewusst einen eigenen Container mit
Template-Datenbank pro Klasse (siehe `AbstractMigrationTest`) — das Muster ist dort nötig und keine
Abweichung von dieser Regel. Ebenfalls außerhalb: die `@WebMvcTest`-Slices, die weder einen
Anwendungskontext noch eine Datenbank starten.

## Liquibase-Baseline (Stand 09/2026, #1492)

Die Liquibase-Historie wurde zweimal zu einer Baseline zusammengefasst: 08/2026 die ersten 134 Changesets aus 67 Dateien (#904/PR #906), 09/2026 die seither entstandenen 56 Changesets aus 33 Dateien (#1492/PR #1504). Beide Male ein bewusster Einmalvorgang vor Produktionsbetrieb, kein wiederkehrendes Muster: jede laufende Installation muss danach neu aufgesetzt werden, weil `DATABASECHANGELOG` nicht mehr passt.

Ergebnis ist die eine Datei `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` mit 13 thematisch gruppierten Changesets (`001-baseline-a` … `-m`) plus zwei bewusst eigenständigen, precondition-geschützten Changesets für die `vector_store`-Ausdrucksindexe (`-n`, `-o`) — die dürfen nicht in eine Gruppe gefaltet werden, sonst überspringt die Precondition beim ersten Start eine ganze Gruppe. `db.changelog-master.yaml` referenziert nur noch diese Datei; Rollback-Blöcke hat die Baseline bewusst keine.

**Changeset-Stil (ADR-0034, #1366):** Changesets sind reines PostgreSQL-SQL in `sql:`-Blöcken, keine nativen Liquibase-Changes. Eigene Objekte werden **nie** schemaqualifiziert (kein `public.`) — das Zielschema kommt aus `opaa.database.schema` (`OPAA_DB_SCHEMA`) über den `search_path` der Verbindung. Braucht SQL den Schemanamen zwingend, dann über `current_schema()` (z. B. `format('GRANT … ON SCHEMA %I …', current_schema())`) bzw. für den gepinnten `search_path` von Funktionen über `${database.defaultSchemaName}`. Tests fragen Kataloge über `current_schema()` ab, nie über das Literal `'public'`; `SchemaPortabilityMigrationTest` wendet den Master-Changelog in ein anderes Schema an.

**Ab der Baseline gilt wieder: ein Changeset pro Datenbankänderung**, jedes mit eigenem Delta-Test unter `backend/src/test/java/io/opaa/migration/` nach dem in `AbstractMigrationTest`/`package-info.java` beschriebenen Muster — die Fixture-Kette für Delta-Tests startet ab `backend/src/test/resources/db/changelog/test-master-through-baseline.yaml`. Vier Testklassen dieses Pakets prüfen die Baseline selbst statt einer Einzelmigration: `MigrationBaselineTest` (Tabellen, pgvector, Seed-Zeilen, Organisationsgrenzen-Regel, portierte Zustandsinvarianten), `AuditPrivilegeModelTest` und `DiagnosticContextPrivilegeModelTest` (die beiden Privilegienmodelle nach ADR-0015) sowie `VectorStoreExpressionIndexTest` (Precondition-Übersprung und Wiederholung der beiden `vector_store`-Changesets). Welche historischen Prüfungen bewusst entfallen sind, steht in den Beschreibungen von PR #906 und PR #1504.
