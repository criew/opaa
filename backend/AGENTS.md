# Backend-Anweisungen für KI-Agenten

Ergänzt die [Wurzel-AGENTS.md](../AGENTS.md) um das, was beim Bauen unter `backend/` und `opaa-api/` zu beachten
ist; Nachschlagewissen steht unter [docs/](docs/).

## Abhängigkeitsverwaltung

Jede Bibliotheks- und Plugin-Version steht in `backend/gradle/libs.versions.toml`, nie in `build.gradle.kts`;
Abhängigkeiten stehen dort in `[libraries]`, verwandte in `[bundles]` (`spring-boot`, `spring-ai`, `test-deps`), und
`build.gradle.kts` referenziert sie über `libs.*`, `libs.bundles.*` und `libs.versions.*`.

## Zeit

Services lesen die Zeit über die `Clock`-Bean aus `io.opaa.common.ClockConfiguration` (`Instant.now(clock)`), damit
Fristen testbar sind; Entities setzen ihre Zeitstempel (`createdAt` u. ä.) selbst im Konstruktor oder `@PrePersist`.

## Konfigurationsschlüssel

Jeder `opaa.*`-Schlüssel hat eine Beschreibung (`@param` der Record-Komponente, ohne `@ConfigurationProperties`-Typ
ein Eintrag in `src/main/resources/META-INF/additional-spring-configuration-metadata.json`) und trägt `@DataImpact`
oder `@NoDataImpact("Grund")` an der Komponente oder pauschal am Typ; Schalter, Modelle, Adressen und Fristen an
der Komponente. Ein datenwirksamer Schlüssel steht mit seinem Standardwert aus `application.yml` unter `settings`
im Fakten-Manifest. `ConfigurationSettingsTest` prüft das samt Helm-Chart und nennt das einzufügende YAML
([ADR-0052](../docs/decisions/0052-compliance-manifest.md), Entscheidung 8).

## API & DTO-Konvention (Fortsetzung)

Die Kernregeln (DTOs nie von Hand, Änderungen beginnen in der Spec) stehen in der Wurzel-`AGENTS.md`, die Begründung
in [ADR-0006](../docs/decisions/0006-openapi-dto-generation.md).

- Spec, Generator und geteilte Domain-Enums (`io.opaa.api.types`) leben im Gradle-Modul `opaa-api`; ein neues
  Domain-Enum braucht nur Einträge in `typeMappings` und `importMappings` in `opaa-api/build.gradle.kts`.
- `:opaa-api:bundleOpenApi` und `frontend/scripts/bundle-openapi.mjs` bündeln nach denselben Regeln; wer sie ändert,
  ändert beide.
- **Domain-Services kennen keine `io.opaa.api.dto`-Typen:** Sie nehmen und liefern Entities, Einzelparameter und
  Domain-Records; eine angereicherte Ansicht ist ein Domain-Record im Fachpaket (`SpaceOverview`).
- **Eine Operation deklariert nur die Statuscodes, die sie selbst entscheidet** (`429` nur bei eigener Grenze); was die
  Infrastruktur für alle entscheidet, steht in `info.description`; `TransportStatusCodeSpecificationTest` prüft es.

## Logische Module und Schichtung

Das Backend ist ein Gradle-Modul, gegliedert in logische Module. Die Definitionen stehen in
`io.opaa.architecture.ModularArchitecture`, was die Tests im Einzelnen prüfen, in deren Javadoc.

| Modul | Pakete (`io.opaa.*`) | darf nutzen (`ALLOWED_MODULE_EDGES`, alle nach unten) |
|---|---|---|
| foundation | common, compliance, observability, organization, outbound, security, ratelimit, sourceaccess, s3, msgraph | — |
| format | format | foundation |
| identity | audit, branding, mail, auth, account, notification | foundation |
| rights | permission, asset, group, directory, succession | foundation, identity |
| knowledge | knowledge, llm, metadata, indexing (ohne Konnektoren), health | foundation, format, identity, rights |
| connectors | jedes direkte Unterpaket von `indexing.source` | foundation, format, knowledge |
| connections | connection | foundation, identity, rights, knowledge |
| workspace | space, revision, diagnosticaccess | foundation, identity, rights, knowledge |
| library | library | foundation, format, identity, rights, knowledge, connections |
| retrieval | retrieval | foundation, format, knowledge |
| assistant | prompt, chat, query, search, searchadmin | foundation, format, identity, rights, knowledge, workspace, library, retrieval |
| external | externalaccess, mcp | foundation, identity, rights, knowledge, library, assistant |
| app | api, config, `OpaaApplication` | foundation, knowledge |

