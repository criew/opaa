# Renovate — selbst betriebene Abhängigkeits-Updates

OPAA nutzt [Renovate](https://docs.renovatebot.com/) für automatisierte Update-PRs — **selbst
betrieben, ohne den Mend-Cloud-Service** (Issue #751): Es ist keine GitHub-App installiert,
kein externer Dienst hat Zugriff auf das Repository. Ein Lauf wird bewusst von einem
Maintainer angestoßen und läuft lokal im offiziellen Docker-Image.

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

## Echter Lauf (erzeugt Branches und PRs)

Renovate liest die `renovate.json5` aus dem Default-Branch des Zielrepositories:

```bash
RENOVATE_TOKEN=$(gh auth token) docker run --rm \
  -e RENOVATE_TOKEN \
  -e RENOVATE_PLATFORM=github \
  -e RENOVATE_REPOSITORIES=criew/opaa \
  -e LOG_LEVEL=info \
  renovate/renovate:latest
```

Der Lauf ist idempotent: erneutes Ausführen aktualisiert bestehende Update-Branches (Rebase
bei Bedarf), schließt Überholtes und legt nur Neues an. Die Update-PRs durchlaufen die normale
CI und den normalen Review-/Merge-Weg (AGENTS.md) — Renovate merged nichts selbst.

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
- **Docker-Hub-Rate-Limit im Dry-Run:** kurz warten und wiederholen; der Lauf cached nichts
  zwischen Containern.
