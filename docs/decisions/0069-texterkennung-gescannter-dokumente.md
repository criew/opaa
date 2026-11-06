# ADR-0069: Texterkennung gescannter Dokumente in der Indexierung

## Status

Akzeptiert · zuletzt geändert 2026-11-06

## Kontext

Ein PDF ohne Textebene weist das Format `pdf` als „kein extrahierbarer Text“ ab; in Verwaltungen
ist das ein großer Teil des Altbestands. Die Modellrolle Texterkennung (ADR-0067) bindet einen
Dienst an, docling-serve mit RapidOCR oder ein OpenAI-kompatibles Vision-Modell, und verweist die
Indexierung bisher nicht darauf. Die Messung des Epics #2640 an synthetischen und neun echten Scans
hat docling-serve mit RapidOCR empfohlen: Tabellen bleiben erhalten, § wird erkannt, es läuft ohne
GPU. Im DoclingDocument liegen die Rahmen der Elemente in PDF-Punkten bezogen auf `pages[n].size`,
die der Textelemente mit Ursprung unten links, die der Tabellenzellen oben links.

Ein Scan kostet je Seite Sekunden bis Minuten Rechenzeit; ein Dienst kann ausfallen, überlastet
sein oder eine Datei ablehnen. Eine Neuindizierung (Pipeline-Nachzug, Wechsel der Chunk-Größe) darf
deshalb nicht jedes Mal erneut erkennen, ein Ausfall darf keinen Bestand dauerhaft ausschließen und
eine einzelne kaputte Datei keinen Lauf blockieren. Ein Upload hat keinen Lauf, in dem ein
Rückstand nachgeholt würde.

## Entscheidung

### 1. Eingriff an der Ablehnung des Formats `pdf`

Nur wenn das Format `pdf` eine Datei als „kein extrahierbarer Text“ ablehnt, fragt die Indexierung
die Texterkennung; die Regel, wann ein PDF als Scan gilt, bleibt die des Formats. Ohne Rolle in
Kraft bleibt das Ergebnis bitgleich die heutige Ablehnung. Es gibt keinen Rückfall auf ein anderes
Verfahren: Scheitert die Erkennung, bleibt der Scan abgewiesen.

### 2. Eigenes Format `pdf-ocr` mit gespeichertem Erkennungsstand

Die Antwort des Dienstes wird ohne eingebettete Bilder mit dem Dokument gespeichert
(`document_text_recognition`, gelöscht mit dem Dokument) und vom Format `pdf-ocr` geschnitten:
nach den erkannten Überschriften (`title`, `section_header`), Absätze und Listen wie ein PDF mit
Textebene, Tabellen zeilenweise mit ihrer Kopfzeile, Kopf- und Fußzeilen der Seite nicht. Die
Chunks tragen die Pipeline `pdf-ocr` mit eigener Version und keinen Routing-Schlüssel, denn die
Datei bleibt eine des Formats `pdf`. Eine Neuindizierung unveränderter Dateien schneidet den
gespeicherten Stand neu und ruft den Dienst nie; ein Lauf ruft ihn nur bei geänderter Datei oder
einem anderen Empfänger erneut. Ausgeschaltet bleibt Gespeichertes nutzbar.

### 3. Vermerk und Rahmen je Chunk

Jeder Chunk trägt im Anker `textOrigin` (Verfahren) und `regions` (ADR-0062, Entscheidung 8), im
Metadatum `text_recognition` Verfahren und Empfänger (`configurationId` der Rolle, ohne Schlüssel).
Die Rahmen rechnet `pdf-ocr` je Element nach dessen `coord_origin` um.

### 4. Fehlerklassen: Ausfall wartet, Ablehnung sperrt

Zeitüberschreitung, 401/403, 429, 5xx, Verbindungsfehler, eine nur teilweise Umwandlung
(`partial_success`) und unerwartete Fehler sagen nichts über die Datei: Der Scan bleibt abgewiesen
ohne Sperre, nichts wird gespeichert, der nächste Lauf versucht es erneut. Gesperrt wird nur, was an
der Datei liegt: eine Ablehnung durch docling-serve (andere 4xx, gescheiterte Umwandlung), die
Seitengrenze und eine unlesbare Datei. Ein Vision-Modell bekommt Seitenbilder, die OPAA selbst
erzeugt; weist es eine Anfrage ab, liegt das meist an Modellname oder Einstellung, deshalb sperrt
dort nur eine zu große Seite (413). Erkennt der Dienst keinen Text, wird das leere Ergebnis
gespeichert statt gesperrt: Ein falsch eingestelltes Vision-Modell sperrte sonst jeden Scan. Das
Dokument bleibt ohne Inhalt und wird wieder gelesen, sobald sich Datei oder Empfänger ändern. Die
Sperre gilt für Prüfsumme, Verbindungstyp, Empfänger und Version von `pdf-ocr`; ändert sich eines,
fällt sie. Wer die Bibliothek bearbeiten darf, hebt sie auf (`TEXT_RECOGNITION_BLOCK_RELEASED` im
Revisionsprotokoll). Sperre und Erkennungsstand liegen in derselben Datenbank wie Dokumente und
Chunks; nach einer Wiederherstellung passen sie zum Stand der Dokumente.

