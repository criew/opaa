---
stand: 2026-10-17
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Update-Vermerk für OPAA

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Der Vermerk hält fest, wie die Behörde ein Update von OPAA bewertet und entschieden hat. Er gehört zu
den Unterlagen der Installation, neben Verzeichnis von Verarbeitungstätigkeiten (VVT),
Datenschutz-Folgenabschätzung (DSFA), Sicherheitskonzept und Dienstvereinbarung. Grundlage ist das
**Update-Paket** der Zielversion, das am GitHub-Release jeder Version hängt und in dem diese Vorlage
liegt:

- `delta/manifest-delta.md`: jede Änderung der Fakten seit dem vorigen finalen Release mit
  Stufenvorschlag (Bagatelle, Anzeige, Zustimmung mit den Klassen Z1 bis Z7) und dem Hinweis, ob
  VVT, DSFA oder Sicherheitskonzept betroffen sind, dazu die als datenrelevant gekennzeichneten
  Änderungen, deren Wirkung nicht im Manifest steht,
- `faktenblaetter/`: die Fakten der Zielversion, etwa {{faktenblatt:datenfluesse}} und
  {{faktenblatt:fristen}},
- die Release-Notizen mit den Vorbereitungsschritten für Bestandsinstallationen.

Überspringt die Behörde Versionen, vergleicht das Delta im Paket nur mit dem vorigen finalen
Release. Für den ganzen Sprung erzeugt der Betrieb das kumulierte Delta zwischen eingesetzter und
Zielversion (Aufruf in `LIESMICH.md` des Pakets) und legt es diesem Vermerk bei.

Stufen und Bezüge im Delta sind Vorschläge; die Bewertung trifft die Behörde. Angaben in eckigen
Klammern **[…]** ersetzt die Behörde. Eine Stelle, die nicht beteiligt ist, wird gestrichen oder mit
„nicht beteiligt“ vermerkt.

## Version

| Feld | Eintrag |
|---|---|
| Eingesetzte Version | [X.Y.Z] |
| Zielversion | [X.Y.Z] |
| Update-Paket | [Dateiname, Prüfsumme aus der `.sha256`-Datei] |
| Herkunft geprüft | [ja, mit `gh attestation verify` am TT.MM.JJJJ / nein, Begründung] |
| Geplanter Termin des Updates | [TT.MM.JJJJ] |

## Delta

| Feld | Eintrag |
|---|---|
| Verglichene Versionen | [von … bis …, wie im Kopf von `manifest-delta.md`] |
| Änderungen mit Vorschlag Zustimmung | [Anzahl, Klassen Z1 bis Z7] |
| Änderungen mit Vorschlag Anzeige | [Anzahl] |
| Änderungen mit Vorschlag Bagatelle | [Anzahl] |
| Weitere gekennzeichnete Änderungen | [Pull Requests aus dem Abschnitt im Delta, oder „keine“] |
| Vorbereitung für Bestandsinstallationen | [aus den Release-Notizen, oder „keine“] |
| Abweichende Einstellungen der Installation | [Voreinstellungen, die die Installation überschrieben hat und die das Delta deshalb anders trifft] |

## Bewertung je Stelle

| Stelle | Bewertung | Folge für die eigenen Unterlagen | Name, Datum |
|---|---|---|---|
| Datenschutzbeauftragte Person | [Bewertung der Änderungen mit Bezug zu VVT und DSFA] | [VVT anpassen ja/nein; DSFA überprüfen ja/nein] | [Name, TT.MM.JJJJ] |
| Personalvertretung | [Stufe nach der Dienstvereinbarung: Bagatelle, Anzeige, Zustimmung] | [Anzeige erfolgt am … / Zustimmung beantragt am … / erteilt am …] | [Name, TT.MM.JJJJ] |
| Informationssicherheitsbeauftragte Person | [Bewertung neuer Datenflüsse, Ports, Ziele, Rollen und Protokollereignisse] | [Sicherheitskonzept fortschreiben ja/nein] | [Name, TT.MM.JJJJ] |
| Betrieb | [Vorbereitung, Sicherung vor dem Update, Wartungsfenster] | [Notfallkonzept betroffen ja/nein] | [Name, TT.MM.JJJJ] |
| [weitere Stelle] | | | |

## Entscheidung

| Feld | Eintrag |
|---|---|
| Entscheidung | [Update freigegeben / freigegeben mit Auflagen / zurückgestellt / abgelehnt] |
| Auflagen | [etwa: Voreinstellung vor dem Update ändern, Funktion abschalten, Beschäftigte informieren] |
| Begründung | [kurz] |
| Entschieden durch | [Name, Funktion] |
| Datum | [TT.MM.JJJJ] |
| Update eingespielt am | [TT.MM.JJJJ, Version laut Anzeige der Installation] |
