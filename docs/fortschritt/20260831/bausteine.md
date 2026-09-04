# Bausteine mit Befund

Die Leistungsinventur zum Stichtag hat **562 Vorgänge** geprüft — jedes geschlossene Issue und
jeden gemergten Pull Request ohne Issue-Verknüpfung. Diese Datei führt die **131 Vorgänge mit
Befund**: solche, bei denen die Lieferung wesentlich vom Vorgang abweicht. Aufnahmekriterium ist
eines der folgenden drei:

1. **Nicht geliefert.** Der Vorgang wurde geschlossen, ohne dass das Beschriebene entstand — als
   `not planned` oder als `completed` durch Ablösung.
2. **Anders geschnitten.** Die Lieferung weicht in Umfang oder Zuschnitt wesentlich ab: Wesentliches
   fehlt, ein Abnahmekriterium blieb unerfüllt, oder der eingeschlagene Weg ist ein anderer als der
   beschriebene — bis hin zum gegenteiligen.
3. **Nicht mehr vorhanden.** Das Gelieferte existiert zum Stichtag nicht mehr oder wurde ersetzt.

Die übrigen **431 Vorgänge sind ohne Befund**: geliefert wie beschrieben, und das Gelieferte steht
noch. Sie sind aus GitHub und `git log` jederzeit nachvollziehbar und werden deshalb nicht
mitgeführt; ihre Nummern stehen am Ende dieser Datei.

Suche mit `Issue #NNN` bzw. `PR #NNN`; jeder Abschnitt trägt den Anker `#issue-NNN` bzw. `#pr-NNN`.

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

<a id="issue-484"></a>

## Issue #484 — feat(security): Pfad-Allowlist und Berechtigung für Konnektorbibliotheken
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #511 (2026-08-19)

**Laut Issue:** Vor Mehrbenutzer-Produktivbetrieb sollte eine Pfad-Allowlist für `FILESYSTEM`-Bibliotheken eingeführt und entschieden werden, welche Rolle Konnektorbibliotheken anlegen darf; Zusammenspiel mit #267 (Zielprüfung gegen private Adressbereiche) benennen.

**Geliefert:** Teilweise abweichend von der Ausgangsfrage: Die Rollenfrage wurde laut PR-Beschreibung als Maintainer-Entscheidung offen gelassen — weiterhin darf jeder mit Anlage-Recht jeden Bibliothekstyp anlegen. Stattdessen liegt die eigentliche Sicherung in einer betriebsseitig konfigurierten Pfad-Allowlist (`opaa.indexing.filesystem.allowlist`, `OPAA_INDEXING_FILESYSTEM_ALLOWLIST`), geprüft bei Anlage/Update und erneut bei jedem Lauf (Traversal-sicher über `Path.normalize()`). Eine leere Allowlist (Standard) deaktiviert `FILESYSTEM` faktisch. URL-Typen (`HTTP_DIRECTORY`, `RSS_FEED`) bleiben bewusst unberührt — #267 bleibt dafür offen.

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

<a id="pr-32"></a>

## PR #32 — docs: add product pitch one-pager (DE + EN)
- Gemergt: 2026-02-20
- Bezug: keiner

**Geliefert:** Gestaltete One-Pager-Flyer für OPAA in Deutsch und Englisch (HTML + PDF) im dunklen A4-Layout mit UI-Screens, fünf Nutzenversprechen und SaaS-Vergleichstabelle, dazu eine Markdown-Fassung des Pitches (`docs/PITCH.md`) und hochauflösende Design-Screenshots.

**Verifikation:** `docs/OPAA-pitch-de.html`/`.pdf` sowie ein Design-Screenshot sind noch vorhanden. `docs/OPAA-pitch-en.html` wurde inzwischen entfernt (Commit `ab51bfda`, „Landing-Page, Pitch und One-Pager auf den Verwaltungston umstellen"), `docs/PITCH.md` ebenfalls entfernt (Commit `73dec48d`, „remove markdown pitch document"). Der deutsche Pitch existiert also fort, die englische Fassung und die Markdown-Version sind spätestens im Zuge der Neuausrichtung auf öffentliche Verwaltung obsolet geworden.

**Themen:** marketing, pitch, doku, mehrsprachigkeit

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

<a id="pr-499"></a>

## PR #499 — test(backend): Migrationstests beschleunigen — Template-DB, geteilter Container, gemeinsamer Kontext
- Gemergt: 2026-08-19
- Bezug: #497

**Geliefert:** Beschleunigt die Backend-Testsuite gemäß Issue #497, Maßnahmen 1–3: Alle 18 damaligen Migrationstests erben von einer neuen `AbstractMigrationTest`, die einen geteilten Testcontainers-Postgres-Singleton nutzt und den Fixture-Changelog nur einmal je Klasse in eine Template-Datenbank baut (statt bis zu 19 Einzelstarts); jede Testmethode klont per `CREATE DATABASE ... TEMPLATE ...`. `OpaaApplicationTests` teilt sich jetzt den Spring-Kontext mit der übrigen Testgruppe (`@ActiveProfiles({"local","dev"})` statt eigenem Profil). Maßnahme 4 (`maxParallelForks=2`) wurde lokal gemessen (instabil, 2 von 3 Läufen langsamer) und bewusst nicht committet. Lokale Wall-Zeit sank von 6:46 min auf 4:50 min, CI-`backend`-Job auf 4:37 min — das Abnahmekriterium „< 3:30 min" wurde damit noch nicht erreicht.

