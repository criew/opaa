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
- **Sicht** – Prüfung an Screenshots, Querbögen und Videos, im UI-Lab im Konsistenz-Review
  (`.claude/skills/ui-lab/konsistenz-review.md`).
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
| Gesperrte Absende-Knöpfe („Weiter", „Anlegen", „Speichern" …) | 0 | gemessen: `disabledSubmits`, sichtbare Absende-Knöpfe mit `disabled` oder `aria-disabled` (P28) |
| Textzeilen je Listenzeile | höchstens 2 | Sicht (P6) |
| Fremde Objekte direkt in der Zeile | Schwelle nach [guidelines.md](./guidelines.md), 5.11 | Sicht in `small` und `scale` (P20, P35) |
| Schriftgrößen im Hauptbereich | höchstens 6 (die Stufen aus [guidelines.md](./guidelines.md), 3.2) | gemessen: `fontSizes`, verschiedene berechnete Größen sichtbaren Texts (P11) |
| Primärschaltflächen je Fläche | 1 | Sicht ([guidelines.md](./guidelines.md), 5.1) |

## Visuell

1. **Ein Signal je Element.** Ein Badge oder Chip trägt nicht zugleich Icon, Punkt, Rahmen,
   Tönung und Zusatztext. Chips selbst: [guidelines.md](./guidelines.md), 5.5. – Sicht
2. **Reiter** sind Text mit Unterstrich: keine Pillen, keine Icons, keine Zähler, kein
   „Neu“-Badge. Die Anzahl der Liste steht neben dem Seitentitel
   ([guidelines.md](./guidelines.md), 4.6). – Sicht; `AreaTabs` kennt keinen Zähler
   (Quelltext-Test)
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
9. **Stepper:** Schrittnamen und Schrittleiste: [guidelines.md](./guidelines.md), 5.15. Darüber
   hinaus keine Unterzeilen, keine Restzeit- oder Prozentangaben. – Sicht
10. **Filter:** schlichte Chips, gewählt heißt gefüllt. Keine Leisten „Gespeicherte Filter“ oder
    „Alle zurücksetzen“ ohne Bedarf. Ein Filter erscheint nur, wenn er kürzt
    ([guidelines.md](./guidelines.md), 5.3). Filterzeile für Assets: [guidelines.md](./guidelines.md),
    5.11. – Sicht
11. Hierarchie über Größe und Gewicht: [guidelines.md](./guidelines.md), 1 und 3. Höchstens die
    sechs Schriftstufen aus 3.2 im Hauptbereich (`fontSizes` ≤ 6, Zählbudget), dazu `colors` im
    Vergleich zwischen Varianten. – gemessen

## Text

12. **Labels aus 1–3 Wörtern** („Adresse“, nicht „Bitte geben Sie hier die vollständige Adresse
    ein“). – Sicht
13. **Hilfetext nur, wenn er einen wahrscheinlichen Fehler verhindert oder sagt, wo man einen Wert
    findet** („In Nextcloud unter Einstellungen › Sicherheit“). Dann höchstens eine Zeile, und er
    bleibt auch im Fehlerfall sichtbar. Er wiederholt nie Label oder Platzhalter; Selbstverständliches
    entfällt („In Confluence sieht man nur, was der Zugang sieht“, „Zugänge legt die
    Systemverwaltung an“). Budget siehe oben. – gemessen und Sicht
14. **Platzhalter sind Beispiel oder Format**, keine Anleitung. – Sicht
15. **Fehlermeldung:** was falsch ist und gegebenenfalls was zu tun ist, in höchstens einem Satz.
    Keine Entschuldigung, keine Schuldzuweisung („Die von Ihnen …“), kein Fehlercode für
    Nutzende. Ton: [guidelines.md](./guidelines.md), 5.7. – Sicht
16. **Status in einem Wort** („Aktiv“), nicht „Der Datensatz ist aktuell aktiv und erfolgreich
    verifiziert“. – Sicht
17. Schaltflächen tragen das Verb: [guidelines.md](./guidelines.md), 5.1 und 5.10.
18. **Keine Redundanz:** „Sind Sie sicher?“, „nicht rückgängig“ und „dauerhaft und unwiderruflich“
    werden ein Satz. Bestätigungen: Frage, Folge und Verb nach [guidelines.md](./guidelines.md),
    5.10 und Verbliste in 6; geprüft durch `confirmTexts.test.ts`. – Sicht
19. **Begriffsregel** (eigener Begriff oder Herstellerbegriff): [guidelines.md](./guidelines.md), 6,
    und das [Glossar](../handbuch/referenz/glossar.md). – Sicht über alle Arten einer Variante

## Große Mengen

Jede Variante wird mit den Mock-Szenarien geprüft (`?mockScenario=`, siehe
[build-und-ci.md](../entwicklung/build-und-ci.md), „Frontend mit Mocks“), nicht nur mit ein oder
zwei Einträgen.

20. **Viele Einträge** (Szenario `scale`: 17 Sites mit je 25 Bibliotheken, 450 Spaces, 200 Ordner
    je Ebene): Schwelle und Auswahldialog nach [guidelines.md](./guidelines.md), 5.11. Die
    Mehrfachauswahl bleibt sichtbar (Chips oder „12 ausgewählt“). – Sicht
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
28. Absenden bleibt aktiv, Fehler am Feld: [guidelines.md](./guidelines.md), 5.15; ein
    Doppelklick legt nichts doppelt an. – gemessen (`disabledSubmits`, Budget 0) und Sicht
29. **Validierung beim Verlassen des Felds oder beim Absenden**, nicht beim Tippen;
    Fehlerzusammenfassung oben mit Sprunglinks, der Seitentitel beginnt dann mit „Fehler: “.
    Ausnahme Assistenten: Fehler am Feld, Zusammenfassung nur im Schritt „Quelle“
    ([guidelines.md](./guidelines.md), 5.15). –
    gemessen (`title`, `invalidFields`) und Tastatur
30. **Zurück verliert nichts**, weder im Assistenten noch über den Browser. – Video
31. **Ungespeicherte Änderungen:** Nachfrage nur, wenn tatsächlich etwas geändert wurde. – Video
32. Prüfschritt vor dem Anlegen: [guidelines.md](./guidelines.md), 5.15. – Sicht
33. **Verbindungstest einheitlich:** automatisch bei „Weiter“ und über einen eigenen Knopf;
    Ergebnis inline, mit der Stufe (Adresse · Anmeldung · Rechte); es verfällt, sobald ein
    Verbindungsfeld geändert wird. – Sicht und Video
34. **Geheimnisse** erscheinen beim Bearbeiten als „Gesetzt“ mit „Ersetzen“
    ([guidelines.md](./guidelines.md), 5.2). – Sicht
35. **Auswahlmuster nach Art der Menge** (Mengenarten, Gruppierung, Schwelle):
    [guidelines.md](./guidelines.md), 5.11. Stresstest mit 300 Assets. – Sicht im Szenario `scale`
36. **Tri-State im Baum** (`aria-checked="mixed"`). – Tastatur oder Screenreader
37. **„Alle auswählen“ nennt den Umfang**; ein Filter löscht nie die Auswahl. – Sicht
38. **Leerzustände und Ladefehler:** [guidelines.md](./guidelines.md), 5.7, Regeln 2–7. – Sicht
    in `empty` und `failed`
39. **Laden:** [guidelines.md](./guidelines.md), 5.7, Regel 1. – Video im Szenario `slow`
40. **Indizierungsstatus** überall gleich: Zustand, „n von m“, letzter Lauf, „Teilweise
    fehlgeschlagen“. – Sicht
41. Popup-Benachrichtigungen: [guidelines.md](./guidelines.md), 5.9. – Sicht in `failed`
42. **Nur-Lesen-Zustand** überall gleich dargestellt. – Sicht
43. **Deutsche Textlänge:** +40 % Textlänge und das 35-Zeichen-Kompositum
    „Grundstücksverkehrsgenehmigungsakte“ bei 320 px ohne Abschneiden. – gemessen
    (`clippedText` im Szenario `scale`)
44. **Automatik:** axe ohne Befund gegen das Zielniveau nach [accessibility.md](./accessibility.md),
    1, Kontrast nach [guidelines.md](./guidelines.md), 2.4, in beiden Schemata; Screenshots hell und dunkel bei
    320 und 1280 px. – gemessen (`axe`; das dunkle Schema wird direkt geladen, siehe
    [README der Werkzeuge](../../e2e/ui-lab/README.md#kalibrierung))

## Layout

45. **„Ändern“ nur als Textlink** an einer gemeinsamen rechten Kante, nie als Knopf. – Sicht
    (Querbögen)
46. **Eine Inhaltsbreite** für Reiterleiste, Zeilen und Fußleiste; sie springt nicht zwischen
    Assistent, Prüfen, Bearbeiten und Detail. Es gibt drei Breiten je Seitentyp (720, 880,
    1180 px), Erklärtexte in Lesebreite, und jede Bereichsseite beginnt mit demselben Kopf aus
    Titel, Anzahl und Anlege-Knopf; ein Plus-Icon steht nur dort, in der Seitenleiste und im
    Leerzustand ([guidelines.md](./guidelines.md), 4.6 und 5.1). – Sicht (Querbögen) und
    Quelltext-Test (`pageGrid.test.tsx`: keine `maxWidth`-Zahl unter `features/`, Plus-Icon
    nur aus `CreateButton`)
47. **Keine doppelten Trennlinien:** Die Unterkante der Reiterleiste ist der erste Trenner; danach
    je ein Trenner zwischen den Abschnitten und einer vor den Knöpfen. – Sicht
48. **Zeilenraster** Titel | Inhalt | „Ändern“ für zusammengefasste Abschnitte, gleich in
    Assistent, Prüfen und Detail. – Sicht (Querbögen)
49. Gleichartige Wahl ohne unterscheidendes Icon: [guidelines.md](./guidelines.md), 5.11. – Sicht
50. **Dieselbe Information nie zweimal auf einer Fläche** – weder als Kopfzeile und Abschnitt noch
    als Banner, Zeile und Feldfehler noch als Überschrift, die den Reiter wiederholt; im Chat weder
    der KI-Hinweis noch der Hinweis auf vorläufige Belege ([guidelines.md](./guidelines.md), 5.8). –
    Sicht (Konsistenz-Review, Kategorie A)
51. **Jeder Dialog hat eine sichtbare Schließen- oder Abbruchstelle:** ✕ im Titel oder
    „Abbrechen“, nicht beides ([guidelines.md](./guidelines.md), 5.6). – Sicht und Tastatur
52. **Dialoge aus den Bausteinen** ([guidelines.md](./guidelines.md), 5.6): Titel wörtlich wie der
    öffnende Knopf, beim Bearbeiten „„<Name>“ bearbeiten“; die Handlung als Verb allein; links
    höchstens eine Nebenhandlung, rechts „Abbrechen“ und die Handlung; Breite 600 oder 900 px,
    444 px nur für die Bestätigung, unter 600 px Bildschirmbreite bildschirmfüllend; keine
    Trennlinien an Titel und Fußzeile; ein Seitenpanel nur, wenn die Liste dahinter gebraucht
    wird. – Sicht; ESLint verbietet `Dialog` und `Drawer` außerhalb von `shared/ui`
53. **Nichts rutscht, wenn ein Vorgang endet:** Kopf und Rahmen einer Fläche stehen vom ersten
    Bild an, ein Titel, der später feststeht, hat bis dahin einen Platzhalter („Neuer Chat“). Was
    schon steht, behält seine Position, wenn eine Antwort, ein Titel oder ein Ergebnis eintrifft
    ([guidelines.md](./guidelines.md), 5.8). – Video im Szenario `slow`

52. **Formularfelder aus einem Baustein:** Beschriftung oberhalb, kein schwebendes Label, kein
    Sternchen, „(optional)“ an optionalen Feldern, Hilfetext bündig mit der Feldkante;
    Such- und Filterfelder ohne sichtbare Beschriftung; Einstellungsformulare einspaltig in
    640 px, Zahlenfelder 160 px, mehrzeilige Felder ab drei Zeilen
    ([guidelines.md](./guidelines.md), 5.2). – Sicht (Querbögen); im Code geprüft durch
    `FormField.test.tsx` und `fieldHeight.test.tsx`
54. **Seiten vor der Anmeldung wie die Anwendung:** Bausteine `FormField`, `SecretField`,
    `TextLink`, `SectionHead`; Rückweg überall „Zur Anmeldung" als Textlink unter dem Formular;
    Abschnittsköpfe grau, auch im Installationsassistenten; die Primärschaltfläche trägt das Verb
    der Seite ([guidelines.md](./guidelines.md), 5.1 und 5.12). – gemessen wie jede Seite
    (`capture.mjs --flow pages --profile signed-out`) und Sicht; im Code geprüft durch
    `preSignInRules.test.ts`
55. **Einrichten und Verwalten aus einem Feldblock:** Was der Installationsassistent und die
    Verwaltung beide abfragen, zeigt dieselben Felder in derselben Reihenfolge mit denselben
    Beschriftungen, Hilfetexten und Bedienelementen; der Schritt heißt wie der Reiter; Geheimnisse
    stehen im `SecretField` mit „Gesetzt“ und „Ersetzen“, Löschen ist eine stille Textaktion am
    Feld; im Assistenten ist keine Schaltfläche gesperrt, die Fußzeile steht rechts, die
    Schrittliste zeigt unter 900 px nur „Schritt 2 von 12 · Erstes Konto“, die Einleitung hat
    höchstens zwei Sätze ([guidelines.md](./guidelines.md), 5.16). – Sicht (Schritt und
    Einstellungsseite nebeneinander, `?mockInstallation=pending`); im Code geprüft durch
    `setupRules.test.ts` und `fieldBlocks.test.tsx`

## Pflege

Ein neuer Punkt bekommt die nächste freie Nummer; ein gestrichener behält seine Nummer mit dem
Vermerk „entfallen“, damit ältere Berichte lesbar bleiben. Wird eine Regel in die Guidelines
übernommen, schrumpft der Punkt hier zum Verweis.
