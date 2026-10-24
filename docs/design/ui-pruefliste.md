# UI-Prüfliste

Prüfliste für Entwürfe und Varianten einer Oberfläche, vor allem im UI-Lab
(`.claude/skills/ui-lab/SKILL.md`). Sie ergänzt die [Design-Guidelines](./guidelines.md) und die
[Barrierefreiheits-Richtlinie](./accessibility.md) um das, was dort nicht steht: Muster, an denen
man generiertes Design erkennt, Zählbudgets für Text und die Pflicht-Szenarien für große Mengen.
Was die Guidelines schon regeln, steht hier nur als Verweis. **Bei einem Widerspruch gilt die
Guideline.**

Die Nummern sind fest. Bewertungen, Berichte und Juroren zitieren sie („P13 verletzt“). Zu jedem
Punkt steht, wie er geprüft wird:

- **gemessen** – `e2e/ui-lab/capture.mjs` schreibt die Kennzahl je Screenshot in `metrics.jsonl`,
  `compare.py` fasst sie je Variante zusammen ([Werkzeuge](../../e2e/ui-lab/README.md)).
- **Sicht** – Prüfung an Screenshots und Videos.
- **Tastatur** – Durchgang nach [accessibility.md](./accessibility.md), 3.2.

## Zählbudgets

Messbare Grenzen statt weicher Textregeln. Ein Screenshot über Budget steht in `metrics.jsonl`
unter `budgetViolations`. Die Grenzwerte stehen zugleich in `BUDGETS` in
`e2e/ui-lab/lib/measure.mjs`; wer einen ändert, ändert beide Stellen.

| Budget | Grenze | Messung |
| --- | --- | --- |
| Hilfetexte je Formular | höchstens 3 | gemessen: `helpTextsPerForm`, sichtbare Hilfetexte ohne Feldfehler je `<form>` (oder Hauptbereich) |
| Zeilen je Hilfetext | höchstens 1 bei 1280 px | gemessen: `helpTextMaxLines`; bei 320 px darf er umbrechen |
| Wörter im Hauptbereich | höchstens 150 je Ansicht | gemessen: `uiWords` im Szenario `small` oder `default`; Text in Listen, Bäumen, Tabellen, Beschreibungslisten und an Auswahlkästchen zählt als Daten und nicht mit |
| Wörter je Label | 1–3 | Sicht (P12) |
| Wörter je Status | 1 | Sicht (P16) |
| Wörter je Schrittname | 1 | Sicht (P9) |
| Textzeilen je Listenzeile | höchstens 2 | Sicht (P6) |
| Einträge ohne Suchfeld | höchstens 6; ab 7 Einträgen Suche bzw. Filter beim Tippen | Sicht im Szenario `scale` (P20, P35) |
| Primärschaltflächen je Fläche | 1 | Sicht ([guidelines.md](./guidelines.md), 5.1) |

## Visuell

1. **Ein Signal je Element.** Ein Badge oder Chip trägt nicht zugleich Icon, Punkt, Rahmen,
   Tönung und Zusatztext. Chips selbst: [guidelines.md](./guidelines.md), 5.5. – Sicht
2. **Reiter** sind Text mit Unterstrich: keine Pillen, keine Icons, keine Zähler, kein
   „Neu“-Badge. – Sicht
3. **Keine Schmuck-Icons** vor Überschriften, Kacheltiteln oder Labels, wenn sie nichts
   unterscheiden. Icons der Kachelauswahl: [guidelines.md](./guidelines.md), 5.11. – Sicht
4. Keine getönten Flächen oder Verläufe als Schmuck: [guidelines.md](./guidelines.md), 1 und 4.3.
5. Akzentfarbe nur für Handlung und Zustand: [guidelines.md](./guidelines.md), 1 und 2.3.
6. **Listenzeile:** höchstens zwei Textzeilen, Hauptangabe und eine Nebenangabe, Navigation über
   Chevron oder die ganze Zeile ([guidelines.md](./guidelines.md), 5.3). Keine Avatare, IDs oder
   Fortschrittsbalken ohne Zweck. – Sicht
7. **Fehler am Feld:** Rahmen und eine kurze Zeile darunter ([guidelines.md](./guidelines.md), 5.2).
   Für Validierung kein Banner mit Icon, Titel, Fehlercode, Support-Link und Schließen-X. –
   gemessen (`alertCount` im Zustand `wizard-validation`) und Sicht
