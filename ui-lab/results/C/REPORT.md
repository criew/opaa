# UI-Lab #2640 · Variante C „Geführt mit Vergleich“

**Idee (a):** Der Reiter führt in Reihenfolge: zuerst die Anbindungsart als zwei Kacheln
(`ChoiceTileGroup`, docling-serve · Vision-Modell), dann „Verbindung“ mit Adresse, Modell und
Schlüssel („Gesetzt · Ersetzen“), „Verbindung testen“ mit gestuftem Ergebnis
„Adresse · Anmeldung · Erkennung“ (Punkt je Stufe), zuletzt Schalter und „Speichern“.

**Idee (b):** Der Beleg zeigt den erkannten Text der Fundstelle selbst als Zitat, darunter
„Per Texterkennung gelesen, im Original prüfen“. Der Viewer dunkelt die Seite um den Rahmen ab
(Spotlight) und zeigt rechts (bei 320 px darunter) „Erkannter Text“ mit Seite, Abschnitt, Zitat
und dem Satz „Vergleichen Sie Beträge und Paragrafen mit dem Scan.“ So prüft man Lesefehler
direkt am Original.

## Prüfliste (Auszug, betroffene Punkte)

| Nr | Status | Beleg |
| --- | --- | --- |
| P1 Ein Signal je Element | ok | Kacheln nach 5.11 mit Symbol, Stufen als `StateDot` |
| P7 Fehler am Feld | ok | `blocked__models__default__light__1280.png` |
| P16 Status in einem Wort | verfehlt | Zustandszeile als Satz wie in A |
| P28 Absenden bleibt aktiv | ok | `disabledSubmits` 0 |
| P33 Verbindungstest mit Stufe | ok | `test__models__failed__light__1280.png`: Anmeldung fehlgeschlagen |
| P34 Geheimnisse | ok | „Gesetzt · Ersetzen“ |
| P49 Gleichartige Wahl ohne unterscheidendes Icon | fraglich | Symbole unterscheiden die Art, zwei Optionen wären auch als Radio klar |
| Guideline 5.8 Belegfenster nur Titel + Fundort + Aktion | verfehlt | Zitat im Beleg, siehe offene Frage F3 |
| P44 axe/Kontrast | ok | 0 / 0 hell und dunkel |
| P25 Reflow 320 px | ok | `overflowX` 0, nach Nachbesserung |

## Kennzahlen (compare.py)

| Kennzahl | Wert |
| --- | --- |
| Screenshots | 58 |
| axe-Knoten (hell) / Kontrast (beide Schemata) | 0 / 0 |
| Überlauf bei 320 px | 0 |
| Budgetüberschreitungen | 0 |
| Alerts gesamt | 2 (info 2) |
| Bedienwörter (Median, small) | 108 |
| Fokus verdeckt | 16, nur Shell-Elemente wie im Ist-Stand |

## Behobene Befunde aus dem Messlauf

- Viewer bei 320 px: Die Seitenleiste „Erkannter Text“ verdrängte den Scan (erster Lauf). Jetzt
  steht sie unter dem Scan, der Scan hat eine Mindesthöhe.

## Bekannte Lücken

- Breitenwechsel bei offenem Viewer: kein erneuter Sprung zum Rahmen (wie A).
- Das Spotlight dunkelt nur die Seite mit dem Rahmen ab.
