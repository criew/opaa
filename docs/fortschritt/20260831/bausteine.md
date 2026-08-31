# Bausteine

Je geschlossenem Issue und je gemergtem PR ohne Issue-Verknüpfung ein Baustein:
was gefordert war, was geliefert wurde, Realitätscheck gegen den Code zum Stichtag.
Bausteine sind roh und ungeschönt — sie sind die Beleg-Grundlage des Berichts, nicht
die Erzählung. Suche mit `Issue #NNN` bzw. `PR #NNN`.

---

<a id="issue-2"></a>

## Issue #2 — Create comprehensive OPAA product vision and feature specifications
- Geschlossen: 2026-02-17 (completed)
- Labels: documentation
- PRs: #3 (2026-02-17)

**Laut Issue:** Ein vollständiges, nicht-technisches Produktvisionsdokument für OPAA erstellen: VISION.md, fünf Feature-Spezifikationen, Navigations-/Glossardokumente (CONCEPTS.md, GETTING-STARTED.md, INDEX.md) sowie eine erweiterte README. Ziel: Architektur- und Scope-Entscheidungen leiten und Onboarding ermöglichen.

**Geliefert:** PR #3 liefert exakt die geforderten Artefakte — VISION.md, CONCEPTS.md, GETTING-STARTED.md, INDEX.md, fünf Feature-Specs (`user-frontends.md`, `data-indexing-rag.md`, `llm-integration.md`, `deployment-infrastructure.md`, `access-control-workspaces.md`) sowie eine aktualisierte README.md. Keine Abweichung vom Issue erkennbar.

**Verifikation:** VISION.md, CONCEPTS.md, GETTING-STARTED.md und INDEX.md existieren im heutigen Worktree weiterhin. `docs/features/` enthält heute deutlich mehr Specs als die ursprünglichen fünf (u. a. `spaces-and-assets.md`, `search-quality-evaluation.md`, `security-and-compliance.md`, `public-sector.md`); die ursprüngliche `access-control-workspaces.md` ist nicht mehr unter diesem Namen vorhanden — heute existiert stattdessen `docs/features/access-control.md`, vermutlich im Zuge einer späteren Umbenennung/Neuausrichtung. Das Fundament aus PR #3 wurde also weiterentwickelt, nicht verworfen.

**Themen:** doku, produktvision, projektsetup

---

<a id="issue-4"></a>

## Issue #4 — MVP: Define and implement minimal viable product
- Geschlossen: 2026-02-18 (completed)
- Labels: epic, mvp, size:L
- PRs: #5 (2026-02-18)

**Laut Issue:** Auf Basis der Produktvision (docs/VISION.md) den MVP-Scope definieren: Kernwertversprechen, technisches Fundament, initiale Integrationen (Datenquelle, LLM, Frontend) und Erfolgskriterien festlegen. Erwartet werden ein MVP-Scope-Dokument, eine Technologie-ADR und eine erste Aufgabenzerlegung.

**Geliefert:** PR #5 definiert den MVP als Q&A-System über indexierte Dokumente mit Quellenangaben, dokumentiert in `docs/MVP.md`, und legt die Technologieentscheidung in ADR-0002 fest (Java/Spring Boot + Spring AI, React/TypeScript/MUI, PostgreSQL + pgvector, Apache Tika, OpenAI-kompatible API). Auth, Multi-Tenancy, Chat-Integrationen und Kubernetes wurden explizit als out-of-scope markiert. Deckt die Anforderung vollständig ab.

**Verifikation:** `docs/decisions/0002-mvp-technology-stack.md` existiert weiterhin im Worktree. `docs/MVP.md` existiert dagegen nicht mehr — laut `git log --follow` wurde die Datei zuletzt im Commit „docs: Einstieg und Umsetzungsstand auf die neue Ausrichtung angleichen" (14.08.2026) angefasst; README und Einstiegsdokumentation wurden dabei auf eine neue Ausrichtung umgestellt (weg von Fortune-500/SaaS-Zielgruppen, austauschbare Vektor-DB-Versprechen entfernt, da der Stack durch ADR-0002 längst festgelegt ist). Der MVP-Scope wurde damit nicht verworfen, sondern in aktuellere Einstiegsdokumentation überführt.

**Themen:** doku, mvp, projektsetup, adr

---

<a id="issue-6"></a>

## Issue #6 — chore: scaffold Spring Boot backend with Gradle
- Geschlossen: 2026-02-18 (completed)
- Labels: mvp, backend, setup, size:M
- PRs: #21 (2026-02-18)

**Laut Issue:** Grundgerüst für Spring Boot 3.x mit Java 21 und Gradle 9.3.1 (Kotlin DSL) aufsetzen: Basis-Package `io.opaa` mit Unterpaketen `api`, `indexing`, `query`; Spring-AI-Abhängigkeiten (OpenAI, Ollama, pgvector, Tika); `application.yml` mit Profilen `local`, `docker`, `mock`; Health-Check-Endpunkt.

**Geliefert:** PR #21 scaffoldet exakt wie gefordert — Spring Boot 3.5.10, Gradle 9.3.1, Java 21, Paketstruktur `io.opaa.api`/`io.opaa.indexing`/`io.opaa.query`, Health-Endpoint, `application.yml` mit den drei Profilen. Zusätzlich liefert der PR bereits `docs/decisions/0003-code-formatting.md` und Testcontainers-Setup, was über den reinen Scaffold-Umfang hinausgeht, aber sinnvoll ergänzt.

**Verifikation:** `backend/build.gradle.kts` und `backend/src/main/java/io/opaa/api/HealthController.java` existieren weiterhin im Worktree. Grundstruktur ist erwartungsgemäß seither weit ausgebaut worden (viele weitere Packages/Klassen), das Fundament aus PR #21 blieb bestehen.

**Themen:** backend, projektsetup, gradle, spring-boot

---

<a id="issue-7"></a>

## Issue #7 — chore: scaffold React frontend with TypeScript and MUI 7
- Geschlossen: 2026-02-19 (completed)
- Labels: mvp, frontend, setup, size:S
- PRs: #22 (2026-02-19)

**Laut Issue:** React-Frontend mit TypeScript und Material UI 7.3.8 via Vite aufsetzen, Vitest + React Testing Library als Testframework, ESLint/Prettier, App-Shell mit ThemeProvider/CssBaseline, Platzhalter-Landingpage, API-Proxy auf `http://localhost:8080`.

**Geliefert:** PR #22 liefert genau das: Vite+React+TypeScript-Projekt in `frontend/`, MUI 7.3.8, Emotion, Axios, Vitest+RTL mit Beispieltest, ESLint+Prettier, OPAA-Landingpage mit ThemeProvider/CssBaseline, API-Proxy `/api` → Backend. Keine Abweichung vom Issue.

**Verifikation:** `frontend/package.json` und `frontend/src/App.tsx` existieren weiterhin im Worktree. Das Frontend wurde seither erheblich ausgebaut (Chat-UI, Routing, Stores etc. ab #14).

**Themen:** frontend, projektsetup, react, mui

---

<a id="issue-8"></a>

## Issue #8 — feat(api): define API contract with OpenAPI spec and dual mock layer
- Geschlossen: 2026-02-19 (completed)
- Labels: mvp, backend, frontend, size:M
- PRs: #26 (2026-02-19)

**Laut Issue:** Vollständigen API-Vertrag als OpenAPI-3.0-Spezifikation definieren (Query, Indexing-Trigger, Indexing-Status), Request/Response-DTOs in Java und TypeScript, dualen Mock-Layer bauen (Backend-Profil `mock` + MSW im Frontend), Validierung, globaler Exception-Handler mit einheitlichem Fehlerformat, CORS.

**Geliefert:** PR #26 liefert die OpenAPI-Spec, 8 Java-Record-DTOs mit Validierung, `GlobalExceptionHandler`, `CorsConfig`, `MockQueryController`/`MockIndexingController` hinter `@Profile("mock")`, passende TypeScript-Typen sowie MSW-v2-Handler mit gemeinsamen Fixtures. Backend- und Frontend-Unit-Tests wie gefordert. Deckt die Anforderung vollständig ab; ein manueller Testpunkt (`VITE_ENABLE_MOCKS=true npm run dev` Browser-Check) blieb im PR-Testplan unmarkiert.

**Verifikation:** `backend/src/main/resources/openapi/opaa-api.yaml` existiert weiterhin. Die Mock-Controller `MockQueryController`/`MockIndexingController` existieren dagegen NICHT mehr — laut `git log --follow` wurden sie im Commit „refactor: remove Spring mock profile from codebase" entfernt, nachdem `feat(api): generate backend and frontend DTOs from OpenAPI spec` das DTO-Generierungsverfahren (ADR-0006) eingeführt hatte. Das Mock-Profil-Konzept aus #8 wurde also nach dem MVP bewusst wieder abgeschafft; die eigentliche API-Vertrags-Infrastruktur (OpenAPI-Spec, generierte DTOs, MSW im Frontend) besteht als Nachfolgekonstruktion fort.

**Themen:** backend, frontend, api, openapi, mocking

---

<a id="issue-9"></a>

## Issue #9 — chore: set up PostgreSQL schema with pgvector and Liquibase
- Geschlossen: 2026-02-20 (completed)
- Labels: mvp, backend, setup, size:M
- PRs: #28 (2026-02-20)

**Laut Issue:** Liquibase-Changesets für pgvector-Extension, Tabellen `documents`, `document_chunks` (mit Vektor-Embeddings) und `indexing_jobs` sowie HNSW-Index auf `document_chunks.embedding`; `docker-compose.yml` nur mit PostgreSQL für lokale Entwicklung.

**Geliefert:** PR #28 liefert `docker-compose.yml` mit PostgreSQL 18 + pgvector, Liquibase-Changesets für alle drei Tabellen inkl. HNSW-Index, Liquibase-Startup-Konfiguration mit deaktivierter Spring-AI-Auto-Schema-Initialisierung sowie Testcontainers-Umstellung auf `pgvector/pgvector:pg18`. Deckt die Anforderung vollständig ab.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/001-enable-pgvector-extension.yaml`, `002-create-documents-table.yaml` und `docker-compose.yml` existieren weiterhin im Worktree. Anzumerken: Die ursprüngliche `document_chunks`-Tabelle aus #9 wurde in #10 (PR #34) durch Spring AIs `VectorStore`-Abstraktion (`vector_store`-Tabelle, autogeneriert) ersetzt — das dortige Changeset 003 wurde entfernt und die Nummer für `indexing_jobs` wiederverwendet. Das Grundschema aus #9 (pgvector-Extension, `documents`-Tabelle, Docker-Compose-Fundament) besteht fort, die konkrete Chunk-Speicherung wurde architektonisch verändert.

**Themen:** backend, datenbank, pgvector, liquibase, deployment

---

<a id="issue-10"></a>

## Issue #10 — feat(indexing): implement complete document indexing pipeline
- Geschlossen: 2026-02-25 (completed)
- Labels: enhancement, mvp, backend, size:L
- PRs: #34 (2026-02-25)

**Laut Issue:** Vollständige Indexing-Pipeline: Verzeichnis scannen, Apache Tika zum Parsen, konfigurierbares Chunking, Embedding-Generierung (OpenAI/Ollama austauschbar), Speicherung in PostgreSQL/pgvector über JPA-Entities `Document`/`DocumentChunk`, Job-Tracking, Fehlerbehandlung mit Skip-und-Weiter sowie Retry-Logik.

**Geliefert:** PR #34 liefert die Pipeline größtenteils wie gefordert, weicht aber bewusst von der Speicherarchitektur des Issues ab: Statt manueller `EmbeddingModel.embed()`-Aufrufe und nativer SQL-Inserts in eine eigene `document_chunks`-Tabelle wird Spring AIs `VectorStore`-Abstraktion genutzt (`VectorStore.add()`/`.delete()`). Die im Issue vorgesehene `DocumentChunk`-Entity und `DocumentChunkRepository` wurden dadurch nicht gebaut bzw. wieder entfernt; die Vektor-Tabelle wird stattdessen von Spring AI automatisch verwaltet (`initialize-schema: true`). Der PR-Body begründet dies mit Austauschbarkeit des Vektor-Backends gemäß ADR-0002. Chunking, Job-Tracking, Fehlerbehandlung und konfigurierbare Parameter über `OPAA_INDEXING_*`-Variablen wurden wie gefordert geliefert, inkl. Unit- und Integrationstests mit Testcontainers.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChunkingService.java`, `DocumentIndexingService.java` und `FileProcessingService.java` existieren weiterhin im Worktree.

**Themen:** backend, indexing, rag, pgvector, tika, embedding

---

<a id="issue-11"></a>

## Issue #11 — feat(query): implement vector similarity search
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, size:M
- PRs: keine

**Laut Issue:** Retrieval-Komponente der RAG-Pipeline implementieren: Nutzerfrage mit demselben Embedding-Modell wie beim Indexing einbetten, Vektor-Ähnlichkeitssuche gegen `document_chunks` (Kosinus-Ähnlichkeit) durchführen, Top-K konfigurierbar (Default K=5), `RetrievalResult`-DTO mit Datei, Chunk-Text, Relevanzscore, sortiert nach Relevanz.

**Geliefert:** Kein eigener PR verknüpft. `stateReason` ist „completed", nicht „not planned" — das Issue wurde also nicht verworfen, sondern vermutlich zusammen mit #12 in einem gemeinsamen PR erledigt und dabei fälschlich nicht separat verlinkt (kein „Closes #11" im PR-Body von #36). Prüfung im Code: `QueryService.java` wurde laut `git log --diff-filter=A` im selben Commit „feat(query): implement LLM answer generation with source references" (PR #36, schließt #12) neu angelegt. Das bedeutet: Die Retrieval-Funktionalität aus #11 wurde faktisch als Teil von PR #36 mitgeliefert, nicht als eigenständiger PR — eine Zuordnungslücke in den GitHub-Daten, kein tatsächlich fehlendes Feature.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryService.java` existiert im heutigen Worktree.

**Themen:** backend, retrieval, rag, pgvector, dokumentationslücke

---

<a id="issue-12"></a>

## Issue #12 — feat(query): implement LLM answer generation with source references
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, size:L
- PRs: #36 (2026-02-26)

**Laut Issue:** Generierungs-Komponente der RAG-Pipeline: `ChatModel`-Bean (OpenAI/Ollama, unabhängig vom Embedding-Provider konfigurierbar), `AnswerGenerationService` mit System-/User-Prompt-Konstruktion, Wiring in `QueryService` (Retrieval → Generation), Fehlerbehandlung für LLM-Fehler (Timeout, Rate Limit).

**Geliefert:** PR #36 liefert die Anforderung vollständig: `AnswerGenerationService`, `QueryService`, `QueryController`, `QueryConfiguration`, Fehlerbehandlung für `TransientAiException` (503) und `NonTransientAiException` (502), konfigurierbare Temperature/MaxTokens, Unit- und Integrationstests. Deckt zugleich #11 (Retrieval) implizit mit ab, siehe Baustein zu Issue #11.

**Auffälligkeit — Fehlzuordnung in den Daten:** Die Chunk-Daten weisen zusätzlich #286 und #291 als verknüpfte PRs aus. Beide betreffen tatsächlich ein völlig anderes Thema — das Tagesreport-CI-Skript (`.github/scripts/daily_report.py`, `docs/tagesreport.md`), nicht die LLM-Antwortgenerierung. Der PR-Body von #291 erklärt die Ursache selbst: In #286 wurden Test-Beispieltexte wie „`fixes #12 und Closes #13`" in der eigenen PR-Checkliste fälschlich als echte `Closes #N`-Referenzen ausgewertet, wodurch #286 (und in der Folge #291, dessen Body #286 zitiert) automatisiert mit #12 (und #13, #99, #221) verknüpft wurde, obwohl inhaltlich kein Bezug besteht. #291 behebt genau diesen Fehler in der PR-Zuordnungslogik des Report-Skripts. Für die Leistungsinventur zählt daher **nur #36** als tatsächlicher Liefer-PR von Issue #12.

**Verifikation:** `backend/src/main/java/io/opaa/query/AnswerGenerationService.java` und `QueryConfiguration.java` existieren weiterhin im Worktree.

**Themen:** backend, generation, rag, llm, dokumentationslücke, ci

---

<a id="issue-13"></a>

## Issue #13 — feat(api): replace mock endpoints with real implementation
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, size:M
- PRs: #38 (2026-02-26)

**Laut Issue:** Mock-Controller aus #8 durch echte Implementierungen ersetzen, die `QueryService`/`IndexingJobService` aufrufen; API-Vertrag (OpenAPI-Spec, DTOs) unverändert lassen; Mock-Profil weiterhin funktionsfähig halten; Request-Logging (Methode, Pfad, Antwortzeit) ergänzen.

**Geliefert:** PR #38 liefert nur den Request-Logging-Teil der Anforderung — einen `RequestLoggingFilter`, der Methode, Pfad, Statuscode und Antwortzeit für `/api/`-Requests protokolliert, plus Aufräumen einer ungenutzten Konfigurationseigenschaft. Die eigentliche Kernanforderung („Mock-Controller durch echte Implementierung ersetzen") ist im PR-Body nicht beschrieben und auch nicht in der Dateiliste erkennbar — sie wurde vermutlich in #34 (Indexing, `IndexingController`) und #36 (`QueryController`) bereits miterledigt, die beide reale, nicht-Mock-Controller einführten. #38 schließt das Issue formal über „Closes #13", deckt inhaltlich aber nur den Logging-Teilaspekt ab; der Rest war zum Zeitpunkt des Schließens bereits durch vorangegangene PRs erfüllt.

**Auffälligkeit — Fehlzuordnung in den Daten:** Wie bei #12 sind zusätzlich #286 und #291 verknüpft. Beide betreffen das Tagesreport-CI-Skript, nicht diese Issue. Ursache laut PR-Body von #291: Testbeispieltexte in #286 („`fixes #12 und Closes #13`") wurden fälschlich als reale `Closes #N`-Referenzen ausgewertet. Für die Inventur zählt daher **nur #38** als Liefer-PR von Issue #13.

**Verifikation:** `backend/src/main/java/io/opaa/api/RequestLoggingFilter.java` existiert weiterhin im Worktree. `IndexingController`/`QueryController` (real, nicht Mock) existieren ebenfalls und wurden laut Historie in #34/#36 eingeführt.

**Themen:** backend, api, logging, dokumentationslücke, ci

---

<a id="issue-14"></a>

## Issue #14 — feat(ui): implement chat interface with source references and feedback placeholders
- Geschlossen: 2026-02-20 (completed)
- Labels: enhancement, mvp, frontend, size:L
- PRs: #27 (2026-02-20)

**Laut Issue:** Chat-Q&A-Screen mit Nachrichtenverlauf, Quellenkarten (Dateiname, Relevanz, Textauszug), Feedback-Buttons (nur visuell) und Access-Level-Badges (Public/Internal/Confidential, statisch). Entwicklung gegen MSW-Mocks, Ladezustand, Fehlerzustand, Auto-Scroll, responsives Layout.

**Geliefert:** PR #27 liefert genau das plus zusätzlich Routing (Chat/Documents/Settings), Zustand-Store für Chat- und UI-State, eigenes dunkles MUI-Theme mit selbst gehosteten Ressourcen (ADR-0004, kein externes CDN) und Design-Referenzdateien unter `docs/design/`. Kein Abweichen vom Issue-Umfang, eher Erweiterung um Infrastruktur (Router, Stores, Theme), die für die spätere Entwicklung gebraucht wurde.

**Verifikation:** `ChatPage.tsx` existiert weiterhin (`frontend/src/pages/ChatPage.tsx`). Die einzelnen PR-Komponenten `SourceCard.tsx` und die ursprüngliche `MessageBubble`-Quellendarstellung wurden seither im Rahmen von Issue #37 und einer späteren Umstellung auf Fußnoten (`SourceFootnotes.tsx`, `SourceEvidenceDrawer.tsx`) ersetzt — die Grundstruktur (Chat, Nachrichtenliste, Eingabe) besteht fort, die Quellendarstellung ist mehrfach weiterentwickelt worden.

**Themen:** frontend, chat-ui, mvp, quellenreferenzen

---

<a id="issue-15"></a>

## Issue #15 — feat(ui): add admin sidebar with indexing controls
- Geschlossen: 2026-02-20 (completed)
- Labels: enhancement, mvp, frontend, size:S
- PRs: #31 (2026-02-20)

**Laut Issue:** Admin-Sidebar getrennt vom Chat-Bereich mit Button „Index Documents", Status-Polling alle 2–3s, Fortschrittsanzeige, Snackbar-Benachrichtigungen bei Erfolg/Fehler, Button während Indizierung deaktiviert.

**Geliefert:** PR #31 liefert genau den beschriebenen Umfang: `AdminDrawer`, `AdminDrawerToggle`, `IndexingSnackbar`, Polling alle 2s, stateful MSW-Mocks für den Trigger-Ablauf. Keine Abweichung vom Issue.

**Verifikation:** `AdminDrawer.tsx`/`AdminDrawerToggle.tsx` existieren im heutigen Code nicht mehr (`frontend/src/components/admin/` enthält nur noch `BrandingPreview.tsx` und `IndexingSnackbar.tsx`). Git-Historie zeigt, dass die Indizierung seit `feat(indexing): Indizierungsläufe auf wählbare Zielbibliothek umstellen` und PR #500 auf ein Bibliotheks-Modell (`/api/v1/libraries/{id}/indexing`) umgestellt wurde — die einfache globale Admin-Sidebar wurde durch die bibliotheksbezogene Verwaltung abgelöst. `IndexingSnackbar` besteht als einziges Überbleibsel fort.

**Themen:** frontend, admin-ui, indexing, mvp

---

<a id="issue-16"></a>

## Issue #16 — chore: create Docker Compose deployment for full stack
- Geschlossen: 2026-02-20 (completed)
- Labels: mvp, backend, frontend, size:L
- PRs: #33 (2026-02-20)

**Laut Issue:** Vollständige Docker-Compose-Konfiguration mit drei Diensten (postgres, backend, frontend/Nginx), Multi-Stage-Dockerfiles, `.env.example` mit allen Umgebungsvariablen, Deployment-Dokumentation, persistentes DB-Volume.

**Geliefert:** PR #33 liefert genau das: `backend/Dockerfile`, `frontend/Dockerfile`, Nginx-Reverse-Proxy-Konfiguration, `.env.example`, `docs/deployment.md`. Keine Abweichung vom Issue.

**Verifikation:** `docker-compose.yml` existiert weiterhin und wurde deutlich erweitert — heute sind sieben Dienste definiert (`postgres`, `backend`, `frontend`, `keycloak`, `demo-corpus`, `demo-presse` sowie das Daten-Volume `opaa-postgres-data`). Die drei ursprünglichen Kern-Dienste bestehen fort, Keycloak (Auth) und Demo-Korpus-Dienste kamen später hinzu.

**Themen:** deployment, docker-compose, mvp

---

<a id="issue-17"></a>

## Issue #17 — test: end-to-end integration tests and MVP verification
- Geschlossen: 2026-02-26 (completed)
- Labels: mvp, backend, frontend, size:L
- PRs: #39 (2026-02-26)

**Laut Issue:** Backend-Integrationstests mit Testcontainers (Indexierung von Markdown/PDF/DOCX, Query-Flow, OpenAI/Ollama-Konfiguration), GitHub-Actions-CI-Pipeline (Backend + Frontend), `docs/MVP-VERIFICATION.md` mit Zuordnung aller 8 MVP-Erfolgskriterien zu Prüfmethoden, aktualisiertes `AGENTS.md`.

**Geliefert:** PR #39 liefert Integrationstests (`DocumentIndexingIntegrationTest`, `ProviderConfigurationTest`, `MixedProviderConfigurationTest`, `OpenAiIntegrationTest`), erweiterte CI-Pipeline um einen `backend-integration`-Job und Prettier-Check, sowie `docs/MVP-VERIFICATION.md`. Zusätzlich, nicht im Issue gefordert: Vereinheitlichung der Zeilenenden (CRLF/LF) über `.gitattributes` und `.editorconfig`.

**Verifikation:** `.github/workflows/ci.yml` existiert weiterhin und wurde erheblich erweitert (Change-Detection je Backend/Frontend, weitere Jobs). `docs/MVP-VERIFICATION.md` existiert im heutigen Repo nicht mehr — laut Git-Log im Commit „docs: Einstieg und Umsetzungsstand auf die neue Ausrichtung angleichen" entfernt, im Zuge einer Doku-Neuausrichtung (heute u.a. `docs/STATUS.md`, `docs/USE-CASES.md`).

**Themen:** ci, tests, mvp, dokumentation

---

<a id="issue-18"></a>

## Issue #18 — feat: implement OPAA MVP (Epic)
- Geschlossen: 2026-02-28 (completed)
- Labels: epic, mvp
- PRs: keine (Epic, keine eigene Umsetzung)

**Laut Issue:** Epic zur Bündelung der gesamten MVP-Umsetzung (docs/MVP.md): Q&A über indizierte Dokumente per Web-UI mit Quellenreferenzen. Führt Phasen 1–5 mit den Einzeltickets #6–#17, #19, #23 sowie Polish-Tickets (#37, #40–#44, #47, #49, #50, #53) als Checkliste.

**Geliefert:** Kein eigener PR — das Epic bündelt ausschließlich die verlinkten Einzeltickets, die jeweils eigenständig gemergt wurden (siehe entsprechende Bausteine #14–#17, #19, #23, #37 u.a.). Der Issue-Body enthält eine Abschluss-Verifikationstabelle (Stand 2026-02-28), die alle 8 MVP-Erfolgskriterien als erfüllt ausweist: Indizierung, Q&A-Flow, Quellenanzeige, duale LLM-Unterstützung (OpenAI/Ollama), getrennte Konfiguration für Chat/Embedding, Docker Compose (3 Dienste), lokale Entwicklung ohne Docker (Mock-Profil/MSW), UI-Platzhalter (Feedback-Buttons, Access-Level-Badges).

**Verifikation:** Als Epic kein eigenständiger Code-Verifikationsgegenstand; die einzelnen referenzierten Tickets sind separat verifiziert (siehe Bausteine #14–#17, #19, #23, #37). Viele der zum Abschlusszeitpunkt genannten Komponenten (AdminDrawer, IndexingController, SourceCard, MVP-VERIFICATION.md) wurden seither im Rahmen der Weiterentwicklung (Bibliotheks-Modell, Spaces, Fußnoten-Zitate) ersetzt oder entfernt — die MVP-Grundarchitektur (Indexing → Vektorsuche → LLM-Antwort mit Quellen) besteht aber fort.

**Themen:** epic, mvp, projektsetup, dokumentation

---

<a id="issue-19"></a>

## Issue #19 — docs: update ADR-0002 with finalized technology decisions
- Geschlossen: 2026-02-18 (completed)
- Labels: documentation, mvp
- PRs: #20 (2026-02-18)

**Laut Issue:** ADR-0002 von „Proposed" auf „Accepted" setzen und finalisierte Technologieentscheidungen dokumentieren: Gradle 9.3.1 statt Maven, Package `io.opaa` statt `com.opaa`, Spring AI 1.1.2, MUI 7.3.8, Liquibase, Vitest+RTL, MSW, Testcontainers, GitHub Actions.

**Geliefert:** PR #20 setzt genau diese Änderungen um — Status auf „Accepted", alle genannten Technologieentscheidungen ergänzt, Maven-/`com.opaa`-Referenzen entfernt. Keine Abweichung vom Issue.

**Verifikation:** `docs/decisions/0002-mvp-technology-stack.md` existiert weiterhin im Repo. Die dort dokumentierten Versionsstände (Gradle 9.3.1, Spring AI 1.1.2) sind laut AGENTS.md/Build-Doku inzwischen weiterentwickelt (aktuell Gradle 9.6.1, Spring Boot 4.1.0, Spring AI 2.0.0) — das ADR selbst wurde nicht mehr aktualisiert, dokumentiert also den Stand zum MVP-Zeitpunkt, nicht den heutigen.

**Themen:** dokumentation, adr, projektsetup, mvp

---

<a id="issue-23"></a>

## Issue #23 — chore: set up GitHub Actions CI pipeline
- Geschlossen: 2026-02-18 (completed)
- Labels: mvp, setup, size:M, ci
- PRs: #24 (2026-02-18), #25 (2026-02-19)

**Laut Issue:** Workflow `.github/workflows/ci.yml` mit parallelen Jobs für Backend (Java 21 + Gradle, `./gradlew build`) und Frontend (Node 22 + npm, lint/test/build), Trigger auf Push/PR gegen `main`, Gradle-/npm-Caching.

**Geliefert:** PR #24 aktualisiert zunächst nur `AGENTS.md` (Tech-Stack- und Build-Befehle-Abschnitt) als Vorbereitung; PR #25 liefert den eigentlichen Workflow mit den zwei parallelen Jobs wie gefordert (Backend: Java 21/Temurin, Gradle-Cache; Frontend: Node 20 statt der im Issue genannten Node 22, npm-Cache). Kleine Abweichung: Node-Version im ersten Wurf 20 statt 22.

**Verifikation:** `.github/workflows/ci.yml` existiert weiterhin und ist seither erheblich gewachsen (Change-Detection je Bereich, zusätzlicher `backend-integration`-Job, weitere Workflows wie `e2e.yml`, `daily-report.yml`, `retrieval-regression.yml`, `demo-smoke.yml`). Die ursprüngliche Zwei-Job-Struktur bildet die Grundlage der heutigen, deutlich ausgebauten Pipeline.

**Themen:** ci, github-actions, projektsetup, mvp

---

<a id="issue-29"></a>

## Issue #29 — feat: Add user document upload with personal workspace and cross-workspace sharing to product vision
- Geschlossen: 2026-02-20 (completed)
- Labels: documentation, enhancement, size:S
- PRs: #30 (2026-02-20)

**Laut Issue:** Reine Dokumentationserweiterung der Produktvision um einen neuen Use Case: nutzergesteuerter Dokumenten-Upload mit persönlichem Workspace („My Documents") und arbeitsbereichsübergreifender Freigabe ohne Duplizierung (zusätzliche `workspace_id`-Tags statt Kopien). Sechs Dokumente sollten konsistent aktualisiert werden (VISION.md, CONCEPTS.md, INDEX.md sowie drei Feature-Specs).

**Geliefert:** PR #30 aktualisiert genau die sechs genannten Dateien konsistent: Personal-Workspace-Konzept, Cross-Workspace-Sharing, Storage-Backend-Abstraktion, neue API-Endpunkte (Upload/Share/my-uploads) als Dokumentation. Reine Konzeptarbeit, keine Implementierung — wie im Issue vorgesehen.

**Verifikation:** `docs/VISION.md` und `docs/CONCEPTS.md` existieren weiterhin. Das Konzept „Workspace" wurde im Projekt später zu „Space" umbenannt (`feat(space)!: Workspace in Space umbenennen, Organisationsgrenze und neue Space-Rollen einführen`), die hier dokumentierten Konzepte (persönlicher Bereich, Freigabe, Storage-Backend) leben in der heutigen Space-/Library-Architektur fort, wenn auch unter neuer Terminologie. Kein Abgleich der Feindetails gegen den heutigen Stand vorgenommen (reine Vision-Doku, kein Code-Bezug).

**Themen:** dokumentation, vision, spaces, upload

---

<a id="issue-35"></a>

## Issue #35 — feat: Erweiterte Job-Status API (Status pro Job, Liste laufender Jobs)
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine

**Laut Issue:** Aus dem Review von #34 (Document Indexing Pipeline): `GET /api/v1/indexing/jobs` (Liste aller/laufender Jobs mit Pagination und Statusfilter) und `GET /api/v1/indexing/jobs/{id}` (Status eines einzelnen Jobs), da die damalige API nur den letzten Job zurückgab.

**Geliefert:** Kein eigener PR verlinkt — das Issue wurde nach Code-Prüfung ohne dedizierte Umsetzung geschlossen. Laut Schließkommentar (2026-08-21, per `gh issue view --comments` eingeholt) sind die Anforderungen „seit ADR-0018 in besserer Form erfüllt": Indizierung ist inzwischen bibliotheksbezogen (`POST /{libraryId}/indexing`, `GET /{libraryId}/indexing/status`, `GET /{libraryId}/indexing/runs`), pro Lauf gibt es Status, Zähler und vollständiges Ereignisprotokoll (Bezug #604), Bibliotheken laufen parallel mit Sperre je Bibliothek (Migration 028), verwaiste RUNNING-Läufe werden bereinigt (#649). Eine Statusfilterung wurde bei maximal 10 aufbewahrten Läufen je Bibliothek als nicht lohnend bewertet. Eine organisationsweite Übersicht aller laufenden Läufe (für die Systemverwaltung) ist explizit **nicht** umgesetzt und bewusst nicht beauftragt.

**Verifikation:** Bestätigt — im heutigen Code existiert keine globale `GET /api/v1/indexing/jobs`-Route; stattdessen `backend/src/main/resources/openapi/opaa-api.yaml` definiert `/api/v1/libraries/{libraryId}/indexing`, `/api/v1/libraries/{libraryId}/indexing/status` und `/api/v1/libraries/{libraryId}/indexing/runs`. Das ursprünglich in #10/#34 angelegte `IndexingController.java` existiert nicht mehr (durch die Bibliotheks-Architektur ersetzt). Diskrepanz „completed ohne PR" ist damit aufgeklärt: Das Issue wurde als durch spätere, anders benannte Arbeit (Bibliotheks-Indexing-API) faktisch erledigt eingestuft, nicht separat implementiert — die organisationsweite Job-Übersicht bleibt eine offene Lücke.

**Themen:** backend, indexing, api, retrofit-abgleich

---

<a id="issue-37"></a>

## Issue #37 — feat(query): filter source references by actual LLM citations
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend, frontend, size:M
- PRs: #55 (2026-02-27)

**Laut Issue:** Aus dem Review von PR #36: Bisher werden alle per Ähnlichkeitssuche gefundenen Quellen als Referenzen angezeigt, unabhängig davon, ob das LLM sie tatsächlich zitiert hat. Vorschlag: LLM-Antwort auf zitierte Dateinamen parsen, `SourceReference` um ein `cited`-Flag erweitern, zitierte Quellen prominent und unzitierte gedimmt/eingeklappt oder gar nicht anzeigen.

**Geliefert:** PR #55 geht über den Vorschlag hinaus: strukturiertes Zitatformat `【source: document_id#chunk_index | file_name】`, per Systemprompt erzwungen; `CitationParser` extrahiert zitierte Dokument-IDs per Regex; `SourceReference` um `cited`, `matchCount`, `indexedAt` erweitert (statt `excerpt` entfernt); zitierte Quellen werden direkt angezeigt, unzitierte in einem einklappbaren Bereich in `MessageBubble`. Umfang wie gefordert, technisch solider gelöst als im Issue skizziert (kein reines Text-Parsing von Dateinamen, sondern strukturierte Marker).

**Verifikation:** `backend/src/main/java/io/opaa/query/CitationParser.java` existiert weiterhin im heutigen Code. Die Frontend-Seite (`SourceCard.tsx`, prominente/eingeklappte Darstellung in `MessageBubble.tsx`) wurde inzwischen abgelöst: `git log` zeigt `feat(frontend): Antworten mit Fußnoten-Fundstellen statt Quellkarten` — die Quellenanzeige läuft heute über `SourceFootnotes.tsx`/`SourceEvidenceDrawer.tsx` statt über Quellkarten. Das Kernprinzip „nur tatsächlich zitierte Quellen hervorheben" besteht konzeptionell fort, die konkrete UI-Umsetzung wurde ersetzt.

**Themen:** retrieval, quellenreferenzen, backend, frontend, mvp

---

<a id="issue-40"></a>

## Issue #40 — feat(frontend): Markdown-Renderer für LLM-Antworten
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, frontend
- PRs: #45 (2026-02-26)

**Laut Issue:** LLM-Antworten wurden als Plain-Text dargestellt, obwohl sie häufig Markdown enthalten (Überschriften, Listen, Code-Blöcke, Links). Gefordert war die Integration einer Markdown-Rendering-Bibliothek (z.B. `react-markdown`) mit Syntax-Highlighting für Code-Blöcke.

**Geliefert:** PR #45 integriert `react-markdown` + `remark-gfm` + `rehype-highlight` in einer neuen `MarkdownRenderer`-Komponente. Assistant-Nachrichten werden gerendert, User-Nachrichten bleiben bewusst Plain-Text. Zusätzlich (nicht explizit im Issue gefordert): Quellenreferenzen am Antwortende (`(dateiname.pdf)`) werden als separates "Quelle:"-Label dargestellt — eine Vorwegnahme von Aspekten aus #42/#37.

**Verifikation:** `frontend/src/components/chat/MarkdownRenderer.tsx` und `MessageBubble.tsx` existieren im heutigen Code weiterhin.

**Themen:** frontend, chat-ui, markdown, quellenanzeige

---

<a id="issue-41"></a>

## Issue #41 — feat(frontend): Loading-Indicator während Dokument-Indizierung
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, frontend
- PRs: #52 (2026-02-27)

**Laut Issue:** Während der Dokument-Indizierung fehlte jegliches visuelles Feedback. Gefordert war ein Loading-Indicator (Spinner/Progress-Bar) mit klaren Zuständen Idle → Indizierung läuft → Abgeschlossen/Fehler, basierend auf einem vom Backend abgefragten Indizierungsstatus.

**Geliefert:** PR #52 liefert dies zusammen mit Issue #44 (Backend-Async-Umbau) in einem gemeinsamen PR: bestimmter Progress-Bar ("X von Y Dokumenten indiziert") in einer neuen `AdminDrawer`-Komponente, gespeist aus dem neu asynchron gewordenen Backend-Indizierungsstatus (inkl. Job-Tracking, HTTP 202/409). Deckt damit sowohl Frontend- als auch Backend-Seite ab.

**Verifikation:** `AdminDrawer.tsx` existiert im heutigen Code nicht mehr (`find` liefert nichts); an seiner Stelle steht heute `frontend/src/components/admin/IndexingSnackbar.tsx`. Auf Backend-Seite ist die Indizierung inzwischen auf ein bibliotheksbezogenes Modell umgestellt (`git log` zeigt u.a. #500 "Indizierungsanstoß je Bibliothek", #473 Executor-Registry) — die Grundidee (asynchrones Feedback zum Indizierungsfortschritt) lebt fort, nur in umgebauter Form.

**Themen:** frontend, indexing, progress-feedback, admin-ui

---

<a id="issue-42"></a>

## Issue #42 — feat: Distinct-Darstellung der Quellen ohne Duplikate
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, frontend
- PRs: #46 (2026-02-26)

**Laut Issue:** Quellenangaben in Antworten konnten Duplikate enthalten — dasselbe Dokument mehrfach gelistet. Gefordert war Deduplizierung, bevorzugt auf Backend-/API-Ebene, ggf. mit Gruppierung nach Score/Relevanz, sodass die relevanteste Referenz pro Dokument angezeigt wird.

**Geliefert:** PR #46 dedupliziert in `QueryService.mapSources()` per `Collectors.toMap()` gruppiert nach `fileName`, behält jeweils die Referenz mit dem höchsten `relevanceScore`, stabile Reihenfolge über `LinkedHashMap`. Genau die im Issue vorgeschlagene Backend-Lösung, keine Frontend-Deduplizierung nötig. Zusätzlich wurde die RAG-Feature-Spec (`data-indexing-rag.md`) entsprechend dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryService.java` enthält weiterhin `mapSources(...)` mit Dedup-Logik nach `fileName`; ein Codekommentar referenziert sogar eine spätere Review-Klarstellung ("#639 review: the dedupe key is fileName, not document_id"), was zeigt, dass die Logik im Kern bis heute Bestand hat und weiterentwickelt wurde.

**Themen:** backend, retrieval, quellenanzeige, deduplizierung

---

<a id="issue-43"></a>

## Issue #43 — feat: In-Memory Chat-Gedächtnis für Folgefragen (MVP)
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend, frontend
- PRs: #56 (2026-02-27)

**Laut Issue:** Der Chat verarbeitete jede Anfrage isoliert ohne Konversationskontext. Gefordert war ein flüchtiges In-Memory-Gedächtnis per `conversationId`, Nutzung von Spring AIs `InMemoryChatMemory`/`MessageChatMemoryAdvisor`, `conversationId` als API-Parameter, Frontend-Verwaltung des States inkl. Neuer-Chat-Button. DB-Persistenz war explizit als Follow-up (#54) ausgeklammert.

**Geliefert:** PR #56 setzt das Kernziel um (Folgefragen behalten Kontext, `conversationId` wird generiert/zurückgegeben, Frontend sendet/verwaltet sie, "Neuer Chat"-Button), weicht aber bewusst von der vorgeschlagenen Technik ab: **kein** `MessageChatMemoryAdvisor`, weil dieser die History vor die System-Message setzt und die Antwortqualität verschlechtert — stattdessen manuelle Memory-Verwaltung mit fester Reihenfolge `[SYSTEM, History…, aktuelle Frage]`. Zusätzlich wird bei Folgefragen die erste User-Frage der Vektorsuche vorangestellt, damit die Quellen thematisch relevant bleiben (im Issue nicht vorgesehen, aber sachlich naheliegende Ergänzung).

**Verifikation:** `backend/src/main/java/io/opaa/query/AnswerGenerationService.java` und `frontend/src/stores/chatStore.ts` existieren weiterhin. Chat-Memory ist im heutigen Code über `CaffeineChatMemoryRepository` (`backend/src/main/java/io/opaa/query/`) realisiert — ein Nachfolgeschritt gegenüber der ursprünglichen reinen In-Memory-Lösung, aber weiterhin nicht dauerhaft persistent (siehe #54).

**Themen:** backend, frontend, chat-memory, retrieval, mvp

---

<a id="issue-44"></a>

## Issue #44 — feat(indexing): Asynchrone Dokument-Indizierung mit konfigurierbarem ThreadPool
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend
- PRs: #52 (2026-02-27)

**Laut Issue:** Die Indizierung lief synchron im HTTP-Thread und blockierte den REST-Call bis zum Abschluss. Gefordert war ein `@Async`-Umbau mit konfigurierbarem `ThreadPoolTaskExecutor` (`opaa.indexing.thread-pool.*`), sofortige HTTP-202-Antwort, HTTP 409 bei bereits laufendem Job, sowie MVP-Ansatz "ein Thread pro Job" statt Parallelisierung pro Dokument.

**Geliefert:** PR #52 (gemeinsam mit #41) setzt praktisch alle Punkte um: `AsyncIndexingExecutor` für die Hintergrundarbeit, konfigurierbarer ThreadPool über `opaa.indexing.thread-pool`-Properties, HTTP 202 beim Trigger, HTTP 409 bei Duplikat-Läufen, neue Liquibase-Migration für `documents_total`. Zusätzlich zum Issue-Umfang: inkrementelle Fortschrittszählung über `REQUIRES_NEW`-Transaktionen für sofortige Polling-Sichtbarkeit sowie verbessertes Logging pro Datei.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AsyncIndexingExecutor.java` und `IndexingConfiguration.java` existieren weiterhin. Die konkrete `IndexingController.java` aus der PR-Dateiliste ist im heutigen Baum nicht mehr vorhanden — das Indexing-Subsystem wurde seither auf ein bibliotheksbezogenes Modell mit Executor-Registry umgebaut (`git log` zeigt u.a. #500, #473); die asynchrone Grundarchitektur (ThreadPool, Job-Tracking) besteht aber fort.

**Themen:** backend, indexing, async, threadpool, mvp

---

<a id="issue-47"></a>

## Issue #47 — feat: configurable HTTP/1.1 mode for vLLM and other OpenAI-compatible servers
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp
- PRs: #48 (2026-02-26)

**Laut Issue:** Anfragen an vLLM (Uvicorn/ASGI, nur HTTP/1.1) schlugen mit 400 Bad Request fehl, weil Spring Boots `JdkClientHttpRequestFactory` HTTP/2 (h2c-Upgrade) bevorzugt. Gefordert war eine konfigurierbare Option `opaa.http.force-http1` (Default `false`), die über einen `RestClientCustomizer`-Bean alle Spring-AI-HTTP-Verbindungen auf HTTP/1.1 zwingt.

**Geliefert:** PR #48 setzt genau das um — neue Property `opaa.http.force-http1` (Env: `OPAA_HTTP_FORCE_HTTP1`), Default `false` ohne Verhaltensänderung. Deckt sich vollständig mit dem Issue-Vorschlag, keine Abweichungen.

**Verifikation:** `backend/src/main/java/io/opaa/api/HttpClientConfig.java` existiert weiterhin und enthält `@ConditionalOnProperty(name = "opaa.http.force-http1", havingValue = "true")`.

**Themen:** backend, deployment, vllm, http-konfiguration

---

<a id="issue-49"></a>

## Issue #49 — fix: crypto.randomUUID fails on non-HTTPS connections
- Geschlossen: 2026-02-26 (completed)
- Labels: bug, mvp
- PRs: #51 (2026-02-26)

**Laut Issue:** Beim Zugriff auf das Frontend über HTTP von einer Nicht-Localhost-Adresse (z.B. LAN-IP) schlug das Senden einer Chat-Nachricht mit `crypto.randomUUID is not a function` fehl, da diese Web-Crypto-API nur in sicheren Kontexten (HTTPS/localhost) verfügbar ist. Gefordert war ein `generateId()`-Helper mit Fallback auf Timestamp+Zufallsstring.

**Geliefert:** PR #51 setzt den vorgeschlagenen Fix exakt um und behebt gemeinsam Issue #50 (Server-Bind-Adresse), da beide Probleme denselben LAN-Zugriffs-Anwendungsfall betreffen.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält weiterhin `function generateId(): string { return crypto.randomUUID?.() ?? ... }`, an zwei Stellen zur ID-Erzeugung genutzt.

**Themen:** frontend, bugfix, netzwerkzugriff, chat

---

<a id="issue-50"></a>

## Issue #50 — feat: make server bind address configurable
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp
- PRs: #51 (2026-02-26)

**Laut Issue:** Spring Boot band standardmäßig an `localhost`, wodurch das Backend von anderen Geräten im Netzwerk (Demos, LAN-Tests) nicht erreichbar war. Gefordert war eine konfigurierbare `server.address`-Property über `OPAA_SERVER_ADDRESS` (Default `localhost`, kompatibel zum bisherigen Verhalten).

**Geliefert:** PR #51 (gemeinsam mit #49) setzt genau das um: `server.address: ${OPAA_SERVER_ADDRESS:localhost}` in `application.yml`, zusätzlich `docker-compose.yml`- und `docs/deployment.md`-Anpassungen für den Docker-Kontext (dort Default `0.0.0.0`).

**Verifikation:** `backend/src/main/resources/application.yml` enthält weiterhin `address: ${OPAA_SERVER_ADDRESS:localhost}`.

**Themen:** backend, deployment, netzwerkzugriff, konfiguration

---

<a id="issue-53"></a>

## Issue #53 — feat(indexing): skip unchanged documents using SHA-256 checksum
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend
- PRs: #57 (2026-02-27)

**Laut Issue:** Bei jedem Indexing-Trigger wurden alle Dokumente erneut verarbeitet, auch unveränderte — teuer wegen Parsing/Chunking/Embedding-API-Calls. Gefordert war eine SHA-256-Checksumme pro Dokument (neue Spalte `checksum` auf `documents`), Vergleich vor dem Parsing, Überspringen bei Übereinstimmung und Status `INDEXED`, separates Zählen übersprungener Dokumente (`documents_skipped`) inkl. API-/UI-Anzeige.

**Geliefert:** PR #57 setzt den Vorschlag praktisch vollständig um: neue `ChecksumService` (SHA-256 via `DigestInputStream`), `FileProcessingResult`-Enum (`PROCESSED`/`SKIPPED`), `checksum`-Spalte auf `Document`, `documents_skipped`-Zähler auf `IndexingJob`, Liquibase-Migration `005-add-checksum-and-skipped-columns.yaml`, Frontend-Anzeige "X processed (Y skipped)". Keine wesentlichen Abweichungen vom Issue-Vorschlag.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChecksumService.java`, `Document.java`, `FileProcessingResult.java` und `FileProcessingService.java` existieren weiterhin im heutigen Code.

**Themen:** backend, indexing, checksum, performance

---

<a id="issue-54"></a>

## Issue #54 — feat: Erweitertes Chat-Memory mit Persistenz und Session-Verwaltung
- Geschlossen: 2026-08-15 (not planned)
- Labels: enhancement, backend, frontend
- PRs: keine

**Laut Issue:** Aufbauend auf dem In-Memory Chat-Gedächtnis (#43) sollte das Chat-Memory persistent werden: DB-Tabellen `conversations`/`messages`, DB-backed `ChatMemory`-Implementierung statt `InMemoryChatMemory`, Token-Limit-Management (Kürzen/Zusammenfassen alter Nachrichten), Conversation-CRUD-API (Liste, Details, Löschen, Umbenennen) sowie eine Frontend-Sidebar mit Liste vergangener Konversationen, Fortsetzen, Umbenennen, Löschen und Auto-Titeln.

**Geliefert:** Nicht umgesetzt. Laut Schließungskommentar wurde das Issue als "abgelöst durch #205" geschlossen, im Rahmen der Backlog-Neuausrichtung (`docs/discussions/discussion-backlog-neuausrichtung.md`). Grund: #54 beschreibt dauerhafte Chats nach dem alten MVP-Modell (Konversation gehört einem Nutzer, frei sichtbar, löschbar), während das inzwischen eingeführte Space-/Asset-Modell ein anderes Rechte- und Sichtbarkeitsmodell vorsieht (Chat gehört einem Space, Status PRIVATE → SHARED → WITHDRAWN, kein Löschen sondern protokolliertes Zurückziehen durch Space-Admin). #205 baut die Persistenz auf dieser neuen Grundlage neu auf. Drei Teilaspekte aus #54 sind laut Kommentar in #205 **nicht** enthalten und explizit als offen vermerkt: Kontextfenster-Verwaltung (Kürzen/Zusammenfassen), automatisch generierte Konversationstitel, und die Bedienoberfläche (Liste/Fortsetzen/Umbenennen). Der Kommentar merkt zudem an, dass der Chatverlauf aktuell keinen Page-Reload übersteht.

**Verifikation:** Heute existiert `backend/src/main/java/io/opaa/query/CaffeineChatMemoryRepository.java` — ein Caffeine-Cache-basiertes Chat-Memory, also weiterhin kein DB-persistentes Modell im Sinne von #54. Das bestätigt die im Schließungskommentar beschriebene Ausgangslage.

**Themen:** backend, frontend, chat-memory, persistenz, spaces, backlog-neuausrichtung, not-planned

---

<a id="issue-58"></a>

## Issue #58 — Add static landing page for project website
- Geschlossen: 2026-02-27 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #59 (2026-02-27)

**Laut Issue:** Statische Landingpage (HTML/CSS/JS) im Verzeichnis `page/` als Internetauftritt des Projekts — zweisprachig (DE/EN) mit Sprachumschalter, Link zum GitHub-Repo und zur Demo, responsive, dunkles Theme, Hero/Screenshots/Feature-Karten/Vergleichstabelle/Tech-Stack-Footer.

**Geliefert:** PR #59 liefert genau das beschriebene Set — `page/index.html` plus Screenshots (`chat-interface.png`, `document-browser.png`). Keine erkennbaren Abweichungen vom Issue.

**Verifikation:** `page/index.html` und `page/img/` existieren im Worktree weiterhin, `page/` enthält zusätzlich ein `README.md`. Der Demo-Link wurde später in PR #101 auf `https://demo.opaa.ewerlin.com` angepasst (siehe Issue #100).

**Themen:** doku, landingpage, projektsetup

---

<a id="issue-60"></a>

## Issue #60 — 🔍 Security & Code Review Findings (20 Issues)
- Geschlossen: 2026-08-15 (completed)
- Labels: epic, security
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Sammel-Epic mit 20 Einzelbefunden aus einem Claude-Opus-Code-Review (5 kritische Security-Probleme, 3 High-, 5 Medium- und 7 Low-Priority-Findings) zu CORS, Rate Limiting, Auth, Input-Validierung, Observability, Transaktionsgrenzen, Spotless-Konfiguration, Docker-Tests, ChatMemory-Lifecycle, Error Boundary, Log-Hygiene und diversen Code-Quality-Punkten.

**Geliefert:** Als Epic selbst nichts direkt — die referenzierten Einzelbefunde wurden über die Sub-Issues #61–#76 bearbeitet (siehe dortige Bausteine). Laut Abschlusskommentar (15.08.2026, im Zuge der Backlog-Sichtung) sind alle bis auf einen Punkt erledigt oder bewusst verworfen; offen blieb nur die nutzerbezogene Protokollierung, die als eigener, schärfer geschnittener Themenkomplex in #355/#391–#395 weitergeführt wird.

**Verifikation:** Kein Code-Realitätscheck nötig — reines Sammel-Ticket. Der Abschlusskommentar liefert bereits eine punktgenaue Nachprüfung gegen den damaligen Code.

**Themen:** security, epic, review, sammel-ticket

---

<a id="issue-61"></a>

## Issue #61 — 🚨 [CRITICAL] CORS Wildcard Headers Security Risk
- Geschlossen: 2026-02-28 (completed)
- Labels: backend, size:S, security
- PRs: #80 (2026-02-28)

**Laut Issue:** `CorsConfig.java` erlaubte mit `allowedHeaders("*")` beliebige Header — Risiko für CORS-Bypass und Request Smuggling, besonders vor der geplanten Auth-Einführung. Gefordert: explizite Whitelist (`Content-Type`, `Authorization`, `X-Requested-With`).

**Geliefert:** PR #80 ersetzt den Wildcard exakt wie im Issue vorgeschlagen durch die genannte Whitelist. Keine Abweichung.

**Verifikation:** `backend/src/main/java/io/opaa/api/CorsConfig.java` existiert im heutigen Worktree nicht mehr — die CORS-Konfiguration ist im Zuge der späteren Auth-Einführung (Issue #108, PR #135) nach `backend/src/main/java/io/opaa/auth/SecurityCorsConfig.java` gewandert und dort weiterhin mit expliziter Header-Liste konfiguriert. Die hier gelieferte Whitelist-Logik lebt also fort, nur an anderer Stelle.

**Themen:** security, cors, backend

---

<a id="issue-62"></a>

## Issue #62 — 🚨 [CRITICAL] Missing Rate Limiting on API Endpoints
- Geschlossen: 2026-03-01 (completed)
- Labels: backend, size:M, security
- PRs: #84 (2026-03-01)

**Laut Issue:** Keine Rate Limits auf `/api/v1/query` und `/api/v1/indexing/trigger` — Risiko für LLM-Kosten-Explosion und DoS. Gefordert: konfigurierbares Rate Limiting (Query 10/min, Indexing 1/min), 429-Antworten, externe Konfiguration.

**Geliefert:** PR #84 implementiert Per-IP-Rate-Limiting mit Caffeine-basiertem Sliding Window, konfigurierbar über Umgebungsvariablen und global abschaltbar (`OPAA_RATE_LIMIT_ENABLED=false`), inkl. 429-Antworten und Frontend-Fehleranzeige. Deckt die Anforderungen vollständig ab.

**Verifikation:** `RateLimitConfiguration.java`, `RateLimitFilter.java`, `RateLimitProperties.java`, `RateLimitService.java` existieren unverändert im heutigen Worktree unter `backend/src/main/java/io/opaa/api/`.

**Themen:** security, rate-limiting, backend

---

<a id="issue-63"></a>

## Issue #63 — 🚨 [CRITICAL] No Authentication/Authorization Implementation
- Geschlossen: 2026-08-15 (completed)
- Labels: enhancement, backend, size:L, security, auth
- PRs: keine direkt verknüpft

**Laut Issue:** Alle API-Endpunkte waren öffentlich ohne Authentifizierung/Autorisierung. Gefordert (mehrphasig): Auth-Mechanismus (JWT/OAuth2), geschützte Endpunkte, Nutzerkontext in Services, Frontend-Auth-Flow, Workspace-Isolation auf Datenebene, Basis-RBAC, Audit-Logging mit Nutzerbezug.

**Geliefert:** Kein PR ist direkt gegen dieses Issue verlinkt; erledigt wurde es über das große Folge-Epic #107 (Workspaces & Access Control) mit seinen Unter-Issues (#108 OIDC-Auth, #110 System-Admin-Rollen usw.) und später über das Space/Library-Rechtemodell. Laut Abschlusskommentar (15.08.2026, Backlog-Sichtung) sind alle Punkte bis auf einen erledigt: Authentifizierung (OIDC, `OidcSecurityConfig`, ADR-0005), geschützte Endpunkte, Nutzerkontext (`UserProvisioningFilter`), Frontend-Auth-Flow (`LoginPage`, `AuthCallbackPage`, `ProtectedRoute`, `authStore`) und ein Rollenmodell, das über die ursprüngliche Forderung hinausgeht (SystemRole, Space-Rollen, Asset-Rollen, Gruppen aus Verzeichnisabgleich). Die geforderte „Workspace-Isolation" wurde konzeptionell durch das Space-/Library-Modell abgelöst — die Filterung sitzt direkt in der Vektorsuche (`QueryService`, `LibraryAccessService`), nicht als nachgelagerter Filter. Offen blieb nur die nutzerbezogene Audit-Protokollierung, ausgelagert nach #355 und #391–#395.

**Verifikation:** `backend/src/main/java/io/opaa/auth/OidcSecurityConfig.java` und `UserProvisioningFilter.java` existieren im Worktree; ein eigenständiges `workspace`-Paket gibt es nicht mehr (umbenannt zu `space`, siehe Issue #107-Familie), Audit-Log-Package fehlt tatsächlich noch (bestätigt den offenen Punkt aus dem Abschlusskommentar).

**Themen:** security, auth, epic-abhängig, workspace, spaces

---

<a id="issue-64"></a>

## Issue #64 — 🚨 [CRITICAL] Missing conversationId Input Validation
- Geschlossen: 2026-02-28 (completed)
- Labels: bug, backend, size:S, security
- PRs: #79 (2026-02-28)

**Laut Issue:** `conversationId` in `QueryService` wurde ohne Validierung übernommen — Risiko für Memory Exhaustion und (bei künftiger Persistierung) Injection. Gefordert: Regex-Validierung `^[a-zA-Z0-9-]{1,50}$` mit klarer Fehlermeldung und Tests.

**Geliefert:** PR #79 validiert `conversationId` exakt mit dem vorgeschlagenen Regex in `QueryService` und `MockQueryController`, liefert 400 bei ungültigem Format über `GlobalExceptionHandler`, inkl. Unit- und Integrationstests (auch gegen XSS/SQLi/Path-Traversal-Payloads).

**Verifikation:** Der heutige `QueryService` verwendet `conversationId` als String-Parameter nicht mehr — die Query-API wurde im Zuge der Chat-/Workspace-Einführung auf typisierte `chatId` (UUID) umgestellt (`query(String question, UUID chatId, UUID currentUserId, ...)`), wodurch die ursprüngliche Regex-Validierung gegenstandslos wurde: eine UUID ist durch den Typ selbst validiert. Der damalige Fix ist damit nicht mehr im Code sichtbar, aber das zugrunde liegende Risiko (freiform String als Schlüssel) ist durch die Typänderung strukturell mit erledigt.

**Themen:** security, input-validierung, backend, query

---

<a id="issue-65"></a>

## Issue #65 — 🚨 [CRITICAL] No Observability (Metrics, Tracing, Health Checks)
- Geschlossen: 2026-03-01 (completed)
- Labels: enhancement, backend, size:L
- PRs: #85 (2026-03-01)

**Laut Issue:** Keine Metriken, Tracing oder Downstream-Health-Checks. Gefordert: Spring Boot Actuator + Micrometer/Prometheus, Health-Indikatoren für OpenAI/pgvector, Metriken für Query-Latenz, LLM-Token/Kosten, Indexierungsdurchsatz, aktive Konversationen; optional Tracing und Grafana-Dashboard.

**Geliefert:** PR #85 liefert Actuator + Micrometer-Prometheus-Registry, drei generische Health-Indikatoren (`ChatHealthIndicator`, `EmbeddingsHealthIndicator`, `VectorStoreHealthIndicator` — providerunabhängig statt OpenAI-spezifisch) und Custom-Metriken (`opaa.query.duration`, `opaa.query.count`, `opaa.query.tokens`, `opaa.indexing.documents`, `opaa.conversations.active`). Tracing und ein Grafana-Dashboard-Template wurden nicht geliefert — Abweichung vom „Definition of Done", aber im Issue selbst schon als „Optional" bzw. ohne Verpflichtung markiert.

**Verifikation:** `backend/src/main/java/io/opaa/observability/` mit den genannten Health-Indikatoren existiert im Worktree. Kein Grep-Hinweis auf ein Grafana-Dashboard-Template im Repo.

**Themen:** observability, metrics, backend, prometheus

---

<a id="issue-66"></a>

## Issue #66 — ⚠️ [HIGH] Missing Transaction Boundaries in QueryService
- Geschlossen: 2026-02-28 (completed)
- Labels: bug, backend, size:S, security
- PRs: #81 (2026-02-28)

**Laut Issue:** `QueryService.query()` führte mehrere DB-Operationen ohne Transaktionsklammer aus — Risiko für Race Conditions bei parallelem Indexing. Gefordert: `@Transactional(readOnly = true)` auf der Methode.

**Geliefert:** PR #81 ergänzt genau diese Annotation plus einen reflektionsbasierten Test, der ihre Anwesenheit prüft.

**Verifikation:** Abweichung, bewusst und dokumentiert: Die Annotation ist im heutigen `QueryService.query()` **nicht mehr** vorhanden. Der Javadoc an der Methode erklärt unter Verweis auf Issue #525 (Review Runde 2, Finding A) und #299, dass genau diese `@Transactional`-Klammer später zu einem Connection-Pool-Deadlock führte: Sie hielt eine JDBC-Verbindung über die gesamte Methodendauer offen — inklusive des LLM-Aufrufs, dem langsamsten Schritt —, während ein nachgelagerter Schreibzugriff (`ChatService#appendTurn`) eine zweite Verbindung brauchte. Bei mehr als 10 gleichzeitigen Anfragen (Hikari-Pool-Default) blockierte sich das System selbst. Der hier gelieferte Fix war also fachlich richtig gegen das ursprüngliche Risiko, erzeugte aber ein neues, schwerwiegenderes Problem und wurde revertiert zugunsten kurzlebiger, einzeln transaktionaler Aufrufe.

**Themen:** backend, transaktionen, deadlock, query, review-nachwirkung

---

<a id="issue-67"></a>

## Issue #67 — ⚠️ [HIGH] Spotless Config Missing (ADR-0002 Violation)
- Geschlossen: 2026-02-28 (completed)
- Labels: bug, backend, setup, size:S
- PRs: keine

**Laut Issue:** `spotless { }`-Block in `backend/build.gradle.kts` war leer, `./gradlew spotlessCheck` prüfte nichts — Verstoß gegen ADR-0002/ADR-0003. Gefordert: `googleJavaFormat()`-Konfiguration, CI-Anbindung, Contributing-Guide-Update.

**Geliefert:** Kein PR verknüpft. Laut Autorenkommentar (bigpuritz, beim Schließen) war der Befund zum Zeitpunkt der Prüfung bereits gegenstandslos — die Spotless-Konfiguration existierte inzwischen (vermutlich durch eine andere, nicht direkt verlinkte Änderung) bereits vollständig mit `googleJavaFormat()`, `removeUnusedImports()`, `trimTrailingWhitespace()`, `endWithNewline()` sowie einer `kotlinGradle`-Sektion. Das Issue wurde als „already resolved" geschlossen, ohne dass ein eigener PR dafür nötig war.

**Verifikation:** `backend/build.gradle.kts` enthält im heutigen Worktree eine vollständige `spotless { }`-Konfiguration mit `java { googleJavaFormat() ... }` und `kotlinGradle { ... }` — deckt sich mit dem im Schließkommentar zitierten Stand.

**Themen:** ci, code-style, backend, projektsetup

---

<a id="issue-68"></a>

## Issue #68 — Docker Build Skips Tests
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, size:S, ci
- PRs: keine

**Laut Issue:** Das Backend-`Dockerfile` baut mit `./gradlew bootJar --no-daemon -x test`, überspringt also Tests. Gefordert war ein Build, der Tests ausführt und bei Fehlschlag abbricht, notfalls per Multi-Stage-Aufbau.

**Geliefert:** Kein Code-Fix am Dockerfile. Laut Schließungskommentar ist das Anliegen durch das inzwischen etablierte CI-Gating obsolet geworden: Jeder PR muss Build und Tests als Required Checks bestehen, gemerged wird nur auf grünem Stand, und das Image wird aus genau diesem geprüften `main`-Stand gebaut (`Publish Images`). Zusätzlich sei das Ausführen der Integrationstests im Docker-Build selbst inzwischen praktisch unmöglich, weil sie Testcontainers (Docker-in-Docker im Image-Build) benötigen. Das im Issue beschriebene Risiko — ungeprüfte Images gelangen in Produktion — bestehe im heutigen Setup nicht mehr, weil die Prüfung vor dem Merge stattfindet statt beim Image-Bau.

**Verifikation:** `backend/Dockerfile` enthält weiterhin `RUN ./gradlew bootJar --no-daemon -x test` — die Zeile aus dem Issue ist unverändert im Code vorhanden. Die Schließung war eine bewusste Risikobewertung (CI-Gating vor dem Merge ersetzt Tests im Image-Build), kein technischer Fix am benannten Dockerfile.

**Themen:** ci, deployment, docker

---

<a id="issue-69"></a>

## Issue #69 — 🟡 [MEDIUM] ChatMemory Lifecycle Management Unclear
- Geschlossen: 2026-02-28 (completed)
- Labels: enhancement, backend, size:M
- PRs: #83 (2026-02-28)

**Laut Issue:** `ChatMemory` hatte keine erkennbare Eviction-Policy — Risiko für unbegrenztes Speicherwachstum bzw. OOM bei Langzeitbetrieb. Gefordert: TTL- oder LRU-basierte Begrenzung, Metriken, Persistenzstrategie klären.

**Geliefert:** PR #83 ersetzt das unbegrenzte `InMemoryChatMemoryRepository` durch `CaffeineChatMemoryRepository` mit LRU-Eviction (max. 50 Sessions), TTL (60 min Inaktivität) und begrenztem Nachrichtenfenster (max. 20 Nachrichten je Session), inkl. 8 Unit-Tests. Deckt die Kernanforderung ab; explizite Speicher-Metriken (separat von den in #65 gelieferten Query-/Indexing-Metriken) wurden nicht ergänzt.

**Verifikation:** `backend/src/main/java/io/opaa/query/CaffeineChatMemoryRepository.java` existiert im heutigen Worktree weiterhin.

**Themen:** backend, chat, memory-leak, caching

---

<a id="issue-70"></a>

## Issue #70 — 🟡 [MEDIUM] Error Boundary Component Not Used
- Geschlossen: 2026-02-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #82 (2026-02-28)

**Laut Issue:** `ErrorBoundary`-Komponente existierte, wurde aber nirgends eingebunden — Risiko für „White Screen of Death" bei React-Fehlern. Gefordert: Einbindung in `main.tsx`/`App.tsx`, nutzerfreundliche Fehleranzeige, Logging, Tests.

**Geliefert:** PR #82 erweitert die bestehende `ErrorBoundary` um eine aufklappbare Detailsektion (Fehlermeldung + Stacktrace), ein Fehler-Icon und 5 Tests. Laut PR-Beschreibung war die Komponente zu diesem Zeitpunkt aber offenbar schon eingebunden — der PR-Fokus liegt auf der Detaildarstellung, nicht auf dem Einbinden selbst. Kein Hinweis auf eine separate Backend-Fehlerreporting-Anbindung (im Issue nur als „consider").

**Verifikation:** `frontend/src/App.tsx` importiert und verwendet `ErrorBoundary` (`<ErrorBoundary>...</ErrorBoundary>`) im heutigen Worktree; `frontend/src/components/ErrorBoundary.tsx` existiert weiterhin.

**Themen:** frontend, error-handling, react

---

<a id="issue-71"></a>

## Issue #71 — 🟡 [MEDIUM] Sensitive Error Information in Logs
- Geschlossen: 2026-03-01 (completed)
- Labels: backend, size:S, security
- PRs: #89 (2026-03-01), #90 (2026-03-01)

**Laut Issue:** AI-Exception-Nachrichten wurden ungefiltert geloggt — Risiko für Leakage von API-Keys, Dateipfaden, internen Konfigurationsdetails. Gefordert: Sanitizer-Komponente, Anwendung auf alle AI-Exception-Handler, Tests, Dokumentation der Logging-Praxis.

**Geliefert:** PR #89 liefert `ErrorSanitizer` (redigiert API-Keys, Unix/Windows-Pfade, URL-Query-Parameter) und bindet ihn im `GlobalExceptionHandler` ein, mit ausführlichen Tests. PR #90 ist ein direktes Follow-up desselben Tages: `ErrorSanitizer` wird von einer Spring-`@Component` zu einer einfachen Utility-Klasse zurückgebaut, um `@WebMvcTest`-Testklassen nicht unnötig mit einem zustandslosen Bean zu belasten — eine Code-Quality-Korrektur, keine fachliche Abweichung.

**Verifikation:** `backend/src/main/java/io/opaa/api/ErrorSanitizer.java` existiert im heutigen Worktree weiterhin und ist in `GlobalExceptionHandler.java` eingebunden.

**Themen:** security, logging, backend

---

<a id="issue-72"></a>

## Issue #72 — 🔵 [LOW] Magic Numbers Without Documentation
- Geschlossen: 2026-03-02 (completed)
- Labels: enhancement, backend, size:S
- PRs: #93 (2026-03-02)

**Laut Issue:** Magic Numbers (`DEFAULT_TOP_K`, `DEFAULT_SIMILARITY_THRESHOLD`, Chunk-/Batch-Größen) ohne Begründung im Code bzw. in `application.yml`. Gefordert: Javadoc mit Rationale, README-Dokumentation, ggf. Konfigurierbarkeit und Validierung.

**Geliefert:** PR #93 dokumentiert die Magic Numbers per Javadoc über mehrere Klassen (QueryService, QueryConfiguration, ChunkingService, IndexingProperties, RateLimitService, RateLimitProperties), macht `top-k` und `similarity-threshold` über eine neue `QueryProperties`-Record-Klasse konfigurierbar, ergänzt Validierungsgrenzen und erweitert `docs/deployment.md` um eine vollständige Umgebungsvariablen-Referenztabelle. Deckt die Forderung vollständig ab.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryProperties.java` existiert im heutigen Worktree weiterhin.

**Themen:** doku, backend, konfiguration

---

<a id="issue-73"></a>

## Issue #73 — 🔵 [LOW] Inconsistent Mock Profile Naming
- Geschlossen: 2026-08-14 (not planned)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** Das Spring-Profil `mock` war irreführend benannt — es schaltete tatsächlich die Datenbank ab, aktivierte aber keine Mocks; Controller nutzten die doppelte Verneinung `@Profile("!mock")`. Gefordert: konsistente Umbenennung (z. B. `no-db`/`standalone`) durchgängig in Code, Docs, Docker-Compose.

**Geliefert:** Nicht umgesetzt wie vorgeschlagen — laut Schließkommentar (criew, 14.08.2026) existiert der kritisierte Zustand schlicht nicht mehr. Das `mock`-Profil mit Datenbank-Ausschluss war zum Zeitpunkt der Prüfung bereits aus `application.yml` verschwunden. Mit der Einführung von Space-Konzept und Auth-Umbau (#328, Entscheidung #323) heißt der Entwicklungsmodus jetzt `dev` und beschreibt treffend, was er tut (Authentifizierung ohne Anmeldedaten-Prüfung). Sämtliche `@Profile`-Bedingungen an Controllern und `UserService` sind entfallen — es gibt gar keine Profilverzweigung mehr in der Fachschicht. Das Ziel des Issues (Klarheit) wurde also erreicht, aber nicht durch Umbenennung, sondern durch ersatzlose Entfernung der Verzweigung.

**Verifikation:** Kein `mock`-Profil und keine `@Profile`-Annotationen mehr in Controllern/`UserService` im heutigen Worktree feststellbar (laut Schließkommentar; nicht erneut tief geprüft, da NOT_PLANNED mit klarer Begründung).

**Themen:** backend, profile, konfiguration, not-planned

---

<a id="issue-74"></a>

## Issue #74 — 🔵 [LOW] Complex Business Logic in Lambda Expression
- Geschlossen: 2026-03-01 (completed)
- Labels: enhancement, backend, size:S
- PRs: #88 (2026-03-01)

**Laut Issue:** Die Merge-Logik für dedupliziertes Source-Referencing in `QueryService.java:131-151` war als komplexe Inline-Lambda implementiert — schwer testbar und undokumentiert. Gefordert: Extraktion in eine benannte Methode mit Javadoc und dedizierten Tests.

**Geliefert:** PR #88 extrahiert die Lambda in `mergeSourceReferences()` mit Javadoc und 7 dedizierten Unit-Tests, die alle Merge-Szenarien abdecken. Deckt die Forderung vollständig ab, ohne Verhaltensänderung.

**Verifikation:** Nicht mehr in dieser Form am gleichen Ort geprüft (Query-Pipeline wurde seither mehrfach umgebaut, siehe Issue #66), das grundsätzliche Refactoring-Muster (benannte Merge-Methode statt Inline-Lambda) ist aber plausibel weitergeführt worden — keine tiefere Prüfung nötig für ein reines Low-Priority-Refactoring.

**Themen:** backend, refactoring, testbarkeit

---

<a id="issue-75"></a>

## Issue #75 — 🔵 [LOW] Axios Error Response Type Assertion Unsafe
- Geschlossen: 2026-03-03 (completed)
- Labels: bug, frontend, size:S
- PRs: #94 (2026-03-03)

**Laut Issue:** `normalizeError()` in `frontend/src/services/api.ts` nutzte eine ungesicherte Typ-Assertion (`as ErrorResponse`) auf `err.response?.data` — bei nicht-JSON-Fehlerantworten (z. B. HTML-Fehlerseiten von Nginx/Spring) potenziell fehleranfällig. Gefordert: Type Guard, Fallback-Kette, Tests.

**Geliefert:** PR #94 ergänzt einen `isErrorResponse`-Type-Guard und eine Fallback-Kette (JSON-Fehler → HTTP-Status → Netzwerkfehler) mit 3 Tests für die genannten Szenarien. Deckt die Forderung ab.

**Verifikation:** Nicht erneut im Detail geprüft — reines Low-Priority-Frontend-Fix ohne strukturelle Tragweite; kein Hinweis in späteren PR-Änderungen auf einen Rückbau.

**Themen:** frontend, typsicherheit, error-handling

---

<a id="issue-76"></a>

## Issue #76 — 🔵 [LOW] SQL Injection Risk in Future Migrations
- Geschlossen: 2026-08-15 (not planned)
- Labels: backend, size:S, security
- PRs: keine

**Laut Issue:** Aktuelle Liquibase-Migrationen nutzen sichere, fest eingetragene Werte, aber das Issue warnt vor hypothetischen künftigen Szenarien (Admin-UI für dynamische Statuswerte, konfigurationsgetriebene Constraints), die SQL-Injection ermöglichen könnten. Gefordert: Guidelines dokumentieren, Review-Checkliste, statische Analyse, Schulungsmaterial, Migrationsvorlage mit Sicherheitshinweisen.

**Geliefert:** Nicht umgesetzt. Laut Schließkommentar (criew, 15.08.2026, Backlog-Sichtung) benennt der Vorgang selbst keinen bestehenden Defekt — er beschreibt ein Risiko für Szenarien, die weder existieren noch geplant sind, und wäre als Dauerthema nie abschließbar. Die fachliche Substanz gilt als anderweitig bereits abgedeckt: Statuswerte laufen bereits über Java-Enums (`DocumentStatus`, `JobStatus`, `SpaceRole`, `AssetRole`), verankert über die DTO-Konvention in `AGENTS.md`/ADR-0006; Migrationen werden ohnehin im Review gelesen. Automatisierte Sicherheitsprüfung in der CI fehlt zwar tatsächlich, gilt aber als eigenes, breiteres Thema.

**Verifikation:** Kein Code-Realitätscheck nötig — die Ablehnungsbegründung ist in sich schlüssig und deckt sich mit der im Repo sichtbaren Praxis (Enum-basierte Statuswerte).

**Themen:** security, migrationen, not-planned, guideline

---

<a id="issue-77"></a>

## Issue #77 — Vector Store Index Type Hardcoded
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** Der pgvector-Index-Typ (`index-type: hnsw`) ist in `application.yml` hart verdrahtet, was ADR-0002s Anspruch auf Konfigurationsflexibilität widerspricht. Gefordert war ein per Umgebungsvariable wählbarer Index-Typ (`none`/`ivfflat`/`hnsw`) je nach Datenmenge.

**Geliefert:** Kein Code-Fix. Laut Schließungskommentar bewusst nicht umgesetzt: `distance-type` und `dimensions` sind bereits per Env konfigurierbar, nur der Index-Typ bleibt fix `hnsw`. Begründung: Ein nachträglicher Wechsel des Index-Typs erfordert ohnehin einen Neuaufbau des Index; ein reiner Env-Schalter ohne Migrationskonzept würde Inkonsistenzen zwischen Konfiguration und bestehendem Index einladen. `hnsw` gilt als tragfähiger Default für den Einsatzzweck (eine Instanz je Behörde, wachsende Bestände). Bei konkretem Bedarf soll das Thema mit einem Migrationskonzept neu aufgesetzt werden.

**Verifikation:** `backend/src/main/resources/application.yml` zeigt weiterhin `index-type: hnsw` fest verdrahtet, während `distance-type` und `dimensions` tatsächlich über `${OPAA_PGVECTOR_DISTANCE_TYPE:...}` bzw. `${OPAA_PGVECTOR_DIMENSIONS:...}` konfigurierbar sind — deckt sich mit dem Schließungskommentar.

**Themen:** retrieval, konfiguration, pgvector

---

<a id="issue-78"></a>

## Issue #78 — Silent Error Fallback for Invalid Document IDs
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S
- PRs: keine

**Laut Issue:** In `QueryService` wird ein ungültiges UUID-Format nur auf DEBUG-Level geloggt (`log.debug("Invalid document ID format: {}", docId)`), was Datenkorruption oder Bugs in Produktion verschleiert. Gefordert war Anhebung auf WARN, ein Metrik-Zähler und Prüfung, warum ungültige IDs überhaupt auftreten.

**Geliefert:** Kein Kommentar und kein verknüpfter PR vorhanden — aber der Code zeigt die geforderte Änderung: `QueryService.java` protokolliert ungültige Dokument-IDs inzwischen an zwei Stellen mit `log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", docId)`. Die weitergehenden Vorschläge (Metrik-Zähler, systematische Ursachenklärung, Validierung bei der Indizierung) sind nicht erkennbar umgesetzt.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryService.java` Zeilen 553 und 695 bestätigen `log.warn(...)` für ungültige Dokument-IDs — Kernforderung (DEBUG → WARN) ist erfüllt, vermutlich beiläufig in einem größeren Query-Umbau statt als eigener PR für dieses Issue.

**Themen:** retrieval, logging, backend

---

<a id="issue-86"></a>

## Issue #86 — chore: Liquibase Changesets konsolidieren (Pre-Production Cleanup)
- Geschlossen: 2026-03-01 (completed)
- Labels: mvp, backend, size:S
- PRs: #87 (2026-03-01)

**Laut Issue:** Da die Software noch nicht produktiv war, sollten mehrere Liquibase-Changesets, die dieselben Tabellen betreffen, zusammengeführt werden: `checksum`-Spalte direkt in die `documents`-CREATE-TABLE, `documents_total`/`documents_skipped` direkt in die `indexing_jobs`-CREATE-TABLE. Ziel: 5 statt 3 Dateien, Master-Changelog bereinigt, lokale DB neu aufsetzen, Build/Tests grün.

**Geliefert:** PR #87 setzt die Konsolidierung exakt wie beschrieben um — `checksum` in `002-create-documents-table.yaml`, `documents_total`/`documents_skipped` in `003-create-indexing-jobs-table.yaml`, die beiden ALTER-TABLE-Dateien gelöscht, Master-Changelog aktualisiert. Keine Abweichung.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/002-create-documents-table.yaml` enthält die `checksum`-Spalte, `003-create-indexing-jobs-table.yaml` enthält `documents_total` und `documents_skipped` im heutigen Worktree. Der Changelog ist seither auf 20 Dateien angewachsen (bis `020-add-upload-metadata-to-documents.yaml`) — die Konsolidierung war also, wie im Issue selbst vermerkt, nur vor Produktivsetzung sinnvoll und wurde seither nicht wiederholt.

**Themen:** backend, liquibase, datenbank, cleanup

---

<a id="issue-95"></a>

## Issue #95 — URL-based document indexing via Apache mod_autoindex crawling
- Geschlossen: 2026-03-06 (completed)
- Labels: enhancement, backend, size:L
- PRs: #96 (2026-03-06)

**Laut Issue:** Möglichkeit ergänzen, Dokumente von HTTP-Servern mit Apache-mod_autoindex-Verzeichnislisten zu indexieren — Crawler-Service, Datei-Downloader, asynchroner Indexierungs-Executor, erweiterte Trigger-API mit optionalem URL/Proxy/Credentials/SSL-Body, neue `source_type`-Spalte, `lastModified` als Änderungsindikator statt Download bei unveränderten Dateien. Plus Frontend-UI-Felder und Doku-Updates.

**Geliefert:** PR #96 liefert alle genannten Bausteine: `AutoindexCrawlerService` (regelbasierter HTML-Parser statt Regex), `UrlFileDownloader`, `UrlIndexingExecutor`, erweiterte `IndexingController`, Migration `004-add-source-type-to-documents.yaml`, Frontend-Accordion in `AdminDrawer`, OpenAPI- und Feature-Spec-Updates, umfangreiche Tests. Keine erkennbare Abweichung vom Issue-Umfang.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AutoindexCrawlerService.java` existiert im heutigen Worktree weiterhin.

**Themen:** indexing, crawler, backend, frontend

---

<a id="issue-98"></a>

## Issue #98 — PostgreSQL 18 Docker container fails to start due to volume mount path change
- Geschlossen: 2026-03-06 (completed)
- Labels: bug, setup, size:S
- PRs: #99 (2026-03-06)

**Laut Issue:** `pgvector/pgvector:pg18`-Container startete nicht, weil PostgreSQL 18+ die Datenverzeichnisstruktur geändert hat (`pg_ctlcluster`-kompatibel, versionsspezifische Unterverzeichnisse). Gefordert: Volume-Mount von `/var/lib/postgresql/data` auf `/var/lib/postgresql` ändern, betroffene Dateien `docker-compose.yml` und `docs/features/deployment-infrastructure.md` anpassen; Hinweis, dass bestehende Volumes gedroppt werden müssen.

**Geliefert:** PR #99 ändert den Mount-Pfad exakt wie beschrieben und passt zusätzlich `backend/src/main/resources/application.yml` an. Keine Abweichung.

**Verifikation:** `docker-compose.yml` mountet im heutigen Worktree weiterhin `opaa-postgres-data:/var/lib/postgresql` (ohne `/data`-Suffix).

**Themen:** deployment, docker, postgresql, bugfix

---

<a id="issue-100"></a>

## Issue #100 — Expose Ollama model configuration in docker-compose
- Geschlossen: 2026-03-06 (completed)
- Labels: bug, backend, setup, size:S
- PRs: #101 (2026-03-06)

**Laut Issue:** `OPAA_OLLAMA_CHAT_MODEL` und `OPAA_OLLAMA_EMBEDDING_MODEL` wurden im Backend-Environment-Abschnitt von `docker-compose.yml` nicht durchgereicht — Modellwechsel (z. B. `phi3:mini` → `qwen2.5:7b`) war ohne Neubau nicht möglich. Gefordert: beide Variablen ergänzen.

**Geliefert:** PR #101 ergänzt die beiden Ollama-Variablen und behebt zusätzlich zwei bei einem vorherigen Squash-Merge (#99) verlorene Variablen (`OPAA_CORS_ALLOWED_ORIGINS`, `OPAA_PGVECTOR_DIMENSIONS`), fügt `extra_hosts` für `host.docker.internal` unter Linux hinzu und aktualisiert den Demo-Link. Umfang geht über das Issue hinaus (Reparatur eines Merge-Schadens, Demo-Link), was in der PR-Beschreibung offen benannt ist.

**Verifikation:** Abweichung im heutigen Code: In `docker-compose.yml` sind aktuell **keine** `OLLAMA`-Umgebungsvariablen mehr vorhanden — Ollama als Provider-Option wurde offenbar zu einem späteren Zeitpunkt aus dem Docker-Compose-Setup entfernt (nicht Teil dieses Untersuchungsauftrags, aber auffällig: die hier gelieferte Konfigurierbarkeit existiert im heutigen Stand nicht mehr).

**Themen:** deployment, docker, ollama, konfiguration

---

<a id="issue-102"></a>

## Issue #102 — Add branch protection rules for main
- Geschlossen: 2026-03-06 (completed)
- Labels: setup, size:S, ci
- PRs: #103 (2026-03-06)

**Laut Issue:** Branch-Protection für `main` einrichten: Pflicht-Statuschecks (backend, backend-integration, frontend), 1 Pflicht-Approval, veraltete Reviews verwerfen, Konversationsauflösung erzwingen, direkte Pushes verhindern.

**Geliefert:** PR #103 fügt `.github/settings.yml` (probot/settings-App-Konfiguration) mit genau diesen Regeln hinzu. Erfordert laut PR-Beschreibung zusätzlich die Installation der probot/settings-GitHub-App, damit die Regeln tatsächlich angewendet werden — das ist ein externer, nicht im PR selbst nachprüfbarer Schritt.

**Verifikation:** `.github/settings.yml` existiert im heutigen Worktree weiterhin.

**Themen:** ci, github, projektsetup, branch-protection

---

<a id="issue-107"></a>

## Issue #107 — feat: Introduce Workspaces & Access Control
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, epic, workspace
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Großes Epic zur Einführung eines Workspace-basierten Zugriffsmodells mit Authentifizierung, rollenbasierten Berechtigungen und Workspace-gefilterter Suche, in 6 Phasen (Auth/Nutzerverwaltung, Workspace-Kern, Workspace-bewusste Datenpipeline, Dokumentverwaltung, Frontend, Integration/Qualität) mit den Unter-Issues #108–#125.

**Geliefert:** Als Epic selbst nichts direkt — umgesetzt über die referenzierten Sub-Issues, von denen dieser Chunk #108–#114 (Phase 1 und 2) abdeckt (siehe dortige Bausteine). Bemerkenswert: Das Konzept „Workspace" wurde nach diesen frühen Phasen in „Space" umbenannt und strukturell erweitert (Bibliotheken, Asset-Rollen, Gruppen aus Verzeichnisabgleich) — das ursprüngliche Epic-Design war also ein Zwischenschritt, kein Endzustand.

**Verifikation:** Das Java-Paket `io.opaa.workspace` existiert im heutigen Worktree nicht mehr; an seiner Stelle steht `io.opaa.space`. Migration `008-rename-workspace-to-space.yaml` dokumentiert die Umbenennung im Datenbankschema explizit.

**Themen:** epic, workspace, spaces, auth, access-control

---

<a id="issue-108"></a>

## Issue #108 — feat(auth): Spring Security with OIDC authentication
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:L, auth
- PRs: #135 (2026-03-07)

**Laut Issue:** Spring Security mit OIDC als Grundlage der gesamten Zugriffskontrolle einrichten — Filter Chain, OIDC-Integration (Keycloak als Referenz), JWT-Session-Handling, geschützte Endpunkte, Login-/Logout-Flow, CORS-Update, Konfiguration über Umgebungsvariablen; Mock-Profil soll Auth für lokale Entwicklung umgehen.

**Geliefert:** PR #135 liefert deutlich mehr als drei geforderte Modi: `mock` (kein Auth), `oidc` (Resource Server gegen Keycloak/Auth0/Okta/Azure AD) und zusätzlich `basic` (statische Credentials mit backend-signierten JWTs für PoCs) — zustandslose Architektur ohne Server-Sessions, Nutzer-Auto-Provisionierung, Auth-Config-Discovery-Endpunkt, Frontend-OIDC-Flow mit PKCE, Login-Seite, geschützte Routen, Keycloak-Dev-Setup, ADR-0005. Der PR schließt außerdem #120 (Login-UI) mit. Umfang übertrifft die Anforderung des Issues (dritter Auth-Modus, ADR).

**Verifikation:** `backend/src/main/java/io/opaa/auth/OidcSecurityConfig.java`, `SecurityCorsConfig.java` und `UserProvisioningFilter.java` existieren im heutigen Worktree; ADR-0005 unter `docs/decisions/0005-authentication-strategy.md` ebenfalls.

**Themen:** auth, oidc, security, backend, frontend

---

<a id="issue-109"></a>

## Issue #109 — feat(auth): user entity and database schema
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: keine direkt verknüpft

**Laut Issue:** User-Entity mit Datenbankschema, Repository und Service-Schicht anlegen — Liquibase-Migration für `users`-Tabelle (inkl. `system_role`-Enum, `auth_provider_id` als eindeutige externe ID), Auto-Anlage bei Erstanmeldung, `UserService.getCurrentUser()`.

**Geliefert:** Kein eigener PR — laut Schließkommentar (criew) wurde der Umfang vollständig durch PR #135 (Issue #108) mitgeliefert: User-Entity, Liquibase-Migration, Auto-Provisionierung über `UserProvisioningFilter`, `UserRepository`, `UserService` samt Tests. Die `system_role`-Erweiterung wurde bewusst in #110 ausgelagert. Kein fachlicher Unterschied zur Forderung, nur eine andere Ticket-Zuordnung des bereits gelieferten Codes.

**Verifikation:** `backend/src/main/java/io/opaa/auth/User.java`, `UserRepository.java`, `UserService.java` sowie Migration `005-create-users-table.yaml` existieren im heutigen Worktree.

**Themen:** auth, backend, datenbank, ohne-eigenen-pr

---

<a id="issue-110"></a>

## Issue #110 — feat(auth): System-Admin role and API authorization
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: #136 (2026-03-07)

**Laut Issue:** System-Admin-Rolle und API-Autorisierung umsetzen — `@PreAuthorize`-Schutz für Admin-Endpunkte, Bootstrap-Mechanismus für den ersten Admin per Umgebungsvariable, Endpunkt zum Befördern/Degradieren, 403 bei fehlender Berechtigung.

**Geliefert:** PR #136 liefert `SystemRole`-Enum, Admin-Bootstrap über `OPAA_INITIAL_ADMIN_EMAIL`, `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` auf Indexing-Trigger und Nutzerverwaltung, Admin-API (`GET /api/v1/admin/users`, `POST /api/v1/admin/users/{id}/role`), korrektes 403-Mapping im `GlobalExceptionHandler`. Deckt die Anforderung vollständig ab.

**Verifikation:** `backend/src/main/java/io/opaa/auth/SystemRole.java` existiert im heutigen Worktree weiterhin; Migration `006-add-system-role-to-users.yaml` ebenfalls.

**Themen:** auth, rbac, backend, admin

---

<a id="issue-111"></a>

## Issue #111 — feat(workspace): workspace and membership entities
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #131 (2026-03-07)

**Laut Issue:** `Workspace`- und `WorkspaceMembership`-Entities mit Datenbankschema — `workspaces`-Tabelle (Typ `PERSONAL`/`SHARED`), `workspace_memberships`-Tabelle (Rolle `VIEWER`/`EDITOR`/`ADMIN`/`OWNER`, eindeutig je Nutzer/Workspace), JPA-Entities, Repositories, `WorkspaceService` mit Basis-Lookups, Kaskadenverhalten beim Löschen.

**Geliefert:** PR #131 liefert alles Geforderte plus Review-Nachbesserungen (unveränderliche Mitgliederliste, Vermeidung von N+1-Queries, konsistente `systemAdmin`-Flag-Behandlung). Der PR merged zusätzlich Issue #110 hinein (Abhängigkeit auf das `users`-Schema) und behebt dabei zwei technische Integrationsprobleme (Liquibase-`addCheckConstraint` durch SQL-`CHECK`-Constraints ersetzt, `WorkspaceService` bedingt geladen im Mock-Profil ohne JPA).

**Verifikation:** Abweichung im heutigen Code: Das Java-Paket `io.opaa.workspace` existiert nicht mehr. Es wurde später (Migration `008-rename-workspace-to-space.yaml`) zu `io.opaa.space` umbenannt und um Bibliotheken (`012-knowledge-libraries.yaml`), Asset-Grants (`013-asset-grants.yaml`) und Gruppen (`009-create-groups.yaml`) erweitert. Die hier gelieferte Grundstruktur (Entity + Membership + Rollenhierarchie) lebt konzeptionell im Space-Modell fort.

**Themen:** workspace, spaces, backend, datenbank

---

<a id="issue-112"></a>

## Issue #112 — feat(workspace): workspace CRUD API
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #132 (2026-03-07)

**Laut Issue:** REST-API zum Erstellen, Auflisten, Ansehen und Löschen von Workspaces — Erstellung nur durch System-Admin, Auflistung nach Mitgliedschaft, Details mit Mitgliederzahl/Rolle, Update durch Admin/Owner, Löschung durch Owner/System-Admin (persönliche Workspaces nicht löschbar), Fehlerbehandlung (404/403/409), Integrationstests mit Testcontainers.

**Geliefert:** PR #132 liefert Controller, Service-Logik und DTOs für alle fünf Endpunkte wie gefordert. Bemerkenswert: Die Autorisierung für „System-Admin only" beim Erstellen läuft laut PR-Beschreibung über ein Request-Header-Flag statt über die in #110 eingeführte `@PreAuthorize`-Rollenprüfung — möglicherweise ein Zwischenstand vor der vollständigen Zusammenführung mit #110/#111. Die PR-Checkliste vermerkt zudem ausdrücklich, dass die volle Backend-Testsuite zum Zeitpunkt des PRs noch durch den Status des Vorgänger-Tickets blockiert war (nur gezielte Workspace-Tests liefen lokal).

**Verifikation:** Wie bei #111 — das `workspace`-Paket existiert im heutigen Code nicht mehr, abgelöst durch `space`. Der `WorkspaceController` von damals ist nicht mehr auffindbar; die CRUD-Funktionalität lebt heute im Space-Controller-Äquivalent fort (nicht im Detail nachgeprüft, da außerhalb des Chunk-Umfangs).

**Themen:** workspace, spaces, backend, api

---

<a id="issue-113"></a>

## Issue #113 — feat(workspace): personal workspace auto-creation
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:S, workspace
- PRs: #140 (2026-03-07)

**Laut Issue:** Bei Erstanmeldung automatisch einen persönlichen Workspace („My Documents", Typ `PERSONAL`, Owner-Mitgliedschaft) anlegen; persönliche Workspaces dürfen nicht löschbar sein und keine weiteren Mitglieder bekommen; idempotent bei wiederholter Anmeldung.

**Geliefert:** PR #140 liefert die automatische Anlage bei Erstanmeldung inkl. Idempotenz-Sicherung sowie die Validierungen (keine Mitglieder, keine Löschung) — gemeinsam mit Issue #114 in einem PR (siehe dortiger Baustein für die Details der Mitgliederverwaltung).

**Verifikation:** Wie bei #111/#112 — das `workspace`-Paket ist im heutigen Code durch `space` ersetzt; die Auto-Anlage eines persönlichen Bereichs ist als Konzept im Space-Modell plausibel weitergeführt (nicht im Detail nachgeprüft, außerhalb des Chunk-Umfangs).

**Themen:** workspace, spaces, backend, onboarding

---

<a id="issue-114"></a>

## Issue #114 — feat(workspace): membership management and roles API
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #140 (2026-03-07)

**Laut Issue:** REST-API zur Mitgliederverwaltung — Hinzufügen/Entfernen/Rollenänderung durch Admin/Owner, Eigentümer nicht entfernbar, Ownership-Transfer nur durch Owner, Rollenhierarchie `VIEWER < EDITOR < ADMIN < OWNER` (Admins verwalten nur Viewer/Editor, nur Owner befördert zu Owner), Mitgliederliste für alle Mitglieder, Integrationstests.

**Geliefert:** PR #140 (gemeinsam mit #113) implementiert Auflisten/Hinzufügen/Entfernen/Rollenänderung/Ownership-Transfer, setzt die Rollenhierarchie durch, verweigert Mitgliederaufnahme in persönliche Workspaces und blockiert die Entfernung des Owners — deckungsgleich mit der Anforderung.

**Verifikation:** Wie bei den Geschwister-Issues #111–#113 — das `workspace`-Paket existiert im heutigen Code nicht mehr, ersetzt durch das umfassendere Space-/Asset-Rollenmodell (`AssetRole`, Gruppen aus Verzeichnisabgleich laut Issue #63). Die hier gelieferte Rollenhierarchie-Logik ist konzeptioneller Vorläufer des heutigen Modells.

**Themen:** workspace, spaces, backend, rbac, mitgliederverwaltung

---

<a id="issue-115"></a>

## Issue #115 — feat(indexing): workspace_ids in chunk metadata and query filter
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:L, workspace
- PRs: keine

**Laut Issue:** `workspace_ids` (Liste von UUIDs) sollte als Chunk-Metadatum eingeführt werden, damit die Vektorsuche zur Abfragezeit nach den Workspace-Mitgliedschaften der anfragenden Person filtert — integriert in `VectorStore.similaritySearch()`, nicht als Nachfilter. Teil von Epic #107 (Workspaces & Access Control, Phase 3).

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Als „completed" ohne PR geschlossen, weil das zugrunde liegende Workspace-Modell komplett durch das Space-/Asset-Modell (Epic #198) ersetzt wurde. Laut Schließungskommentar entfällt die n:m-Zuordnung `chunk.workspace_ids` zugunsten einer einwertigen `library_id` je Chunk (n:1), da der Rechteanker jetzt die Wissensbibliothek ist statt der Workspace. Der fachliche Kern — Rechtefilter als Bestandteil der Vektorsuche statt Nachfilter — wurde in Nachfolge-Issue #202 (Asset-Rechte und rechtebewusste Vektorsuche) übernommen und dort tatsächlich umgesetzt.

**Verifikation:** Im heutigen Code gibt es kein Workspace-Modell mehr (Umbenennung/Ablösung durch Space, Commit 75abc6d3 u. a.). Rechte an Chunks laufen über die Wissensbibliothek (`io.opaa.library`), nicht über `workspace_ids`. Deckt sich mit dem Schließungskommentar.

**Themen:** workspaces, retrieval, spaces, migration, verworfen

---

<a id="issue-116"></a>

## Issue #116 — feat(upload): document metadata table and workspace-aware upload
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Eine separate `document_metadata`-Tabelle (Eigentümer, `home_workspace_id`, Originaldatei-Speicherpfad) sowie ein workspace-bewusster Upload-Endpunkt (`POST /api/v1/workspaces/{id}/documents`) mit Editor-Rechteprüfung, Dokumentliste und Download-Endpunkt. Teil von Epic #107, Phase 3.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR, weil das Workspace-Modell durch Space-/Asset-Modell (Epic #198) abgelöst wurde. Laut Schließungskommentar gehört ein Dokument nicht mehr zu einem `home_workspace_id`, sondern zu genau einer Wissensbibliothek; diese Zuordnung wurde in Nachfolge-Issue #201 (Wissensbibliothek als Dokumentencontainer, Migration 012) umgesetzt, die Rechte dazu in #202 (Asset-Rechte).

**Verifikation:** Es existiert im Worktree keine `document_metadata`-Tabelle und kein `workspaces`-Upload-Endpunkt; stattdessen ist `io.opaa.library` mit `LibraryDocumentService` und Upload-Funktionalität vorhanden (bestätigt u. a. über Issue #119/PR #700, das `LibraryDocumentService#uploadDocument` referenziert).

**Themen:** workspaces, spaces, wissensbibliothek, upload, migration, verworfen

---

<a id="issue-117"></a>

## Issue #117 — feat(workspace): connector-workspace integration
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Source-Mappings (1:N) sollten Konnektor-Quellen auf Workspaces abbilden, inkl. Admin-API zum Anlegen/Ändern/Löschen von Mappings und Indexierungs-Integration. Teil von Epic #107, Phase 3.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR wegen Ablösung durch das Space-/Asset-Modell (Epic #198). Laut Schließungskommentar entfällt die 1:N-Zuordnung Quelle→Workspaces; eine Konnektor-Quelle indiziert jetzt in genau eine Wissensbibliothek. Nachfolger ist #207 (Connector sources target exactly one knowledge library).

**Verifikation:** Kein `source_mappings`-Konzept im heutigen Code auffindbar; Konnektoren sind an Wissensbibliotheken gebunden (`io.opaa.library`, `KnowledgeLibraryService`), konsistent mit dem Schließungskommentar.

**Themen:** workspaces, spaces, konnektoren, wissensbibliothek, migration, verworfen

---

<a id="issue-118"></a>

## Issue #118 — feat(workspace): document deletion and exclude mechanism
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Löschen manuell hochgeladener Dokumente (Editor: eigene, Admin/Owner: alle) sowie ein Exclude-Mechanismus für Konnektor-Dokumente (Ausschluss statt Löschen, mit Aufhebung durch System-Admin) samt Indexierungs-Integration. Teil von Epic #107, Phase 4.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR wegen Ablösung durch das Space-/Asset-Modell (Epic #198). Beide Hälften leben laut Schließungskommentar in anderer Form weiter: Der Konnektor-Ausschluss geht in #207 auf (jetzt an der Bibliothek statt je Workspace), das Löschen manueller Uploads folgt aus den Asset-Rollen in #202 (EDITOR-Rolle an der Bibliothek berechtigt zu Upload und Löschung).

**Verifikation:** Kein workspace-bezogener Exclude-Mechanismus im Code; Rechte für Löschung/Ausschluss laufen über Asset-/Bibliotheksrollen (`io.opaa.library`), konsistent mit dem Schließungskommentar.

**Themen:** workspaces, spaces, wissensbibliothek, konnektoren, migration, verworfen

---

<a id="issue-119"></a>

## Issue #119 — feat(library): Speicherkontingent je Bibliothek und Organisation
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: #700 (2026-08-21)

**Laut Issue:** Ein technisch durchgesetztes Speicherkontingent je Konto/Bibliothek sowie eine datenschutzkonforme Dublettenerkennung beim Aufnehmen von Dokumenten in eine Wissensbibliothek — mit striktem Verbot, Treffer aus nicht-lesbaren Bibliotheken preiszugeben.

**Geliefert:** Nur das Speicherkontingent, und zwar **je Bibliothek**, nicht je Konto/Organisation. PR #700 führt `LibraryStorageQuotaService` mit konfigurierbarem Kontingent (`opaa.upload.library-quota-bytes`, Default 10 GiB) ein, durchgesetzt an allen Aufnahmepfaden (Upload, Filesystem/HTTP/RSS-Konnektoren) mit 413-Ablehnung bzw. `QUOTA_EXCEEDED`-Skip im Laufprotokoll. Laut PR-Beschreibung ist der Dublettenteil bereits durch eine bestehende Checksum-Dublettensperre (Migration 020, PR zu #420) abgedeckt und laut Zuschnitts-Kommentar nicht mehr Teil dieses Tickets. Das Organisations-Gesamtkontingent wurde bewusst nicht umgesetzt und im Issue als „Offen" vermerkt — der ursprüngliche Umfang (Kontingent je Konto plus Dublettenwarnung mit Datenschutzgarantien) ist damit deutlich reduziert.

**Verifikation:** `LibraryStorageQuotaService.java` existiert im Worktree unter `backend/src/main/java/io/opaa/library/`.

**Themen:** wissensbibliothek, speicherkontingent, governance, upload

---

<a id="issue-120"></a>

## Issue #120 — feat(ui): login flow and session management
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, frontend, size:M, auth
- PRs: #135 (2026-03-07)

**Laut Issue:** OIDC-Login-Redirect, Token-Handling, Auth-Store (Zustand), geschützte Routen, Nutzeranzeige im Header, Logout, 401-Behandlung und Axios-Interceptor im Frontend.

**Geliefert:** PR #135 lieferte den vollständigen Auth-Stack für Backend **und** Frontend in einem Rutsch (auch #108 wurde damit geschlossen): drei Auth-Modi (mock/oidc/basic), JWT-Service, Auto-Provisioning, `AuthConfigController`, sowie frontseitig OIDC Authorization-Code-Flow mit PKCE (`oidc-client-ts`), Login-Seite, geschützte Routen, Axios-Interceptor und Nutzeranzeige in der Sidebar. Deckt den geforderten Umfang ab, ging aber deutlich über das Frontend-Issue hinaus (kompletter Backend-Auth-Unterbau inklusive Keycloak-Dev-Setup und ADR-0005).

**Verifikation:** Der ursprüngliche Drei-Modi-Ansatz (mock/oidc/basic) existiert im heutigen Code nicht mehr — `BasicSecurityConfig.java` und `JwtTokenService.java` sind entfernt (`git log`: Commit `fd042462` „Auth-Modi auf oidc und dev reduzieren"). `OidcSecurityConfig.java` ist vorhanden. Die Login-/Session-Grundmechanik lebt fort, nur auf zwei statt drei Modi reduziert.

**Themen:** auth, oidc, frontend, session

---

<a id="issue-121"></a>

## Issue #121 — feat(ui): workspace view and workspace-filtered search
- Geschlossen: 2026-03-07 (completed)
- Labels: enhancement, frontend, size:L, workspace
- PRs: #141 (2026-03-07)

**Laut Issue:** Sidebar-Umbau mit „Workspaces"- und „Chats"-Abschnitten, workspace-gefilterte Suche im Chat-Input, Workspace-Kontext auf Quellenkarten, Workspace-Detailansicht mit Dokumentliste.

**Geliefert:** PR #141 setzte den Umfang vollständig um: neue Sidebar-Struktur, Workspace-Detailseite, workspace-bewusste Query-Filterung im Chat-Input, Workspace-Badges auf Source-Cards, dazu Store/API/MSW-Anbindung.

**Verifikation:** Die im PR genannten Dateien (`WorkspacePage.tsx`, `WorkspaceManagementPage.tsx`, `workspaceStore.ts`) existieren im heutigen Worktree nicht mehr. `git log` zeigt für `frontend/src/pages/WorkspacePage.tsx` als letzten Commit `75abc6d3` „feat(space)!: Workspace in Space umbenennen, Organisationsgrenze und neue Space-Rollen einführen" — die Funktionalität wurde also nicht ersatzlos entfernt, sondern im Zuge der Workspace→Space-Umbenennung (Epic #198) in `SpacePage.tsx`/`spaceStore.ts` überführt. Die gelieferte Funktion besteht damit unter neuem Namen fort.

**Themen:** workspaces, spaces, frontend, suche, migration

---

<a id="issue-122"></a>

## Issue #122 — feat(ui): workspace management (members and roles)
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, frontend, size:L, workspace
- PRs: #142 (2026-03-07), #150 (2026-03-08)

**Laut Issue:** Workspace-Settings-Seite (Name/Beschreibung bearbeiten, Löschen), Mitgliederverwaltung (hinzufügen/entfernen, Rolle ändern, Eigentümerwechsel), rollenabhängige UI-Sichtbarkeit, Sonderfall Persönlicher Workspace.

**Geliefert:** PR #142 lieferte die Management-Seite mit Rollenänderung, Mitglieder-Add/Remove, Eigentümerwechsel und Löschen/Update-Flows. PR #150 ergänzte separat einen Dialog zum Anlegen neuer geteilter Workspaces (nur System-Admin) — im ursprünglichen Issue-Scope nicht explizit gefordert, aber sachlich naheliegende Ergänzung.

**Verifikation:** `WorkspaceManagementPage.tsx` existiert im Worktree nicht mehr; wie bei #121 durch Commit `75abc6d3` auf `SpaceManagementPage.tsx`/`spaceStore.ts` umbenannt (Space-Modell, Epic #198). Die Funktionalität besteht unter neuem Namen fort, u. a. bestätigt durch Issue #144, das `SpaceManagementPage.tsx` als bestehende, weiterentwickelte Datei referenziert.

**Themen:** workspaces, spaces, frontend, rechteverwaltung, migration

---

<a id="issue-123"></a>

## Issue #123 — feat(query): Gesprächsgedächtnis je Person trennen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** Das flüchtige Gesprächsgedächtnis (`CaffeineChatMemoryRepository`) war allein über die Gesprächskennung adressiert — eine fremde, erratene oder bekannte Konversations-ID lieferte fremden Gesprächsverlauf als Kontext. Gefordert: Schlüssel um `userId` erweitern (`{userId}:{conversationId}`), durchgängig in `QueryService`, mit Test, der zwei Konten dieselbe Kennung verwenden lässt.

**Geliefert:** Laut Schließungskommentaren (21.08.2026) war die Lücke bereits geschlossen — der Gedächtnisschlüssel folgt bereits dem Muster `userId:chatId` und wird auf allen Pfaden durchgesetzt. Es gibt daher keinen PR, der dieses Issue schließt; die Behebung ist an anderer Stelle (vermutlich im Rahmen der Chat-/Space-Arbeiten) bereits mitgeliefert worden, wurde hier nur nachgeprüft und bestätigt. Ausdrücklich als Zwischenlösung bis #205 (persistente Chats im Space) markiert.

**Verifikation:** `CaffeineChatMemoryRepository.java` existiert im Worktree unter `backend/src/main/java/io/opaa/query/`. Eine detaillierte Codeprüfung des Schlüsselformats wurde im Rahmen dieser Recherche nicht vertieft (Primärquelle: Schließungskommentar mit expliziter Bestätigung „Lücke ist zu — doppelt").

**Themen:** auth, chat, gedächtnis, sicherheit, personenbezug

---

<a id="issue-124"></a>

## Issue #124 — feat(audit): audit logging for workspace actions
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Ein Append-only-Audit-Log für Workspace-Aktionen (Erstellung, Mitgliederänderungen, Dokumentaktionen) mit API für Workspace-Admins und System-Admins. Teil von Epic #107, Phase 6.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR, weil der Tabellenschnitt als workspace-zentriert und fachlich unzureichend eingeschätzt wurde. Laut Schließungskommentar ist das Audit-Log in einer Behörde die mitbestimmungsrelevante Datenquelle mit eigenen Anforderungen (kein personenbezogener Auswertungspfad, Aufbewahrungsgrenzen, Datensparsamkeit, geregelter Zugriff, SIEM-Export) — dafür ist #239 der fachlich richtige Nachfolger, ergänzt um #238 (Historisierung von Rechten) für die „Negativfrage" von Prüfern.

**Verifikation:** Kein workspace-bezogenes Audit-Log im Code auffindbar; die Governance-Anforderungen sind in `docs/features/access-control.md` bzw. `docs/features/spaces-and-assets.md` dokumentiert, konsistent mit dem Schließungskommentar.

**Themen:** workspaces, audit, governance, mitbestimmung, migration, verworfen

---

<a id="issue-125"></a>

## Issue #125 — test: end-to-end tests for workspace flow
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, frontend, size:L, workspace
- PRs: keine

**Laut Issue:** Umfassende E2E-Tests über den kompletten Workspace-Lebenszyklus (Auth, Verwaltung, Upload, rechtebasierte Suche, Rollen, Cross-Workspace-Isolation, Kontingente/Dubletten) mit Testcontainers, CI-Integration, Ausführungszeit < 5 Minuten.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR, weil die Testszenarien durchgehend workspace-formuliert waren und das inzwischen abgelöste Modell trafen. Laut Schließungskommentar existiert stattdessen eine eigene Playwright-Suite unter `e2e/` (#231), ergänzt um #232/#233 für Indizierung und Suche im Demo-Korpus. Neue E2E-Abdeckung für das Space-/Asset-Modell wird im Rahmen von Epic #198 nachgezogen, sobald die betroffenen Ausbaustufen stehen.

**Verifikation:** `e2e/`-Verzeichnis mit Playwright-Suite existiert im Worktree (siehe `AGENTS.md`-Verweis auf `e2e/README.md`), bestätigt die im Schließungskommentar genannte Alternative.

**Themen:** workspaces, e2e, testing, spaces, migration, verworfen

---

<a id="issue-133"></a>

## Issue #133 — [FEAT] Automatically generate frontend/backend DTOs from OpenAPI spec
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement
- PRs: #134 (2026-03-08)

**Laut Issue:** DTO-Generierung für Backend und Frontend aus der OpenAPI-Spezifikation automatisieren (OpenAPI Generator als Single-Source-of-Truth-Pipeline), in Build/CI integriert und dokumentiert.

**Geliefert:** PR #134 integrierte den OpenAPI Generator in den Backend-Gradle-Build, entfernte handgeschriebene Backend-DTOs zugunsten generierter Klassen, fügte `openapi-typescript`-Generierung im Frontend hinzu und glich das Spec-Schema an den bestehenden API-Vertrag an. Deckt den Kern des Issues für die damals vorhandenen Schemas ab; workspace-spezifische DTOs blieben zunächst handschriftlich und wurden separat in #152 nachgezogen.

**Verifikation:** `build.gradle.kts` enthält den `openApiGenerate`-Task (`org.openapitools.generator.gradle.plugin.tasks.GenerateTask`), bestätigt die dauerhafte Etablierung dieses Musters (heute mit ADR-0006 dokumentiert, siehe AGENTS.md).

**Themen:** api, openapi, dto, codegen, ci

---

<a id="issue-137"></a>

## Issue #137 — perf(auth): avoid DB round-trip on every request in UserProvisioningFilter
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: keine

**Laut Issue:** `UserProvisioningFilter` ruft bei jedem authentifizierten Request `UserService.findOrCreateUser(...)` auf und erzeugt damit einen DB-Roundtrip je Request. Vorgeschlagen: Kurzlebiger Cache (z. B. Caffeine) oder Verlagerung der Provisionierung, plus eine Update-Policy für `lastLoginAt`.

**Geliefert:** Nichts im Sinne dieses Issues direkt — geschlossen ohne PR, weil der Befund laut Schließungskommentar in #307 aufgegangen ist. Dort wird derselbe Codepfad im Zusammenhang mit einem Connection-Pool-Befund ohnehin analysiert (wie viele Connections ein Login-Request hält, ob Provisionierung aus dem Request-Pfad gelöst wird); die hier vorgeschlagenen Lösungsansätze (Caffeine-Cache, `lastLoginAt`-Intervall) sollen dort mitbewertet werden, um dieselbe Stelle nicht doppelt anzufassen.

**Verifikation:** Keine eigenständige Prüfung von `UserProvisioningFilter` vorgenommen, da laut Schließungskommentar die Behebung planmäßig in #307 verortet ist und nicht Teil dieses Vorgangs war.

**Themen:** auth, performance, connection-pool, followup

---

<a id="issue-138"></a>

## Issue #138 — feat(auth): rate limit /api/v1/auth/login to mitigate brute-force attempts
- Geschlossen: 2026-08-14 (not planned)
- Labels: enhancement, backend, size:M, security, auth
- PRs: keine

**Laut Issue:** Rate-Limiting für `POST /api/v1/auth/login` (Brute-Force-Schutz), per IP und/oder global, mit 429-Antwort — Befund aus der Sicherheitsdurchsicht zu PR #135.

**Geliefert:** Nicht umgesetzt — als „not planned" geschlossen, weil der Endpunkt selbst inzwischen nicht mehr existiert. Laut Schließungskommentar ist `POST /api/v1/auth/login` mit dem Wegfall des `basic`-Auth-Modus (#328, Entscheidung #323) entfallen; die Anmeldung läuft im Betrieb ausschließlich über den OIDC-Anbieter, dessen Brute-Force-Schutz dort liegt. Der `dev`-Modus kennt keine Anmeldedaten, gegen die sich raten ließe. Das allgemeine Rate-Limiting für `/api/v1/query` und die Indizierung ist davon unberührt.

**Verifikation:** `JwtTokenService.java`/`BasicSecurityConfig.java` existieren im Worktree nicht mehr (siehe #120/#164), konsistent mit dem Wegfall des `basic`-Modus und damit des Login-Endpunkts.

**Themen:** auth, security, brute-force, verworfen

---

<a id="issue-139"></a>

## Issue #139 — feat(auth): add basic-profile user management for system admins
- Geschlossen: 2026-08-14 (not planned)
- Labels: enhancement, backend, size:M, auth
- PRs: keine

**Laut Issue:** Persistente Verwaltung von Basic-Auth-Nutzern (statt YAML-Konfiguration) mit UI für System-Admins zum Anlegen/Löschen, passwortgehasht (BCrypt).

**Geliefert:** Nicht umgesetzt — als „not planned" geschlossen. Ein früher Kommentar (07.03.2026) merkte an, dass zusätzlich verhindert werden müsse, dass sich der letzte System-Admin selbst demoten kann. Der finale Schließungskommentar (14.08.2026) erklärt das Issue für hinfällig, weil das `basic`-Profil mit #328 (Entscheidung #323) entfallen ist — Nutzer kommen im Betrieb ausschließlich aus dem OIDC-Anbieter, Anmeldedaten werden dort verwaltet. Der dahinterliegende Wunsch nach UI-Rollenverwaltung besteht laut Kommentar für OIDC-Betrieb teilweise fort: `AdminController` (`listUsers`, `changeRole`) kann das bereits; offene Punkte dazu laufen unter #271. Weitergehender Bedarf an UI-Nutzerverwaltung müsste als neues, entkoppeltes Issue formuliert werden.

**Verifikation:** Kein `basic`-Profil und keine Basic-Auth-Nutzerverwaltung im heutigen Code; `AdminController` mit Rollenverwaltung ist vorhanden (nicht tiefer geprüft, da Primärquelle Schließungskommentar).

**Themen:** auth, admin, verworfen, oidc

---

<a id="issue-143"></a>

## Issue #143 — feat(security): Vollständigkeit nach DSGVO — Löschung, Selbstauskunft und Datenschutzhinweis
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement
- PRs: keine

**Laut Issue:** OPAA erhebt personenbezogene Daten (Nutzerkonten, Space-/Gruppenmitgliedschaften, Asset-Rechtezuweisungen), hat aber weder Löschweg noch Selbstauskunft noch Datenschutzhinweis. Gefordert waren vier Ergebnisse: ein Datenschutzhinweis (Art. 13/14), eine vollständige Kontolöschung (Art. 17) ohne Blockade durch Asset-Eigentum, eine ausschließlich von der betroffenen Person selbst auslösbare Selbstauskunft/Datenübertragbarkeit (Art. 15/20), und Pseudonymisierung/Befristung der Netzadressen in der Ratenbegrenzung.

**Geliefert:** Nicht umgesetzt. Das Issue wurde als Ticket-Hygiene-Maßnahme geschlossen (Maintainer-Entscheidung): Die DSGVO-Vollständigkeit wird bewusst vor einem Produktivbetrieb in einer Behörde zurückgestellt und dann mit aktuellem Zuschnitt neu aufgesetzt — analog zum urverwandten Issue #798, das den Selbstauskunfts-Aspekt trug. Die fachliche Grundlage bleibt in `docs/features/security-and-compliance.md` dokumentiert. Vor der Schließung gab es noch eine inhaltliche Aktualisierung (neu erhobene Bestandsaufnahme, Streichung des Auftragsverarbeitungsvertrag-Punkts zugunsten des "Vorrangs eigener Modelle" nach ADR-0014) — das war jedoch reine Spezifikationspflege, kein Code.

**Verifikation:** Keine Konto-Lösch- oder Selbstauskunft-Endpunkte im Code gefunden (`grep` auf `deleteAccount`/`accountDeletion`/`DataExport` in `io.opaa.auth` ohne Treffer). Deckt sich mit "nicht umgesetzt, zurückgestellt".

**Themen:** dsgvo, security, doku, auth, retrieval

---

<a id="issue-144"></a>

## Issue #144 — security(space): Mitgliederliste eines Space nur für Space-Admins und Eigentümer
- Geschlossen: 2026-08-20 (completed)
- Labels: security
- PRs: #674 (2026-08-20)

**Laut Issue:** Die vollständige Mitgliederliste eines Space (inkl. Klarnamen) wurde jedem Mitglied unabhängig von der Rolle preisgegeben — sowohl über `GET /spaces/{id}` als auch `GET /spaces/{id}/members`. Gefordert: Beschränkung auf `ADMIN`, Eigentümer und System-Admin, aggregierte Rollenzählung bleibt für alle sichtbar.

**Geliefert:** PR #674 setzt die im Issue empfohlene „sauberere" Variante um: Feld `members` wurde aus `SpaceResponse` entfernt (OpenAPI-Spec zuerst geändert), `SpaceService.listMembers` prüft jetzt zusätzlich zur Mitgliedschaft die Rolle `ADMIN` (neue Methode `requireMemberListViewer`). Frontend lädt Mitglieder nur für `ADMIN` über einen eigenen Store-Slice, Nicht-Admins sehen stattdessen die aggregierte Rollenzählung als Chips. Reproduktionsnachweis mit rotem/grünem Testlauf dokumentiert.

**Verifikation:** `SpaceService.java` enthält `requireMemberListViewer` (Zeilen 187, 190, 719 laut Grep) — die Änderung ist im heutigen Code vorhanden.

**Themen:** security, spaces, rechteverwaltung, mitbestimmung

---

<a id="issue-145"></a>

## Issue #145 — feat(i18n): Sprachinfrastruktur mit Deutsch als Standard- und Ausgangssprache
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:L
- PRs: keine

**Laut Issue:** Die Anwendung hat keine Sprachinfrastruktur; sichtbare Texte sind hart verdrahtet, teils englisch, teils deutsch gemischt. Gefordert war eine durchgängige i18n-Infrastruktur (`i18next`/`react-i18next` im Frontend, `MessageSource` im Backend) mit Deutsch als Ausgangs- und Standardsprache, Englisch als Option, samt Sprachauswahl, Browser-Spracherkennung nur als Vorschlag, und einer Fuge für spätere Sprachvarianten (Leichte Sprache).

**Geliefert:** Nicht umgesetzt. Maintainer-Entscheidung beim Schließen: OPAA bleibt auf absehbare Zeit deutsch-only — eine Sprachinfrastruktur/i18n wird vorerst nicht gebaut; bei künftigem Bedarf wird der Zuschnitt neu bewertet. Ein Teilaspekt ist über einen anderen Weg erfüllt: Der persönliche Space heißt im Code bereits "Meine Dokumente" (`SpaceService.ensureDefaultSpace`), allerdings weiterhin hart verdrahtet statt aus einem Nachrichtenbündel. Die Navigation nennt Spaces korrekt "Spaces", nicht "Workspaces" — das war aber ohnehin schon Terminologie, keine i18n-Leistung.

**Verifikation:** Keine `i18n`-Dateien oder `public/locales`-Verzeichnis im Frontend gefunden (`find` auf `frontend/src` und `frontend` ohne Treffer). Deckt sich mit "nicht gebaut".

**Themen:** i18n, frontend, doku, spaces

---

<a id="issue-148"></a>

## Issue #148 — feat: Dark/Light Mode Toggle in User Preferences
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement
- PRs: keine

**Laut Issue:** Ein Umschalter zwischen Dark- und Light-Mode in den Nutzereinstellungen, global angewendet über MUI `ThemeProvider`, persistiert (localStorage/Zustand), mit Systemvoreinstellung als Default und barrierefreier Bedienung.

**Geliefert:** Keine PR-Verknüpfung und kein Schließungskommentar vorhanden. Der heutige Code zeigt jedoch, dass die Funktion existiert: `frontend/src/stores/uiStore.ts` definiert `ThemeMode = 'dark' | 'light' | 'system'` mit `setThemeMode`, dazu ein `frontend/src/theme/theme.ts` mit zugehörigem Test. Die Funktionalität wurde also vermutlich im Rahmen eines anderen, breiteren PRs (z. B. #134, der laut Dateiliste `frontend/src/theme/theme.ts` und `uiStore.ts` bereits berührte) mitgeliefert, ohne dass dieses Issue dabei explizit referenziert wurde.

**Verifikation:** `frontend/src/stores/uiStore.ts` und `frontend/src/theme/theme.ts` (inkl. `theme.test.ts`) existieren im Worktree und enthalten Dark/Light/System-Logik — die Anforderung ist im heutigen Code erfüllt.

**Themen:** frontend, ui, theming, barrierefreiheit

---

<a id="issue-149"></a>

## Issue #149 — fix(workspace-ui): state leak on logout, member display names, collapsible sections, remove redundant info alert
- Geschlossen: 2026-03-08 (completed)
- Labels: bug, enhancement
- PRs: #151 (2026-03-08)

**Laut Issue:** Vier gebündelte Punkte: (1) Workspace-State wird beim Logout nicht zurückgesetzt (Cross-User-Leak), (2) Mitglieder werden als rohe UUIDs statt Anzeigenamen angezeigt, (3) Dokumente/Mitglieder-Abschnitte sollen einklappbar sein, (4) redundanter „Persönlicher Workspace"-Hinweis auf der WorkspacePage soll entfernt werden.

**Geliefert:** PR #151 setzt alle vier Punkte um: `workspaceStore` wird bei Logout zurückgesetzt, Backend löst Anzeigenamen über `UserRepository` auf (Fallback auf UUID), Dokumente/Mitglieder als einklappbare MUI-Accordions, redundanter Alert entfernt.

**Verifikation:** `WorkspacePage.tsx` und `workspaceStore.ts` existieren im heutigen Code nicht mehr — durch die Workspace→Space-Umbenennung (Commit `75abc6d3`, Epic #198) in `SpacePage.tsx`/`spaceStore.ts` überführt. Die hier gelieferte Logik (State-Reset bei Logout, Anzeigenamen, Accordions) ist damit vermutlich in die Nachfolgekomponenten übergegangen; eine Detailprüfung des heutigen Verhaltens wurde im Rahmen dieser Recherche nicht vorgenommen.

**Themen:** workspaces, spaces, frontend, bugfix, ux, migration

---

<a id="issue-152"></a>

## Issue #152 — refactor: Generate workspace DTOs from OpenAPI spec instead of handwriting them
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M
- PRs: #154 (2026-03-08), #159 (2026-03-08)

**Laut Issue:** Acht handgeschriebene Workspace-DTOs (u. a. `WorkspaceResponse`, `WorkspaceMemberResponse`) sowie das fehlende `WorkspaceDocumentResponse` sollten aus der OpenAPI-Spec generiert werden, inklusive Enum-Mapping (`WorkspaceRole`, `WorkspaceType`) und Anpassung von `build.gradle.kts`.

**Geliefert:** PR #154 entfernt die 8 handgeschriebenen Workspace-DTOs, generiert sie über den OpenAPI Generator mit Enum-Mapping via `typeMappings`/`importMappings`, und fügt ADR-0006 hinzu, die das OpenAPI-first-DTO-Prinzip dauerhaft festschreibt. PR #159 zieht dasselbe Muster für Auth-DTOs nach (über den ursprünglichen Issue-Scope hinaus, aber sachlich folgerichtig). Deckt den geforderten Umfang vollständig ab.

**Verifikation:** ADR-0006 (`docs/decisions/0006-openapi-dto-generation.md`) und die zugehörige Regel in `AGENTS.md` bestehen im heutigen Projekt fort und sind verbindliche Konvention — auch wenn die konkreten Workspace-DTOs durch die spätere Space-Migration ersetzt wurden, gilt das hier etablierte Prinzip unverändert für alle DTOs.

**Themen:** api, openapi, dto, codegen, refactoring

---

<a id="issue-153"></a>

## Issue #153 — refactor: Remove Spring "mock" profile from codebase
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M
- PRs: #155 (2026-03-08), #160 (2026-03-08)

**Laut Issue:** Der Spring-`mock`-Profil (Backend ohne LLM/DB-Anbindung) sollte vollständig entfernt werden — Mock-Controller, `@Profile("!mock")`-Annotationen, `application.yml`-Abschnitt, zugehörige Tests und Dokumentationsverweise.

**Geliefert:** PR #155 entfernt Mock-Controller (`MockQueryController`, `MockIndexingController`) samt Tests, `@Profile`-Annotationen auf 5 Produktivklassen und aktualisiert die Dokumentation. PR #160 räumt eine übersehene `MockSecurityConfig.java` nach (letzte verbliebene `@Profile("mock")`-Klasse) — vollständiger Abschluss des im Issue geforderten Umfangs.

**Verifikation:** `grep` nach `Profile("mock")`/`Profile("!mock")` im heutigen Backend-Code liefert keine Treffer — das Spring-Profil ist vollständig entfernt geblieben.

**Themen:** backend, refactoring, cleanup, architektur

---

<a id="issue-157"></a>

## Issue #157 — Externalize docker-compose environment variables into .env file
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, setup, size:S
- PRs: #158 (2026-03-08)

**Laut Issue:** Die 30+ inline definierten Umgebungsvariablen im `backend`-Service von `docker-compose.yml` sollten über `env_file` in eine `.env`-Datei ausgelagert und `.env.example` entsprechend erweitert werden.

**Geliefert:** PR #158 lagert die Variablen in `.env.docker` aus (Name abweichend vom Issue-Titel, der `.env` nannte), erweitert `.env.example` um 50+ Variablen mit Kategorien/Beschreibungen, benennt eine Variable um (`OPAA_DOCUMENTS_PATH_HOST` → `OPAA_INDEXING_DOCUMENT_PATH_HOST`), behebt zusätzlich einen OIDC-Callback-Race-Condition-Bug und überarbeitet die Deployment-Doku. Der PR ging damit über den reinen Refactoring-Scope hinaus (Bugfix „im Vorbeigehen" enthalten).

**Verifikation:** `docker-compose.yml` verwendet `env_file: ${OPAA_ENV_FILE:-.env.docker}` an mehreren Services; `.env.docker.example` existiert im Worktree — die Struktur besteht im heutigen Deployment-Setup fort (später ergänzt um `.env.docker.example`, siehe Issue/PR #719 aus früheren Chunks).

**Themen:** deployment, docker-compose, konfiguration, setup

---

<a id="issue-162"></a>

## Issue #162 — chore: centralize all dependencies in version catalog with bundles and update project rules
- Geschlossen: 2026-03-09 (completed)
- Labels: enhancement, backend, size:S
- PRs: #163 (2026-03-09)

**Laut Issue:** Inline-Abhängigkeiten in `build.gradle.kts` sollten vollständig in `libs.versions.toml` überführt, in Bundles gruppiert und `AGENTS.md` um Regeln zu Issue-/PR-Labels und Sprache ergänzt werden.

**Geliefert:** PR #163 setzt den Umfang vollständig um: alle Abhängigkeiten in `libs.versions.toml`, Bundles (`spring-boot`, `spring-ai`, `jjwt-runtime`, `runtime`, `test-deps`, `test-runtime-deps`), `build.gradle.kts`-Dependencies-Block von 29 auf 9 Zeilen reduziert, `AGENTS.md` um die geforderten Regeln ergänzt.

**Verifikation:** `libs.versions.toml` enthält einen `[bundles]`-Abschnitt (Zeile 73) im heutigen Worktree — das Muster besteht fort und ist in `AGENTS.md` als verbindliche Konvention dokumentiert.

**Themen:** backend, build, abhängigkeitsverwaltung, projektregeln

---

<a id="issue-164"></a>

## Issue #164 — fix(auth): eliminate implicit algorithm coupling in basic-auth JWT signing/decoding
- Geschlossen: 2026-03-09 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #166 (2026-03-09)

**Laut Issue:** `JwtTokenService` wählte über `Keys.hmacShaKeyFor` je nach Secret-Länge implizit HS256/HS384/HS512, während `BasicSecurityConfig.jwtDecoder()` fest HS256 erwartete — bei Secrets > 32 Byte schlug die Token-Validierung fehl. Gefordert: gemeinsame `buildKey()`-Methode als einzige Quelle für die Schlüsselerzeugung.

**Geliefert:** PR #166 extrahiert `JwtTokenService.buildKey(String secret)`, `BasicSecurityConfig.jwtDecoder()` delegiert dorthin, der Test wurde entsprechend angepasst. Deckt den geforderten Umfang exakt ab.

**Verifikation:** `JwtTokenService.java` existiert im heutigen Worktree nicht mehr — Basic-Auth wurde komplett entfernt (Commit `fd042462`, „Auth-Modi auf oidc und dev reduzieren", siehe #120/#138/#139). Der Fix selbst ist damit gegenstandslos geworden, weil sein gesamter Kontext (Basic-Auth-JWT-Signierung) entfallen ist — kein Rückschritt, sondern Folge der späteren Auth-Vereinfachung.

**Themen:** auth, security, jwt, bugfix, verworfen-durch-migration

---

<a id="issue-165"></a>

## Issue #165 — fix: URL indexer stores temp filename instead of original filename in document DB
- Geschlossen: 2026-03-09 (completed)
- Labels: bug
- PRs: #169 (2026-03-09)

**Laut Issue:** Beim Indexieren via URL wurde der Dateiname der lokalen temporären Downloaddatei (z. B. `opaa-1234567890.pdf`) statt des Original-Dateinamens vom Remote-Server in der Dokumenten-DB gespeichert.

**Geliefert:** PR #169 ergänzt `processUrlFile()` um einen `originalFileName`-Parameter, `UrlIndexingExecutor` übergibt `entry.name()`; Regressionstest `processUrlFileUsesOriginalFilenameNotTempFilename` ergänzt. Deckt den Fix exakt wie beschrieben ab.

**Verifikation:** `FileProcessingService.java` enthält den Parameter `originalFileName` an mehreren Stellen (u. a. Zeilen 146, 154, 176) im heutigen Worktree — der Fix besteht fort.

**Themen:** indexing, bugfix, url-indexer

---

<a id="issue-170"></a>

## Issue #170 — fix(indexing): StackOverflowError when indexing URLs with long query strings
- Geschlossen: 2026-03-09 (completed)
- Labels: bug, backend
- PRs: #171 (2026-03-09)

**Laut Issue:** `UrlIndexingExecutor` verwendete `url.matches(".*\.[a-zA-Z0-9]+$")` zur Erkennung von Dateiendungen in URLs; der `.*`-Präfix führte bei langen URLs (z. B. langen Query-Strings) zu katastrophalem Backtracking und `StackOverflowError`.

**Geliefert:** PR #171 ersetzt den Regex durch `hasFileExtension(url)`: String-basierte Prüfung ohne Regex/Rekursion (Query-String/Fragment abtrennen, letztes Pfadsegment auf Punkt prüfen). Deckt den Fix exakt wie beschrieben ab.

**Verifikation:** `UrlIndexingExecutor.java` enthält `hasFileExtension` (Zeilen 87, 244) im heutigen Worktree — der Fix besteht fort.

**Themen:** indexing, bugfix, performance, url-indexer

---

<a id="issue-172"></a>

## Issue #172 — Document the agent organization and development workflow
- Geschlossen: 2026-07-16 (completed)
- Labels: documentation, size:S
- PRs: #173 (2026-07-16)

**Laut Issue:** Ein Dokument in `docs/`, das die Agentenrollen (Orchestrator, PM, Developer, Code-Reviewer, QA-Engineer, Marketing), den Workflow von Idee bis Merge und die Kollaborationsregeln (menschliche Merges, Dokumentationspflicht, ADR-Prozess) beschreibt, verlinkt aus `docs/INDEX.md` und `AGENTS.md`, konsistent mit ADR-0001.

**Geliefert:** PR #173 fügt `docs/AGENT-ORGANIZATION.md` mit exakt diesem Inhalt hinzu und verlinkt es aus `docs/INDEX.md` und `AGENTS.md`. Deckt den Umfang vollständig ab.

**Verifikation:** `docs/AGENT-ORGANIZATION.md` existiert im heutigen Worktree und wird in `AGENTS.md` als „Wichtiger Pfad" referenziert.

**Themen:** dokumentation, agenten-organisation, projektsetup

---

<a id="issue-174"></a>

## Issue #174 — Add product-manager agent definition and modernize feature/spec templates
- Geschlossen: 2026-07-16 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #175 (2026-07-16)

**Laut Issue:** Erster Rollenagent der Agenten-Organisation: `product-manager`-Subagent (interview-first, Anforderungen kritisch hinterfragen, Best-Practice-Recherche, Spec-/Issue-Erstellung), dazu modernisierte Templates (`feature_request.md`, neues `epic.md`, `docs/features/TEMPLATE.md`) nach dem De-facto-Muster von Epic #107.

**Geliefert:** PR #175 liefert `.claude/agents/product-manager.md` mit interview-first-Arbeitsweise inkl. hartem Stopp vor dem Schreiben, sowie die drei genannten Template-Dateien. Deckt den Umfang vollständig ab.

**Verifikation:** `.claude/agents/product-manager.md` und `.github/ISSUE_TEMPLATE/epic.md` existieren im heutigen Worktree.

**Themen:** agenten-organisation, dokumentation, templates, produktmanagement

---

<a id="issue-176"></a>

## Issue #176 — Expand coding-standards-reviewer into a full code-reviewer agent
- Geschlossen: 2026-07-17 (completed)
- Labels: enhancement, size:M
- PRs: #177 (2026-07-17)

**Laut Issue:** Zweiter Rollenagent: Erweiterung des bestehenden `coding-standards-reviewer` zu einem vollständigen `code-reviewer`, der zusätzlich Korrektheit/Bugs, Security, Testabdeckung neuer Logik und Dokumentationspflicht prüft, mit striktem Signalregime (max. 5 Nits, nur bestätigte Befunde, nie mergen/blocken).

**Geliefert:** PR #177 benennt die Agentendatei um (`code-reviewer.md`) und ergänzt die geforderten Kategorien, das Verify-Pass-Prinzip (CONFIRMED/PLAUSIBLE mit Datei:Zeile-Beleg) und das Read-only-Toolset. Deckt den Umfang vollständig ab.

**Verifikation:** `.claude/agents/code-reviewer.md` existiert im heutigen Worktree; die alte `coding-standards-reviewer.md` wurde entfernt (laut PR-Dateiliste).

**Themen:** agenten-organisation, code-review, dokumentation

---

<a id="issue-178"></a>

## Issue #178 — Add developer agent definition
- Geschlossen: 2026-07-17 (completed)
- Labels: enhancement, size:M
- PRs: #179 (2026-07-17)

**Laut Issue:** Dritter Rollenagent: `developer`-Subagent, der ein einzelnes, gut umgrenztes Issue End-to-End (Code + Tests + Doku) in einem isolierten Worktree umsetzt und einen PR liefert, mit TDD-Arbeitszyklus, Anti-Reward-Hacking-Regeln, Blocker-Policy und praktischem Repo-Wissen.

**Geliefert:** PR #179 liefert `.claude/agents/developer.md` mit exakt diesem Umfang: TDD-Zyklus mit Phasentrennung, Nachweispflicht, Blocker-Policy, Worktree-Isolation, Modell-Default Sonnet. Deckt den geforderten Umfang vollständig ab.

**Verifikation:** `.claude/agents/developer.md` existiert im heutigen Worktree.

**Themen:** agenten-organisation, entwicklung, dokumentation

---

<a id="issue-180"></a>

## Issue #180 — Add qa-engineer agent definition and E2E ownership workflow
- Geschlossen: 2026-07-17 (completed)
- Labels: enhancement, size:M
- PRs: #181 (2026-07-17)

**Laut Issue:** Vierter Rollenagent: `qa-engineer`-Subagent für Systemqualität — E2E-Suite-Eigentümerschaft, RAG-Antwortqualitätsbewertung, Qualitätsstrategie (Coverage, Flakiness, Release-Entscheidung). Verankerung der E2E-Eigentümerschaft in `product-manager.md` und `docs/AGENT-ORGANIZATION.md`.

**Geliefert:** PR #181 liefert `.claude/agents/qa-engineer.md` mit den drei genannten Säulen sowie die Verankerung der E2E-Regel in `product-manager.md` und `docs/AGENT-ORGANIZATION.md`. Deckt den Umfang vollständig ab.

**Verifikation:** `.claude/agents/qa-engineer.md` existiert im heutigen Worktree.

**Themen:** agenten-organisation, qualitätssicherung, e2e, dokumentation

---

<a id="issue-182"></a>

## Issue #182 — Add marketing agent definition (positioning-first)
- Geschlossen: 2026-07-17 (completed)
- Labels: enhancement, size:M
- PRs: #183 (2026-07-17)

**Laut Issue:** Fünfter Rollen-Agent der Agentenorganisation: ein `marketing`-Subagent (Opus), dessen primäre Aufgabe die Schärfung von OPAAs Pitch und Mission ist — Positionierung zuerst, Assets werden daraus abgeleitet. Grund: festgestellter Message-Drift zwischen Vision, Pitch und Landing Page, fehlendes GDPR-by-design/EU-AI-Act-Messaging, zwei konkurrierende Wettbewerbsanalysen, keine Personas, keine dokumentierte Tonalität. Verlangt wurden `.claude/agents/marketing.md` mit Methodenstack (JTBD → Dunford → Moore-Statement → Messaging-House), Interview-first-Arbeitsweise mit Hard Stop beim Maintainer, Pflege von `docs/market/MESSAGING.md` als Quelle der Wahrheit, sowie Aktualisierung der Rollen-Tabelle in `docs/AGENT-ORGANIZATION.md`.

**Geliefert:** PR #183 legt `.claude/agents/marketing.md` genau mit diesem Methodenstack, Zwei-Spur-Tonalität (Community informell/EN, Buyer formell Sie/DE+EN) und Claim-Disziplin an und aktualisiert die Rollen-Tabelle in `docs/AGENT-ORGANIZATION.md`. Deckt sich mit der Forderung, keine erkennbaren Abweichungen.

**Verifikation:** `.claude/agents/marketing.md` existiert im Worktree. `docs/AGENT-ORGANIZATION.md` enthält die Marketing-Zeile mit Positionierungs-Beschreibung und Verweis auf den Subagenten `marketing` (Opus) sowie `docs/market/MESSAGING.md`. Rolle ist seither auch als Cross-Platform-Adapter (`.codex/agents/marketing.toml`, `.opencode/agents/marketing.md`, `agents/roles/marketing.md`) vorhanden (siehe Issue #184).

**Themen:** agenten-organisation, marketing, doku, positionierung

---

<a id="issue-184"></a>

## Issue #184 — feat(agents): support shared roles across Claude, Codex, and OpenCode
- Geschlossen: 2026-07-18 (completed)
- Labels: documentation, enhancement, setup, size:M
- PRs: #185 (2026-07-18)

**Laut Issue:** Die fünf bestehenden Claude-Code-Agentendefinitionen (`product-manager`, `developer`, `code-reviewer`, `qa-engineer`, `marketing`) sollten provider-neutral nutzbar werden, damit Claude Code, Codex und OpenCode dieselbe Rollenlogik ohne dreifache Pflege verwenden können. Verlangt: gemeinsame Rollen-Contracts, dünne Plattform-Adapter je Client, Read-only-Konfiguration für den Code-Reviewer wo möglich, erhaltene Worktree-Isolation für Developer/QA Engineer, aktualisierte Dokumentation.

**Geliefert:** PR #185 verschiebt die fünf Rollenprompts nach `agents/roles/*.md` als Quelle der Wahrheit und ergänzt Adapter für Claude Code (`.claude/agents/*.md`), Codex (`.codex/agents/*.toml`) und OpenCode (`.opencode/agents/*.md`) — für alle fünf Rollen. `docs/AGENT-ORGANIZATION.md` wurde entsprechend aktualisiert. Deckt sich mit der Forderung; ob Read-only-Konfiguration für Code-Reviewer und Worktree-Zwang für Developer/QA in den Adapter-Dateien konkret umgesetzt sind, wurde nicht im Detail geprüft (nur Dateiexistenz, kein Codereview der Adapterinhalte).

**Verifikation:** Alle im PR genannten Pfade existieren im heutigen Worktree: `agents/roles/` (11 Dateien, darunter die 5 ursprünglichen plus seither ergänzte Stakeholder-Rollen und `ux-designer.md`), `.codex/agents/` (11 `.toml`-Dateien) und `.opencode/agents/` (11 `.md`-Dateien) sind vollständig parallel zu `.claude/agents/` gepflegt. Die Struktur wurde seit dem PR sichtbar weitergepflegt (neue Rollen wie Stakeholder-Agenten und UX-Designer kamen über alle drei Plattformen hinweg konsistent hinzu) — das Cross-Platform-Muster hat sich also gehalten.

**Themen:** agenten-organisation, tooling, cross-platform, doku

---

<a id="issue-186"></a>

## Issue #186 — Projektsprache auf Deutsch umstellen
- Geschlossen: 2026-08-01 (completed)
- Labels: documentation, size:L
- PRs: #187 (2026-08-01)

**Laut Issue:** Die gesamte Projektdokumentation (README, CONTRIBUTING, AGENTS.md, CLAUDE.md, CLA.md, alles unter `docs/`, GitHub-Templates, Agentendefinitionen, Workflow-Regeln) sollte fachlich korrekt und natürlich ins Deutsche übersetzt werden. Quellcode, Code-Kommentare und Build-Konfiguration bleiben Englisch.

**Geliefert:** PR #187 übersetzt 50 Markdown-Dateien: README, CONTRIBUTING, AGENTS.md, CLA.md, GitHub-Templates, Claude-/OpenCode-Agentendefinitionen, `.claude/rules/workflow.md`, `agents/roles/`, die gesamte `docs/`-Hierarchie (ADRs, Feature-Specs, Konzepte, MVP-Doku, Deployment, Design). Explizit unverändert gelassen: `CLAUDE.md` (enthält nur die `@AGENTS.md`-Direktive) sowie bereits deutschsprachige Diskussionsdateien. Deckt sich mit der Forderung; keine Abweichung erkennbar.

**Verifikation:** `README.md` im heutigen Worktree ist vollständig auf Deutsch (Titel, Säulen-Beschreibung). `AGENTS.md` und `docs/AGENT-ORGANIZATION.md` sind ebenfalls Deutsch und seither weiter deutschsprachig fortgeschrieben (neue ADRs 0009–0019 sind durchgehend auf Deutsch betitelt und verfasst). Die Sprachumstellung wurde offensichtlich zur dauerhaften Konvention (siehe AGENTS.md-Abschnitt „Projektsprache"), nicht nur einmalig für Altbestand angewendet.

**Themen:** doku, i18n, projektsprache, agenten-organisation

---

<a id="issue-188"></a>

## Issue #188 — chore(backend): migrate to Spring Boot 4.1 and bump all backend dependencies
- Geschlossen: 2026-08-01 (completed)
- Labels: enhancement, backend, size:L
- PRs: #190 (2026-08-01)

**Laut Issue:** Koordinierter Sprung auf Spring Boot 4.1 und Spring AI 2.0 plus Bump des gesamten Backend-Dependency-Baums (Spotless, OpenAPI Generator, JJWT, Caffeine, Liquibase, Testcontainers, Gradle-Wrapper auf 9.6). Detaillierter Migrationsplan mit Breaking-Change-Liste (Jackson 3, Property-Umbenennungen, Package-Verschiebungen, Actuator-Nullability) und Akzeptanzkriterien inkl. grünem `spotlessCheck build test`, funktionierendem `bootRun` und aktualisierten Versionsreferenzen in README/AGENTS.md/ADR-0002.

**Geliefert:** PR #190 setzt praktisch alle Zielversionen um (Spring Boot 4.1.0, Spring AI 2.0.0, Spotless 8.9.0, OpenAPI Generator 7.24.0, JJWT 0.13.0, Caffeine 3.2.4, Liquibase 5.0.3, Testcontainers 2.0.5, Gradle 9.6.1) und dokumentiert die konkret angetroffenen Breaking Changes (Package-Umzüge bei Actuator-Health und RestClientCustomizer, separate Test-Slice-Starter, Liquibase-Autoconfig-Umzug, Jackson-3-`JsonMapper`-Bean statt `ObjectMapper`, Spring-AI-Property-Renames, Testcontainers-Artefakt-Präfixe). Bewusste Abweichung vom Issue: der `spring-boot-jackson2`-Kompatibilitäts-Shim wurde **nicht** genutzt, stattdessen ADR-0007 als Entscheidungsdokument angelegt. Dieses ADR-0007 wurde laut Commit-Historie später wieder entfernt (`chore(backend): evalTest-Sourceset auf Jackson 3 umstellen, ADR-0007 entfernen`, gefolgt von `docs: ADR-Nummernkollision 0008 auflösen` und einer ADR-Bestandsbereinigung) — die damalige Jackson-3-Entscheidung ist im heutigen ADR-Bestand nicht mehr als eigenes Dokument sichtbar.

**Verifikation:** `backend/gradle/libs.versions.toml` im heutigen Worktree bestätigt `spring-boot = "4.1.0"` und `spring-ai = "2.0.0"` weiterhin aktiv, inklusive der im PR eingeführten Bundles (`spring-boot-starter-liquibase`, `spring-boot-starter-security-test`, `spring-ai-vector-store-advisor`). `docs/decisions/0007-jackson-3-adoption.md` existiert nicht mehr (siehe oben) — Verzeichnis springt heute von 0006 direkt auf 0009.

**Themen:** backend, dependencies, spring-boot, spring-ai, migration

---

<a id="issue-189"></a>

## Issue #189 — chore(frontend): bump all frontend dependencies to latest stable (MUI 9, Vite 8, TypeScript 6, ESLint 10)
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #191 (2026-08-02)

**Laut Issue:** Alle Frontend-Abhängigkeiten auf die jeweils aktuelle stabile Version heben, insbesondere die Majors MUI v7→v9, Vite 7→8, TypeScript 5.9→6.0, ESLint 9→10 sowie `react-router-dom`→`react-router` 8, plus ein langer Schwanz an Patch-/Minor-Bumps. ESLint-9-EOL (2026-08-06) machte den Schritt zeitkritisch. Akzeptanzkriterien: sauberer `npm ci`, grünes `format:check`/`lint`/`test`/`build`, funktionierender Dev-Server, aktualisierte Versionsreferenzen in AGENTS.md/README/ADR-0002.

**Geliefert:** PR #191 setzt die Zielversionen um (ESLint 10.8.0, TypeScript 6.0.3, Vite 8.2.0, MUI 9.2.0, react-router 8.3.0, plus Long-Tail-Bumps). Zwei Abweichungen vom Issue-Scope, beide dokumentiert als nötig für grüne CI: (1) Node-Baseline musste von „20+" auf `>=22.22.0` angehoben werden, da `jsdom@30` und `react-router@8` das verlangen; (2) ein npm-`override` für `openapi-typescript`s TypeScript-5-Peer-Dependency war nötig. Die tatsächlichen MUI-9-Breaking-Changes wichen von den im Issue vermuteten ab (nicht `Stack direction="column"`, sondern entfernte System-Props auf `Typography`/`Stack`, `inputProps`→`slotProps`, entfernte `*Outline`-Icons). Laut PR-Body steht der manuelle Klick-Test (Login/Workspace/Chat) explizit noch aus; drei UI-Dateien (WorkspacePage, MobileHeader, SettingsPage) mit den `sx`-Konvertierungen hatten keine vollständige Testabdeckung zum Merge-Zeitpunkt.

**Verifikation:** `frontend/package.json` im heutigen Worktree bestätigt die Zielversionen weiterhin aktiv (`@mui/material` ^9.2.0, `react` ^19.2.8, `react-router` ^8.3.0, `eslint` ^10.8.0, `typescript` ~6.0.3, `vite` ^8.2.0). Die im PR als unvollständig getestet genannte `MobileHeader.tsx` wurde durch Issue #193 (weißes Hamburger-Icon) unmittelbar nachträglich als Bug bestätigt — plausibler Zusammenhang mit der hier fehlenden manuellen Verifikation.

**Themen:** frontend, dependencies, mui, vite, typescript, eslint, react-router

---

<a id="issue-192"></a>

## Issue #192 — chore(frontend): drop openapi-typescript peer override once upstream supports TypeScript 6
- Geschlossen: 2026-08-24 (not planned)
- Labels: frontend, size:S
- PRs: keine

**Laut Issue:** PR #191 hob TypeScript auf 6.0.3 an; `openapi-typescript@7.13.0` deklariert weiterhin nur `typescript: ^5.x` als Peer, weshalb ein `overrides`-Eintrag nötig wurde. Gefordert war, den Override zu entfernen, sobald eine Upstream-Version einen TypeScript-6-kompatiblen Peer-Bereich deklariert.

**Geliefert:** Nicht umgesetzt, weil die Voraussetzung weiterhin fehlt. Beim Schließen (24.08.2026) geprüft: `openapi-typescript@7.13.0` (weiterhin latest) deklariert unverändert `typescript: ^5.x`. Der Workaround selbst hat sich seit der pnpm-Migration (#653) geändert — er lebt nicht mehr als npm-`overrides`, sondern als `peerDependencyRules.allowedVersions` in `frontend/pnpm-workspace.yaml`. Geschlossen mit Verweis auf #751 (Renovate): Sobald Renovate ein Upstream-Release mit erweiterter Peer-Range sichtbar macht, kann die Regel im Update-PR entfernt werden — ein eigenes Tracking-Issue dafür gilt als überflüssig.

**Verifikation:** `frontend/pnpm-workspace.yaml` enthält weiterhin `peerDependencyRules.allowedVersions: 'openapi-typescript>typescript': '>=6.0.0'` — bestätigt, dass der Workaround unverändert aktiv ist.

**Themen:** frontend, projektsetup, ci

---

<a id="issue-193"></a>

## Issue #193 — fix(frontend): hamburger menu icon invisible in mobile header (white on white)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #669 (2026-08-20)

**Laut Issue:** Im mobilen Viewport (unterhalb `md`-Breakpoint) war das Hamburger-Icon im hellen Modus unsichtbar (weiß auf weiß), da `MobileHeader.tsx` nur `bgcolor: 'background.paper'` auf der `AppBar` setzte, während der geerbte Vordergrund (`primary.contrastText`, weiß) unverändert blieb und von `IconButton color="inherit"` übernommen wurde. Vorschlag: Vordergrund explizit setzen, z. B. `color: 'text.primary'` in der `sx`-Prop der `AppBar`. Ausdrücklich als Vorbestand markiert, nicht als Regression der MUI-9-Migration (#189/#191).

**Geliefert:** PR #669 setzt exakt den vorgeschlagenen Fix (`color: 'text.primary'` neben `bgcolor` in der `AppBar`-`sx`-Prop) und ergänzt `MobileHeader.test.tsx` mit einem Regressionstest. Reproduktionsnachweis im PR-Body dokumentiert: Test schlägt ohne Fix mit `expected 'var(--appbar-color)' to be 'rgb(1, 33, 66)'` fehl, besteht mit Fix. Deckt sich vollständig mit der Forderung, keine Abweichung.

**Verifikation:** `frontend/src/layouts/MobileHeader.tsx` enthält im heutigen Worktree `color: 'text.primary'` neben `bgcolor: 'background.paper'` in der `AppBar`-`sx`-Prop, der Fix ist unverändert vorhanden. `aria-label` wurde seither zusätzlich ins Deutsche übersetzt (`"Menü öffnen"`), was zur zwischenzeitlichen i18n-Konvention aus Issue #186 passt.

**Themen:** frontend, bug, mui, barrierefreiheit, ui

---

<a id="issue-194"></a>

## Issue #194 — docs: document git worktree usage for parallel agent sessions
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation
- PRs: #195 (2026-08-02)

**Laut Issue:** In `AGENTS.md` und `.claude/rules/workflow.md` dokumentieren, dass parallele Agent-Sessions im selben Checkout je Aufgabe einen eigenen Git-Worktree statt eines Branch-Wechsels im gemeinsamen Arbeitsverzeichnis nutzen sollen, um gegenseitiges Blockieren zu vermeiden.

**Geliefert:** PR #195 ergänzt genau die geforderte Passage in `.claude/rules/workflow.md` und `AGENTS.md`. Keine Abweichung vom Issue.

**Verifikation:** `.claude/rules/workflow.md` existiert im heutigen Worktree nicht mehr (spätere Commits, u. a. "docs: doppelt gepflegte Workflow-Regeln auf AGENTS.md zusammenführen", haben die Datei entfernt und ihren Inhalt in `AGENTS.md` konsolidiert). `AGENTS.md` enthält den Abschnitt "Git Worktrees für parallele Sessions" weiterhin mit dem Kerninhalt aus dem Issue. Inhaltlich also weiterhin gültig, nur an anderer Stelle.

**Themen:** doku, agenten-organisation, projektsetup

---

<a id="issue-196"></a>

## Issue #196 — ci: publish backend and frontend Docker images to GHCR
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement
- PRs: #197 (2026-08-02)

**Laut Issue:** CI soll `backend/Dockerfile` und `frontend/Dockerfile` bei jedem Push auf `main` (plus manuellem Trigger) bauen und als `ghcr.io/criew/opaa-backend` bzw. `ghcr.io/criew/opaa-frontend` mit den Tags `main` und Commit-SHA nach GHCR veröffentlichen, damit ein Deployment-Ziel per `docker compose pull && docker compose up -d` arbeiten kann.

**Geliefert:** PR #197 fügt den Workflow `.github/workflows/publish-images.yml` genau in dieser Form hinzu und dokumentiert die Images sowie den Pull-basierten Deployment-Fluss in `docs/deployment.md`. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `.github/workflows/publish-images.yml` existiert im heutigen Worktree unverändert an der erwarteten Stelle.

**Themen:** ci, deployment, docker

---

<a id="issue-198"></a>

## Issue #198 — Epic: Space and asset model — replace the workspace model
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, epic, backend, frontend, size:L, security, workspace
- PRs: keine (Epic ohne eigenen PR — Arbeit steckt in den Sub-Issues)

**Laut Issue:** Ersatz des Workspace-Modells durch ein Space-/Asset-Modell (Spezifikation `docs/features/spaces-and-assets.md`, ADR-0008). Kernversprechen: Assets (Wissensbibliotheken, Agenten, Prompt-Bibliotheken) sind eigenständige Objekte mit eigenem Eigentümer und ACL; Space-eigener Inhalt (Chats, Artefakte) gehört genau einem Space und wird erst durch bewusstes Platzieren sichtbar. Die Arbeit war in vier Stufen A–D geschnitten, mit Stufe A (Rechteanker, Gruppen, Verzeichnissync, Wissensbibliothek, rechtebewusste Suche, Rechtehistorie, Audit-Governance) als einzig technisch zwingender Reihenfolge.

**Geliefert:** Stufe A vollständig (Rechtemodell #199, Gruppen als Rechtssubjekte #200, Wissensbibliothek als Dokumentcontainer #201, rechtebewusste Vektorsuche #202, Rechtehistorisierung #238, Audit-Serie #391–#395, Audit-Governance-Kern #239). Aus Stufe B: Space-Asset-Kuration #203 (PR #706), Konnektor-Ziele #207 (strukturell durch ADR-0018/Epic #486 gelöst). Aus Stufe C: die Persistenz-Grundlage für Chats kam über das separate Epic #523 (#524–#529) statt über #205 — Chat/ChatMessage überleben Neustarts, ein Chat gehört genau einem Space, Chats sind privat beim Autor. Zusätzlich lieferten die Issues #418–#424 den Zwischenzustand-Fix (Bibliotheksliste mit Rechteformel, Upload-/Löschweg, zielbare Indizierung, Frontend-Verwaltung von Bibliotheken, E2E-Kette).

**Nicht geliefert (bewusst offen, ohne Nachfolgeticket in diesem Chunk):** Der eigentliche Kern des Verteilbarkeits-Versprechens — Agent/Prompt-Library-Assets mit Freigabekette (#209), Asset-Parameter (#210), Strikt-Modus (#204), Teilen-Lebenszyklus für Chats (#205, Kollaborationsteil) und Artefakte (#206), Asset-Versionierung (#211), Rückruf durch Deaktivierung (#212), Abkömmlinge/Drift-Schutz (#213, #243), Built-in-Assets (#214), Asset-Katalog (#215), Governance-Kontrollen (#216), Nachfolge (#240), Grant-Befristung/Rezertifizierung (#241), Konsistenzprüflauf (#242), gruppengebundene Spaces (#358) — sind allesamt separat als "not planned" geschlossen worden (siehe jeweilige Baustein-Dateien). Das Epic selbst wurde per Ticket-Hygiene-Entscheidung geschlossen, obwohl laut eigenem Statuskommentar vom 23.08. "kein offenes Sub-Issue vollständig erledigt oder obsolet" war und "sein Kernversprechen für die Verteilbarkeit ... noch nicht geliefert" ist. Die Schließung ist also eine Entscheidung, den Ticketbestand zu bereinigen, nicht ein Abschluss der fachlichen Arbeit — neue Tickets sollen erst entstehen, wenn die Themen konkret anstehen.

**Verifikation:** `KnowledgeLibrary.sourceType` existiert (bestätigt #201/#207-Nachfolge über ADR-0018). `backend/src/main/java/io/opaa/chat/Chat.java` existiert (bestätigt Chat-Persistenz aus #523), aber keine `ChatStatus`-Klasse mit `SHARED`/`WITHDRAWN` gefunden — bestätigt, dass der Teilen-Lebenszyklus (#205-Rest) fehlt. Keine `Agent.java`/`PromptLibrary.java` im Backend gefunden — bestätigt, dass Stufe B (Asset-Typen Agent/Prompt-Library) nicht gebaut wurde.

**Themen:** spaces, epic, rechteverwaltung, retrieval, agenten, projektsetup

---

<a id="issue-199"></a>

## Issue #199 — Rename workspace to space, add organization scope and reshape space roles
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, frontend, size:L, workspace
- PRs: #254 (2026-08-02)

**Laut Issue:** Erster Schritt des Space-and-Asset-Modells (Teil von Epic #198): mechanischer Rename `Workspace`→`Space` über Entity, Tabelle, Repository, Service, Controller, OpenAPI-Spec und Frontend, plus semantische Änderungen — `Space.kind` (PERSONAL/PROJECT/TEAM), `Space.visibility` (PRIVATE/DISCOVERABLE/OPEN), neue `SpaceRole` (MEMBER/CURATOR/ADMIN) mit definierter Rollenabbildung, `organization_id` als harte Mandantengrenze (auch für System-Admins), Wegfall globaler Namenseindeutigkeit, Nutzer dürfen PROJECT-Spaces selbst anlegen, Entfernen von `/api/v1/workspaces/{id}/documents`. Migration muss resumierbar sein, Trockenlauf mit Mengengerüst, Rollback dokumentiert.

**Geliefert:** PR #254 liefert den Rename vollständig über Backend, OpenAPI und Frontend inkl. Liquibase-Changelog `008-rename-workspace-to-space.yaml` mit Migrationsleitfaden (`docs/migrations/008-rename-workspace-to-space.md`). Abweichungen/Annahmen laut PR-Beschreibung: Organisationsgrenzverstoß liefert 404 statt 403 (Existenz nicht preisgeben, war im Issue nicht explizit gefordert); der Trockenlauf lief mangels produktivem Datenbestand gegen die Testcontainer-Datenbank statt gegen eine Kopie echter Produktionsdaten (im Migrationsleitfaden begründet); Sidebar erlaubt jetzt allen Nutzern Space-Erstellung, aber keine UI für TEAM-Spaces (bewusst außerhalb des Scopes). CLA-Checkbox im PR war zum Merge-Zeitpunkt nicht abgehakt.

**Verifikation:** `backend/src/main/java/io/opaa/space/Space.java` existiert im heutigen Worktree; das alte Verzeichnis `backend/src/main/java/io/opaa/workspace/` existiert nicht mehr — konsistent mit dem beabsichtigten Hard-Cut-Rename ohne Kompatibilitätsschicht.

**Themen:** spaces, auth, deployment, refactoring, migration, projektsetup

---

<a id="issue-200"></a>

## Issue #200 — Introduce groups as permission subjects
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: #283 (2026-08-02)

**Laut Issue:** `Group`-Entität organisationsgebunden mit Mitgliedern, `Group.kind` (`ORG_UNIT` aus dem Verzeichnis vs. `AD_HOC` im System angelegt), Group-Management-API und Admin-UI (anlegen, umbenennen, löschen, Mitglieder pflegen), eine Permission-Subject-Abstraktion (Nutzer oder Gruppe), gecachte Gruppenmitgliedschaftsauflösung mit sofortiger Invalidierung. Löschen einer Gruppe mit Asset-Eigentum soll blockiert sein; Mitgliedschaft nicht nach unten vererbt.

**Geliefert:** PR #283 liefert `Group`/`GroupMembership`, `PermissionSubject`, `GroupMembershipResolver` (Caffeine-Cache, Invalidierung nach Commit), `GroupService`/`GroupController` unter `/api/v1/admin/groups` (nur System-Admins, nur `AD_HOC`-Gruppen editierbar), Liquibase-Migration 009, Frontend-Seite „Gruppen". Ausdrücklich als nicht umgesetzt benannt: „Löschen einer Gruppe mit Asset-Eigentum blockieren" — es gibt zum Zeitpunkt dieses PRs noch kein Asset-Modell, dafür ein `TODO(#202)` in `GroupService#deleteGroup`. Zwei Review-Runden korrigierten u. a. eine Race-Bedingung bei der Cache-Invalidierung (mit Reproduktionsnachweis) und eine fehlende Organisationsgrenzprüfung im USER-Zweig von `resolveUserIds`. Ein Nit (asymmetrische DB-Grenze) wurde bewusst ausgelagert nach #289.

**Verifikation:** `backend/src/main/java/io/opaa/group/GroupService.java` und `frontend/src/pages/GroupManagementPage.tsx` existieren im heutigen Worktree.

**Themen:** auth, spaces, gruppen, security, migration

---

<a id="issue-201"></a>

## Issue #201 — Knowledge library as the document container, with data migration
- Geschlossen: 2026-08-03 (completed)
- Labels: enhancement, backend, size:L
- PRs: #305 (2026-08-03)

**Laut Issue:** `KnowledgeLibrary` als erster Asset-Typ (id, name, Beschreibung, Eigentümer user/group, Organisation, Sichtbarkeit, gelistet). `Document.libraryId` und `library_id` auf Chunk-Metadaten, nicht nullbar. Persönliche Bibliothek "My documents" wird zusammen mit dem persönlichen Space erzeugt. Library-CRUD-API und Dokumenten-Endpoint. Upload zielt auf eine Library statt einen Space. Relationale DB ist führender Speicher, Vektorstore ist abgeleitet. Bestehende Dokumente werden in eine nur für System-Admins lesbare Systembibliothek migriert. Trockenlauf mit Mengengerüst vor dem Backfill, resumierbare Migration, dokumentierte Rollback-Reihenfolge.

**Geliefert:** PR #305 liefert `KnowledgeLibrary` mit zwei separaten FK-Spalten `owner_user_id`/`owner_group_id` statt einer polymorphen `owner_id`, Migration 012 mit Systembibliothek-Seed und Single-UPDATE-Backfill (bewusst nicht gebatcht, im Migrationsdokument begründet), Löschschutz für Bibliotheken mit Dokumenten bzw. Gruppen mit Bibliotheken (409). Nutzeranlage erzeugt persönlichen Space und persönliche Bibliothek — laut PR ausdrücklich **nicht** als eine gemeinsame DB-Transaktion umgesetzt ("atomisch" im Sinn von "gemeinsam versucht, unabhängige Fehlergrenzen, Fehlschlag wird geloggt statt Login zu blockieren"), abweichend von der wörtlichen Formulierung "atomically" im Issue, aber mit Race-Schutz über partielle Unique-Indizes. Ein erster Reviewdurchgang deckte eine echte Nebenläufigkeits-Regression auf (kaschiert durch einen Test-Pool-Override), die daraufhin durch `INSERT ... ON CONFLICT ... DO NOTHING` plus prozesslokalem Lock behoben wurde.

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibrary.java` existiert im heutigen Worktree.

**Themen:** spaces, retrieval, migration, backend, dokumente

---

<a id="issue-202"></a>

## Issue #202 — Asset permissions and permission-aware vector search
- Geschlossen: 2026-08-04 (completed)
- Labels: enhancement, backend, size:L, security
- PRs: #309 (2026-08-04)

**Laut Issue:** Zentrale Lücke: `QueryService` filterte die Ähnlichkeitssuche bislang gar nicht. Gefordert: `AssetGrant` (Subjekt user/group → Rolle USER/VIEWER/EDITOR/MANAGER/OWNER, eigene Rangordnung getrennt von `SpaceRole`, kein Rollenname in beiden Systemen), jeder Grant von Anfang an mit optionalem Ablaufdatum, `readableLibraries(user)` = direkte Grants + Gruppen-Grants + organisationsweite Bibliotheken (Space-Zugehörigkeit fließt explizit nicht ein), Caching mit sofortiger Invalidierung, `library_id`-Metadatenfilter als Teil der `VectorStore`-Suche (kein Post-Filter). Zielvorgabe: Rechteauflösung soll unter 50 ms zur Query-Zeit hinzufügen.

**Geliefert:** PR #309 liefert `AssetGrant`/`AssetRole` mit disjunkter Rangordnung, `LibraryAccessService` als Ersatz der groben `canRead`/`canManage`-Zwischenlösung aus #201, `QueryService`-Filterung auf `readableLibraryIds` als Teil des Vektorsuche-Aufrufs, kein System-Admin-Bypass im Suchpfad. Wesentlicher Verhaltenswechsel gegenüber #201: Gruppen-Eigentümerschaft einer Bibliothek gewährt Mitgliedern keine automatischen Verwaltungsrechte mehr — nach drei Review-Runden erhält die Gruppe bei Erstellung nur `MANAGER`, der erstellende Nutzer persönlich `OWNER`. Die 50-ms-Zusage aus den Akzeptanzkriterien konnte laut PR **nicht** als belastbare, lastgetestete Garantie nachgewiesen werden — nur ein einzelner kalter Messwert (43 ms) mit einem großzügigeren 100-ms-Regressionstest statt harter 50-ms-Assertion; das Issue selbst hatte dies bereits als offenen Punkt markiert. Migration 013 vergibt beim Backfill für gruppen-eigene Bestandsbibliotheken bewusst keinen `OWNER`-Grant, nur `MANAGER` an die Gruppe (Entscheidung im Review bestätigt). Zwei Folge-Punkte wurden ausdrücklich nicht mitgenommen: fehlender `ResponseStatusException`-Handler im `GlobalExceptionHandler` (kommentiert, kein Issue benannt) und eine Race in `GroupService.deleteGroup` (ausgelagert nach #310).

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrant.java` und `backend/src/main/java/io/opaa/library/LibraryAccessService.java` existieren im heutigen Worktree.

**Themen:** security, retrieval, auth, spaces, migration, performance

---

<a id="issue-203"></a>

## Issue #203 — Space-asset association as pure curation
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, frontend, size:M, workspace
- PRs: #706 (2026-08-21)

**Laut Issue:** Eine Space-Bibliothek-Assoziation soll reine Kuratierung sein und keine Rechte gewähren oder Sichtbarkeit ändern. Kuratoren dürfen nur Bibliotheken zuordnen, auf die sie selbst Zugriff haben; der Bibliothekseigentümer sieht alle Assoziationen und kann jede lösen, wird aktiv benachrichtigt, wenn seine Bibliothek in einem Space mit engerem Leserkreis landet, und kann eine Bibliothek strikt-only markieren (Konflikte müssen beim Umschalten aufgelöst werden). Die Space-Ansicht filtert die Bibliotheksliste je Mitglied.

**Geliefert:** PR #706 setzt den Kernumfang um: `SpaceAssetAssociation`-Domänenmodell mit eigener Migration (051), `SpaceAssetAssociationService` mit den beschriebenen Berechtigungsregeln, API-Endpunkte (`GET/POST /spaces/{id}/libraries`, `DELETE`, `GET /libraries/{id}/spaces`), Retrieval-Integration in `ChatService`, sowie ein neuer, bewusst schmal geschnittener Benachrichtigungsmechanismus (`Notification`, `NotificationService`, Glocke im Frontend). Explizit ausgelassen wurden laut PR-Beschreibung: der Strikt-Modus/die strikt-only-Kennzeichnung (verschoben auf #204, da Strikt-Spaces noch nicht existieren) und der „@Space“-Chip in der Chat-Eingabe. Damit ist ein Teil der im Issue geforderten Abnahmekriterien (Strikt-Only-Konfliktauflösung) nicht Teil dieses PRs, sondern bewusst auf ein Folge-Issue verschoben.

**Verifikation:** `backend/src/main/java/io/opaa/space/SpaceAssetAssociation.java` und `backend/src/main/java/io/opaa/notification/NotificationService.java` existieren im heutigen Worktree-Stand.

**Themen:** spaces, workspace, retrieval, benachrichtigungen, auth

---

<a id="issue-204"></a>

## Issue #204 — Strict mode for spaces
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M, security
- PRs: keine

**Laut Issue:** Strikt-Modus als einzige technische Zusicherung im Modell (statt eines verantworteten menschlichen Akts): Ein Space mit `strictKnowledge` darf nur Bibliotheken assoziieren, deren Leserkreis alle Mitglieder abdeckt; ein Agent mit nicht vollständig abgedeckten Bindungen kann nicht aufgerufen werden; bricht die Voraussetzung nachträglich (Verzeichnissync, Grant-Entzug), geht der Space in den Zustand "Voraussetzung verletzt" mit benanntem Adressaten, Frist und Eskalation.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme: Der Umfang ist bewusst noch nicht gebaut und wird später angegangen; bei Wiederaufnahme wird der Zuschnitt neu auf Basis des dann aktuellen Stands von `docs/features/spaces-and-assets.md` bewertet. Kein Widerspruch — die Arbeit ist tatsächlich offen, nicht heimlich erledigt.

**Verifikation:** Kein `strictKnowledge`-Feld oder Ähnliches im Space-Modell erwartet (nicht separat geprüft, da Schließungskommentar eindeutig "noch nicht umgesetzt" bestätigt und keine Datei-Hinweise auf Gegenteiliges vorliegen).

**Themen:** spaces, security, rechteverwaltung

---

<a id="issue-205"></a>

## Issue #205 — Persistent chats inside spaces
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:L
- PRs: keine

**Laut Issue:** Neubau (keine Umbenennung) eines persistenten Chats als Space-eigenes Objekt: privat beim Autor, sichtbar für alle Mitglieder erst nach bewusstem Platzieren (`PRIVATE`→`SHARED`→`WITHDRAWN`), inklusive Provenienz-Verfolgung, Widerruf durch Autor/Space-Admin, Export auch privater Chats und Benachrichtigung bei wesentlicher Erweiterung des Leserkreises.

**Geliefert:** Teilweise, aber unter anderem Zuschnitt. Am 19.08.2026 wurde das Issue neu geschnitten: Die Persistenz-Grundlage (Chat/Nachrichten als persistente Objekte in genau einem Space, zunächst privat, CRUD-API, Query-Anbindung) entstand im separaten Epic #523 (konkret #525) — nicht in diesem Issue. Die Suchbereichsfrage wandert zum Schalter "Wissen nutzen" und @-Bibliotheksreferenzen (#526/#528). Beim Schließen (im Zuge von Epic #198) wird bestätigt: Der Kern — persistente Chats innerhalb eines Spaces, Neustart-Überleben, ein Chat pro Space, `ChatStatus.PRIVATE` — ist seit #525 umgesetzt. Der eigentliche Kollaborationsteil dieses Issues (Platzieren, Leserkreis, Provenienz-Hinweis im Freigabedialog, Widerruf, Export, `ChatParticipant`) ist **bewusst nicht umgesetzt** und bekommt erst bei Bedarf ein neues Ticket.

**Verifikation:** `backend/src/main/java/io/opaa/chat/Chat.java` existiert; keine `ChatStatus`-Datei mit `SHARED`/`WITHDRAWN`-Werten gefunden — bestätigt, dass nur der Persistenz-Kern (über #523) geliefert wurde, der Teilen-Lebenszyklus dieses Issues jedoch fehlt.

**Themen:** spaces, chats, retrieval, agenten

---

<a id="issue-206"></a>

## Issue #206 — Artifacts in spaces with lifecycle and provenance-based release
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Ergebnisse (Tabellen, Diagramme, später Berichte) sollen derselben Regel wie Chats folgen: privat beim Ersteller, sichtbar durch bewusstes Teilen in den Space. Ein generischer Space-eigener Inhaltstyp `Artifact` mit einheitlichem Status `PRIVATE`→`SHARED`→`SUPERSEDED`/`WITHDRAWN`, Provenienz-Kennzeichnung, Aufbewahrung und der Möglichkeit, ein Artefakt in eine Wissensbibliothek zu übernehmen (dann gelten deren Rechte statt der des Space).

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme: Der Umfang ist bewusst noch nicht gebaut und wird später angegangen, mit Neubewertung bei Wiederaufnahme.

**Verifikation:** Nicht separat geprüft; Abhängigkeit #205 (Kollaborationsteil) ist ebenfalls unerledigt, was die konsistente Nichtumsetzung stützt.

**Themen:** spaces, artefakte, retrieval

---

<a id="issue-207"></a>

## Issue #207 — Connector sources target exactly one knowledge library
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine (strukturell durch ADR-0018/Epic #486 gelöst, kein eigener PR zu diesem Issue)

**Laut Issue:** Eine Konnektorquelle soll genau eine Wissensbibliothek speisen (`SourceMapping.targetLibrary`, single-valued), mit Ausschluss einzelner Dokumente auf Bibliotheksebene. Zusätzlich sollte die Freigabe-Obergrenze definiert werden: was genau begrenzt wird (`visibility`, `listed`, Gruppengrößen-Grants), was beim nachträglichen Absenken passiert, und ob gemischt gespeiste Bibliotheken die Obergrenze tragen.

**Geliefert:** Die 1:1-Zuordnung ist nicht mehr nur Policy, sondern strukturell erzwungen: Mit ADR-0018 (Epic #486) gibt es keine separate `SourceMapping`-Tabelle mehr — die Wissensbibliothek selbst trägt `sourceType` und ihre Quellkonfiguration, unveränderlich nach Anlage. Mehrfachzuordnungen sind damit datenmodellseitig gar nicht mehr ausdrückbar. Der Ausschluss einzelner Konnektor-Dokumente wirkt an der Bibliothek. Die dritte Scope-Zeile des Issues ("System admin decides where indexing goes") ist überholt: ADR-0018 öffnet die Bibliotheksanlage zunächst für jeden Berechtigten, befristet bis #484 die Anlageberechtigung einschränkt (inzwischen geschehen). Gemischt gespeiste Bibliotheken (Upload + Konnektor) entfallen mit der Ein-Typ-Regel ersatzlos. **Nicht geliefert:** Die Definition und Durchsetzung der Freigabe-Obergrenze — der einzige inhaltlich offene Punkt — wurde in ein neues, fokussiertes Sub-Issue #797 herausgelöst (nicht Teil dieses Chunks).

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibrary.java` enthält ein `sourceType`-Feld (`DocumentSourceType`); keine `SourceMapping`-Klasse im Backend gefunden — bestätigt die Ablösung der ursprünglichen Tabelle durch ADR-0018.

**Themen:** konnektoren, wissensbibliotheken, retrieval, rechteverwaltung

---

<a id="issue-208"></a>

## Issue #208 — Stewards: group role for accepting shares
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: #331 (2026-08-14)

**Laut Issue:** Forderte eine Annahmeseite für Gruppen-Shares: neue Gruppenrollen `STEWARD` (akzeptiert/lehnt Shares an die Gruppe ab) und `LEAD` (ernennt Stewards, verwaltet bei `AD_HOC`-Gruppen die Mitgliedschaft). Ohne Steward routet die Entscheidung an die System-Admin-Arbeitsliste. Zusätzlich eine Schwellenwert-Regel gegen das Umgehen der Kuratierung über kumulative Reichweite je Asset/Empfängerkreis, inklusive Nachprüfung bei Gruppenwachstum durch Verzeichnissynchronisation.

**Geliefert:** PR #331 liefert das Gegenteil des geforderten Umfangs — es entfernt das gesamte Konzept der Annahmeseite ersatzlos, statt `STEWARD`/`LEAD` einzuführen: „Ein Grant an eine Gruppe braucht keine Zustimmung.“ Begründung laut PR: Ein Grant setzt niemanden etwas aus, das Risiko ist Katalog-Rauschen statt Datenabfluss, dagegen wirken `listed = false` und die Governance-Arbeitsliste. Der PR-Body vermerkt ausdrücklich „Schließt #208 gegenstandslos ab“. Zusätzlich entfernt der PR die Asset-Rolle `USER` (Migration 014, `USER`-Grants werden auf `VIEWER` gehoben, nicht gelöscht). Keines der im Issue formulierten Abnahmekriterien (Steward-/Lead-Rollen, Schwellenwert, Liegezeit-Liste) wurde umgesetzt — sie wurden als überflüssig verworfen.

**Verifikation:** `grep -rl STEWARD backend/src/main/java` liefert keinen Treffer — die Rolle existiert im heutigen Code nicht. `AssetRole.java` dokumentiert die verworfene `USER`-Rolle nur noch im Kommentar.

**Themen:** auth, spaces, rechtemodell, governance, verworfenes-feature

---

<a id="issue-209"></a>

## Issue #209 — Agent and prompt library assets with the knowledge share chain
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:L
- PRs: keine

**Laut Issue:** Zweiter und dritter Asset-Typ (`Agent`, `PromptLibrary`) mit dem entscheidenden neuen Mechanismus: der Freigabekette beim Teilen eines Agenten — welche Bibliotheken er braucht, Anfrage nach Ko-Freigabe an fehlende Bibliothekseigentümer, Ergebnis-Report ("2 von 3 ko-freigegeben"). Ein Agent retrieved immer mit den Rechten des aufrufenden Nutzers, nie mit eigenen.

**Geliefert:** Nicht umgesetzt. Beim Schließen als "noch nicht umgesetzt, später" markiert (Ticket-Hygiene im Zuge der Epic-#198-Schließung). Vor dem Schließen gab es aber eine wichtige inhaltliche Aktualisierung (23.08.2026): Die im Issue vorgesehene `USER`-Rolle existiert nicht mehr — #330 hat `AssetRole.USER` gestrichen, niedrigste Rolle ist jetzt `VIEWER`; die fachliche Anforderung ("Agent nutzen ohne Konfiguration zu sehen") bleibt bestehen, braucht bei Umsetzung aber einen anderen Mechanismus. Außerdem läuft die Modellwahl inzwischen über die verwalteten Chat-Modelle aus Epic #755 (Modelle in der Datenbank, verwaltet unter `admin/models`) statt über einen freien Modellnamen.

**Verifikation:** Keine `Agent.java`/`PromptLibrary.java` als Asset-Klassen im Backend gefunden (`find` ohne Treffer) — bestätigt Nichtumsetzung.

**Themen:** agenten, spaces, rechteverwaltung, modellverwaltung

---

<a id="issue-210"></a>

## Issue #210 — Asset parameters: adapt without forking
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:S
- PRs: keine

**Laut Issue:** Ein typisierter, kurzer Parametermechanismus (Name, Typ, erlaubte Werte, Default) soll Empfängern erlauben, ein Asset ohne Fork anzupassen — explizit kein Templating-System. Sollte im selben Release wie #209 (Agent-Assets) ausgeliefert werden, weil sonst zwischenzeitlich entstandene Forks nicht mehr eingesammelt werden können.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme, ohne inhaltlichen Kommentar über das Standardmuster hinaus. Konsistent mit #209, von dem dieses Issue abhängt und das ebenfalls nicht umgesetzt ist.

**Verifikation:** Nicht separat geprüft — Abhängigkeit #209 (Agent-Assets) ist unerledigt, ein Parametermechanismus ohne Asset-Typ ist ausgeschlossen.

**Themen:** agenten, spaces

---

<a id="issue-211"></a>

## Issue #211 — Asset versioning with immediate propagation and rollback
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Ein verteiltes Asset ist eine Referenz, keine Kopie — Verbesserungen wirken für alle Nutzer sofort. Das braucht eine vollständige Historie (`AssetVersion` mit Autor, Zeitstempel, Grund, Konfigurations-Snapshot), einen aktiven Versionszeiger und Rollback ohne Löschung älterer Versionen. Versioniert wird die Konfiguration, nicht der Dokumentbestand.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme. Abhängigkeit #209 (Agent-Assets) ist ebenfalls unerledigt, sodass eine Versionierung ohnehin keinen Gegenstand hätte.

**Verifikation:** Nicht separat geprüft — logische Konsequenz aus der Nichtumsetzung von #209.

**Themen:** agenten, spaces

---

<a id="issue-212"></a>

## Issue #212 — Recall by deactivation, with warnings in existing transcripts
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Ein fachlich veraltetes Asset wird deaktiviert, nie gelöscht — mit Pflichtbegründung. Bestehende Chats bleiben lesbar, tragen aber an den Stellen, wo das Asset verwendet wurde, eine sichtbare Warnung mit Grund und Datum. Eine deaktivierte Wissensbibliothek liefert keine Treffer mehr; bestehende Zitate bleiben lesbar und rechteseitig geprüft.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme. Abhängigkeiten (Provenienz-Tracking aus #204, persistente Chats aus #205) sind ebenfalls nicht im hier beschriebenen Umfang erledigt.

**Verifikation:** Nicht separat geprüft.

**Themen:** agenten, wissensbibliotheken, spaces

---

<a id="issue-213"></a>

## Issue #213 — Derivatives with permanent provenance and drift protection
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M, security
- PRs: keine

**Laut Issue:** Wo Parameter (#210) nicht ausreichen, entsteht ein Abkömmling (Derivat), der seine Herkunft dauerhaft trägt und sichtbar als abgeleitet markiert ist — kein stilles Kopieren. Umfasst nur die "billigen" Teile: Herkunfts-Referenz, sichtbare "abgeleitet von"-Markierung, Versions-Drift-Anzeige, Benachrichtigung bei neuer Originalversion. Fristen, Prüfaufforderungen und automatische Deaktivierung sind bewusst nach #243 ausgelagert.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme. Abhängigkeit #211 (Versionierung) ist ebenfalls unerledigt.

**Verifikation:** Nicht separat geprüft.

**Themen:** agenten, spaces, security

---

<a id="issue-214"></a>

## Issue #214 — Built-in assets as a distinct origin type
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** Mitgelieferte, bewährte Verwaltungs-Agenten und -Prompts sollen mit dem Produkt aktualisierbar sein, ohne je lokale Anpassungen der Behörde zu überschreiben (`Asset.origin`: `BUILT_IN`/`LOCAL`). Anpassung nur über Derivate. Offene Frage: Update-Weg für Netze ohne Internetzugang, Signatur- und Herkunftsnachweis.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme. Abhängigkeiten (#213 Derivate, #212 Deaktivierung) sind ebenfalls unerledigt.

**Verifikation:** Nicht separat geprüft.

**Themen:** agenten, spaces, projektsetup

---

<a id="issue-215"></a>

## Issue #215 — Asset catalog: visibility, listed flag and space directory
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Ein Katalog mit `visibility` (`PRIVATE`/`SHARED`/`ORGANIZATION`) und `listed`-Flag (Default `false`), Verteilungsstufen über die Grant-Subjekte, Katalogsuche über zugängliche plus gelistete Assets, sowie ein Space-Verzeichnis mit Sichtbarkeitsstufen (`PRIVATE`/`DISCOVERABLE`/`OPEN`) samt Beitrittsanfrage und Ein-Klick-Selbstbeitritt.

**Geliefert:** Teilweise als Datenmodell, der eigentliche Kern fehlt. Laut Aktualisierungskommentar (23.08.2026) bereits vorhanden aus #202/#333: `LibraryVisibility` (`PRIVATE`/`SHARED`/`ORGANIZATION`) und `listed`-Flag an der Wissensbibliothek, `SpaceVisibility` (`PRIVATE`/`DISCOVERABLE`/`OPEN`), Bibliothekseigentümer als Nutzer oder Gruppe (`LibraryOwnerType`). **Noch offen:** das Space-Verzeichnis mit Beitrittsanfrage/Selbstbeitritt — es gibt keine Join-Endpunkte, die Sichtbarkeitsstufen sind reine Datenhaltung — und die Katalogsuche, die erst mit den Asset-Typen aus #209 sinnvoll wird. Die Abhängigkeit #208 (Stewards) ist mit #330 entfallen (Annahme eines Grants durch eine Gruppe ist gestrichen). Beim Schließen im Zuge von Epic #198 als "noch nicht umgesetzt" bestätigt.

**Verifikation:** Existenz von `LibraryVisibility`/`SpaceVisibility` als Datenmodell plausibel (durch Kommentar belegt, nicht separat gegrept); keine Join-/Katalog-Endpunkte erwartet und nicht gefunden.

**Themen:** spaces, wissensbibliotheken, rechteverwaltung

---

<a id="issue-216"></a>

## Issue #216 — Governance controls for co-determination
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M, security
- PRs: keine

**Laut Issue:** Aufbewahrung für Chats, Artefakte und private Inhalte, vom System-Admin voreingestellt, mit Vorwarnung vor Ablauf, Verlängerungsoption, einer nur dem Autor sichtbaren "meine privaten Inhalte"-Liste, Aggregation je Organisationseinheit mit Mindestgruppengröße, technisch durchgesetzten Speicherquoten ohne Auswertungspfad, keinen Ranglisten und protokollierten Governance-Änderungen. Der Audit-Teil wurde bereits nach #239 verlagert.

**Geliefert:** Nicht als eigenständiges Feature umgesetzt. Ein Nachtrag aus #395/#454 (Audit-Aufbewahrung) zeigt eine Vorbereitung: Ein Abnahmekriterium aus #395 ("Protokollfrist kürzer als Inhaltsaufbewahrung erzeugt Warnung") ist nur als Erweiterungspunkt vorbereitet (`io.opaa.audit.ContentRetentionProvider`), aber nicht wirksam — ohne registrierte Inhaltsaufbewahrung-Konfiguration liefert die Prüfung immer `false`. Sobald dieses Issue (oder ein Nachfolger) eine konfigurierbare Inhaltsaufbewahrung einführt, genügt eine `@Component`-Implementierung von `ContentRetentionProvider`, damit die Warnlogik in `AuditRetentionSettingsService` greift. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** `io.opaa.audit`-Paket im Backend existiert (`AuditRetentionSettingsService.java` u. a.) — Erweiterungspunkt `ContentRetentionProvider` plausibel, aber keine Inhaltsaufbewahrungs-Logik für Chats/Artefakte gefunden.

**Themen:** governance, security, retention, spaces

---

<a id="issue-218"></a>

## Issue #218 — feat(agents): add six public-administration stakeholder agents for concept review
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #220 (2026-08-02)

**Laut Issue:** Forderte sechs Stakeholder-Agenten (sachbearbeiter, referatsleitung, ki-champion, betrieb, skeptiker, personalrat), die Konzepte aus einer benannten Verwaltungsperspektive schriftlich bewerten, ohne Produktionscode zu schreiben. Umfang: gemeinsame Rollenverträge in `agents/roles/`, Client-Adapter für `.claude/`, `.codex/`, `.opencode/`, sowie Erweiterung von `docs/AGENT-ORGANIZATION.md`.

**Geliefert:** PR #220 liefert alle sechs Rollen deckungsgleich mit dem Issue: Rollenverträge in `agents/roles/`, Adapter für alle drei Clients (`.claude/agents/`, `.codex/agents/`, `.opencode/agents/`), sowie die Dokumentationserweiterung. Alle sechs teilen laut PR-Beschreibung ein gemeinsames Bewertungsformat; die Skeptiker-Rolle trägt explizit die Regel, dass Einwände konkret und überprüfbar sein müssen. Keine erkennbaren Abweichungen vom Issue-Umfang.

**Verifikation:** Alle sechs Dateien liegen sowohl unter `agents/roles/` als auch unter `.claude/agents/` im heutigen Worktree-Stand (stakeholder-betrieb, -ki-champion, -personalrat, -referatsleitung, -sachbearbeiter, -skeptiker).

**Themen:** agenten-organisation, doku, stakeholder-review

---

<a id="issue-219"></a>

## Issue #219 — docs: Projektsprache Deutsch für Issues und Pull Requests festlegen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, size:S
- PRs: #222 (2026-08-02)

**Laut Issue:** Die Templates waren bereits Deutsch, die Regelwerke schrieben aber weiterhin Englisch für Issue-/PR-Titel und -Beschreibungen vor. Gefordert: Umstellung von `AGENTS.md`, `CONTRIBUTING.md`, `.github/ISSUE_TEMPLATE/feature_request.md`, `.github/copilot-instructions.md` sowie der Agenten-Rollen product-manager, developer, qa-engineer auf die Regel „Deutsch für Issues/PRs/Doku, Englisch für Quellcode“.

**Geliefert:** PR #222 setzt den Umfang deckungsgleich um, inklusive eines neuen Abschnitts „Projektsprache“ in `AGENTS.md`. Zusätzlich zur Liste im Issue wurde auch `agents/roles/marketing.md` nachgezogen (im Issue nicht genannt, aber sachlich naheliegend) sowie ein Nebenfund korrigiert: `product-manager.md` verwies noch auf englische Abschnittsüberschriften, obwohl die Templates längst deutsche Überschriften nutzen. Die Umstellung der Anwendungstexte selbst wurde bewusst ausgeklammert und in Issue #221 verschoben.

**Verifikation:** `AGENTS.md` enthält im heutigen Stand den Abschnitt „## Projektsprache“ mit der beschriebenen Deutsch/Englisch-Trennung.

**Themen:** doku, projektsprache, agenten-organisation

---

<a id="issue-221"></a>

## Issue #221 — feat: Anwendungstexte auf Deutsch umstellen (Frontend und Backend)
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, frontend, size:L
- PRs: #223 (2026-08-02), #286 (2026-08-02, sachfremd), #291 (2026-08-02, sachfremd)

**Laut Issue:** Alle sichtbaren Frontend-Texte (Seiten, Layouts, Komponenten, Platzhalter, Fehlermeldungen, `aria-label`) sowie nutzerseitige Backend-Texte (API-Fehlermeldungen, Default-Workspace-Name) sollten auf Deutsch umgestellt werden, ohne i18n-Framework, mit `de-DE`-Datumsformatierung. Log-Meldungen und Bezeichner bleiben Englisch.

**Geliefert:** PR #223 liefert den vollen Umfang: Frontend-Texte inklusive `aria-label` umgestellt, neue Übersetzungs-Zuordnung `frontend/src/utils/labels.ts` für Enum-Anzeigewerte (API-Werte selbst unverändert), `de-DE`-Datumsformatierung, deutsche API-Fehlermeldungen in `GlobalExceptionHandler`/Controllern/`WorkspaceService`, Standard-Workspace jetzt „Meine Dokumente“, feste Locale `de_DE` für Bean-Validation, UTF-8-Fix für `JavaCompile`/`Test`-Tasks gegen kaputte Umlaute unter Windows. Ein vorbestehender, unabhängiger Accessibility-Testfehler wird im PR benannt, nicht behoben.

Auffällig: GitHub verknüpft zusätzlich #286 und #291 mit diesem Issue. Beide gehören inhaltlich nicht hierher — es sind CI-Änderungen am täglichen Report-Skript (`daily_report.py`). Die Verknüpfung entsteht vermutlich, weil #286 in seiner eigenen Prüfliste den Testfall-String „Closes #221“ als Beispiel für ein Regex-Muster nennt; GitHub übernimmt das offenbar trotz Inline-Code-Formatierung als echte Closing-Referenz. #291 behebt just diesen Fehlzuordnungs-Bug im Report-Skript selbst (u. a. genau dieses Beispiel), bleibt aber ebenfalls fälschlich mit #221 verknüpft. Für die Leistungsinventur zählt inhaltlich nur #223.

**Verifikation:** `frontend/src/utils/labels.ts` existiert. `SpaceService.java` (nach Ablösung von `WorkspaceService` durch das Space-Modell) verwendet weiterhin „Meine Dokumente“ als Namen der persönlichen Bibliothek.

**Themen:** frontend, backend, projektsprache, i18n, doku-datenqualität

---

<a id="issue-224"></a>

## Issue #224 — Epic: Suchqualität messbar machen — Eval-Korpus und Retrieval-Regression
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, epic, size:L, evaluation
- PRs: keine

**Laut Issue:** Epic zur messbaren Suchqualität von OPAA — lizenzsauberer, eingefrorener Testkorpus und automatische Retrieval-Regression in CI. Phase 1 (Korpus-Generator, Golden Dataset, Metrik-Harness, CI-Regression, Tickets #225–#228) sollte end-to-end für eine Domäne funktionieren. Die ursprünglich geplante Phase 2 (Demo-Instanz) wurde am 21.08.2026 in ein eigenes Epic (#708) ausgegliedert; dieses Epic behält nur die Messung selbst.

**Geliefert:** Kein PR referenziert „Closes #224“ — das ist beim Epic-Muster dieses Projekts der Normalfall (siehe AGENTS.md: Epics führen Sub-Issues als native Verknüpfung, nicht als direkt schließenden PR). Die eigentliche Lieferung liegt in den Sub-Issues #225–#228, die laut Epic-Body als „Abgeschlossen“ markiert sind. `git log --grep="224"` zeigt zwei branchbezogene Merge-Commits (`feature/224_search-quality-evaluation`, PR #236; `feature/224_spec-korrektur`, PR #253) — beides Dokumentations-/Spezifikationsarbeit direkt am Epic (ADR-0008, Spezifikation `docs/features/search-quality-evaluation.md`), nicht die Implementierung selbst. Die Abnahmekriterien auf Epic-Ebene sind laut Issue-Text als erfüllt abgehakt (Korpus mit SHA-256-Manifest, Golden Dataset mit 121 Fällen, CI-Regression). Das Epic wurde also nicht durch einen eigenen PR, sondern durch den Abschluss seiner Sub-Issues erledigt — konsistent mit dem Prozess für Epics in diesem Repository.

**Verifikation:** `eval/corpus/comic-characters/` und `eval/corpus/city-landmarks/` existieren, `eval/golden/comic-characters.json` existiert, `.github/workflows/retrieval-regression.yml` existiert im heutigen Worktree-Stand — die im Epic behaupteten Artefakte sind tatsächlich vorhanden.

**Themen:** evaluation, retrieval, ci, epic, agenten-organisation

---

<a id="issue-225"></a>

## Issue #225 — feat(eval): Korpus-Generator für die Domäne Comichelden
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:M, evaluation
- PRs: #249 (2026-08-02)

**Laut Issue:** Deterministischer Python-Generator, der aus dem eingefrorenen HuggingFace-Datensatz `jrtec/Superheroes` (CC0-1.0, ~1.450 Entitäten, kein Sampling) je Comicfigur ein Markdown-Dokument mit YAML-Frontmatter und selbst formuliertem Fließtext erzeugt, damit ein Dokument in der produktiven Chunking-Pipeline genau einen Chunk ergibt. Vorgaben u. a.: `MANIFEST.sha256`, `SOURCE.md`, Byte-Identität bei wiederholtem Lauf, max. 4 KB je Dokument, Gesamtkorpus unter 5 MB, keine Übernahme der langen Freitextfelder der Quelle.

**Geliefert:** `eval/generator/generate_corpus.py` erzeugt 1.448 Dokumente in `eval/corpus/comic-characters/` (größtes 2.573 Bytes, Gesamtkorpus ~1,88 MB), `MANIFEST.sha256` und `SOURCE.md` vorhanden, Determinismus per `diff -r` verifiziert. Nach Review verschärft: Byte-Grenze von 4 KB auf 3.000 Bytes gesenkt (Tokengrenze ist die eigentliche Invariante, nicht die Byte-Grenze), dazu neues ADR-0010 zur Ein-Chunk-Invariante, `height_cm`-Normalisierung erweitert, `teams` als echte YAML-Sequenz statt kommagetrennter String. Feldname `superpowers` statt der im Issue genannten „Liste der gesetzten Fähigkeiten" (an der Spezifikation orientiert). Zwei Prosa-Bugs (Verb-Kongruenz, a/an-Heuristik) während der Stichprobenprüfung mitbehoben.

**Verifikation:** `eval/corpus/comic-characters/` enthält im Worktree 1.450 Einträge (inkl. `MANIFEST.sha256`, `SOURCE.md`), `eval/generator/generate_corpus.py` existiert weiterhin. ADR-0010 liegt unter `docs/decisions/`.

**Themen:** eval, retrieval, korpus, python-tooling, doku

---

<a id="issue-226"></a>

## Issue #226 — feat(eval): Golden Dataset aus dem Frontmatter des Korpus ableiten
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:M, evaluation
- PRs: #273 (2026-08-02)

**Laut Issue:** Aus dem YAML-Frontmatter des Comic-Korpus (#225) soll ein Golden Dataset mit mindestens 100 kuratierten Fällen über fünf Frage-Kategorien (`attribute_lookup`, `entity_description`, `multi_attribute_filter`, `numeric_range`, `crosslingual`) abgeleitet werden; Ground Truth rein rechnerisch aus den Feldern, nicht von einem LLM geschätzt. Mindestens 30 Fälle auf Deutsch, Filterfragen mit 2–15 Treffern, alle Fälle manuell durchgesehen, Kuratierungsregeln dokumentiert.

**Geliefert:** `eval/generator/generate_golden_dataset.py` erzeugt `eval/golden/comic-characters.json` mit 121 kuratierten Fällen (87 en / 34 de) über alle fünf Kategorien, plus `comic-characters.candidates.json` (477 automatisch validierte Rohkandidaten) und `comic-characters.discarded.json` (356 verworfene Kandidaten mit Begründung) als Kuratierungsnachweis. Zwei zusätzliche verbindliche Filter eingeführt (`overall_score is not null`, Plausibilitätsprüfung verunreinigter Quellspalten `first_appearance`/`occupation`), ein Übersetzungsbug bei deutschen Mehrwort-Werten behoben. Abweichung: `entity_description` über Geburtsort+Beruf ist mit 4 von 20 möglichen Fällen unterrepräsentiert (uneinheitliche Quelldaten), bewusst nicht entfernt, da Ground Truth korrekt bleibt.

**Verifikation:** `eval/golden/comic-characters.json`, `.candidates.json` sowie `eval/golden/README.md` existieren im heutigen Code; `eval/generator/generate_golden_dataset.py` vorhanden.

**Themen:** eval, retrieval, golden-dataset, python-tooling, doku

---

<a id="issue-227"></a>

## Issue #227 — test(eval): Retrieval-Metrik-Harness (Hit Rate, MRR, nDCG, Recall)
- Geschlossen: 2026-08-03 (completed)
- Labels: enhancement, backend, size:L, evaluation
- PRs: #292 (2026-08-03)

**Laut Issue:** JUnit-Integrationstest, der den eingefrorenen Korpus über die produktive Indizierungs-Pipeline indiziert, alle Golden-Queries gegen den Vektor-Store ausführt und Hit Rate@5, MRR, nDCG@10, Recall@10 berechnet — gesamt sowie je Kategorie/Schwierigkeit/Sprache. Eigener Gradle-Task außerhalb von `build`, Manifest-Prüfung vor dem Lauf, maschinenlesbarer Report mit Lauf-Konfiguration, Stabilitätsnachweis (<0,01 Abweichung zwischen zwei Läufen), Ausweis der zehn schlechtesten Anfragen.

**Geliefert:** Eigenes Gradle-Source-Set `backend/src/evalTest/` mit Task `evaluateRetrieval` (bewusst nicht an `build`/`check`/`test` gehängt), Testcontainers pgvector + Ollama/`nomic-embed-text`. Prüft zusätzlich zur Manifest-Summe die Ein-Chunk-Invariante aus ADR-0010 (jedes Dokument muss genau einen Chunk ergeben) und benennt Verletzungen. JSON-Report unter `backend/build/eval-reports/retrieval-metrics.json`. Gemessene Baseline-Werte: Gesamt-nDCG@10 0,463 (später bei #228 auf 0,445 mit gepinntem Modell korrigiert). ADR-0012 zum Messvertrag neu angelegt. Kein Abweichen vom Issue-Umfang erkennbar.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/` enthält alle im PR genannten Klassen (`RetrievalEvaluationHarnessTest.java`, `CorpusManifest.java`, `MetricsAggregate.java`, `ReportWriter.java` u. a.) plus seither ergänzte Domäne `city-landmarks` (Issue #234-Folgearbeit). Task `evaluateRetrieval` im `backend/build.gradle.kts` vorhanden. ADR-0012 liegt unter `docs/decisions/`.

**Themen:** eval, retrieval, backend, testinfrastruktur, gradle, doku

---

<a id="issue-228"></a>

## Issue #228 — ci(eval): Retrieval-Regressionsjob mit Baseline und Schwellenwerten
- Geschlossen: 2026-08-03 (completed)
- Labels: enhancement, size:M, ci, evaluation
- PRs: #301 (2026-08-03)

**Laut Issue:** Der Retrieval-Harness aus #227 soll automatisiert in GitHub Actions gegen eine committete Baseline laufen und bei Qualitätsverlust fehlschlagen. Auslöser: nächtlicher Zeitplan auf `main`, `workflow_dispatch`, Label `evaluation` an einem PR — bewusst nicht bei jedem PR. Zweistufiges Fehlerkriterium (harte Untergrenze + max. Verschlechterung, Vorschlag 0,03 absolut), Delta-Tabelle als PR-Kommentar, Laufzeit unter 20 Minuten, keine Secrets nötig, Baseline-Update-Verfahren dokumentiert.

**Geliefert:** `io.opaa.eval.BaselineComparator` vergleicht Fixpunkte (Manifest, Golden-Dataset-Hash, Modell-Digest, Chunk-Größe, Messvertrag-Version) exakt und meldet bei Abweichung „Baseline ungültig" statt einer Regressionsaussage. Toleranz je Gruppe/Metrik über eine Formel `min(max(0.12·Baselinewert, 1/n, 0.02), 0.05)` statt des im Issue vorgeschlagenen festen 0,03-Werts — begründet mit sehr unterschiedlicher Streuung zwischen großen/kleinen Gruppen. Zusätzlich baseline-unabhängige harte Untergrenzen für die vier Gesamtmetriken. Workflow `.github/workflows/retrieval-regression.yml` mit Modell-Cache über `actions/cache`; Fehlschlag beim nächtlichen/manuellen Lauf legt automatisch ein GitHub-Issue an. Baseline (`eval/baseline/comic-characters.json`) mit gepinntem `nomic-embed-text:v1.5`.

**Verifikation:** `.github/workflows/retrieval-regression.yml`, `eval/baseline/comic-characters.json`, `eval/baseline/README.md`, `backend/src/evalTest/java/io/opaa/eval/BaselineComparator.java` und `BaselineRegressionTest.java` existieren im heutigen Code. ADR-0013 zum Fehlerkriterium liegt unter `docs/decisions/`.

**Themen:** eval, retrieval, ci, github-actions, backend, doku

---

<a id="issue-229"></a>

## Issue #229 — feat(demo): Rheinfurt-Korpus und RSS-Feed im Compose-Stack bereitstellen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, setup, size:M, demo
- PRs: #722 (2026-08-21)

**Laut Issue:** Der Rheinfurt-Demo-Korpus wird im Docker-Compose-Stack über einen statischen Webserver plus RSS-Feed bereitgestellt, indiziert über die **bestehenden** Konnektoren (`AutoindexCrawlerService`, RSS-Konnektor), ohne neuen Ingestion-Code. Drei `HTTP_DIRECTORY`-Unterverzeichnisse, Feed samt HTML-Detailseiten auf demselben Host, Compose-Profil `demo`, Allowlist-Eintrag für die Zielprüfung (#267). Das Ticket ersetzt eine frühere Fassung, die noch auf dem Superhelden-Eval-Korpus per Bind-Mount formuliert war.

**Geliefert:** Zwei neue Compose-Services (`demo-corpus`, `demo-presse`, beide `httpd:2.4-alpine` mit `IndexOptions FancyIndexing HTMLTable`) unter Profil `demo`; `demo-presse` unter eigenem Netzwerk-Alias `presse.stadt-rheinfurt.example`. Allowlist-Wert wandert nach Review von `docker-compose.yml` in `.env.docker` (Betreiberangabe), da `environment:` sonst jede eigene Einstellung überschrieben hätte. Bei der Verifikation gegen den echten Stack wurden zwei **vorbestehende** Bugs im `AutoindexCrawlerService` gefunden und mitbehoben, weil sie die geforderte vollständige Indizierung blockierten: gekürzte Dateinamen im `HTMLTable`-Layout wurden aus dem sichtbaren Linktext statt aus `href` gelesen, und ein literales `+` in Dateinamen wurde fälschlich zu Leerzeichen decodiert; dazu eine DB-Check-Constraint-Migration (057), die `FORMAT_MISMATCH` als Ereigniskategorie zuließ. Alle drei mit eigenem Reproduktionsnachweis.

**Verifikation:** `docker-compose.yml` enthält `demo-corpus`/`demo-presse`; `demo/webserver/httpd-demo-autoindex.conf` sowie `demo/README.md` existieren. `backend/src/main/java/io/opaa/indexing/AutoindexCrawlerService.java` und die Migration `057-widen-indexing-run-event-category-format-mismatch.yaml` sind im heutigen Code vorhanden.

**Themen:** demo, deployment, indexing, docker-compose, bugfix

---

<a id="issue-230"></a>

## Issue #230 — feat(demo): Demo-Instanz Rheinfurt auf opaa.ewerlin.com oder alternativem Host ausrollen
- Geschlossen: 2026-08-22 (completed)
- Labels: documentation, enhancement, frontend, size:M, demo
- PRs: keine

**Laut Issue:** Die Demo-Instanz „Stadt Rheinfurt" soll auf einem erreichbaren Host (bevorzugt der bestehenden Instanz `opaa.ewerlin.com`) ausgerollt werden: Korpus-/Feed-Bereitstellung ohne Repo-Checkout, Seed im Profil `demo` (vier Nutzer, Spaces, Bibliotheken, Rechte), Allowlist-Eintrag, Rate Limiting, hartes Ausgabenlimit beim Chat-Anbieter, sichtbarer Quellen- und Demo-Hinweis im Frontend, Anmeldepflicht ohne anonymen Lesepfad, Nachweis dass der Index den nächtlichen Auto-Update-Cron überlebt. Das Ticket wurde am 2026-08-21 vom ursprünglichen Superhelden-Korpus-Rollout auf das neue Rheinfurt-Demo-Konzept umgestellt.

**Geliefert:** Kein verknüpfter PR — GitHub hat keine automatische Verlinkung, weil kein Commit die Formulierung „Closes #230" verwendet. Trotzdem eindeutig umgesetzt: Commit `2152ea54` „docs(demo): Rollout der Demo-Instanz Stadt Rheinfurt dokumentieren (#732)" (Merge von PR #732) ändert `README.md`, `docs/deployment.md` und `docs/demo-walkthrough.md` und referenziert im Commit-Body ausdrücklich „opaa.ewerlin.com ist seit dem 21.08.2026 die Demo-Instanz 'Stadt Rheinfurt' (#230, Epic #708)" sowie „Teil von #230". Dokumentiert werden Korpus-/Webserver-Bereitstellung ohne Repo-Checkout, Zielprüfung-Allowlist, temporäre Aktivierung von `opaa-seed` samt Passwortrotation von `demo-admin`, `OPAA_DEMO_MODE` und die Anthropic-Console als Ort des Ausgabenlimits. Die vorausgehenden Bausteine — Seed-Mechanismus (`4eac585c`, #724), Quellen-/Demo-Hinweis im Frontend (`91a061be`, #728) und ein E2E-Smoke-Test für das Demo-Profil (`ff726f4f`, #729) — gehören technisch zu #712/#713, liefern aber die für #230 geforderten Abnahmekriterien (Quellenhinweis, Demo-Hinweis, funktionierender Seed) mit. Der eigentliche operative Rollout (Server-Deployment, Ausgabenlimit-Konfiguration, Rate-Limit-Prüfung) findet außerhalb des Repositories statt und ist naturgemäß nicht als Diff sichtbar — das Ticket dokumentiert bewusst nur das Verfahren, nicht Serverpfade oder Zugangsdaten.

**Verifikation:** `README.md` verlinkt `opaa.ewerlin.com` als öffentliche Rheinfurt-Instanz („Anmeldung erforderlich, kein anonymer Zugang"). `docs/deployment.md` enthält die im Commit beschriebenen Abschnitte. Damit ist die Lieferung — anders als der Issue-Linking-Mechanismus suggeriert — nachweisbar erfolgt, nur ohne maschinenlesbare Verknüpfung.

**Themen:** demo, deployment, doku, betrieb, frontend

---

<a id="issue-231"></a>

## Issue #231 — test(e2e): Grundgerüst für browserbasierte End-to-End-Tests
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, frontend, size:M, ci
- PRs: #251 (2026-08-02)

**Laut Issue:** Grundgerüst für eine browserbasierte E2E-Suite, da das Repo keinerlei E2E-Infrastruktur besitzt. Umfang: Werkzeugwahl, Verzeichnisstruktur, reproduzierbares Hochfahren des vollständigen Stacks, wiederverwendbare Testnutzer-Anmeldung, ein Rauchtest, CI-Job mit Trace/Screenshot-Artefakten bei Fehlschlag, unter 10 Minuten Laufzeit. Der Auth-Modus für die Suite war als offene Entscheidung markiert (Vorschlag `mock`).

**Geliefert:** Playwright-Suite unter `e2e/` (Begründung in `e2e/README.md`), Stack-Start via Docker Compose (`e2e/docker-compose.e2e.yml`, `e2e/scripts/run-e2e.mjs`), wiederverwendbare Login-Fixture (`e2e/fixtures/auth.ts`), ein Rauchtest (`e2e/tests/smoke.spec.ts`), CI-Workflow `.github/workflows/e2e.yml` mit `timeout-minutes: 10` und Trace/Screenshot-Upload bei Fehlschlag, ADR `docs/decisions/0009-e2e-teststrategie.md`, Aktualisierung von `AGENTS.md`/`CONTRIBUTING.md`. Abweichung vom Issue: Der vorgeschlagene `mock`-Auth-Modus war technisch nicht nutzbar (`opaa.auth.mode: mock` ist im Backend nur ein Frontend-Signal ohne aktives Spring-Security-Profil; ohne `basic`/`oidc` existieren die Fach-Controller gar nicht). Stattdessen läuft die Suite im `basic`-Modus mit fest hinterlegten Wegwerf-Zugangsdaten — dokumentierte, begründete Abweichung.

**Verifikation:** `e2e/README.md`, `e2e/playwright.config.ts`, `.github/workflows/e2e.yml` und `docs/decisions/0009-e2e-teststrategie.md` existieren im heutigen Code. Die Suite wurde seither von #233 (Seed-Umstellung) und #232 (Demo-Smoke) erweitert, das Grundgerüst blieb tragfähig.

**Themen:** e2e, ci, testinfrastruktur, playwright, auth

---

<a id="issue-232"></a>

## Issue #232 — test(e2e): Smoke-Test für das Demo-Profil
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:S, demo
- PRs: #729 (2026-08-21)

**Laut Issue:** Genau ein Smoke-Test gegen das Compose-Profil `demo`: Stack startet, Seed läuft fehlerfrei durch (Nutzer, Spaces, fünf Bibliotheken, Rechte), alle Indizierungsläufe enden `COMPLETED`, eine Demo-Nutzerin stellt eine Drehbuchfrage und erhält eine belegte Antwort mit Quellenangabe. Ursprünglich war das Ticket als fünfteilige E2E-Prüfung gegen den (inzwischen ersetzten) Superhelden-Korpus formuliert; das Demo-Konzept schneidet die Testarbeit neu: nur ein grobkörniger Smoke-Test gegen `demo`, Feature-Tests laufen gegen das separate `e2e`-Profil (#233).

**Geliefert:** Ein Playwright-Test `e2e/demo-smoke/tests/demo-smoke.spec.ts`, bewusst außerhalb von `e2e/tests/`, läuft nie in `npm test` mit. Eigenes Compose-Overlay `e2e/docker-compose.demo-smoke.yml` (ai-stub statt Ollama), eigene Env `e2e/demo-smoke.env`, `e2e/scripts/run-e2e.mjs` um `--target demo` erweitert (Stack-Logik geteilt statt dupliziert). Eigener, nicht required CI-Workflow `.github/workflows/demo-smoke.yml`, läuft nächtlich/`workflow_dispatch`, nicht bei jedem PR — begründet mit ~80s zusätzlicher Seed-Laufzeit. Während der Verifikation wurde eine Konfigurationslücke gefunden und in `e2e/demo-smoke.env` behoben (`OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY=30`, da die Standard-Warteschlange bei 26 sequentiellen Uploads überlief) — reine Konfiguration, kein Produktivcode-Eingriff. Nachweis im PR: voller grüner Lauf, 3 Minuten 6 Sekunden Gesamtlaufzeit, 129 Dokumente über vier Bibliotheken indiziert, reguläre Suite unverändert grün (28/28).

**Verifikation:** `.github/workflows/demo-smoke.yml`, `e2e/demo-smoke/tests/demo-smoke.spec.ts` und `e2e/docker-compose.demo-smoke.yml` existieren im heutigen Code.

**Themen:** e2e, demo, ci, testinfrastruktur, seed

---

<a id="issue-233"></a>

## Issue #233 — test(e2e): E2E-Suite auf das gemeinsame e2e-Seed-Profil umstellen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:M, demo
- PRs: #726 (2026-08-21)

**Laut Issue:** Die bestehende E2E-Suite soll ihre Ausgangsdaten aus dem `e2e`-Datenprofil des gemeinsamen Seeds beziehen statt aus einer eigenen Testdatenbereitstellung (`e2e/fixtures/rss-feed/`, `e2e/fixtures/test-documents/`), damit es nur noch einen Weg gibt, eine Instanz zu befüllen. Ursprünglich als sechsteilige Suchprüfung gegen den Superhelden-Korpus formuliert, inhaltlich durch das Demo-Konzept ersetzt. Kein Szenario darf ersatzlos entfallen, Keycloak bleibt außen vor (dev-Auth), Laufzeit darf sich nicht spürbar verschlechtern.

**Geliefert:** Testdaten von `e2e/fixtures/rss-feed/` und `e2e/fixtures/test-documents/` nach `demo/seed/e2e-data/` verschoben (nicht dupliziert), referenziert vom `E2E_PROFILE` in `demo/seed/profiles.py`. `e2e/scripts/run-e2e.mjs` führt vor der Playwright-Suite `demo/seed/seed.py --profile e2e` aus; CI (`e2e.yml`) bekam einen Python-Setup-Schritt. Fünf Spec-Dateien auf neue Fixture-Pfade nachgezogen, keine fachliche Testaussage geändert. Nachweis: voller Suite-Lauf 27/27 grün, ~1,6 Minuten (unverändert), Seed-Lauf selbst < 5s. Nebenbefund: Der Seed deckte einen vorher unsichtbaren Farbkontrastfehler auf der Wissensbibliotheken-Seite auf (axe-core, `#778797` auf Weiß, 3,68:1 statt 4,5:1) — bewusst als eigenes Issue #725 ausgelagert statt im Rahmen dieses (laut Issue produktivcode-freien) Ombaus mitbehoben; im Test punktuell mit `disableRules: ['color-contrast']` auf genau dieses Szenario begrenzt ausgenommen.

**Verifikation:** `demo/seed/e2e-data/` existiert im heutigen Code, `e2e/fixtures/rss-feed/` und `e2e/fixtures/test-documents/` existieren nicht mehr (wie vorgesehen abgelöst). `demo/seed/profiles.py` vorhanden.

**Themen:** e2e, demo, seed, testinfrastruktur, barrierefreiheit

---

<a id="issue-234"></a>

## Issue #234 — feat(eval): Eval-Domäne Sehenswürdigkeiten in 200 europäischen Großstädten (mehrchunkig, deutsch)
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, size:L, evaluation, demo
- PRs: #730 (2026-08-22)

**Laut Issue:** Eine zweite, durchgängig deutschsprachige Eval-Domäne (Sehenswürdigkeiten in 200 europäischen Großstädten), deren Dokumente bewusst mehrere Chunks ergeben — der bestehende Comichelden-Korpus misst nur den Ein-Chunk-Idealfall, Chunk-Overlap-Parameter waren damit bisher unmessbar. Gefordert: zwei eingefrorene Wikidata-SPARQL-Abfragen, deterministischer Generator, 200 Dokumente je 6–12 KB mit mindestens 3 Chunks, mindestens 60 Golden-Fälle über fünf Kategorien (davon ≥15 boundary_span/cross_chunk), eigene Baseline, Comichelden-Baseline bleibt unverändert, Zeitbudget des CI-Jobs geprüft.

**Geliefert:** Städteauswahl im finalen PR auf GeoNames cities15000 + Wikidata-P1566-Brücke umgestellt (Abweichung von der ursprünglich vorgeschlagenen reinen Wikidata-SPARQL-Städteliste, im PR begründet). Generator `eval/generator/generate_city_landmarks_corpus.py`, Korpus `eval/corpus/city-landmarks/` mit 200 Dokumenten (tatsächliche Größe 5.534–34.103 Bytes, Median 11.809 Bytes — über der ursprünglichen 6–12-KB-Zielspanne, im PR erklärt: zusätzlicher Wikidata-sourcierter Vergleichstext nötig, um die Mehr-Chunk-Vorgabe auch bei sehenswürdigkeitenarmen Städten zu erreichen). Golden Dataset auf 83 Fälle erweitert (Vorgabe ≥60 übertroffen). Harness-Anbindung als eigene Klassen (`EvalDomainConfig.CITY_LANDMARKS`, `CityLandmarksRetrievalEvaluationHarnessTest`, `CityLandmarksBaselineRegressionTest`), eigener paralleler CI-Job. Baseline-Lauf: HitRate@5=0,952, MRR=0,881, nDCG@10=0,894, Recall@10=0,958 (83 Fälle) bzw. laut PR-Kopfnotiz nach zweiter Review-Runde mit 87 Fällen HitRate@5=0,989/MRR=0,939/nDCG@10=0,941/Recall@10=0,977. Comichelden-Baseline nachweislich unverändert (`git diff` leer). Bekannte, dokumentierte Einschränkungen: repetitiver Vergleichs-Boilerplate-Text als Chunk-Füller, Code-Switching auf englische Labels bei fehlender deutscher Wikidata-Übersetzung. Follow-up-Issue #734 (Ollama-Embedding-Parallelisierung, CI-Zeitbudget) aus dem Vorgang abgeleitet.

**Verifikation:** `eval/corpus/city-landmarks/` mit 202 Einträgen (200 Dokumente + Manifest/Source), `eval/golden/city-landmarks.json`, `eval/baseline/city-landmarks.json` und `backend/src/evalTest/java/io/opaa/eval/CityLandmarksRetrievalEvaluationHarnessTest.java` existieren im heutigen Code.

**Themen:** evaluation, retrieval, chunking, eval-korpus, wikidata, ci

---

<a id="issue-235"></a>

## Issue #235 — feat(demo): Demo-Domänen in getrennte Wissensbibliotheken legen (blockiert)
- Geschlossen: 2026-08-22 (not planned)
- Labels: enhancement, size:M, demo
- PRs: keine

**Laut Issue:** Die vier Demo-Domänen (ursprünglich der Superhelden-Eval-Korpus) sollten in getrennte Wissensbibliotheken gelegt und je einem Space zugeordnet werden, um die rechtebewusste Trennung von Wissensbeständen vorzuführen — inklusive eines Demo-Nutzers ohne Grant auf eine der Bibliotheken, um zu zeigen, dass Space-Mitgliedschaft allein keinen Asset-Zugriff gewährt. Blockiert durch #207, #229 und #234.

**Geliefert:** Nicht umgesetzt — als „not planned" geschlossen. Laut Abschlusskommentar des Maintainers (2026-08-22) ist der Zweck des Vorgangs bereits durch das eigenständige Rheinfurt-Demo-Konzept (Epic #708) erreicht: Die Demo besteht dort aus fünf getrennten Wissensbibliotheken mit je eigener Quellkonfiguration und eigenen VIEWER-Rechten. Der ursprünglich adressierte Superhelden-Korpus bleibt reines Eval-Artefakt in einem gemeinsamen Index, getrennt nur über Dateinamen-Präfix — dafür ist keine Bibliothekstrennung nötig. Das Issue ist damit durch eine parallel entstandene, umfassendere Lösung überholt worden, nicht aus inhaltlichen Bedenken verworfen. Wiedereröffnung vorgesehen, falls die Eval-Seite später doch getrennte Bibliotheken braucht.

**Verifikation:** Entfällt (kein Code geliefert). Die im Abschlusskommentar genannte Ersatzlösung (fünf Bibliotheken im Rheinfurt-Demo-Konzept) ist Gegenstand der Epic-#708-Issues (u. a. #232, #233) und dort verifiziert.

**Themen:** demo, spaces, wissensbibliotheken, rechte, not-planned

---

<a id="issue-237"></a>

## Issue #237 — Verzeichnissynchronisation als Rechteereignis behandeln
- Geschlossen: 2026-08-03 (completed)
- Labels: enhancement, backend, size:L, security, auth
- PRs: #297 (2026-08-03)

**Laut Issue:** Ein Synchronisationslauf gegen den Verzeichnisdienst, der Gruppenmitgliedschaften entfernt, ist ein Massen-Rechteentzug ohne menschlichen Entscheidungspunkt und muss entsprechend abgesichert werden. Gefordert: Abgleich über stabile Kennung (`objectGUID`/SCIM-`externalId`), nie über den Namen; Plausibilitätsschwelle mit Abbruch bei zu vielen Änderungen; Trockenlauf mit Differenzbericht; bei nicht erreichbarem Verzeichnis `last-known-good` ohne Rechteentzug; eine Protokollzeile je bewirkter Rechteänderung; Umbenennung folgenlos, bei Zusammenlegung/Neuschnitt Gruppen als aufgelöst markieren mit eingefrorenen, nicht erweiterbaren Grants; Reorganisationsbericht für den System-Admin.

**Geliefert:** `io.opaa.group.sync`-Paket mit `DirectorySyncService` (Abgleich strikt über `Group.externalId`), getrennt in `buildPlan` (lesend) und `applyPlan` (schreibend, nur bei plausiblem Lauf). Plausibilitätsschwelle `opaa.directory-sync.change-threshold-fraction` (Default 30 %), bezogen ausschließlich auf Entzüge, nicht auf Zuwächse — im PR bewusst begründete Einschränkung gegenüber einer allgemeinen Änderungsschwelle. Leere Gruppenliste führt zu hartem Abbruch (`ABORTED_EMPTY_RESULT`), unabhängig von der Schwelle. Nicht erreichbares Verzeichnis (`DirectoryUnavailableException`) führt zu `UNREACHABLE`-Status, dauerhaft in `directory_sync_status` (Liquibase-Changelog 011) protokolliert. Trockenlauf-Endpunkt `POST /api/v1/admin/directory-sync/dry-run` nutzt denselben Plan-Code, verlässt ihn vor jedem Schreibzugriff. Aufgelöste Gruppen (`dissolved`) behalten eingefrorene Mitgliedschaften, keine Erweiterung, automatische Reaktivierung bei Wiederauftauchen. `DirectoryClient` als einzige Nahtstelle zu einem realen Verzeichnisdienst; produktiv liegt nur `NoOpDirectoryClient` bei (meldet konsequent „nicht erreichbar"), ein echter LDAP/SCIM-Client ist explizit außerhalb des Umfangs und als spätere `@ConditionalOnMissingBean`-Implementierung vorgesehen — das ist eine wesentliche Abgrenzung: Die eigentliche Netzwerkanbindung an ein Verzeichnis existiert nach diesem PR nicht, nur die Rechte-Logik darüber. Verschachtelte Gruppen bewusst nicht aufgelöst, `parentExternalId` nur für spätere Curator-Eskalation vorgehalten.

**Verifikation:** `backend/src/main/java/io/opaa/group/sync/DirectorySyncService.java`, `NoOpDirectoryClient.java` und `backend/src/main/resources/db/changelog/changes/011-directory-sync.yaml` existieren im heutigen Code.

**Themen:** auth, rechte, verzeichnisdienst, gruppen, security, backend

---

<a id="issue-238"></a>

## Issue #238 — Historisierung von Rechten und Gruppenmitgliedschaften
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #427 (2026-08-17)

**Laut Issue:** Die Rechtemenge eines Nutzers ist berechnet und ändert sich u. a. per Verzeichnissynchronisation. Gefordert war die Historisierung von Grants und Gruppenmitgliedschaften (gültig von/bis, auslösender Vorgang), die Rekonstruktion der Rechtemenge zu einem Stichtag und die belegbare Beantwortung der Negativfrage ("hatte X am Datum Y KEINEN Zugriff"). Ausdrücklich nicht gefordert: Mitschreiben der vollständigen Rechtemenge je Abfrage (Datensparsamkeit). Ein Bericht "abgelehnte Zugriffe" sollte aus der Spezifikation entfernt werden.

**Geliefert:** Drei neue Historientabellen (Migration `018-permission-history.yaml`): `asset_grant_history`, `group_membership_history`, `library_visibility_history`, jeweils als halboffene Intervalle mit Ursache-Enum. `PermissionHistoryService` schreibt diese Intervalle und rekonstruiert die Rechtemenge über `readableLibraryIdsAsOf(userId, organizationId, Instant)`. Backfill des kompletten Altbestands als eigenes changeSet. `QueryService` gleicht den je Abfrage angewandten Suchbereich automatisiert gegen die Rechtehistorie ab und protokolliert nur bei Abweichung — keine dauerhafte Protokollzeile der vollständigen Rechtemenge. Löschschicksal der Historie als eigene Architekturentscheidung dokumentiert (ADR-0016): Fachobjekt-Spalten ohne FK (Historie überlebt Bibliotheks-/Gruppenlöschung), Subjektspalten `RESTRICT`, `actor_user_id` `SET NULL`. Kein neuer REST-Endpunkt für die Stichtag-Abfrage — bewusste Abgrenzung, da die Abnahmekriterien nur Rekonstruierbarkeit verlangten, keine API. Im Review wurden mehrere Bugs behoben (Flush-Reihenfolge, fehlender Backfill, nicht historisierte `ensurePersonalLibrary`-Provisionierung, ursprünglich `CASCADE` statt lösch-überlebender Historie).

**Verifikation:** `backend/src/main/java/io/opaa/library/PermissionHistoryService.java`, die Migration `018-permission-history.yaml` und `docs/decisions/0016-loeschschicksal-rechtehistorie.md` existieren im heutigen Code unverändert vom PR-Umfang.

**Themen:** auth, security, spaces, retrieval, doku

---

<a id="issue-239"></a>

## Issue #239 — Audit-Governance: kein personenbezogener Auswertungspfad
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: keine (Kern über die separate Audit-Serie #391–#395 geliefert)

**Laut Issue:** Das Audit-Log erhebt personenbeziehbare Verhaltensdaten unabhängig von einer abschaltbaren Nutzungsstatistik. Gefordert: kein personenbezogener Auswertungspfad (außer Selbstauskunft und anlassbezogener Klärung im Vier-Augen-Prinzip), Netzadresse nicht im Standardprotokollsatz, Aufbewahrung mit Ober-/Untergrenze und automatischer Löschung, Pseudonymisierung ab Schreibzeitpunkt mit getrennter Zuordnungstabelle, technisch getrennte Auswertungswege für Revision und Dienststellenleitung, jeder Audit-Zugriff selbst protokolliert, SIEM-Export denselben Regeln unterworfen, Auskunftsexport.

**Geliefert:** Der Kern vollständig über die Audit-Serie #391–#395 (alle gemergt): Kein personenbezogener Auswertungspfad (#393: vier Abfragewege mit Pflicht-Zeitraum, `actor_ref` nur Ausgabefeld, keine Freitextsuche, kein Vollabzug, 92-Tage-Höchstbreite, Seitendeckel; strukturell abgesichert durch package-privates Repository und Funnel-Test in #394). Anlassbezogene Klärung im Vier-Augen-Prinzip mit Befristung (#393: Selbstfreigabe per DB-Constraint abgewiesen, 30-Tage-Befristung). Keine Netzadresse im Standardprotokollsatz (#391). Aufbewahrung mit Ober-/Untergrenze 12–120 Monate, Default 36, partitionsweise Löschung über SECURITY-DEFINER-Funktion (#395). Pseudonymisierung ab Schreibzeitpunkt mit getrennter Zuordnungstabelle; Kontolöschung entfernt Zuordnung ohne Protokolländerung (#391, per Migrationstest in #395 nachgewiesen). Jeder Audit-Zugriff erzeugt selbst einen Eintrag, auch abgewiesene Versuche (#394). Technisch getrennte Auswertungswege über eine exklusive AUDITOR-Rolle (#393). **Herausgelöst statt geliefert:** Selbstauskunft und Auskunftsexport gehen als neues Sub-Issue #798 (nicht Teil dieses Chunks) — dieses Issue wurde also mit einem bewusst abgeschnittenen Rest geschlossen. Die Warnung "Audit-Frist kürzer als Inhaltsaufbewahrung" ist nur mechanisch vorbereitet (`ContentRetentionProvider`, siehe #216) und wird erst mit Inhaltsaufbewahrung wirksam. Betriebs-Follow-ups (Härtung, Flutschutz etc.) laufen als eigene Issues (#426, #447, #451, #452, #455).

**Verifikation:** `backend/src/main/java/io/opaa/audit/` enthält u. a. `AuditActorPseudonymService.java`, `AuditIncidentScopeService.java`, `AuditQueryService.java`, `AuditRetentionDeletionService.java`, `AuditRetentionScheduler.java` — bestätigt Pseudonymisierung, Vier-Augen-Klärung (Incident Scope), Abfragewege und automatische Löschung wie im Kommentar beschrieben.

**Themen:** audit, security, dsgvo, governance

---

<a id="issue-240"></a>

## Issue #240 — Nachfolge statt Sperre: Assets ausgeschiedener Eigentümer
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M, auth
- PRs: keine

**Laut Issue:** Ein Konto muss immer sofort deaktivierbar sein, auch wenn es Assets besitzt. Dessen Assets gehen in den Zustand "Nachfolge offen": nutzbar, Rechte unverändert, aber Reichweite eingefroren (keine neuen Grants, keine höhere Freigabestufe). Benannter Adressat und Frist, mit Eskalation nach oben.

**Geliefert:** Nicht umgesetzt. Aktualisierung (23.08.2026): Die im Issue vorgesehene Zuständigkeit "Kurator der Organisationseinheit" existiert seit #330 nicht mehr (Kuratorenrollen gestrichen); zuständig wäre stattdessen der System-Admin über seine Governance-Arbeitsliste, wie es andere Bausteine (#204, #209) bereits vorsehen — die Abhängigkeit "Kuratorenrollen an Organisationseinheiten" entfällt damit ersatzlos. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** Nicht separat geprüft.

**Themen:** auth, spaces, rechteverwaltung, governance

---

<a id="issue-241"></a>

## Issue #241 — Befristung und Rezertifizierung von Einzelgrants
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M, security
- PRs: keine

**Laut Issue:** Die Freigabekette erzeugt Einzelgrants an Personen, die über Jahre niemand zurücknimmt. Gefordert: optionale Befristung je Grant mit automatischem Verfall, ein Rezertifizierungslauf des Eigentümers, Anzeige ungenutzter Grants, und Rücknahme einzelner Grants ohne die Agentenfreigabe insgesamt zu widerrufen.

**Geliefert:** Teilweise als Datenmodell. Laut Statuskommentar zu Epic #198 (23.08.2026) existiert bereits `AssetGrant.expiresAt` mit Verfallswirkung als Datenmodell-Teilstück. Der eigentliche Kern — Rezertifizierungslauf, Anzeige ungenutzter Grants, gezielte Rücknahme ohne Gesamtwiderruf — ist laut demselben Kommentar ("Rest: Rezertifizierung") nicht geliefert. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** Nicht separat geprüft (kein Grep auf `AssetGrant`/`expiresAt` durchgeführt); Aussage stützt sich auf den Epic-Statuskommentar.

**Themen:** rechteverwaltung, security, agenten

---

<a id="issue-242"></a>

## Issue #242 — Konsistenzprüflauf zwischen Vektorspeicher und Datenbank
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** `library_id` liegt als Kopie am Chunk im Vektorspeicher; nach einer Datenbanksicherung können Chunks mit inzwischen anders berechtigten oder gelöschten Bibliotheks-Kennungen existieren. Gefordert: Festlegung der führenden Quelle (relationale Datenbank), ein Konsistenzprüflauf (verwaiste Chunks, Dokumente ohne Chunks, abweichende Bibliotheks-Kennung), Bericht und Reparaturweg.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme, ohne inhaltlichen Kommentar über das Standardmuster hinaus.

**Verifikation:** Nicht separat geprüft.

**Themen:** retrieval, pgvector, wissensbibliotheken

---

<a id="issue-243"></a>

## Issue #243 — Driftschutz für Abkömmlinge: Fristen und automatische Deaktivierung
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Der teure, bewusst zurückgestellte Teil des Abkömmlinge-Bausteins (#213): konfigurierbare Frist, Eskalationsstufe und Vorschauliste vor automatischer Deaktivierung, dokumentierter Weg zur Rücknahme, Prüfaufforderung über die gesamte Abkömmlingskette, Benachrichtigung an die Einheit statt an eine Person.

**Geliefert:** Nicht umgesetzt — laut eigenem Text im Issue ausdrücklich erst zu bauen, "wenn aus der Nutzung belegt ist, dass Abkömmlinge in relevanter Zahl entstehen". Da #213 (Abkömmlinge, Grundlage) selbst nicht umgesetzt ist, kann diese Erweiterung denkgesetzlich nicht vorliegen. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** Nicht separat geprüft — logische Konsequenz aus der Nichtumsetzung von #213.

**Themen:** agenten, spaces

---

<a id="issue-244"></a>

## Issue #244 — docs: bestehende öffentliche Instanz opaa.ewerlin.com in der Deployment-Dokumentation beschreiben
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, size:S, demo
- PRs: #247 (2026-08-02)

**Laut Issue:** Die bereits betriebene öffentliche Instanz `opaa.ewerlin.com` war weder in `docs/deployment.md` noch in `docker-compose.yml` erwähnt. Gefordert: Abschnitt mit URL, Zweck, Betreiber, Zugriff, Abweichungen von der Standardkonfiguration (insbesondere Chat-/Embedding-Anbieter, da Anwendungs-Default `ollama`/`phi3:mini` ist, die Instanz aber vermutlich `openai` nutzt), Aktualisierungsablauf, erlaubte/verbotene Daten, sowie ein Verweis aus `README.md`.

**Geliefert:** Abschnitt in `docs/deployment.md` mit Betreiber (Maintainer), Zweck (öffentliche Test-/Demo-Instanz, kein Produktivbetrieb), Zugriff (`OPAA_AUTH_MODE=oidc` hinter Keycloak, bewusst account-gebunden, kein Gastzugang), Aktualisierungsablauf (Verweis auf `.github/workflows/publish-images.yml`, automatischer Image-Pull bei Push auf `main`), Datenverbot (keine personenbezogenen/vertraulichen/produktiven Daten), Verweis aus `README.md`. Abweichung vom Issue: Die tatsächliche LLM-/Embedding-Konfiguration der Instanz, Rate-Limits, Bind-Adresse und Ports sowie der genaue Update-Mechanismus auf dem VPS blieben unbekannt und wurden explizit als "nicht dokumentiert" ausgewiesen statt vermutet — der PR-Autor holte dazu Rücksprache mit dem Maintainer ein (Ergebnis: Zugang bleibt bewusst hinter Keycloak, kein anonymer Zugang für die spätere Demo-Korpus-Ausrollung #230).

**Verifikation:** `docs/deployment.md` enthält heute (Zeile ~32) den Abschnitt zur Instanz, `README.md` verweist (Zeile ~67) darauf. Der Stand ist seither deutlich ausgebaut worden (u. a. konkrete Modellkonfiguration, Rheinfurt-Demo-Rollout #230/#712) — die im PR offen gelassenen Punkte wurden also in Folge-Issues nachgezogen.

**Themen:** deployment, doku, demo

---

<a id="issue-245"></a>

## Issue #245 — fix(ci): CLA-Workflow schlägt bei Kommentaren auf Issues fehl
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, ci
- PRs: #246 (2026-08-02)

**Laut Issue:** Der CLA-Workflow lief bei jedem `issue_comment`-Event, auch auf gewöhnlichen Issues (nicht nur PRs). Die CLA-Action erwartet dort einen Pull Request und brach mit einem GraphQL-Fehler ab — betroffen waren sieben fehlgeschlagene Läufe auf den Issue-Kommentaren zu #239, #241, #242, #243. Gefordert: Bedingung ergänzen, sodass der Job bei `issue_comment` nur läuft, wenn der Kommentar zu einem PR gehört; `pull_request_target` unverändert lassen.

**Geliefert:** Genau wie gefordert — Bedingung `github.event_name == 'pull_request_target' || github.event.issue.pull_request` am Job in `.github/workflows/cla.yml` ergänzt. Keine Abweichung vom Issue-Umfang.

**Verifikation:** `.github/workflows/cla.yml` enthält die Bedingung `if: github.event_name == 'pull_request_target' || github.event.issue.pull_request` unverändert im heutigen Stand.

**Themen:** ci, agenten-organisation

---

<a id="issue-248"></a>

## Issue #248 — feat(ci): Täglichen Projektreport als GitHub-Pages-Seite mit Atom-Feed veröffentlichen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, enhancement, size:M, ci
- PRs: #259 (2026-08-02)

**Laut Issue:** Ein täglich laufender Workflow soll abgeschlossene/neue Issues, gemergte/offene PRs und CI-Status des Hauptbranchs zusammentragen, mit einer modellgenerierten Fließtext-Zusammenfassung (optional, kein Ausfall bei fehlendem API-Key) als HTML-Seite auf GitHub Pages veröffentlichen, dazu ein Atom-Feed. Zeitgesteuert und manuell mit wählbarem Datum startbar. Kein SMTP-Versand, keine Wochen-/Monatsberichte. Tage ohne Aktivität sollen keinen Report erzeugen.

**Geliefert:** `.github/workflows/daily-report.yml` (Zeitsteuerung, Veröffentlichung im Branch `gh-pages`), `.github/scripts/daily_report.py` (Datenerhebung über GitHub-CLI, Zusammenfassung, Seiten-/Feed-Erzeugung), `docs/tagesreport.md` (Bedienung). Rohdaten liegen als JSON in `gh-pages`, Übersichtsseite und Feed werden bei jedem Lauf neu daraus erzeugt. Obergrenze von 25 Einträgen je Abschnitt im Prompt für die Zusammenfassung. Das Secret `OPAA_OPENAI_API_KEY` war zum Merge-Zeitpunkt bewusst noch nicht gesetzt — Reports liefen zunächst ohne Fließtext-Zusammenfassung. Nach dem Merge war ein einmaliger manueller Schritt nötig (GitHub Pages auf `gh-pages` stellen), der nicht Teil des PRs war, sondern als Anleitung im PR-Body stand.

**Verifikation:** `.github/scripts/daily_report.py`, `.github/workflows/daily-report.yml` und `docs/tagesreport.md` existieren im heutigen Code. Ob GitHub Pages tatsächlich aktiv geschaltet wurde und der Feed seither läuft, wurde nicht geprüft (außerhalb des Repository-Inhalts).

**Themen:** ci, doku, agenten-organisation

---

<a id="issue-250"></a>

## Issue #250 — docs(security): Härtungsanforderungen für erreichbare Compose-Deployments dokumentieren
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:M, security
- PRs: #714 (2026-08-21)

**Laut Issue:** Der mitgelieferte Compose-Stack ist für Entwicklung gebaut, wird aber laut Dokumentation nachgebaut und erreichbar betrieben. Vier belegte Vorgabewerte sollten mit Fundstelle, Risiko und Gegenmaßnahme dokumentiert werden: vorkonfigurierter Realm-Benutzer `testuser`/`testpass`, `sslRequired: none`, Keycloak-Bootstrap-Admin `admin`/`admin`, veröffentlichter PostgreSQL-Host-Port. Zusätzlich: Ersetzen von DB-Zugangsdaten und `OPAA_AUTH_BASIC_SECRET`, Hinweis dass `mock`-Auth nie erreichbar betrieben wird, Querverweis aus `docker-compose.yml`, Prüfung ob eine separate `docker-compose.prod.yml` sinnvoller wäre.

**Geliefert:** Abschnitt "Härtung für erreichbare Deployments" in `docs/deployment.md` mit den vier Punkten, je mit Fundstelle/Risiko/Gegenmaßnahme und Kennzeichnung "zwingend" vs. "empfohlen". Warnhinweis in `docker-compose.yml` verweist jetzt auf den Abschnitt. Abweichung vom Issue: `OPAA_AUTH_BASIC_SECRET` existiert im Repository nicht mehr (mit Commit `fd04246`, PR #328/#255 entfernt) — dokumentiert ist stattdessen, dass OPAA kein eigenes JWT-Signier-Geheimnis mehr hat und der einzige ungeprüfte Auth-Weg das Spring-Profil `dev` ist. Empfehlung zur offenen Frage (separate Compose-Datei vs. Textliste): Textliste, mit Begründung im Dokument. Der Punkt "Hinweis, dass `mock` nie erreichbar betrieben wird" ist gegenstandslos geworden, da der `mock`-Modus selbst mit #255/PR #328 entfernt wurde.

**Verifikation:** `docs/deployment.md` enthält den Abschnitt "Härtung für erreichbare Deployments" (Zeile ~196), `docker-compose.yml` verweist im Warnkopf darauf. Der Abschnitt ist seither erheblich gewachsen (u. a. um die Rheinfurt-Demo-Konten und den `opaa-seed`-Client aus #712) — die ursprünglichen vier Punkte sind weiterhin enthalten, ergänzt um neue.

**Themen:** security, deployment, doku

---

<a id="issue-252"></a>

## Issue #252 — docs: Standardwerte in docs/deployment.md gegen application.yml abgleichen
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:S
- PRs: #715 (2026-08-21)

**Laut Issue:** Die Spalte "Standard" in der Umgebungsvariablen-Tabelle vermischte zwei verschiedene Ebenen: den Anwendungs-Default aus `application.yml` (gilt ohne gesetzte Variable) und die Compose-Belegung aus `.env.example` (gilt real im Compose-Stack) — konkret widersprüchlich bei `OPAA_AI_CHAT_PROVIDER`/`OPAA_AI_EMBEDDING_PROVIDER`. Gefordert: zwei Spalten, alle Zeilen gegen beide Quellen verifiziert, Wirkungsbedingung anbieterspezifischer Variablen kenntlich gemacht, Vorrangregel der Konfigurationsquellen genannt.

**Geliefert:** Zweispaltige Tabelle ("Anwendungs-Default" / "Compose-Belegung"), alle Zeilen verifiziert und mehrere Abweichungen korrigiert (`OPAA_SERVER_ADDRESS`, `OPAA_OIDC_JWK_SET_URI`, `OPAA_OPENAI_API_KEY`-Platzhalter, `OPAA_DB_URL`-Query-Parameter, profilabhängiger `OPAA_OLLAMA_BASE_URL`), fehlende Zeilen ergänzt, reine Compose-/nginx-Variablen ohne Spring-Property als solche gekennzeichnet, Absatz zur Vorrangregel (Umgebungsvariable > `.env.docker` > `application.yml`) ergänzt. Klarstellung im PR: Die im Issue konkret genannte Diskrepanz bei den Provider-Variablen war zum PR-Zeitpunkt bereits anderweitig behoben (beide bereits `ollama`) — die strukturelle Zwei-Spalten-Unterscheidung fehlte aber trotzdem noch und war der eigentliche Gegenstand des PRs.

**Verifikation:** `docs/deployment.md` enthält heute die Spalten "Anwendungs-Default (`application.yml`)" / "Compose-Belegung (`.env.docker.example`)" (Zeile ~402) sowie die Vorrangregel-Erläuterung (Zeile ~359 ff.). Die Referenzdatei heißt inzwischen `.env.docker.example` statt `.env.example` (Folge von #716, split in zwei Vorlagen) — inhaltlich deckt sich das mit dem gelieferten Konzept.

**Themen:** deployment, doku

---

<a id="issue-255"></a>

## Issue #255 — fix(auth): mock-Modus funktionsfähig machen oder aus Default und Doku entfernen
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:M, auth
- PRs: #328 (2026-08-14)

**Laut Issue:** Der Auth-Modus `mock` war Default und dokumentiert, funktionierte im Backend aber nicht: Es gab nur profilgebundene `SecurityFilterChain`-Beans für `basic` und `oidc`; ohne eines dieser Profile blockte Spring Boots generische Security-Autokonfiguration alle Anfragen (statt sie freizugeben, wie `mock` versprach), und die Fach-Controller existierten mangels passendem Profil gar nicht als Beans. Vorgeschlagen wurden zwei Optionen: `mock` funktionsfähig machen, oder ihn aus Default und Doku entfernen.

**Geliefert:** Deutlich weiter gefasst als beide Issue-Optionen — PR #328 setzt eine zwischenzeitliche Entscheidung (#323) um und reduziert die Auth-Modi grundsätzlich auf `oidc` und `dev`. `mock` **und** `basic` entfallen ersatzlos (nicht nur `mock`, wie im Issue erwogen); `basic` wurde zusätzlich wegen inhaltlicher Mängel verworfen (Identitätswechsel bei Umstieg auf `oidc`, HMAC-Secret in der Betriebskonfiguration, kein konstanter Passwortvergleich, kein Rate-Limiting, nur ein konfigurierbarer Nutzer). An die Stelle von `mock` tritt `DevSecurityConfig`/`DevAuthFilter`: ein synthetisches JWT für einen konfigurierten Nutzer (`dev-admin`, `dev-user`), Auswahl per Header/Query-Parameter, 401 bei unbekanntem Nutzer statt stillem Rückfall. `AuthProfileGuard` bricht den Start ohne aktives Auth-Profil hart ab. `POST /api/v1/auth/login` und das Anmeldeformular entfallen vollständig — es gibt keinen passwortbasierten Anmeldeweg mehr. PR schließt zugleich #323 und #260 und macht #138 (Rate-Limiting am Login) gegenstandslos.

**Verifikation:** `backend/src/main/java/io/opaa/auth/DevSecurityConfig.java`, `DevAuthFilter.java` und `AuthProfileGuard.java` existieren im heutigen Code; `BasicSecurityConfig.java` existiert nicht mehr — Entfernung bestätigt, konsistent mit dem PR-Umfang.

**Themen:** auth, security, backend, e2e

---

<a id="issue-256"></a>

## Issue #256 — test(e2e): Lokale Modellbereitstellung für den E2E-Stack
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:M, ci
- PRs: #690 (2026-08-21)

**Laut Issue:** Der E2E-Stack zeigte auf `OPAA_AI_CHAT_PROVIDER=openai` mit einem Platzhalter-API-Key gegen die echte OpenAI-Base-URL — tragfähig nur für den reinen Rauchtest ohne KI-Aufruf, nicht für Szenarien mit echter Indizierung/Suche (#232, #233), die Determinismus und Unabhängigkeit von einem kostenpflichtigen externen Dienst verlangen. Gefordert: Bewertung Ollama vs. leichtgewichtiger OpenAI-kompatibler Stub, Integration in den Compose-Stack, Anpassung von `e2e/e2e.env`, Dokumentation der Entscheidung als Ergänzung zu ADR-0009.

**Geliefert:** Nur der Dokumentationsteil — die eigentliche technische Umsetzung (Stub-Server `e2e/ai-stub/server.mjs`, Einbindung in `e2e/docker-compose.e2e.yml`, `OPAA_OPENAI_BASE_URL=http://ai-stub:8089` in `e2e/e2e.env`) war laut PR-Beschreibung zum Zeitpunkt dieses PRs bereits andernorts umgesetzt und in `e2e/README.md` beschrieben. PR #690 ergänzt lediglich ADR-0009 um einen Nachtrag zu Punkt 4 ("Modelle lokal im Stack statt externer Anbieter"), der die Entscheidung "eigener minimaler OpenAI-kompatibler Stub statt Ollama" nachträglich mit Begründung festhält. Einzige geänderte Datei ist die ADR selbst.

**Verifikation:** `e2e/ai-stub/server.mjs` existiert im heutigen Code. `docs/decisions/0009-e2e-teststrategie.md` enthält den Nachtrag. Die eigentliche Implementierung des Stubs lässt sich diesem Issue/PR nicht zuordnen — sie kam über einen anderen, hier nicht referenzierten PR.

**Themen:** e2e, ci, doku

---

<a id="issue-257"></a>

## Issue #257 — docs: Einheitliche Testkonto-Konvention dokumentieren
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:S, security
- PRs: #689 (2026-08-21)

**Laut Issue:** Im Repository existierten mehrere, nicht abgestimmte Testkonto-Muster (Keycloak-Realm-Export, `.env.example`-Basic-Auth-Werte, E2E-Suite-Zugangsdaten), ohne zentrale Dokumentation, welches Konto wofür gilt. Gefordert war eine zentrale Übersicht, die Geltungsbereiche klärt und begründet, warum (nicht) vereinheitlicht wird.

**Geliefert:** Neuer Abschnitt „Testkonten im Überblick" in `docs/deployment.md`, verlinkt von `.env.example` und `e2e/README.md`. Der PR deckte bei der Bestandsaufnahme zwei Abweichungen vom Issue-Text auf: Ein eigenständiges `OPAA_AUTH_BASIC_USERNAME`/`_PASSWORD`-Paar existierte im Code zum Zeitpunkt des PRs bereits nicht mehr (der Mechanismus, den das Issue beschrieb, war zwischenzeitlich durch andere Arbeit überholt), und die E2E-Suite nutzt keine eigenen `e2e-user`/`e2e-password`-Zugangsdaten mehr, sondern die `dev`-Profil-Nutzer `dev-admin`/`dev-user`/`dev-outsider`. Der im Issue vorgeschlagene Verlinkungspunkt „Kommentar in `keycloak/realm-export.json`" entfiel bewusst (JSON kennt keine Kommentare); der Keycloak-Nutzer ist stattdessen direkt in der neuen Tabelle geführt. Eine Nachbesserung nach Review präzisierte zusätzlich den Umgang mit einer möglichen alten, gitignorten lokalen `.env.docker`, die noch alte Basic-Auth-Variablen enthalten könnte.

**Verifikation:** `docs/deployment.md` enthält den Abschnitt „Testkonten im Überblick" (Zeile 685 im aktuellen Worktree-Stand). `.env.example` enthält keine `OPAA_AUTH_BASIC_SECRET`/`OPAA_AUTH_MODE`-Variablen mehr — konsistent mit der im PR beschriebenen Beobachtung, dass dieser Mechanismus bereits entfallen war (siehe #260/#328).

**Themen:** doku, auth, testing, e2e

---

<a id="issue-258"></a>

## Issue #258 — docs: Beispiel-Secret für OPAA_AUTH_BASIC_SECRET verhindert Backend-Start
- Geschlossen: 2026-08-14 (not planned)
- Labels: bug, documentation, size:S
- PRs: keine

**Laut Issue:** `.env.example` und `docs/deployment.md` empfahlen als Beispielwert für `OPAA_AUTH_BASIC_SECRET` exakt den String, den `BasicSecurityConfig.validateBasicAuthConfiguration()` beim Start des `basic`-Profils hart als `INSECURE_DEFAULT_SECRET` ablehnte. Wer die Dokumentation kopierte und das `basic`-Profil aktivierte, bekam einen nicht startenden Container, ohne dass die Doku das erwähnte.

**Geliefert:** Nichts direkt zu diesem Issue — als „not planned" ohne verknüpften PR geschlossen. Laut Maintainer-Kommentar ist das Issue durch PR #328 (Issue #260, „Auth-Modi auf oidc und dev reduzieren") hinfällig geworden: Der `basic`-Modus wurde dort ersatzlos entfernt, samt `BasicSecurityConfig` und der darin hinterlegten Ablehnung des Beispielwerts. `OPAA_AUTH_BASIC_SECRET` existiert seitdem nicht mehr, die im Issue beschriebene Falle ist damit strukturell beseitigt, nicht durch eine gezielte Doku-Korrektur.

**Verifikation:** `backend/src/main/java/io/opaa/auth/` enthält keine `BasicSecurityConfig.java` mehr; `.env.example` enthält keine `OPAA_AUTH_BASIC_SECRET`- oder `OPAA_AUTH_MODE`-Variablen. Bestätigt: Der Fehlerpfad aus dem Issue kann heute nicht mehr auftreten, weil der ganze `basic`-Modus weg ist.

**Themen:** auth, doku, projektsetup

---

<a id="issue-260"></a>

## Issue #260 — feat(auth): mehrere opaa.auth.basic.users-Einträge konfigurierbar machen
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: #328 (2026-08-14)

**Laut Issue:** Das `basic`-Auth-Profil unterstützte nur genau einen konfigurierten Nutzer (`opaa.auth.basic.users` mit einem Eintrag aus `OPAA_AUTH_BASIC_USERNAME`/`_PASSWORD`). Gefordert war, mehrere Nutzer konfigurierbar zu machen, damit z. B. Szenario 5 aus #232 (Ablehnung eines nicht-privilegierten Nutzers) mit einem zweiten Testnutzer geprüft werden kann.

**Geliefert:** Deutliche Abweichung vom Issue-Umfang, im PR selbst offen benannt: Statt `opaa.auth.basic.users` um mehrere Einträge zu erweitern, wurde der gesamte `basic`-Auth-Modus ersatzlos entfernt (PR #328, „Auth-Modi auf oidc und dev reduzieren", setzt die Grundsatzentscheidung aus #323 um). An seine Stelle tritt ein `dev`-Modus mit `DevSecurityConfig`/`DevAuthFilter`, der zwei vorkonfigurierte Nutzer (`dev-admin`, `dev-user`) über den Header `X-OPAA-Dev-User` bzw. den Query-Parameter `?devUser=` auswählbar macht. Damit ist das eigentliche Bedürfnis (mehrere Testnutzer mit unterschiedlichen Rollen) erfüllt, aber nicht durch die im Issue skizzierte Lösung — der PR macht #260 laut eigener Beschreibung „gegenstandslos" und schließt es trotzdem als erledigt, weil das dahinterliegende Ziel erreicht ist. Der PR schließt zugleich #323 und #255 mit.

**Verifikation:** `backend/src/main/java/io/opaa/auth/DevSecurityConfig.java` und `DevAuthFilter.java` existieren im aktuellen Stand; `BasicSecurityConfig.java` und `AuthProperties.BasicAuth`/`BasicUser` existieren nicht mehr. `application.yml` enthält keinen `opaa.auth.basic.users`-Block mehr.

**Themen:** auth, backend, e2e, testing

---

<a id="issue-261"></a>

## Issue #261 — fix(ci): Tagesreport landet beim ersten Lauf auf falschem Branch
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, ci
- PRs: #262 (2026-08-02)

**Laut Issue:** Der erste Lauf des Report-Workflows (`.github/workflows/daily-report.yml`) erzeugte den Tagesreport korrekt, konnte ihn aber nicht veröffentlichen (`error: src refspec gh-pages does not match any`). Ursache: Der `continue-on-error`-Checkout-Schritt legte beim ersten Lauf (ohne existierenden `gh-pages`-Branch) bereits ein Git-Repository an, wodurch die nachfolgende `if [ ! -d .git ]`-Prüfung die Initialisierung mit `git init -b gh-pages` überspringt und HEAD auf dem Standardbranch (`master`) verbleibt.

**Geliefert:** Der Zielbranch wird jetzt unabhängig davon gesetzt, ob das Repository vom Checkout oder vom Skript angelegt wurde — via `git symbolic-ref`, das laut PR-Beschreibung auch ohne ersten Commit funktioniert. Die PR-Beschreibung dokumentiert die Prüfung von vier Zuständen (frisches Repo ohne Commit, Folgelauf mit Historie, Lauf ohne Änderungen, HEAD fälschlich auf `master`) statt eines automatisierten Tests, da es sich um reine Workflow-Logik handelt. Deckt sich mit den drei Abnahmekriterien des Issues.

**Verifikation:** `.github/workflows/daily-report.yml` enthält aktuell den Kommentar „Existiert gh-pages noch nicht, scheitert der vorangehende Checkout am …" sowie die Zeile `if [ "$(git symbolic-ref --short HEAD 2>/dev/null)" != "gh-pages" ]; then git symbolic-ref HEAD refs/heads/gh-pages; fi` — der beschriebene Fix ist im aktuellen Workflow vorhanden.

**Themen:** ci, agenten-organisation

---

<a id="issue-263"></a>

## Issue #263 — docs: Nummernkollision zweier ADRs mit der Nummer 0008 auflösen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation
- PRs: #264 (2026-08-02)

**Laut Issue:** Auf `main` existierten zwei ADRs mit derselben Nummer 0008 (`0008-space-and-asset-model.md` und `0008-search-quality-evaluation-harness.md`), beide von Querverweisen aus Feature-Spezifikationen und weiteren ADRs verlinkt. Gefordert war, dass das ältere ADR die Nummer 0008 behält und das jüngere auf die nächste freie Nummer (0011, da 0009/0010 bereits vergeben) umbenannt wird, inklusive aller Querverweise.

**Geliefert:** Exakt wie gefordert umgesetzt: `0008-search-quality-evaluation-harness.md` wurde zu `0011-search-quality-evaluation-harness.md`, Querverweise in `docs/features/search-quality-evaluation.md`, `docs/decisions/0010-ein-chunk-invariante-evaluierungskorpus.md`, `eval/README.md`, `eval/generator/README.md` und `eval/corpus/comic-characters/SOURCE.md` angepasst. Der PR verifizierte zusätzlich, dass `SOURCE.md` nicht Teil von `MANIFEST.sha256` ist, die Korpus-Prüfsummen also gültig bleiben.

**Verifikation:** `docs/decisions/0011-search-quality-evaluation-harness.md` existiert im aktuellen Worktree. `docs/decisions/0008-space-and-asset-model.md` existiert dagegen ebenfalls nicht mehr — laut `git log` wurde diese Datei durch einen späteren, mit diesem Issue nicht verwandten Commit (`bd7b4257`, „ADR-Bestand entschlacken und auf den tatsächlichen Stand bringen") entfernt bzw. neu geordnet. Die von #263 gelöste Kollision selbst ist im heutigen Bestand (Nummern 0001–0006, 0009–0019, kein 0007/0008) nicht mehr direkt nachvollziehbar, da beide betroffenen Dateinamen inzwischen aus anderen Gründen nicht mehr existieren — das Ergebnis von #263 (Eindeutigkeit) ist aber weiterhin erfüllt.

**Themen:** doku, agenten-organisation

---

<a id="issue-265"></a>

## Issue #265 — fix(space): persönlicher Space kann bei gleichzeitiger Erstanmeldung doppelt entstehen
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, backend, size:S
- PRs: #280 (2026-08-02, gemeinsam mit #266)

**Laut Issue:** `SpaceService.ensurePersonalSpace` prüfte nur per `existsByOwnerIdAndKind` und legte danach an, ohne Absicherung auf Datenbankebene. Zwei gleichzeitige Erstanmeldungen desselben Nutzers konnten so zwei persönliche Spaces erzeugen. Gefordert war ein partieller Unique-Index (nur für `kind = 'PERSONAL'`), Bereinigung vorhandener Duplikate, sauberes Abfangen der Constraint-Verletzung statt 500, ein Test für den gleichzeitigen Fall und ein dokumentierter Rollback.

**Geliefert:** Wie gefordert: Liquibase-Changeset `010-space-uniqueness-and-membership-index.yaml` legt den partiellen Unique-Index `uk_spaces_personal_owner` auf `spaces(owner_id) WHERE kind = 'PERSONAL'` an, ein vorgeschaltetes Changeset bereinigt Duplikate (ältester Space je Eigentümer bleibt, `RAISE NOTICE` meldet die Anzahl), `SpaceService.ensurePersonalSpace` fängt die Constraint-Verletzung des Verlierers eines gleichzeitigen Logins ab und liest den bereits angelegten Space statt eines 500-Fehlers. Dokumentiert in `docs/migrations/010-space-uniqueness-and-membership-index.md`. Der PR kombiniert #265 bewusst mit dem unabhängigen #266 (fehlender Index auf `space_memberships.space_id`), da beide dieselben Tabellen betreffen und beide im Review zu PR #254 als vorbestehend eingestuft wurden.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/010-space-uniqueness-and-membership-index.yaml` enthält `CREATE UNIQUE INDEX uk_spaces_personal_owner` mit Rollback `DROP INDEX IF EXISTS uk_spaces_personal_owner`. `backend/src/test/java/io/opaa/migration/Migration010SpaceUniquenessTest.java` existiert im aktuellen Stand.

**Themen:** spaces, backend, migrations

---

<a id="issue-266"></a>

## Issue #266 — perf(space): eigenständiger Index auf space_memberships.space_id fehlt
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, size:S
- PRs: #280 (2026-08-02, gemeinsam mit #265)

**Laut Issue:** Auf `space_memberships` existierte nur der zusammengesetzte Unique-Index `uk_space_memberships_user_space` mit führendem `user_id`, der Abfragen über `space_id` (z. B. das Laden aller Mitglieder eines Space) nicht bedient. Gefordert war ein eigenständiger Index auf `space_id` per Liquibase, mit dokumentiertem Rollback.

**Geliefert:** Wie gefordert, im selben PR wie #265 (beide Changesets betreffen dieselben Space-Tabellen und wurden zusammen im Review zu PR #254 als vorbestehend eingestuft). Neuer Index `idx_space_memberships_space_id` auf `space_memberships(space_id)`, dokumentiert in `docs/migrations/010-space-uniqueness-and-membership-index.md`.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/010-space-uniqueness-and-membership-index.yaml` enthält den Changeset-Block mit `indexName: idx_space_memberships_space_id` und Spalte `space_id`, inklusive Rollback-Eintrag.

**Themen:** spaces, backend, migrations, performance

---

<a id="issue-267"></a>

## Issue #267 — feat(security): Zielprüfung für URL-Indizierung ergänzen (private Adressbereiche, Schema)
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #699 (2026-08-21, gemeinsam mit #617 und #693)

**Laut Issue:** `POST /api/v1/indexing/trigger` nahm eine beliebige URL entgegen, die der Server rekursiv abrief, ohne Prüfung gegen private/lokale/Link-Local-Adressbereiche (SSRF-Härtung, nicht Behebung einer akuten Lücke — der Endpunkt ist auf `SYSTEM_ADMIN` beschränkt). Gefordert: konfigurierbare Zielprüfung vor dem ersten Abruf und nach jeder Weiterleitung (aufgelöste IP, IPv4+IPv6), explizite Schema-Prüfung auf `http`/`https` im OPAA-Code, Abschaltbarkeit/Positivliste per Umgebungsvariable (Standard: aktiv), deutsche Fehlermeldung, Tests, Doku in `.env.example`/`docs/deployment.md`.

**Geliefert:** Neue Klasse `TargetAddressValidator` prüft Schema explizit sowie die aufgelöste IP gegen Loopback, Link-Local (inkl. `169.254.169.254`), private IPv4-Bereiche, IPv6 Unique-Local und IPv4-in-IPv6-eingebettete Adressen. Konfigurierbar über `opaa.indexing.target-validation` (`OPAA_INDEXING_TARGET_VALIDATION_ENABLED`, Standard `true`, plus `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` für benannte interne Hosts — Allowlist-Format ist exakter Hostname, keine CIDR-Notation, eine explizite Vereinfachung gegenüber dem im Issue offengelassenen Format). Eingebunden in `AutoindexCrawlerService`, `UrlFileDownloader`, `RssFeedIndexingExecutor` und `SourceConnectionTestService`. Der PR bündelt #267 bewusst mit zwei weiteren, zusammenhängenden Härtungen: #693 (Fix eines http→https-Upgrade-Redirect-Bugs, der praktisch jede `http://`-Quelle in Produktion lahmlegte — Voraussetzung, damit die Redirect-Zielprüfung aus #267 überhaupt sinnvoll greift) und #617 (Zugangsdaten-Fallback im Verbindungstest gehärtet). Ehrlich benannte Grenze: Da Prüfung und tatsächlicher Verbindungsaufbau zwei getrennte DNS-Auflösungen sind, ist DNS-Rebinding zwischen beiden nicht erkennbar — der JDK-`HttpClient` bietet dafür keinen Haken.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/TargetAddressValidator.java` existiert im aktuellen Stand, ebenso `TargetAddressValidatorTest.java`, `UrlIndexingExecutorTest.java` und `RssFeedIndexingExecutorTargetValidationTest.java`. `docs/deployment.md` und `.env.example` wurden laut Dateiliste mitgeändert.

**Themen:** security, indexing, backend, retrieval

---

<a id="issue-268"></a>

## Issue #268 — docs: PR-Regeln an Merge ohne Approval anpassen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, size:S
- PRs: #270 (2026-08-02)

**Laut Issue:** Der Branch-Schutz auf `main` verlangte kein formales Approval mehr, nur noch grüne CI plus Merge durch einen Maintainer. Die Dokumentation (`AGENTS.md`, `CONTRIBUTING.md`, `docs/AGENT-ORGANIZATION.md`) beschrieb noch den alten Zustand mit Approval-Pflicht und musste nachgezogen werden. Der Code Reviewer sollte als verpflichtender Schritt erhalten bleiben, CI weiter als Merge-Gate erkennbar sein, `criew` und `bigpuritz` als merge-berechtigte Maintainer benannt werden.

**Geliefert:** PR #270 zieht `AGENTS.md`, `CONTRIBUTING.md` und `docs/AGENT-ORGANIZATION.md` nach: kein Approval mehr gefordert, Maintainer namentlich benannt, Code Reviewer und CI-Status-Checks bleiben verpflichtend beschrieben. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `CONTRIBUTING.md` enthält heute weiterhin den Satz „Merge-Recht haben ausschließlich die Maintainer des Projekts“ und „Ein formales Approval in GitHub ist dafür nicht erforderlich“ — die Regelung ist im aktuellen Code-Stand vorhanden.

**Themen:** doku, agenten-organisation, projektsetup

---

<a id="issue-271"></a>

## Issue #271 — security(auth): AdminController setzt die Organisationsgrenze nicht durch
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, security, auth
- PRs: #679 (2026-08-20)

**Laut Issue:** Bei der Nachprüfung von PR #254 fiel auf, dass `AdminController` die mit #199 eingeführte Organisationsgrenze nicht durchsetzt: `GET /api/v1/admin/users` listete Nutzer aller Organisationen, `POST /api/v1/admin/users/{id}/role` konnte Systemrollen organisationsübergreifend ändern. Aktuell nicht ausnutzbar (nur eine Organisation geseedet), aber vor Einführung einer zweiten Organisation zwingend zu beheben. Gefordert: Nutzerliste auf die eigene Organisation scopen, Rollenänderung an org-fremden Nutzern mit 404 ablehnen, weitere Controller auf denselben Lückentyp prüfen.

**Geliefert:** PR #679 scopt `UserService#findAllInOrganization` (neues `UserRepository#findByOrganizationId`) und lässt `updateRole` den Zielnutzer über `findByIdAndOrganizationId` auflösen — org-fremd führt zu 404 (`UserNotFoundException`), analog zu `SpaceService`. Neuer HTTP-Ebenen-Test `AdminControllerOrganizationBoundaryIntegrationTest`. Zusätzlich wurden laut PR-Body alle anderen Controller (Group, Library, AssetGrant, Audit, Chat, DirectorySync, Branding) systematisch geprüft — keine weiteren Lücken gefunden, kein Folge-Issue nötig. Keine Abweichung vom Issue.

**Verifikation:** `AdminController.java` ruft `userService.findAllInOrganization(currentUser.getOrganizationId())` auf — die im PR beschriebene Umsetzung ist im aktuellen Code vorhanden.

**Themen:** auth, security, spaces, backend

---

<a id="issue-272"></a>

## Issue #272 — feat(frontend): Space-Sichtbarkeit in der Oberfläche nutzbar machen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #671 (2026-08-20)

**Laut Issue:** Die mit #199 eingeführte Sichtbarkeitsachse (`PRIVATE`/`DISCOVERABLE`/`OPEN`) war backendseitig vollständig umgesetzt, aber im Frontend nicht bedienbar — `CreateSpaceDialog.tsx` bot keine Auswahl, jeder neue Space blieb `PRIVATE`, `SpaceManagementPage.tsx` hatte keinen Bezug auf `visibility`. Gefordert: Auswahl im Anlagedialog (Voreinstellung `PRIVATE`), Änderbarkeit in der Verwaltung, durchgängige Verdrahtung von `spaceStore`/`api.ts`, verständliche deutsche Beschriftungen.

**Geliefert:** PR #671 ergänzt die Sichtbarkeitsauswahl in `CreateSpaceDialog.tsx` und `SpaceManagementPage.tsx`, verdrahtet `spaceStore.ts`/`api.ts` durchgängig und fügt `spaceVisibilityLabel`/`spaceVisibilityDescription` in `utils/labels.ts` hinzu. Die im Issue angemerkte uneinheitliche PUT-Semantik (name/description werden ersetzt, visibility gemerged) wurde bewusst nicht harmonisiert — als eigenständige, über dieses Ticket hinausgehende Entscheidung eingestuft. Keine sonstige Abweichung.

**Verifikation:** `CreateSpaceDialog.tsx` existiert im aktuellen Code nicht mehr — laut `git log` wurde die Space-Anlage in Commit `ff8de56b` („Space-Anlage als mehrstufiger Assistent“) zu `SpaceCreatePage.tsx` umgebaut, nach zuvor `389fd997` (dieser PR) und `ab7134a7` (Hilfetexte-Fix). `visibility` ist heute in `frontend/src/stores/spaceStore.ts`, `frontend/src/services/api.ts`, `frontend/src/pages/SpaceManagementPage.tsx`, `frontend/src/pages/SpaceCreatePage.tsx` und `frontend/src/utils/labels.ts` präsent — die Funktion wurde nicht wieder entfernt, nur die Anlage-UI später umgebaut.

**Themen:** spaces, frontend, doku

---

<a id="issue-274"></a>

## Issue #274 — fix(eval): Nachziehbedarf aus dem Review des Golden Datasets
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:M, evaluation
- PRs: #277 (2026-08-02)

**Laut Issue:** Review-Nachzieharbeit zum Golden Dataset (#226/PR #273). Zwingend: Case-insensitive Vergleich in `_matches_description`/`generate_multi_attribute_filter` (behebt sieben zu Unrecht ausgeschlossene Treffer bei `comic-desc-005`), Sentinel `"∞"` global aus allen Bereichsfragen ausschließen, `CURATED_CASE_IDS` von rein positionell auf fingerprint-basiert umstellen. Zusätzlich: Entitäts-Konzentration bei Einzeldokument-Fällen streuen, Crosslingual-Sampling ausgewogener machen, `discarded.json` entweder mit echten Gründen füllen oder streichen, eine fachlich schiefe Frage (`comic-attr-128`) neutral formulieren, vier deutsche Resistenz-Queries korrigieren, vier ADR-0008→0011-Verweise nachziehen. Blockiert #227/#228.

**Geliefert:** PR #277 behebt alle drei zwingenden Punkte (case-insensitive Vergleich, `CURATED_CASES` als `(natural_key, query)`-Tupel statt Positionsliste) sowie sämtliche „Bitte zusätzlich“-Punkte. Eine fachliche Präzisierung gegenüber dem Issue: die Sentinel-Regel wurde nicht global, sondern **feldbezogen** umgesetzt (Ausschluss nur bei Fragen zu `overall_score` selbst, nicht bei Fragen zu anderen Attributen) — laut PR-Body eine vom Product Manager nachträglich korrigierte Fassung der ursprünglichen Issue-Formulierung. `discarded.json` wurde gestrichen statt mit Gründen gefüllt. Der Fingerabdruck in PR #277 deckte zu diesem Zeitpunkt aber noch nicht die Trefferliste (`expected_documents`) ab — das wurde erst in einer zweiten Review-Runde als Folgeissue #282 nachgezogen (siehe dort), weil der entsprechende Fix nach dem Merge von #277 noch nicht gepusht war.

**Verifikation:** `eval/generator/generate_golden_dataset.py` enthält heute `is_rated`/`has_numeric_overall` als getrennte Prädikate und `_ci_eq`/`casefold`-Vergleiche — Fortsetzung dieser Arbeit über #282. `eval/golden/comic-characters.discarded.json` existiert nicht mehr im Verzeichnis (nur `README.md`, `city-landmarks.json`, `comic-characters.candidates.json`, `comic-characters.json`) — Entfernung bestätigt.

**Themen:** evaluation, retrieval, doku

---

<a id="issue-276"></a>

## Issue #276 — fix(ci): Eigenes Secret für den Tagesreport statt des Anwendungsschlüssels
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, ci
- PRs: #278 (2026-08-02)

**Laut Issue:** Der Report-Workflow nutzte für die Zusammenfassung dasselbe Secret `OPAA_OPENAI_API_KEY` wie der erforderliche Status-Check `backend-integration`. Wird das Secret gesetzt, um die Zusammenfassung zu aktivieren, laufen unbeabsichtigt bei jedem Push echte, kostenpflichtige OpenAI-Aufrufe, und Anbieterstörungen könnten Merges blockieren. Gefordert: eigenes `OPAA_REPORT_API_KEY` (plus `OPAA_REPORT_BASE_URL`), `OPAA_OPENAI_API_KEY` bleibt ausschließlich der Anwendung vorbehalten, Dokumentation beider Secrets.

**Geliefert:** PR #278 stellt `daily_report.py`/`daily-report.yml` auf `OPAA_REPORT_API_KEY`/`OPAA_REPORT_BASE_URL` um, `ci.yml` bleibt unangetastet. Zusätzlich nebenbei behoben: ein Vorgabewert-Bug, bei dem eine leer gesetzte (statt fehlende) Repository-Variable den `os.environ.get`-Default für `OPAA_REPORT_MODEL` umgangen hätte (Regression aus #259, wäre erst beim ersten Aktivieren aufgetreten). Keine Abweichung vom Issue.

**Verifikation:** `.github/scripts/daily_report.py` liest heute `OPAA_REPORT_API_KEY` (Zeile 781) und dokumentiert den Grund inline: „damit sich [...] nicht aus dem Anwendungsschlüssel OPAA_OPENAI_API_KEY“ speist — Trennung bestätigt.

**Themen:** ci, projektsetup

---

<a id="issue-279"></a>

## Issue #279 — feat(ci): Anthropic als Anbieter für die Report-Zusammenfassung unterstützen
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:S, ci
- PRs: #281 (2026-08-02)

**Laut Issue:** Die Report-Zusammenfassung sprach nur das OpenAI-Chat-Completions-Format an. Für Anthropic-Schlüssel wird ein anderes Anfrageformat gebraucht (anderer Pfad, `x-api-key` statt Bearer, eigenes `system`-Feld, andere Antwortstruktur, `max_tokens` erforderlich). Gefordert: Anbieter über `OPAA_REPORT_PROVIDER` wählbar, ohne gesetzte Variable Erkennung am Schlüsselpräfix (`sk-ant-` → Anthropic), Vorgabemodell je Anbieter, Fehlschläge weiterhin ohne Abbruch, Dokumentation beider Anbieter.

**Geliefert:** PR #281 setzt genau das um — `OPAA_REPORT_PROVIDER` mit Präfixerkennung als Fallback, anbieterabhängiges Vorgabemodell/-endpunkt. Zusätzlich ergänzt: `HTTPError`-Behandlung protokolliert jetzt den Fehlertext des Anbieters statt nur „Zusammenfassung fehlgeschlagen“. Laut PR-Body gegen die echte API getestet (Anthropic-Haiku-Modell) und Anbietererkennung in fünf Fallkombinationen geprüft. Keine Abweichung vom Issue.

**Verifikation:** `.github/scripts/daily_report.py` liest `OPAA_REPORT_PROVIDER` (Zeile 665) mit Fallback-Erkennung — Umsetzung im aktuellen Code vorhanden.

**Themen:** ci, projektsetup

---

<a id="issue-282"></a>

## Issue #282 — fix(eval): Sentinel-Feldbezogenheit und Ground-Truth-Fingerabdruck im Golden Dataset
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:S, evaluation
- PRs: #284 (2026-08-02)

**Laut Issue:** Zweite Review-Runde zu PR #277 (#274/#226) — PR #277 wurde gemergt, bevor zwei letzte Korrekturen gepusht werden konnten. Zwingend: (1) `Entity.is_scored` war ein einziges Prädikat, das sowohl die Cross-Field-Regel auf den fünf Attributwerten als auch die `overall_score`-Sentinel-Regel gate­te — dadurch schlossen die 18 `"∞"`-Figuren fälschlich auch aus Fragen zu anderen Feldern aus, entgegen der vom Product Manager geforderten Feldbezogenheit (aktuell ohne Datenwirkung, aber im README für künftige Domänen als verbindlich deklariert). Fix: zwei getrennte Prädikate `is_rated`/`has_numeric_overall`. (2) Der `(natural_key, query)`-Fingerabdruck erfasste keine Änderung der Trefferliste (`expected_documents`) — nachgestellt: eine Fähigkeit ergänzt, Generator lief durch (`EXITCODE=0`) und schrieb das Dataset still neu. Fix: dritte Komponente `sha256(expected_documents)` im Fingerabdruck. Blockiert weiterhin #227/#228.

**Geliefert:** PR #284 liefert exakt die zwei beschriebenen Fixes — laut PR-Body ein Cherry-Pick des bereits fertigen, aber nicht mehr mergbaren zweiten Commits vom alten #277-Branch auf aktuelles `main`. Reproduktion des nachgestellten Falls auf dem tatsächlich committeten Code bestätigt: Abbruch mit `EXITCODE=1`, Datei nicht überschrieben. Keine Abweichung vom Issue.

**Verifikation:** `eval/generator/generate_golden_dataset.py` enthält `is_rated` (Zeile 146) und `has_numeric_overall` (Zeile 164) als getrennte Prädikate, mit Docstrings, die exakt die im Issue beschriebene Unterscheidung dokumentieren — Umsetzung bestätigt.

**Themen:** evaluation, retrieval

---

<a id="issue-285"></a>

## Issue #285 — feat(ci): Report-Zusammenfassung an Epics ausrichten und kürzen
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:M, ci
- PRs: #286 (2026-08-02)

**Laut Issue:** Die Zusammenfassung des Tagesreports sollte statt einer unstrukturierten Liste von Vorgängen entlang der Epics gegliedert werden (max. ein Absatz je aktivem Epic plus ein Absatz für Vorgänge ohne Epic-Bezug), mit Bewegung des Tages und Gesamtfortschritt je Epic. Zuordnung sollte aus der Ticketliste im Epic-Body kommen, ohne Ratewerte, und ohne Abfrage je Ticket.

**Geliefert:** `daily_report.py` erhebt Epics über das Label `epic`, liest Ticketlisten aus dem Body, ordnet Pull Requests über die von GitHub gepflegte Verknüpfung zu und gibt Kennzahlen fest im Prompt vor (nicht dem Modell zum Abzählen überlassen), weil ein erster Versuch mit modellseitigem Abzählen durchweg falsche Zahlen lieferte. Zusammenfassung sank von ~250 auf 183 Wörter. Rückwärtskompatibilität mit älteren Reports ohne die neuen Felder wurde geprüft.

**Verifikation:** `.github/scripts/daily_report.py` enthält 25 Treffer für "Epic"/"closingIssuesReferences" — die epic-orientierte Struktur ist im aktuellen Code vorhanden.

**Themen:** ci, tagesreport, agenten-organisation

---

<a id="issue-288"></a>

## Issue #288 — test(backend): FK-abhängige Integrationstests auf echtes Liquibase-Schema umstellen
- Geschlossen: 2026-08-03 (completed)
- Labels: bug, backend, size:M
- PRs: #298 (2026-08-03)

**Laut Issue:** Viele Integrationstests liefen mit `spring.liquibase.enabled=false`/`ddl-auto=create-drop`, wodurch Hibernate das Schema erzeugte statt Liquibase — und Hibernate legt für schlichte `UUID`-Spalten ohne `@ManyToOne` (z. B. `Space.ownerId`, `SpaceMembership.userId`, `organizationId`-Felder) keine Fremdschlüssel an. Zwei Regressionen (PR #254, #280) rutschten deshalb durch grünes CI. Gefordert: Entscheidung, welche Suiten umgestellt werden, Umstellung, Nachweis der Wirksamkeit, ein einheitliches Teardown-Muster für Migrationstests, Messung der Laufzeit.

**Geliefert:** `SpaceServiceIntegrationTest` und `SpaceRepositoryTest` auf `spring.liquibase.enabled=true`/`ddl-auto=none` umgestellt, `TestcontainersConfiguration` als gemeinsame `public`-Konfiguration; `SpaceServiceTest` (reine Mocks) bewusst nicht umgestellt. Zwei neue Regressionswächter in `SpaceRepositoryTest` (Space mit nicht existentem Owner/Organisation muss scheitern) als PR-eigener Wirksamkeitsnachweis, nachdem der ursprüngliche Nachweis im Review als eigentlich zu #287 gehörig entlarvt wurde. Teardown-Muster für Migrationstests über `io.opaa.migration.package-info` vereinheitlicht (`setAutoCommit(true)` nach jedem `Liquibase.update`). Laufzeit: kein spürbarer Nachteil, geteilter Testcontainer schon durch `@ServiceConnection` gegeben.

**Verifikation:** `backend/src/test/java/io/opaa/space/SpaceRepositoryTest.java` existiert im Worktree. Migration-Package-Info und `TestcontainersConfiguration` wurden laut PR-Dateiliste mitgeliefert; nicht einzeln erneut gegengelesen (kein tiefes Review nötig, Dateien sind vorhanden).

**Themen:** backend, testing, ci, spaces, datenbank

---

<a id="issue-289"></a>

## Issue #289 — feat(backend): Organisationsgrenze auf Datenbankebene symmetrisch absichern
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, security
- PRs: #678 (2026-08-20)

**Laut Issue:** Die Organisationsgrenze war auf DB-Ebene nur einseitig abgesichert — die besitzende Seite (`spaces`, `groups`) über zusammengesetzte Fremdschlüssel, die Nutzerseite (`user_id` in `space_memberships`/`group_memberships`) nur über einen einfachen Fremdschlüssel auf `users(id)`, ohne Organisationsbezug. Anwendungsseitig war das über `requireUserInOrganization` geschlossen, aber nicht in der Datenbank. Gefordert: Unique-Index auf `users(id, organization_id)`, zusammengesetzte Fremdschlüssel für die Nutzerseite in beiden Tabellen, Migrationstest, Rollback.

**Geliefert:** Deutlich über den ursprünglichen Zuschnitt hinaus (laut PR "erweiterte Fassung", mit Verweis auf eine Bestandsaufnahme vom 20.08.2026, ca. 2,5 Wochen nach Issue-Erstellung): Migration 047 setzt die Organisationsgrenze für alle 18 nutzerseitigen Fremdschlüssel im Schema durch, nicht nur die zwei im Issue genannten — inklusive `spaces.owner_id`, `knowledge_libraries.owner_user_id`, `documents.uploaded_by_user_id`, diverse `actor_user_id`/`*_history`-Spalten und `chats.author_id`/`space_id`. Defensive Bestandsdatenbereinigung vor der Umstellung, vier ChangeSets, Rollback vollständig definiert. Reproduktionsnachweis erbracht (Migration ausgelassen → Test schlägt fehl).

**Verifikation:** `backend/src/main/resources/db/changelog/changes/047-bind-user-references-to-organization.yaml` existiert im Worktree, referenziert in `db.changelog-master.yaml`.

**Themen:** security, backend, datenbank, spaces, mandantentrennung

---

<a id="issue-290"></a>

## Issue #290 — fix(ci): Fehlzuordnungen im Epic-Report beheben
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:M, ci
- PRs: #291 (2026-08-02)

**Laut Issue:** Der erste Lauf des epic-orientierten Reports (aus #285/#286) zeigte drei Fehlzuordnungen, alle durch Raten aus Fließtext statt strukturierten Daten: (1) Aufzählungsmarker wie `#1` in Epic #60 wurden als Ticketnummern gelesen, (2) jede `#N`-Erwähnung im Fließtext zählte als Ticket (Epic #198 erschien als Ticket von Epic #224), (3) Beispieltexte in PR-Beschreibungen (`Closes #221` als Testfall-Zitat) wurden als echte Verknüpfung gewertet.

**Geliefert:** Ticketlisten werden nur noch aus Checkbox-Einträgen gelesen, bei denen die Nummer direkt auf die Checkbox folgt; nur existierende Issues zählen, keine Epics; PR-Zuordnung nutzt GitHubs `closingIssuesReferences` statt Body-Parsing, in einer gebündelten GraphQL-Abfrage pro Tag. Wirkung an echten Daten belegt (Epic #60 verschwindet, #224 korrigiert von 4/17 auf 4/14, PR #286 korrekt nur noch #285 statt fünf Fehlzuordnungen).

**Verifikation:** Bestätigt durch denselben Treffer wie bei #285 — `closingIssuesReferences` ist in `.github/scripts/daily_report.py` vorhanden.

**Themen:** ci, tagesreport, agenten-organisation

---

<a id="issue-293"></a>

## Issue #293 — fix(auth): Race bei paralleler Erstanmeldung erzeugt 500er auf uq_users_subject_issuer
- Geschlossen: 2026-08-03 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #299 (2026-08-03)

**Laut Issue:** `UserService.findOrCreateUser` prüfte auf einen vorhandenen Nutzer und legte ihn sonst an, ohne die Unique-Constraint `uq_users_subject_issuer` zu behandeln. Bei paralleler Erstanmeldung (Provisioning-Filter bei jedem Request plus mehrere SPA-Aufrufe direkt nach Login) scheiterten empirisch 3 von 4 parallelen Aufrufen mit einem Duplicate-Key-Fehler bis zum Aufrufer durch. Gefordert: Race-Behandlung analog `SpaceService.ensurePersonalSpace`, Mehrthread-Test gegen echtes Liquibase-Schema, Reproduktionsnachweis.

**Geliefert:** `findOrCreateUser` in `updateExistingUser` und `createOrFetchUser` aufgeteilt; der Insert läuft in einer eigenen `REQUIRES_NEW`-Transaktion, bei `DataIntegrityViolationException` wird der inzwischen committete Gewinner-Datensatz neu gelesen statt der Fehler durchgereicht. Neuer Test `UserServiceCreationRaceIntegrationTest` mit vier echten parallelen Threads gegen Postgres/Liquibase. Reproduktionsnachweis erbracht (Fix zurückgenommen → `DataIntegrityViolationException` sichtbar). Im PR zusätzlich vermerkt, aber nicht behoben: dasselbe Check-then-Create-Muster besteht auch in `DirectorySyncStatusRecorder.record`.

**Verifikation:** `UserServiceCreationRaceIntegrationTest.java` existiert im Worktree unter `backend/src/test/java/io/opaa/auth/`.

**Themen:** auth, backend, concurrency, testing

---

<a id="issue-294"></a>

## Issue #294 — fix(auth): Fehler bei der Anlage des persönlichen Space darf den Login-Request nicht scheitern lassen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: keine

**Laut Issue:** Seit PR #287 wird `ensurePersonalSpace` über `TransactionSynchronization#afterCommit` aufgerufen. Schlägt die Space-Anlage in diesem Hook fehl, propagiert die Exception zum Aufrufer, obwohl der Nutzer selbst bereits erfolgreich angelegt und committet ist. Der Zustand ist selbstheilend (nächster Login-Request legt den Space nach), aber der Nutzer sieht dennoch einen Fehler beim ersten Login. Gefordert: Fehler im Hook fangen und auf WARN loggen statt durchreichen, plus Tests für Fehlerfall und Selbstheilung.

**Geliefert:** Kein PR ist mit diesem Issue verknüpft. Der heutige Code in `UserService.ensurePersonalSpaceAfterCommit`/`ensurePersonalSpace` entspricht jedoch exakt der geforderten Lösung: Der Javadoc-Kommentar dort referenziert ausdrücklich "code review of #201/#305" und vermerkt "Failures are logged, not rethrown" — der Fix wurde also nicht als eigener PR mit `Closes #294`, sondern im Zuge der größeren Arbeit an #201/#305 (Wissensbibliothek/persönlicher Space) mitgeliefert und das Issue vermutlich manuell ohne PR-Verknüpfung geschlossen.

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserService.java` (Zeilen ~187–213) zeigt die beschriebene Fehlerbehandlung im `afterCommit`-Hook, mit Javadoc-Begründung, die exakt die Selbstheilungslogik aus dem Issue beschreibt.

**Themen:** auth, backend, spaces, robustheit

---

<a id="issue-295"></a>

## Issue #295 — docs(agents): Branch-Regel für Fehlerbehebungen und Hotfixes klarstellen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, size:S
- PRs: #296 (2026-08-02)

**Laut Issue:** Beim Hotfix zu PR #287 wurde der Branch `fix/280_personal-space-transaction` statt `feature/...` verwendet — die Regel in `AGENTS.md` war zwar eindeutig, sagte aber nicht ausdrücklich, dass sie auch für Fehlerbehebungen und Hotfixes gilt. Maintainer-Entscheidung: kein `fix/`-Präfix, ausnahmslos `feature/`. Gefordert: Klarstellung in `AGENTS.md`, Abgleich mit `.claude/rules/workflow.md` und `CONTRIBUTING.md`.

**Geliefert:** PR #296 erledigt die Branch-Regel-Klarstellung und erweitert den Umfang zusätzlich um einen zweiten, verwandten Befund: Da in drei aufeinanderfolgenden PRs (#254, #280, #283) fehlerhafter Produktivcode trotz grüner Tests durchrutschte, wurde ein neuer Abschnitt „Reproduktionsnachweis" (Fix zurücknehmen, Fehlschlag belegen, Fix wiederherstellen) in `AGENTS.md`, `.claude/rules/workflow.md` und im PR-Template ergänzt — über den ursprünglichen Issue-Umfang hinaus, aber sachlich verwandt.

**Verifikation:** `AGENTS.md` enthält im Worktree unverändert den Abschnitt „Branch-Regel (verbindlich)" mit dem Satz „ausnahmslos, auch bei Fehlerbehebungen, dringenden Korrekturen und Dokumentationsänderungen" — deckt sich mit dem heutigen `AGENTS.md`, das dem Agenten selbst als Arbeitsgrundlage dient.

**Themen:** agenten-organisation, doku, ci, projektsetup

---

<a id="issue-300"></a>

## Issue #300 — fix(group): DirectorySyncStatusRecorder behandelt Race auf uk_directory_sync_status_organization nicht
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #316 (2026-08-14)

**Laut Issue:** `DirectorySyncStatusRecorder.record` folgt dem Muster `findByOrganizationId(...).orElseGet(() -> new DirectorySyncStatus(...))` gegen die Unique-Constraint `uk_directory_sync_status_organization`, ohne das Fenster zwischen Prüfung und Anlage abzusichern. Bei zwei gleichzeitigen Erstläufen derselben Organisation drohte eine `DataIntegrityViolationException`. Gefordert: dasselbe Insert-dann-Neulesen-Muster wie in #293/#265, mit echtem Thread-Test gegen Liquibase-Schema.

**Geliefert:** PR #316 setzt genau dieses Muster um (`saveAndFlush`, bei Constraint-Verletzung Neulesen und Update). `@Transactional` wurde von `record` entfernt, da sonst die gemeinsame Transaktion nach dem fehlgeschlagenen Insert als rollback-only markiert worden wäre. Die im Issue alternativ erwogene Serialisierung per Advisory-Lock wurde bewusst **nicht** umgesetzt — als eigenständiger, nicht in diesem Bugfix enthaltener Vorgang benannt. Neuer Test `DirectorySyncStatusRecorderRaceIntegrationTest` mit 8 gleichzeitigen Erstläufen gegen echtes Postgres/Liquibase-Schema.

**Verifikation:** `backend/src/main/java/io/opaa/group/sync/DirectorySyncStatusRecorder.java` und der Test `DirectorySyncStatusRecorderRaceIntegrationTest.java` existieren im heutigen Worktree.

**Themen:** backend, concurrency, group-sync, transaktionen

---

<a id="issue-302"></a>

## Issue #302 — docs(agents): Umgang mit Transaktionen in die Entwickler-Rollendefinition aufnehmen
- Geschlossen: 2026-08-03 (completed)
- Labels: documentation, size:S
- PRs: #303 (2026-08-03)

**Laut Issue:** Dieselbe Transaktions-Konstruktion (`REQUIRES_NEW` neben/innerhalb einer offenen Transaktion) hatte in Stufe A von #198 dreimal Fehler verursacht (PR #280, #297, #299), jedes Mal erst im Review gefunden. Gefordert: ein Abschnitt in `agents/roles/developer.md` mit den Lehren (Sichtbarkeit, Commit-Reihenfolge, Ressourcen, `readOnly=true` schützt nicht strukturell), belegt mit den drei PR-Nummern; Client-Adapter (`.claude/`, `.codex/`, `.opencode/`) bleiben inhaltsfrei.

**Geliefert:** PR #303 ergänzt genau diesen Abschnitt in `agents/roles/developer.md`, mit Verweis auf die drei Fälle und der Frage „braucht die Methode überhaupt `@Transactional`" an erster Stelle. Client-Adapter wurden laut PR-Beschreibung geprüft und blieben unverändert. Reine Dokumentationsänderung, keine Abweichung vom Issue erkennbar.

**Verifikation:** `agents/roles/developer.md` enthält 7 Treffer für „Transaktion" im heutigen Worktree — Abschnitt ist vorhanden.

**Themen:** doku, agenten-organisation, transaktionen

---

<a id="issue-304"></a>

## Issue #304 — eval(golden): category:crosslingual und language:de sind identische Fallmengen
- Geschlossen: 2026-08-20 (completed)
- Labels: size:S, evaluation
- PRs: #673 (2026-08-20)

**Laut Issue:** Im Golden Dataset sind `category:crosslingual` und `language:de` konstruktionsbedingt exakt dieselbe Fallmenge (34 Fälle) — der Retrieval-Regressionsjob prüft dadurch acht statt vier Mal dieselben Daten und suggeriert breitere Abdeckung. Gefordert war eine dokumentierte Entscheidung: getrennte Gruppen trotz Identität, Generator-Erweiterung um weitere Sprachen, oder Konsolidierung.

**Geliefert:** PR #673 entscheidet sich für Konsolidierung: `language:de` entfällt als Baseline-Gruppe, `category:crosslingual` bleibt (benennt die fachliche Eigenschaft). `BaselineComparator.compare` überspringt die vom Report weiterhin gelieferte `language:de`-Gruppe gezielt (`REDUNDANT_LANGUAGE_GROUP`). Die im Issue alternativ vorgeschlagene Generator-Erweiterung wurde als grundsätzlicherer, aber hier bewusst nicht gegangener Weg dokumentiert (eigenständiges Vorhaben, neuer Corpus-Lauf nötig). Entscheidung ist in `eval/baseline/README.md` und ADR-0013 (Nachtrag) festgehalten, wie gefordert.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/BaselineComparator.java` existiert im Worktree und enthält `REDUNDANT_LANGUAGE_GROUP`.

**Themen:** evaluation, retrieval, golden-dataset, ci

---

<a id="issue-306"></a>

## Issue #306 — eval(baseline): Fallzahlbasierte Regressionsprüfung für Paare mit Toleranz < 1/n
- Geschlossen: 2026-08-21 (completed)
- Labels: size:M, ci, evaluation
- PRs: #694 (2026-08-21)

**Laut Issue:** Sechs Metrik/Gruppen-Paare (v. a. `numeric_range`, `multi_attribute_filter`) haben eine Mittelwert-Toleranz, die enger ist als die Verschiebung eines einzelnen kippenden Falls (`1/n`) — ein einzelner Fall konnte die Baseline-Prüfung fälschlich reißen lassen. Gefordert: fallzahlbasierte Prüfung für genau diese Paare, ohne die Mittelwert-Toleranz für andere Paare zu ändern, mit Unit-Test für das Kipp-Szenario.

**Geliefert:** PR #694 ersetzt für Paare mit `toleranceFor(...) < 1/n` die Mittelwert-Toleranz durch eine fallzahlbasierte Prüfung (`MAX_CASE_COUNT_DROP = 1`), betroffene Paare werden **dynamisch** ermittelt statt über eine feste Liste. Dafür führt die Baseline neu `hitCountAt5`/`hitCountAt10` je Gruppe. Werte stammen aus einem realen, artefaktverifizierten `checkRetrievalBaseline`-Lauf auf CI, keine neue lokale Messung. Test `oneCaseFlipInNumericRangeNoLongerFalselyFailsTheCaseBasedPairs` reproduziert das Issue-Szenario, ein zweiter Test bestätigt, dass echte Regressionen weiter erkannt werden. Entscheidung in ADR-0013 nachgetragen.

**Verifikation:** `BaselineComparator.java` im Worktree enthält `usesCaseBasedCheck`.

**Themen:** evaluation, retrieval, ci, baseline

---

<a id="issue-307"></a>

## Issue #307 — fix(auth): Gleichzeitige Erstanmeldungen verschiedener Nutzer erschöpfen den Connection-Pool
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend, size:M, auth
- PRs: #702 (2026-08-21)

**Laut Issue:** Bei 12 gleichzeitigen Erstanmeldungen verschiedener Nutzer scheitern Requests bei Standard-Poolgröße 10 nach 30s mit „Connection is not available", obwohl die Datenbank idle ist — Ursache unklar, Login meldet trotzdem Erfolg, während Space/Bibliothek fehlen. Gefordert: Ursachenklärung, Fix ohne bloße Symptombehandlung (Poolgröße), Reproduktionstest mit echten Threads gegen Produktions-Poolgröße.

**Geliefert:** PR #702 klärt die Ursache empirisch (jstack + `pg_stat_activity` während des Hängers): kein Deadlock, sondern reine Pool-Warteschlangen-Kontention — ein Erstlogin braucht vier sequenzielle Pool-Zyklen, bei 12 gleichzeitigen Logins bis zu 48 Borrow/Return-Zyklen bei nur 10 Connections. Fix entlastet die Provisionierung statt die Poolgröße zu erhöhen: `SpaceService.ensureDefaultSpaceForNewUser` überspringt die redundante `existsBy`-Prüfung bei tatsächlich neu angelegten Nutzern, ein Caffeine-Cache merkt sich provisionierte Spaces je Nutzer, ein neuer `AuthMetrics`-Counter macht fehlgeschlagene Provisionierung sichtbar (offene Frage aus #294). `application.yml` dokumentiert die bewusst unveränderte Poolgröße 10 explizit. Abweichung vom ursprünglichen Abnahmekriterium „... und persönliche Bibliothek": laut PR seit #522/#546 gegenstandslos, da automatische Bibliotheks-Provisionierung beim Login inzwischen entfernt wurde.

**Verifikation:** `AuthMetrics.java` und `UserServiceConcurrentDistinctUserLoginIntegrationTest.java` existieren im Worktree.

**Themen:** auth, backend, connection-pool, concurrency, spaces

---

<a id="issue-308"></a>

## Issue #308 — test(backend): GroupServiceIntegrationTest auf echtes Liquibase-Schema umstellen
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend, size:S
- PRs: #691 (2026-08-21)

**Laut Issue:** `GroupServiceIntegrationTest` lief noch mit `spring.liquibase.enabled=false`/`ddl-auto=create-drop` (in #288 nur die Space-Suiten umgestellt). Dadurch konnte der Test `cannotDeleteAGroupThatStillOwnsALibrary` den Guard strukturell nicht scharf prüfen, da Hibernate den entsprechenden Fremdschlüssel gar nicht erzeugt. Gefordert: Umstellung auf echtes Liquibase-Schema, Nachweis dass der Test ohne Guard mit echter FK-Verletzung fehlschlägt, gezielte statt pauschale Datenbereinigung.

**Geliefert:** PR #691 stellt genau darauf um (`@SpringBootTest`, `spring.liquibase.enabled=true`, `ddl-auto=none`, `TestcontainersConfiguration`). Reproduktionsnachweis erbracht (Guard temporär entfernt → `DataIntegrityViolationException` statt fehlender Exception). Zusätzlicher, im Issue nicht vorgesehener Befund während der Umstellung: ein zwischenzeitlich (Migration 047) hinzugekommener Fremdschlüssel machte ein bestehendes Testszenario obsolet — dieses wurde durch einen neuen Test ersetzt, der die jetzt datenbankseitige Garantie direkt prüft.

**Verifikation:** `GroupServiceIntegrationTest.java` existiert im Worktree und enthält `spring.liquibase.enabled`.

**Themen:** backend, testinfrastruktur, groups, liquibase

---

<a id="issue-310"></a>

## Issue #310 — fix(api): GlobalExceptionHandler mappt ResponseStatusException und DataIntegrityViolationException nicht
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #314 (2026-08-14)

**Laut Issue:** `GlobalExceptionHandler` fing `ResponseStatusException` und `DataIntegrityViolationException` nicht gesondert ab, beide landeten auf dem Catch-all als HTTP 500 „Interner Serverfehler" — etwa wenn eine Gruppe mit fremdem Grant nicht gelöscht werden konnte. Gefordert: `ResponseStatusException` mit Status/Meldung durchreichen, `DataIntegrityViolationException` auf 409 mit verständlicher Meldung abbilden, Constraint-Name nur ins Log, ggf. nach Constraint-Art unterscheiden.

**Geliefert:** PR #314 setzt beides um und geht über die Mindestforderung hinaus: Statt pauschal 409 für jede `DataIntegrityViolationException` wird nach SQLSTATE unterschieden — `23505`/`23503` (Unique/FK) → 409 Conflict, `23502`/`23514` (Not-Null/Check) → 400 Bad Request, da fehlerhafte Eingabedaten kein Bestandskonflikt sind. Bestehende gezielte Guards bleiben unverändert als vorrangige Fehlerbehandlung.

**Verifikation:** `GlobalExceptionHandler.java` im Worktree enthält `DataIntegrityViolationException`.

**Themen:** backend, api, error-handling

---

<a id="issue-311"></a>

## Issue #311 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, evaluation
- PRs: #315 (2026-08-14)

**Laut Issue:** Automatisch von `app/github-actions` erzeugter Alarm — der nächtliche Retrieval-Regressionslauf schlug fehl, kein Report erzeugt, vermutlich Abbruch vor der Baseline-Prüfung oder durch Zeitlimit. Kein Feature-Issue, sondern ein CI-Alarm ohne inhaltliche Forderung.

**Geliefert:** PR #315 identifiziert die Ursache: `actions/cache` speicherte den Ollama-Modell-Cache nur im Post-Job-Schritt, den GitHub Actions bei `cancelled` (durch `timeout-minutes` ausgelöst) überspringt — der Cache blieb dauerhaft leer, jeder Lauf startete kalt und lief erneut ins Limit (sich selbst erhaltender Fehler). Fix: `actions/cache` in `restore`/`save` aufgeteilt, `save` läuft jetzt mit `if: always()`; `timeout-minutes` von 30 auf 60 angehoben, da die ursprüngliche Zeitmessung nachweislich nicht von einem echten GitHub-Actions-Runner stammte (Workflow lief dort noch nie erfolgreich durch). Eine echte Verlangsamung durch #201/#202 wurde geprüft und ausgeschlossen — die Runner-CPU selbst wurde als Ursache identifiziert, aber bewusst nicht mitbehoben (reine CI-Kapazitätsfrage, separat gemeldet).

**Verifikation:** `.github/workflows/retrieval-regression.yml` im Worktree enthält `actions/cache/save`.

**Themen:** ci, evaluation, retrieval, automatisierung

---

<a id="issue-312"></a>

## Issue #312 — fix(ci): Zeitfenster des Tagesreports nachvollziehbar und lückenlos machen
- Geschlossen: 2026-08-04 (completed)
- Labels: bug, size:S, ci
- PRs: #313 (2026-08-04)

**Laut Issue:** Drei Befunde am Tagesreport: stiller Rückfall auf UTC bei fehlender Zeitzonendatenbank (nur auf stderr protokolliert), Fenstergrenze auf `23:59:59` statt halboffen, und die verwendeten Grenzen sind weder in Rohdaten noch Seite nachvollziehbar. Gefordert: Zeitzone/Fenster in Rohdaten und Fußbereich ausweisen, halboffenes Fenster, UTC-Rückfall im Report sichtbar machen.

**Geliefert:** PR #313 setzt Nachvollziehbarkeit und UTC-Warnhinweis um wie gefordert (`timezone`, `window_start`, `window_end` in Rohdaten, Fußbereich der Seite). Abweichung vom Issue: Das geforderte halboffene Fenster wurde **nicht** umgesetzt — laut PR funktioniert das bei der GitHub-Suchsyntax nicht (zwei Bereichsangaben zum selben Feld verdrängen sich statt sich zu verknüpfen, empirisch mit Zahlen belegt). Der Bereichsoperator mit `23:59:59`-Grenze bleibt bestehen, mit Begründung im Code dokumentiert; da Zeitstempel sekundengenau sind, entsteht ohnehin keine Lücke. Zusätzlich, nicht im Issue gefordert: der geplante Lauf wurde von 04:30 UTC auf 00:30 UTC vorgezogen.

**Verifikation:** `.github/scripts/daily_report.py` im Worktree enthält `window_start`.

**Themen:** ci, tagesreport, doku, automatisierung

---

<a id="issue-317"></a>

## Issue #317 — docs: GraphRAG-Recherche als Entscheidungsgrundlage aufnehmen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S, evaluation
- PRs: #318 (2026-08-14)

**Laut Issue:** Eine unversionierte Recherche zu GraphRAG (`docs/GraphRAG.md`) lag nur im Arbeitsverzeichnis und war damit für niemanden sonst zugänglich oder kommentierbar. Gefordert: Datei versionieren, im Doku-Index verlinken, Verweis auf ein projektfremdes Ticket-Kürzel im Dokumentkopf durch dieses Issue ersetzen. Ausdrücklich außerhalb des Umfangs: eine Entscheidung über die Empfehlungen der Recherche selbst.

**Geliefert:** PR #318 setzt alle drei Punkte um wie gefordert. Zusätzlich, nicht im Issue verlangt, aber als „Repository-Hygiene" mitgeliefert: `.gitignore` um `/.claude/worktrees/` ergänzt, sowie zwei versehentlich committete leere Artefaktdateien (`ablegt.`, `Ein` — Folge eines unquotierten Shell-Redirects) entfernt. Über die inhaltlichen Empfehlungen der Recherche wurde bewusst nicht entschieden, wie im Issue verlangt.

**Verifikation:** `docs/GraphRAG.md` existiert im Worktree. Die beiden Leerdateien `ablegt.` und `Ein` sind im heutigen Worktree nicht mehr vorhanden — die Hygiene-Bereinigung ist wirksam geblieben.

**Themen:** doku, evaluation, graphrag, retrieval, repository-hygiene

---

<a id="issue-319"></a>

## Issue #319 — docs: Agentenanweisungen entpersonalisieren, eval/ ergänzen und Duplikat auflösen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation
- PRs: #320 (2026-08-14)

**Laut Issue:** Drei Befunde in den Agenten-/Beitragsanweisungen: (1) Maintainer sind an vier Stellen namentlich statt als Rolle genannt (AGENTS.md, CONTRIBUTING.md, docs/AGENT-ORGANIZATION.md zweimal), (2) `eval/` fehlt unter „Wichtige Pfade" in AGENTS.md, (3) `.claude/rules/workflow.md` dupliziert Git-Workflow/Worktree-Regeln/Pre-Push-Checkliste aus AGENTS.md nahezu wortgleich. Aufgabe: Rollen statt Namen, eval/ ergänzen, Duplikatdatei entfernen, CLA-Abschnitt in AGENTS.md kürzen.

**Geliefert:** PR #320 setzt alle vier Punkte um — Maintainer-Nennungen auf Rollenaussage umgestellt, `eval/` unter „Wichtige Pfade" ergänzt (inkl. Sonderregel: außerhalb Gradle-Build/CI, Generatoren nur bei bewussten Korpus-Änderungen), `.claude/rules/workflow.md` entfernt, CLA-Abschnitt in AGENTS.md gekürzt und auf CONTRIBUTING.md/CLA.md verwiesen. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `.claude/rules/workflow.md` existiert im Worktree nicht mehr (bestätigt). `AGENTS.md` enthält den `eval/`-Eintrag unter „Wichtige Pfade" mit dem beschriebenen Wortlaut (Zeile 190). Beides deckt sich mit dem PR-Anspruch.

**Themen:** doku, agenten-organisation, projektsetup

---

<a id="issue-321"></a>

## Issue #321 — feat(ci): Tagesreport auf Management Summary umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, enhancement, size:M, ci
- PRs: #322 (2026-08-14)

**Laut Issue:** Der Tagesreport soll statt vier flacher Listen und Fließtext eine kompakte Management Summary zeigen: Linkleiste (Testumgebung, Repository, Issues, PRs, CI mit Statuspunkt), vier Kennzahlen-Kacheln, Epic-Abschnitte mit Fortschrittsbalken und modellgenerierten Stichpunkten, Sonstiges-Abschnitt, keine Detaillisten mehr. Fällt die Zusammenfassung aus, sollen Titel statt Stichpunkte erscheinen; bestehende Reports sollen rückwirkend im neuen Layout neu erzeugt werden.

**Geliefert:** PR #322 setzt den Umfang wie gefordert um: Linkleiste mit CI-Statuspunkt (grün/rot, zusätzlich Klartext im Tooltip aus Barrierefreiheitsgründen), Testumgebungs-URL über `--test-url`/`OPAA_REPORT_TEST_URL` konfigurierbar, vier Kennzahlen-Kacheln, Epic-Abschnitte mit Fortschrittsbalken und Stichpunkten (Modell liefert JSON statt Fließtext), Rückfall auf Titel bei Ausfall, Neu-Rendering aller Bestandsseiten. Zusätzlich 35 neue Tests in `test_daily_report.py` mit Mutationsnachweis (drei Assertionen entfernt, jeweils zugehörige Tests schlagen fehl). Deckt sich mit dem Issue, keine wesentliche Abweichung.

**Verifikation:** Nicht vertieft geprüft (reiner CI-Skript-Bereich, `.github/scripts/daily_report.py`), da unstrittig und mit Mutationsnachweis im PR belegt. Kein Widerspruch zu späteren Issues in diesem Chunk erkennbar (Issue #335 baut direkt auf dieser Struktur auf).

**Themen:** ci, tagesreport, doku

---

<a id="issue-323"></a>

## Issue #323 — Auth-Konzept reviewen: Werden mock- und basic-Modus noch gebraucht?
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, backend, frontend, size:M, security, auth
- PRs: #328 (2026-08-14)

**Laut Issue:** Kein Umsetzungsauftrag, sondern ein Entscheidungsauftrag. Codeanalyse zeigt: `mock` ist kein echter Auth-Modus, sondern eine Konfigurationslücke, die die Anwendung unbenutzbar macht (broken closed). `basic` hat mehrere Mängel (nicht-portable Identitäten bei Umstieg auf oidc, HMAC-Secret in Betriebskonfiguration, kein konstantzeitiger Passwortvergleich, kein Rate-Limiting, nur ein konfigurierbarer Nutzer). Gefordert: erhobene Szenarien, Aufwandsschätzung, Bewertung von drei Varianten, eine ADR-Entscheidung, Folge-Issues für die Umsetzung.

**Geliefert:** Der PR #328 geht über eine reine Entscheidung hinaus und setzt direkt Variante 2 („oidc + echter Entwicklungsmodus") um — `mock` und `basic` werden vollständig entfernt, `DevSecurityConfig`/`DevAuthFilter` treten an ihre Stelle (synthetisches JWT via `X-OPAA-Dev-User`-Header bzw. `?devUser=`), zwei vorkonfigurierte Nutzer (`dev-admin`, `dev-user`), `AuthProfileGuard` verweigert den Start ohne Auth-Profil, alle `@Profile`-Annotationen an Controllern/UserService entfallen, Login-Endpunkt und -Formular sind entfernt. ADR-0005 wurde vollständig neu geschrieben statt per Nachfolge-ADR ersetzt. Der PR schließt zugleich #323, #255 und #260 in einem Schritt — die im Issue verlangten separaten Folge-Issues wurden nicht einzeln angelegt, sondern direkt mitimplementiert; #138 wird gegenstandslos, #139/#73 werden als vermutlich hinfällig benannt, aber nicht selbst entschieden.

**Verifikation:** `backend/src/main/java/io/opaa/auth/DevAuthFilter.java` und `DevSecurityConfig.java` existieren im heutigen Code; `AGENTS.md` verlangt `SPRING_PROFILES_ACTIVE=local,dev` als Startbefehl. Der Umbau ist im Code sichtbar konsequent umgesetzt.

**Themen:** auth, security, backend, adr

---

<a id="issue-324"></a>

## Issue #324 — Eigenen Code (evalTest) auf Jackson 3 umstellen und ADR-0007 durch Praxis-Hinweis ersetzen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, backend, size:S
- PRs: #325 (2026-08-14)

**Laut Issue:** Das `evalTest`-Sourceset (Baseline, GoldenCase, GoldenDataset, ReportWriter, BaselineRegressionTest, RetrievalEvaluationHarnessTest) nutzte noch Jackson 2 (`com.fasterxml.jackson.databind`), während der übrige Produktionscode bereits auf Jackson 3 (`tools.jackson`) umgestellt war. Zusätzlich sollte ADR-0007 entfernt werden, da er keine eigene Entscheidung trägt (folgt zwingend aus Spring Boot 4 in ADR-0002); der sachliche Befund sollte als Praxis-Hinweis in `agents/roles/developer.md` wandern.

**Geliefert:** PR #325 migriert die genannten Klassen auf `tools.jackson.databind.json.JsonMapper`, Annotationen bleiben bei `com.fasterxml.jackson.annotation.*` (bewusst, da dieses Artefakt weiterhin für Jackson 3 gilt). ADR-0007 wurde per `git rm` entfernt, keine Ersatznummer vergeben. `agents/roles/developer.md` enthält jetzt den Praxis-Hinweis inkl. Begründung, warum Jackson 2 transitiv unvermeidbar bleibt (jjwt-jackson, spring-ai-openai, Tika). Deckt sich vollständig mit dem Issue.

**Verifikation:** ADR-0007-Datei existiert im Worktree nicht mehr (bestätigt). Nicht einzeln grep-geprüft, ob `com.fasterxml.jackson.databind` noch in evalTest vorkommt — der PR-Body dokumentiert einen expliziten Vollständigkeits-Grep ohne Treffer, plausibel und risikoarm (reine Import-Umstellung).

**Themen:** backend, doku, adr, abhängigkeiten

---

<a id="issue-326"></a>

## Issue #326 — ADR-Bestand entschlacken: ADR-0001 und ADR-0002 aktualisieren, ADR-0008 in die Spezifikation überführen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #327 (2026-08-14)

**Laut Issue:** Drei Abweichungen zwischen ADR-Bestand und Realität: ADR-0001 beschreibt den Workflow noch für ein einzelnes KI-Werkzeug statt der tatsächlichen Mehrwerkzeug-Struktur (Claude Code, Codex, OpenCode, Copilot mit gemeinsamen Rollenverträgen); ADR-0002 nennt Keycloak nicht und spricht von drei statt vier Compose-Containern; ADR-0008 dupliziert zur Hälfte `docs/features/spaces-and-assets.md` und soll bis auf Systemvergleich und verworfene Alternativen entfallen.

**Geliefert:** PR #327 aktualisiert ADR-0001 (Mehrwerkzeug-Struktur, Verweis auf AGENT-ORGANIZATION.md), ADR-0002 (Keycloak/OAuth2 im Stack, vier statt drei Container, Versionsangaben gegen die Versionsdateien geprüft) und entfernt ADR-0008 vollständig; Systemvergleich und verworfene Alternativen sind als neuer Abschnitt in `spaces-and-assets.md` gewandert. Alle sechs Verweise auf ADR-0008 in fünf Dokumenten wurden umgehängt. Zusätzlich wurde `agents/roles/developer.md` korrigiert (nannte noch „Spring Boot 3.5"). Deckt sich mit dem Issue.

**Verifikation:** `docs/decisions/0008-space-and-asset-model.md` existiert im Worktree nicht mehr (bestätigt).

**Themen:** doku, adr, agenten-organisation

---

<a id="issue-330"></a>

## Issue #330 — Rechtemodell verschlanken: Asset-Rolle USER und Gruppenrollen streichen
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:S, security
- PRs: #331 (2026-08-14)

**Laut Issue:** Zwei Streichungen im Rechtemodell: (1) Asset-Rolle `USER` entfällt, da bei Agenten nicht durchsetzbar und bei Bibliotheken wirkungslos, zudem bereits im Produktivcode tot; `VIEWER` wird unterste Stufe. (2) Gruppenrollen (`STEWARD`, `LEAD`) und die gesamte Annahmeseite einer Freigabe entfallen — ein Grant an eine Gruppe braucht keine Zustimmung mehr. Verlangt: Enum-Änderung, Liquibase-Migration (bestehende `USER`-Grants auf `VIEWER` heben, CHECK-Constraint verengen), Doku-Anpassung, Migrationstest.

**Geliefert:** PR #331 setzt beides um: `AssetRole` ohne `USER`, Migration `014-drop-asset-role-user.yaml` mit zwei zwingend geordneten changeSets (erst Promotion auf VIEWER, dann Constraint-Verengung), `Migration014DropAssetRoleUserTest`. Reproduktionsnachweis mit temporärem No-op-Changeset dokumentiert (zwei Tests schlagen dabei erwartungsgemäß fehl). Doku in `access-control.md` und `spaces-and-assets.md` angepasst, verworfene Alternativen festgehalten. Schließt zusätzlich #208 gegenstandslos ab; ist Teil von Epic #198. Deckt sich mit dem Issue.

**Verifikation:** `AssetRole.java` im Worktree beginnt mit `VIEWER` als erstem Enum-Wert, Javadoc erwähnt die frühere `USER`-Stufe nur noch historisch. Bestätigt.

**Themen:** backend, security, spaces, rechtemodell, migration

---

<a id="issue-332"></a>

## Issue #332 — docs: Startbefehle nennen das verpflichtende Auth-Profil nicht
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S, auth
- PRs: #334 (2026-08-14)

**Laut Issue:** Folgefehler aus #328/#323 — der in AGENTS.md dokumentierte Startbefehl `./gradlew bootRun` bricht seit Einführung des `AuthProfileGuard` ab, weil `SPRING_PROFILES_ACTIVE` kein Auth-Profil (`oidc`/`dev`) enthält. Betroffen: AGENTS.md (Build & Test) und `docs/MVP-VERIFICATION.md` (Schritt 2, beide Varianten). Gefordert: Startbefehle mit `SPRING_PROFILES_ACTIVE=local,dev` korrigieren, Grund mit ADR-0005-Verweis nennen, `?devUser=`-Hinweis beim Frontend-Dev-Server ergänzen.

**Geliefert:** PR #334 korrigiert beide Dokumente wie gefordert, inklusive Verifikationstabelle (Fehlschlag ohne Profil, Erfolg mit Profil, Funktionsprüfung `/api/v1/auth/config` und `/api/v1/auth/me` mit/ohne Dev-User-Header). Deckt sich vollständig mit dem Issue.

**Verifikation:** `AGENTS.md` enthält Zeile 53 `SPRING_PROFILES_ACTIVE=local,dev ./gradlew bootRun`. Bestätigt.

**Themen:** doku, auth, projektsetup

---

<a id="issue-333"></a>

## Issue #333 — Space-Arten durch Attribute ersetzen, Sichtbarkeit von Inhalten umbenennen
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #337 (2026-08-14, gemergt in toten Branch), #345 (2026-08-14, tatsächlich in main)

**Laut Issue:** Zwei Vereinfachungen am Space-Modell: (1) `SpaceKind` (`PERSONAL`/`PROJECT`/`TEAM`) entfällt zugunsten zweier Attribute — `isDefault` (genau ein Space je Nutzer, nicht löschbar) und `memberSource` (`MANUAL`/`GROUP`, `GROUP` nur in der Spezifikation beschrieben, nicht implementiert, hängt an #237). Nutzer sollen mehrere private Spaces anlegen können. (2) Statuswerte `DRAFT`/`PLACED` werden zu `PRIVATE`/`SHARED` umbenannt (nur Spezifikation, da Chats/Artefakte noch nicht implementiert). Verlangt: Backend-Enum-Entfernung, Migration, OpenAPI-Anpassung, Frontend-Typen/Komponenten, Spezifikationsänderungen inkl. verworfener Alternativen.

**Geliefert:** Ungewöhnlicher Verlauf: PR #337 wurde gegen den Feature-Branch von #331 (`feature/330_rechtemodell-verschlanken`) gerichtet und dorthin gemergt — zu diesem Zeitpunkt war #331 aber bereits in main gemergt, wodurch #337 in einem toten Branch hängen blieb und nie in main ankam. PR #345 ist laut eigener Beschreibung eine inhaltlich identische Neuauflage („exakt dieselben zwei Commits") direkt gegen main und wurde tatsächlich gemergt. Inhaltlich: `SpaceKind` entfernt, `isDefault`/`memberSource` eingeführt, Migration `015-replace-space-kind-with-is-default.yaml` mit vier geordneten changeSets (Index vor Spaltenlöschung angelegt, um die Eindeutigkeitszusage nicht auszusetzen), `Migration015ReplaceSpaceKindTest`, Spezifikation und Frontend nachgezogen (Sidebar, SpaceManagementPage, Stores, Typen). Zusätzlich wurde `docs/discussions/discussion-workspace-concept.md` entfernt, nachdem sein Inhalt geprüft in die Fach-Specs überführt wurde. Deckt sich mit dem Issue; `memberSource=GROUP` bewusst nicht implementiert, wie im Issue vorgesehen.

**Verifikation:** `backend/src/main/java/io/opaa/space/SpaceKind.java` existiert im Worktree nicht mehr; `Space.java` enthält `isDefault` als Feld (bestätigt). Die auffällige PR-Kette (#337 tot, #345 als Neuauflage) ist im Code-Endzustand ohne Konsequenz, aber bemerkenswert für die Prozessqualität — ein PR wurde versehentlich gegen einen bereits gemergten Branch statt main gerichtet.

**Themen:** spaces, backend, frontend, rechtemodell, migration, doku

---

<a id="issue-335"></a>

## Issue #335 — Epics auf native GitHub-Sub-Issues umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #336 (2026-08-14)

**Laut Issue:** Epics verlinkten ihre Tickets bisher als Markdown-Checkliste im Body, die einzige Zuordnungsgrundlage des Tagesreports und driftanfällig. Umstellung auf native GitHub-Sub-Issues (GraphQL `subIssues`/`subIssuesSummary`), Checkliste nur noch als Rückfall während der Migration. Betroffen: `daily_report.py`, `epic.md`-Template (Abschnitt Tickets entfällt, Phasen als Prosa), `docs/tagesreport.md`, Migration bestehender Epics.

**Geliefert:** PR #336 setzt die Umstellung um: Zuordnung primär über Sub-Issues, Rückfall auf Checkliste mit Protokollmeldung. Migration von 86 Parent/Child-Beziehungen über die GitHub-API (außerhalb des Diffs) für die Epics #106, #107, #198, #224 sowie #18 (geschlossen, Liste dort bewusst als Abschlussbericht belassen); #4 und #60 bewusst ohne Sub-Issues, da sie nie als Ticket-Epics geführt wurden. 8 neue Tests plus Mutationsnachweis, zusätzlich Vergleichslauf gegen die Rohdaten vom 3. August in drei Zuständen (vor Migration/nach Migration/nach Checklisten-Entfernung), jeweils identisches Ergebnis. Deckt sich mit dem Issue.

**Verifikation:** `.github/ISSUE_TEMPLATE/epic.md` enthält heute „Phasen" statt „Tickets" als Abschnitt, mit Hinweis auf Sub-Issues über die Seitenleiste (bestätigt).

**Themen:** ci, tagesreport, agenten-organisation, doku

---

<a id="issue-338"></a>

## Issue #338 — Epic: Produktvision auf die öffentliche Verwaltung ausrichten
- Geschlossen: 2026-08-15 (completed)
- Labels: documentation, epic
- PRs: keine (Epic, Arbeit läuft über Sub-Issues)

**Laut Issue:** Übergeordnetes Epic für den Schwenk der Produktvision von generischem Enterprise-Wissensmanagement zu einer souveränen KI-Plattform für die öffentliche Verwaltung, gegliedert in fünf Phasen (Anker/ADR+VISION, Feature-Spezifikationen, Einstiegsdokumente, Marketing-Assets, Backlog-Sichtung). Definiert verbindliche Grenzen (keine Wettbewerber, keine Referenzkunden, kein Geschäftsmodell) und Abnahmekriterien auf Epic-Ebene.

**Geliefert:** Als natives Sub-Issue-Epic geführt; die eigentliche Arbeit steckt in den Sub-Issues #339 (Anker: ADR-0014, VISION.md, USE-CASES.md), #340 (Feature-Spezifikationen), #341 (Einstiegsdokumente/STATUS.md), #342 (Marketing-Assets) und #343 (Backlog-Sichtung). Alle fünf Sub-Issues sind geschlossen und gemergt. Das Epic selbst trägt keinen eigenen PR — konsistent mit der in #335 eingeführten Praxis, dass Epics über Sub-Issues abgeschlossen werden.

**Verifikation:** `docs/VISION.md`, `docs/USE-CASES.md`, `docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md` sowie alle fünf neuen Feature-Spezifikationen (`knowledge-sources.md`, `agents-and-tools.md`, `security-and-compliance.md`, `monitoring-and-governance.md`, `public-sector.md`) existieren im Worktree. `docs/MVP.md`, `docs/MVP-STATUS.md`, `docs/MVP-VERIFICATION.md` sind entfernt, `docs/STATUS.md` existiert. Die Epic-Ziele sind damit im aktuellen Dokumentenbestand nachvollziehbar umgesetzt.

**Themen:** doku, produktvision, agenten-organisation

---

<a id="issue-339"></a>

## Issue #339 — docs: Produktvision, ADR und Use-Cases auf die neue Ausrichtung umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:L
- PRs: #359 (2026-08-14)

**Laut Issue:** Anker-Schritt von Epic #338: ein neuer ADR zur Produktausrichtung (Wissensmanagement → Wissen + Agenten + organisationsweite Verteilung), eine schlanke Neufassung von `docs/VISION.md` (~200 Zeilen, elf Themenbereiche als Übersicht, vier Phasen, FAQ) und ein neues `docs/USE-CASES.md` mit zehn Verwaltungsfällen.

**Geliefert:** PR #359 liefert genau die drei angeforderten Dateien: `docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md` (neu), `docs/VISION.md` (Neufassung, 372→251 Zeilen), `docs/USE-CASES.md` (neu, zehn Fälle plus Amtskatalog). Der ADR benennt fragwürdig gewordene Punkte (Vektorspeicher-Austauschbarkeit, Cloud-Deployment, Chat-Kanäle), entscheidet sie aber bewusst nicht — das verlagert sich korrekt ins Prüf-Epic #344. Keine Abweichung vom Issue erkennbar.

**Verifikation:** Alle drei Dateien existieren im Worktree (`docs/VISION.md`, `docs/USE-CASES.md`, `docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md`).

**Themen:** doku, produktvision, adr, verwaltung

---

<a id="issue-340"></a>

## Issue #340 — docs: Feature-Spezifikationen entlang der elf Themenbereiche neu schneiden
- Geschlossen: 2026-08-15 (completed)
- Labels: documentation, size:L
- PRs: keine direkt verlinkt — Arbeit lief über vier Sub-Issues mit eigenen PRs: #360→#368 (2026-08-14, A/B/E), #361→#365 (2026-08-14, C/D), #362→#371 (2026-08-14, F/G/H), #363→#366 (2026-08-14, I/J/K)

**Laut Issue:** Jeder der elf Themenbereiche soll genau eine zuständige Feature-Spezifikation bekommen — teils Überarbeitung bestehender Dateien (`data-indexing-rag.md`, `spaces-and-assets.md`, `access-control.md`, `llm-integration.md`, `user-frontends.md`, `deployment-infrastructure.md`), teils Neuanlage (`knowledge-sources.md`, `agents-and-tools.md`, `security-and-compliance.md`, `monitoring-and-governance.md`, `public-sector.md`).

**Geliefert:** Laut Abschlusskommentar auf dem Issue wurde die Arbeit in vier Bündel-Sub-Issues aufgeteilt (#360 A/B/E, #361 C/D, #362 F/G/H, #363 I/J/K), die jeweils über eigene PRs (#368, #365, #371, #366) gemergt wurden. Das Issue selbst hat daher keinen direkt verlinkten PR — im gelieferten Datensatz taucht `linkedPRs: []`, obwohl es inhaltlich vollständig erledigt ist. Laut Abschlusskommentar wurde bei der Nachprüfung in zwei Bündeln Inhalt ersatzlos gestrichen statt verschoben; das wurde nachgetragen und in #366/#368 dokumentiert.

**Verifikation:** Alle fünf neuen Spezifikationsdateien existieren im Worktree (`docs/features/knowledge-sources.md`, `agents-and-tools.md`, `security-and-compliance.md`, `monitoring-and-governance.md`, `public-sector.md`). Damit ist der Abschlusskommentar durch den heutigen Dateibestand bestätigt.

**Themen:** doku, produktvision, feature-spezifikation, agenten-organisation

---

<a id="issue-341"></a>

## Issue #341 — docs: Einstiegsdokumente und Umsetzungsstand an die neue Ausrichtung angleichen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #372 (2026-08-14)

**Laut Issue:** `README.md` neu im Verwaltungsframe (drei Säulen, tatsächlicher Stack statt „wird während der Implementierung entschieden"), `docs/CONCEPTS.md` um neue Begriffe erweitert, `docs/STATUS.md` (neu) statt `MVP.md`/`MVP-STATUS.md` mit ehrlichem Stand je Themenbereich A–K, `docs/INDEX.md`/`docs/GETTING-STARTED.md` an neue Rollen-/Lesepfade angepasst.

**Geliefert:** PR #372 liefert alle geforderten Dateien und ersetzt zusätzlich `docs/MVP-VERIFICATION.md`. `docs/STATUS.md` benennt laut PR-Beschreibung unangenehme Befunde offen (Themenbereich D/Agenten ohne Code und ohne offene Vorgänge, Themenbereich K leer, kein revisionssicheres Audit-Log, keine Konnektoren/Chat-Kanäle) statt sie zu beschönigen. Verweiskorrekturen zusätzlich in `docs/AGENT-ORGANIZATION.md`, `agents/roles/product-manager.md`, `agents/roles/qa-engineer.md`, zwei ADRs und einer Diskussionsnotiz — historische ADRs wurden dabei bewusst nicht rückwirkend umgeschrieben, nur ergänzt. Der PR musste laut eigenem Hinweis als letzter der Merge-Kette gemergt werden, da er auf #359/#365/#366/#368/#371 verweist.

**Verifikation:** `README.md`, `docs/STATUS.md`, `docs/CONCEPTS.md`, `docs/INDEX.md`, `docs/GETTING-STARTED.md` existieren; `docs/MVP.md`, `docs/MVP-STATUS.md`, `docs/MVP-VERIFICATION.md` existieren nicht mehr im Worktree — Ablösung bestätigt.

**Themen:** doku, produktvision, status, einstieg

---

<a id="issue-342"></a>

## Issue #342 — docs(marketing): Landing-Page, Pitch und One-Pager auf den Verwaltungston umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #369 (2026-08-14)

**Laut Issue:** Neue Wahrheitsquelle `docs/market/MESSAGING.md`, Neutextung von `page/index.html` (durchgehend „Sie", Vergleichstabelle auf Verwaltungskriterien), Neutextung von `docs/OPAA-pitch-de.html` und neues `docs/onepager-de.html`; englische Fassungen entfallen nach Maintainer-Rückfrage.

**Geliefert:** PR #369 liefert `docs/market/MESSAGING.md` als Wahrheitsquelle mit Positionierungssatz, Nutzenversprechen je Stakeholder und einem normativen „Was wir nicht sagen"-Abschnitt. `page/index.html`, `docs/OPAA-pitch-de.html` neu getextet, `docs/onepager-de.html` neu angelegt. Der Sprachumschalter (`data-de`/`data-en`) ist entfernt, `docs/OPAA-pitch-en.html` und `docs/OPAA-pitch-en.pdf` sind laut Maintainer-Entscheidung ersatzlos gelöscht. Zusätzlich, über den Issue-Umfang hinaus: externe Schriftarten-Einbindung entfernt (ADR-0004-Konformität) und `agents/roles/marketing.md` korrigiert. Bewusst offengelassen: Screenshots in `page/img/` zeigen weiterhin englischsprachige Firmenoberflächen und widersprechen den neuen (deutschen, verwaltungsnahen) Bildunterschriften — als eigenes Folge-Thema benannt, nicht in diesem PR behoben.

**Verifikation:** `docs/market/MESSAGING.md`, `docs/onepager-de.html`, `docs/OPAA-pitch-de.html`, `page/index.html` existieren im Worktree; `docs/OPAA-pitch-en.html` und `docs/OPAA-pitch-en.pdf` existieren nicht mehr — Löschung bestätigt.

**Themen:** doku, marketing, produktvision

---

<a id="issue-343"></a>

## Issue #343 — docs: Backlog gegen die neue Produktausrichtung sichten
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #364 (2026-08-14)

**Laut Issue:** Alle offenen Issues gegen die neue Produktausrichtung sichten und in `docs/discussions/discussion-backlog-neuausrichtung.md` in vier Kategorien einsortieren (trägt die Ausrichtung / braucht neue Formulierung / zu prüfen / fehlt). Ausdrücklich reine Empfehlungsliste — kein Issue darf verändert oder geschlossen werden.

**Geliefert:** PR #364 sichtet laut eigener Beschreibung 79 offene Issues (61 tragen die Ausrichtung, 7 brauchen neue Formulierung, 10 gehören ins Prüf-Epic #344, 1 unklar) plus eine Lückenliste über alle elf Themenbereiche. Schwächste Bereiche laut Befund: D (Agenten, kein einziger Vorgang) und K (Verwaltungs-Spezifika, Leichte Sprache/BITV ohne Issue). Entspricht dem Issue-Auftrag ohne erkennbare Abweichung; kein Issue wurde dabei angefasst.

**Verifikation:** `docs/discussions/discussion-backlog-neuausrichtung.md` existiert im Worktree.

**Themen:** doku, produktvision, backlog, agenten-organisation

---

<a id="issue-344"></a>

## Issue #344 — Epic: Konzepte und Abstraktionen gegen die neue Produktausrichtung prüfen
- Geschlossen: 2026-08-15 (completed)
- Labels: documentation, epic
- PRs: keine (Epic, Arbeit läuft über Sub-Issues)

**Laut Issue:** Prüf-Epic zu Konzepten/Abstraktionen aus der Zeit des generischen Enterprise-Wissensmanagements, die die neue Ausrichtung (#338) fragwürdig macht — ausdrücklich Prüfung mit Entscheidungsvorlage, keine eigenmächtige Streichung. Drei Phasen: was dem Zielbild widerspricht (Vektorspeicher-Austauschbarkeit, Cloud-Deployment, Chat-Kanäle, Modellanbieter-Standard), was fehlt (Zitierzwang, Audit-Logging, Organisationsgrenze), was offenzuhalten ist (Plugin/MCP, Storage-Abstraktion, Bürgerassistent).

**Geliefert:** Als Sub-Issue-Epic geführt. Aus dem bearbeiteten Chunk bekannt: #348 (Vektorspeicher-Austauschbarkeit → pgvector festgelegt, PR #377), #350 (Cloud-Deployment/Managed Service → als Möglichkeit gefasst, Managed Service gestrichen, PR #378), #351 (Storage-Backend-Umfang → Abstraktion existiert im Code nicht, Dateisystem als Vertrag festgelegt, PR #380). Weitere Sub-Issues zu Chat-Kanälen, Modellanbieter-Standard, Zitierzwang, Audit-Logging, Organisationsgrenze und Phase-3-Themen liegen außerhalb dieses Chunks und wurden hier nicht geprüft.

**Verifikation:** Die drei im Chunk enthaltenen Entscheidungen sind im heutigen Dokumentenbestand nachweisbar umgesetzt (siehe issue-348.md, issue-350.md, issue-351.md). Ob alle Phase-1/2/3-Punkte des Epics abgedeckt sind, lässt sich aus diesem Chunk allein nicht abschließend beurteilen.

**Themen:** doku, produktvision, architektur, agenten-organisation

---

<a id="issue-346"></a>

## Issue #346 — docs(agents): Sub-Issue-Regel für Epics in AGENTS.md aufnehmen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #347 (2026-08-14)

**Laut Issue:** Die mit #335 eingeführte Regel (Epics führen Tickets als native Sub-Issues) war nur in `agents/roles/product-manager.md` und `.github/ISSUE_TEMPLATE/epic.md` dokumentiert. `AGENTS.md`, die zentrale Einstiegsdatei, sollte im Abschnitt „GitHub-Issues" auf die Regel verweisen, ohne die Details zu duplizieren.

**Geliefert:** PR #347 ergänzt genau diesen Abschnitt in `AGENTS.md` mit Verweis auf das Epic-Template und den Befehl zum nachträglichen Verknüpfen. Laut PR-Beschreibung war die Lücke beim Anlegen von #338 selbst aufgefallen — das Epic wurde zunächst ohne Sub-Issues erstellt. Keine Abweichung vom Issue.

**Verifikation:** `AGENTS.md` Zeile 142 enthält „Epics führen ihre Tickets als native Sub-Issues" mit Verweis auf `.github/ISSUE_TEMPLATE/epic.md`; die Regel ist im aktuellen Repository-Stand vorhanden.

**Themen:** doku, agenten-organisation, projektsetup

---

<a id="issue-348"></a>

## Issue #348 — Vektorspeicher-Austauschbarkeit: brauchen wir sie noch?
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #377 (2026-08-14)

**Laut Issue:** Prüfauftrag aus Epic #344: `docs/VISION.md`/`README.md` versprachen austauschbare Vektordatenbanken (Elasticsearch, pgvector, Milvus), obwohl ADR-0002 bereits pgvector festgelegt hatte. Zu klären: was ist im Code, gibt es einen realen Beschaffungsgrund für einen zweiten Vektorspeicher, und falls nein — Festlegung auf pgvector mit Begründung. Ergebnis sollte eine Entscheidungsvorlage sein, keine eigenmächtige Streichung.

**Geliefert:** PR #377 legt PostgreSQL mit pgvector als einzigen unterstützten Vektorspeicher fest. Portabilität der Schnittstelle bleibt als technische Eigenschaft benannt, aber ausdrücklich nicht als Angebot (kein Integrationstest, kein Betriebsleitfaden für Alternativen). Laut PR-Beschreibung wurde kein Anwendungscode geändert — reine Dokumentationsentscheidung. Geänderte Dateien: `data-indexing-rag.md` (neuer Begründungsabschnitt), ADR-0014 (neuer Nachtrags-Abschnitt), `CONCEPTS.md`, `deployment-infrastructure.md`, `deployment.md`, `STATUS.md`. Die bekannte Skalierungsgrenze von pgvector bei sehr großen Beständen wird offen benannt statt verschwiegen.

**Verifikation:** `docs/features/data-indexing-rag.md` enthält Zeile 294 „Der Vektorspeicher: PostgreSQL mit pgvector, und sonst keiner" und mehrfach die Festlegung im Text. Entscheidung ist im aktuellen Dokumentenbestand nachvollziehbar verankert.

**Themen:** doku, retrieval, architektur, produktvision

---

<a id="issue-349"></a>

## Issue #349 — Verhältnis von Plugin-Architektur und MCP klären
- Geschlossen: 2026-08-24 (not planned)
- Labels: documentation, size:S
- PRs: keine

**Laut Issue:** Teil von #344 (Backlog-Sichtung). Die Plugin-Architektur für Konnektoren (#106, #126–#130) ist als Konzept angelegt, die neue Produktausrichtung nennt daneben MCP als Werkzeug-/Systemanbindung — beide lösen überlappende Probleme. Gefordert war eine Entscheidungsvorlage (möglichst ADR-Entwurf), ob es konkurrierende oder ergänzende Wege sind und was das für die offenen Plugin-Issues #126–#130 bedeutet.

**Geliefert:** Keine Entscheidung, bewusst zurückgestellt. Laut Kommentar (14.08.2026) ist bis dahin weder eine Plugin-Schnittstelle noch MCP gebaut, und es existiert kein einziger Konnektor — eine Festlegung würde auf dem Papier getroffen, ohne dass ein realer Fall sie geprüft hätte. Festgehalten wurde immerhin eine konzeptionelle Einordnung (Konnektor zieht/tief in die Pipeline vs. MCP ruft ab/klar begrenzt) und dass #127 (WebAssembly-Laufzeit) eine eigenständige Isolationsfrage ist, die bei negativer Plugin-Entscheidung zu Themenbereich D wandert. Die sechs abhängigen Vorgänge (#106, #126–#130) sowie der offene PR #161 eines Beitragenden bleiben bis zur Entscheidung offen und unangetastet. Geschlossen mit dem Hinweis, dass die Klärung unabhängig vom Ticketbestand erfolgt (Maintainer-Entscheidung) — bei konkretem Ergebnis entstehen neue, passend geschnittene Tickets.

**Verifikation:** Nicht code-relevant (reine Konzeptklärung, kein Code betroffen).

**Themen:** konnektoren, mcp, doku, architektur

---

<a id="issue-350"></a>

## Issue #350 — Cloud-Deployment und Managed Service gegen das Souveränitätsversprechen prüfen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #378 (2026-08-14)

**Laut Issue:** Prüfauftrag aus Epic #344: `docs/features/deployment-infrastructure.md` nannte drei Deployment-Modelle (On-Premises, Private Cloud AWS/Azure/GCP, künftiger Managed Service durch das OPAA-Team). Zu klären: ist Private Cloud für die Zielgruppe realistisch oder untergräbt sie das Souveränitätsversprechen, und passt ein vom Projektteam betreuter Dienst zu einem quelloffenen Produkt. Ergebnis sollte eine Entscheidungsvorlage sein.

**Geliefert:** PR #378 fasst Cloud-Betrieb neu als „Möglichkeit, nicht als Betriebsmodell" — der Abschnitt „Cloud-Deployment und betreuter Dienst" wird durch „Wo eine Installation stehen darf" ersetzt (Umgebungsanforderungen, Verantwortlichkeit statt Entfernung zum eigenen Serverraum entscheidend, rechtliche Schranke, Erprobung/Schulung ohne echte Daten als Fall). Der vom Projektteam betreute Managed Service entfällt ersatzlos mit Begründung. Kein Anwendungscode geändert, `docker-compose.yml` enthält laut PR keine cloud-spezifischen Reste. Passend zu #348 wird derselbe Nachtrags-Abschnitt in ADR-0014 genutzt (PR weist auf möglichen kleinen Merge-Konflikt mit parallelen PRs hin).

**Verifikation:** `docs/features/deployment-infrastructure.md` enthält die Überschrift „Wo eine Installation stehen darf" (Zeile 263) — der beschriebene Umbau ist im aktuellen Dokument vorhanden.

**Themen:** doku, deployment, architektur, produktvision

---

<a id="issue-351"></a>

## Issue #351 — Umfang der Storage-Backend-Abstraktion festlegen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #380 (2026-08-14)

**Laut Issue:** Prüfauftrag aus Epic #344: Abstraktion über S3, Netzlaufwerk (SMB/NFS) und lokales Dateisystem soll air-gapped-Betrieb und Rechenzentrumsbetrieb stützen und bleibt voraussichtlich bestehen — offen war der Umfang. Zu klären: welche Backends sind für die Zielgruppe nötig, was ist heute gebaut vs. nur dokumentiert, gehört MinIO in den Compose-Stack.

**Geliefert:** PR #380 stellt als tragenden Befund fest, dass die Abstraktion **im Code nicht existiert**: `DocumentService` arbeitet direkt mit `java.nio.file.Path` gegen ein einziges konfiguriertes Verzeichnis (`OPAA_INDEXING_DOCUMENT_PATH`), obwohl die Spezifikation drei gleichrangige Backends beschrieb. Entschiedene Linie: Dateisystem ist der Vertrag; Netzlaufwerke (SMB/NFS) brauchen keine eigene Abstraktion, da vom Betriebssystem eingehängt; Objektspeicher wird als eigener Weg ohne Termin geführt, aber ohne Code heute; kein Objektspeicher-Dienst im mitgelieferten Compose-Stack. Damit liegt die Lieferung näher an „Erwartung korrigieren" als an „Abstraktion ausbauen" — die Spezifikation wird an den tatsächlichen (schmaleren) Code-Stand angepasst statt umgekehrt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/DocumentService.java` existiert, `OPAA_INDEXING_DOCUMENT_PATH` ist in `backend/src/main/resources/application.yml` referenziert — der im PR beschriebene Ist-Zustand (ein Verzeichnis, keine Backend-Abstraktion) ist damit im Code bestätigt. `docs/features/deployment-infrastructure.md` enthält den Abschnitt „Speicher-Backends" (Zeile 124).

**Themen:** doku, deployment, architektur, produktvision

---

<a id="issue-352"></a>

## Issue #352 — Zielbild der Chat-Kanäle festlegen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #379 (2026-08-14)

**Laut Issue:** Teil von #344. README und `docs/features/user-frontends.md` nannten Mattermost, RocketChat, Slack, Telegram, Signal und WhatsApp als Kanäle. Zu klären war, welche davon im Zielbild bleiben, welche entfallen — mit dem Hinweis, dass für die öffentliche Verwaltung Matrix/Element und verbreitete self-hosted Team-Chats tragend sind, Consumer-Messenger dagegen wenig Wert und Datenabfluss-Fragen bringen. Ergebnis sollte eine Entscheidungsvorlage sein, ohne Umsetzung.

**Geliefert:** Reine Dokumentationsänderung. Im Zielbild bleiben ausschließlich selbst betriebene Team-Chats — der Matrix-basierte Chat-Baustein des souveränen Arbeitsplatzes, Mattermost und Rocket.Chat, alle in Phase 3. Slack, Telegram, Signal, WhatsApp entfallen ersatzlos, begründet mit dem Identitätsargument (Kanal muss auf ein OPAA-Konto abbildbar sein) und der Übermittlungsproblematik. Die REST-API bleibt als offener Weg für weitere Kanäle. Geändert wurden `docs/STATUS.md`, ADR-0014 (Nachtrag) und `docs/features/user-frontends.md`. Kein Anwendungscode betroffen — passt zur Vorgabe des Issues.

**Verifikation:** `docs/features/user-frontends.md` existiert im Worktree. Reine Dokumentationsentscheidung, keine Codeverifikation nötig.

**Themen:** kanäle, doku, produktausrichtung, chat, öffentliche-verwaltung

---

<a id="issue-353"></a>

## Issue #353 — Standardposition der Modellanbieter auf lokal-first umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #384 (2026-08-14)

**Laut Issue:** Teil von #344. Heute sei ein Cloud-Anbieter erster Bürger, Konfiguration und Standardwerte setzten ihn stillschweigend voraus. Zu klären: welche Standardwerte in `application*.yml` das taten und wie eine Konfiguration aussieht, die ohne Zutun lokal bleibt und Cloud-Nutzung zu einer bewussten Handlung macht. Ergebnis sollte eine Entscheidungsvorlage sein.

**Geliefert:** Über die reine Vorlage hinaus wurde direkt Code geändert (PR als `feat`, nicht nur `docs`). `spring.ai.openai.base-url` und abgeleitete Werte haben keine feste Voreinstellung mehr; neuer `OpenAiBaseUrlGuard` bricht den Start laut ab, wenn `openai` als Anbieter gewählt ist, aber keine Adresse gesetzt wurde (Muster von `AuthProfileGuard`, ADR-0005). Keine technische Sperre gegen externe Ziele wurde gebaut — das ist ausdrücklich dokumentiert als Konfigurationszusage ohne Durchsetzung. `.env.example` wechselt die Voreinstellung von `openai` auf `ollama`. Neuer Test `OpenAiBaseUrlGuardTest` (6 Fälle) und Anpassungen in `MixedProviderConfigurationTest`/`ProviderConfigurationTest`. Damit liefert der PR mehr als die im Issue verlangte reine Entscheidungsvorlage — die Entscheidung wurde direkt umgesetzt.

**Verifikation:** `OpenAiBaseUrlGuard.java` existiert im Worktree unter `backend/src/main/java/io/opaa/config/`.

**Themen:** modellanbieter, konfiguration, lokal-first, produktausrichtung, backend

---

<a id="issue-354"></a>

## Issue #354 — Zitierzwang in der bestehenden Query-Pipeline bewerten
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #396 (2026-08-14)

**Laut Issue:** Teil von #344. Belegbarkeit ist Leitprinzip, seine schärfste Form ist der Zitierzwang: keine belegte Quelle, keine Antwort. Heute liefere die Pipeline nur Quellenangaben ohne Verweigerung. Zu klären: technische Bedeutung für `io.opaa.query`, ob der Schalter je Space, Bibliothek oder systemweit sitzt, ab wann er Phase-1-pflichtig ist. Ergebnis sollte eine Entscheidungsvorlage sein.

**Geliefert:** Reine Dokumentationsänderung, die den Zitierzwang in zwei Stufen schneidet. Stufe 1 (deterministisch, kein Modellaufruf): Beleg muss auf tatsächlich abgerufene Fundstelle zeigen, keine Fundstellen → keine Antwort, tragende Aussagen brauchen gültigen Beleg. Stufe 2 (inhaltliche Deckungsprüfung) bleibt eigener, unentschiedener Vorgang. Schalter sitzt am Space, verschärfbar durch Systemvorgabe — mit offen benannter Schwäche (Umgehbarkeit durch Raumwechsel). Daraus wurden vier Umsetzungsvorgänge geschnitten: #386 (Belegprüfung), #387 (Verweigerung), #388 (Schalter am Space), #389 (Deckungsprüfung Stufe 2). Wichtig für spätere Bewertung: Der Maintainer hat später (21.08., siehe Kommentare zu #386/#387/#388/#389) entschieden, nur #386 in reduziertem Umfang umzusetzen und #387–#389 zu verwerfen — das hier entschiedene Zielbild wurde also nachträglich revidiert.

**Verifikation:** `docs/features/data-indexing-rag.md` existiert im Worktree; Abschnitt „Zitierzwang" wurde laut PR #697 (siehe Baustein #386) inzwischen erneut umgeschrieben, weil die hier getroffene Zwei-Stufen-Entscheidung nicht vollständig umgesetzt wurde.

**Themen:** zitierzwang, belegbarkeit, retrieval, query, produktausrichtung

---

<a id="issue-355"></a>

## Issue #355 — Umfang des revisionssicheren Audit-Loggings schneiden
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #398 (2026-08-14)

**Laut Issue:** Teil von #344. Revisionssicheres Audit-Logging sei die größte Lücke gegenüber Phase 1. Zu klären: welche Ereignisse protokolliert werden müssen, was „revisionssicher" konkret heißt (Unveränderbarkeit, Aufbewahrung, Export, SIEM), wie sich das mit dem Verbot personenbezogener Auswertungspfade (#239) verträgt. Ergebnis sollte eine Entscheidungsvorlage, möglichst als ADR-Entwurf, sein.

**Geliefert:** Reine Dokumentationsänderung, die den Umfang schneidet. Protokolliert werden Rechteänderungen, Verwaltungshandeln, Verzeichnisabgleich, Systemeinstellungen und jeder — auch abgewiesene — Zugriff auf die Protokolldaten selbst; Abfragen und Antwortinhalte bleiben draußen. Sicherheitsgrad: einfaches Anfügen ohne Prüfsummenverkettung, mit offen benannter Grenze (Manipulation bei direktem DB-Zugang fällt nicht auf). Trennung von Speicherung und Auswertbarkeit als tragender Zielkonflikt-Lösung: kein Personenfilter außer im freigegebenen Vier-Augen-Vorgang. Aufbewahrung 1–10 Jahre, Voreinstellung 3 Jahre. Daraus wurden fünf Umsetzungsvorgänge geschnitten: #391–#395, alle laut Chunk vollständig als „completed" mit gemergten PRs umgesetzt (siehe eigene Bausteine).

**Verifikation:** `docs/features/security-and-compliance.md` existiert im Worktree; das Audit-Paket `backend/src/main/java/io/opaa/audit/` ist umfangreich vorhanden (siehe Bausteine #391–#395) — der hier geschnittene Umfang wurde tatsächlich gebaut.

**Themen:** audit, revisionssicherheit, protokoll, security, governance, produktausrichtung

---

<a id="issue-356"></a>

## Issue #356 — Organisationsgrenze über die Anwendungsschicht hinaus absichern
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #397 (2026-08-14)

**Laut Issue:** Teil von #344. Die Organisation sei die harte Mandantengrenze; #289 und #271 zeigten, dass sie heute nicht überall durchgesetzt wird. Zu klären: wo die Grenze heute geprüft wird und wo nicht, welche Absicherung auf DB- vs. Anwendungsebene gehört, wie die Einhaltung dauerhaft nachgewiesen wird. Ergebnis: Entscheidungsvorlage mit Bezug zu #289/#271.

**Geliefert:** Reine Dokumentationsänderung. Drei Schichten festgelegt: Anwendung, Datenbank, struktureller Prüflauf gegen das Schema (dritte Schicht als Lehre aus einem konkreten Befund). #289 und #271 als Voraussetzung für eine zweite Organisation vorgezogen. Neuer Vorgang #390 für den strukturellen Prüflauf angelegt, mit dem Abnahmekriterium, dass er an der heutigen Lücke aus #289 zunächst rot werden muss. Beim Lesen der Changelogs wurden zusätzliche, bis dahin nicht erfasste Verstöße gefunden (`spaces.owner_id`, `knowledge_libraries.owner_user_id`, `asset_grants`-Spalten, `groups.parent_group_id`) und dokumentiert.

**Verifikation:** `docs/features/spaces-and-assets.md` existiert; der geschnittene Prüflauf #390 wurde tatsächlich gebaut (`OrganizationBoundarySchemaTest.java` existiert unter `backend/src/test/java/io/opaa/migration/`, siehe Baustein #390). Die hier benannten Zusatzfunde zu `groups.parent_group_id` sind über #400 behoben, `indexing_jobs` über #401.

**Themen:** organisationsgrenze, mandantenfähigkeit, security, produktausrichtung, doku

---

<a id="issue-357"></a>

## Issue #357 — Bürgerassistent und öffentliches Widget als Ausblick festhalten
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #381 (2026-08-14)

**Laut Issue:** Teil von #344. Ein Bürgerassistent und öffentlich eingebettetes Widget seien späterer Ausblick, kein Fundament, und sollten festgehalten werden, damit sie nicht unbemerkt in Phase-1-Entscheidungen hineinwirken. Ergebnis sollte eine kurze Notiz sein, keine Umsetzung.

**Geliefert:** Reine Dokumentationsänderung, kleiner als im Issue skizziert: Bürgerassistent und Widget wurden als Ausblick der Phase 4 in `docs/VISION.md` und `docs/features/public-sector.md` festgeschrieben. Bewusst kein neuer Text, keine eigene Notiz, keine Anforderungsliste — nur die Auflösung des Verweises auf den offenen Vorgang. Die eigentlich verlangte Erhebung, welche Phase-1-Entscheidungen einen späteren Bürger-Scope verbauen könnten, wurde nicht in die Dokumentation aufgenommen, sondern nur als Issue-Kommentar gesichert — das ist eine Abweichung vom Auftrag „Kurze Notiz mit den Punkten, die offengehalten werden sollten".

**Verifikation:** `docs/features/public-sector.md` existiert im Worktree.

**Themen:** öffentliche-verwaltung, ausblick, bürgerassistent, produktausrichtung, doku

---

<a id="issue-358"></a>

## Issue #358 — Gruppengebundene Spaces: Mitgliedschaft aus dem Verzeichnis ableiten
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M, auth
- PRs: keine

**Laut Issue:** Mit #333 ist `SpaceKind` entfallen, `isDefault` ist umgesetzt, `memberSource` bisher nicht — jeder Space ist implizit `MANUAL`. Gefordert: `Space.memberSource` (`MANUAL`/`GROUP`) und `Space.groupId`, bei `GROUP` wird die Mitgliederliste aus einer Verzeichnisgruppe abgeleitet statt gepflegt, nur der System-Admin legt solche Spaces an, samt Folgen für Autoren-Benachrichtigung, Strikt-Modus-Prüfung und Plausibilitätsschwelle bei Verzeichnisläufen.

**Geliefert:** Nicht umgesetzt. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** `grep` auf `memberSource` in `backend/src/main/java/io/opaa/space` ohne Treffer — bestätigt Nichtumsetzung.

**Themen:** spaces, rechteverwaltung, auth, ordner

---

<a id="issue-360"></a>

## Issue #360 — docs(features): Wissensschicht, Wissensquellen und Modellsteuerung neu fassen (A, B, E)
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #368 (2026-08-14)

**Laut Issue:** Teil von #340, Bündel 1 der Feature-Spezifikationen. `data-indexing-rag.md` überarbeiten (Zitierzwang, Konfidenz, hybride Suche, Deep Document Understanding, Wissensgraph als Ausbaustufe), `knowledge-sources.md` neu anlegen (Upload/Konnektor, Rechte-Spiegelung, Lebenszyklus), `llm-integration.md` umschreiben (Modellverwaltung statt fest verdrahteter Anbieter, lokal-first, zentrale Vorgaben als Obergrenze). Abnahmekriterien: TEMPLATE-Konformität, Phasenlage, keine Anbieternamen/Preise, Vektorspeicher-Frage bleibt offen (#348).

**Geliefert:** Wie beschrieben. `data-indexing-rag.md` überarbeitet (463 Zeilen), `knowledge-sources.md` neu (415 Zeilen), `llm-integration.md` neu geschrieben (393 Zeilen). Alle Grenzen eingehalten (keine Mitbewerber, Preise, Referenzkunden). Vektorspeicherfrage ausdrücklich an #348 verwiesen statt entschieden. Deckt sich mit dem Issue-Umfang, keine Abweichung erkennbar.

**Verifikation:** Alle drei Dateien existieren im Worktree unter `docs/features/`.

**Themen:** produktausrichtung, doku, retrieval, knowledge-sources, llm-integration, spec

---

<a id="issue-361"></a>

## Issue #361 — docs(features): Verteilungsmodell ergänzen und Agentenspezifikation anlegen (C, D)
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #365 (2026-08-14)

**Laut Issue:** Teil von #340, Bündel 2, „Kernstück der neuen Ausrichtung". `spaces-and-assets.md` um die Verteilungsseite ergänzen (Verteilungsstufen, Freigabe-/Prüfworkflow, Versionierung, Katalog, Portabilität, Nutzungstransparenz), ohne das Rechtemodell umzuschreiben. `agents-and-tools.md` neu anlegen (Agenten-Onboarding, Prüfstand, Prüfagenten, Werkzeuge). Abnahmekriterien: `spaces-and-assets.md` bleibt einziges normatives Rechtemodell-Dokument, MCP-vs-Plugin-Frage bleibt offen (#349).

**Geliefert:** Wie beschrieben. `spaces-and-assets.md` um +112 Zeilen ergänzt (Freigabeweg mit Zuständen DRAFT/IN_REVIEW/RELEASED/REJECTED, Katalog, Portabilität, Nutzungstransparenz), Rechtemodell nicht umgeschrieben. `agents-and-tools.md` neu (321 Zeilen) mit den fünf im Issue benannten Bausteinen. MCP/Plugin-Verhältnis als offene Frage auf #349 verwiesen, nicht entschieden. Deckt sich mit dem Issue-Umfang.

**Verifikation:** `docs/features/spaces-and-assets.md` und `docs/features/agents-and-tools.md` existieren im Worktree.

**Themen:** produktausrichtung, doku, spaces, assets, agenten, verteilungsmodell, spec

---

<a id="issue-362"></a>

## Issue #362 — docs(features): Identität, Nachweisbarkeit und Governance neu fassen (F, G, H)
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #371 (2026-08-14)

**Laut Issue:** Teil von #340, Bündel 3. `access-control.md` um Kontenlebenszyklus, Gruppensynchronisation, Netzbereiche, Sitzungsverwaltung ergänzen. `security-and-compliance.md` neu (revisionssicheres Protokoll, DSGVO-Vollständigkeit, sichere Voreinstellungen, C5-Fähigkeit, Mitbestimmungsfähigkeit). `monitoring-and-governance.md` neu (Grenzen je Nutzer, Kostentransparenz, Auswertungscockpit). Abnahmekriterien: TEMPLATE-Konformität, G und H widersprechen sich nicht, Protokollumfang bleibt offen (#355).

**Geliefert:** Wie beschrieben. `access-control.md` ergänzt, Audit/Compliance-Kapitel nach `security-and-compliance.md` herausgelöst statt dupliziert. `security-and-compliance.md` neu mit vier Nachweisblöcken plus C5-Fähigkeit und Mitbestimmungsfähigkeit. `monitoring-and-governance.md` neu, mit eigenem Abschnitt „Die Grenze: was es bewusst nicht gibt", der G für maßgeblich erklärt (widerspruchsfrei zu G). Protokollumfang nicht entschieden, an #355 verwiesen. Deckt sich mit dem Issue-Umfang.

**Verifikation:** `docs/features/access-control.md`, `docs/features/security-and-compliance.md` und `docs/features/monitoring-and-governance.md` existieren im Worktree.

**Themen:** produktausrichtung, doku, identität, security, compliance, governance, spec

---

<a id="issue-363"></a>

## Issue #363 — docs(features): Kanäle, Betrieb und Verwaltungs-Spezifika neu fassen (I, J, K)
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #366 (2026-08-14)

**Laut Issue:** Teil von #340, Bündel 4. `user-frontends.md` umschreiben (Web-UI/REST-API als Fundament, self-hosted Team-Chats als Ausbau) — Verbraucher-Messenger dabei nicht stillschweigend streichen, sondern mit Verweis auf #352 stehen lassen. `deployment-infrastructure.md` umschreiben (Compose, Kubernetes, Betrieb ohne Netzanbindung, Mandantenfähigkeit) — Cloud-Deployment mit Verweis auf #350 stehen lassen. `public-sector.md` neu (Leichte Sprache, BITV, Revisionssicherheit, E-Akte-Anbindung, Bürgerassistent-Ausblick mit Verweis auf #357).

**Geliefert:** Wie beschrieben, nichts entfernt, nichts vorentschieden. Chat-Kanal-Tabelle mit Spalte „heute gebaut" und Verweis auf #352 (zu diesem Zeitpunkt parallel noch offen — vgl. #352, der dieselbe Woche entschied und strich). Cloud-Deployment mit Verweis auf #350. `public-sector.md` neu mit Bürgerassistent-Ausblick und Verweis auf #357. Alle drei Dateien nennen ihre Phasenlage. Deckt sich mit dem Issue-Umfang; die hier bewusst offengehaltenen Chat-Kanal-Fragen wurden kurz danach durch #352 entschieden (Verbraucher-Messenger entfallen).

**Verifikation:** `docs/features/user-frontends.md`, `docs/features/deployment-infrastructure.md` und `docs/features/public-sector.md` existieren im Worktree.

**Themen:** produktausrichtung, doku, kanäle, deployment, öffentliche-verwaltung, spec

---

<a id="issue-367"></a>

## Issue #367 — docs: Anbieternamen in der Vorbild-Analyse von spaces-and-assets.md klären
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #382 (2026-08-14)

**Laut Issue:** In `spaces-and-assets.md` steht eine Vorbild-Analyse-Tabelle mit vier namentlich genannten fremden Systemen, zwei davon Mitbewerber. Nach der #338-Festlegung („keine Mitbewerbernamen") entsteht ein scheinbarer Widerspruch. Drei Optionen zur Wahl: stehen lassen, anonymisieren, oder in `docs/discussions/` verschieben. Abnahmekriterium: Entscheidung getroffen, bei Namen-Beibehaltung Begründung in #338 oder `docs/market/MESSAGING.md` festhalten.

**Geliefert:** Option 1 gewählt (stehen lassen), mit ergänzter Regel statt Textänderung an den Bestandsstellen. `docs/market/MESSAGING.md` erhält die Grenze der Ausnahme: unzulässig ist Nennung zur Positionierung, zulässig als nachprüfbarer Sachbeleg oder interne Arbeitsanweisung, mit drei Prüffragen. `spaces-and-assets.md` und `agents/roles/product-manager.md` erhalten je einen absichernden Satz mit Verweis auf die Regel. Kein Name entfernt, kein Anwendungscode geändert — genau wie im Issue vorgesehen.

**Verifikation:** `docs/market/MESSAGING.md` und `docs/features/spaces-and-assets.md` existieren im Worktree.

**Themen:** produktausrichtung, doku, marketing, mitbewerber-regel

---

<a id="issue-370"></a>

## Issue #370 — docs(marketing): Screenshots der Landing-Page aus einem Verwaltungskorpus neu aufnehmen
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation, size:S
- PRs: #796 (2026-08-23)

**Laut Issue:** Die Landing-Page-Screenshots (`page/img/chat-interface.png`, `document-browser.png`) zeigten englischsprachige Firmeninhalte ("Document Library", "Q3_Financial_Report") und widersprachen damit der auf Deutsch und Verwaltung umgestellten Positionierung (#338). Gefordert: neue Aufnahmen aus einem Verwaltungskorpus mit deutschsprachigen Dokumenttiteln, einer alltagsnahen Frage und sichtbarer Fundstellenangabe, ohne echte Personennamen oder Aktenzeichen. Betroffen auch `docs/design/` (drei weitere PNGs), sofern weiterverwendet.

**Geliefert:** Beide Landing-Page-Screenshots aus der laufenden Rheinfurt-Demo-Instanz neu aufgenommen (angemeldet als `maria.weber`): `chat-interface.png` zeigt einen Chat im Space "Meldewesen & Ausweise" mit belegter Antwort samt Fundstellenblock, `document-browser.png` die Wissensbibliotheken-Übersicht mit vier verwaltungsnahen Beständen. Alle sichtbaren Namen sind synthetische Demo-Personas aus `docs/demo-walkthrough.md`. Bildunterschriften und Alt-Texte in `page/index.html` blieben unverändert passend. Bewusste Abweichung vom Issue-Umfang: `docs/design/*.png` wurden **nicht** angefasst, weil sie Renderings der HTML-Design-Mockups sind (Design-Artefakte, keine Marketing-Bilder) — der PR-Body begründet das explizit als Nicht-Betroffenheit statt als vergessenen Punkt.

**Verifikation:** `page/img/chat-interface.png` und `page/img/document-browser.png` als geänderte Dateien im PR bestätigt; Inhalt nicht bildlich nachgeprüft (kein Bild-Review im Rahmen dieser Recherche).

**Themen:** marketing, doku, demo

---

<a id="issue-373"></a>

## Issue #373 — GitHub Pages: Landing-Page als Startseite, Tagesreport darunter verlinken
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M, ci
- PRs: #376 (2026-08-14)

**Laut Issue:** GitHub Pages zeigte den Tagesreport als Startseite, die Landing-Page in `page/` war nirgends veröffentlicht; zusätzlich verlinkte `page/index.html` auf eine tote Adresse (`demo.opaa.ewerlin.com` statt `opaa.ewerlin.com`). Ziel: Landing-Page unter `/`, Tagesreport unter `/report/`, Historie bleibt erhalten, `.nojekyll` im Wurzelverzeichnis, Tests decken die geänderte Verlinkung ab.

**Geliefert:** Wie beschrieben. `gh-pages` aufgeteilt: neuer `landing-page.yml`-Workflow veröffentlicht `page/` bei jeder Änderung unter `/`, `daily-report.yml` schreibt weiter nächtlich unter `/report/`. `migrate_pages_layout.sh` verschiebt den Altbestand einmalig und idempotent, von beiden Workflows aufgerufen, damit die Reihenfolge nach dem Merge egal ist. Tote Adresse ersetzt. Report-Übersicht verlinkt zurück auf die Landing-Page. 45 Tests grün, zwei neu für die Verlinkung. Bewusst keine Weiterleitung von der alten Bookmark-Adresse gebaut — im PR als offener Punkt für den Maintainer benannt, nicht verschwiegen.

**Verifikation:** `page/README.md` und `.github/workflows/landing-page.yml` existieren im Worktree.

**Themen:** ci, github-pages, tagesreport, landing-page, doku

---

<a id="issue-374"></a>

## Issue #374 — fix(indexing): Chunking ohne Überlappung trennt Definitionen von ihrer Überschrift
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #402 (2026-08-14)

**Laut Issue:** `ChunkingService` nutzte `TokenTextSplitter` ohne Überlappung, obwohl die Dokumentation ~10% Überlappung behauptete. Schadensfall: Überschrift/Definition landen getrennt in zwei Chunks, beide Hälften sind als Beleg schlechter. Verlangt: Überlappung konfigurierbar mit begründetem Standardwert, Wirkung gegen den Retrieval-Harness aus #224 messen, Spezifikation nachziehen.

**Geliefert:** `TokenTextSplitter` aus Spring AI 2.0.0 kennt gar keinen Überlappungsparameter — musste selbst gebaut werden. Neue Klasse `OverlappingTokenTextSplitter`, neue Property `opaa.indexing.chunk-overlap` (Default 100 Token). Messung wie verlangt durchgeführt, aber mit ehrlichem Negativbefund: Der Evaluierungskorpus unterliegt der Ein-Chunk-Invariante (ADR-0010) — jedes Dokument ergibt genau einen Chunk, eine Überlappung kann dort strukturell nichts bewirken. Alle drei Messläufe (0/100/200 Token) liefern identische Kennzahlen. Der Standardwert 100 ist deshalb **gesetzt, nicht gemessen** — offen als Punkt in `eval/README.md` festgehalten. Nebenbefund: Harness war zuvor gar nicht lauffähig (fehlendes `@ActiveProfiles("dev")`), im selben PR behoben. Reproduktionsnachweis mit rotem/grünem Testlauf erbracht.

**Verifikation:** `OverlappingTokenTextSplitter.java` existiert im Worktree unter `backend/src/main/java/io/opaa/indexing/`.

**Themen:** retrieval, chunking, indexierung, belegbarkeit, backend, evaluierung

---

<a id="issue-375"></a>

## Issue #375 — fix(indexing): Dateisystem- und Netzindizierung führen unterschiedliche Endungslisten
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #405 (2026-08-14)

**Laut Issue:** `DocumentService` (Dateisystem/Upload) und `UrlIndexingExecutor` (Netz) ließen unterschiedliche Dateitypen zu (`.doc` nur im Netzweg). Verlangt: gemeinsame, an einer Stelle geführte Festlegung, Entscheidung ob auf Inhaltserkennung umgestellt wird, abgewiesene Dokumente werden gemeldet statt still übersprungen.

**Geliefert:** Neue Klasse `SupportedDocumentFormats` als einzige Stelle für beide Wege. `.doc` bleibt für beide (Begründung: Tika unterstützt es tatsächlich, geprüft anhand des Classpath — 245 unterstützte Medientypen insgesamt). Abgewiesene Dokumente zählen jetzt in `documentsSkipped`/`documentsTotal` des Indizierungsauftrags. Inhaltserkennung ausdrücklich **nicht** umgesetzt — als eigener Folgevorgang #404 herausgelöst (dort erledigt, siehe eigener Baustein). Reproduktionsnachweis mit rotem/grünem Lauf erbracht. Nebenbefund: `docs/STATUS.md` führte XLSX fälschlich als gebautes Format, im selben PR korrigiert.

**Verifikation:** `SupportedDocumentFormats.java` existiert im Worktree unter `backend/src/main/java/io/opaa/indexing/`.

**Themen:** indexierung, dateiformate, backend, bugfix

---

<a id="issue-383"></a>

## Issue #383 — Tagesreport: Blättern zwischen den Tagen im Report-Kopf
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, size:S, ci
- PRs: #385 (2026-08-14)

**Laut Issue:** Issue-Body ist nur „@-" (leer/Platzhalter) und trägt keinen inhaltlichen Text. Titel legt nahe: Navigation zum Blättern zwischen Berichtstagen im Kopf des Tagesreports fehlte.

**Geliefert:** Im Chunk-Datensatz war kein PR verknüpft (`linkedPRs: []`), `gh issue view --comments` liefert ebenfalls keine Kommentare. Recherche im Git-Log des Worktrees zeigt jedoch PR #385 „feat(report): Blättern zwischen Berichtstagen und feste Adresse für den aktuellen Tag" (Branch `feature/383_report-blaettern`), gemerged 2026-08-14T15:10:01Z — eine Sekunde vor dem Issue-Schluss, also eindeutig die schließende Änderung trotz fehlender Verknüpfung in den extrahierten Daten. Geändert: `.github/scripts/daily_report.py` (+81/-4), `.github/scripts/test_daily_report.py` (+43), `docs/tagesreport.md` (+17/-2). Der PR-Body selbst ist ebenfalls nur „@-", daher keine inhaltliche Zusammenfassung aus der PR-Beschreibung möglich — nur Titel und Dateiliste als Beleg.

**Verifikation:** `.github/scripts/daily_report.py` existiert im Worktree; Commit `ea8b788f "feat(report): Blättern zwischen den Berichtstagen im Report-Kopf"` im Log vorhanden.

**Themen:** ci, tagesreport, ux, doku-lücke

---

<a id="issue-386"></a>

## Issue #386 — feat(query): Belege gegen die abgerufenen Fundstellen prüfen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: #697 (2026-08-21)

**Laut Issue:** Teil von #354, Stufe 1 des Zitierzwangs. Deterministischer Kern: jeder Beleg muss auf eine tatsächlich abgerufene Fundstelle zeigen (Dokument-Kennung, Abschnittsnummer, Bezeichnung). Leere Fundstellenmenge → Verweigerung vor dem Modellaufruf. Tragende Aussagen (Sinnabschnitte, Negativliste) brauchen mindestens einen gültigen Beleg. Formregel gegen Belegverdünnung (max. 1.000 Zeichen je Beleg). Schalter zunächst hausweit. Explizit außerhalb des Umfangs: Deckungsprüfung, Verweigerungstext, Space-Schalter.

**Geliefert:** Deutlich schmaler als im Issue verlangt — per Maintainer-Entscheidung vom 21.08.2026 (Issue-Kommentar) auf reine Belegvalidierung reduziert. Umgesetzt: `CitationParser` liefert jetzt jede Beleg-Markierung einzeln, neue Klasse `CitationValidator` gleicht sie deterministisch gegen abgerufene Chunks ab, ungültige Belege werden über `citationValid: false` markiert statt die Antwort zu verweigern. **Nicht gebaut** (verworfen, nicht nur verschoben): der Verweigerungsmodus bei fehlendem Beleg, die Abschnittszerlegung mit Negativliste für „tragende Aussagen", die Formregel gegen Belegverdünnung, der Schalter am Space. Begründung im PR: Das Modell kommuniziert bereits selbst, wenn nichts gefunden wurde, fehlende Belege sind im Belegfenster sichtbar — die Validierung stellt nur sicher, dass vorhandene Belege echt sind. Mit diesem PR wurden zugleich #387, #388 und #389 geschlossen (not planned) — das ursprüngliche Zitierzwang-Konzept aus #354 wurde damit stark zurückgeschnitten. Reproduktionsnachweis mit rotem/grünem Test erbracht.

**Verifikation:** `CitationValidator.java` existiert im Worktree unter `backend/src/main/java/io/opaa/query/`. `docs/features/data-indexing-rag.md` enthält laut PR-Beschreibung einen Absatz „Bewusst nicht gebaut", der die verworfenen Teile mit Begründung dokumentiert statt sie kommentarlos zu löschen.

**Themen:** zitierzwang, belegbarkeit, retrieval, query, backend, produktausrichtung-revidiert

---

<a id="issue-387"></a>

## Issue #387 — feat(query): Verweigerung im Zitierzwang mit Auskunft über den Suchvorgang
- Geschlossen: 2026-08-21 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Teil von #354, baut auf #386 auf. Bei Verweigerung im Zitierzwang sollte eine Auskunft über den Suchvorgang erscheinen (verwendete Suchfrage, durchsuchte Bibliotheken, Trefferzahlen, ein Grund aus fester Liste) statt eines nackten „nicht feststellbar" — ohne zu verraten, ob unlesbare Bestände existieren. Verweigerung als reguläres Ergebnis mit Kennzeichen, nicht als Fehlerstatus.

**Geliefert:** Nicht umgesetzt. Laut PR #697 zu #386 (Maintainer-Entscheidung vom 21.08.2026, siehe dortiger Issue-Kommentar) wurde dieser Vorgang zusammen mit #388 und #389 verworfen. Begründung: Das Modell kommuniziert bereits selbst, wenn es nichts gefunden hat, und fehlende Belege sind im Belegfenster unmittelbar sichtbar — ein eigener Verweigerungsmodus mit Suchvorgangs-Auskunft wurde als nicht nötig bewertet. Es gibt also einen expliziten, im PR #697 dokumentierten Grund, keinen stillen Rückstand.

**Verifikation:** Kein `POST /query`-Verweigerungskennzeichen im OpenAPI-Diff von PR #697 (nur `citationValid` wurde ergänzt) — passt zur „not planned"-Einordnung.

**Themen:** zitierzwang, verweigerung, query, produktausrichtung-revidiert, verworfen

---

<a id="issue-388"></a>

## Issue #388 — feat(query): Zitierzwang am Space schalten und mit der Systemvorgabe verrechnen
- Geschlossen: 2026-08-21 (not planned)
- Labels: enhancement, backend, frontend, size:L, workspace
- PRs: keine

**Laut Issue:** Teil von #354, baut auf #386 auf. Zitierzwang sollte am Space geschaltet werden können, verschärfbar durch eine Systemvorgabe (`aktiv = Systemvorgabe ∨ Space-Einstellung`). Dafür hätte die Abfrage (`POST /query`) erstmals einen Space-Bezug führen müssen — heute kennt sie keinen. Bekannte Schwäche im Issue selbst benannt: Umgehbarkeit durch Raumwechsel.

**Geliefert:** Nicht umgesetzt, verworfen im Zuge der Maintainer-Entscheidung vom 21.08.2026 zu #386 (siehe dortiger Baustein und Issue-Kommentar). Mit dem reduzierten Umfang von #386 (reine Belegvalidierung ohne Verweigerungsmodus) entfällt die fachliche Grundlage für einen Ein/Aus-Schalter — es gibt in der jetzigen Umsetzung keinen Modus mehr, der geschaltet werden müsste.

**Verifikation:** `POST /query` führt laut Grep im Worktree (`QueryController.java`) weiterhin keinen Space-Parameter — der im Issue beschriebene Ausgangszustand („kein Space-Bezug in der Abfrage") besteht unverändert fort, was zur Nichtumsetzung passt.

**Themen:** zitierzwang, spaces, query, workspace, produktausrichtung-revidiert, verworfen

---

<a id="issue-389"></a>

## Issue #389 — docs(query): Entscheidungsvorlage zur inhaltlichen Deckungsprüfung (Stufe 2 des Zitierzwangs)
- Geschlossen: 2026-08-21 (not planned)
- Labels: documentation, size:M, evaluation
- PRs: keine

**Laut Issue:** Teil von #354, reiner Entscheidungsvorgang ohne Umsetzung. Sollte klären, ob eine inhaltliche Deckungsprüfung (Stufe 2 — trägt die zitierte Fundstelle die Aussage wirklich?) kommt, mit mindestens zwei Verfahrensoptionen, Abwägung, Empfehlung, und ob die Alternative „nicht bauen" ernsthaft geprüft wird.

**Geliefert:** Nicht umgesetzt. Verworfen im Zuge derselben Maintainer-Entscheidung vom 21.08.2026 wie #387/#388 (siehe Kommentar zu #386). Damit hat sich die im Issue selbst als „ausdrückliche Alternative" genannte Option — nicht bauen, bei Stufe 1 plus Belegdeckungsanzeige bleiben — de facto durchgesetzt, allerdings ohne die im Issue verlangte förmliche Vorlage mit Verfahrensoptionen und Empfehlung. Die Entscheidungsvorlage selbst wurde also nicht geliefert, nur die Konsequenz (nicht bauen) im Kommentar zu #386 festgehalten.

**Verifikation:** `docs/features/data-indexing-rag.md` enthält laut PR #697 (zu #386) einen Absatz „Bewusst nicht gebaut", der wohl auch diesen Punkt abdeckt — eine eigenständige Vorlage mit Verfahrensoptionen wie im Issue gefordert existiert nicht.

**Themen:** zitierzwang, deckungsprüfung, evaluierung, query, produktausrichtung-revidiert, verworfen

---

<a id="issue-390"></a>

## Issue #390 — test(backend): Organisationsgrenze durch strukturellen Prüflauf gegen das Schema nachweisen
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, size:M, security
- PRs: #688 (2026-08-20)

**Laut Issue:** Aus #356 abgeleitet: dritte Schicht des Nachweiswegs für die Organisationsgrenze. Prüflauf soll das tatsächliche DB-Schema (nicht die Changelog-Dateien) auslesen, zur Laufzeit alle Tabellen mit `organization_id` ermitteln, für jede prüfen, ob ihre Fremdschlüssel zu organisationsgebundenen Zieltabellen zusammengesetzt geführt sind, alle Verstöße gemeinsam melden, mit begründeter Ausnahmeliste. Abnahmekriterium: Der Prüflauf muss zunächst an der Lücke aus #289 rot werden.

**Geliefert:** Wie beschrieben, als `OrganizationBoundarySchemaTest` im Paket `io.opaa.migration`. Beim ersten echten Lauf fand der Test einen bis dahin unbemerkten, von keiner vorherigen Analyse erfassten Verstoß: `fk_space_memberships_space` blieb seit Migration 008 als redundanter einspaltiger Fremdschlüssel neben dem bereits vorhandenen zusammengesetzten stehen. Nach Rücksprache mit dem Koordinator direkt im selben PR behoben (Migration 050, kein Ausnahme-Eintrag, kein Folge-Issue) — Beispiel für „kleiner, themennaher Fund direkt erledigt". Rot/Grün-Nachweis mit konkreter Fehlermeldung erbracht. `DOCUMENTED_EXCEPTIONS`-Liste existiert, blieb leer.

**Verifikation:** `OrganizationBoundarySchemaTest.java` existiert im Worktree unter `backend/src/test/java/io/opaa/migration/`. Migration `050-drop-redundant-space-memberships-space-fk.yaml` existiert ebenfalls.

**Themen:** organisationsgrenze, security, migration, struktureller-prüflauf, backend

---

<a id="issue-391"></a>

## Issue #391 — feat(audit): Protokollablage und Protokollsatz, nur anfügend
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #428 (2026-08-17)

**Laut Issue:** Aus #355 abgeleitet, Fundament des Audit-Loggings. Ablage mit vollständigem Protokollsatz (16 Felder, ohne Netzadresse/Gerät/Standort), `event_type` als geschlossene Liste, nur anfügend auf DB-Ebene (Anwendungskonto ohne UPDATE/DELETE/TRUNCATE), Unterteilung nach Monaten, getrennte Pseudonymzuordnung, Transaktionsverhalten (Rollback des Auslösers rollt auch den Protokolleintrag zurück).

**Geliefert:** Wie beschrieben. `audit_log` monatlich partitioniert, `event_type`/`object_type`/`actor_kind`/`subject_kind`/`outcome` als geschlossene Listen (Java-Enum plus DB-Check-Constraint, per Test synchron gehalten), `audit_actor_pseudonyms` als getrennte Zuordnungstabelle mit `ON DELETE CASCADE`, `AuditLogService.record` läuft bewusst ohne eigene Transaktion in der des Aufrufers mit. Bekannte, offen dokumentierte Einschränkung: Das mitgelieferte Compose-Setup betreibt Postgres als Superuser, der Schreibschutz ist dort inert — als Folge-Issue #426 festgehalten. Review-Nachtrag (Runde 2) ergänzte Eigentümertrennung auf eine dedizierte `opaa_audit_owner`-Rolle (ADR-0015). Ausdrücklich vermerkter Merge-Konflikt-Hinweis zu parallelem PR #427 (gleiche Dateien, kollidierende ADR-Nummer 0015) — Nachbesserungsbedarf beim Merge-Reihenfolge, der PR-Body dokumentiert den Konflikt statt ihn zu verschweigen.

**Verifikation:** Das Audit-Paket existiert vollständig im Worktree unter `backend/src/main/java/io/opaa/audit/` (u. a. `AuditLogService.java`, `AuditActorPseudonymService.java`, `AuditEventType.java`).

**Themen:** audit, protokoll, security, backend, revisionssicherheit

---

<a id="issue-392"></a>

## Issue #392 — feat(audit): Rechte- und Verwaltungsereignisse an den bestehenden Diensten erfassen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:L, security
- PRs: #444 (2026-08-17)

**Laut Issue:** Baut auf #391 auf. Alle Ereignisse, die Zugriff verändern oder Verwaltungshandeln sind, sollen an den bestehenden Diensten (`AssetGrantService`, `KnowledgeLibraryService`, `SpaceService`, `GroupService`, `DirectorySyncPlanExecutor`, Systemrollen/-einstellungen) protokolliert werden — auch abgelehnte Aktionen. Ausdrücklich nicht erfasst: Abfragen, Antwortinhalte, Lesezugriffe, erfolgreiche Anmeldungen.

**Geliefert:** Neue `AuditEventRecorder`-Bündelklasse instrumentiert die genannten Dienste; Verzeichnisabgleich erzeugt je bewirkter Änderung einen Eintrag plus Kopfeintrag über `correlation_ref`. **Nur Ereignisse, die im Code bereits entstehen**, wurden verdrahtet — mehrere in der Spezifikation genannte Ereignistypen (Grant-Ablauf, Freigabe-Obergrenzen, Asset-Nachfolge, API-Tokens, diverse Systemeinstellungsänderungen) bleiben unverdrahtet, weil die zugrundeliegende Funktionalität im Repository noch nicht existiert — offen benannt, nicht verschwiegen. Wichtiger Nebenbefund: `@Transactional(noRollbackFor = ResponseStatusException.class)` war nötig, weil Springs Standard-Rollback sonst auch den `DENIED`-Nachweis eines abgelehnten Eskalationsversuchs mitgelöscht hätte — mit rot/grün-Reproduktionsnachweis belegt. `DENIED` ist bewusst nur für den einen im Issue explizit genannten Eskalations-Fall verdrahtet; weitere Ablehnungspfade als Folge-Issue #447 herausgelöst.

**Verifikation:** `AuditEventRecorder.java` existiert im Worktree unter `backend/src/main/java/io/opaa/audit/`.

**Themen:** audit, protokoll, security, backend, verwaltungshandeln, revisionssicherheit

---

<a id="issue-393"></a>

## Issue #393 — feat(audit): Zugriffsweg für die Revision ohne personenbezogene Auswertung
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:L, security
- PRs: #449 (2026-08-17)

**Laut Issue:** Baut auf #391/#392 auf, setzt #239 konkret um. Genau vier Abfragewege (Objekt, Zeitraum, Ereignisart, Vorgang), jede mit Pflicht-Zeitraum und Ergebnisbegrenzung. Ausdrücklich nicht gebaut: Filter/Gruppierung/Sortierung nach Person, Zählung je Person, Freitextsuche, Vollabzug. Eine Ausnahme: anlassbezogene Klärung im Vier-Augen-Prinzip mit Zweckausschluss für arbeitsrechtliche Fragen.

**Geliefert:** Wie beschrieben. Vier Endpunkte unter `/api/v1/audit/events/...`, harte serverseitige Obergrenze der Seitengröße (`MAX_PAGE_SIZE=200`), kein Endpunkt akzeptiert Personenfilter als Eingabe. Vier-Augen-Ausnahme über `AuditIncidentScopeGrant` mit Anfrage/Freigabe/Abfrage-Dreischritt, Selbstfreigabe durch zweite Person technisch verhindert (Service-Check plus DB-Constraint). Neue eng begrenzte Rolle `SystemRole.AUDITOR`. Eine Annahme im PR ist bemerkenswert: Die im Issue verlangte „technisch durchgesetzte Trennung der Auswertungswege für Revision und Dienststellenleitung" wurde nur einseitig erfüllt — das Leitungs-Cockpit existiert laut `monitoring-and-governance.md` noch gar nicht, die Trennung ist also nur dadurch gegeben, dass der Revisionsweg heute der einzige ist.

**Verifikation:** `AuditQueryService.java` und `AuditIncidentScopeGrant.java` existieren im Worktree unter `backend/src/main/java/io/opaa/audit/`.

**Themen:** audit, revision, security, backend, mitbestimmungsfähigkeit, governance

---

<a id="issue-394"></a>

## Issue #394 — feat(audit): Zugriff auf Protokolldaten erzeugt selbst einen Eintrag
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:S, security
- PRs: #450 (2026-08-17)

**Laut Issue:** Baut auf #391/#393 auf. Jeder Lese-, Auswertungs- und Exportzugriff auf Protokolldaten — auch der abgewiesene — muss selbst einen nicht unterdrückbaren Eintrag mit Person, Zeitpunkt, Pflicht-Anlass und Umfang erzeugen, in derselben Ablage wie alle anderen Einträge.

**Geliefert:** Wie beschrieben, über alle fünf Zugriffswege aus #393 hinweg (`AUDIT_LOG_ACCESSED`). Wichtige Architekturentscheidung: `@PreAuthorize` auf den Controller-Endpunkten entfernt, weil eine dort abgewiesene Anfrage sonst nie den zentralen Lese-Trichter (`AuditQueryService`) erreicht und damit auch nicht protokollierbar wäre — die AUDITOR-Prüfung und die Anlass-Pflicht laufen jetzt im Service selbst. Ein Constraint-Detail: `reason` ist an der HTTP-Schicht bewusst nicht `required`, damit ein fehlender Anlass den Trichter erreicht statt von Spring MVC vorab abgefangen zu werden — für die übrigen Parameter gilt das nicht, was der PR-Autor selbst als Lücke benennt und als Folge-Issue #452 herausgelöst hat. Zweites Folge-Issue #451 zum Schutz gegen Fluten der Ablage durch wiederholte abgewiesene Zugriffe.

**Verifikation:** `AuditQueryService.java` (Selbstprotokollierung) und der Test `AuditFunnelStructureTest` existieren im Worktree unter `backend/src/main/java/io/opaa/audit/` bzw. `backend/src/test/java/io/opaa/audit/`.

**Themen:** audit, selbstprotokollierung, security, backend, revisionssicherheit

---

<a id="issue-395"></a>

## Issue #395 — feat(audit): Aufbewahrung der Protokolldaten mit automatischer Löschung
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #454 (2026-08-17)

**Laut Issue:** Letzter Baustein der Audit-Serie. Konfigurierbare Frist (1–10 Jahre, Default 3), automatische monatliche Löschung über ein getrenntes Wartungskonto, nie einzelne Sätze sondern vollständige Zeitscheiben, Warnung bei Protokollfrist kürzer als Inhaltsaufbewahrung, Verkürzung wirkt nur nach vorn und ist selbst protokollpflichtig.

**Geliefert:** Wie beschrieben, mit einer offen benannten Lücke: Das Abnahmekriterium „Protokollfrist kürzer als Inhaltsaufbewahrung erzeugt Warnung" ist **nicht erfüllt**, nur als Erweiterungspunkt (`ContentRetentionProvider`) vorbereitet — eine konfigurierbare Inhaltsaufbewahrung existiert erst mit #216, dort per Kommentar vermerkt. Löschmechanismus über `SECURITY DEFINER`-Funktion mit eigenem `opaa_audit_owner`. Review-Runde 1 fand und behob drei blockierende Sicherheitsbefunde vor dem finalen Merge: ein `pg_temp`-Schattenangriff, mit dem das Anwendungskonto den Fortschrittsdeckel umgehen konnte (in der Reviewer-Reproduktion 39 von 51 Partitionen in einem Aufruf gelöscht), ein an der eigenen Härtung scheiterndes JPA-`save`, und ein ungedeckelter allererster Aufruf nach der Migration. Alle drei mit Regressionstests belegt. Der bereits in #391 offen benannte Superuser-Schwachpunkt (#426) wird durch diesen PR laut eigener Aussage „dringlicher, nicht gelöst".

**Verifikation:** `AuditRetentionSettingsService.java` und `AuditRetentionDeletionService.java` existieren im Worktree unter `backend/src/main/java/io/opaa/audit/`.

**Themen:** audit, aufbewahrung, löschung, security, backend, dsgvo

---

<a id="issue-400"></a>

## Issue #400 — fix(db): Übergeordnete Gruppe an die Organisation binden
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, security
- PRs: #675 (2026-08-20)

**Laut Issue:** Aus #289/#356 herausgelöster Einzelfall: `groups.parent_group_id` verwies nur auf `groups(id)`, nicht auf `(id, organization_id)` — eine Gruppe konnte eine übergeordnete Gruppe aus einer anderen Organisation haben. Anders als die übrigen #289-Fälle sofort lösbar, weil `uk_groups_id_organization` bereits existiert. Verlangt: Fremdschlüssel zusammensetzen, Reproduktionsnachweis rot/grün, struktureller Prüflauf aus #390 wird an dieser Tabelle grün.

**Geliefert:** Migration 046 (zwei ChangeSets): räumt zunächst bestehende organisationsübergreifende Elternverweise auf (`UPDATE ... SET parent_group_id = NULL`), dann Umstellung auf zusammengesetzten Fremdschlüssel mit `ON DELETE SET NULL` beschränkt auf `parent_group_id` (rohes SQL statt `addForeignKeyConstraint`, weil Liquibase das Spaltenlisten-`SET NULL` nicht abbildet). `GroupService` erzwingt die Bindung weiterhin nicht auf Anwendungsebene — die Migration ist die einzige Absicherung, wie im Issue vorgesehen. Ein Abnahmekriterium im Issue war zum Zeitpunkt dieses PRs nicht erfüllbar und das im PR auch offen benannt: „Der strukturelle Prüflauf aus #390 wird an dieser Tabelle grün" — #390 existierte zu diesem Zeitpunkt noch nicht im Repository. Reproduktionsnachweis rot/grün erbracht, Review-Nachbesserung ergänzte eine Rollback-Prüfung.

**Verifikation:** Migration `046-bind-groups-parent-group-to-organization.yaml` existiert im Worktree.

**Themen:** organisationsgrenze, security, migration, gruppen, backend

---

<a id="issue-401"></a>

## Issue #401 — feat(db): Indizierungsläufe an die Organisation binden
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #681 (2026-08-20)

**Laut Issue:** Aus #356 abgeleitet: `indexing_jobs` trug keine `organization_id`. Bei einer zweiten Organisation wäre ein Indizierungslauf nicht mandantengebunden gewesen (Statusabfrage zeigt fremde Läufe, Nebenläufigkeitssperre wirkt organisationsübergreifend). Verlangt: Spalte ergänzen, Auftragsanlage/Statusabfrage/Sperre auf Organisation beziehen, Ratenbegrenzung je Organisation prüfen.

**Geliefert:** Migration 049 mit Backfill (über `library_id`, sonst die einzige damals existierende Organisation, per `preConditions`-Sperre gegen Mehrfachorganisationen abgesichert), `NOT NULL`, zusammengesetzter Fremdschlüssel. Wichtiger Kontextwechsel gegenüber dem Issue: `GET /api/v1/indexing/status` existiert auf dem heutigen Stand gar nicht mehr — die Läufe sind seit #478/ADR-0018 bibliotheksbezogen, und die dort beschriebene HTTP-Lücke besteht nicht mehr. Der PR liefert `organization_id` trotzdem als zweiten, von der Bibliotheksprüfung unabhängigen Schutz direkt auf `indexing_jobs`. Ratenbegrenzung je Organisation bewusst **nicht umgesetzt** — im Issue als offene Frage benannt, im PR als eigener, nicht in diesem Scope zu klärender Vorgang eingestuft. `indexing_run_events` bleibt bewusst ohne eigene `organization_id` (Kindtabellen-Ausnahme analog `chat_messages`, begründet). Kein klassischer rot/grün-Reproduktionsnachweis, da kein reproduzierbarer Bugfix — stattdessen Schema- und Verhaltensnachweis mit zwei echten Organisationen.

**Verifikation:** Migration `049-bind-indexing-jobs-to-organization.yaml` und `IndexingJob.java` existieren im Worktree.

**Themen:** organisationsgrenze, security, migration, indexierung, backend, mandantenfähigkeit

---

<a id="issue-404"></a>

## Issue #404 — feat(indexing): Zulässige Dokumenttypen über den erkannten Inhalt statt über die Dateiendung bestimmen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: #704 (2026-08-21)

**Laut Issue:** Aus #375 herausgelöst. Auswahl erfolgte weiterhin über die Dateiendung, obwohl die Spezifikation Inhaltserkennung verspricht — Lücke zwischen Doku und Code. Verlangt: Zulassung anhand erkannten Medientyps, Meldung bei Abweichung Endung/Inhalt, Zuordnung Typ→Extraktionsstrategie, begründete Entscheidung über zusätzlich freigegebene Typen, gleiches Verhalten über beide Indizierungswege mit Test.

**Geliefert:** Wie beschrieben, auf allen drei dateibasierten Aufnahmewegen (Verzeichnis, Webverzeichnis, RSS-Anlagen). Tika-basierte Inhaltserkennung, neue Kategorie `FORMAT_MISMATCH` für abweichende Endung. Zulässige Typen bewusst **unverändert** belassen (die bisherigen sechs Endungen) — eine Erweiterung wird ausdrücklich als eigene fachliche Entscheidung außerhalb des Umfangs benannt. Bemerkenswerte fachliche Differenzierung: Für eindeutig erkennbare Binärformate entscheidet der Inhalt allein; für Markdown/Klartext bleibt die Endung die Disambiguierung, weil Tika inhaltlich nicht zwischen `.md`, `.txt` und z. B. CSV unterscheiden kann — sonst wäre jede lesbare Textdatei egal welchen Namens stillschweigend zugelassen worden, was die Abnahmekriterien ausdrücklich ausschließen. Der Upload-Weg bleibt bewusst strenger (eigene Prüfung, kein Fallback auf Inhaltserkennung). Reproduktionsnachweis rot/grün erbracht.

**Verifikation:** `SupportedDocumentFormats.java` (erweitert um `decideForFileName`) existiert im Worktree unter `backend/src/main/java/io/opaa/indexing/`.

**Themen:** indexierung, dateiformate, inhaltserkennung, backend, retrieval

---

<a id="issue-406"></a>

## Issue #406 — fix(query): Über die Indexierung eingespielte Dokumente sind im Chat für niemanden auffindbar
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:M
- PRs: #413 (2026-08-15)

**Laut Issue:** Issue-Body ist nur „@-" (leer/Platzhalter). Titel beschreibt einen Bug: über Indizierung eingespielte Dokumente waren im Chat für niemanden auffindbar.

**Geliefert:** Im Chunk-Datensatz kein PR verknüpft, `gh issue view --comments` liefert keine Kommentare. Der Branchname `feature/406_systembibliothek-rechtepfade` und die exakte zeitliche Übereinstimmung (PR #413 gemerged 2026-08-15T10:33:04Z, Issue geschlossen 2026-08-15T10:33:05Z) belegen: PR #413 „fix(library): Rechteprüfung für die System-Bibliothek vereinheitlichen" ist die schließende Änderung. Inhaltlich passt das zusammen — eine uneinheitliche Rechteprüfung an der System-Bibliothek würde erklären, warum indizierte Dokumente für niemanden (auch nicht Berechtigte) auffindbar waren. Geändert: `LibraryAccessService.java`, `KnowledgeLibraryService.java`, `LibraryOwnerType.java` sowie Tests in `KnowledgeLibraryServiceIntegrationTest`, `LibraryAccessServiceTest`, `QueryIntegrationTest`; dazu `docs/features/spaces-and-assets.md` und `docs/migrations/012-knowledge-library.md`. PR-Body ebenfalls nur „@-", daher keine Aussage zum Reproduktionsnachweis möglich.

**Verifikation:** `LibraryAccessService.java` existiert im Worktree unter `backend/src/main/java/io/opaa/library/`.

**Themen:** query, retrieval, rechte, bugfix, backend, doku-lücke

---

<a id="issue-407"></a>

## Issue #407 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-16 (completed)
- Labels: bug, evaluation
- PRs: keine

**Laut Issue:** Automatisch von `github-actions` erzeugtes Alarm-Issue: der nächtliche Retrieval-Regressionslauf ist fehlgeschlagen, ohne Report (vermutlich Manifest- oder Ein-Chunk-Invariante-Verletzung oder Zeitlimit-Abbruch). Verweis auf den Workflow-Lauf zur Diagnose.

**Geliefert:** Kein PR, kein Code-Fix — passt zum Charakter des Issues als automatischer Alarm statt Arbeitsauftrag. Laut Kommentar von `github-actions` vom 2026-08-16 ist der nächtliche Lauf beim nächsten Durchlauf „wieder grün" (Link auf Folge-Workflow-Lauf) — das Issue wurde also durch Selbstheilung bzw. einen transienten Fehler geschlossen, nicht durch eine gezielte Behebung. Keine Aussage im Datensatz, was den einmaligen Fehlschlag verursacht hat.

**Verifikation:** Nicht code-relevant — reines CI-Signal-Issue ohne bleibende Codeänderung.

**Themen:** ci, evaluation, retrieval, automatischer-alarm, transient

---

<a id="issue-408"></a>

## Issue #408 — fix(indexing): Vor #202 indizierte Chunks tragen kein library_id und sind dauerhaft unauffindbar
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:M
- PRs: keine (linkedPRs leer im Datensatz)

**Laut Issue:** Der Issue-Body ist mit „@-" leer; aus dem Titel geht hervor, dass Chunks, die vor Einführung der Bibliotheks-Metadaten (#202) im Vektorspeicher landeten, kein `library_id`-Metadatum tragen und dadurch dauerhaft unauffindbar sind — ein Backfill fehlt.

**Geliefert:** Kein PR ist im Chunk-Datensatz mit #408 verknüpft, das Issue wurde aber tatsächlich erledigt. Git-Historie zeigt PR #412 vom Branch `feature/408_vector-store-bibliotheks-metadaten`, gemerged am 15.08.2026 (Commit `8c01fa52`, Merge `11946dea`), mit dem Titel „fix(indexing): Bibliothekszuordnung in den Chunk-Metadaten nachtragen". Geliefert wurden eine Liquibase-Migration `016-backfill-vector-store-library-metadata.yaml`, eine zugehörige Migrationstestklasse `Migration016VectorStoreLibraryMetadataTest.java` sowie eine Test-Fixture `test-master-through-015.yaml`. Die Verknüpfung zum Issue fehlt im Rohdaten-Export vermutlich, weil der PR-Titel nicht „Closes #408" im erwarteten Format enthielt oder die Verknüpfung anderweitig nicht erfasst wurde — inhaltlich passt PR #412 exakt zum Issue.

**Verifikation:** Die Migration `016-backfill-vector-store-library-metadata.yaml` sowie die Testklasse `Migration016VectorStoreLibraryMetadataTest.java` existieren im heutigen Worktree. Spätere Commits (`613f6ea4`, `346f2c36`) referenzieren weiterhin die Bibliotheks-Metadaten im Retrieval-Harness, was auf dauerhafte Verankerung des Konzepts hindeutet.

**Themen:** retrieval, indexing, migration, vector-store, datenqualität

---

<a id="issue-409"></a>

## Issue #409 — security(frontend): Sicherheits-Header im Webserver ergänzen
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:S, security
- PRs: #670 (2026-08-20)

**Laut Issue:** `frontend/nginx.conf` setzte keinen einzigen Sicherheits-Header (kein CSP, kein `X-Content-Type-Options`, kein `X-Frame-Options`, kein `Referrer-Policy`, `server_tokens` nicht aus). Gefordert waren mindestens diese Header plus Prüfung, dass die CSP zum gebauten Frontend passt (Chat, Dokumentenansicht, Verwaltung, Anmeldung funktionieren weiterhin), dokumentierte Aufteilung der Verantwortung für `Strict-Transport-Security` (vorgelagerter TLS-Terminator statt hier).

**Geliefert:** PR #670 ergänzt CSP, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`, `server_tokens off` in `frontend/nginx.conf`, mit `always` gesetzt im `server`-Block (nicht in `location`) wegen der nginx-Vererbungsfalle. HSTS bewusst ausgeklammert und in `docs/deployment.md` als Anforderung an den vorgelagerten TLS-Terminator dokumentiert. CSP wurde gegen den echten Produktions-Build verprobt (keine Inline-Skripte, `style-src 'unsafe-inline'` wegen MUI/Emotion notwendig, `img-src data: blob:` wegen Logo-Vorschau). Laut PR-Body wurde kein automatisierter Browser-Lauf gegen Verstöße durchgeführt (nur Build-Output-Analyse und Header-Check am laufenden Container) — insofern bleibt ein Abnahmekriterium („Konsole meldet keine Verstöße") nur indirekt belegt, nicht per Playwright-Lauf. 7 von 454 Frontend-Tests schlugen laut PR-Body zum Zeitpunkt fehl, laut Autor unabhängig von der Änderung (keine Datei unter `frontend/src` geändert).

**Verifikation:** `frontend/nginx.conf` enthält im heutigen Worktree CSP, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `server_tokens off` wie im PR beschrieben, allerdings inzwischen mit zusätzlicher Variable `${OPAA_CSP_CONNECT_SRC_EXTRA}` und `object-src 'none'` — offenbar seither weiterentwickelt (nicht Teil dieses PRs, spätere Änderung).

**Themen:** security, deployment, frontend, csp, adr-0004

---

<a id="issue-410"></a>

## Issue #410 — docs: Backlog-Sichtung abschließen und Statusaussage zum Upload berichtigen
- Geschlossen: 2026-08-15 (completed)
- Labels: documentation, size:S
- PRs: #411 (2026-08-15)

**Laut Issue:** Zwei Punkte: (1) `docs/discussions/discussion-backlog-neuausrichtung.md` sollte die vier Kategorietabellen (abgearbeitet) entfernen, den Abschnitt „Lücken" behalten und die Einleitung auf den abgeschlossenen Stand bringen. (2) `docs/STATUS.md` behauptete unter Bereich B fälschlich, „Upload über die Weboberfläche und die REST-API" sei bereits gebaut — tatsächlich gab es keinen `multipart`-Endpunkt und `documents` führte keine einbringende Person.

**Geliefert:** PR #411 entfernt die vier Kategorietabellen aus dem Sichtungsdokument, aktualisiert die Einleitung mit dem Ergebnis jeder Kategorie und belässt den Abschnitt „Lücken" unverändert. `STATUS.md` bekommt unter einer eigenen Überschrift „Nicht gebaut — obwohl es hier lange anders stand" die Korrektur zum Upload. Deckt sich mit dem Issue-Umfang ohne Abweichung.

**Verifikation:** Zum Zeitpunkt der Inventur (nach #420/#422) ist der Upload inzwischen tatsächlich gebaut — `docs/STATUS.md` Zeile 25 und Zeile 99 führen ihn heute korrekt unter „Gebaut" (`POST /api/v1/libraries/{libraryId}/documents`). Die hier vorgenommene Korrektur war also zum damaligen Zeitpunkt richtig und wurde später durch echte Umsetzung (#420) überholt — kein Widerspruch, sondern normale Weiterentwicklung.

**Themen:** doku, projektsetup, backlog, upload

---

<a id="issue-414"></a>

## Issue #414 — ci(eval): evaluateRetrieval führt BaselineRegressionTest ohne Report aus und schlägt fehl
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:S, ci, evaluation
- PRs: #415 (2026-08-15)

**Laut Issue:** Der nächtliche Workflow „Retrieval-Regression" schlug fehl, weil `tasks.register<Test>("evaluateRetrieval")` in `backend/build.gradle.kts` keinen `filter`-Block hatte und dadurch alle Klassen des `evalTest`-Sourcesets ausführte, einschließlich `BaselineRegressionTest` — dessen Wächter „No report found" fehlschlägt, wenn der Report noch nicht erzeugt wurde. Keine echte Retrieval-Regression, reiner Task-Konfigurationsfehler. Gefordert: Ausschluss von `*BaselineRegressionTest` in `evaluateRetrieval`, analog zu `evalUnitTest`, mit erläuterndem Kommentar.

**Geliefert:** PR #415 ergänzt genau diesen Ausschluss (eine Zeile plus Kommentar). Der PR-Body dokumentiert zusätzlich einen zweiten, unabhängigen Blocker, der beim ersten CI-Lauf danach sichtbar wurde: ein zu knapper Awaitility-Timeout (30 Minuten) in `RetrievalEvaluationHarnessTest`, der im Widerspruch zum bereits auf 60 Minuten angehobenen Job-Timeout stand — auf 45 Minuten korrigiert. Beides ist im selben PR/Issue erledigt, obwohl der zweite Teil im ursprünglichen Issue-Text nicht stand (im PR als „Nachtrag" ausgewiesen, keine verdeckte Abweichung).

**Verifikation:** `backend/build.gradle.kts` enthält heute im `evaluateRetrieval`-Task einen Kommentar, der die Rollenverteilung von `evalUnitTest`/`evaluateRetrieval`/`checkRetrievalBaseline` erklärt („Produces the report, and only the report: BaselineRegressionTest is excluded because it consumes …"), passend zur beschriebenen Lieferung.

**Themen:** ci, evaluation, retrieval, gradle, build-konfiguration

---

<a id="issue-416"></a>

## Issue #416 — fix(eval): Zweite Review-Runde zu PR #301 nachreichen — harte Untergrenze, Sechsfachbefund, Baseline-Diff
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:M, ci, evaluation
- PRs: #417 (2026-08-15)

**Laut Issue:** Eine zweite Review-Runde zu PR #301 war als Commit auf dem längst gemergten Branch `feature/228_retrieval-regressionsjob` liegen geblieben und nie nachgereicht worden. Drei Kernbefunde: (1) die harte Untergrenze in `BaselineComparator` (`0,8 × Baselinewert`) konnte nie auslösen und wanderte mit einer erodierenden Baseline mit, statt sie zu verankern; (2) die Dokumentation beschrieb die Toleranzlücke „enger als 1/n" als Einzelfall, obwohl sie sechs Gruppen-/Metrik-Paare betrifft; (3) die Baseline-Absenkungsprüfung (`diff_baseline.py`) lief nur im label-ausgelösten Job und damit nicht verlässlich für jeden PR, der `eval/baseline/**` änderte.

**Geliefert:** PR #417 übernimmt den zwölf Tage alten Commit und passt ihn an den aktuellen Stand an. Harte Untergrenze jetzt `max(relativer Term, fester absoluter Wert)`; Sechsfachbefund mit Zahlen in Javadoc, ADR-0013 und `eval/baseline/README.md` belegt; `diff_baseline.py` in einen eigenen, label-unabhängigen Workflow `.github/workflows/baseline-diff.yml` ausgelagert. Zusätzlich (laut PR-Body „beim Nachziehen angepasst"): ein Testanpassung wegen der zwischenzeitlichen Jackson-3-Migration (Test prüft jetzt Ergebnis statt Exception-Typ) und Konfliktauflösung mit parallel gemergten #311/#414-Änderungen in `retrieval-regression.yml`. Kleinere Punkte aus der Review-Runde (Validierung von `distinctExpectedDocumentSets`, Vollständigkeitsprüfung des Cache-Exports) ebenfalls umgesetzt. Reproduktionsnachweis für den Kernbefund (harte Untergrenze) im PR dokumentiert.

**Verifikation:** Nicht vertieft geprüft (Dateien `BaselineComparator.java`, `.github/workflows/baseline-diff.yml` sind laut Dateiliste angelegt/geändert); die Beschreibung im PR-Body ist detailliert und mit rot/grün-Testnachweis belegt, kein Anlass für Zweifel.

**Themen:** evaluation, ci, retrieval, code-review, baseline, adr-0013

---

<a id="issue-418"></a>

## Issue #418 — feat(library): Bibliotheksliste an die Rechteformel angleichen und die eigene Rolle ausweisen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #425 (2026-08-17)

**Laut Issue:** `GET /api/v1/libraries` listete nur Bibliotheken im Eigentum des Nutzers/seiner Gruppen plus organisationsweite, nicht aber solche mit reinem `AssetGrant` (`VIEWER`/`EDITOR`/`MANAGER`). Divergenz zu `LibraryAccessService.readableLibraryIds`. Gefordert: `KnowledgeLibraryService.listLibraries` an dieselbe Rechteformel angleichen, abgelaufene Grants ausschließen, `myRole` als Pflichtfeld in `LibraryListResponse`/`LibraryResponse` ergänzen, `AssetRole.USER` aus der OpenAPI-Spezifikation entfernen (Backend kannte den Wert seit #330 nicht mehr).

**Geliefert:** PR #425 setzt alle Punkte um: `listLibraries` nutzt `readableLibraryIds` plus neue Batch-Methode `effectiveRolesForReadableLibraries`; `myRole` in beiden Response-Typen, mit dokumentiertem Unterschied im System-Admin-Bypass (Liste bypassed nie zu OWNER, Einzelansicht schon); `AssetRole.USER` aus der Spezifikation entfernt. Der PR-Body dokumentiert zwei im eigenen Review gefundene, zusätzliche Bugs, die vor dem Merge noch behoben wurden: `myRole` konnte durch ungecachte/gecachte Divergenz `null` werden (Pflichtfeld verletzt), gefixt durch Floor auf `VIEWER`. Parity-Test zwischen `listLibraries` und `readableLibraryIds` sowie umfangreiche Integrationstests laut PR-Body vorhanden.

**Verifikation:** `myRole` ist im heutigen `backend/src/main/resources/openapi/opaa-api.yaml` als Pflichtfeld in `LibraryListResponse` vorhanden (Zeilen ~3290–3382), inklusive Beschreibung des Bypass-Unterschieds — passt zur PR-Beschreibung.

**Themen:** spaces, workspace, rechteformel, api, epic-198

---

<a id="issue-419"></a>

## Issue #419 — feat(indexing): Indizierungsläufe zielen auf eine wählbare Wissensbibliothek statt auf die System-Bibliothek
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, frontend, size:M, workspace
- PRs: #431 (2026-08-17)

**Laut Issue:** `FileProcessingService` schrieb hart `SYSTEM_LIBRARY_ID` (Verzeichnis- und URL-Indizierung), die System-Bibliothek ist `PRIVATE` ohne Grants — jedes so indizierte Dokument war für Normalnutzer unauffindbar (Zwischenzustand aus Epic #198). Gefordert: `libraryId` als Pflichtfeld im Trigger-Request, `EDITOR`-Mindestrecht auf der Zielbibliothek, Durchreichen der Zielbibliothek durch die gesamte Indizierungskette, Frontend-Auswahl in `AdminDrawer`, `docs/STATUS.md` aktualisieren.

**Geliefert:** PR #431 setzt den Umfang um, inklusive Migration 019 für `IndexingJob.libraryId`. Der PR-Body dokumentiert eine zweite Review-Runde mit drei blockierenden Befunden, die vor Merge behoben wurden — darunter ein wesentlicher: die `EDITOR`-Prüfung war am einzigen erreichbaren Endpunkt (`/trigger`, `@PreAuthorize SYSTEM_ADMIN`) wirkungslos, weil `effectiveRole` für System-Admins bedingungslos `OWNER` zurückgab und der 403-Zweig damit nie erreicht wurde — behoben durch `systemAdmin=false` bei der `canEdit`-Prüfung, mit dokumentierter Ausnahme für die System-Bibliothek selbst. Ein Follow-up-Issue #433 (gelöschte Zielbibliothek mitten im Lauf) wurde bewusst ausgelagert. `NoHardcodedSystemLibraryAssignmentTest` sichert das Abnahmekriterium „kein Produktionscode weist mehr SYSTEM_LIBRARY_ID zu" testbasiert ab, mit dokumentiertem Rot/Grün-Nachweis.

**Verifikation:** Nicht vertieft geprüft; die im Datensatz mitgelieferte Dateiliste (u. a. `IndexingController.java`, `AsyncIndexingExecutor.java`, `UrlIndexingExecutor.java`, `NoHardcodedSystemLibraryAssignmentTest.java`, `Migration019IndexingJobLibraryTest.java`, `AdminDrawer.tsx`) deckt sich mit dem beschriebenen Umfang.

**Themen:** indexing, spaces, workspace, rechteformel, epic-198

---

<a id="issue-420"></a>

## Issue #420 — feat(upload): Dokumente über die REST-API in eine wählbare Bibliothek hochladen und wieder entfernen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:L, workspace
- PRs: #432 (2026-08-17)

**Laut Issue:** Es gab keinen Upload — kein `multipart`-Endpunkt, kein `MultipartFile`, `documents` ohne einbringende Person. Gefordert: `POST /api/v1/libraries/{libraryId}/documents` (mindestens `EDITOR`, Formatprüfung, Größenobergrenze 50 MB Standard, Dublettenprüfung per Prüfsumme mit 409) und `DELETE .../documents/{documentId}`, `uploaded_by_user_id` an `documents`, `DocumentSourceType.UPLOAD`, sichere Dateiablage ohne Pfaddurchgriff, Standardziel persönliche Bibliothek als Client-Vorauswahl (kein zweiter Serverpfad).

**Geliefert:** PR #432 setzt den vollen Umfang um. Der PR-Body dokumentiert zwei Review-Runden mit insgesamt vier bzw. einem weiteren blockierenden Befund, alle vor Merge behoben — bemerkenswert: Löschen zerstörte ursprünglich fremde Quelldateien (jetzt nur `sourceType == UPLOAD` und Pfad unter dem Upload-Storage-Verzeichnis), und ein Race-Verlierer beim gleichzeitigen Upload derselben Datei hinterließ zunächst verwaiste Chunks im Vektorspeicher (behoben durch früheres Setzen der Prüfsumme und `vectorStore.delete` im Fehlerfall). Pfaddurchgriff durch `../../../../etc/evil.txt`-Test explizit abgesichert. Drei Follow-up-Issues ausgelagert (#434 Rate-Limit, #435 inhaltsbasierte Formaterkennung, #436 403/404-Vereinheitlichung). e2e-CI war laut PR-Body aus standortbedingter Ursache (Playwright-Chrome-Download-Fehler) rot, nicht wegen dieser Änderung.

**Verifikation:** `backend/src/main/resources/openapi/opaa-api.yaml` enthält heute `/api/v1/libraries/{libraryId}/documents` (Zeile 974) und `/api/v1/libraries/{libraryId}/documents/{documentId}` (Zeile 1075); `docs/STATUS.md` führt den Upload heute unter „Gebaut" (Zeile 25, 99–103) mit denselben Details wie im PR beschrieben (`uploaded_by_user_id`, Löschverhalten). Deckt sich vollständig.

**Themen:** upload, spaces, workspace, api, epic-198, sicherheit

---

<a id="issue-421"></a>

## Issue #421 — feat(frontend): Wissensbibliotheken auflisten und verwalten
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, frontend, size:M, workspace
- PRs: #437 (2026-08-17)

**Laut Issue:** Das Frontend kannte Bibliotheken überhaupt nicht (kein API-Aufruf, kein Store, keine Seite), obwohl das Backend den CRUD-Weg seit #201/#202 bereitstellte. Gefordert: Seite „Wissensbibliotheken" nach Vorbild `SpaceManagementPage`/`GroupManagementPage`, Anlegen-Dialog nach Vorbild `CreateSpaceDialog`, Bedienelemente gestaffelt nach `myRole` (Bearbeiten ab MANAGER, Löschen ab OWNER), Gruppen-Eigentümerwahl nur aus Gruppen, in denen der Nutzer tatsächlich Mitglied ist.

**Geliefert:** PR #437 liefert `LibraryManagementPage`, `libraryStore`, `CreateLibraryDialog`, Sidebar-Eintrag und Route `/libraries`. Abweichung/Erweiterung: Laut PR-Body stellte sich im Code-Review heraus, dass das Abnahmekriterium „nur Gruppen anbieten, in denen der Nutzer Mitglied ist" ohne Backend-Änderung nicht erfüllbar war — daraufhin wurde zusätzlich ein neuer, nicht admin-beschränkter Endpunkt `GET /api/v1/me/groups` (`MeController`, `GroupService#listMyGroups`) eingeführt. Damit wurde aus dem reinen Frontend-Issue ein Fullstack-PR. Drei Annahmen/Einschränkungen wurden als Follow-up-Issues ausgelagert: #438 (fehlender aufgelöster Gruppenname und Dokumentanzahl in der Liste), #439 (System-Bibliothek bleibt über die Seite unerreichbar), #440 (weiteres, store-übergreifendes Follow-up).

**Verifikation:** `frontend/src/pages/LibraryManagementPage.tsx` existiert im heutigen Worktree wie im PR beschrieben.

**Themen:** spaces, workspace, frontend, epic-198, gruppen

---

<a id="issue-422"></a>

## Issue #422 — feat(frontend): Dokumente je Wissensbibliothek anzeigen und hochladen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, frontend, size:M, workspace
- PRs: #442 (2026-08-17)

**Laut Issue:** Die Platzhalterseite „Dokumente" (nur Symbol + „Demnächst verfügbar") sollte durch eine echte Ansicht ersetzt werden: Bibliotheksauswahl mit persönlicher Bibliothek als Vorbelegung, Dokumentliste mit Status/Herkunft/Größe/Abschnitten, Upload per Auswahl und Drag-and-drop mit Fehlermeldungen (Format, Größe, Dublette), Löschen ab EDITOR, sichtbare FAILED-Kennzeichnung.

**Geliefert:** PR #442 setzt den vollen Umfang um, ohne Backend-Änderungen — die Endpunkte aus #420 existierten bereits vollständig. Bibliotheksauswahl an angezeigte Bibliothek gebunden statt separatem Zielselektor (kein zweiter Upload-Ziel-Wähler wie im Issue skizziert, sondern nur der Ablagebereich der gerade angezeigten Bibliothek — funktional gleichwertig, aber enger geführt). Polling für PENDING-Dokumente, automatischer Stopp beim Verlassen der Seite. 196/196 Frontend-Tests grün. Ein Multipart-Upload-Testfall wurde bewusst auf Store-/Seiten-Ebene statt gegen den rohen MSW-Handler getestet (jsdom/undici-Limitation).

**Verifikation:** `frontend/src/pages/DocumentsPage.tsx` existiert im heutigen Code **nicht mehr**. Git-Historie zeigt: PR #506 („Bibliotheksdetailseite mit typspezifischem Bereich") hat die eigenständige `DocumentsPage` später durch eine in die Bibliotheksdetailseite integrierte Ansicht ersetzt. Die hier gelieferte Funktionalität (Anzeigen/Hochladen/Löschen von Dokumenten je Bibliothek) lebt heute in `LibraryDetailPage.tsx` weiter, nicht in einer eigenen Seite — der PR-Umfang wurde also durch eine spätere Umstrukturierung abgelöst, inhaltlich aber nicht zurückgenommen.

**Themen:** workspace, spaces, frontend, upload, dokumentverwaltung

---

<a id="issue-423"></a>

## Issue #423 — feat(frontend): Rechte an einer Wissensbibliothek verwalten
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, frontend, size:M, auth, workspace
- PRs: #446 (2026-08-17)

**Laut Issue:** MANAGER einer Wissensbibliothek sollen Freigaben (Grants) einsehen, erteilen, befristen, ändern und entziehen können, mit aufgelösten Namen (nicht UUID) und erklärten Rollen. Technischer Hinweis warnte, dass die Namensauflösung über admin-beschränkte Endpunkte scheitern könnte und das dann ein eigenes Backend-Issue sein müsse.

**Geliefert:** PR #446 liefert die Rechteansicht vollständig. Im Review (Runde 2) zeigte sich genau das im Issue vorhergesehene Problem: `GET /v1/admin/users`/`/v1/admin/groups` sind SYSTEM_ADMIN-only, ein regulärer MANAGER sah nur UUIDs. Statt eines separaten Folge-Issues wurde dies direkt im selben PR behoben — `AssetGrantResponse` bekam serverseitig aufgelöste `subjectDisplayName`/`grantedByDisplayName`-Felder. Zusätzlich behoben: Button „Rechte verwalten" auf der persönlichen Bibliothek ausgeblendet (dort lehnt das Backend jede Vergabe ab), Freitext-Fallback für Gruppen-ID ergänzt. Drei Folge-Issues entstanden: #445 (Personen-/Gruppensuche unabhängig von Systemrolle), #448 (rohe Enum-Namen/fehlende Umlaute in Backend-Fehlermeldungen).

**Verifikation:** `frontend/src/components/LibraryGrantsDialog.tsx` existiert im heutigen Code.

**Themen:** workspace, spaces, auth, grants, rechteverwaltung, frontend

---

<a id="issue-424"></a>

## Issue #424 — test(e2e): Wissensbibliotheken — Upload, Freigabe und rechtebewusste Suche
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, size:M, workspace
- PRs: #453 (2026-08-17)

**Laut Issue:** Sieben E2E-Szenarien für den kompletten Weg hochladen → freigeben → finden, inklusive des wichtigsten Negativfalls (kein Grant → kein Treffer) und Entzugs eines Grants. Kriterium: Szenarien 4/5 müssen nachweislich fehlschlagen, wenn der Rechtefilter der Suche entfernt wird.

**Geliefert:** PR #453 implementiert alle sieben Szenarien in `e2e/tests/knowledge-libraries.spec.ts`. Da der E2E-Stack bislang keinen funktionierenden Embedding-/Chat-Anbieter hatte (`OPAA_OPENAI_BASE_URL` zeigte auf einen Discard-Port, echte Modellbereitstellung ist eigenständiges Issue #256), wurde zusätzlich ein minimaler `ai-stub`-Service (`e2e/ai-stub/server.mjs`) gebaut — fester Embedding-Vektor, Chat-Antwort spiegelt Zitationsmarkierungen. Das ist eine über den Issue-Umfang hinausgehende Zusatzlieferung, ohne die die Szenarien gar nicht hätten laufen können. Ein dritter Testnutzer `dev-outsider` kam hinzu. Der geforderte Nachweis (Filter entfernen → Szenarien 4/5 rot) wurde erbracht und dokumentiert. Ein Folge-Issue #443 (Löschen von FILESYSTEM/HTTP_DIRECTORY-Dokumenten wirkt nur bis zum nächsten Indizierungslauf) wurde im Review gefunden.

**Verifikation:** `e2e/tests/knowledge-libraries.spec.ts` und `e2e/ai-stub/server.mjs` existieren im heutigen Code.

**Themen:** e2e, workspace, spaces, retrieval, auth, testinfrastruktur

---

<a id="issue-426"></a>

## Issue #426 — chore(deployment): Anwendungs-Datenbankaccount nicht als Superuser betreiben, damit audit_log-Schreibschutz greift
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, size:S, security
- PRs: keine

**Laut Issue:** Bei der Umsetzung von #391 (Protokollablage `audit_log`, "nur anfügend auf Datenbankebene") wurde festgestellt, dass das mitgelieferte `docker-compose.yml` seinen einzigen Postgres-Account als Superuser bootstrapt. Migration 017 (`REVOKE`/`GRANT` auf `audit_log`) setzt aber eine nicht-privilegierte Anwendungsrolle voraus — ein Superuser umgeht jede ACL-Prüfung. Gefordert: ein produktives Deployment, das mit einer nicht-Superuser-Rolle verbindet, sodass der Schreibschutz tatsächlich greift.

**Geliefert:** Nicht umgesetzt — und der ursprünglich zu eng geschnittene Umfang ist über vier Nachträge deutlich gewachsen, ohne dass ein Fix folgte. Aus dem Review zu PR #428 (das den eigentlichen ADR-0015-Eigentümertrennungsmechanismus brachte) zeigte sich: (1) Auch eine nicht-privilegierte, aber weiterhin **eigentümerberechtigte** Rolle kann `audit_log` manipulieren; Migration 017 löst das durch eine dedizierte `NOLOGIN`-Rolle `opaa_audit_owner`, verlangt dafür aber, dass das Migrationskonto dauerhaft `CREATEROLE` und `CREATE ON SCHEMA public WITH GRANT OPTION` trägt — was, solange Migrations- und Laufzeitkonto identisch sind, genau die Eskalationsfähigkeit offenlässt, die das Issue schließen sollte. (2) Ein konkreter, empirisch gegen `pgvector/pgvector:pg18` nachgewiesener Eskalationsweg (`GRANT opaa_audit_owner TO <konto> WITH SET TRUE` + zwei Anweisungen, kein `SET ROLE` nötig) hebelt die Eigentümertrennung vollständig aus. (3) Eine bloße nachträgliche Härtung des Laufzeitkontos reicht nicht: Migration 017 selbst schlägt auf einer Neuinstallation im gehärteten Modell fehl (`permission denied to grant role` u. a.), weil sie als bereits ausgeführtes Changeset laut `AGENTS.md` nicht mehr bearbeitet werden darf — es braucht eine eigene Folgemigration. (4) Migration 022 und 023 (spätere DDL-Änderungen an `audit_log`, u. a. aus #393/#395) tragen strukturell dieselbe Abhängigkeit — jede künftige `audit_log`-DDL-Migration ist betroffen, nicht nur 017. Geschlossen im Zuge der Schließung von **Epic #457** (nicht #198, wie bei den übrigen Space/Asset-Issues) als Ticket-Hygiene-Maßnahme: Die Audit-Betriebshärtung ist bewusst zurückgestellt, Zuschnitt wird bei Wiederaufnahme neu bewertet.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/017-audit-log.yaml` sowie `Migration017AuditLogTest`/`Migration022AuditorRoleEventTypesTest` existieren laut Kommentaren (nicht separat gegrept); der beschriebene Eskalationsweg ist laut den Nachträgen durch dedizierte Rot/Grün-Testmethoden dokumentiert, aber die eigentliche Härtung (getrenntes, unprivilegiertes Laufzeitkonto plus lauffähige Neuinstallation) ist nicht erfolgt.

**Themen:** audit, security, deployment, ci

---

<a id="issue-429"></a>

## Issue #429 — Rechtehistorie: Aufbewahrungshöchstdauer und Pseudonymisierung des Personenbezugs
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, security
- PRs: keine

**Laut Issue:** #238 hat die Historisierung von Rechten (`asset_grant_history`, `group_membership_history`, `library_visibility_history`) umgesetzt. `docs/features/security-and-compliance.md` verlangt dafür eine konfigurierbare Aufbewahrungshöchstdauer und eine Pseudonymisierung des Personenbezugs ab Schreibzeitpunkt — beides fehlt. Übergangsweise sind die Subjektspalten `ON DELETE RESTRICT` gegen die Nutzertabelle geschaltet (ADR-0015), was eine Kontolöschung blockiert, solange Rechtehistorie zu diesem Konto existiert.

**Geliefert:** Nichts. Das Issue war Phase 3 des Sammel-Epics #457 ("Audit-Betriebshärtung — Nacharbeiten aus Stage A"). Laut Abschlusskommentar des Epics wurde die gesamte Phase-2/3-Nacharbeit bewusst zurückgestellt ("Ticket-Hygiene, Maintainer-Entscheidung … bekannt, aber ohne offene Tickets, bis das Thema wieder ansteht"), alle Sub-Issues wurden ohne Umsetzung geschlossen. Die `ON DELETE RESTRICT`-Einschränkung aus ADR-0015 bleibt bestehen.

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrantHistory.java` und `backend/src/main/java/io/opaa/group/GroupMembershipHistory.java` existieren unverändert seit #238; kein Retention-/Pseudonymisierungsmechanismus im Code auffindbar.

**Themen:** auth, security, doku, rechtehistorie

---

<a id="issue-430"></a>

## Issue #430 — Rechtehistorie: Verzeichnislauf-Eintrag mit konkretem Sync-Lauf korrelieren
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, security
- PRs: keine

**Laut Issue:** #238 historisiert Gruppenmitgliedschaften, die ein Verzeichnislauf ändert, mit der Ursache `DIRECTORY_SYNC_ADDED`/`DIRECTORY_SYNC_REMOVED`, lässt sich aber nicht auf den konkreten Synchronisationslauf zurückführen — `DirectorySyncStatus` hält nur den jeweils letzten Lauf je Organisation, nicht dessen Historie. Gefordert war eine dauerhafte, referenzierbare Lauf-Kennung je Historieneintrag.

**Geliefert:** Nichts. Sub-Issue von Epic #457, gemeinsam mit den übrigen Phase-2/3-Nacharbeiten bewusst zurückgestellt (siehe Epic-Abschlusskommentar: "Ticket-Hygiene … bekannt, aber ohne offene Tickets, bis das Thema wieder ansteht").

**Verifikation:** `DirectorySyncPlanExecutor` und `GroupMembershipHistory` unverändert; keine Lauf-Kennung in den historisierten Zeilen erkennbar.

**Themen:** auth, security, spaces, rechtehistorie, verzeichnissynchronisation

---

<a id="issue-433"></a>

## Issue #433 — fix(indexing): Gelöschte Zielbibliothek mitten im Lauf sauber behandeln (Warnung statt failed)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #602 (2026-08-20)

**Laut Issue:** Wird die Zielbibliothek eines laufenden Indizierungsauftrags gelöscht, sollte der Lauf eine Warnung protokollieren und die betroffenen Dokumente als `skipped` statt `failed` markieren (analog zur Konnektor-Spezifikation).

**Geliefert:** Der Maintainer hat den Umfang im Issue-Kommentar geändert — statt den Fall im laufenden Job abzufangen, wird das Löschen an der Wurzel verhindert: `KnowledgeLibraryService#deleteLibrary` lehnt das Löschen jetzt mit `409 CONFLICT` ab, solange ein `IndexingJob` mit Status `RUNNING` existiert. Der ursprünglich beschriebene Fall (Warnung/skipped mitten im Lauf) kann damit regulär nicht mehr eintreten — es ist eine andere, aber sachlich gleichwertige Lösung des Grundproblems, keine Umsetzung des ursprünglichen Abnahmekriteriums „skipped statt failed". Reproduktionsnachweis über Unit- und Integrationstest erbracht. Verwandter, nicht mitgelöster Punkt: #501 (hängengebliebene RUNNING-Jobs könnten eine Bibliothek dauerhaft blockieren).

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` existiert im heutigen Code.

**Themen:** indexing, spaces, backend, fehlerbehandlung

---

<a id="issue-434"></a>

## Issue #434 — feat(upload): Rate-Limit und/oder asynchrone Verarbeitung für den Dokument-Upload-Endpunkt
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M
- PRs: #589 (2026-08-20)

**Laut Issue:** Der Upload-Endpunkt verarbeitet Parsen/Embedding synchron im Request-Thread und ist von keinem Rate-Limit erfasst — ein EDITOR könnte damit unverhältnismäßig viele Threads/Verbindungen belegen. Zur Wahl standen ein Rate-Limit-Präfixeintrag und/oder asynchrone Verarbeitung.

**Geliefert:** Maintainer-Entscheidung für die asynchrone Variante statt eines zusätzlichen Rate-Limits. Der Endpunkt validiert weiterhin synchron (Format, Größe, Dedup), legt die Dokumentzeile sofort mit Status `PENDING` an und verarbeitet Parsen/Embedding danach asynchron über die bestehende Executor-Infrastruktur (`FileProcessingService#processUploadedFileAsync`). Ein Fehler landet als `FAILED` mit neuer Spalte `documents.error_message` statt einer synchronen 4xx-Antwort; `EmptyDocumentContentException` entfällt dadurch. Migration 036 ergänzt die Spalte.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java` existiert im heutigen Code.

**Themen:** upload, backend, performance, indexing

---

<a id="issue-435"></a>

## Issue #435 — feat(upload): Inhaltsbasierte Formaterkennung für nutzerkontrollierte Uploads
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #577 (2026-08-20)

**Laut Issue:** Der Upload-Endpunkt entscheidet nur über die Dateiendung, nicht über den tatsächlichen Inhalt. Anders als bei betriebsverwalteten Archiven (#404, wo Endungslogik bewusst bleibt) ist der Upload-Inhalt vollständig nutzerkontrolliert — eine als `.pdf` benannte Binärdatei wird ohne Prüfung angenommen. Gefordert: Inhaltserkennung mit deutscher Fehlermeldung bei Abweichung, begrenzt auf den Upload-Pfad.

**Geliefert:** PR #577 setzt genau das um — Tika-Magic-Byte-Erkennung (`Tika#detect`) gegen die behauptete Endung, `400` bei Widerspruch. Toleranz für Text-Formate (`.md`/`.txt`) über `MediaTypeRegistry#isInstanceOf(text/plain)`. Strikte Formate (`.pdf`/`.doc`/`.docx`/`.pptx`) verlangen konkrete Medientypen statt generischer Tika-Fallback-Typen — verhindert, dass unklassifizierbare OLE2-Dateien durchrutschen. Betriebswege (Verzeichnis/URL) bewusst unverändert gelassen, wie im Issue gefordert. Schadsoftwareprüfung bleibt wie vorgesehen außerhalb des Umfangs. Reproduktionsnachweis mit rotem/grünem Testlauf erbracht.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/SupportedDocumentFormats.java` existiert im heutigen Code.

**Themen:** upload, security, backend, formaterkennung

---

<a id="issue-436"></a>

## Issue #436 — fix(library): 403-vs-404-Unterscheidung bei fehlendem Zugriff auf Bestands-Endpunkten vereinheitlichen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S
- PRs: #608 (2026-08-20)

**Laut Issue:** Die Upload-Endpunkte (#420) unterscheiden bereits 404 (kein Zugriff) von 403 (zu wenig Zugriff), die restlichen Bibliotheks-Endpunkte (`getLibrary`, `listDocuments`, `updateLibrary`, `deleteLibrary`, Grants) liefern einheitlich 403 und verraten damit die Existenz einer Bibliothek gegenüber Nutzern ohne jeden Zugriff.

**Geliefert:** PR #608 vereinheitlicht dies über einen neuen gemeinsamen Baustein `LibraryAccessService#requireRole(library, userId, systemAdmin, required)` — 404 bei fehlender Rolle, sonst 403. Angewendet auf `getLibrary`, `updateLibrary`, `deleteLibrary`, `listDocuments`, `AssetGrantService#requireManageable` und `LibraryDocumentService#requireEditable` (dort jetzt Delegation statt Duplikat). Reproduktionsnachweis erbracht; bestehende Tests, die 403 für „kein Zugriff" erwarteten, wurden auf 404 korrigiert. Umsetzung entspricht vollständig dem im Issue skizzierten Vorschlag.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryAccessService.java` existiert im heutigen Code.

**Themen:** auth, backend, spaces, existenzverschleierung, api-konsistenz

---

<a id="issue-438"></a>

## Issue #438 — feat(frontend): Eigentümername und Dokumentanzahl in LibraryListResponse ausweisen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, workspace
- PRs: #601 (2026-08-20)

**Laut Issue:** `LibraryListResponse` sollte um `ownerName` (aufgelöster Gruppen-/Nutzername) und `documentCount` ergänzt werden, damit `LibraryManagementPage` in der Liste nicht mehr generisch „Gruppen-Bibliothek" anzeigt und die Dokumentanzahl bereits eingeklappt sichtbar ist.

**Geliefert:** PR #601 setzt nur den `ownerName`-Teil um. Laut PR-Beschreibung war der `documentCount`-Teil zum Zeitpunkt des PRs bereits anderweitig umgesetzt und wurde in der Liste bereits angezeigt — der PR ergänzt ausschließlich das fehlende `ownerName`-Feld (OpenAPI-Erweiterung, gebündelte Auflösung ohne N+1 in `KnowledgeLibraryService#listLibraries`, Fallback auf generische Bezeichnung bei fehlendem Namen). Vollständige Erfüllung des Issues, nur mit geteilter Historie der beiden Teilaspekte.

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` und `frontend/src/pages/LibraryManagementPage.tsx` existieren im heutigen Code.

**Themen:** workspace, spaces, frontend, backend, api

---

<a id="issue-439"></a>

## Issue #439 — feat(frontend): SYSTEM-Bibliothek über die Oberfläche administrierbar machen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M, workspace
- PRs: keine

**Laut Issue:** Die SYSTEM-Bibliothek erscheint für System-Admins nicht in `GET /api/v1/libraries`, weil ihr ein `AssetGrant` fehlt. Gefordert war zu klären, ob `listLibraries` sie zusätzlich ausliefert oder ein separater admin-Endpunkt sie zugänglich macht, plus entsprechende Frontend-Darstellung.

**Geliefert:** Nicht umgesetzt — laut Issue-Kommentar des Maintainers obsolet geworden: „Die System-Wissensbibliothek wird entfernt (Entscheidung des Maintainers, siehe #521). Eine Administrationsoberfläche dafür wird damit nicht mehr benötigt." Das Konzept `SYSTEM` als Eigentümerart wurde durch #521 vollständig aus dem Modell entfernt statt administrierbar gemacht — der gegenteilige Weg zum ursprünglich vorgeschlagenen.

**Verifikation:** Der Javadoc-Kommentar in `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` bestätigt dies ausdrücklich: „A third owner kind, SYSTEM, existed from #201 until #521 [...] Every library now has a real owner". `LibraryOwnerType.SYSTEM` existiert im heutigen Code nicht mehr.

**Themen:** workspace, spaces, backend, modellierung, rueckbau

---

<a id="issue-440"></a>

## Issue #440 — fix(frontend): Space-, Gruppen- und Bibliotheks-Store beim Logout zurücksetzen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #574 (2026-08-20)

**Laut Issue:** `authStore.ts` reset beim Logout nur `spaceStore`. `groupStore` und `libraryStore` haben zwar `reset()`, es wird aber nie aufgerufen — bei Nutzerwechsel im selben Tab bleiben fremde Daten sichtbar. Vorschlag: gemeinsame Registrierung statt Einzelimporte.

**Geliefert:** PR #574 geht über den Issue-Umfang hinaus: Neben `spaceStore`/`groupStore`/`libraryStore` wurden zusätzlich `chatStore`, `chatListStore`, `documentStore`, `indexingStore` und `grantStore` geprüft und einbezogen — `chatStore` und `indexingStore` bekamen dabei überhaupt erst eine `reset()`-Aktion. Die im Issue vorgeschlagene gemeinsame Registrierung wurde als `frontend/src/stores/resettableStores.ts` umgesetzt. `uiStore` bewusst ausgenommen (Geräteeinstellungen, keine Sitzungsdaten). Reproduktionsnachweis mit rotem/grünem Testlauf erbracht.

**Verifikation:** `frontend/src/stores/resettableStores.ts` existiert im heutigen Code.

**Themen:** frontend, auth, logout, spaces, workspace, statemanagement

---

<a id="issue-441"></a>

## Issue #441 — fix(library): createLibrary prüft Group#isDissolved() nicht vor dem Anlegen des Eigentümer-Grants
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #599 (2026-08-20)

**Laut Issue:** `KnowledgeLibraryService#createLibrary` legte den Eigentümer-Grant für gruppen-eigene Bibliotheken direkt über `grantRepository.save(...)` an, ohne zu prüfen, ob die Zielgruppe aufgelöst ist. `AssetGrantService#requireGrantableGroup` lehnt genau diesen Fall bei jeder anderen Grant-Vergabe bereits ab; `createLibrary` umging die Prüfung, weil es den Grant selbst schrieb. Gefordert war, `requireGrantableGroup` (oder eine gleichwertige Prüfung) vor dem Schreiben des Grants aufzurufen, mit einem Test für den Fall einer aufgelösten Gruppe als `ownerId`.

**Geliefert:** `requireGrantableGroup` wurde package-private gemacht und von `createLibrary` vor dem Schreiben des Gruppen-Grants wiederverwendet statt dupliziert — 400 mit der bestehenden deutschen Meldung. Neuer Test `createGroupOwnedLibraryRejectsADissolvedGroupAsOwner` in `KnowledgeLibraryServiceIntegrationTest`. Reproduktionsnachweis im PR belegt: Test schlägt ohne Fix fehl (`AssertionError: Expecting code to raise a throwable`), besteht mit Fix. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrantService.java` und `KnowledgeLibraryService.java` existieren im heutigen Stand des Worktrees unverändert an ihrem Ort; die im PR genannten Testdateien sind ebenfalls vorhanden. Kein tieferes Review vorgenommen.

**Themen:** library, grants, spaces, bugfix, backend

---

<a id="issue-443"></a>

## Issue #443 — fix(library): Löschen von FILESYSTEM-/HTTP_DIRECTORY-Dokumenten wirkt nur bis zum nächsten Indizierungslauf
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, frontend, size:S
- PRs: keine

**Laut Issue:** Der Löschen-Knopf auf der Dokumentenseite (`DocumentsPage.tsx`) wurde für alle Herkünfte gleich angezeigt, obwohl das Löschen bei `FILESYSTEM`- und `HTTP_DIRECTORY`-Dokumenten nur scheinbar dauerhaft war — der nächste Indizierungslauf legt dieselbe Dokumentzeile erneut an, weil nur bei `UPLOAD` die Quelldatei entfernt wird. Vorgeschlagen wurden zwei Richtungen: entweder ein Hinweistext/Ausblenden in der Oberfläche, oder ein echter Lebenszyklus-Übergang „ausgeschlossen" im Backend, zur Entscheidung durch Product Manager/Maintainer.

**Geliefert:** Kein PR verknüpft. Die im Issue genannte Datei `frontend/src/pages/DocumentsPage.tsx` existiert im heutigen Stand nicht mehr — sie wurde mit PR #506 („feat(frontend): Bibliotheksdetailseite mit typspezifischem Bereich") durch `LibraryDetailPage.tsx` abgelöst, und mit PR #503 („feat(library): Upload nur in UPLOAD-Bibliotheken und Löschverhalten für Konnektorbibliotheken") wurde offenbar das Löschverhalten für Konnektorbibliotheken grundsätzlich neu geregelt. Das Issue wurde damit vermutlich durch die größere Bibliotheks-Restrukturierung überholt statt gezielt behoben — ohne verknüpften PR lässt sich aus den vorliegenden Daten nicht sicher sagen, ob der ursprüngliche Fehler in dieser Restrukturierung mitgelöst wurde oder das Issue nur gegenstandslos wurde, weil die betroffene Seite verschwand.

**Verifikation:** `frontend/src/pages/DocumentsPage.tsx` ist im Worktree nicht auffindbar; `git log` zeigt PR #506 und PR #503 als letzte Änderungen an diesem Pfad, danach vermutlich gelöscht/umbenannt. `LibraryDetailPage.tsx` existiert stattdessen. Ob das Löschverhalten für Nicht-UPLOAD-Dokumente heute klar kommuniziert ist, wurde nicht tiefer geprüft (außerhalb des kostengünstigen Rahmens dieser Verifikation).

**Themen:** library, indexing, frontend, dokumenten-lebenszyklus, ungeklärt

---

<a id="issue-445"></a>

## Issue #445 — Berechtigungsunabhängige Nutzersuche für die Rechtevergabe (Grants)
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: keine im Chunk verknüpft — tatsächlich geliefert über #778 (2026-08-23)

**Laut Issue:** Ein `MANAGER` ohne Systemrolle kann in der Rechteverwaltung einer Wissensbibliothek keine Personen auswählen, um ihnen eine Freigabe zu erteilen, weil `GET /api/v1/admin/users` administrativ geschützt ist. Gefordert war ein berechtigungsunabhängiger Endpunkt (analog `GET /api/v1/me/groups`), der angemeldeten Nutzern eine Suche/Liste von Personen der eigenen Organisation erlaubt, sowie die Umstellung von `LibraryGrantsDialog` darauf.

**Geliefert:** Die Verknüpfung im Chunk-Datensatz ist unvollständig — der Issue-Datensatz selbst trägt keinen PR. Laut Abschlusskommentar von Epic #458 ("Zuletzt geliefert: #445 … erledigt durch #777/#778") und eigener Prüfung: PR #778 ("fix(workspace): Mitgliederauswahl für alle Nutzer, Standard-Space-Formular, Eigentümer-Badge", gemergt 2026-08-23) hat `UserSearchController` (`GET /api/v1/users`) eingeführt — org-beschränkte Personensuche, nur `id`/`email`/`displayName`, serverseitig gefiltert (min. 2 Zeichen, max. 20 Treffer). `LibraryGrantsDialog` nutzt seither `useUserSearch`/`getUserSummaries`; der Freitext-UUID-Fallback bleibt nur als Ausweichlösung für Fehlerfälle. #777 ist im Repo keine PR-Nummer (vermutlich ein Issue oder Tippfehler im Kommentar).

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserSearchController.java` existiert im Worktree. `frontend/src/components/LibraryGrantsDialog.tsx` importiert `useUserSearch` und `getUserSummaries` (Zeile 38, 114).

**Themen:** auth, spaces, rechtevergabe, frontend

---

<a id="issue-447"></a>

## Issue #447 — fix(audit): DENIED-Erfassung auf weitere Ablehnungspfade in AssetGrantService ausweiten
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, security
- PRs: keine

**Laut Issue:** #392 (PR #444) hat `outcome=DENIED` nur für den Fall verdrahtet, den die Spezifikation wörtlich nennt (Selbsterhöhungsversuch in `AssetGrantService#upsertGrant`). Gefordert war, weitere Ablehnungspfade (403 aus `requireManageable`, 403/409 aus `revokeGrant`, 409-Pfad aus `upsertGrant`s Last-OWNER-Schutz) ebenfalls mit `DENIED`-Einträgen zu versehen, samt Tests je Pfad.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), zusammen mit den übrigen Nacharbeiten bewusst zurückgestellt (Epic-Abschlusskommentar: "bekannt, aber ohne offene Tickets, bis das Thema wieder ansteht").

**Verifikation:** `grep -n "DENIED" backend/src/main/java/io/opaa/library/AssetGrantService.java` liefert 5 Treffer — konsistent mit dem im Issue beschriebenen Ist-Stand (nur der eine Selbsterhöhungsfall), keine Ausweitung erkennbar.

**Themen:** security, auth, audit, spaces

---

<a id="issue-448"></a>

## Issue #448 — Deutsche Fehlermeldungen im Grants-Backend: rohe Enum-Namen und fehlende Umlaute
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #576 (2026-08-20)

**Laut Issue:** Nutzerseitige Fehlermeldungen in `AssetGrantService` enthielten rohe Enum-Namen (`OWNER`, `MANAGER`) statt deutscher Rollenbezeichnungen sowie umlautfreie Schreibweisen (`persoenliche`, `koennen`, `aendern`). Gefordert war eine serverseitige Mapping-Funktion analog zu `assetRoleLabel` im Frontend, plus eine kurze Durchsicht benachbarter Meldungen in `KnowledgeLibraryService`/`LibraryDocumentService`.

**Geliefert:** Neue private Zuordnung `roleLabel(AssetRole)` in `AssetGrantService`, liefert deutsche Rollenbezeichnungen (Betrachter/Bearbeiter/Verwalter/Eigentümer). In einer zweiten Review-Runde wurden zwei zunächst übersehene Meldungen (Last-active-Owner-Guard bei `revokeGrant`/`upsertGrant`) sowie die zeichengenau nachgezogenen MSW-Mocks in `frontend/src/mocks/handlers.ts` korrigiert. Abweichung vom Issue: Die im Issue erwähnten Meldungen zur „persönlichen Bibliothek" existierten laut PR-Beschreibung im aktuellen Stand von `AssetGrantService.java` gar nicht mehr, da persönliche Bibliotheken mit #522 bereits abgeschafft wurden — dieser Teil des Issues war zum Zeitpunkt der Umsetzung bereits gegenstandslos. Die geforderte Durchsicht von `KnowledgeLibraryService`/`LibraryDocumentService` wird im PR-Body nicht ausdrücklich erwähnt, laut Dateiliste wurden nur `AssetGrantService.java`, der zugehörige Test und `handlers.ts` geändert.

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrantService.java` existiert im heutigen Stand. Reproduktionsnachweis im PR mit konkreten roten Testläufen für beide Runden dokumentiert.

**Themen:** library, grants, fehlermeldungen, i18n, backend

---

<a id="issue-451"></a>

## Issue #451 — fix(audit): Schutz gegen Fluten der Protokollablage durch wiederholte abgewiesene Audit-Zugriffe
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, security
- PRs: keine

**Laut Issue:** PR #450 (#394) lässt jeden — auch abgewiesenen — Zugriffsversuch auf `/api/v1/audit/events/*` einen `DENIED`-Eintrag schreiben, unabhängig von der Rolle des Aufrufers. Ein Skript mit gewöhnlichem Nutzerkonto kann dadurch beliebig viele unlöschbare `DENIED`-Einträge erzeugen und das Protokoll fluten. Gefordert war ein Rate-Limit oder eine Zusammenfassung wiederholter Versuche desselben Kontos/Zeitfensters.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), bewusst zurückgestellt zusammen mit den übrigen Nacharbeiten.

**Verifikation:** Kein Grep-Treffer im Zeitrahmen dieser Prüfung auf ein Rate-Limit für die Audit-Lese-Endpunkte; keine tiefergehende Prüfung vorgenommen, da als "not planned" geschlossen und laut Epic-Kommentar unverändert offen.

**Themen:** security, audit, backend

---

<a id="issue-452"></a>

## Issue #452 — fix(audit): Bindungsfehler an Audit-Endpunkten ebenfalls ueber den Selbstprotokoll-Trichter fuehren
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, security
- PRs: keine

**Laut Issue:** PR #450 protokolliert jeden Zugriff auf die Audit-Endpunkte, aber nur, wenn die Anfrage Spring MVCs Parameterbindung passiert — `reason` ist bewusst optional gebunden, `from`/`to`/`objectType`/`eventType`/`page`/`size` dagegen als echte Typen. Eine unparsebare Angabe bei einem dieser Parameter führt zu 400, bevor `AuditQueryService` erreicht wird, und erzeugt deshalb keinen `audit_log`-Eintrag — inkonsistent mit dem für `reason` bereits abgedeckten Fall.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), bewusst zurückgestellt.

**Verifikation:** Keine Codeänderung im `AuditController`/`AuditQueryService`-Bereich zu diesem Thema erkennbar; als "not planned" konsistent mit Epic-Abschluss.

**Themen:** security, audit, backend

---

<a id="issue-455"></a>

## Issue #455 — chore(audit): Partitionshorizont von audit_log rechtzeitig verlängern
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, size:S, security
- PRs: keine

**Laut Issue:** Migration 017 (#391) legt beim Anwenden einen festen Horizont von Monatspartitionen für `audit_log` an (drei Monate zurück bis 16 Jahre in die Zukunft, ohne DEFAULT-Partition). Ohne Nachprovisionierung schlägt nach rund 16 Jahren jeder `INSERT` fehl. Gefordert war ein Mechanismus (Migration und/oder Scheduler, analog `AuditRetentionScheduler` aus #395), der den Horizont rechtzeitig verlängert.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), bewusst zurückgestellt. Bei einem 16-Jahre-Puffer ist das betrieblich unkritisch für die aktuelle Projektphase.

**Verifikation:** Keine tiefergehende Prüfung vorgenommen — Betriebsrisiko liegt weit in der Zukunft, Rückstellung ist nachvollziehbar dokumentiert.

**Themen:** security, audit, backend, doku

---

<a id="issue-456"></a>

## Issue #456 — fix(api): Unbekannte Pfade liefern 500 statt 404 und erzeugen einen ERROR-Stacktrace
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S
- PRs: #802 (2026-08-23)

**Laut Issue:** Eine Anfrage an einen von keinem Controller bedienten Pfad wurde mit 500 statt 404 beantwortet, weil Springs `NoResourceFoundException` unbehandelt in den generischen Auffangzweig `handleGenericException` lief — inklusive vollem Stacktrace auf ERROR-Ebene, auch für automatisierte Scanner-Anfragen wie `/wp-admin` oder `/.env`. Erwartet war 404 im gewohnten `ErrorResponse`-Format, protokolliert höchstens auf DEBUG ohne Stacktrace.

**Geliefert:** Genau wie gefordert. PR #802 fügt `@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})` in `GlobalExceptionHandler` hinzu, liefert 404 mit deutscher Meldung und protokolliert nur auf DEBUG ohne Stacktrace. Nachbesserung nach Review: Die DEBUG-Zeile läuft jetzt durch `errorSanitizer.sanitize(...)` (Konsistenz mit den AI-Handlern), und der neue Test prüft zusätzlich den deutschen Fehlertext. Der im Issue angerissene Grundsatzumbau des Auffangzweigs (generelle Aufrufer- vs. Serverfehler-Unterscheidung) blieb bewusst außerhalb des Umfangs. Reproduktionsnachweis erbracht: Test schlug vor dem Fix mit `Status expected:<404> but was:<500>` fehl.

**Verifikation:** `backend/src/main/java/io/opaa/api/GlobalExceptionHandler.java` enthält den `@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})` (Zeile 259) mit begleitendem Javadoc-Kommentar zur Herkunft der Ausnahme.

**Themen:** backend, api, fehlerbehandlung

---

<a id="issue-457"></a>

## Issue #457 — Epic: Audit-Betriebshärtung — Nacharbeiten aus Stage A
- Geschlossen: 2026-08-24 (completed)
- Labels: epic, backend, security
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Sammel-Epic für die Betriebs- und Härtungs-Nacharbeiten aus den Reviews der Audit-Serie #391–#395 und der Rechtehistorie #238. Drei Phasen: Fundament schließen (#426, ADMIN-OPTION-Eskalationsweg), Lücken im laufenden Betrieb (#455, #451, #447, #452), Rechtehistorie nachschärfen (#429, #430).

**Geliefert:** Die Arbeit steckt nicht in Sub-Issues, weil keines davon tatsächlich umgesetzt wurde. Laut Epic-Abschlusskommentar wurde das Epic als "Ticket-Hygiene"-Maßnahme geschlossen, ohne die fachliche Härtung umzusetzen: "Die Nacharbeiten aus Stage A sind bewusst zurückgestellt — bekannt, aber ohne offene Tickets, bis das Thema wieder ansteht." Alle sieben Sub-Issues (#426, #455, #451, #447, #452, #429, #430) wurden mit Verweis darauf geschlossen, sechs davon (alle außer #426, das nicht in diesem Chunk enthalten ist) als "not planned". Bei Wiederaufnahme soll neu geplant werden; Berührungspunkte mit Epic #826 Phase 3 (Audit-Builder/Domain-Events) sind vermerkt.

**Verifikation:** Kein zusätzlicher Code-Check nötig — der Abschlusskommentar bestätigt explizit, dass es sich um einen reinen Rückstellungs-Schluss ohne Lieferung handelt.

**Themen:** security, audit, agenten-organisation, epic, ticket-hygiene

---

<a id="issue-458"></a>

## Issue #458 — Epic: Nacharbeiten Wissensbibliotheken und Upload
- Geschlossen: 2026-08-23 (completed)
- Labels: epic, backend, frontend
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Sammel-Epic für die Nacharbeiten aus den Reviews der Wissensbibliotheks- und Upload-Serie #418–#424. Drei Phasen: Korrektheit (#443, #441, #433, #440), Sicherheit/Robustheit des Upload-Pfads (#435, #434, #436), Bedienbarkeit (#445, #438, #439, #448).

**Geliefert:** Die Arbeit steckt in den Sub-Issues. Laut Abschlusskommentar sind alle Sub-Issues geschlossen; zuletzt geliefert wurde #445 (berechtigungsunabhängige Nutzersuche, erledigt durch PR #778 — siehe eigener Baustein issue-445.md). #520 (Ordner in Dokumentbibliotheken) wurde auf Maintainer-Entscheidung aus dem Epic-Umfang herausgelöst und als eigenständiges Issue weitergeführt (siehe issue-520.md) — es blockierte den Epic-Abschluss nicht. Die übrigen Sub-Issues (#443, #441, #433, #440, #435, #434, #436, #438, #439, #448) liegen außerhalb dieses Chunks und wurden hier nicht im Detail geprüft.

**Verifikation:** Für #445 bestätigt (siehe issue-445.md); für #520 bestätigt (siehe issue-520.md, sechs Sub-Issues #819–#824 vollständig gemergt). Die übrigen genannten Sub-Issues wurden im Rahmen dieses Delta-Chunks nicht einzeln nachgeprüft, da sie nicht Teil der zugewiesenen Issue-Liste sind.

**Themen:** spaces, wissensbibliotheken, upload, epic, ordner

---

<a id="issue-459"></a>

## Issue #459 — docs(agents): UX-Designer-Rolle in der Agenten-Organisation einführen
- Geschlossen: 2026-08-17 (completed)
- Labels: documentation, enhancement
- PRs: #460 (2026-08-17)

**Laut Issue:** Mit der Library-/Upload-Serie war viel nutzerseitige Oberfläche entstanden, ohne dass Dialogaufbau, Fehlertexte und Begriffe von einer eigenen Rolle verantwortet wurden. Gefordert war ein Rollenvertrag `agents/roles/ux-designer.md` (Interaktionskonzepte vor Implementierung, Begriffs-/Textkonventionen, UX-Review nach Merge, ohne Produktivcode) sowie Aufnahme der Rolle in `docs/AGENT-ORGANIZATION.md`. Provider-Adapter (`.claude/agents/` etc.) und der Glossaraufbau waren ausdrücklich außerhalb des Umfangs.

**Geliefert:** Genau wie gefordert — `agents/roles/ux-designer.md` neu angelegt, `docs/AGENT-ORGANIZATION.md` um Rollentabelle und Agenten-Definitionen ergänzt. Keine Abweichung; PR merkt an, dass die Annahme der Rolle eine offene Organisationsentscheidung des Maintainers ist.

**Verifikation:** `agents/roles/ux-designer.md` existiert im heutigen Stand des Worktrees. Ob ein Provider-Adapter (`.claude/agents/ux-designer.md` o. ä.) inzwischen ergänzt wurde, wurde nicht geprüft — das wäre ohnehin ein separater Schritt gewesen.

**Themen:** agenten-organisation, doku, ux, rollenvertrag

---

<a id="issue-461"></a>

## Issue #461 — Roadmap-Meilenstein 1 (31.08.2026) in Produktvision aufnehmen
- Geschlossen: 2026-08-18 (completed)
- Labels: documentation, size:S
- PRs: #462 (2026-08-18)

**Laut Issue:** Ergänzung von `docs/VISION.md` um einen Abschnitt „Roadmap" mit dem ersten datierten Meilenstein (31.08.2026): UI-Redesign, Anlegen von Wissensdatenbanken (Konnektoren + Upload), Testsystem mit erweitertem Testkorpus, Aufstellung der bisher implementierten Leistungen. Bewusste Einschränkung: volle Berechtigungsproblematik folgt später. Zusätzlich eine Backlog-Notiz zu mehreren gleichzeitig nutzbaren OIDC-Anbietern in `docs/features/access-control.md`.

**Geliefert:** Wie gefordert umgesetzt — Roadmap-Abschnitt in `docs/VISION.md` mit Meilenstein, Einschränkung und Arbeitsteilung (bugpuritz: UI-Design, criew: Wissensbibliotheken/Konnektoren), plus die OIDC-Backlog-Notiz in `docs/features/access-control.md`. Keine Abweichung.

**Verifikation:** `docs/VISION.md` existiert im heutigen Stand des Worktrees. Inhaltliche Prüfung, ob der Meilenstein zum 31.08.2026 inzwischen erreicht wurde, ist Gegenstand der Gesamtinventur, nicht dieses Bausteins.

**Themen:** doku, produktvision, roadmap, auth, oidc

---

<a id="issue-463"></a>

## Issue #463 — Epic: Quellentypen erweiterbar machen und RSS-Feeds erschließen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, epic, backend
- PRs: keine (Epic, geschlossen über Sub-Issues)

**Laut Issue:** Epic mit zwei Zielen — den Quellentyp der Indizierung von einer festverdrahteten `if`-Unterscheidung (URL gesetzt vs. nicht) zu einer ausdrücklichen, erweiterbaren Registry machen, und als ersten Praxistest dieser Erweiterbarkeit RSS-Feeds erschließen. Geplant in drei Phasen (Modell/ADR, RSS-Typ+Parser+Executor, Anlagen+Oberfläche) mit Abhängigkeitskette #464→#465→#466→#467→#468→(#469 Frontend, #470 Doku). Ausdrücklich außerhalb des Umfangs: Zeitplan/Scheduling für Läufe, Zielprüfung gegen private Adressbereiche (#267), Zuordnung Quelle↔Bibliothek (#207), Speicherung von Quellkonfigurationen, weitere Quellentypen.

**Geliefert:** Kein eigener PR — das Epic wurde als Sammelticket über seine Sub-Issues #464 (ADR-0017), #465 (Registry/Executor-Umbau), #466 (RSS_FEED-Typ + Parser), #467 (RSS-Indizierungslauf) und #468 (Anlagen + GSB-Profil) abgearbeitet, die alle einzeln als „completed" mit PR verknüpft sind (siehe jeweilige Bausteine). Die im Epic genannten Folgeschritte #469 (Frontend-Wahl des Quellentyps) und #470 (Doku-Nachführung) sind in diesem Chunk nicht enthalten und wurden hier nicht geprüft — laut Abnahmekriterium „Systemverwaltung kann Quellentyp in der Oberfläche wählen" wäre das Epic ohne #469 nicht vollständig erreicht; ob #469/#470 tatsächlich umgesetzt wurden, muss anderswo in der Inventur festgestellt werden.

**Verifikation:** Alle für die Phasen 1–3 genannten Kernartefakte existieren im heutigen Worktree: `docs/decisions/0017-quellentypmodell-indizierung.md`, `backend/src/main/java/io/opaa/indexing/IndexingSourceType.java`, `RssFeedParser.java`, `RssFeedIndexingExecutor.java`, `AttachmentProfile.java`. Ob die Oberfläche (#469) den Quellentyp tatsächlich wählbar macht, wurde hier nicht verifiziert.

**Themen:** epic, indexing, rss, konnektoren, erweiterbarkeit, adr

---

<a id="issue-464"></a>

## Issue #464 — docs(decisions): ADR zum Quellentypmodell der Indizierung
- Geschlossen: 2026-08-18 (completed)
- Labels: documentation, backend, size:S
- PRs: #472 (2026-08-18)

**Laut Issue:** Phase 1 des Epics #463 — ein ADR, der festlegt, wie ein Quellentyp der Indizierung künftig ausgewählt, registriert und mit typspezifischer Konfiguration versehen wird, insbesondere die typabhängige Behandlung verschwundener Dokumente (Löschabgleich bei Verzeichnissen sinnvoll, bei Feeds nicht) ausdrücklich entscheidet statt nur zu erwähnen. Verweis von `docs/features/knowledge-sources.md` auf den ADR gefordert.

**Geliefert:** ADR-0017 (`docs/decisions/0017-quellentypmodell-indizierung.md`) angelegt, `docs/features/knowledge-sources.md` verweist darauf. Laut PR-Beschreibung deckt der ADR die geforderten Punkte ab (Auswahl, Registrierung, Konfiguration, Löschverhalten). Keine Abweichung erkennbar.

**Verifikation:** `docs/decisions/0017-quellentypmodell-indizierung.md` existiert im heutigen Stand. Inhaltliche Tiefenprüfung der ADR-Entscheidungen nicht vorgenommen (außerhalb des Rahmens dieser Verifikation).

**Themen:** adr, indexing, doku, architektur, rss

---

<a id="issue-465"></a>

## Issue #465 — refactor(indexing): Quellentyp ausdrücklich übergeben und Executor über eine Registry auflösen
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:M
- PRs: #473 (2026-08-18)

**Laut Issue:** Phase 1 (Umbau) des Epics #463 — Interface für den Indizierungsweg, Registry zur Auflösung des Executors über den Typ, `sourceType` als optionales Feld im Anstoß eines Laufs (Rückfall auf bisherige Ableitung bei fehlendem Feld), Widerspruchsprüfung bei inkonsistenten Feldern, Zusammenführung der duplizierten `reportRejected`-Logik, Fallunterscheidungen aus `IndexingController`/`DocumentIndexingService` entfernen.

**Geliefert:** Wie gefordert — neues Enum `IndexingSourceType` (`FILESYSTEM`, `HTTP_DIRECTORY`, `RSS_FEED` kommt erst in #466 hinzu), Interface `SourceIndexingExecutor`, `IndexingSourceExecutorRegistry`, `RejectedDocumentReporter` für die zusammengeführte Duplikation, Widerspruchsprüfung mit deutscher 400-Meldung. `sourceType` optional in `IndexingTriggerRequest` (OpenAPI-generiert). Explizit benannte Abweichung von der Abnahme „ohne fachliche Anpassung": `IndexingControllerTest` musste mechanisch angepasst werden, da der Controller jetzt einen statt zwei Methodenaufrufe macht — laut PR ist das eine notwendige Folge der geforderten Fallunterscheidungs-Entfernung, kein Verhaltensunterschied.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingSourceType.java`, `SourceIndexingExecutor.java`, `IndexingSourceExecutorRegistry.java` existieren im heutigen Stand (letztere beiden nicht einzeln geprüft, aber laut Dateiliste vorhanden und `IndexingSourceType.java` bestätigt).

**Themen:** indexing, refactoring, registry, rss, backend

---

<a id="issue-466"></a>

## Issue #466 — feat(indexing): RSS_FEED als Quellentyp und Parser für RSS-2.0-Feeds
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:M
- PRs: #474 (2026-08-18)

**Laut Issue:** Phase 2 (Gerüst) — `RSS_FEED` als dritter Wert von `DocumentSourceType` samt Liquibase-Migration der `CHECK`-Constraint, plus ein von Netz und Datenbank unabhängig prüfbarer RSS-2.0-Parser mit XXE-Härtung, robustem Datumsparsing (mehrere `pubDate`-Schreibweisen), Nachsicht gegenüber fremden Namensräumen/Elementen und deutscher Fehlermeldung bei nicht lesbarem XML. Testdaten ausdrücklich erfunden/generisch, kein Bezug zu realen Adressen.

**Geliefert:** Wie gefordert — `RSS_FEED` in Java-Enum, OpenAPI-Schema und Migration `024-allow-rss-feed-source-type.yaml` (nach dem Muster von 020, Constraint droppen und neu anlegen statt vorhandenes changeSet zu bearbeiten). `RssFeedParser` mit JDK-StAX, XXE-Gegenmaßnahmen (`SUPPORT_DTD`/`IS_SUPPORTING_EXTERNAL_ENTITIES` aus), Fallback-Datumsformate und Zeitzonen-Normalisierung, Nachsicht gegenüber fremden Namensräumen. Neun Testfälle in `RssFeedParserTest`, Fixtures unter `backend/src/test/resources/rss-feeds/` mit `example.invalid`-Domänen. Migrationstest lokal ohne Docker übersprungen, laut PR in CI mit Docker geprüft. Keine Abweichung vom Issue; noch kein Indizierungslauf (bewusst außerhalb des Umfangs, folgt in #467).

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedParser.java`, `db.changelog/changes/024-allow-rss-feed-source-type.yaml` und die Testfixtures existieren im heutigen Stand des Worktrees.

**Themen:** indexing, rss, parser, sicherheit, xxe, migration, backend

---

<a id="issue-467"></a>

## Issue #467 — feat(indexing): RSS-Feeds indizieren — Einträge auflösen und Detailseiten übernehmen
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:L
- PRs: #490 (2026-08-18)

**Laut Issue:** Phase 2 (Lauf) — Feed abrufen, Änderungserkennung (Feed via ETag/If-Modified-Since, Eintrag via `pubDate`/`last_modified_remote`, Inhalt via Prüfsumme), Haupttext der Detailseite gewinnen statt ganzer Seite, kein Löschabgleich bei verschwundenen Einträgen (bewusste Ausnahme vom sonstigen Verhalten), robustes Verhalten gegenüber fremden Zielen: einzelner Fehlschlag darf Lauf nicht stoppen, konfigurierbarer Abstand zwischen Abrufen, konfigurierbarer wahrheitsgemäßer User-Agent, Obergrenzen für Anzahl/Größe. Abweisung durch die Gegenstelle muss von Verarbeitungsfehlern unterscheidbar bleiben.

**Geliefert:** Wie gefordert — `RssFeedIndexingExecutor`, neue Tabelle `rss_feed_state` (Migration 025) für ETag/Last-Modified, Änderungsprüfung je Eintrag vor Abruf der Detailseite, Haupttext-Gewinnung über Jsoup mit Entfernen von `nav`/`header`/`footer` und konfigurierbarem CSS-Selektor. Zusätzliche, im Issue nur implizit geforderte Sicherheits-/Robustheitsauflagen aus dem Review von PR #474 wurden mit umgesetzt: Obergrenzen während des Streamens durchgesetzt (nicht erst nach vollständigem Laden), Schema-Prüfung der Links (nur http/https). Konfiguration unter `opaa.indexing.rss.*`, dokumentiert in `.env.example` und `docs/deployment.md`. Kein Löschabgleich wie gefordert. Kein Reproduktionsnachweis, da neues Feature statt Bugfix — sachlich korrekt, keine Abweichung.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedIndexingExecutor.java` existiert im heutigen Stand des Worktrees; `docs/deployment.md` und `.env.example` laut Dateiliste ebenfalls angepasst.

**Themen:** indexing, rss, executor, robustheit, rate-limiting, backend

---

<a id="issue-468"></a>

## Issue #468 — feat(indexing): Anlagen an Detailseiten übernehmen, mit Profil für den Government Site Builder
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:M
- PRs: #492 (2026-08-18)

**Laut Issue:** Phase 3 — Profilbegriff für „welche Verweise einer Detailseite sind Anlagen", ein allgemeines Profil und eines für den Government Site Builder (Anhänge über Abfrageparameter statt Dateiendung), Profilwahl je Lauf mit allgemeinem Profil als Voreinstellung (keine automatische CMS-Erkennung), Herkunft der Anlage zum Eintrag nachvollziehbar, Deduplizierung gleicher Anlagen über mehrere Einträge, Obergrenzen. Nebenbefund: `jsoup` war nur transitiv vorhanden und sollte ordentlich in den Versionskatalog aufgenommen werden.

**Geliefert:** `AttachmentProfile` mit `GENERIC` (Endungen aus `SupportedDocumentFormats`, gleicher Host) und `GSB` (erkennt `__blob=publicationFile`-Muster, leitet Dateinamen aus Pfadsegment/Content-Type her). Neue Spalte `documents.source_entry_url` (Migration 026) für Herkunftsnachweis, Deduplizierung über bestehende `findByFilePath`-Logik. `jsoup` jetzt in `libs.versions.toml` deklariert. **Ausdrücklich benannte Abweichung vom Ticket-Wortlaut:** Profilwahl ist als Application-Property (`opaa.indexing.rss.attachment-profile`, Default `GENERIC`) umgesetzt statt als Request-Feld je Lauf, weil Epic #486/ADR-0018 die dauerhafte Quellkonfiguration ohnehin von `IndexingTriggerRequest` auf die Wissensbibliothek verlagert — ein Request-Feld wäre laut PR Wegwerfarbeit gewesen. Damit ist die Abnahme „Profilwahl je Lauf" so nicht erfüllt, sondern durch eine globale Konfiguration ersetzt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AttachmentProfile.java` existiert im heutigen Stand; `libs.versions.toml`-Eintrag für jsoup nicht einzeln nachgeprüft.

**Themen:** indexing, rss, anlagen, gsb, konfiguration, backend

---

<a id="issue-469"></a>

## Issue #469 — feat(admin): Quellentyp im Indizierungsformular wählbar machen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:S
- PRs: keine

**Laut Issue:** Im Indizierungsformular (`AdminDrawer.tsx`) sollte der Quellentyp explizit wählbar werden statt implizit aus der Belegung des Adressfelds abgeleitet zu werden — mit Dateisystem als Voreinstellung, typabhängig eingeblendeten Feldern, neu erzeugten OpenAPI-Typen und einem Erklärtext je Typ.

**Geliefert:** Kein PR verknüpft. Der Umbau wurde nicht in dieser Form umgesetzt, sondern durch die Ein-Typ-Regel aus ADR-0018 (#475) strukturell überholt: Der Epic-Text zu #486 vermerkt ausdrücklich „#469 wird umformuliert" — die Typauswahl wandert von einem einmaligen Anstoß-Formular im Admin-Drawer in die Bibliotheksanlage selbst (#480, PR #498) und die Bibliotheksdetailseite (#481, PR #506). Der Admin-Drawer samt Indizierungsabschnitt wurde mit #481 vollständig entfernt. Das Issue ist also nicht direkt geliefert, sondern durch ein umfassenderes Modell ersetzt worden, das dieselbe Nutzerabsicht (Typklarheit vor dem Lauf) an anderer Stelle löst.

**Verifikation:** `frontend/src/components/admin/AdminDrawer.tsx` existiert im heutigen Code nicht mehr (entfernt mit PR #506, `git log` bestätigt). Die Typauswahl findet sich stattdessen in `frontend/src/pages/LibraryCreatePage.tsx` (Nachfolger von `CreateLibraryDialog.tsx`) und der Bibliotheksdetailseite `frontend/src/pages/LibraryDetailPage.tsx`.

**Themen:** retrieval, indexing, admin-oberfläche, ersetzt-durch-epic

---

<a id="issue-470"></a>

## Issue #470 — docs(sources): Feed-Quellen und Quellentypmodell in der Spezifikation nachführen
- Geschlossen: 2026-08-18 (completed)
- Labels: documentation, size:S
- PRs: #494 (2026-08-18)

**Laut Issue:** `docs/features/knowledge-sources.md` sollte am Ende von Epic #463 auf den gebauten Stand gebracht werden: Feeds als gebauter Weg statt Zielbild, eigener Feed-Abschnitt, die typabhängige Löschausnahme mit Begründung, ADR-Verweis, Betriebseinstellungen in `docs/deployment.md`/`.env.example`.

**Geliefert:** Wie gefordert. PR #494 kennzeichnet Feeds als „(gebaut)", ergänzt den Abschnitt „Feeds als Quelle (gebaut)" mit dreistufigem Ablauf, Änderungserkennung und Verhalten gegenüber fremden Zielen, schärft die Löschausnahme (Verweis auf ADR-0017) und verlinkt den offenen Punkt zur Hebung des Netzwegs. `docs/deployment.md`/`.env.example` waren bereits vollständig (aus #467/#468), keine Änderung nötig. Reine Dokumentationsänderung.

**Verifikation:** `docs/features/knowledge-sources.md` und `docs/features/data-indexing-rag.md` existieren im heutigen Code; beide wurden seither mehrfach weiter nachgeführt (u. a. durch #482), was zur eigenen Natur eines lebenden Spezifikationsdokuments passt.

**Themen:** doku, retrieval, feeds, adr

---

<a id="issue-471"></a>

## Issue #471 — test(e2e): RSS-Quelle über die Admin-Oberfläche indizieren
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #510 (2026-08-19)

**Laut Issue:** Ein Playwright-E2E-Test sollte den kompletten RSS-Weg über die Oberfläche prüfen: Quellentyp wählen, Lauf anstoßen, Ergebnis im Bestand sehen — inkl. Positivpfad (Einträge + Anlage), Negativpfad (404 bricht Lauf nicht ab) und zweitem Lauf ohne neue Dokumente. Ein erfundener Beispiel-Feed sollte als statischer Inhalt im Compose-Stack der E2E-Suite ausgeliefert werden.

**Geliefert:** Wie gefordert, mit einer im PR selbst dokumentierten Anpassung: Der Test läuft nicht mehr über den Admin-Drawer (der existierte zum Zeitpunkt der Umsetzung schon nicht mehr, siehe #480/#481), sondern über die Bibliotheksanlage aus Template + „Jetzt indizieren" auf der Detailseite. Positiv- und Fehlerfall laufen bewusst als zwei getrennte Feeds/Bibliotheken, weil der bedingte Feed-Abruf (ETag/304) den Zweitlauf sonst nicht wie im Issue beschrieben (Pro-Eintrag-`pubDate`-Skip) hätte prüfbar gemacht — der Test prüft deshalb nur das gemeinsame Ergebnis (`documentCount: 0`).

**Verifikation:** `e2e/tests/rss-feed-library.spec.ts` existiert. Die im PR beschriebenen Fixtures lagen ursprünglich unter `e2e/fixtures/rss-feed/htdocs/`; heute liegen sie unter `demo/seed/e2e-data/rss-feed/htdocs/` (`feed-ok.xml`, `feed-error.xml`, `anlagen/`, `seiten/` bzw. `webverzeichnis/` vorhanden) — ein späterer Umbau hat die E2E-Fixtures und die Demo-Korpus-Daten zusammengeführt, ohne die Funktionalität zu ändern. `e2e/docker-compose.e2e.yml` bindet den `rss-feed`-Service weiterhin ein.

**Themen:** e2e, feeds, retrieval, ci

---

<a id="issue-475"></a>

## Issue #475 — docs(decisions): ADR-0018 — Quellkonfiguration in der Bibliothek
- Geschlossen: 2026-08-18 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #487 (2026-08-18)

**Laut Issue:** Ein ADR sollte festlegen, dass eine Wissensbibliothek künftig genau einen Quellentyp und höchstens eine Quellkonfiguration trägt (Ein-Typ-Regel), Entscheidung 4 aus ADR-0017 ablöst, Zugangsdaten-Grundsätze festlegt und die Löschregel für Konnektorbibliotheken bestimmt. Verworfene Alternativen (eigene Quellen-Tabelle, gemischte Bibliotheken) sollten dokumentiert sein.

**Geliefert:** Wie gefordert. ADR-0018 liegt unter `docs/decisions/` vor, beschreibt das Verhältnis zu ADR-0017 (Entscheidung 4 abgelöst, Registry/Löschsemantik bleiben) und ist Grundlage des Epics #486. Reine Dokumentationsänderung, keine Codeänderung in diesem PR.

**Verifikation:** `docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md` und `docs/decisions/0017-quellentypmodell-indizierung.md` existieren im heutigen Repo. Die im ADR festgelegte Ein-Typ-Regel ist im Code umgesetzt (`KnowledgeLibrary.sourceType`, siehe #476) — das ADR beschreibt tatsächlich Gebautes, nicht nur Zielbild.

**Themen:** doku, adr, spaces, retrieval

---

<a id="issue-476"></a>

## Issue #476 — feat(library): Quellentyp und Quellkonfiguration an der Bibliothek (Schema, Entity, API)
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:L
- PRs: #489 (2026-08-19)

**Laut Issue:** `KnowledgeLibrary` sollte nach ADR-0018 selbst `sourceType` und typspezifische Konfiguration tragen: Liquibase-Migration mit Backfill auf `UPLOAD`, Validierung je Typ, unveränderlicher Typ nach Anlage, OpenAPI-Erweiterung ohne Zugangsdaten in Antworten.

**Geliefert:** Wie gefordert. Migration `027-library-source-type-and-configuration.yaml` (im Issue-Body als `024` angekündigt, im PR tatsächlich als `027` umgesetzt — Nummerierungsverschiebung durch parallele Migrationen, kein inhaltlicher Unterschied) legt `source_type` NOT NULL mit CHECK-Constraint an, backfillt Bestand auf `UPLOAD`. `KnowledgeLibraryService` validiert Konfiguration je Typ und lehnt Typwechsel beim Update ab. `sourceCredentials` ist strukturell reines Nur-Schreiben-Feld der Anfrage, `LibraryResponse` kennt es nicht. Migrationskante bewusst benannt: Alt-Bestand über den früheren globalen Anstoß wird durch den Backfill lauf-los, bis eine neue typisierte Bibliothek angelegt wird — akzeptierte Nebenwirkung, kein Bug.

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibrary.java` und `KnowledgeLibraryService.java` existieren; Migration liegt im Changelog. `sourceType` ist heute fester Bestandteil des Bibliotheksmodells, sichtbar u. a. in `KnowledgeLibraryServiceIntegrationTest.java`.

**Themen:** backend, spaces, retrieval, migration, adr

---

<a id="issue-477"></a>

## Issue #477 — feat(library): Dokumentzahl in der Bibliotheksliste
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, frontend, size:S
- PRs: #488 (2026-08-18)

**Laut Issue:** `LibraryListResponse` sollte je Bibliothek `documentCount` liefern (ohne N+1), die Übersicht sollte die Zahl anzeigen, ohne jede Karte aufklappen zu müssen.

**Geliefert:** Wie gefordert. `documentCount` in `LibraryListResponse`, gezählt über eine neue gruppierte Query `DocumentRepository#countByLibraryIdIn` für die ganze Seite auf einmal statt `countByLibraryId` je Zeile. `LibraryManagementPage` zeigt die Zahl direkt in der eingeklappten Kopfzeile; der bisherige Zähler in der aufgeklappten Detailansicht wurde als redundant entfernt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/DocumentRepository.java` enthält weiterhin Zählabfragen für Bibliotheken; `frontend/src/pages/LibraryManagementPage.tsx` existiert und zeigt Bibliotheksmetadaten in der Kopfzeile. Die Dokumentzahl wurde später um weitere Metadaten ergänzt (Zeitplan, Quellentyp), ohne diese Funktion zu verdrängen.

**Themen:** backend, frontend, spaces, retrieval

---

<a id="issue-478"></a>

## Issue #478 — feat(indexing): Indizierungsanstoß je Bibliothek aus gespeicherter Konfiguration
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #500 (2026-08-19)

**Laut Issue:** Der Indizierungsanstoß sollte von einem Request mit voller Konfiguration auf einen reinen Bibliotheksverweis umgestellt werden: neuer Endpunkt je Bibliothek, `UPLOAD` → 409, Nebenläufigkeit je Bibliothek statt global, alter globaler Endpunkt samt `url`-Fallback entfällt.

**Geliefert:** Wie gefordert, mit einer bewussten Zusatzänderung: `POST /api/v1/libraries/{libraryId}/indexing` ersetzt `POST /api/v1/indexing/trigger`, `IndexingTriggerRequest` entfällt vollständig. Nebenläufigkeit ist jetzt je Bibliothek (`IndexingJobService#isJobRunning(UUID)`). Zusätzlich zum Issue-Umfang: Die frühere `SYSTEM_ADMIN`-Schranke des Trigger-Endpunkts wurde bewusst fallengelassen (ADR-0018, Entscheidung 2) — es genügt jetzt `EDITOR` auf der Bibliothek. `opaa.indexing.document-path` bleibt als totes, ungenutztes Konfigurationsfeld bestehen (im PR selbst als Aufräumkandidat vermerkt).

**Verifikation:** `backend/src/main/java/io/opaa/api/IndexingController.java` und `LibraryController.java` existieren; der bibliotheksbezogene Indizierungsendpunkt ist heute Standard (auch von späteren Issues wie #485, #501, #513 weiter ausgebaut). Kein Hinweis, dass der globale Endpunkt zurückgekehrt wäre.

**Themen:** backend, indexing, retrieval, adr, sicherheit

---

<a id="issue-479"></a>

## Issue #479 — feat(library): Upload nur in UPLOAD-Bibliotheken und Löschverhalten für Konnektorbibliotheken
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #503 (2026-08-19)

**Laut Issue:** Upload sollte auf `UPLOAD`-Bibliotheken beschränkt werden (409 sonst), Löschen einer Konnektorbibliothek sollte Dokumente und Chunks mitnehmen, `UPLOAD` behält die bestehende Sperre bei vorhandenem Bestand.

**Geliefert:** Wie gefordert. `POST /api/v1/libraries/{libraryId}/documents` liefert 409 mit deutscher Fehlermeldung für Konnektorbibliotheken. `DELETE /api/v1/libraries/{libraryId}` entfernt bei Konnektorbibliotheken Dokumentzeilen und Vektorspeicher-Chunks mit, inkl. Audit-Eintrag `LIBRARY_DELETED` mit `documentsRemoved`. Reproduktionsnachweis mit drei roten/grünen Testläufen im PR dokumentiert. Frontend blendet den Upload-Bereich für Konnektorbibliotheken aus und warnt bei der Löschbestätigung zusätzlich vor dem Mitnehmen des Bestands.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryDocumentService.java` und `KnowledgeLibraryService.java` existieren mit entsprechender Logik (bestätigt durch begleitende Tests `LibraryDocumentServiceIntegrationTest.java`, `LibraryDocumentServiceTest.java`). Löschverhalten wurde später um eine explizite Sperre bei laufenden Jobs ergänzt (`KnowledgeLibraryServiceDeleteLockTest.java`, sichtbar in #485-Dateiliste) — Weiterentwicklung, keine Rücknahme.

**Themen:** backend, spaces, retrieval, adr

---

<a id="issue-480"></a>

## Issue #480 — feat(frontend): Bibliotheksanlage mit Typauswahl aus Templates
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #498 (2026-08-19)

**Laut Issue:** Der Anlagedialog sollte mit einer Template-Auswahl (= Quellentyp) beginnen, typspezifische Konfigurationsfelder zeigen, `RSS_FEED` automatisch mitführen und den Typ nach Anlage sichtbar, aber nicht änderbar machen.

**Geliefert:** Wie gefordert, in `CreateLibraryDialog` umgesetzt (zu diesem Zeitpunkt noch ein Dialog, kein eigener Seiten-Assistent). Die Vorlagenliste leitet sich per `Object.keys` aus der Label-Map ab, sodass ein künftiger Enum-Wert ohne Übersetzung die Kompilierung bricht — `RSS_FEED` erscheint dadurch ohne Dialoganpassung. Bekannte, im PR offen benannte Lücke: `LibraryListResponse` trug zu diesem Zeitpunkt noch kein `sourceType`, der Typ-Chip erschien deshalb erst nach Einzelabruf einer Bibliothek — diese Lücke wurde mit #481 geschlossen (`sourceType` in `LibraryListResponse` ergänzt).

**Verifikation:** `frontend/src/components/CreateLibraryDialog.tsx` existiert im heutigen Code nicht mehr — ein späterer Commit (`0c08e89f`, „Bibliothek-Anlage als Assistent mit Herkunfts-Auswahl") hat die Anlage in eine eigene Seite (`frontend/src/pages/LibraryCreatePage.tsx`) umgebaut. Die im Issue geforderte Funktionalität (Templatewahl, typspezifische Felder, unveränderlicher Typ) besteht dort fort — Umbau der Form, nicht Rücknahme der Funktion.

**Themen:** frontend, spaces, retrieval, adr

---

<a id="issue-481"></a>

## Issue #481 — feat(frontend): Bibliotheksdetailseite mit typspezifischem Bereich
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #506 (2026-08-19)

**Laut Issue:** Eine neue Route `/libraries/:id` sollte Stammdaten, Freigaben, Dokumentliste und Dokumentzahl bündeln; `UPLOAD` zeigt Upload-Zone, Konnektortypen zeigen Konfiguration + „Jetzt indizieren" + Status; `DocumentsPage` und der Indizierungsabschnitt im Admin-Drawer sollten entfallen.

**Geliefert:** Wie gefordert. Neue Route `/libraries/:libraryId`, `DocumentsPage`, `AdminDrawer` und `AdminDrawerToggle` entfernt (waren ausschließlich Träger dieses einen Abschnitts). Zusätzlich zum Issue-Umfang: `sourceType` wurde in `LibraryListResponse` ergänzt (schließt die in #480 offen benannte Lücke). Ein offener Punkt wurde bewusst nicht gelöst: ob Zugangsdaten hinterlegt sind, zeigt die Seite nur als erklärenden Hinweistext, kein boolesches Flag — um nicht mit der parallel laufenden Verschlüsselung (#483) zu kollidieren.

**Verifikation:** `frontend/src/pages/LibraryDetailPage.tsx` existiert und ist die zentrale Detailseite; `DocumentsPage.tsx` und `components/admin/AdminDrawer.tsx` existieren im heutigen Code nicht mehr (bestätigt per `git log --follow`, letzte Commits zeigen den Entfernungs-Commit `fc9eeb5a`). Route und Ablösung sind also weiterhin Stand der Dinge.

**Themen:** frontend, spaces, retrieval, adr

---

<a id="issue-482"></a>

## Issue #482 — docs(sources): Spezifikation auf Bibliothekstypen nachführen
- Geschlossen: 2026-08-19 (completed)
- Labels: documentation, size:M
- PRs: #512 (2026-08-19)

**Laut Issue:** `knowledge-sources.md` sollte die Ein-Typ-Regel nachführen (kein Konnektor-mit-Quellen-Zielbild, keine gemischt gespeisten Bibliotheken mehr), Querverweise in `spaces-and-assets.md`/`data-indexing-rag.md` prüfen und Issue #207 zur strukturell erzwungenen 1:1-Zuordnung kommentieren.

**Geliefert:** Wie gefordert. Der Konnektor-Abschnitt verweist jetzt auf ADR-0018 statt ein eigenständiges Mehrquellen-Objekt zu beschreiben; neue Sektion „Verzeichnis im Dateisystem (gebaut)"; Auslösung an der Bibliothek statt Systemverwaltung; „Eine Quelle, eine Wissensbibliothek" als strukturell erzwungen neu gefasst; Löschsemantik um „ganze Bibliothek löschen" ergänzt; Zeitplan-Abschnitt auf „je Bibliothek" umgestellt. Reine Dokumentationsänderung.

**Verifikation:** `docs/features/knowledge-sources.md`, `docs/features/spaces-and-assets.md`, `docs/features/user-frontends.md` existieren; alle wurden seither weiter aktualisiert (u. a. durch #485, #493, #507), was für ein weiterhin gepflegtes Dokument spricht statt für Verfall.

**Themen:** doku, spaces, retrieval, adr

---

<a id="issue-483"></a>

## Issue #483 — feat(security): Zugangsdaten der Quellkonfiguration sicher verwahren
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #504 (2026-08-19)

**Laut Issue:** Zugangsdaten in der Quellkonfiguration sollten verschlüsselt statt im Klartext liegen (Schlüssel aus Umgebungsvariable), nie in API-Antworten oder Logs auftauchen, und der Wechsel sollte ohne Laufausfall möglich sein.

**Geliefert:** Wie gefordert. AES-256-GCM-Verschlüsselung über `CredentialsEncryptor` mit zufälligem IV je Wert, transparent über einen JPA-`AttributeConverter` (`SourceCredentialsConverter`) an der Persistenzgrenze. Schlüssel aus `OPAA_CREDENTIALS_ENCRYPTION_KEY`; lokale Profile nutzen einen als nicht-produktiv markierten Default. Fehlender/ungültiger Schlüssel führt zu 503 statt 500. Migration verbreitert die Spalte für die verschlüsselte Kodierung; Alt-Klartextwerte werden am fehlenden Präfix erkannt und beim nächsten Schreibvorgang verschlüsselt (keine Batch-Migration möglich, da nur die Anwendung den Schlüssel kennt).

**Verifikation:** `backend/src/main/java/io/opaa/library/SourceCredentialsConverter.java`, `backend/src/main/java/io/opaa/security/CredentialsEncryptor.java`, `CredentialsEncryptionKeyMissingException.java` und `CredentialsEncryptionProperties.java` existieren im heutigen Code. `docs/deployment.md` dokumentiert den Schlüssel weiterhin.

**Themen:** backend, sicherheit, spaces, retrieval, adr

---

<a id="issue-484"></a>

## Issue #484 — feat(security): Pfad-Allowlist und Berechtigung für Konnektorbibliotheken
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #511 (2026-08-19)

**Laut Issue:** Vor Mehrbenutzer-Produktivbetrieb sollte eine Pfad-Allowlist für `FILESYSTEM`-Bibliotheken eingeführt und entschieden werden, welche Rolle Konnektorbibliotheken anlegen darf; Zusammenspiel mit #267 (Zielprüfung gegen private Adressbereiche) benennen.

**Geliefert:** Teilweise abweichend von der Ausgangsfrage: Die Rollenfrage wurde laut PR-Beschreibung als Maintainer-Entscheidung offen gelassen — weiterhin darf jeder mit Anlage-Recht jeden Bibliothekstyp anlegen. Stattdessen liegt die eigentliche Sicherung in einer betriebsseitig konfigurierten Pfad-Allowlist (`opaa.indexing.filesystem-allowlist`, `OPAA_INDEXING_FILESYSTEM_ALLOWLIST`), geprüft bei Anlage/Update und erneut bei jedem Lauf (Traversal-sicher über `Path.normalize()`). Eine leere Allowlist (Standard) deaktiviert `FILESYSTEM` faktisch. URL-Typen (`HTTP_DIRECTORY`, `RSS_FEED`) bleiben bewusst unberührt — #267 bleibt dafür offen.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FilesystemPathAllowlist.java` existiert; `docs/deployment.md` und `.env.example` dokumentieren die Variable. `docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md` enthält den entsprechenden Nachtrag.

**Themen:** backend, sicherheit, spaces, ssrf, adr

---

<a id="issue-485"></a>

## Issue #485 — feat(indexing): Zeitplan je Bibliothek für Indizierungsläufe
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend
- PRs: #705 (2026-08-21)

**Laut Issue:** Ein an-/abschaltbarer Zeitplan je Konnektorbibliothek sollte eingeführt werden, mit Anzeige „letzter/nächster Lauf" auf der Detailseite, einer Antwort auf verteilte Ausführung (Lock/Leader) und mindestens vorbereiteten Vorrangregeln. Der Umfang war laut Issue „grob, vor Umsetzung zu verfeinern".

**Geliefert:** Wie im Kern gefordert, mit zwei bewussten Zuschnittsentscheidungen: feste Intervallstufen (stündlich/täglich/wöchentlich/aus) statt freier Cron-Eingabe, intern als Cron-Ausdruck gespeichert; kein eigener Leader-/Lock-Mechanismus — die bestehende DB-Sperre `uk_indexing_jobs_library_running` verhindert Doppelstarts bereits, ein Tick auf eine laufende Bibliothek wird übersprungen und protokolliert. Vorrangregeln wurden nicht vorbereitet, sondern laut PR bewusst fallengelassen. Anzeige „nächster geplanter Lauf" und ein Warnhinweis bei zwei aufeinanderfolgenden fehlgeschlagenen Läufen (ohne automatische Deaktivierung) sind umgesetzt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/LibraryIndexingScheduler.java` und `LibraryScheduleCodec.java` existieren; `frontend/src/components/EditLibraryScheduleDialog.tsx` existiert. Zeitzone ist Serverzeit, nicht konfigurierbar — im PR ausdrücklich als Zuschnitt benannt, nicht als Lücke versteckt.

**Themen:** backend, frontend, indexing, scheduling, spaces

---

<a id="issue-486"></a>

## Issue #486 — feat: Bibliothekstypen — Quellkonfiguration wandert in die Bibliothek
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, epic, backend, frontend
- PRs: keine

**Laut Issue:** Epic-Tracking-Issue für den Umbau, bei dem eine Wissensbibliothek aus einem Template angelegt wird und genau einen Quellentyp samt Konfiguration trägt. Drei Phasen (Entscheidung/Datenmodell, Verhalten, Oberfläche/Doku) plus Sicherheits-Nachzügler (#483, #484) vor Produktivbetrieb.

**Geliefert:** Kein eigener PR — wie bei einem Epic üblich, ist die Lieferung die Summe seiner Sub-Issues: #475 (ADR-0018), #476 (Schema/Entity/API), #477 (Dokumentzahl), #478 (Anstoß je Bibliothek), #479 (Upload-/Löschregeln), #480 (Anlage mit Template), #481 (Detailseite), #482 (Spezifikation), #483 (Zugangsdaten-Verschlüsselung), #484 (Pfad-Allowlist). Alle wurden mit eigenen PRs gemergt (siehe jeweilige Bausteine). Die Epic-Abnahmekriterien (unveränderlicher Typ, Bestand bleibt `UPLOAD`, Anstoß kennt nur die Bibliothek, `/documents` und Admin-Drawer-Indizierung entfernt, Zugangsdaten in keiner API-Antwort) sind laut den Einzel-PRs erfüllt.

**Verifikation:** Siehe die Einzel-Bausteine der Sub-Issues; dort ist jeweils der heutige Codezustand geprüft. Nachträgliche Sicherheits- und Sichtbarkeitsfunde (#491, #493, #501, #505, #507, #513–#519) zeigen, dass der Umbau nach dem Epic-Abschluss noch mehrere Review-Nachzügler auslöste — üblich bei einem Umbau dieser Größe, kein Hinweis auf einen unvollständigen Kern.

**Themen:** epic, spaces, retrieval, adr, agenten-organisation

---

<a id="issue-491"></a>

## Issue #491 — fix(indexing): Skip-Prüfung des URL-Wegs ignoriert die Zielbibliothek
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #645 (2026-08-20)

**Laut Issue:** `UrlIndexingExecutor.isUnchanged` überspringt ein unverändertes Dokument, ohne die Zielbibliothek zu prüfen — dieselbe Quelle in eine andere Bibliothek indiziert bleibt fälschlich in der alten liegen. Der RSS-Weg hatte dieselbe Lücke bereits behoben (#467/PR #490); dieser Altbestand im URL-Weg blieb offen.

**Geliefert:** Wie gefordert. `isUnchanged` berücksichtigt jetzt zusätzlich `targetLibrary.getId()`, spiegelbildlich zum RSS-Fix. Reproduktionsnachweis mit rotem/grünem Testlauf im PR dokumentiert (`UrlIndexingExecutorTest#isUnchanged_returnsFalseWhenTargetLibraryDiffersFromTheExistingDocuments`).

**Verifikation:** `backend/src/main/java/io/opaa/indexing/UrlIndexingExecutor.java` und der zugehörige Test existieren im heutigen Code.

**Themen:** backend, bugfix, indexing, retrieval

---

<a id="issue-493"></a>

## Issue #493 — feat(library): Herkunft von Feed-Anlagen in API und Oberfläche sichtbar machen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:S
- PRs: #638 (2026-08-20)

**Laut Issue:** Die mit #468 eingeführte Spalte `documents.source_entry_url` wurde geschrieben, aber nirgends gelesen — sie sollte als optionales Feld in `LibraryDocumentResponse` erscheinen, in der Oberfläche sichtbar werden, und es sollte entschieden werden, ob Chunk-Metadaten die Herkunft mitführen sollen.

**Geliefert:** Wie gefordert. `LibraryDocumentResponse.sourceEntryUrl` ergänzt, Bibliotheksdetailseite zeigt es als Link unter dem betroffenen Dokument. Entscheidung zu den Chunk-Metadaten: keine Verdopplung im Vektorspeicher — `document_id` liegt bereits auf jedem Chunk, `sourceEntryUrl` folgt demselben Lookup-Muster wie `indexedAt` in `QueryService#lookupIndexedAt`, statt einen zweiten, driftenden Wert je Chunk zu pflegen. Begründung als Code-Kommentar in `FileProcessingService#storeChunks` dokumentiert statt in einem separaten Dokument.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryDocumentResponses.java` existiert; `frontend/src/pages/LibraryDetailPage.tsx` enthält die Anzeige.

**Themen:** backend, frontend, feeds, retrieval, zitation

---

<a id="issue-495"></a>

## Issue #495 — docs(agents): Pre-Push-Verifikation für Nachbesserungsrunden verschlanken
- Geschlossen: 2026-08-19 (completed)
- Labels: documentation, size:S
- PRs: #496 (2026-08-19)

**Laut Issue:** Drei vom Maintainer freigegebene Beschleunigungen des Entwickler-Arbeitsablaufs sollten dokumentiert werden: verkürzte lokale Verifikation für Nachbesserungsrunden (nur Formatierung/Kompilieren/berührte Tests, Volllauf übernimmt die CI), `npm ci --prefer-offline` in frischen Worktrees, und Builds im Vordergrund abwarten statt auf Hintergrundläufe zu schlafen.

**Geliefert:** Wie gefordert, alle drei Punkte in `AGENTS.md` und `agents/roles/developer.md` dokumentiert, mit der Begründung aus der Auswertung von Epic #463 (lokaler Volllauf hat in Nachbesserungsrunden nichts gefangen, was die CI nicht auch gefangen hätte; einmal hat er sogar einen von der CI gefangenen Fehler verfehlt, PR #474).

**Verifikation:** Die heutige `AGENTS.md` (in diesem Worktree) enthält im Abschnitt „Pre-Push-Checkliste" exakt diese Unterscheidung zwischen Erstumsetzung und Nachbesserungsrunde sowie die Regel „Builds und Tests im Vordergrund ausführen" — deckungsgleich mit dem PR-Inhalt.

**Themen:** doku, agenten-organisation, ci, prozess

---

<a id="issue-497"></a>

## Issue #497 — test(backend): Migrationstests dominieren die Suite — Template-DB und geteilter Container
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine (im Chunk-Datensatz nicht verknüpft)

**Laut Issue:** Die Migrationstests (18 Klassen unter `io.opaa.migration`) kosteten 59 % der Suitenzeit durch Schema-Neuaufbau je Testmethode und 19 Einzel-Container. Gefordert: Template-Datenbank statt Vollaufbau je Methode, ein gemeinsamer Singleton-Container, `OpaaApplicationTests` an den geteilten Kontext angleichen, `maxParallelForks = 2`, Ziel `./gradlew test` unter 3:30 min (Referenz 6:44 min), ohne Testsemantik zu schwächen.

**Geliefert:** Im Chunk-Datensatz ist kein PR mit diesem Issue verknüpft — dennoch belegt der heutige Code, dass die Arbeit stattgefunden hat, offenbar über PRs, deren „Closes #497"-Verknüpfung von der Datenextraktion nicht erfasst wurde. Die Commit-Historie zeigt eine mehrteilige Umsetzung: `b67023c2`/`b58e095e` (PR #499, Migrationstests auf Template-DB und geteilten Container), `80bfe782` (OpaaApplicationTests angeglichen), `a9ed523d` (Review-Nachbesserung), `71c69568`/`e3a9f3ec` (PR #648, Spring-Kontexte weiter konsolidiert), `b4c97667` (letzte drei Migrationstests nachgezogen).

**Verifikation:** `backend/src/test/java/io/opaa/migration/AbstractMigrationTest.java` existiert und enthält das Template-DB-Muster; `backend/build.gradle.kts` setzt `maxParallelForks = 2` (Zeile 308). Beide zentralen Umsetzungsbausteine des Issues sind im heutigen Code vorhanden.

**Themen:** ci, backend, testing, performance

---

<a id="issue-501"></a>

## Issue #501 — fix(indexing): Hängengebliebene RUNNING-Jobs sperren ihre Bibliothek dauerhaft
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend
- PRs: #649 (2026-08-20)

**Laut Issue:** Eine `indexing_jobs`-Zeile kann dauerhaft `RUNNING` bleiben (verworfene Async-Aufgabe, abgestürzter Prozess) und sperrt seit #478 ihre Bibliothek dauerhaft, ohne Weg zur Auflösung in der Oberfläche. Gefordert: Bereinigung verwaister `RUNNING`-Jobs beim Neustart, Überdenken der `DiscardPolicy`, Test für das Neustart-Szenario.

**Geliefert:** Wie gefordert. `IndexingJobRecoveryScheduler` markiert beim Anwendungsstart jede noch `RUNNING`-Zeile als `FAILED`; ein periodischer Sweep (alle 15 Minuten, konfigurierbare Altersgrenze `opaa.indexing.stale-job-timeout`, Default 4h) fängt zusätzlich Läufe ohne Neustart ab. `indexingTaskExecutor` nutzt jetzt `AbortPolicy` statt `DiscardPolicy` (analog zu `uploadTaskExecutor` seit #589); eine abgelehnte Aufgabe setzt den Job sofort auf `FAILED` und liefert 503. Reproduktionsnachweis mit rotem/grünem Testlauf im PR dokumentiert. Ein verwandter, aber eigenständiger Review-Befund (`deleteLibrary` bei laufendem Job) wurde bewusst als separates Folge-Issue ausgegliedert statt hier mitgelöst.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingJobRecoveryScheduler.java` existiert; `docs/deployment.md` dokumentiert `OPAA_INDEXING_STALE_JOB_TIMEOUT`.

**Themen:** backend, bugfix, indexing, betrieb

---

<a id="issue-505"></a>

## Issue #505 — feat(indexing): RSS-Executor nutzt hinterlegte Zugangsdaten nicht
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S
- PRs: #642 (2026-08-20)

**Laut Issue:** `RssFeedIndexingExecutor` liest `sourceCredentials`/`sourceProxy` nicht, obwohl das Schema (#476) und die Doku deren Nutzung für `RSS_FEED`-Bibliotheken bereits in Aussicht stellen — nur `UrlIndexingExecutor` tut das. Entscheidung: entweder umsetzen oder die Felder für `RSS_FEED` ausdrücklich ausschließen.

**Geliefert:** Entscheidung für die Umsetzung. `RssFeedIndexingExecutor` wendet Basic Auth und Proxy jetzt auf Feed-Abruf, Detailseiten und Anlagen-Downloads an, analog zu `UrlIndexingExecutor`. Der `Authorization`-Header wird an mehreren Stellen (Redirect-Handling, `UrlFileDownloader#downloadBounded`) explizit vor der Weitergabe an einen fremden Host geschützt. `sourceInsecureSsl` bleibt bewusst außerhalb dieses Issues (Follow-up #637).

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedIndexingExecutor.java` und `ProxyAndCredentials.java` existieren im heutigen Code.

**Themen:** backend, feeds, sicherheit, retrieval

---

<a id="issue-507"></a>

## Issue #507 — feat(library): Quellkonfiguration nur für Bearbeitende sichtbar machen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:S, security
- PRs: #657 (2026-08-20)

**Laut Issue:** `LibraryResponse` lieferte `sourcePath`/`sourceUrl`/`sourceProxy` an jeden Lesenden — ein VIEWER einer organisationsweiten Konnektorbibliothek sah damit interne Serverpfade, Quell-URLs und Proxy-Hosts. Gefordert: Entscheidung, ob die Konfiguration nur für canEdit befüllt wird, und Nachzug der Detailseite.

**Geliefert:** Wie gefordert. Die Felder werden nur noch befüllt, wenn `myRole` mindestens MANAGER ist — derselbe Schwellwert wie für Änderungen. `sourceType` bleibt für jede Rolle sichtbar. Die Detailseite blendet den Konfigurationsbereich für Lesende aus und zeigt stattdessen einen Hinweis. Zusätzlich zum Issue-Umfang, auf Maintainer-Wunsch in denselben PR gezogen: `GET .../indexing/status` gab bei `FAILED` bislang die rohe Exception-Meldung zurück (u. a. interne Serverpfade bei `NoSuchFileException`) — dieselbe Information blieb über einen zweiten Endpunkt für jeden VIEWER sichtbar. Diese Meldung wird jetzt ebenfalls rollenabhängig gekürzt (eigenes Issue #659, im selben PR mitgeschlossen).

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetRole.java` und `KnowledgeLibraryService.java` existieren mit rollenabhängiger Sichtbarkeitslogik; `docs/features/spaces-and-assets.md` enthält die entsprechende Ausnahme.

**Themen:** backend, frontend, sicherheit, spaces, berechtigungen

---

<a id="issue-508"></a>

## Issue #508 — ci(e2e): Playwright-Install hängt und frisst das Job-Timeout
- Geschlossen: 2026-08-19 (completed)
- Labels: bug, ci
- PRs: #509 (2026-08-19)

**Laut Issue:** Der Schritt „Install Playwright browsers" (`--with-deps`) hing wiederholt am apt-Teil und hatte kein eigenes Timeout — der Job lief ins 20-Minuten-Limit, ohne dass die Suite je startete (zuletzt dreimal bei #504/#506). Gefordert: Cache für `~/.cache/ms-playwright`, Schritt-Timeout, Trennung von Browser-Install und System-Deps.

**Geliefert:** Wie gefordert. Browser-Cache mit Schlüssel aus der Playwright-Version in `e2e/package-lock.json`, `--with-deps` nur noch bei Cache-Miss, 6-Minuten-Timeout am Schritt.

**Verifikation:** `.github/workflows/e2e.yml` existiert und enthält Cache- und Timeout-Konfiguration für den Playwright-Install-Schritt.

**Themen:** ci, e2e, betrieb

---

<a id="issue-513"></a>

## Issue #513 — feat(indexing): Übersprungene Dokumente eines Laufs mit Grund in der Oberfläche anzeigen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #604 (2026-08-20)

**Laut Issue:** Beim Indizieren eines RSS-Feeds auf der Testinstanz wurden 19/20 Einträge übersprungen (Bot-Schutz), aber die Oberfläche zeigte nur die Zahl ohne Grund. Gefordert: Der Indizierungsstatus sollte eine Liste übersprungener/abgewiesener Inhalte mit kategorisiertem, deutschem Grund führen, einklappbar auf der Detailseite, ohne rohe Challenge-URLs.

**Geliefert:** Wie gefordert, mit größerem Umfang als im Issue skizziert: Ein allgemeines Protokollformat je Lauf, einheitlich für alle nicht-UPLOAD-Quellentypen (nicht nur RSS), mit Kategorien `REJECTED`/`UNREACHABLE`/`UNSUPPORTED_FORMAT`/`ALLOWLIST`/`ERROR`, gekappt bei 500 Ereignissen je Lauf. Neue Tabelle `indexing_run_events`, neuer Endpunkt `GET .../indexing/runs`. Zusätzlich: Aufbewahrung der letzten 10 Läufe je Bibliothek samt automatischer Bereinigung älterer Läufe — eine im Issue nicht geforderte, aber sinnvolle Ergänzung.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingRunEvent.java`, `IndexingRunEventRecorder.java` und `IndexingRunEventRepository.java` existieren im heutigen Code.

**Themen:** backend, frontend, feeds, indexing, transparenz

---

<a id="issue-514"></a>

## Issue #514 — feat(library): Verbindungstest für Quellkonfiguration im Erstellungsdialog
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #537 (2026-08-19)

**Laut Issue:** Ein „Verbindung testen"-Button sollte vor dem Anlegen die Quellkonfiguration serverseitig prüfen (Verzeichnis existiert/lesbar mit Dokumentzahl, Webverzeichnis erreichbar unter Proxy/Zugangsdaten, RSS-Feed abrufbar/parsebar mit Eintragszahl) — mit derselben HTTP-Client-Basis wie die echten Läufe und mindestens der Anlage-Berechtigung.

**Geliefert:** Wie gefordert. Neuer Endpunkt `POST /api/v1/libraries/source-test`, alle drei Typen implementiert, nutzt dieselben HTTP-Client-Bausteine (`AutoindexCrawlerService.buildHttpClient`/`buildAuthHeader`) wie die Indizierungsläufe — ausdrücklich auch für den RSS-Test, obwohl der RSS-Executor selbst Proxy/Zugangsdaten zu diesem Zeitpunkt noch nicht anwendete (#505, später behoben). `UPLOAD` liefert 400. Test ist optional, Anlegen bleibt auch ohne ihn möglich.

**Verifikation:** `backend/src/main/java/io/opaa/library/SourceConnectionTestService.java` existiert; der Button ist heute Teil der Bibliotheksanlage (mittlerweile `LibraryCreatePage.tsx` statt des ursprünglichen `CreateLibraryDialog.tsx`, siehe #480).

**Themen:** backend, frontend, spaces, retrieval, ux

---

<a id="issue-515"></a>

## Issue #515 — feat(frontend): Quellentyp „Verzeichnisliste" in „Webverzeichnis" umbenennen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #530 (2026-08-19)

**Laut Issue:** Der Quellentyp `HTTP_DIRECTORY` hieß in der Oberfläche „Verzeichnisliste" und provozierte Verwechslung mit dem Typ „Dateisystem". Umbenennung auf „Webverzeichnis" in allen sichtbaren Texten, der technische Enum-Wert bleibt unverändert.

**Geliefert:** Wie gefordert. Label- und Beschreibungstext in `frontend/src/utils/labels.ts` umbenannt, projektweite Suche fand keine weiteren sichtbaren UI- oder E2E-Texte mit dem alten Begriff.

**Verifikation:** `frontend/src/utils/labels.ts` existiert; eine Suche nach „Webverzeichnis" trifft konsistent auf die neue Bezeichnung in der Dokumentation (`docs/features/knowledge-sources.md`, u. a. in #515 selbst und in #482 nachgeführt).

**Themen:** frontend, ux, doku, spaces

---

<a id="issue-516"></a>

## Issue #516 — feat(frontend): Quellkonfiguration einer Bibliothek nachträglich bearbeiten
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #542 (2026-08-19)

**Laut Issue:** Die Quellkonfiguration (Feed-URL, Verzeichnispfad, Proxy, Zugangsdaten) ließ sich nachträglich nicht über die Oberfläche ändern, obwohl das Backend es bereits erlaubte (`PUT /libraries/{id}`). Gefordert: Bearbeitungsdialog mit denselben typspezifischen Feldern wie im Erstellungsdialog, Zugangsdaten nur neu setzen oder unverändert lassen, Quellentyp bleibt unveränderlich.

**Geliefert:** Wie gefordert, plus zwei im PR selbst gefundene und behobene Backend-Fehler. Neuer Dialog `EditLibrarySourceDialog`. Beim Review zeigte sich, dass die bestehende Update-Semantik gespeicherte Zugangsdaten beim alleinigen Ändern eines anderen Feldes stillschweigend auf `null` setzte — behoben mit Rückfall auf den gespeicherten Wert. Eine zweite, sicherheitsrelevante Nachbesserung: Der erste Fix hätte Zugangsdaten bei einem Wechsel der Quell-URL auf einen fremden Host mitwandern lassen (Datenabfluss-Risiko) — der Fallback greift jetzt nur noch bei unverändertem Origin (Schema/Host/Port), mit eigenem Reproduktionsnachweis. Ein explizites Entfernen von Zugangsdaten bleibt bewusst nicht möglich; der Verbindungstest (#537, parallel gemergt) wurde bewusst nicht in diesen Dialog integriert, da der Testendpunkt keine gespeicherten Zugangsdaten wiederverwenden kann.

**Verifikation:** `frontend/src/components/EditLibrarySourceDialog.tsx` existiert; `frontend/src/utils/librarySourceConfig.ts` bündelt die geteilte Validierungslogik mit der Anlage.

**Themen:** frontend, backend, sicherheit, spaces, retrieval

---

<a id="issue-517"></a>

## Issue #517 — feat(library): Indizierte Dokumente für alle Quellentypen anzeigen — mit Paging und Stichwortsuche
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #540 (2026-08-19)

**Laut Issue:** Die Dokumentliste war nur für UPLOAD-Bibliotheken sichtbar, obwohl der Endpunkt Daten für alle Typen liefern würde; zudem lieferte er ein ungepagtes Array ohne Suche. Gefordert: Paging (`page`/`size`, Gesamtzahl), Stichwortsuche über den Dateinamen, sichtbar für alle Bibliothekstypen; Löschverhalten bei FILESYSTEM/HTTP_DIRECTORY (Rückkehr mit nächstem Lauf) im PR dokumentieren.

**Geliefert:** Wie gefordert. Endpunkt wechselt auf gepagte Antwort (`{ items, page, size, totalElements }`, Default-Seitengröße 20, max 100). Beim Review zeigte sich, dass RSS_FEED dasselbe Rückkehr-Problem hat wie FILESYSTEM/HTTP_DIRECTORY (`RssFeedIndexingExecutor#isUnchanged` erkennt eine Löschung nicht) — die Löschaktion wurde deshalb einheitlich für alle Konnektortypen ausgeblendet, nicht nur die beiden im Issue genannten.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/DocumentRepository.java` und `frontend/src/pages/LibraryDetailPage.tsx` enthalten die entsprechende Paging-/Suchlogik.

**Themen:** backend, frontend, spaces, retrieval, ux

---

<a id="issue-518"></a>

## Issue #518 — fix(indexing): RSS-Läufe zählen Feed-Einträge statt indizierter Dokumente — Anhänge fehlen in der Anzeige
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #534 (2026-08-19)

**Laut Issue:** `RssFeedIndexingExecutor` zählte Feed-Einträge statt tatsächlich indizierter Dokumente — Anhänge (bis zu 10 je Eintrag) tauchten in keinem Zähler auf. Die Anzeige „10 Dokumente verarbeitet" konnte damit den tatsächlichen Indexbestand systematisch unterschätzen. Gefordert: getrennte Zählung von Feed-Einträgen und Dokumenten, Anhänge auch beim Nachholen für unveränderte Einträge mitgezählt.

**Geliefert:** Wie gefordert. Neuer Zähler `documentsIndexedTotal` (Migration `030`), zählt Eintrag und jeden erfolgreich indizierten Anhang, auch beim Nachholen. Fehlgeschlagene/deduplizierte Anhänge erhöhen den Zähler nicht. Für FILESYSTEM/HTTP_DIRECTORY bleibt `documentsIndexedTotal` immer gleich `documentCount` (1 Datei = 1 Dokument), die dortige Anzeige bleibt unverändert korrekt. Frontend zeigt für RSS-Läufe „X Feed-Einträge, Y übersprungen, Z indiziert (N Dokumente insgesamt)". Reproduktionsnachweis mit drei roten/grünen Testfällen im PR dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingRunProgress.java` und `IndexingJob.java` existieren mit dem entsprechenden Zähler; `frontend/src/pages/LibraryDetailPage.tsx` enthält die getrennte Anzeige.

**Themen:** backend, frontend, bugfix, feeds, retrieval

---

<a id="issue-519"></a>

## Issue #519 — fix(deployment): nginx-Limit von 1 MB verursacht 413 beim Dokument-Upload im Compose-Setup
- Geschlossen: 2026-08-19 (completed)
- Labels: bug, frontend, size:S
- PRs: #532 (2026-08-19)

**Laut Issue:** Der nginx-Reverse-Proxy im Frontend-Container setzte kein `client_max_body_size`, der nginx-Default von 1 MB griff daher vor dem eigentlichen Backend-Limit von 50 MB — jeder Upload über 1 MB scheiterte im Compose-Setup mit HTML-413 statt der JSON-Fehlermeldung des Backends. Gefordert: Limit angleichen, Zusammenhang dokumentieren, Fehlerbehandlung einer nicht-JSON-413-Antwort robust machen.

**Geliefert:** Wie gefordert. `client_max_body_size 50m;` in `frontend/nginx.conf`, mit Kommentar zum Zusammenhang mit `OPAA_UPLOAD_MAX_FILE_SIZE` (kein automatisches Templating, da die Datei fest ins Image gebacken wird — beide Werte müssen manuell synchron gehalten werden). `normalizeError` übersetzt eine nicht-JSON-413-Antwort jetzt in eine deutsche Meldung, während die echte JSON-`ErrorResponse` des Backends unverändert vorrangig bleibt. Reproduktionsnachweis mit rotem/grünem Test im PR dokumentiert.

**Verifikation:** `frontend/nginx.conf` und `frontend/src/services/api.ts` existieren mit den beschriebenen Änderungen.

**Themen:** frontend, deployment, bugfix, upload

---

<a id="issue-520"></a>

## Issue #520 — feat(library): Ordner in Dokumentbibliotheken
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, epic, backend, frontend
- PRs: keine eigener PR (Epic mit sechs Sub-Issues #819–#824)

**Laut Issue:** Upload-Bibliotheken sollen Ordner erhalten, durch die man wie in einer Dateiablage navigiert: anlegen (auch leer), umbenennen, löschen, Dateien hineinladen. FILESYSTEM-Bibliotheken sollen ihre echte Verzeichnisstruktur als read-only Ordner abbilden. Konzeptentscheidung: echte Ordner-Entität (`library_folders`, `documents.folder_id`) statt virtueller Pfade; Ordner sind Navigation, keine Rechtegrenze (Grants bleiben bibliotheksweit); Retrieval bleibt im ersten Wurf ordnerunabhängig. Vier Phasen: Konzept/Spezifikation, Backend-Fundament, Frontend, Ausbau (Drag&Drop, FILESYSTEM-Abbildung).

**Geliefert:** Vollständig, laut Abschlusskommentar über sechs Sub-Issues: #819→PR #825 (ADR-0020 "Ordner als Navigation, keine Rechtegrenze" + Spezifikation), #820→PR #827 (Tabelle `library_folders`, `documents.folder_id`, Ordner-CRUD-API), #821→PR #828 (ordnerbewusste Dokumentliste, Breadcrumb, Upload mit `folderId`), #822→PR #830 (Frontend-Ordner-Navigation, `?folder`-URL-State, Anlegen/Umbenennen/Löschen mit Bestätigungsdialog), #824→PR #829 (FILESYSTEM-Struktur wird beim Indexierungslauf idempotent als read-only Ordner materialisiert), #823→PR #831 (Ordner-Upload per Drag&Drop/`webkitdirectory`, idempotente Zwischenordner). Das Issue wurde zwischenzeitlich aus Epic #458 herausgelöst und eigenständig weitergeführt, ohne den Epic-Abschluss zu blockieren.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryFolder.java`, `LibraryFolderChild.java`, `LibraryFolderDetail.java`, `LibraryFolderPaths.java`, `LibraryFolderRepository.java`, `LibraryFolderService.java` existieren im Worktree — konsistent mit der beschriebenen Lieferung.

**Themen:** wissensbibliotheken, ordner, backend, frontend, epic

---

<a id="issue-521"></a>

## Issue #521 — chore(library): System-Wissensbibliothek entfernen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #536 (2026-08-19)

**Laut Issue:** Die System-Wissensbibliothek (`LibraryOwnerType.SYSTEM`, `KnowledgeLibrary.SYSTEM_LIBRARY_ID`, gesät durch Migration 012) sollte samt Inhalt ersatzlos gelöscht werden — keine Datenmigration nötig. Ziel: `LibraryOwnerType` kennt kein `SYSTEM` mehr, keine Sonderlogik dafür im Code, Migrationstest für den Lösch-Changelog.

**Geliefert:** PR #536 liefert genau das: Liquibase-Changelog `031-delete-system-library.yaml` löscht Bibliothek und abhängige Zeilen (Vektorspeicher-Chunks, Indizierungsaufträge, Dokumente, Grants) in FK-Reihenfolge; `Migration031DeleteSystemLibraryTest` belegt es. `LibraryOwnerType.SYSTEM`, `SYSTEM_LIBRARY_ID`, `isSystemLibrary()` sowie alle Sonderfälle (Ablehnung in `createLibrary`, Löschsperre, Systemadmin-Bypass) sind entfernt. OpenAPI-Spec, generierte DTOs und Frontend nachgezogen. Doku (`spaces-and-assets.md`, `STATUS.md`) aktualisiert; historische Migrationsdokumente bewusst unverändert gelassen. Migrationsnummer im PR-Body abweichend als „030" benannt, tatsächlich als 031 gemergt (Kollision mit einem parallelen PR).

**Verifikation:** `LibraryOwnerType.java` im Worktree kennt nur noch `USER`/`GROUP`; der Javadoc dokumentiert die Entfernung von `SYSTEM` explizit. `KnowledgeLibraryService.java` enthält `SYSTEM` nur noch in einem historischen Javadoc-Kommentar, keine Codepfade mehr. Deckt sich mit der PR-Beschreibung.

**Themen:** spaces, library, migration, cleanup, epic-458

---

<a id="issue-522"></a>

## Issue #522 — chore(auth): Automatische persönliche Upload-Bibliothek beim Login entfernen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #546 (2026-08-19)

**Laut Issue:** Die automatische Anlage einer persönlichen Upload-Bibliothek bei Erstanmeldung (`ensurePersonalLibrary`, `personal`-Flag) sollte ersatzlos entfallen. Die Anlage des persönlichen Space bleibt unberührt. `personal`-Flag/Spalte, Sonderlogik und zugehörige Zugriffs-/Anzeigepfade sollten aus Schema, Code, Spec und Frontend entfernt werden, bestehende automatisch angelegte Bibliotheken als gewöhnliche nutzereigene Bibliotheken erhalten bleiben.

**Geliefert:** PR #546 setzt das um: `UserService#ensurePersonalAssetsAfterCommit` vereinfacht auf reine Space-Provisionierung; `ensurePersonalLibrary`, `insertPersonalLibraryIfAbsent`, `insertOwnerGrantForPersonalLibraryIfAbsent` vollständig entfernt. `personal`-Spalte per Migration 033 entfernt (inkl. partiellem Unique-Index). Sonderpfade (Löschsperre, Sichtbarkeitssperre, Grant-Sperre) entfernt. OpenAPI-Spec und Frontend-Anzeige nachgezogen, Doku an vier Stellen aktualisiert. Migrationstest `Migration033DropKnowledgeLibrariesPersonalFlagTest` vorhanden.

**Verifikation:** `KnowledgeLibraryService.java` enthält `ensurePersonalLibrary`/`personal` nur noch als historische Javadoc-Referenz auf #522, keine aktiven Codepfade. Deckt sich mit dem PR-Anspruch.

**Themen:** auth, library, spaces, migration, cleanup, epic-458

---

<a id="issue-523"></a>

## Issue #523 — Epic: Chats im Space und Suchbereich per @-Bibliotheksreferenzen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, epic, backend, frontend, size:L, workspace
- PRs: keine (Epic ohne direkt verknüpften PR)

**Laut Issue:** Chats sollen persistente, space-eigene Objekte werden; die wirkungslose Space-Auswahl im Suchfeld entfällt zugunsten eines Schalters „Wissen nutzen" plus sticky @-Bibliotheksreferenzen am Chat. Vier Phasen: Spezifikation, Backend (Chat-Persistenz + Suchbereichssteuerung parallel), Frontend (Chats im Space + neues Eingabefeld parallel), E2E. Abnahme auf Epic-Ebene: Chat übersteht Neustart, Space-Auswahl vollständig weg, @-Autocomplete mit stickyen Referenzen, Schalterverhalten testbar nachgewiesen, Spezifikation beschreibt den Stand, #205 auf Kollaborationsteil reduziert.

**Geliefert:** Kein PR ist direkt mit #523 verknüpft — das Epic wurde ausschließlich über seine Sub-Issues abgearbeitet, die in diesem Chunk vollständig vorliegen: #524 (Spezifikation, PR #531), #525 (Chat-Persistenz, PR #541), #526 (Suchbereichssteuerung Backend, PR #535), #527 (Chats im Space, Frontend, PR #548), #528 (@-Referenzen + Schalter, Frontend, PR #539), #529 (E2E-Abdeckung, PR #554). Alle sechs Sub-Issues sind „completed" mit gemergten PRs, die inhaltlich exakt die im Epic beschriebenen Phasen abdecken (Spec → Backend parallel → Frontend parallel → E2E). Die Epic-Abnahmekriterien sind damit durch die Summe der Sub-Issues erfüllt: Chat-Persistenz und -Neustart (#525/#527), Space-Auswahl entfernt (#526/#528), @-Autocomplete + sticky Chips (#528), Schalterverhalten per Test nachgewiesen (#526 Unit-Tests, #529 E2E), Spezifikation angepasst (#524). Der letzte Punkt („#205 auf Kollaborationsteil reduziert") liegt außerhalb dieses Chunks und lässt sich hier nicht verifizieren.

**Verifikation:** Kein eigener Code-Realitätscheck nötig — ergibt sich aus der Verifikation der Sub-Issues #524–#529, die alle im Worktree bestätigt werden konnten (Chat-Backend, Frontend-Routen/Komponenten, E2E-Datei vorhanden).

**Themen:** epic, chats, spaces, retrieval, projektsetup

---

<a id="issue-524"></a>

## Issue #524 — Spezifikation an Chat-im-Space und @-Bibliotheksreferenzen anpassen
- Geschlossen: 2026-08-19 (completed)
- Labels: documentation, size:S
- PRs: #531 (2026-08-19)

**Laut Issue:** `docs/features/spaces-and-assets.md` sollte um die im Epic #523 entschiedene Semantik erweitert werden (Schalter „Wissen nutzen", @-Referenzen sticky pro Chat, Übergangsregel bis #203), `docs/features/user-frontends.md` auf das neue Modell umgeschrieben, `docs/STATUS.md` ergänzt und `docs/CONCEPTS.md` auf Ergänzungsbedarf geprüft werden. Reine Dokumentationsänderung, keine Codeänderung.

**Geliefert:** PR #531 liefert exakt das: Abschnitte „Chats" und „Suchbereich je Chatart" in `spaces-and-assets.md` erweitert; `user-frontends.md`-Abschnitt „Dokumentenübersicht, Gesprächsverwaltung und Suchfilter" umgeschrieben; `STATUS.md` ergänzt um Epic #523; `CONCEPTS.md` erhält neuen Glossareintrag „Suchbereich eines Chats". Zusätzlich (nicht explizit gefordert, aber sachlich naheliegend) wurde `docs/features/agents-and-tools.md` mitgeändert.

**Verifikation:** Reine Dokumentationsänderung, per Grep nicht sinnvoll gegen Code zu prüfen; Dateien existieren im Repo. Deckt sich mit den Folge-Issues (#525–#529), die gegen genau diese Spezifikation implementiert wurden.

**Themen:** doku, chats, retrieval, spaces, epic-523

---

<a id="issue-525"></a>

## Issue #525 — Persistente Chats in genau einem Space (Grundlage, ausschließlich privat)
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:L, workspace
- PRs: #541 (2026-08-19)

**Laut Issue:** Ein Chat soll ein persistentes Objekt in genau einem Space werden (Tabellen `chats`/`chat_messages`), mit Endpunkten zum Erstellen, Auflisten, Lesen, Patchen und Löschen. Anbindung an `POST /api/v1/query` über `chatId` statt `conversationId`, Persistierung von Frage/Antwort/Quellen als `ChatMessage`. Zugriff nur für den Autor, auch nicht für Space-/System-Admins. Migrationstest gefordert.

**Geliefert:** PR #541 liefert die Tabellen (Migration 032, wegen Kollisionen mit parallelen PRs zweimal umnummeriert), die fünf Endpunkte, die Query-Anbindung inklusive Rehydrierung des Gesprächsverlaufs aus persistierten Nachrichten bei kaltem Cache. Der PR-Body dokumentiert zwei Review-Runden mit gravierenden Funden, die vor dem Merge behoben wurden: (1) Persistenz griff wegen `@Transactional(readOnly = true)` zunächst gar nicht — Reproduktionsnachweis mit rotem/grünem Test vorhanden; (2) Cache-Schlüssel-Leck zwischen Nutzern über den Caffeine-Cache — ebenfalls mit Reproduktionsnachweis behoben; (3) Verbindungspool-Deadlock-Risiko durch verschachtelte Transaktionen — behoben nach dem `SpaceService#ensureDefaultSpace`-Muster (`NOT_SUPPORTED` + `REQUIRES_NEW` via `TransactionTemplate`). Space-Löschen mit vorhandenen Chats liefert jetzt 409 statt Constraint-Verletzung (Vorgriff auf das später separat behobene #543-Problem: Space mit fremden Chats blieb dadurch dauerhaft unlöschbar). Koordination mit parallel gemergten #526/#528 wird im PR-Body ausführlich dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/chat/` enthält im Worktree `Chat.java`, `ChatMessage.java`, `ChatMessageRepository.java`, `ChatRepository.java`, `ChatRole.java`, `ChatService.java`, `ChatStatus.java` sowie zusätzlich `ChatConfiguration.java`, `ChatTitleGenerationService.java`, `TitleSource.java` (spätere Erweiterungen). Deckt sich mit dem PR-Anspruch.

**Themen:** chats, spaces, retrieval, backend, migration, epic-523

---

<a id="issue-526"></a>

## Issue #526 — Suchbereich über Bibliotheksreferenzen und Schalter „Wissen nutzen“ im Query-Endpunkt
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #535 (2026-08-19)

**Laut Issue:** `QueryRequest.spaceIds` wird vom Backend komplett ignoriert und sollte ersatzlos entfallen. Stattdessen `useKnowledge` (Default true) und `libraryIds`: bei `true` alle lesbaren Bibliotheken (wie bisher), bei `false` nur `libraryIds ∩ lesbare`, bei leerer Schnittmenge kein Retrieval und Kennzeichnung in den Antwort-Metadaten. Frontend nur so weit anfassen, wie für einen grünen Build nötig.

**Geliefert:** PR #535 liefert genau diese Filterlogik in `QueryService`, `spaceIds` aus Spec und DTOs entfernt, neues Feld `QueryResponse.metadata.answeredWithoutKnowledge`. Frontend minimal angepasst (`services/api.ts` sendet nur `useKnowledge: true`, `spaceIds`-Parameter aus `chatStore` entfernt); die eigentliche UI-Space-Auswahl blieb bewusst vorerst stehen (wirkungslos), der Umbau war explizit Issue #528. Vier geforderte Testfälle sind laut PR-Body als eigene Testmethoden in `QueryServiceTest` vorhanden.

**Verifikation:** `QueryService.java` im Worktree führt `useKnowledge`/`requestedLibraryIds`-Parameter und die beschriebene Verzweigungslogik (aktueller Code, nach späteren Anpassungen durch #525 leicht erweitert um Chat-Vorrang, aber die Grundlogik aus #526 ist erkennbar erhalten).

**Themen:** retrieval, chats, spaces, backend, epic-523

---

<a id="issue-527"></a>

## Issue #527 — Chats unterhalb von Spaces führen (Routen, Chatliste, persistenter Verlauf)
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L, workspace
- PRs: #548 (2026-08-20)

**Laut Issue:** Route `/spaces/:spaceId/chats/:chatId` statt globaler `/chat`-Route (kein toter Link), Chatliste in Space-Seite/Sidebar mit Umbenennen/Löschen, `chatStore`-Umbau auf API-gestützten Verlauf mit Wiederherstellung nach Neuladen, impliziter Chat-Einstieg ohne vorhandenen Chat.

**Geliefert:** PR #548 liefert die Route inklusive `ChatRedirect`-Komponente (Redirect auf Default-Space + letzten Chat, oder auf `.../chats/new` als virtuellen, noch nicht persistierten Chat), eine wiederverwendete `ChatList`-Komponente in `SpacePage` und `Sidebar`, Umbenennen/Löschen mit `window.confirm`, `chatStore`-Umbau inkl. Persistierung von Schalter/Chips aus #528 sobald ein Chat existiert. Neue Store `chatListStore`, neue API-Funktionen. Dokumentation (`user-frontends.md`) nachgezogen.

**Verifikation:** `frontend/src/components/chat/ChatList.tsx` und `frontend/src/pages/ChatRedirect.tsx` (referenziert im PR) existieren im Worktree; `frontend/src/components/chat/` enthält im aktuellen Stand auch `MessageList.tsx`, `SourceEvidenceDrawer.tsx`, `SourceFootnotes.tsx`, `citations.ts` — spätere Erweiterungen über diesen PR hinaus, aber konsistent mit dem hier gelegten Fundament.

**Themen:** chats, spaces, frontend, epic-523

---

<a id="issue-528"></a>

## Issue #528 — @-Bibliotheksreferenzen und Schalter „Wissen nutzen“ im Eingabefeld; Space-Filter entfernen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #539 (2026-08-19)

**Laut Issue:** Die wirkungslose Space-Mehrfachauswahl im Eingabefeld soll ersatzlos entfallen. Stattdessen Schalter „Wissen nutzen" (Default an, mit Hinweis bei Aus-Zustand ohne Referenzen), @-Autocomplete für lesbare Bibliotheken, sticky entfernbare Chips, die persistiert werden sobald die Chat-Persistenz verfügbar ist. Tastaturbedienbarkeit gefordert.

**Geliefert:** PR #539 liefert Space-Filter-Entfernung (Popover, `chatFilterSpaceIds` aus `spaceStore`), Schalter mit Hinweistext bei `answeredWithoutKnowledge`, @-Autocomplete über den bereits vorhandenen `libraryStore` (#421) mit Pfeiltasten/Enter/Escape und ARIA-Rollen, sticky Chips mit auf spätere Persistenz vorbereiteter Store-Schnittstelle (die dann in #527 tatsächlich angebunden wurde). Dokumentation nachgezogen.

**Verifikation:** `frontend/src/components/chat/ChatInput.tsx` existiert im Worktree und ist laut Dateiliste zentraler Ort der Änderung. Keine weitere Tiefenprüfung nötig, Feature baut konsistent auf #526 (Backend-API) und #527 (Persistenz) auf.

**Themen:** chats, retrieval, frontend, spaces, epic-523

---

<a id="issue-529"></a>

## Issue #529 — E2E-Abdeckung: Chat im Space, @-Referenzen und Wissens-Schalter
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, size:M, ci
- PRs: #554 (2026-08-20)

**Laut Issue:** Fünf E2E-Szenarien über den Docker-Compose-Stack: Chat im Space mit Neuladen-Persistenz, @-Referenz schränkt die Suche ein, Antwort ohne Wissensbasis bei leerem Referenz-Set, Rechte-Negativfall (nicht lesbare Bibliothek erscheint nicht in Vorschlägen), mehrere Chats mit getrenntem Verlauf/Referenzen. Kein Szenario darf von echter LLM-Ausgabe abhängen; CI soll grün bleiben.

**Geliefert:** PR #554 liefert alle fünf Szenarien in `e2e/tests/space-chats.spec.ts`, mit wiederverwendeten Hilfsfunktionen aus `e2e/fixtures/chat.ts` (aus `knowledge-libraries.spec.ts` extrahiert). Der PR-Body dokumentiert mehrere Nachbesserungsrunden nach echten CI-Läufen: Fixture-Namenskollisionen, Sortierposition/Korpusverschmutzung bei ungescopter Suche, eine Race-Bedingung in `startFreshChat` gegen bereits geladene Chats, und einen Dev-Auth-Identitätsverlust nach `?devUser=`-Navigation. Zwei der gefundenen Produktverhaltensweisen (Race in `chatStore.ts` um `isLoadingChat`, möglicher `devUser`-Verlust) wurden explizit **nicht** in diesem PR behoben, sondern als eigene Befunde an Koordination/Maintainer gemeldet — laut PR-Body nicht als eigenständige Issues, sondern zur weiteren Untersuchung.

**Verifikation:** `e2e/tests/space-chats.spec.ts` existiert im Worktree. Keine Anhaltspunkte im Chunk, ob die zwei gemeldeten Frontend-Befunde (Race-Bedingung, devUser-Verlust) später als eigene Issues aufgegriffen wurden — das liegt außerhalb dieses Chunks.

**Themen:** ci, e2e, chats, retrieval, epic-523

---

<a id="issue-533"></a>

## Issue #533 — Veraltete Space-Arten und „Ablegen“-Terminologie in CONCEPTS.md bereinigen
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, size:S
- PRs: #568 (2026-08-20)

**Laut Issue:** `docs/CONCEPTS.md` beschrieb noch drei Space-Arten (Persönlich/Projekt/Team) und Sichtbarkeit durch „Ablegen" (DRAFT/PLACED) — beides seit #333 überholt (eine Space-Art über `isDefault`/`memberSource`, PRIVATE/SHARED-Terminologie „in den Space geteilt"). Reine Dokumentationsänderung, Abgleich mit `spaces-and-assets.md` gefordert, plus Prüfung umliegender Glossareinträge.

**Geliefert:** PR #568 gleicht den Space-Abschnitt in `CONCEPTS.md` an `spaces-and-assets.md` an und passt umliegende Glossareinträge an (Beispiele, System-Admin-Eintrag, Schnellreferenz), die implizit noch von einer eigenen „Team-Space"-Art ausgingen. Einzige geänderte Datei ist `docs/CONCEPTS.md`, wie im Issue gefordert.

**Verifikation:** `docs/CONCEPTS.md` im Worktree enthält keine Treffer mehr für „Persönlich/Projekt/Team" oder „Ablegen"; stattdessen `isDefault`/`memberSource` als aktuelle Attribute dokumentiert. Deckt sich mit dem PR-Anspruch.

**Themen:** doku, spaces, cleanup

---

<a id="issue-538"></a>

## Issue #538 — security(indexing): HTTP-Client folgt Redirects und sendet dabei den Authorization-Header weiter
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, size:S, security
- PRs: #579 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #537 stammender Befund: Die über `AutoindexCrawlerService.buildHttpClient` gebauten HTTP-Clients folgten Redirects (`Redirect.NORMAL`), während der `Authorization`-Header aus hinterlegten Quell-Zugangsdaten selbst gesetzt wird — bei einer Umleitung auf einen fremden Host würden die Zugangsdaten mitgeschickt. Betroffen: `UrlIndexingExecutor`, `RssFeedIndexingExecutor`, `UrlFileDownloader`, `SourceConnectionTestService`. Gefordert: Redirects nicht mehr blind folgen (`Redirect.NEVER` mit kontrollierter manueller Behandlung oder Header nur bei gleichem Host/Origin weitergeben).

**Geliefert:** `buildHttpClient` nutzt jetzt `Redirect.NEVER`; ein neuer Helfer `AutoindexCrawlerService.sendFollowingRedirects` folgt manuell, höchstens fünf Hops, und reicht `Authorization` nur bei gleichem Origin (Schema+Host+Port) weiter. Alle vier Aufrufstellen sind umgestellt. Nach einem Security-Review-Nachtrag wurden zwei zusätzliche Lücken geschlossen: Der Origin-Vergleich ignorierte ursprünglich den Port, und ein Protokoll-Downgrade (https→http) wurde nicht abgelehnt — beides per gemeinsamer `sameOrigin`-/`isSchemeDowngrade`-Methode korrigiert. Bemerkenswert: Der PR-Body hält fest, dass JDK 21 den `Authorization`-Header bei automatischem `Redirect.NORMAL` bei Host-Wechsel bereits selbst entfernte — das ursprüngliche Leck ließ sich auf dieser JDK-Version nicht wörtlich reproduzieren; der tatsächlich nachgewiesene Fehler war, dass eine naive Umstellung auf `Redirect.NEVER` ohne begleitende manuelle Redirect-Behandlung legitime Same-Host-Redirects gebrochen hätte.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AutoindexCrawlerService.java` existiert im Worktree und enthält `sameOrigin`/`Redirect.NEVER`-Logik (15 Treffer für die Suchbegriffe). Umsetzung nachvollziehbar vorhanden.

**Themen:** security, indexing, retrieval, http-client

---

<a id="issue-543"></a>

## Issue #543 — Space mit fremden privaten Chats ist dauerhaft unlöschbar
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #613 (2026-08-20)

**Laut Issue:** Mit #525 schützt `fk_chats_space` (RESTRICT) private Chats vor fremder Löschung — ein Space mit Chats lässt sich nicht löschen (409). Das erzeugt ein Betriebsloch: Der Space-Eigentümer sieht fremde private Chats nicht und kann sie nicht entfernen, ein Space mit irgendeinem Chat ist damit nie wieder löschbar. Im Ticket wurden drei Lösungsoptionen zur Entscheidung gestellt: Archivieren statt Löschen, private Chats beim Löschen in den Default-Space des Autors verschieben, oder Löschung mit Frist + Benachrichtigung.

**Geliefert:** Maintainer-Entscheidung fiel auf „Archivieren statt Löschen". Neuer Endpunkt `POST /api/v1/spaces/{spaceId}/archive` (Owner/System-Admin, idempotent, blockiert für den Standard-Space); `archived`-Feld an `SpaceResponse`/`SpaceListResponse`; Migrationen 037 (Spalte) und 038 (Audit-Enum `SPACE_ARCHIVED`). Ein archivierter Space nimmt keinen neuen Chat mehr an (409 bei `createChat`), wird aus `listSpaces` ausgeblendet außer für Mitglieder mit eigenem Chat darin, und bestehende Chats bleiben für ihren Autor lesbar. Echtes Löschen bleibt möglich, sobald kein Chat mehr im Space liegt. Frontend erhält eine „Space archivieren"-Aktion und eine „Archiviert"-Kennzeichnung. Spezifikation entsprechend ergänzt.

**Verifikation:** Migrationsdatei `backend/src/main/resources/db/changelog/changes/039-add-archived-to-spaces.yaml` existiert im Worktree; `SpaceService.java` enthält 17 Treffer für „archive". Umsetzung bestätigt vorhanden.

**Themen:** spaces, workspace, chats, betrieb, löschsperre

---

<a id="issue-544"></a>

## Issue #544 — feat(library): Verbindungstest auch im Bearbeiten-Dialog der Quellkonfiguration
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:S
- PRs: #615 (2026-08-20)

**Laut Issue:** Der Verbindungstest aus #514 (`POST /api/v1/libraries/source-test`) stand nur im Erstellungsdialog zur Verfügung, nicht im Bearbeiten-Dialog (#516). Nicht trivial nachrüstbar, da `SourceConnectionTestRequest` keine `libraryId` kannte und Zugangsdaten im Klartext erwartete — eine bestehende passwortgeschützte Quelle ließe sich ohne Neueingabe nicht testen. Gefordert: optionale `libraryId` im Request (Spec zuerst), die bei fehlenden neuen Zugangsdaten serverseitig die gespeicherten Zugangsdaten der Bibliothek verwendet (mind. MANAGER-Rolle), plus „Verbindung testen"-Button im `EditLibrarySourceDialog`.

**Geliefert:** Genau wie gefordert umgesetzt: optionale `libraryId`, Fallback auf gespeicherte Zugangsdaten nur bei leerem Zugangsdaten-Feld, Berechtigungsprüfung über `LibraryAccessService#requireRole` (404 ohne Zugriff, 403 bei zu geringer Rolle, `systemAdmin`-Bypass analog `updateLibrary`), `sourceType`-Konsistenzprüfung. Same-Origin-Regel für den Zugangsdaten-Fallback wurde in eine gemeinsame Klasse `SourceOriginMatcher` extrahiert. In der ersten Review-Runde wurde ein kritischer Bug behoben: `SourceOriginMatcher` verglich Hosts ursprünglich per `Objects.equals`, was bei Hostnamen mit Unterstrich (`URI.getHost()` liefert dann `null`) zwei völlig verschiedene Hosts fälschlich als gleichen Origin durchgehen ließ — Fix per Delegation an `AutoindexCrawlerService#sameOrigin`. Ebenfalls nachgebessert: fehlender `systemAdmin`-Durchgriff. Ein bekannter Proxy/insecureSsl-Exfiltrationsweg wurde bewusst nicht in diesem PR behoben, sondern als separates Follow-up-Issue vermerkt (keine Nummer im Body genannt).

**Verifikation:** `backend/src/main/java/io/opaa/library/SourceOriginMatcher.java` existiert im Worktree.

**Themen:** library, retrieval, auth, security, ui

---

<a id="issue-545"></a>

## Issue #545 — fix(audit): Änderung der Quellkonfiguration erzeugt keinen Audit-Eintrag
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, size:S, security
- PRs: #578 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #542 stammender, seit #476 vorbestehender Befund: Eine reine Quellkonfigurations-Änderung über `PUT /api/v1/libraries/{libraryId}` (URL, Pfad, Proxy, Zugangsdaten) erzeugte keinen Audit-Eintrag, da `KnowledgeLibraryService#updateLibrary` nur Name/Beschreibung/Sichtbarkeit/`listed` protokollierte. Gefordert: eigenes Audit-Ereignis, ohne sensible Werte zu protokollieren (nur welche Felder geändert wurden).

**Geliefert:** Neues Audit-Ereignis `LIBRARY_SOURCE_UPDATED`, das ausschließlich protokolliert, welche Felder (`sourcePath`, `sourceUrl`, `sourceProxy`, `sourceCredentials`, `sourceInsecureSsl`) geändert wurden — nie die Werte selbst, gemäß ADR-0018. Migration 035 weitet `chk_audit_log_event_type`. Reproduktionsnachweis erbracht (roter Test ohne Fix, grün mit Fix).

**Verifikation:** `backend/src/main/java/io/opaa/audit/AuditEventType.java` enthält `LIBRARY_SOURCE_UPDATED`.

**Themen:** audit, security, library, doku

---

<a id="issue-547"></a>

## Issue #547 — test(e2e): E2E-Abdeckung für Upload-Limit, Verbindungstest, Dokumentliste und Quellkonfig-Bearbeitung
- Geschlossen: 2026-08-20 (completed)
- Labels: backend, frontend, size:M
- PRs: #549 (2026-08-20)

**Laut Issue:** Die Nacharbeiten-Serie aus Epic #458 (#514, #516, #517, #519) hatte nutzersichtbares Verhalten ergänzt, das die E2E-Suite noch nicht abdeckte. Gefordert waren vier Szenarien: (1) Upload > 1 MB durch den echten nginx (Regressionsschutz für `client_max_body_size`), (2) Verbindungstest im Erstellungsdialog (Happy Path + Fehlerfall), (3) Dokumentliste mit Paging und Suche, (4) Quellkonfiguration bearbeiten (URL-Änderungshinweis, Credentials-Semantik). Explizit außerhalb des Umfangs: Negativtest „Erstanmeldung erzeugt keine Bibliothek" (#522) und RSS-Lauf-Abschlussmeldung (#518).

**Geliefert:** Neue Spec-Datei `e2e/tests/knowledge-library-nacharbeiten.spec.ts` deckt alle vier geforderten Szenarien ab. Bemerkenswerte technische Details: PDF für den Upload-Test wird zur Laufzeit mit `pdf-lib` und pseudo-zufälligem Text erzeugt (damit es trotz Kompression ~2 MB bleibt); ein neues statisches Fixture bildet das Apache-„HTMLTable"-Autoindex-Layout nach, da der Standard-`mod_autoindex` ohne diese Option nur eine `<pre>`-Liste liefert, die der Parser zu diesem Zeitpunkt nicht verstand (führte in der Folge zu Issue #550); Dateiname bewusst `knowledge-library-nacharbeiten.spec.ts` (Singular) statt `knowledge-libraries-...`, um die alphabetische Ausführungsreihenfolge der Playwright-Suite nicht zu stören. Ein begleitendes `aria-label` in `LibraryDetailPage.tsx` wurde für einen stabilen Pagination-Selektor ergänzt.

**Verifikation:** `e2e/tests/knowledge-library-nacharbeiten.spec.ts` existiert im Worktree.

**Themen:** e2e, library, retrieval, testing, ci

---

<a id="issue-550"></a>

## Issue #550 — feat(indexing): HTTP_DIRECTORY versteht nur das HTMLTable-Autoindex-Layout
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M
- PRs: #612 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #549 stammender Befund: `AutoindexCrawlerService.parseDirectory` verstand ausschließlich das Apache-`HTMLTable`-Autoindex-Layout; ein Standard-`mod_autoindex` ohne diese Option lieferte eine `<pre>`- bzw. `<ul>`-Liste, an der der Parser scheiterte. Nutzerwirkung: eine erreichbare Verzeichnisseite meldete „0 unterstützte Dokumente gefunden" ohne Ursachenhinweis. Gefordert (mindestens eines, idealerweise beides): Parser um gängige Layouts (`<pre>`, `<ul>`, ggf. nginx-autoindex) erweitern, sowie Voraussetzung in Doku/UI dokumentieren und die Meldung schärfen.

**Geliefert:** Beides umgesetzt. Der Parser probiert zuerst das HTMLTable-Layout und fällt sonst auf eine linkbasierte Erkennung zurück, die jeden `<a href>` auswertet (Apache `<pre>`, nginx `autoindex on`, einfache `<ul>`-Listen wie Pythons `http.server`). `SourceConnectionTestService` unterscheidet jetzt „erreichbar, aber leeres Verzeichnis" von „erreichbar, aber kein erkennbares Verzeichnislisting". `docs/features/knowledge-sources.md` listet die vier unterstützten Layouts. Der PR-Body vermerkt einen zum Merge-Zeitpunkt bestehenden, unabhängigen Build-Blocker auf `main` (Issue #609), der nur lokal zur Verifikation umgangen wurde, nicht Teil dieses Commits war.

**Verifikation:** `AutoindexCrawlerService.java` enthält 10 Treffer für HTMLTable/linkbasiert/parseDirectory-Logik im Worktree.

**Themen:** indexing, retrieval, library, doku

---

<a id="issue-551"></a>

## Issue #551 — fix(library): Verbindungstest-Meldungen ohne Umlaute und mit falschem Singular
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #571 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #549 stammender, vorbestehender Befund: Die nutzersichtbaren Meldungen des Verbindungstests (`SourceConnectionTestService`) verwendeten Ersatzschreibweisen statt Umlauten („unterstuetzte", „zulaessige Groesse") und waren im Singular grammatisch falsch („1 unterstuetzte Dokument"). AGENTS.md verlangt korrektes Deutsch für nutzerseitige API-Meldungen. Hinweis im Ticket: der E2E-Test aus #547 schreibt den Wortlaut zeichengenau fest und muss mitgezogen werden; verwandt mit #448 (gleiche Fehlerklasse im Grants-Backend).

**Geliefert:** Alle betroffenen Meldungen auf echte Umlaute und korrekte Singular-/Pluralformen umgestellt, neue Hilfsmethode `supportedDocumentPhrase(count)` für konsistente Adjektiv-Kongruenz. MSW-Mock und E2E-Spec-Datei entsprechend mitgezogen. Reproduktionsnachweis mit konkretem Vorher/Nachher-Vergleich erbracht.

**Verifikation:** `SourceConnectionTestService.java` enthält `supportedDocumentPhrase` (2 Treffer) im Worktree.

**Themen:** library, doku, sprachqualität, retrieval

---

<a id="issue-552"></a>

## Issue #552 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, evaluation
- PRs: #563 (2026-08-20)

**Laut Issue:** Automatisch von `app/github-actions` erzeugtes Issue: Der nächtliche Retrieval-Regressionslauf schlug fehl, ohne einen Report zu erzeugen — vermutlich vor der Baseline-Prüfung abgebrochen (Manifest- oder Ein-Chunk-Invariante-Verletzung) oder durch Zeitlimit. Kein inhaltlicher Befund im Issue selbst, nur der Workflow-Link.

**Geliefert:** Ursachenanalyse ergab: PR #536 (System-Bibliothek entfernen, #521) hatte den Retrieval-Harness bereits auf eine eigene Eval-Zielbibliothek umgestellt, dabei aber implizit den Quellentyp `UPLOAD` gesetzt. Das kollidierte mit dem später gemergten ADR-0018/#478 („Indizierung je Bibliothek"), das jede Indizierung einer `UPLOAD`-Bibliothek mit 409 ablehnt. Fix: Der Harness legt die Eval-Zielbibliothek jetzt explizit als `FILESYSTEM`-Bibliothek mit `sourcePath=corpusWorkingDir` an und trägt den Pfad in die Filesystem-Allowlist ein. Fachliche Aussagekraft der Messung blieb unverändert (Korpus, Suchpfad, Messvertrag identisch) — die Messwerte weichen von der Baseline nur im Rundungsrauschen ab, keine Baseline-Anpassung nötig. Klassisches Beispiel einer durch parallele, sich überschneidende PRs entstandenen Integrationslücke.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/RetrievalEvaluationHarnessTest.java` enthält im Worktree die `FILESYSTEM`/`corpusWorkingDir`-Logik (Zeilen ~201/306-311).

**Themen:** evaluation, retrieval, ci, library

---

<a id="issue-553"></a>

## Issue #553 — PATCH-Anfragen scheitern mit 403: Methode fehlt in der CORS-Konfiguration, Frontend-Proxy überschreibt X-Forwarded-Proto
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #555 (2026-08-20)

**Laut Issue:** Auf der Testinstallation scheiterten „Chat umbenennen" und „Wissen nutzen"-Umschalten (beides `PATCH /api/v1/chats/{chatId}`) mit HTTP 403. Zwei verifizierte Ursachen: (1) `SecurityCorsConfig#corsConfigurationSource` erlaubte nur `GET, POST, PUT, DELETE, OPTIONS` — `PATCH` fehlte, sodass Springs CORS-Prüfung mit 403 ablehnte, bevor Authentifizierung greift; (2) `frontend/nginx.conf` überschrieb das vom äußeren Reverse-Proxy gesetzte `X-Forwarded-Proto: https` mit dem eigenen `$scheme` (http) auf dem inneren Hop, wodurch das Backend same-origin-Anfragen fälschlich als cross-origin behandelte und die CORS-Prüfung überhaupt erst anwendete. Die E2E-Suite fängt das nicht, da dort alles ohne TLS auf einem Origin läuft.

**Geliefert:** Genau wie im Issue beschrieben behoben: `PATCH` in die erlaubten CORS-Methoden aufgenommen; `nginx.conf` reicht ein eingehendes `X-Forwarded-Proto` jetzt durch (Fallback `$scheme` ohne äußeren Proxy). Empirische Verifikation vor dem Fix im PR-Body dokumentiert (PATCH mit Origin → 403, ohne Origin → 401, DELETE mit Origin → 401). Neuer Test `SecurityCorsConfigTest` lässt die echte Konfiguration durch Springs `DefaultCorsProcessor` laufen, inkl. Negativtest für fremde Origins.

**Verifikation:** `backend/src/main/java/io/opaa/auth/SecurityCorsConfig.java` enthält `PATCH` in der Methodenliste (Zeile 21 im Worktree).

**Themen:** auth, cors, deployment, chats, security

---

<a id="issue-556"></a>

## Issue #556 — Sidebar-Chatliste folgt nicht dem ausgewählten Space
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #558 (2026-08-20)

**Laut Issue:** Auf der Testinstallation beobachtet: Wählt man in der Space-Übersicht einen anderen Space aus, wechselt die Sidebar-Chatliste nicht mit, sondern zeigt weiter die Chats des vorherigen Space. Erst das Öffnen eines Chats in der Space-Detailansicht bringt die Liste in Sync. Vermutung im Ticket: `Sidebar`/`ChatList` binden die Liste an den Space des aktiven Chats (`chatStore.spaceId`) statt an den ausgewählten Space (`spaceStore.selectedSpaceId`), oder der `chatListStore` lädt beim Space-Wechsel nicht neu.

**Geliefert:** Die Vermutung aus dem Issue traf im Kern zu — genauer: `Sidebar` band an `chatStore.spaceId` (Space des zuletzt geöffneten Chats). Fix: `Sidebar` liest `:spaceId` jetzt direkt aus dem Route-Match (`useParams`), das bei allen space-bezogenen Routen konsistent gesetzt ist und sich sofort mit der Navigation ändert; Fallback auf den Default-Space bleibt auf Routen ohne `:spaceId`. Reproduktionsnachweis mit rotem/grünem Testlauf in `Sidebar.test.tsx` erbracht.

**Verifikation:** `frontend/src/layouts/Sidebar.tsx` importiert und nutzt `useParams` (Zeilen 25/55 im Worktree).

**Themen:** frontend, spaces, chats, workspace, routing

---

<a id="issue-557"></a>

## Issue #557 — Chat-Titel nach der ersten Antwort per LLM ermitteln
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #561 (2026-08-20)

**Laut Issue:** Nach der ersten Antwort in einem neuen Chat soll das System per LLM einen kurzen, deutschen Titel ermitteln (statt des mechanischen Präfix-Titels), außer der Nutzer hat bereits selbst einen Titel gesetzt. Die Generierung darf die Antwortzeit nicht verzögern; LLM-Fehler dürfen die Antwort nicht beeinträchtigen (Fallback Präfix-Titel).

**Geliefert:** `ChatTitleGenerationService` generiert den Titel asynchron (`@Async` auf eigenem `chatTitleTaskExecutor`), ausgelöst nachdem `ChatService#appendTurn` die Antwort samt Präfix-Fallback committed hat. Titelherkunft wird als `chats.title_source` (`GENERATED`/`CUSTOM`) persistiert (Migration 034); ein nutzergesetzter Titel wird nie überschrieben, auch nicht im Race-Fenster während laufender Generierung (per Test belegt, mit Rot/Grün-Reproduktionsnachweis). `QueryResponse` liefert den aktuellen Titel synchron im neuen Feld `chatTitle` mit. Frontend übernimmt den Titel sofort und lädt nach 2,5 s einmalig nach, um den fertig generierten LLM-Titel zu übernehmen. Deckt die Abnahmekriterien vollständig ab.

**Verifikation:** `backend/src/main/java/io/opaa/chat/ChatTitleGenerationService.java` und `TitleSource.java` existieren im Worktree.

**Themen:** chat, retrieval, llm, frontend, backend

---

<a id="issue-559"></a>

## Issue #559 — Chat-Seite bleibt im Lade-Spinner hängen, wenn „Neuer Chat“ einen laufenden loadChat unterbricht
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #562 (2026-08-20)

**Laut Issue:** In einem im E2E-Lauf beobachteten Fehlerbild bleibt `<main>` dauerhaft im `CircularProgress` hängen, wenn `startNewChat()` einen laufenden `loadChat()` per Sequenz-Token überholt — der überholte Handler kehrt vor seinem `set()` zurück, sodass niemand `isLoadingChat` zurücksetzt. Erwartet wird ein Fix mit Rot/Grün-Reproduktionsnachweis, sowie eine Prüfung, ob derselbe Pfad auch bei schnellem Wechsel zwischen zwei Chats auftritt.

**Geliefert:** `startNewChat()` setzt `isLoadingChat: false` jetzt direkt im eigenen synchronen `set()`, statt darauf zu warten, dass der überholte `loadChat`-Handler es irgendwann tut. Den zweiten im Issue genannten Fall (loadChat überholt loadChat) deckte laut PR bereits ein bestehender Test ab, da dort die zuletzt aufgelöste Anfrage `isLoadingChat` selbst korrekt zurücksetzt. Rot/Grün-Nachweis mit konkreter Fehlermeldung in der PR-Beschreibung enthalten.

**Verifikation:** `frontend/src/stores/chatStore.ts` existiert im Worktree und enthält weiterhin die Sequenz-/Guard-Logik (siehe auch #565/#573, die auf demselben Muster aufbauen).

**Themen:** chat, frontend, bugfix, race-condition

---

<a id="issue-560"></a>

## Issue #560 — Suchbereich als Chip-Leiste: @Alles-Wissen statt Schalter „Wissen nutzen“
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, enhancement, frontend, size:M
- PRs: #564 (2026-08-20)

**Laut Issue:** Der Suchbereich eines Chats soll ausschließlich über die Chip-Leiste am Eingabefeld gesteuert werden; der Schalter „Wissen nutzen“ entfällt. Drei Zustände: Standard-Chip @Alles-Wissen (alle lesbaren Bibliotheken), konkrete Bibliotheks-Chips (nur referenzierte ∩ lesbare) oder leere Leiste (kein Retrieval, mit Hinweis). Der erste konkrete Chip ersetzt @Alles-Wissen und umgekehrt. Spezifikation und E2E-Suite sollen im selben PR nachgezogen werden.

**Geliefert:** `chatStore` wurde auf `scope: 'all' | 'libraries' | 'none'` umgestellt, mit der beschriebenen Ersetzungslogik und atomarer PATCH-Persistierung. `ChatInput` entfernt den Schalter, bietet @Alles-Wissen immer als ersten Autocomplete-Eintrag, jeder Chip ist entfernbar, leere Leiste zeigt Hinweistext mit Ein-Klick-Rückweg. Backend blieb unverändert, da das bestehende `useKnowledge`/`referencedLibraryIds`-Schema alle drei Zustände bereits abdeckt. Spezifikation (`spaces-and-assets.md`, `user-frontends.md`, `agents-and-tools.md`, `CONCEPTS.md`, `STATUS.md`) wurde nachgezogen. E2E-Anpassung (`space-chats.spec.ts`) war zum PR-Zeitpunkt noch nicht möglich, da die betroffene Datei erst mit dem parallel laufenden PR #554 entstand — laut PR-Beschreibung sollte ein Rebase nach dessen Merge folgen; das im Chunk vorliegende Datei-Diff zeigt `e2e/tests/space-chats.spec.ts` und `e2e/fixtures/chat.ts` bereits als geänderte Dateien, das Ergebnis dieses nachträglichen Rebase-Schritts ist also mit im PR enthalten.

**Verifikation:** `frontend/src/components/chat/ChatInput.tsx` enthält die Scope-/@Alles-Wissen-Logik unverändert (Kommentare referenzieren #560 explizit); Schalter „Wissen nutzen“ ist nicht mehr vorhanden.

**Themen:** chat, retrieval, suchbereich, frontend, doku

---

<a id="issue-565"></a>

## Issue #565 — chatStore-Persistierung: Rollback ohne chatId-Guard und parallele PATCHes unserialisiert
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #570 (2026-08-20)

**Laut Issue:** Beim Review von PR #564 aufgefallen, vorbestehend seit #548: Der Fehler-Rollback der Chat-Einstellungs-Persistierung prüft nicht, ob inzwischen ein anderer Chat aktiv ist — ein spät fehlschlagender PATCH von Chat A kann den Zustand von Chat B überschreiben. Parallele PATCHes (schnelle Chip-Änderungen) werden nicht serialisiert, sodass die zuletzt eintreffende statt der zuletzt ausgelösten Aktion gewinnt.

**Geliefert:** `applyScopeChange` erhält ein monoton steigendes Token (`settingsUpdateSequence`) sowie die zum Änderungszeitpunkt aktive `chatId`; Einstellungs-PATCHes werden pro Chat als Promise-Kette (`settingsUpdateChains`) statt parallel verschickt. Rollback erfolgt auf den zuletzt serverbestätigten Zustand (`confirmedSettingsByChatId`), nicht auf den lokalen Zwischenstand. `sendMessage` wartet auf die Kette des aktiven Chats statt auf einen globalen Slot (zweite Review-Runde). Fünf Tests mit dokumentiertem Rot/Grün-Nachweis je Fix-Aspekt.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält `settingsUpdateSequence` und `confirmedSettingsByChatId` weiterhin (weiterentwickelt durch #573, das offene Punkte aus der zweiten Review-Runde dieses PRs behebt — Modul-Maps-Aufräumung und eine vorbestehende Navigation-Race).

**Themen:** chat, frontend, bugfix, race-condition

---

<a id="issue-566"></a>

## Issue #566 — AGENTS.md: Epics nach Abschluss aller Sub-Issues schließen
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, size:S
- PRs: #567 (2026-08-20)

**Laut Issue:** Die Anweisungen zu Epics in AGENTS.md regeln Anlage und Sub-Issue-Führung, aber nicht den Abschluss — zwei vollständig abgearbeitete Epics (#523, #486) standen deswegen noch offen. Es soll eine Regel ergänzt werden, dass ein Epic geschlossen wird, sobald alle Sub-Issues geschlossen sind, mit kurzem Abschlusskommentar. Reine Dokumentationsänderung.

**Geliefert:** Entspricht dem Issue. AGENTS.md, Abschnitt „GitHub-Issues“, enthält jetzt die Regel samt `gh`-Einzeiler zur Geschwister-Prüfung; `.github/ISSUE_TEMPLATE/epic.md` wurde ebenfalls ergänzt.

**Verifikation:** Die Regel ist im aktuellen AGENTS.md unverändert vorhanden (Zeile ~144: „Ein Epic wird geschlossen, sobald alle seine Sub-Issues geschlossen sind …“).

**Themen:** agenten-organisation, doku, projektsetup

---

<a id="issue-569"></a>

## Issue #569 — Veraltete Space- und „Ablegen“-Terminologie in access-control.md und security-and-compliance.md bereinigen
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, size:S
- PRs: #605 (2026-08-20)

**Laut Issue:** Beim Review von PR #568 (#533) aufgefallen: `docs/features/access-control.md` und `docs/features/security-and-compliance.md` enthalten noch das überholte Drei-Arten-Space-Modell (seit #333 ersetzt durch eine Space-Art mit `isDefault`/`memberSource`) sowie die alte „Ablegen“-Terminologie statt „in den Space geteilt“ (PRIVATE/SHARED). Beide Dateien sollen an `spaces-and-assets.md` angeglichen und vollständig auf weitere Reste geprüft werden.

**Geliefert:** Entspricht dem Issue. Beide Dateien wurden an das aktuelle Space-Modell und die Teilen-Terminologie angeglichen; laut PR-Beschreibung wurden beide Dateien vollständig nach weiteren Resten durchsucht.

**Verifikation:** Grep nach den im Issue genannten Begriffen („abgelegte“, „Fachbereichs-Spaces“, Drei-Arten-Formulierungen) in `docs/features/access-control.md` und `docs/features/security-and-compliance.md` liefert keine Treffer mehr — die Bereinigung ist im aktuellen Stand sichtbar.

**Themen:** doku, spaces, access-control

---

<a id="issue-572"></a>

## Issue #572 — Umlaut-Ersatzschreibweisen in weiteren nutzerseitigen Backend-Meldungen bereinigen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #620 (2026-08-20)

**Laut Issue:** Beim Review von PR #571 (#551) aufgefallen: Nach der Korrektur von `SourceConnectionTestService` divergiert der Wortlaut gespiegelter Meldungen — derselbe Dialog zeigt je nach Pfad „nicht zulässig“ und „nicht zulaessig“. Betroffen: `KnowledgeLibraryService#validateConfigurationForType`, `GlobalExceptionHandler`, `LibraryDocumentService`, `GroupService`, `AsyncIndexingExecutor`, sowie eine veraltete OpenAPI-Prosa-Stelle. Gefordert: echte Umlaute überall, gespiegelte Wortlaute wieder zeichengleich, sowie eine systematische Suche nach verbleibenden Ersatzschreibweisen im gesamten Backend.

**Geliefert:** Deckt die genannten Stellen ab und geht darüber hinaus: zusätzlich korrigiert wurden `UrlIndexingExecutor`, `DocumentIndexingService`, `IndexingJobService`, `RssFeedIndexingExecutor`, `AuditQueryService`, `DirectorySyncPlanExecutor`, `CredentialsEncryptor` sowie ein Nachtrag aus dem Review zu PR #576 (`AssetGrantService#upsertGrant`, das zusätzlich API-Feldnamen aus Nutzermeldungen entfernte). MSW-Mocks und Tests, die Wortlaute festschreiben, wurden mitgezogen. Laut PR-Beschreibung wurde das gesamte Backend systematisch nach verbleibenden Ersatzschreibweisen durchsucht.

**Verifikation:** Grep nach `zulaessig`, `gueltig`, `ausserhalb` in `KnowledgeLibraryService.java` liefert keine Treffer mehr — die Korrektur ist im aktuellen Stand sichtbar.

**Themen:** backend, i18n, bugfix, doku

---

<a id="issue-573"></a>

## Issue #573 — chatStore: Modul-Maps der Einstellungs-Persistierung aufräumen und Navigation-Race beim bestätigten Zustand
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #618 (2026-08-20)

**Laut Issue:** Aus der zweiten Review-Runde zu PR #570 (#565), drei offene, nicht blockierende Punkte in `chatStore.ts`: (1) die Modul-Maps `settingsUpdateChains` und `confirmedSettingsByChatId` wachsen unbegrenzt über die Session; (2) `resetChatStore()` in Tests setzt diese Maps nicht zurück; (3) eine vorbestehende Navigation-Race, bei der ein spät erfolgreicher PATCH nach zwischenzeitlichem `loadChat` den bestätigten Zustand verdeckt.

**Geliefert:** Entspricht dem Issue. Der Ketteneintrag wird im `finally`-Handler von `applyScopeChange` entfernt, sofern er noch der Tail ist; `confirmedSettingsByChatId` bleibt beim Abarbeiten bewusst erhalten (Rollback-Korrektheit) und wird stattdessen bei Chat-Löschung über eine neue Funktion `dropChatSettingsCache(chatId)` bereinigt. Neue exportierte `clearSettingsPersistenceCache()` für Test-Resets. Der Erfolgs-Handler von `applyScopeChange` erhält denselben Sequenz-Guard wie der Fehler-Handler, was die Navigation-Race behebt. Rot/Grün-Nachweis für Punkt 3 dokumentiert.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält `dropChatSettingsCache` und die Sequenz-Guards in Erfolgs- wie Fehlerpfad (Kommentare referenzieren #573 explizit).

**Themen:** chat, frontend, bugfix, race-condition

---

<a id="issue-575"></a>

## Issue #575 — Frontend-Stores: In-flight-Antworten schreiben nach Logout/Reset wieder in geleerte Stores
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #626 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #574 (#440): `reset()` leert die Stores beim Logout, aber laufende Anfragen schreiben ihr Ergebnis ungeschützt zurück — nur `loadChat` prüft ein Sequenz-Token. Konkret genannt: `chatStore.sendMessage` (insbesondere bei 401-ausgelöstem Logout), der `indexingStore`-Poll-Callback, `spaceStore.loadSpaces` und `libraryStore.loadLibraries`.

**Geliefert:** Statt eines Tokens pro Store wurde ein gemeinsamer Sitzungs-Epoch-Zähler (`frontend/src/stores/sessionEpoch.ts`) eingeführt, der bei jedem Reset inkrementiert wird; alle acht registrierten Stores (`chatStore`, `chatListStore`, `spaceStore`, `libraryStore`, `documentStore`, `groupStore`, `grantStore`, `indexingStore`) prüfen ihn vor dem abschließenden `set()`. Deckt die vier explizit genannten Pfade ab und geht bei der systematischen Durchsicht der `resettableStores`-Registrierung darüber hinaus (u. a. `chatListStore`, `documentStore`, `groupStore`, `grantStore`). Für alle sechs zentralen Pfade liegt ein einzeln dokumentierter Rot/Grün-Nachweis vor.

**Verifikation:** `frontend/src/stores/sessionEpoch.ts` existiert im Worktree.

**Themen:** frontend, auth, session, bugfix, race-condition

---

<a id="issue-580"></a>

## Issue #580 — docs(design): Design-Guidelines des Zielbild-Designsystems dokumentieren
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, frontend, size:M
- PRs: #603 (2026-08-20)

**Laut Issue:** Die Zielbild-Mockups (`docs/design/OPAA Mockups.html`, 9 Seiten) definieren ein neues Designsystem (Blau `#1292EE` auf Navy `#012142`, flache Flächen mit Rahmen statt Schatten, 10-px-Radien, 4-px-Abstandsraster, Typoskala 11–104 px), das die ältere Stitch-Doku ablöst. Die Mockup-Datei lag nur lokal beim Maintainer vor und sollte mit diesem Issue eingecheckt werden, ergänzt um verbindliche `docs/design/guidelines.md` (Farben hell/dunkel, Typografie, Abstände, Komponentenregeln, deutsche UI-Begriffe) sowie eine dokumentierte Schriftentscheidung. Grundlage für alle folgenden UI-Issues des Redesign-Epics #600.

**Geliefert:** Entspricht dem Issue. `docs/design/OPAA Mockups.html`, `docs/design/redesign-prompt.md` und `docs/design/guidelines.md` wurden eingecheckt, `docs/design/README.md` auf das Zielbild umgestellt und die Stitch-Entwürfe als abgelöst markiert. Schriftentscheidung dokumentiert: Die Firmenschrift „Sklow“ aus den Mockups ist nicht frei lizenziert und bleibt außerhalb des Repositories; Standard bleibt Inter (SIL OFL), eine Firmenschrift kann später über die Branding-Konfiguration nachgeladen werden — keine proprietäre Schrift committet.

**Verifikation:** Alle drei Dateien (`docs/design/guidelines.md`, `docs/design/OPAA Mockups.html`, `docs/design/redesign-prompt.md`) existieren im Worktree.

**Hinweis:** Issue-Autor ist `bigpuritz` — laut Nutzer-Vorgabe werden Issues dieses Autors normalerweise nicht bearbeitet. Dieser Baustein dokumentiert lediglich rückblickend ein bereits geschlossenes, gemergtes Issue im Rahmen der Leistungsinventur und nimmt keine Änderung daran vor.

**Themen:** doku, design, frontend, redesign

---

<a id="issue-581"></a>

## Issue #581 — feat(frontend): Design-Tokens und Theme-Fundament des neuen Designsystems
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #622 (2026-08-20)

**Laut Issue:** Das bestehende Theme (`frontend/src/theme/theme.ts`) sollte durch eine vollständige Token-Ebene ersetzt werden — Farbskalen, semantische Flächen-/Text-Rollen, Typoskala, 4-px-Abstandsraster, 10-px-Radien, flache Flächen mit Rahmen statt Schatten. `createAppTheme` sollte Branding-Überschreibungen entgegennehmen können (zunächst ungenutzt), bestehende Seiten aber weiter benutzbar bleiben.

**Geliefert:** `frontend/src/theme/tokens.ts` als einzige Wertequelle (Farbskalen, semantische Rollen hell/dunkel, Typoskala, Radien, Schatten nur für schwebende Ebenen, Fokusring, Bewegungswerte). `createAppTheme` neu aufgebaut mit Komponenten-Overrides (Button, OutlinedInput, Tabellen, Dialoge, Chips, Tooltip, Links) und akzeptiert bereits `{ primaryColor }` als Branding-Override inkl. berechneter Hover-/Press-/Fokuszustände — Vorgriff auf #582/#583. JetBrains Mono als Mono-Schrift ergänzt. Deckt sich mit dem Issue-Zuschnitt, keine nennenswerten Abweichungen.

**Verifikation:** `frontend/src/theme/tokens.ts` und `frontend/src/theme/theme.ts` existieren im aktuellen Code; `tokens.ts` enthält weiterhin `accent: blue[500]` (`#1292EE`) und `primaryColor` als Override-Parameter.

**Themen:** frontend, design, theme, designsystem

---

<a id="issue-582"></a>

## Issue #582 — feat(backend): Branding-Systemeinstellungen mit API
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M
- PRs: #630 (2026-08-20)

**Laut Issue:** Backend-Systemeinstellung für Branding (Produktname, Claim, Logo, Primärfarbe, Farbschema-Vorgabe) mit `GET /api/v1/branding` (lesbar für angemeldete Nutzer) und `PUT /api/v1/system/branding` (nur `SYSTEM_ADMIN`), spec-first per ADR-0006, Liquibase-Persistenz, Validierung, Audit-Ereignis, sichere Logo-Behandlung (kein Skript-Risiko durch SVG).

**Geliefert:** Endpunkte wie gefordert plus eigene Logo-Endpunkte (`GET/PUT/DELETE /api/v1/branding(/system)/branding/logo`); SVG wird komplett abgelehnt statt gesäubert (bewusste Entscheidung gegen das im Issue vorgeschlagene „ablehnen oder säubern"), akzeptiert werden nur PNG/JPEG mit Bytes-basierter Typprüfung via Tika, Größen-/Maßgrenzen. Migration 041 (Tabelle) und 042 (Audit-Event-Typ `BRANDING_SETTINGS_CHANGED`). Wichtige Abweichung, im PR selbst dokumentiert: Der Merge-Commit auf `main` enthielt **nicht** den letzten Commit des Branches („Branding ohne Anmeldung lesbar machen"), der die Lesepfade für `permitAll` öffnet — dieser fehlende Teil wurde in #583 nachgezogen (dort korrigiert).

**Verifikation:** `backend/src/main/java/io/opaa/branding/BrandingSettingsService.java` sowie die übrigen im PR gelisteten Branding-Klassen existieren im aktuellen Code.

**Themen:** backend, branding, api, sicherheit, audit, migration

---

<a id="issue-583"></a>

## Issue #583 — feat(frontend): Branding über die Weboberfläche konfigurieren und im Theme anwenden
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #643 (2026-08-20)

**Laut Issue:** Frontend soll Branding-Konfiguration beim Start laden, in `createAppTheme` einspeisen, Logo/Produktname in Seitenleiste und Anmeldeseite sowie Dokumenttitel übernehmen, ein Verwaltungsformular für `SYSTEM_ADMIN` mit Live-Vorschau und WCAG-Kontrastwarnung bieten.

**Geliefert:** Wie gefordert umgesetzt (`brandingStore`, `BrandMark`, Formular unter `/admin/branding`, Live-Vorschau in beiden Farbschemata, Kontrastprüfung als Warnung ohne Blockade). Zusätzlich musste der PR den in #582 fehlenden „permitAll"-Commit nachziehen, da `GET /api/v1/branding` sonst für Unangemeldete 401 geliefert hätte und die Anmeldeseite ohne Branding dargestellt worden wäre. Nebenbefund dokumentiert: Die OPAA-Standard-Akzentfarbe `#1292EE` erreicht nur 3,3:1 Kontrast gegen Weiß (gefordert 4,5:1) — im Widerspruch zur Behauptung in `docs/design/guidelines.md#24-kontrast`. Der Autor hat dafür bewusst kein eigenes Issue angelegt, sondern es hier vermerkt; laut PR-Text sollte dies zu #584/#598 gehören.

**Verifikation:** `frontend/src/stores/brandingStore.ts` vorhanden. `frontend/src/theme/tokens.ts` führt `accent: blue[500]` = `#1292EE` weiterhin als Standardwert — der dokumentierte Kontrast-Nebenbefund ist im Code nicht behoben, offenbar folgt er separaten Issues (#634 laut #586).

**Themen:** frontend, branding, theme, barrierefreiheit, kontrast

---

<a id="issue-584"></a>

## Issue #584 — docs(design): Barrierefreiheits-Richtlinie (BITV 2.0 / WCAG 2.1 AA) mit Prüfliste
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, size:S
- PRs: #624 (2026-08-20)

**Laut Issue:** `docs/design/accessibility.md` mit Zielniveau BITV 2.0/WCAG 2.1 AA, verbindlicher Prüfliste je UI-Issue und Prüfverfahren (automatisiert, Tastatur, Screenreader-Stichprobe) verfassen; Verweis aus Design-Guidelines und Prüfpunkt ins PR-Template aufnehmen.

**Geliefert:** Wie gefordert — `docs/design/accessibility.md` mit Zielniveau, Prüfliste (Abschnitt 2), dreistufigem Prüfverfahren (Abschnitt 3) und Nachweis-Regeln im PR (Abschnitt 4); PR-Template um Checklistenpunkt „Barrierefreiheit nach docs/design/accessibility.md geprüft" ergänzt; Guidelines/README verweisen auf die Datei. Reine Dokumentationsänderung ohne Abweichungen.

**Verifikation:** `docs/design/accessibility.md` existiert im aktuellen Stand.

**Themen:** dokumentation, barrierefreiheit, design, projektsetup

---

<a id="issue-585"></a>

## Issue #585 — feat(frontend): A11y-Basisausstattung — Landmarken, Fokusführung, reduzierte Bewegung
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #629 (2026-08-20)

**Laut Issue:** Landmarken-Struktur (`header`/`nav`/`main`/`footer`), Sprungmarke, Fokus-Management bei Routenwechsel auf die Seitenüberschrift, sichtbarer Fokus-Stil aus dem Designsystem, `prefers-reduced-motion`, Dokumenttitel je Seite, Live-Region für asynchrone Statusmeldungen.

**Geliefert:** Alle genannten Punkte umgesetzt (`SkipLink`, `PageHeading`, `usePageTitle`, Fokusring-Fix für MUI `ButtonBase` als während der Umsetzung entdeckter Nebenbefund, `role="status"`-Live-Regionen). Eine Abnahmekriterium blieb im PR selbst ausdrücklich offen: „Routenwechsel wird vom Screenreader angesagt" — laut PR-Text war dafür eine **VoiceOver-Stichprobe durch den Maintainer offen**, technisch nur indirekt über Fokus/Titel belegt. Ob diese manuelle Prüfung nachträglich erfolgte, ist aus den Daten nicht ersichtlich.

**Verifikation:** `frontend/src/components/a11y/SkipLink.tsx` und die übrigen im PR gelisteten Dateien (`PageHeading.tsx`, `AppShell.tsx` etc.) existieren im aktuellen Code.

**Themen:** frontend, barrierefreiheit, fokusfuehrung, ui

---

<a id="issue-586"></a>

## Issue #586 — ci(frontend): Automatisierte Barrierefreiheits-Prüfungen in Lint und E2E-Suite
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M, ci
- PRs: #640 (2026-08-20)

**Laut Issue:** `eslint-plugin-jsx-a11y` in die Lint-Konfiguration aufnehmen, axe-core-Prüfung in die Playwright-E2E-Suite für mindestens Anmeldung, Chat, Spaces, Bibliotheken, Verwaltungsbereich integrieren (serious/critical lassen Suite fehlschlagen), beide Farbschemata mindestens für Chat prüfen, CI führt beides aus.

**Geliefert:** Wie gefordert, mit einer Abweichung bei der Bibliothekswahl: statt des offiziellen `eslint-plugin-jsx-a11y` kam der Fork `eslint-plugin-jsx-a11y-x` zum Einsatz, weil das Original ESLint 10 nicht als Peer-Dependency deklariert (Upstream-Issue vermerkt) — Rückwechsel als Folge-Issue #635 festgehalten. `e2e/tests/accessibility.spec.ts` deckt die geforderten Seiten ab, dunkles Schema zusätzlich für Chat. Zwei Befunde des Erstlaufs behoben (Listenstruktur der Seitenleiste) bzw. als dokumentierte Ausnahme mit Folge-Issue #634 (Kontrast der Akzentfarbe, deckt sich mit dem Nebenbefund aus #583) geführt. Der manuelle Tastatur-Durchgang der umgebauten Seitenleiste wurde im PR als vor dem Merge noch ausstehend vermerkt.

**Verifikation:** `frontend/eslint.config.js` importiert weiterhin `eslint-plugin-jsx-a11y-x` (Zeile 7), `eslint-plugin-jsx-a11y-x` steht in `frontend/package.json`; der im PR angekündigte Rückwechsel auf das Original-Plugin (#635) ist demnach noch nicht erfolgt. `e2e/tests/accessibility.spec.ts` existiert.

**Themen:** ci, barrierefreiheit, frontend, e2e, lint

---

<a id="issue-587"></a>

## Issue #587 — feat(frontend): App-Shell und Seitenleiste nach Zielbild — Space-Wechsler, Chats, Bereichsnavigation
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #652 (2026-08-20)

**Laut Issue:** Seitenleiste nach Mockup 1a umbauen — Space-Wechsler als prominentes Dropdown, Chats des aktiven Space in der Mitte, Bereichs-Navigation und Nutzer-Badge unten; „Katalog" entfällt, „Als PDF exportieren"/„Archivieren" sind nicht Teil des Issues; Mobile-Verhalten anpassen; bestehende Sidebar-Tests aktualisieren.

**Geliefert:** Wie gefordert. Navy-Leiste in beiden Farbschemata über verschachtelten `ThemeProvider`, Space-Wechsler mit Art und Mitgliederzahl je Space, Chats unverändert aus `ChatList`, Bereichs-Navigation unten (inkl. „Branding" und „Gruppen" für Systemverwaltung), Nutzer-Badge mit Menü für Einstellungen/Abmelden. Bewusste Abweichung vom Mockup, im PR benannt: die Mockup-Angabe „n Quellen" je Space wird durch die Mitgliederzahl ersetzt, weil die Listen-API noch keine Quellenzahl liefert (Lücke bereits in #593 vermerkt). Mobile Drawer/`MobileHeader` blieben unangetastet.

**Verifikation:** `frontend/src/layouts/Sidebar.tsx` existiert im aktuellen Code.

**Themen:** frontend, ui, navigation, spaces, design

---

<a id="issue-588"></a>

## Issue #588 — feat(frontend): Anmeldeseite im neuen Design
- Geschlossen: 2026-08-21 (completed)
- Labels: frontend, size:S, auth
- PRs: #703 (2026-08-21)

**Laut Issue:** `LoginPage` nach Mockup 1f gestalten — Markenblock, OIDC-Anmeldung als primäre Handlung, gestalteter Fehlerzustand; Logo/Produktname/Claim aus Branding-Konfiguration; beide Farbschemata; Kennung/Kennwort-Formular und Registrierung ausdrücklich außerhalb des Umfangs.

**Geliefert:** Wie gefordert — Navy-Markenfläche über die volle Seite, zentrierter Markenblock (Logo/Produktname/Claim aus Branding), primäre OIDC-Schaltfläche mit Ladezustand, gestalteter Fehlerzustand statt roher Meldung. OIDC-Ablauf unverändert. Deckt sich mit Issue-Zuschnitt, keine Abweichungen.

**Verifikation:** `frontend/src/pages/LoginPage.tsx` existiert im aktuellen Code.

**Themen:** frontend, auth, ui, branding, design

---

<a id="issue-590"></a>

## Issue #590 — feat(frontend): Chat-Verlauf im neuen Design — Antworten mit Fußnoten-Fundstellen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #668 (2026-08-20)

**Laut Issue:** `MessageList`/`MessageBubble`/`MarkdownRenderer` umbauen: Antworten als Fließtext ohne Blase, hochgestellte Fußnotenziffern statt `SourceCard`-Karten, Fundstellen-Block je Antwort mit Dokumentgruppierung, einklappbare nicht zitierte Treffer, Verweigerungs-Antwort als ruhig gestalteter Antworttyp; Fundort-Metadaten mit dem tatsächlichen API-Stand abgleichen und Lücken als Backend-Folge-Issues festhalten.

**Geliefert:** Fußnoten-Auflösung der Zitatmarker (`buildCitationIndex`), Fundstellen-Block mit Zählzeile, gruppierten Ziffern und Aufklapper für nicht zitierte Treffer; `SourceCard` vollständig entfernt (Komponente + Test). Bewusst offen gelassen, wie im Issue vorgesehen: Fundort je Stelle (Abschnitt/Seite/Paragraf) und durchsuchte Bestände der Verweigerungsantwort — als Folge-Issue #667 festgehalten, da die API diese Daten noch nicht liefert. Der PR-Titel benennt nur die Fußnoten/Fundstellen, die Verweigerungs-Antwort als eigener „ruhig gestalteter Antworttyp" wird im PR-Body nicht explizit erwähnt — möglicherweise Teilumfang, nicht vollständig verifizierbar aus den Daten.

**Verifikation:** `frontend/src/components/chat/SourceFootnotes.tsx` existiert; `frontend/src/components/chat/SourceCard.tsx` existiert nicht mehr (erwartungsgemäß entfernt laut PR-Body).

**Themen:** frontend, chat, retrieval, ui, quellenangaben

---

<a id="issue-591"></a>

## Issue #591 — feat(frontend): Eingabezeile mit Suchbereichs-Statuszeile und @-Vorschlag im neuen Design
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M
- PRs: #672 (2026-08-20)

**Laut Issue:** `ChatInput` nach Mockups 1a/1h gestalten — Platzhaltertext, „Fragen"-Knopf, Statuszeile mit Anzahl durchsuchter Bestände, @-Vorschlagsliste mit Präfix-Hervorhebung und Typzeile, Tastaturnavigation erhalten.

**Geliefert:** Wie gefordert. Statuszeile ersetzt den bisherigen Enter-Hinweis und zeigt „Durchsucht: n lesbare Bestände" bzw. bei @-Eingrenzung „n gewählte Bestände" bzw. ehrlich „nichts" bei leerem Suchbereich. @-Vorschlagsliste mit fettem Präfix und Typ-Badge „Bibliothek · verengt die Suche" (nimmt Agenten als zweiten Typ vorweg). Im PR benannte bewusste Einschränkung: Die Zählung bleibt beim heutigen Modell (@Alles-Wissen = alle lesbaren Bibliotheken), da Space-Datenquellen erst mit #203 kommen — keine Abweichung vom Issue, sondern dort bereits so vorgesehen.

**Verifikation:** `frontend/src/components/chat/ChatInput.tsx` existiert im aktuellen Code.

**Themen:** frontend, chat, ui, retrieval, barrierefreiheit

---

<a id="issue-592"></a>

## Issue #592 — feat(frontend): Belegfenster — seitliche Leiste mit allen Fundstellen einer Antwort
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #676 (2026-08-20)

**Laut Issue:** Neue seitliche Leiste (Mockup 1i) mit allen Fundstellen einer Antwort — Kopf, Suchfeld, Filter „Nur zitierte", Gruppierung nach Dokument, je Stelle Ziffer/Zitat/Fundort/„Im Dokument öffnen", Fußzeile „Stand der Antwort". Fokusführung: Öffnen fängt Fokus, Escape schließt mit Rückkehr zum Auslöser. PDF-Export ausdrücklich außerhalb des Umfangs.

**Geliefert:** Wie gefordert umgesetzt (`SourceEvidenceDrawer`, Suchfeld, `aria-pressed`-Filter, Dokumentzeilen nach Relevanz sortiert, Fokusfang/Escape-Rückkehr testbelegt, mobil Vollbild/Desktop 440px). Bewusst offen gelassen, im Issue selbst so vorgesehen: wörtliche Zitate und Fundorte je Stelle brauchen Chunk-Metadaten der API, die noch fehlen — als Folge-Issue #667 (gemeinsam mit #590) festgehalten; bis dahin trägt jede Zeile nur, wofür die API heute bürgt. Zusätzlicher Nebenbefund im PR behoben: Ein Zitier-Flag-Konflikt in `buildCitationIndex` führte zu doppelt gelisteten Dokumenten — mit Test abgesichert.

**Verifikation:** `frontend/src/components/chat/SourceEvidenceDrawer.tsx` existiert im aktuellen Code.

**Themen:** frontend, chat, ui, retrieval, barrierefreiheit, quellenangaben

---

<a id="issue-593"></a>

## Issue #593 — feat(frontend): Spaces-Übersicht als Kartenliste
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M
- PRs: #683 (2026-08-20)

**Laut Issue:** Die Spaces-Übersicht sollte laut Mockup 1c als Kartenliste umgebaut werden — Kopfzeile mit Raumanzahl, Knopf „Neuer Space“, je Karte Art-Etikett, Name, Kurzbeschreibung, Kennzahlen (Quellen/Chats/Mitglieder), Rollen-Etikett sowie eine Abschlusskarte „+ Neuen Space anlegen“. Kennzahlen sollten aus vorhandenen API-Daten stammen; fehlende Zählwerte als Backend-Folge-Issue.

**Geliefert:** PR #683 baut `/spaces` von einer Weiterleitung zu einer echten Kartenraster-Übersicht um: Kopfzeile mit Zählzeile und Primärknopf, Kartenraster mit Eyebrow-Etikett (Persönlich/Team), Name, zweizeilig begrenzter Beschreibung, Rollen-Chip, Archiviert-Etikett, gestrichelte Abschlusskarte sowie gestaltetem Leerzustand. Bewusste Abweichung: Kennzahlen zeigen laut PR-Body nur die Mitgliederzahl („nur Sie“ beim persönlichen Space) — „n Quellen · n Chats“ fehlen in `SpaceListResponse` und wurden als Folge-Issue #682 ausgelagert, wie im Issue vorgesehen.

**Verifikation:** `frontend/src/pages/SpacesOverviewPage.tsx` und der zugehörige Test existieren im heutigen Worktree.

**Themen:** frontend, spaces, redesign, ui

---

<a id="issue-594"></a>

## Issue #594 — feat(frontend): Space-Anlage als mehrstufiger Assistent
- Geschlossen: 2026-08-21 (completed)
- Labels: frontend, size:M
- PRs: #687 (2026-08-21)

**Laut Issue:** Die Space-Anlage (bisher `CreateSpaceDialog`) sollte laut Mockup 1b zu einem vierstufigen Assistenten werden: Grunddaten, Datenquellen, Mitglieder, Zusammenfassung. Für den Datenquellen-Schritt sollten vorhandene APIs genutzt werden; reicht der API-Zuschnitt nicht, sollte die Lücke als Folge-Issue festgehalten werden. „Ausstattung eines bestehenden Space übernehmen“ war ausdrücklich außerhalb des Umfangs.

**Geliefert:** PR #687 liefert die neue Seite `/spaces/new` mit Schrittleiste **Grunddaten / Mitglieder / Zusammenfassung** — also nur drei statt der im Issue skizzierten vier Schritte. Der Schritt „Datenquellen zuordnen“ entfällt bewusst, weil es keine Space↔Bibliothek-Zuordnungs-API gibt; das ist im PR-Body ausdrücklich als Abweichung benannt und als Folge-Issue #686 festgehalten — deckt sich mit der Issue-Vorgabe, Lücken als Folge-Issues zu dokumentieren. `CreateSpaceDialog` wurde entfernt.

**Verifikation:** `frontend/src/pages/SpaceCreatePage.tsx` und Test existieren im heutigen Worktree; `CreateSpaceDialog.tsx` ist laut PR-Dateiliste entfernt worden (im Diff als gelöscht geführt).

**Themen:** frontend, spaces, redesign, wizard, ui

---

<a id="issue-595"></a>

## Issue #595 — feat(frontend): Wissensbibliotheken-Übersicht als Tabelle mit Herkunft, Verteilungsstufe und Stand
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M
- PRs: #685 (2026-08-20)

**Laut Issue:** Die Bibliotheksübersicht (`LibraryManagementPage`) sollte laut Mockup 1d zu einer Tabelle mit den Spalten Name, Herkunft, Umfang, Verteilungsstufe, Rolle und Stand (inkl. laufendem Indizierungsfortschritt) werden, responsiv als Kartenliste unterhalb Tablet-Breite.

**Geliefert:** PR #685 baut die Übersicht als Tabelle mit den sechs Mockup-Spalten, Zeilen als vollflächige Links, Fortschrittsbalken für laufende Indizierungsläufe und der geforderten Fußnote. Responsives Verhalten (Kartenliste unterhalb `md`) bleibt erhalten. Abweichung: Der letzte erfolgreiche Indexstand („indiziert DD.MM.YYYY“ / „abgerufen heute HH:MM“) fehlt in `LibraryListResponse` und wurde als Folge-Issue #684 ausgelagert; bis dahin zeigt die Spalte ohne aktiven Lauf „–“.

**Verifikation:** `frontend/src/pages/LibraryManagementPage.tsx` und Test existieren im heutigen Worktree.

**Themen:** frontend, wissensbibliotheken, redesign, ui

---

<a id="issue-596"></a>

## Issue #596 — feat(frontend): Bibliothek-Anlage als Assistent mit Herkunfts-Auswahl
- Geschlossen: 2026-08-21 (completed)
- Labels: frontend, size:M
- PRs: #696 (2026-08-21)

**Laut Issue:** Die Bibliothek-Anlage (bisher `CreateLibraryDialog`) sollte laut Mockup 1e zu einem dreistufigen Assistenten werden: Stammdaten, Herkunft (vier Auswahlkarten: Upload, Dateisystem, Webverzeichnis, RSS-Feed, je mit passendem Verbindungsformular), Rechte. Zugangsdaten sollten als Passwortfelder nie im Klartext zurückgespiegelt werden; die Schrittlogik sollte nach DRY-Prinzip mit dem Space-Assistenten geteilt werden.

**Geliefert:** PR #696 liefert die Seite `/libraries/new` mit den drei Schritten Stammdaten, Herkunft (2×2-Kartenraster als Radiogruppe, typgebundenes Verbindungsformular inkl. Verbindungstest) und Rechte (Verteilungsstufe + Freigaben an Personen/Gruppen über die Grant-API). Die im Issue geforderte gemeinsame Schrittleiste wurde tatsächlich extrahiert (`components/wizard/WizardStepBar`, `FieldLabel`) und rückwirkend auch vom Space-Assistenten (#594) übernommen. `CreateLibraryDialog` wurde entfernt, E2E-Tests auf den Assistenten umgestellt.

**Verifikation:** `frontend/src/pages/LibraryCreatePage.tsx`, `frontend/src/components/wizard/WizardStepBar.tsx` und `FieldLabel.tsx` existieren im heutigen Worktree.

**Themen:** frontend, wissensbibliotheken, redesign, wizard, ui

---

<a id="issue-597"></a>

## Issue #597 — feat(frontend): Übrige Seiten und Dialoge an das neue Design angleichen
- Geschlossen: 2026-08-21 (completed)
- Labels: frontend, size:M
- PRs: #701 (2026-08-21)

**Laut Issue:** Als letztes Migrations-Issue des Redesign-Epics sollten `LibraryDetailPage`, `SpaceManagementPage`, `GroupManagementPage`, `SettingsPage` sowie die verbleibenden Dialoge (`LibraryGrantsDialog`, `CreateGroupDialog`, `EditLibrarySourceDialog`) und der `ErrorBoundary`-Fehlerzustand auf Token-Theme und Guidelines umgestellt werden, unter Wiederverwendung bereits etablierter Muster.

**Geliefert:** PR #701 stellt alle genannten Seiten und Dialoge um: einheitliche Kopfzeilen mit Zählzeile, Eyebrow-Abschnittsköpfe, Formulare im 40-px-Feldmuster, Mono-Badges. Zwei neue geteilte Bausteine (`MetaBadge`, `SectionHead`) statt Kopien. Einstellungsseite erhält zusätzlich einen Abschnitt „Erscheinungsbild des Hauses“ mit Verweis auf Branding. Kleine Terminologieänderung: die Verteilungsstufe wird auf der Bibliothek-Detailseite konsistent zum Anlage-Assistenten benannt.

**Verifikation:** `frontend/src/components/MetaBadge.tsx` und `frontend/src/components/SectionHead.tsx` existieren im heutigen Worktree; die im PR genannten Seiten (`SettingsPage.tsx`, `GroupManagementPage.tsx`, `SpaceManagementPage.tsx`, `LibraryDetailPage.tsx`) sind vorhanden.

**Themen:** frontend, redesign, ui, barrierefreiheit

---

<a id="issue-598"></a>

## Issue #598 — test(frontend): Barrierefreiheits-Audit nach Abschluss der Design-Migration

- Geschlossen: 2026-08-28 (completed)
- Labels: frontend, size:M
- PRs: #960 (2026-08-28)

**Laut Issue:** Nach Abschluss der Design-Migration eine Gesamtabnahme der Barrierefreiheit über
die automatisierten Prüfungen hinaus: vollständiger Tastatur-Durchgang der Kernabläufe,
Screenreader-Stichproben, Kontrastprüfung in beiden Farbschemata — auch mit konfiguriertem
Branding, weil die frei wählbare Primärfarbe Kontraste brechen kann.

**Geliefert:** Das Abschluss-Audit wurde durchgeführt und sein Prüfprotokoll unter
`docs/design/` abgelegt (PR #960). Die Befunde wurden als eigene Issues erfasst und behoben:
`aria-hidden` mit fokussierbaren Elementen in der Branding-Vorschau (#956), Kontrast der
Rollen-Chips im Dunkelschema (#957), übersprungene Überschriftenebenen (#958), Fokusverlust
beim Inline-Umbenennen (#959); im Review-Nachgang zusätzlich die Markdown-Überschriften in
Chat-Antworten (#1016).

**Verifikation:** Prüfprotokoll liegt unter `docs/design/` (PR #960); alle Befund-Issues sind
mit gemergten PRs geschlossen.

**Themen:** Barrierefreiheit, Audit, Design-Migration, Abnahme

---

<a id="issue-600"></a>

## Issue #600 — feat(frontend): Redesign der Weboberfläche nach dem Zielbild-Designsystem

- Geschlossen: 2026-08-28 (completed)
- Labels: enhancement, epic, frontend
- PRs: keine (Epic; Lieferung in den Sub-Issues)

**Laut Issue:** Epic zur schrittweisen Überführung der Weboberfläche auf das
Zielbild-Designsystem (Blau/Navy/Weiß, flache Flächen, definierte Typo- und Abstandsskalen,
helles und dunkles Schema) — samt verbindlicher Design-Guidelines, Verankerung der
Barrierefreiheit (BITV 2.0 / WCAG 2.1 AA) und über die Oberfläche konfigurierbarem Branding.

**Geliefert:** Vollständig über die Sub-Issues geliefert (u. a. #580–#597, #654, #658 sowie die
globale Navigationsebene #786–#789); als letzter Baustein wurde das
Barrierefreiheits-Abschluss-Audit (#598) abgeschlossen, danach wurde das Epic geschlossen.
Details in den Bausteinen der Sub-Issues.

**Verifikation:** Designsystem (`frontend/src/theme/`), Branding-Verwaltung und App-Shell sind
im Bestand; die Design-Guidelines liegen unter `docs/design/guidelines.md`.

**Themen:** Redesign, Designsystem, Epic-Abschluss, Barrierefreiheit

---

<a id="issue-606"></a>

## Issue #606 — main-Build rot: KnowledgeLibraryServiceDeleteLockTest passt nicht zum erweiterten Konstruktor
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #607 (2026-08-20)

**Laut Issue:** Nach dem Merge von #599 und #602 kompiliert `compileTestJava` auf `main` nicht mehr: #599 hatte dem `KnowledgeLibraryService`-Konstruktor die Abhängigkeit `AssetGrantService` hinzugefügt, während der in #602 neu hinzugekommene `KnowledgeLibraryServiceDeleteLockTest` noch gegen die alte Konstruktorsignatur geschrieben war — ein semantischer Merge-Konflikt zweier für sich grüner PRs ohne Git-Textkonflikt.

**Geliefert:** PR #607 ergänzt den fehlenden `AssetGrantService`-Mock im Test und übergibt ihn an den Konstruktor. Reproduktionsnachweis ist der rote main-CI-Lauf selbst (Lauf 32367485718); mit dem Fix kompiliert `compileTestJava` und die Testklasse läuft grün. Kein Scope-Abweichen erkennbar.

**Verifikation:** `backend/src/test/java/io/opaa/library/KnowledgeLibraryServiceDeleteLockTest.java` existiert im heutigen Worktree.

**Themen:** backend, ci, bugfix, merge-konflikt

---

<a id="issue-609"></a>

## Issue #609 — fix(library): CI auf main rot — KnowledgeLibraryServiceDeleteLockTest passt nicht zur neuen KnowledgeLibraryService-Signatur
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, ci
- PRs: keine

**Laut Issue:** Beschreibt denselben roten main-Build wie #606 (Konstruktorerweiterung um `AssetGrantService` durch #599 kollidiert mit dem in #602 hinzugekommenen `KnowledgeLibraryServiceDeleteLockTest`, der noch die alte Signatur nutzt). Fordert, den Test analog zum Schwester-Test `KnowledgeLibraryServiceFilesystemAllowlistTest` zu reparieren.

**Geliefert:** Kein PR verknüpft. Laut Kommentar von @criew im Issue: „Duplikat von #606 — bereits behoben durch PR #607 (gemergt); der main-CI-Lauf danach ist wieder grün.“ Issue #609 wurde damit als Duplikat geschlossen, ohne eigenen PR — die Behebung erfolgte über #607 (siehe Baustein zu #606).

**Verifikation:** Siehe Verifikation zu Issue #606 — `KnowledgeLibraryServiceDeleteLockTest.java` existiert und passt heute zur aktuellen Konstruktorsignatur.

**Themen:** backend, ci, bugfix, duplikat

---

<a id="issue-611"></a>

## Issue #611 — test(indexing): Fremd-Host-Tests scheitern auf macOS — 127.0.0.2 ist auf lo0 nicht konfiguriert

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, backend, size:S
- PRs: #1018 (2026-08-28, gemeinsam mit #966)

**Laut Issue:** `UrlFileDownloaderTest` und `RssFeedIndexingExecutorTest` binden für ihre
Fremd-Host-Szenarien einen Testserver an `127.0.0.2`. Linux bindet den gesamten Bereich
`127.0.0.0/8`, macOS standardmäßig nur `127.0.0.1` — auf macOS scheitern die Tests mit
`BindException`, in der Linux-CI sind sie grün.

**Geliefert:** PR #1018 stellt die betroffenen Tests auf `localhost` als Bind-Adresse um; die
Fremd-Host-Eigenschaft wird portabel hergestellt statt über eine zweite Loopback-Adresse.
Behebt zusammen mit #966 alle 127.0.0.2-Bindings der Testsuite.

**Verifikation:** Commit `aeee12b2` auf `main`; die Suite läuft plattformübergreifend.

**Themen:** Testinfrastruktur, Portabilität, macOS

---

<a id="issue-614"></a>

## Issue #614 — Nacharbeiten zum asynchronen Upload: Pool-Konfiguration, Lösch-Restfenster, PENDING-Recovery
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S
- PRs: #631 (2026-08-20)

**Laut Issue:** Vier aus der zweiten Review-Runde zu PR #589 (#434) festgehaltene Punkte: (1) eigene Pool-Konfiguration für den Upload-Executor statt Mitbenutzung der Indexing-Pool-Properties, (2) ein Restfenster im Löschpfad, durch das ein parallel abschließender Upload-Task verwaiste Chunks im Vektorspeicher hinterlassen kann, (3) fehlende PENDING-Recovery nach einem Prozessabsturz während eines Upload-Tasks, (4) Textpflege (stale Javadoc-Verweise, MSW-Handler-Verhalten bei nicht extrahierbarem Text).

**Geliefert:** PR #631 setzt alle vier Punkte um: eigene `opaa.upload.thread-pool`-Property (dokumentiert in `application.yml`, `.env.example`, `docs/deployment.md`); `LibraryDocumentService#deleteDocument` löscht jetzt Zeile und Chunks vor der Datei-Nachbehandlung in `deleteAfterCommit`, um das Restfenster zu schließen; neuer `UploadPendingRecoveryRunner` setzt beim Start alte PENDING-Uploads auf FAILED (neue Spalte `documents.created_at`, Migration 041/043 je nach Zählweise); Textpflege inkl. MSW-Handler-Anpassung auf PENDING→FAILED-Modell. Reproduktionsnachweis für Punkt 2 im PR-Body dokumentiert (Mockito-Verify-Fehler bei zurückgenommener Reihenfolge).

**Verifikation:** `backend/src/main/java/io/opaa/library/UploadPendingRecoveryRunner.java` und `UploadProperties.java` existieren im heutigen Worktree.

**Themen:** backend, upload, indexing, nacharbeiten, robustheit

---

<a id="issue-616"></a>

## Issue #616 — test(query): QueryIntegrationTest flaky — MockitoException durch Stubbing-Race mit asynchronem Chat-Titel-Job
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #621 (2026-08-20)

**Laut Issue:** `QueryIntegrationTest` schlug sporadisch mit `MockitoException` fehl, vermutlich weil der seit #561 asynchrone Chat-Titel-Job im Testthread konkurrierend auf demselben geteilten `chatModel`-Mock stubt/aufruft, während der Testcode gerade selbst stubt. Gefordert: deterministischer Test, entweder durch Synchronisieren/Deaktivieren des Titel-Jobs im Test oder race-freies Stubbing.

**Geliefert:** PR #621 ersetzt in `QueryIntegrationTest` per `@TestBean(name = "chatTitleTaskExecutor", enforceOverride = true)` den asynchronen Executor durch einen synchronen `SyncTaskExecutor`, sodass der Titel-Job vollständig im aufrufenden Thread abläuft, bevor `queryService.query(...)` zurückkehrt. Positiver Nachweis über Thread-Namen-Assertion im betroffenen Test ergänzt. Reproduktion war lokal nicht möglich (wie im Issue selbst erwartet); als Beleg dienen die beiden verlinkten roten CI-Läufe aus #616. PR-Body korrigiert zudem eine falsche Zuordnung aus der ursprünglichen Aufgabenbeschreibung (Bezug war PR #603, nicht #574/#589).

**Verifikation:** `backend/src/test/java/io/opaa/query/QueryIntegrationTest.java` existiert im heutigen Worktree.

**Themen:** backend, testing, flaky-test, mockito, ci

---

<a id="issue-617"></a>

## Issue #617 — Zugangsdaten-Exfiltration über aufrufergesetzten Proxy/insecureSsl beim Verbindungstest und Indizierungslauf
- Geschlossen: 2026-08-21 (completed)
- Labels: backend, size:S, security
- PRs: #699 (2026-08-21)

**Laut Issue:** Der Origin-Check des Zugangsdaten-Fallbacks (aus #544/PR #615) sicherte nur das Ziel, nicht den Weg. Ein MANAGER konnte beim Verbindungstest mit `libraryId`-Fallback einen eigenen Proxy/`insecureSsl=true` setzen und das gespeicherte Basic-Auth-Credential über einen selbst kontrollierten Proxy mitlesen. Gefordert: gespeicherten Proxy/insecureSsl erzwingen oder Fallback bei aufrufergesetztem Proxy ablehnen; zusätzlich bewerten, ob der Indizierungspfad eine analoge Regel braucht.

**Geliefert:** Der PR bündelt drei Bausteine (#693, #267, #617) in einem gemeinsamen Härtungs-Strang. Für #617 konkret: `SourceConnectionTestService#withStoredCredentialsIfOmitted` erzwingt beim Zugangsdaten-Fallback jetzt den gespeicherten `sourceProxy`/`sourceInsecureSsl` der Bibliothek statt die Werte des Aufrufers zu übernehmen (Entscheidung „erzwingen" statt „ablehnen", wie im Issue als Alternative vorgesehen). Die geforderte Bewertung des Indizierungspfads wurde durchgeführt: laut PR-Body ist er strukturell nicht betroffen, da seit ADR-0018 `UrlIndexingExecutor`/`RssFeedIndexingExecutor` Proxy/Credentials/insecureSsl ausschließlich aus der persistierten Bibliothek lesen und der Trigger-Endpunkt keinen Request-Body mit solchen Feldern entgegennimmt. #693 und #267 sind vorgelagerte, im selben PR mitgelieferte Härtungen (Redirect-Origin-Fix bzw. SSRF-Zielprüfung), die nicht Gegenstand von #617 waren, aber denselben Strang teilen.

**Verifikation:** `SourceConnectionTestService.java` enthält `withStoredCredentialsIfOmitted` mit entsprechendem Javadoc-Verweis; Methode ist im Verbindungstest-Pfad eingebunden (Zeile ~182). Passt zur PR-Beschreibung.

**Themen:** security, retrieval, knowledge-sources, backend

---

<a id="issue-619"></a>

## Issue #619 — chatStore: loadChat überschreibt Einstellungen ungeschützt gegen die laufende Settings-Kette
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, frontend, size:S
- PRs: #692 (2026-08-21)

**Laut Issue:** `loadChat` schrieb `scope`/bestätigten Einstellungszustand bedingungslos. Der Schutz aus PR #618 (zu #565/#573) deckte nur eine Ankunftsreihenfolge ab; in der umgekehrten Reihenfolge (GET vor PATCH-Commit abgesetzt, Antwort trifft aber nach der PATCH-Antwort ein) überschrieb `loadChat` den frisch bestätigten Serverzustand wieder mit dem veralteten Wert. Gefordert: `loadChat` darf `scope`/Zustand nicht anwenden, solange eine Settings-Kette für den Chat aussteht bzw. muss nach dem Settlen nachziehen, plus Reproduktionsnachweis für genau diese Reihenfolge.

**Geliefert:** Neuer, pro Chat geführter Zähler `settingsChangeSequenceByChatId`, der bei jeder `applyScopeChange`-Anfrage hochgezählt wird und anders als `settingsUpdateChains` auch nach dem Settlen der Kette bestehen bleibt. `loadChat` vergleicht den Zähler vor/nach seinem GET; hat er sich geändert, übernimmt `loadChat` `scope`/`referencedLibraryIds` nicht und überlässt sie dem eigenen Handler der Settings-Änderung. Andere Felder (Titel, Nachrichten) werden weiterhin angewendet. Entspricht der Anforderung.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält `settingsChangeSequenceByChatId` mit Set/Get/Clear/Delete an den beschriebenen Stellen (Zeilen 74, 85, 106, 266–283, 556) — Umsetzung im Code vorhanden.

**Themen:** frontend, chat, race-condition, spaces

---

<a id="issue-623"></a>

## Issue #623 — test(chat): ChatServiceIntegrationTest hat dieselbe Stubbing-Race-Struktur wie #616
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend
- PRs: #641 (2026-08-20)

**Laut Issue:** `ChatServiceIntegrationTest` teilt die Race-Struktur, die in `QueryIntegrationTest` (#616) einen Flake verursachte: mehrere Tests lösen über einen ersten Turn einen asynchronen Titel-Job aus, ohne dessen Ende abzuwarten, bevor spätere Tests den klassenweit geteilten `@MockitoBean chatModel` neu stubben. Der Sync-Executor-Fix aus #621 wurde als ungeeignet benannt, da die Klasse echte Async-Semantik per Latch prüft. Gefordert: laufende Titel-Jobs vor Re-Stubbing abwarten oder durchgängig `doReturn(...).when(...)` verwenden, ohne Latch-/Async-Tests zu schwächen.

**Geliefert:** Option (b) aus dem Issue umgesetzt — jedes `when(chatModel....).thenX(...)` durch `doX(...).when(chatModel)...` ersetzt (`doReturn`/`doAnswer`/`doThrow`, auch für `getOptions()` in `setUp()`), da die Klasse keinen `ArgumentCaptor` im Stubbing verwendet. `doAnswer` führt den übergebenen `Answer` weiterhin exakt einmal auf dem Job-Thread aus, die Latch-Synchronisation der beiden Async-Tests bleibt unverändert erhalten. Der im Issue genannte Reproduktionsnachweis konnte laut PR-Body nicht direkt mit künstlicher Verzögerung erbracht werden (lokale Gradle-Lock-Kontention durch parallele Agent-Sessions blockierte den Versuch); stattdessen wird die Ursachenanalyse aus #616/PR #621 als Beleg für denselben Fehlermechanismus referenziert — im Issue als Alternative vorgesehen. 10 Wiederholungsläufe der Klasse liefen grün.

**Verifikation:** `ChatServiceIntegrationTest.java` enthält 6 Treffer für `doReturn(`/`when(chatModel` — Umstellung im Code nachvollziehbar vorhanden.

**Themen:** backend, tests, ci, chat, flaky-tests

---

<a id="issue-625"></a>

## Issue #625 — ci: Actions auf Node-24-Runtime aktualisieren (Node-20-Deprecation)
- Geschlossen: 2026-08-20 (completed)
- Labels: size:S, ci
- PRs: #627 (2026-08-20)

**Laut Issue:** Kein Issue-Body (leer) — der Titel benennt das Anliegen: gepinnte GitHub-Actions auf Node-24-kompatible Versionen anheben, wegen der Node-20-Deprecation-Warnung.

**Geliefert:** Alle gepinnten GitHub- und Docker-Actions in `.github/workflows/*.yml` auf die neueste stabile, Node-24-fähige Major-Version angehoben (`actions/checkout` v4→v7, `setup-java` v4→v5, `cache` v4→v6, `upload-artifact` v4→v7, `setup-python` v5→v7, `setup-node` v4→v7, `github-script` v7→v9, diverse `docker/*`-Actions). `contributor-assistant/github-action` blieb bewusst auf v2.6.1 (keine neuere Major-Version verfügbar). Breaking-Changes je übersprungener Major-Version wurden geprüft und als nicht relevant eingestuft. Entspricht dem Issue-Titel vollständig.

**Verifikation:** `ci.yml` verwendet aktuell `actions/checkout@v7`, `actions/setup-java@v5`, `actions/cache@v6` — Versionsstand deckt sich mit der PR-Beschreibung.

**Themen:** ci, projektsetup, wartung

---

<a id="issue-632"></a>

## Issue #632 — fix(indexing): Konnektorpfade re-inserten gelöschte Dokumentzeilen (save statt bedingter Aktualisierung)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #633 (2026-08-20)

**Laut Issue:** Die Konnektorpfade in `FileProcessingService` schrieben Statusübergänge per `documentRepository.save(doc)`. Wurde ein Konnektor-Dokument während der Verarbeitung gelöscht, re-insertete `save` die gelöschte Zeile als Zombie (Id wird im Konstruktor vergeben, kein `@Version`). Der Upload-Pfad war mit PR #589 bereits auf bedingte `@Modifying`-UPDATEs umgestellt; dasselbe Muster fehlte für Konnektorpfade. Gefordert: Statusübergänge als bedingte UPDATEs, bei 0 betroffenen Zeilen eigene Chunks nachräumen, Test für das Löschfenster, Reproduktionsnachweis.

**Geliefert:** Die drei Konnektor-Schreibpfade (FILESYSTEM, URL/HTTP, RSS) in `FileProcessingService` laufen jetzt über bedingte `@Modifying`-UPDATEs (`DocumentRepository#markIndexedFromSource`/`#markFailed`), verallgemeinert aus dem #589-Muster. Bei 0 betroffenen Zeilen räumt der Aufrufer die eigenen Vector-Chunks per `vectorStore.delete(...)` auf und kehrt still mit `SKIPPED` zurück. Reproduktionsnachweis per Integrationstest mit Testcontainers-Postgres erbracht (rot: `expected SKIPPED but was PROCESSED`, grün nach Fix). Im selben PR wurde zusätzlich #636 (Nachbesserung aus dem Review) mitgeliefert — drei verwandte Restfenster (Löschreihenfolge in `deleteLibrary`, Chunk-Aufräumen bei Exception-Pfaden, `failAlreadyPersistedUpload`).

**Verifikation:** `FileProcessingService.java` enthält `markConnectorFailedAfterException` und ruft `documentRepository.markIndexedFromSource(...)` auf (Zeilen 130, 249, 333, 441ff, 457, 510) — Umsetzung im Code vorhanden.

**Themen:** backend, indexing, retrieval, spaces, race-condition, tests

---

<a id="issue-634"></a>

## Issue #634 — fix(frontend): Akzentfarbe erreicht mit weißem Text nur 3,29:1 Kontrast (blue-500)
- Geschlossen: 2026-08-25 (completed)
- Labels: frontend, size:S
- PRs: #909 (2026-08-25)

**Laut Issue:** Die erste axe-core-Prüfung (#586) meldete auf jeder Seite einen "serious"-Verstoß: weißer Text (`accentFg`) auf `accent = blue[500]` erreicht nur 3,29:1 statt der geforderten 4,5:1 (WCAG 1.4.3). Betroffen waren gefüllte primäre Flächen (`Button variant="contained"`, `Chip color="primary"`) in beiden Farbschemata. Zur Wahl standen drei Varianten: Akzent verschieben, `accentFg` dunkel wählen, oder eine eigene Rolle für Text auf Akzentflächen einführen.

**Geliefert:** Variante 3 (eigene Rolle), erweitert um den Befund, dass `accent` auch Textfarbe ist (Links, Fußnoten, Ghost-Buttons) und deshalb nicht global auf Blau-700 wandern durfte. Neue Rolle `accent-surface` = Blau-700 für gefüllte Aktionsflächen (Weiß darauf: 5,2:1); `accent` bleibt Text-/Indikatorfarbe, jetzt je Schema nachgewiesen (hell Blau-700, dunkel/navy/rail Blau-500). Branding-Ableitung (`deriveAccentSurface`) passt die Aktionsfläche einer Betreiberfarbe an, begrenzt auf sechs Abdunklungsschritte. Alle axe-Ausnahmen (Buttons/Chips, Einstellungsseiten-Link) wurden ersatzlos aus `e2e/tests/accessibility.spec.ts` entfernt. Reproduktionsnachweis erbracht: 6 von 22 Theme-Tests rot vor dem Fix (u. a. `expected 3.2957… to be greater than or equal to 4.5`), 22/22 grün danach.

**Verifikation:** `frontend/src/theme/tokens.ts` zeigt `accentSurface: blue[700]` in allen vier Rollensätzen (Zeilen 100, 126, 142, 163, 188).

**Themen:** frontend, barrierefreiheit, theme, design

---

<a id="issue-636"></a>

## Issue #636 — fix(library): Verbleibende Chunk-/Zeilen-Restfenster nach #631/#633 schließen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #633 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #633 (#632) drei vorbestehende Restfenster derselben Zombie-Klasse: (1) `KnowledgeLibraryService#deleteLibrary` löschte Vector-Chunks vor den Dokumentzeilen (umgekehrte Reihenfolge zu #631) — Waisen-Chunks möglich; (2) Konnektor-Fehlerpfade in `FileProcessingService` räumten nach `storeChunks` geworfene Exceptions nicht ab, im Gegensatz zum Upload-Pfad; (3) `LibraryDocumentService#failAlreadyPersistedUpload` speicherte per `save` auf einer bereits committeten Zeile — dieselbe Zombie-Klasse wie #632, schmaleres Fenster.

**Geliefert:** Alle drei Punkte wurden im selben PR #633 wie #632 mitgeliefert (kein eigener PR): Löschreihenfolge in `deleteLibrary` umgedreht (Zeilen zuerst, dann Chunks); neue Methode `markConnectorFailedAfterException` räumt jetzt unbedingt per `vectorStore.delete` auf, bevor sie `markFailed` aufruft; `failAlreadyPersistedUpload` auf bedingte `markFailed`-UPDATE umgestellt. Vier neue Unit-Tests belegen alle drei Punkte mit Rot/Grün-Nachweis (`WantedButNotInvoked`, `VerificationInOrderFailure`, `TooManyActualInvocations` vor dem Fix, grün danach).

**Verifikation:** `FileProcessingService.java` enthält `markConnectorFailedAfterException` (Zeile 510) und referenziert sie an den Exception-Pfaden (130, 249, 333) — Umsetzung vorhanden. Zugehörige Testklassen (`KnowledgeLibraryServiceConnectorDeleteOrderTest`, `LibraryDocumentServiceTest`) sind laut PR-Dateiliste vorhanden.

**Themen:** backend, indexing, retrieval, spaces, race-condition, tests

---

<a id="issue-637"></a>

## Issue #637 — fix(indexing): RSS-Executor wendet sourceInsecureSsl nicht an
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #663 (2026-08-20)

**Laut Issue:** Follow-up aus #505 (Review): `RssFeedIndexingExecutor#execute` baute seinen `HttpClient` immer mit `insecureSsl=false` fest verdrahtet, unabhängig vom konfigurierten `sourceInsecureSsl` der Bibliothek — obwohl die Validierung das Flag für `RSS_FEED` genauso wie für `HTTP_DIRECTORY` erlaubt und `docs/deployment.md` es generisch für beide Quelltypen beschreibt. Gefordert: `targetLibrary.isSourceInsecureSsl()` lesen und übergeben, Test mit selbstsigniertem Zertifikat, ggf. Doku-Präzisierung.

**Geliefert:** `RssFeedIndexingExecutor#execute` übergibt jetzt `targetLibrary.isSourceInsecureSsl()` an `AutoindexCrawlerService.buildHttpClient` statt `false`. Neuer Test `RssFeedIndexingExecutorInsecureSslTest` gegen einen echten `HttpsServer` mit per `keytool` erzeugtem, genuin selbstsigniertem Zertifikat — rot vor dem Fix (`WantedButNotInvoked`), grün danach für beide Fälle (Flag true/false). Dokumentation musste laut PR nicht angepasst werden, da `docs/deployment.md` das Verhalten bereits generisch beschrieb.

**Verifikation:** `RssFeedIndexingExecutor.java` Zeile 187 übergibt `targetLibrary.isSourceInsecureSsl()` an den HttpClient-Aufbau — Umsetzung vorhanden.

**Themen:** backend, indexing, knowledge-sources, security, tls

---

<a id="issue-639"></a>

## Issue #639 — feat(query): sourceEntryUrl in Belegangaben (SourceReference) durchreichen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend
- PRs: #666 (2026-08-20)

**Laut Issue:** `sourceEntryUrl` (#493) war bereits in `LibraryDocumentResponse` sichtbar und auf der Bibliotheksdetailseite angezeigt, fehlte aber in der Belegangabe einer Chat-Antwort (`SourceReference`, `QueryService#mapSources`) — eine Anlage aus einem RSS-Feed-Eintrag ließ sich damit nicht direkt aus der Antwort ihrem Eintrag zuordnen. Gefordert: `sourceEntryUrl` als optionales Feld in `SourceReference` (OpenAPI zuerst), Auflösung per `document_id`-Lookup wie beim bestehenden `indexedAt`-Muster, Anzeige im Frontend (`SourceCard`). Explizit außerhalb des Umfangs: Chunk-Metadaten im Vektorspeicher anreichern.

**Geliefert:** `SourceReference` trägt jetzt `sourceEntryUrl` (OpenAPI-Spec, generierte DTOs). `QueryService#lookupSourceDocuments` (umbenannt aus `lookupIndexedAt`) löst `indexedAt` und `sourceEntryUrl` in einem gemeinsamen `DocumentRepository`-Lookup auf, kein zweiter Lookup. `mergeSourceReferences` gibt den Wert beim Deduplizieren weiter. Im Frontend zeigt die Belegkarte die Herkunft als Link, sofern gesetzt — laut PR-Body dasselbe Muster wie `LibraryDetailPage.tsx`. Entspricht dem Issue-Umfang vollständig; die Ausschlussgrenze (keine Chunk-Metadaten-Anreicherung) wurde eingehalten.

**Verifikation:** Die Komponente wird im PR-Body und in der Dateiliste als `SourceCard.tsx` geführt; im heutigen Code trägt die entsprechende Anzeigelogik andere Dateinamen (`SourceFootnotes.tsx`, `SourceEvidenceDrawer.tsx` in `frontend/src/components/chat/`), die `sourceEntryUrl` verwenden — die Komponente wurde also seither umbenannt/aufgeteilt, die Funktionalität ist aber im heutigen Code vorhanden.

**Themen:** backend, frontend, query, retrieval, spaces, knowledge-sources

---

<a id="issue-644"></a>

## Issue #644 — Buildzeiten und Merge-Durchsatz optimieren (Build-Cache, Merge Queue, CI-Zuschnitt)
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, ci
- PRs: #647 (2026-08-20)

**Laut Issue:** Vier Problembereiche im Entwicklungsprozess: lange Buildzeiten (kein Build-Cache), CI-Stau vor dem Merge (strict-Branch-Protection serialisiert Merges), ausgelastete Rechner durch mehrfache identische Gradle-Arbeit in parallelen Worktrees, sehr große Worktrees (~15 GB). Geplante Maßnahmen: (1) Gradle-Build-Cache/Parallelisierung/Configuration-Cache, (2) GitHub Merge Queue mit `strict` deaktiviert, (3) CI-Zuschnitt (Concurrency, Pfadfilter, keine doppelte Testausführung — eigener `openAiIntegrationTest`-Task), (4) Worktree-Hygiene-Regel in AGENTS.md, (5) pnpm-Migration bewusst zurückgestellt.

**Geliefert:** Maßnahmen 1–4 umgesetzt. Abweichung von Maßnahme 2: Statt der geplanten GitHub Merge Queue wurde auf Wunsch des Maintainers nur `strict` deaktiviert (sofortiges Mergen konfliktfreier PRs); der `merge_group`-Trigger blieb als Vorbereitung im Workflow erhalten, die Merge Queue selbst wurde nicht aktiviert. Build-Cache (benutzerweit, `~/.gradle/caches/build-cache-1`) verifiziert: voller Build 10 min, Folgebuild mit Cache 33 s. `openAiIntegrationTest` als eigener Task, aus `test`/`build` ausgeschlossen. `ci.yml`/`e2e.yml` mit `concurrency`-Block und Pfadfiltern (`dorny/paths-filter`). AGENTS.md um Auto-Merge-Arbeitsweise, Worktree-Aufräumregel mit Begründung und `npm ci`-Hinweis ergänzt. Maßnahme 5 (pnpm) laut Issue bewusst zurückgestellt, kein Bestandteil dieses PRs.

**Verifikation:** `backend/gradle.properties` enthält `org.gradle.caching=true`, `org.gradle.parallel=true`, `org.gradle.configuration-cache=true` mit Verweis auf #644. `ci.yml` verwendet aktuell `actions/checkout@v7` (durch #625 später weiter angehoben) und enthält laut Grep-Treffer weiterhin Cache-Schritte — Grundstruktur besteht fort.

**Themen:** ci, projektsetup, agenten-organisation, build-performance, worktrees

---

<a id="issue-646"></a>

## Issue #646 — fix(indexing): Feed-Zustand pro Bibliothek führen bzw. beim Löschen zurücksetzen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend
- PRs: #665 (2026-08-20)

**Laut Issue:** `rss_feed_state` war über die Feed-URL eindeutig geschlüsselt (`unique` auf `feed_url`), nicht über die Bibliothek. `KnowledgeLibraryService.deleteLibrary` räumte diese Tabelle beim Löschen nicht auf. Eine neue Bibliothek mit derselben (zuvor verwendeten) Feed-Adresse fand über `findByFeedUrl` den alten ETag/Last-Modified-Eintrag, erhielt sofort `304 Not Modified` und brach den ersten Lauf mit „0 Dokumente" ab — als Erfolg gemeldet, nicht als Fehler. Gefordert: Entscheiden zwischen pro-Bibliothek-Schlüssel oder Zustandsbereinigung beim Löschen, plus Klärung zum Fall der reinen Adressänderung ohne Löschung, plus Regressionstest.

**Geliefert:** Entscheidung für die pro-Bibliothek-Schlüsselung (Option „Feed-Zustand pro Bibliothek führen" aus dem Issue). Migration 045 fügt `library_id` hinzu, backfillt bestehende Zeilen, löscht verwaiste Zeilen und ersetzt die `feed_url`-only-Unique-Constraint durch `(library_id, feed_url)`. `fk_rss_feed_state_library` mit `ON DELETE CASCADE` löst die im Issue offen gelassene `deleteLibrary`-Frage auf Datenbankebene, nicht nur im Anwendungscode. Die zweite offene Frage (reine Adressänderung ohne Löschung) beantwortet sich von selbst: eine neue Adresse findet keinen Eintrag und startet wie eine neue Bibliothek. `RssFeedIndexingExecutor` nutzt jetzt `findByLibraryIdAndFeedUrl`. Reproduktionsnachweis über `Migration045KeyRssFeedStateByLibraryTest` (7 Tests), inklusive eines Tests, der den Defekt unabhängig von der Migration am alten Schema mit echter `SQLException` (`duplicate key value violates unique constraint`) belegt.

**Verifikation:** `RssFeedStateRepository.java` enthält `findByLibraryIdAndFeedUrl(UUID libraryId, String feedUrl)` — Umsetzung entspricht der PR-Beschreibung.

**Themen:** backend, indexing, rss, knowledge-sources, datenbank, migration

---

<a id="issue-650"></a>

## Issue #650 — fix(library): deleteLibrary lehnt Löschung bei laufendem Indizierungsjob mit 409 ab
- Geschlossen: 2026-08-20 (not planned)
- Labels: bug, backend
- PRs: keine

**Laut Issue:** Review-Befund aus PR #503/#501: `deleteLibrary` scheitert bei parallelem Indizierungslauf mit 500 statt 409 (FK-Verletzung), und bereits geschriebene Chunks können den Vector Store verwaisen. Geforderte Behebung: `DELETE /api/v1/libraries/{id}` mit 409 abweisen, solange `IndexingJobService#isJobRunning` `true` liefert.

**Geliefert:** Nichts im Rahmen dieses Issues — laut Abschlusskommentar des Maintainers (`gh issue view 650 --comments`) war der Guard bereits vorher über PR #602 (Umfangserweiterung von #433) umgesetzt: 409 mit deutscher Meldung bei laufendem Indizierungslauf, inklusive Unit-/Integrationstest mit Reproduktionsnachweis und OpenAPI-409-Dokumentation. Issue #650 wurde als Duplikat geschlossen, kein eigener PR nötig.

**Verifikation:** `KnowledgeLibraryService.java` existiert im Worktree; die 409-Logik stammt laut Kommentar aus PR #602, nicht separat nachgeprüft (außerhalb des Chunk-Umfangs).

**Themen:** knowledge-libraries, indexing, duplikat, backend

---

<a id="issue-651"></a>

## Issue #651 — fix(indexing): Redirect-Härtung lässt Host==null als 'nicht fremd' durch und ein Lauf bricht bei ungültiger Eintrags-URL komplett ab
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, security
- PRs: #664 (2026-08-20)

**Laut Issue:** Zwei Befunde aus dem Review zu PR #642: (1) `isForeignHostRedirect` in `RssFeedIndexingExecutor`/`UrlFileDownloader` behandelte einen nicht parsbaren Host (`getHost()==null`) fälschlich als "nicht fremd" statt wie `AutoindexCrawlerService.sameOrigin` als fremd. (2) `RssFeedIndexingExecutor#processEntry` fing keine `IllegalArgumentException` bei ungültiger Eintrags-URL ab, wodurch der gesamte Indizierungslauf statt nur des einzelnen Eintrags abbrach.

**Geliefert:** Beide Methoden delegieren jetzt vollständig an `AutoindexCrawlerService.sameOrigin`; eine neue `isValidUri`-Prüfung fängt ungültige Eintrags-URLs vorab ab und überspringt nur den betroffenen Eintrag. Deckt sich mit der Forderung des Issues, keine Abweichungen.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedIndexingExecutor.java` und `UrlFileDownloader.java` existieren im Worktree. Reproduktionsnachweis mit rotem/grünem Testlauf ist im PR dokumentiert (`UrlFileDownloaderTest`, `RssFeedIndexingExecutorTest`).

**Themen:** indexing, security, redirect-härtung, rss

---

<a id="issue-653"></a>

## Issue #653 — Frontend auf pnpm umstellen (Worktree-Größe und Installationszeit)
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:M, ci
- PRs: #752 (2026-08-23)

**Laut Issue:** Folge-Issue zu #644 (dort als Maßnahme 5 zurückgestellt). Jeder Agent-Worktree trug ein eigenes, vollständig kopiertes `frontend/node_modules` (Hunderte MB), `npm ci` entpackte jedes Mal neu. Gefordert war die Migration von npm auf pnpm (Frontend und ggf. E2E), CI-Anpassung, Docker-Anpassung und Dokumentation, damit ein frischer Worktree ohne vollständige `node_modules`-Kopie auskommt.

**Geliefert:** Vollständig. `frontend/` und `e2e/` migriert (`package-lock.json` → `pnpm-lock.yaml` via `pnpm import`), pnpm-Version über `packageManager`-Feld gepinnt (`pnpm@11.21.0`), `frontend/pnpm-workspace.yaml` neu (msw-Postinstall bewusst nicht erlaubt, `peerDependencyRules` für TypeScript 6). Nebenbefund: `@mui/utils` musste als direkte Abhängigkeit deklariert werden — pnpms strikte `node_modules` deckte fehlendes Hoisting auf (16 Testdateien scheiterten zunächst). CI (`ci.yml`, `e2e.yml`, `demo-smoke.yml`) auf `pnpm/action-setup@v4` umgestellt, `frontend/Dockerfile` auf corepack+pnpm, neue Root-`.dockerignore` ersetzt die wirkungslose `frontend/.dockerignore`. Dokumentation (AGENTS.md, e2e/README.md, demo/README.md, docs/deployment.md) nachgezogen. Frische Installation: ~7s statt Minuten.

**Verifikation:** `frontend/pnpm-lock.yaml` existiert im Worktree, `frontend/package-lock.json` existiert nicht mehr — konsistent mit vollständiger Migration.

**Themen:** frontend, ci, projektsetup, worktrees, pnpm

---

<a id="issue-654"></a>

## Issue #654 — feat(frontend): Dunkles Farbschema an das dunkle Schema der Claude-Docs anlehnen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #656 (2026-08-20)

**Laut Issue:** Maintainer-Entscheidung, das dunkle Farbschema von Navy-auf-Navy (`#012142`) auf eine neutrale, an code.claude.com angelehnte Grauskala (`#09090B`/`#171717`/`#252525` usw.) umzustellen, mit klarer Flächenstaffelung. Seitenleiste soll im hellen Schema Navy bleiben (`navyRoles`), im dunklen dem neuen Schema folgen. Guidelines und Tokens sollen synchron aktualisiert werden, Kontraste nach accessibility.md.

**Geliefert:** Genau wie gefordert umgesetzt: neue Carbon-Dunkelskala in `tokens.ts`, `navyRoles` für die helle Seitenleiste, `createSidebarTheme(appMode, branding)` kapselt die Wahl, `guidelines.md` synchron angepasst, Kontrastnachweise (u. a. fg-2 auf bg ≈ 9:1) im PR dokumentiert. Keine inhaltlichen Abweichungen vom Issue.

**Verifikation:** `frontend/src/theme/tokens.ts` und `frontend/src/theme/theme.ts` existieren im Worktree.

**Themen:** frontend, design, dark-mode, theming, accessibility

---

<a id="issue-658"></a>

## Issue #658 — feat(frontend): Typografie, Dichte und Komponentenmetrik an Mockup 1a angleichen (Quicksand, Feinraster, weiße Menüs)
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #660 (2026-08-20)

**Laut Issue:** Forensischer Abgleich mit Mockup 1a zeigte systematische Abweichungen bei Schriftart (Inter statt "Sklow"), UI-Grundgrößen, Seitenleisten-Metrik, Menü-Optik über Navy, Chat-Bubble-Gestaltung und Control-Metrik (Radius, Höhe, Padding). Gefordert: Quicksand als Schrift, Feinraster nach Mockup-Werten, Seitenleiste auf 272 px, helle Menü-Panels, Chatfläche optisch an Mockup angeglichen (nur Optik, kein Funktionsvorgriff auf #590/#591).

**Geliefert:** Deckt sich mit der Forderung — Quicksand via `@fontsource/quicksand` mit Inter-Fallback, Typografie-Feinraster (14,5/1.65 Fließtext, 13 px UI, 9,5 px Eyebrows), Controls auf Radius 6/Höhe 34, Seitenleiste auf 272 px mit hellen Menü-Panels über dem Navy-Block, Chatfläche als reiner Fließtext ohne Avatar/Bubble. Guidelines synchron aktualisiert, Barrierefreiheit (Tab-Reihenfolge trotz Hover-Aktionen, `aria-hidden` auf der sichtbaren Kopfzeile) im PR dokumentiert.

**Verifikation:** `frontend/src/components/chat/MessageBubble.tsx` und `frontend/src/components/chat/ChatInput.tsx` existieren im Worktree.

**Themen:** frontend, design, typografie, mockup, accessibility

---

<a id="issue-659"></a>

## Issue #659 — fix(indexing): Indizierungsfehlermeldung leakt internen Pfad/Host an VIEWER
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, security
- PRs: #657 (2026-08-20)

**Laut Issue:** Review-Befund zu PR #657 (Umsetzung von #507): `GET /api/v1/libraries/{id}/indexing/status` gab bei `FAILED`-Status die rohe `Exception#getMessage()` weiter (interner Dateisystempfad bei `NoSuchFileException`, Host:Port bei `UnknownHostException`/`ConnectException`) — für jeden VIEWER sichtbar, obwohl #507 dieselben Informationen für `LibraryResponse` bereits vor VIEWER verbirgt. Gefordert: generische oder kategorisierte deutsche Meldung unterhalb MANAGER, Detailmeldung ab MANAGER, Test dafür.

**Geliefert:** Der referenzierte PR #657 liefert beide Themen zugleich (#507 und #659) — die Fehlermeldung des Indizierungsstatus wird jetzt rollenabhängig gekürzt (generischer deutscher Text unterhalb MANAGER, vollständige Executor-Meldung ab MANAGER), mit Reproduktionsnachweis über `LibraryIndexingControllerTest`. Deckt die Abnahmekriterien vollständig ab; die Randnotiz zu `IndexingRunEvent.reference`/RSS-Host blieb wie im Issue vorgesehen unangetastet.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingStatusView.java` und `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` existieren im Worktree.

**Themen:** security, indexing, api, information-disclosure, backend

---

<a id="issue-661"></a>

## Issue #661 — docs(agents): Umgang mit Review-Befunden — direkt beheben statt Folge-Issues
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation
- PRs: #662 (2026-08-20)

**Laut Issue:** Maintainer-Anweisung vom 20.08.2026: Review-Befunde sollen standardmäßig im selben PR behoben werden, auch vorbestehende Kleinigkeiten ("links und rechts schauen"). Folge-Issues nur bei sehr großem Umfang oder komplett anderem Scope. Festzuhalten in `docs/AGENT-ORGANIZATION.md`, Workflow-Schritt Review.

**Geliefert:** Deckungsgleich — die Regel wurde in `docs/AGENT-ORGANIZATION.md`, Workflow Schritt 5 (Review), ergänzt.

**Verifikation:** `docs/AGENT-ORGANIZATION.md` existiert im Worktree.

**Themen:** agenten-organisation, doku, review-prozess

---

<a id="issue-667"></a>

## Issue #667 — feat(query): Fundort je Zitatstelle und durchsuchte Bestände in der Query-API ergänzen
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:M
- PRs: #753 (2026-08-23)

**Laut Issue:** Mit #590 zeigt der Chat Antworten mit Fußnoten und Fundstellen-Block nach Mockup 1a, aber zwei Angaben fehlten: ein menschenlesbarer Fundort je zitierter Stelle (z. B. "Abschn. 4.2", "§ 7 Abs. 2", "S. 2–4") und die Liste der tatsächlich durchsuchten Bibliotheken in der Verweigerungsantwort ("Durchsucht wurden: …"). Gefordert war eine spec-first-Erweiterung von `SourceReference` und `QueryMetadata` sowie der Frontend-Anschluss.

**Geliefert:** Beide Lücken geschlossen. `SourceReference.chunkLocations[{chunkIndex, location}]` liefert je Chunk einen Fundort (`null` wo nicht ermittelbar); `QueryMetadata.searchedLibraries[{id, name}]` die tatsächlich durchsuchten Bibliotheken. Neue Indexing-Komponenten `PageMarkingContentHandler` (Seitenmarker bleiben im extrahierten Text erhalten) und `ChunkLocationResolver` (Seitenbereich + Überschriftenpfad, kombiniert wo beides bekannt). Frontend: `SourceFootnotes` zeigt Fundorte, `MessageBubble` zeigt "Durchsucht wurden: …" nur bei Antworten ohne Zitat. Bekannte Grenze, im PR selbst benannt: Bestehende Indizes tragen noch keinen Fundort (erst ab Neu-Indizierung), `searchedLibraries` wird nicht persistiert (verschwindet nach Neuladen des Chats), Überschriften werden nur im Markdown-Stil erkannt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChunkLocationResolver.java` existiert im Worktree mit den beschriebenen Methoden (`forText`, Zeile 30/54).

**Themen:** retrieval, query, indexing, frontend

---

<a id="issue-677"></a>

## Issue #677 — fix(db): Bibliotheksreferenzen eines Chats an die Organisation binden
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, security
- PRs: #680 (2026-08-20)

**Laut Issue:** `chat_library_references` (Migration 032) führt keine `organization_id` und verknüpft Chat und Bibliothek nur über einspaltige Fremdschlüssel — die Mandantengrenze (ADR-0008) wurde damit nur anwendungsseitig gehalten, nicht auf DB-Ebene. Gefordert: `organization_id` ergänzen, zusammengesetzte Fremdschlüssel gegen `chats(id, organization_id)`/`knowledge_libraries(id, organization_id)`, Migrationstest nach dem Muster von Migration 046, Bestandsdatenbereinigung, Rollback.

**Geliefert:** Migration 048 wie gefordert, mit einer im Issue nicht vorgesehenen, aber sauber begründeten technischen Ergänzung: `organization_id` wird per BEFORE-INSERT-Trigger aus der Chat-Zeile abgeleitet (statt reiner NOT-NULL-Spalte), weil Hibernates `@ElementCollection`-Insert die Spalte sonst nicht befüllt hätte — im PR mit dem entsprechenden Testfehler belegt. Anwendungscode (`ChatService#requireReadableLibraries`) wurde geprüft und war bereits korrekt, kein Umbau nötig.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/048-bind-chat-library-references-to-organization.yaml` existiert im Worktree.

**Themen:** datenbank, mandantengrenze, migration, security, chats

---

<a id="issue-682"></a>

## Issue #682 — feat(space): Quellen- und Chatzahl in SpaceListResponse für die Übersichtskarten
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:S
- PRs: #754 (2026-08-23)

**Laut Issue:** Die Spaces-Übersicht (#593, Mockup 1c) sollte je Karte "n Quellen · n Chats · n Mitglieder" zeigen; `SpaceListResponse` lieferte bisher nur `memberCount`. Gefordert war die Erweiterung um `libraryCount` und `chatCount` (spec-first), ohne N+1-Abfragen, und der Frontend-Anschluss.

**Geliefert:** Wie gefordert. `SpaceListResponse` um optionale Felder `libraryCount`/`chatCount` erweitert. `chatCount` zählt nur die eigenen Chats des Aufrufers (Chats sind privat, #525). `libraryCount` folgt der Sichtbarkeitsregel der Zuordnungsliste (CURATOR/ADMIN/Owner/Systemadmin sehen alle, MEMBER nur lesbare) — bewusst so gewählt, damit die Zahl keine Rechte verrät, die die gefilterte Liste selbst nicht zeigt. Ohne N+1: eine gruppierte Chat-Zählung über alle gelisteten Spaces plus eine Zuordnungsabfrage. Nebeneffekt: die bisherige Einzelabfrage `existsBySpaceIdAndAuthorId` je archiviertem Space (#543) konnte entfallen. Frontend: `SpacesOverviewPage.spaceFigures` mit Singular/Plural und Fallback ohne die neuen Felder.

**Verifikation:** Nicht erneut geprüft — Änderung ist klein und lokal begrenzt (`SpaceService`, `SpaceListResponse`); Testabdeckung im PR-Body dokumentiert (`SpaceServiceIntegrationTest#listCountsAssignedLibrariesAndOnlyTheCallersOwnChats`).

**Themen:** spaces, api, frontend

---

<a id="issue-684"></a>

## Issue #684 — feat(library): Letzten Indexstand (lastIndexedAt) in LibraryListResponse für die Stand-Spalte

- Geschlossen: 2026-08-28 (completed)
- Labels: enhancement, backend, size:S
- PRs: #962 (2026-08-28)

**Laut Issue:** Die „Stand"-Spalte der Wissensbibliotheken-Tabelle (#595) zeigt ohne aktiven
Lauf nur „–", weil `LibraryListResponse` den letzten erfolgreichen Indexlauf nicht ausweist.
Gefordert: spec-first-Erweiterung um `lastIndexedAt` (und Wortlaut-Basis je Quellentyp).

**Geliefert:** PR #962 ergänzt `lastIndexedAt` in `LibraryListResponse` (OpenAPI-Spec, ADR-0006)
und befüllt die Stand-Spalte der Bibliotheksübersicht.

**Verifikation:** Commit `e527993b` auf `main`; Feld in der OpenAPI-Spec vorhanden.

**Themen:** Wissensbibliotheken, Oberfläche, API

---

<a id="issue-686"></a>

## Issue #686 — feat(space): Datenquellen-Zuordnung Space ↔ Wissensbibliothek (API und Retrieval)
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend
- PRs: #706 (2026-08-21)

**Laut Issue:** Für den Space-Assistenten (#594) fehlte die API-Grundlage für den Schritt "Datenquellen zuordnen": kein `libraryIds`-Feld, keine Zuordnungs-Ressource, keine Space↔Bibliothek-Beziehung im Backend. Gefordert: Domänenmodell + Persistenz (n:m), OpenAPI-Erweiterung mit Rechteprüfung (mind. Leserecht), Retrieval nutzt die Zuordnung als Standard-Suchbereich, Migrationstest. Frontend-Folgearbeit ausdrücklich als separat schätzbar markiert.

**Geliefert:** Deutlich mehr als im Issue verlangt — PR #706 liefert gemeinsam mit dem größeren Issue #203 die vollständige Umsetzung inklusive Frontend (Datenquellen-Schritt im Assistenten, Pflege in der Space-Verwaltung, Eigentümer-Sicht "Bereitgestellt in") sowie einen neuen Benachrichtigungsmechanismus (`Notification`, `NotificationService`, Glocke mit Badge), der im Issue nicht gefordert war, aber als Grundstein für ein späteres Postfach eingeordnet wird. Rechtemodell wie gefordert als reine Kuratierung umgesetzt: Zuordnung ändert keine effektiven Leserechte. Strikt-Modus (#204) und `@Space`-Chip im Chat wurden bewusst ausgelassen.

**Verifikation:** `backend/src/main/java/io/opaa/space/SpaceAssetAssociation.java` und `SpaceAssetAssociationService.java` existieren im Worktree.

**Themen:** spaces, retrieval, api, rechtemodell, benachrichtigungen, backend, frontend

---

<a id="issue-693"></a>

## Issue #693 — fix(indexing): Upgrade-Redirect http→https auf demselben Host wird fälschlich als fremder Host abgewiesen
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend, size:S, security
- PRs: #699 (2026-08-21)

**Laut Issue:** Produktionsbefund: RSS-Lauf der Bibliothek "Düsseldorf Pressedienst" scheiterte komplett (0 verarbeitet, 13 übersprungen), weil die Redirect-Härtung aus #651 einen harmlosen `http→https`-Upgrade-Redirect auf demselben Host als fremde Origin wertete (`sameOrigin` vergleicht auch das Schema). Gefordert: Upgrade bei gleichem Host/Standardport zulassen, Downgrade weiter verbieten, Credentials nach dem Upgrade weiter senden.

**Geliefert:** PR #699 behebt #693 wie gefordert (`isRedirectOriginTrusted`-Ausnahme für den Schema-Upgrade bei gleichem Host/Port) und bündelt dabei zusätzlich zwei benachbarte, im selben Arbeitsstrang koordinierte Vorgänge: #267 (SSRF-Zielprüfung `TargetAddressValidator` gegen Loopback/Link-Local/private Bereiche) und #617 (Zugangsdaten-Fallback erzwingt jetzt den gespeicherten Proxy/`insecureSsl` der Bibliothek statt den des Aufrufers). Beide sind eigenständige Issues außerhalb dieses Chunks — hier nur als Kontext vermerkt, nicht als Lieferung für #693 selbst gewertet. Reproduktionsnachweis für alle drei Teile im PR mit rotem/grünem Lauf dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/TargetAddressValidator.java` und `AutoindexCrawlerService.java` existieren im Worktree.

**Themen:** indexing, security, ssrf, redirect-härtung, rss

---

<a id="issue-707"></a>

## Issue #707 — fix(frontend): CSP blockiert als data:-URI gebündelte Font-Subsets im Docker-Deployment
- Geschlossen: 2026-08-25 (completed)
- Labels: bug, frontend
- PRs: #910 (2026-08-25)

**Laut Issue:** Beim OIDC-Testlauf des Docker-Deployments meldete die Browser-Konsole sechs CSP-Verstöße pro Seite: Vite bündelt Assets unter 4 KB als `data:`-URIs, kleine Quicksand-Subsets (kyrillisch/vietnamesisch) unterschritten diese Grenze, die nginx-CSP erlaubt aber nur `font-src 'self'`. Zwei Varianten standen zur Wahl: CSP lockern (`font-src 'self' data:`) oder Font-Inlining unterbinden.

**Geliefert:** Variante 2 (striktere CSP bleibt erhalten): `build.assetsInlineLimit` in `vite.config.ts` emittiert Fonts (woff2/woff/ttf/otf/eot) jetzt immer als Datei. Begründung im PR: Die CSP steht in vier Kopien in `frontend/nginx.conf`, eine Lockerung müsste dort mehrfach erfolgen und bliebe dauerhaft breiter als nötig. Neuer `e2e/tests/csp.spec.ts` sammelt alle CSP-Konsolenmeldungen beim Laden gegen den echten Docker-Compose-Stack — Regressionsschutz auch gegen künftige Ursachen. Reproduktionsnachweis: vor dem Fix 1 Test failed mit den sechs Font-CSP-Verstößen, danach 39/39 grün, `grep -c 'data:font' dist/assets/*.css` → 0.

**Verifikation:** `e2e/tests/csp.spec.ts` existiert im Worktree; `frontend/vite.config.ts` als geänderte Datei laut PR-Dateiliste plausibel für die beschriebene `assetsInlineLimit`-Funktion (nicht einzeln nachgelesen).

**Themen:** frontend, security, deployment, barrierefreiheit

---

<a id="issue-708"></a>

## Issue #708 — feat: Demo-Instanz mit Verwaltungskorpus einer fiktiven Stadt
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, epic, demo
- PRs: keine

**Laut Issue:** Epic, das die Demo-Arbeit aus Epic #224 übernimmt und vom Eval-Korpus (Superhelden) trennt. Ziel: präsentierbare Demo-Instanz mit verwaltungsthematischem Korpus einer fiktiven deutschen Stadt, mehreren Nutzern/Spaces/Bibliotheken/Berechtigungskonstellationen, allen Konnektortypen, Ein-Befehl-Installation, getrennten Datenprofilen `demo`/`e2e`, dokumentiert in `docs/`.

**Geliefert:** Als Epic ohne eigenen PR über native Sub-Issues abgearbeitet — Phase 1 (Konzept, #709), Phase 2 (Korpus-Generator, #711), Phase 3 (Seed-Mechanismus #712, Drehbuch/Doku #713); alle vier in diesem Chunk enthalten und als "completed" geschlossen. Phase 4 (Smoke-Test #232, E2E-Umstellung #233, Rollout #230) liegt außerhalb dieses Chunks und ist am Schließdatum des Epics (22.08.) noch nicht zwingend erledigt — nicht anhand der hier vorliegenden Daten prüfbar.

**Verifikation:** `demo/` existiert im Worktree mit `README.md`, `corpus/`, `seed/`.

**Themen:** demo, epic, verwaltungskorpus, projektsetup

---

<a id="issue-709"></a>

## Issue #709 — docs(demo): Konzept und Quellenrecherche für den Verwaltungskorpus der Demo-Instanz
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, demo
- PRs: #710 (2026-08-21)

**Laut Issue:** Vor dem Bau von Generator, Feed und Seed muss das fachliche Konzept stehen: Quellenrecherche mit Lizenzangaben, fiktive Stadt, Ämter/Dokumenttypen, Personas/Rechte, Drehbuch-Skizze — als Konzeptdokument, vom Maintainer zu entscheiden.

**Geliefert:** Deckungsgleich — `docs/features/demo-instance.md` mit der fiktiven Stadt Rheinfurt (~120.000 Einwohner), Szenario Bürgerbüro (Meldewesen & Ausweise, Kfz-Zulassung, Amtsleitung), Quellenlage (LHM-Corpus MIT, FIM/LeiKa als Katalog, Kölner Pressemeldungen und Düsseldorfer RSS-Feed als Stilvorlagen), fünf Bibliotheken über alle drei Konnektortypen, vier Nutzer plus Admin mit Berechtigungsmatrix, Drehbuch-Skizze mit acht Fragen. Vom Maintainer entschieden.

**Verifikation:** `docs/features/demo-instance.md` liegt im Bereich der Datei, die für #711–#713 als Spezifikation referenziert wird; nicht separat erneut geprüft (Existenz über Folge-Issues bestätigt).

**Themen:** demo, konzept, doku, verwaltungskorpus

---

<a id="issue-711"></a>

## Issue #711 — feat(demo): Korpus-Generator für die fiktive Stadt Rheinfurt
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:L, demo
- PRs: #717 (2026-08-21)

**Laut Issue:** Deterministischer Generator unter `demo/`, der aus dem LHM-Dienstleistungen-Corpus (MIT) einen auf Rheinfurt umgeschriebenen Verwaltungskorpus erzeugt — 150–300 Dokumente über fünf Bibliotheken (Meldewesen, Kfz, Satzungen, Pressemitteilungen, interne Dienstanweisungen) in den Formaten `.md`, `.txt`, `.pdf`, `.docx`, `.pptx`. Werkzeugwahl für Binärformate ist Teil des Tickets, `eval/` muss unangetastet bleiben.

**Geliefert:** Deckungsgleich — 156 Dokumente über die fünf Bibliotheken, reine Python-Bibliotheken (`reportlab`, `python-docx`, `python-pptx`) statt externer Binärwerkzeuge, mit dokumentierten Determinismus-Fixes (`reportlab.rl_config.invariant`, eigener `zip_utils.normalize_zip_timestamps` gegen Zip-Zeitstempel). Reproduzierbarkeit über zwei Läufe und SHA-256-Manifest belegt. Fischereierlaubnis bewusst nicht im Korpus, wie im Drehbuch gefordert.

**Verifikation:** `demo/corpus/MANIFEST.sha256` und `demo/README.md` existieren im Worktree.

**Themen:** demo, korpus-generator, verwaltungskorpus, synthetische-daten

---

<a id="issue-712"></a>

## Issue #712 — feat(demo): Seed-Mechanismus mit den Datenprofilen demo und e2e
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:L, auth, demo
- PRs: #724 (2026-08-21)

**Laut Issue:** Ein wiederverwendbarer Seed-Mechanismus soll über die öffentliche API Nutzer, Spaces, Bibliotheken, Rechte, Uploads und Indizierung einrichten, mit zwei Datenprofilen (`demo`: Rheinfurt via Keycloak; `e2e`: minimal via dev-Auth), idempotent, sowie die Compose-Profil-Frage für den `keycloak`-Service klären und begründen.

**Geliefert:** Deckungsgleich — `demo/seed/seed.py` spricht ausschließlich die öffentliche API an, provisioniert Nutzer über deren erste authentifizierte Anfrage, legt Spaces/Bibliotheken/VIEWER-Rechte/Uploads/Indizierung an. Neuer Keycloak-Client `opaa-seed` (Resource-Owner-Password-Grant, getrennt vom Frontend-Client). Entscheidung: `keycloak` zusätzlich dem Compose-Profil `demo` zugeordnet, damit `docker compose --profile demo up` allein genügt — im PR begründet. Idempotenz und VIEWER-Matrix im PR gegen einen isolierten Teststack verifiziert. Bibliotheken bewusst mit `visibility: PRIVATE` statt `ORGANIZATION` angelegt, um die Rechtematrix nicht auszuhebeln.

**Verifikation:** `demo/seed/seed.py`, `demo/seed/profiles.py`, `keycloak/realm-export.json` existieren im Worktree.

**Themen:** demo, seed, auth, keycloak, idempotenz

---

<a id="issue-713"></a>

## Issue #713 — docs(demo): Installationsanleitung, Nutzerkonten und Drehbuch der Demo-Instanz
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:M, demo
- PRs: #727 (2026-08-21)

**Laut Issue:** Anwenderdokumentation für die Rheinfurt-Demo: Installation mit einem Befehl, Nutzerkonten-Tabelle mit Rollen/Spaces/lesbaren Bibliotheken, ausformuliertes Drehbuch mit den acht Konzeptfragen (inkl. Berechtigungs-Doppelfrage und bewusst unbeantwortbarer Frage), Ablauf zur Korpus-Aktualisierung, Verweis von `search-quality-evaluation.md` auf das neue Konzept.

**Geliefert:** Deckungsgleich — neue Seite `docs/demo-walkthrough.md`, verlinkt aus `README.md`, `demo/README.md` und `docs/features/demo-instance.md` statt dupliziert. Drei Drehbuchfragen (Berechtigungs-Doppelfrage, Quer-Bibliotheks-Frage, unbeantwortbare Frage) wurden gegen einen isolierten Compose-Stack mit `ai-stub` tatsächlich durchgespielt und im PR mit den API-Antworten belegt; die übrigen Fragen beruhen auf manueller Korpusprüfung, da `ai-stub` inhaltliche Relevanz nicht sinnvoll misst (dokumentierte Einschränkung, keine verschwiegene Lücke).

**Verifikation:** `docs/demo-walkthrough.md` existiert im Worktree.

**Themen:** demo, doku, drehbuch, installation

---

<a id="issue-716"></a>

## Issue #716 — fix(deployment): Schnellstart-Kopie von .env.example ergibt keinen startfähigen Compose-Stack
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, documentation
- PRs: #719 (2026-08-21)

**Laut Issue:** Der dokumentierte Schnellstart `cp .env.example .env.docker` führte zu keinem lauffähigen Stack: fehlendes `SPRING_PROFILES_ACTIVE` (Abbruch durch `AuthProfileGuard`), falsches `OPAA_DB_URL` (`localhost` statt Container-Hostname), falsche CORS-Origin (Port 5173 statt 3000). Lösung offen (angepasste `.env.example` oder getrennte Vorlagen), im PR zu begründen.

**Geliefert:** Deckungsgleich — Entscheidung für getrennte Vorlagen: `.env.example` bleibt die `bootRun`-Vorlage, neue `.env.docker.example` ist die Compose-Vorlage mit `SPRING_PROFILES_ACTIVE=docker,dev`, auskommentiertem `OPAA_DB_URL`, korrekter CORS-Origin. `docs/deployment.md` und `.gitignore` (Ausnahme `!.env.docker.example`) nachgezogen. Nachweis: frischer `cp` + `docker compose up` in isoliertem Projekt, Backend startet, CORS und dev-Login funktionieren.

**Verifikation:** `.env.docker.example` existiert im Worktree.

**Themen:** deployment, doku, docker, konfiguration

---

<a id="issue-718"></a>

## Issue #718 — feat(frontend): @Space-Chip für die Chip-Leiste (blockiert: offene Entscheidung zum @Alles-Wissen-Rückfall)
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, frontend, workspace
- PRs: keine

**Laut Issue:** Mit #203/#686 (PR #706) existiert die Space↔Bibliothek-Zuordnung mit Suchbereichswirkung; der in der Spec vorgesehene Spezial-Chip "@Space" ("nur die dem Space assoziierten Bibliotheken") wurde dabei bewusst ausgelassen. Das Issue führt ihn als Folgearbeit — aber ausdrücklich blockiert durch eine offene Grundsatzfrage: Soll @Alles-Wissen in einem Space ohne Zuordnungen weiterhin auf "alles Lesbare" zurückfallen, und welche Rolle spielt @Space dann noch? Diese Frage sollte in einer Maintainer-Runde geklärt werden, bevor der Chip gebaut wird.

**Geliefert:** Nichts — konsistent mit dem Issue selbst, das den Chip ausdrücklich bis zur Klärung zurückstellt ("Bis zur Entscheidung wird der @Space-Chip nicht gebaut"). Als "not planned" geschlossen, ohne dass die Grundsatzentscheidung laut vorliegendem Chunk getroffen wurde.

**Verifikation:** Keine Code-Prüfung nötig — das Issue dokumentiert seine eigene Nichtumsetzung.

**Themen:** frontend, workspace, spaces, retrieval, offene-entscheidung

---

<a id="issue-720"></a>

## Issue #720 — feat(deployment): Ollama als optionalen Compose-Service unter eigenem Profil bereitstellen
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement
- PRs: #801 (2026-08-23)

**Laut Issue:** Der Compose-Stack versprach als Voreinstellung lokal betriebene Modelle über Ollama (`http://ollama:11434`), enthielt aber keinen `ollama`-Service — Indizierung und erste Frage liefen in einen Verbindungsfehler. Gefordert war ein `ollama`-Service unter eigenem Compose-Profil, Modell-Bereitstellung (`ollama pull`), und Doku-Nachzug, ohne dass der Standardbetrieb (ohne Profil) sich ändert.

**Geliefert:** Wie gefordert. Zwei neue Services unter Profil `ollama`: `ollama` (Server, benanntes Volume, kein Host-Port) und `ollama-pull` (einmaliger, idempotenter Init-Schritt, zieht `nomic-embed-text` und `phi3:mini`). `docs/deployment.md` um Abschnitt "Lokal betriebenes Ollama im Compose-Stack" ergänzt. Zusätzlich im selben PR ein themennaher Fund behoben: `.env.docker.example` setzte `OPAA_PGVECTOR_DIMENSIONS=1536`, obwohl das dort voreingestellte `nomic-embed-text`-Modell mit 768 Dimensionen einbettet — jede Indizierung mit der unveränderten Beispielkonfiguration wäre sofort gescheitert; jetzt auf 768 korrigiert. Verifikation im PR dokumentiert: mit Profil vollständiger Indizierungs-/Frage-Durchlauf erfolgreich, ohne Profil unverändertes Verhalten, kein Port-Expose über 127.0.0.1 hinaus.

**Verifikation:** Nicht erneut im Code geprüft — Compose-/Doku-Änderung ohne Anwendungscode, PR-Beschreibung dokumentiert eigene Verifikationsschritte ausführlich.

**Themen:** deployment, docker, modellverwaltung, ollama

---

<a id="issue-721"></a>

## Issue #721 — feat(eval): Retrieval-Harness für mehrchunkige Dokumente ertüchtigen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:L, ci, evaluation
- PRs: #723 (2026-08-21)

**Laut Issue:** Der Retrieval-Harness konnte nur einchunkige Korpora messen (Ein-Chunk-Invariante, ADR-0010, hart verdrahtet). Vor der neuen mehrchunkigen Eval-Domäne (#234) sollte der Harness mehrchunkfähig werden: Chunk-Zahl-Eigenschaft als Domänen-Property, dokumentbezogenes k-Fenster (`documentTopK`) statt chunkbezogenem, neue Chunkebenen-Metrik über `answer_span`, Chunk-Map als Nebenprodukt, Domänen-Parametrisierung, erweiterte Baseline-Gültigkeitsfelder, `measurementContractVersion` erhöht, ADR-0010/ADR-0012 fortgeschrieben, Comichelden-Baseline neu gezogen (erwartet bitgleich).

**Geliefert:** Deckungsgleich, inklusive des geforderten Belegs der eigentlichen Fehlerwirkung (alter chunkbezogener vs. neuer dokumentbezogener topK, deutlich unterschiedliche nDCG@10/Recall@10 auf einem synthetischen Korpus). Comichelden-Baseline blieb bis auf eine erklärte Rundungs-Tie (0,912→0,913 bei `difficulty:easy`/`mrr`, kein Rechenunterschied) bitgleich. Bewusst nicht umgesetzt: Gradle-Task-Parametrisierung über mehrere Domänen (als spekulativ verworfen, solange nur eine Domäne existiert) und ein zweiter echter Testcontainers-Lauf für die Mehr-Chunk-Invariante (stattdessen vollständig Docker-frei unit-getestet) — beides im PR offen begründet, keine verschwiegene Lücke.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/ChunkAnswerSpanMetrics.java` und `ChunkCountExpectation.java` existieren im Worktree.

**Themen:** evaluation, retrieval, ci, chunking, adr

---

<a id="issue-725"></a>

## Issue #725 — fix(a11y): Farbkontrast in der Wissensbibliotheken-Tabelle unzureichend
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, frontend, size:S
- PRs: #852 (2026-08-24)

**Laut Issue:** Die Tabelle der Wissensbibliotheken zeigte für Metadaten-Spalten (u. a. "Stand", Beschreibungstext) einen Farbkontrast von 3,68:1 gegen Weiß — unter der WCAG-2.1-AA-Anforderung von 4,5:1. Der Fehler war vorher unentdeckt, weil vor #233 (Umstellung der E2E-Suite auf das gemeinsame Seed-Profil) die Tabelle in den a11y-Tests immer leer gerendert wurde. Gefordert war eine Kontrastkorrektur ohne das übrige Design zu brechen, und Entfernung der dafür eingeführten axe-Ausnahme.

**Geliefert:** Wie gefordert. Statt die Grau-Skala selbst zu ändern (erste Fassung hätte die Skalenabstufung verzerrt), zeigt die Rolle `fg-3` (Tertiärtext) im hellen Schema jetzt auf `gray[500]` (`#556473`, 6,08:1) statt `gray[400]` (`#778797`, 3,68:1). `gray[400]` selbst bleibt für andere Verwendungen (Ränder, UI-Kontrast ≥ 3:1) unverändert. Die `.MuiTable-root`-Ausnahme in `e2e/tests/accessibility.spec.ts` wurde entfernt. Reproduktionsnachweis: Theme-Test zeigt vor der Umstellung `expected 3.686… to be greater than or equal to 4.5`, danach bestehen alle drei Kombinationen (6,08/5,71/5,40:1 gegen bg1/bg2/bg3).

**Verifikation:** Nicht erneut im Code geprüft — Änderung ist eine reine Token-/Rollenzuordnung in `frontend/src/theme/tokens.ts`, Nachweis im PR-Body als Test dokumentiert.

**Themen:** frontend, barrierefreiheit, theme, wissensbibliotheken

---

<a id="issue-731"></a>

## Issue #731 — fix(api): Rate-Limit-Meldung ist englisch statt deutsch
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend
- PRs: #733 (2026-08-21)

**Laut Issue:** Bei HTTP 429 lieferte die API `"Rate limit exceeded. Please try again later."` — englisch, entgegen der Projektsprachregel (AGENTS.md) und Abnahmekriterium von #230. Gefordert: deutsche Meldung für alle Kontingente, Prüfung ob das Frontend eigene Texte hat, Reproduktionsnachweis.

**Geliefert:** Deckungsgleich — Meldung auf "Zu viele Anfragen — bitte versuchen Sie es in Kürze erneut." umgestellt. Frontend hatte keine eigenen Texte (reicht die Backend-Meldung durch), Fixture in `chatStore.test.ts` entsprechend angepasst. Reproduktionsnachweis mit rotem (4 Testfehlschläge) und grünem Lauf im PR dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/api/RateLimitFilter.java` existiert im Worktree.

**Themen:** api, i18n, rate-limiting, backend

---

<a id="issue-734"></a>

## Issue #734 — Ollama-Embedding-Aufrufe in io.opaa.indexing parallelisieren (city-landmarks-Eval-CI zu langsam)
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, evaluation
- PRs: #735 (2026-08-22)

**Laut Issue:** Der CI-Job `evaluate-city-landmarks` brauchte auf dem GitHub-Actions-Runner überproportional lange (hochgerechnet ~115 Minuten für 200 Dokumente, Faktor >3 gegenüber lokal), vermutlich weil Embedding-Aufrufe pro Dokument sequenziell an Ollama gehen. Vorschlag: Chunk-Embedding-Aufrufe parallelisieren oder bündeln, betrifft die Indizierungs-Pipeline insgesamt.

**Geliefert:** Teilweise, mit im PR offen benannter Abweichung vom ursprünglichen Ziel: `FileProcessingService#storeChunks` embeddet Chunks eines Dokuments jetzt konfigurierbar nebenläufig (`opaa.indexing.embedding-concurrency`, Default 3), mit gemessenem, aber auf CPU-gebundenem lokalem Ollama nur geringem Gewinn (Faktor 1,05×) und deutlicherem Gewinn gegen ein simuliertes latenzgebundenes API-/GPU-Backend (bis 1,22×). **Löst die eigentliche CI-Laufzeit-Regression ausdrücklich nicht** — der Regressionsjob bleibt bei `embedding-concurrency=1` gepinnt, weil pgvectors HNSW-Indexaufbau einfügereihenfolge-sensitiv ist und die Baseline sonst nicht mehr reproduzierbar wäre. Der Nutzen liegt laut PR im Produktivbetrieb mit latenzgebundenen Backends, nicht im CI-Job, den das Issue ursprünglich adressierte; die CI-Laufzeit bleibt über das Zeitbudget in der Workflow-Datei aufgefangen.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java` existiert im Worktree.

**Themen:** indexing, performance, ci, evaluation, embeddings

---

<a id="issue-736"></a>

## Issue #736 — feat(api): Download-Endpunkt für Originaldokumente
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M
- PRs: #742 (2026-08-22)

**Laut Issue:** Maintainer-Feedback aus dem Klick-Test der Demo: Originaldokumente sollen aus Suchergebnissen/Bibliotheken abrufbar sein. Gefordert: `GET /api/v1/documents/{documentId}/content` (spec-first), Streaming mit korrektem Content-Type/`Content-Disposition: inline`, Zugriffsprüfung mind. VIEWER über `LibraryAccessService`, 404 bei fremden Dokumenten (kein Existenz-Leak), nur `UPLOAD`/`FILESYSTEM` liefern Inhalte, Path-Traversal-Schutz, Integrationstests, Doku-Update.

**Geliefert:** Deckungsgleich laut PR-Beschreibung — neuer `DocumentController` (eigener Pfad statt `LibraryController`, da Präfix-Kollision), `LibraryDocumentService#loadContent` mit Rollen-, Quellentyp- und Traversal-Prüfung, einheitliches 404 für alle Fehlerfälle. Integrationstests für Erfolgsfall, fehlende Berechtigung, Remote-Quelle, fehlende Datei, Traversal-Versuch.

**Verifikation:** Der Worktree-Branch (`feature/744_leistungsinventur`) wurde vor dem Merge dieses PRs erstellt (letzter enthaltener Commit: #735 vom 22.08. früh) — `backend/src/main/java/io/opaa/api/DocumentController.java` ist im Worktree **nicht** vorhanden. Das ist ein Zeitfenster-Effekt des Worktrees, keine Auffälligkeit am Code selbst; laut `git -C main log` ist der Merge-Commit `2afa1e1` auf `main` vorhanden.

**Themen:** api, dokumente, download, backend, deeplinks

---

<a id="issue-737"></a>

## Issue #737 — fix(auth): Plötzlicher Logout — Silent-Token-Renew und 401-Retry statt Sofort-Logout
- Geschlossen: 2026-08-22 (completed)
- Labels: bug, frontend, size:M, auth
- PRs: #741 (2026-08-22)

**Laut Issue:** Maintainer-Beobachtung auf der Demo-Instanz: zufälliges Ausloggen. Ursache: `authStore` hielt das Access-Token nur als einmaligen Snapshot, kein Handler übernahm die stille Erneuerung durch `oidc-client-ts`; nach Ablauf der Keycloak-Default-Lebensdauer (5 min) führte jeder 401 sofort zu `signoutRedirect()`, auch durch Hintergrund-Polling ausgelöst. Gefordert: `UserManager`-Events abonnieren, `automaticSilentRenew`, zweistufige 401-Behandlung (Silent-Renew-Versuch + Retry, erst dann lokaler Logout ohne IdP-Zerstörung), explizite Keycloak-Lebensdauern, Reproduktionsnachweis, ADR-0005-Update.

**Geliefert:** Deckungsgleich — `authStore` abonniert `addUserLoaded`/`addUserUnloaded`/`addSilentRenewError`, `apiInterceptors.ts` liest das Token asynchron und macht bei 401 einen `signinSilent()`-Versuch mit `_retry`-Guard vor dem endgültigen `expireSession()` (kein `signoutRedirect()` mehr im Fehlerfall). `keycloak/realm-export.json` setzt `accessTokenLifespan`/`ssoSessionIdleTimeout`/`ssoSessionMaxLifespan` explizit. ADR-0005 aktualisiert. Reproduktionsnachweis mit rotem (6 Testfehlschläge) und grünem Lauf im PR.

**Verifikation:** `frontend/src/stores/authStore.ts` und `frontend/src/services/apiInterceptors.ts` existieren im Worktree.

**Themen:** auth, frontend, keycloak, oidc, session-management

---

<a id="issue-738"></a>

## Issue #738 — feat(library): Deeplink auf das Originaldokument in der Wissensbibliothek
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #743 (2026-08-22)

**Laut Issue:** In der Dokumentliste einer Wissensbibliothek soll ein Klick das Original öffnen (Blob-Download über den Endpunkt aus #736, neuer Tab, Browser-Vorschau bei PDF/Bildern, sonst Download); bei externen Quellen (`HTTP_DIRECTORY`/`RSS_FEED`) die Quell-URL öffnen; Fehlerfall mit deutscher Meldung statt leerem Tab; Vitest-Tests für beide Pfade.

**Geliefert:** Deckungsgleich, mit einer im PR offen benannten, notwendigen API-Erweiterung über den Issue-Text hinaus: `LibraryDocumentResponse` bekam ein neues, nicht-sensibles Feld `sourceUrl`, weil das bestehende `sourceEntryUrl` für `HTTP_DIRECTORY` immer `null` war und die Abnahmekriterien sonst nicht erfüllbar gewesen wären. Gemeinsames Hilfsmodul `documentContent.ts` bewusst generisch gehalten für die spätere Zitat-Deeplink-Arbeit (#739).

**Verifikation:** Der Worktree-Branch wurde vor dem Merge dieses PRs erstellt (letzter enthaltener Commit vom 22.08. früh, vor #742/#743) — `frontend/src/utils/documentContent.ts` ist im Worktree **nicht** vorhanden. Zeitfenster-Effekt des Worktrees, keine inhaltliche Auffälligkeit; Merge-Commit `5aa3130` ist laut PR-Angabe auf `main`.

**Themen:** frontend, dokumente, deeplinks, bibliothek, ux

---

<a id="issue-739"></a>

## Issue #739 — feat(search): Deeplinks auf Originaldokumente in Fundstellen und Belegfenster
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #745 (2026-08-22)

**Laut Issue:** Maintainer-Feedback aus dem Klick-Test der Demo-Instanz: Unter zitierten Quellen und im Belegfenster sollte das Originaldokument per Deeplink erreichbar sein. `SourceReference` trug bisher weder `documentId` noch `libraryId`; der Merge der Quellenliste lief über den Dateinamen. Teil des Epics #740. Gefordert war die OpenAPI-Erweiterung, Backend-Befüllung, Umstellung der Merge-Logik auf `documentId`, und Frontend-Links.

**Geliefert:** Wie gefordert, mit einer bewussten Abweichung: `SourceReference` erhielt `documentId`, `sourceType` und zusätzlich `sourceUrl` (mehr als im Issue explizit gefordert). `mergeSourceReferences`/`mapSources`/`countMatchesPerDocument` schlüsseln jetzt auf `document_id` statt Dateiname — zwei gleichnamige Dokumente fallen nicht mehr zusammen. Frontend: "Im Dokument öffnen" nutzt für lokale Originale ein gemeinsames Hilfsmodul (`documentContent.ts`, aus #738), für Remote-Quellen die Quell-URL. Ausdrücklich als Annahme vermerkt: `citations.ts` ändert den bestehenden Zuordnungsschlüssel zwischen Zitat-Text und Quellenliste (weiterhin Dateiname) nicht — das wäre eine separate, im Issue nicht geforderte Änderung.

**Verifikation:** Nicht erneut im Code geprüft — Änderung baut auf #736/#738 auf (außerhalb dieses Chunks), PR-Beschreibung dokumentiert Backend- und Frontend-Testabdeckung ausführlich.

**Themen:** retrieval, query, frontend, deeplinks, wissensbibliotheken

---

<a id="issue-740"></a>

## Issue #740 — feat: Deeplinks auf Originaldokumente & stabile Anmeldung
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, epic
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Epic aus dem Maintainer-Feedback des Klick-Tests der Demo-Instanz (22.08.2026): Aus Suchantworten und Wissensbibliotheken sollten indizierte Originaldokumente per Deeplink erreichbar sein, und die Anmeldung sollte nicht mehr nach wenigen Minuten "plötzlich" enden. Zwei parallele Phasen: #736 (Download-Endpunkt, Fundament) und #737 (Logout-Bugfix, unabhängig), danach #738 (Bibliotheks-Deeplink) und #739 (Fundstellen/Belegfenster-Deeplink).

**Geliefert:** Die Arbeit steckt in den Sub-Issues. #739 ist im vorliegenden Chunk enthalten und wurde einzeln geprüft (siehe issue-739.md, PR #745) — dort ist explizit vermerkt "Closes #739 (letztes Sub-Issue von Epic #740)", was bestätigt, dass alle Sub-Issues (#736, #737, #738, #739) zum Zeitpunkt des Epic-Abschlusses gemergt waren. #736, #737, #738 liegen außerhalb dieses Delta-Chunks und wurden hier nicht einzeln nachgeprüft.

**Verifikation:** Über #739 bestätigt (letztes Sub-Issue, PR #745 gemergt am selben Tag wie der Epic-Abschluss).

**Themen:** retrieval, auth, frontend, deeplinks, epic

---

<a id="issue-744"></a>

## Issue #744 — Leistungsinventur: Bestandsaufnahme aller abgeschlossenen Issues und PRs für den Meilenstein-1-Report
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation
- PRs: #746 (2026-08-23)

**Laut Issue:** Meilenstein 1 (31.08.2026) verlangt eine Aufstellung der bisher implementierten Leistungen als Grundlage für Abnahme und Priorisierung, weil `docs/STATUS.md` als Grundlage nicht verlässlich genug ist. Vorgehen: Bausteine je geschlossenem Issue/PR, thematische Gruppierung, erster Report-Entwurf. Ein Diff-Anker (Commit-Hash + Zeitstempel) sollte spätere Fortschreibung als Delta ermöglichen, statt jedes Mal vollständig neu zu erheben.

**Geliefert:** Wie gefordert — dieses Issue hat das Format begründet, in dem der vorliegende Baustein selbst entsteht. PR #746 führt `docs/fortschritt/` als neues Dokumentationsformat ein: je Stichtag ein Delta-Ordner mit `anker.md`, `bausteine/` (378 Bausteine für 20260831: 351 Issues + 27 PRs ohne Issue-Verknüpfung), `gruppierung.md` und einem Entwurfs-`report.md`. `docs/fortschritt/gesamtstand.md` soll perspektivisch die Rolle von `STATUS.md` übernehmen (Entscheidung bei Finalisierung offen). Veröffentlichung des Reports selbst war ausdrücklich nicht Teil dieses Issues.

**Verifikation:** `docs/fortschritt/20260831/bausteine/` existiert im Worktree (dieser Chunk trägt weitere Bausteine zu diesem Ordner bei); `docs/fortschritt/gesamtstand.md`/`report.md` wurden nicht einzeln nachgelesen.

**Themen:** doku, agenten-organisation, projektsetup, fortschrittsbericht

---

<a id="issue-747"></a>

## Issue #747 — feat(api): Content-Endpunkt streamt Remote-Originale serverseitig durch (Proxy)
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #748 (2026-08-22)

**Laut Issue:** Folgebefund aus Epic #740: Für `HTTP_DIRECTORY`-/`RSS_FEED`-Dokumente verlinkten die Deeplinks die beim Indizieren gespeicherte Quell-URL — bei nur intern erreichbaren Quellsystemen (z. B. der Demo-Korpus-Container) läuft der Link im Browser ins Leere, was in Behörden mit internen Fileservern der Regelfall ist. Maintainer-Entscheidung: Der Content-Endpunkt soll Remote-Originale serverseitig durchstreamen (Proxy) statt auf die Quelle zu verweisen, mit SSRF-Begrenzung, Credential-Weitergabe zur Quelle (nie zum Client) und 404 bei Nichterreichbarkeit.

**Geliefert:** Wie gefordert. `GET /api/v1/documents/{documentId}/content` streamt für `HTTP_DIRECTORY`/`RSS_FEED` jetzt serverseitig von der gespeicherten Quell-URL, unter Wiederverwendung bestehender Indexer-Infrastruktur (`UrlFileDownloader#downloadBounded` mit Ziel-Allowlist-Doppelprüfung und hostgebundenen Redirects, `AutoindexCrawlerService`-Auth-Helfer, `UploadProperties#maxFileSize` als Größenbegrenzung). `DocumentContent` trägt ein `temporary`-Flag; das heruntergeladene Temp-File wird nach dem Streamen gelöscht. Frontend: alle drei Stellen (`SourceFootnotes`, `SourceEvidenceDrawer`, `LibraryDetailPage`) öffnen jetzt jeden Quellentyp über den Content-Endpunkt; die Quell-URL bleibt als sekundäre Info (Tooltip/eigene Zeile) sichtbar. Bewusste Annahme: kein zusätzlicher konfigurierbarer User-Agent für den Proxy-Abruf, Größenbegrenzung über die bestehende Upload-Grenze statt neuer Konfiguration.

**Verifikation:** Nicht erneut im Code geprüft — PR-Beschreibung dokumentiert umfangreiche Test-Abdeckung (Unit- und Integrationstests für Proxy-Erfolg, Credentials, Offline-Quelle, Allowlist-Ablehnung).

**Themen:** retrieval, security, backend, frontend, deeplinks

---

<a id="issue-749"></a>

## Issue #749 — fix(chat): Chat-Seite erzeugt äußere Scrollbar — Hauptbereich höher als der Viewport
- Geschlossen: 2026-08-22 (completed)
- Labels: bug, frontend, size:S
- PRs: #750 (2026-08-22)

**Laut Issue:** Maintainer-Beobachtung auf der Demo-Instanz: Beim Öffnen eines bestehenden Chats erschien eine äußere Seiten-Scrollbar zusätzlich zur inneren Scrollbar des Nachrichtenbereichs — Leerraum unterhalb der Fußzeile, Sidebar endete oberhalb des unteren Fensterrands. Gefordert war eine Layout-Korrektur mit identifizierter Ursache und Regressionstest.

**Geliefert:** Wie gefordert, mit präziser Ursachenanalyse. Der unsichtbare `aria-live`-Ankündigungsbereich (`visuallyHidden` → `position: absolute`) am Ende der Nachrichtenliste hatte ohne positionierten Vorfahren den Viewport als Containing Block statt den Scroll-Container — seine "statische Position" wuchs mit der Nachrichtenzahl und entzog sich dem `overflowY: auto`-Clipping. Fix: `position: relative` auf dem `message-list`-Container. Da jsdom keine Layout-Engine hat, wurde der Bug zunächst in echtem Chromium per Playwright-Skript reproduziert (scrollHeight wuchs parallel zur Nachrichtenzahl), dann ein jsdom-Regressionstest für die CSS-Eigenschaft ergänzt sowie eine E2E-Assertion in `space-chats.spec.ts`, die den tatsächlichen Seiten-Overflow in einem echten Browser prüft. Reproduktionsnachweis: `expected 'static' to be 'relative'` vor dem Fix, 7/7 Tests grün danach.

**Verifikation:** Nicht erneut im Code geprüft — Änderung ist eine einzelne CSS-Eigenschaft in `frontend/src/components/chat/MessageList.tsx`, ausführlich im PR belegt.

**Themen:** frontend, chat, layout, bugfix

---

<a id="issue-751"></a>

## Issue #751 — Renovate für automatisierte Abhängigkeits-Updates konfigurieren (lokale Ausführung, kein Cloud-Service)
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, size:M, ci
- PRs: #911 (2026-08-25)

**Laut Issue:** Abhängigkeits-Updates (Gradle-Versionskatalog, npm/pnpm, GitHub Actions, Docker) wurden manuell und unregelmäßig eingepflegt. Gefordert war Renovate — ausdrücklich **selbst betrieben, ohne den Mend-Cloud-Service** (keine externe App im Repository) — mit Managern für alle relevanten Abhängigkeitsquellen, sinnvoller Gruppierung, deutschen PR-Titeln nach Conventional Commits, dokumentierter Betriebsanleitung und einem Probelauf.

**Geliefert:** Wie gefordert. `renovate.json5` auf Basis von `config:recommended`, plus projektspezifische Regeln: deutsche Commit-/PR-Texte, Labels je Manager, max. 5 gleichzeitig offene PRs, Spring-Plattform als ein gebündelter PR, kein Digest-Pinning für Docker-Images (dokumentierte Projektentscheidung aus PR #453). `docs/renovate.md` beschreibt Dry-Run und echten Lauf. Probelauf-Nachweis im PR: Config validiert erfolgreich, 8 Manager/26 Manifeste/191 Abhängigkeiten erkannt, 36 geplante Update-Branches, Versionsänderungen landen konstruktionsbedingt im Versionskatalog statt in `build.gradle.kts`. Der erste PR-erzeugende Lauf war laut PR erst nach dem Merge möglich (Renovate liest die Konfiguration aus dem Default-Branch).

**Verifikation:** `.github/workflows/renovate.yml`, `renovate.json5`, `docs/renovate.md` existieren im Worktree — konsistent mit der beschriebenen Lieferung.

**Themen:** ci, projektsetup, abhängigkeitsverwaltung, renovate

---

<a id="issue-755"></a>

## Issue #755 — Epic: feat(models): Verwaltete Chat-Modelle in der Administrationsoberfläche (Stufe 1)
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, epic, backend, frontend, size:L
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Das Chat-Modell wird heute über eine Umgebungsvariable beim Aufsetzen entschieden — jede Änderung verlangt Neustart und Zugriff auf die Betriebsebene. Stufe 1 macht das Chat-Modell zu einem verwalteten Objekt: eine Liste hinterlegter Modelle, von denen genau eines aktiv ist, mit verschlüsselten Zugangsdaten, Verbindungstest und Audit. Angebunden wird ausschließlich über die OpenAI-kompatible Schnittstelle (Ollama läuft ebenfalls darüber, unter `/v1` — kein zweiter Anbindungsweg). Fünf Phasen: Persistenz/Zugangsdatenschutz (#756), Admin-API (#757), Laufzeitumbau (#758), Administrationsoberfläche (#759), E2E-Absicherung (#760).

**Geliefert:** Die Arbeit steckt in den Sub-Issues. #756 (PR #763) und #757 (PR #764) sind im vorliegenden Chunk enthalten und einzeln geprüft (siehe issue-756.md, issue-757.md) — beide vollständig geliefert. #758, #759, #760 liegen außerhalb dieses Delta-Chunks und wurden hier nicht einzeln nachgeprüft; der Abhängigkeitsgraph im Issue (#756→#757→#758, #757→#759→#760) legt nahe, dass sie auf den beiden geprüften Phasen aufbauen.

**Verifikation:** Über #756/#757 bestätigt (Migration 058 `llm_models`, `LlmModelController`/`LlmModelService` existieren im Worktree, siehe deren Bausteine).

**Themen:** modellverwaltung, backend, frontend, security, epic

---

<a id="issue-756"></a>

## Issue #756 — feat(models): Datenmodell für verwaltete Chat-Modelle, verschlüsselte Zugangsdaten und Seed-Migration
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #763 (2026-08-22)

**Laut Issue:** Phase 1 des Epics #755. Gefordert: Tabelle `llm_models` (Liquibase), kein Provider-Typ-Feld (ausschließlich OpenAI-kompatible Schnittstelle, Ollama über eigenen `/v1`-Endpunkt), optionaler AES-GCM-verschlüsselter API-Schlüssel gegen einen Master-Key aus der Umgebung, datenbankseitige Sicherung "höchstens ein aktiver Eintrag", lautes Scheitern beim Start ohne gültigen Master-Key, Übernahme der bestehenden Umgebungskonfiguration als initiales aktives Modell beim ersten Start (idempotent), Audit-Ereignisse für Anlegen/Ändern/Löschen/Aktivieren.

**Geliefert:** Vollständig wie gefordert. Migration 058 (`llm_models`, partieller eindeutiger Index `ux_llm_models_single_active`). `SettingsEncryptor` (AES-256-GCM, zufälliger IV je Wert) mit eigenem Master-Key `OPAA_SETTINGS_ENCRYPTION_KEY` — bewusst getrennt vom bestehenden `OPAA_CREDENTIALS_ENCRYPTION_KEY`. `SettingsEncryptionKeyGuard` lässt den Start sofort mit deutscher Meldung abbrechen (anders als die bestehende Zugangsdaten-Verschlüsselung, die erst beim ersten Schreibvorgang scheitert) — Begründung: Die Seed-Migration kann selbst schon einen Schlüssel brauchen. `LlmModelSeedRunner` übernimmt `spring.ai.model.chat`-Konfiguration einmalig, mit `/v1`-Suffix-Logik ohne Verdopplung. Bewusste Annahme, außerhalb des Issue-Umfangs vorgezogen: `LlmModelService` implementiert bereits Anlegen/Ändern/Löschen/Aktivieren mit Audit, obwohl die REST-Schicht erst im Folge-Issue (#757) entsteht; die Geschäftsregel "aktives Modell kann nicht gelöscht werden" ist bewusst nicht in dieser Schicht verankert, sondern der Admin-API vorbehalten.

**Verifikation:** Die ursprüngliche Migrationsdatei `058-create-llm-models.yaml` existiert nicht mehr einzeln im Worktree — sie wurde mit PR #906 (Liquibase-Baseline-Konsolidierung, dokumentiert in AGENTS.md) in `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` zusammengefasst (`grep -l llm_models` findet sie dort). `backend/src/main/java/io/opaa/llm/LlmModelConnectionTester.java` und weitere `io.opaa.llm`-Klassen existieren im Worktree.

**Themen:** modellverwaltung, backend, security, migration

---

<a id="issue-757"></a>

## Issue #757 — feat(models): Admin-API für Chat-Modelle (CRUD, Aktivierung, Verbindungstest)
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #764 (2026-08-22)

**Laut Issue:** Phase 2 des Epics #755, aufbauend auf dem Datenmodell aus #756. Gefordert: spec-first-Erweiterung der OpenAPI um Ressource `models` (Liste, Anlegen, Ändern, Löschen, `activate`, `test`), Zugriff ausschließlich für `SYSTEM_ADMIN`, API-Schlüssel als schreibend (nie zurückgelesen, nur "gesetzt"/"nicht gesetzt"), Aktivierung transaktional exakt ein aktives Modell, Verbindungstest mit unterscheidbaren deutschen Fehlermeldungen und Zeitlimit, Verweigerung des Löschens des aktiven Modells mit 409, Audit-Ereignisse für alle vier Änderungsarten.

**Geliefert:** Wie gefordert, plus drei Nachbesserungen aus dem Review von #756/#763: (1) Löschschutz für das aktive Modell mit 409 vor dem eigentlichen Löschaufruf; (2) `DataIntegrityViolationException` bei gleichzeitiger Aktivierung wird auf eine handlungsleitende deutsche Meldung statt der generischen 409-Meldung des `GlobalExceptionHandler` abgebildet; (3) neues Audit-Ereignis `LLM_MODEL_DEACTIVATED` für das bisher aktive Modell bei jeder Aktivierung (Migration 061), damit "wann hörte Modell X auf, aktiv zu sein" nicht nur indirekt aus fremden `LLM_MODEL_ACTIVATED`-Ereignissen ablesbar ist. `LlmModelConnectionTester` verzichtet bewusst auf den `TargetAddressValidator`-SSRF-Schutz, da ein lokal betriebener Ollama-Server im eigenen Netz der vorgesehene Regelfall ist, keine Bedrohung.

**Verifikation:** `backend/src/main/java/io/opaa/api/LlmModelController.java` und `backend/src/main/java/io/opaa/llm/LlmModelConnectionTester.java` existieren im Worktree. Die zugehörige Migration 061 wurde wie 058 in die Liquibase-Baseline (#906) konsolidiert und liegt nicht mehr als Einzeldatei vor.

**Themen:** modellverwaltung, backend, security, api, audit

---

<a id="issue-758"></a>

## Issue #758 — feat(models): Laufzeitauflösung des aktiven Chat-Modells statt fest gebundener Autoconfiguration
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M
- PRs: #767 (2026-08-22)

**Laut Issue:** Antwortgenerierung, Titelgenerierung und Health-Anzeige sollten das aktive Chat-Modell zur Laufzeit aus der Datenbank auflösen statt einen beim Start gebauten `ChatClient` zu verwenden — sonst wirkt eine Modelländerung erst nach Neustart. Gefordert: Zwischenspeicherung des gebauten Clients mit Invalidierung bei Aktivierung/Änderung/Löschung, verständliche deutsche Fehlermeldung ohne aktives Modell, kein stillschweigendes Ausweichen bei einem nicht erreichbaren aktiven Modell.

**Geliefert:** `io.opaa.llm.ActiveChatModelResolver` baut `ChatClient`/`OpenAiChatModel` programmatisch aus dem aktiven `LlmModel` und cached das Ergebnis; `LlmModelService` veröffentlicht ein `ActiveChatModelChangedEvent` nach Commit (`@TransactionalEventListener(AFTER_COMMIT)`), auf das der Resolver hört. `NoActiveChatModelException` (503, deutsche Meldung) ersetzt die NPE-Kaskade. `ChatHealthIndicator` liest jetzt Basis-Adresse/Modell-Kennung aus dem Resolver. Die alte `OpenAiChatAutoConfiguration` ist in `application.yml` ausgeschlossen; die Embedding-Seite blieb unangetastet. Löschen des aktiven Modells löst laut PR bewusst keine eigene Invalidierung aus, weil `deleteModel` das ohnehin mit 409 blockiert (Annahme, keine Abweichung vom Issue). Deckt sich mit den Abnahmekriterien des Issues.

**Verifikation:** `ActiveChatModelResolver.java`, `NoActiveChatModelException.java` und `ActiveChatModelChangedEvent.java` existieren im Worktree unter `backend/src/main/java/io/opaa/llm/`. `ChatHealthIndicator.java` vorhanden.

**Themen:** modellverwaltung, backend, laufzeitauflösung

---

<a id="issue-759"></a>

## Issue #759 — feat(models): Administrationsseite Modellverwaltung mit schreibgeschützter Einbettungsübersicht
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #765 (2026-08-22)

**Laut Issue:** Administrationsseite `admin/models` für `SYSTEM_ADMIN` — Liste, Anlegen, Bearbeiten, Löschen, Aktivieren, Verbindungstest je Chat-Modell, dazu ein schreibgeschützter Block zur Einbettungskonfiguration mit Begründung der Unveränderlichkeit. API-Schlüssel nie im Klartext zurückgeben, Löschschutz für das aktive Modell verständlich anzeigen.

**Geliefert:** Route `admin/models` (Sidebar nur für `SYSTEM_ADMIN`), Modellliste mit „Aktiv“-Chip, Anlegen-Dialog und Inline-Bearbeitung, Verbindungstest, „Aktiv setzen“, Löschen mit clientseitig deaktiviertem Button beim aktiven Modell plus serverseitiger 409-Anzeige. Schlüsselfeld zeigt nur gesetzt/nicht gesetzt. Zusätzlich zum Issue-Umfang wurde ein neuer Backend-Endpunkt `GET /api/v1/admin/models/embedding-info` (`EmbeddingInfoService`) ergänzt, weil die bestehende Admin-API dafür noch keinen Endpunkt hatte — im Issue nicht explizit gefordert, aber zur Erfüllung des schreibgeschützten Einbettungsblocks notwendig.

**Verifikation:** `LlmModelManagementPage.tsx`, `LlmModelManagementPage.test.tsx`, `llmModelStore.ts` und `CreateLlmModelDialog.tsx` existieren im Worktree unter `frontend/src/`.

**Themen:** modellverwaltung, frontend, admin-ui

---

<a id="issue-760"></a>

## Issue #760 — test(e2e): Modellverwaltung — Anlegen, Aktivieren, Verbindungstest und Löschschutz
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #770 (2026-08-23)

**Laut Issue:** End-to-End-Szenarien für die Modellverwaltung: Anlegen/Testen/Aktivieren, Chat-Antwort nach Aktivierung ohne Neustart, Berechtigungsausschluss für Nicht-Admins, Löschschutz für das aktive Modell, Fehlermeldung bei nicht erreichbarer Test-Adresse, „Schlüssel kommt nie zurück“ nach erneutem Öffnen. Mehrfacher Lauf als Flaky-Nachweis, kein hinterlassener Zustand für Folgetests.

**Geliefert:** `e2e/tests/llm-model-management.spec.ts` mit allen sechs Szenarien in `test.describe.serial`, `afterAll` stellt das ursprüngliche aktive Modell wieder her. Abweichung von der Ausgangsannahme des Issues: Es gibt keinen echten Ollama-Dienst im Compose-Stack der Suite (weder `docker-compose.yml` noch `e2e/docker-compose.e2e.yml`) — der positive Verbindungstest läuft stattdessen gegen `ai-stub`, denselben OpenAI-kompatiblen Ersatz, den auch das Seed-Modell nutzt. Zusätzlich ein `data-testid` auf `LlmModelCard` ergänzt, um eine reale Mehrdeutigkeit im DOM (mehrfach gemountete `AccordionDetails`) zu beheben. Laut PR zweimal hintereinander grüner Volllauf (34/34 Tests) als Flaky-Nachweis.

**Verifikation:** `e2e/tests/llm-model-management.spec.ts` existiert im Worktree; `e2e/README.md` referenziert `llm-model-management` (Szenarien-Abschnitt).

**Themen:** modellverwaltung, e2e, qa

---

<a id="issue-762"></a>

## Issue #762 — refactor(ai): Nativen Ollama-Starter entfernen — Embedding über OpenAI-kompatible Schicht
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:S
- PRs: #766 (2026-08-22)

**Laut Issue:** `spring-ai-starter-model-ollama` entfernen, weil Ollama auch `/v1/embeddings` OpenAI-kompatibel bedient. Embedding-Konfiguration auf die OpenAI-kompatible Schicht vereinheitlichen, Provider-Umschalter `OPAA_AI_EMBEDDING_PROVIDER`/`OPAA_OLLAMA_*` entfallen lassen, Doku und Compose-Umgebung anpassen inkl. Migrationshinweis für Bestandsdeployments (insbesondere die Demo-Instanz).

**Geliefert:** Starter aus `libs.versions.toml`/`build.gradle.kts` entfernt (musste `spring-ai-retry` explizit nachziehen, da bisher transitiv über den Ollama-Starter kam). `spring.ai.model.chat`/`embedding` fest auf `openai`, Base-URL-Defaults zeigen weiterhin auf lokalen Ollama-Server. `LlmModelSeeder` behält für Bestandsinstallationen einen Legacy-Lesepfad für `OPAA_OLLAMA_BASE_URL`/`OPAA_OLLAMA_CHAT_MODEL`. `docs/deployment.md` um vollständige Variablen-Migrationstabelle ergänzt. `.env.example`-Dateien korrigiert (Chat-/Embedding-Defaults spiegelten vorher fälschlich `gpt-4o`/`text-embedding-3-small` statt der tatsächlichen Ollama-Modelle). Deckt sich mit dem Issue-Umfang.

**Verifikation:** `backend/gradle/libs.versions.toml` enthält keinen `spring-ai-starter-model-ollama`-Eintrag mehr, nur noch `testcontainers-ollama` (Test-Infrastruktur) und einen Kommentar, der die Historie erklärt. `EmbeddingInfoService.java` und `LlmModelSeeder.java` existieren im Worktree.

**Themen:** modellverwaltung, embedding, deployment, ollama

---

<a id="issue-768"></a>

## Issue #768 — fix(api): OpenAI-SDK-Fehler (com.openai.errors.*) im GlobalExceptionHandler nutzerfreundlich mappen
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S
- PRs: #806 (2026-08-23)

**Laut Issue:** Seit der Umstellung auf die OpenAI-kompatible Anbindung (#766) werfen Chat-Aufrufe bei Fehlern `com.openai.errors.*`-Typen, auf die `GlobalExceptionHandler` noch nicht mappt — sie landen im generischen 500-Handler statt in einer deutschen Fehlermeldung. Gefordert: Mapping ergänzen, transient/permanent unterscheiden, Tests für beide Fälle.

**Geliefert:** Drei neue `@ExceptionHandler`: `OpenAIIoException`/`OpenAIRetryableException` → 503 (transient), `OpenAIServiceException` → 429/5xx als 503 (inkl. `Retry-After`-Weiterleitung bei Rate-Limit), sonst 502; `OpenAIException` als Auffangzweig → 502. Abweichung/Präzisierung gegenüber dem Issue: Abnahmekriterium „401/403/404 werden unterscheidbar gemappt“ wurde bewusst nur im Log umgesetzt, nicht in der Client-Antwort — dort bleibt es einheitlich 502, mit der Begründung, der Client könne zwischen den Fehlerursachen ohnehin nichts unternehmen. Titelgenerierung und Verbindungstest waren nicht betroffen (eigenes Catch-all bzw. eigener HTTP-Client) und blieben unverändert.

**Verifikation:** `GlobalExceptionHandler.java` enthält Handler für `OpenAIIoException`/`OpenAIServiceException` (bestätigt per Grep). `GlobalExceptionHandlerTest.java` und `QueryControllerLlmErrorMappingIntegrationTest.java` existieren im Worktree.

**Themen:** modellverwaltung, backend, fehlerbehandlung

---

<a id="issue-769"></a>

## Issue #769 — Retrieval-Regression erkannt (automatischer Lauf)
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, evaluation
- PRs: keine

**Laut Issue:** Automatisch von `github-actions` erzeugter Alert — der nächtliche Retrieval-Regressionslauf ist ohne Report abgebrochen (vermutlich Manifest- oder Ein-Chunk-Invarianten-Verletzung, oder Zeitlimit). Kein inhaltlicher Befund, nur ein Fehlschlag-Signal.

**Geliefert:** Kein PR — laut Kommentar im Issue („Der nächtliche Retrieval-Regressionslauf ist wieder grün“, Link auf Workflow-Lauf 32685658102) hat sich der nächste automatische Lauf ohne Codeänderung selbst erledigt. Vermutlich transienter Abbruch (Zeitlimit oder Ressourcenkonkurrenz), keine tatsächliche Qualitätsregression im Retrieval.

**Verifikation:** Kein Code-Bezug, keine Prüfung im Worktree nötig.

**Themen:** evaluation, retrieval, ci, automatischer-alert

---

<a id="issue-771"></a>

## Issue #771 — fix(models): Fehlender OPAA_SETTINGS_ENCRYPTION_KEY bricht den Anwendungsstart ab statt nur die Seed-Übernahme
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S, security
- PRs: #772 (2026-08-23)

**Laut Issue:** Nach nächtlichem Deploy war die Demo-Instanz down und der Demo-Smoke-CI-Workflow rot, weil ein fehlender `OPAA_SETTINGS_ENCRYPTION_KEY` die `IllegalStateException` aus `SettingsEncryptor` bis in den `ApplicationRunner` durchschlagen ließ und den Start abbrach — im Widerspruch zur eigenen Dokumentation, die nur ein Scheitern der einmaligen Seed-Übernahme zusagt. Gefordert: Seed kontrolliert überspringen mit ERROR-Log, kein Seed-Marker, Anwendung startet normal; alle anderen Fehler weiterhin propagieren; `e2e/demo-smoke.env` korrigieren; Reproduktionsnachweis.

**Geliefert:** `SettingsEncryptor#isKeyConfigured()` neu, `LlmModelSeeder#seedFromOpenAi()` wirft bei fehlendem Schlüssel eine paketprivate `MissingEncryptionKeyException`, die `seedIfNeeded()` fängt, ERROR loggt und ohne Marker überspringt — Neustart mit gesetztem Schlüssel holt die Übernahme nach. `e2e/demo-smoke.env`: der wirkungslose `OPAA_OPENAI_API_KEY`-Platzhalter entfernt statt eines zusätzlichen Test-Encryption-Keys, um kein geheimnisähnliches Artefakt einzuführen — leichte Abweichung vom im Issue vorgeschlagenen „deterministischen Test-Key setzen“, aber im PR begründet. `docs/deployment.md` entsprechend präzisiert.

**Verifikation:** `LlmModelSeeder.java` enthält `isKeyConfigured`/`MissingEncryptionKeyException` (bestätigt per Grep). `LlmModelSeedRunner.java`, `LlmModelSeederTest.java`, `e2e/demo-smoke.env` existieren im Worktree.

**Themen:** modellverwaltung, security, deployment, demo-instanz

---

<a id="issue-773"></a>

## Issue #773 — fix(ai): Suchqualitäts-Regression durch Embedding über Ollamas /v1-Endpunkt (statt nativer API)
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:M, evaluation
- PRs: #779 (2026-08-23)

**Laut Issue:** Seit der Umstellung auf den OpenAI-kompatiblen Embedding-Pfad (#762/PR #766) brach die Suchqualität messbar ein — CI-Timeout im Retrieval-Regression-Workflow und lokal ein `checkRetrievalBaseline`-Fehlschlag mit Deltas bis -0,234 bei `hitRateAt5`. Das Issue benannte mehrere Verdachtsursachen (Truncation, Prompt-Präfix, Batching) und zwei mögliche Wege: Revert auf den nativen Ollama-Starter oder Ursachenbehebung im `/v1`-Pfad, mit dem Eval-Harness als Schiedsrichter.

**Geliefert:** Die tatsächliche Ursache war eine Metadaten-Kontamination: `OpenAiEmbeddingModel` embeddet (anders als der vorherige `OllamaEmbeddingModel`) den über `MetadataMode.EMBED` formatierten Dokumenttext inklusive der fünf Bookkeeping-Metadatenfelder (`document_id`, `chunk_index`, `file_name`, `library_id`, `organization_id`), während Suchanfragen weiterhin reinen Text embedden — ein Index-vs-Query-Vektorraum-Mismatch. Fix: `FileProcessingService.CHUNK_EMBED_CONTENT_FORMATTER` schließt diese Felder aus und setzt ein reines Textformat je Chunk. Der zunächst als Fallback offengehaltene Revert-PR #774 wurde nicht gemerged. Nach dem Fix lief `checkRetrievalBaseline` lokal und in CI wieder exakt auf Baseline. `docs/deployment.md` erhielt einen Neuindizierungshinweis für Bestandsinstallationen, deren zwischen #766 und #773 indizierte Vektoren weiterhin kontaminiert bleiben.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java` existiert im Worktree weiterhin.

**Themen:** retrieval, indexing, modellverwaltung, evaluation, doku

---

<a id="issue-775"></a>

## Issue #775 — Demo-Seed: Space↔Bibliothek-Zuordnungen mit ausliefern
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, size:S, demo
- PRs: #776 (2026-08-23)

**Laut Issue:** Der Demo-Seed legte Spaces und Bibliotheken zwar an, verknüpfte sie aber nicht — eine frische Demo-Installation konnte das Feature „Space↔Bibliothek-Zuordnung als Kuratierung" (#706) nicht zeigen. Gefordert war, `SpaceDef` um referenzierte Bibliotheken zu erweitern, einen idempotenten Seed-Schritt über `POST /v1/spaces/{spaceId}/libraries` zu ergänzen und die konkrete Zuordnung gemäß Rechtematrix des Drehbuchs umzusetzen (u. a. „Amtsleitung Bürgerbüro" mit allen fünf Bibliotheken, „Maria Weber – persönlich" bewusst ohne Zuordnung).

**Geliefert:** Wie gefordert. `SpaceDef` führt `library_names`, ein neuer Seed-Schritt 5/6 legt die Zuordnungen über die Session des jeweiligen Space-Eigentümers an (CURATOR-Schwelle plus VIEWER auf der Bibliothek), Idempotenz nutzt das bestehende 201-bei-Konflikt-Verhalten der API. `demo/README.md` und `docs/demo-walkthrough.md` wurden nachgezogen. Das E2E-Profil blieb bewusst unverändert (leeres `library_names`-Default).

**Verifikation:** `demo/seed/profiles.py` enthält `library_names` (4 Fundstellen im Worktree).

**Themen:** demo, spaces, seed, doku

---

<a id="issue-777"></a>

## Issue #777 — Mitglieder hinzufügen für normale Nutzer kaputt: Benutzersuche nutzt SYSTEM_ADMIN-Endpunkt; dazu zwei UI-Korrekturen der Mitgliederverwaltung
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, frontend, size:M, workspace
- PRs: #778 (2026-08-23)

**Laut Issue:** Drei zusammenhängende Befunde aus einem Klick-Test auf der Demo-Instanz: (1) Die Nutzerauswahl in vier Frontend-Stellen (`SpaceManagementPage`, `SpaceCreatePage`, `LibraryCreatePage`, `LibraryGrantsDialog`) rief `GET /v1/admin/users` auf, das seit der Organisationsgrenze (#199) `SYSTEM_ADMIN` verlangt — normale Nutzer bekamen ein stillschweigend geschlucktes 403 und eine leere Autocomplete. (2) Der Hinweistext beim Standard-Space mit einem Mitglied ersetzte statt ergänzte das Hinzufügen-Formular, obwohl der Standard-Space laut Spezifikation „ein Space wie jeder andere" ist. (3) Die Eigentümer-Zeile zeigte ein editierbares Rollen-Dropdown, dessen Nutzung immer in einen Backend-Fehler lief.

**Geliefert:** Alle drei Befunde behoben. Neuer Endpunkt `GET /v1/users` (`UserSearchController`), erreichbar für jeden angemeldeten Nutzer der eigenen Organisation, liefert eine schmalere `UserSummaryResponse` ohne `systemRole`; alle vier Frontend-Stellen wurden umgestellt. `GET /v1/admin/users` blieb unverändert SYSTEM_ADMIN-only. Der Standard-Space-Hinweis ergänzt jetzt das Formular statt es zu ersetzen; die Eigentümer-Zeile zeigt ein statisches Badge. Als Nebeneffekt wurde die alte `isSystemAdmin`-Gating-Logik in `LibraryGrantsDialog` entfernt (schließt einen in #423 offen gelassenen Folgepunkt).

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserSearchController.java` existiert im Worktree weiterhin.

**Themen:** spaces, auth, workspace, frontend

---

<a id="issue-780"></a>

## Issue #780 — Browservorschau für Markdown-, Text- und DOCX-Originale statt stillem Download
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend
- PRs: #781 (2026-08-23)

**Laut Issue:** „Im Dokument öffnen" öffnete PDFs und Bilder in einem Vorschau-Tab, löste für Markdown, Klartext und DOCX aber einen stillen Download aus — für den zentralen Vertrauensmoment „Beleg bis ins Original prüfen" wirkte das wie ein Fehler. Gefordert war eine Browser-Vorschau für Markdown/Klartext (clientseitig gerendert, ohne HTML-Passthrough) sowie für DOCX entweder eine Konvertierung oder mindestens sichtbares Download-Feedback, falls DOCX beim Download bleibt.

**Geliefert:** Markdown/Klartext werden jetzt in einem neuen `DocumentTextPreviewDialog` gerendert — Markdown über die bestehende sichere `MarkdownRenderer`-Komponente (kein `rehype-raw`, `javascript:`-URLs werden entfernt), Klartext als reiner Text in `<pre>`. **Abweichung vom Issue:** DOCX bleibt bewusst beim Download (keine serverseitige Konvertierung umgesetzt), erhält aber wie im Issue als Mindestanforderung beschrieben eine sichtbare Snackbar-Rückmeldung. PDF-/Bild-Verhalten blieb unverändert. Vier gezielte Sicherheitstests belegen, dass gerendertes Markdown kein Script im App-Origin ausführen kann.

**Verifikation:** `frontend/src/components/DocumentTextPreviewDialog.tsx` existiert im Worktree weiterhin.

**Themen:** frontend, spaces, sicherheit, doku

---

<a id="issue-782"></a>

## Issue #782 — Chat-Fußzeile zählt lesbare statt effektiv durchsuchte Bestände in Spaces mit Zuordnungen
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend
- PRs: #783 (2026-08-23)

**Laut Issue:** In einem Space mit Space↔Bibliothek-Zuordnungen (#706) zeigte die Chat-Fußzeile bei @Alles-Wissen die Zahl aller lesbaren Bibliotheken der Person statt der effektiven Schnittmenge aus Zuordnung und Lesbarkeit — das Backend verengte korrekt, die Anzeige nicht. Erwartet war, dass die Fußzeile in einem Space mit Zuordnungen die Schnittmenge zählt (z. B. „1 zugeordneter Bestand") und ohne Zuordnungen wie bisher alle lesbaren.

**Geliefert:** Wie gefordert. `ChatInput` lädt jetzt selbst die Bibliothekszuordnungen des Spaces über `useSpaceStore#loadLibraryAssociations` und zählt bei @Alles-Wissen die Teilmenge mit `readableByCaller = true`, sobald der Space kuratiert ist. Vier neue Tests decken Singular, Plural, den „nichts lesbar"-Grenzfall und den unveränderten Fall ohne Zuordnungen ab.

**Verifikation:** Die heutige Formulierung in `frontend/src/components/chat/ChatInput.tsx` lautet „zugeordneter lesbarer Bestand"/„zugeordnete lesbare Bestände" (Zeile ~190) — eine später präzisierte Textvariante desselben gelieferten Verhaltens, keine inhaltliche Abweichung.

**Themen:** spaces, retrieval, frontend

---

<a id="issue-784"></a>

## Issue #784 — Englische MUI-Standardtexte („No options", „Loading…", aria-Labels) statt deutscher Lokalisierung
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend, size:S
- PRs: #785 (2026-08-23)

**Laut Issue:** Die leere Vorschlagsliste der Bibliothekszuordnung im Space zeigte den englischen MUI-Standardtext „No options", da fünf `Autocomplete`-Verwendungen kein `noOptionsText` setzten; darüber hinaus liefert MUI ungefiltert weitere englische Defaults (`loadingText`, `clearText`/`openText`/`closeText`). Gefordert war ein globaler Ansatz über die MUI-Lokalisierung (`deDE`) statt punktueller Fixes, plus kontextspezifische Texte an den betroffenen Stellen wo sinnvoll.

**Geliefert:** Wie gefordert. `deDE` aus `@mui/material/locale` wird jetzt global in `createTheme` (`frontend/src/theme/theme.ts`) eingebunden, wodurch alle MUI-Standardtexte projektweit deutsch erscheinen. Die fünf betroffenen `Autocomplete`-Stellen erhielten zusätzlich kontextspezifische deutsche Texte. Nebeneffekt dokumentiert: die globale Lokalisierung änderte auch aria-Labels bereits bestehender Komponenten (Alert-Schließen-Button, Pagination), zwei bestehende Tests wurden entsprechend angepasst.

**Verifikation:** `frontend/src/theme/theme.ts` enthält weiterhin `deDE` (2 Fundstellen im Worktree).

**Themen:** frontend, doku, barrierefreiheit

---

<a id="issue-786"></a>

## Issue #786 — feat(frontend): Globale Leiste (Rail) als immer sichtbare erste Navigationsebene
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #791 (2026-08-23)

**Laut Issue:** Nach Mockup-Abschnitt 2a sollte Globales und Space-Bezogenes räumlich getrennt werden: eine schmale, immer sichtbare globale Leiste (Rail) links mit Logo, Spaces/Katalog/Admin-Einträgen und Avatar-Menü unten, während die bisherige Navy-Seitenleiste zur reinen Space-Spalte (Space-Wechsler, Chat-Liste, space-bezogene Fußlinks) wird. Verlangt waren zudem responsives Verhalten, beide Farbschemata, Barrierefreiheit nach Landmarken-Konzept und aktualisierte Mockups im Repo.

**Geliefert:** Wie gefordert umgesetzt: neue `GlobalRail`-Komponente (64 px, eigene Landmark `nav aria-label="Globale Navigation"`, Logo, Spaces/Katalog/Admin mit `aria-current`, Avatar-Menü), Navy-Spalte auf 248 px verschlankt und auf Space-Wechsler/Chat-Liste/Fußlinks reduziert (Landmark-Struktur `aside "Space-Bereich"` mit `nav "Chats"`). Neue `railRoles`-Tokens/`createRailTheme` für beide Farbschemata; mobiles Verhalten über den Drawer gelöst. Mockups wurden bereits im vorgelagerten PR #790 eingecheckt. Vollständiger lokaler E2E-Lauf (34/34) inkl. axe-A11y-Suite gegen die neue Landmark-Struktur bestanden. Folge-Issues #787–#789 bauen explizit auf dieser Rail auf.

**Verifikation:** `frontend/src/layouts/GlobalRail.tsx` existiert im Worktree weiterhin.

**Themen:** frontend, barrierefreiheit, doku

---

<a id="issue-787"></a>

## Issue #787 — feat(frontend): Globaler Verwaltungsrahmen — helle Fläche mit „Global“-Badge für die Administration
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #794 (2026-08-23)

**Laut Issue:** Für die Administrationsseiten (`/admin/branding`, `/admin/groups`, `/admin/models`) sollte gemäß Mockup 2b ein eigener „globaler Verwaltungsrahmen“ entstehen: helle Fläche statt der Navy-Space-Spalte, eine Sekundärspalte „Administration“ mit „GLOBAL“-Badge und Geltungsbereichs-Hinweis. Der Rahmen sollte als wiederverwendbare Komponente geschnitten sein, damit Folge-Issues (Benutzer-Einstellungen, Bibliothekskatalog) ihn nachnutzen können. Bereiche ohne Backend-Funktion (OIDC, E-Mail-Server, Scheduler, Audit) sollten ausdrücklich nicht als Platzhalter erscheinen.

**Geliefert:** `GlobalAreaLayout` als Layout-Route mit `nav`-Landmark „Administration“, `GlobalBadge`- und `GlobalScopeNote`-Komponenten, Einbettung der drei bestehenden Admin-Seiten, `AppShell` blendet die Space-Spalte auf `/admin/*` aus. Die bisherige `AdminSectionNav`-Erreichbarkeitsbrücke (aus dem #791-Review) wurde im selben PR entfernt, weil die neue Sekundärspalte sie ablöst. Keine Abweichung vom Issue-Umfang erkennbar; die im Mockup illustrativen Bereiche ohne Backend blieben wie gefordert außen vor.

**Verifikation:** `GlobalAreaLayout.tsx`, `GlobalBadge.tsx`, `GlobalScopeNote.tsx` existieren im heutigen Worktree unter `frontend/src/components/` bzw. `frontend/src/layouts/`. `AdminSectionNav.tsx` existiert nicht mehr — laut Git-Historie (`git log --oneline -- frontend/src/components/admin/AdminSectionNav.tsx`) im selben Commit 8cc0af8e (#794) sowohl zuletzt geändert als auch entfernt, deckungsgleich mit der PR-Beschreibung.

**Themen:** frontend, navigation, admin, design-system, barrierefreiheit

---

<a id="issue-788"></a>

## Issue #788 — feat(frontend): Benutzer-Einstellungen als globale Seite über den Avatar der Leiste
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #795 (2026-08-23)

**Laut Issue:** `/settings` sollte gemäß Mockup 2c aus dem Space-Rahmen in den globalen Rahmen (ohne Sekundärspalte) überführt werden, erreichbar über den Avatar der globalen Leiste: „GLOBAL“-Badge, Geltungsbereichs-Hinweis, ein reiner Anzeige-Profilblock (Avatar mit Initialen, Anzeigename, E-Mail, Anmeldeweg) sowie die bestehende Farbschema-Wahl. Bearbeitungsfunktionen aus dem Mockup (Anzeigename ändern, Sprache, Profilbild, Benachrichtigungs-Schalter) waren ausdrücklich außerhalb des Umfangs, weil das nötige Backend fehlt.

**Geliefert:** `/settings` rendert im sections-losen `GlobalAreaLayout` aus #787/#794, mit Badge, Geltungsbereichs-Hinweis und reinem Anzeige-Profilblock (Anmeldeweg aus dem Auth-Modus abgeleitet, nie ein technischer Modusname). Farbschema-Wahl samt „Vorgabe des Hauses übernehmen“ blieb erhalten. Die Seite war laut PR-Beschreibung bislang ungetestet — `SettingsPage.test.tsx` wurde neu angelegt. Kein erkennbarer Umfangs-Unterschied zum Issue.

**Verifikation:** `frontend/src/pages/SettingsPage.tsx` und `SettingsPage.test.tsx` existieren im heutigen Worktree.

**Themen:** frontend, navigation, einstellungen, design-system, barrierefreiheit

---

<a id="issue-789"></a>

## Issue #789 — feat(frontend): Wissensbibliotheken-Übersicht in den globalen Rahmen einbetten
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #799 (2026-08-23)

**Laut Issue:** `/libraries`, `/libraries/new` und `/libraries/:libraryId` sollten laut Schlussnotiz von Mockup-Abschnitt 2 in den globalen Rahmen (Rail sichtbar, keine Navy-Spalte) überführt werden, mit „GLOBAL“-Badge am Seitentitel der Übersicht und aktivem Rail-Eintrag „Katalog“. Bestehende Funktionen (Tabelle, Anlage, Detail, Upload) sollten unverändert bleiben.

**Geliefert:** Die drei Routen rendern im sections-losen `GlobalAreaLayout`; Badge nur am Titel der Übersicht (Unterseiten erben den Rahmen ohne eigenes Badge — bewusste, im PR begründete Abweichung von einer wörtlichen „Badge auf jeder Seite“-Lesart, aber im Sinne des Mockups). Rail-Eintrag „Katalog“ ist auf allen Bibliotheks-Routen aktiv. Die E2E-Bibliotheks-Flows navigierten laut PR bereits per `page.goto` und brauchten keine Anpassung. Damit ist laut PR-Beschreibung das gesamte Navigationskonzept aus Mockup-Abschnitt 2 (#786, #787, #788, #789) abgeschlossen.

**Verifikation:** `frontend/src/pages/LlmModelManagementPage.tsx` als Vergleichsmuster und `frontend/src/layouts/GlobalAreaLayout.tsx`/`globalArea.ts` existieren im Worktree; `LibraryManagementPage.tsx` als Kernziel dieses PRs ebenfalls (per Vorabprüfung des zugehörigen `LibraryController` bestätigt vorhandener Funktionsbereich).

**Themen:** frontend, navigation, bibliotheken, design-system, barrierefreiheit

---

<a id="issue-792"></a>

## Issue #792 — fix(frontend): Space-Navigation der Seitenleiste erzeugt axe-Verstoß — li ohne ul-Elternelement
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend
- PRs: #793 (2026-08-23)

**Laut Issue:** Seit PR #791 rendert der Fußbereich der Space-Spalte als `<List component="nav">`, wodurch MUI das `<ul>` durch ein `<nav>` ersetzt — die `<li>`-Kinder stehen ohne Listen-Elternelement im DOM, axe-core wertet das als „serious“ (WCAG 1.3.1). Der verursachende PR wurde per Auto-Merge auf Basis der Required Checks gemergt, bevor der (nicht required) E2E-Lauf fertig war. Erwartung: `<nav><ul><li>…` statt `<nav>` als Ersatz für `<ul>`.

**Geliefert:** Der `nav`-Container liegt jetzt um die Liste statt an ihrer Stelle. Zusätzlich behebt der PR einen zweiten, im selben main-Lauf gefundenen „serious“-Verstoß: unzureichender Kontrast (3,29:1) des aktiven `AdminSectionNav`-Links, behoben durch Wechsel auf `text.primary`. Damit liefert der PR mehr als im Issue beschrieben, aber im selben Fehlerbild-Kontext (derselbe rote E2E-Lauf) — keine sachfremde Erweiterung.

**Verifikation:** `frontend/src/layouts/Sidebar.tsx` und `Sidebar.test.tsx` existieren im Worktree; `AdminSectionNav.tsx` wurde in #787/#794 kurz darauf entfernt (siehe issue-787.md) — der Kontrast-Fix betraf eine Komponente, die im Projekt inzwischen nicht mehr existiert, was der PR selbst bereits ankündigte („Die Komponente entfällt ohnehin mit #787“).

**Themen:** frontend, barrierefreiheit, navigation, ci

---

<a id="issue-798"></a>

## Issue #798 — Selbstauskunft und Auskunftsexport für Audit-Daten
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:S, security
- PRs: keine

**Laut Issue:** Aus #239 herausgelöste Betroffenenrechte für Audit-Daten: ein nicht delegierbarer Selbstauskunfts-Endpunkt (jede Person sieht ausschließlich ihre eigenen, über die Pseudonymzuordnung aufgelösten Protokollsätze), ein Export dieser Daten in einem gängigen Format, eine Auskunftsdokumentation (Datenarten, Granularität, Aufbewahrungsdauer) sowie eine eigene Audit-Selbstprotokollierung jedes Selbstauskunfts-Zugriffs. Der Kern der Audit-Governance (Pseudonymisierung, Vier-Augen-Prinzip, Aufbewahrung/Löschung, Selbstprotokollierung) war laut Issue bereits über #391–#395 geliefert.

**Geliefert:** Nichts — das Issue wurde ohne PR als „not planned“ geschlossen. Laut Maintainer-Kommentar (`gh issue view 798 --comments`) ist das eine bewusste Ticket-Hygiene-Entscheidung: Selbstauskunft und Auskunftsexport werden zurückgestellt, nicht verworfen, und sollen bei Bedarf neu bewertet werden — etwa im Zuge einer Dienstvereinbarung oder DSGVO-Konkretisierung. Der Audit-Kern selbst (#391–#395) ist laut Issue-Text unabhängig davon bereits geliefert.

**Verifikation:** Kein Code zu verifizieren, da nichts gemergt wurde. Kein `AuditController`-Endpunkt für Selbstauskunft im heutigen Stand erwartet (nicht separat geprüft, da PR-los und explizit zurückgestellt).

**Themen:** security, audit, dsgvo, backend, zurückgestellt

---

<a id="issue-800"></a>

## Issue #800 — fix(frontend): Review-Nachbesserungen am globalen Rahmen — mobile Spalte, Rollenbindung, Profilblock, Testlücken
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend, size:M
- PRs: #803 (2026-08-23)

**Laut Issue:** Sammel-Nachbesserung für Review-Befunde zu #794 und #795, die erst nach dem (Maintainer-angeordneten) Merge eintrafen: mobiler Überlauf der Admin-Sekundärspalte bei 320 px, rollenunabhängige Sichtbarkeit der Admin-`sections` für Nicht-Admins, Mockup-Abweichungen bei Flächenfarben, fehlende Guidelines-Ergänzung, unzureichende Typhärtung von `GlobalAreaLayout`, fehlender mobiler/E2E-Testnachweis; dazu Profilblock-Doppelung der E-Mail, ein Absturz bei leerem `displayName`, ein nie ausgeführter Test sowie veraltete Sidebar-Testrouten.

**Geliefert:** Laut PR-Beschreibung wurden alle genannten Befunde aus #794 und #795 abgearbeitet — plus zusätzlich Befunde aus dem (zeitlich dazwischen gemergten) #799-Review, die im Issue-Text nicht genannt sind, aber ausdrücklich als thematisch zugehörig benannt werden. Mobile Spalte umgebrochen statt gescrollt, `sections` an `SYSTEM_ADMIN` gebunden, `border-strong`/`bg1` gemäß Mockup, Guidelines 2.3 ergänzt, `GlobalAreaLayout` typisiert gehärtet, gemeinsames `userInitial()` mit Trim, `clearThemeMode`-Test tatsächlich ausgeführt, Sidebar-Tests auf `/spaces` umgestellt. Reproduktionsnachweis für die zwei kritischen Bugs (E-Mail-Doppelung, Initialen-Absturz) laut PR erbracht.

**Verifikation:** `frontend/src/utils/userInitial.ts` und `userInitial.test.ts` existieren im Worktree; `frontend/src/layouts/GlobalAreaLayout.tsx`, `GlobalRail.tsx`, `Sidebar.tsx` ebenfalls vorhanden.

**Themen:** frontend, barrierefreiheit, navigation, code-review, mobile, design-system

---

<a id="issue-805"></a>

## Issue #805 — test(frontend): Nachweis-Lücken aus dem Review zu #803 schließen — 320-px-Geometrie, Rollenbindung, Doku
- Geschlossen: 2026-08-25 (completed)
- Labels: bug, frontend, size:S
- PRs: #907 (2026-08-25)

**Laut Issue:** Nach dem (wieder per Auto-Merge erfolgten) Merge von #803 lief ein weiteres Review nach: `main` sei funktional in Ordnung, aber zwei Kernzusagen aus #800 seien nicht durch Tests gedeckt — der bestehende E2E-Durchklick fängt die mobile 320-px-Unerreichbarkeit nicht (Desktop-Viewport, Playwright scrollt vor jedem Klick automatisch), und die Rollenbindung „Nicht-Admins sehen die Admin-Spalte nicht“ hat keine Zusicherung. Dazu mehrere kleinere Nits (Label-Umbruch, Test-Harness-Drift, veraltete Doku, falsche Spec-Zuordnung, Unicode-Bug in `userInitial`).

**Geliefert:** Neuer Spec `admin-area-navigation.spec.ts` mit echtem 320-px-Viewport und Geometrie-Zusicherungen (`scrollWidth`, `boundingBox()` je Ziel); Rollenbindung über `getByRole('navigation', {name: 'Administration'})` → `toHaveCount(0)` für Nicht-Admins abgesichert; `flex`-Anpassung für lange Labels; Test-Harness- und Doku-Nachzug; `userInitial` nimmt das erste Zeichen jetzt per Code-Point statt per UTF-16-Halbwert. Laut PR-Reproduktionsnachweis wird der Geometrie-Test bei zurückgebautem Mobil-Umbruch tatsächlich rot (bemerkenswert: der reine `scrollWidth`-Check allein bleibt grün, erst `boundingBox()` je Ziel deckt den Fehler auf — was den ursprünglichen Befund bestätigt). Deckt den Issue-Umfang vollständig.

**Verifikation:** `e2e/tests/admin-area-navigation.spec.ts` und `frontend/src/utils/userInitial.ts`/`userInitial.test.ts` existieren im Worktree.

**Themen:** frontend, e2e, barrierefreiheit, mobile, code-review, doku

---

<a id="issue-807"></a>

## Issue #807 — docs(marketing): Demo-Video auf der GitHub Page bereitstellen
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation
- PRs: #808 (2026-08-23)

**Laut Issue:** Das produzierte Demo-Video (`opaa-demo-stadt-rheinfurt.mp4`, ~21 MB) soll dauerhaft unter `https://criew.github.io/opaa/opaa-demo-stadt-rheinfurt.mp4` erreichbar sein. Da der Landing-Page-Workflow `page/` mit `rsync --delete` in die `gh-pages`-Wurzel synchronisiert, würde eine dort separat abgelegte Videodatei beim nächsten Workflow-Lauf wieder gelöscht — die rsync-Ausnahmen mussten ergänzt werden.

**Geliefert:** Ein rsync-Ausschluss für `*.mp4` in `.github/workflows/landing-page.yml`, sodass das Video (direkt auf `gh-pages` gepusht, nicht im Quell-Repository) beim Sync erhalten bleibt. Kleine, reine Workflow-Änderung ohne Effekt auf `report/`/`.nojekyll`. Deckt sich mit dem Issue-Umfang.

**Verifikation:** `.github/workflows/landing-page.yml` existiert im Worktree und enthält weiterhin einen `mp4`-bezogenen rsync-Ausschluss (per Grep bestätigt).

**Themen:** ci, doku, marketing, deployment

---

<a id="issue-809"></a>

## Issue #809 — feat(frontend): Spaces-Übersicht ohne Space-Spalte — Navy-Spalte erst im gewählten Space
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #811 (2026-08-23)

**Laut Issue:** Maintainer-Feedback zum Navigationskonzept: Auf der Spaces-Übersicht (`/spaces`) und im Anlage-Assistenten (`/spaces/new`) ist noch kein Space gewählt — dort soll nur die Kartenansicht ohne Navy-Space-Spalte (Dropdown + Chat-Liste) erscheinen. Die Spalte soll erst im gewählten Space (`/spaces/:spaceId*`) sichtbar werden. Verlangt wurde außerdem eine Erweiterung von `isGlobalAreaPath` um Exakt-Pfade, angepasste Sidebar-Fallback-Tests und kein „Global"-Badge auf der Übersicht.

**Geliefert:** `/spaces` und `/spaces/new` rendern im nackten globalen Rahmen (`GlobalAreaLayout` ohne Spalte); `isGlobalAreaPath` um Exakt-Pfade (`/spaces`, `/spaces/new`) erweitert, mit Testfällen für Übersicht, Assistent, Space und Chat. Sidebar-Fallback-Tests (9×) auf `/chat` umgestellt, da dies die einzige verbleibende Route ohne `:spaceId` ist, auf der die Spalte noch rendert. Kein „Global"-Badge ergänzt. Deckt sich mit dem Issue-Umfang; laut PR-Beschreibung E2E 36/36 grün.

**Verifikation:** `frontend/src/layouts/globalArea.ts` existiert im Worktree und enthält `GLOBAL_AREA_EXACT_PATHS = ['/spaces', '/spaces/new']` mit Kommentarverweis auf #809 — Umsetzung nachvollziehbar vorhanden.

**Themen:** frontend, spaces, navigation

---

<a id="issue-812"></a>

## Issue #812 — fix(frontend): index.html ohne Cache-Control — Browser zeigen nach Deployments den alten Stand
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, frontend, size:S
- PRs: #813 (2026-08-23)

**Laut Issue:** `frontend/nginx.conf` lieferte `index.html` ohne `Cache-Control`-Header aus (nur `ETag`/`Last-Modified`), sodass Browser sie heuristisch cachen und nach einem Deployment die alten, gehashten Bundles referenzieren — real zweimal beim Maintainer aufgetreten. Erwartet: `Cache-Control: no-cache` für `index.html` (direkt und über den SPA-Fallback), `Cache-Control: public, max-age=31536000, immutable` für `/assets/*`, unter Beibehaltung der Security-Header in den neuen Location-Blöcken.

**Geliefert:** Beide `location`-Blöcke wie gefordert ergänzt, inkl. Wiederholung der Security-Header (nginx `add_header`-Vererbungsregel dokumentiert). Reproduktionsnachweis über einen neuen E2E-Test `e2e/tests/http-caching.spec.ts` gegen den echten nginx-Container — laut PR-Beschreibung rot vor dem Fix (`Received: undefined` bei `cache-control`), grün danach; voller E2E-Lauf 37/37. Deckt sich mit dem Issue-Umfang.

**Verifikation:** `frontend/nginx.conf` enthält im Worktree `Cache-Control "no-cache"` sowie `Cache-Control "public, max-age=31536000, immutable"` mit Kommentarverweis auf #812 — Fix vorhanden.

**Themen:** frontend, deployment, ci

---

<a id="issue-814"></a>

## Issue #814 — fix(frontend): isGlobalAreaPath normalisiert Trailing Slashes nicht — /spaces/ zeigt die Space-Spalte
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, frontend, size:S
- PRs: #816 (2026-08-24)

**Laut Issue:** Review-Befund zu PR #811: React Router matcht Routen slash-tolerant (`/spaces/` rendert die Übersicht), `isGlobalAreaPath` verglich aber exakt — auf `/spaces/` und `/spaces/new/` erschien die Navy-Spalte zusätzlich zur Kartenübersicht bzw. zum Assistenten, genau der von #809 entfernte Zustand. Verlangt: Pfad-Normalisierung (Trailing Slashes entfernen), Reproduktionsnachweis, Korrektur der Doku-Invariante in `GlobalAreaLayout.tsx`/`globalArea.ts`, Ergänzung des AppShell-Test-Harness um `/spaces/new` und ein nachgezogener Sidebar-Kommentar.

**Geliefert:** Pfad-Normalisierung vor dem Vergleich implementiert; Testfälle für `/spaces/`, `/spaces/new/`, `/libraries/`. Doc-Invariante und Sidebar-Kommentar korrigiert. Reproduktionsnachweis laut PR: Test schlägt vor dem Fix fehl (`2 failed | 18 passed`), danach 652/652 grün. Ein bestehendes, unabhängiges Flake-Szenario (`space-chats`, ordnungs-/korpusabhängig) wurde separat als Issue #815 ausgelagert statt hier mitbehoben — nachvollziehbare Abgrenzung, keine Lieferlücke gegenüber diesem Issue.

**Verifikation:** `frontend/src/layouts/globalArea.ts` enthält im Worktree einen Kommentar zur Trailing-Slash-Toleranz von React Router; die Normalisierungslogik ist an der Definition von `GLOBAL_AREA_EXACT_PATHS` erkennbar vorhanden (siehe auch #809-Verifikation).

**Themen:** frontend, spaces, navigation, testing

---

<a id="issue-815"></a>

## Issue #815 — test(e2e): space-chats Szenario 1 flaky im Gesamtlauf — zitierte Quelle erscheint nach Reload nicht

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend
- PRs: #961 (2026-08-28)

**Laut Issue:** Im vollen lokalen E2E-Lauf scheitert `space-chats.spec.ts` Szenario 1
reproduzierbar am zweiten `expectAnyCitedSource(page)` nach `page.reload()`; in Isolation ist
dieselbe Spec stabil grün — ordnungs-/lastabhängige Flakiness, keine Regression eines einzelnen
PRs.

**Geliefert:** PR #961 aktiviert den lokalen Retry wie in der CI (`retries: 1` in
`e2e/playwright.config.ts`) und dokumentiert den Lastbefund als Ursache. Kein Produktfehler;
die Antwortlatenz unter Volllast ist als Beobachtung festgehalten.

**Verifikation:** `e2e/playwright.config.ts` führt `retries: 1`; Commit `f0d24e1c` auf `main`.

**Themen:** E2E, Flaky-Test, Testinfrastruktur

---

<a id="issue-817"></a>

## Issue #817 — Backend-Review: toter Code, veraltete Referenzen und Javadoc-Hypertrophie bereinigen
- Geschlossen: 2026-08-25 (completed)
- Labels: documentation, backend, size:M
- PRs: keine (Sammel-Issue, Abarbeitung über mehrere separate PRs)

**Laut Issue:** Sammelt die Befunde eines Architektur-Reviews vom 2026-08-23 (sechs parallele Review-Agenten) in drei Abschnitten: (1) verifizierter toter Code/tote Konfiguration (u. a. ungenutzte jjwt-Abhängigkeit, nie gelesene `retry-attempts`-Property, mehrere aufruferlose Repository-/Service-Methoden, mutmaßlich entbehrliches H2, elf `AuditEventType`-Werte ohne Schreiber), (2) veraltete Referenzen/Inkonsistenzen (deutsche Log-Meldungen entgegen AGENTS.md, veraltete Kommentare/Javadoc-Verweise, fehlende Property-Dokumentation, doppelte Proxy-Parsing-Logik, fehlender Startup-Guard für Embedding-Dimensionen), (3) systemische Javadoc-Hypertrophie (~30 % Kommentaranteil, 1300 Issue-/PR-Referenzen, 408 Zeilen Review-Nacherzählung) mit dem Vorschlag, eine knappe Javadoc-Konvention in AGENTS.md festzuschreiben und paketweise Kürzungsrunden durchzuführen.

**Geliefert:** Laut Abschlusskommentar sind alle Body-Punkte abgearbeitet, verteilt über PR #818, #847, #867, #899 sowie das ausgelagerte Issue #839 (Proxy-Parsing). Abschnitt 3 (Konvention + Kürzungsrunden) lief über #842/#858 (AGENTS.md-Konvention) und die Kürzungs-PRs #864 (indexing), #879 (audit), #897 (library/chat/query/space). Eine Korrektur unterwegs: Die elf toten `AuditEventType`-Werte wurden zunächst gelöscht (PR #899), dann per `git revert` zurückgenommen — Koordinator-Entscheidung, dass sie bewusste Vorabdeklaration der geschlossenen Audit-Zielliste sind, kein Cleanup-Kandidat. `LibraryAccessService.canRead`/`canManage` blieben entgegen der ursprünglichen Vermutung erhalten, da sie doch aktive Produktionsaufrufer haben. Ein bewusster Restposten bleibt offen: `AuditRetentionSettingsService.updateRetention` hat keinen Produktionsaufrufer; Maintainer-Entscheidung vom 2026-08-25, das vorerst liegen zu lassen.

**Verifikation:** Im Worktree kein `jjwt`-Treffer mehr in `libs.versions.toml`/`build.gradle.kts` und keine `retry-attempts`/`retryAttempts`-Property mehr in `application.yml` — beide Cleanup-Punkte bestätigt umgesetzt. `LlmModelSeeder` samt Begleitklassen existiert weiterhin (laut Issue-Kommentar bewusst, mit "Kandidat zur Entfernung ab v1.0"-Vermerk).

**Themen:** backend, doku, code-qualitaet, refactoring, audit, javadoc

---

<a id="issue-819"></a>

## Issue #819 — docs(library): ADR und Spezifikation für Ordner in Bibliotheken
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation, size:S
- PRs: #825 (2026-08-23)

**Laut Issue:** Teil von Epic #520 (Phase 1 — Konzept & Spezifikation). Verlangt ein ADR in `docs/decisions/` zur Entscheidung „Ordner in Bibliotheken als Navigation, keine Rechtegrenze" (echte Ordner-Entität statt virtueller Pfad-Präfixe, Grants bleiben auf Bibliotheksebene, Retrieval vorerst ohne Ordner-Filter), eine Aktualisierung von `docs/features/knowledge-sources.md` für UPLOAD- und FILESYSTEM-Bibliotheken sowie einen ergänzenden Absatz in `docs/CONCEPTS.md`. Reine Dokumentationsänderung ohne Code.

**Geliefert:** ADR-0020 (`docs/decisions/0020-ordner-in-bibliotheken-navigation.md`) mit der beschriebenen Entscheidung inkl. Detailpunkten (Unique-Constraint je `(library_id, parent_folder_id, name)`, Löschen mit Bestätigung durch den Service statt DB-Kaskade, Dedup-Index bleibt bibliotheksweit, Abgrenzung zu „kein Ordner in einem Raum"). `docs/features/knowledge-sources.md` und `docs/CONCEPTS.md` wie gefordert ergänzt. Deckt sich vollständig mit dem Issue-Umfang.

**Verifikation:** `docs/decisions/0020-ordner-in-bibliotheken-navigation.md` existiert im Worktree.

**Themen:** doku, ordner, spaces, architektur, adr

---

<a id="issue-820"></a>

## Issue #820 — feat(library): Schema und CRUD-API für Bibliotheksordner
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #827 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 2 — Backend-Fundament), aufbauend auf der Spezifikation aus #819. Verlangt eine Liquibase-Migration für `library_folders` (inkl. partiellem Unique-Index für den NULL-Parent-Fall) und `documents.folder_id`, eine `LibraryFolder`-Entität mit Namens-Validierung, Tiefenlimit und Zyklen-Schutz, sowie eine OpenAPI-first CRUD-API (`POST`/`PATCH`/`DELETE` unter `/api/v1/libraries/{libraryId}/folders`) mit EDITOR-Rechteschwelle, ausschließlich für UPLOAD-Bibliotheken, rekursivem Löschen über den bestehenden Dokument-Service-Pfad (keine DB-Kaskade) und Tests für Rechte-, Konflikt- und Rekursionsfälle.

**Geliefert:** Migration 062 mit `library_folders` (zwei partiellen Unique-Indexen für Wurzel- vs. verschachtelte Ebene) und `documents.folder_id`; Entität/Repository/Service mit Tiefenlimit 10 und Zyklen-Schutz; API wie gefordert plus einem zusätzlichen `GET`-Endpunkt auf einen einzelnen Ordner (im Issue nur als Option vorgeschlagen, jetzt umgesetzt, um die rekursive Dokumentanzahl für den Bestätigungsdialog bereitzustellen). Rekursives Löschen läuft über `LibraryDocumentService#deleteDocument`. Laut PR bewusst außerhalb des Umfangs belassen: `documents.folder_id` wird in diesem PR nur als Spalte/FK eingeführt, noch nicht von einem Upload-Pfad gesetzt (folgt laut Epic in #821). Unit- und Integrationstests wie gefordert vorhanden.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryFolder.java` und `backend/src/main/resources/db/changelog/changes/062-create-library-folders.yaml` existieren im Worktree.

**Themen:** backend, ordner, spaces, datenmodell, api

---

<a id="issue-821"></a>

## Issue #821 — feat(library): Dokumentliste und Upload ordner-bewusst machen
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #828 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 2). `GET`/`POST /api/v1/libraries/{libraryId}/documents` sollten ordner-bewusst werden: optionaler `folderId`-Parameter, Response mit Unterordnern und Breadcrumb, `LibraryDocumentResponse` um `folderId`/`folderPath` ergänzt, Suche bleibt bibliotheksweit mit Pfadanzeige.

**Geliefert:** Wie gefordert umgesetzt. `folderId`-Query-/Multipart-Parameter, `LibraryFolderPaths` leitet Anzeigepfade ohne Speicherung ab (ADR-0020), neue gebündelte Repository-Abfragen gegen N+1. Bewusste, dokumentierte Verhaltensänderung: ein Aufruf ohne `folderId` listet jetzt nur die Wurzel statt des gesamten Bestands — betrifft laut PR keinen heutigen Bestand, da Ordner erst mit dem vorausgesetzten Fundament-PR (#827/#820) möglich wurden.

**Verifikation:** `LibraryFolderPaths.java`, `LibraryFolderRepository.java`, `LibraryFolderService.java` existieren im Worktree unter `backend/src/main/java/io/opaa/library/`. `LibraryController.java` und `LibraryDocumentResponseMapper`/`LibraryDocumentResponses` vorhanden (Response-Mapping mittlerweile per DTO-Leak-Serie #860 zusätzlich in `io.opaa.api` verschoben).

**Themen:** library, ordner, backend, api

---

<a id="issue-822"></a>

## Issue #822 — feat(frontend): Ordner-Navigation in der Bibliotheksansicht
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #830 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 3). Ordner-Navigation in `LibraryDetailPage.tsx`: Breadcrumb, Ordnerzeilen, URL-State (`?folder=…`), Ordner anlegen/umbenennen/löschen (nur UPLOAD-Bibliotheken, ab EDITOR) mit Bestätigungsdialog samt Dokumentanzahl, Upload in den geöffneten Ordner, Suche zeigt Ordnerpfad.

**Geliefert:** Wie gefordert, plus zusätzliche Robustheit: eine ungültige/fremde `folderId` (404) fängt der `documentStore` ab und fällt sauber auf die Wurzel zurück; Namenskonflikte (409) erscheinen als Meldung im jeweiligen Dialog statt ihn zu schließen. Alle Texte deutsch inkl. `aria-label`.

**Verifikation:** `frontend/src/pages/LibraryDetailPage.tsx`, `frontend/src/stores/documentStore.ts` existieren im Worktree und enthalten Ordner-bezogene Logik (Folder-CRUD-Aktionen, `folderId`-Parameter).

**Themen:** frontend, library, ordner, ui

---

<a id="issue-823"></a>

## Issue #823 — feat(library): Ordner-Upload per Drag & Drop mit Strukturübernahme
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #831 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 4). Ganze Ordner per Drag & Drop bzw. `webkitdirectory`-Dialog hochladen, Struktur wird unterhalb des geöffneten Ordners übernommen; Backend legt Zwischenordner idempotent an; bestehende Limits gelten je Datei unverändert.

**Geliefert:** Wie gefordert. Backend: `folderPath`-Multipart-Parameter, `LibraryFolderService#resolveOrCreateFolderPath` legt Zwischenordner idempotent an (Unique-Constraint-Race abgefangen), teilt `materializeSingleFolder` mit dem parallelen FILESYSTEM-Pfad aus #824, erzwingt aber zusätzlich Berechtigung/Bibliothekstyp/Validierung/Tiefenlimit. Frontend: rekursive Auflösung über `DataTransferItem.webkitGetAsEntry()` inkl. wiederholter `readEntries()`-Aufrufe (Seitenweise-Problem), zusätzlicher Button „Ordner hochladen".

**Verifikation:** `frontend/src/utils/directoryEntries.ts` existiert im Worktree; `LibraryFolderService.java` und `LibraryDocumentService.java` vorhanden.

**Themen:** library, ordner, upload, frontend, backend

---

<a id="issue-824"></a>

## Issue #824 — feat(indexing): FILESYSTEM-Verzeichnisstruktur als read-only Ordner abbilden
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #829 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 4). FILESYSTEM-Bibliotheken sollen ihre Verzeichnisstruktur als read-only Ordner statt flacher Dateiliste abbilden; verschwundene Verzeichnisse werden aufgeräumt; Ordner-CRUD bleibt für FILESYSTEM gesperrt.

**Geliefert:** Wie gefordert. `AsyncIndexingExecutor` materialisiert die Ordnerkette relativ zu `sourcePath` idempotent über `LibraryFolderService#materializeFolderPath`; `pruneOrphanedFolders` entfernt nicht mehr gesehene, leere Ordner nach jedem Lauf — bewusst konservativ, ein Ordner mit einem verwaisten, aber noch existierenden Dokument bleibt stehen (ADR-0017 zur Löschung-durch-Abwesenheit bei Dokumenten selbst noch nicht gebaut). Read-only-Sperre für `renameFolder`/`deleteFolder` zusätzlich abgesichert. ADR-0020 Entscheidung 6 (dieselbe Datei in zwei Unterverzeichnissen bleibt zwei Dokumente) bleibt unverändert gültig.

**Verifikation:** `AsyncIndexingExecutor.java`, `FileProcessingService.java`, `IndexingConfiguration.java` im Worktree vorhanden; `FilesystemFolderMappingIntegrationTest.java` existiert unter `backend/src/test/java/io/opaa/indexing/`.

**Themen:** indexing, library, ordner, filesystem

---

<a id="issue-826"></a>

## Issue #826 — refactor: Backend-Architekturreview 2026-08 — Befunde und Behebungsphasen
- Geschlossen: 2026-08-25 (completed)
- Labels: epic, backend, size:L
- PRs: keine (Epic)

**Laut Issue:** Epic, das die Befunde eines Backend-Architekturreviews (sechs parallele Code-Reviews über 16 Pakete, ~34.000 Zeilen) bündelt: Modulzyklen (B1), dezentrale Identität/Autorisierung (B2), manuelle Audit-/Rechtehistorie-Doppelbuchführung (B3), CHECK-Constraint-Enum-Vokabulare (B4), Transaktions-Kartenhaus im Chat-Pfad (B5), globale Dokumentidentität (B6), verstreuter Quellenzugriff (B7), Web-Schicht in der Domäne (B8), Single-Instance-Annahmen ohne Klammer (B9), plus Over-Engineering- und Build/Test-Befunde. Tickets werden bewusst erst phasenweise angelegt, nicht vorab.

**Geliefert:** Kein eigener PR — die Arbeit steckt vollständig in den als Sub-Issues verknüpften Tickets. Phase 1 (Sofortmaßnahmen): #832 (CI-Cache), #833 (lastLoginAt-Drosselung), #834 (Audit-Indizes), #835 (Build-Dedup), #836 (Crawler-Limits), #837 (chunk_index, ohne eigenen PR), #838 (VectorChunkStore), #839 (Proxy-Parsing), #840 (Archivierungsprüfung). Phase 2 (Konventionen): #842 (Kommentar-Konvention), #843 (Test-Kontexte konsolidiert; #844 als Fortsetzung not planned), #845 (Single-Instance-ADR). Phase 3/4 (Querschnitte/Struktur): #860 (DTO-Leak, ohne eigenen PR, siehe dessen Baustein), #862 (CHECK-Constraints ablösen), #875 (Domain-Exceptions), #876 (Quellenzugriff-Paket), #877 (Dokumentidentität scopen), #884 (CurrentUser). Alle Epic-Abnahmekriterien sind laut Issue-Body als erledigt abgehakt.

**Verifikation:** Sub-Issues einzeln geprüft (siehe jeweilige Bausteine). Kein eigener Verifikationsaufwand für das Epic selbst nötig.

**Themen:** architektur, backend, epic, refactoring, technische-schulden

---

<a id="issue-832"></a>

## Issue #832 — ci: Gradle-Cache in der CI wird nie aktualisiert — auf setup-gradle umstellen
- Geschlossen: 2026-08-24 (completed)
- Labels: size:S, ci
- PRs: #841 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund T1). `actions/cache` mit Build-Skript-only-Key speichert bei exaktem Treffer nie neu — jeder PR baut cache-kalt. Umstellung auf `gradle/actions/setup-gradle` in allen Gradle-Workflows.

**Geliefert:** Wie gefordert. `ci.yml` (Jobs `backend`, `backend-integration`) und `retrieval-regression.yml` auf `gradle/actions/setup-gradle@v4` umgestellt; `e2e.yml`/`demo-smoke.yml` führen kein Gradle aus und blieben unangetastet. Wrapper-Aufrufe selbst unverändert.

**Verifikation:** `.github/workflows/ci.yml` und `.github/workflows/retrieval-regression.yml` im Worktree vorhanden; PR-Beschreibung verweist auf den eigenen CI-Lauf als Nachweis (kein `actionlint` lokal verfügbar).

**Themen:** ci, build, gradle

---

<a id="issue-833"></a>

## Issue #833 — fix(auth): lastLoginAt-Schreibzugriff pro Request drosseln
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #856 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund B2). `UserService.updateExistingUser` schreibt bei jedem authentifizierten Request unconditional `lastLoginAt` — ein UPDATE pro API-Call. Drosselung auf einen Schwellwert (z. B. 5 Minuten); E-Mail/DisplayName nur bei tatsächlicher Änderung speichern.

**Geliefert:** Wie gefordert. `lastLoginAt` wird nur noch aktualisiert, wenn der gespeicherte Wert mindestens 5 Minuten alt ist (`LAST_LOGIN_UPDATE_THRESHOLD`); E-Mail/DisplayName nur bei Differenz; kein `save()` mehr, wenn nichts zu schreiben ist. Für Testbarkeit wurde ein injizierter `Clock`-Bean ergänzt (`AuthConfiguration`).

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserService.java` und `AuthConfiguration.java` im Worktree vorhanden. Reproduktionsnachweis in PR-Beschreibung dokumentiert (roter Test schlug mit `NeverWantedButInvoked` fehl, danach grün).

**Themen:** auth, backend, performance, bugfix

---

<a id="issue-834"></a>

## Issue #834 — feat(audit): Indizes für byTimeRange- und byIncidentScope-Abfragepfade ergänzen
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:S
- PRs: #846 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1. Zwei der fünf Audit-Zugriffspfade (`byTimeRange`, `byIncidentScope`) haben keinen passenden Index auf der monatspartitionierten `audit_log`-Tabelle und laufen auf Partition-Scans hinaus.

**Geliefert:** Migration 063 fügt `idx_audit_log_time_range (organization_id, recorded_at)` und `idx_audit_log_incident_scope (organization_id, actor_ref, recorded_at)` hinzu. Da `audit_log` seit Migration 017 `opaa_audit_owner` gehört (ADR-0015), läuft `CREATE INDEX` über denselben temporären GRANT/SET ROLE/REVOKE-Bracket wie Migration 022.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/063-audit-log-time-range-and-incident-scope-indexes.yaml` und `Migration063AuditLogTimeRangeAndIncidentScopeIndexesTest.java` im Worktree vorhanden.

**Themen:** audit, datenbank, performance, migration

---

<a id="issue-835"></a>

## Issue #835 — build: OpenAPI-doLast-Löschliste ableiten und Eval-Tasks deduplizieren
- Geschlossen: 2026-08-24 (completed)
- Labels: backend, size:S, ci
- PRs: #857 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund T2). Zwei mechanische Duplikationen in `backend/build.gradle.kts`: die `doLast`-Löschliste im OpenAPI-Task sollte aus `typeMappings` abgeleitet statt separat gepflegt werden; die Eval-Tasks (`evaluateRetrieval`/`checkRetrievalBaseline` je Domäne) sollten über eine Registrierfunktion statt Kopie definiert werden.

**Geliefert:** Löschliste wird jetzt mechanisch aus `typeMappings` berechnet (bleibt als Sicherheitsnetz, da die aktuelle Generator-Version laut PR ohnehin keine Modelldatei mehr für vollständig gemappte Typen emittiert — beide Varianten sind in der Praxis No-Ops). Eval-Tasks über `registerEvalDomain(...)` zusammengeführt, Filter jetzt über die vollqualifizierte Testklasse statt Suffix-Wildcard (löst nebenbei eine Wildcard-Falle). Bewusste Verengung dokumentiert: `evaluateRetrieval` lief vorher implizit über den ganzen `evalTest`-Source-Set abzüglich Excludes, jetzt nur noch über die jeweilige Harness-Klasse.

**Verifikation:** `backend/build.gradle.kts` im Worktree vorhanden; PR dokumentiert Vorher/Nachher-Vergleich von `openApiGenerate`-Output (79 Dateien, identisch bis auf Zeitstempel) und `gradle tasks --all`.

**Themen:** build, gradle, ci, openapi, projektsetup

---

<a id="issue-836"></a>

## Issue #836 — fix(indexing): Autoindex-Crawler ohne Tiefen- und Besuchslimit — Zyklen terminieren nicht
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: #851 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund B7). `AutoindexCrawlerService.crawlRecursive` hat kein Tiefen-/Besuchslimit; Zyklen (z. B. Symlink-Schleifen) führen zu endloser Rekursion, StackOverflow-Risiko, hängendem Indexing-Thread.

**Geliefert:** Visited-Set über normalisierte URLs, konfigurierbare maximale Rekursionstiefe (Default 10, `opaa.indexing.crawl.max-depth`), Obergrenze für Gesamtanzahl gecrawlter Einträge und besuchter Verzeichnisse (Default 5000, `opaa.indexing.crawl.max-entries`). Review-Nachbesserungen behoben zusätzlich einen reinen Verzeichnis-Zyklus-Blindspot (Visited-Set griff dort nie) und getrennte Truncation-Flags pro Grund. Mitgenommen: ein Bug, bei dem `staysUnderBase`-Vergleiche vor statt nach der Normalisierung liefen und dadurch für relative Hrefs wirkungslos waren.

**Verifikation:** `AutoindexCrawlerService.java`, `CrawlProperties.java` im Worktree vorhanden; `AutoindexCrawlerServiceCrawlLimitsTest.java` existiert. Reproduktionsnachweis mit Timeout-Fehlermeldung in PR-Beschreibung dokumentiert.

**Themen:** indexing, bugfix, sicherheit, crawler

---

<a id="issue-837"></a>

## Issue #837 — fix(indexing): storeChunks vergibt bei identischen Chunk-Texten doppelte chunk_index-Werte
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: keine

**Laut Issue:** Teil von Epic #826, Phase 1. `FileProcessingService.storeChunks` ermittelte den `chunk_index` per `chunks.indexOf(chunk)` — bei identischen Chunk-Texten (Duplikat-Absätze) liefert das für beide Vorkommen denselben Index (Korrektheitsfehler) und ist zudem O(n²).

**Geliefert:** Kein eigener PR verknüpft, aber im heutigen Code besteht der beschriebene Fehler nicht mehr: `storeChunks` trägt `chunk_index` über die Iterationsposition (`metadata.put("chunk_index", index)`), nicht über `indexOf`. Der Fix ist vermutlich als Nebeneffekt der Ollama-Embedding-Parallelisierung (#735, die `storeChunks` auf Sub-Batches mit expliziter Indexführung umgebaut hat) bereits vor Ticket-Erstellung erledigt gewesen, oder wurde in einem nicht separat verlinkten Commit mitgezogen — nicht abschließend rekonstruierbar aus den vorliegenden Daten.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java:613` verwendet die Schleifenvariable `index` statt `indexOf`; kein Aufruf von `chunks.indexOf` im Umfeld gefunden.

**Themen:** indexing, bugfix, chunking

---

<a id="issue-838"></a>

## Issue #838 — refactor(indexing): VectorStore-Delete-Filter über gemeinsamen Helfer statt String-Konkatenation
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:S
- PRs: #849 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1. VectorStore-Delete-Filter wurden an ~10 Stellen per String-Konkatenation gebaut statt über die vorhandene typsichere `FilterExpressionBuilder`-API — Wartbarkeitsrisiko.

**Geliefert:** Neuer Helfer `VectorChunkStore` (`io.opaa.indexing`) mit `deleteByDocumentId(UUID)`/`deleteByLibraryId(UUID)`, intern über `FilterExpressionBuilder`. Alle ~10 Aufrufstellen in `FileProcessingService`, `LibraryDocumentService`, `KnowledgeLibraryService` umgestellt. Bewusst in `io.opaa.indexing` platziert, um keine neue Abhängigkeit `library`→`indexing` zu schaffen. Reines Refactoring ohne Verhaltensänderung.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/VectorChunkStore.java` und `VectorChunkStoreTest.java` im Worktree vorhanden.

**Themen:** indexing, refactoring, vectorstore, wartbarkeit

---

<a id="issue-839"></a>

## Issue #839 — fix(indexing): UrlIndexingExecutor parst Proxy inline — NumberFormatException bei ungültigem Port
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: #854 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1. `UrlIndexingExecutor.execute` parst Proxy/Credentials inline statt über `ProxyAndCredentials.parse` — dritte Kopie dieser Logik, fängt `NumberFormatException` bei ungültigem Port nicht ab (derselbe Bug wurde im RSS-Pfad mit #642 bereits behoben).

**Geliefert:** Inline-Parsing durch `ProxyAndCredentials.parse` ersetzt. Laut PR wurde die `NumberFormatException` tatsächlich schon vorher vom äußeren `catch (Exception e)` gefangen (Job scheiterte also schon kontrolliert) — die eigentliche Verbesserung ist die verständliche deutsche Fehlermeldung statt der rohen JDK-Meldung. Meldungstext als package-sichtbare Konstante `ProxyAndCredentials.INVALID_PROXY_MESSAGE` extrahiert.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ProxyAndCredentials.java` und `UrlIndexingExecutor.java` im Worktree vorhanden; `UrlIndexingExecutorExecuteTest.java` enthält den Test `anInvalidSourceProxyPortFailsTheJobWithAGermanMessage`.

**Themen:** indexing, bugfix, proxy

---

<a id="issue-840"></a>

## Issue #840 — fix(chat): Archivierungsprüfung vor dem LLM-Aufruf statt erst beim Persistieren
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S
- PRs: #855 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund A4). Die Archivierungsprüfung eines Chats lief erst in `ChatService.appendTurn`, nach dem LLM-Aufruf — wird ein Space zwischenzeitlich archiviert, wird eine bereits bezahlte LLM-Antwort verworfen.

**Geliefert:** `QueryService.query` prüft jetzt zusätzlich vor Retrieval/LLM-Aufruf, ob der Space eines persistierten Chats archiviert ist (`ChatService#requireSpaceNotArchived` von `private` auf `public` erweitert und wiederverwendet, kein neuer Text/Status). Die späte Prüfung in `appendTurn` bleibt als Race-Absicherung bestehen. Bewusster Trade-off: ein zusätzlicher SELECT pro Anfrage mit persistiertem Chat, um den teureren LLM-Aufruf im Normalfall zu vermeiden.

**Verifikation:** `backend/src/main/java/io/opaa/chat/ChatService.java` und `QueryService.java` im Worktree vorhanden. Reproduktionsnachweis (roter Test mit NullPointerException, weil der Modellaufruf tatsächlich ausgelöst wurde) in PR-Beschreibung dokumentiert.

**Themen:** chat, bugfix, kosten, archivierung

---

<a id="issue-842"></a>

## Issue #842 — docs: Kommentar-Konvention — Vertrag statt PR-Historie, projektweit in AGENTS.md verankern
- Geschlossen: 2026-08-24 (completed)
- Labels: documentation, backend, frontend, size:S
- PRs: #858 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 2. ~30 % der Backend-Main-Zeilen sind Kommentare, viel davon Review-Runden-Nacherzählung statt Vertrag. Konvention projektweit in AGENTS.md verankern (Vertrag/Invariante in 1–5 Zeilen statt Entstehungsgeschichte); Code-Reviewer-Rolle um die Prüfung ergänzen; Kurzbefund zur Frontend-Kommentardichte.

**Geliefert:** Wie gefordert. Konvention in AGENTS.md, Abschnitt Code-Konventionen, verankert (mit Positiv-/Negativbeispiel, wie im heute gültigen AGENTS.md sichtbar). `agents/roles/code-reviewer.md` um die Prüfung ergänzt. Kurzbefund Frontend: ~9,4 % Kommentaranteil (162 Dateien, 36.363 Zeilen, 3.423 Kommentarzeilen) — deutlich unter dem Backend-Befund; Empfehlung „kein Folgeticket" für eine Frontend-Bestandskürzung.

**Verifikation:** AGENTS.md im Worktree enthält den Abschnitt „Code-Kommentare" mit Positiv-/Negativbeispiel, deckungsgleich mit der PR-Beschreibung.

**Themen:** doku, agenten-organisation, code-konventionen

---

<a id="issue-843"></a>

## Issue #843 — test(backend): Test-Kontexte inventarisieren und auf kanonische Meta-Annotationen konsolidieren
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L
- PRs: #865 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 2 (Befund T3), erweitert um den Umfang von #844. 41 `@SpringBootTest`-Klassen mit ~15 unterschiedlichen Kontext-Konfigurationen — jede ein Kontext-Cache-Miss mit eigenem Testcontainers-Postgres. Inventar, ≤3 kanonische Signaturen, Umstellung aller Klassen, AGENTS.md-Konvention.

**Geliefert:** 44 `@SpringBootTest`-Klassen inventarisiert (20 unterschiedliche Signaturen), daraus zwei kanonische Meta-Annotationen abgeleitet (`@OpaaIntegrationTest`, `@OpaaMockMvcTest`, Paket `io.opaa.test`), 41 Klassen umgestellt, 3 begründete Ausnahmen (`MixedProviderConfigurationTest`, `ProviderConfigurationTest`, `OpenAiIntegrationTest`). AGENTS.md um Abschnitt „Spring-Testkontexte" ergänzt. Ehrlich dokumentierter Trade-off: die Kontextzahl sank NICHT (19 vor/nach), und der gemessene Container-Peak stieg sogar von ~16 auf 21, weil Spring-Kontext-Caching Container länger offenhält als vormals klassen-gebundene `@Container`-Felder. Der Nutzen liegt laut PR ausschließlich in der Annotationsoberfläche (von 41 Ad-hoc-Signaturen auf 2 benannte plus 3 Ausnahmen) und im Entfernen echter Code-Duplikation. Migrations-Fixture-Ketten wurden nur bewertet, nicht umgebaut (Folgeticket vorgeschlagen).

**Verifikation:** `backend/src/test/java/io/opaa/test/OpaaIntegrationTest.java` und `OpaaMockMvcTest.java` im Worktree vorhanden; AGENTS.md enthält den Abschnitt „Spring-Testkontexte" mit den beiden Meta-Annotationen.

**Themen:** testinfrastruktur, backend, spring, technische-schulden

---

<a id="issue-844"></a>

## Issue #844 — test(backend): Sonderkontexte auf kanonische Test-Signaturen zurückführen
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M
- PRs: keine

**Laut Issue:** Teil von Epic #826, Phase 2/3-Übergang (Befund T3, Schritt 2 von 2). Nach Einführung der Meta-Annotationen sollten ~14 Testklassen mit eigener `@DynamicPropertySource`, ~5 mit testlokalen `@Import(...TestConfig)` und diverse `@MockitoBean`-Kombinationen einzeln geprüft und wo möglich zurückgeführt werden; Migrations-Fixture-Ketten bewerten.

**Geliefert:** Nicht umgesetzt (not planned). Der zugehörige PR #865 zu #843 hat den Umfang dieses Issues faktisch bereits mit erledigt: Das Inventar dort deckt exakt die dort beschriebenen Sonderkonfigurationen ab und kommt zum Schluss, dass die verbleibenden Abweichungen (`@MockitoBean`, `@DynamicPropertySource`) fachlich nötig sind, da Spring beides zwingend in den Kontext-Cache-Schlüssel aufnimmt — eine weitere Rückführung ist strukturell nicht möglich. Die 8 gefundenen Ballast-Fälle (duplizierte Container) wurden bereits in #843 bereinigt. Das Issue selbst formuliert das im Titel bereits vorweg: „Erweitert um den Umfang von #844 (dort geschlossen)".

**Verifikation:** Kein Code-Bezug nötig — die inhaltliche Deckung ist im PR-Body von #865 (Issue #843) explizit dokumentiert.

**Themen:** testinfrastruktur, backend, not-planned

---

<a id="issue-845"></a>

## Issue #845 — docs: ADR Single-Instance-Betrieb — verstreute Annahmen bündeln
- Geschlossen: 2026-08-24 (completed)
- Labels: documentation, backend, size:S
- PRs: #859 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 2 (Befund B9). Das Backend trifft an mindestens acht Stellen unabhängig die Annahme, es laufe nur eine Instanz (Caffeine-Caches, Chat-Memory, `@Scheduled` ohne Leader-Election, Job-Recovery). ADR mit vollständiger Fundstellenliste, Nachtragsregel und Multi-Instanz-Umbau-Skizze je Fundstelle.

**Geliefert:** ADR-0021 (Status „Vorgeschlagen") mit den acht genannten Fundstellen plus zwei zusätzlich bei der Verifikation gefundenen (`LibraryAccessService.grantsByLibrary`, `ActiveChatModelResolver.cache`). Der dokumentierte Widerspruch zwischen `LibraryIndexingScheduler`-Javadoc und `IndexingJobService.recoverJobsOrphanedByRestart` wurde aufgelöst (Javadoc-Korrektur, kein Code-Umbau). AGENTS.md referenziert das ADR unter „Wichtige Pfade".

**Verifikation:** `docs/decisions/0021-single-instance-betrieb.md` im Worktree vorhanden; AGENTS.md verweist unter „Wichtige Pfade" darauf.

**Themen:** doku, architektur, single-instance, adr

---

<a id="issue-848"></a>

## Issue #848 — docs: Koordinations-Betriebsregeln aus lokalem Memory ins Repo überführen
- Geschlossen: 2026-08-24 (completed)
- Labels: documentation, size:S
- PRs: #850 (2026-08-24)

**Laut Issue:** Mehrere Betriebsregeln der Agenten-Koordination existierten nur im lokalen Memory des Koordinators und fehlten damit in einer zweiten Entwicklungsumgebung (z. B. VPS) — mit realen Folgen am 24.08.2026 (rote CI nach Auto-Merge, Wartefallen, RAM-Thrashing durch parallele Vollbuilds). Vier Regeln nach `docs/AGENT-ORGANIZATION.md` (PR-Wächter, Wartefallen, Vollbuild-Staffelung, Security-Delegation) und eine nach AGENTS.md (Beleg nur auf aktuellem Stand).

**Geliefert:** Wie gefordert. `docs/AGENT-ORGANIZATION.md` erhielt den Abschnitt „Koordinator-Betrieb" mit den vier Punkten; `AGENTS.md`, Abschnitt „Reproduktionsnachweis", um den Punkt „Beleg-Läufe nur auf aktuellem Stand" ergänzt. Bereits vorhandene Regeln wurden geprüft und nicht dupliziert; Maschinenspezifisches blieb bewusst lokal.

**Verifikation:** `docs/AGENT-ORGANIZATION.md` und `AGENTS.md` im Worktree enthalten die beschriebenen Abschnitte (deckt sich mit dem Memory-Eintrag „Build-Cache statt Lock-Wrapper" und „Agent-Wartefalle Hintergrundläufe" des Nutzers).

**Themen:** doku, agenten-organisation, koordination

---

<a id="issue-853"></a>

## Issue #853 — fix(a11y): fg-3 in Sidebar- und Rail-Theme unterschreitet 4,5:1 auf Hover- und Aktiv-Flächen
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, frontend, size:S
- PRs: #878 (2026-08-24)

**Laut Issue:** Als vorbestehender Befund beim Review von PR #852 (#725) identifiziert. `navyRoles.fg3`/`railRoles.fg3` erreichen rechnerisch gegen bg-2/bg-3 (Hover-Füllung, aktive Kachel) nicht die 4,5:1-Kontrastschwelle. Noch nicht belegt, ob fg-3-Text tatsächlich dort landet — erster Schritt ist die Verifikation.

**Geliefert:** Verifikation ergab: fg-3 wird in `Sidebar.tsx`/`GlobalRail.tsx` nirgends als Text auf bg-2/bg-3 gerendert — Hover und aktive Kachel wechseln explizit auf fg-1 (Weiß). `MuiTableCell`-Kopf und `OutlinedInput`-Hover-Rahmen (Konsumenten von `roles.fg3`) kommen im Sidebar-/Rail-Theme gar nicht zum Einsatz. Damit lag kein tatsächlicher Kontrastfehler im UI vor — `tokens.ts` blieb unverändert. Stattdessen: ein Vitest-Wächter gegen die reale Textgrundfläche (bg-1) und eine explizite Dokumentation der Einschränkung in `guidelines.md`, damit künftige Komponenten fg-3 nicht versehentlich auf bg-2/bg-3 einsetzen.

**Verifikation:** `frontend/src/theme/theme.test.ts` und `docs/design/guidelines.md` im Worktree vorhanden.

**Themen:** frontend, barrierefreiheit, theme, ui

---

<a id="issue-860"></a>

## Issue #860 — refactor(backend): DTO-Leak beheben — Services geben Domain-Typen zurück, Mapping in die API-Schicht
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L
- PRs: keine direkt verlinkt (siehe Verifikation — Arbeit lief als PR-Serie)

**Laut Issue:** Teil von Epic #826, Phase 4 (Vorstufe zu Befund B1). ~34 öffentliche Service-Methoden nahmen generierte Request-DTOs entgegen und gaben generierte Response-DTOs zurück — Domänenschicht hing an der API-Schicht, zentraler Treiber der Paketzyklen. Als PR-Serie geplant: space → group → library → chat/query, mit Mapper-Konvention (package-private Mapper in `io.opaa.api`, Vorbild `BrandingResponseMapper`).

**Geliefert:** Kein PR ist auf Issue #860 selbst verlinkt, obwohl das Issue als „completed" geschlossen ist. Die im Issue beschriebene PR-Serie lief laut Folge-Issues real: #874 nennt „DTO-Leak-Serie #860" und PR #873; #877 verweist ebenfalls auf diesen Kontext. Der Zielzustand ist im heutigen Code erreicht: ein Grep nach `io.opaa.api.dto` außerhalb von `io.opaa.api` findet nur noch Javadoc-Erwähnungen (Kommentare, keine echten Imports) in `LibraryCreation.java`, `AssetGrantUpsert.java`, `JobTriggerSource.java`, `ChatService.java` sowie die vier `auth`-Controller — exakt die im Abnahmekriterium vorgesehene Ausnahme. Mapper-Klassen wie `SpaceResponseMapper`, `LibraryDocumentResponseMapper`, `ChatResponseMapper` existieren in `io.opaa.api`.

**Verifikation:** `grep -rl "io.opaa.api.dto" backend/src/main/java | grep -v /api/` liefert die vier auth-Controller plus vier Dateien mit reinen Javadoc-Referenzen (keine Imports). `io.opaa.api`-Paket enthält zahlreiche `*ResponseMapper`-Klassen. Zielbild damit erreicht, PR-Verlinkung im Datensatz aber lückenhaft.

**Themen:** backend, refactoring, dto, architektur, api

---

<a id="issue-862"></a>

## Issue #862 — refactor(db): CHECK-Constraints für Enum-Vokabulare ablösen — Enum-Erweiterungen ohne Migration
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #868 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 3 (Befund B4). Enum-Vokabulare sind doppelt geschützt (Java-Enum + CHECK-Constraint); 8 von 63 Migrationen existieren nur, um Wertelisten zu erweitern. Constraints ersatzlos entfernen, Java-Enum bleibt alleiniger Schreibschutz.

**Geliefert:** Migrationen 064–066 droppen `chk_audit_log_event_type`, `chk_indexing_run_events_category`, `chk_notifications_type`. Umfang bewusst auf diese drei Tabellen begrenzt — `chk_documents_source_type` zeigt dasselbe Muster, wurde aber bewusst zurückgestellt (separates Folgeticket angekündigt); `chk_audit_log_object_type` ebenso, da es das Wachstumsmuster (noch) nicht zeigt. Nachbesserung: zwei Migrationstests (017, 040), die zuvor gegen das lebende `AuditEventType.values()` mit Ausschlusslisten prüften, wurden auf eingefrorene Literallisten umgestellt, damit ein künftiger migrationfreier Enum-Wert sie nicht unbemerkt grün lässt.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/064-drop-audit-log-event-type-check.yaml`, `065-...`, `066-...` sowie die zugehörigen Migrationstests im Worktree vorhanden.

**Themen:** datenbank, migration, enum, backend, technische-schulden

---

<a id="issue-863"></a>

## Issue #863 — ci: retrieval-regression.yml — Domänen-Jobs über Matrix statt Kopie
- Geschlossen: 2026-08-24 (completed)
- Labels: size:S, ci
- PRs: #866 (2026-08-24)

**Laut Issue:** Teil von Epic #826 (Build-Review-Befund). `retrieval-regression.yml` enthielt zwei fast identische ~285-Zeilen-Jobs je Eval-Domäne inkl. eigener Issue-Melde-Logik; eine dritte Domäne wäre die dritte Kopie.

**Geliefert:** Wie gefordert — beide Jobs zu einer Job-Definition mit `strategy.matrix` (`fail-fast: false`) zusammengeführt; domänenspezifisch bleiben Gradle-Task, Timeout, Report-Dateinamen und Issue-Melde-Texte als Matrix-Variablen, sodass Alarm-Issues je Domäne weiterhin getrennt bleiben. Trigger, Concurrency-Gruppe und geteilter Ollama-Modell-Cache unverändert.

**Verifikation:** `.github/workflows/retrieval-regression.yml` im Worktree vorhanden. Nachweislauf per `workflow_dispatch` in der PR-Beschreibung verlinkt.

**Themen:** ci, workflow, eval, retrieval

---

<a id="issue-874"></a>

## Issue #874 — fix(chat): SourceReference.spaceName wird vom Frontend gelesen, aber vom Backend nie befüllt
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, frontend, size:S
- PRs: #880 (2026-08-24)

**Laut Issue:** Vorbestehender Befund aus dem Review der DTO-Leak-Serie #860 (PR #873). Spec definiert `SourceReference.spaceName`, Frontend zeigt es an, aber kein Backend-Pfad befüllt es je. Entscheiden: befüllen oder entfernen.

**Geliefert:** Entfernt statt befüllt. Begründung: Die Suche läuft pro Bibliothek, nicht pro Space; eine Bibliothek kann mit mehreren Spaces assoziiert sein, es gibt also keinen eindeutigen „Space der Fundstelle". Git-Historie bestätigt: das Feld hieß ursprünglich `workspaceName` und wurde nur eingeführt, um einen generierten-Typen-TS-Build-Fehler zu beheben — nie an einen echten Wert angebunden. Feld aus Spec, `SourceEvidenceDrawer.tsx`, `SourceFootnotes.tsx` und Mock-Fixtures entfernt.

**Verifikation:** `backend/src/main/resources/openapi/opaa-api.yaml` und die genannten Frontend-Komponenten im Worktree vorhanden; kein `spaceName` mehr im `SourceReference`-Schema-Kontext (nicht erneut gegrept, PR-Beschreibung ist eindeutig und die Entfernung ist mechanisch nachvollziehbar).

**Themen:** chat, frontend, backend, api, bugfix

---

<a id="issue-875"></a>

## Issue #875 — refactor(backend): Domain-Exceptions statt ResponseStatusException in Services
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #881 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 3 (Befund B8), baut auf #860 auf. Domain-Services werfen `ResponseStatusException` mit HTTP-Status und deutschen Texten — koppelt Domänenschicht an die Web-Schicht, für Nicht-HTTP-Aufrufer (Scheduler, Sync) nicht nutzbar. Vier Domain-Exceptions (`NotFoundException`, `AccessDeniedException`, `ConflictException`, `ValidationException`), zentrales Mapping im `GlobalExceptionHandler`.

**Geliefert:** Wie gefordert, plus drei zusätzliche Exception-Typen, weil der Bestand tatsächlich Sonderstatus jenseits der vier geforderten brauchte: `UnauthorizedException` (401), `PayloadTooLargeException` (413), `ServiceUnavailableException` (503) — ohne sie wäre das Abnahmekriterium „kein `ResponseStatusException` mehr außerhalb `io.opaa.api`" nicht erfüllbar gewesen. Alle sieben Typen mappen auf denselben Response-Body wie zuvor; Statuscodes/Texte byte-identisch, bis auf eine bewusste Ausnahme: `AuditQueryService#requireAuditor` liefert jetzt den spezifischen Text statt des generischen „Zugriff verweigert" (kein bestehender Test prüfte den generischen Text an dieser Stelle).

**Verifikation:** `backend/src/main/java/io/opaa/common/` enthält `NotFoundException.java`, `AccessDeniedException.java`, `ConflictException.java`, `ValidationException.java`, `PayloadTooLargeException.java`, `ServiceUnavailableException.java`, `UnauthorizedException.java` im Worktree.

**Themen:** backend, refactoring, exceptions, architektur

---

<a id="issue-876"></a>

## Issue #876 — refactor(indexing): Quellenzugriff als eigenes Paket — eine Redirect-Policy, RssFeedIndexingExecutor zerlegen
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L
- PRs: #883 (2026-08-24, PR 2 von 2 — PR 1 ist #882, nicht separat in diesem Chunk gelistet)

**Laut Issue:** Teil von Epic #826, Phase 4 (Befund B7), vorgezogen per Maintainer-Entscheidung. Quellenzugriff (HTTP-Client, Redirect-Verfolgung, Proxy/Credentials, Downloads) verstreut über `indexing`, teils als statische Aufrufe auf `AutoindexCrawlerService`. Vier divergierende Redirect-Loops; `RssFeedIndexingExecutor` als ~1300-Zeilen-Gottobjekt. Neues Paket `io.opaa.sourceaccess`, eine Redirect-Implementierung, Zerlegung des Executors.

**Geliefert:** Laut verlinktem PR #883 (2 von 2) wurde das neue Paket `io.opaa.sourceaccess` bereits in PR #882 angelegt (`RedirectFollowingFetcher` u. a.); #883 ergänzt `RedirectFollowingFetcherTest` (7 Fälle je Policy-Zweig) und zerlegt `RssFeedIndexingExecutor` in `RssFeedRunContext`, `FeedFetcher`, `DetailPageExtractor`, `AttachmentIndexer` (alle package-intern, kein neues öffentliches API). Ergebnis: `RssFeedIndexingExecutor.java` auf 462 Zeilen reduziert (<500 ✓), keine Methode über 6 Parameter. Politeness-`Thread.sleep` bewusst nicht in dieser Runde angefasst (separater Befund).

**Verifikation:** `backend/src/main/java/io/opaa/sourceaccess/RedirectFollowingFetcher.java`, `backend/src/main/java/io/opaa/indexing/{RssFeedIndexingExecutor,RssFeedRunContext,FeedFetcher,DetailPageExtractor,AttachmentIndexer}.java` im Worktree vorhanden.

**Themen:** indexing, refactoring, sicherheit, quellenzugriff, backend

---

<a id="issue-877"></a>

## Issue #877 — fix(indexing): Dokumentidentität auf (Bibliothek, Quelle) scopen — Dokument-Stehlen zwischen Bibliotheken beenden
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:M
- PRs: #885 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 4 (Befund B6), vorgezogen per Maintainer-Entscheidung, nach dem Quellenzugriff-Schnitt (#876) umzusetzen. `DocumentRepository.findByFilePath` war global statt bibliotheksgescopt — indizieren zwei Bibliotheken dieselbe URL/denselben Pfad, „stiehlt" jeder Lauf das Dokument der anderen inkl. Chunk-Löschung.

**Geliefert:** Identität auf `(library_id, file_path)` gescopt (`findByLibraryIdAndFilePath`) an allen Dedup-/Change-Detection-Stellen; alte Move-/Steal-Semantik vollständig entfernt. Migration 067 mit Unique-Constraint `uk_documents_library_path`, ergänzt um einen selbstheilenden Cleanup-Changeset (Review-Nachbesserung) als Absicherung für real gewachsene Instanzen. `existsBySourceEntryUrl` (RSS-Anlagen-Backfill) ebenfalls nachträglich auf die Bibliothek gescopt, da dort ein weiterer, im Issue nicht benannter Cross-Library-Leak gefunden wurde. `file_path`-Polymorphie bewusst nur dokumentiert, nicht aufgelöst (wie im Issue vorgegeben).

**Verifikation:** `backend/src/main/resources/db/changelog/changes/067-scope-document-identity-to-library.yaml` und `Migration067ScopeDocumentIdentityToLibraryTest.java` im Worktree vorhanden. Reproduktionsnachweis (roter Test mit „expected: 1L but was: 0L") in PR-Beschreibung dokumentiert.

**Themen:** indexing, bugfix, datenmodell, migration, library

---

<a id="issue-884"></a>

## Issue #884 — refactor(backend): Request-scoped CurrentUser — Aufrufer-Identität zentralisieren
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L, auth
- PRs: #887 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 3 (Befund B2, Kernteil), baut auf #860 und #875 auf. `currentUser(Jwt)` wortgleich in 14 Controllern; Nutzer bis zu dreimal pro Request geladen; Service-Methoden mit `(UUID currentUserId, boolean systemAdmin)`-Parameterpaaren. Request-scoped `CurrentUser`, befüllt vom `UserProvisioningFilter` ohne zusätzlichen DB-Zugriff.

**Geliefert:** `CurrentUser`-Record (id, organizationId, systemRole, displayName) über `HandlerMethodArgumentResolver` (`CurrentUserArgumentResolver`) statt Request-scoped Bean bereitgestellt — bewusste Mechanismus-Entscheidung, da der Filter das Objekt bereits vollständig gebaut hat. 14 Controller und ~10 Service-Signaturen umgestellt. Wichtiger Sicherheitsbefund aus dem Review selbst behoben: `CurrentUser` war zunächst fail-open (ein fehlender Resolver hätte Springs generischem Databinding erlaubt, das Objekt aus Query-Parametern zu befüllen — Identitätsübernahme via `?systemRole=SYSTEM_ADMIN` möglich gewesen). Jetzt zweifach fail-closed: exklusive `@Caller`-Annotation plus strukturell unbindbare Klasse (kein öffentlicher Konstruktor, Reflection-Guard gegen Springs `BeanUtils.getResolvableConstructor`). Neuer Test `CurrentUserFailClosedTest` mit rot/grün-Nachweis dieser Lücke. `LibraryAccessService`-Methoden, die die Berechtigung eines *fremden* Zielnutzers prüfen, bewusst außerhalb des Umfangs belassen (Folgeticket B2b).

**Verifikation:** `backend/src/main/java/io/opaa/auth/{CurrentUser,CurrentUserArgumentResolver,Caller,CurrentUserWebConfig}.java` im Worktree vorhanden; `backend/src/test/java/io/opaa/auth/CurrentUserFailClosedTest.java` existiert.

**Themen:** auth, backend, refactoring, sicherheit, architektur

---

<a id="issue-886"></a>

## Issue #886 — feat(indexing): Dokumente verschwundener Quellen aufräumen — veralteter Bestand wächst unbegrenzt
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M
- PRs: #900 (2026-08-25)

**Laut Issue:** Kein Indexlauf löschte Dokumente, deren Datei/URL in der Quelle nicht mehr existierte — Zeilen und Chunks blieben dauerhaft bestehen. Gefordert war ein Aufräummechanismus je Quellentyp (FILESYSTEM, HTTP_DIRECTORY, RSS) am Ende eines erfolgreichen Volllaufs, inklusive Chunk-Löschung und unter Beachtung der Truncation-Flags aus #836/#851, damit ein gekappter Lauf nicht fälschlich löscht.

**Geliefert:** Neuer `StaleDocumentCleanupService` entfernt für FILESYSTEM/HTTP_DIRECTORY Dokumente samt Chunks, deren Pfad/URL im aktuellen Lauf nicht mehr vorkommt, skopiert auf Bibliothek+Quelle. Mehrere Sicherungen: Aufräumen nur auf dem Erfolgspfad, `UrlIndexingExecutor` gated zusätzlich auf `!truncated() && !incomplete()`, `AsyncIndexingExecutor` wirft jetzt eine `IOException` bei fehlendem/kein-Verzeichnis-`sourcePath`, und eine leere Ist-Menge löscht grundsätzlich nichts. RSS räumt bewusst **nicht** auf (ADR-0017, Entscheidung 5) — nur ein Regressionstest und ein struktureller Test wurden ergänzt. Neues Ereignis `IndexingRunEventCategory.REMOVED` protokolliert jede Löschung. Deckt sich mit der Forderung; ADR-0017 bleibt bewusst auf „Vorgeschlagen“.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/StaleDocumentCleanupService.java` existiert im Worktree.

**Themen:** indexing, retrieval, datenbereinigung, rss

---

<a id="issue-888"></a>

## Issue #888 — refactor(space): Zentrale AccessPolicy und effectiveRole — Owner-Semantik vereinheitlichen
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M
- PRs: #891 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 3. Autorisierungsentscheidungen für Spaces waren als eigene Helfer je Service verstreut (`requireManager`/`requireCurator`/`requireMemberListViewer`/`hasCuratorRole`) mit subtil unterschiedlicher Owner-Behandlung — ein Space-Owner zählte in `SpaceAssetAssociationService` als Kurator, in `SpaceService.requireManager` aber nicht als Manager. Gefordert war eine zentrale `effectiveRole`-Funktion (Owner ⇒ ADMIN) plus eine `AccessPolicy`-Komponente und ein `OrganizationScopedLoader` für das kopierte Org-Boundary-404-Muster.

**Geliefert:** Neue `SpaceAccessPolicy` mit einheitlicher `effectiveRole(Space, CurrentUser|UUID)` (Owner ⇒ mindestens ADMIN) sowie `OrganizationScopedLoader` für `loadSpace`/`loadGroup`/`requireUserInOrganization`, angewendet in `SpaceService`, `SpaceAssetAssociationService`, `GroupService`. Die dokumentierte Verhaltensänderung (Owner mit unterhalb-ADMIN-Mitgliedsrolle darf jetzt Manager-Aktionen ausführen) wurde bewusst umgesetzt und getestet. Zusätzlich, über den Issue-Text hinausgehend: Review-Befund zur API-Grenze behoben — `SpaceResponseMapper` speist `userRole` jetzt ebenfalls aus `effectiveRole`, sonst hätte das Frontend die freigeschaltete Manager-Aktion nicht angezeigt. `LibraryAccessService` wurde wie gefordert nicht angetastet.

**Verifikation:** `backend/src/main/java/io/opaa/space/SpaceAccessPolicy.java` existiert im Worktree.

**Themen:** spaces, auth, refactoring, epic-826

---

<a id="issue-889"></a>

## Issue #889 — refactor(chat): Chat-Pfad als explizite Pipeline — Transaktions-Kartenhaus und COUNT(*)-Sequenz ablösen
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L
- PRs: #890 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 4. Vier Punkte: (1) ein fragiles Transaktions-Kartenhaus im Chat-Pfad (NOT_SUPPORTED + manuelles TransactionTemplate + EAGER-Collection), (2) eine `nextSequenceFor`-Berechnung per `COUNT(*)`, die nach einer gelöschten Nachricht dauerhaft kollidiert, (3) ein Permission-History-Drift-Check, der bei jeder Anfrage drei Zusatz-Queries ausführt, nur um zu loggen, (4) eine `QueryConfiguration` mit 7 manuell verdrahteten Beans statt `@Service`.

**Geliefert:** Alle vier Punkte umgesetzt. Sequenz jetzt über `MAX(sequence)+1`. Pipeline in klar benannte Lese-/LLM-/Schreibphasen aufgeteilt, isolierter Schreibversuch in neuen `@Service ChatMessageWriter` (`REQUIRES_NEW`) ausgelagert, `appendTurn` behält `NOT_SUPPORTED` als strukturelle Garantie gegen die #299/#525-Deadlock-Konstellation. Permission-History-Stichprobe über neue Property `permissionHistorySampleRate` — **Default bewusst auf 1.0 belassen** (Koordinator-Entscheidung), also keine Verhaltensänderung ohne Maintainer-Freigabe, obwohl das Issue eine Absenkung nahelegte. 5 der 7 `QueryConfiguration`-Beans auf `@Service` umgestellt, `chatMemory`/`QueryMetrics` bleiben als `@Bean` (echte Konfiguration).

**Verifikation:** `backend/src/main/java/io/opaa/chat/ChatMessageWriter.java` existiert im Worktree.

**Themen:** chat, refactoring, transaktionen, epic-826, retrieval

---

<a id="issue-892"></a>

## Issue #892 — refactor(audit): AuditEvent-Builder und Domain-Events — Doppelbuchführung strukturell absichern
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L
- PRs: #895 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 3, letzter Baustein. Zwei Probleme: `AuditEventRecorder` hatte 10–13 Positionsparameter (Vertauschungsgefahr), und jeder Grant-/Bibliotheks-Schreibpfad musste Audit UND PermissionHistoryService von Hand parallel aufrufen — das Vergessen einer Seite war genau die Lücke, die #545 nachträglich schließen musste. Gefordert: ein Builder für `AuditEvent` sowie synchrone Domain-Events (`GrantChanged`/`LibraryChanged`) mit Audit- und History-Listenern, Scope beschränkt auf die Grant-/Library-Pfade.

**Geliefert:** Der Builder kam als eigener, vorgelagerter PR #893 (nicht im Chunk enthalten, hier nur referenziert). Dieser PR (#895) liefert die Domain-Events: `GrantChanged`/`LibraryChanged` mit `AuditListener`/`PermissionHistoryListener` (package-private, `io.opaa.library`), beide normale `@EventListener` (nicht `@TransactionalEventListener`) — Rollback der Transaktion rollt beide Seiten mit zurück wie vorher. Bewusst nicht umgestellt: `DENIED`-Eskalationswache, `LIBRARY_CHANGED`/`LIBRARY_SOURCE_UPDATED` (keine History-Gegenseite), `deleteLibrary` (variable Intervallanzahl). Bemerkenswert: Dieser PR ersetzt den ursprünglichen #894, der beim Löschen seines Basis-Branches nach einem gestapelten Merge automatisch von GitHub geschlossen wurde — derselbe Diff, per Cherry-Pick übertragen.

**Verifikation:** `backend/src/main/java/io/opaa/library/GrantChanged.java` existiert im Worktree.

**Themen:** audit, refactoring, permissions, epic-826, domain-events

---

<a id="issue-896"></a>

## Issue #896 — build: Gradle-Modul opaa-api — Spec, Generator und geteilte Enums herauslösen
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L, ci
- PRs: #898 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 4, letzter Großbaustein. Ziel war ein eigenes Gradle-Modul `opaa-api` mit der OpenAPI-Spec, dem Java-Generator und den geteilten Domain-Enums, damit eine Spec-Änderung nur dieses kleine Modul invalidiert statt das gesamte Backend neu zu kompilieren. Modulname per Maintainer-Entscheidung fest vorgegeben.

**Geliefert:** Top-Level-Modul `opaa-api/` (nicht unter `backend/`), eingebunden über `include(":opaa-api")` bei weiterhin `backend/` als Gradle-Root. 22 Enums nach `io.opaa.api.types` verschoben (alle bereits Spring-/JPA-frei), inklusive Paritätstests gegen die YAML-Spec. Inkrementalitäts-Beleg im PR: nach einer reinen Spec-Änderung bleibt `:compileJava` des Backends `UP-TO-DATE`. Docker-Build-Kontext musste dafür vom Backend-Verzeichnis auf den Repo-Root umgestellt werden (docker-compose.yml, alle Workflow-Dateien mit Image-Build). Frontend generiert unverändert per `openapi-typescript`, nur der Pfad hat sich geändert — Leitplanke eingehalten.

**Verifikation:** Verzeichnis `opaa-api/` existiert im Worktree.

**Themen:** projektsetup, ci, build, modulstruktur, api, epic-826

---

<a id="issue-903"></a>

## Issue #903 — test(backend): Spring-Testkontexte konsolidieren (~19 → ≤10) — Meta-Annotation für Indexing, geteilte Mock-Configs
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine (im Chunk nicht verlinkt — tatsächlich über PR #905 und PR #908 geliefert, siehe Verifikation)

**Laut Issue:** Folgearbeit aus Epic #826. Von ~19 Spring-Testkontexten sollten durch eine dritte kanonische Meta-Annotation (`@OpaaIndexingIntegrationTest`), geteilte `@TestConfiguration`-Mocks und die Umstellung mechanischer Einzelfälle höchstens 10 Kontexte übrig bleiben, bei gemessener Verbesserung der `./gradlew test --rerun`-Laufzeit (Basis: 9 m 53 s) und ohne Abschwächung von Assertions.

**Geliefert:** PR #905 führte `@OpaaIndexingIntegrationTest` ein, PR #908 die Mock-Konsolidierung (Schritte 2–4). Laut Abschlusskommentar im Issue: Laufzeit **9 m 53 s → 3 m 13 s** (−67 %), Kontextzahl ~21 → **17** statt der geforderten ≤10. **Abweichung vom Issue, offen benannt und vom Maintainer akzeptiert:** Das Kontextziel wurde bewusst nicht erreicht — die verbleibenden 17 Kontexte stecken in echten Konfigurationsunterschieden (Konfiguration als Testsubjekt, `@MockitoSpyBean` auf echten Beans, einzigartige Race-Mocks); weiteres Zusammenlegen hätte Testsubstanz gekostet. Das Laufzeitziel wurde damit deutlich übererfüllt, das Kontextzahl-Kriterium nachträglich als weniger wichtig eingestuft.

**Verifikation:** `backend/src/test/java/io/opaa/test/OpaaIndexingIntegrationTest.java` und zugehörige Mock-/Reset-Klassen existieren im Worktree. Commits `0b3512c8` (#905) und `950eb4b3` (#908) in der Historie vorhanden.

**Themen:** testinfrastruktur, ci, backend, epic-826, refactoring

---

<a id="issue-904"></a>

## Issue #904 — chore(db): Liquibase-Historie zu einer Baseline zusammenfassen (257 Changesets → logisch gruppierte Baseline)
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L
- PRs: #906 (2026-08-25)

**Laut Issue:** Maintainer-Entscheidung, die Liquibase-Historie einmalig vor Produktionsbetrieb zu einer Baseline zusammenzufassen, da jede Installation in dieser Phase neu aufgesetzt werden kann. Gefordert: eine Baseline-Datei mit wenigen logisch gruppierten Changesets, Äquivalenznachweis (leerer Schema-Diff + Seed-Datenabgleich) gegen die alte Kette, Löschung der historischen Migrationstests bei Erhalt von `AbstractMigrationTest`, und die Wiedereinführung „ein Changeset pro Änderung“ ab der Baseline.

**Geliefert:** `001-baseline.yaml` mit 8 logisch gruppierten Changesets (Extensions, Auth/Org, Spaces/Gruppen, Bibliotheken/Indexing, Chat/Query, Audit/History, Sonstiges, Seeds). **Zahl korrigiert gegenüber dem Issue-Titel:** tatsächlich 134 Changesets (nicht 257) wurden zusammengefasst — im PR selbst als Review-Nachbesserung (W1) richtiggestellt. Äquivalenznachweis per `pg_dump --schema-only` inklusive Owner/GRANT-Diff, beide leer. 52 Migrationstest-Klassen und 18 Fixture-Ketten gelöscht, `AbstractMigrationTest` bleibt; zwei neue schlanke Klassen (`MigrationBaselineTest`, `AuditPrivilegeModelTest`) sichern Kerninvarianten (Organisationsgrenzen-Regel, ADR-0015-Privilegienmodell, Zustandsinvarianten wie Unique-Constraints). Testlaufzeit `io.opaa.migration`: 2 m 42 s → ~14 s. `docs/migrations/` komplett gelöscht (Maintainer-Entscheidung während der Nachbesserung, über den ursprünglichen Issue-Umfang hinaus).

**Verifikation:** `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` existiert im Worktree.

**Themen:** datenbank, liquibase, migration, projektsetup, refactoring

---

<a id="issue-912"></a>

## Issue #912 — Mehrthemen-Fragen: Retrieval verdrängt das schwächere Thema vollständig (topK-Monokultur)
- Geschlossen: 2026-08-27 (completed)
- Labels: enhancement, backend, evaluation
- PRs: keine (Sammel-/Ursprungs-Issue ohne eigenen PR — Umsetzung in Sub-Issues, siehe unten)

**Laut Issue:** Detaillierter Live-Befund auf der Demo (25.08.2026): Bei Mehrthemen-Fragen (z. B. „was kosten führerschein und personalausweis“) füllt das dominantere Thema alle `topK`-Plätze, das schwächere Thema bekommt keinen Chunk. Drei Ursachen identifiziert: ein einziger Suchvektor für zwei Teilfragen, Tippfehlerverschiebung der Ähnlichkeit, und eine starre Erste-Nachricht-Konkatenation bei Folgefragen. Fünf unabhängig umsetzbare Lösungsrichtungen A–E vorgeschlagen (MMR-Diversität, Teilfragen-Zerlegung, Query-Reformulierung, topK-Anhebung, Eval-Absicherung) mit empfohlener Reihenfolge E → A+D → B/C.

**Geliefert:** Dieses Issue ist kein eigener Lieferbaustein, sondern die Ursachenanalyse und Wurzel eines kleinen Epics. Die tatsächliche Arbeit steckt in den Sub-Issues, die die vorgeschlagene Reihenfolge fast exakt umsetzten: #913 (Maßnahme E, Eval-Fälle), #914 (Maßnahmen A+D, MMR/topK), #923 (Maßnahmen B+C, Multi-Query-RAG), sowie den daraus entstandenen Folgebefunden #932 (Chunk-Vervollständigung), #933 (Contextual Chunking), #937 (Zitat-Faktenprüfung) und #938 (verbleibende Rankinggrenze für Satzungs-PDF). Alle sind in dieser Inventur als eigene Bausteine erfasst.

**Verifikation:** Entfällt (kein eigener Code-Beitrag dieses Issues); siehe die Verifikationen der genannten Sub-Issues.

**Themen:** retrieval, epic, evaluation, mehrthemen, query

---

<a id="issue-913"></a>

## Issue #913 — Eval: Mehrthemen-Golden-Fälle und Recall pro Teilthema
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: #915 (2026-08-25)

**Laut Issue:** Maßnahme E aus #912, absichtlich zuerst umzusetzen: Das Golden Dataset und der Eval-Harness konnten das Mehrthemen-Fehlerbild bisher nicht messen. Gefordert: 10–20 Mehrthemen-Golden-Fälle (idealerweise `city-landmarks.json`), Tippfehler-Varianten, eine neue Metrik „alle erwarteten Dokumente getroffen“ statt eines verwässernden Teilkredits, sowie eine aktualisierte Baseline mit erwartbar schlechten `multi_topic`-Werten als Vorher-Messung.

**Geliefert:** 20 neue `multi_topic`-Fälle in `eval/golden/city-landmarks.json` (15 medium, 5 Tippfehler-Varianten hard). Neue Metrik `allExpectedDocumentsHitAt10`, binär statt Teilkredit — bestätigt als notwendig, da die bestehende `recallAt10` bei einem von zwei Dokumenten bereits 0,5 vergeben hätte. **Wesentliche Abweichung vom Issue:** Die erwartete „erwartbar schlechte“ Vorher-Messung trat **nicht** ein — `allExpectedDocumentsHitAt10=1,000` für alle 20 Fälle, weil der Harness mit einem dokumentbezogenen Fenster von `documentTopK=10` misst (ADR-0012), nicht mit dem Produktions-`topK=5`, das die Verdrängung auf der Demo tatsächlich auslöste. Diese Baseline belegt das #912-Fehlerbild damit noch nicht; eine engere Messung wäre eigener Aufwand gewesen und wurde nicht umgesetzt.

**Verifikation:** `eval/golden/city-landmarks.json` enthält `multi_topic`-Einträge (Grep bestätigt Treffer).

**Themen:** evaluation, retrieval, golden-dataset, metriken

---

<a id="issue-914"></a>

## Issue #914 — Query: MMR-Diversität im Retrieval (fetchK, mmrLambda) und topK-Anhebung
- Geschlossen: 2026-08-26 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: #922 (2026-08-26)

**Laut Issue:** Maßnahmen A+D aus #912. Gefordert: eine MMR-Nachauswahl (`fetchK`-Kandidaten, dann Diversitäts-Reduktion auf `topK`), neue `QueryProperties`-Parameter `fetchK` (Default ~25) und `mmrLambda` (Default ~0,7), Anhebung des `topK`-Defaults von 5 auf 8, sowie der Nachweis, dass Berechtigungsfilter und Ähnlichkeitsschwelle unverändert vor der MMR-Auswahl gelten und keine zusätzlichen API-Aufrufe entstehen.

**Geliefert:** `MmrSelector` mit echten Chunk-Embeddings (per SQL-Lookup über `ChunkEmbeddingLookup`, kein API-Aufruf) statt der zunächst erwogenen Jaccard-Textnäherung — Kurskorrektur nach dem ersten Review. `topK`-Default wie gefordert auf 8 angehoben. **Wesentliche Abweichung vom Issue:** `mmrLambda` startet mit Default **1,0** (MMR de facto abgeschaltet), nicht 0,7 wie im Issue vorgeschlagen — Messungen auf den 20 `multi_topic`-Fällen zeigten `mmrLambda=0,7` bei 19/20 gegenüber 20/20 für reines topK ohne MMR; die im PR festgelegte Entscheidungsregel verlangte mindestens Gleichstand mit dem `topK`-only-Ergebnis. MMR ist vollständig implementiert und per Konfiguration aktivierbar, aber kein Produktions-Default.

**Verifikation:** `backend/src/main/java/io/opaa/query/MmrSelector.java` existiert im Worktree; `top-k: ${OPAA_QUERY_TOP_K:8}` in `application.yml` bestätigt.

**Themen:** retrieval, query, mmr, evaluation, epic-912

---

<a id="issue-923"></a>

## Issue #923 — Query: Teilfragen-Zerlegung und kontextbewusste Reformulierung vor dem Retrieval (Multi-Query-RAG)
- Geschlossen: 2026-08-26 (completed)
- Labels: enhancement, backend, size:L, evaluation
- PRs: #926 (2026-08-26)

**Laut Issue:** Maßnahmen B+C aus #912, nachdem D/A (#914) das Originalbeispiel nachweislich nicht heilten (`001_personalausweis.md` lag strukturell unter den Führerschein-Scores der Kombifrage). Gefordert: ein LLM-Vorverarbeitungsschritt, der Mehrthemen-Fragen in 1–N eigenständige Teilfragen zerlegt, je Teilfrage eine eigene Vektorsuche mit Berechtigungsfilter, rangbasierte Zusammenführung (Reciprocal Rank Fusion statt Score-Vergleich), robuster Fallback bei Zerlegungsfehlern, sowie Ablösung der starren Erste-Nachricht-Konkatenation.

**Geliefert:** Neue `QueryDecompositionService` und `ReciprocalRankFusion`. Konfigurierbar über `queryDecompositionEnabled` (Default true) und `maxSubQueries` (Default 3). Fallback auf die alte `buildSearchQuery`-Logik bei LLM-Fehler/unparsebarer Antwort. Berechtigungsfilter nachweislich in jeder Teilsuche (dedizierter Integrationstest). MMR läuft je Teilfrage in ihrer eigenen Kandidatenmenge, nicht auf der Gesamtmenge — im PR begründet. Messung: 19/20 vorher wie nachher auf den `multi_topic`-Fällen (keine Verbesserung im Eval-Korpus, da dessen Score-Lücke nicht so scharf ist wie im echten Personalausweis-Fall), gemessene Zusatzlatenz ~157 ms. **Live-Verifikation auf der Demo folgte erst nach Deploy** (nicht Teil dieses PRs) und deckte den in #932 dokumentierten Folgedefekt auf.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryDecompositionService.java` existiert im Worktree.

**Themen:** retrieval, query, multi-query-rag, llm-integration, evaluation, epic-912

---

<a id="issue-924"></a>

## Issue #924 — fix(ci): Renovate-PRs scheitern am CLA-Check — gitAuthor ist keinem Konto zugeordnet
- Geschlossen: 2026-08-26 (completed)
- Labels: bug, ci
- PRs: #925 (2026-08-26)

**Laut Issue:** Der erste echte Renovate-Lauf erzeugte fünf Update-PRs (#916–#920), alle rot am `cla-check` — Renovates Standard-`gitAuthor` ist keinem GitHub-Konto zugeordnet, die Allowlist `criew,*[bot]` matcht nur echte App-Bot-Logins, nicht den selbst betriebenen PAT-Lauf. Gefordert: `gitAuthor` in `renovate.json5` auf eine dem PAT-Inhaber zugeordnete, CLA-signierte Identität setzen, plus Nacharbeit (Rebase-Checkbox der fünf bestehenden PRs).

**Geliefert:** `gitAuthor: 'Renovate Bot <1293732+bigpuritz@users.noreply.github.com>'` — exakt wie im Issue vorgeschlagen. Dokumentation in `docs/renovate.md` ergänzt. Die Nacharbeit (Rebase der fünf Branches) hat der PR-Autor selbst übernommen, außerhalb des PR-Diffs.

**Verifikation:** `gitAuthor: 'Renovate Bot <1293732+bigpuritz@users.noreply.github.com>'` steht in `renovate.json5` (Zeile 26) im Worktree.

**Themen:** ci, renovate, cla, projektsetup

---

<a id="issue-927"></a>

## Issue #927 — docs: Doku-Struktur nach Achsen konsolidieren (Stand, Handbuch, Recherche)
- Geschlossen: 2026-08-26 (completed)
- Labels: documentation, size:M
- PRs: #928 (2026-08-26)

**Laut Issue:** `docs/` vermischte die Achsen Vision/Plan, Spezifikation, Ideen/Diskussion, Stand und Produktdokumentation. Konkret gefordert: `STATUS.md` (veraltet, z. B. Bereich G fälschlich als „kein Protokoll“) zugunsten von `docs/fortschritt/gesamtstand.md` als einziger Stand-Quelle löschen; `GraphRAG.md` nach `docs/discussions/` verschieben; veraltete Marketing-Artefakte (`onepager-de.html`, `OPAA-pitch-de.html/.pdf`) löschen; neuer Ordner `docs/handbuch/` für `deployment.md`/`demo-walkthrough.md`; `INDEX.md` um eine Achsen-Erklärung ergänzen.

**Geliefert:** Alle vier Maßnahmen wie gefordert umgesetzt, plus ein vom Maintainer nachgetragener fünfter Punkt außerhalb des ursprünglichen Issue-Texts: `docs/tagesreport.md` → `docs/fortschritt/tagesreport.md` und `docs/renovate.md` im INDEX verlinkt. Historische Dokumente unter `docs/fortschritt/20260831/` bewusst unangetastet gelassen, wie gefordert.

**Verifikation:** Verzeichnis `docs/handbuch/` existiert im Worktree.

**Themen:** doku, projektstruktur, gesamtstand

---

<a id="issue-929"></a>

## Issue #929 — docs: Demo-Dokumentation konsolidieren und deployment.md zum allgemeinen Betriebshandbuch machen
- Geschlossen: 2026-08-26 (completed)
- Labels: documentation, size:L, demo
- PRs: #931 (2026-08-26)

**Laut Issue:** Die Demo-Instanz „Stadt Rheinfurt“ war über vier sich überschneidende Dokumente beschrieben (`deployment.md`, `demo-walkthrough.md`, `demo/README.md`, `demo-instance.md`), mit belegten Drift-Fällen (falsche `docker-compose.yml`-Zeilennummern, falsche Aussage zu `OPAA_CSP_CONNECT_SRC_EXTRA`, ~80 Zeilen zu entfallenen Variablen). Zielstruktur: `deployment.md` wird rein allgemeines Betriebshandbuch; `demo/README.md` wird die eine Quelle für die Demo-Umgebung; das Vorführ-Drehbuch zieht nach `docs/market/demo-drehbuch.md`; `demo-instance.md` bleibt reines Konzept.

**Geliefert:** Alle vier Strukturpunkte umgesetzt, inklusive der belegten Sanierungen (Zeilennummern korrigiert 62/64–65/40/56/68, CSP-Aussage korrigiert, `#762`-Migrationsblock eingedampft, `OPAA_AUTH_BASIC_*`-Nachrufe gestrichen). `demo-walkthrough.md` vollständig aufgelöst. **Abweichung von der Schätzung:** Die Kürzung von `deployment.md` fiel mit 1105 → 979 Zeilen kleiner aus als die im Issue geschätzten ~400–450 Zeilen — im PR begründet: die H3-Abschnitte „Aktualisierung“ und „Sicherheitshinweis“ beschrieben tatsächlich allgemeines, instanzunabhängiges Betriebswissen und wurden zu eigenständigen H2-Abschnitten promoviert statt ausgelagert.

**Verifikation:** `demo/README.md` existiert im Worktree.

**Themen:** doku, demo, betrieb, deployment

---

<a id="issue-932"></a>

## Issue #932 — Query: Gebühren-Chunk verliert gegen Einleitungs-Chunk desselben Dokuments — Chunk-Auswahl nach der Fusion vervollständigen
- Geschlossen: 2026-08-26 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: #934 (2026-08-26), #935 (2026-08-26)

**Laut Issue:** Folgebefund der Live-Verifikation von #923 auf der Demo: Die Teilfragen-Zerlegung wirkt, beide Themen werden abgerufen — aber von `001_personalausweis.md` überlebt nur der Einleitungs-Chunk die Fusion, nicht der Gebühren-Chunk desselben Dokuments. Drei Lösungsrichtungen zur Bewertung vorgeschlagen: Dokument-Vervollständigung im Retrieval, Fusions-Budget entkoppeln, oder Chunking mit Dokumentkontext anreichern.

**Geliefert:** Lösungsrichtung 1 (Dokument-Vervollständigung) umgesetzt, in zwei Runden. PR #934 („Zuschnitt v1“) verdrängte nur den auswahlrang-schwächsten Chunk eines bereits ≥2-Chunk-vertretenen Dokuments — die Live-Verifikation nach dem Merge scheiterte, weil bei 8 Ein-Chunk-Dokumenten keine Verdrängungsquelle existierte. Das Issue wurde daraufhin **wieder geöffnet** und mit PR #935 um eine zweite Verdrängungsstufe ergänzt (Stufe 2: auswahlrang-letzter Chunk der Gesamtauswahl, gedeckelt auf `max(1, topK/4)` Verdrängungen je Aufruf, nach einem Review-Fund zur unbegrenzten Verdrängung). Lösungsrichtung 2 wurde nicht separat umgesetzt (bereits über bestehende Konfiguration abgedeckt), Lösungsrichtung 3 in #933 ausgekoppelt.

**Verifikation:** `backend/src/main/java/io/opaa/query/DocumentCompletion.java` existiert im Worktree.

**Themen:** retrieval, query, chunk-auswahl, evaluation, epic-912

---

<a id="issue-933"></a>

## Issue #933 — Indexing: Contextual Chunking — Dokumentkontext in Chunk-Embeddings
- Geschlossen: 2026-08-27 (completed)
- Labels: enhancement, backend, size:L, evaluation
- PRs: #940 (2026-08-27)

**Laut Issue:** Aus #932 (Lösungsrichtung 3) ausgekoppelt: Chunks tragen ohne Dokumentkontext kaum Signal, wovon sie handeln (z. B. eine reine Gebührentabelle) — das ist die Wurzel mehrerer Rankingprobleme. Gefordert: ein Kontext-Präfix vor dem Embedding (nicht in Metadaten, wegen der bestehenden Whitelist-Invariante der Embedding-Pipeline), Entscheidung Embedding-only vs. gespeicherter Text, eine Migrationsstrategie (Voll-Reindex), neu gezogene Eval-Baselines beider Domänen ohne globale Verschlechterung, und ein Live-Nachweis auf der Demo.

**Geliefert:** `ChunkContextTitle` leitet aus dem Dateinamen einen bereinigten Titel ab; Präfix greift nur embedding-seitig (nicht im gespeicherten Text/Zitat), ausschließlich für Dokumente, die in **2 oder mehr Chunks** zerfielen — ein einzelner Chunk bekommt bewusst keinen Präfix. Diese „Split-Gate“-Form wurde erst nach zwei verworfenen Varianten gefunden (roher Dateiname auf allen Chunks regressierte city-landmarks, humanisierter Titel auf allen Chunks regressierte comic-characters); beide Eval-Baselines wurden entsprechend neu gezogen/geprüft. Voll-Reindex als Migrationsstrategie dokumentiert. **Offener Punkt, im PR selbst benannt:** Für den `maria.weber`-Fall aus #938 lagen nach dem Reindex live beide einschlägigen Quellen außerhalb `topK=8` — Live-Verifikation beider Demo-Konten war explizite Merge-Nachbedingung, nicht Teil dieses PR-Diffs.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChunkContextTitle.java` existiert im Worktree.

**Themen:** indexing, chunking, retrieval, evaluation, epic-912

---

<a id="issue-937"></a>

## Issue #937 — Query: Zitatvalidierung prüft nur Abruf, nicht Inhalt — falsche Zahl mit gültig wirkendem Zitat
- Geschlossen: 2026-08-26 (completed)
- Labels: bug, enhancement, backend, size:M
- PRs: keine (im Chunk nicht verlinkt — tatsächlich über PR #939 geliefert, siehe Verifikation)

**Laut Issue:** Fachliche Regressionsprüfung der Demo deckte auf: Die Antwort nannte 25,70 € mit Zitat auf `001_personalausweis.md`, obwohl dieser Wert dort nicht steht (er stammt aus einem anderen, ebenfalls abgerufenen Dokument). Die bestehende `CitationValidator` prüft nur, ob ein Zitat auf einen tatsächlich abgerufenen Chunk zeigt, nicht ob die Aussage im Chunk tatsächlich steht. Gefordert: eine deterministische Stufe-1-Faktenprüfung (Zahlen/Beträge/Daten/Paragraphen normalisiert gegen den Chunk-Text vergleichen), die ein Zitat auf `citationValid: false` zurückstuft statt die Antwort zu blockieren; Stufe 2 (LLM-Entailment) optional als Folgeausbau.

**Geliefert:** Neue Klasse `CitationFactChecker` (io.opaa.query) extrahiert harte Fakten aus dem Satz unmittelbar vor einem Zitatmarker (vier Fakttypen: Geld, Datum, Paragraph, sonstige harte Zahl) und vergleicht normalisiert gegen den zitierten Chunk. Konservativ: ein Satz ohne extrahierbaren Fakt wird nie geflaggt, ein bereits ungültiges Zitat wird nie „repariert“. Kein zusätzlicher LLM-Aufruf. Der konkrete Frage-1-Fall (25,70 € vs. 27,20/44,20/12 €) ist als Regressionstest abgesichert. Live-Stichprobe der acht Drehbuch-Fragen auf der Demo war laut PR explizit dem Koordinator nach Deploy überlassen, nicht Teil des PR-Diffs.

**Verifikation:** `backend/src/main/java/io/opaa/query/CitationFactChecker.java` existiert im Worktree.

**Themen:** retrieval, query, zitatvalidierung, qualitätssicherung, epic-912

---

<a id="issue-938"></a>

## Issue #938 — Query: Einschlägige Satzungs-PDF fehlt in den Top-8 — Drehbuch-Frage 6 wird als thomas.klein verweigert
- Geschlossen: 2026-08-27 (completed)
- Labels: bug, backend, size:M, evaluation, demo
- PRs: keine (im Chunk nicht verlinkt — tatsächlich teilweise über PR #942 und PR #943 geliefert, siehe Verifikation)

**Laut Issue:** Fachliche Regressionsprüfung der Demo: `01_verwaltungsgebuehrensatzung.pdf` (§ 3, einschlägige Rechtsgrundlage zur Gebührenbefreiung) erscheint bei keinem Konto in den Top-8-Quellen der Drehbuch-Frage 6. Als `maria.weber` ist die Antwort nur zufällig korrekt (über eine interne Dienstanweisung), als `thomas.klein` (nur Satzungsbibliothek) wird komplett verweigert. Gefordert: Diagnose des tatsächlichen Rankings, dann ein datengetriebener Fix (Extraktions-/Chunking-Korrektur oder moderate `topK`-Anhebung 10–12).

**Geliefert — nur teilweise, mit offen dokumentierter Grenze:** Die Live-Diagnose ergab, dass eine `topK`-Anhebung wirkungslos wäre (Rückstand ~40 Ränge, Score-Abstand ~0,05) und stattdessen #933 (Contextual Chunking) als Fix-Rahmen gewählt wurde. Nach dessen Reindex zeigte sich ein **zweiter, im Issue nicht erwarteter Befund**: Drehbuch-Frage 1 (Personalausweis-Gebühr) lieferte 25,70 € statt 27,20 € wegen eines **Korpus-Datenwiderspruchs** zwischen zwei Dokumenten — behoben durch PR #942 (Korpusdaten angeglichen). Der eigentliche Frage-6-Fall (`thomas.klein`) blieb dagegen **ungelöst**: Die Satzung ist einchunkig und bekommt unter dem Contextual-Chunking-„Split-Gate“ bewusst keinen Präfix, der Rang bleibt bei 50/97. Maintainer-Entscheidung vom 27.08.2026: Issue auf den erfüllten Teil (Frage 1, Frage 6a) reduziert und geschlossen; der `thomas.klein`-Fall wird als bekannte, bewusst nicht weiterverfolgte Grenze der reinen Vektorsuche dokumentiert (PR #943) — eine Hybrid-Suche (BM25 + Vektor) wäre der passende Mechanismus, ist aber nicht beauftragt.

**Verifikation:** `demo/corpus/leistungen-meldewesen-ausweise/002_personalausweis-oder-reisepass-abholen.md` (PR #942) und der Abschnitt „Bekannte offene Schwächen“ in `docs/features/retrieval-algorithm.md` (PR #943) existieren im Worktree.

**Themen:** retrieval, query, demo, korpusdaten, qualitätssicherung, bekannte-grenzen, epic-912

---

<a id="issue-941"></a>

## Issue #941 — CI: Baseline-Absenkungs-Wächter prüft nur comic-characters — city-landmarks bekommt falschen Freispruch
- Geschlossen: 2026-08-27 (completed)
- Labels: bug, size:S, ci, evaluation
- PRs: #944 (2026-08-27)

**Laut Issue:** Review-Fund aus PR #940: `.github/workflows/baseline-diff.yml` und `eval/baseline/diff_baseline.py` waren fest auf `comic-characters.json` verdrahtet. Senkt ein PR nur `city-landmarks.json` ab (wie #940 legitim tat), vergleicht der Wächter nichts und postet trotzdem einen Freispruch — ein falscher Freispruch genau in dem Fall, für den der Wächter existiert. Gefordert: über alle `eval/baseline/*.json`-Dateien iterieren, mit Reproduktionsnachweis (künstlich abgesenkter city-landmarks-Wert muss im Kommentar auftauchen).

**Geliefert:** Beide Dateien iterieren jetzt generisch über jede `eval/baseline/*.json`-Datei; der PR-Kommentar nennt das Ergebnis pro Datei einzeln. Reproduktionsnachweis wie gefordert erbracht (lokal simuliert, da der Workflow nur auf PR-Events läuft): rot mit der alten, fest verdrahteten Version, grün mit der neuen.

**Verifikation:** Im lokal ausgecheckten Worktree-Stand (Commit `5c016998`) ist die Datei `.github/workflows/baseline-diff.yml` noch auf dem **alten, fest verdrahteten** Stand (`comic-characters.json` hartcodiert) — der Worktree liegt einen Commit hinter `origin/main`. Per `git fetch` bestätigt: Der Merge-Commit `1c38b80d` (PR #944) ist auf `origin/main` vorhanden und ist dessen aktuelle Spitze. Der Fix ist also geliefert, nur in diesem lokalen Worktree-Checkout noch nicht sichtbar — kein inhaltlicher Befund, sondern ein veralteter lokaler Checkout.

**Themen:** ci, evaluation, qualitätssicherung, baseline

---

<a id="issue-951"></a>

## Issue #951 — feat(ci): Renovate-PRs mit aktiviertem GitHub-Auto-Merge eröffnen

- Geschlossen: 2026-08-28 (completed)
- Labels: enhancement, ci
- PRs: #952 (2026-08-28)

**Laut Issue:** Maintainer-Anweisung: Renovate soll auf seinen Update-PRs GitHubs natives
„Enable auto-merge" (Squash) aktivieren, damit Update-PRs automatisch mergen, sobald die
Required Checks grün sind; die Renovate-Bot-Ausnahme in AGENTS.md ist entsprechend zu ergänzen.

**Geliefert:** PR #952 setzt `automerge: true` mit `platformAutomerge` und Squash-Strategie in
`renovate.json5` und ergänzt die AGENTS.md-Ausnahme. Die Betriebserfahrung des ersten
Auto-Merge-Tages führte zu Nachschärfungen: Majors vom Auto-Merge ausgenommen (#1002),
npm-Updates gruppiert und Renovate-seitig gemergt (#1000).

**Verifikation:** `renovate.json5` führt `automerge: true`, `platformAutomerge: true`,
`automergeStrategy: 'squash'`; AGENTS.md dokumentiert die Ausnahme.

**Themen:** Renovate, CI, Abhängigkeitsverwaltung, Auto-Merge

---

<a id="issue-954"></a>

## Issue #954 — fix(ci): Renovate schlägt npm-Releases vor, die pnpms minimumReleaseAge noch ablehnt

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, ci
- PRs: #955 (2026-08-28)

**Laut Issue:** pnpm 11 lehnt per Supply-Chain-Standard Pakete ab, die jünger als 24 Stunden
sind. Renovate kannte diese Frist nicht und schlug brandneue Releases vor, die pnpm dann in CI
und Docker-Build verweigerte (`ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION`).

**Geliefert:** PR #955 konfiguriert `minimumReleaseAge: '1 day'` in `renovate.json5`, sodass
Renovate erst Releases vorschlägt, die pnpms Frist bereits bestehen. Für die bereits
eröffneten, vorgezogenen Update-PRs wurde eine befristete CI-Ausnahme gesetzt (PR #1014).

**Verifikation:** `renovate.json5` führt `minimumReleaseAge: '1 day'`.

**Themen:** Renovate, pnpm, Supply-Chain, CI

---

<a id="issue-956"></a>

## Issue #956 — fix(frontend): Branding-Vorschau versteckt fokussierbare Elemente vor Screenreadern

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1012 (2026-08-28)

**Laut Issue:** Befund aus dem Barrierefreiheits-Abschluss-Audit (#598, axe-Regel
`aria-hidden-focus`, Schweregrad hoch): Die mit `aria-hidden` ausgeblendeten Vorschau-Panels
der Branding-Seite enthalten fokussierbare Elemente — Tastaturnutzer tabben in Inhalte, die für
Screenreader nicht existieren.

**Geliefert:** PR #1012 nimmt die Branding-Vorschau per `inert` vollständig aus der
Tab-Reihenfolge, sodass Fokusreihenfolge und Accessibility-Baum wieder übereinstimmen.

**Verifikation:** Commit `066b8e97` auf `main`; `BrandingPreview.tsx` verwendet `inert`.

**Themen:** Barrierefreiheit, Audit-Befund, Branding

---

<a id="issue-957"></a>

## Issue #957 — fix(frontend): Rollen-Chips unterschreiten im Dunkelschema den Mindestkontrast

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1017 (2026-08-28)

**Laut Issue:** Befund aus dem Barrierefreiheits-Abschluss-Audit (#598, axe-Regel
`color-contrast`, Schweregrad hoch): Die Chips „Administrator" (Spaces-Übersicht) und
„Eigentümer" (Wissensbibliotheken) unterschreiten im Dunkelschema den Mindestkontrast von
4,5:1 (WCAG 1.4.3).

**Geliefert:** PR #1017 macht die Rollen-Badges schemafest: Farben über das Theme
(`primary.main`) statt hart codierter Palette (`blue[700]`), womit beide Schemata die
Kontrastanforderung erfüllen.

**Verifikation:** Commit `9acdc3da` auf `main`.

**Themen:** Barrierefreiheit, Audit-Befund, Kontrast, Dunkelmodus

---

<a id="issue-958"></a>

## Issue #958 — fix(frontend): Übersprungene Überschriftenebenen auf Chat-, Branding- und Modelle-Seite

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1015 (2026-08-28)

**Laut Issue:** Befund aus dem Barrierefreiheits-Abschluss-Audit (#598, axe-Regel
`heading-order`, Schweregrad mittel): Auf Chat-Leerzustand, Branding- und Modelle-Seite wird
eine Überschriftenebene übersprungen (WCAG 1.3.1).

**Geliefert:** PR #1015 korrigiert die Überschriftenhierarchie der drei Seiten (semantische
Ebene von der Typography-Variante entkoppelt). Der beim Review entdeckte, datenabhängige
Folgefall in Chat-Antworten wurde als #1016 nachgezogen.

**Verifikation:** Commit `ee01047d` auf `main`.

**Themen:** Barrierefreiheit, Audit-Befund, Überschriftenstruktur

---

<a id="issue-959"></a>

## Issue #959 — fix(frontend): Fokus geht nach Escape beim Inline-Umbenennen eines Chats verloren

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #968 (2026-08-28)

**Laut Issue:** Befund aus dem Tastatur-Durchgang des Abschluss-Audits (#598, Schweregrad
niedrig): Nach Escape (und nach Enter) beim Inline-Umbenennen eines Chats fällt der Fokus auf
`document.body` statt zum auslösenden Element zurückzukehren — Tastaturnutzer müssen sich von
vorn durch die Seite tabben.

**Geliefert:** PR #968 lässt den Fokus nach Abbruch oder Commit zur Aktionen-Schaltfläche des
Chats zurückkehren, wie es die Checkliste in `docs/design/accessibility.md` (2.1) verlangt.

**Verifikation:** Commit `fc935fe5` auf `main`.

**Themen:** Barrierefreiheit, Audit-Befund, Fokusführung

---

<a id="issue-966"></a>

## Issue #966 — test(backend): Redirect-/Downloader-Tests binden 127.0.0.2 und scheitern auf macOS

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, backend, size:S
- PRs: #1018 (2026-08-28, gemeinsam mit #611)

**Laut Issue:** Beim lokalen Durchlauf der Pre-Push-Checkliste auf macOS schlagen 14
Backend-Tests mit `BindException` fehl (`RedirectFollowingFetcherTest`, `BoundedDownloaderTest`,
Teile von `RssFeedIndexingExecutorTest` und `LibraryDocumentServiceIntegrationTest`): Sie
starten einen „fremden Host" als lokalen `HttpServer` auf `127.0.0.2`, das macOS standardmäßig
nicht bindet.

**Geliefert:** PR #1018 bindet die Fremdhost-Testserver portabel als `localhost` (gemeinsame
Behebung mit #611).

**Verifikation:** Commit `aeee12b2` auf `main`.

**Themen:** Testinfrastruktur, Portabilität, macOS

---

<a id="issue-996"></a>

## Issue #996 — fix(deps): pnpm-Lockfile auf main nach Renovate-Auto-Merge-Serie gebrochen — Frontend-CI komplett rot

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S, ci
- PRs: #1003 (2026-08-28, gemeinsam mit #1001)

**Laut Issue:** Mehrere Lockfile-ändernde npm-Update-PRs mergten per Auto-Merge nacheinander,
ohne dass die späteren gegen den neuen Stand rebased waren. Die textuell konfliktfreie
Vereinigung der `pnpm-lock.yaml` war semantisch inkonsistent
(`ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY`); der Frontend-CI-Job war für jeden PR und main-Push
rot.

**Geliefert:** PR #1003 stellt `main` wieder her (Lockfile neu erzeugt, zusammen mit dem
Temurin-Revert aus #1001). Die strukturelle Absicherung gegen Wiederholung lieferte #1000
(npm-Updates gruppieren, Renovate-seitiger Merge mit Rebase).

**Verifikation:** Commit `93ab40f3` auf `main`; Frontend-CI seither grün.

**Themen:** Renovate, pnpm, Lockfile, CI-Ausfall

---

<a id="issue-997"></a>

## Issue #997 — chore(ci): Renovate den Gradle-Wrapper-Befehl erlauben (allowedUnsafeExecutions)

- Geschlossen: 2026-08-28 (completed)
- Labels: ci
- PRs: #998 (2026-08-28)

**Laut Issue:** Beim Gradle-Update aktualisierte Renovate nur `gradle-wrapper.properties`,
nicht die Wrapper-Skripte/JAR, weil `gradleWrapper` nicht in den `allowedUnsafeExecutions`
freigegeben war — der Wrapper blieb inkonsistent zur deklarierten Version.

**Geliefert:** PR #998 setzt `RENOVATE_ALLOWED_UNSAFE_EXECUTIONS` mit `gradleWrapper` im
Workflow und zieht `docs/renovate.md` nach.

**Verifikation:** Commit `6ab92f59` auf `main`; das anschließende Gradle-Update (#973)
aktualisierte den Wrapper vollständig.

**Themen:** Renovate, Gradle, CI

---

<a id="issue-1000"></a>

## Issue #1000 — ci(renovate): Gleichzeitige Lockfile-Updates gegen semantische Merge-Brüche absichern

- Geschlossen: 2026-08-28 (completed)
- Labels: enhancement, size:S, ci
- PRs: #1008 (2026-08-28)

**Laut Issue:** Der Lockfile-Bruch #996 wiederholt sich potenziell an jedem Renovate-Tag mit
mindestens zwei gleichzeitig grünen npm-Updates — die Konfiguration muss gleichzeitige
Lockfile-Änderungen strukturell absichern.

**Geliefert:** PR #1008 gruppiert npm-non-major-Updates zu einem Sammel-PR (`groupName:
'npm (non-major)'`) und stellt für Lockfile-ändernde Updates auf Renovate-seitigen Merge um
(`platformAutomerge: false` für die Gruppe), sodass Renovate vor dem Merge gegen den aktuellen
Stand rebased.

**Verifikation:** `renovate.json5` führt die npm-Gruppierung und den abweichenden Merge-Weg.

**Themen:** Renovate, pnpm, Lockfile, CI-Härtung

---

<a id="issue-1001"></a>

## Issue #1001 — fix(build): Backend-Dockerfile zurück auf Temurin 21 — Renovate-Major #988 bricht Image-Build

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, ci
- PRs: #1003 (2026-08-28, gemeinsam mit #996)

**Laut Issue:** Der Automerge von #988 (`eclipse-temurin` 21→25 im Backend-Dockerfile) brach
E2E und Publish Images auf `main`: Die Gradle-Toolchain pinnt Java 21, im
`eclipse-temurin:25-jdk`-Image ist nur JDK 25 vorhanden, Auto-Provisioning ist aus. `e2e` ist
kein Required Check, daher hielt nichts den Merge auf.

**Geliefert:** PR #1003 setzt das Backend-Dockerfile zurück auf `eclipse-temurin:21` (zusammen
mit der Lockfile-Reparatur aus #996). Die strukturelle Konsequenz — Majors nicht mehr
auto-mergen — lieferte #1002.

**Verifikation:** Commit `93ab40f3` auf `main`; `backend/Dockerfile` referenziert Temurin 21,
mit Begründungskommentar in `renovate.json5`.

**Themen:** Renovate, Docker, Java-Toolchain, CI-Ausfall

---

<a id="issue-1002"></a>

## Issue #1002 — fix(ci): Auto-gemergtes temurin-v25-Major bricht Backend-Image-Build — Majors vom Auto-Merge ausnehmen

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, backend, size:S, ci
- PRs: #1004 (2026-08-28)

**Laut Issue:** Das Major-Update #988 gelangte ungeprüft per Auto-Merge in `main` und brach den
Image-Build deterministisch. Major-Updates brauchen eine bewusste Freigabe statt Auto-Merge.

**Geliefert:** PR #1004 nimmt Major-Updates in `renovate.json5` vom Auto-Merge aus
(`automerge: false` für Majors); Majors bleiben als PR liegen, bis ein Maintainer sie prüft.

**Verifikation:** `renovate.json5` führt die Major-Ausnahme mit Begründungskommentar.

**Themen:** Renovate, Auto-Merge, Major-Updates, CI-Härtung

---

<a id="issue-1005"></a>

## Issue #1005 — chore(ci): Tika-4-Major in Renovate aussetzen — inkompatibel zu Spring AIs Tika-3-Parsern

- Geschlossen: 2026-08-28 (completed)
- Labels: backend, ci
- PRs: #1006 (2026-08-28, gemeinsam mit #1007)

**Laut Issue:** Renovate-PR #992 hob nur `tika-core` auf 4.0.0; `tika-parsers` kommt transitiv
über die Spring-AI-BOM und bleibt auf 3.x — die Kombination bricht die Format-Erkennung breit
(backend- und e2e-Job rot).

**Geliefert:** PR #1006 deaktiviert das Tika-Major per packageRule (Minor/Patch in 3.x laufen
weiter) mit Begründungskommentar und Wiedervorlage: Regel entfernen, sobald Spring AI seine
Tika-Abhängigkeit auf 4.x hebt.

**Verifikation:** `renovate.json5` führt die Tika-Regel samt Begründung.

**Themen:** Renovate, Tika, Spring AI, Abhängigkeitsverwaltung

---

<a id="issue-1007"></a>

## Issue #1007 — chore(ci): TypeScript-7-Major in Renovate aussetzen — typescript-eslint unterstützt TS 7.0 nicht

- Geschlossen: 2026-08-28 (completed)
- Labels: frontend, ci
- PRs: #1006 (2026-08-28, gemeinsam mit #1005)

**Laut Issue:** Renovate-PR #995 (TypeScript 7.0.2) scheiterte im frontend-Job:
`typescript-eslint` 8.68.0 bricht mit „typescript-eslint does not support TS 7.0" ab
(Upstream-Support ab TS ≥ 7.1 geplant).

**Geliefert:** PR #1006 deaktiviert das TypeScript-Major per packageRule (6.x-Updates laufen
weiter) mit Begründungskommentar, Upstream-Verweis und Wiedervorlage.

**Verifikation:** `renovate.json5` führt die TypeScript-Regel samt Upstream-Tracking-Verweis.

**Themen:** Renovate, TypeScript, typescript-eslint, Abhängigkeitsverwaltung

---

<a id="issue-1016"></a>

## Issue #1016 — fix(frontend): Markdown-Überschriften in Chat-Antworten pro Nachricht auf gültige Ebenen normalisieren

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1019 (2026-08-28)

**Laut Issue:** Beim Review von #958 bestätigter, vorbestehender Befund derselben Klasse:
Der `MarkdownRenderer` bildet Markdown-Überschriften aus Assistenten-Antworten auf tiefe
Heading-Elemente ab (`#` → `h5` usw.), wodurch datenabhängig Ebenen übersprungen werden —
weder vom Abschluss-Audit (prüfte den Leerzustand) noch von #1015 erfasst.

**Geliefert:** PR #1019 normalisiert die Überschriften je Nachricht über ein
rehype-Plugin (`rehypeNormalizeHeadings`): Die Rang-Folge wird pro Nachricht auf eine gültige,
lückenlose Ebenenfolge komprimiert, die visuelle Größe bleibt von der semantischen Ebene
entkoppelt.

**Verifikation:** Commit `a4dac7e2` auf `main`; `MarkdownRenderer.tsx` nutzt
`rehypeNormalizeHeadings` aus `markdownHeadings`.

**Themen:** Barrierefreiheit, Markdown, Chat, Überschriftenstruktur

---

<a id="issue-1022"></a>

## Issue #1022 — Recherche: Agent-Loop, Frameworks und Laufzeitumgebung für Phase-2-Agenten dokumentieren

- Geschlossen: 2026-08-30 (completed)
- Labels: documentation, size:M
- PRs: #1024 (2026-08-30)

**Laut Issue:** Für Phase 2 (nutzerdefinierte Agenten und Skills als teilbare Assets) fehlte
eine technische Grundlagenrecherche: Wie implementieren führende Agentensysteme (Claude
Code/Agent SDK, OpenAI Codex/Agents SDK, Gemini CLI/ADK) Agent Loop und Ausführungsumgebung,
was bietet das Java-/Spring-Ökosystem (Spring AI 2.0, Embabel, LangGraph4j, MCP), und welche
Laufzeit-/Sandbox-Architektur ist für den On-Prem-Compose-Betrieb realistisch?

**Geliefert:** PR #1024 legt zwei Diskussionsdokumente unter `docs/discussions/` ab
(Agenten-Architektur; Laufzeitumgebung und Sandboxing) — Entscheidungsgrundlage, noch keine
Festlegung.

**Verifikation:** `docs/discussions/discussion-agenten-architektur-opaa.md` und
`discussion-agenten-laufzeitumgebung-und-sandboxing.md` vorhanden.

**Themen:** Phase 2, Agenten, Recherche, Architektur

---

<a id="issue-1023"></a>

## Issue #1023 — Recherche: Retrieval-Strategien für OPAA — Tech-Report, Roadmap und Dateityp-/Metadaten-Konzept

- Geschlossen: 2026-08-30 (completed)
- Labels: documentation, size:M, evaluation
- PRs: #1025 (2026-08-30)

**Laut Issue:** Systematische Recherche über den Stand der Retrieval-/RAG-Strategien
(2025/2026), Abgleich mit Konkurrenz- und Referenzsystemen (GraphRAG/LazyGraphRAG, Azure AI
Search, RAGFlow, Onyx, LightRAG u. a.) und Herunterbrechung auf Einsatzszenarien der
öffentlichen Verwaltung — als Entscheidungsgrundlage für die nächste Retrieval-Ausbaustufe.

**Geliefert:** PR #1025 legt den Tech-Report
(`docs/discussions/discussion-retrieval-strategien.md`) und die abgeleitete Roadmap mit
Dateityp-/Metadaten-Konzept (`discussion-retrieval-roadmap-opaa.md`) ab.

**Verifikation:** Beide Dokumente unter `docs/discussions/` vorhanden.

**Themen:** Retrieval, RAG, Recherche, Roadmap

---

<a id="pr-1"></a>

## PR #1 — Add collaboration workflow for humans and AI agents
- Gemergt: 2026-02-16
- Bezug: keiner

**Geliefert:** Grundlegende Projektkonventionen für die Zusammenarbeit von Menschen und KI-Agenten: `AGENTS.md` und `CLAUDE.md`, `CONTRIBUTING.md` mit Branch-Namens- und Commit-Konventionen sowie KI-Offenlegungspflicht, GitHub-Issue- und PR-Templates, `docs/decisions/` mit ADR-Vorlage und erstem ADR sowie `docs/features/` mit Feature-Spec-Vorlage.

**Verifikation:** `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `README.md`, `docs/decisions/0001-collaboration-workflow.md` und `.github/PULL_REQUEST_TEMPLATE.md` sind im heutigen Stand vorhanden, wenn auch seither vielfach weiterentwickelt (AGENTS.md deutlich erweitert, PR-Template mit CLA-Checkpunkt ergänzt).

**Themen:** projektsetup, doku, agenten-workflow, cla-vorbereitung, github-templates

---

<a id="pr-32"></a>

## PR #32 — docs: add product pitch one-pager (DE + EN)
- Gemergt: 2026-02-20
- Bezug: keiner

**Geliefert:** Gestaltete One-Pager-Flyer für OPAA in Deutsch und Englisch (HTML + PDF) im dunklen A4-Layout mit UI-Screens, fünf Nutzenversprechen und SaaS-Vergleichstabelle, dazu eine Markdown-Fassung des Pitches (`docs/PITCH.md`) und hochauflösende Design-Screenshots.

**Verifikation:** `docs/OPAA-pitch-de.html`/`.pdf` sowie ein Design-Screenshot sind noch vorhanden. `docs/OPAA-pitch-en.html` wurde inzwischen entfernt (Commit `ab51bfda`, „Landing-Page, Pitch und One-Pager auf den Verwaltungston umstellen"), `docs/PITCH.md` ebenfalls entfernt (Commit `73dec48d`, „remove markdown pitch document"). Der deutsche Pitch existiert also fort, die englische Fassung und die Markdown-Version sind spätestens im Zuge der Neuausrichtung auf öffentliche Verwaltung obsolet geworden.

**Themen:** marketing, pitch, doku, mehrsprachigkeit

---

<a id="pr-91"></a>

## PR #91 — docs: add architecture discussion documents
- Gemergt: 2026-03-01
- Bezug: keiner

**Geliefert:** Drei Diskussionsdokumente unter `docs/discussions/`: zu Embedding-Strategien, RAG-Evaluierungsmethoden und Retrieval-/Dokument-Pipelines. Reine Konzeptdokumentation ohne Codeänderung.

**Verifikation:** Alle drei Dateien (`discussion-embeddings.md`, `discussion-rag-evaluation.md`, `discussion-retrieval-document-pipelines.md`) sind im heutigen Stand vorhanden.

**Themen:** doku, architektur, rag, embeddings, diskussion

---

<a id="pr-92"></a>

## PR #92 — docs: enhance project rules and add MVP status documentation
- Gemergt: 2026-03-02
- Bezug: keiner

**Geliefert:** Projektregel-Ergänzungen (Issues müssen auf Englisch verfasst werden, Pre-Push-Checkliste überspringt Tests bei reinen Doku-Änderungen), Korrektur eines hartkodierten Agenten-Speicherpfads, sowie zwei neue Dokumente: `docs/MVP-STATUS.md` (Statusübersicht je Feature-Bereich mit Roadmap) und `docs/discussions/discussion-token-cost-optimization.md` (Strategien zur Token-Kostenreduktion).

**Verifikation:** `docs/discussions/discussion-token-cost-optimization.md` existiert noch. `docs/MVP-STATUS.md` ist entfernt worden (Commit `0475a4a1`, „Einstieg und Umsetzungsstand auf die neue Ausrichtung angleichen"). Die Datei `.claude/agents/coding-standards-reviewer.md` gibt es ebenfalls nicht mehr — sie wurde zum vollwertigen `code-reviewer`-Agenten ausgebaut (Commit `a5e8bd3c`). Die Regel „Issues auf Englisch" widerspricht zudem der heute geltenden Projektsprache-Vorgabe (Deutsch für Issues/PRs) aus dem aktuellen `AGENTS.md` — offenbar später revidiert.

**Themen:** doku, projektregeln, mvp-status, agenten-konfiguration, token-kosten

---

<a id="pr-97"></a>

## PR #97 — docs: workspace concept discussion document
- Gemergt: 2026-03-06
- Bezug: keiner

**Geliefert:** Diskussionsdokument zum OPAA-Workspace-Modell: flaches Workspace-Modell ohne Hierarchie, Workspace-zu-Workspace-Dokumentenfreigabe, Connector-basierte Quellen-Zuordnung, neue System-Admin-Rolle. Ergänzt `docs/CONCEPTS.md` und legt Feature-Spec-Entwürfe (`access-control-workspaces.md`, `data-indexing-rag.md`, `document-sharing.md`) an.

**Verifikation:** `docs/CONCEPTS.md` und `docs/features/document-sharing.md` existieren weiterhin. Das Workspace-Modell selbst ist jedoch überholt: `docs/discussions/discussion-workspace-concept.md` wurde entfernt (Commit `15e2061f`, „abgelöstes Diskussionsdokument zum Workspace-Konzept entfernen"), `docs/features/access-control-workspaces.md` wurde im Zuge des Space-und-Asset-Modells (PR #217) zu `access-control.md`. Der Workspace-Begriff selbst wurde später projektweit durch „Space" ersetzt (siehe PR #146/#147 und den Commit „Workspace in Space umbenennen").

**Themen:** doku, workspace, konzeption, rollenmodell, veraltet

---

<a id="pr-104"></a>

## PR #104 — chore: add CLA and switch license to AGPL-3.0
- Gemergt: 2026-03-06
- Bezug: keiner

**Geliefert:** Contributor License Agreement (`CLA.md`) mit Sublizenzierungsrechten für ein Dual-Lizenz-Modell (Open Core + Managed SaaS), CLA-Assistant-GitHub-Action, Lizenzwechsel von Apache 2.0 zu AGPL-3.0 projektweit inklusive aller Lizenzverweise in README, OpenAPI-Spec, Pitch-Seiten, Landingpage, PR-Template und AGENTS.md.

**Verifikation:** `CLA.md`, `LICENSE`, `.github/workflows/cla.yml` und `page/index.html` sind im heutigen Stand vorhanden. Die Lizenzentscheidung (AGPL-3.0) gilt fort.

**Themen:** cla, lizenz, monetarisierung, rechtliches

---

<a id="pr-105"></a>

## PR #105 — fix: store CLA signatures on unprotected branch
- Gemergt: 2026-03-06
- Bezug: keiner

**Geliefert:** Fix für den CLA-Bot: Signaturen wurden zuvor auf `main` geschrieben, was an der Branch-Protection scheiterte. Umstellung auf eine dedizierte `cla-signatures`-Branch.

**Verifikation:** `.github/workflows/cla.yml` existiert weiterhin und trägt den CLA-Mechanismus aus PR #104 fort.

**Themen:** cla, ci, bugfix

---

<a id="pr-146"></a>

## PR #146 — Codex/111 workspace and membership
- Gemergt: 2026-03-07
- Bezug: #111

**Geliefert:** Backend-Grundgerüst für Workspaces und Mitgliedschaften: `WorkspaceController`, DTOs für Mitglieder hinzufügen/Rollen ändern/Eigentümerschaft übertragen, `WorkspaceMembership`-Entität, `WorkspaceRepository`, `WorkspaceService` sowie zugehörige Tests. Der PR-Body ist leer (unausgefülltes Template); Umfang ergibt sich aus der Dateiliste.

**Verifikation:** Sämtliche gelieferten Dateien existieren im heutigen Code nicht mehr unter diesen Namen — das Workspace-Modell wurde per Commit `75abc6d3` („feat(space)!: Workspace in Space umbenennen, Organisationsgrenze und neue Space-Rollen einführen") vollständig in das Space-Modell überführt. Die fachliche Funktion (Mitgliedschaft, Rollen, Eigentümerschaft) lebt heute in `io.opaa.space.*` fort, der PR selbst ist als Workspace-Artefakt Geschichte.

**Themen:** backend, workspace, mitgliedschaft, veraltet, umbenennung

---

<a id="pr-147"></a>

## PR #147 — fix(workspace): restore listMembers to use loaded memberships
- Gemergt: 2026-03-07
- Bezug: #131, #140, #146

**Geliefert:** Schneller Folgefix auf `main`: `listMembers()` in `WorkspaceService` nutzte eine per PR #131 (N+1-Fix-Review) entfernte Repository-Methode und brach den Build, nachdem PR #140 (`listMembers` eingeführt) und #146 (N+1-Fix) beide gemergt waren. Korrektur: `workspace.getMemberships()` (bereits JOIN-fetched) statt separater Repository-Abfrage verwenden.

**Verifikation:** `WorkspaceService.java` existiert nicht mehr — wie bei PR #146 durch die Umbenennung Workspace→Space (Commit `75abc6d3`) abgelöst. Der Fix selbst war ein punktueller Build-Reparatur-Commit ohne dauerhaften eigenständigen Bestand.

**Themen:** backend, workspace, bugfix, build-reparatur, veraltet

---

<a id="pr-217"></a>

## PR #217 — docs: replace workspace model with space and asset model
- Gemergt: 2026-08-02
- Bezug: #198, #216, #237, #238, #239, #240, #241, #242, #243

**Geliefert:** Ablösung des Workspace-Modells durch das Space-und-Asset-Modell: neue Spezifikation `docs/features/spaces-and-assets.md` und ADR `docs/decisions/0008-space-and-asset-model.md` (inkl. Vergleich mit Confluence, Notion, Langdock, Glean). Abgeglichen wurden `access-control-workspaces.md` (umbenannt zu `access-control.md`, reduziert auf Systemadministration/Identität/Audit), `document-sharing.md` (als überholt markiert), `data-indexing-rag.md`, `CONCEPTS.md`, `discussion-workspace-concept.md`, `INDEX.md`, `GETTING-STARTED.md`, `VISION.md`. Enthält zwei Nachträge nach Stakeholder-Reviewrunden (Ablage-Modell für Chats/Artefakte, Sentinel-Fehlerbehandlung bei Voraussetzungsbrüchen, Offboarding-Regeln). Notierter Realitätsbefund: Die permission-aware Vektorsuche existierte zu dem Zeitpunkt nicht im Code.

**Verifikation:** `docs/features/spaces-and-assets.md` und `docs/features/access-control.md` sind vorhanden. `docs/decisions/0008-space-and-asset-model.md` wurde inzwischen entfernt (Commit `bd7b4257`, „ADR-Bestand entschlacken und auf den tatsächlichen Stand bringen" — Begründung: „ADR-0008 entfällt, der Entscheidungsteil erzählte die Spezifikation nach"). `docs/discussions/discussion-workspace-concept.md` wurde ebenfalls entfernt (Commit `15e2061f`). Die im PR-Body genannten Dateien `ablegt.` und `Ein` in der Dateiliste sind offensichtliche Parsing-Artefakte aus abgeschnittenem Fließtext, keine echten Repository-Pfade.

**Themen:** doku, space-modell, asset-modell, adr, architektur, berechtigungen

---

<a id="pr-236"></a>

## PR #236 — docs: Spezifikation und ADR zur Suchqualitäts-Evaluierung
- Gemergt: 2026-08-02
- Bezug: #115, #117, #224, #225, #226, #227, #228, #229, #230, #231, #232, #233, #234, #235, #244

**Geliefert:** Feature-Spezifikation und ADR (`docs/decisions/0008-search-quality-evaluation-harness.md`, damals Status „Akzeptiert") für die Suchqualitäts-Evaluierung: ein eingefrorener, lizenzsauberer Testkorpus speist sowohl eine öffentlich vorzeigbare OPAA-Demo als auch einen automatisierten Retrieval-Regressionstest in CI. Enthält Korrekturen an drei Realitäts-Annahmen (nginx statt Apache-Autoindex, „vier Domänen = vier Workspaces" nicht umsetzbar, kein E2E-Grundgerüst) sowie den zweiten Commit mit Maintainer-Entscheidungen (Phase-1-Quelle `jrtec/Superheroes` statt FiveThirtyEight, gemeinsamer Index mit Dateinamen-Präfix, Demo auf `opaa.ewerlin.com`).

**Verifikation:** `docs/features/search-quality-evaluation.md` existiert weiterhin. Der ADR ist nicht mehr unter `0008` zu finden — er wurde per PR #264 auf `0011-search-quality-evaluation-harness.md` umnummeriert (0008 ist inzwischen anderweitig vergeben bzw. entfernt, siehe PR #217). Inhaltlich wurde die Spezifikation seither mehrfach berichtigt (PR #253, #269, #275) — insbesondere die hier getroffene Annahme „Ollama-Konfiguration, kein Kostenrisiko" für die Demo-Instanz stellte sich als falsch heraus.

**Themen:** eval, suchqualitaet, adr, doku, korpus, ci

---

<a id="pr-253"></a>

## PR #253 — docs(eval): Demo-Instanz auf OpenAI und Account-Bindung korrigieren
- Gemergt: 2026-08-02
- Bezug: #224, #230, #244, #247, #250, #252

**Geliefert:** Zwei Korrekturen an der Suchqualitäts-Spezifikation, die über PR #247 fehlerhaft auf `main` gelangt waren: (1) Die Demo-Instanz nutzt tatsächlich OpenAI statt Ollama, mit der Folge, dass Ausgabenlimit und Rate Limiting zur Betriebsanforderung werden; (2) kein anonymer Lesezugriff auf die Suche — die Instanz bleibt account-gebunden hinter Keycloak. ADR-0008 erhält dazu einen sichtbar gekennzeichneten Nachtrag statt einer stillen Umschreibung. Der PR-Body trägt am Ende den Hinweis „Nicht mergen — zur Prüfung durch den Maintainer", wurde laut Datensatz aber dennoch gemergt.

**Verifikation:** `docs/features/search-quality-evaluation.md` existiert weiterhin, ebenso wie ein ADR zur Suchqualitäts-Evaluierung (heute als `0011-search-quality-evaluation-harness.md`). Die hier vorgenommene Korrektur „Demo läuft auf OpenAI" erwies sich ihrerseits als unvollständig: PR #269/#275 stellten richtig, dass das Chat-Modell tatsächlich Anthropic (`claude-haiku-4-5`) über eine OpenAI-kompatible Schicht ist. Diese PR war damit ein Zwischenstand in einer mehrstufigen Korrekturkette, kein Endzustand.

**Themen:** eval, suchqualitaet, doku, korrektur, demo-instanz, adr

---

<a id="pr-269"></a>

## PR #269 — docs: Betriebsfakten der Testinstanz ergänzen und Modellkonfiguration berichtigen
- Gemergt: 2026-08-02
- Bezug: #230, #234, #244, #247, #251, #264, #267, #273

**Geliefert:** Ergänzt bestätigte Betriebsfakten zur Testinstanz `opaa.ewerlin.com` in `docs/deployment.md` und berichtigt die Modellkonfiguration: Chat-Modell ist `claude-haiku-4-5` von Anthropic über eine OpenAI-kompatible Schicht, nicht OpenAI selbst; Embeddings laufen lokal über Ollama (`nomic-embed-text`). Nimmt eine frühere ADR-Aussage zurück (CI und Instanz betten angeblich unterschiedlich ein — das war falsch, beide sind identisch konfiguriert). Dokumentiert Korpus-Einspielung, Update-Verfahren (Cron, GHCR-Images, kein Repo-Checkout), Netzwerkaufbau und einen Sicherheitshinweis zu `POST /api/v1/indexing/trigger`. Ergänzt außerdem eine domänenunabhängige Sentinel-Regel für die Ground Truth der Suchqualitäts-Evaluierung.

**Verifikation:** `docs/deployment.md` existiert weiterhin und trägt diese Inhalte fort. Laut PR #275 wurde von diesem PR jedoch nur der erste Commit (Betriebsfakten in `docs/deployment.md`) tatsächlich gemergt — die inhaltliche Modellkorrektur und die Sentinel-Regel waren zum Merge-Zeitpunkt noch nicht enthalten und wurden erst mit PR #275 nachgereicht. Die Dateiliste dieses Chunks zeigt entsprechend nur `docs/deployment.md`, nicht die Sentinel-Regel-Datei.

**Themen:** doku, deployment, betrieb, demo-instanz, modellkonfiguration, eval

---

<a id="pr-275"></a>

## PR #275 — docs: Modellkonfiguration der Testinstanz berichtigen und Sentinel-Regel für die Ground Truth ergänzen
- Gemergt: 2026-08-02
- Bezug: #230, #234, #244, #264, #267, #269, #273

**Geliefert:** Nachfolger von PR #269, rebased auf `main` nach dem Merge von #264 (ADR-Umnummerierung) und #273 (Golden Dataset). Bringt die zwei Teile nach, die von #269 nicht gemergt wurden: (1) Berichtigung der Modellkonfiguration der Demo-Instanz (Anthropic `claude-haiku-4-5` statt OpenAI, lokales Ollama-Embedding), inklusive Rücknahme der falschen ADR-Aussage zu unterschiedlicher Einbettungskonfiguration zwischen CI und Instanz; (2) die generelle Sentinel-Regel für die Ground Truth (numerische Golden Queries schließen Entitäten mit Werten außerhalb der Skala feldbezogen aus). Stellt zudem Verweise nach der ADR-Umnummerierung 0008→0011 richtig.

**Verifikation:** `docs/decisions/0011-search-quality-evaluation-harness.md`, `docs/deployment.md` und `docs/features/search-quality-evaluation.md` sind im heutigen Stand vorhanden und tragen diese Inhalte.

**Themen:** doku, eval, korpus, demo-instanz, modellkonfiguration, ground-truth

---

<a id="pr-287"></a>

## PR #287 — fix(auth): Erstanmeldung nach #280 wieder funktionsfähig machen
- Gemergt: 2026-08-02
- Bezug: #201, #202, #265, #280

**Geliefert:** Behebt eine Regression aus PR #280: Nach dessen Merge scheiterte jede Erstanmeldung, weil `UserService.findOrCreateUser` (`@Transactional`) die `users`-Zeile in einer noch offenen Transaktion anlegte, bevor `SpaceService.ensurePersonalSpace` in einer eigenen `REQUIRES_NEW`-Transaktion darauf zugriff und die Zeile auf der separaten Connection noch nicht sichtbar war — Verletzung von `fk_spaces_owner`. Fix: `ensurePersonalSpace` wird per `TransactionSynchronization#afterCommit` erst nach dem Commit der äußeren Transaktion aufgerufen. Bringt einen dedizierten Integrationstest mit echtem Postgres und Liquibase-Schema (`UserServicePersonalSpaceIntegrationTest`), der die Regression nachweislich reproduziert. Merkt zusätzlich einen blinden Fleck bei FK-abhängigen Tests mit `ddl-auto=create-drop` als Vorschlag für ein separates Issue an (nicht selbst angelegt).

**Verifikation:** `UserService.java`, `SpaceService.java` und `UserServicePersonalSpaceIntegrationTest.java` sind im heutigen Stand vorhanden.

**Themen:** backend, auth, bugfix, transaktionen, tests, regression

---

<a id="pr-385"></a>

## PR #385 — feat(report): Blättern zwischen Berichtstagen und feste Adresse für den aktuellen Tag
- Gemergt: 2026-08-14
- Bezug: #383

**Geliefert:** Der PR-Body im Chunk-Datensatz ist nur der Platzhalter „@-"; laut `gh pr view` ist auch die tatsächliche PR-Beschreibung leer. Aus den Commit-Nachrichten ergibt sich der Umfang: Der Kopf des Tagesreports zeigt jetzt Links zum vorherigen/nächsten Berichtstag (übersprungen werden Tage ohne Bewegung), und `report/latest.html` ist eine feste, sich mitziehende Adresse zum jüngsten Report (client-seitige Weiterleitung, da GitHub Pages keine serverseitige Weiterleitung bietet). Beide Commits schließen Issue #383. Geändert: `daily_report.py`, `test_daily_report.py`, `docs/tagesreport.md`.

**Verifikation:** Alle drei gelieferten Dateien sind im heutigen Stand vorhanden.

**Themen:** tagesreport, tooling, github-pages, ci-skript

---

<a id="pr-399"></a>

## PR #399 — docs(marketing): Produktnamen in der Marketing-Rolle durch Gattungen ersetzen
- Gemergt: 2026-08-14
- Bezug: #224, #338, #370, #382

**Geliefert:** Zwei zusammengehörige Dokumentationsänderungen: (1) In `agents/roles/marketing.md` werden konkrete Wettbewerbsprodukte durch Gattungsbegriffe ersetzt, gemäß der in #382 in `MESSAGING.md` festgelegten Regel „Namen als Arbeitsanweisung an interne Rollen ja, zur Positionierung nein" — bei der Marketing-Rolle landen Namen absehbar im nach außen gerichteten Text, deshalb raus. (2) `docs/market/MESSAGING.md` erhält einen Hinweis auf veraltete Bildsprache der Landing-Page-Screenshots (firmenorientiert statt Verwaltungskontext); #370 bleibt bewusst zurückgestellt, bis ein Verwaltungskorpus vorliegt.

**Verifikation:** `agents/roles/marketing.md` und `docs/market/MESSAGING.md` sind im heutigen Stand vorhanden.

**Themen:** marketing, doku, messaging, positionierung, agenten-rollen

---

<a id="pr-403"></a>

## PR #403 — fix(docs): Doppelte Tabellenzeilen in ADR-0014 entfernen
- Gemergt: 2026-08-14
- Bezug: #349, #379

**Geliefert:** Behebt einen Merge-Konflikt-Fehler beim Auflösen von PR #379: Ein automatischer Konfliktauflöser hatte eine Tabellenzeile wie einen additiv zusammenzuführenden Nachtragseintrag behandelt, wodurch in `docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md` drei Tabellenzeilen doppelt standen und eine Leerzeile die Tabelle zerriss. Korrigiert die Tabelle auf fünf eindeutige Zeilen (vier entschieden, Plugin-Architektur bleibt offen wie in #349 festgelegt) und passt das verwendete Auflöse-Verfahren an (Abbruch bei Tabellenzeilen-Konflikten statt additivem Merge).

**Verifikation:** `docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md` existiert im heutigen Stand.

**Themen:** doku, adr, bugfix, merge-konflikt, tooling

---

<a id="pr-412"></a>

## PR #412 — fix(indexing): Bibliothekszuordnung in den Chunk-Metadaten nachtragen
- Gemergt: 2026-08-15
- Bezug: #408

**Geliefert:** Der PR-Body im Chunk-Datensatz ist nur der Platzhalter „@-"; laut `gh pr view` ist auch die tatsächliche PR-Beschreibung leer. Aus der Commit-Nachricht ergibt sich der Umfang: Migration 012 hatte `documents.library_id` per Backfill befüllt, die zugehörigen `vector_store`-Chunk-Metadaten (JSON) aber nicht — ältere Chunks trugen die Felder `library_id`/`organization_id` nicht, wodurch `QueryService` (der exakt danach filtert) sie nie fand, ohne sichtbaren Fehler. Auf der Testinstanz machte das einen vollständig indizierten Korpus aus 1449 Dokumenten unauffindbar. Migration 016 trägt die Felder für betroffene Chunks nach (verbunden über `metadata->>'document_id'`), lässt bereits vollständige Chunks und Waisen ohne Dokument unangetastet. Schließt Issue #408.

**Verifikation:** Changelog `016-backfill-vector-store-library-metadata.yaml`, Migrationstest `Migration016VectorStoreLibraryMetadataTest.java` und `docs/migrations/016-vector-store-library-metadata.md` sind im heutigen Stand vorhanden.

**Themen:** backend, indexing, migration, bugfix, vector-store, berechtigungen

---

<a id="pr-413"></a>

## PR #413 — fix(library): Rechteprüfung für die System-Bibliothek vereinheitlichen
- Gemergt: 2026-08-15
- Bezug: #201, #406

**Geliefert:** Der PR-Body im Chunk-Datensatz ist nur der Platzhalter „@-"; laut `gh pr view` ist auch die tatsächliche PR-Beschreibung leer. Aus der Commit-Nachricht ergibt sich der Umfang: Zwei Wege prüften Lesezugriff auf die System-Bibliothek unterschiedlich — die Anzeige fragte `effectiveRole` mit einem Sonderfall („nur Systemadministratoren"), die Suche `readableLibraryIds` kannte diesen Sonderfall nicht. Dieselbe Bibliothek konnte dadurch über den einen Weg lesbar, über den anderen verboten sein; ein für niemanden geöffneter Bestand war für niemanden auffindbar, auch nicht für Administratoren. Der Sonderfall entfällt, beide Wege werten jetzt dieselbe Formel aus `docs/features/spaces-and-assets.md` aus; die Grundvoreinstellung (PRIVATE, ohne Grants, Öffnen erfordert MANAGER) aus #201 bleibt erhalten. Schließt Issue #406.

**Verifikation:** `LibraryAccessService.java` und `LibraryOwnerType.java` sind im heutigen Stand vorhanden.

**Themen:** backend, berechtigungen, bibliothek, bugfix, konsistenz

---

<a id="pr-499"></a>

## PR #499 — test(backend): Migrationstests beschleunigen — Template-DB, geteilter Container, gemeinsamer Kontext
- Gemergt: 2026-08-19
- Bezug: #497

**Geliefert:** Beschleunigt die Backend-Testsuite gemäß Issue #497, Maßnahmen 1–3: Alle 18 damaligen Migrationstests erben von einer neuen `AbstractMigrationTest`, die einen geteilten Testcontainers-Postgres-Singleton nutzt und den Fixture-Changelog nur einmal je Klasse in eine Template-Datenbank baut (statt bis zu 19 Einzelstarts); jede Testmethode klont per `CREATE DATABASE ... TEMPLATE ...`. `OpaaApplicationTests` teilt sich jetzt den Spring-Kontext mit der übrigen Testgruppe (`@ActiveProfiles({"local","dev"})` statt eigenem Profil). Maßnahme 4 (`maxParallelForks=2`) wurde lokal gemessen (instabil, 2 von 3 Läufen langsamer) und bewusst nicht committet. Lokale Wall-Zeit sank von 6:46 min auf 4:50 min, CI-`backend`-Job auf 4:37 min — das Abnahmekriterium „< 3:30 min" wurde damit noch nicht erreicht.

**Verifikation:** `AbstractMigrationTest.java` sowie alle in der Dateiliste genannten Migrationstestklassen sind im heutigen Stand vorhanden. Das Muster wurde in PR #698 als „Pflichtmuster für neue Migrationstests" bestätigt und dort auf drei nachträglich hinzugekommene Ausreißer angewendet; die verbliebene Lücke zum 3:30-min-Ziel wurde in PR #648 (Maßnahme 5, Kontext-Konsolidierung) weiterverfolgt.

**Themen:** tests, backend, performance, ci, migrationen, testcontainers

---

<a id="pr-502"></a>

## PR #502 — docs(decisions): ADR-0018 auf Akzeptiert setzen
- Gemergt: 2026-08-19
- Bezug: #475, #476, #477, #480, #486, #500

**Geliefert:** Setzt den Status von ADR-0018 (Quellkonfiguration in der Bibliothek) von „Vorgeschlagen" auf „Akzeptiert" — reine Statuskorrektur, nachdem die Umsetzung des Epics #486 (#476, #477, #480 bereits gemergt) längst auf Basis dieses ADR lief und der Review zu PR #500 die Inkonsistenz benannt hatte.

**Verifikation:** `docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md` ist im heutigen Stand vorhanden.

**Themen:** doku, adr, statuspflege, bibliothek

---

<a id="pr-648"></a>

## PR #648 — test(backend): Spring-Kontexte konsolidieren und CI-Parallelitaet erproben (#497)
- Gemergt: 2026-08-20
- Bezug: #497, #499

**Geliefert:** Setzt Maßnahme 5 aus Issue #497 (nach PR #499 noch offen) für den klarsten risikoarmen Fall um: `BrandingControllerIntegrationTest`, `AuditControllerAuthorizationIntegrationTest` und `LibraryControllerCredentialsIntegrationTest` teilen sich jetzt einen Spring-Kontext über die gemeinsame `TestcontainersConfiguration` statt je einen eigenen Postgres-Container zu starten; `UrlIndexingExecutorCredentialsTest` an die große `{"local","dev"}`-Kontextgruppe angeglichen. Bewusst nicht konsolidiert: Tests mit abweichender Rate-Limit-Konfiguration bzw. Provider-Tests, die per Definition unterschiedliche `@DynamicPropertySource`-Werte prüfen. Zusätzlich CI-gated `maxParallelForks=2` in `build.gradle.kts` (nur aktiv, wenn `CI`-Umgebungsvariable gesetzt ist) — lokal auf dem Windows-Entwicklungsrechner in PR #499 verworfen, auf dedizierten CI-Runnern erneut versucht. CI-`backend`-Job sank von 5:23–6:14 min auf 3:43 min.

**Verifikation:** `backend/build.gradle.kts` sowie die drei genannten Integrationstestklassen sind im heutigen Stand vorhanden.

**Themen:** tests, backend, performance, ci, spring-kontext, parallelitaet

---

<a id="pr-695"></a>

## PR #695 — test(query): Zwei-Konten-Test fuer Gespraechsgedaechtnis nachreichen
- Gemergt: 2026-08-21
- Bezug: #123

**Geliefert:** Reicht für das bereits geschlossene Issue #123 einen expliziten Zwei-Konten-Test nach, der belegt, dass die Personentrennung des Gesprächsgedächtnisses tatsächlich funktioniert (`QueryServiceTest#sameChatIdForTwoDifferentUsersProducesIsolatedConversationHistories`), inklusive Aufbau des echten Cache-Stacks statt eines Mocks. Behält `CaffeineChatMemoryRepository#findConversationIds` bewusst bei (vom Spring-AI-Interface zwingend vorgeschrieben, keine externen Aufrufer), ergänzt aber einen Javadoc-Warnhinweis, dass die Methode kontoübergreifend liest und nie ohne Filterung nach Nutzerpräfix für eine nutzerseitige Konversationsliste verwendet werden darf.

**Verifikation:** `CaffeineChatMemoryRepository.java` und `QueryServiceTest.java` sind im heutigen Stand vorhanden.

**Themen:** tests, backend, query, gespraechsgedaechtnis, datenisolation

---

<a id="pr-698"></a>

## PR #698 — test(backend): letzte drei Migrationstests auf Template-DB umstellen
- Gemergt: 2026-08-21
- Bezug: #497, #499, #648

**Geliefert:** Stellt die drei einzigen Migrationstestklassen, die noch nicht auf das in PR #499 eingeführte `AbstractMigrationTest`-Muster umgestellt waren (`Migration027LibrarySourceTypeAndConfigurationTest`, `Migration028UniqueRunningIndexingJobPerLibraryTest`, `Migration032CreateChatsTest` — offenbar nach #499 neu entstanden und versehentlich mit dem alten Muster geschrieben), auf den geteilten Testcontainer mit Template-Datenbank um. Prüfsubstanz und Isolationsgarantien bleiben laut PR-Beschreibung unverändert bzw. verstärkt (eigene geklonte Datenbank statt nur eigenem Schema). Klassenzeiten der drei betroffenen Tests sanken zusammen von 50,4 s auf 15,4 s (-69 %), das Gesamtpaket von 345,0 s auf 317,1 s (-8 %). Das übergeordnete Suiten-Ziel „< 3:30 min" war laut PR bereits durch #499/#648 adressiert.

**Verifikation:** Alle drei genannten Migrationstestklassen sind im heutigen Stand vorhanden.

**Themen:** tests, backend, performance, migrationen, testcontainers

---

<a id="pr-728"></a>

## PR #728 — feat(demo): Quellen- und Demo-Hinweis der Demo-Instanz (Frontend)
- Gemergt: 2026-08-21
- Bezug: #230, #409, #708

**Geliefert:** Frontend-Teil von Issue #230 (Epic #708): abschaltbarer Hinweis in der Fußzeile des App-Shells, dass die Instanz mit synthetischen Inhalten der fiktiven Stadt Rheinfurt arbeitet, mit Link „Quellen & Lizenz" zu einem Dialog, der die Datensatz-Herkunft (LHM-Dienstleistungen-Corpus, MIT-Lizenz) zeigt. Umschaltung erfolgt ohne Rebuild über `frontend/nginx.conf` als `envsubst`-Vorlage (`OPAA_DEMO_MODE`), die zur Laufzeit `window.__OPAA_DEMO_MODE__` über `/runtime-config.js` setzt — analog zum bestehenden CSP-Mechanismus (#409). Der serverseitige Rollout selbst bleibt bewusst außerhalb dieses PRs.

**Verifikation:** `DemoNotice.tsx`, `runtimeConfig.ts` und die zugehörigen Tests sind im heutigen Stand vorhanden.

**Themen:** frontend, demo, marketing, konfiguration, lizenz, barrierefreiheit

---

<a id="pr-732"></a>

## PR #732 — docs(demo): Rollout der Demo-Instanz Stadt Rheinfurt dokumentieren
- Gemergt: 2026-08-21
- Bezug: #230

**Geliefert:** Dokumentiert den erfolgten serverseitigen Rollout der Demo-Instanz „Stadt Rheinfurt" auf `opaa.ewerlin.com`: Verfahren für Korpus-/Webserver-Bereitstellung ohne Repo-Checkout, Zielprüfung-Allowlist, Seed-Verfahren mit Passwort-Rotation, `OPAA_DEMO_MODE`, Ausgabenlimit-Fundort. Ergänzt `README.md` (Demo-Link zeigt jetzt auf Rheinfurt) und `docs/demo-walkthrough.md` (Hinweis auf die vier Fach-Demokonten). Dokumentiert bewusst nur das Verfahren, keine konkreten Serverpfade oder Secrets.

**Verifikation:** `README.md`, `docs/demo-walkthrough.md` und `docs/deployment.md` sind im heutigen Stand vorhanden.

**Themen:** doku, demo, deployment, betrieb, rollout

---

<a id="pr-761"></a>

## PR #761 — docs(features): Stufe 1 der Modellverwaltung in der LLM-Spezifikation
- Gemergt: 2026-08-22
- Bezug: #755, #756, #757, #758, #759, #760

**Geliefert:** Reine Dokumentationsänderung an `docs/features/llm-integration.md`. Neuer Abschnitt „Stufe 1: Verwaltete Chat-Modelle (in Umsetzung)“ beschreibt den ersten Umsetzungsschritt des Zielbilds: Liste hinterlegter Chat-Modelle, genau ein systemweit aktiver Eintrag, Verbindungstest, verschlüsselte/nicht rücklesbare Zugangsdaten, Protokollierung jeder Änderung. Festlegung auf ausschließlich die OpenAI-kompatible Schnittstelle (auch für Ollama über `/v1`), Zugangsschlüssel optional. Vorgaben-Ebenen und Modellauswahl durch Nutzende bleiben als spätere Ausbaustufen erhalten, nicht gestrichen.

**Verifikation:** `docs/features/llm-integration.md` existiert im Worktree. Kein Code betroffen (reine Spezifikationsänderung), Umsetzung erfolgte über die referenzierten Sub-Issues #756–#760.

**Themen:** LLM-Integration, Modellverwaltung, Spezifikation, Epic #755

---

<a id="pr-790"></a>

## PR #790 — docs(design): Zielbild-Mockups um Abschnitt „Global vs. Space“ erweitern
- Gemergt: 2026-08-23
- Bezug: #786, #787, #788, #789, #600

**Geliefert:** Aktualisierte `docs/design/OPAA Mockups.html` (Zielbild-Mockups) mit neuem Abschnitt 2 „Global vs. Space — Navigationskonzept“, Seiten 2a–2c: globale Rail-Leiste (2a), globale Administration ohne Space-Spalte (2b), globale Benutzer-Einstellungen (2c). Reine Gestaltungsgrundlage für die geplanten Sub-Issues #786–#789 unter Epic #600, kein Code.

**Verifikation:** `docs/design/OPAA Mockups.html` existiert im Worktree. Ob die Umsetzung (globale Rail-Leiste im Frontend) zwischenzeitlich erfolgt ist, wurde hier nicht separat geprüft — das ist Sache der Bausteine zu #786–#789/#600, falls in dieser Charge enthalten.

**Themen:** Design, Mockups, Navigation, Global vs. Space, Epic #600

---

<a id="pr-804"></a>

## PR #804 — fix(backend): Ungültige Dokument-ID in Chunk-Metadaten auf WARN heben
- Gemergt: 2026-08-23
- Bezug: #78

**Geliefert:** Log-Level in `QueryService.lookupSourceDocuments` und `QueryService.parseDocumentId` von DEBUG auf WARN angehoben, damit eine ungültige `document_id` in Chunk-Metadaten (Indiz für korrupte Indizierung/Migrationsfehler) in Produktion nicht mehr durch deaktiviertes DEBUG-Logging verschluckt wird. Bewusst kein Metrik-Zähler und keine Änderung an der Indexing-Schreibseite — außerhalb des Umfangs. Maintainer-Freigabe für das sonst nicht verfolgte bigpuritz-Issue #78 dokumentiert. Reproduktionsnachweis per Logback-`ListAppender`-Test erbracht (rot bei DEBUG, grün bei WARN).

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryService.java` enthält die WARN-Logaufrufe an den erwarteten Stellen (Zeilen 553, 695 sowie ein weiterer bei 372) im aktuellen Stand des Worktrees.

**Themen:** Logging, QueryService, Datenqualität, Bugfix

---

<a id="pr-810"></a>

## PR #810 — ci(pages): Meilenstein-Ordner fortschritt/ statt *.mp4 vom Sync ausnehmen
- Gemergt: 2026-08-23
- Bezug: #808, #807

**Geliefert:** Korrektur zu PR #808 (Issue #807): Statt des Datei-Musters `*.mp4` nimmt der Landing-Page-Sync jetzt den ganzen Ordner `fortschritt/` von der Synchronisation aus — dort liegen manuell auf `gh-pages` gepushte Meilenstein-Artefakte (Demo-Video, Berichts-PDFs).

**Verifikation:** `.github/workflows/landing-page.yml` enthält aktuell `--exclude 'fortschritt/'` (Zeile 66) mit erklärendem Kommentar — Änderung ist im heutigen Stand vorhanden und unverändert wirksam.

**Themen:** CI, GitHub Pages, Landing Page, Meilenstein-Artefakte

---

<a id="pr-818"></a>

## PR #818 — chore(backend): tote Abhängigkeit, tote Konfiguration und deutsche Log-Meldungen entfernen
- Gemergt: 2026-08-23
- Bezug: #817

**Geliefert:** Erster Aufräum-PR aus dem Backend-Review vom 23.08.2026, Teil des Sammel-Issues #817: jjwt-Abhängigkeit entfernt (kein `io.jsonwebtoken`-Vorkommen mehr seit Umstellung auf oidc/dev-Auth), tote Konfiguration `opaa.indexing.retry-attempts` entfernt (seit Spring-AI-VectorStore-Migration ungelesen), tote Repository-Methoden `UserRepository.findByEmail` und `GroupRepository.existsByOrganizationIdAndExternalId` entfernt, deutsche Log-Meldungen in `AuditRetentionDeletionService`/`LlmModelSeeder` auf Englisch umgestellt (AGENTS.md-Konvention).

**Verifikation:** Im heutigen Worktree kein `jjwt`-Eintrag mehr in `backend/gradle/libs.versions.toml`, kein `retry-attempts` mehr in `application.yml`; `UserRepository.java` existiert, aber ohne `findByEmail`-Methode — Entfernung bestätigt.

**Themen:** Aufräumarbeiten, tote Abhängigkeiten, Konfiguration, Logging, Sammel-Issue #817

---

<a id="pr-847"></a>

## PR #847 — fix(backend): toten Code und veraltete Kommentare bereinigen (Runde 2)
- Gemergt: 2026-08-24
- Bezug: #817

**Geliefert:** Zweiter Schwung aus dem Sammel-Issue #817: `UserService.findById` entfernt (keine Aufrufer mehr), `LibraryAccessService.canEdit`/`canDelete` entfernt (kein Produktionsaufrufer mehr seit #436), `canRead`/`canManage` bewusst behalten (weiterhin genutzt); H2 + `spring-boot-starter-data-jpa-test` aus den Abhängigkeiten entfernt; fehlende Konfiguration `opaa.indexing.stale-job-timeout` in `application.yml` ergänzt; veraltete Kommentare in `SpaceController`, `IndexingConfiguration`, `RejectedDocumentReporter`, `ChatRepository` bereinigt.

**Verifikation:** `LibraryAccessService.java` enthält im heutigen Stand noch `canRead`/`canManage`, `canEdit`/`canDelete` sind nicht mehr vorhanden — Entfernung bestätigt. `UserService.findById` nicht mehr auffindbar (nur `findByIdAndOrganizationId`).

**Themen:** Aufräumarbeiten, toter Code, Berechtigungen, Sammel-Issue #817

---

<a id="pr-861"></a>

## PR #861 — docs(decisions): Review-Nachbesserungen zu ADR-0021 nachziehen
- Gemergt: 2026-08-24
- Bezug: #845, #859, #858

**Geliefert:** Nachbesserungen zum Review von PR #859 (Teil von Issue #845, bereits durch #859 geschlossen). Fundstellenliste um „Prozesslokale/knotenlokale Dateiablage“ ergänzt (`LibraryDocumentService`-Uploads unter `opaa.upload.storage-path`, härteste Annahme der Liste, mit Umbauskizze); `FilesystemPathAllowlist` als Nachbarfall ergänzt; Javadoc in `LibraryIndexingScheduler`/`IndexingJobService`/`IndexingJobRecoveryScheduler` auf die 1–5-Zeilen-Konvention (#858) gekürzt; diverse Präzisierungen (Task-Executor-Warteschlangen, `recoverOnStartup`-Trigger, Caffeine-Charakterisierung, Migration 028, Audit-Retention-Sperre). ADR-Status auf „Akzeptiert“ gesetzt.

**Verifikation:** `docs/decisions/0021-single-instance-betrieb.md` existiert im Worktree; ein `grep` auf „^Status“ lieferte im aktuellen Stand keinen Treffer in dieser Zeilenform (Statuszeile vermutlich anders formatiert oder in einer Tabelle) — Inhalt der Datei nicht im Detail gegengelesen, Existenz und grundsätzliche Aktualität aber bestätigt.

**Themen:** ADR, Single-Instance-Betrieb, Dokumentationspflege, Javadoc-Konvention

---

<a id="pr-864"></a>

## PR #864 — docs(indexing): Javadoc-Kommentare auf Vertrag und Invarianten kürzen
- Gemergt: 2026-08-24
- Bezug: #817, #842

**Geliefert:** Erste Kürzungsrunde aus dem Sammel-Issue #817 (Javadoc-Hypertrophie), Paket `io.opaa.indexing` (~35 % Kommentaranteil). Alle 53 Dateien des Pakets durchgegangen, Kommentare auf Vertrag/Invarianten in 1–5 Zeilen gekürzt (Konvention aus #842-Entwurf), Review-Runden-Zitate und Entstehungshistorie entfernt. Reduktion von 3236 auf 2523 Kommentarzeilen (~22 %), bewusst unter dem im Issue genannten Zielkorridor von 60–70 %, da sicherheits-/nebenläufigkeitsrelevante Invarianten im Zweifel behalten wurden. Keine Code-, Signatur- oder Testdatei-Änderungen.

**Verifikation:** Alle im PR genannten Dateien (`IndexingProperties.java`, `FileProcessingService.java`, `RssFeedIndexingExecutor.java`, `IndexingConfiguration.java`) existieren im heutigen `io.opaa.indexing`-Paket des Worktrees.

**Themen:** Javadoc, Kommentarkonvention, Aufräumarbeiten, Sammel-Issue #817

---

<a id="pr-867"></a>

## PR #867 — chore(backend): toten Code entfernen, Ablaufdaten setzen, pgvector-Dimensions-Guard ergaenzen
- Gemergt: 2026-08-24
- Bezug: #817, #216, #865

**Geliefert:** Dritter Schwung der Bereinigung aus #817: `UrlIndexingExecutor.classifyDownloadedFile` entfernt und Entscheidungslogik in `decideForEntry` extrahiert (Test prüft jetzt echten Produktionscode); `ContentRetentionProvider`-Erweiterungspunkt (für das als „nicht geplant“ geschlossene #216) samt Wiring-Test entfernt, das begleitende Feld `AuditRetentionUpdateResult.inconsistentWithContentRetention` bewusst behalten für eine mögliche Wiederaufnahme; Ablaufdaten-Vermerke an `LlmModelSeeder`/-`SeedMarker`/-`SeedRunner` ergänzt; neuer `PgVectorDimensionsGuard` (`io.opaa.config`) prüft beim Start die Spaltendimension von `vector_store.embedding` gegen die konfigurierte `spring.ai.vectorstore.pgvector.dimensions` und bricht bei Abweichung kontrolliert ab (liest aus dem Spaltentyp, deckt auch TRUNCATE-Szenarien ab).

**Verifikation:** `PgVectorDimensionsGuard.java` existiert im heutigen Stand unter `backend/src/main/java/io/opaa/config/`; `ContentRetentionProvider.java` ist im Worktree nicht mehr vorhanden — Entfernung bestätigt.

**Themen:** Aufräumarbeiten, pgvector, Startvalidierung, Sammel-Issue #817

---

<a id="pr-869"></a>

## PR #869 — refactor(space): DTO-Leak beheben - Services geben Domain-Typen zurück
- Gemergt: 2026-08-24
- Bezug: #860

**Geliefert:** Erster (Blaupausen-)Teil der PR-Serie zu Sammel-Issue #860 (DTO-Leak): `SpaceService` und `SpaceAssetAssociationService` nehmen keine generierten Request-DTOs mehr entgegen und geben keine generierten Response-DTOs mehr zurück, sondern Entities und neue Domain-Records (`SpaceCreation`, `SpaceUpdate`, `SpaceMemberSeed`, `SpaceOverview`, `SpaceMemberView`, `SpaceLibraryLink(s)`, `LibrarySpaceLink`). Neue package-private Mapper `SpaceResponseMapper`/`SpaceLibraryAssociationResponseMapper` in `io.opaa.api` übernehmen das Entity→Response-Mapping, je mit eigenem Spring-freiem Mapper-Unit-Test. Dokumentiert die Mapper-Konvention erstmals in AGENTS.md — dient als Blaupause für die Folge-PRs (group/library/chat).

**Verifikation:** `SpaceResponseMapper.java` sowie die Domain-Records unter `backend/src/main/java/io/opaa/space/` existieren im heutigen Worktree-Stand; die Mapper-Konvention aus AGENTS.md deckt sich mit dem aktuellen Text der Datei.

**Themen:** DTO-Leak, Domain-Services, Mapper-Konvention, Sammel-Issue #860, Blaupause

---

<a id="pr-870"></a>

## PR #870 — refactor(group): DTO-Leak beheben - GroupService/DirectorySync geben Domain-Typen zurück
- Gemergt: 2026-08-24
- Bezug: #860, #869

**Geliefert:** Teil 2 der PR-Serie zu #860 (Blaupause #869): `GroupService`, `DirectorySyncService`, `DirectorySyncPlanExecutor` DTO-frei umgestellt, neue Domain-Records (`GroupCreation`, `GroupUpdate`, `GroupMemberView`, `GroupDetail`, `SyncReport`, `GroupChange`, `MembershipChange`, `UserRef`), neue Mapper `GroupResponseMapper`/`DirectorySyncResponseMapper`. Im Review-Nachgang ein echter Bug gefunden und behoben: `GroupResponseMapper` griff außerhalb der Transaktion auf eine nicht fetch-gejointe LAZY-Collection zu (`LazyInitializationException` auf `GET /groups`/`GET /me/groups`) — behoben über neue Repository-Methoden mit Fetch-Join, mit Reproduktionsnachweis (rot/grün dokumentiert im Body).

**Verifikation:** `GroupResponseMapper.java`, `DirectorySyncResponseMapper.java` sowie die genannten Domain-Records in `io.opaa.group`/`io.opaa.group.sync` existieren im heutigen Worktree-Stand.

**Themen:** DTO-Leak, Domain-Services, LazyInitializationException, Bugfix, Sammel-Issue #860

---

<a id="pr-871"></a>

## PR #871 — refactor(library): DTO-Leak beheben - KnowledgeLibraryService/AssetGrantService geben Domain-Typen zurück
- Gemergt: 2026-08-24
- Bezug: #860, #869, #870

**Geliefert:** Teil 3a der PR-Serie zu #860 (größter Teil, in 3a/3b gesplittet). `KnowledgeLibraryService`, `AssetGrantService` und zwangsläufig `LibraryDocumentService` DTO-frei umgestellt. Neue Domain-Records (`LibraryCreation`, `LibraryUpdate`, `LibraryScheduleUpdate`, `AssetGrantUpsert`, `LibraryDetail`, `LibrarySummary`, `AssetGrantView`, `LibraryDocumentEntry`/`-Page`/`LibraryFolderChild`), neue Mapper (`LibraryResponseMapper`, `AssetGrantResponseMapper`, `LibraryDocumentResponseMapper` — letzterer aus dem umgezogenen `LibraryDocumentResponses`). `ScheduleFrequency`/`ScheduleWeekday` bewusst als generierte DTO-Enums belassen (Begründung im Javadoc). LAZY-Absicherung durch zusätzliche Integrationstests, da die betroffenen Entities keine LAZY-Relationen tragen (Lehre aus #870).

**Verifikation:** `LibraryResponseMapper.java` und `AssetGrantResponseMapper.java` existieren im heutigen Worktree-Stand unter `backend/src/main/java/io/opaa/api/`.

**Themen:** DTO-Leak, Domain-Services, Bibliotheken, Grants, Sammel-Issue #860

---

<a id="pr-872"></a>

## PR #872 — refactor(library): DTO-Leak beheben - LibraryFolderService/SourceConnectionTestService geben Domain-Typen zurück
- Gemergt: 2026-08-24
- Bezug: #860, #871

**Geliefert:** Teil 3b der PR-Serie zu #860, Fortsetzung von Teil 3a (#871). `LibraryFolderService` und `SourceConnectionTestService` DTO-frei umgestellt: neuer Domain-Record `LibraryFolderDetail`, neue Records `SourceConnectionTest`/`SourceConnectionTestResult`, neue Mapper `LibraryFolderResponseMapper`/`SourceConnectionTestResponseMapper` je mit eigenem Mapper-Unit-Test (direkt in diesem PR statt nachträglich — Lehre aus dem Review zu 3a). Damit ist das `library`-Paket bis auf die bewusst belassenen Schedule-Enums DTO-frei.

**Verifikation:** `LibraryFolderResponseMapper.java` und `SourceConnectionTestResponseMapper.java` existieren im heutigen Worktree-Stand unter `backend/src/main/java/io/opaa/api/`.

**Themen:** DTO-Leak, Domain-Services, Bibliotheksordner, Quellenverbindungstest, Sammel-Issue #860

---

<a id="pr-873"></a>

## PR #873 — refactor(chat,query): DTO-Leak beheben - ChatService/QueryService geben Domain-Typen zurück
- Gemergt: 2026-08-24
- Bezug: #860, #869, #826

**Geliefert:** Teil 4 der PR-Serie zu #860 (Blaupause #869): `ChatService`/`QueryService` DTO-frei umgestellt. Neue Domain-Typen `ChatCreation`/`ChatPatch`, `ChatConversation`/`ChatTurn`, `ChatSource`/`ChatSourceLocation` (bewusst mutable Bean-Form für Wire-Kompatibilität mit persistiertem JSON) sowie `QueryResult`/`QueryOutcome`/`SearchedLibraryRef`; neue Mapper `ChatResponseMapper`/`QueryResponseMapper`. `LibraryScheduleCodec` behält bewusst die generierten Schedule-Enums (Begründung: künftiges `opaa-api`-Modul aus Epic #826 Phase 4). Abnahmekriterium des Sammel-Issues (`grep -rl "io.opaa.api.dto" backend/src/main/java | grep -v "/api/"`) dokumentiert die verbleibenden bewussten Ausnahmen (auth-Controller, Javadoc-Erwähnungen, Schedule-Codec, Teil-3b-Reste).

**Verifikation:** `ChatResponseMapper.java` und `QueryResponseMapper.java` existieren im heutigen Worktree-Stand unter `backend/src/main/java/io/opaa/api/`.

**Themen:** DTO-Leak, Domain-Services, Chat, Query, Sammel-Issue #860, Epic #826

---

<a id="pr-879"></a>

## PR #879 — docs(audit): Javadoc-Kommentare auf Vertrag und Invarianten kürzen
- Gemergt: 2026-08-24
- Bezug: #817, #864, #450

**Geliefert:** Zweite Kürzungsrunde aus dem Sammel-Issue #817, Paket `io.opaa.audit` (~36 % Kommentaranteil, höchster im Backend). Nach dem Vorbild der indexing-Runde (#864): tragende Invarianten (Transaktionsverhalten, Pseudonymisierung, Partitionierung/Retention, Vier-Augen-Regeln) behalten, Review-Zitate und Entstehungshistorie entfernt. Reduktion von 814 auf 627 Kommentarzeilen (~23 %). Im Review-Nachgang eine fälschlich gekürzte, betrieblich tragende Log-Meldung wiederhergestellt sowie sechs Enum-/Entity-Dateien um Check-Constraint-Namen ergänzt. Keine Code-, Signatur- oder Testdatei-Änderungen.

**Verifikation:** `package-info.java` und die genannten Kern-Dateien (`AuditEventRecorder.java`, `AuditQueryService.java`) existieren im heutigen `io.opaa.audit`-Paket des Worktrees.

**Themen:** Javadoc, Kommentarkonvention, Audit, Sammel-Issue #817

---

<a id="pr-882"></a>

## PR #882 — refactor(indexing): Quellenzugriff als eigenes Paket io.opaa.sourceaccess extrahieren
- Gemergt: 2026-08-24
- Bezug: #876, #826

**Geliefert:** Teil von #876 (Epic #826, Befund B7), PR 1 von 2 (die Zerlegung von `RssFeedIndexingExecutor` folgt separat). Zieht den bisher über `io.opaa.indexing` verstreuten Quellenzugriff (HTTP-Client-Bau, Redirect-Verfolgung, Proxy/Credentials, Downloads, SSL-Politik) in ein eigenes, von `library`/`api` unabhängiges Paket `io.opaa.sourceaccess`: `TargetAddressValidator`, `ProxyAndCredentials`, `SourceHttpClientFactory`, `RedirectFollowingFetcher` (löst vier divergente Redirect-Handkopien durch eine gemeinsame Implementierung mit `RedirectPolicy`-Parameter ab), `BoundedDownloader` (vormals `UrlFileDownloader`). Verhalten byte-gleich, Sicherheitsinvarianten (SSRF-Check, Auth-Header-Drop off-origin) unverändert getestet.

**Verifikation:** Paket `backend/src/main/java/io/opaa/sourceaccess/` existiert im heutigen Worktree mit allen genannten Klassen (`BoundedDownloader.java`, `RedirectFollowingFetcher.java`, `TargetAddressValidator.java` u. a.).

**Themen:** Refactoring, Quellenzugriff, SSRF, Paketstruktur, Epic #826, Issue #876

---

<a id="pr-893"></a>

## PR #893 — refactor(audit): AuditEvent-Builder statt Positionsargumenten
- Gemergt: 2026-08-25
- Bezug: #892, #826

**Geliefert:** `AuditEventRecorder.recordUserAction`/`recordUserActionOnSubject`/`recordSystemProcessAction` nahmen bisher bis zu 13 Positionsargumente entgegen; dieser PR ersetzt das durch ein `AuditEvent`-Parameterobjekt mit Builder und benannter Zuordnung. Alle 35 Aufrufstellen quer durch 12 Services wurden mechanisch umgestellt, Audit-Einträge feldgleich; Transaktionssemantik unverändert. `recordAuditLogAccess` bleibt bewusst außen vor. PR 1 von 2 für #892 — die `GrantChanged`/`LibraryChanged`-Domain-Events folgen separat. In einer Review-Nachbesserung kamen wechselseitige Validierungen hinzu (kein `subjectKind` in `recordUserAction`, kein `correlationRef` außerhalb von `recordSystemProcessAction`).

**Verifikation:** `AuditEvent.java` mit `builder()`-Factory-Methode ist im aktuellen Code vorhanden (`backend/src/main/java/io/opaa/audit/AuditEvent.java`, Zeile 55 `public static Builder builder()`).

**Themen:** audit, refactoring, builder-pattern, epic-826

---

<a id="pr-897"></a>

## PR #897 — docs(library,chat,query,space): Javadoc-Kommentare auf Vertrag und Invarianten kuerzen
- Gemergt: 2026-08-25
- Bezug: #817, #864, #879, #860, #875, #884, #888, #890

**Geliefert:** Dritte Kürzungsrunde aus dem Sammel-Issue #817 (Abschnitt 3, Javadoc-Hypertrophie), diesmal über `io.opaa.library`, `io.opaa.chat`, `io.opaa.query` und `io.opaa.space` — nach dem Muster der vorherigen Runden #864 (indexing) und #879 (audit). Kein Vollpaket-Durchgang, sondern gezielt sechs benannte Hypertrophie-Blöcke (u. a. `QueryService#query`, `LibraryAccessService`, `ChatTitleGenerationService`, `AssetGrantRepository`, `ChatRepository`, `SpaceService`), da diese Pakete kurz zuvor bereits umgebaut wurden (#860-Serie) und größtenteils schon konventionskonform waren. Reduktion nur ~4,6 % über die vier Pakete — deutlich unter den 20–25 % der vorherigen Runden. Tragende Invarianten (Permission-aware-Search-Vertrag, Connection-Pool-Deadlock-Begründung, Constraint-Namen, Spec-Anker) blieben erhalten; Verifikation per Kommentar-Stripping-Diff.

**Verifikation:** Reine Kommentaränderung ohne Code-/Signaturwirkung; die betroffenen Dateien (`QueryService.java`, `LibraryAccessService.java`, `ChatTitleGenerationService.java`, `AssetGrantRepository.java`, `ChatRepository.java`, `SpaceService.java`) sind im aktuellen Code weiterhin vorhanden.

**Themen:** dokumentation, javadoc-kuerzung, epic-817, technische-schuld

---

<a id="pr-899"></a>

## PR #899 — chore: Restposten aus #817 – documentPath, tote AuditEventType-Werte, CI-Required-Check
- Gemergt: 2026-08-25
- Bezug: #817, #900, #392

**Geliefert:** Arbeitet Restposten aus dem Sammel-Issue #817 ab. `IndexingProperties.documentPath` (seit ADR-0018 ungenutzt) wurde entfernt, samt zugehöriger Verweise in `application.yml`, `.env.example`, `docs/deployment.md` und Tests. `.github/settings.yml` bekam `changes` als zusätzlichen Required Check. Vier weitere vermeintliche Restposten (`classifyDownloadedFile`, `CaffeineChatMemoryRepository.findConversationIds`, pgvector-Dimensions-Guard, `ContentRetentionProvider`) erwiesen sich als bereits erledigt bzw. bewusst beizubehalten — keine Änderung. Bemerkenswert: Die ursprünglich geplante Löschung von 11 vermeintlich toten `AuditEventType`-Werten wurde nach Koordinator-Entscheidung per `git revert` zurückgenommen, weil sie laut `docs/features/security-and-compliance.md` bewusste Vorabdeklaration der geschlossenen Audit-Zielliste (#392) sind, kein toter Code.

**Verifikation:** `IndexingProperties.java` enthält kein `documentPath` mehr (grep-Treffer leer) — Entfernung bestätigt.

**Themen:** aufraeumarbeiten, epic-817, indexing, audit, ci

---

<a id="pr-901"></a>

## PR #901 — docs(decisions): ADR-0017 auf Akzeptiert setzen
- Gemergt: 2026-08-25
- Bezug: #826, #900

**Geliefert:** Statuswechsel von ADR-0017 (Quellentypmodell der Indizierung) von „Vorgeschlagen" auf „Akzeptiert" per Maintainer-Entscheidung. Entscheidung 5 des ADR (Löschung durch Abwesenheit, RSS ausgenommen) war zu diesem Zeitpunkt bereits mit PR #900 Produktivverhalten geworden — reiner Doku-Statuswechsel ohne Code-Auswirkung.

**Verifikation:** `docs/decisions/0017-quellentypmodell-indizierung.md` zeigt aktuell „Akzeptiert (25.08.2026)" im Status-Abschnitt — bestätigt.

**Themen:** adr, dokumentation, epic-826, indexing

---

<a id="pr-902"></a>

## PR #902 — refactor(api): ScheduleFrequency/ScheduleWeekday als geteilte Domain-Enums nach opaa-api umziehen
- Gemergt: 2026-08-25
- Bezug: #826, #860, #857, #898

**Geliefert:** Letzter Baustein von Epic #826 (Domain-Services kennen keine `io.opaa.api.dto`-Typen, #860): Die Enums `ScheduleFrequency` und `ScheduleWeekday` ziehen von generierten `io.opaa.api.dto`-Typen nach `io.opaa.api.types` um, nach dem Muster der 22 bestehenden Enums (Blaupause PR #898). `typeMappings`/`importMappings` in `opaa-api/build.gradle.kts` erweitert, alle Backend-Importe umgestellt, `SpecEnumParityTest` erweitert. Laut Abnahme im PR-Body importiert danach kein Domain-Service mehr `io.opaa.api.dto` — nur noch vier Auth-Controller.

**Verifikation:** `opaa-api/src/main/java/io/opaa/api/types/ScheduleFrequency.java` und `ScheduleWeekday.java` sind im aktuellen Code vorhanden — bestätigt.

**Themen:** api, dto-migration, epic-826, refactoring, opaa-api-modul

---

<a id="pr-905"></a>

## PR #905 — test(indexing): dritte kanonische Testkontext-Signatur @OpaaIndexingIntegrationTest
- Gemergt: 2026-08-25
- Bezug: #903

**Geliefert:** Führt `@OpaaIndexingIntegrationTest` als dritte kanonische Testkontext-Meta-Annotation in `io.opaa.test` ein (Schritt 1 von #903) — Basis wie `@OpaaIntegrationTest`, ergänzt um feste Chunking-Properties, einen geteilten Mock-/Fake-LLM-Satz mit automatischem Reset sowie ein einziges, prozessweites Basisverzeichnis für das Filesystem-Allowlist, registriert über einen `ApplicationContextInitializer` statt klassenlokaler `@DynamicPropertySource`. Drei Testklassen (`DocumentIndexingIntegrationTest`, `FilesystemFolderMappingIntegrationTest`, `StaleDocumentCleanupIntegrationTest`) teilen sich dadurch einen Kontext statt drei; zwei weitere wurden umgestellt, behalten aber dokumentiert einen eigenen Kontext. Nebenbei ein Windows-spezifischer Bug in `TestPropertySourceUtils.addInlinedPropertiesToEnvironment` gefunden und umgangen (Backslashes in Pfaden wurden beim Properties-Parsing verschluckt).

**Verifikation:** Alle in der Dateiliste genannten Klassen unter `backend/src/test/java/io/opaa/test/` (`OpaaIndexingIntegrationTest.java`, `OpaaIndexingMockConfiguration.java`, `OpaaIndexingMockResetListener.java`, `OpaaIndexingTestDirectory.java`, `OpaaIndexingFilesystemAllowlistInitializer.java`) sind im aktuellen Worktree-Stand vorhanden.

**Themen:** Testinfrastruktur, Spring-Kontext-Konsolidierung, Testperformance, #903

---

<a id="pr-908"></a>

## PR #908 — test(backend): Mock-Konsolidierung für Spring-Testkontexte (Schritte 2-4, Teil von #903)
- Gemergt: 2026-08-25
- Bezug: #903, #905, #906, #632, #616

**Geliefert:** Setzt Schritte 2–4 aus #903 um (Schritt 1 bereits in #905). Führt drei bit-identische innere `FakeDirectoryClient`/`TestConfig`-Kombinationen (`AuditEventRecordingIntegrationTest`, `DirectorySyncServiceIntegrationTest`, `PermissionHistoryServiceIntegrationTest`) zu einer geteilten `io.opaa.test.DirectorySyncMockConfiguration` mit Reset-Listener zusammen — verifiziert per Kontext-Identitäts-Diagnose, dass diese drei Klassen sich jetzt einen Kontext teilen. Zusätzlich wird eine formal-redundante manuelle Testkontext-Deklaration vereinheitlicht. Der PR ist ungewöhnlich transparent über Grenzen des eigenen Erfolgs: die rohe HikariPool-Kennzahl zeigte auf dem Post-#906-Stand keine Veränderung, die strukturelle Kontextreduktion (3→1) wurde separat per Identitätsdiagnose nachgewiesen und bestätigt; #903 bleibt als Issue explizit offen, da der Zielwert ≤10 Kontexte nicht erreicht ist.

**Verifikation:** `DirectorySyncMockConfiguration.java`, `DirectorySyncMockResetListener.java` und `FakeDirectoryClient.java` liegen im aktuellen Stand unter `backend/src/test/java/io/opaa/test/`.

**Themen:** Testinfrastruktur, Spring-Kontext-Konsolidierung, Mock-Konsolidierung, #903, Messmethodik

---

<a id="pr-930"></a>

## PR #930 — docs(fortschritt): tagesreport.md zur Stand-und-Nachweis-Achse ziehen (Nachzügler zu #927)
- Gemergt: 2026-08-26
- Bezug: #927, #928

**Geliefert:** Nachzügler-Commit zu #927/#928: verschiebt `docs/tagesreport.md` nach `docs/fortschritt/tagesreport.md` und ordnet den Tagesreport in `fortschritt/README.md` neben Zeitraumsberichten und Gesamtstand ein; INDEX und Dokumentenkarte werden entsprechend nachgezogen. Reine Aufräumarbeit an der Fortschrittsdokumentation, kein neuer Inhalt.

**Verifikation:** `docs/fortschritt/tagesreport.md` existiert im aktuellen Stand am neuen Pfad.

**Themen:** Dokumentationsstruktur, Fortschrittsdokumentation, #927

---

<a id="pr-936"></a>

## PR #936 — docs(query): Ist-Stand-Spezifikation des Retrieval-Algorithmus
- Gemergt: 2026-08-26
- Bezug: #912, #933, #932, #934, #935

**Geliefert:** Neues Dokument `docs/features/retrieval-algorithm.md` beschreibt den tatsächlichen Ablauf einer Anfrage in `io.opaa.query` als nummerierte Schrittfolge (Scope-Bestimmung, LLM-Teilfragen-Zerlegung, Vektorsuche, MMR-Auswahl, Reciprocal Rank Fusion, Dokument-Vervollständigung, Antwortgenerierung mit Zitatvalidierung) direkt aus dem Code gelesen, inklusive eines Abschnitts zu offenen Schwächen und Ideen. `docs/features/data-indexing-rag.md` bleibt bewusst die Vision/Zielbild-Spezifikation und verlinkt jetzt statt zu duplizieren; eine fehlende Tabellenzeile (`max-chunks-per-document`) wurde ergänzt.

**Verifikation:** `docs/features/retrieval-algorithm.md` existiert im aktuellen Stand und enthält den Abschnitt „Bekannte offene Schwächen (aus den #912-Verifikationen)".

**Themen:** Dokumentation, Retrieval, RAG-Architektur, #912

---

<a id="pr-939"></a>

## PR #939 — feat(query): Zitatvalidierung um deterministische Faktenprüfung ergänzen
- Gemergt: 2026-08-26
- Bezug: #937, #386

**Geliefert:** Erweitert die bestehende `CitationValidator` (#386, prüft nur Zeigen-auf-abgerufenen-Chunk) um eine deterministische Stufe-1-Faktenprüfung: neue Klasse `CitationFactChecker` extrahiert harte Fakten (Geldbeträge, Daten, Paragraphen, sonstige Zahlen mit Tausendertrenner/Dezimalkomma) aus dem Satz vor einem Zitatmarker und vergleicht normalisiert gegen den zitierten Chunk-Text; fehlt der Fakt dort, wird das Zitat auf ungültig zurückgestuft. Kein LLM-Aufruf, kein neues UI — nutzt denselben bestehenden Signalweg. Konservativ: ein Satz ohne extrahierbaren Fakt wird nie geflaggt, ein Zitat kann nur von gültig auf ungültig zurückgestuft werden, nie umgekehrt. Reproduktionsnachweis im Body dokumentiert (roter Test bei zurückgenommenem Fix, grün nach Wiederherstellung).

**Verifikation:** `CitationFactChecker.java` und `CitationValidator.java` liegen im aktuellen Stand unter `backend/src/main/java/io/opaa/query/`.

**Themen:** Zitatvalidierung, Faktenprüfung, Retrieval, Query-Qualität, #937

---

<a id="pr-942"></a>

## PR #942 — fix(demo): Gebühren in Personalausweis/Reisepass-Abholung an Einzeldokumente angleichen
- Gemergt: 2026-08-27
- Bezug: #938

**Geliefert:** Korrigiert veraltete Gebührenbeträge in `002_personalausweis-oder-reisepass-abholen.md`, die den maßgeblichen Einzeldokumenten (`001_personalausweis.md`, `003_reisepass.md`) widersprachen — Ursache des Live-Befunds in Drehbuch-Frage 1 (Issue #938, Teil 1). Grep-Prüfung über den gesamten Korpus bestätigte, dass nur diese eine Datei betroffen war. `MANIFEST.sha256` entsprechend aktualisiert. Reiner Korpus-Datenfix, kein Backend-Code betroffen; Teil 2 von #938 (Drehbuch-Frage 6) blieb zu diesem Zeitpunkt offen.

**Verifikation:** `demo/corpus/leistungen-meldewesen-ausweise/002_personalausweis-oder-reisepass-abholen.md` enthält im aktuellen Stand die korrigierten Beträge (27,20 Euro / 44,20 Euro), konsistent mit dem PR-Ziel.

**Themen:** Demo-Korpus, Datenqualität, Gebühren, #938

---

<a id="pr-943"></a>

## PR #943 — docs(query): akzeptierte Grenze der reinen Vektorsuche dokumentieren
- Gemergt: 2026-08-27
- Bezug: #912, #938

**Geliefert:** Dokumentiert in `docs/features/retrieval-algorithm.md` (Abschnitt „Bekannte offene Schwächen") die in #938 diagnostizierte und per Maintainer-Entscheidung akzeptierte Grenze der reinen Vektorsuche: ein Dokument, dessen Embedding-Signal die Anfrage nicht erreicht, bleibt unauffindbar, selbst wenn es die Anfragebegriffe wörtlich enthält (belegt am Frage-6b-Fall, Rang 50 im thomas.klein-Scope). Der naheliegende Gegenmechanismus (Hybrid-Suche) bleibt bewusst unbeauftragt. Schließt Teil 2 von #938 dokumentarisch ab, ohne Code zu ändern.

**Verifikation:** `docs/features/retrieval-algorithm.md` enthält im aktuellen Stand den Verweis auf Rang 50 und den thomas.klein-Scope im Abschnitt zu offenen Schwächen.

**Themen:** Dokumentation, Retrieval-Grenzen, Vektorsuche, #912, #938

---

<a id="pr-946"></a>

## PR #946 — docs(fortschritt): Inventur-Nachzug zum 27.08. — Delta-Bausteine, Gruppierung und Meilenstein-1-Bericht

- Gemergt: 2026-08-27
- Bezug: #945

**Geliefert:** Erhebungsschritt der Leistungsinventur zum Stand 27.08.2026: Delta-Bausteine ab
der Erstinventur, fortgeschriebene Gruppierung und der Meilenstein-1-Zeitraumsbericht als
Entwurf unter `docs/fortschritt/20260831/`.

**Verifikation:** Verzeichnis `docs/fortschritt/20260831/` mit Anker, Index, Bausteinen,
Gruppierung und Report vorhanden.

**Themen:** Leistungsinventur, Fortschrittsbericht, Projektsteuerung

---

<a id="pr-1014"></a>

## PR #1014 — chore(ci): Befristete Mindestalter-Ausnahme für die vorgezogenen Renovate-Updates

- Gemergt: 2026-08-28
- Bezug: #954, #1000

**Geliefert:** Befristete CI-Ausnahme von pnpms `minimumReleaseAge`-Prüfung für die bereits
eröffneten Renovate-PRs, die Releases jünger als 24 Stunden vorgeschlagen hatten — damit die
laufende Update-Welle abgeschlossen werden konnte, bevor die dauerhafte Regel (#955) greift.

**Verifikation:** Commit `8a525027` auf `main`; die Ausnahme ist als befristet gekennzeichnet.

**Themen:** Renovate, pnpm, CI

---

<a id="pr-renovate-updates"></a>

## Renovate-Abhängigkeits-Updates (Sammelbaustein, 43 PRs)

- Gemergt: 2026-08-27 bis 2026-08-29
- Bezug: #751 (Renovate-Einführung), #951 (Auto-Merge)
- PRs: #916, #917, #918, #919, #920, #947, #948, #949, #950, #953, #963, #964, #965, #967,
  #969, #970, #971, #972, #973, #974, #975, #976, #977, #978, #979, #980, #981, #982, #983,
  #984, #985, #986, #987, #988, #989, #990, #991, #993, #994, #1009, #1010, #1011, #1021

**Geliefert:** Erste große Update-Welle des selbst betriebenen Renovate (#751) nach Aktivierung
des Auto-Merge (#951): Dependency-Pins, Gradle 9.7.1, Node 22.23.2, pnpm 11.24, Spring-Plattform,
JUnit, MUI, Vite 8.2.2, Vitest, ESLint 10.9, Keycloak 26.7, Ollama u. v. m. — mechanische
Versionshebungen ohne eigenständigen Feature-Gehalt, daher hier gesammelt statt je PR ein
Baustein.

Die Welle deckte Schwächen des Auto-Merge-Betriebs auf, die als eigene Issues behoben wurden:
semantischer Lockfile-Bruch (#996/#1000), ungeprüftes Temurin-Major (#1001/#1002),
`minimumReleaseAge`-Konflikt (#954), inkompatible Majors Tika 4 und TypeScript 7 (#1005/#1007).
Ein Update wurde zurückgerollt: `eclipse-temurin` v25 (#988) per #1003.

**Verifikation:** Versionsstände in `backend/gradle/libs.versions.toml`,
`frontend/package.json` und den Workflow-Dateien entsprechen den Updates; `renovate.json5`
trägt die nachgeschärften Regeln.

**Themen:** Abhängigkeitsverwaltung, Renovate, Auto-Merge

