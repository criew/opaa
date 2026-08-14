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

## Warum der `dev`-Auth-Modus?

OPAA kennt zwei Auth-Modi (`opaa.auth.mode`: `oidc`, `dev` — siehe
[ADR-0005](../docs/decisions/0005-authentication-strategy.md)). Die Suite läuft auf **`dev`**:

- `dev` authentifiziert jede Anfrage als einen der unter `opaa.auth.dev.users` konfigurierten
  Nutzer — ohne Anmeldevorgang, ohne Token, ohne Secret. Die Filterkette ist ansonsten identisch
  zur produktiven OIDC-Konfiguration, die Suite übt also dieselben Autorisierungsregeln aus.
- `oidc` bräuchte zusätzlich Keycloak (siehe `docker-compose.yml`, Profil `oidc`): ein weiterer
  Container, der Realm-Import und der Weiterleitungsablauf des Autorisierungscode-Flusses im
  Prüfpfad — mehr Fehlerquellen ohne Aussagewert für die Fachszenarien. Der Anmeldeablauf selbst
  ist bewusst nicht Teil der Suite.

`e2e/fixtures/auth.ts` kapselt die Nutzerwahl als einzigen wiederverwendbaren Baustein: Der
Query-Parameter `?devUser=<subject>` wird beim Laden der Anwendung ausgewertet, für die Dauer der
Browser-Session gemerkt und als Header `X-OPAA-Dev-User` an alle API-Aufrufe angehängt. Künftige
Szenarien nutzen die Fixtures `authenticatedPage` bzw. `regularUserPage`, statt das zu kopieren.

### Keine Secrets

Der `dev`-Modus kennt weder Anmeldedaten noch einen Signaturschlüssel. `e2e/e2e.env` ist deshalb
vollständig frei von Secrets und bewusst in git eingecheckt; `scripts/run-e2e.mjs` erzeugt und
reicht nichts Vertrauliches mehr durch.

### Zwei Testnutzer

Der `dev`-Modus bringt zwei Nutzer mit (`backend/src/main/resources/application.yml`,
`opaa.auth.dev.users`):

| Subject | E-Mail | Rolle |
|---------|--------|-------|
| `dev-admin` | `admin@opaa.local` | `SYSTEM_ADMIN` (entspricht dem Standardwert von `opaa.auth.initial-admin-email`) |
| `dev-user` | `dev-user@opaa.local` | regulärer Nutzer |

Szenarien, die Berechtigungsgrenzen prüfen, verwenden dafür die Fixture `regularUserPage`. Die
frühere Einschränkung auf einen einzigen Testnutzer (#260) besteht nicht mehr.

## Serialisierungs-Konvention

`playwright.config.ts` setzt `fullyParallel: false` und `workers: 1`: alle Specs teilen sich einen
Stack und eine Datenbank. Specs, die globalen Zustand verändern (Indizierungsjobs, Rate-Limiting,
Workspace-Daten, ...) laufen deshalb **nacheinander**, nie parallel — sonst können sich Läufe
gegenseitig Daten wegschreiben oder Zähler verfälschen. Neue Spec-Dateien müssen diese Annahme
nicht selbst absichern; sie dürfen nur nicht versuchen, `test.describe.configure({ mode: 'parallel'
})` global zu aktivieren.

## Selektor-Konvention

Für neue Assertions bevorzugt in dieser Reihenfolge:

1. `getByRole` (z. B. `getByRole('button', { name: 'Neuer Chat' })`) — am robustesten, testet
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
