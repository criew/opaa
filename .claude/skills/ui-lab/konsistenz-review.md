# Konsistenz-Review

Gehört zum Skill [ui-lab](./SKILL.md), Abschnitte 4 und 6. Läuft nur für die Gewinnerin nach der
Teilmatrix, vor dem Halt beim Maintainer und vor den Videos. Die allgemeinen Regeln dahinter
stehen in der UI-Prüfliste, vor allem P13, P18, P19 und das Layout P45–P51; ein Befund nennt die
Nummer, wenn er eine verletzt. Die Muster unten stammen aus dem Review der Mischvariante F
im ersten UI-Lab (28 Befunde an rund 470 Bildern und 26 Querbögen); sie sind Beispiele dafür,
wonach zu suchen ist, keine abschließende Liste.

## Unterlagen

```bash
cd e2e
.venv/bin/python -I ui-lab/contact_sheets.py --dir <matrix> --out <review-dir>            # Viewport
.venv/bin/python -I ui-lab/contact_sheets.py --dir <matrix> --out <review-dir>/full --full # ganze Seiten
```

- `<review-dir>/sheets/`: Querbögen, je Bildschirm alle Arten nebeneinander.
- `<review-dir>/reviewers/<n>-<arten>.txt`: Bilderliste je Prüfer (je zwei Arten).

## Auftrag an einen Detailprüfer

Ein Agent je Bilderliste, alle parallel, mit frischem Kontext. Der Auftrag nennt:

- die Bilderliste und die Querbögen der Variante, dazu die Prüfliste
  `docs/design/ui-pruefliste.md`;
- die Kategorien A–F unten; jeder Befund steht in genau einer;
- je Befund: Kategorie, Kurztitel, Beleg als Dateiname (bei Inkonsistenzen beide Seiten),
  betroffene Arten oder „alle“, Vorschlag in einem Satz;
- Inkonsistenzen zwischen Arten erst in den Querbögen suchen, dann in den Einzelbildern belegen;
- nichts beheben, nur berichten.

## Kategorien

### A · Doppelungen

Dieselbe Information, Aktion oder Meldung zweimal auf einer Fläche.

- Kopfzeile und Abschnitt sagen dasselbe (Umfang oben in der Kopfzeile und im Reiter).
- Ein Fehler als Banner, als rote Zeile und am Feld; eine Fehlerzusammenfassung, die die
  Feldfehler wörtlich wiederholt.
- Eine Laufzeile mit zwei Zahlen für dasselbe („17 fehlgeschlagen“ und „17 nicht lesbar“).
- Laden und Leer gleichzeitig („wird geladen …“ neben „Keine Ordner ausgewählt“).
- Satz und Knopf sagen dasselbe („Bitte später erneut versuchen“ und „Erneut laden“).
- Überschrift wiederholt den Reiter oder Titel; Titel wiederholt einen Chip.
- Tri-State plus Wort „teilweise“; ein Grund in jeder Zeile statt einmal.
- Mehrere Bestätigungen für einen Abschnitt („Testen“, „Übernehmen“, „Weiter“).

### B · Inkonsistenzen

Dasselbe Ding in Assistent, Prüfen, Bearbeiten, Detail, zwischen Arten oder Szenarien anders
dargestellt.

- „Ändern“ einmal als Link, einmal als Knopf; verschiedene rechte Kanten.
- Derselbe Dialog-Öffner oder dieselbe Handeingabe mit verschiedenen Namen je Art oder Szenario.
- Ein Muster nur bei einem Teil der Arten (etwa „Alle / Nur ausgewählte“), andere Vorgaben.
- Zusammenfassungen und Status in mehreren Formen; Pfade mit verschiedenen Trennern.
- Knopfreihenfolge und Zahl der Primärknöpfe je Fläche verschieden.
- Inhaltsbreiten, Feldbreiten, Einrückungen und Kanten springen zwischen Ansichten.
- Überschriftenstufen und Trennlinien uneinheitlich.
- Fehlerformulierungen verschieden („… ist erforderlich“, „Bitte … eingeben“, „… fehlt“).
- Ein Wert fehlt in Prüfen oder Detail, der im Assistenten gesetzt wurde; leere Werte mal
  „keine Angabe“, mal „keine“.

### C · Kaputtes und Irreführendes

- Überlauf oder Abschneiden bei 320 px (Kopfaktionen, Titel, Reiter ohne Scrollhinweis).
- Widersprüchliche Zustände (Status „Abgeschlossen“ in Grün trotz Fehlern).
- Falsche Zahlen („1 ausgewählt“ ohne Haken, „Alle 20 auswählen“ bei 30 Zeilen).
- Leer- oder Fehlertext nennt die falsche Ebene; Suchfeld bei 0 Einträgen.
- Dialoge mit fester Höhe fast leer; Tooltips verdecken Folgezeilen.

### D · Hilfetexte, die weg müssen

Hilfetexte, die nichts Neues sagen, Rohdaten zeigen oder einen Zustand erklären, den die Oberfläche
zeigen sollte (Prüfliste P13 und Zählbudgets). Behalten wird, was sagt, wo man einen Wert findet.
Eine einzelne Auswahlkachel ohne Alternative wird zur Zeile.

### E · KI-Look-Reste

Verläufe, getönte Chips und Kacheln, Akzentfarbe ohne Handlung, getönte Banner (Prüfliste P1–P5).

### F · Mock- und Aufnahmefehler

Getrennt aufführen; sie zählen nicht als UI-Befund, werden aber vor den Videos behoben.

- Fixtures widersprechen sich (Zeitplan „06:00“, nächster Lauf „04:00“; Namen je Ansicht
  verschieden).
- Ein Szenario zeigt einen Zustand, den es nicht haben dürfte („slow“ mit Fehlermeldung).
- Bilder byte-gleich über Szenarien, die sich unterscheiden müssten.
- Aufnahme zeigt das Falsche (Overlay statt Dialog, abgeschnittene ganze Seite); dann
  `e2e/ui-lab/` korrigieren, nicht die Variante.

## REVIEW-n.md

`ui-lab/results/<X>/REVIEW-<n>.md`, `n` zählt die Runden:

```markdown
# Variante X – Konsistenz-Review n (<Datum>)

Grundlage: <Zahl> Einzelbilder (<Prüfer> Prüfer, je 2 Arten) + <Zahl> Querbögen.

## A · Doppelungen
1. **<Kurztitel>**: <Befund> (<Datei>, <Arten>). → <Vorschlag>.

## B · Inkonsistenzen
…
## F · Mock- und Aufnahmefehler
…
```

Nummern laufen über alle Kategorien durch, damit `REPORT.md` sie abhaken kann („R1-12 erledigt“,
„R1-15 bewusst nicht: …“). Der Nachcheck nach dem Neuaufnehmen prüft nur die Querbögen der
betroffenen Bildschirme gegen diese Liste; danach klickt der Maintainer den Prototyp durch (Skill,
Abschnitt 6), erst dann folgen die Videos.
