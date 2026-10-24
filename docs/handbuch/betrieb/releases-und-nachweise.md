# Releases und Nachweise

Wer OPAA betreibt oder beschafft, muss wissen, welche Version er einsetzt, wie lange sie
Sicherheitsupdates bekommt und wie sich belegen lässt, woraus sie besteht und woher sie stammt.
Dieses Kapitel fasst zusammen, was ein Release liefert, wie man es prüft und wie das Projekt mit
Schwachstellen und Lizenzen umgeht. Es gehört zum Teil Betrieb des [Handbuchs](../README.md).

## Versionen

Es gibt zwei Arten von Ständen: **Releases** mit fester Versionsnummer `X.Y.Z`, die einmal gebaut
und nie überschrieben werden, und den beweglichen Entwicklungsstand **`main`**. **Im Echtbetrieb
läuft immer ein Release, nie `main`**: `main` ändert sich mit jedem Merge und hat kein Update-Paket; er
dient nur zum Erproben. Welche Image-Tags es gibt und wie man einen Stand
pinnt, steht unter [Images und Versionen](images-und-laufzeit.md#images-und-versionen).

Gezählt wird nach Semantic Versioning. Solange die Hauptversion `0` ist, steigt `Y` bei neuen
Funktionen und bei jedem **Bruch**, `Z` bei Fehlerbehebungen und Sicherheitsupdates ohne neue
Funktion. Ab `1.0.0` steigt bei einem Bruch die Hauptversion. Als Bruch gilt, was eine bestehende
Installation zu Handarbeit zwingt: eine neue Pflichtangabe in der Konfiguration, ein
Vorbereitungsschritt wie geänderte Ports oder Eigentumsrechte, eine inkompatible Änderung der
Schnittstelle oder eine Neuindizierung. Ein Patch-Release verlangt deshalb nie Vorbereitung.

**Vorab-Releases** tragen einen Zusatz wie `1.2.3-rc.1` (auch `alpha`, `beta`). Sie sind zum
Erproben gedacht, bekommen kein wanderndes Tag `X.Y` und keine Sicherheitsupdates.

Schemaänderungen der Datenbank laufen nur vorwärts; zurück auf eine ältere Version geht es über die
Sicherung ([Aktualisierung](aktualisierung.md)).

### Linien und Supportzeiträume

OPAA erscheint zweimal im Jahr mit einem Funktionsrelease `X.Y.0`, etwa im Juni und im Dezember,
jeweils 4–6 Wochen nach einer neuen Version von Spring Boot, der Basis des Backends. Jedes finale
`X.Y.0` eröffnet die **Linie** `X.Y`. Ihre Patch-Releases bringen nur Sicherheitskorrekturen und
Behebungen kritischer Fehler, keine neue Funktion und keinen Bruch; eine Behörde kann sie deshalb
sofort einspielen. Kritisch ist ein Fehler ohne zumutbaren Workaround, der Daten verliert oder
verfälscht, OPAA oder eine Kernfunktion ausfallen lässt oder Rechte falsch auswertet.

| Linie | Patch-Releases bis |
|---|---|
| normale Linie (Dezember-Release) | einen Monat nach dem nächsten Funktionsrelease |
| LTS-Linie (Juni-Release) | Ende des kostenlosen Supports ihrer Spring-Boot-Version, im Regelfall 30.06. des Folgejahres, also rund 12 Monate |

Das Supportende steht in den Release-Notizen und wird nie vorgezogen; bei einer LTS-Linie steht es
mit Erscheinen fest, bei einer normalen mit Erscheinen ihres Nachfolgers. Wer nur LTS betreibt,
aktualisiert einmal im Jahr. Das nächste LTS erscheint spätestens zwei Wochen nach Spring Boot als
Release-Kandidat (`X.Y.0-rc.N`) mit Update-Paket, danach kommen nur Korrekturen hinzu. Auf dieser
Grundlage kann die Behörde die Beteiligung beginnen; das finale LTS erscheint spätestens zum
Supportende der laufenden LTS-Linie. Die Regeln gelten ab dem ersten finalen Release, einer
normalen Linie. Die Zusage steht auch in `SECURITY.md` im Repository.

## Sicherheitsupdates

Sicherheitskorrekturen bekommen **`main`** und jede unterstützte
[Linie](#linien-und-supportzeiträume) bis zu ihrem Supportende, Vorab-Releases nicht. Ein
Release-Image wird nie neu gebaut und trägt deshalb den Sicherheitsstand seines Bautags. Behoben wird
über ein **Patch-Release** der Linie:

| Befund in einer unterstützten Linie | Reaktion |
|---|---|
| kritisch, mit verfügbarer Korrektur | Patch-Release ohne Aufschub |
| hoch, mit verfügbarer Korrektur | spätestens als Patch-Release nach der wöchentlichen Sichtung |
| mittel oder niedrig, mit verfügbarer Korrektur | mit dem nächsten Patch-Release der Linie |
| ohne verfügbare Korrektur | kein Release; der Befund wird gesichtet |

Liegt die Korrektur im Basis-Image, entsteht das Patch-Release aus demselben Code neu gebaut. Wer
das Tag `X.Y` verwendet, erhält ein Patch-Release mit dem nächsten Pull; wer `X.Y.Z` pinnt, wechselt
bewusst.

**Wartungsbranches.** Jede Linie wird auf einem eigenen Wartungsbranch gepflegt, die jüngste
ebenso wie ältere. Eine Korrektur im Code kommt zuerst in den laufenden Entwicklungsstand `main`
und von dort auf jede unterstützte Linie, die sie betrifft. Jedes Patch-Release entsteht auf dem
Wartungsbranch seiner Linie; ein Patch-Release aus `main` lehnt der Release-Lauf ab. So enthält
ein Patch-Release nie Funktionen einer neueren Linie. Ältere Linien gibt es ab dem zweiten finalen
Release. Der nächtliche Scan prüft das jüngste Patch-Release jeder unterstützten Linie
([Schwachstellen](#schwachstellen)).

## Was ein Release liefert

Zu jeder Version gehören:

| Ort | Inhalt |
|---|---|
| GHCR | die Images `ghcr.io/criew/opaa-backend:X.Y.Z` und `ghcr.io/criew/opaa-frontend:X.Y.Z` für `linux/amd64` und `linux/arm64`, je mit SBOM und Provenance |
| GHCR | das Helm-Chart `oci://ghcr.io/criew/charts/opaa` in Version `X.Y.Z`, mit signierter Provenance |
| GitHub-Release `OPAA vX.Y.Z` | die Release-Notizen und das Update-Paket mit Prüfsummendatei |
| GitHub-Release `OPAA vX.Y.Z` | das Offline-Bündel für Netze ohne Internetzugang, attestiert ([Installation ohne Internetzugang](images-und-laufzeit.md#installation-ohne-internetzugang)) |

Die **Release-Notizen** enthalten:

- **Datenrelevante Änderungen** ganz oben ([Nachvollziehbarkeit](nachvollziehbarkeit.md#was-bei-jeder-änderung-geprüft-wird))
- alle übrigen Änderungen nach Art
- Image-Namen und Installationsbefehle
- **Vorbereitung für Bestandsinstallationen**: was vor dem Update zu tun ist; bei übersprungenen
  Versionen für jedes übersprungene Release lesen

Die Provenance des Charts prüft:

```bash
gh attestation verify oci://ghcr.io/criew/charts/opaa:X.Y.Z --owner criew
```

## Das Update-Paket

Jedes Release, auch ein Vorab-Release, hat das Update-Paket `opaa-update-paket-X.Y.Z.tar.gz`.
Es richtet sich an alle, die ein Update verantworten, ohne den Code zu lesen, und enthält:

- die Faktenblätter der Version
- die Änderungen seit dem vorigen Release mit Stufenvorschlag
- die Folgen für den Betrieb
- den Prüfbericht der automatisierten Barrierefreiheitsprüfung der Version
- die Lizenzübersicht und die SBOMs je Ökosystem
- die Vorlage für den Update-Vermerk
- unter `vorlagen/` alle Behördenvorlagen, verlinkt mit den Faktenblättern
- unter `handbuch/` dieses Handbuch im Stand der Version

Neben jeder Markdown-Datei liegt dieselbe Seite als HTML; `index.html` öffnet das ganze Paket im
Browser, ohne Internetzugang.

Aufbau im Einzelnen, Prüfung der Provenance und Delta über mehrere Versionen:
[Das Update-Paket eines Release](nachvollziehbarkeit.md#das-update-paket-eines-release).

<a id="herkunft-und-software-stückliste"></a>

## Provenance und Software-Stückliste

Eine **Software-Stückliste** (SBOM) zählt auf, welche Bestandteile in einem Artefakt stecken. OPAA
liefert sie auf zwei Wegen:

- **Am Image.** Jedes veröffentlichte Image, Release wie `main`, trägt je Architektur eine SBOM und
  eine Provenance (Quell-Commit und Bauparameter). Die SBOM umfasst auch die
  Betriebssystempakete des Basis-Images. Abrufen lassen sich beide mit `docker buildx imagetools
  inspect`; die Befehle stehen unter [Images und Versionen](images-und-laufzeit.md#images-und-versionen).
- **Je Ökosystem.** Zu jedem Release entsteht je eine CycloneDX-SBOM der ausgelieferten
  Laufzeit-Abhängigkeiten von Backend und Frontend, ohne Test- und Build-Werkzeuge. Sie hängen als
  `opaa-backend-X.Y.Z.cdx.json` und `opaa-frontend-X.Y.Z.cdx.json` am GitHub-Release und liegen im
  Update-Paket unter `sbom/`, beide mit Provenance.

Eine Provenance mit Signatur, prüfbar mit `gh attestation verify`, liegt außerdem am Chart und am
Update-Paket.

## Schwachstellen

- **Erkennen:** GitHub meldet bekannte Schwachstellen in den Bibliotheken; ein nächtlicher Scan
  prüft die Images von `main` und des jüngsten Patch-Release jeder unterstützten Linie, bis zu
  deren Supportende.
- **Behandeln:** Jeder Befund wird behoben oder mit Begründung zurückgestellt; wie schnell ein
  Release folgt, steht unter [Sicherheitsupdates](#sicherheitsupdates).
- **Melden:** Lücken in OPAA vertraulich über das Private Vulnerability Reporting von GitHub
  (Reiter „Security“ im Repository), nicht über öffentliche Issues. Behobene Lücken erscheinen dort
  als Security Advisory. Einzelheiten regelt `SECURITY.md` im Repository.

## Lizenzen

- **OPAA** steht unter der GNU Affero General Public License v3.0 (Datei `LICENSE` im Repository).
  Wer OPAA verändert und anderen über ein Netz bereitstellt, muss den Quellcode der veränderten
  Fassung anbieten.
- **Abhängigkeiten:** Die CI lässt nur Lizenzen zu, die mit der GPLv3 verträglich sind, sonst eine
  begründete Ausnahme. Die Lizenzübersicht liegt im Update-Paket unter `lizenzen/`.
- **Beiträge:** Wer Änderungen ins Projekt einbringen will, unterzeichnet einmalig das Contributor
  License Agreement (CLA). Ohne CLA pflegt die Behörde eine eigene Abweichung bei jedem Update
  selbst.

## Was nicht gebaut ist

- Eine Ablage auf openCode, der Plattform der öffentlichen Verwaltung für quelloffene Software. Das
  Repository bringt Spiegel und Metadaten für das Softwareverzeichnis mit; eingerichtet ist die
  Ablage nicht, weil openCode Projekte einem Träger der öffentlichen Verwaltung zuordnet
  ([#3041](https://github.com/criew/opaa/issues/3041)). Bezugsquelle für Code, Images und
  Update-Paket bleibt GitHub.

## Weiterführende Kapitel

- [Images und Laufzeit](images-und-laufzeit.md) — Images und Versionen
- [Aktualisierung](aktualisierung.md) — Ablauf eines Updates
- [Nachvollziehbarkeit](nachvollziehbarkeit.md) — Faktenblätter und Update-Paket im Einzelnen
