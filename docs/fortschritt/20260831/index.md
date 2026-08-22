# Inventur-Index: geschlossene Issues und gemergte PRs

Automatisch erzeugt aus den GitHub-Dumps. Spalte PRs: über closingIssuesReferences bzw. Body-Referenzen verknüpfte, gemergte PRs.

## Geschlossene Issues

| Issue | Titel | geschlossen | Grund | PRs |
|---|---|---|---|---|
| #2 | Create comprehensive OPAA product vision and feature specifications | 2026-02-17 | COMPLETED | #3 |
| #4 | MVP: Define and implement minimal viable product | 2026-02-18 | COMPLETED | #5 |
| #6 | chore: scaffold Spring Boot backend with Gradle | 2026-02-18 | COMPLETED | #21 |
| #7 | chore: scaffold React frontend with TypeScript and MUI 7 | 2026-02-19 | COMPLETED | #22 |
| #8 | feat(api): define API contract with OpenAPI spec and dual mock layer | 2026-02-19 | COMPLETED | #26 |
| #9 | chore: set up PostgreSQL schema with pgvector and Liquibase | 2026-02-20 | COMPLETED | #28 |
| #10 | feat(indexing): implement complete document indexing pipeline | 2026-02-25 | COMPLETED | #34 |
| #11 | feat(query): implement vector similarity search | 2026-02-26 | COMPLETED | — |
| #12 | feat(query): implement LLM answer generation with source references | 2026-02-26 | COMPLETED | #36, #286, #291 |
| #13 | feat(api): replace mock endpoints with real implementation | 2026-02-26 | COMPLETED | #38, #286, #291 |
| #14 | feat(ui): implement chat interface with source references and feedback placeholders | 2026-02-20 | COMPLETED | #27 |
| #15 | feat(ui): add admin sidebar with indexing controls | 2026-02-20 | COMPLETED | #31 |
| #16 | chore: create Docker Compose deployment for full stack | 2026-02-20 | COMPLETED | #33 |
| #17 | test: end-to-end integration tests and MVP verification | 2026-02-26 | COMPLETED | #39 |
| #18 | feat: implement OPAA MVP | 2026-02-28 | COMPLETED | — |
| #19 | docs: update ADR-0002 with finalized technology decisions | 2026-02-18 | COMPLETED | #20 |
| #23 | chore: set up GitHub Actions CI pipeline | 2026-02-18 | COMPLETED | #24, #25 |
| #29 | feat: Add user document upload with personal workspace and cross-workspace sharing to product vision | 2026-02-20 | COMPLETED | #30 |
| #35 | feat: Erweiterte Job-Status API (Status pro Job, Liste laufender Jobs) | 2026-08-21 | COMPLETED | — |
| #37 | feat(query): filter source references by actual LLM citations | 2026-02-27 | COMPLETED | #55 |
| #40 | feat(frontend): Markdown-Renderer für LLM-Antworten | 2026-02-26 | COMPLETED | #45 |
| #41 | feat(frontend): Loading-Indicator während Dokument-Indizierung | 2026-02-27 | COMPLETED | #52 |
| #42 | feat: Distinct-Darstellung der Quellen ohne Duplikate | 2026-02-26 | COMPLETED | #46 |
| #43 | feat: In-Memory Chat-Gedächtnis für Folgefragen (MVP) | 2026-02-27 | COMPLETED | #56 |
| #44 | feat(indexing): Asynchrone Dokument-Indizierung mit konfigurierbarem ThreadPool | 2026-02-27 | COMPLETED | #52 |
| #47 | feat: configurable HTTP/1.1 mode for vLLM and other OpenAI-compatible servers | 2026-02-26 | COMPLETED | #48 |
| #49 | fix: crypto.randomUUID fails on non-HTTPS connections | 2026-02-26 | COMPLETED | #51 |
| #50 | feat: make server bind address configurable | 2026-02-26 | COMPLETED | #51 |
| #53 | feat(indexing): skip unchanged documents using SHA-256 checksum | 2026-02-27 | COMPLETED | #57 |
| #54 | feat: Erweitertes Chat-Memory mit Persistenz und Session-Verwaltung | 2026-08-15 | NOT_PLANNED | — |
| #58 | Add static landing page for project website | 2026-02-27 | COMPLETED | #59 |
| #60 | 🔍 Security & Code Review Findings (20 Issues) | 2026-08-15 | COMPLETED | — |
| #61 | 🚨 [CRITICAL] CORS Wildcard Headers Security Risk | 2026-02-28 | COMPLETED | #80 |
| #62 | 🚨 [CRITICAL] Missing Rate Limiting on API Endpoints | 2026-03-01 | COMPLETED | #84 |
| #63 | 🚨 [CRITICAL] No Authentication/Authorization Implementation | 2026-08-15 | COMPLETED | — |
| #64 | 🚨 [CRITICAL] Missing conversationId Input Validation | 2026-02-28 | COMPLETED | #79 |
| #65 | 🚨 [CRITICAL] No Observability (Metrics, Tracing, Health Checks) | 2026-03-01 | COMPLETED | #85 |
| #66 | ⚠️ [HIGH] Missing Transaction Boundaries in QueryService | 2026-02-28 | COMPLETED | #81 |
| #67 | ⚠️ [HIGH] Spotless Config Missing (ADR-0002 Violation) | 2026-02-28 | COMPLETED | — |
| #69 | 🟡 [MEDIUM] ChatMemory Lifecycle Management Unclear | 2026-02-28 | COMPLETED | #83 |
| #70 | 🟡 [MEDIUM] Error Boundary Component Not Used | 2026-02-28 | COMPLETED | #82 |
| #71 | 🟡 [MEDIUM] Sensitive Error Information in Logs | 2026-03-01 | COMPLETED | #89, #90 |
| #72 | 🔵 [LOW] Magic Numbers Without Documentation | 2026-03-02 | COMPLETED | #93 |
| #73 | 🔵 [LOW] Inconsistent Mock Profile Naming | 2026-08-14 | NOT_PLANNED | — |
| #74 | 🔵 [LOW] Complex Business Logic in Lambda Expression | 2026-03-01 | COMPLETED | #88 |
| #75 | 🔵 [LOW] Axios Error Response Type Assertion Unsafe | 2026-03-03 | COMPLETED | #94 |
| #76 | 🔵 [LOW] SQL Injection Risk in Future Migrations | 2026-08-15 | NOT_PLANNED | — |
| #86 | chore: Liquibase Changesets konsolidieren (Pre-Production Cleanup) | 2026-03-01 | COMPLETED | #87 |
| #95 | URL-based document indexing via Apache mod_autoindex crawling | 2026-03-06 | COMPLETED | #96 |
| #98 | PostgreSQL 18 Docker container fails to start due to volume mount path change | 2026-03-06 | COMPLETED | #99 |
| #100 | Expose Ollama model configuration in docker-compose | 2026-03-06 | COMPLETED | #101 |
| #102 | Add branch protection rules for main | 2026-03-06 | COMPLETED | #103 |
| #107 | feat: Introduce Workspaces & Access Control | 2026-08-14 | COMPLETED | — |
| #108 | feat(auth): Spring Security with OIDC authentication | 2026-03-07 | COMPLETED | #135 |
| #109 | feat(auth): user entity and database schema | 2026-03-07 | COMPLETED | — |
| #110 | feat(auth): System-Admin role and API authorization | 2026-03-07 | COMPLETED | #136 |
| #111 | feat(workspace): workspace and membership entities | 2026-03-07 | COMPLETED | #131 |
| #112 | feat(workspace): workspace CRUD API | 2026-03-08 | COMPLETED | #132 |
| #113 | feat(workspace): personal workspace auto-creation | 2026-03-08 | COMPLETED | #140 |
| #114 | feat(workspace): membership management and roles API | 2026-03-08 | COMPLETED | #140 |
| #115 | feat(indexing): workspace_ids in chunk metadata and query filter | 2026-08-14 | COMPLETED | — |
| #116 | feat(upload): document metadata table and workspace-aware upload | 2026-08-14 | COMPLETED | — |
| #117 | feat(workspace): connector-workspace integration | 2026-08-14 | COMPLETED | — |
| #118 | feat(workspace): document deletion and exclude mechanism | 2026-08-14 | COMPLETED | — |
| #119 | feat(library): Speicherkontingent je Bibliothek und Organisation | 2026-08-21 | COMPLETED | #700 |
| #120 | feat(ui): login flow and session management | 2026-03-07 | COMPLETED | #135 |
| #121 | feat(ui): workspace view and workspace-filtered search | 2026-03-07 | COMPLETED | #141 |
| #122 | feat(ui): workspace management (members and roles) | 2026-03-08 | COMPLETED | #142, #150 |
| #123 | feat(query): Gesprächsgedächtnis je Person trennen | 2026-08-21 | COMPLETED | — |
| #124 | feat(audit): audit logging for workspace actions | 2026-08-14 | COMPLETED | — |
| #125 | test: end-to-end tests for workspace flow | 2026-08-14 | COMPLETED | — |
| #133 | [FEAT] Automatically generate frontend/backend DTOs from OpenAPI spec | 2026-03-08 | COMPLETED | #134 |
| #137 | perf(auth): avoid DB round-trip on every request in UserProvisioningFilter | 2026-08-20 | COMPLETED | — |
| #138 | feat(auth): rate limit /api/v1/auth/login to mitigate brute-force attempts | 2026-08-14 | NOT_PLANNED | — |
| #139 | feat(auth): add basic-profile user management for system admins | 2026-08-14 | NOT_PLANNED | — |
| #144 | security(space): Mitgliederliste eines Space nur für Space-Admins und Eigentümer | 2026-08-20 | COMPLETED | #674 |
| #148 | feat: Dark/Light Mode Toggle in User Preferences | 2026-03-08 | COMPLETED | — |
| #149 | fix(workspace-ui): state leak on logout, member display names, collapsible sections, remove redundant info alert | 2026-03-08 | COMPLETED | #151 |
| #152 | refactor: Generate workspace DTOs from OpenAPI spec instead of handwriting them | 2026-03-08 | COMPLETED | #154, #159 |
| #153 | refactor: Remove Spring "mock" profile from codebase | 2026-03-08 | COMPLETED | #155, #160 |
| #157 | Externalize docker-compose environment variables into .env file | 2026-03-08 | COMPLETED | #158 |
| #162 | chore: centralize all dependencies in version catalog with bundles and update project rules | 2026-03-09 | COMPLETED | #163 |
| #164 | fix(auth): eliminate implicit algorithm coupling in basic-auth JWT signing/decoding | 2026-03-09 | COMPLETED | #166 |
| #165 | fix: URL indexer stores temp filename instead of original filename in document DB | 2026-03-09 | COMPLETED | #169 |
| #170 | fix(indexing): StackOverflowError when indexing URLs with long query strings | 2026-03-09 | COMPLETED | #171 |
| #172 | Document the agent organization and development workflow | 2026-07-16 | COMPLETED | #173 |
| #174 | Add product-manager agent definition and modernize feature/spec templates | 2026-07-16 | COMPLETED | #175 |
| #176 | Expand coding-standards-reviewer into a full code-reviewer agent | 2026-07-17 | COMPLETED | #177 |
| #178 | Add developer agent definition | 2026-07-17 | COMPLETED | #179 |
| #180 | Add qa-engineer agent definition and E2E ownership workflow | 2026-07-17 | COMPLETED | #181 |
| #182 | Add marketing agent definition (positioning-first) | 2026-07-17 | COMPLETED | #183 |
| #184 | feat(agents): support shared roles across Claude, Codex, and OpenCode | 2026-07-18 | COMPLETED | #185 |
| #186 | Projektsprache auf Deutsch umstellen | 2026-08-01 | COMPLETED | #187 |
| #188 | chore(backend): migrate to Spring Boot 4.1 and bump all backend dependencies | 2026-08-01 | COMPLETED | #190 |
| #189 | chore(frontend): bump all frontend dependencies to latest stable (MUI 9, Vite 8, TypeScript 6, ESLint 10) | 2026-08-02 | COMPLETED | #191 |
| #193 | fix(frontend): hamburger menu icon invisible in mobile header (white on white) | 2026-08-20 | COMPLETED | #669 |
| #194 | docs: document git worktree usage for parallel agent sessions | 2026-08-02 | COMPLETED | #195 |
| #196 | ci: publish backend and frontend Docker images to GHCR | 2026-08-02 | COMPLETED | #197 |
| #199 | Rename workspace to space, add organization scope and reshape space roles | 2026-08-02 | COMPLETED | #254 |
| #200 | Introduce groups as permission subjects | 2026-08-02 | COMPLETED | #283 |
| #201 | Knowledge library as the document container, with data migration | 2026-08-03 | COMPLETED | #305 |
| #202 | Asset permissions and permission-aware vector search | 2026-08-04 | COMPLETED | #309 |
| #203 | Space-asset association as pure curation | 2026-08-21 | COMPLETED | #706 |
| #208 | Stewards: group role for accepting shares | 2026-08-14 | COMPLETED | #331 |
| #218 | feat(agents): add six public-administration stakeholder agents for concept review | 2026-08-02 | COMPLETED | #220 |
| #219 | docs: Projektsprache Deutsch für Issues und Pull Requests festlegen | 2026-08-02 | COMPLETED | #222 |
| #221 | feat: Anwendungstexte auf Deutsch umstellen (Frontend und Backend) | 2026-08-02 | COMPLETED | #223, #286, #291 |
| #224 | Epic: Suchqualität messbar machen — Eval-Korpus und Retrieval-Regression | 2026-08-22 | COMPLETED | — |
| #225 | feat(eval): Korpus-Generator für die Domäne Comichelden | 2026-08-02 | COMPLETED | #249 |
| #226 | feat(eval): Golden Dataset aus dem Frontmatter des Korpus ableiten | 2026-08-02 | COMPLETED | #273 |
| #227 | test(eval): Retrieval-Metrik-Harness (Hit Rate, MRR, nDCG, Recall) | 2026-08-03 | COMPLETED | #292 |
| #228 | ci(eval): Retrieval-Regressionsjob mit Baseline und Schwellenwerten | 2026-08-03 | COMPLETED | #301 |
| #229 | feat(demo): Rheinfurt-Korpus und RSS-Feed im Compose-Stack bereitstellen | 2026-08-21 | COMPLETED | #722 |
| #230 | feat(demo): Demo-Instanz Rheinfurt auf opaa.ewerlin.com oder alternativem Host ausrollen | 2026-08-22 | COMPLETED | — |
| #231 | test(e2e): Grundgerüst für browserbasierte End-to-End-Tests | 2026-08-02 | COMPLETED | #251 |
| #232 | test(e2e): Smoke-Test für das Demo-Profil | 2026-08-21 | COMPLETED | #729 |
| #233 | test(e2e): E2E-Suite auf das gemeinsame e2e-Seed-Profil umstellen | 2026-08-21 | COMPLETED | #726 |
| #234 | feat(eval): Eval-Domäne Sehenswürdigkeiten in 200 europäischen Großstädten (mehrchunkig, deutsch) | 2026-08-22 | COMPLETED | #730 |
| #235 | feat(demo): Demo-Domänen in getrennte Wissensbibliotheken legen (blockiert) | 2026-08-22 | NOT_PLANNED | — |
| #237 | Verzeichnissynchronisation als Rechteereignis behandeln | 2026-08-03 | COMPLETED | #297 |
| #238 | Historisierung von Rechten und Gruppenmitgliedschaften | 2026-08-17 | COMPLETED | #427 |
| #244 | docs: bestehende öffentliche Instanz opaa.ewerlin.com in der Deployment-Dokumentation beschreiben | 2026-08-02 | COMPLETED | #247 |
| #245 | fix(ci): CLA-Workflow schlägt bei Kommentaren auf Issues fehl | 2026-08-02 | COMPLETED | #246 |
| #248 | feat(ci): Täglichen Projektreport als GitHub-Pages-Seite mit Atom-Feed veröffentlichen | 2026-08-02 | COMPLETED | #259 |
| #250 | docs(security): Härtungsanforderungen für erreichbare Compose-Deployments dokumentieren | 2026-08-21 | COMPLETED | #714 |
| #252 | docs: Standardwerte in docs/deployment.md gegen application.yml abgleichen | 2026-08-21 | COMPLETED | #715 |
| #255 | fix(auth): mock-Modus funktionsfähig machen oder aus Default und Doku entfernen | 2026-08-14 | COMPLETED | #328 |
| #256 | test(e2e): Lokale Modellbereitstellung für den E2E-Stack | 2026-08-21 | COMPLETED | #690 |
| #257 | docs: Einheitliche Testkonto-Konvention dokumentieren | 2026-08-21 | COMPLETED | #689 |
| #258 | docs: Beispiel-Secret für OPAA_AUTH_BASIC_SECRET verhindert Backend-Start | 2026-08-14 | NOT_PLANNED | — |
| #260 | feat(auth): mehrere opaa.auth.basic.users-Einträge konfigurierbar machen | 2026-08-14 | COMPLETED | #328 |
| #261 | fix(ci): Tagesreport landet beim ersten Lauf auf falschem Branch | 2026-08-02 | COMPLETED | #262 |
| #263 | docs: Nummernkollision zweier ADRs mit der Nummer 0008 auflösen | 2026-08-02 | COMPLETED | #264 |
| #265 | fix(space): persönlicher Space kann bei gleichzeitiger Erstanmeldung doppelt entstehen | 2026-08-02 | COMPLETED | #280 |
| #266 | perf(space): eigenständiger Index auf space_memberships.space_id fehlt | 2026-08-02 | COMPLETED | #280 |
| #267 | feat(security): Zielprüfung für URL-Indizierung ergänzen (private Adressbereiche, Schema) | 2026-08-21 | COMPLETED | #699 |
| #268 | docs: PR-Regeln an Merge ohne Approval anpassen | 2026-08-02 | COMPLETED | #270 |
| #271 | security(auth): AdminController setzt die Organisationsgrenze nicht durch | 2026-08-20 | COMPLETED | #679 |
| #272 | feat(frontend): Space-Sichtbarkeit in der Oberfläche nutzbar machen | 2026-08-20 | COMPLETED | #671 |
| #274 | fix(eval): Nachziehbedarf aus dem Review des Golden Datasets | 2026-08-02 | COMPLETED | #277 |
| #276 | fix(ci): Eigenes Secret für den Tagesreport statt des Anwendungsschlüssels | 2026-08-02 | COMPLETED | #278 |
| #279 | feat(ci): Anthropic als Anbieter für die Report-Zusammenfassung unterstützen | 2026-08-02 | COMPLETED | #281 |
| #282 | fix(eval): Sentinel-Feldbezogenheit und Ground-Truth-Fingerabdruck im Golden Dataset | 2026-08-02 | COMPLETED | #284 |
| #285 | feat(ci): Report-Zusammenfassung an Epics ausrichten und kürzen | 2026-08-02 | COMPLETED | #286 |
| #288 | test(backend): FK-abhängige Integrationstests auf echtes Liquibase-Schema umstellen | 2026-08-03 | COMPLETED | #298 |
| #289 | feat(backend): Organisationsgrenze auf Datenbankebene symmetrisch absichern | 2026-08-20 | COMPLETED | #678 |
| #290 | fix(ci): Fehlzuordnungen im Epic-Report beheben | 2026-08-02 | COMPLETED | #291 |
| #293 | fix(auth): Race bei paralleler Erstanmeldung erzeugt 500er auf uq_users_subject_issuer | 2026-08-03 | COMPLETED | #299 |
| #294 | fix(auth): Fehler bei der Anlage des persönlichen Space darf den Login-Request nicht scheitern lassen | 2026-08-20 | COMPLETED | — |
| #295 | docs(agents): Branch-Regel für Fehlerbehebungen und Hotfixes klarstellen | 2026-08-02 | COMPLETED | #296 |
| #300 | fix(group): DirectorySyncStatusRecorder behandelt Race auf uk_directory_sync_status_organization nicht | 2026-08-14 | COMPLETED | #316 |
| #302 | docs(agents): Umgang mit Transaktionen in die Entwickler-Rollendefinition aufnehmen | 2026-08-03 | COMPLETED | #303 |
| #304 | eval(golden): category:crosslingual und language:de sind identische Fallmengen | 2026-08-20 | COMPLETED | #673 |
| #306 | eval(baseline): Fallzahlbasierte Regressionsprüfung für Paare mit Toleranz < 1/n | 2026-08-21 | COMPLETED | #694 |
| #307 | fix(auth): Gleichzeitige Erstanmeldungen verschiedener Nutzer erschöpfen den Connection-Pool | 2026-08-21 | COMPLETED | #702 |
| #308 | test(backend): GroupServiceIntegrationTest auf echtes Liquibase-Schema umstellen | 2026-08-21 | COMPLETED | #691 |
| #310 | fix(api): GlobalExceptionHandler mappt ResponseStatusException und DataIntegrityViolationException nicht | 2026-08-14 | COMPLETED | #314 |
| #311 | Retrieval-Regression erkannt (automatischer Lauf) | 2026-08-14 | COMPLETED | #315 |
| #312 | fix(ci): Zeitfenster des Tagesreports nachvollziehbar und lückenlos machen | 2026-08-04 | COMPLETED | #313 |
| #317 | docs: GraphRAG-Recherche als Entscheidungsgrundlage aufnehmen | 2026-08-14 | COMPLETED | #318 |
| #319 | docs: Agentenanweisungen entpersonalisieren, eval/ ergänzen und Duplikat auflösen | 2026-08-14 | COMPLETED | #320 |
| #321 | feat(ci): Tagesreport auf Management Summary umstellen | 2026-08-14 | COMPLETED | #322 |
| #323 | Auth-Konzept reviewen: Werden mock- und basic-Modus noch gebraucht? | 2026-08-14 | COMPLETED | #328 |
| #324 | Eigenen Code (evalTest) auf Jackson 3 umstellen und ADR-0007 durch Praxis-Hinweis ersetzen | 2026-08-14 | COMPLETED | #325 |
| #326 | ADR-Bestand entschlacken: ADR-0001 und ADR-0002 aktualisieren, ADR-0008 in die Spezifikation überführen | 2026-08-14 | COMPLETED | #327 |
| #330 | Rechtemodell verschlanken: Asset-Rolle USER und Gruppenrollen streichen | 2026-08-14 | COMPLETED | #331 |
| #332 | docs: Startbefehle nennen das verpflichtende Auth-Profil nicht | 2026-08-14 | COMPLETED | #334 |
| #333 | Space-Arten durch Attribute ersetzen, Sichtbarkeit von Inhalten umbenennen | 2026-08-14 | COMPLETED | #337, #345 |
| #335 | Epics auf native GitHub-Sub-Issues umstellen | 2026-08-14 | COMPLETED | #336 |
| #338 | Epic: Produktvision auf die öffentliche Verwaltung ausrichten | 2026-08-15 | COMPLETED | — |
| #339 | docs: Produktvision, ADR und Use-Cases auf die neue Ausrichtung umstellen | 2026-08-14 | COMPLETED | #359 |
| #340 | docs: Feature-Spezifikationen entlang der elf Themenbereiche neu schneiden | 2026-08-15 | COMPLETED | — |
| #341 | docs: Einstiegsdokumente und Umsetzungsstand an die neue Ausrichtung angleichen | 2026-08-14 | COMPLETED | #372 |
| #342 | docs(marketing): Landing-Page, Pitch und One-Pager auf den Verwaltungston umstellen | 2026-08-14 | COMPLETED | #369 |
| #343 | docs: Backlog gegen die neue Produktausrichtung sichten | 2026-08-14 | COMPLETED | #364 |
| #344 | Epic: Konzepte und Abstraktionen gegen die neue Produktausrichtung prüfen | 2026-08-15 | COMPLETED | — |
| #346 | docs(agents): Sub-Issue-Regel für Epics in AGENTS.md aufnehmen | 2026-08-14 | COMPLETED | #347 |
| #348 | Vektorspeicher-Austauschbarkeit: brauchen wir sie noch? | 2026-08-14 | COMPLETED | #377 |
| #350 | Cloud-Deployment und Managed Service gegen das Souveränitätsversprechen prüfen | 2026-08-14 | COMPLETED | #378 |
| #351 | Umfang der Storage-Backend-Abstraktion festlegen | 2026-08-14 | COMPLETED | #380 |
| #352 | Zielbild der Chat-Kanäle festlegen | 2026-08-14 | COMPLETED | #379 |
| #353 | Standardposition der Modellanbieter auf lokal-first umstellen | 2026-08-14 | COMPLETED | #384 |
| #354 | Zitierzwang in der bestehenden Query-Pipeline bewerten | 2026-08-14 | COMPLETED | #396 |
| #355 | Umfang des revisionssicheren Audit-Loggings schneiden | 2026-08-14 | COMPLETED | #398 |
| #356 | Organisationsgrenze über die Anwendungsschicht hinaus absichern | 2026-08-14 | COMPLETED | #397 |
| #357 | Bürgerassistent und öffentliches Widget als Ausblick festhalten | 2026-08-14 | COMPLETED | #381 |
| #360 | docs(features): Wissensschicht, Wissensquellen und Modellsteuerung neu fassen (A, B, E) | 2026-08-14 | COMPLETED | #368 |
| #361 | docs(features): Verteilungsmodell ergänzen und Agentenspezifikation anlegen (C, D) | 2026-08-14 | COMPLETED | #365 |
| #362 | docs(features): Identität, Nachweisbarkeit und Governance neu fassen (F, G, H) | 2026-08-14 | COMPLETED | #371 |
| #363 | docs(features): Kanäle, Betrieb und Verwaltungs-Spezifika neu fassen (I, J, K) | 2026-08-14 | COMPLETED | #366 |
| #367 | docs: Anbieternamen in der Vorbild-Analyse von spaces-and-assets.md klären | 2026-08-14 | COMPLETED | #382 |
| #373 | GitHub Pages: Landing-Page als Startseite, Tagesreport darunter verlinken | 2026-08-14 | COMPLETED | #376 |
| #374 | fix(indexing): Chunking ohne Überlappung trennt Definitionen von ihrer Überschrift | 2026-08-14 | COMPLETED | #402 |
| #375 | fix(indexing): Dateisystem- und Netzindizierung führen unterschiedliche Endungslisten | 2026-08-14 | COMPLETED | #405 |
| #383 | Tagesreport: Blättern zwischen den Tagen im Report-Kopf | 2026-08-14 | COMPLETED | — |
| #386 | feat(query): Belege gegen die abgerufenen Fundstellen prüfen | 2026-08-21 | COMPLETED | #697 |
| #387 | feat(query): Verweigerung im Zitierzwang mit Auskunft über den Suchvorgang | 2026-08-21 | NOT_PLANNED | — |
| #388 | feat(query): Zitierzwang am Space schalten und mit der Systemvorgabe verrechnen | 2026-08-21 | NOT_PLANNED | — |
| #389 | docs(query): Entscheidungsvorlage zur inhaltlichen Deckungsprüfung (Stufe 2 des Zitierzwangs) | 2026-08-21 | NOT_PLANNED | — |
| #390 | test(backend): Organisationsgrenze durch strukturellen Prüflauf gegen das Schema nachweisen | 2026-08-20 | COMPLETED | #688 |
| #391 | feat(audit): Protokollablage und Protokollsatz, nur anfügend | 2026-08-17 | COMPLETED | #428 |
| #392 | feat(audit): Rechte- und Verwaltungsereignisse an den bestehenden Diensten erfassen | 2026-08-17 | COMPLETED | #444 |
| #393 | feat(audit): Zugriffsweg für die Revision ohne personenbezogene Auswertung | 2026-08-17 | COMPLETED | #449 |
| #394 | feat(audit): Zugriff auf Protokolldaten erzeugt selbst einen Eintrag | 2026-08-17 | COMPLETED | #450 |
| #395 | feat(audit): Aufbewahrung der Protokolldaten mit automatischer Löschung | 2026-08-17 | COMPLETED | #454 |
| #400 | fix(db): Übergeordnete Gruppe an die Organisation binden | 2026-08-20 | COMPLETED | #675 |
| #401 | feat(db): Indizierungsläufe an die Organisation binden | 2026-08-20 | COMPLETED | #681 |
| #404 | feat(indexing): Zulässige Dokumenttypen über den erkannten Inhalt statt über die Dateiendung bestimmen | 2026-08-21 | COMPLETED | #704 |
| #406 | fix(query): Über die Indexierung eingespielte Dokumente sind im Chat für niemanden auffindbar | 2026-08-15 | COMPLETED | — |
| #407 | Retrieval-Regression erkannt (automatischer Lauf) | 2026-08-16 | COMPLETED | — |
| #408 | fix(indexing): Vor #202 indizierte Chunks tragen kein library_id und sind dauerhaft unauffindbar | 2026-08-15 | COMPLETED | — |
| #409 | security(frontend): Sicherheits-Header im Webserver ergänzen | 2026-08-20 | COMPLETED | #670 |
| #410 | docs: Backlog-Sichtung abschließen und Statusaussage zum Upload berichtigen | 2026-08-15 | COMPLETED | #411 |
| #414 | ci(eval): evaluateRetrieval führt BaselineRegressionTest ohne Report aus und schlägt fehl | 2026-08-15 | COMPLETED | #415 |
| #416 | fix(eval): Zweite Review-Runde zu PR #301 nachreichen — harte Untergrenze, Sechsfachbefund, Baseline-Diff | 2026-08-15 | COMPLETED | #417 |
| #418 | feat(library): Bibliotheksliste an die Rechteformel angleichen und die eigene Rolle ausweisen | 2026-08-17 | COMPLETED | #425 |
| #419 | feat(indexing): Indizierungsläufe zielen auf eine wählbare Wissensbibliothek statt auf die System-Bibliothek | 2026-08-17 | COMPLETED | #431 |
| #420 | feat(upload): Dokumente über die REST-API in eine wählbare Bibliothek hochladen und wieder entfernen | 2026-08-17 | COMPLETED | #432 |
| #421 | feat(frontend): Wissensbibliotheken auflisten und verwalten | 2026-08-17 | COMPLETED | #437 |
| #422 | feat(frontend): Dokumente je Wissensbibliothek anzeigen und hochladen | 2026-08-17 | COMPLETED | #442 |
| #423 | feat(frontend): Rechte an einer Wissensbibliothek verwalten | 2026-08-17 | COMPLETED | #446 |
| #424 | test(e2e): Wissensbibliotheken — Upload, Freigabe und rechtebewusste Suche | 2026-08-17 | COMPLETED | #453 |
| #433 | fix(indexing): Gelöschte Zielbibliothek mitten im Lauf sauber behandeln (Warnung statt failed) | 2026-08-20 | COMPLETED | #602 |
| #434 | feat(upload): Rate-Limit und/oder asynchrone Verarbeitung für den Dokument-Upload-Endpunkt | 2026-08-20 | COMPLETED | #589 |
| #435 | feat(upload): Inhaltsbasierte Formaterkennung für nutzerkontrollierte Uploads | 2026-08-20 | COMPLETED | #577 |
| #436 | fix(library): 403-vs-404-Unterscheidung bei fehlendem Zugriff auf Bestands-Endpunkten vereinheitlichen | 2026-08-20 | COMPLETED | #608 |
| #438 | feat(frontend): Eigentümername und Dokumentanzahl in LibraryListResponse ausweisen | 2026-08-20 | COMPLETED | #601 |
| #439 | feat(frontend): SYSTEM-Bibliothek über die Oberfläche administrierbar machen | 2026-08-19 | COMPLETED | — |
| #440 | fix(frontend): Space-, Gruppen- und Bibliotheks-Store beim Logout zurücksetzen | 2026-08-20 | COMPLETED | #574 |
| #441 | fix(library): createLibrary prüft Group#isDissolved() nicht vor dem Anlegen des Eigentümer-Grants | 2026-08-20 | COMPLETED | #599 |
| #443 | fix(library): Löschen von FILESYSTEM-/HTTP_DIRECTORY-Dokumenten wirkt nur bis zum nächsten Indizierungslauf | 2026-08-20 | COMPLETED | — |
| #448 | Deutsche Fehlermeldungen im Grants-Backend: rohe Enum-Namen und fehlende Umlaute | 2026-08-20 | COMPLETED | #576 |
| #459 | docs(agents): UX-Designer-Rolle in der Agenten-Organisation einführen | 2026-08-17 | COMPLETED | #460 |
| #461 | Roadmap-Meilenstein 1 (31.08.2026) in Produktvision aufnehmen | 2026-08-18 | COMPLETED | #462 |
| #463 | Epic: Quellentypen erweiterbar machen und RSS-Feeds erschließen | 2026-08-19 | COMPLETED | — |
| #464 | docs(decisions): ADR zum Quellentypmodell der Indizierung | 2026-08-18 | COMPLETED | #472 |
| #465 | refactor(indexing): Quellentyp ausdrücklich übergeben und Executor über eine Registry auflösen | 2026-08-18 | COMPLETED | #473 |
| #466 | feat(indexing): RSS_FEED als Quellentyp und Parser für RSS-2.0-Feeds | 2026-08-18 | COMPLETED | #474 |
| #467 | feat(indexing): RSS-Feeds indizieren — Einträge auflösen und Detailseiten übernehmen | 2026-08-18 | COMPLETED | #490 |
| #468 | feat(indexing): Anlagen an Detailseiten übernehmen, mit Profil für den Government Site Builder | 2026-08-18 | COMPLETED | #492 |
| #469 | feat(admin): Quellentyp im Indizierungsformular wählbar machen | 2026-08-19 | COMPLETED | — |
| #470 | docs(sources): Feed-Quellen und Quellentypmodell in der Spezifikation nachführen | 2026-08-18 | COMPLETED | #494 |
| #471 | test(e2e): RSS-Quelle über die Admin-Oberfläche indizieren | 2026-08-19 | COMPLETED | #510 |
| #475 | docs(decisions): ADR-0018 — Quellkonfiguration in der Bibliothek | 2026-08-18 | COMPLETED | #487 |
| #476 | feat(library): Quellentyp und Quellkonfiguration an der Bibliothek (Schema, Entity, API) | 2026-08-19 | COMPLETED | #489 |
| #477 | feat(library): Dokumentzahl in der Bibliotheksliste | 2026-08-18 | COMPLETED | #488 |
| #478 | feat(indexing): Indizierungsanstoß je Bibliothek aus gespeicherter Konfiguration | 2026-08-19 | COMPLETED | #500 |
| #479 | feat(library): Upload nur in UPLOAD-Bibliotheken und Löschverhalten für Konnektorbibliotheken | 2026-08-19 | COMPLETED | #503 |
| #480 | feat(frontend): Bibliotheksanlage mit Typauswahl aus Templates | 2026-08-19 | COMPLETED | #498 |
| #481 | feat(frontend): Bibliotheksdetailseite mit typspezifischem Bereich | 2026-08-19 | COMPLETED | #506 |
| #482 | docs(sources): Spezifikation auf Bibliothekstypen nachführen | 2026-08-19 | COMPLETED | #512 |
| #483 | feat(security): Zugangsdaten der Quellkonfiguration sicher verwahren | 2026-08-19 | COMPLETED | #504 |
| #484 | feat(security): Pfad-Allowlist und Berechtigung für Konnektorbibliotheken | 2026-08-19 | COMPLETED | #511 |
| #485 | feat(indexing): Zeitplan je Bibliothek für Indizierungsläufe | 2026-08-21 | COMPLETED | #705 |
| #486 | feat: Bibliothekstypen — Quellkonfiguration wandert in die Bibliothek | 2026-08-20 | COMPLETED | — |
| #491 | fix(indexing): Skip-Prüfung des URL-Wegs ignoriert die Zielbibliothek | 2026-08-20 | COMPLETED | #645 |
| #493 | feat(library): Herkunft von Feed-Anlagen in API und Oberfläche sichtbar machen | 2026-08-20 | COMPLETED | #638 |
| #495 | docs(agents): Pre-Push-Verifikation für Nachbesserungsrunden verschlanken | 2026-08-19 | COMPLETED | #496 |
| #497 | test(backend): Migrationstests dominieren die Suite — Template-DB und geteilter Container | 2026-08-21 | COMPLETED | — |
| #501 | fix(indexing): Hängengebliebene RUNNING-Jobs sperren ihre Bibliothek dauerhaft | 2026-08-20 | COMPLETED | #649 |
| #505 | feat(indexing): RSS-Executor nutzt hinterlegte Zugangsdaten nicht | 2026-08-20 | COMPLETED | #642 |
| #507 | feat(library): Quellkonfiguration nur für Bearbeitende sichtbar machen | 2026-08-20 | COMPLETED | #657 |
| #508 | ci(e2e): Playwright-Install hängt und frisst das Job-Timeout | 2026-08-19 | COMPLETED | #509 |
| #513 | feat(indexing): Übersprungene Dokumente eines Laufs mit Grund in der Oberfläche anzeigen | 2026-08-20 | COMPLETED | #604 |
| #514 | feat(library): Verbindungstest für Quellkonfiguration im Erstellungsdialog | 2026-08-19 | COMPLETED | #537 |
| #515 | feat(frontend): Quellentyp „Verzeichnisliste“ in „Webverzeichnis“ umbenennen | 2026-08-19 | COMPLETED | #530 |
| #516 | feat(frontend): Quellkonfiguration einer Bibliothek nachträglich bearbeiten | 2026-08-19 | COMPLETED | #542 |
| #517 | feat(library): Indizierte Dokumente für alle Quellentypen anzeigen — mit Paging und Stichwortsuche | 2026-08-19 | COMPLETED | #540 |
| #518 | fix(indexing): RSS-Läufe zählen Feed-Einträge statt indizierter Dokumente — Anhänge fehlen in der Anzeige | 2026-08-19 | COMPLETED | #534 |
| #519 | fix(deployment): nginx-Limit von 1 MB verursacht 413 beim Dokument-Upload im Compose-Setup | 2026-08-19 | COMPLETED | #532 |
| #521 | chore(library): System-Wissensbibliothek entfernen | 2026-08-19 | COMPLETED | #536 |
| #522 | chore(auth): Automatische persönliche Upload-Bibliothek beim Login entfernen | 2026-08-19 | COMPLETED | #546 |
| #523 | Epic: Chats im Space und Suchbereich per @-Bibliotheksreferenzen | 2026-08-20 | COMPLETED | — |
| #524 | Spezifikation an Chat-im-Space und @-Bibliotheksreferenzen anpassen | 2026-08-19 | COMPLETED | #531 |
| #525 | Persistente Chats in genau einem Space (Grundlage, ausschließlich privat) | 2026-08-19 | COMPLETED | #541 |
| #526 | Suchbereich über Bibliotheksreferenzen und Schalter „Wissen nutzen" im Query-Endpunkt | 2026-08-19 | COMPLETED | #535 |
| #527 | Chats unterhalb von Spaces führen (Routen, Chatliste, persistenter Verlauf) | 2026-08-20 | COMPLETED | #548 |
| #528 | @-Bibliotheksreferenzen und Schalter „Wissen nutzen" im Eingabefeld; Space-Filter entfernen | 2026-08-19 | COMPLETED | #539 |
| #529 | E2E-Abdeckung: Chat im Space, @-Referenzen und Wissens-Schalter | 2026-08-20 | COMPLETED | #554 |
| #533 | Veraltete Space-Arten und „Ablegen"-Terminologie in CONCEPTS.md bereinigen | 2026-08-20 | COMPLETED | #568 |
| #538 | security(indexing): HTTP-Client folgt Redirects und sendet dabei den Authorization-Header weiter | 2026-08-20 | COMPLETED | #579 |
| #543 | Space mit fremden privaten Chats ist dauerhaft unlöschbar | 2026-08-20 | COMPLETED | #613 |
| #544 | feat(library): Verbindungstest auch im Bearbeiten-Dialog der Quellkonfiguration | 2026-08-20 | COMPLETED | #615 |
| #545 | fix(audit): Änderung der Quellkonfiguration erzeugt keinen Audit-Eintrag | 2026-08-20 | COMPLETED | #578 |
| #547 | test(e2e): E2E-Abdeckung für Upload-Limit, Verbindungstest, Dokumentliste und Quellkonfig-Bearbeitung | 2026-08-20 | COMPLETED | #549 |
| #550 | feat(indexing): HTTP_DIRECTORY versteht nur das HTMLTable-Autoindex-Layout | 2026-08-20 | COMPLETED | #612 |
| #551 | fix(library): Verbindungstest-Meldungen ohne Umlaute und mit falschem Singular | 2026-08-20 | COMPLETED | #571 |
| #552 | Retrieval-Regression erkannt (automatischer Lauf) | 2026-08-20 | COMPLETED | #563 |
| #553 | PATCH-Anfragen scheitern mit 403: Methode fehlt in der CORS-Konfiguration, Frontend-Proxy überschreibt X-Forwarded-Proto | 2026-08-20 | COMPLETED | #555 |
| #556 | Sidebar-Chatliste folgt nicht dem ausgewählten Space | 2026-08-20 | COMPLETED | #558 |
| #557 | Chat-Titel nach der ersten Antwort per LLM ermitteln | 2026-08-20 | COMPLETED | #561 |
| #559 | Chat-Seite bleibt im Lade-Spinner hängen, wenn „Neuer Chat" einen laufenden loadChat unterbricht | 2026-08-20 | COMPLETED | #562 |
| #560 | Suchbereich als Chip-Leiste: @Alles-Wissen statt Schalter „Wissen nutzen" | 2026-08-20 | COMPLETED | #564 |
| #565 | chatStore-Persistierung: Rollback ohne chatId-Guard und parallele PATCHes unserialisiert | 2026-08-20 | COMPLETED | #570 |
| #566 | AGENTS.md: Epics nach Abschluss aller Sub-Issues schließen | 2026-08-20 | COMPLETED | #567 |
| #569 | Veraltete Space- und „Ablegen"-Terminologie in access-control.md und security-and-compliance.md bereinigen | 2026-08-20 | COMPLETED | #605 |
| #572 | Umlaut-Ersatzschreibweisen in weiteren nutzerseitigen Backend-Meldungen bereinigen | 2026-08-20 | COMPLETED | #620 |
| #573 | chatStore: Modul-Maps der Einstellungs-Persistierung aufräumen und Navigation-Race beim bestätigten Zustand | 2026-08-20 | COMPLETED | #618 |
| #575 | Frontend-Stores: In-flight-Antworten schreiben nach Logout/Reset wieder in geleerte Stores | 2026-08-20 | COMPLETED | #626 |
| #580 | docs(design): Design-Guidelines des Zielbild-Designsystems dokumentieren | 2026-08-20 | COMPLETED | #603 |
| #581 | feat(frontend): Design-Tokens und Theme-Fundament des neuen Designsystems | 2026-08-20 | COMPLETED | #622 |
| #582 | feat(backend): Branding-Systemeinstellungen mit API | 2026-08-20 | COMPLETED | #630 |
| #583 | feat(frontend): Branding über die Weboberfläche konfigurieren und im Theme anwenden | 2026-08-20 | COMPLETED | #643 |
| #584 | docs(design): Barrierefreiheits-Richtlinie (BITV 2.0 / WCAG 2.1 AA) mit Prüfliste | 2026-08-20 | COMPLETED | #624 |
| #585 | feat(frontend): A11y-Basisausstattung — Landmarken, Fokusführung, reduzierte Bewegung | 2026-08-20 | COMPLETED | #629 |
| #586 | ci(frontend): Automatisierte Barrierefreiheits-Prüfungen in Lint und E2E-Suite | 2026-08-20 | COMPLETED | #640 |
| #587 | feat(frontend): App-Shell und Seitenleiste nach Zielbild — Space-Wechsler, Chats, Bereichsnavigation | 2026-08-20 | COMPLETED | #652 |
| #588 | feat(frontend): Anmeldeseite im neuen Design | 2026-08-21 | COMPLETED | #703 |
| #590 | feat(frontend): Chat-Verlauf im neuen Design — Antworten mit Fußnoten-Fundstellen | 2026-08-20 | COMPLETED | #668 |
| #591 | feat(frontend): Eingabezeile mit Suchbereichs-Statuszeile und @-Vorschlag im neuen Design | 2026-08-20 | COMPLETED | #672 |
| #592 | feat(frontend): Belegfenster — seitliche Leiste mit allen Fundstellen einer Antwort | 2026-08-20 | COMPLETED | #676 |
| #593 | feat(frontend): Spaces-Übersicht als Kartenliste | 2026-08-20 | COMPLETED | #683 |
| #594 | feat(frontend): Space-Anlage als mehrstufiger Assistent | 2026-08-21 | COMPLETED | #687 |
| #595 | feat(frontend): Wissensbibliotheken-Übersicht als Tabelle mit Herkunft, Verteilungsstufe und Stand | 2026-08-20 | COMPLETED | #685 |
| #596 | feat(frontend): Bibliothek-Anlage als Assistent mit Herkunfts-Auswahl | 2026-08-21 | COMPLETED | #696 |
| #597 | feat(frontend): Übrige Seiten und Dialoge an das neue Design angleichen | 2026-08-21 | COMPLETED | #701 |
| #606 | main-Build rot: KnowledgeLibraryServiceDeleteLockTest passt nicht zum erweiterten Konstruktor | 2026-08-20 | COMPLETED | #607 |
| #609 | fix(library): CI auf main rot — KnowledgeLibraryServiceDeleteLockTest passt nicht zur neuen KnowledgeLibraryService-Signatur | 2026-08-20 | COMPLETED | — |
| #614 | Nacharbeiten zum asynchronen Upload: Pool-Konfiguration, Lösch-Restfenster, PENDING-Recovery | 2026-08-20 | COMPLETED | #631 |
| #616 | test(query): QueryIntegrationTest flaky — MockitoException durch Stubbing-Race mit asynchronem Chat-Titel-Job | 2026-08-20 | COMPLETED | #621 |
| #617 | Zugangsdaten-Exfiltration über aufrufergesetzten Proxy/insecureSsl beim Verbindungstest und Indizierungslauf | 2026-08-21 | COMPLETED | #699 |
| #619 | chatStore: loadChat überschreibt Einstellungen ungeschützt gegen die laufende Settings-Kette | 2026-08-21 | COMPLETED | #692 |
| #623 | test(chat): ChatServiceIntegrationTest hat dieselbe Stubbing-Race-Struktur wie #616 | 2026-08-20 | COMPLETED | #641 |
| #625 | ci: Actions auf Node-24-Runtime aktualisieren (Node-20-Deprecation) | 2026-08-20 | COMPLETED | #627 |
| #632 | fix(indexing): Konnektorpfade re-inserten gelöschte Dokumentzeilen (save statt bedingter Aktualisierung) | 2026-08-20 | COMPLETED | #633 |
| #636 | fix(library): Verbleibende Chunk-/Zeilen-Restfenster nach #631/#633 schließen | 2026-08-20 | COMPLETED | #633 |
| #637 | fix(indexing): RSS-Executor wendet sourceInsecureSsl nicht an | 2026-08-20 | COMPLETED | #663 |
| #639 | feat(query): sourceEntryUrl in Belegangaben (SourceReference) durchreichen | 2026-08-20 | COMPLETED | #666 |
| #644 | Buildzeiten und Merge-Durchsatz optimieren (Build-Cache, Merge Queue, CI-Zuschnitt) | 2026-08-20 | COMPLETED | #647 |
| #646 | fix(indexing): Feed-Zustand pro Bibliothek führen bzw. beim Löschen zurücksetzen | 2026-08-20 | COMPLETED | #665 |
| #650 | fix(library): deleteLibrary lehnt Löschung bei laufendem Indizierungsjob mit 409 ab | 2026-08-20 | NOT_PLANNED | — |
| #651 | fix(indexing): Redirect-Härtung lässt Host==null als 'nicht fremd' durch und ein Lauf bricht bei ungültiger Eintrags-URL komplett ab | 2026-08-20 | COMPLETED | #664 |
| #654 | feat(frontend): Dunkles Farbschema an das dunkle Schema der Claude-Docs anlehnen | 2026-08-20 | COMPLETED | #656 |
| #658 | feat(frontend): Typografie, Dichte und Komponentenmetrik an Mockup 1a angleichen (Quicksand, Feinraster, weiße Menüs) | 2026-08-20 | COMPLETED | #660 |
| #659 | fix(indexing): Indizierungsfehlermeldung leakt internen Pfad/Host an VIEWER | 2026-08-20 | COMPLETED | #657 |
| #661 | docs(agents): Umgang mit Review-Befunden — direkt beheben statt Folge-Issues | 2026-08-20 | COMPLETED | #662 |
| #677 | fix(db): Bibliotheksreferenzen eines Chats an die Organisation binden | 2026-08-20 | COMPLETED | #680 |
| #686 | feat(space): Datenquellen-Zuordnung Space ↔ Wissensbibliothek (API und Retrieval) | 2026-08-21 | COMPLETED | #706 |
| #693 | fix(indexing): Upgrade-Redirect http→https auf demselben Host wird fälschlich als fremder Host abgewiesen | 2026-08-21 | COMPLETED | #699 |
| #708 | feat: Demo-Instanz mit Verwaltungskorpus einer fiktiven Stadt | 2026-08-22 | COMPLETED | — |
| #709 | docs(demo): Konzept und Quellenrecherche für den Verwaltungskorpus der Demo-Instanz | 2026-08-21 | COMPLETED | #710 |
| #711 | feat(demo): Korpus-Generator für die fiktive Stadt Rheinfurt | 2026-08-21 | COMPLETED | #717 |
| #712 | feat(demo): Seed-Mechanismus mit den Datenprofilen demo und e2e | 2026-08-21 | COMPLETED | #724 |
| #713 | docs(demo): Installationsanleitung, Nutzerkonten und Drehbuch der Demo-Instanz | 2026-08-21 | COMPLETED | #727 |
| #716 | fix(deployment): Schnellstart-Kopie von .env.example ergibt keinen startfähigen Compose-Stack | 2026-08-21 | COMPLETED | #719 |
| #721 | feat(eval): Retrieval-Harness für mehrchunkige Dokumente ertüchtigen | 2026-08-21 | COMPLETED | #723 |
| #731 | fix(api): Rate-Limit-Meldung ist englisch statt deutsch | 2026-08-21 | COMPLETED | #733 |
| #734 | Ollama-Embedding-Aufrufe in io.opaa.indexing parallelisieren (city-landmarks-Eval-CI zu langsam) | 2026-08-22 | COMPLETED | #735 |
| #736 | feat(api): Download-Endpunkt für Originaldokumente | 2026-08-22 | COMPLETED | #742 |
| #737 | fix(auth): Plötzlicher Logout — Silent-Token-Renew und 401-Retry statt Sofort-Logout | 2026-08-22 | COMPLETED | #741 |
| #738 | feat(library): Deeplink auf das Originaldokument in der Wissensbibliothek | 2026-08-22 | COMPLETED | #743 |

