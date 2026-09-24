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
| GitHub-Actions-Workflows (`.github/workflows/`) | `github-actions` | `ci` |
| Docker-Basisimages (`Dockerfile`s, `docker-compose*.yml`) | `dockerfile`, `docker-compose` | `ci` |
| Demo-Seed-/Generator-Requirements (`demo/*/requirements.txt`) | `pip_requirements` | `demo` |
| Node-Version für die lokale Entwicklung (`frontend/.nvmrc`) | `nvm` | `frontend` |
| Pins, die kein regulärer Manager sieht: die Image-Konstanten in `S3TestFixture.java` und `KeycloakFixture.java`, der `pnpm dlx`-Aufruf in `sbom.yml` | `custom.regex` | `ci` |

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
DEBUG: Matched 1 file(s) for manager regex: backend/src/test/java/io/opaa/indexing/source/s3/S3TestFixture.java
DEBUG: Matched 1 file(s) for manager regex: backend/src/test/java/io/opaa/integration/keycloak/KeycloakFixture.java
DEBUG: Matched 1 file(s) for manager regex: .github/workflows/sbom.yml
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
  `renovate.json5` die Liste gezielt (Kommentar dort), statt sie zu leeren. Ein Negativ-Eintrag ist
  in `ignorePaths` nicht möglich, die Liste dort ist also eine **Kopie der Preset-Vorgabe** und
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
