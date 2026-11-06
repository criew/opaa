# UI-Lab #2640 · Variante B „Zusammenfassung und Etikett“

**Idee (a):** Der Reiter zeigt eine Zusammenfassung im Zeilenraster des Reiters „Quelle“
(`ValueRow`): „Zustand“ (Punkt + ein Wort, Schalter rechts, schaltet sofort), „Anbindung“
(Art, Adresse, „Schlüssel gesetzt“) mit „Ändern“/„Einrichten“ und „Verbindung“ mit „Testen“.
Bearbeitet wird im Dialog „Anbindung der Texterkennung“ mit Auswahlliste, Adresse, Modell und
Schlüssel als „Gesetzt · Ersetzen · Entfernen“.

**Idee (b):** Ein umrandetes Etikett „Texterkennung“ (MetaBadge, Tooltip und `aria-label` mit dem
ganzen Satz) im Beleg und in der Werkzeugleiste des Viewers. Der Rahmen ist gestrichelt mit
gelber Markierungsfläche und einer Lasche „Fundstelle“, damit er im Scan auch ohne Farbe auffällt.

## Prüfliste (Auszug, betroffene Punkte)

| Nr | Status | Beleg |
| --- | --- | --- |
| P1 Ein Signal je Element | ok | Etikett nur mit Wort, Zustand als `StateDot` |
| P7 Fehler am Feld | ok | `blocked__models__default__light__1280.png`: Feldfehler im Dialog |
| P16 Status in einem Wort | ok | „Aktiv“, „Aus“, „Nicht erreichbar“ |
| P28 Absenden bleibt aktiv | ok | `disabledSubmits` 0 |
| P33 Verbindungstest | teilweise | Zeile „Verbindung“ mit Ergebnis, keine Stufen |
| P34 Geheimnisse | ok | „Gesetzt · Ersetzen“ |
| P45/P48 „Ändern“ als Textlink an rechter Kante | ok | `tab__models__default__light__1280.png` |
| P50 Information nicht doppelt | ok | Schalter ohne zweite Beschriftung neben „Aktiv“ (nachgebessert) |
| P44 axe/Kontrast | ok | 0 / 0 hell und dunkel |
| P25 Reflow 320 px | ok | `overflowX` 0 |

## Kennzahlen (compare.py)

| Kennzahl | Wert |
| --- | --- |
| Screenshots | 58 |
| axe-Knoten (hell) / Kontrast (beide Schemata) | 0 / 0 |
| Überlauf bei 320 px | 0 |
| Budgetüberschreitungen | 0 |
| Alerts gesamt | 14 (success 10, error 2, info 2) |
| Bedienwörter (Median, small) | 36 |
| Fokus verdeckt | 6, nur Shell-Elemente wie im Ist-Stand |

## Behobene Befunde aus dem Messlauf

- Doppelte Aussage „Aktiv“ + Schalterbeschriftung „Eingeschaltet“ (P50): Der Schalter trägt nur
  noch ein `aria-label`.

## Bekannte Lücken

- Der Schalter speichert sofort; ein Fehler (z. B. Betrieb lehnt ab) erscheint über der Liste.
  Abweichung vom Muster „Speichern“ der übrigen Rollen, siehe offene Frage F1.
- Breitenwechsel bei offenem Viewer: kein erneuter Sprung zum Rahmen (wie A).
