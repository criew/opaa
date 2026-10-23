---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Teststellungsszenario

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die OPAA vor einer Vergabe oder einem Pilot mit eigenen Beständen
prüfen.

Eine Teststellung prüft, ob OPAA mit den Dokumenten der Behörde die erwarteten Antworten liefert.
Was gut ist, legt die Behörde vor dem Start fest: Prüffragen mit erwarteter Fundstelle sowie Erfolgs-
und Abbruchkriterien. Eine Teststellung mit Demodaten sagt über die eigenen Bestände wenig.
Verantwortlich ist die Projektleitung zusammen mit dem Fachbereich.

## Was OPAA liefert

- **Erster Eindruck ohne eigene Daten:** die [Demo](../../../demo/README.md) mit fiktivem
  Verwaltungsbestand.
- **Kennzahlen der Suchqualität je Version:** im Update-Paket unter `betrieb/antwortqualitaet.md`,
  gemessen gegen den Testkorpus des Projekts, mit Messdatum
  ([Update-Paket](../../handbuch/betrieb/nachvollziehbarkeit.md#das-update-paket-eines-release),
  [Messverfahren](../../features/search-quality-evaluation.md)).
- **Belege und Diagnose:** jede Antwort nennt ihre Fundstellen; das Diagnosewerkzeug zeigt, warum ein
  Dokument fehlt ([Belege](../../handbuch/spaces/suche.md#7-belege-prüfen-und-fundstellen-bilden),
  [Typische Befunde](../../handbuch/spaces/suche.md#9-typische-befunde)).

## Aufgaben der Behörde

1. **Bestände auswählen:** typische Dokumente in den üblichen Formaten und im üblichen Umfang, dazu
   eine Bibliothek, die nicht freigegeben wird. Personenbezogene Daten möglichst meiden, sonst die
   [Kurzprüfung](kurzpruefung.md) abschließen.
2. **Prüffragen schreiben** mit Fachleuten, je Frage die erwartete Fundstelle; auch Fragen, die der
   Bestand nicht beantwortet.
3. **Erfolgs- und Abbruchkriterien festlegen** und vor dem Start unterzeichnen.
4. **Version und Modelle festhalten** und während der Teststellung nicht wechseln.
5. **Bewerten** mit dem Bogen unten, möglichst durch zwei Personen.
6. **Ergebnis dokumentieren** und die Daten danach löschen
   ([Löschnachweis](teststellungsvereinbarung.md#löschnachweis)).

## Ergebnis festhalten

Vor dem Start auszufüllen:

```text
OPAA-Version:        [X.Y.Z], Commit [ ]
Modelle:             Chat [ ], Embedding [ ], Reranking [ / aus]
Kennzahlen Projekt:  [aus betrieb/antwortqualitaet.md der Version, Messdatum]
Bestände:            [Bibliotheken, Zahl der Dokumente, Formate]; nicht freigegeben: [ ]
Zeitraum:            [von] bis [bis]
Festgelegt durch:    [Name, Funktion]          am: [TT.MM.JJJJ, vor dem Start]
```

| Kriterium | Art | Schwelle | Ergebnis |
|---|---|---|---|
| Antworten mit zutreffender Fundstelle | Erfolg | mindestens [ ] % der Prüffragen | [ ] |
| Antworten ohne falsche Aussage | Erfolg | mindestens [ ] % | [ ] |
| Fragen ohne Antwort im Bestand werden als nicht beantwortbar erkannt | Erfolg | mindestens [ ] % | [ ] |
| Ausgewählte Dokumente vollständig indiziert | Erfolg | [ ] % | [ ] |
| Antwortzeit | Erfolg | in [ ] % der Fragen unter [ ] Sekunden | [ ] |
| Fundstelle aus nicht freigegebenem Bestand | Abbruch | bei jedem Vorkommen | [ ] |
| Verbindung an ein nicht vereinbartes Ziel ({{faktenblatt:datenfluesse}}) | Abbruch | bei jedem Vorkommen | [ ] |
| [eigenes Kriterium] | [ ] | [ ] | [ ] |

| Nr. | Prüffrage | Erwartete Fundstelle | Fundstelle genannt | Antwort richtig | Anmerkung |
|---|---|---|---|---|---|
| 1 | [ ] | [Dokument, Abschnitt / keine] | [ja / nein] | [ja / teilweise / nein] | [ ] |

## Gut zu wissen

- Die Antwortqualität hängt stark an Chat- und Embedding-Modell. Ein Ergebnis gilt nur für die
  festgehaltenen Modelle.
- Die Kennzahlen des Projekts stammen aus dessen Testkorpus; erst die eigenen Prüffragen zeigen,
  was OPAA für die Behörde leistet.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Modelle](../../handbuch/betrieb/modelle.md): Chat, Embedding und Reranking einrichten.
- [Teststellungsvereinbarung](teststellungsvereinbarung.md): Rahmen, wenn ein Dienstleister die
  Teststellung betreibt.