`ModularArchitectureTest` erzwingt Module, Kanten, Schichtung und Zyklenfreiheit, `SourceReferenceTest` dasselbe für
Importe und Javadoc-Verweise (ein Verweis nach oben steht als `{@code …}`). **Eine neue Kante ist eine Entscheidung**
und wird im PR begründet; vorher prüfen, ob eine Schnittstelle im unteren Modul reicht. Ein neues Top-Level-Paket
gehört in `LAYERS` (über alles, was es nutzt) und `MODULES`, ein Unterpaket von `indexing` in `INDEXING_CORE`, von
`connection` in `CONNECTION_PACKAGES`; Entfallenes wird ausgetragen. Feinere Regeln: `PermissionPackageBoundaryTest`,
`KnowledgeLibraryReachWriterTest` und die `*DependencyStructureTest`-Klassen einzelner Pakete.
Der Vertrag für Kanten, Querschnittsaspekte und akzeptierte Abweichungen steht in
[ADR-0051](../docs/decisions/0051-architekturvertrag-des-backends.md).

### Web-Schicht je Modul

- Controller, Response-Mapper und Web-Hilfen liegen im Unterpaket `web` eines Top-Level-Pakets (`io.opaa.space.web`),
  in `io.opaa.api` nur, was jedes Modul teilt (`SHARED_API_CLASSES`). Ein `web`-Paket unterliegt nur den Modulkanten.
- Ein Controller liegt im `web`-Paket seiner Ressource, braucht er ein höheres Modul, im höchsten beteiligten;
  braucht nur eine Unterressource das höhere Modul, bekommt sie dort einen eigenen Controller. Was die Web-Schicht
  zeigt, holt die Domäne über die Ports von `io.opaa.permission` in ihren Domain-Record.
- **Admin-only:** `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` am Controller; hat der Service weitere Aufrufer,
  stattdessen `CurrentUser#requireSystemAdmin(String)` im Service, ohne Service im Controller. Keine private Kopie.
- **Mapper:** handgeschrieben, package-private im Paket des Controllers (`SpaceResponseMapper`); `public` nur mit den
  Methoden, die ein anderes `web`-Paket aufruft; von mehreren Fachpaketen gebraucht, im tiefsten gemeinsamen. Prüfen
  Tests Entity-Ableitungen statt Response-Feldern, sichert ein Mapper-Unit-Test die Feldbelegung, im Paket des Mappers.

### Anweisungen je Modul

Jedes Modul hat im Hauptpaket unter `backend/src/main/java/io/opaa/` eine `AGENTS.md` (höchstens 60 Zeilen à 120
Zeichen, Link- und Tabellenzeilen ausgenommen): Zweck und Grenze, Invarianten, Stolpersteine, ADRs (je Zeile Link und
wenige Wörter), Verweise, zu laufende Tests. Daneben liegt `CLAUDE.md` mit `@AGENTS.md`, jedes weitere Top-Level-Paket
des Moduls importiert sie (`@../common/AGENTS.md`); andere Werkzeuge lesen die Datei vor der Arbeit im Modul selbst.
Modulübergreifendes (Modulgrenzen, Liquibase) steht hier, nicht in der Datei des Moduls.

| Modul | Datei | Modul | Datei |
|---|---|---|---|
| foundation | `common/AGENTS.md` | connectors | `indexing/source/AGENTS.md` |
| format | `format/AGENTS.md` | workspace | `space/AGENTS.md` |
| identity | `auth/AGENTS.md` | library | `library/AGENTS.md` |
| rights | `permission/AGENTS.md` | assistant | `query/AGENTS.md` |
| knowledge | `knowledge/AGENTS.md` | external | `externalaccess/AGENTS.md` |
| retrieval | `retrieval/AGENTS.md` | app | `api/AGENTS.md` |
| connections | `connection/AGENTS.md` | | |

## Prozesslokaler Zustand (ADR-0021)

