---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Datenschutz-Folgenabschätzung (DSFA)

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, deren Datenschutzaufsicht die oder der BfDI oder die
Landesdatenschutzbeauftragten sind. Öffentliche Unternehmen im Wettbewerb, etwa Sparkassen, und
kirchliche Träger folgen teils anderen Regeln.

Eine DSFA ist eine schriftliche Risikoprüfung, die die Datenschutz-Grundverordnung vor dem Start einer
Verarbeitung verlangt, wenn diese für die betroffenen Menschen voraussichtlich ein hohes Risiko
birgt. Bei einem KI-Assistenten, der auch Daten über Beschäftigte speichert, ist das in der Regel der
Fall. Verantwortlich ist die Behörde; ihre Datenschutzbeauftragte oder ihr Datenschutzbeauftragter
(DSB) berät und nimmt Stellung.

## Brauchen wir eine DSFA?

Maßgeblich ist die **Muss-Liste der zuständigen Aufsichtsbehörde**: für Bundesbehörden die Liste der
oder des [BfDI](https://www.bfdi.bund.de/), für Landes- und Kommunalbehörden die Liste der
Landesdatenschutzbeauftragten des eigenen Bundeslands, jeweils auf deren Website. Die oder der DSB
der Behörde weiß, welche Liste gilt. Die Listen nennen KI-Verfahren und die Überwachung von Beschäftigten
regelmäßig. Wer sich gegen eine DSFA entscheidet, schreibt die Begründung auf.

## Was OPAA liefert

Die Faktenblätter beantworten die Fragen, mit denen jede DSFA beginnt.

| Frage der DSFA | Antwort |
|---|---|
| Welche Daten über wen, wozu und wie lange? | {{faktenblatt:datenkatalog}}, {{faktenblatt:fristen}} |
| Wer sieht was? | {{faktenblatt:rollensicht}} |
| Was wird protokolliert? | {{faktenblatt:protokollereignisse}} |
| Welche Daten verlassen das Haus? | {{faktenblatt:datenfluesse#datenfluesse}} |
| Was tut die KI? | {{faktenblatt:voreinstellungen#ki-funktionen}} |

Die Faktenblätter zeigen die Voreinstellungen. Was die eigene Installation anders eingestellt hat,
zeigt die [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation).

## Aufgaben der Behörde

1. **Muss-Liste prüfen** und das Ergebnis festhalten
2. **Verarbeitung beschreiben:** Einsatzzweck, eingesetzte Version, Betrieb im eigenen Haus oder bei
   einem Dienstleister, Modellanbieter (siehe [AVV-Entscheidungsmatrix](./avv-entscheidungsmatrix.md));
   alles Übrige aus der Tabelle oben.
3. **Begründen, warum der Assistent nötig ist:** welche Dokumentensammlungen angebunden werden, welche
   bewusst nicht, welche Fristen gelten (siehe [Löschkonzept](./loeschkonzept.md)).
4. **Risiken bewerten:** die Tabelle unten durchgehen, je Zeile Maßnahme und Bewertung eintragen,
   eigene Risiken ergänzen.
5. **Stellungnahme der oder des DSB einholen**, dann entscheidet die Leitung.
6. **Bei jedem Update nachprüfen:** Das Update-Paket nennt die Änderungen, bei denen es eine
   Überprüfung der DSFA vorschlägt.

## Typische Risiken

| Risiko | Was OPAA mitbringt | Was die Behörde festlegt | Bewertung |
|---|---|---|---|
| Kontrolle von Leistung und Verhalten der Beschäftigten | pseudonymisiertes Protokoll ([Pseudonymisierung im Protokoll](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll)), Einsicht nur für die Revision ({{faktenblatt:rollensicht#auditor}}) | [Dienstvereinbarung](../05-mitbestimmung/dienstvereinbarung.md), Rollenvergabe | [ ] |
| Fragen und Antworten werden anderen bekannt | Sichtbarkeit und Löschung von Chats ({{faktenblatt:datenkatalog#chats}}) | Regeln der [Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md) | [ ] |
| Zu weit gefasste Rechte an Dokumenten mit Personendaten | Rechte je Bibliothek und Space mit Historie ([Berechtigungen](../../handbuch/konten-und-rechte/berechtigungen.md)) | Freigabeprozess, ausgeschlossene Quellen | [ ] |
| Daten gehen an einen externen Modellanbieter | lokale Modelle als Voreinstellung ([Modelle](../../handbuch/betrieb/modelle.md)) | Wahl des Anbieters, Vertrag, Netzregeln | [ ] |
| Falsche Aussagen über Personen in Antworten | Antworten mit Fundstellen ([Suche](../../handbuch/spaces/suche.md)) | Regel „Antwort ist ein Entwurf“, keine Entscheidungen über Personen | [ ] |
| Daten bleiben länger als nötig | Fristen und Löschung je Datum ({{faktenblatt:fristen}}) | gewählte Fristen | [ ] |
| Beschäftigte sollen ihre Selbstauskunft vorlegen, etwa bei Vorgesetzten | Hinweis gegen die Vorlage in Oberfläche und Auskunft, keine Signatur, Download nur nach erneuter Anmeldung, Benachrichtigung bei jedem Start ([Selbstauskunft](../../handbuch/konten-und-rechte/benutzerverwaltung.md#meine-daten-die-selbstauskunft)) | Regel in [Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md) oder [Dienstvereinbarung](../05-mitbestimmung/dienstvereinbarung.md) | [ ] |
| Unbefugter Zugriff auf Datenbank oder Sicherungen | [Technische und organisatorische Maßnahmen](./technische-und-organisatorische-massnahmen.md) des Produkts | Sicherheitskonzept, Betrieb | [ ] |

## Ergebnis festhalten

```text
DSFA für:            [Name der Installation], OPAA-Version [Version]
Erforderlich nach:   [Muss-Liste / eigene Prüfung], Begründung: [ ]
Restrisiko:          [ ]
Stellungnahme DSB:   [ ]
Entscheidung:        [ ]        durch: [ ]        am: [ ]
Nächste Überprüfung: beim nächsten Update-Paket mit Hinweis auf die DSFA
```

## Gut zu wissen

- Bleibt nach den Maßnahmen ein hohes Risiko, muss die Behörde vor dem Start die Aufsichtsbehörde
  einbeziehen.
- Der Vorschlag des Update-Pakets, die DSFA zu überprüfen, ist eine Faustregel. Ob sich das Risiko
  tatsächlich ändert, bewertet die Behörde.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Kurzpapier Nr. 5 der Datenschutzkonferenz](https://www.datenschutzkonferenz-online.de/kurzpapiere.html):
  wie eine DSFA abläuft, auf wenigen Seiten.
- [Orientierungshilfe „Künstliche Intelligenz und Datenschutz“](https://www.datenschutzkonferenz-online.de/media/oh/20240506_DSK_Orientierungshilfe_KI_und_Datenschutz.pdf)
  der Datenschutzkonferenz (6.5.2024): Fragen, die bei KI-Anwendungen zu prüfen sind.

**Rechtsgrundlagen** für die oder den DSB:

| Worum es geht | Fundstelle |
|---|---|
| Wann eine DSFA Pflicht ist und was sie enthalten muss | Art. 35 Abs. 1 und 7 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Muss-Listen der Aufsichtsbehörden | Art. 35 Abs. 4 DSGVO |
| Überprüfung, wenn sich das Risiko ändert | Art. 35 Abs. 11 DSGVO |
| Aufsichtsbehörde einbeziehen bei hohem Restrisiko | Art. 36 DSGVO |
| Daten über Beschäftigte | § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht |
