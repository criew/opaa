---
name: ui-lab
description: Neugestaltung einer Oberfläche als UI-Lab in zwei Stufen – „schnell“ (ein Dialog oder eine Seite, eine Session, 3–5 Varianten als klickbarer Prototyp, Messlauf, ein Juror) und „groß“ (ein ganzer Bereich wie Spaces oder Administration – Inventur, Prüfliste, Skizzenrunde, Varianten als Prototyp in parallelen Cloud-Sessions, drei Juroren, Gewinnerin mit Feinschliff, Konsistenz-Review und Videos, Vergleichsseite). Einsetzen, wenn mehrere Entwürfe gegeneinander abgewogen werden sollen, bevor einer umgesetzt wird.
---

# UI-Lab

Ziel: in Stunden, nicht in Tagen, von „dieser Bereich wirkt unruhig“ zu einer begründeten
Entscheidung des Maintainers mit messbaren Belegen. Ergebnis ist ein veröffentlichter klickbarer
Prototyp (Stufe „groß“: dazu eine Vergleichsseite) und eine freigegebene Richtung; die Umsetzung
läuft danach als eigenes Issue. Entschieden wird am Prototyp: Durchklicken geht schneller als jede
Screenshot-Matrix, von der erfahrungsgemäß nur rund zehn Bilder angesehen werden.

Werkzeuge und Maßstab:

- **Messskript, Videos, Vergleich:** [`e2e/ui-lab/`](../../../e2e/ui-lab/README.md) – `capture.mjs`,
  `record-flows.mjs`, `calibrate.mjs`, `compare.py`, die Querbögen `contact_sheets.py` und der
  Generator der Vergleichsseite `build_site.py`; alle Befehle aus `e2e/`
- **Mock-Szenarien:** `?mockScenario=default|small|scale|empty|slow|failed|partial`
  (`frontend/src/shared/mocks/mockScenario.ts`, Tabelle im README der Werkzeuge)
- **Prüfliste:** [`docs/design/ui-pruefliste.md`](../../../docs/design/ui-pruefliste.md) mit den
  Zählbudgets; darüber [`guidelines.md`](../../../docs/design/guidelines.md) und
  [`accessibility.md`](../../../docs/design/accessibility.md)
- **Klickbarer Prototyp:** `build-prototype.mjs`, Varianten umschaltbar mit `?variant=<id>` in
  der Prototyp-Leiste (README der Werkzeuge, „Klickbarer Prototyp“)

## Zwei Stufen

| | **schnell** (Ziel: rund 1 h) | **groß** |
| --- | --- | --- |
| Anlass | ein Dialog oder eine Seite („mach mir diesen Dialog schön, gib mir ein paar Vorschläge“), auch wenn es nur wenig zu verbessern gibt | ein ganzer Bereich mit mehreren Arten, Szenarien und Abläufen |
| Sessions | eine | eine je Variante (Abschnitt 4) |
| Varianten | 3–5 in einem Branch, registriert in `UI_LAB_VARIANTS` | grob, je eine als Prototyp |
| Prüfung | Messlauf (Grundregel 7) | Messlauf; Konsistenz-Review nur für die Gewinnerin |
| Juroren | ein Durchgang: ein Juror mit allen drei Blickwinkeln aus Abschnitt 5 | drei (Abschnitt 5) |
| Videos | keine | nur für die Gewinnerin (Grundregel 4) |
| Ergebnis | veröffentlichter Prototyp-Link mit kurzer Bewertung je Variante | Prototyp-Link und Vergleichsseite (Abschnitt 7) |

**Ablauf „schnell“:** Ist-Stand und Szenarien prüfen, Mock-Lücken schließen (Abschnitt 1, Punkt 1)
→ 3–5 Varianten bauen → Messlauf je Variante, Befunde beheben → Juror → Prototyp veröffentlichen
(Abschnitt 7) → **Halt: Der Maintainer klickt durch und entscheidet.** Inventur-Dokument,
Prüflisten-Freigabe, Skizzenrunde, Querbögen und Vergleichsseite entfallen.

Der Rest dieses Skills beschreibt die Stufe „groß“; die Grundregeln gelten für beide.

## Grundregeln

Diese Regeln sind Lehren aus dem ersten UI-Lab. Sie gelten in jedem Schritt.

