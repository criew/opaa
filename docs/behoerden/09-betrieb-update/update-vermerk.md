---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Update-Vermerk

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden; die beteiligten Stellen richten sich nach der eigenen Organisation.

Der Update-Vermerk hält fest, wie die Behörde ein Update von OPAA bewertet und entschieden hat. Er
gehört zu den Unterlagen der Installation, neben Verzeichnis von Verarbeitungstätigkeiten (VVT),
Datenschutz-Folgenabschätzung (DSFA), Sicherheitskonzept und Dienstvereinbarung. Grundlage ist das
Update-Paket, das am GitHub-Release jeder Version hängt und diese Vorlage enthält.

> **Beispiel.** Ein Landratsamt will von Version 1.2 auf 1.3 gehen. Das Delta im Update-Paket
> schlägt für eine neue Auswertung „Zustimmung“ vor und meldet ein neues Ziel für ausgehende
> Verbindungen. Die Projektleitung reicht den Vermerk an Datenschutz, Personalrat,
> Informationssicherheit und Betrieb.
> Der Personalrat stimmt zu, der Betrieb plant die Sicherung vor dem Update; die Amtsleitung gibt
> frei, das Update läuft zwei Wochen später.

## Was OPAA liefert

Das Update-Paket der Zielversion enthält:

- `delta/manifest-delta.md`: jede Änderung der Fakten seit dem vorigen finalen Release, mit
  Stufenvorschlag (Bagatelle, Anzeige, Zustimmung mit den Klassen Z1 bis Z7) und dem Hinweis, ob VVT,
  DSFA oder Sicherheitskonzept betroffen sind.
- `faktenblaetter/`: die Fakten der Zielversion, etwa {{faktenblatt:datenfluesse}} und
  {{faktenblatt:fristen}}.
- `betrieb/betriebsfolgen.md`: Vorbereitung, Neuindizierung mit Dauerschätzung, Schemaänderungen,
  neue Ports und Ziele.
- `betrieb/antwortqualitaet.md`: die Suchqualität beider Versionen.

## Aufgaben der Behörde

1. **Paket prüfen lassen:** Prüfsumme und Herkunft, wie in `LIESMICH.md` beschrieben.
2. **Übersprungene Versionen beachten:** Das Paket vergleicht nur mit dem vorigen finalen Release.
   Für einen größeren Sprung erzeugt der Betrieb Delta und Betriebsfolgen zwischen eingesetzter und
   Zielversion und legt sie bei.
3. **Vermerk an die Stellen geben**, die nach eigener Organisation beteiligt sind. Die Stufen im
   Delta sind Vorschläge; die Bewertung trifft jede Stelle selbst.
4. **Entscheiden lassen** und Auflagen vor dem Update erledigen.
5. **Nach dem Update** Datum und angezeigte Version eintragen und den Vermerk ablegen.

## Ergebnis festhalten

Angaben in eckigen Klammern ersetzt die Behörde; eine Stelle, die nicht beteiligt ist, wird
gestrichen oder mit „nicht beteiligt“ vermerkt.

| Feld | Eintrag |
|---|---|
| Eingesetzte Version / Zielversion | [X.Y.Z] / [X.Y.Z] |
| Update-Paket | [Dateiname, Prüfsumme aus der `.sha256`-Datei] |
| Herkunft geprüft | [ja, mit `gh attestation verify` am TT.MM.JJJJ / nein, Begründung] |
| Geplanter Termin | [TT.MM.JJJJ] |
| Verglichene Versionen | [von … bis …, wie im Kopf von `manifest-delta.md`] |
| Vorschläge im Delta | [Zustimmung: Anzahl, Klassen; Anzeige: Anzahl; Bagatelle: Anzahl] |
| Weitere gekennzeichnete Änderungen | [aus dem Abschnitt im Delta, oder „keine“] |
| Vorbereitung, Neuindizierung | [aus `betrieb/betriebsfolgen.md`, mit Dauerschätzung, oder „keine“] |
| Schemaänderungen und Sicherung | [Anzahl; Sicherung erstellt am TT.MM.JJJJ] |
| Neue Ports und Ziele | [aus `betrieb/betriebsfolgen.md`; Freigaben beantragt am TT.MM.JJJJ, oder „keine“] |
| Antwortqualität | [beide Versionen aus `betrieb/antwortqualitaet.md`, oder „nicht gemessen“] |
| Abweichende Einstellungen | [Voreinstellungen, die die Installation überschrieben hat und die das Delta deshalb anders trifft] |

| Stelle | Bewertung | Folge für die eigenen Unterlagen | Name, Datum |
|---|---|---|---|
| Datenschutzbeauftragte Person | [Änderungen mit Bezug zu VVT und DSFA] | [VVT anpassen ja/nein; DSFA überprüfen ja/nein] | [ ] |
| Personalvertretung | [Stufe nach der Dienstvereinbarung] | [Anzeige am … / Zustimmung beantragt am … / erteilt am …] | [ ] |
| Informationssicherheit | [neue Datenflüsse, Ports, Ziele, Rollen, Protokollereignisse] | [Sicherheitskonzept fortschreiben ja/nein] | [ ] |
| Betrieb | [Vorbereitung, Sicherung vor dem Update, Wartungsfenster] | [Notfallkonzept betroffen ja/nein] | [ ] |
| [weitere Stelle] | | | |

| Feld | Eintrag |
|---|---|
| Entscheidung | [freigegeben / freigegeben mit Auflagen / zurückgestellt / abgelehnt] |
| Auflagen | [etwa: Voreinstellung vorher ändern, Funktion abschalten, Beschäftigte informieren] |
| Begründung | [kurz] |
| Entschieden durch, am | [Name, Funktion, TT.MM.JJJJ] |
| Update eingespielt am | [TT.MM.JJJJ, Version laut Anzeige der Installation] |

## Gut zu wissen

- Das Delta misst an den Voreinstellungen. Hat die Installation eine Einstellung überschrieben,
  trifft eine Änderung sie womöglich anders; das prüft die Behörde selbst.
- Auch ohne Datenänderung kann ein Update den Betrieb betreffen, etwa durch eine Neuindizierung.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Das Update-Paket eines Release](https://github.com/criew/opaa/blob/main/docs/handbuch/nachvollziehbarkeit.md#das-update-paket-eines-release)
  im Handbuch: Inhalt des Pakets, Herkunftsprüfung, Delta für übersprungene Versionen.
- [Aktualisierung](https://github.com/criew/opaa/blob/main/docs/handbuch/betrieb.md#aktualisierung)
  im Handbuch: wie der Betrieb ein Update einspielt.
