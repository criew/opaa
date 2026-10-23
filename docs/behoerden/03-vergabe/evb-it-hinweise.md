---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# EVB-IT

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** öffentliche Auftraggeber, also Behörden und auch kommunale Gesellschaften, die
ausschreiben müssen und die EVB-IT verwenden.

Die EVB-IT (Ergänzende Vertragsbedingungen für die Beschaffung von IT-Leistungen) sind die
Vertragsmuster der öffentlichen Hand für IT-Leistungen. Seit März 2026 gilt eine neue Fassung, die
Open Source als Regelfall behandelt und Regeln zu Software-Stücklisten (SBOM, eine Liste aller
enthaltenen Softwarebestandteile) und zu Programmständen enthält. Die Behörde verwendet die amtlichen
Muster in der aktuellen Fassung und füllt sie selbst aus.

> **Beispiel.** Ein Landratsamt vergibt Einführung und Schulung als Dienstleistung, die Pflege als
> Pflege S. Weil das Projekt nur das jüngste Release mit Korrekturen versorgt, verpflichtet es den
> Dienstleister, neue Versionen in festen Fristen einzuspielen.

## Was OPAA liefert

OPAA steht unter der GNU Affero General Public License v3.0 ohne Lizenzentgelt; eine Überlassung der
Software ist deshalb meist unnötig. Zu jeder Version gibt es unveränderliche Images, SBOMs und eine
Lizenzübersicht ([Tabelle unten](#programmstände-sbom-und-supportzeiträume)).

## Aufgaben der Behörde

1. **Leistungen zuordnen** nach der Tabelle unten und die amtlichen Muster verwenden.
2. **Support und Übergabe** im passenden Vertrag regeln, meist Service oder Pflege S (zu prüfen).
3. **Quellcode-Angebot sichern:** Wer eine angepasste Fassung über ein Netz anbietet, muss den
   Quellcode anbieten. Ein Dienstleister sichert das zu; im Eigenbetrieb plant die Behörde es ein.
4. **Supportzeitraum vereinbaren,** wenn ein Programmstand über das nächste Release hinaus halten
   soll.

## Zuordnung der Leistungen

| Leistung | Vertragstyp | Bezug im [Anforderungskatalog](anforderungskatalog.md) |
|---|---|---|
| Updates einspielen, Fehler beheben | EVB-IT Pflege S | L-03 |
| Betrieb durch einen Dienstleister in der Infrastruktur der Behörde oder des Dienstleisters | EVB-IT Service | L-02 |
| Bereitstellung als Dienst über das Internet (SaaS) | EVB-IT Cloud | L-02 als Variante |
| Einführung und Schulung | EVB-IT Dienstleistung | L-01, L-05 |
| Anpassungen und Erweiterungen | EVB-IT Erstellung | L-06 |
| Überlassung der Software | EVB-IT Überlassung Typ A oder B; bei kostenloser Lizenz meist unnötig | O-01 |

## Programmstände, SBOM und Supportzeiträume

| Thema | Was OPAA liefert | Fundstelle |
|---|---|---|
| Programmstand | Jede Version ist unveränderlich benannt (`X.Y.Z`); Images und Chart einer Version werden nie überschrieben | [Was ein Release erzeugt](../../releases.md#was-ein-release-erzeugt), [Versionsnummern](../../releases.md#versionsnummern) |
| SBOM | Je Image und Architektur eine SBOM-Attestierung, auch für Release-Images; zusätzlich je Ökosystem eine CycloneDX-SBOM als CI-Artefakt von `main` | [Image-Attestierungen](../../sbom.md#ebene-1--image-attestierungen), [Ökosystem-SBOMs](../../sbom.md#ebene-2--ökosystem-sboms-cyclonedx-ci-artefakte) |
| Lizenzen der Abhängigkeiten | Lizenzübersicht im Update-Paket jeder Version | [Lizenzprüfung](../../sbom.md#lizenzprüfung), [Update-Paket](../../releases.md#update-paket) |
| Sicherheitskorrekturen | nur für das jüngste Release und `main`; ältere Linien werden nicht nachgepflegt | [Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen) |
| Änderungen zwischen Programmständen | Delta der Datenwirkung zum vorigen Release | [Update-Paket](../../releases.md#update-paket), [Update-Vermerk](../09-betrieb-update/update-vermerk.md) |

## Gut zu wissen

- Ob es Supportlinien mit festen Zeiträumen geben wird, hat das Projekt noch nicht entschieden. Bis
  dahin kann eine längere Zusage nur ein Dienstleister geben.
- Das Quellcode-Angebot betrifft Erstellung und Cloud, und auch die Behörde selbst, wenn sie eine
  angepasste Fassung für ihre Beschäftigten betreibt.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Übersicht der Anpassungen der EVB-IT](https://www.it-planungsrat.de/fileadmin/beschluesse/2025/Beschluss_2025_48_EVB-IT_%C3%9Cbersicht_Anpassungen.pdf)
  des IT-Planungsrats (Beschluss 2025/48): was sich mit der Fassung 2026 geändert hat.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Neue Fassung seit März 2026; Open Source als Regelfall; SBOM und Programmstände in Pflege S; Zuordnung der Vertragstypen | [IT-Planungsrat, Beschluss 2025/48](https://www.it-planungsrat.de/fileadmin/beschluesse/2025/Beschluss_2025_48_EVB-IT_%C3%9Cbersicht_Anpassungen.pdf) |
| Amtliche Vertragsmuster in der aktuellen Fassung | Veröffentlichungsstelle der EVB-IT, zu prüfen |
| Quellcode-Angebot bei Nutzung über ein Netz | [GNU AGPL v3.0](../../../LICENSE), Abschnitt 13 |
