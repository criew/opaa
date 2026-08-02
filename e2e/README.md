# E2E-Suite

Browserbasierte End-to-End-Tests für OPAA mit [Playwright](https://playwright.dev/).

## Werkzeugwahl

**Playwright** statt Cypress, Selenium o. ä.:

- TypeScript-first, passt zum bestehenden Frontend-Stack (React + TypeScript + Vite).
- Ein Test-Runner für mehrere Browser-Engines (Chromium, Firefox, WebKit); die Suite startet
  hier bewusst nur mit Chromium — ausreichend für ein Grundgerüst, weitere Projekte lassen sich
  in `playwright.config.ts` jederzeit ergänzen.
- Eingebauter Trace-Viewer (`npx playwright show-trace`) und automatische Trace-/Screenshot-Erfassung
  bei Fehlschlägen, ohne zusätzliche Tooling-Integration.
- Gute CI-Unterstützung (offizielles `playwright install --with-deps`, Artefakt-Upload).

## Verzeichnisstruktur

```
e2e/
  fixtures/         Wiederverwendbare Playwright-Fixtures (z. B. Anmeldung)
  tests/            Testfälle (*.spec.ts)
  scripts/run-e2e.mjs   Orchestrierung: Stack starten → Suite ausführen → Stack stoppen
  e2e.env          Environment für den Docker-Compose-Stack der Suite
  playwright.config.ts
```

Neue Szenarien kommen als weitere `*.spec.ts`-Dateien unter `tests/`; neue wiederverwendbare
Bausteine (z. B. Seiten-Interaktionen) gehören nach `fixtures/`.

## Lokal ausführen

Voraussetzung: Docker Desktop (bzw. Docker Engine + Compose) läuft.

```bash
cd e2e
npm ci
npx playwright install --with-deps chromium   # einmalig
npm test
```

`npm test` (→ `scripts/run-e2e.mjs`) übernimmt den gesamten Lebenszyklus:

1. Bestehende Container mit den Namen `opaa-postgres`/`opaa-backend`/`opaa-frontend` entfernen und
   `docker compose down -v` ausführen (definierte Ausgangslage).
2. `e2e/e2e.env` temporär als `.env.docker` im Projekt-Root installieren (eine eventuell vorhandene
   eigene `.env.docker` wird gesichert und am Ende wiederhergestellt).
3. `docker compose up -d --build postgres backend frontend` und auf Erreichbarkeit warten.
4. `playwright test` ausführen.
5. Stack wieder stoppen (`down -v`) und die eigene `.env.docker` wiederherstellen — auch bei
   fehlgeschlagenen Tests oder Abbrüchen.

Um nur Playwright gegen einen bereits laufenden Stack auszuführen (z. B. während der Entwicklung
eines neuen Tests): `npm run test:playwright` (kein Docker-Lifecycle, erwartet den Stack unter
`http://localhost:3000`, überschreibbar via `E2E_BASE_URL`).

### Verifizieren, dass der Rauchtest bei nicht erreichbarem Frontend fehlschlägt

```bash
cd e2e
npx playwright test   # ohne laufenden Stack
```

Der Test schlägt mit `net::ERR_CONNECTION_REFUSED` fehl und legt Trace/Screenshot ab.

## Warum `basic` statt `mock`-Auth?

OPAA unterstützt drei Auth-Modi (`opaa.auth.mode`: `mock`, `basic`, `oidc`). Für die E2E-Suite
wurde bewusst **`basic`** gewählt, nicht der Standard-Modus `mock`:

- `mock` ist im Backend nur als Frontend-Signal implementiert (`/api/v1/auth/config` meldet
  `mode: mock`, das Frontend überspringt daraufhin die Login-Seite) — es aktiviert keinen
  eigenen Security-/Controller-Satz. Die Fach-Controller (`WorkspaceController` usw.) sind mit
  `@Profile({"oidc", "basic"})` annotiert und existieren ohne aktives Spring-Profil `basic`/`oidc`
  gar nicht; ohne eines der beiden bleibt außerdem Spring Boots generische
  Security-Auto-Konfiguration aktiv und blockt alle Anfragen. `mock` ist für den vollständig über
  Docker Compose laufenden Stack der E2E-Suite also nicht nutzbar.
- `oidc` bräuchte zusätzlich Keycloak (siehe `docker-compose.yml`, Profil `oidc`) — mehr
  bewegliche Teile, mehr Startzeit, ohne zusätzlichen Testwert für dieses Grundgerüst.
- `basic` ist der einfachste Modus, der tatsächlich End-to-End funktioniert: kein externer
  Identity-Provider, feste Testnutzer-Zugangsdaten in `e2e.env` (siehe `OPAA_AUTH_BASIC_USERNAME`/
  `OPAA_AUTH_BASIC_PASSWORD`, keine echten Secrets), und er übt das echte Login-Formular
  (`frontend/src/pages/LoginPage.tsx`) aus.

`e2e/fixtures/auth.ts` kapselt den Login-Ablauf als einzigen wiederverwendbaren Baustein; künftige
Szenarien nutzen die `authenticatedPage`-Fixture, statt die Anmeldung zu kopieren.

## CI

`.github/workflows/e2e.yml` führt die Suite bei jedem Pull Request aus (Zeitbudget: 10 Minuten).
Bei Fehlschlägen werden der Playwright-HTML-Report sowie Traces/Screenshots als Workflow-Artefakte
hochgeladen.