**Verifikation:** `AbstractMigrationTest.java` sowie alle in der Dateiliste genannten Migrationstestklassen sind im heutigen Stand vorhanden. Das Muster wurde in PR #698 als „Pflichtmuster für neue Migrationstests" bestätigt und dort auf drei nachträglich hinzugekommene Ausreißer angewendet; die verbliebene Lücke zum 3:30-min-Ziel wurde in PR #648 (Maßnahme 5, Kontext-Konsolidierung) weiterverfolgt.

**Themen:** tests, backend, performance, ci, migrationen, testcontainers

---

<a id="pr-943"></a>

## PR #943 — docs(query): akzeptierte Grenze der reinen Vektorsuche dokumentieren
- Gemergt: 2026-08-27
- Bezug: #912, #938

**Geliefert:** Dokumentiert in `docs/features/retrieval-algorithm.md` (Abschnitt „Bekannte offene Schwächen") die in #938 diagnostizierte und per Maintainer-Entscheidung akzeptierte Grenze der reinen Vektorsuche: ein Dokument, dessen Embedding-Signal die Anfrage nicht erreicht, bleibt unauffindbar, selbst wenn es die Anfragebegriffe wörtlich enthält (belegt am Frage-6b-Fall, Rang 50 im thomas.klein-Scope). Der naheliegende Gegenmechanismus (Hybrid-Suche) bleibt bewusst unbeauftragt. Schließt Teil 2 von #938 dokumentarisch ab, ohne Code zu ändern.

**Verifikation:** `docs/features/retrieval-algorithm.md` enthält im aktuellen Stand den Verweis auf Rang 50 und den thomas.klein-Scope im Abschnitt zu offenen Schwächen.

**Themen:** Dokumentation, Retrieval-Grenzen, Vektorsuche, #912, #938

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

---

## Anhang: geprüfte Vorgänge ohne Befund

Geliefert wie beschrieben, und das Gelieferte steht zum Stichtag noch. Belege sind das Issue, sein Pull Request und der heutige Code.

#2, #4, #6, #7, #11, #12, #14, #16, #17, #19, #23, #29, #37, #40, #42, #43, #44, #47, #49, #50, #53, #58, #60, #61, #62, #64, #65, #67, #69, #70, #71, #72, #74, #75, #78, #86, #95, #98, #102, #108, #109, #110, #111, #112, #113, #114, #121, #122, #123, #133, #137, #144, #148, #149, #152, #153, #157, #162, #165, #170, #172, #174, #176, #178, #180, #182, #184, #186, #188, #189, #193, #194, #196, #199, #200, #201, #218, #219, #221, #224, #225, #226, #227, #228, #229, #230, #232, #233, #234, #238, #244, #245, #248, #250, #252, #256, #257, #261, #263, #265, #266, #267, #268, #271, #272, #274, #276, #279, #282, #285, #288, #289, #290, #293, #294, #295, #300, #302, #304, #306, #308, #310, #311, #317, #319, #321, #324, #326, #330, #332, #333, #335, #338, #339, #340, #341, #342, #343, #344, #346, #348, #350, #355, #356, #360, #361, #362, #363, #367, #370, #373, #375, #383, #390, #394, #400, #401, #404, #406, #407, #408, #409, #410, #414, #416, #418, #419, #420, #421, #422, #423, #424, #434, #435, #436, #438, #440, #441, #445, #456, #458, #459, #461, #463, #464, #465, #466, #467, #470, #471, #475, #476, #477, #478, #479, #480, #481, #482, #483, #486, #491, #493, #495, #497, #501, #505, #507, #508, #513, #514, #515, #516, #517, #518, #519, #520, #521, #522, #523, #524, #525, #526, #527, #528, #533, #538, #544, #545, #547, #550, #551, #552, #553, #556, #557, #559, #560, #565, #566, #569, #572, #573, #575, #580, #581, #582, #583, #584, #588, #590, #591, #592, #593, #594, #595, #596, #597, #598, #600, #606, #609, #611, #614, #616, #617, #619, #623, #625, #632, #634, #636, #637, #639, #646, #650, #651, #653, #654, #658, #659, #661, #677, #682, #684, #686, #693, #707, #708, #709, #711, #712, #713, #716, #720, #721, #725, #731, #736, #737, #738, #739, #740, #744, #747, #749, #751, #755, #756, #757, #758, #759, #760, #762, #769, #771, #773, #775, #777, #782, #784, #786, #787, #788, #789, #792, #800, #805, #807, #809, #812, #814, #815, #819, #820, #821, #822, #823, #824, #826, #832, #833, #834, #835, #836, #837, #838, #839, #840, #842, #844, #845, #848, #860, #863, #875, #876, #877, #884, #886, #888, #892, #896, #912, #924, #927, #929, #937, #941, #951, #954, #956, #957, #958, #959, #966, #996, #997, #1000, #1001, #1002, #1005, #1007, #1016, #1022, #1023, PR #1, PR #91, PR #104, PR #105, PR #275, PR #287, PR #385, PR #399, PR #403, PR #412, PR #413, PR #502, PR #648, PR #695, PR #698, PR #728, PR #732, PR #761, PR #790, PR #804, PR #810, PR #818, PR #847, PR #861, PR #864, PR #867, PR #869, PR #870, PR #871, PR #872, PR #873, PR #879, PR #882, PR #893, PR #897, PR #899, PR #901, PR #902, PR #905, PR #908, PR #930, PR #936, PR #939, PR #942, PR #946, PR #1014
