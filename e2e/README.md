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
  fixtures/                 Wiederverwendbare Playwright-Fixtures (z. B. Anmeldung, Chat, a11y)
  tests/                    Testfälle (*.spec.ts)
  scripts/run-e2e.mjs       Orchestrierung: Stack starten → Seed → Suite ausführen → Stack stoppen
                             (Ziel "e2e" per Default, "demo" für den Demo-Smoke, siehe unten)
  e2e.env                   Environment für den Docker-Compose-Stack der Suite (kein Secret enthalten)
  docker-compose.e2e.yml    Compose-Overlay: Secret-Injektion + dynamische CORS-Origin
  playwright.config.ts
  demo-smoke/                    Demo-Smoke gegen das Compose-Profil "demo" (#232, siehe unten) —
    playwright.config.ts         eigene Konfiguration, damit dieses eine Szenario nie in `npm test` mitläuft
    tests/demo-smoke.spec.ts
  demo-smoke.env             Environment für den Demo-Smoke-Stack (Keycloak-Anmeldung, kein Secret enthalten)
  docker-compose.demo-smoke.yml  Compose-Overlay: ai-stub statt echtem Anbieter
```

Neue Szenarien kommen als weitere `*.spec.ts`-Dateien unter `tests/`; neue wiederverwendbare
Bausteine (z. B. Seiten-Interaktionen) gehören nach `fixtures/`. `e2e/fixtures/` enthält seit #233
nur noch echte Playwright-Fixtures (Anmeldung, Chat-Bausteine, Barrierefreiheit) — die frühere
eigene Testdatenbereitstellung dieser Suite (`e2e/fixtures/rss-feed/`, `e2e/fixtures/test-documents/`)
ist abgelöst: Beide leben jetzt unter [`demo/seed/e2e-data/`](../demo/seed/), Teil des gemeinsamen
Seed-Mechanismus (`docs/features/demo-instance.md`, „Installation und Seed") statt eines zweiten,
unabhängigen Befüllungswegs. Siehe „Lokal ausführen" unten, Schritt 3, für Details, was der Seed
tatsächlich anlegt und was weiterhin einzelne Szenarien selbst über die Oberfläche einrichten.

## Lokal ausführen

Voraussetzung: Docker Desktop (bzw. Docker Engine + Compose) läuft, sowie Python 3 mit dem
`venv`-Modul (Teil der Standardbibliothek) auf dem `PATH` (`python` unter Windows, `python3`
sonst). `scripts/run-e2e.mjs` legt daraus bei Bedarf ein eigenes, gitignortes venv unter
`e2e/.venv` an und nutzt ausschließlich dessen Interpreter für `pip install` und
`demo/seed/seed.py` (Issue #233, PR #726 review) — kein Schreibzugriff auf die
Systeminstallation, funktioniert daher auch mit PEP 668 „externally-managed-environment"
(Debian/Ubuntu ≥ 23.04, Homebrew-Python).

```bash
cd e2e
npm ci
npx playwright install --with-deps chromium   # einmalig
npm test
```

`npm test` (→ `scripts/run-e2e.mjs`) übernimmt den gesamten Lebenszyklus:

1. `docker compose down -v` für das **eigene** Compose-Projekt `opaa-e2e` (definierte Ausgangslage;
   siehe "Isolation von einem laufenden Dev-Stack" unten).
2. `docker compose up -d --build ai-stub rss-feed postgres backend frontend` unter diesem
   Projektnamen, mit eigenen Host-Ports und `e2e/e2e.env` als Environment (siehe unten), und Warten
   auf Erreichbarkeit.
3. **Seed-Lauf (#233):** `demo/seed/seed.py --profile e2e --base-url http://localhost:$OPAA_BACKEND_PORT/api`
   — derselbe gemeinsame Seed-Mechanismus, den auch das `demo`-Compose-Profil nutzt
   (`demo/seed/profiles.py`, `E2E_PROFILE`), hier gegen den dev-Auth-Modus dieses Stacks statt
   gegen Keycloak. Legt den Nutzer `dev-user` (und `dev-outsider`, ohne jede Freigabe) bereit, den
   Space „E2E Space" und die Bibliothek „E2E Wissensbibliothek" mit einem einzigen, dediziert für
   den Seed bestimmten Dokument (`demo/seed/e2e-data/test-documents/seed/e2e-basisdokument.txt`) —
   bewusst *nicht* eine der Dateien, die einzelne Szenarien selbst über die Oberfläche hochladen
   (`demo/seed/e2e-data/test-documents/*.txt`), sonst würde eine dauerhaft für `dev-user` lesbare
   Kopie z. B. von `wissensdokument.txt` die Exklusivitätsprüfung in
   `knowledge-libraries.spec.ts` (Szenario 5, „Entzug wirkt") unterlaufen. Ein Fehlschlag hier
   bricht den Lauf ab wie ein fehlgeschlagener Stack-Start.
4. `playwright test` ausführen.
5. Stack wieder stoppen (`down -v`) — auch bei fehlgeschlagenen Tests, Abbrüchen (`Strg+C` /
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

### Drei Testnutzer

Der `dev`-Modus bringt standardmäßig zwei Nutzer mit (`backend/src/main/resources/application.yml`,
`opaa.auth.dev.users`):

| Subject | E-Mail | Rolle |
|---------|--------|-------|
| `dev-admin` | `admin@opaa.local` | `SYSTEM_ADMIN` (entspricht dem Standardwert von `opaa.auth.initial-admin-email`) |
| `dev-user` | `dev-user@opaa.local` | regulärer Nutzer |

Szenarien, die Berechtigungsgrenzen prüfen, verwenden dafür die Fixture `regularUserPage`. Die
frühere Einschränkung auf einen einzigen Testnutzer (#260) besteht nicht mehr.

Ein dritter Nutzer, `dev-outsider` (`outsider@opaa.local`, regulär, Fixture `outsiderPage`),
existiert **nur für diese Suite** — er wird über indizierte `OPAA_AUTH_DEV_USERS_*`-Umgebungsvariablen
in `docker-compose.e2e.yml` ergänzt, nicht in `application.yml`s Standardliste. Ein einfacher
`SPRING_PROFILES_ACTIVE=local,dev`-Backend kennt ihn nicht. Szenarien, die eine Person ganz ohne
jede Beziehung zu den Testdaten brauchen (der Negativfall in #424: eine Freigabe darf niemals bei
jemandem landen, dem sie nie erteilt wurde), verwenden ihn statt `dev-user` — dessen eigene
Freigaben aus anderen Szenarien sonst das Ergebnis verfälschen könnten.

Einordnung dieser drei Nutzer neben den übrigen Testkonto-Mustern des Repos (Keycloak-Realm-Nutzer,
Quellenzugangsdaten): [`docs/deployment.md`, Abschnitt „Testkonten im
Überblick"](../docs/deployment.md#testkonten-im-überblick).

### KI-Stub statt echtem Modell

`docker-compose.e2e.yml` startet zusätzlich `ai-stub` (`e2e/ai-stub/server.mjs`), einen minimalen,
deterministischen Ersatz für einen OpenAI-kompatiblen Chat-/Embedding-Anbieter (kein Dockerfile,
kein Build — das Skript wird nur in das offizielle `node`-Image gemountet). Er beantwortet
`POST /v1/embeddings` immer mit demselben festen Vektor (analog zu
`backend/src/test/java/io/opaa/FakeEmbeddingModel.java`, das denselben Zweck für Backend-
Integrationstests erfüllt) und `POST /v1/chat/completions` mit einer Antwort, die jede im Prompt
enthaltene Zitationsmarkierung (`【source: …】`, siehe `io.opaa.query.CitationParser`) unverändert
zurückgibt. Damit hängt das Suchergebnis in dieser Suite ausschließlich vom Rechtefilter ab (welche
Chunks überhaupt in die Anfrage an den Vektorspeicher gelangen), nie von einer echten
Relevanzbewertung — genau das, was die Szenarien unten prüfen sollen. Antwortqualität im
eigentlichen Sinn ist Sache von Epic #224, nicht dieser Suite. Die lokale Modellbereitstellung
für echte Chat-/Embedding-Läufe bleibt eigenes, noch offenes Issue (#256).

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
Suite. Das Frontend trägt bislang genau ein `data-testid` (`source-card` auf `SourceCard.tsx`,
ergänzt für #424 — eine Quellenkarte im Chat hat keine für Rolle/Label geeignete feste Beschriftung,
weil sie einen zur Laufzeit ermittelten Dateinamen zeigt); wer für eine künftige Assertion eines
braucht, das sich nicht über Rolle/Label ausdrücken lässt, ergänzt das nötige `data-testid` im
selben PR — und verwendet es dort auch tatsächlich, statt es unbenutzt stehen zu lassen.

## Szenarien

- `tests/smoke.spec.ts` — die Anwendung lädt und zeigt die Chat-Startseite (#231).
- `tests/accessibility.spec.ts` (#586) — automatisierte Barrierefreiheitsprüfung mit axe-core
  (`@axe-core/playwright`, Helfer in `fixtures/a11y.ts`): Anmeldeseite, Chat in beiden
  Farbschemata, Space-Seite, Wissensbibliotheken und Verwaltungsbereich (Gruppen) werden im
  Ausgangszustand gegen WCAG 2.1 A/AA geprüft. Verstöße der Stufen „serious" und „critical"
  lassen den Test fehlschlagen; „minor"/„moderate" landen als Annotation im Playwright-Report.
  Ausnahmen stehen als `KNOWN_EXCEPTIONS` in der Spec, jede mit Begründung und Issue
  (derzeit: Kontrast der Akzentfarbe, #634). Die Anmeldeseite ist im dev-Auth-Modus nur sichtbar,
  wenn `/api/v1/auth/config` per `page.route` eine OIDC-Konfiguration zurückgibt — die
  erfundene Authority wird dabei nie kontaktiert. Das dunkle Farbschema wird über
  `page.emulateMedia({ colorScheme: 'dark' })` aktiviert (die Voreinstellung „System" folgt
  `prefers-color-scheme`). Die Szenarien verändern keinen geteilten Zustand und sind daher von
  der Reihenfolge der Suite unabhängig.
- `tests/knowledge-libraries.spec.ts` (#424) — Wissensbibliotheken über den vollen Stack: Nutzer A
  legt eine Bibliothek an und lädt ein Dokument hoch, sieht es nach der Verarbeitung als indiziert,
  und die Suche findet den eigenen Inhalt mit Quellenangabe. A gibt die Bibliothek an Nutzer B frei
  (`VIEWER`); B findet sie in der eigenen Liste und über die Suche. Nutzer C ohne Freigabe findet
  weder die Bibliothek noch den Inhalt (Negativfall — siehe unten). Der Entzug von B's Freigabe
  wirkt sofort. Eine Freigabe an eine Gruppe wirkt für ihr Mitglied C. Ein Nutzer mit `VIEWER`
  bekommt in der Dokumente-Oberfläche keinen Upload angeboten.

  Die Negativfälle (kein Treffer ohne Freigabe, kein Treffer nach Entzug) sind die einzigen
  Szenarien der Suite, die tatsächlich `io.opaa.query.QueryService`s Rechtefilter auf der
  Vektorsuche ausüben — nicht nur die Bibliotheksliste. Das wurde manuell nach dem
  Reproduktionsnachweis-Muster aus `AGENTS.md` verifiziert: mit versuchsweise entferntem
  `.filterExpression(...)` in `QueryService#query` schlagen beide Szenarien fehl (der eigentlich
  ausgeschlossene Nutzer findet den Inhalt plötzlich), mit dem Filter wieder an Ort und Stelle sind
  sie wieder grün. Details und die konkrete Fehlermeldung des roten Laufs stehen im PR, der dieses
  Szenario eingeführt hat.

- `tests/rss-feed-library.spec.ts` (#471) — die RSS-Feed-Quelle über den vollen Stack: eine
  Bibliothek wird über das RSS-Feed-Template angelegt (Anlage #480, Detailseite #481, ADR-0018)
  und gegen einen erfundenen, generischen RSS-2.0-Feed einer fiktiven "Beispielbehörde" indiziert -
  ausgeliefert vom eigenen `rss-feed`-Service in `docker-compose.e2e.yml`
  (`demo/seed/e2e-data/rss-feed/htdocs/`, dasselbe "statischer Inhalt im Compose-Stack"-Muster wie #229's
  Demo-Korpus). Ein Eintrag verweist auf eine nicht existierende Detailseite (404, Negativpfad) -
  der Lauf bricht deswegen nicht ab und weist den Eintrag als übersprungen aus. Ein zweiter Lauf
  über den unveränderten Feed erzeugt keine neuen Dokumente (bedingter Feed-Abruf, ADR-0017).
  Positiv- und Fehlerfall laufen bewusst als zwei getrennte Feeds/Bibliotheken (siehe der Spec-Datei
  eigener Kommentar) - sonst würde ein im ersten Lauf zurückgestellter Eintrag verhindern, dass der
  zweite Lauf des unveränderten Feeds den bedingten Feed-Abruf (ETag/If-Modified-Since) überhaupt
  nutzt.

  Anders als #424 prüft dieses Szenario den Indizierungserfolg nicht über eine Quellenangabe im
  Chat, sondern über die Dokumentzahlen der Indizierungsantwort und der Bibliothek selbst - siehe
  die Spec-Datei für die Begründung (der KI-Stub liefert für jede Anfrage denselben Embedding-
  Vektor, siehe "KI-Stub statt echtem Modell" oben).

- `tests/knowledge-library-nacharbeiten.spec.ts` (#547) — vier zuvor nicht abgedeckte
  Verhaltensweisen aus der Nacharbeiten-Serie #514/#516/#517/#519, jede in eigener,
  wegwerfbarer Bibliothek. Dateiname bewusst Singular ("library", nicht "libraries") und damit
  alphabetisch *nach* `knowledge-libraries.spec.ts` sortiert - siehe die Spec-Datei für die
  Begründung (Reihenfolge schützt #424s Szenario 2 vor einem durch diese Datei aufgeblähten,
  admin-lesbaren Bestand mit identischem ai-stub-Embedding-Vektor):
  - Ein ~2-MB-PDF (zur Laufzeit mit `pdf-lib` erzeugt, nicht eingecheckt) wird durch den echten,
    containerisierten nginx hochgeladen und erfolgreich indiziert - Regressionsschutz für
    `client_max_body_size` in `frontend/nginx.conf` (#519).
  - "Verbindung testen" im Erstellungsdialog: eine erreichbare `HTTP_DIRECTORY`-Quelle
    (`http://rss-feed/webverzeichnis/`, ein eigens für diesen Test angelegtes, statisches
    Apache-FancyIndexing-HTMLTable-Fixture im selben `rss-feed`-Dienst wie #471 - siehe
    `demo/seed/e2e-data/rss-feed/htdocs/webverzeichnis/index.html`) zeigt einen Zählwert; eine
    Verbindung, die von einem intern auflösbaren Host (`ai-stub`) auf einem geschlossenen Port
    sofort abgelehnt wird, zeigt die deutsche Fehlermeldung, und Anlegen bleibt trotzdem möglich
    (#514).
  - Die Dokumentliste einer Bibliothek mit mehr als einer Seite Dokumenten (Filler-Dokumente per
    API angelegt) lässt sich blättern und durchsuchen; dieselbe Liste erscheint auch für eine
    Nicht-Upload-(Konnektor-)Bibliothek, nur ohne Upload-Zone (#517).
  - Das Ändern der Quell-URL einer Konnektorbibliothek zeigt den Hinweis "wirkt erst mit dem
    nächsten Indizierungslauf"; ein leer gelassenes Zugangsdaten-Feld lässt bereits hinterlegte
    Zugangsdaten unverändert, solange die neue Adresse denselben Origin trägt (#516).

  Bewusst nicht abgedeckt (siehe Issue #547): der Negativtest zur Erstanmeldung (#522) und die
  RSS-Lauf-Abschlussmeldung (#518, bräuchte einen eigenen Feed-Fixture-Container).

- `tests/space-chats.spec.ts` (#529, Teil von Epic #523) — Chats als space-eigene, persistente
  Objekte samt der neuen Suchbereichssteuerung im Eingabefeld: ein Chat wird im Space erstellt,
  eine Frage liefert eine Antwort mit Quellenangabe, und ein Neuladen der Seite stellt sowohl den
  Chatlisten-Eintrag (Sidebar) als auch den Gesprächsverlauf (Hauptbereich) wieder her. Eine
  `@`-Referenz ersetzt den vorbelegten Spezial-Chip @Alles-Wissen und schränkt die Suche auf genau
  die referenzierte Bibliothek ein (#560). Ohne Wissensbasis (Chip-Leiste geleert, keine Referenz)
  antwortet die KI ohne Quellen und mit sichtbarem Hinweis. Eine nicht mit dem Nutzer geteilte Bibliothek bleibt aus dessen
  `@`-Vorschlägen ausgeschlossen — mit einer Positivprobe auf der Bibliothek selbst (deren
  Ersteller sieht sie in den eigenen Vorschlägen sehr wohl), sonst wäre die Abwesenheit ebenso gut
  durch eine kaputte Mention-Funktion erklärbar wie durch die Rechteprüfung. Ein zweiter, über
  "Neuer Chat" gestarteter Chat im selben Space hält seinen eigenen, nach einem Neuladen weiterhin
  getrennten Verlauf und seine eigene, sticky `@`-Referenz.

  Dateiname bewusst nicht `chats-in-spaces.spec.ts` (die ursprüngliche Wahl): der sortiert
  alphabetisch *vor* `knowledge-libraries.spec.ts`, und diese Datei legt selbst mehrere,
  admin-lesbare Bibliotheken an. #424s Szenarien 1/2/5 laufen dann gegen einen bereits
  aufgeblähten, unscoped-topK-durchsuchten Korpus — das ist exakt in CI aufgefallen (PR #554):
  `wissensdokument.txt` fiel aus den Top-5-Treffern, weil identische ai-stub-Embeddings (siehe "KI-
  Stub statt echtem Modell" oben) jeden zusätzlichen Chunk gleich relevant erscheinen lassen wie
  den eigentlich gesuchten. `space-chats.spec.ts` sortiert dagegen *nach* sowohl
  `knowledge-libraries.spec.ts` als auch `knowledge-library-nacharbeiten.spec.ts` (`s` > `k`,
  dasselbe Muster wie beim Nacharbeiten-Dateinamen oben) — #424s Szenarien laufen also gegen den
  kleinstmöglichen Korpus, bevor diese Datei ihn selbst weiter aufbläht.

  Aus demselben Grund verwendet diese Datei ausschließlich eigene, in der gesamten Suite einmalige
  Fixture-Dateien (`demo/seed/e2e-data/test-documents/chatdokument-a.txt`/`chatdokument-b.txt`, nicht
  `wissensdokument.txt`/`eigenesdokument.txt`) — sonst könnte eine gleichnamige Datei aus einer
  ihrer eigenen, unterschiedlich freigegebenen Bibliotheken (z. B. der dauerhaft an dev-user
  freigegebenen `E2E-Chat-Freigegeben-*`) #424s eigene Upload-/Freigabe-Assertionen unbemerkt
  "mitbestehen" lassen, selbst wenn deren eigener Pfad kaputt wäre.

  Aus demselben Grund - der eigene, unscoped-topK-durchsuchte Korpus wächst mit jeder zuvor
  gelaufenen Spec-Datei weiter, auch innerhalb dieser eigenen Datei - prüft nur, was den jeweiligen
  Szenariozweck tatsächlich braucht: Szenario 1 (Chat-Mechanik: Antwort mit Quellen, Persistenz
  über ein Neuladen) prüft nur "irgendeine Quelle wurde zitiert" (`expectAnyCitedSource`), nicht
  einen bestimmten Dateinamen - bei unverändertem @Alles-Wissen-Chip sucht es unscoped über den
  gesamten lesbaren Korpus, und welches Dokument dabei in die Top-Treffer fällt, ist nicht das, was
  dieses Szenario zeigen soll. Szenarien 2 und 5, die tatsächlich zeigen sollen, *welche* Bibliothek
  durchsucht wurde, referenzieren stattdessen explizit per `@` - das ersetzt den @Alles-Wissen-Chip
  und bleibt unabhängig von der Korpusgröße deterministisch.

  Die wiederverwendbaren Bausteine (Chat starten/fragen, zitierte Quelle prüfen, Bibliothek anlegen
  und befüllen, Bibliothek mit einer Person teilen) leben in `fixtures/chat.ts`, extrahiert aus
  `knowledge-libraries.spec.ts` (#424) und von beiden Dateien importiert, statt dupliziert.

## Demo-Smoke (#232)

Ein separater, eigenständig startbarer Lauf gegen das Compose-Profil `demo`
(`docs/features/demo-instance.md`) — bewusst **kein** Teil dieser Suite oder von `npm test`: Die
Erstindizierung des Rheinfurt-Korpus (~150–300 Dokumente über vier Bibliotheken plus 26 Uploads)
dauert deutlich länger als der minimale `e2e`-Datenprofil-Seed oben, selbst mit `ai-stub` als
deterministischem Modell.

```bash
cd e2e
npm ci
npx playwright install --with-deps chromium   # einmalig
npm run test:demo-smoke
```

`npm run test:demo-smoke` (→ `scripts/run-e2e.mjs --target demo`) übernimmt denselben
Lebenszyklus wie `npm test` oben, mit denselben Bausteinen, aber anderem Ziel:

- Compose-Profil `demo` (`docker compose --profile demo`) statt der festen Servicenamen der
  `e2e`-Suite — startet zusätzlich `keycloak`, `demo-corpus` und `demo-presse`
  ([`../demo/README.md`](../demo/README.md), „Compose-Stack starten (#229)").
- `ai-stub` (dasselbe Skript, `ai-stub/server.mjs`) tritt über
  [`docker-compose.demo-smoke.yml`](./docker-compose.demo-smoke.yml) und
  [`demo-smoke.env`](./demo-smoke.env) an die Stelle des in `demo/README.md` dokumentierten
  Standard-Anbieters (Ollama) — der Test hängt von keinem externen Dienst ab, exakt wie die
  reguläre Suite.
- Seed-Lauf: `demo/seed/seed.py --profile demo` (statt `--profile e2e`), mit erhöhtem
  Indizierungs-Zeitlimit (600s statt der Standard-300s des Seed-Skripts selbst) — die Begründung
  steht in `scripts/run-e2e.mjs`s eigenem Kommentar.
- Playwright-Lauf mit einer eigenen Konfiguration
  ([`demo-smoke/playwright.config.ts`](./demo-smoke/playwright.config.ts), `testDir` zeigt auf
  `demo-smoke/tests/`), damit `npx playwright test` (ohne Pfadangabe, wie `npm test` und
  `.github/workflows/e2e.yml` es aufrufen) das einzige Szenario dort niemals mitläuft.

**Das eine Szenario** (`demo-smoke/tests/demo-smoke.spec.ts`): Maria Weber meldet sich über die
echte Keycloak-Anmeldung an (Autorisierungscode-Flow, nicht der `dev`-Modus — genau das prüft
dieser Test zusätzlich zur restlichen Suite, siehe „Warum der `dev`-Auth-Modus?" oben), sieht den
Demo-Hinweis (#230, `frontend/src/layouts/DemoNotice.tsx`, aktiviert über `OPAA_DEMO_MODE=true`
in `demo-smoke.env` — eine echte Demo-Instanz setzt dieses Flag ebenfalls), stellt die erste
Drehbuchfrage aus `docs/demo-walkthrough.md` (Gebührenfrage, garantiert beantwortbar, #711) und
bekommt eine belegte Antwort mit mindestens einer zitierten Quelle (`expectAnyCitedSource`,
dieselbe Prüfung wie in `space-chats.spec.ts` oben) — Verhalten und Vorhandensein eines Belegs,
nie ein Wortlaut oder eine Dokumentanzahl, die sich mit dem nächsten Korpuslauf verschieben
könnte.

**Isolation:** Die Keycloak-Realm des `demo`-Profils (`keycloak/realm-export.json`) trägt feste
`redirectUris`/`webOrigins` für `http://localhost:3000` — anders als die `e2e`-Suite oben lässt
sich der Frontend-Port dieses Laufs also nicht frei über `OPAA_FRONTEND_PORT` verlegen, ohne auch
den Realm-Export anzupassen. Der Lauf bekommt trotzdem ein eigenes Compose-Projekt
(`opaa-demo-smoke`), läuft aber nicht neben einem eigenen, auf denselben Standardports
betriebenen Dev-Stack — für einen CI-Runner (siehe unten) ist das ohnehin der Normalfall.

`.github/workflows/demo-smoke.yml` führt diesen Lauf **nicht** bei jedem Pull Request aus (siehe
dort für die Begründung), sondern nächtlich per `schedule` und manuell per `workflow_dispatch` —
vor Präsentationen oder nach Änderungen am Korpus/Seed lässt er sich damit gezielt auslösen, ohne
im Required-Pfad jedes PRs zu hängen.

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
