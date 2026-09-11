# Anweisungen für KI-Agenten

## Projektübersicht

OPAA (Open Project AI Assistant) ist ein quelloffenes Projekt, das einen KI-gestützten Projektassistenten entwickelt.
Beiträge von Menschen und KI-Agenten sind gleichermaßen willkommen.

## Projektsprache

Die Projektsprache ist **Deutsch**. Englisch bleibt ausschließlich dem Quellcode vorbehalten.

**Deutsch:**

- GitHub-Issues (Titel und Beschreibung)
- Pull Requests (Titel und Beschreibung)
- Dokumentation (`README.md`, `docs/`, ADRs, Feature-Spezifikationen)
- Templates unter `.github/`
- Commit-Beschreibungen und -Body (der Conventional-Commit-Typ und -Scope bleiben englisch, z. B. `feat(workspace): Rollenverwaltung ergänzen`)
- Alle in der Anwendung sichtbaren Texte — Frontend-UI, `aria-label`-Attribute und nutzerseitige API-Fehlermeldungen

**Englisch:**

- Bezeichner im Quellcode (Klassen, Methoden, Variablen, CSS-Klassen)
- Datei- und Verzeichnisnamen
- Code-Kommentare
- Log-Ausgaben und entwicklerseitige Exception-Messages
- Technische Konstanten, Enum-Werte, API-Feldnamen und die OpenAPI-Spezifikation
- Labels und Branch-Namen

## Architektur

- **Backend:** Java 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 (Gradle 9.6.1, Kotlin DSL)
- **Datenbank:** PostgreSQL 18 + pgvector, Liquibase
- **Frontend:** React 19 + TypeScript 6 + Material UI 9 + React Router 8 + Zustand + Vitest + MSW (Vite 8, Node 22+)
- **CI:** GitHub Actions
- **Deployment:** Docker Compose

> Vollständige Begründung: [ADR-0002](docs/decisions/0002-mvp-technology-stack.md)

## Build & Test

```bash
# Backend (aus backend/)
./gradlew build
./gradlew test
./gradlew test -PtestShard=api    # nur ein CI-Shard (api | indexing | core); Paketlisten in
                                  # build.gradle.kts (`testShards`), CI führt die drei parallel aus
./gradlew openAiIntegrationTest   # OpenAI-E2E-Tests (io.opaa.integration.*); braucht
                                  # OPAA_OPENAI_API_KEY und Docker, nicht Teil von build/test
OPAA_CONFLUENCE_IT=true ./gradlew confluenceIntegrationTest
                                  # Zugriffsschicht gegen ein echtes Confluence Data
                                  # Center im Container (io.opaa.integration.confluence.*);
                                  # braucht Docker, ~4 GB RAM und Internet für die 3-Stunden-
                                  # Testlizenz; nicht Teil von build/test, in CI nightly und per
                                  # Label "confluence-suite" (ADR-0023, #1171)
# Die MinIO-Suite des S3-Konnektors (io.opaa.indexing.source.s3.*Minio*, ADR-0027, #1382)
# läuft innerhalb von test/build, sobald Docker erreichbar ist (sonst übersprungen). Fußabdruck:
# ein geteilter MinIO je Test-JVM (MinioFixture) plus je Methode des Ereignisweg-Tests ein
# eigener MinIO samt sshd-Sidecar für die Portweiterleitung; die Spring-Klassen teilen sich den
# @OpaaIntegrationTest-Kontext (kein zusätzlicher Postgres). Bei maxParallelForks = 2 in
# der CI verdoppelt sich das. Alle drei Klassen zusammen unter zwei Minuten.
./gradlew spotlessCheck
./gradlew spotlessApply

# Backend starten — das Auth-Profil MUSS gesetzt sein, sonst bricht der Start
# mit einer Meldung von AuthProfileGuard ab (siehe ADR-0005).
SPRING_PROFILES_ACTIVE=local,dev ./gradlew bootRun

# Frontend (aus frontend/) — Paketmanager ist pnpm (siehe Issue #653); die
# Version ist im "packageManager"-Feld der package.json gepinnt, pnpm wechselt
# selbstständig auf diese Version
pnpm install                            # Abhängigkeiten installieren
VITE_ENABLE_MOCKS=true pnpm run dev     # Dev-Server mit MSW-Mocks
pnpm run dev                            # Dev-Server (benötigt Backend auf :8080)
# Im dev-Auth-Modus laufen Anfragen als "dev-admin"; auf einen regulären Nutzer
# wechseln mit http://localhost:5173/?devUser=dev-user
pnpm run build                          # Production-Build
pnpm run lint                           # Lint (ESLint)
pnpm run test                           # Tests (Vitest)
pnpm run format:check                   # Prettier-Formatierung prüfen
pnpm run format                         # Automatisch mit Prettier formatieren

# E2E-Suite (aus e2e/, siehe e2e/README.md)
pnpm install                            # Abhängigkeiten installieren
pnpm exec playwright install --with-deps chromium   # Browser installieren (einmalig)
pnpm test                               # Stack via Docker Compose starten, Suite ausführen, Stack wieder stoppen
```

