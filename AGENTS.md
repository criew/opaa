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
./gradlew openAiIntegrationTest   # OpenAI-E2E-Tests (io.opaa.integration.*); braucht
                                  # OPAA_OPENAI_API_KEY und Docker, nicht Teil von build/test
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

Neue Backend-Integrationstests verwenden eine der kanonischen Meta-Annotationen aus `io.opaa.test`
(`backend/src/test/java/io/opaa/test/`) statt eigener `@SpringBootTest`/`@ActiveProfiles`/`@Import`/
`@Testcontainers`-Kombinationen:

- `@OpaaIntegrationTest` — Service-/Repository-Ebene gegen echtes Postgres, ohne MockMvc
  (`webEnvironment = RANDOM_PORT`, `@ActiveProfiles({"local", "dev"})`). `RANDOM_PORT` startet einen
  echten Servlet-Container; das ist bewusst gewählt, damit diese Signatur mit der großen
  `@OpaaIntegrationTest`-Gruppe kontext-kompatibel bleibt, auch für Klassen, die selbst keinen HTTP-Client
  gegen die eigene Anwendung nutzen.
- `@OpaaMockMvcTest` — Controller-Ebene über MockMvc (`@AutoConfigureMockMvc`,
  `@ActiveProfiles("dev")`).
- `@OpaaIndexingIntegrationTest` — dieselbe Basis wie `@OpaaIntegrationTest`, ergänzt um die feste
  Chunking-Konfiguration (`opaa.indexing.chunk-size`/`-overlap`/`-batch-size` als `properties`) und
  den kanonischen Mock-/Fake-LLM-Satz (`ChatModel`, `ActiveChatModelResolver`, `EmbeddingModel`) der
  Indexing-Pipeline-Tests. `opaa.indexing.filesystem-allowlist` zeigt auf ein einziges,
  prozessweites Basisverzeichnis (`OpaaIndexingTestDirectory.BASE_DIR`), einmalig über einen
  `ApplicationContextInitializer` registriert statt über eine klassenlokale
  `@DynamicPropertySource` — eine Testklasse legt sich darunter mit
  `OpaaIndexingTestDirectory.subdirectory(name)` ihr eigenes Unterverzeichnis an.
  `OpaaIndexingMockResetListener` setzt die beiden Mocks vor jeder Testmethode zurück, damit
  Stubbing nicht zwischen Klassen im selben Kontext durchsickert.

Jede Klasse mit identischer Signatur teilt sich einen Spring-Kontext und einen Testcontainers-
Postgres statt einen eigenen zu booten — Spring cached Kontexte anhand der exakten, zusammengeführten
Konfiguration (Issue #843). Eine eigene `@DynamicPropertySource`, ein eigenes `@Import(...TestConfig)`
oder ein eigener `@MockitoBean`-Satz erzwingt trotz gemeinsamer Meta-Annotation einen eigenen Kontext
(Spring bezieht das in den Cache-Schlüssel ein) — das ist zulässig, wenn fachlich nötig, muss aber mit
einem 1–2-zeiligen Kommentar über der Annotation begründet werden (Review-Flagge). In eine
`@DynamicPropertySource` gehört nur, was zur Laufzeit aus einer Ressource (z. B. einem Testcontainer)
gelesen wird — ein konstanter Wert gehört stattdessen in `properties` auf der `@SpringBootTest`-Annotation
selbst. Ein zur Laufzeit berechneter Wert, der über alle Klassen einer Signatur identisch sein muss
(z. B. ein einmalig angelegtes, geteiltes Basisverzeichnis), gehört in einen geteilten
`ApplicationContextInitializer` der Meta-Annotation selbst statt in eine klassenlokale
`@DynamicPropertySource` — letztere spaltet den Kontext trotz identischen Werts, weil Spring die
Methode selbst (nicht nur ihr Ergebnis) in den Cache-Schlüssel einbezieht (siehe
`@OpaaIndexingIntegrationTest`). Ein neuer Postgres-Container wird nie manuell deklariert; `@ServiceConnection` kommt aus der
Meta-Annotation. Ausnahme: `io.opaa.migration`-Tests booten bewusst einen eigenen Container mit
Template-Datenbank pro Klasse (siehe `AbstractMigrationTest`) — das Muster ist dort nötig und keine
Abweichung von dieser Regel. Passt keine der drei Signaturen, ist das ein Fall für eine weitere
kanonische Meta-Annotation statt einer weiteren Ad-hoc-Kombination — im Zweifel im PR begründen und
dem Review überlassen.

### Liquibase-Baseline (seit #904)

Die Liquibase-Historie bis 08/2026 (134 Changesets) wurde einmalig zu `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` zusammengefasst — ein bewusster Einmalvorgang vor Produktionsbetrieb, kein wiederkehrendes Muster. `db.changelog-master.yaml` referenziert nur noch diese eine Datei. **Ab der Baseline gilt wieder: ein Changeset pro Datenbankänderung**, jedes mit eigenem Delta-Test unter `backend/src/test/java/io/opaa/migration/` nach dem in `AbstractMigrationTest`/`package-info.java` beschriebenen Muster — die Fixture-Kette für Delta-Tests startet ab `backend/src/test/resources/db/changelog/test-master-through-baseline.yaml`. `MigrationBaselineTest` und `AuditPrivilegeModelTest` sind die einzigen verbleibenden Tests dieses Pakets aus der Zeit vor der Baseline; sie prüfen, dass die Baseline selbst auf einer leeren Datenbank die erwarteten Kerninvarianten herstellt (Tabellen, pgvector, Seed-Zeilen, Organisationsgrenzen-Regel, ausgewählte Zustandsinvarianten, Audit-Privilegienmodell nach ADR-0015) — keine Einzelmigration wird mehr gegen ihren Vorgängerzustand getestet; welche der ~40 historischen Prüfungen bewusst entfallen sind, steht in der Beschreibung von PR #906.

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
- `.github/ISSUE_TEMPLATE/` — Issue-Templates
- `.github/PULL_REQUEST_TEMPLATE.md` — PR-Template
- `CONTRIBUTING.md` — Leitfaden für Beitragende
- `AGENTS.md` — Anweisungen für KI-Agenten
- `backend/` — Spring Boot Backend (Gradle-Projekt, Gradle-Root des Multi-Modul-Builds)
- `opaa-api/` — Gradle-Modul mit OpenAPI-Spec, Generator-Konfiguration und geteilten Domain-Enums (`io.opaa.api.types`), siehe [ADR-0006](docs/decisions/0006-openapi-dto-generation.md) (#896)
- `frontend/` — React-Frontend (Vite-Projekt)
- `frontend/src/test/test-utils.tsx` — Gemeinsame Test-Render-Helfer
- `e2e/` — Browserbasierte End-to-End-Tests (Playwright), siehe `e2e/README.md`
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