OPAA läuft als genau ein Backend-Prozess je Installation
([ADR-0021](../docs/decisions/0021-single-instance-betrieb.md)). Wer eine neue prozesslokale Stelle einführt (Cache,
`@Scheduled`, Executor, Semaphore, Zähler, Start-Aktion), trägt sie in das Register
[docs/prozesslokaler-zustand.md](docs/prozesslokaler-zustand.md) ein und nennt „(ADR-0021)“ in der Javadoc.

## Scheduler

Der Default-`TaskScheduler` hinter `@EnableScheduling` hat einen Thread für alle `@Scheduled`-Methoden. Eine
`@Scheduled`-Methode entscheidet deshalb nur und reicht Arbeit, die an Netz, Objektspeicher, Dateisystem oder LLM
hängt, an einen eigenen Executor (`DirectorySyncScheduler` → `directorySyncExecutor`) oder per `scheduler = "…"` an
einen eigenen Scheduler weiter (`PrivateLibraryDeletionSchedule`, Probes der Modellrollen). Kurze DB-Läufe (einzelne
`DELETE`s, Partition-Drops, Batches) bleiben auf dem Tick.

## Spring-Testkontexte

Jeder Test, der einen Anwendungskontext startet, trägt genau eine Meta-Annotation aus `io.opaa.test`
(`@OpaaIntegrationTest` und ihre Varianten), nie eine eigene `@SpringBootTest`/`@ActiveProfiles`/`@Import`/
`@Testcontainers`-Kombination; einen Postgres-Container deklariert er nie selbst (ausgenommen `io.opaa.migration` und
`@WebMvcTest`-Slices). **Ein Kontext mehr braucht eine harte technische Begründung**, keine fachliche Zugehörigkeit,
und wird in `SpringContextSignatureTest` eingetragen. Klassenlokale Konfiguration spaltet den Kontext; sie gehört:

- Konstanter Wert → `properties` der Meta-Annotation.
- Zur Laufzeit gelesener Wert → `ApplicationContextInitializer` der Meta-Annotation, nie `@DynamicPropertySource`.
- Ersetzte oder ergänzte Bean (`@MockitoBean`, `@MockitoSpyBean`, `@TestBean`, `@TestConfiguration`, `@Import`) →
  `OpaaTestBeans` bzw. Spy-Liste der Meta-Annotation, lieber Spy als Mock, geteilte Fakes je Testmethode zurückgesetzt.
- Verzeichnis, dessen Pfad in eine Property fließt → `OpaaTestDirectory.subdirectory(name)`, kein `@TempDir`.

**Eine Datenbank für die ganze Suite.** Eine Klasse räumt in `@AfterEach` auf (zusätzlich in `@BeforeEach` ist
richtig, nur dort nicht), fasst nur Zeilen ihrer eigenen IDs an und prüft nie gegen eine ungefilterte Tabelle;
kein pauschales `deleteAll()`. Signaturen, Begründungen und Wächter: [docs/testkontexte.md](docs/testkontexte.md).

## Liquibase

Herleitung und Prüfungen: [docs/liquibase.md](docs/liquibase.md).

- Ein Changeset je Datenbankänderung, als neue Datei `db/changelog/<modul>/JJJJ-MM-TT-<thema>.yaml` im Verzeichnis
  des Moduls, dem die Tabelle gehört; ID `<modul>-<dateistamm>` (weitere der Datei mit `--<suffix>`), Autor `opaa`,
  der Master bleibt unverändert. Eine neue Tabelle kommt in `ChangelogModuleBoundaryTest.TABLE_MODULES`.
- Reines PostgreSQL-SQL in `sql:`-Blöcken, eigene Objekte nie schemaqualifiziert (kein `public.`), ein nötiger
  Schemaname über `current_schema()` oder `${database.defaultSchemaName}` (ADR-0034).
- Nur Tabellen des eigenen Moduls; Fremdschlüssel, Trigger und Funktionen reichen nur in Module, von denen es nach
  `ALLOWED_MODULE_EDGES` abhängen darf.
- Korrekt in beiden Reihenfolgen: bei einer Neuinstallation und angehängt an eine bestehende Installation.
- Rein additive DDL (neue Tabelle, nullable Spalte ohne Default und `GENERATED`, nicht eindeutiger Index, Constraint
  auf neuer Tabelle) kommt ohne eigenen Test aus; alles andere braucht einen Delta-Test unter `io.opaa.migration`
  (`AbstractMigrationTest`). Im Zweifel ist es nicht additiv.
