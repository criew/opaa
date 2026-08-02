# ADR-0009: E2E-Teststrategie

## Status

Vorgeschlagen

## Kontext

Issue #231 verlangt ein Grundgerüst für browserbasierte End-to-End-Tests, auf dem #232 und #233
(Indizierung und Suche im Demo-Korpus) sowie spätere `test(e2e)`-Issues aufbauen. Das Repository
hatte zuvor keinerlei E2E-Infrastruktur (`backend/src/test/.../integration/` enthält nur einen
OpenAI-Integrationstest). Für das Grundgerüst waren vier zusammenhängende Entscheidungen nötig,
die über die reine Werkzeugwahl hinausgehen und Folgeissues betreffen:

1. Welches Test-Werkzeug?
2. Wie wird der volle Stack (Postgres, Backend, Frontend) für einen Testlauf orchestriert?
3. Welcher Auth-Modus wird für die Suite verwendet?
4. Woher kommen die KI-Modelle (Chat/Embedding) während eines Testlaufs?

## Entscheidung

### 1. Playwright, Chromium-only, eigenes Verzeichnis mit eigenem Lockfile

`e2e/` ist ein eigenständiges npm-Projekt (eigenes `package.json`/`package-lock.json`, nicht Teil
von `frontend/`), TypeScript-first, mit [Playwright](https://playwright.dev/). Begründung und
Alternativen in `e2e/README.md#werkzeugwahl`. Die Suite startet bewusst nur mit dem
`chromium`-Projekt; weitere Browser-Engines lassen sich in `playwright.config.ts` bei Bedarf
ergänzen, ohne die übrige Struktur zu ändern.

### 2. Vollständiger Stack pro Lauf, in einem eigenen Compose-Projekt

`scripts/run-e2e.mjs` startet Postgres, Backend und Frontend über das bestehende
`docker-compose.yml` (dieselben Dockerfiles wie für Produktions-Images), nicht über Testcontainers
oder eine separate Compose-Datei. Das hält den Testkontext realitätsnah (echte Nginx-Reverse-Proxy-
Konfiguration, echter Container-Build) und vermeidet eine zweite, potenziell abweichende
Infrastrukturdefinition.

Damit der Stack unabhängig von einem parallel laufenden Dev-Stack (`AGENTS.md` "Git Worktrees für
parallele Sessions" beschreibt genau dieses Parallel-Szenario als Normalfall) betrieben werden
kann, läuft er als **eigenes Compose-Projekt** (`COMPOSE_PROJECT_NAME=opaa-e2e`) mit **eigenen
Host-Ports** und einem **eigenen Environment** (`e2e/e2e.env`, niemals `.env.docker`). Dafür wurde
`docker-compose.yml` in zwei kleinen, rückwärtskompatiblen Punkten angepasst: die vier
`container_name`-Festlegungen wurden entfernt (Compose vergibt sonst projektpräfixierte Namen von
selbst) und `env_file` sowie der Postgres-Port wurden parametrisierbar gemacht
(`${OPAA_ENV_FILE:-.env.docker}`, `${OPAA_DB_PORT:-5432}`) — beides ändert das Verhalten für
bestehende `docker compose up`-Nutzung nicht (Standardwerte identisch zum vorherigen Verhalten).

Jeder Lauf beginnt und endet mit `docker compose down -v` unter diesem Projektnamen — eine
definierte, reproduzierbare Ausgangslage, ohne Container oder Volumes fremder Projekte anzufassen.
`scripts/run-e2e.mjs` registriert `SIGINT`/`SIGTERM`-Handler, die denselben Teardown auslösen, damit
ein abgebrochener Lauf (`Strg+C`, CI-Cancel) keinen laufenden Stack hinterlässt.

Ein per Lauf zufällig erzeugtes JWT-Signing-Secret (`OPAA_AUTH_BASIC_SECRET`, siehe Punkt 3) wird
ausschließlich über die Prozessumgebung durchgereicht und nie in eine Datei geschrieben.

### 3. Auth-Modus `basic`, nicht `mock`

Der dokumentierte Standard-Modus `mock` (ADR-0005: "Kein Auth — alle Anfragen erlaubt") ist im
Backend nicht als eigenständiger, funktionierender Pfad implementiert: Es existieren nur zwei
`SecurityFilterChain`-Beans (`@Profile("basic")`, `@Profile("oidc")`); ohne eines der beiden aktiven
Spring-Profile bleibt Spring Boots generische Security-Auto-Konfiguration aktiv (blockt alle
Anfragen) und die Fach-Controller (`WorkspaceController`, `UserInfoController`, `AdminController`,
`UserService`, alle `@Profile({"oidc","basic"})`) existieren gar nicht als Beans. `mock` ist für den
vollständig über Docker Compose laufenden E2E-Stack damit nicht nutzbar — dieser Mangel liegt im
Produkt, nicht in der Teststrategie (siehe Follow-up-Issue "`mock`-Modus funktionsfähig machen oder
aus Default und Doku entfernen").

Die Suite verwendet daher `basic`: kein externer Identity-Provider (im Gegensatz zu `oidc`, das
zusätzlich Keycloak bräuchte), ein Testnutzer mit fest hinterlegtem Benutzernamen/Passwort in
`e2e/e2e.env` (keine echten Secrets) und ein pro Lauf zufällig erzeugtes Signing-Secret (Punkt 2).
Der Testnutzer wird über `OPAA_INITIAL_ADMIN_EMAIL` zum `SYSTEM_ADMIN`, damit Indexing-/
Admin-Endpunkte für spätere Szenarien erreichbar sind.

### 4. Modelle lokal im Stack statt externer Anbieter

Für das Grundgerüst (#231) genügt ein Platzhalter-API-Key (`OPAA_OPENAI_API_KEY=sk-e2e-placeholder`),
da der einzige Testfall (Rauchtest) keinen KI-Aufruf auslöst. Sobald #232/#233 tatsächliche
Chat-/Indizierungs-Läufe brauchen, darf kein Szenario von einem externen, kostenpflichtigen Dienst
abhängen (Nichtdeterminismus, Kosten, Netzwerkabhängigkeit in CI). Die konkrete Umsetzung (lokaler
Ollama-Service im Compose-Stack vs. OpenAI-kompatibler Stub) ist bewusst nicht Teil dieses ADRs,
sondern eines eigenen, blockierenden Issues ("Lokale Modellbereitstellung für den E2E-Stack").

## Konsequenzen

### Positiv

- Der E2E-Stack kann beliebig oft parallel zu einem Dev-Stack auf derselben Maschine laufen, ohne
  Container, Volumes oder Ports zu teilen — verifiziert durch einen manuellen Test mit einem
  simulierten, gleichnamigen Dev-Container.
- Jeder Lauf startet und endet garantiert mit einem sauberen Compose-Projekt, auch bei Abbruch.
- Kein Secret landet in einer committeten Datei.
- Realitätsnahe Tests (echte Docker-Images, echter Nginx-Proxy), keine zweite
  Infrastrukturdefinition zusätzlich zu `docker-compose.yml`.

### Negativ

- **Der Image-Build dominiert die CI-Zeit**, nicht der Testlauf selbst (der Rauchtest dauert
  wenige Sekunden). Der CI-Job hat deshalb ein separates, großzügigeres Zeitbudget als die in #231
  genannten 10 Minuten (die sich auf den Suite-Lauf beziehen); ein GitHub-Actions-Layer-Cache
  mildert das, ersetzt aber keinen echten Cache über Runner-Neustarts hinweg.
- **`basic` unterstützt aktuell nur einen konfigurierten Nutzer.** Szenarien, die einen
  nicht-privilegierten Zweitnutzer brauchen (z. B. Berechtigungsprüfungen), sind mit dieser
  Grundlage nicht abbildbar, ohne entweder die Backend-Konfiguration zu erweitern (mehrere
  `opaa.auth.basic.users`-Einträge) oder auf Workspace-Rollenwechsel auszuweichen.
- **`mock` bleibt ungetestet.** Solange der Produktmangel aus Punkt 3 nicht behoben ist, deckt die
  E2E-Suite den dokumentierten Standard-Auth-Modus nicht ab.
- Der zufällig generierte Signing-Secret bedeutet, dass zwischen zwei Läufen ausgestellte Tokens
  nicht wiederverwendbar sind — für eine Suite, die pro Lauf ohnehin frisch anmeldet, ohne Nachteil.

### Neutral

- Diese Entscheidungen gelten für die E2E-Suite; sie ändern nichts an `docker-compose.yml`s
  Verhalten für reguläre Dev-/Deployment-Nutzung (Standardwerte bleiben unverändert).
