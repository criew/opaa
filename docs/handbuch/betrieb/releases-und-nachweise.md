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

## Sicherheitsupdates

Sicherheitskorrekturen bekommen nur das **jüngste Release** (ohne Vorab-Releases) und **`main`**.
Eine ältere Release-Linie wird nicht nachgepflegt; sobald ein neues Release erscheint, endet die
Pflege des vorigen. Feste Supportzeiträume oder parallel gepflegte Linien sagt das Projekt nicht zu;
ob es solche Linien geben wird, ist offen ([#2575](https://github.com/criew/opaa/issues/2575)).

Ein Release-Image wird nie neu gebaut und trägt deshalb den Sicherheitsstand seines Bautags. Behoben
wird über ein **Patch-Release**:

| Befund im jüngsten Release | Reaktion |
|---|---|
| kritisch, mit verfügbarer Korrektur | Patch-Release ohne Aufschub |
| hoch, mit verfügbarer Korrektur | mit dem nächsten Release, spätestens als Patch-Release nach der wöchentlichen Sichtung |
| ohne Korrektur, mittel oder niedrig | kein eigenes Release |

Liegt die Korrektur im Basis-Image, entsteht das Patch-Release aus demselben Code neu gebaut. Liegt
sie in einer Abhängigkeit des Codes und trägt `main` schon neue Funktionen, erscheint sie mit der
nächsten Version `0.(Y+1).0`. Wer das Tag `X.Y` verwendet, erhält ein Patch-Release mit dem nächsten
Pull; wer `X.Y.Z` pinnt, wechselt bewusst.

## Was ein Release liefert

Zu jeder Version gehören:

| Ort | Inhalt |
|---|---|
| GHCR | die Images `ghcr.io/criew/opaa-backend:X.Y.Z` und `ghcr.io/criew/opaa-frontend:X.Y.Z` für `linux/amd64` und `linux/arm64`, je mit SBOM und Provenance |
| GHCR | das Helm-Chart `oci://ghcr.io/criew/charts/opaa` in Version `X.Y.Z`, mit signierter Provenance |
| GitHub-Release `OPAA vX.Y.Z` | die Release-Notizen und das Update-Paket mit Prüfsummendatei |

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
- die Lizenzübersicht
- die Vorlage für den Update-Vermerk
- unter `vorlagen/` alle Behördenvorlagen, verlinkt mit den Faktenblättern

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
- **Je Ökosystem.** Zu jedem Stand von `main` erzeugt die CI je eine CycloneDX-SBOM der
  ausgelieferten Laufzeit-Abhängigkeiten von Backend und Frontend, ohne Test- und Build-Werkzeuge.
  Sie liegen 90 Tage als Artefakt am Lauf, nicht am GitHub-Release.

Für eine Release-Version ist deshalb die SBOM am Image der Nachweis. Eine Provenance mit
Signatur, prüfbar mit `gh attestation verify`, liegt am Chart und am Update-Paket.

## Schwachstellen

- **Erkennen:** GitHub meldet bekannte Schwachstellen in den Bibliotheken; ein nächtlicher Scan
  prüft die Images von `main` und des jüngsten Release.
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

- Parallel gepflegte Release-Linien mit festen Supportzeiträumen
  ([#2575](https://github.com/criew/opaa/issues/2575)).
- Ein Offline-Bündel mit Images, Chart, SBOMs und Update-Paket für Netze ohne Internetzugang
  ([#2557](https://github.com/criew/opaa/issues/2557)).

## Weiterführende Kapitel

- [Images und Laufzeit](images-und-laufzeit.md) — Images und Versionen
- [Aktualisierung](aktualisierung.md) — Ablauf eines Updates
- [Nachvollziehbarkeit](nachvollziehbarkeit.md) — Faktenblätter und Update-Paket im Einzelnen
