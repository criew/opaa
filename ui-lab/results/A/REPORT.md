# UI-Lab #2640 · Variante A „Schlicht wie Reranking“

**Idee (a):** Der Reiter „Texterkennung“ ist ein Formular wie „Reranking“ und „Spracheingabe“:
Schalter oben, Anbindungsart als zwei Radio-Optionen mit je einem Satz, darunter Adresse,
Modell-Kennung (nur Vision-Modell), Schlüssel als Kennwortfeld mit „Gespeicherten Schlüssel
entfernen“, Ergebnis des Verbindungstests als Alert, „Verbindung testen“ und „Speichern“.

**Idee (b):** Eine ruhige Zeile im Beleg „Per Texterkennung gelesen, Abweichungen möglich“ mit
Scanner-Symbol. Der Viewer zeichnet einen durchgehenden Rahmen (3 px, blue-700, leichte Tönung)
um die Fundstelle und sagt sichtbar „Fundstelle auf Seite 2 umrahmt. Text per Texterkennung
gelesen, Abweichungen vom Original möglich.“ Ohne Rahmen (Vision-Modell): Seitensprung mit
Hinweis.

## Prüfliste (Auszug, betroffene Punkte)

| Nr | Status | Beleg |
| --- | --- | --- |
| P1 Ein Signal je Element | ok | `drawer__beleg__default__light__1280.png`: Symbol + Satz, kein Chip |
| P7 Fehler am Feld | ok | `blocked__models__default__light__1280.png`: Zielprüfung als Feldfehler unter „Adresse“ |
| P12 Labels 1–3 Wörter | ok | „Anbindung“, „Adresse“, „Modell-Kennung“, „Schlüssel (optional)“ |
| P13 Hilfetexte ≤ 3, je 1 Zeile | ok | `metrics.jsonl`: `budgetViolations` leer |
| P16 Status in einem Wort | verfehlt | „Zustand: Eingeschaltet, der Dienst antwortet.“ (wie heute bei Reranking) |
| P28 Absenden bleibt aktiv | ok | `disabledSubmits` 0 |
| P33 Verbindungstest | teilweise | Ergebnis inline, verfällt bei Feldänderung; keine Stufe (Adresse · Anmeldung · Erkennung) |
| P34 Geheimnisse „Gesetzt · Ersetzen“ | verfehlt | Kennwortfeld wie bei Reranking |
| P44 axe/Kontrast | ok | 0 / 0 hell und dunkel |
| P25 Reflow 320 px | ok | `overflowX` 0 |

## Kennzahlen (compare.py)

| Kennzahl | Wert |
| --- | --- |
| Screenshots | 58 |
| axe-Knoten (hell) / Kontrast (beide Schemata) | 0 / 0 |
| Überlauf bei 320 px | 0 |
| Budgetüberschreitungen | 0 |
| Alerts gesamt | 24 (info 12, success 10, error 2) |
| Bedienwörter (Median, small) | 93 |
| Fokus verdeckt | 9, nur Shell-Elemente wie im Ist-Stand |

## Bekannte Lücken

- Wechselt die Breite bei offenem Viewer (Aufnahme 1280 → 320 px), springt pdf.js nicht erneut
  zum Rahmen; die 320er-Aufnahme zeigt Seite 1. Beim direkten Öffnen in 320 px tritt das nicht auf.
- Mehrseitige Fundstellen sind im Viewer umgesetzt (Rahmen je Seite), im Fixture aber nicht belegt.
- „Chunk anzeigen“ in der Diagnose ist nicht gestaltet.