8. Bestätigungsdialog: [guidelines.md](./guidelines.md), 5.10. Darüber hinaus kein
   „Nicht mehr fragen“, kein „Später“, keine zusätzliche Warnbox. – Sicht
9. **Stepper:** Schrittnamen aus einem Wort, keine Unterzeilen, keine Restzeit- oder
   Prozentangaben. – Sicht
10. **Filter:** schlichte Chips, gewählt heißt gefüllt. Keine Leisten „Gespeicherte Filter“ oder
    „Alle zurücksetzen“ ohne Bedarf. Filterzeile für Assets: [guidelines.md](./guidelines.md),
    5.11. – Sicht
11. Hierarchie über Größe und Gewicht: [guidelines.md](./guidelines.md), 1 und 3. Messbar
    begrenzt durch `fontSizes` und `colors` (verschiedene Werte im Hauptbereich), im Vergleich
    zwischen Varianten. – gemessen

## Text

12. **Labels aus 1–3 Wörtern** („Adresse“, nicht „Bitte geben Sie hier die vollständige Adresse
    ein“). – Sicht
13. **Hilfetext nur, wenn er etwas Neues sagt**; er wiederholt nie Label oder Platzhalter. Budget
    siehe oben. – gemessen und Sicht
14. **Platzhalter sind Beispiel oder Format**, keine Anleitung. – Sicht
15. **Fehlermeldung:** was falsch ist und gegebenenfalls was zu tun ist, in höchstens einem Satz.
    Keine Entschuldigung, keine Schuldzuweisung („Die von Ihnen …“), kein Fehlercode für
    Nutzende. Ton: [guidelines.md](./guidelines.md), 5.7. – Sicht
16. **Status in einem Wort** („Aktiv“), nicht „Der Datensatz ist aktuell aktiv und erfolgreich
    verifiziert“. – Sicht
17. Schaltflächen tragen das Verb: [guidelines.md](./guidelines.md), 5.1 und 5.10.
18. **Keine Redundanz:** „Sind Sie sicher?“, „nicht rückgängig“ und „dauerhaft und unwiderruflich“
    werden ein Satz. – Sicht
19. **Begriffsregel:** eine Sache, ein Begriff, in allen Bereichen und für alle Arten gleich.
    Gleichartige Felder heißen überall gleich (Adresse, Kennung, Kennwort); der Begriff des
    Herstellers (App-Passwort, API-Token, Access Key) steht höchstens im Hilfetext. Maßgeblich
    sind [guidelines.md](./guidelines.md), 6 und das [Glossar](../handbuch/glossar.md); wo 6 einen
    Begriff festlegt, gilt 6. – Sicht über alle Varianten einer Art

## Große Mengen

Jede Variante wird mit den Mock-Szenarien geprüft (`?mockScenario=`, siehe
[build-und-ci.md](../entwicklung/build-und-ci.md), „Frontend mit Mocks“), nicht nur mit ein oder
zwei Einträgen.

20. **Viele Einträge** (Szenario `scale`: 17 Sites mit je 25 Bibliotheken, 450 Spaces, 200 Ordner
    je Ebene): ab 7 Einträgen Suche oder Filter beim Tippen; die Mehrfachauswahl bleibt sichtbar
    (Chips oder „12 ausgewählt“). – Sicht
21. **Hierarchie** (Site → Bibliothek → Ordner) ohne Kontextverlust, über Brotkrumen oder Baum;
    die Auswahl bleibt über Ebenen hinweg nachvollziehbar. – Sicht und Video
22. **Lange Namen** (80 und mehr Zeichen): gekürzt mit Tooltip, kein Layoutbruch. – gemessen
    (`clippedText`, `overflowX`) und Sicht
23. **Tiefe Verschachtelung** (8 Ebenen), **0 Treffer**, **langsames oder fehlgeschlagenes Laden**
    je Ebene (Szenarien `empty`, `slow`, `failed`, `partial`) zeigen Leer-, Lade- und
    Fehlerzustand nach [guidelines.md](./guidelines.md), 5.7. – Sicht
24. **„Alle auswählen“ und „Keine“**, wo Massenauswahl realistisch ist. – Sicht

## Allgemeine Qualität

25. Reflow bei 320 px und 400 % Zoom ohne waagerechtes Scrollen:
    [accessibility.md](./accessibility.md), 2.5. – gemessen (`overflowX` bei 320 px)
