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

## Zeit

Services lesen die aktuelle Zeit über die `Clock`-Bean aus `io.opaa.common.ClockConfiguration`
(`Instant.now(clock)`), damit Ablauf, Fälligkeit und Fristen mit fester Clock testbar sind; Entities
setzen ihre Zeitstempel (`createdAt` u. ä.) selbst im Konstruktor oder in `@PrePersist`.

## API & DTO-Konvention (Fortsetzung)

Die beiden Kernregeln — DTOs nie von Hand, Änderungen beginnen in der Spec — stehen in der Wurzel-`AGENTS.md`.

- Spec, Generator-Konfiguration und die geteilten Domain-Enums, auf die `typeMappings` zeigt, leben im eigenen Gradle-Modul `opaa-api` (`io.opaa.api.types`); das Backend konsumiert sie über `implementation(project(":opaa-api"))` (#896)
- Die Spec-Fragmente unter `opaa-api/src/main/openapi/` bündelt der Task `:opaa-api:bundleOpenApi` (`io.opaa.api.bundler.OpenApiBundler`, eigenes Source-Set `bundler`). Tests lesen das Bündel wie bisher als Klassenpfad-Ressource `/openapi/opaa-api.yaml`. Das Frontend bündelt dieselben Fragmente mit `frontend/scripts/bundle-openapi.mjs` nach denselben Regeln. Wer die Regeln ändert, ändert beide Bundler (#2002)
- Domain-Enums in DTOs (z. B. `SpaceRole`, `AssetRole`) werden über `typeMappings`/`importMappings` in `opaa-api/build.gradle.kts` gemappt
- Beim Hinzufügen neuer Domain-Enums zur API genügen Einträge in `typeMappings` und `importMappings`; der `doLast`-Cleanup-Block im `openApiGenerate`-Task leitet die zu löschenden generierten Dateien mechanisch aus `typeMappings` ab
- **Domain-Services kennen keine `io.opaa.api.dto`-Typen** (#860): Service-Methoden nehmen Entities, Einzelparameter oder kleine Domain-Parameter-Records entgegen und geben Entities oder Domain-Records zurück. Für angereicherte Ansichten (Response ≠ Entity, z. B. mit einer zusätzlichen Zählung) trägt ein Domain-Record im jeweiligen Fachpaket die zusätzlichen Felder (z. B. `SpaceOverview(space, libraryCount, chatCount)`). Wo Controller und Mapper liegen und wie die Mapper getestet werden, steht unter „Web-Schicht je Modul“
- **Eine Operation deklariert, was sie selbst entscheidet** (#1781). Was die Infrastruktur für alle Operationen gleich entscheidet — `400` bei unlesbarer Anfrage, `401` ohne brauchbare Sitzung, `403` bei erzwungenem Passwortwechsel oder einem Zugangstoken außerhalb seines Kanals, `404` bei unbekannter Route, `405`, `406`, `415`, `500` — steht einmal in `info.description` der Spezifikation und **in dieser Ausprägung** an keiner Operation. Eigene Entscheidungen bleiben deklariert: die Prüfung des eigenen Nutzinhalts (`400`), die eigene Rechteprüfung (`403`), die eigene Ressource (`404`), der eigene Konflikt (`409`), die eigene Größengrenze (`413`), die eigene Abhängigkeit (`503`) — und `401` dort, wo die Operation selbst eine ihr übergebene Berechtigung abweist (Anmeldung, Refresh-Cookie, Webhook-Signatur, Anbieter-Token der Übergabe). `429` steht an genau den Operationen mit einer eigenen Grenze; die Ratenbegrenzung ist endpunktweise, nicht global. `TransportStatusCodeSpecificationTest` hält beide Hälften maschinell fest und leitet die `429`-Erwartung aus der produktiven Verdrahtung von `RateLimitConfiguration` ab

> Vollständige Begründung: [ADR-0006](../docs/decisions/0006-openapi-dto-generation.md)

## Logische Module und Schichtung

Das Backend ist ein Gradle-Modul, aber in logische Module gegliedert (Epic #1906, #2000).
`io.opaa.architecture.ModularArchitectureTest` erzwingt sie mit ArchUnit auf den kompilierten
Hauptklassen, ohne Spring-Kontext, als Teil von `./gradlew test`. Die Definitionen stehen in
`ModularArchitecture` daneben, die Negativfälle belegt `ModularArchitectureFixtureTest`.

| Modul | Pakete (`io.opaa.*`) |
|---|---|
| foundation | common, observability, organization, outbound, security, ratelimit, sourceaccess, s3, msgraph |
| format | format |
| identity | audit, branding, mail, auth, account, notification |
| rights | permission, asset, group, directory, succession |
| knowledge | knowledge, llm, metadata, indexing (ohne Konnektoren), health |
| connectors | jedes direkte Unterpaket von `indexing.source` |
| connections | connection |
| workspace | space, revision, diagnosticaccess |
| library | library |
| retrieval | retrieval |
| assistant | prompt, chat, query, search, searchadmin |
| external | externalaccess, mcp |
| app | api, config, `OpaaApplication` |

Erlaubte Kanten zwischen Modulen (`ALLOWED_MODULE_EDGES`), alle nach unten:

- format → foundation
- identity → foundation
- rights → foundation, identity
- knowledge → foundation, format, identity, rights
- connectors → foundation, format, knowledge
- connections → foundation, identity, rights, knowledge
- workspace → foundation, identity, rights, knowledge
- library → foundation, format, identity, rights, knowledge, connections
- retrieval → foundation, format, knowledge
- assistant → foundation, format, identity, rights, knowledge, workspace, library, retrieval
- external → foundation, identity, rights, knowledge, library, assistant
- app → foundation, knowledge

format liegt direkt über foundation und kennt nur `sourceaccess`: Die Dokumentparser und ihre
Bibliotheken bleiben so ohne Wissen über Indexierung, Bestand und Rechte. Wer darüber Formate
nutzt, zeigt nach unten; was format von oben braucht (Chunkgröße), kommt als Schnittstelle
(`ChunkSizing`).

retrieval (die Pipeline von der Frage zu den Chunks) liegt über knowledge und kennt weder Rechte
noch Räume noch Chats: Den Suchbereich bekommt sie fertig aufgelöst. Antwort, Suche als Dienst und
Diagnose im Modul assistant setzen darauf auf.

Der Test prüft außerdem:

- **Schichtung:** `LAYERS` ordnet alle Top-Level-Pakete, unten zuerst. Ein Paket nutzt nur sich
  selbst und Pakete davor. Ausgenommen ist die Web-Schicht (siehe „Web-Schicht je Modul“).
- **Zyklen:** keine zwischen Top-Level-Paketen, ein `web`-Paket zählt dabei für sich, und keine
  zwischen den Unterpaketen eines Top-Level-Pakets.
- **Kern von `indexing`:** `INDEXING_CORE` ordnet dessen Unterpakete, unten zuerst (Vertrag `spi`,
  dann `credentials`, `registry`, `run`); ein Paket nutzt nur sich und die davor, nie das Wurzelpaket,
  `indexing.web` oder einen Konnektor. Jedes weitere Unterpaket von `indexing` gehört in diese Liste,
  sonst schlägt der Test fehl.
- **Unterpakete von `connection`:** `CONNECTION_PACKAGES` ordnet sie ebenso; keines nennt das
  Wurzelpaket oder `connection.web`, und ein nicht eingetragenes schlägt fehl (ADR-0041).
- **Konnektor-Freigabe:** `CREATE_CONNECTOR_LIBRARY` und die Methoden von `CapabilityService` mit
  Geltungsbereich nennen nur rights und connections (ADR-0041, Entscheidung 8).
- **Konnektoren:** Kein Konnektor kennt einen anderen, und keine Klasse außerhalb eines Konnektors
  kennt ihn. Kern, Verwaltung und API erreichen Konnektoren nur über die `SourceConnectorRegistry`.
  Ein Konnektor und der Datei-Abgleich nutzen aus `indexing` nur `CONNECTOR_IMPORTS`, also nie
  `credentials` (Resolver, Dienstkonto-Schlüssel; ADR-0040, Entscheidung 2) oder `registry`.
- Die Pakete des Gradle-Moduls `opaa-api` (`io.opaa.api.dto`, `io.opaa.api.types`) liegen
  außerhalb der Schichtung.
- **Namen in den Listen:** `everyNamedCodeUnitExists` löst jeden Eintrag der Form `paket.Klasse`,
  `paket.Äußere$Innere` oder `paket.Klasse#methode` aus allen Konstanten von `ModularArchitecture`
  auf; ein Eintrag, der nach einer Umbenennung ins Leere zeigt, lässt den Test fehlschlagen, statt
  dass seine Regel still nichts mehr prüft.
- **Blinder Fleck:** Der Test sieht nur den Bytecode. Was dort fehlt, prüft `SourceReferenceTest`
  im Quelltext gegen `LAYERS` und `ALLOWED_MODULE_EDGES`: jeden Import, auch `import static`, und
  jedes `{@link io.opaa.…}`, `{@linkplain …}`, `{@value …}`, `@see`, `@throws` und `@exception`
  auf `io.opaa.…`. Ein Javadoc-Verweis nach oben steht deshalb als `{@code …}`. Ungeprüft bleiben
  eine einfaltbare Konstante, die der Code vollqualifiziert ohne Import liest, und Parametertypen
  in `{@link X#m(io.opaa.…)}`.

**Neues Top-Level-Paket:** in `LAYERS` oberhalb aller Pakete eintragen, die es nutzt, und in
`MODULES` einem Modul zuordnen. Fehlt der Eintrag, nennt der Test das Paket und beide Stellen.
Umgekehrt schlägt er fehl, wenn ein gelistetes Paket verschwindet oder eine erlaubte Modulkante
nicht mehr genutzt wird. Dann wird der Eintrag entfernt.

**Eine neue Kante zwischen Modulen ist eine bewusste Entscheidung** und wird im PR begründet. Den
Eintrag nicht nur ergänzen, damit der Test grün wird. Zuerst prüfen, ob eine Schnittstelle im
unteren Modul reicht, die das obere implementiert.

Feinere Regeln als die Paketebene prüfen eigene Strukturtests: `PermissionPackageBoundaryTest`
(Kanten zwischen Fachpaketen, die die Schichtung zuließe), `KnowledgeLibraryReachWriterTest`
(Methodenebene) und die `*DependencyStructureTest`-Klassen einzelner Pakete (Unterpakete,
`opaa-api`-Typen).

### Web-Schicht je Modul

Controller, ihre Mapper zwischen Domäne und generierten DTOs und ihre Web-Hilfen liegen im
Unterpaket `web` eines Top-Level-Pakets (`io.opaa.space.web`, `io.opaa.auth.web`). Jedes
Top-Level-Paket hat höchstens eines; ein tieferes Paket dieses Namens, etwa der Konnektor
`indexing.source.web`, ist keines. In `io.opaa.api` bleibt nur, was jedes Modul teilt
(`ModularArchitecture.SHARED_API_CLASSES`): Fehlerbehandlung, Request-Logging, HTTP-Client und
`HealthController`.

- **Zuordnung:** Ein Controller liegt im `web`-Paket seiner Ressource. Braucht er ein höheres Modul,
  liegt er im höchsten beteiligten, im Paket, dessen Dienste er dort nutzt (`MeController` in
  `group.web`). Braucht nur eine Unterressource das höhere Modul, bekommt sie einen eigenen
  Controller dort, und der Rest bleibt bei der Ressource: `AssetSpaceController` in `space.web`
  neben `AssetController` in `asset.web`, `PointInTimeAccessController` in `revision.web` neben
  `AuditController` in `audit.web`.
- **Schichtung:** Ein `web`-Paket steht über allen Paketen, die sein Modul erreicht. Es unterliegt
  deshalb nicht `LAYERS`, nur `ALLOWED_MODULE_EDGES`: `branding.web` darf `auth` nutzen, obwohl
  `branding` darunter liegt. Umgekehrt nutzt kein Paket außerhalb der Web-Schicht und des Moduls app
  ein `web`-Paket, und zwischen `web`-Paketen gibt es keinen Zyklus. `PermissionPackageBoundaryTest`
  gilt für ein `web`-Paket wie für sein Fachpaket: Was die Web-Schicht eines Fachpakets zeigt, holt
  die Domäne über die Ports von `io.opaa.permission` in ihren Domain-Record.
- **Admin-only-Endpunkte:** Die Prüfung auf Systemverwaltung steht entweder als
  `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` am Controller oder, wenn der Service auch von anderen
  Aufrufern erreichbar ist, im Service per `CurrentUser#requireSystemAdmin(String)` — dann ohne
  `@PreAuthorize`, damit eine Stelle für jeden Aufrufer entscheidet (`PermissionHistoryRetentionController`);
  steht kein Service dahinter, ruft der Controller die Methode selbst (`ClientAddressDiagnosticsController`).
  Keine private Kopie der Prüfung.
- **Mapper:** Die Abbildung Entity → Response lebt in einer package-private, handgeschriebenen
  Mapper-Klasse im Paket des Controllers (Vorbild `BrandingResponseMapper`, `SpaceResponseMapper`).
  Braucht ein anderes `web`-Paket sie, wird sie `public`, und nur mit den Methoden, die es aufruft
  (`PermissionTransferResponseMapper#toResponse`). Ein Mapper, den mehrere Fachpakete brauchen, liegt
  im tiefsten `web`-Paket, das alle erreichen dürfen (`permission.web.SuccessionStateResponseMapper`).
- **Mapper-Tests:** Werden Test-Assertions von Response-Feldern auf Entity-Ableitungen umgestellt,
  sichert ein Mapper-Unit-Test die Feldbelegung (`SpaceResponseMapperTest`,
  `SpaceAssetAssociationResponseMapperTest`). Sonst prüft kein Test mehr, dass der Mapper jedes Feld
  befüllt. Tests eines Controllers oder Mappers liegen in dessen Paket.
- `ModularArchitectureTest` hält das fest: Klassen mit `@Controller` oder `@RestController` und
  `*ResponseMapper` liegen nur in einem `web`-Paket oder in `io.opaa.api`, und `io.opaa.api` hält nur
  die Klassen aus `SHARED_API_CLASSES`.

### Anweisungen je Modul

Jedes Modul hat eine kurze `AGENTS.md` (höchstens 60 Zeilen à 120 Zeichen; Zeilen mit Markdown-Link
und Tabellenzeilen ausgenommen) mit Zweck und Grenze, Invarianten und Stolpersteinen, den
maßgeblichen ADRs (je Zeile ein Link und wenige Wörter, was er für das Modul festlegt), Verweisen
und den Tests, die bei Änderungen laufen sollten. Sie liegt im Hauptpaket unter `backend/src/main/java/io/opaa/`:

| Modul | Datei | Modul | Datei |
|---|---|---|---|
| foundation | `common/AGENTS.md` | connectors | `indexing/source/AGENTS.md` |
| format | `format/AGENTS.md` | workspace | `space/AGENTS.md` |
| identity | `auth/AGENTS.md` | library | `library/AGENTS.md` |
| rights | `permission/AGENTS.md` | assistant | `query/AGENTS.md` |
| knowledge | `knowledge/AGENTS.md` | external | `externalaccess/AGENTS.md` |
| retrieval | `retrieval/AGENTS.md` | app | `api/AGENTS.md` |
| connections | `connection/AGENTS.md` | | |

Daneben liegt eine `CLAUDE.md` mit `@AGENTS.md`; jedes weitere Top-Level-Paket des Moduls hat eine
`CLAUDE.md`, die die Datei des Moduls importiert (`@../common/AGENTS.md`). Claude Code lädt eine
`CLAUDE.md` in einem Unterverzeichnis, sobald dort eine Datei gelesen wird. Andere Werkzeuge lesen
die Datei des Moduls vor der Arbeit im Modul selbst. `ModuleAgentsFileTest` hält Ablage, Import,
Länge und ADR-Verweise fest (jede genannte Nummer und jeder Link hat eine Datei unter
`docs/decisions/`); ein neues Top-Level-Paket bekommt eine solche `CLAUDE.md`. Modulspezifisches
gehört in diese Dateien, hier bleibt nur, was modulübergreifend gilt. Für jedes Modul gilt außerdem:
Eine Schemaänderung ist eine neue Datei im Verzeichnis des Moduls unter `db/changelog/` (siehe
„Liquibase: Changelog je Modul“), und was die Module trennt, steht oben in „Logische Module und
Schichtung“, nicht noch einmal in der Datei des Moduls.

## Prozesslokaler Zustand (ADR-0021)

OPAA läuft als genau ein Backend-Prozess je Installation
([ADR-0021](../docs/decisions/0021-single-instance-betrieb.md)). Wer eine neue prozesslokale Stelle
einführt (Cache, `@Scheduled`, Executor, Semaphore, Zähler, Start-Aktion), trägt sie in das Register
ein und nennt „(ADR-0021)“ in der Javadoc. Das Register: [docs/prozesslokaler-zustand.md](docs/prozesslokaler-zustand.md).

## Scheduler

Der Default-`TaskScheduler` hinter `@EnableScheduling` hat einen Thread für alle `@Scheduled`-Methoden.
Eine `@Scheduled`-Methode entscheidet deshalb nur und reicht Arbeit, die an Netz, Objektspeicher,
Dateisystem oder LLM hängt, an einen eigenen Executor (`DirectorySyncScheduler` → `directorySyncExecutor`)
oder per `scheduler = "…"` an einen eigenen Scheduler weiter (`PrivateLibraryDeletionSchedule`, Probes
der Modellrollen). Kurze DB-Läufe (einzelne `DELETE`s, Partition-Drops, Batches) bleiben auf dem Tick.

## Spring-Testkontexte

Jeder Test, der einen Anwendungskontext startet, trägt genau eine Meta-Annotation aus `io.opaa.test`
(`@OpaaIntegrationTest` und ihre Varianten), nie eine eigene
`@SpringBootTest`/`@ActiveProfiles`/`@Import`/`@Testcontainers`-Kombination; einen Postgres-Container
deklariert er nie selbst (ausgenommen `io.opaa.migration` und `@WebMvcTest`-Slices). Klassenlokale
Konfiguration spaltet den Kontext und kostet einen Container; sie gehört stattdessen:

- Konstanter Wert → `properties` der Meta-Annotation.
- Zur Laufzeit gelesener Wert → `ApplicationContextInitializer` der Meta-Annotation, nie eine
  klassenlokale `@DynamicPropertySource`.
- Ersetzte oder ergänzte Bean (`@MockitoBean`, `@MockitoSpyBean`, `@TestBean`, innere
  `@TestConfiguration`, klassenlokales `@Import`) → `OpaaTestBeans` bzw. die Spy-Liste der
  Meta-Annotation, lieber Spy als Mock; ein geteilter Fake wird je Testmethode zurückgesetzt.
- Verzeichnis, dessen Pfad in eine Property fließt → `OpaaTestDirectory.subdirectory(name)`, kein `@TempDir`.

**Ein Kontext mehr braucht eine harte technische Begründung**, keine fachliche Zugehörigkeit, und
wird in `SpringContextSignatureTest` eingetragen.

**Eine Datenbank für die ganze Suite.** Eine Klasse räumt in `@AfterEach` hinter sich auf (zusätzlich
in `@BeforeEach` ist richtig, nur dort nicht), fasst nur Zeilen ihrer eigenen IDs an und prüft nie
gegen eine ungefilterte Tabelle; kein pauschales `deleteAll()`. Signaturen, Begründungen und Wächter:
[docs/testkontexte.md](docs/testkontexte.md).

## Liquibase

Ein Changeset je Datenbankänderung, als neue Datei `<modul>/JJJJ-MM-TT-<thema>.yaml` im Verzeichnis
des Moduls unter `src/main/resources/db/changelog/`, dem die Tabelle gehört; der Master bleibt
unverändert. Herleitung und Prüfungen: [docs/liquibase.md](docs/liquibase.md).

- ID `<modul>-<dateistamm>`, weitere Changesets der Datei hängen `--<suffix>` an; Autor `opaa`. Eine
  neue Tabelle kommt in `ChangelogModuleBoundaryTest.TABLE_MODULES`.
- Reines PostgreSQL-SQL in `sql:`-Blöcken, eigene Objekte nie schemaqualifiziert (kein `public.`),
  ein nötiger Schemaname über `current_schema()` oder `${database.defaultSchemaName}` (ADR-0034).
- Nur Tabellen des eigenen Moduls; Fremdschlüssel, Trigger und Funktionen reichen nur in Module,
  von denen es nach `ALLOWED_MODULE_EDGES` abhängen darf.
- Korrekt in beiden Reihenfolgen: bei einer Neuinstallation und angehängt an eine bestehende.
- Rein additive DDL (neue Tabelle, nullable Spalte ohne Default und `GENERATED`, nicht eindeutiger
  Index, Constraint auf neuer Tabelle) kommt ohne eigenen Test aus; alles andere braucht einen
  Delta-Test unter `io.opaa.migration` (`AbstractMigrationTest`). Im Zweifel ist es nicht additiv.
