---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Projektstatus

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die einschätzen müssen, ob ein quelloffenes Projekt über die Laufzeit
ihres Vorhabens trägt.

Bei quelloffener Software gibt es keinen Hersteller, der vertraglich für Fortbestand und Support
einsteht. Die Behörde braucht deshalb ein Bild davon, wer das Projekt führt, wie es Versionen pflegt
und wie es mit Sicherheitslücken umgeht. Die Bewertung des Risikos liegt bei der Behörde.

## Was OPAA liefert

| Thema | Stand | Nachweis |
|---|---|---|
| Ablage | öffentliches Repository auf GitHub mit Quellcode, Handbuch, Architekturentscheidungen, Issues und Pull Requests | [criew/opaa](https://github.com/criew/opaa) |
| Wer das Projekt führt | die Maintainer mit Schreibrechten am Repository; nur sie übernehmen Änderungen und legen Releases an | [Mitwirken](../../../CONTRIBUTING.md#wie-ein-pr-nach-main-kommt) |
| Beiträge | offen für alle; Voraussetzung für eine Übernahme ist die unterzeichnete CLA | [Lizenzen](../../handbuch/betrieb/releases-und-nachweise.md#lizenzen) |
| Release-Zyklus | geplant sind zwei Funktionsreleases im Jahr, das Juni-Release als LTS; die Regeln gelten ab dem ersten finalen Release, bis dahin gibt es nur Vorab-Releases | [Linien und Supportzeiträume](../../handbuch/betrieb/releases-und-nachweise.md#linien-und-supportzeiträume) |
| Sicherheitsupdates | Patch-Releases für jede unterstützte Linie bis zu ihrem Supportende, ältere Linien über eigene Wartungsbranches; der Schwachstellen-Scan prüft jede Linie | [Sicherheitsupdates](../../handbuch/betrieb/releases-und-nachweise.md#sicherheitsupdates) |
| Meldestelle für Sicherheitslücken | vertraulich über das Private Vulnerability Reporting von GitHub, einziger Meldeweg; Bearbeitung nach bestem Bemühen | [Meldeweg](../../../SECURITY.md#meldeweg), [Ablauf](../../../SECURITY.md#ablauf) |
| Rolle nach dem Cyber Resilience Act | ordnet sich nach bestem Wissen als Open-Source-Steward ein, obwohl der CRA dafür eine juristische Person voraussetzt und es keine gibt; nicht rechtlich geprüft; `SECURITY.md` ist die Cybersicherheitsstrategie | [Rollenklärung](../06-informationssicherheit/rollenklaerung-cra.md) |
| Support | keine Leistungen und keine Reaktionszeiten des Projekts; Fragen über GitHub | [Kein Angebot eingegangen](../03-vergabe/ohne-angebot.md#was-opaa-liefert) |
| Fortbestand | keine Zusage; Code, Handbuch und Unterlagen bleiben unter der AGPL-3.0 für jeden weiterführbar | [Lizenz](lizenz.md) |

## Aufgaben der Behörde

1. **Aktivität ansehen:** Releases, Änderungen der letzten Monate, Umgang mit Issues und
   Sicherheitsmeldungen ([Security Advisories](https://github.com/criew/opaa/security/advisories)).
2. **Supportzeitraum abgleichen** mit dem eigenen Update-Takt. Ein jährlicher Takt mit LTS-Linien
   setzt voraus, dass ältere Linien weiter Patches bekommen; das geschieht über eigene
   Wartungsbranches. Das Update-Paket eines LTS-Release vergleicht auch mit der vorigen LTS-Linie
   ([Das Update-Paket eines Release](../../handbuch/betrieb/nachvollziehbarkeit.md#das-update-paket-eines-release)).
3. **Zusagen vertraglich holen,** die das Projekt nicht gibt: Reaktionszeiten, längere Pflege einer
   Version, Weiterführung bei Ende des Projekts. Das leistet ein Dienstleister oder der eigene
   Betrieb.
4. **Ausstiegsweg festhalten:** wer die Installation weiterbetreibt, wenn das Projekt endet.

## Gut zu wissen

- Vorab-Releases dienen dem Erproben und bekommen keine Sicherheitsupdates. Ob eine Linie schon
  final erschienen ist, zeigt die [Release-Seite](https://github.com/criew/opaa/releases).
- Das Projekt liegt auf GitHub; eine Ablage des Projekts auf openCode, der Plattform der
  öffentlichen Verwaltung für quelloffenen Code, ist geplant. Bis dahin kann die Behörde den Code
  unter der AGPL-3.0 selbst dort spiegeln.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [`SECURITY.md`](../../../SECURITY.md): Meldeweg, unterstützte Versionen und Ablauf bis zur
  Veröffentlichung einer Lücke.
- [Releases und Nachweise](../../handbuch/betrieb/releases-und-nachweise.md): Versionen, Supportzeiträume,
  Schwachstellen und Lizenz.
- [openCode](https://opencode.de/de): Plattform von Bund, Ländern und Kommunen für quelloffenen Code.

**Rechtsgrundlagen** für die Informationssicherheit:

| Worum es geht | Fundstelle |
|---|---|
| Pflichten eines Open-Source-Stewards | Art. 24 [Cyber Resilience Act](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu) |