### 5. Seitenbudget je Lauf, Plätze je Instanz und Frist je Dokument

Ein Lauf sendet höchstens `opaa.ocr.max-pages-per-run` Seiten; der erste Scan passt immer. Was
darüber liegt, bleibt unverändert, der Lauf endet unvollständig mit einer Notiz und der nächste
setzt fort. Gleichzeitig erkennt eine Instanz höchstens `opaa.ocr.max-concurrent` Dokumente
(ADR-0053, je Instanz); ein Lauf wartet auf einen Platz, ein Upload ohne Lauf und das Nachholen
überlassen den Scan bei vollem Platz dem nächsten Durchgang. Ein abgebrochenes Warten gibt die
reservierten Seiten zurück. `opaa.ocr.document-timeout` ist eine harte Frist für alle Aufrufe eines
Dokuments, bei docling-serve wie beim Vision-Modell: Jeder Aufruf bekommt höchstens, was von ihr
übrig ist, und wird bei Ablauf abgebrochen. Sie ist auf 55 Minuten begrenzt.

### 6. Nachholen hochgeladener Scans

Upload-Bibliotheken haben keine Läufe. Alle fünf Minuten liest ein Durchgang unter der
ShedLock-Sperre `text-recognition-backlog` (ADR-0053, Entscheidung 7) wartende hochgeladene Scans
aus der Originalablage, mit eigenem Seitenbudget; nach 50 Minuten beginnt er kein Dokument mehr.
Der Abstand ist über `opaa.ocr.catch-up-interval` einstellbar, mindestens 20 Sekunden; die E2E-Suite
nutzt das, um nicht Minuten zu warten. Die Sperre bleibt mindestens zehn Sekunden gehalten, kürzer
als jeder zulässige Abstand. Ein Durchgang einer weiteren Instanz direkt danach schadet nicht: Er
findet die schon gelesenen Scans nicht mehr als wartend.
Mit der Frist eines Dokuments (höchstens 55 Minuten) und dessen Einbettung bleibt er unter der
Sperrdauer von zwei Stunden. Er endet beim ersten Ausfall des Dienstes und bei belegtem Platz. Quellbibliotheken holen im nächsten Lauf nach, der ein abgewiesenes Dokument
ohnehin erneut liest.

### 7. Keine Personenbezüge in Log und Messwerten

Protokoll, Log und die Messwerte `opaa.ocr.documents`, `opaa.ocr.pages` und `opaa.ocr.duration`
nennen Verbindungstyp, Empfänger, Seitenzahl und Grund, nie ein Konto oder einen Inhalt; Pfade nur,
wie die Bibliothek sie ohnehin protokolliert.

### 8. Dieselben Optionen für docling-serve

Die Indexierung schickt dieselben Optionen wie der Verbindungstest (ADR-0067): erzwungene OCR mit
RapidOCR, Deutsch, genaue Tabellenstruktur, JSON, Bilder nur als Platzhalter. Optionen gegen den
Verlust kurzer und erster Zeilen werden erst gesetzt, wenn eine Messung ihre Wirkung belegt (Offen).

## Konsequenzen

- Gescannte PDF-Dokumente werden durchsuchbar; Belege können die Fundstelle auf dem Original
  einrahmen und den Text als erkannt kennzeichnen.
- Der erkannte Text liegt ein zweites Mal in der Datenbank (Datenkatalog „Dokumentbestand“), in
  der Regel einige Kilobyte je Seite; Seitenbilder werden nicht gespeichert.
- Ein Rückstand nach dem Einschalten baut sich über mehrere Läufe und Durchgänge ab; die Bibliothek
  zeigt wartende und gesperrte Scans.
- Restrisiko: Die Erkennung kann Zeichen und ganze Zeilen verfehlen; das Original bleibt maßgeblich.

## Verworfene Alternativen

- Erkennen bei jeder Neuindizierung — vervielfacht Rechenzeit und Datenabfluss ohne neuen Inhalt.
- Sperren bei jedem Fehler — ein Ausfall des Dienstes schlösse den ganzen Bestand aus.
- Teilergebnis einer Umwandlung speichern — fehlende Seiten blieben dauerhaft unauffindbar, weil
  eine Neuindizierung den Dienst nicht mehr fragt.
- Rückfall auf das andere Verfahren oder auf Tesseract — ein zweiter Empfänger ganzer Seiten ohne
  eigene Entscheidung der Verwaltung; Tesseract liest § nicht zuverlässig.
- Seitenbilder speichern — Datenmenge ohne Nutzen für Suche und Fundstelle.
- Einzelne Bildseiten in einem PDF mit Textebene erkennen — braucht seitenweisen Zuschnitt und
  zwei Textquellen in einem Dokument; ganze Scans decken den Bestand weitgehend ab.

## Offen

- Verlust kurzer, alleinstehender und erster Zeilen bei RapidOCR (nummerierte Punkte, einzelne
  §-Überschriften): Ob eine Option von docling-serve (etwa die OCR-Region oder die Schwelle der
  Erkennung) ihn behebt, ist nicht gemessen; ohne Messung bleiben die Optionen unverändert.
- Fotos und Bilddateien, einzelne Scanseiten in PDF mit Textebene.
