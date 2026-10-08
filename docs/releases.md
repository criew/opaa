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
| GitHub-Release `OPAA vX.Y.Z` | Änderungsübersicht aus den gemergten PRs, gruppiert nach Label (`.github/release.yml`), dazu die Image-Namen |

Wie bei `main` tragen die Images eine SBOM- und eine Provenance-Attestierung
([sbom.md](sbom.md)).

Ein Tag `latest` gibt es bewusst nicht. Wer keine Version pinnt, folgt `main`.

Ein Vorab-Stand behält seinen Suffix vollständig im Image-Tag, damit am Tag erkennbar bleibt, ob es
ein Alpha-, Beta- oder Release-Kandidat ist. Er bekommt kein `X.Y` und wird als Vorab-Release
markiert. Nur das führende `v` des Git-Tags entfällt:

| Git-Tag | Image-Tags | GitHub-Release |
|---|---|---|
| `v1.2.3-alpha.1` | `1.2.3-alpha.1` | Vorab-Release |
| `v1.2.3-beta.2` | `1.2.3-beta.2` | Vorab-Release |
| `v1.2.3-rc.1` | `1.2.3-rc.1` | Vorab-Release |
| `v1.2.3` | `1.2.3`, `1.2` | Release |

Der Workflow bricht ab, wenn aus dem Git-Tag nicht genau dieses Image-Tag entsteht. Ein
abgeschnittener Suffix würde also nie veröffentlicht.

## Schutzregeln im Workflow

Bevor gebaut wird, bricht der Lauf ab, wenn:

- das Tag nicht die Form `vX.Y.Z` bzw. `vX.Y.Z-<vorab>` hat oder keine gültige
  [SemVer](https://semver.org/lang/de/)-Version ist, etwa `v1.0.0-rc.01` mit führender Null,
- der getaggte Commit nicht auf `main` liegt,
- `X.Y.Z` in GHCR bereits existiert. Das gilt auch für einen erneut gepushten Tag und einen
  manuellen Lauf auf dem Tag. Eine fehlerhafte Version wird nicht ersetzt, sondern durch die
  nächste Patch-Version abgelöst.

Der wöchentliche Neubau (#1450) läuft nur auf `main`. Er berührt Release-Tags nie.

## Wer ein Release anlegt

Releases legen Maintainer an, nie ein Agent ohne ausdrückliche Freigabe. Technisch kann heute
jeder mit Schreibrecht ein Tag `v*` pushen; die Prüfungen im Workflow schützen vor Versehen, nicht
vor Absicht. Ablauf:

1. Prüfen, dass `main` grün ist, einschließlich des letzten nächtlichen E2E-Laufs.
2. Version nach den Regeln unten bestimmen.
3. Tag setzen und pushen:

   ```bash
   git fetch origin
   git tag -a v0.1.0 origin/main -m "OPAA v0.1.0"
   git push origin v0.1.0
   ```

4. Den Lauf von „Publish Images“ abwarten. Danach steht das GitHub-Release unter *Releases*.
   Das erste Release trägt nur einen festen Text: Ohne Vorgänger umfassten generierte Notizen die
   gesamte Historie.
5. Die generierten Notizen bei Bedarf ergänzen, vor allem um **Vorbereitungsschritte** für
   Bestandsinstallationen, wenn das Release welche verlangt.

Die erste Version ist `v0.1.0`.

## Versionsnummern

OPAA zählt nach [Semantic Versioning](https://semver.org/lang/de/). Solange die Hauptversion `0`
ist, gilt:

| Teil | Erhöht bei |
|---|---|
| `Y` in `0.Y.Z` | neuen Funktionen **und** jedem Bruch |
| `Z` in `0.Y.Z` | Fehlerbehebungen und Sicherheitsupdates ohne neue Funktion, auch nur des Basis-Images |

Ab `1.0.0` steigt bei einem Bruch die Hauptversion.

**Als Bruch gilt**, was eine Bestandsinstallation zu Handarbeit zwingt:

- eine neue Pflicht-Umgebungsvariable oder ein geänderter Wert, ohne den der Start scheitert,
- ein Vorbereitungsschritt im Handbuch, z. B. Eigentumsrechte, Ports, Volumes,
- eine inkompatible Änderung an `/api/v1` oder am MCP-Endpunkt,
- eine Migration, die eine Neuindizierung oder einen Neuaufbau verlangt.

Schemaänderungen laufen immer vorwärts. Zurück auf eine ältere Version geht es nur über die
Datenbanksicherung, unabhängig davon, ob die Version als Bruch gezählt wird.

Releases entstehen nur von Commits auf `main`. Eine ältere Linie wird nicht nachgepflegt. Daraus
folgen zwei Arten von Patch-Release:

- **Ohne Codeänderung:** Ein neues Tag `vX.Y.(Z+1)` auf **demselben Commit** wie das letzte Release.
  Der neue Bau zieht die aktuellen Pakete der Basis-Images.
- **Mit Codeänderung:** Nur, solange `main` seit dem letzten Release keine neue Funktion trägt.
  Sonst erscheint die Korrektur mit der nächsten Version von `main`, also als `0.(Y+1).0`.

## Sicherheitsupdates für Releases

Weil Release-Images nie neu gebaut werden, tragen sie den Sicherheitsstand ihres Bautags. Der
nächtliche CVE-Scan prüft deshalb neben `main` auch die Images des jüngsten Releases (ohne
Vorab-Releases, siehe [cve-scanning.md](cve-scanning.md)). Daraus folgt:

| Befund im jüngsten Release | Reaktion |
|---|---|
| `critical` mit verfügbarem Fix | Patch-Release, Frist wie in cve-scanning.md. Liegt der Fix im Basis-Image, genügt ein neues Tag auf dem Commit des Releases; liegt er in einer Abhängigkeit, kommt er mit der nächsten Version von `main` |
| `high` mit verfügbarem Fix | mit dem nächsten Release, spätestens als Patch-Release nach der wöchentlichen Triage |
| ohne Fix, `medium` und `low` | kein eigenes Release |

Solange das jüngste Release einen `critical`-Befund trägt, bleibt das Alarm-Issue des Scans offen,
auch wenn `main` schon behoben ist. Es schließt erst mit dem Patch-Release.

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
