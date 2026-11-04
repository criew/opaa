---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Kurzprüfung vor Pilot und Teststellung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden mit Personalrat und behördlicher oder behördlichem Datenschutzbeauftragten;
die beteiligten Stellen richten sich nach der eigenen Organisation.

Die Kurzprüfung sammelt die Fragen, die Datenschutz, Informationssicherheit und Personalvertretung
vor dem Start eines Pilots oder einer Teststellung beantwortet sehen wollen. Sie ersetzt keine
Datenschutz-Folgenabschätzung, kein Sicherheitskonzept und keine Dienstvereinbarung, sondern klärt,
ob die Erprobung so beginnen kann. Schon ein Pilot mit Beschäftigten oder echten Daten kann
mitbestimmungspflichtig sein. Verantwortlich ist die Projektleitung.

## Was OPAA liefert

Die Antworten stehen in den Faktenblättern der Version, die erprobt wird, und im Handbuch. Die
Version wird vor dem Start festgelegt und bleibt während der Erprobung gleich.

## Aufgaben der Behörde

1. **Version festlegen,** die erprobt wird, und die drei Stellen beteiligen.
2. **Je Stelle die Tabelle durchgehen** und offene Punkte vor dem Start klären.
3. **Ergebnis festhalten** und der [Pilot-Vereinbarung](pilot-vereinbarung.md) oder der
   [Teststellungsvereinbarung](teststellungsvereinbarung.md) beilegen.

## Datenschutz

| Frage | Wo die Antwort steht | geklärt |
|---|---|---|
| Welche Daten über Beschäftigte und Dritte fallen an, wie lange? | {{faktenblatt:datenkatalog}}, {{faktenblatt:fristen}} | [ ] |
| Gehen Daten an einen externen Modellanbieter? | {{faktenblatt:datenfluesse}}, gewählter Betriebsweg | [ ] |
| Enthalten die Testdokumente personenbezogene Daten? | Auswahl im [Teststellungsszenario](teststellungsszenario.md) | [ ] |
| Ist eine DSFA schon für die Erprobung nötig? | [Datenschutz-Folgenabschätzung](../04-datenschutz/datenschutz-folgenabschaetzung.md#brauchen-wir-eine-dsfa) | [ ] |
| Braucht es einen Vertrag zur Auftragsverarbeitung? | [AVV-Entscheidungsmatrix](../04-datenschutz/avv-entscheidungsmatrix.md) | [ ] |
| Wie werden die Teilnehmenden informiert? | [Informationspflichten](../04-datenschutz/informationspflichten.md) | [ ] |
| Wie und wann wird nach dem Ende gelöscht? | [Löschnachweis](teststellungsvereinbarung.md#löschnachweis) | [ ] |

## Informationssicherheit

| Frage | Wo die Antwort steht | geklärt |
|---|---|---|
| Wo läuft die Erprobung, wer betreibt sie? | [Bausteine](../../handbuch/betrieb/README.md#bausteine) | [ ] |
| Ist die Installation gehärtet und nur im vorgesehenen Netz erreichbar? | [Härtung](../../handbuch/betrieb/netz-und-sicherheit.md#härtung-für-erreichbare-deployments) | [ ] |
| Welche Verbindungen nach außen sind nötig? | {{faktenblatt:datenfluesse}} | [ ] |
| Erhält die Version Sicherheitsupdates? | [Versionen](../../handbuch/betrieb/releases-und-nachweise.md#versionen); Vorab-Releases nicht | [ ] |
| Wer meldet und behebt einen Sicherheitsvorfall? | [Sicherheitsnachweise](../06-informationssicherheit/sicherheitsnachweise.md) | [ ] |

## Personalvertretung

| Frage | Wo die Antwort steht | geklärt |
|---|---|---|
| Wer nimmt teil, freiwillig, wie lange? | [Pilot-Vereinbarung](pilot-vereinbarung.md) | [ ] |
| Was wird über die Teilnehmenden protokolliert? | {{faktenblatt:protokollereignisse}}, [Pseudonymisierung](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll) | [ ] |
| Wer sieht was über andere? | {{faktenblatt:rollensicht#matrix}}, [Informationsmappe](../05-mitbestimmung/informationsmappe-personalrat.md) | [ ] |
| Welche Auswertungen wären möglich, welche sind ausgeschlossen? | Zeile „Mögliche Auswertungen“ im {{faktenblatt:datenkatalog}}, [Pilot-Vereinbarung](pilot-vereinbarung.md) | [ ] |
| Was geschieht mit den Daten nach dem Ende? | [Pilot-Vereinbarung](pilot-vereinbarung.md) | [ ] |

## Ergebnis festhalten

```text
Erprobung:          [Pilot / Teststellung], OPAA-Version [X.Y.Z], von [ ] bis [ ]
Datenschutz:        [Start möglich / mit Auflagen: … / nicht möglich]   Name, Datum: [ ]
Informationssich.:  [Start möglich / mit Auflagen: … / nicht möglich]   Name, Datum: [ ]
Personalvertretung: [zugestimmt / Auflagen: … / abgelehnt]              Name, Datum: [ ]
```

## Gut zu wissen

- Für die Mitbestimmung kommt es darauf an, ob die Technik zur Überwachung geeignet ist, nicht, ob
  die Behörde überwachen will.
- Wer nur einen ersten Eindruck braucht, nutzt die [Demo](../../../demo/README.md) mit fiktiven
  Daten; dafür fallen keine Daten der Behörde an.

## Zum Nachlesen

**Rechtsgrundlagen** für die beteiligten Stellen:

| Worum es geht | Fundstelle |
|---|---|
| Mitbestimmung bei technischen Einrichtungen zur Überwachung | § 80 Abs. 1 Nr. 21 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__80.html) bzw. Landespersonalvertretungsgesetz |
| Objektive Eignung zur Überwachung genügt | BVerwG 5 P 16.21 ([Pressemitteilung](https://www.bverwg.de/de/pm/2023/34)) |
| Datenschutz-Folgenabschätzung | Art. 35 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Auftragsverarbeitung | Art. 28 DSGVO |
| Daten über Beschäftigte | § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht |
