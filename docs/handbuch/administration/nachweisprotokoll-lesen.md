# Nachweisprotokoll lesen

**Ziel:** Belegt, wer wann Rechte vergeben, Einstellungen geändert oder Konten gesperrt hat.
{: .kopf }

**Wer:** Revision, über die Programmierschnittstelle
{: .kopf }

Das Nachweisprotokoll hält fest, **dass** jemand etwas getan hat: Rechte vergeben, Einstellungen
geändert, Konten gesperrt, Auskünfte abgerufen. Personen stehen darin als Pseudonym.

## Abfragen

Jede Abfrage braucht einen Zeitraum von **höchstens 92 Tagen** und einen Anlass, und sie steht selbst
im Protokoll. Vier Einstiege gibt es:

| Einstieg | Frage |
|---|---|
| nach Objekt | Was geschah mit dieser Bibliothek, diesem Space, dieser Gruppe? |
| nach Zeitraum | Was geschah in dieser Woche? |
| nach Ereignisart | Wann wurde die Revision vergeben? |
| nach Vorgang | Welche Einträge gehören zu dieser einen Handlung? |

**Nach einer Person lässt sich nicht suchen.** Auch ein Konto als Objekt wird abgewiesen.

## Eine anlassbezogene Klärung

Der eine Fall, in dem das Protokoll nach einer Person gefiltert werden darf, etwa bei einem
Sicherheitsvorfall oder einer externen Prüfung. Arbeits-, Disziplinar- und Leistungsfragen sind als
Zweck ausgeschlossen.

1. Eine Person der Revision beantragt: Person, Zeitraum, Zweck und Begründung. Der Zeitraum liegt
   ganz in der Vergangenheit und ist ab Werk höchstens 92 Tage lang.
2. Eine **zweite** Person der Revision gibt frei.
3. Erst dann liefert die Klärung die Einträge, ohne Kennung der Person.

Antrag und Freigabe stehen im Protokoll. Wie lange sie danach liegen bleiben, stellt die
Systemverwaltung unter [Datenschutz einstellen](datenschutz.md) ein.

**Gut zu wissen:**

- Ob das Protokoll eines Monats unverändert ist, prüft die Revision über die Prüfsummenkette
  ([Nachvollziehbarkeit](../betrieb/nachvollziehbarkeit.md#prüfsummenkette-und-monatssiegel)).
- Wer wann ein Objekt lesen durfte, sagt nicht das Protokoll, sondern die
  [Stichtagsauskunft](stichtagsauskunft.md).
- Die Aufbewahrung beträgt ab Werk 36 Monate, durch die Systemverwaltung einstellbar von 12 bis
  120 Monaten, heute nur per Import unter [Einstellungen übertragen](einstellungen-uebertragen.md).
