---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# NIS2 in der Bundesverwaltung: Hinweise zu OPAA

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Diese Seite richtet sich an Einrichtungen der Bundesverwaltung, die OPAA einsetzen wollen. Sie ordnet
OPAA den Vorgaben zu, die aus der NIS2-Umsetzung folgen, und beantwortet die Lieferkettenfragen, die
eine Einrichtung an ein eingesetztes Produkt stellt. Ob und mit welchen Pflichten eine Einrichtung
erfasst ist, stellt sie selbst fest; diese Seite nimmt das nicht vorweg.

## Quellen und Prüfstand

Grundlage ist die Rechtsrecherche vom 13.10.2026 (erster Kommentar in Epic
[#2542](https://github.com/criew/opaa/issues/2542)):

| Aussage | Fundstelle | Art |
|---|---|---|
| Das NIS2-Umsetzungs- und Cybersicherheitsstärkungsgesetz (NIS2UmsuCG) gilt seit 6.12.2025 | [BGBl. I 2025 Nr. 301](https://www.recht.bund.de/bgbl/1/2025/301/VO.html) | Normtext |
| Einrichtungen der Bundesverwaltung gelten als besonders wichtige Einrichtungen | [Heuking](https://www.heuking.de/de/news-events/newsletter-fachbeitraege/artikel/ab-jetzt-gilts-nis2-umsetzungsgesetz-in-kraft.html) | Fachbeitrag |
| Bundesministerien wenden den IT-Grundschutz an, die übrige Bundesverwaltung die Mindeststandards des BSI | [Heuking](https://www.heuking.de/de/news-events/newsletter-fachbeitraege/artikel/ab-jetzt-gilts-nis2-umsetzungsgesetz-in-kraft.html) | Fachbeitrag |
| Länder und Kommunen erfasst das Gesetz nicht; für sie gilt Landesrecht | [Heuking](https://www.heuking.de/de/news-events/newsletter-fachbeitraege/artikel/ab-jetzt-gilts-nis2-umsetzungsgesetz-in-kraft.html) | Fachbeitrag |

Die Aussagen aus dem Fachbeitrag sind vor der Übernahme gegen das BSI-Gesetz in der Fassung des
NIS2UmsuCG zu prüfen; diese Vorlage nennt deshalb keine Paragrafen.

Eine Landes- oder Kommunalbehörde kann diese Seite als Orientierung nutzen, prüft aber die Vorgaben
ihres Landes.

## Zuordnung zu IT-Grundschutz und Mindeststandards

| Vorgabe | Wann sie OPAA betrifft | Beitrag von OPAA | Unterlage |
|---|---|---|---|
| IT-Grundschutz (BSI-Standards 200-1 bis 200-4, Kompendium) | Bundesministerien; alle, die den Grundschutz freiwillig anwenden | Bausteinzuordnung mit Produkt- und Betreiberanteil | [Grundschutz-Modellierung](grundschutz-modellierung.md) |
| [Mindeststandard zur Protokollierung und Detektion von Cyberangriffen](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html) | jede Installation | Anwendungsprotokoll auf der Standardausgabe, optional strukturiert; Nachweisprotokoll; Metriken und Alarme. Eine eigene Ausleitung an ein Sicherheitsmonitoring ist nicht gebaut | [Protokollierung](sicherheitsnachweise.md#protokollierung), {{faktenblatt:protokollereignisse}} |
| [Mindeststandard zur Nutzung externer Cloud-Dienste](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html) | wenn ein Modelldienst außerhalb der Einrichtung liegt oder ein Dienstleister OPAA als Cloud-Dienst betreibt | Ziele ausgehender Verbindungen sind je Version beschrieben; jeder Modell-Endpunkt ist frei wählbar, auch im Haus | {{faktenblatt:datenfluesse}}, [C5:2026](c5-2026-cloud-anbieter.md) |
| [Mindeststandard zur Verwendung von Transport Layer Security](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html) | jede Installation | TLS wird am vorgelagerten Proxy oder Ingress abgeschlossen; das Zertifikat jeder Quelle wird geprüft | [Netzwerkzugang](../../handbuch/betrieb.md#netzwerkzugang), [Zielprüfung](../../handbuch/betrieb.md#zielprüfung-abrufe-des-servers) |

Welche Mindeststandards in welcher Fassung gelten, steht auf der Übersichtsseite des BSI; die Behörde
prüft dort den aktuellen Stand.

## Lieferkette: Fragen an das Produkt

Die NIS2-Richtlinie zählt die Sicherheit der Lieferkette zu den Risikomanagementmaßnahmen. Für
OPAA als quelloffenes Produkt ohne Vertragspartner beantwortet das Projekt die üblichen Fragen so:

| Frage | Antwort |
|---|---|
| Wer liefert, und gibt es einen Vertrag? | Das Projekt stellt OPAA quelloffen bereit, ohne Vertrag und ohne Zusage zu Verfügbarkeit oder Reaktionszeit. Wer Zusagen braucht, schließt sie mit einem Dienstleister; Vorlagen zur Vergabe entstehen in [#2564](https://github.com/criew/opaa/issues/2564) |
| Woraus besteht das Produkt? | Software-Stückliste je Image und je Ökosystem ([SBOM](sicherheitsnachweise.md#software-stückliste-sbom)) |
| Ist das Ausgelieferte das Geprüfte? | Provenance-Attestierungen der Images und eine signierte Attestierung des Helm-Charts; Release-Images werden nie überschrieben ([Release-Verfahren](../../releases.md#was-ein-release-erzeugt)) |
| Wie werden Schwachstellen erkannt und behoben? | [Schwachstellen und Sicherheitsupdates](sicherheitsnachweise.md#schwachstellen-und-sicherheitsupdates) |
| Wie erfährt die Einrichtung von einer Lücke? | über die [Security Advisories](https://github.com/criew/opaa/security/advisories) des Projekts; ein Abonnement des Repositorys auf Sicherheitsmeldungen ist Sache der Einrichtung |
| Wohin fließen Daten, und an welche Dritten? | nur zu den Zielen, die die Installation einträgt; die Liste je Version steht in {{faktenblatt:datenfluesse}} |
| Kann OPAA ohne Internet betrieben werden? | Ja, mit Modelldiensten im Haus; der Weg ins Netz geht auf Wunsch nur über einen Ausgangsproxy ([Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy)) |
| Welche Pflichten hat das Projekt nach dem Cyber Resilience Act? | [Rollenklärung zum Cyber Resilience Act](../07-ki-verordnung-cra/rollenklaerung-cra.md) |

## Was bei der Einrichtung bleibt

- **Meldung erheblicher Sicherheitsvorfälle** an das BSI nach den Fristen des Gesetzes. Das
  Nachweisprotokoll und das Anwendungsprotokoll liefern Anhaltspunkte, die Bewertung und Meldung
  trifft die Einrichtung.
- **Risikomanagement, Notfallvorsorge und Wiederherstellung:** [Notfallkonzept](../09-betrieb-update/notfallkonzept.md)
  und [Wiederherstellungsprobe](../09-betrieb-update/wiederherstellungsprobe.md).
- **Updates** einspielen, solange nur das jüngste Release Sicherheitskorrekturen erhält
  ([Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen)).
- **Steuerung eines Dienstleisters**, der OPAA betreibt, einschließlich seiner eigenen
  Lieferkette.
