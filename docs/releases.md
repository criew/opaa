# Releases

Ein Release ist ein unveränderlicher, benannter Stand von OPAA: zwei Container-Images in GHCR mit
derselben Versionsnummer und ein GitHub-Release mit der Änderungsübersicht. Daneben gibt es weiter
den beweglichen Stand `main`, der jedem Merge folgt. Wer reproduzierbar betreiben will, etwa mit dem
geplanten Helm-Chart (Epic #2346), pinnt eine Version.

## Was ein Release erzeugt

Ein Git-Tag `vX.Y.Z` auf einem Commit von `main` startet `.github/workflows/publish-images.yml`:

| Ergebnis | Inhalt |
|---|---|
| `ghcr.io/criew/opaa-backend:X.Y.Z`, `ghcr.io/criew/opaa-frontend:X.Y.Z` | der Release-Stand, **nie überschrieben** |
| `…:X.Y` | wandert mit jedem Patch-Release dieser Linie mit |
| `…:sha-<commit>` | wie bei jedem Bau, „aus welchem Commit“ |
| GitHub-Release `OPAA vX.Y.Z` | Änderungsübersicht aus den gemergten PRs, gruppiert nach Label (`.github/release.yml`), dazu die Image-Namen |

Wie bei `main` tragen die Images eine SBOM- und eine Provenance-Attestierung
([sbom.md](sbom.md)).

Ein Tag `latest` gibt es bewusst nicht. Wer keine Version pinnt, folgt `main`.

Ein Vorab-Stand (`vX.Y.Z-rc.1`, `vX.Y.Z-beta.2`) bekommt nur seine volle Version als Image-Tag,
kein `X.Y`, und wird als Vorab-Release markiert.

## Schutzregeln im Workflow

Bevor gebaut wird, bricht der Lauf ab, wenn:

- das Tag nicht die Form `vX.Y.Z` bzw. `vX.Y.Z-<vorab>` hat,
- der getaggte Commit nicht auf `main` liegt,
- `X.Y.Z` in GHCR bereits existiert. Das gilt auch für einen erneut gepushten Tag und einen
  manuellen Lauf auf dem Tag. Eine fehlerhafte Version wird nicht ersetzt, sondern durch die
  nächste Patch-Version abgelöst.

Der wöchentliche Neubau (#1450) läuft nur auf `main`. Er berührt Release-Tags nie.

## Wer ein Release anlegt

Releases legen Maintainer an, nie ein Agent ohne ausdrückliche Freigabe. Ablauf:

1. Prüfen, dass `main` grün ist, einschließlich des letzten nächtlichen E2E-Laufs.
2. Version nach den Regeln unten bestimmen.
3. Tag setzen und pushen:

   ```bash
   git fetch origin
   git tag -a v0.1.0 origin/main -m "OPAA v0.1.0"
   git push origin v0.1.0
   ```

4. Den Lauf von „Publish Images“ abwarten. Danach steht das GitHub-Release unter *Releases*.
5. Die generierten Notizen bei Bedarf ergänzen, vor allem um **Vorbereitungsschritte** für
   Bestandsinstallationen, wenn das Release welche verlangt.

Die erste Version ist `v0.1.0`.

## Versionsnummern

OPAA zählt nach [Semantic Versioning](https://semver.org/lang/de/). Solange die Hauptversion `0`
ist, gilt:

| Teil | Erhöht bei |
|---|---|
| `Y` in `0.Y.Z` | neuen Funktionen **und** jedem Bruch |
| `Z` in `0.Y.Z` | ausschließlich Fehlerbehebungen und Sicherheitsupdates, auch des Basis-Images |

Ab `1.0.0` steigt bei einem Bruch die Hauptversion.

**Als Bruch gilt**, was eine Bestandsinstallation zu Handarbeit zwingt:

- eine neue Pflicht-Umgebungsvariable oder ein geänderter Wert, ohne den der Start scheitert,
- ein Vorbereitungsschritt im Handbuch, z. B. Eigentumsrechte, Ports, Volumes,
- eine inkompatible Änderung an `/api/v1` oder am MCP-Endpunkt,
- eine Migration, die eine Neuindizierung oder einen Neuaufbau verlangt.

Schemaänderungen laufen immer vorwärts. Zurück auf eine ältere Version geht es nur über die
Datenbanksicherung, unabhängig davon, ob die Version als Bruch gezählt wird.

Releases entstehen nur von `main`. Eine ältere Linie wird nicht nachgepflegt. Eine Korrektur
erscheint in der nächsten Version von `main`.

## Sicherheitsupdates für Releases

Weil Release-Images nie neu gebaut werden, tragen sie den Sicherheitsstand ihres Bautags. Der
nächtliche CVE-Scan prüft deshalb neben `main` auch die Images des jüngsten Releases (ohne
Vorab-Releases, siehe [cve-scanning.md](cve-scanning.md)). Daraus folgt:

| Befund im jüngsten Release | Reaktion |
|---|---|
| `critical` mit verfügbarem Fix | Patch-Release, sobald `main` den Fix trägt; Frist wie in cve-scanning.md |
| `high` mit verfügbarem Fix | mit dem nächsten Release, spätestens als Patch-Release nach der wöchentlichen Triage |
| ohne Fix, `medium` und `low` | kein eigenes Release |

Ein solches Patch-Release kann ohne Codeänderung entstehen: Der neue Bau zieht die aktuellen
Pakete des Basis-Images.

## Mehrere Architekturen

Die Images werden nur für `linux/amd64` gebaut. Ein zusätzlicher Bau für `linux/arm64` ist
geprüft und **vorerst zurückgestellt**:

- **Kosten:** Für ein öffentliches Repository stehen native arm64-Runner zur Verfügung, ein
  emulierter Bau ist also nicht nötig. Der Aufwand liegt im Workflow, der je Architektur baut und
  die Ergebnisse zu einem Manifest zusammenführt. Dazu kommen die doppelten Laufzeiten bei jedem
  Push auf `main`.
- **Basis-Images:** Alle verwendeten Basis-Images gibt es auch für arm64: Temurin, Distroless,
  `nginx-unprivileged`, `pgvector/pgvector`.
- **Bedarf:** Es gibt keine Zielumgebung, die arm64 verlangt. E2E-Suite und Scans laufen nur
  auf amd64. Ein ungeprüftes arm64-Image wäre eine Zusage, die niemand einlöst.

Wieder aufgegriffen wird das, sobald eine Installation arm64 braucht. Dann gehören E2E und Scan für
arm64 dazu, nicht nur der Bau.
