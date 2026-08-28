# Renovate — selbst betriebene Abhängigkeits-Updates

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
nicht auf — die nächtliche E2E-Suite auf `main` bleibt das Sicherheitsnetz (bewusste
Repo-Entscheidung, vgl. #792).

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
  direkt in `build.gradle.kts` eingetragene Versionen sind ohnehin verboten (AGENTS.md).
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