1. **Von Anfang an Cloud-Sessions.** Varianten laufen in eigenen Sessions (`create_session`), jede
   mit eigenem Branch, Dev-Server und Port. Lokale Parallelität in einem Arbeitsverzeichnis
   blockiert sich gegenseitig und kostet mehr, als sie spart.
2. **Die Prüfliste vollständig in der ersten Runde.** Alle bereichsspezifischen Ergänzungen
   kommen auf einmal zur Freigabe, nicht scheibchenweise nach jeder Variante. Was später
   auffällt, wird ein neuer Punkt für das nächste Lab, keine neue Runde für dieses.
3. **Zweistufig: erst Richtung, dann Breite.** Die Skizzenrunde entscheidet an drei typischen
   Fällen über die Richtung. Erst danach werden alle Arten und Szenarien als Prototyp gebaut und
   gemessen; Feinschliff bekommt nur die Gewinnerin.
4. **Videos nur für die Gewinnerin, nach dem Durchklicken.** Videos zeigen Übergänge, Flackern
   und Kontextverlust, sind aber teuer. Sie entstehen erst, wenn die Gewinnerin das
   Konsistenz-Review bestanden und der Maintainer ihren Prototyp durchgeklickt hat; sonst zeigen
   sie Fehler, die ein Klick gefunden hätte. Je Szenario **ein** Video über alle Reiter mit echten
   Aktionen (wählen, speichern, Fehler auslösen), ohne Wiederholungen desselben Wegs.
5. **Zählbudgets statt weicher Textregeln.** „Hilfetexte sparsam“ wird „höchstens 3 je
   Formular, je eine Zeile“. Eine Regel, die sich nicht zählen lässt, wird zur Sichtprüfung mit
   Beispiel, nicht zum Appell.
6. **Fragen an den Maintainer immer mit Beispiel und Folge.** Jede Frage nennt die Optionen, je
   Option ein konkretes Beispiel aus dem Bereich (Screenshot oder Wortlaut) und die Folge für
   Umsetzung und andere Bereiche, dazu eine Empfehlung. Fragen werden gebündelt, nicht einzeln
   gestellt:

   ```markdown
   **F3 · Zeitplan schon im Assistenten oder erst nach dem Anlegen?**
   - A: im Assistenten – Beispiel: Zeile „Zeitplan · Täglich 06:00 · Ändern“ vor „Prüfen“
     (Bild wizard-filled__nextcloud__small). Folge: ein Schritt mehr; gilt für alle Arten.
   - B: nach dem Anlegen – Beispiel: Detail, Reiter „Quelle“, Zeile „Zeitplan · Aus“.
     Folge: die erste Indizierung läuft ohne Zeitplan; Prüflistenpunkt P32 bleibt unberührt.
   Empfehlung: A, weil …
   ```

7. **Messung vor Meinung, Messlauf statt Bild-Review.** Ein Urteil nennt Prüflistennummer und
   Beleg (Zeile aus `metrics.jsonl`, Screenshot-Datei, Videozeitpunkt). Der **Messlauf** ist
   `capture.mjs` über die Szenarien des Bereichs, hell bei 1280 und 320 px, dunkel als Stichprobe
   (ein Szenario); gelesen werden axe, Kontrast, `overflowX` und `budgetViolations` aus
   `metrics-summary.md` (`compare.py`), nicht die Bilder. Vor dem ersten Lauf
   `cd e2e && node ui-lab/calibrate.mjs` ausführen; schlägt sie fehl, wird nichts verglichen.
8. **Der Ist-Stand ist immer eine Variante.** „Ist“ ist `main` nur mit Mock-Szenarien, ohne
   Korrekturen, gemessen mit demselben Messlauf wie die Entwürfe. Jeder
   Entwurf wird neben dem Ist-Stand gezeigt, nicht nur neben den anderen Entwürfen; im Prototyp
   ist das `?variant=none`.
9. **Screenshots, wenn welche entstehen, in voller Auflösung.** Kein Herunterskalieren, keine
   verlustreiche Kompression, auch nicht auf Vergleichsseite und Querbögen: Ein verkleinertes Bild
   ist für die Abnahme wertlos. Wo ein Werkzeug noch skaliert, verlinkt die Seite das Original
   (README der Werkzeuge, „Grenzen“).

## Branches und Ablage

