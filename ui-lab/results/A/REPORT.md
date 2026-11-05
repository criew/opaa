# UI-Lab Dienstkonten (#3589) · Variante A · Seitenleiste

Stufe „schnell“, Epic #3587. Prototyp: `?variant=a` (Prototyp-Leiste → Variante).

## Idee

Dienstkonten als weitere Liste (Tabelle) im Reiter „Externe KI-Werkzeuge“; ein Konto öffnet sich in der Seitenleiste wie die Einstellungen (`?account=<id>`). Bibliothek: Abschnitt „Fachverfahren mit Zugriff“ mit Textaktion „Aufnehmen“ → Dialog mit Radioliste (ab 8 mit Suche) und „Zugriff bis“.

## Prüfliste

| Nr | Status | Beleg |
|---|---|---|
| P5 Akzentfarbe nur für Handlung/Zustand | erfüllt | Sicht; Zustände als `StateDot` |
| P6 Listenzeile höchstens 2 Textzeilen | erfüllt (Tabelle, Kartenliste unter md) | Sicht `list__admin__default__light__1280.png` |
| P7 Fehler am Feld | erfüllt | `create-validation__admin__default__light__1280.png`, kein Banner |
| P8/5.10 Bestätigungen | erfüllt | Deaktivieren, Widerrufen, Entfernen mit `confirmAction`, Ton `danger` |
| P12 Labels 1–3 Wörter | erfüllt | „Name“, „Verantwortliche Person“, „Läuft ab“, „Zugriff bis“ |
| P13 Hilfetexte | erfüllt | gemessen: 0 Shots über Budget (je Formular, Zeilen) |
| P16 Status in einem Wort | erfüllt | „Aktiv“, „Deaktiviert“, „gültig“, „widerrufen“ |
| P17 Verb auf Schaltfläche | erfüllt | Anlegen, Ausstellen, Aufnehmen, Deaktivieren |
| P20/5.11 Auswahl Dienstkonto | erfüllt: Radioliste ≤ 7, darüber Suche + Radioliste | Szenario `scale` (60 Konten) |
| P22/P43 lange Namen | erfüllt | `clippedText`/`overflowX` 0 in `scale`, Kompositum bricht um |
| P23/P38 Leer/Fehler/Laden | erfüllt | `empty`, `failed`: Leerzustand mit Satz, Fehler mit „Erneut laden“ |
| P25 Reflow 320 px | erfüllt | `overflowX` 0 |
| P27 Fokus nicht verdeckt | geerbt | 6 verdeckte Tab-Stopps, alle „Zum Inhalt springen“/„Menü öffnen“ der App-Shell (auch im Ist) |
| P28 Absenden bleibt aktiv | erfüllt | `disabledSubmits` 0 |
| P29 Validierung beim Absenden | erfüllt | Fokus aufs erste fehlerhafte Feld |
| P34 Geheimnis einmal | erfüllt | Einmalanzeige im Dialog, schließt nicht über Escape |
| P44 axe/Kontrast | erfüllt | axe 0 Knoten hell, Kontrast 0 in hell und dunkel |
| P50 nichts doppelt | erfüllt | Sicht |
| P51 Dialog-Abbruchstelle | erfüllt | Formulardialoge „Abbrechen“, Ansichten ✕ |
| Wortbudget 150 | geerbt: 175 Wörter im Reiter (Ist 171 ohne Dienstkonten, schon über Budget); 8 Shots | gemessen |

## Kennzahlen (compare.py, Auszug; vollständig in `../metrics-summary.md`)

- Screenshots: 54 (hell 1280/320 über small, default, scale, empty, failed, partial; dunkel 1280/320 in default)
- axe-Knoten hell: 0 · Kontrastverstöße hell+dunkel: 0 · `overflowX` bei 320 px: 0
- Budget Hilfetexte: 0 · Wortbudget: geerbt: 175 Wörter im Reiter (Ist 171 ohne Dienstkonten, schon über Budget); 8 Shots

## Bekannte Lücken

- `token-value` im Szenario `small` vom Ablauf nicht erreicht (nur `default` aufgenommen); kein Darstellungsfehler.
- Datumsfelder zeigen im Headless-Chromium das US-Format (Gebietsschema des Browsers, auch im Ist).
- Das Thema `external-access` braucht für die Personensuche (`UserPicker`) die Kante zu `principal`; auf dem Lab-Branch in `eslint/themes.js` ergänzt, für die Umsetzung abzustimmen.
- Kein Konsistenz-Review (Stufe „schnell“).
- Die Zeile ist per Maus ganz klickbar, für Tastatur ist der Name der Link (eine Tab-Position).
