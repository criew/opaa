---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Pilot-Vereinbarung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden mit Personalrat; für Bundesbehörden gilt das Bundespersonalvertretungsgesetz,
sonst das Personalvertretungsgesetz des Landes.

Die Pilot-Vereinbarung legt zwischen Dienststelle und Personalvertretung fest, unter welchen Regeln
ein begrenzter Kreis von Beschäftigten OPAA erprobt. Sie gilt nur für den Pilot und ersetzt nicht die
Dienstvereinbarung für den Regelbetrieb. Ihre Anlagen beschreiben genau eine Version von OPAA, nicht
„die jeweils gültige Fassung“.

## Was OPAA liefert

- **Anlagen der Version:** die Faktenblätter {{faktenblatt:datenkatalog}},
  {{faktenblatt:protokollereignisse}}, {{faktenblatt:rollensicht}}, {{faktenblatt:datenfluesse}},
  {{faktenblatt:fristen}} und {{faktenblatt:voreinstellungen}}. Ihr Kopf nennt Version und Commit.
- **Stand der Installation:** die [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation)
  zeigt, wo die Installation von den Voreinstellungen abweicht.
- **Änderungen bei einem Update:** das Update-Paket mit Delta und Stufenvorschlag
  ([Update-Vermerk](../09-betrieb-update/update-vermerk.md)).

## Aufgaben der Behörde

1. **Version festlegen** und die Faktenblätter genau dieser Version als Anlagen beifügen.
2. **Auskunft über die Datenerhebung** zu Beginn abrufen und beilegen.
3. **Regelungen ausfüllen** in der Tabelle unten, die Vorschläge anpassen.
4. **Kurzprüfung abschließen** ([Kurzprüfung](kurzpruefung.md)), dann unterzeichnen.
5. **Bei einem Update** neue Anlagen beifügen und die Personalvertretung nach der Vereinbarung
   beteiligen.
6. **Am Ende** die Installation löschen und die Löschung nachweisen, etwa mit dem
   [Löschnachweis](teststellungsvereinbarung.md#löschnachweis).

## Ergebnis festhalten

| Regelung | Eintrag |
|---|---|
| Zweck | Erprobung von OPAA für [Aufgabe, Bestände]; Ergebnis ist eine Empfehlung für oder gegen den Regelbetrieb |
| Dauer | von [TT.MM.JJJJ] bis [TT.MM.JJJJ]; Verlängerung nur schriftlich mit Zustimmung der Personalvertretung |
| Teilnehmerkreis | [Organisationseinheiten, Höchstzahl]; Liste bei der Projektleitung |
| Freiwilligkeit | Teilnahme freiwillig; wer nicht teilnimmt oder aussteigt, hat keine Nachteile |
| Eingesetzte Version | OPAA [X.Y.Z], Commit [ ], Anlagen 1 bis 6 dieser Version |
| Angebundene Bestände | [Wissensbibliotheken]; keine Personalakten, keine Verschlusssachen |
| Modelle | [im eigenen Haus / externer Anbieter: Name, Vertrag] |
| Ausgeschlossene Auswertungen | keine Kontrolle von Leistung und Verhalten; keine Auswertung von Fragen, Chats oder Protokoll zu einzelnen Personen; keine Ranglisten |
| Zugriff auf Datenbank und Protokoll | nur [benannte Rollen], nur anlassbezogen, mit zweiter Person; jeder Zugriff wird der Personalvertretung mitgeteilt |
| Bewertung des Pilots | nur zusammengefasst über alle Teilnehmenden; Befragungen freiwillig und ohne Namen |
| Kontrollrechte | Personalvertretung erhält auf Anfrage die Auskunft über die Datenerhebung und Einsicht in die Anlagen |
| Updates während des Pilots | nur nach [Update-Vermerk](../09-betrieb-update/update-vermerk.md); Stufe Zustimmung nur mit Zustimmung |
| Ende und Löschung | binnen [ ] Wochen nach Ende wird die ganze Installation des Pilots gelöscht: Datenbank, Originalablage, Sicherungen und Logs; Nachweis an die Personalvertretung. Bleibt die Installation stehen, etwa weil ein Regelbetrieb folgt, regelt die Dienstvereinbarung den Bestand; das Protokoll folgt dann seiner Frist nach {{faktenblatt:fristen}} |
| Anlagen | 1–6 Faktenblätter Version [X.Y.Z]; 7 Auskunft über die Datenerhebung vom [ ]; 8 Kurzprüfung |
| Unterzeichnet | Dienststelle: [Name, Datum]; Personalvertretung: [Name, Datum] |

## Gut zu wissen

- Die Faktenblätter zeigen, welche Auswertungen die Daten technisch zulassen. Die Vereinbarung
  regelt, welche davon ausgeschlossen sind.
- Für den Regelbetrieb braucht es eine Dienstvereinbarung (Ordner `05-mitbestimmung/`).
- Das Protokoll lässt sich nur anfügen, nicht vorzeitig einzeln löschen. Vor Ablauf seiner Frist
  entfällt es nur mit der ganzen Datenbank.

## Zum Nachlesen

**Arbeitshilfen** für Projektleitung und Personalvertretung:

- [Pseudonymisierung im Protokoll](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll):
  wie OPAA Personen im Protokoll schützt.

**Rechtsgrundlagen** für Dienststelle und Personalvertretung:

| Worum es geht | Fundstelle |
|---|---|
| Mitbestimmung bei technischen Einrichtungen zur Überwachung | § 80 Abs. 1 Nr. 21 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__80.html) bzw. Landespersonalvertretungsgesetz |
| Objektive Eignung zur Überwachung genügt | BVerwG 5 P 16.21 ([Pressemitteilung](https://www.bverwg.de/de/pm/2023/34)) |
| Dienstvereinbarungen | § 63 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__63.html) bzw. Landespersonalvertretungsgesetz |
| Daten über Beschäftigte | § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht |
