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
- Die Spec-Fragmente unter `opaa-api/src/main/openapi/` bündelt der Task `:opaa-api:bundleOpenApi` (`io.opaa.api.bundler.OpenApiBundler`, eigenes Source-Set `bundler`). Tests lesen das Bündel wie bisher als Klassenpfad-Ressource `/openapi/opaa-api.yaml`. Das Frontend bündelt dieselben Fragmente mit `frontend/scripts/bundle-openapi.mjs` nach denselben Regeln. Wer die Regeln ändert, ändert beide Bundler (#2002)
- Domain-Enums in DTOs (z. B. `SpaceRole`, `AssetRole`) werden über `typeMappings`/`importMappings` in `opaa-api/build.gradle.kts` gemappt
- Beim Hinzufügen neuer Domain-Enums zur API genügen Einträge in `typeMappings` und `importMappings`; der `doLast`-Cleanup-Block im `openApiGenerate`-Task leitet die zu löschenden generierten Dateien mechanisch aus `typeMappings` ab
- **Domain-Services kennen keine `io.opaa.api.dto`-Typen** (#860): Service-Methoden nehmen Entities, Einzelparameter oder kleine Domain-Parameter-Records entgegen und geben Entities oder Domain-Records zurück. Für angereicherte Ansichten (Response ≠ Entity, z. B. mit einer zusätzlichen Zählung) trägt ein Domain-Record im jeweiligen Fachpaket die zusätzlichen Felder (z. B. `SpaceOverview(space, libraryCount, chatCount)`). Wo die Mapper liegen und wie sie getestet werden, steht in der Datei des Moduls app (`io/opaa/api/AGENTS.md`)
- **Eine Operation deklariert, was sie selbst entscheidet** (#1781). Was die Infrastruktur für alle Operationen gleich entscheidet — `400` bei unlesbarer Anfrage, `401` ohne brauchbare Sitzung, `403` bei erzwungenem Passwortwechsel oder einem Zugangstoken außerhalb seines Kanals, `404` bei unbekannter Route, `405`, `406`, `415`, `500` — steht einmal in `info.description` der Spezifikation und **in dieser Ausprägung** an keiner Operation. Eigene Entscheidungen bleiben deklariert: die Prüfung des eigenen Nutzinhalts (`400`), die eigene Rechteprüfung (`403`), die eigene Ressource (`404`), der eigene Konflikt (`409`), die eigene Größengrenze (`413`), die eigene Abhängigkeit (`503`) — und `401` dort, wo die Operation selbst eine ihr übergebene Berechtigung abweist (Anmeldung, Refresh-Cookie, Webhook-Signatur, Anbieter-Token der Übergabe). `429` steht an genau den Operationen mit einer eigenen Grenze; die Ratenbegrenzung ist endpunktweise, nicht global. `TransportStatusCodeSpecificationTest` hält beide Hälften maschinell fest und leitet die `429`-Erwartung aus der produktiven Verdrahtung von `RateLimitConfiguration` ab

> Vollständige Begründung: [ADR-0006](../docs/decisions/0006-openapi-dto-generation.md)

## Logische Module und Schichtung

Das Backend ist ein Gradle-Modul, aber in logische Module gegliedert (Epic #1906, #2000).
`io.opaa.architecture.ModularArchitectureTest` erzwingt sie mit ArchUnit auf den kompilierten
Hauptklassen, ohne Spring-Kontext, als Teil von `./gradlew test`. Die Definitionen stehen in
`ModularArchitecture` daneben, die Negativfälle belegt `ModularArchitectureFixtureTest`.

| Modul | Pakete (`io.opaa.*`) |
|---|---|
| foundation | common, observability, organization, notification, security, ratelimit, sourceaccess, s3 |
| identity | audit, branding, mail, auth |
| rights | permission, asset, group, succession |
| knowledge | knowledge, llm, indexing (ohne Konnektoren) |
| connectors | jedes direkte Unterpaket von `indexing.source` |
| workspace | space, revision, diagnosticaccess |
| library | library |
| assistant | prompt, chat, query, search, searchadmin, health |
| external | externalaccess, mcp |
| app | api, config, `OpaaApplication` |

Erlaubte Kanten zwischen Modulen (`ALLOWED_MODULE_EDGES`), alle nach unten:

- identity → foundation
- rights → foundation, identity
- knowledge → foundation, identity, rights
- connectors → foundation, knowledge
- workspace, library → foundation, identity, rights, knowledge
- assistant → foundation, identity, rights, knowledge, workspace, library
- external → foundation, identity, rights, knowledge, library, assistant
- app → alle außer connectors

Der Test prüft außerdem:

- **Schichtung:** `LAYERS` ordnet alle Top-Level-Pakete, unten zuerst. Ein Paket nutzt nur sich
  selbst und Pakete davor.
- **Zyklen:** keine zwischen Top-Level-Paketen. Zwischen Unterpaketen sind die heutigen Zyklen als
  Paketkanten in `KNOWN_SUBPACKAGE_CYCLE_EDGES` eingefroren, vor allem in `indexing` und `query`.
  Jede weitere Kante auf einem Zyklus lässt den Test fehlschlagen, also jeder neue Zyklus.
- **Konnektoren:** Kein Konnektor kennt einen anderen, und keine Klasse außerhalb eines Konnektors
  kennt ihn. Kern, Verwaltung und API erreichen Konnektoren nur über die `SourceConnectorRegistry`.
- Die Pakete des Gradle-Moduls `opaa-api` (`io.opaa.api.dto`, `io.opaa.api.types`) liegen
  außerhalb der Schichtung.
- **Blinder Fleck:** Der Test sieht nur den Bytecode. Liest ein Paket von einem anderen nur eine
  `static final`-Konstante, die der Compiler einfaltet, ist das für ihn keine Kante. Dasselbe gilt
  für Verweise, die nur im Javadoc stehen.

**Neues Top-Level-Paket:** in `LAYERS` oberhalb aller Pakete eintragen, die es nutzt, und in
`MODULES` einem Modul zuordnen. Fehlt der Eintrag, nennt der Test das Paket und beide Stellen.
Umgekehrt schlägt er fehl, wenn ein gelistetes Paket verschwindet, eine erlaubte Modulkante nicht
mehr genutzt wird oder eine eingefrorene Zykluskante auf keinem Zyklus mehr liegt. Dann wird der
Eintrag entfernt.

**Eine neue Kante zwischen Modulen ist eine bewusste Entscheidung** und wird im PR begründet. Den
Eintrag nicht nur ergänzen, damit der Test grün wird. Zuerst prüfen, ob eine Schnittstelle im
unteren Modul reicht, die das obere implementiert.

Feinere Regeln als die Paketebene prüfen eigene Strukturtests: `PermissionPackageBoundaryTest`
(Kanten zwischen Fachpaketen, die die Schichtung zuließe), `KnowledgeLibraryReachWriterTest`
(Methodenebene) und die `*DependencyStructureTest`-Klassen einzelner Pakete (Unterpakete,
`opaa-api`-Typen).

### Anweisungen je Modul

Jedes Modul hat eine kurze `AGENTS.md` (höchstens 60 Zeilen) mit Zweck und Grenze, Invarianten und
Stolpersteinen, Verweisen und den Tests, die bei Änderungen laufen sollten. Sie liegt im
Hauptpaket unter `backend/src/main/java/io/opaa/`:

| Modul | Datei | Modul | Datei |
|---|---|---|---|
| foundation | `common/AGENTS.md` | workspace | `space/AGENTS.md` |
| identity | `auth/AGENTS.md` | library | `library/AGENTS.md` |
| rights | `permission/AGENTS.md` | assistant | `query/AGENTS.md` |
| knowledge | `knowledge/AGENTS.md` | external | `externalaccess/AGENTS.md` |
| connectors | `indexing/source/AGENTS.md` | app | `api/AGENTS.md` |

Daneben liegt eine `CLAUDE.md` mit `@AGENTS.md`; jedes weitere Top-Level-Paket des Moduls hat eine
`CLAUDE.md`, die die Datei des Moduls importiert (`@../common/AGENTS.md`). Claude Code lädt eine
`CLAUDE.md` in einem Unterverzeichnis, sobald dort eine Datei gelesen wird. Andere Werkzeuge lesen
die Datei des Moduls vor der Arbeit im Modul selbst. `ModuleAgentsFileTest` hält Ablage, Import und
Länge fest; ein neues Top-Level-Paket bekommt eine solche `CLAUDE.md`. Modulspezifisches gehört in
diese Dateien, hier bleibt nur, was modulübergreifend gilt.

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

Fünf weitere tragen den Betriebsmodus **`oidc`**, den die vier oben nicht liefern können, weil unter
`local,dev` keine lokale Sitzung fahrbar ist (ADR-0033, #1543): `@OpaaLocalAuthMockMvcTest` als
Basis, dazu `@OpaaLocalAuthLinkTest`, `@OpaaLocalAuthSeedTest`, `@OpaaLocalAuthRateLimitTest` und
`@OpaaLocalAuthProviderTest`. Was jede von ihnen abweichend trägt und warum, steht in der Datei des
Moduls identity (`io/opaa/auth/AGENTS.md`).

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
Klasse repariert. `LeftoverRowGuard` zählt `documents`, `knowledge_libraries`, `assets`, `vector_store`,
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

## Liquibase-Baseline (Stand 09/2026, #2001)

Die Liquibase-Historie wurde dreimal zu einer Baseline zusammengefasst: 08/2026 (#904/PR #906), 09/2026 (#1492/PR #1504) und Ende 09/2026 die Baseline samt den Changesets 002–092 (#2001/PR #2007). Jedes Mal ein bewusster Einmalvorgang vor Produktionsbetrieb: jede laufende Installation muss danach neu aufgesetzt werden, weil `DATABASECHANGELOG` nicht mehr passt (Handbuch, `deployment.md`, „Migrationen aus älteren Ständen").

Ergebnis ist die eine Datei `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` mit **einem Changeset je logischem Modul** (siehe „Logische Module und Schichtung"), in Abhängigkeitsreihenfolge: `001-baseline-foundation`, `-identity`, `-rights`, `-knowledge`, `-connectors`, `-workspace`, `-assistant`, `-external`. Das Modul `library` besitzt keine Tabelle und damit kein Changeset. Eine Tabelle steht im Changeset des Moduls, dessen Paket ihre Entity trägt — mit einer Ausnahme: `notifications` (Paket `notification`, foundation) verweist auf `users` und steht deshalb im identity-Changeset. Dazu kommen zwei bewusst eigenständige, precondition-geschützte Changesets für die `vector_store`-Ausdrucksindexe (`001-baseline-knowledge-vector-store-library-id`, `…-document-id`) — die dürfen nicht in ein Modul-Changeset gefaltet werden, sonst überspringt die Precondition beim ersten Start das ganze Modul. `db.changelog-master.yaml` referenziert nur noch diese Datei; Rollback-Blöcke hat die Baseline bewusst keine. Ein Changeset referenziert nur Tabellen seines eigenen oder eines früheren Moduls.

**Changeset-Stil (ADR-0034, #1366):** Changesets sind reines PostgreSQL-SQL in `sql:`-Blöcken, keine nativen Liquibase-Changes. Eigene Objekte werden **nie** schemaqualifiziert (kein `public.`) — das Zielschema kommt aus `opaa.database.schema` (`OPAA_DB_SCHEMA`) über den `search_path` der Verbindung. Braucht SQL den Schemanamen zwingend, dann über `current_schema()` (z. B. `format('GRANT … ON SCHEMA %I …', current_schema())`) bzw. für den gepinnten `search_path` von Funktionen über `${database.defaultSchemaName}`. Tests fragen Kataloge über `current_schema()` ab, nie über das Literal `'public'`; `SchemaPortabilityMigrationTest` wendet den Master-Changelog in ein anderes Schema an.

**Ab der Baseline gilt wieder: ein Changeset pro Datenbankänderung**, jedes mit eigenem Delta-Test unter `backend/src/test/java/io/opaa/migration/` nach dem in `AbstractMigrationTest`/`package-info.java` beschriebenen Muster — die Fixture-Kette für Delta-Tests startet ab `backend/src/test/resources/db/changelog/test-master-through-baseline.yaml`. Die übrigen Klassen des Pakets prüfen die Baseline selbst: `MigrationBaselineTest` das Modulübergreifende (Changeset-Reihenfolge, Tabelleninventar, pgvector, Seed-Zeilen, Partitionierung, Organisationsgrenzen-Regel), je eine Unterklasse von `AbstractBaselineTest` die Zustandsinvarianten eines Moduls (`IdentityBaselineTest`, `RightsBaselineTest`, `KnowledgeBaselineTest`, `ConnectorsBaselineTest`, `WorkspaceBaselineTest`, `AssistantBaselineTest`, `ExternalBaselineTest`), dazu `AuditPrivilegeModelTest` und `DiagnosticContextPrivilegeModelTest` (Privilegienmodelle nach ADR-0015), `ProtocolRetentionDeletionTest` (Löschlauf beider Protokolle) und `VectorStoreExpressionIndexTest` (Precondition-Übersprung und Wiederholung). Welche historischen Prüfungen bewusst entfallen sind, steht in den Beschreibungen von PR #906, PR #1504 und PR #2007.
