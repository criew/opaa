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
statt `opaa-postgres`/`opaa-backend`/`opaa-frontend` (die damalige Verifizierungsübersicht
`docs/MVP-VERIFICATION.md` wurde entsprechend aktualisiert; sie ist inzwischen abgelöst — wer selbst
Skripte oder Aliase mit den alten Namen hat, muss sie anpassen).

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
sondern eines eigenen, blockierenden Issues ("Lokale Modellbereitstellung für den E2E-Stack") —
**entschieden, siehe [Nachtrag vom 21.08.2026](#21082026--lokale-modellbereitstellung-openai-kompatibler-stub-statt-ollama)**.

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

## Nachträge

Punkt 4 hat die konkrete Umsetzung ausdrücklich einem eigenen, blockierenden Issue überlassen.
Sobald ein solcher Punkt entschieden ist, kommt er hier als Nachtrag hinzu — der ADR-Wortlaut oben
bleibt unverändert, damit erkennbar bleibt, was wann galt.

### 21.08.2026 — Lokale Modellbereitstellung: OpenAI-kompatibler Stub statt Ollama

- **Punkt:** Die in Punkt 4 offengelassene konkrete Umsetzung — lokaler Ollama-Service im
  Compose-Stack vs. leichtgewichtiger OpenAI-kompatibler Stub.
- **Entscheidung:** Ein eigener, minimaler OpenAI-kompatibler Stub-Server
  (`e2e/ai-stub/server.mjs`) läuft als weiterer Dienst im E2E-Compose-Stack
  (`e2e/docker-compose.e2e.yml`); `e2e/e2e.env` zeigt mit `OPAA_AI_CHAT_PROVIDER=openai` und
  `OPAA_OPENAI_BASE_URL=http://ai-stub:8089` darauf. Ollama wird für den E2E-Stack nicht
  eingesetzt.
- **Begründung:** Der Stub beantwortet `POST /v1/embeddings` für jede Eingabe mit demselben festen
  Vektor und `POST /v1/chat/completions`, indem er die im Prompt enthaltenen Zitationsmarkierungen
  unverändert zurückgibt — beides ohne jede Modellinferenz, also bitgenau deterministisch über
  jeden Lauf hinweg. Das ist mehr als ein Implementierungsdetail: Da die Rechteprüfung in der
  Vektorsuche darüber entscheidet, welche Chunks überhaupt in eine Anfrage gelangen (siehe
  `io.opaa.query.SearchScopeStage#libraryFilter`), hängt das Ergebnis der ACL-Szenarien in
  `tests/knowledge-libraries.spec.ts` (#424) ausschließlich vom Rechtefilter ab, nie von einer
  echten, potenziell schwankenden Relevanzbewertung eines Modells — genau das, was diese Szenarien
  belegen sollen, nicht Antwortqualität (die ist Sache von Epic #224). Ein echtes Modell, auch
  lokal über Ollama betrieben, hätte diese Eigenschaft nicht garantiert. Hinzu kommt der
  Betriebsaufwand: Ollama bräuchte einen Modell-Download im Compose-Stack (Netzwerkabhängigkeit und
  Laufzeit beim ersten Start bzw. in jedem CI-Lauf ohne persistenten Cache) und, für brauchbare
  Antwortzeiten, GPU- oder zumindest nennenswerte CPU-Ressourcen auf dem CI-Runner — beides, was
  der Stub, der ohne Modellgewichte auskommt und in Millisekunden antwortet, nicht braucht. Der
  Stub kommt zudem ohne echten API-Key aus (`OPAA_OPENAI_API_KEY=sk-e2e-placeholder` wird nicht
  geprüft), während ein reales `openai`-kompatibles Ziel eines voraussetzt. Der Preis der gewählten
  Lösung: Der Stub bildet kein echtes Modellverhalten nach und eignet sich deshalb nicht für
  Szenarien, die tatsächliche Antwortqualität oder Modell-Nichtdeterminismus prüfen wollen — dafür
  bräuchte es weiterhin einen echten Anbieter oder ein lokal betriebenes Modell wie Ollama, außerhalb
  des Umfangs dieser Suite.
- **Verweis:** [#256](https://github.com/criew/opaa/issues/256) · `e2e/ai-stub/server.mjs` ·
  `e2e/docker-compose.e2e.yml` · `e2e/e2e.env` ·
  [e2e/README.md](../../e2e/README.md#ki-stub-statt-echtem-modell)

### 21.08.2026 — Punkt 2: Overlay-Datei statt reiner `docker-compose.yml`

- **Punkt:** Punkt 2 sagt, der Stack werde über das bestehende `docker-compose.yml` gestartet,
  „nicht über Testcontainers oder eine separate Compose-Datei". Das stimmt seit Längerem nicht mehr
  wörtlich: `e2e/scripts/run-e2e.mjs` startet mit `-f docker-compose.yml -f
  e2e/docker-compose.e2e.yml`, also mit genau einer zusätzlichen Compose-Datei als Overlay. Nicht
  durch diesen PR verursacht, aber durch den neuen Nachtrag oben — der `e2e/docker-compose.e2e.yml`
  bereits als Faktum zitiert — im selben Dokument erstmals sichtbar widersprüchlich.
- **Entscheidung:** `e2e/docker-compose.e2e.yml` ist ein Overlay, keine zweite, eigenständige
  Stack-Definition. Es startet keinen eigenen Postgres/Backend/Frontend, sondern ergänzt nur
  E2E-eigene Dienste (`ai-stub`, `rss-feed`) und punktuelle Umgebungswerte um die in Punkt 2
  beschriebenen Kerndienste aus `docker-compose.yml`. Die tragende Aussage von Punkt 2 —
  dieselben Dockerfiles/Images wie für Produktion, keine zweite Infrastrukturdefinition für
  Postgres/Backend/Frontend — bleibt richtig; nur die Formulierung „nicht über … eine separate
  Compose-Datei" war zu absolut.
- **Begründung:** E2E-spezifische Dienste wie `ai-stub` und `rss-feed` gehören nicht in den Stack,
  den ein Entwickler mit einem gewöhnlichen `docker compose up` hochzieht — laut Kommentar bei
  `ai-stub` in `e2e/docker-compose.e2e.yml` bewusst nicht Teil von `docker-compose.yml`, damit kein
  Nicht-E2E-Gebrauch des Stacks aus Versehen darauf zeigen kann. Eine Overlay-Datei trägt genau
  diese Trennung, ohne Postgres/Backend/Frontend selbst zu duplizieren.
- **Verweis:** `e2e/scripts/run-e2e.mjs` · `e2e/docker-compose.e2e.yml`

### 22.08.2026 — Paketmanager der Suite ist pnpm

- **Punkt:** Punkt 1 nennt `e2e/` ein eigenständiges npm-Projekt mit eigenem `package-lock.json`.
- **Entscheidung:** Mit der Migration des Frontend-Builds auf pnpm (#653) wurde auch `e2e/`
  umgestellt: Das Lockfile heißt jetzt `pnpm-lock.yaml`, Installation und Aufrufe laufen über
  `pnpm install` / `pnpm test` / `pnpm exec playwright …`. Die tragende Aussage von Punkt 1 —
  eigenständiges Node-Projekt mit eigenem Lockfile, nicht Teil von `frontend/` — bleibt unverändert.
- **Verweis:** PR #752 · `e2e/README.md`
