---
stand: 2026-10-17
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-01-15
---

# Hinweise zu den EVB-IT (Fassung 2026)

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Die Ergänzenden Vertragsbedingungen für die Beschaffung von IT-Leistungen (EVB-IT) liegen seit März
2026 in einer neuen Fassung vor (Beschluss 2025/48 des IT-Planungsrats). Sie behandeln Open Source
als Regelfall und enthalten neue Regeln zu Software-Stücklisten (SBOM) und zu Programmständen im
Vertragstyp Pflege S. Diese Seite ordnet die Leistungen rund um OPAA den Vertragstypen zu und nennt,
was OPAA zu SBOM und Programmständen beiträgt. Sie baut keine Vertragsmuster nach: Die Behörde
verwendet die amtlichen Muster in der aktuellen Fassung und füllt sie selbst aus.

## Zuordnung der Leistungen

| Leistung | Vertragstyp | Bezug im [Anforderungskatalog](anforderungskatalog.md) |
|---|---|---|
| Updates einspielen, Fehler beheben | EVB-IT Pflege S | L-03 |
| Betrieb durch einen Dienstleister in der Infrastruktur der Behörde oder des Dienstleisters | EVB-IT Service | L-02 |
| Bereitstellung als Dienst über das Internet (SaaS) | EVB-IT Cloud | L-02 als Variante |
| Einführung und Schulung | EVB-IT Dienstleistung | L-01, L-05 |
| Anpassungen und Erweiterungen | EVB-IT Erstellung | L-06 |
| Überlassung der Software | EVB-IT Überlassung Typ A oder B; bei kostenloser Lizenz meist unnötig | O-01 |

Für Support (L-04) und die Übergabe am Vertragsende (L-07) nennt die Recherche keine eigene
Zuordnung; die Behörde regelt sie in dem Vertrag, zu dem sie gehören, meist Service oder Pflege S
(zu prüfen).

**Lizenz bei Anpassungen und SaaS:** OPAA steht unter der GNU Affero General Public License v3.0.
Wer OPAA verändert und Nutzern über ein Netz zugänglich macht, muss ihnen den Quellcode der
veränderten Fassung anbieten (Abschnitt 13 der [Lizenz](../../../LICENSE)). Das betrifft
Erstellung und Cloud; die Behörde lässt sich die Einhaltung zusichern.

## Programmstände, SBOM und Supportzeiträume

| Thema | Was OPAA liefert | Fundstelle |
|---|---|---|
| Programmstand | Jede Version ist unveränderlich benannt (`X.Y.Z`); Images und Chart einer Version werden nie überschrieben | [Was ein Release erzeugt](../../releases.md#was-ein-release-erzeugt), [Versionsnummern](../../releases.md#versionsnummern) |
| SBOM | Je Image und Architektur eine SBOM-Attestierung, auch für Release-Images; zusätzlich je Ökosystem eine CycloneDX-SBOM als CI-Artefakt von `main` | [Image-Attestierungen](../../sbom.md#ebene-1--image-attestierungen), [Ökosystem-SBOMs](../../sbom.md#ebene-2--ökosystem-sboms-cyclonedx-ci-artefakte) |
| Lizenzen der Abhängigkeiten | Lizenzübersicht im Update-Paket jeder Version | [Lizenzprüfung](../../sbom.md#lizenzprüfung), [Update-Paket](../../releases.md#update-paket) |
| Sicherheitskorrekturen | nur für das jüngste Release und `main`; ältere Linien werden nicht nachgepflegt | [Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen) |
| Änderungen zwischen Programmständen | Delta der Datenwirkung zum vorigen Release | [Update-Paket](../../releases.md#update-paket), [Update-Vermerk](../09-betrieb-update/update-vermerk.md) |

**Offene Entscheidung: Supportzeiträume.** Ob es Supportlinien mit festen Zeiträumen geben wird,
ist im Projekt noch nicht entschieden ([#2575](https://github.com/criew/opaa/issues/2575)). Bis
dahin gilt: Nur das jüngste Release erhält Korrekturen. Ein Pflegevertrag, der einen Programmstand
länger als bis zum nächsten Release halten soll, braucht dafür eine Zusage des Dienstleisters; das
Projekt gibt sie nicht. Diese Seite wird nach der Entscheidung durchgesehen.

## Was diese Seite nicht enthält

- ausgefüllte EVB-IT-Verträge oder eigene Vertragsmuster,
- die Wertung konkreter Angebote,
- ein Verzeichnis von Dienstleistern.

## Quellen und Prüfstand

| Aussage | Fundstelle | Herkunft |
|---|---|---|
| Neue Fassung der EVB-IT seit März 2026; Open Source als Regelfall; Regeln zu SBOM und Programmständen in Pflege S; Zuordnung der Vertragstypen; Überlassung bei kostenloser Lizenz meist unnötig | [IT-Planungsrat, Beschluss 2025/48, Übersicht der Anpassungen](https://www.it-planungsrat.de/fileadmin/beschluesse/2025/Beschluss_2025_48_EVB-IT_%C3%9Cbersicht_Anpassungen.pdf) | amtliche Veröffentlichung; Recherche vom 13.10.2026 |
| Amtliche Vertragsmuster in der aktuellen Fassung | Veröffentlichungsstelle der EVB-IT; Fundstelle zu prüfen | nicht Teil der Recherche |
| Quellcode-Angebot bei Nutzung über ein Netz | [GNU AGPL v3.0, Abschnitt 13](../../../LICENSE) | Lizenztext |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542).