- Basis: `feature/<issue>_ui-lab-base` von `main`, mit den Mock-Lücken und Szenarien des Bereichs.
- Varianten: `feature/<issue>_ui-lab-var-<x>` von der Basis, je Session eine.
- Ergebnisse je Variante auf ihrem Branch unter `ui-lab/results/<X>/`: `REPORT.md`, Screenshots
  in voller Auflösung (PNG, Grundregel 9), `metrics.jsonl`, bei der Gewinnerin Videos mit
  `manifest.json` unter `videos/`. Der Ist-Stand
  liegt auf dem Basis-Branch unter `ui-lab/results/ist/`. Lab-Branches werden nie gemerged.
- Nach `main` gehören nur Werkzeuge, Szenarien und Mock-Lücken (eigenes Issue) sowie später die
  umgesetzte Richtung (eigenes Issue).

## Ablauf

### 1 · Inventur

Ist-Stand des Bereichs auf `main` erfassen, in `ui-lab/inputs/inventory.md` auf dem Basis-Branch:

1. Mock-Szenarien für den Bereich ergänzen, falls sie fehlen: Handler fragen
   `currentMockScenario()` ab; Vorlage `frontend/src/features/library/mocks/sourceScenarioMock.ts`.
   Mock-Lücken (fehlende Endpunkte, Fixtures, die ein Neuladen nicht überstehen) zuerst schließen.
2. Messen: `node ui-lab/capture.mjs --flow pages --pages <pfade> --out <dir>` über alle
   Szenarien; für Assistenten und Dialoge einen eigenen Ablauf unter `e2e/ui-lab/flows/`
   anlegen (Vorlage `flows/libraries.mjs`), dazu eine Ablaufdefinition für die Videos der
   Gewinnerin (Vorlage `flows/library-videos.mjs`).
3. Ist-Stand als Variante „Ist“ aufnehmen: von einem Arbeitsverzeichnis auf `main` (nur die
   Szenarien aus Punkt 1, keine Korrekturen) den Messlauf (Grundregel 7) mit `capture.mjs` nach
   `ui-lab/results/ist/`.
4. Randfälle je Art aus Datenmodell und API ableiten, nicht aus der heutigen Oberfläche: Menge,
   Tiefe, Namen (Länge, Sonderzeichen), Rechte, Anmeldezustände, Ladeverhalten (langsam,
   unvollständig, Teilfehler). Je Art eine Tabelle:

   | Randfall | Größenordnung | Ist-Verhalten | bricht? |
   | --- | --- | --- | --- |
   | 450 Spaces | Confluence Data Center einer Landesbehörde | Checkbox-Liste ohne Suche, 9.000 px | ja |

   Fehlende Randfälle werden Mock-Szenarien (Punkt 1).
5. Festhalten, was dem kleinen Fall genügt (ein Space, ein Ordner): Jede Variante muss ihn so
   leicht lassen, wie er heute ist.
6. Nebenbefunde außerhalb der UI (Backend kürzt still, API liefert zu wenig) sofort als eigenes
   Issue anlegen und im Inventar verlinken, nicht im Lab mitschleppen.
7. Inventar gliedern: Aufbau des Bereichs · je Unterart (Komponente, Felder, Zustände) ·
   Vergleichsmatrix konsistent/inkonsistent · Verstöße mit Prüflistennummer und Beleg · zehn
   größte Probleme nach Wirkung · Lücken der Mocks · Randfalltabellen · kleiner Fall ·
   Nebenbefund-Issues.

### 2 · Prüfliste zur Freigabe

Aus der Inventur die bereichsspezifischen Ergänzungen zur
[UI-Prüfliste](../../../docs/design/ui-pruefliste.md) ableiten: neue Punkte, neue Zählbudgets,
Szenarien, die jede Variante bestehen muss. Eingänge sind außerdem eine Best-Practice-Recherche
mit Quellen (eine Regel je Zeile, nur was Guidelines und Prüfliste nicht schon sagen) und die
Beispielbilder des Maintainers; beide gehören zur Vorlage. Vollständig in einer Runde (Grundregel 2), mit den
offenen Fragen im Format aus Grundregel 6. **Halt: Der Maintainer gibt die Prüfliste frei.**
Allgemeingültige Punkte kommen per PR in `docs/design/ui-pruefliste.md`; Vorschläge zur
Guideline-Änderung als eigenes Issue.