## Gemergte PRs ohne verknüpftes Issue

| PR | Titel | gemergt |
|---|---|---|
| #1 | Add collaboration workflow for humans and AI agents | 2026-02-16 |
| #32 | docs: add product pitch one-pager (DE + EN) | 2026-02-20 |
| #91 | docs: add architecture discussion documents | 2026-03-01 |
| #92 | docs: enhance project rules and add MVP status documentation | 2026-03-02 |
| #97 | docs: workspace concept discussion document | 2026-03-06 |
| #104 | chore: add CLA and switch license to AGPL-3.0 | 2026-03-06 |
| #105 | fix: store CLA signatures on unprotected branch | 2026-03-06 |
| #146 | Codex/111 workspace and membership | 2026-03-07 |
| #147 | fix(workspace): restore listMembers to use loaded memberships | 2026-03-07 |
| #217 | docs: replace workspace model with space and asset model | 2026-08-02 |
| #236 | docs: Spezifikation und ADR zur Suchqualitäts-Evaluierung | 2026-08-02 |
| #253 | docs(eval): Demo-Instanz auf OpenAI und Account-Bindung korrigieren | 2026-08-02 |
| #269 | docs: Betriebsfakten der Testinstanz ergänzen und Modellkonfiguration berichtigen | 2026-08-02 |
| #275 | docs: Modellkonfiguration der Testinstanz berichtigen und Sentinel-Regel für die Ground Truth ergänzen | 2026-08-02 |
| #287 | fix(auth): Erstanmeldung nach #280 wieder funktionsfähig machen | 2026-08-02 |
| #385 | feat(report): Blättern zwischen Berichtstagen und feste Adresse für den aktuellen Tag | 2026-08-14 |
| #399 | docs(marketing): Produktnamen in der Marketing-Rolle durch Gattungen ersetzen | 2026-08-14 |
| #403 | fix(docs): Doppelte Tabellenzeilen in ADR-0014 entfernen | 2026-08-14 |
| #412 | fix(indexing): Bibliothekszuordnung in den Chunk-Metadaten nachtragen | 2026-08-15 |
| #413 | fix(library): Rechteprüfung für die System-Bibliothek vereinheitlichen | 2026-08-15 |
| #499 | test(backend): Migrationstests beschleunigen — Template-DB, geteilter Container, gemeinsamer Kontext | 2026-08-19 |
| #502 | docs(decisions): ADR-0018 auf Akzeptiert setzen | 2026-08-19 |
| #648 | test(backend): Spring-Kontexte konsolidieren und CI-Parallelitaet erproben (#497) | 2026-08-20 |
| #695 | test(query): Zwei-Konten-Test fuer Gespraechsgedaechtnis nachreichen | 2026-08-21 |
| #698 | test(backend): letzte drei Migrationstests auf Template-DB umstellen | 2026-08-21 |
| #728 | feat(demo): Quellen- und Demo-Hinweis der Demo-Instanz (Frontend) | 2026-08-21 |
| #732 | docs(demo): Rollout der Demo-Instanz Stadt Rheinfurt dokumentieren | 2026-08-21 |