## Abhängigkeitsverwaltung

- Alle Bibliotheks- und Plugin-Versionen MÜSSEN in `backend/gradle/libs.versions.toml` deklariert werden — niemals eine Version direkt in `build.gradle.kts` eintragen
- Alle Abhängigkeiten MÜSSEN im Abschnitt `[libraries]` als Bibliotheken definiert und über `libs.*` in `build.gradle.kts` referenziert werden
- Verwandte Bibliotheken in `[bundles]` zusammenfassen, wo sinnvoll (z. B. `spring-boot`, `spring-ai`, `test-deps`)
- Versions-Kataloge (`libs.versions.*`, `libs.*`, `libs.bundles.*`) für die Referenzierung von Versionen, Bibliotheken und Bundles verwenden
- Dies gilt für die Abschnitte `[versions]`, `[libraries]`, `[bundles]` und `[plugins]`

## API & DTO-Konvention

- **Alle API-DTOs MÜSSEN aus der OpenAPI-Spezifikation generiert werden** (`opaa-api/src/main/resources/openapi/opaa-api.yaml`) — niemals DTO-Klassen in `io.opaa.api.dto` manuell schreiben
- Änderungen an Request-/Response-Schemas beginnen mit einer Spec-Änderung, dann werden die generierten DTOs verwendet
- Spec, Generator-Konfiguration und die geteilten Domain-Enums, auf die `typeMappings` zeigt, leben im eigenen Gradle-Modul `opaa-api` (`io.opaa.api.types`); das Backend konsumiert sie über `implementation(project(":opaa-api"))` (#896)
- Domain-Enums in DTOs (z. B. `SpaceRole`, `AssetRole`) werden über `typeMappings`/`importMappings` in `opaa-api/build.gradle.kts` gemappt
- Beim Hinzufügen neuer Domain-Enums zur API genügen Einträge in `typeMappings` und `importMappings`; der `doLast`-Cleanup-Block im `openApiGenerate`-Task leitet die zu löschenden generierten Dateien mechanisch aus `typeMappings` ab
- Frontend-Typen werden aus derselben Spezifikation über `openapi-typescript` generiert
- **Domain-Services kennen keine `io.opaa.api.dto`-Typen** (#860): Service-Methoden nehmen Entities, Einzelparameter oder kleine Domain-Parameter-Records entgegen und geben Entities oder Domain-Records zurück. Das Entity→Response-Mapping lebt in einer package-private Mapper-Klasse im Paket des aufrufenden Controllers (heute meist `io.opaa.api`; Vorbild: `BrandingResponseMapper`, `SpaceResponseMapper`). Für angereicherte Ansichten (Response ≠ Entity, z. B. mit einer zusätzlichen Zählung) trägt ein Domain-Record im jeweiligen Fachpaket die zusätzlichen Felder (z. B. `SpaceOverview(space, libraryCount, chatCount)`) — kein Mapping-Framework, handgeschriebene Mapper genügen bei dieser DTO-Größenordnung
- **Werden Test-Assertions von Response-Feldern auf Entity-Ableitungen umgestellt** (etwa weil ein Service-Test jetzt gegen ein Entity statt ein DTO prüft), **muss die tatsächliche Feldbelegung durch einen Mapper-Unit-Test zugesichert werden** — sonst prüft kein Test mehr, dass der Mapper jedes Feld korrekt befüllt (siehe `SpaceResponseMapperTest`, `SpaceLibraryAssociationResponseMapperTest`)

> Vollständige Begründung: [ADR-0006](docs/decisions/0006-openapi-dto-generation.md)

## Spring-Testkontexte

Die gesamte Backend-Suite läuft auf **vier** Spring-Kontexten und damit vier Testcontainers-Postgres
je Test-JVM (Issue #1481; `TestcontainersConfiguration` deklariert den Container als gewöhnliches
Singleton-`@Bean`, es gilt also exakt: ein Kontext = ein Container). Jeder Backend-Test, der einen
Anwendungskontext startet, trägt genau eine der vier Meta-Annotationen aus `io.opaa.test`
(`backend/src/test/java/io/opaa/test/`) — niemals eine eigene
`@SpringBootTest`/`@ActiveProfiles`/`@Import`/`@Testcontainers`-Kombination:

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

Die drei Varianten sind über `@OpaaIntegrationTest` selbst meta-annotiert und ergänzen nur ihre
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
Klasse repariert. `LeftoverGrantGuard` nennt nach jeder Testklasse die Verursacherin für die beiden
RESTRICT-Kindtabellen, die keine Aufräumkette abdeckt (`diagnostic_impersonation_grants`,
`audit_incident_scope_grants`).

**`SpringContextSignatureTest` zieht die Grenze maschinell.** Er baut über
`BootstrapUtils.resolveTestContextBootstrapper(...)` je Testklasse die `MergedContextConfiguration`,
ohne einen Kontext zu starten, gruppiert danach und schlägt fehl, sobald eine Klasse ihre Signatur
verlässt oder eine fünfte Signatur entsteht. Eine neue kanonische Signatur wird dort bewusst
eingetragen; ein Code-Kommentar als Begründung genügt nicht mehr (nach #843 hatte jede der
gewachsenen 23 Kontext-Varianten einen formal regelkonformen Kommentar).

Ein neuer Postgres-Container wird nie manuell deklariert; `@ServiceConnection` kommt aus der
Meta-Annotation. Ausnahme: `io.opaa.migration`-Tests booten bewusst einen eigenen Container mit
Template-Datenbank pro Klasse (siehe `AbstractMigrationTest`) — das Muster ist dort nötig und keine
Abweichung von dieser Regel. Ebenfalls außerhalb: die `@WebMvcTest`-Slices, die weder einen
Anwendungskontext noch eine Datenbank starten.

### Liquibase-Baseline (Stand 09/2026, #1492)

Die Liquibase-Historie wurde zweimal zu einer Baseline zusammengefasst: 08/2026 die ersten 134 Changesets aus 67 Dateien (#904/PR #906), 09/2026 die seither entstandenen 56 Changesets aus 33 Dateien (#1492/PR #1504). Beide Male ein bewusster Einmalvorgang vor Produktionsbetrieb, kein wiederkehrendes Muster: jede laufende Installation muss danach neu aufgesetzt werden, weil `DATABASECHANGELOG` nicht mehr passt.

Ergebnis ist die eine Datei `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` mit 13 thematisch gruppierten Changesets (`001-baseline-a` … `-m`) plus zwei bewusst eigenständigen, precondition-geschützten Changesets für die `vector_store`-Ausdrucksindexe (`-n`, `-o`) — die dürfen nicht in eine Gruppe gefaltet werden, sonst überspringt die Precondition beim ersten Start eine ganze Gruppe. `db.changelog-master.yaml` referenziert nur noch diese Datei; Rollback-Blöcke hat die Baseline bewusst keine.

**Ab der Baseline gilt wieder: ein Changeset pro Datenbankänderung**, jedes mit eigenem Delta-Test unter `backend/src/test/java/io/opaa/migration/` nach dem in `AbstractMigrationTest`/`package-info.java` beschriebenen Muster — die Fixture-Kette für Delta-Tests startet ab `backend/src/test/resources/db/changelog/test-master-through-baseline.yaml`. Vier Testklassen dieses Pakets prüfen die Baseline selbst statt einer Einzelmigration: `MigrationBaselineTest` (Tabellen, pgvector, Seed-Zeilen, Organisationsgrenzen-Regel, portierte Zustandsinvarianten), `AuditPrivilegeModelTest` und `DiagnosticContextPrivilegeModelTest` (die beiden Privilegienmodelle nach ADR-0015) sowie `VectorStoreExpressionIndexTest` (Precondition-Übersprung und Wiederholung der beiden `vector_store`-Changesets). Welche historischen Prüfungen bewusst entfallen sind, steht in den Beschreibungen von PR #906 und PR #1504.

## Code-Konventionen

### Code-Kommentare

Ein Kommentar beschreibt den Verhaltensvertrag oder eine nicht offensichtliche Invariante — in 1–5 Zeilen. Entstehungsgeschichte (Review-Runden, verworfene Alternativen, Fehlversuche) gehört in Commit-Nachrichten und PR-Beschreibungen, nicht in den Code; sie ist dort über `git log`/`git blame` jederzeit auffindbar. Eine Issue-/PR-Referenz im Code ist nur zulässig, wenn sie eine aktive Einschränkung markiert, zum Beispiel ein Workaround bis zu einem Upstream-Fix, ein `@Disabled`/`.skip` mit Ticketverweis, oder eine Deprecation mit dem Ablösungs-Issue. Gilt projektweit — Javadoc, TSDoc, Inline-Kommentare in `.java`/`.ts`/`.tsx`, sowie `application.yml`, `build.gradle.kts`, `vite.config.ts` und Workflow-Dateien. Nicht betroffen: Markdown-Dokumentation und ADRs — dort ist die Abwägung (verworfene Alternativen, Entscheidungshistorie) Zweck des Dokuments.

In Tests darf ein Kommentar zusätzlich die abgesicherte Regression benennen (z. B. `// regression guard for #307: ...`), sofern er die Invariante nennt und nicht den Review-Verlauf — sonst ist die Regel genau beim größten Bestand nicht durchsetzbar.

**Negativbeispiel** (Nacherzählung statt Vertrag):

```java
// PR #612 review, finding 3: originally this called the repository directly,
// which caused an N+1 problem, see discussion in #598.
// After a talk with the reviewer we switched to the cache introduced in #545.
// Careful: #501 already had a similar bug here — the cache must be invalidated
// on every write path, and #559 nearly reverted this fix because someone
// missed one of the three write paths. Do not remove this without re-reading
// the whole thread in #598 first.
```

**Positivbeispiel** (Vertrag/Invariante):

```java
/**
 * Cached values are invalidated on every write path in {@link SpaceService};
 * a cache hit is therefore always consistent with the last committed state.
 */
```

### Commit-Nachrichten

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) verwenden:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

Typen: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`, `build`

KI-Agenten müssen einen `Co-Authored-By`-Trailer in Commits einfügen.

### Git-Workflow

- Immer einen Feature-Branch erstellen; niemals direkt auf `main` committen
- PRs fokussiert halten: eine logische Änderung pro PR
- Bei der Behebung eines Issues in der PR-Beschreibung mit `Closes #N` referenzieren

### Git Worktrees für parallele Sessions

Wenn mehrere Agent-Sessions gleichzeitig in diesem Verzeichnis arbeiten (z. B. mehrere Features parallel), für jede neue Aufgabe einen eigenen Git Worktree nutzen, statt im Hauptverzeichnis zu branchen. So blockieren sich parallele Sessions nicht gegenseitig durch Branch-Wechsel im selben Arbeitsverzeichnis.

- Neue Aufgabe → eigenen Worktree anlegen (eigener Branch, eigenes Arbeitsverzeichnis)
- Aufgabe fertig & gemerged → Worktree **sofort entfernen**. Ein bebauter Worktree belegt durch `backend/build` und `.gradle` schnell viele GB — liegengebliebene Worktrees füllen die Platte. (`frontend/node_modules` ist seit der pnpm-Migration nur noch ein Link-Baum in den benutzerweiten Store und fällt kaum noch ins Gewicht.)
- Aufgabe unterbrochen, später weiterführen → Worktree behalten
- `pnpm install` in einem Worktree erst ausführen, wenn tatsächlich am Frontend gearbeitet wird

### Branch-Benennung

Format: `feature/<issue-id>_<kurze-beschreibung>`

Jeder Branch ist über seine ID mit einem GitHub-Issue verknüpft.

**Branch-Regel (verbindlich):**
- Branches immer mit `feature/` erstellen — **ausnahmslos**, auch bei Fehlerbehebungen, dringenden Korrekturen und Dokumentationsänderungen. Es gibt kein `fix/`-, `hotfix/`- oder `docs/`-Präfix.
- Immer die GitHub-Issue-ID im Branch-Namen angeben.
- Keine generischen Namen wie `feature/workspace` ohne Issue-ID verwenden.

**Ausnahme Renovate:** Von Renovate erzeugte Update-Branches heißen `renovate/<slug>` (ohne
`feature/`-Präfix und ohne Issue-ID), und Renovate-PRs verwenden weder das PR-Template noch
einen durchgehend deutschen Body (deutscher Titel und Kopfsatz, generierter englischer Rest).
Renovate-PRs werden zudem mit aktiviertem GitHub-Auto-Merge eröffnet (stehende
Maintainer-Anweisung, Issue #951) — sie mergen automatisch, sobald die Required Checks grün
sind. Das ist die einzige zugelassene Abweichung von den Branch-, PR- und Auto-Merge-Regeln
dieses Dokuments — sie gilt ausschließlich für den Bot (siehe `docs/renovate.md`,
Issues #751/#951); Menschen und Agenten bleiben an die Regeln gebunden.

Die Art der Änderung wird über den Conventional-Commit-Typ ausgedrückt (`fix`, `docs`, `chore`, …), nicht über das Branch-Präfix — in der Commit-Nachricht und im PR-Titel. Ein Branch `feature/295_branch-regel-klarstellen` mit dem Commit `docs(agents): …` ist der Normalfall, kein Widerspruch.

### GitHub-Issues

- Beim Erstellen eines GitHub-Issues IMMER passende Labels basierend auf dem Inhalt zuweisen
- Vorhandene Labels verwenden (z. B. `bug`, `enhancement`, `backend`, `frontend`, `security`, `auth`, `size:S/M/L`, usw.)
- Issue-Titel und -Beschreibungen MÜSSEN auf Deutsch verfasst werden (siehe [Projektsprache](#projektsprache))
- **Epics führen ihre Tickets als native Sub-Issues**, nicht als Checkliste im Body. GitHub führt Status und Fortschritt dann selbst, und der Tagesreport liest dieselbe Beziehung. Aufbau des Epic-Bodys: [.github/ISSUE_TEMPLATE/epic.md](.github/ISSUE_TEMPLATE/epic.md)
- Ein bereits angelegtes Issue wird nachträglich verknüpft über `gh api -X POST repos/{owner}/{repo}/issues/{epic}/sub_issues -F sub_issue_id={id}` — `{id}` ist die Objekt-ID des Kind-Issues (`gh api repos/{owner}/{repo}/issues/{nr} --jq .id`), nicht seine Nummer
- **Ein Epic wird geschlossen, sobald alle seine Sub-Issues geschlossen sind** — mit einem kurzen Abschlusskommentar: was geliefert wurde (PR-Verweise genügen) und welche Folge-Issues außerhalb des Epic-Umfangs entstanden sind. Wer das letzte Sub-Issue eines Epics abschließt, prüft den Stand der Geschwister (`gh api repos/{owner}/{repo}/issues/{epic}/sub_issues --jq '[.[] | select(.state=="open")] | length'`)

### Pull Requests

- Keine direkten Pushes zu `main` — alle Änderungen gehen über PRs
- Der Code Reviewer prüft jeden PR vor dem Merge; seine Befunde gehen zurück an den Autor
- Ein formales Approval in GitHub ist nicht erforderlich. Es genügt, dass ein Maintainer des Projekts den PR merged, sobald CI grün ist
- **Auto-Merge nutzen:** `gh pr merge --auto --squash` merged den PR automatisch, sobald die Required Checks grün sind. Ein PR muss dafür nicht up to date mit `main` sein (nur konfliktfrei) — manuelles „Branch aktualisieren und CI abwarten" entfällt. Der Push auf `main` lässt die CI anschließend den kombinierten Stand prüfen
- Auto-Merge setzen dürfen nur Maintainer und der Koordinator mit ausdrücklicher Maintainer-Freigabe; Entwickler- und Review-Agenten mergen nie (siehe [docs/AGENT-ORGANIZATION.md](docs/AGENT-ORGANIZATION.md), Schritt 6)
- Beim Erstellen eines PRs IMMER passende Labels basierend auf dem Inhalt zuweisen
- PR-Titel und -Beschreibungen MÜSSEN auf Deutsch verfasst werden (siehe [Projektsprache](#projektsprache))
- IMMER das PR-Template (Zusammenfassung, Zugehörige Issues, Art der Änderung, Checkliste, KI-Agenten-Offenlegung) in [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) für neue Pull Requests verwenden

### Pre-Push-Checkliste

Bei reinen Dokumentationsänderungen überspringen. Vor dem **ersten Push eines PRs** müssen alle folgenden Punkte lokal bestehen:

- Backend-Formatierung
- Backend-Build + Test
- Frontend-Formatierung
- Frontend-Lint
- Frontend-Build + Test

**Nachbesserungsrunden** (Folge-Pushes auf einen bestehenden PR, etwa nach Review-Befunden) verwenden die
verkürzte Prüfung: Formatierung, Kompilieren und die von der Änderung berührten Testklassen. Den vollen
Durchlauf übernimmt die CI des PRs — sie führt ohnehin dieselben Prüfungen aus, und ein roter CI-Lauf
kostet nicht mehr Zeit als der eingesparte lokale Volllauf. Wer die verkürzte Prüfung nutzt, prüft das
CI-Ergebnis des Folge-Pushes, bevor der PR als bereit gilt.

**Builds und Tests im Vordergrund ausführen** und aktiv abwarten (mit ausreichendem Timeout), statt einen
eigenen Hintergrundlauf zu starten und auf dessen Benachrichtigung zu warten — das hat sich als
wiederkehrende Quelle verlorener Wartezeit erwiesen.

## Wichtige Pfade

- `docs/AGENT-ORGANIZATION.md` — Agenten-Rollen, Idee-bis-Merge-Workflow und Kollaborationsregeln
- `docs/decisions/` — Architecture Decision Records (ADRs), u. a. [ADR-0021](docs/decisions/0021-single-instance-betrieb.md) zur Single-Instance-Annahme des Backends
- `docs/features/` — Feature-Spezifikationen
- `docs/handbuch/` — Produkthandbuch des gebauten Ist-Stands (`deployment.md`, `indexierung.md` mit je einem Kapitel pro Konnektor und Format, `metadaten.md`); Regeln der Kapitel in Issue #1282
- `.github/ISSUE_TEMPLATE/` — Issue-Templates
- `.github/PULL_REQUEST_TEMPLATE.md` — PR-Template
- `CONTRIBUTING.md` — Leitfaden für Beitragende
- `AGENTS.md` — Anweisungen für KI-Agenten
- `backend/` — Spring Boot Backend (Gradle-Projekt, Gradle-Root des Multi-Modul-Builds)
- `opaa-api/` — Gradle-Modul mit OpenAPI-Spec, Generator-Konfiguration und geteilten Domain-Enums (`io.opaa.api.types`), siehe [ADR-0006](docs/decisions/0006-openapi-dto-generation.md) (#896)
- `frontend/` — React-Frontend (Vite-Projekt)
- `frontend/src/test/test-utils.tsx` — Gemeinsame Test-Render-Helfer
- `e2e/` — Browserbasierte End-to-End-Tests (Playwright), siehe `e2e/README.md`
- `docs/renovate.md` — selbst betriebene Abhängigkeits-Updates (Renovate, Issue #751); Regeln in `renovate.json5`
- `docs/sbom.md` — Software Bill of Materials: Image-Attestierungen und CycloneDX-CI-Artefakte (Issue #1078)
- `docs/cve-scanning.md` — CVE-Erkennung: Dependabot-Alerts, Trivy-Image-Scan und Triage-Verfahren (Issues #1079, #1450)
- `eval/` — Korpora, Golden Datasets und Generatoren der Suchqualitäts-Evaluierung, siehe `eval/README.md`. Liegt bewusst außerhalb von Gradle-Build und CI; die Generatoren laufen nur bei bewussten Korpus-Änderungen, nie automatisch. Der Metrik-Harness selbst ist ein Integrationstest im Backend

## Contributor License Agreement

Ohne unterzeichnete [Contributor License Agreement](./CLA.md) kann kein Pull Request zusammengeführt werden. **Ein Agent unterzeichnet nie selbst** — verantwortlich ist der menschliche Betreiber, einmal pro GitHub-Account.

Verfahren und Wortlaut des Unterzeichnungskommentars stehen in [CONTRIBUTING.md](./CONTRIBUTING.md#contributor-license-agreement-cla).

## Agenten-Verhalten

- In der Sprache antworten, in der der Benutzer schreibt
- Code nicht umstrukturieren, sofern nicht ausdrücklich verlangt
- Vor dem Erstellen neuer Dateien prüfen, ob ähnliche Muster oder Hilfsfunktionen bereits existieren
- Kleine, fokussierte Commits gegenüber großen bevorzugen
- Bei der Behebung eines Bugs zuerst einen Test schreiben, der den Bug reproduziert — und **nachweisen, dass er auf dem fehlerhaften Stand tatsächlich fehlschlägt** (siehe [Reproduktionsnachweis](#reproduktionsnachweis))
- `docs/decisions/` für Architecture Decision Records vor größeren strukturellen Änderungen lesen

### Reproduktionsnachweis

Ein Test, der den Fehler nicht fangen würde, ist wertlos — und das fällt am Ergebnis nicht auf, weil er ja grün ist.

Deshalb gilt bei jeder Fehlerbehebung: Den Fix vorübergehend zurücknehmen, den Test laufen lassen, das Fehlschlagen belegen, den Fix wiederherstellen, erneut laufen lassen. **Beide Ergebnisse gehören in die PR-Beschreibung**, mit der konkreten Fehlermeldung des roten Laufs.

**Beleg-Läufe nur auf aktuellem Stand:** Vor dem roten und dem grünen Lauf `origin/main` in den Branch mergen — ein Nachweis auf veraltetem Stand belegt nichts. Widerspricht ein lokaler grüner Lauf einem roten CI-Lauf, gilt die CI; der lokale Lauf ist dann auf einem anderen (meist älteren oder anders kombinierten) Stand gelaufen.

Typische Ursachen dafür, dass ein Test den Fehler verfehlt:

- **Er prüft eine Bedingung, die vor und nach dem Fix gilt** — etwa „irgendwann wurde invalidiert" statt „zum richtigen Zeitpunkt".
- **Er läuft gegen ein anderes Schema als die Produktion.** Mit `ddl-auto=create-drop` erzeugt Hibernate keine Fremdschlüssel für einfache `UUID`-Spalten ohne `@ManyToOne`, Liquibase dagegen schon. Für alles FK-abhängige gehört `spring.liquibase.enabled=true` und `ddl-auto=none` in den Test.
- **Er führt den geänderten Code gar nicht aus** — etwa Liquibase-Changelogs, die in den regulären Integrationstests nicht angewendet werden. Dafür gibt es das Muster in `backend/src/test/java/io/opaa/migration/`.
- **Er mockt genau die Stelle weg, um die es geht** — ein gemockter `PlatformTransactionManager` führt keine Propagation aus, ein gemockter API-Client validiert keinen Request-Body.

## Sicherheit

- Niemals Secrets, API-Schlüssel oder Anmeldeinformationen committen
- Umgebungsvariablen für die Konfiguration verwenden
- `.env`-Dateien nicht committen
