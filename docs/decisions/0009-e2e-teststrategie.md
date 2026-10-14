# ADR-0009: E2E-Teststrategie

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt durch ADR-0033

## Kontext

Issue #231 verlangte ein Grundgerüst für browserbasierte End-to-End-Tests, auf dem Indizierung und
Suche im Demo-Korpus (#232, #233) und spätere `test(e2e)`-Issues aufbauen. Das Repository hatte keine
E2E-Infrastruktur. Zu entscheiden waren Werkzeug, Orchestrierung des vollen Stacks (Postgres,
Backend, Frontend) je Lauf, Auth-Modus der Suite und Herkunft der KI-Modelle während eines Laufs.
Mehrere Agent-Sessions arbeiten parallel auf derselben Maschine, ein Dev-Stack läuft also häufig
daneben.

## Entscheidung

### 1. Playwright, Chromium, eigenes Projekt mit eigenem Lockfile

`e2e/` ist ein eigenständiges Node-Projekt (eigene `package.json` und `pnpm-lock.yaml`, nicht Teil
von `frontend/`), TypeScript-first, mit Playwright; Aufrufe über `pnpm install`, `pnpm test`,
`pnpm exec playwright …`. Die Suite läuft nur mit dem Projekt `chromium`; weitere Engines lassen sich
in `playwright.config.ts` ergänzen. Begründung und Alternativen: `e2e/README.md#werkzeugwahl`.

### 2. Vollständiger Stack je Lauf in einem eigenen Compose-Projekt

`e2e/scripts/run-e2e.mjs` startet Postgres, Backend und Frontend aus `docker-compose.yml` mit
denselben Dockerfiles wie die Release-Images (echter Nginx-Proxy, echter Container-Build), nicht über
Testcontainers. `e2e/docker-compose.e2e.yml` ist ein Overlay, keine zweite Stack-Definition: Es
ergänzt nur E2E-eigene Dienste (`ai-stub`, `rss-feed`, `nextcloud`, `dropbox-stub`) und einzelne
Umgebungswerte. Der Stack läuft als eigenes Compose-Projekt (`opaa-e2e`) mit eigenen Host-Ports und
eigener Umgebungsdatei (`e2e/e2e.env`, nie `.env.docker`); `docker-compose.yml` hält dafür
`env_file` und Postgres-Port parametrisierbar (`${OPAA_ENV_FILE:-.env.docker}`,
`${OPAA_DB_PORT:-5432}`) und vergibt keine festen `container_name`. Jeder Lauf beginnt und endet mit
`docker compose down -v` unter diesem Projektnamen, auch bei `SIGINT`/`SIGTERM`. Es gibt keine
Secrets: Der Stack läuft im Modus `dev`.

### 3. Auth-Modus `dev`

Die Suite läuft mit `SPRING_PROFILES_ACTIVE=docker,dev` (ADR-0005): jede Anfrage ist als einer der
unter `opaa.auth.dev.users` konfigurierten Nutzer authentifiziert, die Filterkette ist sonst identisch
zur produktiven. `e2e/fixtures/auth.ts` wählt den Nutzer über `?devUser=`; `dev-admin`
(`SYSTEM_ADMIN`) und `dev-user` kommen aus dem Profil, zwei weitere Nutzer setzt das Overlay. Der
Anmeldevorgang selbst ist nicht Teil dieser Suite; ihn prüfen die Ziele aus Entscheidung 6.

### 4. Modelle: OpenAI-kompatibler Stub statt echtem Modell

Kein Szenario hängt von einem externen oder lokal inferierenden Modell ab. `e2e/ai-stub/server.mjs`
läuft als Dienst im Overlay; `e2e/e2e.env` zeigt mit `OPAA_OPENAI_BASE_URL=http://ai-stub:8089` darauf,
der API-Key ist ein Platzhalter. Der Stub beantwortet `/v1/embeddings` für jede Eingabe mit demselben
Vektor und `/v1/chat/completions` mit den Zitationsmarkierungen des Prompts, also bitgenau
deterministisch und ohne Modellgewichte. Die ACL-Szenarien hängen dadurch allein am Rechtefilter
(`io.opaa.retrieval.scope.SearchScopeStage`), nie an schwankender Relevanz. Antwortqualität prüft die
Suite nicht; das ist Sache der Suchqualitäts-Evaluierung (ADR-0011).

### 5. Kein PR-Gate: Lauf nach dem Merge

Seit #1226 läuft `.github/workflows/e2e.yml` bei jedem Push auf `main`, nächtlich und per
`workflow_dispatch`, nicht bei Pull Requests. Ein Fehlschlag auf `main` legt ein Alarm-Issue (Label
`e2e`, Marker `<!-- e2e-alert -->`) an oder kommentiert das offene; ein grüner Lauf schließt es. Die
Suite war nie Required Check, belegte aber je PR-Push 8 bis 12 Minuten Runner; das Netz nach dem Merge
fängt dieselben Regressionen. Wer eine UI-Änderung vorab prüfen will, läuft die Suite lokal.

### 6. Zwei weitere Ziele desselben Skripts: Demo-Rauchtest und lokale Anmeldung

`run-e2e.mjs` kennt `--target demo` (`pnpm run test:demo-smoke`, #232) und `--target local-auth`
(`pnpm run test:local-auth`, #1543), je mit eigenem Compose-Projekt, eigenem Overlay, eigener
Umgebungsdatei und eigener Playwright-Konfiguration, damit sie nie in `pnpm test` mitlaufen. Der
Demo-Rauchtest startet das Compose-Profil `demo` mit Keycloak und Rheinfurt-Korpus, sät über
`demo/seed/seed.py --profile demo` und übt in `e2e/demo-smoke/tests/` die echte Keycloak-Anmeldung
(Autorisierungscode-Fluss), zwei Anbieter ohne Vermischung (ADR-0025) und den Direktlink über die
Anmeldung; der Frontend-Port ist durch den Realm-Export auf 3000 festgelegt. Das Ziel `local-auth`
startet den Modus `oidc` ohne Keycloak mit dem Mail-Fänger und ohne Seed und prüft den Erststart
mit dem gesäten Notanker-Konto und den lokalen Anmeldeweg (ADR-0033, `e2e/local-auth/tests/`). Beide
Workflows (`demo-smoke.yml`, `local-auth-e2e.yml`) laufen nächtlich und per `workflow_dispatch`,
`local-auth-e2e.yml` zusätzlich per Label `local-auth-suite`; keiner ist Required Check.

## Konsequenzen

Einfacher: Der E2E-Stack läuft beliebig oft neben einem Dev-Stack, ohne Container, Volumes oder Ports
zu teilen; jeder Lauf startet und endet sauber; kein Secret in einer committeten Datei; echte Images
und echter Proxy ohne zweite Infrastrukturdefinition.

Schwieriger: Der Image-Build dominiert die CI-Zeit, nicht der Testlauf (eigenes Zeitbudget des Jobs,
gha-Layer-Cache mildert, ersetzt aber keinen Cache über Runner-Neustarts hinweg); der Stub bildet kein
Modellverhalten nach, Szenarien zu Antwortqualität brauchen einen anderen Weg; die Suite hängt an
`docker-compose.yml`, Änderungen dort können sie unbemerkt brechen; Regressionen fallen erst nach dem
Merge auf.

## Verworfene Alternativen

- Cypress oder Selenium — `e2e/README.md#werkzeugwahl`.
- Testcontainers oder eine separate Compose-Datei für den Kernstack — zweite, abweichende
  Infrastrukturdefinition.
- Keycloak im Prüfpfad der regulären Suite — zweiter Container, Realm-Import und Weiterleitungen ohne
  Aussagewert für Fachszenarien.
- Ollama im E2E-Stack — Modell-Download, Laufzeit und CPU-Bedarf im Runner, dazu keine garantierte
  Determinismus-Eigenschaft.
- E2E als PR-Check — Runner-Zeit je Nachbesserungsrunde ohne Merge-Wirkung.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-21 | #256 | OpenAI-kompatibler Stub statt Ollama; Overlay-Datei als Faktum | 2, 4 |
| 2026-08-22 | #653 | Paketmanager pnpm | 1 |
| 2026-09-08 | #1226 | Suite nicht mehr bei Pull Requests | 5 |
| 2026-09-12 | #1543 | Ziel `local-auth` für den echten Anmeldevorgang | 6 |
| 2026-10-14 | #2697 | Neufassung; Status Akzeptiert; Demo-Rauchtest (#232) eingearbeitet | 6 |
