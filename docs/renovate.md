# Renovate — selbst betriebene Abhängigkeits-Updates

Renovate liefert Updates; ob eine Abhängigkeit dazwischen eine bekannte Schwachstelle hat, meldet
[`docs/cve-scanning.md`](./cve-scanning.md) (Dependabot-Alerts, Trivy-Image-Scan).

OPAA nutzt [Renovate](https://docs.renovatebot.com/) für automatisierte Update-PRs — **selbst
betrieben, ohne den Mend-Cloud-Service** (Issue #751): Es ist keine GitHub-App installiert,
kein externer Dienst hat Zugriff auf das Repository. Der Lauf erfolgt **täglich als
GitHub-Actions-Workflow** (`.github/workflows/renovate.yml`, 06:23 MESZ, zusätzlich manuell
über *Run workflow* auslösbar) im offiziellen Docker-Image; dasselbe Kommando lässt sich
jederzeit auch lokal ausführen (unten).

## Was Renovate hier aktualisiert

| Quelle | Manager | Bereichs-Label |
|---|---|---|
| `backend/gradle/libs.versions.toml` (einzige zulässige Versionsquelle, AGENTS.md) | `gradle` | `backend` |
| Gradle-Wrapper | `gradle-wrapper` | `backend` |
| `frontend/package.json` + `pnpm-lock.yaml` (inkl. `packageManager`-Pinning) | `npm` | `frontend` |
| `e2e/package.json` + `pnpm-lock.yaml` | `npm` | `frontend` |
| GitHub-Actions-Workflows (`.github/workflows/`), Commit-SHA und Versionskommentar gemeinsam (siehe [unten](#actions-per-commit-sha)) | `github-actions` | `ci` |
| Docker-Basisimages (`Dockerfile`s, `docker-compose*.yml`) | `dockerfile`, `docker-compose` | `ci` |
| Helm-Chart (`deploy/helm/opaa/`): Abhängigkeiten in `Chart.yaml` (derzeit keine) und Images mit festem Tag in `values.yaml` (die Evaluierungsdatenbank); Backend und Frontend folgen der Chart-Version und tragen dort keinen Tag | `helmv3`, `helm-values` | `ci` |
| Images der Hilfsdienste im Kubernetes-Erprobungsaufbau (`examples/kubernetes-trial/manifests/` und `jobs/`); ohne Auto-Merge, weil keine CI den Aufbau prüft — Beleg vor dem Merge mit `trial.sh up` (README dort) | `kubernetes` | `ci` |
| Demo-Seed-/Generator-Requirements (`demo/*/requirements.txt`) | `pip_requirements` | `demo` |
| Node-Version für die lokale Entwicklung (`frontend/.nvmrc`) | `nvm` | `frontend` |
| Pins, die kein regulärer Manager sieht: die Image-Konstanten in `S3TestFixture.java` und `KeycloakFixture.java`, der `pnpm dlx`-Aufruf in `sbom.yml`, die Werkzeugversionen der Chart-Prüfung in `helm-chart.yml`, die Helm-Version der Chart-Veröffentlichung in `publish-images.yml` | `custom.regex` | `ci` |

**Rein transitive Sicherheits-Pins brauchen einen `[libraries]`-Eintrag.** Wird eine Bibliothek
angehoben, die kein Build-Skript direkt deklariert (eingebetteter Tomcat, Bouncy Castle, junrar —
siehe den `dependencyManagement`-Block in `backend/build.gradle.kts`), genügt ein bloßer
`[versions]`-Eintrag nicht: Der `gradle`-Manager leitet seine Koordinaten aus `[libraries]` ab und
sieht eine Version ohne `module` überhaupt nicht. Der Pin bekäme dann nie einen Update-PR und
bliebe auf dem Stand stehen, auf den ihn die damalige CVE gehoben hat. Deshalb steht zu jedem
solchen Pin ein `[libraries]`-Eintrag mit `version.ref`, den der Override über `libs.*` verwendet.

Achtung bei den Demo-Requirements: Für Änderungen ausschließlich unter `demo/` läuft derzeit
**kein** CI-Job (die Pfadfilter in `ci.yml` kennen `demo/` nicht) — solche Update-PRs vor dem
Merge lokal gegen `demo/seed/seed.py` bzw. den Generator prüfen.

Regeln in [`renovate.json5`](../renovate.json5) (kommentiert): deutsche Commit-/PR-Texte im
Stil `chore(deps): <Paket> auf <Version> aktualisieren`, Labels je Bereich, höchstens fünf
gleichzeitig offene Update-PRs, Spring-Plattform als ein gebündelter PR, **kein**
Digest-Pinning für Docker-Images (gleitende Tags sind eine dokumentierte Projektentscheidung,
siehe `e2e/docker-compose.e2e.yml`). Zusätzlich pflegt Renovate ein Übersichts-Issue
(„Abhängigkeits-Übersicht (Renovate)") mit allen anstehenden Updates.

**npm-Releases brauchen 24 h Reife** (`minimumReleaseAge: '1 day'`, #954): pnpm 11 lehnt
jüngere Releases per Standard-Supply-Chain-Richtlinie ohnehin ab — Renovate schlägt deshalb
erst vor, was pnpm auch installiert. Ein wegen dieser Frist noch zurückgehaltenes Update
erscheint als „Pending" in der Abhängigkeits-Übersicht.

**npm wird gepinnt** (`rangeStrategy: 'pin'` für `dependencies`/`devDependencies`):
`frontend/` und `e2e/` sind Anwendungen — exakte Versionen in der `package.json` machen jeden
Bump als PR sichtbar statt als stilles Lockfile-only-Update. Der allererste Lauf erzeugt dafür
einmalig einen „Pin dependencies"-PR, der alle Caret-Ranges auf exakte Versionen umschreibt;
`engines` bleibt bewusst eine Range, `packageManager` ist bereits exakt gepinnt.

## Actions per Commit-SHA

Alle Workflows unter `.github/workflows/` binden jede Action über den vollen Commit-SHA ein, mit
der exakten Version als Kommentar (#2397, mit #2432 auf alle Workflows ausgeweitet):

```yaml
- uses: docker/login-action@dbcb813823bdd20940b903addbd779551569679f # v4.6.0
```

Ein Tag lässt sich auf anderen Code umhängen, ein SHA nicht. Wird ein Action-Repository
kompromittiert, läuft ein umgehängter Tag sonst beim nächsten Lauf mit den Rechten des Workflows,
in einem Release-Lauf bis hin zu manipulierten, gültig attestierten Images.

**Warum alle Workflows.** Bis #2432 galt der SHA nur für Workflows mit Schreibrechten auf
veröffentlichte Artefakte oder das Repository. Zwei Wege führten daran vorbei:

- **Der GitHub-Actions-Cache.** Jeder Job, der auf `main` läuft, darf in den Cache von `main`
  schreiben, und zwar unter jedem Schlüssel (siehe [unten](#sicherheitsmodell-des-gha-caches)).
  Eine kompromittierte Action in `e2e.yml` oder `demo-smoke.yml` hätte so Einträge für den
  Bau-Cache von `publish-images.yml` anlegen können.
- **Schreibrechte unterhalb der Artefakte.** `e2e.yml`, `retrieval-regression.yml` und
  `baseline-diff.yml` legen Issues oder PR-Kommentare an.

Eine Grenze nach Schreibrechten müsste beides einzeln nachhalten. Eine Regel für alle Dateien
ist einfacher zu prüfen und braucht keine Ausnahmeliste.

Der Preis sind mehr Renovate-PRs. Über einen gleitenden Major-Tag (`@v7`) kam jedes Minor- und
Patch-Release still an; mit SHA schlägt Renovate jedes einzeln vor. Neu betroffen sind die acht
Action-Repositories, die vorher in keinem Workflow gepinnt waren. Sie hatten in den sechs Monaten
bis zum 10.10.2026 zusammen 33 Releases, also etwa einen zusätzlichen PR pro Woche. Die übrigen
Actions waren schon gepinnt. Renovate bündelt ein Update über alle Dateien in einem Branch, ihr
PR ändert jetzt nur mehr Dateien.

### Sicherheitsmodell des gha-Caches

Zugriffsregeln laut GitHub-Dokumentation („Dependency caching reference“, Abschnitte
„Restrictions for accessing a cache“ und „Cache access for low-trust workflow triggers“, Stand
10.10.2026):

| Lauf | darf lesen | darf schreiben |
|---|---|---|
| `push`, `schedule`, `workflow_dispatch` auf `main` | Cache von `main` | Cache von `main` |
| `pull_request`, auch aus einem Fork | eigener Merge-Ref, Basis-Branch, `main` | nur den eigenen Merge-Ref; sichtbar nur für erneute Läufe desselben PRs |
| `pull_request_target`, `issue_comment`, `workflow_run` auf `main` (hier `cla.yml`) | Cache von `main` | nichts, solange der Workflow kein schreibendes `cache-mode` setzt; keiner tut das |
| Release-Tag `vX.Y.Z` | eigener Tag, `main` | eigener Tag; kein anderer Tag sieht ihn |

Daraus folgt:

- **Ein Pull Request, auch aus einem Fork, kann weder den Cache von `main` noch den eines
  Releases vergiften.**
- **Ein Scope ist keine Grenze.** `scope=backend-amd64` ist nur ein Präfix der Cache-Schlüssel.
  Wer in den Cache von `main` schreiben darf, darf jeden Schlüssel anlegen, auch einen jüngeren
  Index-Eintrag für den Scope eines anderen Workflows. Dazu genügt jede Action in einem Job auf
  `main`. Öffentlich beschrieben ist außerdem, dass Code in einem `run`-Schritt das Cache-Token
  aus dem Runner-Prozess lesen kann (Adnan Khan, „The Monsters in Your Build Cache“, 2024; hier
  nicht nachgestellt). Dann reicht auch eine kompromittierte Abhängigkeit, die ein solcher Job
  installiert oder ausführt, und dagegen hilft kein SHA-Pin.
- **`cache-mode` hilft hier nicht.** Das Workflow-Schlüsselwort regelt Lesen und Schreiben je
  Job, nicht je Schlüssel, und die Jobs auf `main` brauchen ihre eigenen Caches.

Deshalb gilt zusätzlich zu den Pins:

- **Releases bauen ohne gha-Cache.** `publish-images.yml` setzt `cache-from` und `cache-to` nur
  bei Läufen, die nicht von einem Tag kommen. Ein Release-Image entsteht damit nur aus Checkout,
  Basis-Images und Paketquellen, unabhängig davon, was ein Job auf `main` in den Cache gelegt
  hat. `test_publish_images_cache.py` hält die Bedingung fest. Gemessen an den ersten Läufen mit
  den Scopes je Architektur, die keinen Cache-Treffer hatten (#2411, Läufe 37980307632 und
  37980340271), braucht ein Bein ohne Cache 280 bis 354 s (Backend) bzw. 87 bis 105 s
  (Frontend), mit Cache 85 bis 108 s bzw. 51 bis 65 s (Lauf 38036312587). Die Beine laufen
  parallel, ein Release dauert also rund vier Minuten länger.
- **`:main`, `sha-<commit>` und der wöchentliche Neubau nutzen den Cache weiter.** Das ist ein
  bewusst getragenes Restrisiko: Code, der in einem Job auf `main` läuft, kann den Bau-Cache
  dieser Images vergiften. Nach den SHA-Pins bleiben dafür eine kompromittierte Abhängigkeit oder
  ein Fehler in einem gemergten Workflow. Die Stufe `runtime` baut jeder Lauf ohnehin neu
  (`no-cache-filters`). Wer geprüft und reproduzierbar betreiben will, nimmt ein Release
  ([releases.md](releases.md)).

### Renovate pflegt SHA und Kommentar gemeinsam

Zwei `packageRules` in `renovate.json5` gelten für den `github-actions`-Manager in allen
Workflows, ohne Dateiliste:

- **`pinDigests: true`.** Renovate liest `currentValue` aus dem Kommentar und `currentDigest` aus
  dem SHA und schreibt bei einem Update beides neu.
- **`minimumReleaseAge: '3 days'`.** Ein neues Release wird frühestens drei Tage nach seinem
  Zeitstempel vorgeschlagen; bis dahin steht es als „Pending“ in der Abhängigkeits-Übersicht, und
  Renovate legt keinen Branch an (`internalChecksFilter` steht auf der Vorgabe `strict`). Ohne
  Branch gibt es auch keinen PR-Lauf. Das ist wichtig, weil `cve-scan.yml` und
  `dependency-graph.yml` auch bei `pull_request` laufen, und zwar in der Fassung des PRs mit ihren
  Schreibrechten. Ein späteres `automerge: false` käme dafür zu spät.
  **Der Schutz ist nur teilweise:** Der Zeitstempel der Datasource `github-tags` ist das
  Commit-Datum (leichtgewichtiger Tag) bzw. das Tagger-Datum (annotierter Tag), angehoben auf das
  Datum eines GitHub-Releases, falls es eins gibt und es später liegt. Commit- und Tagger-Datum
  setzt der Autor selbst. Ohne GitHub-Release lässt sich ein Tag also rückdatieren.
- **Digest-Updates brauchen eine Freigabe** (`dependencyDashboardApproval: true`, dazu
  `automerge: false`). Ein neuer SHA bei gleicher Version heißt, dass der Tag umgehängt wurde,
  also genau der Angriff, gegen den die Pins schützen. Renovate legt den Branch erst an, wenn ein
  Maintainer das Update in der Abhängigkeits-Übersicht anhakt; vorher läuft nichts. Vor der
  Freigabe wird geklärt, warum der Tag umgehängt wurde.

Eine dritte Regel betrifft nur `publish-images.yml`: **Ihre Actions mergen nie automatisch**, auch
nicht bei Minor- und Patch-Updates. Kein Required Check übt sie aus, sie laufen aber bei jedem
Push auf `main` mit `packages: write`. Weil ein Update-Branch alle Dateien mit derselben Action
umfasst, gilt das auch für den Teil in anderen Workflows.

Alle übrigen Minor- und Patch-Releases mergen nach Ablauf der Frist wie andere Updates
automatisch, Majors nie. Das entspricht dem Stand vor den Pins, als diese Releases über den
gleitenden Major-Tag ohne PR ankamen.

### Prüfung in der CI

**Der Guard `.github/scripts/check_action_pins.sh`** (Job `changes` in `ci.yml`, bei Änderungen
unter `.github/workflows/`) lehnt in jeder Datei `.github/workflows/*.yml` und `*.yaml` jedes
`uses:` ohne vollen SHA und `# vX.Y.Z`-Kommentar ab. Er erkennt Block-Stil, gequotete Schlüssel
und Flow-Mappings (`- { uses: … }`), nicht aber einen Wert auf einer eigenen Folgezeile. Lokale
Actions (`./…`) und `docker://…@sha256:…` sind ausgenommen. Ein neuer Workflow ist ohne weiteres
Zutun erfasst. Die Selbstprobe `test_check_action_pins.py` prüft außerdem, dass die beiden
Renovate-Regeln oben keine Dateiliste tragen, also ebenfalls für jeden Workflow gelten.

### SHA ermitteln und im Review prüfen

Eine neue Action bekommt ihren SHA über die GitHub-API, nicht von Hand aus der Weboberfläche. Bei
annotierten Tags (`"type": "tag"`) zeigt die Referenz auf das Tag-Objekt, nicht auf den Commit,
und muss einmal dereferenziert werden:

```bash
gh api repos/docker/login-action/git/ref/tags/v4.6.0 --jq '.object'
# bei "type": "tag" zusätzlich:
gh api repos/<owner>/<repo>/git/tags/<sha> --jq '.object.sha'
```

**Herkunft im Review prüfen.** Ein Commit aus einem Fork ist auch über das Upstream-Repository
erreichbar. `gh api repos/<owner>/<repo>/commits/<sha>` findet ihn, und der Tarball unter
`codeload.github.com/<owner>/<repo>/tar.gz/<sha>`, den der Runner lädt, wird ausgeliefert.
Geprüft am 09.10.2026 mit einem Commit, der nur im Fork `bhouston/checkout-git-dedup` liegt,
abgefragt über `actions/checkout`. Dass der SHA existiert, beweist also nichts. Das Review
gleicht jeden neuen oder geänderten Pin mit den beiden Befehlen oben ab: Der dereferenzierte
Commit des Tags aus dem Kommentar muss genau der gepinnte SHA sein.

## Voraussetzungen

- Docker
- Ein GitHub-Token als Umgebungsvariable `RENOVATE_TOKEN` — **nie committen**. Minimaler
  Zuschnitt (Fine-grained PAT, nur Repository `criew/opaa`):
  - *Contents*: Read and write (Branches anlegen)
  - *Pull requests*: Read and write (PRs eröffnen/aktualisieren)
  - *Issues*: Read and write (Abhängigkeits-Übersicht)
  - *Workflows*: Read and write — **ohne diese Berechtigung lehnt GitHub jeden Push ab, der
    eine Datei unter `.github/workflows/` ändert**; der `github-actions`-Manager ist mit
    Abstand der größte Update-Lieferant dieses Repos
  - *Metadata*: Read

  Ein klassisches PAT braucht entsprechend die Scopes `repo` **und** `workflow`. Für den
  Alltag genügt das CLI-Token eines angemeldeten Maintainers (`RENOVATE_TOKEN=$(gh auth
  token)`) — es bringt beide Scopes mit.

## Probelauf ohne Schreibzugriff (Dry-Run)

Zeigt im Log, welche Updates ein echter Lauf anlegen würde — nichts wird geschrieben. Läuft
gegen den lokalen Arbeitsstand (nützlich auch, um Änderungen an `renovate.json5` vor dem
Merge zu prüfen):

```bash
GITHUB_COM_TOKEN=$(gh auth token) docker run --rm \
  -v "$(pwd)":/usr/src/app -w /usr/src/app \
  -e RENOVATE_PLATFORM=local \
  -e GITHUB_COM_TOKEN \
  -e LOG_LEVEL=info \
  renovate/renovate:latest
```

Der Dry-Run funktioniert auch ganz ohne Token, endet dann aber mit `WARN: GitHub token is
required for some dependencies` — die Lookups der GitHub-Datasource (alle Actions, `node`,
`python`, …) bleiben dann aus bzw. rate-limitiert. Mit Token ist es weiterhin ein reiner
Lese-Lauf.

Am Log-Ende fasst `packageFiles with updates` je Manager zusammen, was erkannt wurde und
welche neuen Versionen anstehen.

## CustomManager belegt prüfen

Ein `customManagers`-Eintrag ist die einzige Konfiguration hier, die **still** wirkungslos sein
kann: `renovate-config-validator` prüft nur die Form, und ein Eintrag, der keine Datei trifft,
erzeugt weder Fehler noch Warnung — er fehlt einfach im Log. Ein Pin kann so jahrelang
einfrieren, ohne dass es auffällt (so geschehen mit dem MinIO-Image, #1861). Ein neuer oder
geänderter Eintrag wird deshalb **belegt**, nicht angenommen:

```bash
docker run --rm -v "$(pwd)":/usr/src/app -w /usr/src/app \
  -e RENOVATE_PLATFORM=local -e LOG_LEVEL=debug \
  renovate/renovate:latest > renovate-debug.log 2>&1

grep 'Matched .* file(s) for manager regex' renovate-debug.log
```

Die `regex`-Zeilen erscheinen in der Reihenfolge der `customManagers` (den ersten beiden geht
`config:recommended` mit seinen zwei tsconfig-Managern voraus). **Ein Eintrag, der keine Datei
trifft, hat gar keine Zeile** — das ist der Befund. Erwartet:

```
DEBUG: Matched 1 file(s) for manager regex: backend/src/test/java/io/opaa/s3/S3TestFixture.java
DEBUG: Matched 1 file(s) for manager regex: backend/src/test/java/io/opaa/integration/keycloak/KeycloakFixture.java
DEBUG: Matched 1 file(s) for manager regex: .github/workflows/sbom.yml
DEBUG: Matched 2 file(s) for manager regex: .github/workflows/helm-chart.yml, .github/workflows/publish-images.yml
```

Dass die Datei getroffen wurde, heißt noch nicht, dass der `matchStrings`-Ausdruck greift; dafür
gibt es zwei weitere Belege im selben Log:

- `No dependencies found in file for custom regex manager (packageFile=…)` — Datei getroffen,
  Regex daneben.
- Der Block `packageFiles with updates` am Log-Ende führt unter `"regex"` je Eintrag den
  `packageFile` mit `depName`, `currentValue` und `currentVersion`. Steht dort die erwartete
  Version, ist die Kette vollständig belegt.

Zwei Stolpersteine, die beide schon zugeschlagen haben:

- **`ignorePaths` wirkt vor jedem Manager.** `config:recommended` bringt über
  `:ignoreModulesAndTests` unter anderem `**/test/**`, `**/tests/**`, `**/examples/**` und
  `**/__fixtures__/**` mit. Eine Datei unter `backend/src/test/…` ist damit für **alle** Manager
  unsichtbar, egal wie genau `managerFilePatterns` sie benennt. Deshalb überschreibt
  `renovate.json5` die Liste gezielt (Kommentar dort), statt sie zu leeren; neben den beiden
  Testverzeichnissen fehlt dort auch `**/examples/**`, damit der `kubernetes`-Manager den
  Erprobungsaufbau sieht. Ein Negativ-Eintrag ist in `ignorePaths` nicht möglich, die Liste dort
  ist also eine **Kopie der Preset-Vorgabe** und
  friert deren heutigen Stand ein: Ergänzt Renovate `:ignoreModulesAndTests` später um einen
  Eintrag, greift der hier nicht mehr. Bei einem Renovate-Major deshalb abgleichen — der Workflow
  läuft auf `renovate/renovate:latest`, ein solcher Wechsel passiert von selbst und still.
- **`managerFilePatterns` sind Globs**, solange sie nicht in Schrägstriche gefasst sind
  (`/…regex…/`). Ein voller Pfad ohne Platzhalter ist ein gültiges Glob und trifft genau diese
  eine Datei — erkennbar an der `Using file pattern: … for manager regex`-Zeile kurz oberhalb.

Der lokale Lauf genügt als Beleg: Er liest dieselbe `renovate.json5` und durchläuft dieselbe
Extraktionsstufe wie der Lauf gegen GitHub. Wer den echten Lauf sehen will, stößt
`.github/workflows/renovate.yml` per *Run workflow* an — der schreibt allerdings (Branches, PRs)
und liefert nur `LOG_LEVEL=info`.

## Automatischer täglicher Lauf

`.github/workflows/renovate.yml` führt täglich exakt das unten dokumentierte Docker-Kommando
aus. Einzige Voraussetzung ist das Repository-Secret **`RENOVATE_TOKEN`** — ein PAT mit den
oben beschriebenen Berechtigungen (Fine-grained inkl. *Workflows: Read and write* bzw.
klassisch `repo` + `workflow`), hinterlegt von einem Maintainer:

```bash
gh secret set RENOVATE_TOKEN
```

Die Commits der Update-Branches tragen als Autor „Renovate Bot" mit der Noreply-Adresse des
PAT-Inhabers (`gitAuthor` in `renovate.json5`) — so besteht der CLA-Check über dessen
vorhandene Unterschrift (#924). Wechselt der Token-Inhaber, muss `gitAuthor` mitziehen.

Bewusst ein PAT und nicht der eingebaute `GITHUB_TOKEN` des Workflows: Mit dem
`GITHUB_TOKEN` erstellte PRs lösen **keine** CI-Workflows aus (GitHubs Schutz vor rekursiven
Triggern) — die Update-PRs stünden dauerhaft ohne Checks da — und Workflow-Dateien dürfte er
auch nicht ändern. Fehlt das Secret, bricht der Lauf mit einer klaren Fehlermeldung ab.
Token-Rotation: neues PAT erzeugen, `gh secret set RENOVATE_TOKEN` erneut ausführen, altes
Token widerrufen.

## Manueller Lauf (erzeugt Branches und PRs)

Renovate liest die `renovate.json5` aus dem Default-Branch des Zielrepositories:

```bash
RENOVATE_TOKEN=$(gh auth token) docker run --rm \
  -e RENOVATE_TOKEN \
  -e RENOVATE_PLATFORM=github \
  -e RENOVATE_REPOSITORIES=criew/opaa \
  -e RENOVATE_ALLOWED_UNSAFE_EXECUTIONS=gradleWrapper \
  -e LOG_LEVEL=info \
  renovate/renovate:latest

`RENOVATE_ALLOWED_UNSAFE_EXECUTIONS=gradleWrapper` erlaubt Renovate, bei einem Gradle-Update den
Wrapper-Befehl auszuführen (#997) — ohne die Freigabe aktualisiert es nur
`gradle-wrapper.properties`, nicht die Wrapper-Skripte/JAR, und der Lauf meldet eine WARN-Zeile im
Dependency-Dashboard. Bewusst nur dieser eine Befehl, keine weiteren unsicheren Ausführungen.
```

Der Lauf ist idempotent: erneutes Ausführen aktualisiert bestehende Update-Branches (Rebase
bei Bedarf), schließt Überholtes und legt nur Neues an.

**Auto-Merge (#951, eingeschränkt durch #1002):** Renovate eröffnet seine PRs mit aktiviertem
GitHub-Auto-Merge (Squash) — gemergt wird automatisch, sobald die **Required Checks** grün
sind. Das gilt für minor/patch/pin/digest; **Major-Updates sind ausgenommen** und bleiben als
normale PRs zur menschlichen Entscheidung offen (Hintergrund: das auto-gemergte
`eclipse-temurin`-v25-Major brach den Backend-Image-Build, siehe #1002 und „Typische
Fehlerbilder"). Ein unerwünschtes Update lehnt man durch Schließen des PRs ab (Renovate legt
es dann nicht erneut vor). Zu beachten: `e2e` ist kein Required Check und hält den Auto-Merge
nicht auf — die E2E-Suite läuft seit #1226 gar nicht mehr bei PRs; der Lauf bei jedem Push auf
`main` und der nächtliche Lauf bleiben das Sicherheitsnetz und legen bei Fehlschlag ein
Alarm-Issue an (bewusste Repo-Entscheidung, vgl. #792).

## Konfiguration validieren

Nach Änderungen an `renovate.json5`:

```bash
docker run --rm -v "$(pwd)":/usr/src/app -w /usr/src/app \
  -e RENOVATE_CONFIG_FILE=/usr/src/app/renovate.json5 \
  --entrypoint renovate-config-validator renovate/renovate:latest
```

## Typische Fehlerbilder

- **`Repository is disabled` / Onboarding-PR statt Updates:** `renovate.json5` liegt nicht im
  Default-Branch — erst mergen, dann laufen lassen.
- **403/401 beim echten Lauf:** Token abgelaufen oder Zuschnitt zu eng (siehe oben);
  `gh auth token` liefert nur ein gültiges Token, solange `gh auth status` angemeldet ist.
- **Gradle-Updates fehlen im Log:** Der `gradle`-Manager braucht die
  `libs.versions.toml`-Einträge in Standardform (`[versions]`/`[libraries]`-Referenzen) —
  direkt in `build.gradle.kts` eingetragene Versionen sind ohnehin verboten (AGENTS.md). Ein
  `[versions]`-Eintrag **ohne** zugehörigen `[libraries]`-Eintrag hat keine Koordinate und wird
  still übergangen; das trifft besonders transitive Pins (siehe oben).
- **`pnpm install --frozen-lockfile` bricht nach einem Renovate-Tag mit
  `ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY` (#996):** Mehrere Lockfile-ändernde npm-PRs mergten
  nacheinander, ohne dass die späteren gegen den neuen Stand rebased waren — die textuell
  konfliktfreie Git-Vereinigung der `pnpm-lock.yaml` ist dann semantisch inkonsistent.
  Vorbeugung seit #1000: Non-Major-npm-Updates laufen als **ein Sammel-PR** je Lauf
  (`groupName: 'npm (non-major)'`), und npm-Branches mergen **Renovate-seitig** statt über
  GitHubs nativen Auto-Merge (`rebaseWhen: 'behind-base-branch'` + `platformAutomerge: false`)
  — gemergt wird nur ein Branch, der aktuell hinter `main` steht und grün ist; das passiert
  folglich nur während eines Renovate-Laufs. Heilung, falls es doch passiert: `pnpm install`
  auf `main`-Stand, Lockfile-Diff committen.
- **`packageManager` verliert den Corepack-Hash (#1660):** Aktualisiert ein Sammel-PR pnpm
  zusammen mit mindestens einem weiteren npm-Paket, ruft Renovate `corepack use pnpm@<version>`
  auf, bevor die Lockfile neu erzeugt ist; der Aufruf bricht mit `ERR_PNPM_OUTDATED_LOCKFILE` ab
  und der zuvor eingetragene, hashlose Wert bleibt stehen. Sichtbar wird das am roten Status
  `renovate/artifacts`. Den `+sha512.`-Anteil prüft **corepack** beim Laden der pnpm-Binary —
  in `frontend/Dockerfile` (`corepack enable`, dann `pnpm install --frozen-lockfile`, also im
  Produktions-Image-Build) und in jeder lokalen Arbeitskopie. `pnpm/action-setup` schneidet den
  Hash dagegen ab und liest nur die Version; ein Verlust fiele in der CI also von selbst nie
  auf. Deshalb prüft ihn der Guard `.github/scripts/check_package_manager_hash.sh` (Job
  `changes` in `ci.yml`, Selbstprobe in `test_check_package_manager_hash.py`). Heilung:
  `corepack use pnpm@<version>` im betroffenen Verzeichnis ausführen, sobald die Lockfile
  aktuell ist, und `package.json` committen.
- **Ein `customManagers`-Eintrag erzeugt nie einen PR, obwohl die Konfiguration validiert:** Die
  Datei liegt unter einem Pfad, den `ignorePaths` ausschließt (Vorgabe aus `config:recommended`,
  darunter `**/test/**`), oder `managerFilePatterns`/`matchStrings` treffen nicht. Der Nachweis
  steht oben unter „CustomManager belegt prüfen"; ohne Debug-Lauf ist dieser Fall unsichtbar, weil
  ein leerer Manager keine Warnung erzeugt (#1861).
- **Docker-Hub-Rate-Limit im Dry-Run:** kurz warten und wiederholen; der Lauf cached nichts
  zwischen Containern.
- **Major-Update eines Basisimages bricht einen Build, obwohl der Update-PR grün war:** Der
  brechende Job (z. B. `e2e`, das den Backend-Image-Build enthält) ist kein Required Check und
  hielt den Auto-Merge nicht auf. Seit #1002 mergen Majors deshalb nicht mehr automatisch;
  passiert es doch (z. B. manuell gemergt), Basisimage-Tag zurücksetzen und den erneut
  aufschlagenden Renovate-PR bewusst entscheiden (Vorfall: `eclipse-temurin` 21 → 25 bei
  Gradle-Toolchain `languageVersion = 21`).
- **`pnpm install --frozen-lockfile` bricht nach einem Renovate-Tag mit
  `ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY`:** Mehrere Lockfile-ändernde Update-PRs sind
  nacheinander per Auto-Merge gemergt, ohne dass die späteren gegen den neuen Stand rebased
  waren — die textuell konfliktfreie Git-Vereinigung der `pnpm-lock.yaml` ist dann semantisch
  inkonsistent (#996). Heilung: `pnpm install` auf `main`-Stand, Lockfile-Diff committen
  (Vorbild: PR #1003); Vorbeugung wird in #1000 verfolgt.
