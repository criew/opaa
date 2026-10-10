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
| GitHub-Actions-Workflows (`.github/workflows/`); in Workflows mit Schreibrechten Commit-SHA und Versionskommentar gemeinsam (siehe [unten](#actions-in-workflows-mit-schreibrechten-per-commit-sha)) | `github-actions` | `ci` |
| Docker-Basisimages (`Dockerfile`s, `docker-compose*.yml`) | `dockerfile`, `docker-compose` | `ci` |
| Helm-Chart (`deploy/helm/opaa/`): Abhängigkeiten in `Chart.yaml` (derzeit keine) und Images mit festem Tag in `values.yaml` (die Evaluierungsdatenbank); Backend und Frontend folgen der Chart-Version und tragen dort keinen Tag | `helmv3`, `helm-values` | `ci` |
| Images der Hilfsdienste im Kubernetes-Erprobungsaufbau (`examples/kubernetes-trial/manifests/` und `jobs/`); ohne Auto-Merge, weil keine CI den Aufbau prüft — Beleg vor dem Merge mit `trial.sh up` (README dort) | `kubernetes` | `ci` |
| Demo-Seed-/Generator-Requirements (`demo/*/requirements.txt`) | `pip_requirements` | `demo` |
| Node-Version für die lokale Entwicklung (`frontend/.nvmrc`) | `nvm` | `frontend` |
| Pins, die kein regulärer Manager sieht: die Image-Konstanten in `S3TestFixture.java` und `KeycloakFixture.java`, der `pnpm dlx`-Aufruf in `sbom.yml`, die Werkzeugversionen der Chart-Prüfung in `helm-chart.yml`, die Helm-Version der Chart-Veröffentlichung in `publish-images.yml`, das Renovate-Image in `renovate.yml` (siehe [unten](#renovate-image-und-token-des-workflows)) | `custom.regex` | `ci` |

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
siehe `e2e/docker-compose.e2e.yml`; einzige Ausnahme ist das Renovate-Image selbst, siehe
[unten](#renovate-image-und-token-des-workflows)). Zusätzlich pflegt Renovate ein Übersichts-Issue
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

## Actions in Workflows mit Schreibrechten per Commit-SHA

Im Repository gilt für Actions grundsätzlich **Tag-Pinning** (`uses: actions/checkout@v7`). Davon
ausgenommen sind die Workflows, die veröffentlichte Artefakte oder das Repository selbst verändern
können (#2397). Dort ist jede Action auf den vollen Commit-SHA festgelegt, mit der exakten Version
als Kommentar:

```yaml
- uses: docker/login-action@dbcb813823bdd20940b903addbd779551569679f # v4.6.0
```

Ein Tag lässt sich auf anderen Code umhängen, ein SHA nicht. Wird ein Action-Repository
kompromittiert, kann ein umgehängter Tag sonst in einem Release-Lauf manipulierte Images
veröffentlichen und gültig attestieren.

| Workflow | Schreibrecht, das die Aufnahme begründet |
|---|---|
| `publish-images.yml` | `packages: write`, `id-token: write`, `attestations: write` (Images, Chart, Attestierungen), `contents: write` (GitHub-Release) |
| `cve-scan.yml` | `security-events: write` (Code-Scanning-Alerts), `issues: write` |
| `cla.yml` | `contents: write` (Branch `cla-signatures`), zusätzlich das PAT `CLA_TOKEN`; läuft als `pull_request_target` |
| `daily-report.yml` | `contents: write` (Push auf `gh-pages`) |
| `landing-page.yml` | `contents: write` (Push auf `gh-pages`) |
| `dependency-graph.yml` | `contents: write` (Dependency-Snapshots, Grundlage der Dependabot-Alerts) |

Nicht aufgenommen sind Workflows, die höchstens Issues, PR-Kommentare oder Caches schreiben
(`e2e.yml`, `retrieval-regression.yml`, `baseline-diff.yml`) oder nur lesen. `renovate.yml`
nutzt zwar das PAT `RENOVATE_TOKEN`, enthält aber keine Action, sondern startet ein
Docker-Image. Eine Ausweitung auf alle Workflows wäre eine Abkehr von der Grundregel und
bekäme ein eigenes Issue.

**Renovate pflegt SHA und Kommentar gemeinsam.** Drei `packageRules` in `renovate.json5` regeln
für genau diese Dateien Folgendes:

- **`pinDigests: true`** beim `github-actions`-Manager. Renovate liest `currentValue` aus dem
  Kommentar und `currentDigest` aus dem SHA und schreibt bei einem Update beides neu.
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
  Maintainer das Update in der Abhängigkeits-Übersicht anhakt; vorher läuft nichts mit
  Schreibrechten. Vor der Freigabe wird geklärt, warum der Tag umgehängt wurde.
- **`publish-images.yml` mergt nie automatisch**, auch nicht bei Minor- und Patch-Updates. Kein
  Required Check übt diese Actions aus, sie laufen aber bei jedem Push auf `main` mit
  `packages: write`.

In den übrigen fünf Dateien mergen Minor- und Patch-Releases nach Ablauf der Frist wie alle
anderen Updates automatisch, Majors nie.

**Der Guard `.github/scripts/check_action_pins.sh`** (Job `changes` in `ci.yml`, bei Änderungen
unter `.github/workflows/`) lehnt in diesen Dateien jedes `uses:` ohne vollen SHA und
`# vX.Y.Z`-Kommentar ab. Er erkennt Block-Stil, gequotete Schlüssel und Flow-Mappings
(`- { uses: … }`), nicht aber einen Wert auf einer eigenen Folgezeile. Lokale Actions (`./…`)
und `docker://…@sha256:…` sind ausgenommen. Die Selbstprobe `test_check_action_pins.py` prüft
außerdem:

- Die Dateiliste des Guards stimmt mit der in den Renovate-Regeln überein. Wer einen Workflow
  aufnimmt oder entfernt, ändert beide.
- Jeder Workflow mit einem `…: write`-Recht steht entweder in dieser Liste oder in der
  Ausnahmeliste `WRITE_WITHOUT_PINNING` im Test, mit Begründung. Ein neuer Workflow mit
  Schreibrechten fällt so in der CI auf.

Eine neue Action in einem dieser Workflows bekommt ihren SHA über die GitHub-API, nicht von Hand
aus der Weboberfläche. Bei annotierten Tags (`"type": "tag"`) zeigt die Referenz auf das
Tag-Objekt, nicht auf den Commit, und muss einmal dereferenziert werden:

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
- Ein GitHub-Token als Umgebungsvariable `RENOVATE_TOKEN` — **nie committen**. Die minimalen
  Rechte und warum ein Fine-grained PAT hier nur vom Konto `criew` ausgestellt werden kann,
  stehen unter [Token: minimale Rechte](#token-minimale-rechte). Ein klassisches PAT braucht die
  Scopes `repo` **und** `workflow`. Für den Alltag genügt das CLI-Token eines angemeldeten
  Maintainers (`RENOVATE_TOKEN=$(gh auth token)`) — es bringt beide Scopes mit.

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
  wechselt erst mit dem Merge des Major-PRs für das Renovate-Image, das ist der Zeitpunkt dafür.
- **`managerFilePatterns` sind Globs**, solange sie nicht in Schrägstriche gefasst sind
  (`/…regex…/`). Ein voller Pfad ohne Platzhalter ist ein gültiges Glob und trifft genau diese
  eine Datei — erkennbar an der `Using file pattern: … for manager regex`-Zeile kurz oberhalb.

Der lokale Lauf genügt als Beleg: Er liest dieselbe `renovate.json5` und durchläuft dieselbe
Extraktionsstufe wie der Lauf gegen GitHub. Wer den echten Lauf sehen will, stößt
`.github/workflows/renovate.yml` per *Run workflow* an — der schreibt allerdings (Branches, PRs)
und liefert nur `LOG_LEVEL=info`.

## Automatischer täglicher Lauf

`.github/workflows/renovate.yml` führt täglich das unten dokumentierte Docker-Kommando aus, mit
dem gepinnten Image statt `latest` (siehe
[Renovate-Image und Token des Workflows](#renovate-image-und-token-des-workflows)). Einzige
Voraussetzung ist das Repository-Secret **`RENOVATE_TOKEN`** — ein PAT mit den
[dort beschriebenen Berechtigungen](#token-minimale-rechte), hinterlegt von einem Maintainer:

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

## Renovate-Image und Token des Workflows

### Image auf Version und Digest festgelegt

`renovate.yml` übergibt dem Image das Token `RENOVATE_TOKEN`, das Code und Workflows im
Repository ändern kann (#2433). Mit `renovate/renovate:latest` liefe jede neu veröffentlichte
Fassung beim nächsten Lauf ungeprüft mit diesem Token, also mit mehr Rechten als jede gepinnte
Action. Deshalb steht das Image in der Variable `IMAGE` des Schritts mit Version und Digest:

```yaml
# renovate: datasource=docker depName=renovate/renovate
IMAGE: renovate/renovate:44.142.0@sha256:e9c2dcbc5a6027e68755b98cccb10470da8f941d0fbfaa67c0d85b7e064025c7
```

Der Digest ist der des Multi-Arch-Index, nicht der eines einzelnen Plattform-Images. Docker
startet mit `Tag@Digest` immer den Digest; ein umgehängter Tag ändert also nichts. Docker Hub und
`ghcr.io/renovatebot/renovate` liefern denselben Index-Digest. Ermitteln und Herkunft prüfen:

```bash
docker buildx imagetools inspect renovate/renovate:<version> --format '{{json .Manifest.Digest}}'
docker buildx imagetools inspect ghcr.io/renovatebot/renovate:<version> --format '{{json .Manifest.Digest}}'
# Signatur des Renovate-Release-Workflows (keyless, Sigstore):
docker run --rm ghcr.io/sigstore/cosign/cosign:v3.0.2 verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/renovatebot/renovate/\.github/workflows/' \
  ghcr.io/renovatebot/renovate@<digest>
```

**Update-Weg.** Den `docker run`-Aufruf sieht kein regulärer Manager; ein eigener Eintrag unter
`customManagers` liest `currentValue` und `currentDigest` aus der Zeile unter dem Kommentar, ein
Update schreibt beide neu. Zwei `packageRules` gelten nur für dieses Image:

- **`pinDigests: true`**, als ausdrücklich festgehaltene Ausnahme von der Docker-Regel
  `pinDigests: false`. Einen vorhandenen Digest pflegt Renovate auch ohne die Option mit. Fehlt
  der Digest in der Zeile, greift der Ausdruck des Managers nicht mehr und das Image fiele aus
  der Pflege; das fängt die Selbstprobe `test_renovate_image_pin.py` in der CI ab.
- **`minimumReleaseAge: '3 days'`** wie bei den gepinnten Actions (Renovate rechnet seinen
  Vorgabepuffer `minimumReleaseAgeBuffer` von 30 Minuten dazu), und **`automerge: false`** für
  alle Updates, auch Minor und Patch. Renovate veröffentlicht mehrmals täglich; der Update-PR
  (Branch `renovate/renovate-renovate-44.x`) bleibt offen und wird bei jedem Lauf auf das neueste
  Release gehoben, das die Frist erfüllt. Ein Major kommt als eigener PR.
- **Ein neuer Digest bei gleicher Version** braucht die Freigabe in der Abhängigkeits-Übersicht
  (`dependencyDashboardApproval: true`). Ein Versionstag wie `44.142.0` wird nach der
  Veröffentlichung nicht neu belegt; ein solcher Vorschlag heißt, dass der Tag umgehängt wurde.

Der Update-PR selbst führt nichts mit dem Token aus: `renovate.yml` läuft nur nach Zeitplan und
per *Run workflow*, nie bei `pull_request`. Das neue Image startet erst mit dem nächsten Lauf nach
dem Merge. Vor dem Merge: Release Notes der übersprungenen Versionen überfliegen und den Digest
mit den Befehlen oben gegen beide Registries und die Signatur abgleichen.

**Zeitstempel kommen von Docker Hub.** Die Wartezeit rechnet ab `tag_last_pushed`, den die
Registry beim Push setzt, nicht der Autor; anders als bei Git-Tags lässt er sich nicht
rückdatieren. Anonym liefert Docker Hub aber höchstens 10 Seiten der Tag-Liste (1000 Tags), und
`renovate/renovate` hat deutlich mehr. Ohne Begrenzung bricht die Abfrage auf Seite 11 mit 403 ab
(`pagination offset too large for anonymous requests`), Renovate weicht auf die Tag-Liste der
Registry ohne Zeitstempel aus und hält mit `minimumReleaseAgeBehaviour=timestamp-required` jedes
neuere Release als „Pending“ zurück. Der Pin fröre still ein. Deshalb setzt `renovate.yml`
`RENOVATE_DOCKER_MAX_PAGES=10`: Renovate liest die 1000 zuletzt geschobenen Tags und behält deren
Zeitstempel. Die Einstellung gilt für alle Docker-Hub-Images; Images mit weniger als 1000 Tags
betrifft sie nicht, bei den übrigen fehlen nur Tags, die seit Langem niemand geschoben hat. Bei
anderen Registries außer `ghcr.io` und `quay.io` begrenzt sie die Tag-Liste auf 10 statt der
Vorgabe von 20 Seiten.

**Beleg nach einer Änderung** am Eintrag oder an der Zeile (die Selbstprobe
`.github/scripts/test_renovate_image_pin.py` prüft Zeile, Ausdruck und Regeln, aber keine
Abfrage gegen die Registry):

```bash
docker run --rm -v "$(pwd)":/usr/src/app:ro -w /usr/src/app \
  -e RENOVATE_PLATFORM=local -e RENOVATE_ENABLED_MANAGERS=custom.regex \
  -e RENOVATE_DOCKER_MAX_PAGES=10 -e LOG_LEVEL=debug \
  <Image aus renovate.yml> > renovate-debug.log 2>&1
```

Im Block `packageFiles with updates` steht unter `.github/workflows/renovate.yml` der Eintrag
`renovate/renovate` mit `currentValue` und `currentDigest`. Ein neueres Release erscheint als
`newValue` mit `newDigest` und `releaseTimestamp`, jüngere als `pendingVersions`. Fehlt
`releaseTimestamp` und steht im Log `Marking … release(s) as pending, as they do not have a
releaseTimestamp`, greift die Seitenbegrenzung nicht.

**Warum nicht `renovatebot/github-action`.** Die offizielle Action startet ebenfalls ein
Docker-Image und empfiehlt selbst, die Renovate-Version per Regex-Manager zu pinnen. Sie bringt
also keinen eigenen Pin mit, sondern eine zweite Komponente, die das Token in der Hand hat: Ihr
JavaScript läuft mit dem Token im Runner und bräuchte ihrerseits einen SHA-Pin. `renovate.yml`
käme dann in die Liste des Guards `check_action_pins.sh`. Der direkte `docker run` hat eine
vertrauenswürdige Komponente weniger und bleibt deshalb.

### Token: minimale Rechte

Ein klassisches PAT mit `repo` und `workflow` gilt für **alle** Repositories, auf die sein
Inhaber Zugriff hat. Kleiner geht es mit einem Fine-grained PAT, das nur `criew/opaa` sieht.
Renovate braucht dort ([Renovate-Doku, GitHub-Plattform](https://docs.renovatebot.com/modules/platform/github/)):

| Recht | Zugriff | Wofür |
|---|---|---|
| *Contents* | Read and write | `renovate.json5` lesen, Update-Branches pushen, Renovate-seitiger Merge der npm-PRs |
| *Pull requests* | Read and write | PRs eröffnen, aktualisieren, GitHub-Auto-Merge setzen |
| *Issues* | Read and write | Abhängigkeits-Übersicht, Labels |
| *Workflows* | Read and write | Pushes, die Dateien unter `.github/workflows/` ändern (Actions, dieses Image) |
| *Commit statuses* | Read and write | Status `renovate/stability-days` und `renovate/artifacts`, Prüfstatus vor dem Merge |
| *Dependabot alerts* | Read-only | Schwachstellen-Hinweise in Update-PRs; ohne das Recht nur eine Warnung im Log |
| *Metadata* | Read-only | wird automatisch gesetzt |

*Members* aus der Renovate-Liste entfällt, weil `criew` ein persönliches Konto ist und keine
Organisation.

**Einschränkung: Ein Fine-grained PAT kann hier nur das Konto `criew` ausstellen.** Fine-grained
PATs gelten für Repositories ihres Inhabers oder seiner Organisationen. Wer nur Collaborator
eines fremden persönlichen Repositorys ist, kann damit nicht schreiben
([GitHub-Doku, Limitations](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#fine-grained-personal-access-tokens-limitations)).
Das heutige Token gehört laut `gitAuthor` dem Collaborator-Konto `bigpuritz`; mit diesem Konto
ist ein Fine-grained PAT für `criew/opaa` also nicht möglich. Wege:

1. **Fine-grained PAT des Kontos `criew`** (empfohlen, kleinster Umbau). Als `criew` anmelden,
   *Settings → Developer settings → Fine-grained tokens → Generate new token*; *Resource owner*
   `criew`, *Only select repositories* `criew/opaa`, die Rechte der Tabelle, Ablauf höchstens ein
   Jahr. Hinterlegen mit `gh secret set RENOVATE_TOKEN`, *Run workflow* einmal anstoßen und im Log
   auf 401/403 prüfen, danach das alte klassische PAT widerrufen. Im selben PR `gitAuthor` in
   `renovate.json5` auf die Noreply-Adresse von `criew` umstellen
   (`<id>+criew@users.noreply.github.com`, `<id>` aus `gh api users/criew --jq .id`), und vorher
   klären, dass `criew` die CLA unterschrieben hat oder in der Allowlist steht; sonst fallen die
   Update-PRs durch den CLA-Check (#924). PRs und Commits zeigen dann `criew` als Urheber.
2. **Eigene GitHub App**, nur in `criew/opaa` installiert, mit den Rechten aus der Renovate-Doku.
   Die Installations-Tokens gelten eine Stunde, kein Personenkonto steht dahinter. Der Workflow
   müsste das Token mit einer Action erzeugen (`actions/create-github-app-token`, SHA-gepinnt),
   und `renovate.yml` käme in die Pin-Liste des Guards. Das ist ein eigener Umbau mit eigenem Issue.
3. **Klassisches PAT eines eigenen Maschinenkontos**, das nur Collaborator von `criew/opaa` ist.
   `repo` reicht dann nicht weiter als dieses eine Repository, die Scopes bleiben aber grob.

Solange keiner der Wege umgesetzt ist, bleibt das klassische PAT mit `repo` und `workflow`. Das
Pinning des Images verkleinert das Risiko unabhängig davon: Ohne gemergten Update-PR startet mit
dem Token kein anderes Image.

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
