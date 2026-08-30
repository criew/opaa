# Inventur-Index: geschlossene Issues und gemergte PRs

Automatisch erzeugt aus den GitHub-Dumps; Stand der Erhebung: 2026-08-30.
Spalte PRs: über closingIssuesReferences bzw. Body-Referenzen verknüpfte, gemergte PRs.

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
| #68 | ⚠️ [HIGH] Docker Build Skips Tests | 2026-08-23 | COMPLETED | — |
| #69 | 🟡 [MEDIUM] ChatMemory Lifecycle Management Unclear | 2026-02-28 | COMPLETED | #83 |
| #70 | 🟡 [MEDIUM] Error Boundary Component Not Used | 2026-02-28 | COMPLETED | #82 |
| #71 | 🟡 [MEDIUM] Sensitive Error Information in Logs | 2026-03-01 | COMPLETED | #89, #90 |
| #72 | 🔵 [LOW] Magic Numbers Without Documentation | 2026-03-02 | COMPLETED | #93 |
| #73 | 🔵 [LOW] Inconsistent Mock Profile Naming | 2026-08-14 | NOT_PLANNED | — |
| #74 | 🔵 [LOW] Complex Business Logic in Lambda Expression | 2026-03-01 | COMPLETED | #88 |
| #75 | 🔵 [LOW] Axios Error Response Type Assertion Unsafe | 2026-03-03 | COMPLETED | #94 |
| #76 | 🔵 [LOW] SQL Injection Risk in Future Migrations | 2026-08-15 | NOT_PLANNED | — |
| #77 | 🔵 [LOW] Vector Store Index Type Hardcoded | 2026-08-23 | COMPLETED | — |
| #78 | 🔵 [LOW] Silent Error Fallback for Invalid Document IDs | 2026-08-23 | COMPLETED | — |
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
| #143 | feat(security): Vollständigkeit nach DSGVO — Löschung, Selbstauskunft und Datenschutzhinweis | 2026-08-24 | NOT_PLANNED | — |
| #144 | security(space): Mitgliederliste eines Space nur für Space-Admins und Eigentümer | 2026-08-20 | COMPLETED | #674 |
| #145 | feat(i18n): Sprachinfrastruktur mit Deutsch als Standard- und Ausgangssprache | 2026-08-24 | NOT_PLANNED | — |
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
| #192 | chore(frontend): drop openapi-typescript peer override once upstream supports TypeScript 6 | 2026-08-24 | NOT_PLANNED | — |
| #193 | fix(frontend): hamburger menu icon invisible in mobile header (white on white) | 2026-08-20 | COMPLETED | #669 |
| #194 | docs: document git worktree usage for parallel agent sessions | 2026-08-02 | COMPLETED | #195 |
| #196 | ci: publish backend and frontend Docker images to GHCR | 2026-08-02 | COMPLETED | #197 |
| #198 | Epic: Space and asset model — replace the workspace model | 2026-08-24 | COMPLETED | — |
| #199 | Rename workspace to space, add organization scope and reshape space roles | 2026-08-02 | COMPLETED | #254 |
| #200 | Introduce groups as permission subjects | 2026-08-02 | COMPLETED | #283 |
| #201 | Knowledge library as the document container, with data migration | 2026-08-03 | COMPLETED | #305 |
| #202 | Asset permissions and permission-aware vector search | 2026-08-04 | COMPLETED | #309 |
| #203 | Space-asset association as pure curation | 2026-08-21 | COMPLETED | #706 |
| #204 | Strict mode for spaces | 2026-08-24 | NOT_PLANNED | — |
| #205 | Persistent chats inside spaces | 2026-08-24 | NOT_PLANNED | — |
| #206 | Artifacts in spaces with lifecycle and provenance-based release | 2026-08-24 | NOT_PLANNED | — |
| #207 | Connector sources target exactly one knowledge library | 2026-08-23 | COMPLETED | — |
| #208 | Stewards: group role for accepting shares | 2026-08-14 | COMPLETED | #331 |
| #209 | Agent and prompt library assets with the knowledge share chain | 2026-08-24 | NOT_PLANNED | — |
| #210 | Asset parameters: adapt without forking | 2026-08-24 | NOT_PLANNED | — |
| #211 | Asset versioning with immediate propagation and rollback | 2026-08-24 | NOT_PLANNED | — |
| #212 | Recall by deactivation, with warnings in existing transcripts | 2026-08-24 | NOT_PLANNED | — |
| #213 | Derivatives with permanent provenance and drift protection | 2026-08-24 | NOT_PLANNED | — |
| #214 | Built-in assets as a distinct origin type | 2026-08-24 | NOT_PLANNED | — |
| #215 | Asset catalog: visibility, listed flag and space directory | 2026-08-24 | NOT_PLANNED | — |
| #216 | Governance controls for co-determination | 2026-08-24 | NOT_PLANNED | — |
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
| #239 | Audit-Governance: kein personenbezogener Auswertungspfad | 2026-08-23 | COMPLETED | — |
| #240 | Nachfolge statt Sperre: Assets ausgeschiedener Eigentümer | 2026-08-24 | NOT_PLANNED | — |
| #241 | Befristung und Rezertifizierung von Einzelgrants | 2026-08-24 | NOT_PLANNED | — |
| #242 | Konsistenzprüflauf zwischen Vektorspeicher und Datenbank | 2026-08-24 | NOT_PLANNED | — |
| #243 | Driftschutz für Abkömmlinge: Fristen und automatische Deaktivierung | 2026-08-24 | NOT_PLANNED | — |
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
| #349 | Verhältnis von Plugin-Architektur und MCP klären | 2026-08-24 | NOT_PLANNED | — |
| #350 | Cloud-Deployment und Managed Service gegen das Souveränitätsversprechen prüfen | 2026-08-14 | COMPLETED | #378 |
| #351 | Umfang der Storage-Backend-Abstraktion festlegen | 2026-08-14 | COMPLETED | #380 |
| #352 | Zielbild der Chat-Kanäle festlegen | 2026-08-14 | COMPLETED | #379 |
| #353 | Standardposition der Modellanbieter auf lokal-first umstellen | 2026-08-14 | COMPLETED | #384 |
| #354 | Zitierzwang in der bestehenden Query-Pipeline bewerten | 2026-08-14 | COMPLETED | #396 |
| #355 | Umfang des revisionssicheren Audit-Loggings schneiden | 2026-08-14 | COMPLETED | #398 |
| #356 | Organisationsgrenze über die Anwendungsschicht hinaus absichern | 2026-08-14 | COMPLETED | #397 |
| #357 | Bürgerassistent und öffentliches Widget als Ausblick festhalten | 2026-08-14 | COMPLETED | #381 |
| #358 | Gruppengebundene Spaces: Mitgliedschaft aus dem Verzeichnis ableiten | 2026-08-24 | NOT_PLANNED | — |
| #360 | docs(features): Wissensschicht, Wissensquellen und Modellsteuerung neu fassen (A, B, E) | 2026-08-14 | COMPLETED | #368 |
| #361 | docs(features): Verteilungsmodell ergänzen und Agentenspezifikation anlegen (C, D) | 2026-08-14 | COMPLETED | #365 |
| #362 | docs(features): Identität, Nachweisbarkeit und Governance neu fassen (F, G, H) | 2026-08-14 | COMPLETED | #371 |
| #363 | docs(features): Kanäle, Betrieb und Verwaltungs-Spezifika neu fassen (I, J, K) | 2026-08-14 | COMPLETED | #366 |
| #367 | docs: Anbieternamen in der Vorbild-Analyse von spaces-and-assets.md klären | 2026-08-14 | COMPLETED | #382 |
| #370 | docs(marketing): Screenshots der Landing-Page aus einem Verwaltungskorpus neu aufnehmen | 2026-08-23 | COMPLETED | #796 |
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
| #426 | chore(deployment): Anwendungs-Datenbankaccount nicht als Superuser betreiben, damit audit_log-Schreibschutz greift | 2026-08-24 | NOT_PLANNED | — |
| #429 | Rechtehistorie: Aufbewahrungshöchstdauer und Pseudonymisierung des Personenbezugs | 2026-08-24 | NOT_PLANNED | — |
| #430 | Rechtehistorie: Verzeichnislauf-Eintrag mit konkretem Sync-Lauf korrelieren | 2026-08-24 | NOT_PLANNED | — |
| #433 | fix(indexing): Gelöschte Zielbibliothek mitten im Lauf sauber behandeln (Warnung statt failed) | 2026-08-20 | COMPLETED | #602 |
| #434 | feat(upload): Rate-Limit und/oder asynchrone Verarbeitung für den Dokument-Upload-Endpunkt | 2026-08-20 | COMPLETED | #589 |
| #435 | feat(upload): Inhaltsbasierte Formaterkennung für nutzerkontrollierte Uploads | 2026-08-20 | COMPLETED | #577 |
| #436 | fix(library): 403-vs-404-Unterscheidung bei fehlendem Zugriff auf Bestands-Endpunkten vereinheitlichen | 2026-08-20 | COMPLETED | #608 |
| #438 | feat(frontend): Eigentümername und Dokumentanzahl in LibraryListResponse ausweisen | 2026-08-20 | COMPLETED | #601 |
| #439 | feat(frontend): SYSTEM-Bibliothek über die Oberfläche administrierbar machen | 2026-08-19 | COMPLETED | — |
| #440 | fix(frontend): Space-, Gruppen- und Bibliotheks-Store beim Logout zurücksetzen | 2026-08-20 | COMPLETED | #574 |
| #441 | fix(library): createLibrary prüft Group#isDissolved() nicht vor dem Anlegen des Eigentümer-Grants | 2026-08-20 | COMPLETED | #599 |
| #443 | fix(library): Löschen von FILESYSTEM-/HTTP_DIRECTORY-Dokumenten wirkt nur bis zum nächsten Indizierungslauf | 2026-08-20 | COMPLETED | — |
| #445 | Berechtigungsunabhängige Nutzersuche für die Rechtevergabe (Grants) | 2026-08-23 | COMPLETED | — |
| #447 | fix(audit): DENIED-Erfassung auf weitere Ablehnungspfade in AssetGrantService ausweiten | 2026-08-24 | NOT_PLANNED | — |
| #448 | Deutsche Fehlermeldungen im Grants-Backend: rohe Enum-Namen und fehlende Umlaute | 2026-08-20 | COMPLETED | #576 |
| #451 | fix(audit): Schutz gegen Fluten der Protokollablage durch wiederholte abgewiesene Audit-Zugriffe | 2026-08-24 | NOT_PLANNED | — |
| #452 | fix(audit): Bindungsfehler an Audit-Endpunkten ebenfalls ueber den Selbstprotokoll-Trichter fuehren | 2026-08-24 | NOT_PLANNED | — |
| #455 | chore(audit): Partitionshorizont von audit_log rechtzeitig verlängern | 2026-08-24 | NOT_PLANNED | — |
| #456 | fix(api): Unbekannte Pfade liefern 500 statt 404 und erzeugen einen ERROR-Stacktrace | 2026-08-23 | COMPLETED | #802 |
| #457 | Epic: Audit-Betriebshärtung — Nacharbeiten aus Stage A | 2026-08-24 | COMPLETED | — |
| #458 | Epic: Nacharbeiten Wissensbibliotheken und Upload | 2026-08-23 | COMPLETED | — |
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
| #520 | feat(library): Ordner in Dokumentbibliotheken | 2026-08-24 | COMPLETED | — |
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
| #598 | test(frontend): Barrierefreiheits-Audit nach Abschluss der Design-Migration | 2026-08-28 | COMPLETED | #960 |
| #600 | feat(frontend): Redesign der Weboberfläche nach dem Zielbild-Designsystem | 2026-08-28 | COMPLETED | — |
| #606 | main-Build rot: KnowledgeLibraryServiceDeleteLockTest passt nicht zum erweiterten Konstruktor | 2026-08-20 | COMPLETED | #607 |
| #609 | fix(library): CI auf main rot — KnowledgeLibraryServiceDeleteLockTest passt nicht zur neuen KnowledgeLibraryService-Signatur | 2026-08-20 | COMPLETED | — |
| #611 | test(indexing): Fremd-Host-Tests scheitern auf macOS — 127.0.0.2 ist auf lo0 nicht konfiguriert | 2026-08-28 | COMPLETED | #1018 |
| #614 | Nacharbeiten zum asynchronen Upload: Pool-Konfiguration, Lösch-Restfenster, PENDING-Recovery | 2026-08-20 | COMPLETED | #631 |
| #616 | test(query): QueryIntegrationTest flaky — MockitoException durch Stubbing-Race mit asynchronem Chat-Titel-Job | 2026-08-20 | COMPLETED | #621 |
| #617 | Zugangsdaten-Exfiltration über aufrufergesetzten Proxy/insecureSsl beim Verbindungstest und Indizierungslauf | 2026-08-21 | COMPLETED | #699 |
| #619 | chatStore: loadChat überschreibt Einstellungen ungeschützt gegen die laufende Settings-Kette | 2026-08-21 | COMPLETED | #692 |
| #623 | test(chat): ChatServiceIntegrationTest hat dieselbe Stubbing-Race-Struktur wie #616 | 2026-08-20 | COMPLETED | #641 |
| #625 | ci: Actions auf Node-24-Runtime aktualisieren (Node-20-Deprecation) | 2026-08-20 | COMPLETED | #627 |
| #632 | fix(indexing): Konnektorpfade re-inserten gelöschte Dokumentzeilen (save statt bedingter Aktualisierung) | 2026-08-20 | COMPLETED | #633 |
| #634 | fix(frontend): Akzentfarbe erreicht mit weißem Text nur 3,29:1 Kontrast (blue-500) | 2026-08-25 | COMPLETED | #909 |
| #636 | fix(library): Verbleibende Chunk-/Zeilen-Restfenster nach #631/#633 schließen | 2026-08-20 | COMPLETED | #633 |
| #637 | fix(indexing): RSS-Executor wendet sourceInsecureSsl nicht an | 2026-08-20 | COMPLETED | #663 |
| #639 | feat(query): sourceEntryUrl in Belegangaben (SourceReference) durchreichen | 2026-08-20 | COMPLETED | #666 |
| #644 | Buildzeiten und Merge-Durchsatz optimieren (Build-Cache, Merge Queue, CI-Zuschnitt) | 2026-08-20 | COMPLETED | #647 |
| #646 | fix(indexing): Feed-Zustand pro Bibliothek führen bzw. beim Löschen zurücksetzen | 2026-08-20 | COMPLETED | #665 |
| #650 | fix(library): deleteLibrary lehnt Löschung bei laufendem Indizierungsjob mit 409 ab | 2026-08-20 | NOT_PLANNED | — |
| #651 | fix(indexing): Redirect-Härtung lässt Host==null als 'nicht fremd' durch und ein Lauf bricht bei ungültiger Eintrags-URL komplett ab | 2026-08-20 | COMPLETED | #664 |
| #653 | Frontend auf pnpm umstellen (Worktree-Größe und Installationszeit) | 2026-08-23 | COMPLETED | #752 |
| #654 | feat(frontend): Dunkles Farbschema an das dunkle Schema der Claude-Docs anlehnen | 2026-08-20 | COMPLETED | #656 |
| #658 | feat(frontend): Typografie, Dichte und Komponentenmetrik an Mockup 1a angleichen (Quicksand, Feinraster, weiße Menüs) | 2026-08-20 | COMPLETED | #660 |
| #659 | fix(indexing): Indizierungsfehlermeldung leakt internen Pfad/Host an VIEWER | 2026-08-20 | COMPLETED | #657 |
| #661 | docs(agents): Umgang mit Review-Befunden — direkt beheben statt Folge-Issues | 2026-08-20 | COMPLETED | #662 |
| #667 | feat(query): Fundort je Zitatstelle und durchsuchte Bestände in der Query-API ergänzen | 2026-08-23 | COMPLETED | #753 |
| #677 | fix(db): Bibliotheksreferenzen eines Chats an die Organisation binden | 2026-08-20 | COMPLETED | #680 |
| #682 | feat(space): Quellen- und Chatzahl in SpaceListResponse für die Übersichtskarten | 2026-08-23 | COMPLETED | #754 |
| #684 | feat(library): Letzten Indexstand (lastIndexedAt) in LibraryListResponse für die Stand-Spalte | 2026-08-28 | COMPLETED | #962 |
| #686 | feat(space): Datenquellen-Zuordnung Space ↔ Wissensbibliothek (API und Retrieval) | 2026-08-21 | COMPLETED | #706 |
| #693 | fix(indexing): Upgrade-Redirect http→https auf demselben Host wird fälschlich als fremder Host abgewiesen | 2026-08-21 | COMPLETED | #699 |
| #707 | fix(frontend): CSP blockiert als data:-URI gebündelte Font-Subsets im Docker-Deployment | 2026-08-25 | COMPLETED | #910 |
| #708 | feat: Demo-Instanz mit Verwaltungskorpus einer fiktiven Stadt | 2026-08-22 | COMPLETED | — |
| #709 | docs(demo): Konzept und Quellenrecherche für den Verwaltungskorpus der Demo-Instanz | 2026-08-21 | COMPLETED | #710 |
| #711 | feat(demo): Korpus-Generator für die fiktive Stadt Rheinfurt | 2026-08-21 | COMPLETED | #717 |
| #712 | feat(demo): Seed-Mechanismus mit den Datenprofilen demo und e2e | 2026-08-21 | COMPLETED | #724 |
| #713 | docs(demo): Installationsanleitung, Nutzerkonten und Drehbuch der Demo-Instanz | 2026-08-21 | COMPLETED | #727 |
| #716 | fix(deployment): Schnellstart-Kopie von .env.example ergibt keinen startfähigen Compose-Stack | 2026-08-21 | COMPLETED | #719 |
| #718 | feat(frontend): @Space-Chip für die Chip-Leiste (blockiert: offene Entscheidung zum @Alles-Wissen-Rückfall) | 2026-08-24 | NOT_PLANNED | — |
| #720 | feat(deployment): Ollama als optionalen Compose-Service unter eigenem Profil bereitstellen | 2026-08-23 | COMPLETED | #801 |
| #721 | feat(eval): Retrieval-Harness für mehrchunkige Dokumente ertüchtigen | 2026-08-21 | COMPLETED | #723 |
| #725 | fix(a11y): Farbkontrast in der Wissensbibliotheken-Tabelle unzureichend | 2026-08-24 | COMPLETED | #852 |
| #731 | fix(api): Rate-Limit-Meldung ist englisch statt deutsch | 2026-08-21 | COMPLETED | #733 |
| #734 | Ollama-Embedding-Aufrufe in io.opaa.indexing parallelisieren (city-landmarks-Eval-CI zu langsam) | 2026-08-22 | COMPLETED | #735 |
| #736 | feat(api): Download-Endpunkt für Originaldokumente | 2026-08-22 | COMPLETED | #742 |
| #737 | fix(auth): Plötzlicher Logout — Silent-Token-Renew und 401-Retry statt Sofort-Logout | 2026-08-22 | COMPLETED | #741 |
| #738 | feat(library): Deeplink auf das Originaldokument in der Wissensbibliothek | 2026-08-22 | COMPLETED | #743 |
| #739 | feat(search): Deeplinks auf Originaldokumente in Fundstellen und Belegfenster | 2026-08-22 | COMPLETED | #745 |
| #740 | feat: Deeplinks auf Originaldokumente & stabile Anmeldung | 2026-08-22 | COMPLETED | — |
| #744 | Leistungsinventur: Bestandsaufnahme aller abgeschlossenen Issues und PRs für den Meilenstein-1-Report | 2026-08-23 | COMPLETED | #746 |
| #747 | feat(api): Content-Endpunkt streamt Remote-Originale serverseitig durch (Proxy) | 2026-08-22 | COMPLETED | #748 |
| #749 | fix(chat): Chat-Seite erzeugt äußere Scrollbar — Hauptbereich höher als der Viewport | 2026-08-22 | COMPLETED | #750 |
| #751 | Renovate für automatisierte Abhängigkeits-Updates konfigurieren (lokale Ausführung, kein Cloud-Service) | 2026-08-25 | COMPLETED | #911 |
| #755 | feat(models): Verwaltete Chat-Modelle in der Administrationsoberfläche (Stufe 1) | 2026-08-23 | COMPLETED | — |
| #756 | feat(models): Datenmodell für verwaltete Chat-Modelle, verschlüsselte Zugangsdaten und Seed-Migration | 2026-08-22 | COMPLETED | #763 |
| #757 | feat(models): Admin-API für Chat-Modelle (CRUD, Aktivierung, Verbindungstest) | 2026-08-22 | COMPLETED | #764 |
| #758 | feat(models): Laufzeitauflösung des aktiven Chat-Modells statt fest gebundener Autoconfiguration | 2026-08-22 | COMPLETED | #767 |
| #759 | feat(models): Administrationsseite Modellverwaltung mit schreibgeschützter Einbettungsübersicht | 2026-08-22 | COMPLETED | #765 |
| #760 | test(e2e): Modellverwaltung — Anlegen, Aktivieren, Verbindungstest und Löschschutz | 2026-08-23 | COMPLETED | #770 |
| #762 | refactor(ai): Nativen Ollama-Starter entfernen — Embedding über OpenAI-kompatible Schicht | 2026-08-22 | COMPLETED | #766 |
| #768 | fix(api): OpenAI-SDK-Fehler (com.openai.errors.*) im GlobalExceptionHandler nutzerfreundlich mappen | 2026-08-23 | COMPLETED | #806 |
| #769 | Retrieval-Regression erkannt (automatischer Lauf) | 2026-08-24 | COMPLETED | — |
| #771 | fix(models): Fehlender OPAA_SETTINGS_ENCRYPTION_KEY bricht den Anwendungsstart ab statt nur die Seed-Übernahme | 2026-08-23 | COMPLETED | #772 |
| #773 | fix(ai): Suchqualitäts-Regression durch Embedding über Ollamas /v1-Endpunkt (statt nativer API) | 2026-08-23 | COMPLETED | #779 |
| #775 | Demo-Seed: Space↔Bibliothek-Zuordnungen mit ausliefern | 2026-08-23 | COMPLETED | #776 |
| #777 | Mitglieder hinzufügen für normale Nutzer kaputt: Benutzersuche nutzt SYSTEM_ADMIN-Endpunkt; dazu zwei UI-Korrekturen der Mitgliederverwaltung | 2026-08-23 | COMPLETED | #778 |
| #780 | Browservorschau für Markdown-, Text- und DOCX-Originale statt stillem Download | 2026-08-23 | COMPLETED | #781 |
| #782 | Chat-Fußzeile zählt lesbare statt effektiv durchsuchte Bestände in Spaces mit Zuordnungen | 2026-08-23 | COMPLETED | #783 |
| #784 | Englische MUI-Standardtexte („No options", „Loading…", aria-Labels) statt deutscher Lokalisierung | 2026-08-23 | COMPLETED | #785 |
| #786 | feat(frontend): Globale Leiste (Rail) als immer sichtbare erste Navigationsebene | 2026-08-23 | COMPLETED | #791 |
| #787 | feat(frontend): Globaler Verwaltungsrahmen — helle Fläche mit „Global“-Badge für die Administration | 2026-08-23 | COMPLETED | #794 |
| #788 | feat(frontend): Benutzer-Einstellungen als globale Seite über den Avatar der Leiste | 2026-08-23 | COMPLETED | #795 |
| #789 | feat(frontend): Wissensbibliotheken-Übersicht in den globalen Rahmen einbetten | 2026-08-23 | COMPLETED | #799 |
| #792 | fix(frontend): Space-Navigation der Seitenleiste erzeugt axe-Verstoß — li ohne ul-Elternelement | 2026-08-23 | COMPLETED | #793 |
| #798 | Selbstauskunft und Auskunftsexport für Audit-Daten | 2026-08-24 | NOT_PLANNED | — |
| #800 | fix(frontend): Review-Nachbesserungen am globalen Rahmen — mobile Spalte, Rollenbindung, Profilblock, Testlücken | 2026-08-23 | COMPLETED | #803 |
| #805 | test(frontend): Nachweis-Lücken aus dem Review zu #803 schließen — 320-px-Geometrie, Rollenbindung, Doku | 2026-08-25 | COMPLETED | #907 |
| #807 | docs(marketing): Demo-Video auf der GitHub Page bereitstellen | 2026-08-23 | COMPLETED | #808 |
| #809 | feat(frontend): Spaces-Übersicht ohne Space-Spalte — Navy-Spalte erst im gewählten Space | 2026-08-23 | COMPLETED | #811 |
| #812 | fix(frontend): index.html ohne Cache-Control — Browser zeigen nach Deployments den alten Stand | 2026-08-23 | COMPLETED | #813 |
| #814 | fix(frontend): isGlobalAreaPath normalisiert Trailing Slashes nicht — /spaces/ zeigt die Space-Spalte | 2026-08-24 | COMPLETED | #816 |
| #815 | test(e2e): space-chats Szenario 1 flaky im Gesamtlauf — zitierte Quelle erscheint nach Reload nicht | 2026-08-28 | COMPLETED | #961 |
| #817 | Backend-Review: toter Code, veraltete Referenzen und Javadoc-Hypertrophie bereinigen | 2026-08-25 | COMPLETED | — |
| #819 | docs(library): ADR und Spezifikation für Ordner in Bibliotheken | 2026-08-23 | COMPLETED | #825 |
| #820 | feat(library): Schema und CRUD-API für Bibliotheksordner | 2026-08-24 | COMPLETED | #827 |
| #821 | feat(library): Dokumentliste und Upload ordner-bewusst machen | 2026-08-24 | COMPLETED | #828 |
| #822 | feat(frontend): Ordner-Navigation in der Bibliotheksansicht | 2026-08-24 | COMPLETED | #830 |
| #823 | feat(library): Ordner-Upload per Drag & Drop mit Strukturübernahme | 2026-08-24 | COMPLETED | #831 |
| #824 | feat(indexing): FILESYSTEM-Verzeichnisstruktur als read-only Ordner abbilden | 2026-08-24 | COMPLETED | #829 |
| #826 | refactor: Backend-Architekturreview 2026-08 — Befunde und Behebungsphasen | 2026-08-25 | COMPLETED | — |
| #832 | ci: Gradle-Cache in der CI wird nie aktualisiert — auf setup-gradle umstellen | 2026-08-24 | COMPLETED | #841 |
| #833 | fix(auth): lastLoginAt-Schreibzugriff pro Request drosseln | 2026-08-24 | COMPLETED | #856 |
| #834 | feat(audit): Indizes für byTimeRange- und byIncidentScope-Abfragepfade ergänzen | 2026-08-24 | COMPLETED | #846 |
| #835 | build: OpenAPI-doLast-Löschliste ableiten und Eval-Tasks deduplizieren | 2026-08-24 | COMPLETED | #857 |
| #836 | fix(indexing): Autoindex-Crawler ohne Tiefen- und Besuchslimit — Zyklen terminieren nicht | 2026-08-24 | COMPLETED | #851 |
| #837 | fix(indexing): storeChunks vergibt bei identischen Chunk-Texten doppelte chunk_index-Werte | 2026-08-24 | COMPLETED | — |
| #838 | refactor(indexing): VectorStore-Delete-Filter über gemeinsamen Helfer statt String-Konkatenation | 2026-08-24 | COMPLETED | #849 |
| #839 | fix(indexing): UrlIndexingExecutor parst Proxy inline — NumberFormatException bei ungültigem Port | 2026-08-24 | COMPLETED | #854 |
| #840 | fix(chat): Archivierungsprüfung vor dem LLM-Aufruf statt erst beim Persistieren | 2026-08-24 | COMPLETED | #855 |
| #842 | docs: Kommentar-Konvention — Vertrag statt PR-Historie, projektweit in AGENTS.md verankern | 2026-08-24 | COMPLETED | #858 |
| #843 | test(backend): Test-Kontexte inventarisieren und auf kanonische Meta-Annotationen konsolidieren | 2026-08-24 | COMPLETED | #865 |
| #844 | test(backend): Sonderkontexte auf kanonische Test-Signaturen zurückführen | 2026-08-24 | NOT_PLANNED | — |
| #845 | docs: ADR Single-Instance-Betrieb — verstreute Annahmen bündeln | 2026-08-24 | COMPLETED | #859 |
| #848 | docs: Koordinations-Betriebsregeln aus lokalem Memory ins Repo überführen | 2026-08-24 | COMPLETED | #850 |
| #853 | fix(a11y): fg-3 in Sidebar- und Rail-Theme unterschreitet 4,5:1 auf Hover- und Aktiv-Flächen | 2026-08-24 | COMPLETED | #878 |
| #860 | refactor(backend): DTO-Leak beheben — Services geben Domain-Typen zurück, Mapping in die API-Schicht | 2026-08-24 | COMPLETED | — |
| #862 | refactor(db): CHECK-Constraints für Enum-Vokabulare ablösen — Enum-Erweiterungen ohne Migration | 2026-08-24 | COMPLETED | #868 |
| #863 | ci: retrieval-regression.yml — Domänen-Jobs über Matrix statt Kopie | 2026-08-24 | COMPLETED | #866 |
| #874 | fix(chat): SourceReference.spaceName wird vom Frontend gelesen, aber vom Backend nie befüllt | 2026-08-24 | COMPLETED | #880 |
| #875 | refactor(backend): Domain-Exceptions statt ResponseStatusException in Services | 2026-08-24 | COMPLETED | #881 |
| #876 | refactor(indexing): Quellenzugriff als eigenes Paket — eine Redirect-Policy, RssFeedIndexingExecutor zerlegen | 2026-08-24 | COMPLETED | #883 |
| #877 | fix(indexing): Dokumentidentität auf (Bibliothek, Quelle) scopen — Dokument-Stehlen zwischen Bibliotheken beenden | 2026-08-24 | COMPLETED | #885 |
| #884 | refactor(backend): Request-scoped CurrentUser — Aufrufer-Identität zentralisieren | 2026-08-24 | COMPLETED | #887 |
| #886 | feat(indexing): Dokumente verschwundener Quellen aufräumen — veralteter Bestand wächst unbegrenzt | 2026-08-25 | COMPLETED | #900 |
| #888 | refactor(space): Zentrale AccessPolicy und effectiveRole — Owner-Semantik vereinheitlichen | 2026-08-25 | COMPLETED | #891 |
| #889 | refactor(chat): Chat-Pfad als explizite Pipeline — Transaktions-Kartenhaus und COUNT(*)-Sequenz ablösen | 2026-08-25 | COMPLETED | #890 |
| #892 | refactor(audit): AuditEvent-Builder und Domain-Events — Doppelbuchführung strukturell absichern | 2026-08-25 | COMPLETED | #895 |
| #896 | build: Gradle-Modul opaa-api — Spec, Generator und geteilte Enums herauslösen | 2026-08-25 | COMPLETED | #898 |
| #903 | test(backend): Spring-Testkontexte konsolidieren (~19 → ≤10) — Meta-Annotation für Indexing, geteilte Mock-Configs | 2026-08-25 | COMPLETED | — |
| #904 | chore(db): Liquibase-Historie zu einer Baseline zusammenfassen (257 Changesets → logisch gruppierte Baseline) | 2026-08-25 | COMPLETED | #906 |
| #912 | Mehrthemen-Fragen: Retrieval verdrängt das schwächere Thema vollständig (topK-Monokultur) | 2026-08-27 | COMPLETED | — |
| #913 | Eval: Mehrthemen-Golden-Fälle und Recall pro Teilthema | 2026-08-25 | COMPLETED | #915 |
| #914 | Query: MMR-Diversität im Retrieval (fetchK, mmrLambda) und topK-Anhebung | 2026-08-26 | COMPLETED | #922 |
| #923 | Query: Teilfragen-Zerlegung und kontextbewusste Reformulierung vor dem Retrieval (Multi-Query-RAG) | 2026-08-26 | COMPLETED | #926 |
| #924 | fix(ci): Renovate-PRs scheitern am CLA-Check — gitAuthor ist keinem Konto zugeordnet | 2026-08-26 | COMPLETED | #925 |
| #927 | docs: Doku-Struktur nach Achsen konsolidieren (Stand, Handbuch, Recherche) | 2026-08-26 | COMPLETED | #928 |
| #929 | docs: Demo-Dokumentation konsolidieren und deployment.md zum allgemeinen Betriebshandbuch machen | 2026-08-26 | COMPLETED | #931 |
| #932 | Query: Gebühren-Chunk verliert gegen Einleitungs-Chunk desselben Dokuments — Chunk-Auswahl nach der Fusion vervollständigen | 2026-08-26 | COMPLETED | #934, #935 |
| #933 | Indexing: Contextual Chunking — Dokumentkontext in Chunk-Embeddings | 2026-08-27 | COMPLETED | #940 |
| #937 | Query: Zitatvalidierung prüft nur Abruf, nicht Inhalt — falsche Zahl mit gültig wirkendem Zitat | 2026-08-26 | COMPLETED | — |
| #938 | Query: Einschlägige Satzungs-PDF fehlt in den Top-8 — Drehbuch-Frage 6 wird als thomas.klein verweigert | 2026-08-27 | COMPLETED | — |
| #941 | CI: Baseline-Absenkungs-Wächter prüft nur comic-characters — city-landmarks bekommt falschen Freispruch | 2026-08-27 | COMPLETED | #944 |
| #951 | feat(ci): Renovate-PRs mit aktiviertem GitHub-Auto-Merge eröffnen | 2026-08-28 | COMPLETED | #952 |
| #954 | fix(ci): Renovate schlägt npm-Releases vor, die pnpms minimumReleaseAge noch ablehnt | 2026-08-28 | COMPLETED | #955 |
| #956 | fix(frontend): Branding-Vorschau versteckt fokussierbare Elemente vor Screenreadern | 2026-08-28 | COMPLETED | #1012 |
| #957 | fix(frontend): Rollen-Chips unterschreiten im Dunkelschema den Mindestkontrast | 2026-08-28 | COMPLETED | #1017 |
| #958 | fix(frontend): Übersprungene Überschriftenebenen auf Chat-, Branding- und Modelle-Seite | 2026-08-28 | COMPLETED | #1015 |
| #959 | fix(frontend): Fokus geht nach Escape beim Inline-Umbenennen eines Chats verloren | 2026-08-28 | COMPLETED | #968 |
| #966 | test(backend): Redirect-/Downloader-Tests binden 127.0.0.2 und scheitern auf macOS | 2026-08-28 | COMPLETED | #1018 |
| #996 | fix(deps): pnpm-Lockfile auf main nach Renovate-Auto-Merge-Serie gebrochen — Frontend-CI komplett rot | 2026-08-28 | COMPLETED | #1003 |
| #997 | chore(ci): Renovate den Gradle-Wrapper-Befehl erlauben (allowedUnsafeExecutions) | 2026-08-28 | COMPLETED | #998 |
| #1000 | ci(renovate): Gleichzeitige Lockfile-Updates gegen semantische Merge-Brüche absichern | 2026-08-28 | COMPLETED | #1008 |
| #1001 | fix(build): Backend-Dockerfile zurück auf Temurin 21 — Renovate-Major #988 bricht Image-Build | 2026-08-28 | COMPLETED | #1003 |
| #1002 | fix(ci): Auto-gemergtes temurin-v25-Major bricht Backend-Image-Build — Majors vom Auto-Merge ausnehmen | 2026-08-28 | COMPLETED | #1004 |
| #1005 | chore(ci): Tika-4-Major in Renovate aussetzen — inkompatibel zu Spring AIs Tika-3-Parsern | 2026-08-28 | COMPLETED | #1006 |
| #1007 | chore(ci): TypeScript-7-Major in Renovate aussetzen — typescript-eslint unterstützt TS 7.0 nicht | 2026-08-28 | COMPLETED | #1006 |
| #1016 | fix(frontend): Markdown-Überschriften in Chat-Antworten pro Nachricht auf gültige Ebenen normalisieren | 2026-08-28 | COMPLETED | #1019 |
| #1022 | Recherche: Agent-Loop, Frameworks und Laufzeitumgebung für Phase-2-Agenten dokumentieren | 2026-08-30 | COMPLETED | #1024 |
| #1023 | Recherche: Retrieval-Strategien für OPAA — Tech-Report, Roadmap und Dateityp-/Metadaten-Konzept | 2026-08-30 | COMPLETED | #1025 |

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
| #761 | docs(features): Stufe 1 der Modellverwaltung in der LLM-Spezifikation | 2026-08-22 |
| #790 | docs(design): Zielbild-Mockups um Abschnitt „Global vs. Space" erweitern | 2026-08-23 |
| #804 | fix(backend): Ungültige Dokument-ID in Chunk-Metadaten auf WARN heben | 2026-08-23 |
| #810 | ci(pages): Meilenstein-Ordner fortschritt/ statt *.mp4 vom Sync ausnehmen | 2026-08-23 |
| #818 | chore(backend): tote Abhängigkeit, tote Konfiguration und deutsche Log-Meldungen entfernen | 2026-08-23 |
| #847 | fix(backend): toten Code und veraltete Kommentare bereinigen (Runde 2) | 2026-08-24 |
| #861 | docs(decisions): Review-Nachbesserungen zu ADR-0021 nachziehen | 2026-08-24 |
| #864 | docs(indexing): Javadoc-Kommentare auf Vertrag und Invarianten kürzen | 2026-08-24 |
| #867 | chore(backend): toten Code entfernen, Ablaufdaten setzen, pgvector-Dimensions-Guard ergaenzen | 2026-08-24 |
| #869 | refactor(space): DTO-Leak beheben - Services geben Domain-Typen zurück | 2026-08-24 |
| #870 | refactor(group): DTO-Leak beheben - GroupService/DirectorySync geben Domain-Typen zurück | 2026-08-24 |
| #871 | refactor(library): DTO-Leak beheben - KnowledgeLibraryService/AssetGrantService geben Domain-Typen zurück | 2026-08-24 |
| #872 | refactor(library): DTO-Leak beheben - LibraryFolderService/SourceConnectionTestService geben Domain-Typen zurück | 2026-08-24 |
| #873 | refactor(chat,query): DTO-Leak beheben - ChatService/QueryService geben Domain-Typen zurück | 2026-08-24 |
| #879 | docs(audit): Javadoc-Kommentare auf Vertrag und Invarianten kürzen | 2026-08-24 |
| #882 | refactor(indexing): Quellenzugriff als eigenes Paket io.opaa.sourceaccess extrahieren | 2026-08-24 |
| #893 | refactor(audit): AuditEvent-Builder statt Positionsargumenten | 2026-08-25 |
| #897 | docs(library,chat,query,space): Javadoc-Kommentare auf Vertrag und Invarianten kuerzen | 2026-08-25 |
| #899 | chore: Restposten aus #817 – documentPath, tote AuditEventType-Werte, CI-Required-Check | 2026-08-25 |
| #901 | docs(decisions): ADR-0017 auf Akzeptiert setzen | 2026-08-25 |
| #902 | refactor(api): ScheduleFrequency/ScheduleWeekday als geteilte Domain-Enums nach opaa-api umziehen | 2026-08-25 |
| #905 | test(indexing): dritte kanonische Testkontext-Signatur @OpaaIndexingIntegrationTest | 2026-08-25 |
| #908 | test(backend): Mock-Konsolidierung für Spring-Testkontexte (Schritte 2-4, Teil von #903) | 2026-08-25 |
| #930 | docs(fortschritt): tagesreport.md zur Stand-und-Nachweis-Achse ziehen (Nachzügler zu #927) | 2026-08-26 |
| #936 | docs(query): Ist-Stand-Spezifikation des Retrieval-Algorithmus | 2026-08-26 |
| #939 | feat(query): Zitatvalidierung um deterministische Faktenprüfung ergänzen | 2026-08-26 |
| #942 | fix(demo): Gebühren in Personalausweis/Reisepass-Abholung an Einzeldokumente angleichen | 2026-08-27 |
| #943 | docs(query): akzeptierte Grenze der reinen Vektorsuche dokumentieren | 2026-08-27 |
| #946 | docs(fortschritt): Inventur-Nachzug zum 27.08. — Delta-Bausteine, Gruppierung und Meilenstein-1-Bericht | 2026-08-27 |
| #1014 | chore(ci): Befristete Mindestalter-Ausnahme für die vorgezogenen Renovate-Updates | 2026-08-28 |

Renovate-Abhängigkeits-Updates (43 PRs, 2026-08-27 bis 2026-08-29) sind gesammelt im Baustein
[pr-renovate-updates.md](./bausteine/pr-renovate-updates.md) geführt statt je PR einzeln.
