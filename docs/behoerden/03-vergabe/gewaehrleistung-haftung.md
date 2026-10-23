---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Gewährleistung und Haftung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** öffentliche Auftraggeber, also Behörden und auch kommunale Gesellschaften, die
ausschreiben müssen.

Gewährleistung heißt, dass jemand für Mängel der Leistung einsteht; Haftung, dass er für entstandene
Schäden aufkommt. Eine Behörde, die OPAA einsetzt, hat keinen Vertrag mit dem Projekt. Gewährleistung,
Haftung und feste Reaktionszeiten erhält sie nur von einem Dienstleister, mit dem sie einen Vertrag
schließt.

> **Beispiel.** Ein Landratsamt meldet einen Fehler, der im Code des Projekts liegt. Der
> Pflegevertrag verpflichtet den Dienstleister, ihn in der vereinbarten Frist zu beheben. Er tut das
> und bietet die Korrektur dem Projekt an.

## Was OPAA liefert

| Thema | Projekt OPAA | Fundstelle |
|---|---|---|
| Gewährleistung | keine; die Software wird bereitgestellt „wie sie ist“ | [Lizenz](../../../LICENSE), Abschnitt 15 |
| Haftung | ausgeschlossen, soweit das anwendbare Recht es zulässt | [Lizenz](../../../LICENSE), Abschnitte 16 und 17 |
| Reaktionszeiten auf gemeldete Sicherheitslücken | keine festen Zusagen, Bearbeitung nach bestem Bemühen | [Sicherheitsrichtlinie](../../../SECURITY.md#ablauf) |
| Sicherheitskorrekturen | für das jüngste Release und `main` | [Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen) |
| Reaktion auf Befunde des eigenen Schwachstellenscans | Fristen nach Schwere | [Sicherheitsupdates für Releases](../../releases.md#sicherheitsupdates-für-releases) |
| Pflichten nach dem Cyber Resilience Act | als Open-Source-Steward | [Rollenklärung zum CRA](../06-informationssicherheit/rollenklaerung-cra.md#die-rollen-im-überblick) |

Für die Planung zählt: Gegenüber dem Projekt kann die Behörde keine Ansprüche einplanen.

## Aufgaben der Behörde

Die Lizenz erlaubt ausdrücklich, Support und Gewährleistung gegen Entgelt anzubieten. Im Vertrag mit
dem Dienstleister legt die Behörde fest:

1. **Gewährleistung für dessen eigene Leistung** (L-01 bis L-03, L-06 im
   [Anforderungskatalog](anforderungskatalog.md)).
2. **Einstehen für die Software im vereinbarten Umfang,** etwa Fehlerbehebung in Fristen, auch wenn
   der Fehler im Code des Projekts liegt; Quellcode und eigene Korrekturen erlaubt die Lizenz.
3. **Haftung** nach dem gewählten [EVB-IT-Vertragstyp](evb-it-hinweise.md#zuordnung-der-leistungen).
4. **Pflichten als Hersteller nach dem Cyber Resilience Act,** wenn der Dienstleister OPAA kommerziell
   als Produkt bereitstellt (L-08).
5. **Rückgabe von Korrekturen** an das Projekt (L-06); Sicherheitslücken vorher vertraulich über den
   [Meldeweg](../../../SECURITY.md#meldeweg).

## Gut zu wissen

- Übernimmt das Projekt eine Korrektur, entfällt die Pflege einer eigenen Abweichung bei jedem
  Update. Das setzt die unterzeichnete
  [Contributor License Agreement](../../../CONTRIBUTING.md#contributor-license-agreement-cla) voraus.
- Wie weit der Ausschluss nach deutschem Recht wirkt, behandelt diese Vorlage nicht.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Rollenklärung zum CRA](../06-informationssicherheit/rollenklaerung-cra.md): wer nach dem Cyber
  Resilience Act welche Pflichten trägt.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Keine Gewährleistung; Haftungsausschluss; Support und Gewährleistung Dritter gegen Entgelt; schriftliche Zusagen Dritter | [GNU AGPL v3.0](../../../LICENSE), Abschnitte 4 und 15 bis 17 |
| Wirksamkeit eines Gewährleistungs- und Haftungsausschlusses nach deutschem Recht | zu prüfen |
| Pflichten eines kommerziellen Bereitstellers als Hersteller | [VO (EU) 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu) (Cyber Resilience Act) |