### 3 · Skizzenrunde mit drei typischen Fällen

Drei Fälle wählen, die den Bereich aufspannen: klein und glatt (`small`), groß
(`scale`), Fehler oder Teilfehler (`failed`/`partial`). Drei Richtungen als Varianten eines
Prototyps skizzieren (`?variant=`), je Richtung nur diese drei Fälle der Hauptart. Skizzen dürfen
grob sein; sie zeigen Struktur und Auswahlmuster, nicht Feinschliff. **Halt: Der Maintainer
klickt den Prototyp durch und wählt die Richtung** oder eine Kombination (Frage mit Beispiel und
Folge).

### 4 · Drei Varianten in parallelen Cloud-Sessions

Je Variante eine Session mit `create_session` (Repository, `source_revision` = Basis-Branch).
Der Auftrag an jede Session enthält:

- Branch `feature/<issue>_ui-lab-var-<x>` von der Basis; Richtung aus Schritt 3 und die Achse,
  in der sich diese Variante unterscheiden soll (z. B. A: Dialog je Auswahl, B: Seite je
  Schritt, C: alles auf einer Seite);
- die freigegebene Prüfliste als verbindlicher Maßstab, Budgets eingeschlossen;
- Dev-Server mit eigenem Port, `calibrate.mjs` vor dem ersten Lauf;
- die feste Reihenfolge:
  1. Variante grob bauen, alle Arten und Szenarien, ohne Feinschliff.
  2. Messlauf (Grundregel 7), Verstöße gegen Budgets, axe, Kontrast und `overflowX` beheben.
  3. Prototyp bauen und veröffentlichen (Abschnitt 7), Link an den Orchestrator.
- `ui-lab/results/<X>/REPORT.md`: Tabelle `Nr | Status | Beleg` über alle Prüflistenpunkte,
  Kennzahlen aus `compare.py` und bekannte Lücken; Ergebnisse committen und pushen;
- Regeln aus `AGENTS.md`, Code-Kommentare und Tests wie in jedem Frontend-PR, aber kein PR.

Zwischen den Halten laufen die Sessions ohne Rückfragen; am Halt warten sie auf die
Rückmeldung, die der Orchestrator mit `send_message` weitergibt. Den Stand mit `get_session` und `list_events`
prüfen, nicht in einer Schleife warten; eine Rückmeldung mit `send_later` planen.

**Halt: Der Maintainer klickt die Prototypen durch** und gibt Rückmeldung; Screenshot-Matrizen
werden ihm nicht vorgelegt.

**Konsistenz-Review** (nur für die Gewinnerin, Abschnitt 6, vor dem Halt und den Videos).
Es findet, was zwischen den Arten, Bereichen und Szenarien einer Variante auseinanderläuft; Messwerte und
Prüfliste allein sehen das nicht. Ablauf und Prüfkategorien:
[konsistenz-review.md](./konsistenz-review.md).

1. Teilmatrix aufnehmen: alle Arten und Szenarien hell bei 1280 und 320 px, dunkel als
   Stichprobe je Bereich.
2. Querbögen erzeugen: `contact_sheets.py --dir <matrix> --out <dir>` stellt denselben
   Bildschirm (Bereich × Szenario × Breite) aller Arten nebeneinander und schreibt je Prüfer eine
   Bilderliste: alle hellen Bilder, alle 320er, je Bereich mindestens ein dunkles, ohne
   `__full`, das byte-gleich zu seinem Viewport-Bild ist.
3. Parallele Detailprüfer als Agenten, je zwei Arten pro Prüfer, mit ihrer Bilderliste und den
   Querbögen; jeder Befund in einer festen Kategorie, mit Datei als Beleg und Vorschlag.
4. Die Befunde zu `ui-lab/results/<X>/REVIEW-<n>.md` zusammenführen (Doppelte vereinen, nach
   Wirkung ordnen, Mock- und Aufnahmefehler getrennt) und mit `send_message` an die Session der
   Variante geben.

### 5 · Bewertung durch drei Juroren

Drei Subagenten mit frischem Kontext, je einer pro Blickwinkel, ohne zu wissen, wer welche
Variante gebaut hat. Alle bekommen dieselben Unterlagen: den Prototyp-Link, `metrics-summary.md`
aus `compare.py --variants A=…,B=…,C=…`, die Berichte und die Prüfliste.

