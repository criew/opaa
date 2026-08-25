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

Regeln in [`renovate.json5`](../renovate.json5) (kommentiert): deutsche Commit-/PR-Texte im
Stil `chore(deps): <Paket> auf <Version> aktualisieren`, Labels je Bereich, höchstens fünf
gleichzeitig offene Update-PRs, Spring-Plattform als ein gebündelter PR, **kein**
Digest-Pinning für Docker-Images (gleitende Tags sind eine dokumentierte Projektentscheidung,
siehe `e2e/docker-compose.e2e.yml`). Zusätzlich pflegt Renovate ein Übersichts-Issue
(„Abhängigkeits-Übersicht (Renovate)") mit allen anstehenden Updates.

## Voraussetzungen

- Docker
- Ein GitHub-Token als Umgebungsvariable `RENOVATE_TOKEN` — **nie committen**. Minimaler
  Zuschnitt (Fine-grained PAT, nur Repository `criew/opaa`):
  - *Contents*: Read and write (Branches anlegen)
  - *Pull requests*: Read and write (PRs eröffnen/aktualisieren)
  - *Issues*: Read and write (Abhängigkeits-Übersicht)
  - *Metadata*: Read

  Ein klassisches PAT mit `repo`-Scope funktioniert ebenfalls. Für den Alltag genügt auch das
  CLI-Token eines angemeldeten Maintainers: `RENOVATE_TOKEN=$(gh auth token)`.

## Probelauf ohne Schreibzugriff (Dry-Run)

Zeigt im Log, welche Updates ein echter Lauf anlegen würde — kein Token nötig, nichts wird
geschrieben. Läuft gegen den lokalen Arbeitsstand (nützlich auch, um Änderungen an
`renovate.json5` vor dem Merge zu prüfen):

```bash
docker run --rm \
  -v "$(pwd)":/usr/src/app -w /usr/src/app \
  -e RENOVATE_PLATFORM=local \
  -e LOG_LEVEL=info \
  renovate/renovate:latest
```

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
