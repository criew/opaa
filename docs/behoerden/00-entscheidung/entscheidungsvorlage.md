---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Entscheidungsvorlage

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden; Form und Weg der Vorlage richten sich nach der eigenen Geschäftsordnung.

Die Entscheidungsvorlage bereitet für die Hausleitung die Frage auf, ob die Behörde OPAA prüfen und
erproben lässt. Sie nennt Nutzen, Kosten, Risiken und die nächsten Schritte, bevor Geld für
Markterkundung, Pilot oder Vergabe gebunden wird. Verantwortlich ist die Stelle, die das Vorhaben
anstößt; die Wirtschaftlichkeit untersucht sie nach der WiBe-Methodik des Bundes, der
standardisierten Wirtschaftlichkeitsbetrachtung für IT-Vorhaben.

## Was OPAA liefert

| Frage der Hausleitung | Quelle |
|---|---|
| Was leistet OPAA? | [Produktdatenblatt](../01-markterkundung/produktdatenblatt.md), [Was OPAA ist](../../handbuch/README.md#2-was-opaa-ist) |
| Was braucht der Betrieb? | [Bausteine](../../handbuch/betrieb/README.md#bausteine), [Modelle](../../handbuch/betrieb/modelle.md) |
| Welche Daten verlassen das Haus? | {{faktenblatt:datenfluesse}} |
| Wie lange gibt es Sicherheitsupdates? | [Linien und Supportzeiträume](../../handbuch/betrieb/releases-und-nachweise.md#linien-und-supportzeiträume) |
| Wer steht hinter dem Projekt? | [Projektstatus](../01-markterkundung/projektstatus.md) |
| Was kostet die Lizenz? | nichts; [Lizenz](../01-markterkundung/lizenz.md) |

OPAA nennt keine Preise und keine Aufwände. Die Kosten hängen am Betriebsweg und an den Modellen,
die die Behörde wählt.

## Aufgaben der Behörde

1. **Ziel benennen:** welche Fragen der Assistent beantworten soll, aus welchen Beständen, für wen.
2. **Betriebsweg skizzieren:** eigener Betrieb oder Dienstleister; Modelle im eigenen Haus oder bei
   einem externen Anbieter.
3. **Kostenspanne ermitteln** mit der Tabelle unten. Jede Zahl braucht eine genannte Annahme.
4. **Risiken bewerten** und Gegenmaßnahmen festlegen.
5. **Beteiligte früh einbinden:** Datenschutz, Informationssicherheit und Personalvertretung. Schon
   ein Pilot mit Beschäftigten oder echten Daten kann mitbestimmungspflichtig sein.
6. **Nächste Schritte beschließen:** Markterkundung, Pilot und Teststellung, Vergabe.

## Ergebnis festhalten

Kostenspanne über die geplante Laufzeit. Je Zeile die Annahme nennen, etwa Zahl der Nutzenden,
Größe der Bestände, Modellgröße, Tagessatz oder Abschreibungsdauer.

| Kostenart | Bestandteile | Annahme | einmalig (von–bis) | laufend je Jahr (von–bis) |
|---|---|---|---|---|
| Hardware und GPU | Server für OPAA und Datenbank, Modellserver mit GPU, Speicher für Datenbank, Originale und Sicherungen | [ ] | [ ] | [ ] |
| Betrieb | Rechenzentrum, Strom, Sicherung, Überwachung, Netz; bei externem Modellanbieter dessen nutzungsabhängige Entgelte | [ ] | [ ] | [ ] |
| Personal | Projektleitung, Betrieb und Updates, Pflege der Wissensbibliotheken, Schulung, Beteiligungsverfahren | [ ] | [ ] | [ ] |
| Leistungen Dritter | Einführung, Betrieb, Pflege oder Support durch einen Dienstleister ([Auftragswertschätzung](../03-vergabe/auftragswertschaetzung.md)) | [ ] | [ ] | [ ] |
| **Summe** | | | [ ] | [ ] |

| Risiko | Gegenmaßnahme | Bewertung |
|---|---|---|
| Antworten taugen für die eigenen Bestände nicht | Teststellung mit eigenen Dokumenten und vorab festgelegten Kriterien | [ ] |
| Daten gehen an einen externen Modellanbieter | Modelle im eigenen Haus; Ziele je Version in {{faktenblatt:datenfluesse}} | [ ] |
| Kontrolle von Leistung und Verhalten | [pseudonymisiertes Protokoll](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll), Dienstvereinbarung | [ ] |
| Kein Hersteller mit vertraglicher Zusage | Dienstleister oder Eigenbetrieb; [Projektstatus](../01-markterkundung/projektstatus.md) | [ ] |
| Sicherheitslücken | Patch-Releases je unterstützter Linie, ältere Linien über eigene Wartungsbranches; der Schwachstellen-Scan prüft bisher nur das jüngste Release; [Sicherheitsnachweise](../06-informationssicherheit/sicherheitsnachweise.md) | [ ] |
| Updates ändern Daten oder Voreinstellungen | [Update-Vermerk](../09-betrieb-update/update-vermerk.md) je Update | [ ] |
| [eigenes Risiko] | [ ] | [ ] |

```text
Vorhaben:          [Name], Ziel: [ ]
Betriebsweg:       [eigener Betrieb / Dienstleister], Modelle: [im Haus / extern: Anbieter]
Kostenspanne:      einmalig [von–bis], laufend je Jahr [von–bis], Laufzeit [ ] Jahre
Beteiligt:         DSB [ ], ISB [ ], Personalvertretung [ ]
Entscheidung:      [Markterkundung / Pilot / Vergabe / nicht weiterverfolgen]
                   durch: [ ]        am: [ ]
```

## Gut zu wissen

- Kein Lizenzentgelt heißt nicht kostenlos. Größter Hardwareposten ist in der Regel der Modellserver
  mit GPU; ein externer Modellanbieter verschiebt Kosten in den laufenden Betrieb.
- Den Nutzen belegt am besten eine Teststellung mit eigenen Dokumenten, nicht die Demo.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Wirtschaftlichkeitsbetrachtung (WiBe)](https://www.digitale-verwaltung.de/Webs/DV/DE/aktuelles-service/wirtschaftlichkeitsbetrachtung/wirtschaftlichkeitsbetrachtung-node.html):
  Methode, Kriterienkatalog und Kalkulator des Bundes.
- [Auftragswertschätzung](../03-vergabe/auftragswertschaetzung.md): Leistungen eines Dienstleisters
  für die spätere Vergabe.

**Rechtsgrundlagen** für Haushalt und Hausleitung:

| Worum es geht | Fundstelle |
|---|---|
| Wirtschaftlichkeit und Wirtschaftlichkeitsuntersuchung bei finanzwirksamen Maßnahmen | § 7 [BHO](https://www.gesetze-im-internet.de/bho/__7.html) bzw. Landeshaushaltsordnung; für Kommunen Gemeindehaushaltsrecht, zu prüfen |
| Mitbestimmung bei technischen Einrichtungen, die zur Überwachung geeignet sind | § 80 Abs. 1 Nr. 21 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__80.html) bzw. Landespersonalvertretungsgesetz |
| Objektive Eignung zur Überwachung genügt | BVerwG 5 P 16.21 ([Pressemitteilung](https://www.bverwg.de/de/pm/2023/34)) |
