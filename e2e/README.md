# E2E-Suite

Browserbasierte End-to-End-Tests für OPAA mit [Playwright](https://playwright.dev/).

Architektur- und Modellentscheidungen für diese Suite sind in
[`docs/decisions/0009-e2e-teststrategie.md`](../docs/decisions/0009-e2e-teststrategie.md)
festgehalten (ADR).

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
  fixtures/                 Wiederverwendbare Playwright-Fixtures (z. B. Anmeldung)
  tests/                    Testfälle (*.spec.ts)
  scripts/run-e2e.mjs       Orchestrierung: Stack starten → Suite ausführen → Stack stoppen
  e2e.env                   Environment für den Docker-Compose-Stack der Suite (kein Secret enthalten)
  docker-compose.e2e.yml    Compose-Overlay: Secret-Injektion + dynamische CORS-Origin
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

1. `docker compose down -v` für das **eigene** Compose-Projekt `opaa-e2e` (definierte Ausgangslage;
   siehe "Isolation von einem laufenden Dev-Stack" unten).
2. `docker compose up -d --build postgres backend frontend` unter diesem Projektnamen, mit eigenen
   Host-Ports und `e2e/e2e.env` als Environment (siehe unten), und Warten auf Erreichbarkeit.
3. `playwright test` ausführen.
4. Stack wieder stoppen (`down -v`) — auch bei fehlgeschlagenen Tests, Abbrüchen (`Strg+C` /
   `SIGINT`/`SIGTERM`) oder Fehlern beim Hochfahren.

Um nur Playwright gegen einen bereits laufenden Stack auszuführen (z. B. während der Entwicklung
eines neuen Tests): `npm run test:playwright` (kein Docker-Lifecycle, erwartet den Stack unter
`http://localhost:3000`, überschreibbar via `E2E_BASE_URL`).

### Isolation von einem laufenden Dev-Stack

Der E2E-Stack läuft als **eigenes Compose-Projekt** (`COMPOSE_PROJECT_NAME=opaa-e2e`) auf **eigenen
Host-Ports** (Postgres `15432`, Backend `18081`, Frontend `13000`, überschreibbar via
`OPAA_DB_PORT`/`OPAA_BACKEND_PORT`/`OPAA_FRONTEND_PORT`) und mit einem **eigenen Environment**
(`e2e/e2e.env`, nie `.env.docker`). `docker-compose.yml` selbst hat keine festen `container_name`-
Werte mehr — Compose präfixiert Container-, Netzwerk- und Volume-Namen automatisch mit dem
Projektnamen. Ein parallel laufender Dev-Stack (`docker compose up`, Standardprojekt) wird von
`npm test` also weder gestoppt noch entfernt, und umgekehrt. Das wurde manuell verifiziert: ein
mit dem Namen `opaa-postgres` laufender Container bleibt während eines vollständigen `npm test`-
Laufs unangetastet.

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
  Docker Compose laufenden Stack der E2E-Suite also nicht nutzbar — siehe Issue "`mock`-Modus
  funktionsfähig machen oder aus Default und Doku entfernen" für den zugrunde liegenden Produktmangel.
- `oidc` bräuchte zusätzlich Keycloak (siehe `docker-compose.yml`, Profil `oidc`) — mehr
  bewegliche Teile, mehr Startzeit, ohne zusätzlichen Testwert für dieses Grundgerüst.
- `basic` ist der einfachste Modus, der tatsächlich End-to-End funktioniert: kein externer
  Identity-Provider, ein fester Testnutzer in `e2e.env` (siehe `OPAA_AUTH_BASIC_USERNAME`/
  `OPAA_AUTH_BASIC_PASSWORD`, keine echten Secrets), und er übt das echte Login-Formular
  (`frontend/src/pages/LoginPage.tsx`) aus.

`e2e/fixtures/auth.ts` kapselt den Login-Ablauf als einzigen wiederverwendbaren Baustein; künftige
Szenarien nutzen die `authenticatedPage`-Fixture, statt die Anmeldung zu kopieren.

### Secret-Handling

`e2e/e2e.env` enthält absichtlich **kein** `OPAA_AUTH_BASIC_SECRET`. `scripts/run-e2e.mjs` erzeugt
pro Lauf ein zufälliges JWT-Signing-Secret (`crypto.randomBytes(32)`) und reicht es ausschließlich
über die Prozessumgebung an `docker-compose.e2e.yml` durch, das es in den Backend-Container
injiziert. Es landet nie in einer Datei. Username/Passwort des Testnutzers sind unkritisch (nur
innerhalb des E2E-Stacks gültig) und bleiben deshalb statisch in `e2e.env`.

### Bekannte Einschränkung: nur ein Testnutzer

Das `basic`-Profil (`application.yml`) definiert `opaa.auth.basic.users` derzeit als Liste mit
genau einem Eintrag, gespeist aus `OPAA_AUTH_BASIC_USERNAME`/`OPAA_AUTH_BASIC_PASSWORD`. Der
E2E-Stack hat also aktuell nur den einen Testnutzer `e2e-user`, der zugleich über
`OPAA_INITIAL_ADMIN_EMAIL=e2e-user@opaa.local` zum `SYSTEM_ADMIN` gemacht wird (nötig für
Indexing-/Admin-Endpunkte). Ein **nicht**-administrativer Zweitnutzer lässt sich damit nicht ohne
Produktivcode-Änderung abbilden. Szenarien, die einen nicht-privilegierten Nutzer brauchen (z. B.
Berechtigungsprüfungen), brauchen dafür #260 (mehrere `opaa.auth.basic.users`-Einträge
konfigurierbar machen) — als Voraussetzung an #232 Szenario 5 verlinkt — oder weichen auf
Rollenwechsel innerhalb eines Workspace-Memberships aus.

