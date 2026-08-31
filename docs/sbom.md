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
```

## Ebene 2 — Ökosystem-SBOMs (CycloneDX, CI-Artefakte)

Der Workflow `.github/workflows/sbom.yml` erzeugt bei jedem Push auf `main` (und manuell via
`workflow_dispatch`) je ein CycloneDX-JSON pro Ökosystem und lädt sie als GitHub-Actions-Artefakte
hoch (`sbom-backend`, `sbom-frontend`, 90 Tage Aufbewahrung):

- **Backend:** `./gradlew cyclonedxBom` aggregiert die Laufzeit-Abhängigkeiten von `backend` und
  `opaa-api` (nur `runtimeClasspath`, keine Test-/Build-Toolchain) zu einer SBOM mit
  Komponentenname `opaa-backend`. Ergebnis: `backend/build/reports/cyclonedx/application.cdx.json`.
- **Frontend:** [cdxgen](https://github.com/CycloneDX/cdxgen) liest `frontend/pnpm-lock.yaml` und
  erzeugt `frontend/bom.json`.

## Lokal erzeugen

```bash
# Backend (aus backend/)
./gradlew cyclonedxBom

# Frontend (aus frontend/)
pnpm install --frozen-lockfile
pnpm dlx @cyclonedx/cdxgen@11.11.0 -t pnpm --no-babel -o bom.json .
```
