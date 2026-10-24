# Software Bill of Materials (SBOM)

OPAA erzeugt SBOMs auf zwei Ebenen (Issue #1078), damit Abnehmer und Prüfer nachvollziehen
können, welche Komponenten in den ausgelieferten Artefakten stecken. Ob darin bekannte
Schwachstellen stecken, prüft [`docs/cve-scanning.md`](./cve-scanning.md). Für Betreiber
zusammengefasst: [Handbuch, Releases und Nachweise](handbuch/betrieb/releases-und-nachweise.md#herkunft-und-software-stückliste).

## Ebene 1 — Image-Attestierungen

Jedes in GHCR veröffentlichte Image (`ghcr.io/criew/opaa-backend`, `ghcr.io/criew/opaa-frontend`)
trägt je Architektur (`linux/amd64`, `linux/arm64`) eine SBOM- und eine Provenance-Attestierung,
die BuildKit beim Push erzeugt (`.github/workflows/publish-images.yml`, Job `build`,
`sbom: true` / `provenance: mode=max`). Jede Architektur wird in einem eigenen Job auf einem Runner
dieser Architektur gebaut; der Job `publish` führt die Images samt ihren Attestierungs-Manifesten
zu einem Manifest-Index zusammen, auf den die Tags zeigen. Die SBOM deckt auch die OS-Pakete des
Basis-Images ab.

Abrufen (die Ausgabe ist nach Plattform gegliedert):

```bash
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/criew/opaa-frontend:main --format '{{ json .SBOM }}'

# Provenance-Attestierung (Build-Herkunft, Quell-Commit, Build-Parameter)
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .Provenance }}'
docker buildx imagetools inspect ghcr.io/criew/opaa-frontend:main --format '{{ json .Provenance }}'

# nur eine Plattform
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json (index .SBOM "linux/arm64") }}'
```

<a id="ebene-2--ökosystem-sboms-cyclonedx-ci-artefakte"></a>

## Ebene 2 — Ökosystem-SBOMs (CycloneDX)

Je Ökosystem entsteht ein CycloneDX-JSON, für `main` als CI-Artefakt, für jedes Release am
GitHub-Release und im Update-Paket.

**`main`:** Der Workflow `.github/workflows/sbom.yml` erzeugt sie bei jedem Push auf `main` (und
manuell via `workflow_dispatch`) und lädt sie als GitHub-Actions-Artefakte hoch (`sbom-backend`,
`sbom-frontend`, 90 Tage Aufbewahrung).

**Release:** Der Job `update-package` in `.github/workflows/publish-images.yml` erzeugt sie aus dem
Stand des Tags, mit demselben Werkzeug, derselben Version und demselben Umfang wie für `main`
(`.github/scripts/update_package.py`; `test_update_package.py` hält Installation und Aufruf von
cdxgen gleich denen in `sbom.yml`). Die Frontend-SBOM nennt als beschriebene Komponente die
Release-Version statt der `0.0.0` aus `frontend/package.json`. Sie hängen als `opaa-backend-X.Y.Z.cdx.json` und `opaa-frontend-X.Y.Z.cdx.json` am
GitHub-Release, jede mit eigener Provenance-Attestierung, und liegen unter denselben Namen in
`sbom/` des [Update-Pakets](releases.md#update-paket), dort abgedeckt von `SHA256SUMS` und der
Attestierung des Archivs. Zeitstempel und Seriennummer jeder SBOM legt das Release fest
(Commit-Zeit, aus Repository, Commit und Dateiname abgeleitete UUID); die Zusammenfassung, die
cdxgen als Annotation mit dem Tag des Laufs anhängt, entfällt. So ergibt ein wiederholter Lauf
dieselben Dateien. Scheitert eine der beiden SBOMs oder enthält sie keine Komponenten,
scheitert der Release-Lauf, bevor ein Tag gesetzt ist.

```bash
gh release download vX.Y.Z --repo criew/opaa --pattern 'opaa-*.cdx.json'
gh attestation verify opaa-backend-X.Y.Z.cdx.json --repo criew/opaa
gh attestation verify opaa-frontend-X.Y.Z.cdx.json --repo criew/opaa
```

Beide SBOMs sind auf das beschränkt, was tatsächlich ausgeliefert wird — keine Test-/Lint-/Build-Toolchain:

- **Backend:** `./gradlew cyclonedxBom` aggregiert die Laufzeit-Abhängigkeiten von `backend` und
  `opaa-api` (nur `runtimeClasspath`) zu einer SBOM mit Komponentenname `opaa-backend`. Ergebnis:
  `backend/build/reports/cyclonedx/application.cdx.json`.
- **Frontend:** [cdxgen](https://github.com/CycloneDX/cdxgen) liest `frontend/pnpm-lock.yaml` mit
  `--required-only` (ohne `devDependencies` wie `eslint`/`vitest`) und erzeugt `frontend/bom.json`
  (nicht committet, siehe `.gitignore`).

**cdxgen selbst** kommt aus einer eigenen Lockfile unter `.github/tools/cdxgen/`: Version und jede
transitive Abhängigkeit stehen mit Prüfsumme in `pnpm-lock.yaml`, installiert mit
`pnpm install --frozen-lockfile --ignore-scripts --no-optional` (keine Installationsskripte, keine
optionalen Plattform-Binärpakete, die der pnpm-Scan nicht braucht). Ein Release-Lauf bezieht also
keinen Werkzeugstand, den kein PR gesehen hat. Renovate pflegt Version und Lockfile, ohne
Auto-Merge; ein solcher PR startet `sbom.yml` und probt damit den Aufruf.

## Lokal erzeugen

```bash
# Backend (aus backend/)
./gradlew cyclonedxBom

# cdxgen (aus .github/tools/cdxgen/)
pnpm install --frozen-lockfile --ignore-scripts --no-optional

# Frontend (aus frontend/)
pnpm install --frozen-lockfile
../.github/tools/cdxgen/node_modules/.bin/cdxgen -t pnpm --no-babel --required-only --project-name opaa-frontend -o bom.json .
```

## Lizenzprüfung

Die CI prüft bei jedem PR, der Abhängigkeiten berührt, und bei jedem Push auf `main` die Lizenzen
aller ausgelieferten Abhängigkeiten gegen eine Freigabeliste und erzeugt dabei eine
Lizenzübersicht. Die Prüfung läuft in den Jobs `backend-build` (über den Required Check
`backend`) und `frontend` von `.github/workflows/ci.yml`. Ein Renovate-PR, der eine Abhängigkeit
mit nicht freigegebener Lizenz einbringt, wird deshalb nicht automatisch gemergt; gepflegt werden
muss dafür nichts.

| Teil | Ort |
|---|---|
| Prüfung | `.github/scripts/check_licenses.py`, Selbstprobe `test_check_licenses.py` |
| Freigabeliste (SPDX-Kennungen) | `.github/licenses/allowed.txt` |
| Ausnahmeliste, eine Zeile je Paket mit Begründung | `.github/licenses/exceptions.txt` |
| Lizenzübersicht (Markdown, generiert) | CI-Artefakte `license-report-backend` und `license-report-frontend`, 90 Tage |

Geprüft wird derselbe Umfang wie in den SBOMs, nur Laufzeit-Abhängigkeiten:

- **Backend:** die CycloneDX-SBOM, die `./gradlew build` ohnehin erzeugt
  (`backend/build/reports/cyclonedx/application.cdx.json`); die eigenen Module `io.opaa` sind
  ausgenommen.
- **Frontend:** `pnpm licenses list --prod --json`. Die cdxgen-SBOM taugt dafür nicht, weil sie
  für einen Teil der Pakete keine Lizenz enthält. `--prod` erfasst nur, was als `dependencies`
  deklariert ist. Vite kopiert aber auch `public/` unverändert in den Build, darunter
  `public/mockServiceWorker.js` aus `msw` (devDependency, MIT). Der Worker dient nur
  Entwicklungsläufen mit `VITE_ENABLE_MOCKS`, deshalb löscht `frontend/Dockerfile` ihn nach dem
  Build, und das Image enthält ihn nicht. Wer eine Datei aus einem devDependency-Paket nach
  `public/` legt und ausliefert, muss deren Lizenz selbst prüfen.
- **e2e/** wird nicht ausgeliefert und nicht geprüft.

Eine Abhängigkeit besteht, wenn ihr Lizenzausdruck mit der Freigabeliste erfüllt ist (`A OR B`:
eine Seite, `A AND B`: beide, `A WITH <Zusatz>`: `A`). Mehrere Lizenzangaben eines Pakets in der
SBOM gelten als Wahlmöglichkeit, wie bei einem doppelt lizenzierten Maven-POM. Fehlt die Lizenz,
meldet die Prüfung `NOASSERTION`. Sonst besteht das Paket nur über eine Ausnahme, die genau seine
heutige Lizenz nennt. Die Freigabeliste folgt den Lizenzen, die die FSF als frei und mit GPLv3
verträglich führt; Begründungen stehen im Kopf der Datei.

### Vorgehen bei einem Befund

Schlägt die Prüfung fehl, nennt das Log jede Abhängigkeit mit `NOT ALLOWED`, ihrer Version und
ihrer Lizenz; die Lizenzübersicht im Artefakt markiert sie als „nicht freigegeben“.

1. **Herkunft klären:** Welche direkte Abhängigkeit zieht das Paket herein
   (`./gradlew dependencyInsight --configuration runtimeClasspath --dependency <name>` bzw.
   `pnpm why <name>`)?
2. **Lizenzangabe prüfen:** Steht im POM oder in der `package.json` nur ein Name statt einer
   SPDX-Kennung, oder ordnet das SBOM-Plugin falsch zu, genügt eine Ausnahme mit der richtigen
   Einordnung als Begründung.
3. **Entscheiden:** Das Paket vermeiden (andere Version, andere Bibliothek, Ausschluss der
   transitiven Abhängigkeit) oder eine Ausnahme eintragen. Eine Ausnahme ist eine Zeile in
   `.github/licenses/exceptions.txt`:

   ```text
   pkg:maven/<group>/<artifact> | <Lizenz, wie sie die Prüfung meldet> | <Begründung>
   pkg:npm/<paket> | <Lizenz> | <Begründung>
   ```

   Der purl steht ohne Version; ein `*` am Ende deckt alle Artefakte einer Gruppe ab. Ändert ein
   späteres Update die Lizenz, greift die Ausnahme nicht mehr und die Prüfung schlägt erneut an.
4. **Freigeben lassen:** Ausnahme und Erweiterung der Freigabeliste gehen im Review an einen
   Maintainer. Eine rechtliche Einzelfallbewertung trifft die Prüfung nicht; sie macht nur
   sichtbar, wo eine nötig ist.

**Renovate-PRs** mit Befund bleiben offen, weil der Required Check rot ist. Wer den PR übernimmt,
geht wie oben vor und pusht die Ausnahme auf den Renovate-Branch oder schließt den PR, wenn das
Update abgelehnt wird. Ein Hinweis `warning: unused exception` im Log bedeutet, dass ein Paket
entfallen ist; die Zeile kann bei Gelegenheit entfernt werden, die Prüfung scheitert daran nicht.

Lokal prüfen (aus dem Repository-Wurzelverzeichnis, nach `./gradlew build` bzw. `pnpm install`):

```bash
python3 .github/scripts/check_licenses.py --allowed .github/licenses/allowed.txt \
  --exceptions .github/licenses/exceptions.txt \
  --cyclonedx backend/build/reports/cyclonedx/application.cdx.json --ignore 'pkg:maven/io.opaa/*' \
  --title Backend --report /tmp/licenses-backend.md

(cd frontend && pnpm licenses list --prod --json) > /tmp/pnpm-licenses.json
python3 .github/scripts/check_licenses.py --allowed .github/licenses/allowed.txt \
  --exceptions .github/licenses/exceptions.txt --pnpm /tmp/pnpm-licenses.json \
  --title Frontend --report /tmp/licenses-frontend.md
```