## Serialisierungs-Konvention

`playwright.config.ts` setzt `fullyParallel: false` und `workers: 1`: alle Specs teilen sich einen
Stack und eine Datenbank. Specs, die globalen Zustand verändern (Indizierungsjobs, Rate-Limiting,
Workspace-Daten, ...) laufen deshalb **nacheinander**, nie parallel — sonst können sich Läufe
gegenseitig Daten wegschreiben oder Zähler verfälschen. Neue Spec-Dateien müssen diese Annahme
nicht selbst absichern; sie dürfen nur nicht versuchen, `test.describe.configure({ mode: 'parallel'
})` global zu aktivieren.

## Selektor-Konvention

Für neue Assertions bevorzugt in dieser Reihenfolge:

1. `getByRole` (z. B. `getByRole('button', { name: 'Anmelden' })`) — am robustesten, testet
   zugleich Zugänglichkeit.
2. `getByLabel` für Formularfelder.
3. `data-testid` für alles andere, das sich nicht sinnvoll über Rolle/Label fassen lässt.

`getByPlaceholder`/`getByText` auf sichtbaren, für Menschen formulierten Text (wie im aktuellen
Rauchtest) sind **zu vermeiden**, sobald sich das vermeiden lässt: Sie brechen bei jeder
Textänderung (inkl. Sonderzeichen wie `…`) und sind kein stabiler Vertrag zwischen Frontend und
Suite. Das Frontend hat aktuell kein einziges `data-testid`; wer für #232/#233 eine Assertion
braucht, die sich nicht über Rolle/Label ausdrücken lässt, ergänzt das nötige `data-testid` im
selben PR.

## CI

`.github/workflows/e2e.yml` führt die Suite aus bei:

- jedem Pull Request (außer reinen Doku-Änderungen, `paths-ignore`),
- jedem Push auf `main`,
- täglich per `schedule` (Post-Merge-QA-Schleife, siehe `docs/AGENT-ORGANIZATION.md`).

Der Job baut Backend- und Frontend-Image zunächst separat mit einem GitHub-Actions-Layer-Cache
(`docker/build-push-action`, `cache-from/to: type=gha`) und lädt sie lokal, bevor
`scripts/run-e2e.mjs` mit `E2E_SKIP_BUILD=true` läuft und diese Images wiederverwendet, statt sie
erneut zu bauen. **Das 10-Minuten-Budget aus #231 bezieht sich auf den Suite-Lauf selbst** (aktuell
wenige Sekunden), nicht auf den Image-Build davor — der Job hat dafür ein eigenes, großzügigeres
`timeout-minutes`-Budget.

Bei Fehlschlägen (inkl. Timeout/Cancel) werden Playwright-HTML-Report, Traces/Screenshots sowie die
Container-Logs (`docker-compose.log`) als Workflow-Artefakte hochgeladen.

> **Hinweis für später:** Sollte der `e2e`-Job jemals als Required Check für PRs konfiguriert
> werden, blockiert `paths-ignore` reine Doku-PRs dauerhaft im Status "pending" (GitHub wartet auf
> einen Check, der für diese PRs nie ausgelöst wird). In dem Fall muss stattdessen ein separater,
> immer laufender Skip-Job mit demselben Job-Namen ergänzt werden, der für Doku-only-Änderungen
> sofort grün durchläuft (Standardmuster für `paths-ignore` + Required Checks).
