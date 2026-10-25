# Jury Detailansicht – Juror „skalierung“

Blickwinkel: große Mengen (`scale`), Fehler und Teilfehler (`failed`, `partial`), laufende Läufe (`slow`), leere Zustände, 320 px, Leser-Rolle. Leitfrage: Findet man bei Problemen schnell Ursache und Handlung?

Stichprobe (nur hell, je 1280 und 320, Ist/A/B/C nebeneinander; Vergleichsbogen unter `juroren-detail/skalierung/*.png`):
`dokumente__confluence__failed`, `quelle__confluence__failed`, `quelle__sharepoint__partial`, `quelle__filesystem__partial`, `laeufe__confluence__scale__…__full`, `dokumente__s3__slow`, `laeufe__s3__slow`, `dokumente__upload__failed` (1280 + 320), `upload__upload__partial`, `freigaben__sharepoint__scale`, `freigaben__confluence__scale__…__320`, `dokumente__confluence-leser__failed`, `quelle__confluence-leser__failed__…__320`, `dokumente__privat__scale`, `dokumente__confluence__empty`, C `abschluss__confluence__failed` (1280 + 320). Videos: `A__laeufe__fehler`, `B__laeufe__fehler`, `C__quelle__fehler` (Einzelbilder alle 5 s). Kennzahlen aus `metrics*.jsonl` (tabsTop, axe, docHeight) selbst ausgewertet.

## Punkte

| Variante | Punkte |
|---|---|
| Ist | **28** |
| A | **84** |
| B | **70** |
| C | **68** |

### Ist – 28
Im Ernstfall sagt der Kopf nur „Letzter Lauf: Fehlgeschlagen · Dokumente: 0 verarbeitet“. Die Ursache (Anmeldung abgelehnt) steht nirgends, die Zeile Verbindung zeigt sogar „nicht geprüft“ (`quelle__confluence__failed__light__1280`). Bei `scale` ist ein aufgeklappter Lauf eine ungruppierte Ereignisliste mit bis zu 24 492 px Seitenhöhe (`laeufe__*__scale__light__1280__full`). Ein Teilfehler steht als Banner mit Randbalken zwischen Kopf und Reitern (`quelle__sharepoint__partial`). Bei 320 px liegt die Reiterleiste unter 4–6 Kennzahlzeilen und einem Leser-Alert (`dokumente__confluence__failed__light__320`). Eine Upload-Ablehnung erscheint als roter Alert mit Endungsliste (`upload__upload__partial`).

### A – 84
Ursache und Handlung sind am klarsten verkettet. Der Kopf meldet „● Anmeldung abgelehnt · 23.10.2026“ als Link, die Verbindung zeigt „Anmeldung abgelehnt am 23.10.2026 · Ändern“, und in der Laufliste bleibt der letzte Lauf mit Daten („Teilweise fehlgeschlagen · 12 Dokumente · 5 fehlgeschlagen“) unter den Ablehnungen sichtbar (`quelle__confluence__failed__light__1280`, Video `A__laeufe__fehler`). Bei `scale` macht A aus 45 Freigaben eine Suche mit dem Umschalter Alle/Gruppen/Personen (`freigaben__sharepoint__scale__light__1280`). Ein aufgeklappter Lauf ist dort 745 px hoch, gruppiert und mit „alle n anzeigen“. Teilfehler stehen am betroffenen Eintrag mit Namen, auch bei Dateisystem („Gebührensatzungen ● nicht lesbar“, `quelle__filesystem__partial__light__1280`). Upload zeigt Zahlen („Läuft · 214 von 216 · 17 Fehler“) und die Ablehnung an Ort und Stelle (`upload__upload__partial__light__1280`). Messbar: tabsTop 1280 immer 212 px, 320 px 331/350 px, axe 0, keine Budgetverletzung. Abzug für 320 px: Drei fest reservierte Titelzeilen lassen bei kurzen Namen rund 40 px Lücke zwischen Titel und Beschreibung, das wirkt wie ein Fehler (`dokumente__upload__failed__light__320`, `quelle__confluence-leser__failed__light__320`). Bei 1280 px wird der Titel in `scale` auf eine Zeile gekürzt.

