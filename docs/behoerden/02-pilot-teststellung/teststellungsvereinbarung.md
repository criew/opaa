---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Teststellungsvereinbarung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, die eine Teststellung von einem Dienstleister betreiben lassen.

Betreibt ein Dienstleister die Teststellung, regelt eine Vereinbarung, was getestet wird, welche
Daten der Behörde dabei wohin gehen und wie sie danach gelöscht werden. Der Löschnachweis belegt
das Ende. Verantwortlich für die Daten bleibt die Behörde; liegen personenbezogene Daten beim
Dienstleister, braucht es in der Regel einen Vertrag zur Auftragsverarbeitung (AVV).

## Was OPAA liefert

- **Wo Daten liegen:** Speicherort und Löschmechanismus je Datum im {{faktenblatt:datenkatalog}};
  Datenbank und Originalablage als die beiden Bestände
  ([Was OPAA ist](../../handbuch/README.md#2-was-opaa-ist),
  [Sicherung](../../handbuch/betrieb/sicherung-und-wiederherstellung.md#sicherung)).
- **Wohin Daten gehen:** {{faktenblatt:datenfluesse}}.
- **Wie die Installation eingestellt ist:** die
  [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation).

## Aufgaben der Behörde

1. **Vereinbarung ausfüllen** und vor der ersten Datenübergabe unterzeichnen.
2. **Auftragsverarbeitung prüfen** ([AVV-Entscheidungsmatrix](../04-datenschutz/avv-entscheidungsmatrix.md)).
3. **Nur ausgewählte Bestände übergeben**, nach dem [Teststellungsszenario](teststellungsszenario.md),
   auf einem vereinbarten Weg.
4. **Gleichbehandlung wahren:** Folgt eine Vergabe, prüft die Vergabestelle, ob der Dienstleister
   durch die Teststellung einen Vorsprung erhält, und gleicht ihn aus.
5. **Löschnachweis verlangen** und prüfen, ob jede Zeile ausgefüllt ist.

## Ergebnis festhalten

| Regelung | Eintrag |
|---|---|
| Parteien | Behörde: [ ]; Dienstleister: [ ] |
| Gegenstand | Teststellung von OPAA [X.Y.Z] nach dem Teststellungsszenario vom [ ] |
| Dauer | von [ ] bis [ ] |
| Betriebsort | [Rechenzentrum, Staat]; Unterauftragnehmer: [keine / Liste] |
| Modelle und Ziele nach außen | [Modelle, Anbieter]; nur die vereinbarten Ziele aus {{faktenblatt:datenfluesse}} |
| Daten der Behörde | [Bestände, Konten der Testpersonen]; Übergabe über [ ] |
| Zugriff beim Dienstleister | nur [benannte Personen], zur Vertraulichkeit verpflichtet |
| Auftragsverarbeitung | [AVV vom … / nicht nötig, Begründung] |
| Entgelt | [unentgeltlich / Betrag] |
| Ergebnisse | gehören der Behörde; keine Nennung als Referenz ohne schriftliche Zustimmung |
| Ende und Löschung | binnen [ ] Tagen nach Ende alle Daten der Behörde, auch in Sicherungen und Logs; Nachweis mit dem Löschnachweis unten |
| Unterzeichnet | Behörde: [Name, Datum]; Dienstleister: [Name, Datum] |

### Löschnachweis

| Speicherort | Gelöscht am | Wie | Durch |
|---|---|---|---|
| Datenbank der Installation | [ ] | [Datenbank gelöscht / Datenträger überschrieben] | [ ] |
| Originalablage | [ ] | [ ] | [ ] |
| Sicherungen | [ ] | [ ] | [ ] |
| Logs der Installation und des Log-Sammlers | [ ] | [ ] | [ ] |
| Modelldienst: Zwischenspeicher und Logs, soweit beim Dienstleister | [ ] | [ ] | [ ] |
| Kopien der übergebenen Dokumente | [ ] | [ ] | [ ] |
| Zugänge zu Quellen der Behörde | [ ] | [widerrufen bei der Quelle] | [ ] |

```text
Der Dienstleister bestätigt, dass keine weiteren Kopien der Daten der Behörde bestehen.
Ort, Datum, Name, Unterschrift: [ ]
Geprüft für die Behörde:        [Name, Datum]
```

## Gut zu wissen

- Gelöscht werden muss auch, was außerhalb von OPAA liegt: Sicherungen, Log-Sammler und der
  Modelldienst. Der Datenkatalog nennt nur, was OPAA selbst speichert.
- Zugänge zu Quellen, etwa ein Dienstkonto für eine Ablage, widerruft die Behörde bei der Quelle.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Löschkonzept](../04-datenschutz/loeschkonzept.md): Fristen und Löschwege im Regelbetrieb.

**Rechtsgrundlagen** für DSB und Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Vertrag zur Auftragsverarbeitung | Art. 28 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Löschen oder Rückgabe nach Ende der Verarbeitung | Art. 28 Abs. 3 lit. g DSGVO |
| Gleichbehandlung der Teilnehmer eines Vergabeverfahrens | § 97 Abs. 2 [GWB](https://www.gesetze-im-internet.de/gwb/__97.html) |
| Vorbefasste Unternehmen, Ausgleich eines Vorsprungs | § 7 [VgV](https://www.gesetze-im-internet.de/vgv_2016/__7.html); unterhalb der EU-Schwellenwerte UVgO bzw. Landesrecht, zu prüfen |
