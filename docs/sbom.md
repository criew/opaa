# Software Bill of Materials (SBOM)

OPAA erzeugt SBOMs auf zwei Ebenen (Issue #1078), damit Abnehmer und Prüfer nachvollziehen
können, welche Komponenten in den ausgelieferten Artefakten stecken.

## Ebene 1 — Image-Attestierungen

Jedes in GHCR veröffentlichte Image (`ghcr.io/criew/opaa-backend`, `ghcr.io/criew/opaa-frontend`)
trägt eine SBOM- und eine Provenance-Attestierung, die BuildKit beim Push erzeugt
(`.github/workflows/publish-images.yml`, `sbom: true` / `provenance: mode=max`). Die SBOM
deckt auch die OS-Pakete des Basis-Images ab.

Abrufen:

```bash
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/criew/opaa-frontend:main --format '{{ json .SBOM }}'

# Provenance-Attestierung (Build-Herkunft, Quell-Commit, Build-Parameter)
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .Provenance }}'
docker buildx imagetools inspect ghcr.io/criew/opaa-frontend:main --format '{{ json .Provenance }}'
```

## Ebene 2 — Ökosystem-SBOMs (CycloneDX, CI-Artefakte)

Der Workflow `.github/workflows/sbom.yml` erzeugt bei jedem Push auf `main` (und manuell via
`workflow_dispatch`) je ein CycloneDX-JSON pro Ökosystem und lädt sie als GitHub-Actions-Artefakte
hoch (`sbom-backend`, `sbom-frontend`, 90 Tage Aufbewahrung):

Beide SBOMs sind auf das beschränkt, was tatsächlich ausgeliefert wird — keine Test-/Lint-/Build-Toolchain:

- **Backend:** `./gradlew cyclonedxBom` aggregiert die Laufzeit-Abhängigkeiten von `backend` und
  `opaa-api` (nur `runtimeClasspath`) zu einer SBOM mit Komponentenname `opaa-backend`. Ergebnis:
  `backend/build/reports/cyclonedx/application.cdx.json`.
- **Frontend:** [cdxgen](https://github.com/CycloneDX/cdxgen) liest `frontend/pnpm-lock.yaml` mit
  `--required-only` (ohne `devDependencies` wie `eslint`/`vitest`) und erzeugt `frontend/bom.json`
  (nicht committet, siehe `.gitignore`).

## Lokal erzeugen

```bash
# Backend (aus backend/)
./gradlew cyclonedxBom

# Frontend (aus frontend/)
pnpm install --frozen-lockfile
pnpm dlx @cyclonedx/cdxgen@11.11.0 -t pnpm --no-babel --required-only --project-name opaa-frontend -o bom.json .
```