| Juror | Blickwinkel | Belege |
| --- | --- | --- |
| Regeln | jeder Prüflistenpunkt und jede Guideline-Regel, mit Nummer | `metrics.jsonl`, axe, Budgets |
| Skalierung | große Mengen, Hierarchie, lange Namen, Leer-, Lade- und Fehlerzustände | Prototyp in `scale`, `partial`, `failed`, `slow` und bei 320 px |
| Ruhe und Konsistenz | visuelle Ruhe, gleiche Muster und Begriffe über alle Arten, Begriffsregel | Prototyp, `fontSizes`/`colors` |

Jeder Juror liefert je Variante: bestanden/verfehlt je Prüflistenblock, die fünf schwersten
Befunde mit Beleg und die Teile, die in eine Mischung gehören. Keine Gesamtnote ohne Belege.

### 6 · Mischung und Feinschliff der Gewinnerin

Aus den Urteilen die Gewinnerin festlegen, meist eine Mischvariante M: Grundgerüst der stärksten
Variante, einzelne Muster der anderen, jedes mit Herkunft und Prüflistenpunkt. M in einer
weiteren Session (oder in der Session der Grundvariante) bauen. Nur sie bekommt die volle Runde:

1. Feinschliff, Messlauf; M hält alle Zählbudgets ein und ist axe- und kontrastfrei.
2. Konsistenz-Review an der Teilmatrix (Abschnitt 4), Befunde aus `REVIEW-<n>.md` beheben,
   Nachcheck an den Querbögen der betroffenen Bildschirme.
3. Prototyp neu veröffentlichen. **Halt vor den Videos: Der Maintainer klickt den Prototyp von M
   durch**, nicht die Screenshot-Matrix; die Session baut die Rückmeldung ein.
4. Videos mit `record-flows.mjs` nach Grundregel 4.

Was offen bleibt, steht im Bericht.

### 7 · Prototyp veröffentlichen und Vergleichsseite

**Prototyp:** `node ui-lab/build-prototype.mjs` baut und prüft; es bricht ab, wenn eine Datei
U+FFFD enthält, und teilt die Dateien in Pakete unter der Grenze von 255 Dateien je Publish. Upload
als Artifact: `index.html` als Seite, die Pakete aus `files.json` nacheinander an dieselbe URL
(README der Werkzeuge, „Klickbarer Prototyp“). In Stufe „schnell“ endet das Lab hier.

**Vergleichsseite** (Stufe „groß“): eine Seite für den Maintainer, gebaut mit `build_site.py` und veröffentlicht als Artifact
(README der Werkzeuge, „build_site.py“). `site.json` nennt Ist, A, B, C und M mit ihren
Ergebnisordnern und füllt die Texte: je Entwurf Idee, Stärken, Schwächen und Bewertung der
Juroren; „Gelernt und umgesetzt“ mit dem, was in M eingeflossen ist, was für die Guidelines
entschieden wurde und was am Vorgehen besser wurde. Darunter wählt der Maintainer Variante →
Bereich → Fall → Breite und sieht Videos und alle Bildschirme in voller Auflösung (Grundregel 9),
auf Wunsch mit dem Ist-Stand daneben. Videos gehen als Assets ins Artifact, die Bildschirme als
ein Sprite je Auswahl. Prototyp-Link, Kennzahlen aus `metrics-summary.md` und die offenen Fragen
(Grundregel 6) gehören in den Bericht neben der Seite. **Halt: Der Maintainer
entscheidet.** Danach: Umsetzungs-Issue mit der gewählten Variante als Spezifikation, eigenes
Issue für Guideline-Änderungen, Lab-Sessions archivieren.

### 8 · Umsetzung auf `main`

Die E2E-Suite läuft nicht bei Pull Requests, nur danach auf `main`. Ein Umsetzungs-PR baut
Bedienabläufe um, deshalb vor dem Merge:

- Texte aus `getByRole`/`getByLabel`/`getByText` in `e2e/tests/` gegen den neuen Stand von
  `frontend/src` greppen. Jeder Treffer, den es nicht mehr gibt, ist eine kaputte Spec.
- Betroffene Specs im selben PR umstellen und die E2E-Suite lokal laufen lassen
  ([`e2e/README.md`](../../../e2e/README.md)); das Ergebnis gehört in die PR-Beschreibung.