### B – 70
Der Reiter Verlauf ist im Fehlerfall stark. Der Filter „Nur mit Fehlern (3)“ steht über der Liste, ein abgelehnter Lauf führt aufgeklappt direkt zu „Verbindung ändern ›“ (Video `B__laeufe__fehler`), und das Band ist auf jedem Reiter gleich lesbar (`dokumente__confluence__failed__light__1280`). Bei 320 px trägt die Variante aber am meisten über den Reitern: Das Band steht in drei Zeilen, die Reiterleiste bricht in zwei Zeilen um. Im Großteil der 320-px-Bilder liegt die Reiterleiste bei 344 px, in `scale` bei 391 px, also über dem Budget von 360 px (`freigaben__confluence__scale__light__320`, gekürzter Titel „…und…“). Der REPORT nennt 296 px, das stimmt nur für die nachaufgenommene Teilmatrix aus Runde 4 (`metrics-r4*.jsonl`); die übrigen rund 700 Bilder stammen aus Runde 3. Beim Upload-Teilfehler steht „Teilweise fehlgeschlagen“ ohne Zahl, und die abgelehnte Datei ist nicht zu sehen (`upload__upload__partial__light__1280`). Das Feld Umfang kürzt bei `scale` hart („25 Dokumentbibliotheken, davon 100 Or…“).

### C – 68
Bei 320 px ist C am kompaktesten: eine Zeile „Steckbrief ● Anmeldung abgelehnt ⌄“ (`dokumente__confluence__failed__light__320`). Nach dem Anlegen erscheint im Fehlerfall als erster Schritt „Zugang prüfen“ (`abschluss__confluence__failed__light__1280`), und Upload bietet „Abgelehnte Dateien anzeigen“ (`upload__upload__partial__light__1280`). Bei Teilfehlern verliert C Information: Bei Dateisystem fehlt jede Markierung „nicht lesbar“ im Umfang und im Lauf (`quelle__filesystem__partial__light__1280`), bei SharePoint steht „● nicht lesbar“ auf einer eigenen Zeile ohne Bezug zum Eintrag. Abgelehnte Läufe zeigen nur „abgebrochen“ und lassen sich nicht aufklappen (`quelle__confluence__failed`). Bei `scale` stehen 45-mal ein lautes „Entziehen“ und „Mitglieder anzeigen“ untereinander, ab 50 Einträgen wird nicht gruppiert (`freigaben__sharepoint__scale__light__1280`). Upload-Zahlen heißen „mind. 13 fehlgeschlagen“. Bei 320 px zeigt die Reiterleiste Wortbruchstücke („‹ ε“, „M› “), und der Abschluss schiebt die Reiter auf 380–462 px (14 Budgetverletzungen laut eigenem REPORT).

## Stärken und Schwächen

**Ist**
- \+ Alle Läufe und Ereignisse sind vollständig da, nichts ist versteckt.
- \+ Laufender Fortschritt steht als Zahl („831 von 1340“).
- \+ Upload-Ablehnung nennt den Grund.
- − Ursache „Anmeldung abgelehnt“ fehlt, Verbindung zeigt „nicht geprüft“.
- − `scale`-Lauf ungruppiert, 24 000 px Seite.
- − 320 px: Kennzahlen und Alerts drücken die Reiter weit nach unten.

