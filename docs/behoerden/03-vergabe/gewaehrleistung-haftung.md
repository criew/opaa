---
stand: 2026-10-17
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-01-15
---

# Gewährleistung und Haftung bei quelloffener Software

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Eine Behörde, die OPAA einsetzt, hat keinen Vertrag mit dem Projekt. Gewährleistung, Haftung und
Zusagen zu Reaktionszeiten erhält sie nur von einem Dienstleister, mit dem sie einen Vertrag
schließt. Diese Seite beschreibt die Aufteilung und was die Behörde dafür ausschreibt.

## Was das Projekt zusagt und was nicht

| Thema | Projekt OPAA | Fundstelle |
|---|---|---|
| Gewährleistung | keine; die Software wird bereitgestellt „wie sie ist“ | [Lizenz](../../../LICENSE), Abschnitt 15 |
| Haftung | ausgeschlossen, soweit das anwendbare Recht es zulässt | [Lizenz](../../../LICENSE), Abschnitte 16 und 17 |
| Reaktionszeiten auf gemeldete Sicherheitslücken | keine festen Zusagen, Bearbeitung nach bestem Bemühen | [Sicherheitsrichtlinie](../../../SECURITY.md#ablauf) |
| Sicherheitskorrekturen | für das jüngste Release und `main` | [Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen) |
| Reaktion auf Befunde des eigenen Schwachstellenscans | Fristen nach Schwere | [Sicherheitsupdates für Releases](../../releases.md#sicherheitsupdates-für-releases) |
| Pflichten nach dem Cyber Resilience Act | als Open-Source-Steward | [Rollenklärung zum CRA](../06-informationssicherheit/rollenklaerung-cra.md#die-rollen-im-überblick) |

Wieweit der Ausschluss von Gewährleistung und Haftung nach deutschem Recht wirkt, ist nicht
Gegenstand dieser Vorlage (zu prüfen). Für die Planung zählt: Die Behörde kann gegenüber dem Projekt
keine Ansprüche einplanen.

## Was der Dienstleister übernimmt

Die Lizenz erlaubt ausdrücklich, Support und Gewährleistung gegen Entgelt anzubieten (Abschnitt 4),
und sieht schriftliche Zusagen Dritter vor (Abschnitte 15 und 17 der [Lizenz](../../../LICENSE)).
Die Behörde legt im Vertrag fest:

- **Gewährleistung für die eigene Leistung:** Einführung, Betrieb, Pflege und Anpassungen des
  Dienstleisters (L-01 bis L-03, L-06 im [Anforderungskatalog](anforderungskatalog.md)).
- **Einstehen für die Software im vereinbarten Umfang:** etwa Fehlerbehebung innerhalb von Fristen
  bei der eingesetzten Version, auch wenn der Fehler im Code des Projekts liegt. Dafür braucht der
  Dienstleister Zugang zum Quellcode und darf Korrekturen selbst erstellen; beides erlaubt die Lizenz.
- **Haftung** nach den Regeln des gewählten EVB-IT-Vertragstyps
  ([EVB-IT-Hinweise](evb-it-hinweise.md#zuordnung-der-leistungen)).
- **Pflichten als Hersteller nach dem Cyber Resilience Act**, wenn der Dienstleister OPAA kommerziell
  als Produkt bereitstellt (L-08).

## Wenn ein Fehler im Projekt liegt

Behebt der Dienstleister einen Fehler im Code des Projekts, bietet er die Korrektur dem Projekt an
(L-06). Übernimmt das Projekt sie, entfällt die Pflege einer eigenen Abweichung bei jedem Update. Eine
Übernahme setzt die unterzeichnete [Contributor License Agreement](../../../CONTRIBUTING.md#contributor-license-agreement-cla)
voraus. Sicherheitslücken meldet er vorher vertraulich über den
[Meldeweg](../../../SECURITY.md#meldeweg).

## Quellen und Prüfstand

| Aussage | Fundstelle | Herkunft |
|---|---|---|
| Keine Gewährleistung; Haftungsausschluss; Support und Gewährleistung Dritter gegen Entgelt möglich | [GNU AGPL v3.0, Abschnitte 4 und 15 bis 17](../../../LICENSE) | Lizenztext |
| Wirksamkeit eines Gewährleistungs- und Haftungsausschlusses nach deutschem Recht | Fundstelle zu prüfen | nicht Teil der Recherche |
| Pflichten eines kommerziellen Bereitstellers als Hersteller | [VO (EU) 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu) | Normtext; Recherche vom 13.10.2026 |
