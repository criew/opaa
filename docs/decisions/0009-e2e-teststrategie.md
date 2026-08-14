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
`docker-compose.yml` in drei Punkten angepasst: `env_file` sowie der Postgres-Port wurden
parametrisierbar gemacht (`${OPAA_ENV_FILE:-.env.docker}`, `${OPAA_DB_PORT:-5432}`) — das ändert
das Verhalten für bestehende `docker compose up`-Nutzung nicht (Standardwerte identisch zum
vorherigen Verhalten; die `:-`-Form bleibt auch bei einer gesetzten, aber leeren Variable
verhaltensneutral). Zusätzlich wurden die vier `container_name`-Festlegungen entfernt (Compose
vergibt sonst projektpräfixierte Namen von selbst) — **das ändert das Verhalten für bestehende
Nutzung einmalig**: Container heißen fortan `opaa-postgres-1`/`opaa-backend-1`/`opaa-frontend-1`
statt `opaa-postgres`/`opaa-backend`/`opaa-frontend` (`docs/MVP-VERIFICATION.md` entsprechend
aktualisiert; wer selbst Skripte oder Aliase mit den alten Namen hat, muss sie anpassen).

Jeder Lauf beginnt und endet mit `docker compose down -v` unter diesem Projektnamen — eine
definierte, reproduzierbare Ausgangslage, ohne Container oder Volumes fremder Projekte anzufassen.
`scripts/run-e2e.mjs` registriert `SIGINT`/`SIGTERM`-Handler, die denselben Teardown auslösen, damit
ein abgebrochener Lauf (`Strg+C`, CI-Cancel) keinen laufenden Stack hinterlässt.

Es gibt keine Secrets zu verwalten: Der Stack läuft im Auth-Modus `dev` (Punkt 3), der weder
Anmeldedaten noch einen Signaturschlüssel kennt.

### 3. Auth-Modus `dev`

Die Suite läuft mit `SPRING_PROFILES_ACTIVE=docker,dev`. Der `dev`-Modus ([ADR-0005](0005-authentication-strategy.md))
authentifiziert jede Anfrage als einen der unter `opaa.auth.dev.users` konfigurierten Nutzer, ohne
Anmeldevorgang und ohne Token; die Filterkette ist ansonsten identisch zur produktiven
OIDC-Konfiguration. `e2e/fixtures/auth.ts` wählt den Nutzer über den Query-Parameter `?devUser=`.

Der Alternative `oidc` hätte einen weiteren Container, den Realm-Import und den Weiterleitungsablauf
des Autorisierungscode-Flusses in den Prüfpfad gebracht — zusätzliche Fehlerquellen ohne Aussagewert
für die Fachszenarien. Der Anmeldeablauf selbst ist bewusst nicht Teil der Suite.

Standardmäßig stehen zwei Nutzer bereit: `dev-admin` (E-Mail `admin@opaa.local`, entspricht dem
Standardwert von `opaa.auth.initial-admin-email` und wird damit als `SYSTEM_ADMIN` angelegt, nötig
für Indexing- und Admin-Endpunkte) und `dev-user` als regulärer Nutzer für Berechtigungsszenarien.

> **Historie:** Bis zur Überarbeitung von ADR-0005 lief die Suite auf dem inzwischen entfernten
> Modus `basic`, weil der damalige Standardmodus `mock` gar keine funktionierende
> Security-Filterkette mitbrachte. Dieser Produktmangel ist mit der Überarbeitung behoben.

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
- **Der OIDC-Anmeldeablauf ist nicht abgedeckt.** Die Suite prüft die Anwendung hinter der
  Authentifizierung, nicht den Anmeldevorgang selbst. Ein gezielter Test gegen das Compose-Profil
  `oidc` mit Keycloak bleibt offen.
- **Die E2E-Suite hängt jetzt an `docker-compose.yml`.** Jede künftige Änderung an den Service-
  Definitionen dort (z. B. neue Pflicht-Umgebungsvariablen, geänderte Ports, neue Abhängigkeiten)
  kann die Suite brechen, ohne dass das beim Ändern von `docker-compose.yml` offensichtlich ist.

### Neutral

- Ports und Environment-Handling ändern sich für bestehende `docker compose up`-Nutzung nicht
  (Standardwerte identisch); die Container-Namen ändern sich einmalig (siehe Punkt 2).