**A**
- \+ Kette Zustandswort → Zeile Verbindung „Ändern“ → Laufliste mit letztem Datenlauf.
- \+ Skaliert sichtbar: Freigaben Alle/Gruppen/Personen, Lauf 745 px, „alle n anzeigen“ mit Suche.
- \+ Zahlen statt Vagem, auch bei Upload („214 von 216 · 17 Fehler“); axe 0, tabsTop konstant.
- − 320 px: reservierte Titelzeilen hinterlassen sichtbare Lücken.
- − Titel bei 1280 einzeilig gekürzt (Tooltip), langer Name schlecht erkennbar.
- − Zustandswort am Ende einer langen Kennzahlzeile; bei vielen Kennzahlen (privat) hängt es an der Kante.

**B**
- \+ „Nur mit Fehlern (n)“ über einer Laufliste von 10.
- \+ Abgelehnter Lauf führt aufgeklappt direkt zu „Verbindung ändern ›“.
- \+ Band auf jedem Reiter gleich, Umfang-Teilfehler als Kurzform sichtbar.
- − 320 px: Band dreizeilig, Reiter zweizeilig, tabsTop 344 und in `scale` 391 (> 360).
- − Upload-Teilfehler ohne Zahl und ohne sichtbare abgelehnte Datei.
- − Kurzformen im Band kürzen bei `scale` mitten im Wort.

**C**
- \+ 320 px: Zustand in einer einklappbaren Zeile, Reiter früh erreichbar.
- \+ Abschluss setzt bei Fehlern „Zugang prüfen“ an die erste Stelle.
- \+ Upload: „Abgelehnte Dateien anzeigen“ als feste Anlaufstelle.
- − Teilfehler gehen verloren oder hängen ohne Bezug (Dateisystem, SharePoint).
- − Abgelehnte Läufe zeigen nur „abgebrochen“, kein Detail und keine Handlung.
- − `scale`: 45-mal „Entziehen“, keine Gruppierung; 320-px-Reiterleiste mit Bruchstücken; Abschluss über dem Budget.

## Was in eine Mischung gehört

- **Aus A (Grundgerüst):**
  - Zustandswort im Kopf als Link zur Ursache
  - Verbindung mit „abgelehnt am … · Ändern“
  - Laufliste, die neben den Ablehnungen den letzten Lauf mit Daten zeigt
  - Markierung „nicht lesbar“ am Eintrag im Umfang, für alle Quellarten
  - Freigaben mit Suche und Umschalter Alle/Gruppen/Personen
  - Upload-Zustand mit Zahlen
  - Ablehnungsmeldung an der Liste
- **Aus B:**
  - Filter „Nur mit Fehlern (n)“ über der Laufliste, ohne eigenen Reiter
  - im aufgeklappten abgelehnten Lauf ein Link „Verbindung ändern ›“
  - im Leerzustand die eine Handlung „Jetzt indizieren“ inline
- **Aus C:**
  - bei 320 px statt reservierter Titelzeilen eine kompakte Zustandszeile (Punkt + Wort + ⌄); Vorbild ist die Steckbrief-Zeile, ohne die rechte Spalte
  - nach dem Anlegen bei Fehlern „Zugang prüfen“ als erster Schritt
  - beim Upload eine feste Stelle „Abgelehnte Dateien anzeigen“, die nach dem Schließen der Meldung bleibt
- **Nicht übernehmen:**
  - Bands Kurzformen, die mitten im Wort kürzen (B)
  - zweizeilige Reiterleiste (B)
  - „abgebrochen“ ohne Grund (C)
  - Textaktion „Entziehen“ je Zeile (C)
  - Zählungen mit „mind.“ (C)

## Empfehlung

**A als Grundgerüst.** Aus meinem Blickwinkel kommt man dort am schnellsten von „etwas stimmt nicht“ zu Ursache und Handlung. A skaliert bei 45 Freigaben, 120 Spaces und 500 Ereignissen messbar im Budget und verliert keinen Teilfehler. Vor der Umsetzung nachbessern: Die 320-px-Titellücke durch eine flexible Titelhöhe oder Cs kompakte Zustandszeile ersetzen, und den Fehlerfilter sowie den Handlungslink im Laufdetail aus B übernehmen.