26. **Tastatur:** die ganze Strecke ohne Maus ([accessibility.md](./accessibility.md), 2.1). Nach
    „Weiter“ liegt der Fokus auf der Schrittüberschrift, und `document.title` nennt den Schritt. –
    gemessen (`tabStops`, `title`) und Tastatur
27. Fokus nicht verdeckt durch Sticky-Leiste oder Popup-Benachrichtigung (WCAG 2.4.11). –
    gemessen (`focusObscured`, `focusHidden`)
28. **Absenden bleibt aktiv:** kein `disabled` wegen fehlender Eingaben; ein Doppelklick legt
    nichts doppelt an. – gemessen (`disabledSubmits`) und Sicht
29. **Validierung beim Verlassen des Felds oder beim Absenden**, nicht beim Tippen;
    Fehlerzusammenfassung oben mit Sprunglinks, der Seitentitel beginnt dann mit „Fehler: “. –
    gemessen (`title`, `invalidFields`) und Tastatur
30. **Zurück verliert nichts**, weder im Assistenten noch über den Browser. – Video
31. **Ungespeicherte Änderungen:** Nachfrage nur, wenn tatsächlich etwas geändert wurde. – Video
32. **Prüfschritt** vor dem Anlegen, mit „Ändern“-Verweisen je Abschnitt. – Sicht
33. **Verbindungstest einheitlich:** automatisch bei „Weiter“ und über einen eigenen Knopf;
    Ergebnis inline, mit der Stufe (Adresse · Anmeldung · Rechte); es verfällt, sobald ein
    Verbindungsfeld geändert wird. – Sicht und Video
34. **Geheimnisse** erscheinen beim Bearbeiten als „Gesetzt“ mit „Ersetzen“
    ([guidelines.md](./guidelines.md), 5.2). – Sicht
35. **Auswahlmuster nach Art der Menge**, nicht nach fester Obergrenze:
    - *geschlossene Menge von Arten* (Quellart, Asset-Art): Kacheln nach
      [guidelines.md](./guidelines.md), 5.11; wächst sie über 12, gruppieren statt durchsuchen;
    - *offene Menge eigener Objekte* (Katalog, Zuordnung, Token): Asset-Kacheln mit Filterzeile
      nach 5.11; Stresstest mit 300 Assets;
    - *fremde Objekte aus einer Quelle* (Sites, Spaces, Buckets, Ordner): Liste oder Baum, Suche
      ab 7 Einträgen, keine Kacheln.

    – Sicht im Szenario `scale`
36. **Tri-State im Baum** (`aria-checked="mixed"`). – Tastatur oder Screenreader
37. **„Alle auswählen“ nennt den Umfang**; ein Filter löscht nie die Auswahl. – Sicht
38. **Drei Leerzustände** unterscheidbar: erstmals leer · 0 Treffer (mit „Filter zurücksetzen“)
    · kein Zugriff. – Sicht in `empty` und `failed`
39. **Kein Lade-Flackern** unter 300 ms; eine Aktion über 1 s zeigt Fortschritt im auslösenden
    Knopf. – Video im Szenario `slow`
40. **Indizierungsstatus** überall gleich: Zustand, „n von m“, letzter Lauf, „Teilweise
    fehlgeschlagen“. – Sicht
41. **Fehler, die Handeln verlangen, nie nur als Popup-Benachrichtigung**
    ([guidelines.md](./guidelines.md), 5.9). – Sicht in `failed`
42. **Nur-Lesen-Zustand** überall gleich dargestellt. – Sicht
43. **Deutsche Textlänge:** +40 % Textlänge und das 35-Zeichen-Kompositum
    „Grundstücksverkehrsgenehmigungsakte“ bei 320 px ohne Abschneiden. – gemessen
    (`clippedText` im Szenario `scale`)
44. **Automatik:** axe ohne Befund (WCAG 2.0–2.2 A/AA, `target-size` ab 24 px), Kontrast nach
    [guidelines.md](./guidelines.md), 2.4, in beiden Schemata; Screenshots hell und dunkel bei
    320 und 1280 px. – gemessen (`axe`; das dunkle Schema wird direkt geladen, siehe
    [README der Werkzeuge](../../e2e/ui-lab/README.md#kalibrierung))

## Pflege

Ein neuer Punkt bekommt die nächste freie Nummer; ein gestrichener behält seine Nummer mit dem
Vermerk „entfallen“, damit ältere Berichte lesbar bleiben. Wird eine Regel in die Guidelines
übernommen, schrumpft der Punkt hier zum Verweis.
