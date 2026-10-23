---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# NIS2 in der Bundesverwaltung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** nur Einrichtungen der Bundesverwaltung. Länder und Kommunen erfasst das Gesetz nicht;
für sie gilt Landesrecht, die Seite kann ihnen als Orientierung dienen.

NIS2 ist eine EU-Richtlinie zur Cybersicherheit; Deutschland hat sie mit einem Gesetz umgesetzt, das
seit dem 6.12.2025 gilt. Einrichtungen der Bundesverwaltung gelten danach als *besonders wichtige
Einrichtungen* mit Pflichten zu Risikomanagement, Lieferkette und Meldung von Vorfällen.
Ihre Mindestanforderungen ergeben sich aus dem IT-Grundschutz und den *Mindeststandards* des BSI,
verbindlichen Vorgaben zu einzelnen Themen wie Protokollierung. Ob und mit welchen Pflichten sie
erfasst ist, stellt jede Einrichtung selbst fest.

## Was OPAA liefert

| Vorgabe | Beitrag von OPAA |
|---|---|
| IT-Grundschutz | Bausteinzuordnung: [IT-Grundschutz](grundschutz-modellierung.md) |
| Mindeststandard Protokollierung und Detektion | Anwendungs- und Nachweisprotokoll, Metriken und Alarme ([Sicherheitsnachweise](sicherheitsnachweise.md), {{faktenblatt:protokollereignisse}}); eine Ausleitung an ein Sicherheitsmonitoring ist nicht gebaut |
| Mindeststandard externe Cloud-Dienste | ausgehende Ziele je Version ({{faktenblatt:datenfluesse}}); Modelle auch im Haus betreibbar; bei Cloud-Betrieb [C5](c5-cloud-betrieb.md) |
| Mindeststandard TLS | TLS am vorgelagerten Proxy, Zertifikatsprüfung bei Quellen ([Netzwerkzugang](../../handbuch/netz-und-sicherheit.md#netzwerkzugang), [Zielprüfung](../../handbuch/netz-und-sicherheit.md#zielprüfung-abrufe-des-servers)) |

Zur **Lieferkette**, also den Fragen an ein eingesetztes Produkt:

- OPAA ist quelloffen, ohne Vertrag und ohne Zusage zu Verfügbarkeit oder Reaktionszeit; Zusagen gibt
  nur ein Dienstleister ([Gewährleistung und Haftung](../03-vergabe/gewaehrleistung-haftung.md)).
- Bestandteile, Provenance der Images und der Umgang mit Schwachstellen stehen in den
  [Sicherheitsnachweisen](sicherheitsnachweise.md), Pflichten des Projekts in der
  [Rollenklärung zum Cyber Resilience Act](rollenklaerung-cra.md).
- Daten fließen nur zu den Zielen, die die Installation einträgt ({{faktenblatt:datenfluesse}}).
  Ohne Internet geht es mit Modellen im Haus, auf Wunsch über einen
  [Ausgangsproxy](../../handbuch/netz-und-sicherheit.md#ausgangsproxy).

## Aufgaben der Behörde

1. **Betroffenheit und Pflichten feststellen.**
2. **Vorgaben zuordnen:** IT-Grundschutz und die geltenden Mindeststandards, mit der Tabelle oben.
3. **Lieferkette bewerten:** OPAA und, falls vorhanden, Dienstleister und Modellanbieter.
4. **Erhebliche Sicherheitsvorfälle an das BSI melden.** Die Protokolle liefern Anhaltspunkte; die
   Bewertung trifft die Einrichtung.
5. **Updates zeitnah einspielen**, weil nur das jüngste Release Sicherheitskorrekturen erhält, und
   Notfallvorsorge betreiben ([Notfallkonzept](../09-betrieb-update/notfallkonzept.md)).

## Zum Nachlesen

**Arbeitshilfen:**

- [Mindeststandards des BSI](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html):
  welche Standards in welcher Fassung gelten.

**Rechtsgrundlagen und Quellen:**

| Worum es geht | Fundstelle |
|---|---|
| NIS2-Umsetzungs- und Cybersicherheitsstärkungsgesetz (NIS2UmsuCG), gilt seit 6.12.2025 | [BGBl. I 2025 Nr. 301](https://www.recht.bund.de/bgbl/1/2025/301/VO.html) |
| Bundesverwaltung als besonders wichtige Einrichtungen | [§ 29 Abs. 1 und 2 BSIG](https://www.gesetze-im-internet.de/bsig_2025/__29.html) |
| Mindestanforderungen aus IT-Grundschutz und Mindeststandards | [§ 44 Abs. 1 BSIG](https://www.gesetze-im-internet.de/bsig_2025/__44.html) |
| Behörden der Länder und Kommunen nicht erfasst | [§ 28 Abs. 8 BSIG](https://www.gesetze-im-internet.de/bsig_2025/__28.html) |
| Sicherheit der Lieferkette als Risikomanagementmaßnahme | [Richtlinie (EU) 2022/2555](https://eur-lex.europa.eu/eli/dir/2022/2555/oj/deu), Artikel zu prüfen |
