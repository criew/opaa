# Formate

Welche Dateiformate OPAA aufnimmt, welche Format-Pipeline sie verarbeitet und welche Formate
bewusst nicht zugelassen sind. Wie ein Dokument die Aufnahmestrecke durchläuft, beschreibt das
Kapitel [Wissensbibliotheken](../README.md).

## Zugelassene Formate

Zugelassen wird nach **Inhalt**, nicht nach Endung. Die Spalte „Zulassung" nennt, wie streng die
Inhaltsprüfung ist: *strikt* heißt fester Byte-Signatur-Abgleich; *text-tolerant* heißt, der Inhalt
muss nur als Text erkennbar sein und die Datei muss die Endung selbst tragen.

| Endung | Pipeline | Zulassung | Erkannte Struktur | Kapitel |
|---|---|---|---|---|
| `.pdf` | `pdf` | strikt | Lesezeichen-Gliederung, sonst Seiten | [PDF](pdf.md) |
| `.docx` | `docx` | strikt | Überschriften bis Ebene 3, Tabellen, Kopf- und Fußzeilen | [Word](docx.md) |
| `.doc` | `tika-fallback` | strikt | keine, Token-Fenster | [Auffang-Pipeline](fallback.md) |
| `.pptx` | `pptx` | strikt | eine Folie je Chunk, Titel, Notizen | [PowerPoint](pptx.md) |
| `.xlsx` | `tabular` | strikt | Blätter, Kopfzeile, Zeilengruppen | [Tabellen](tabular.md) |
| `.csv` | `tabular` | text-tolerant | Kopfzeile, Zeilengruppen | [Tabellen](tabular.md) |
| `.ods` | `tabular` | strikt | Blätter, Kopfzeile, Zeilengruppen | [Tabellen](tabular.md) |
| `.odt` | `odt` | strikt | Überschriften bis Ebene 3, Tabellen, Kopf- und Fußzeilen | [OpenDocument Text](odt.md) |
| `.odp` | `odp` | strikt | eine Folie je Chunk, Titel, Notizen, Masterfolie | [OpenDocument Präsentation](odp.md) |
| `.html` | `html` | strikt | Überschriften h1 bis h3, Hauptinhalt ohne Navigation, Tabellen, Listen | [HTML](html.md) |
| `.md` | `markdown` | text-tolerant | Überschriften bis Ebene 3, Frontmatter | [Markdown](markdown.md) |
| `.txt` | `tika-fallback` | text-tolerant | keine, Token-Fenster | [Auffang-Pipeline](fallback.md) |
| `.eml` | `email` | text-tolerant | Kopfdaten, Nachrichtentext, Thread-Segmente, Anhänge | [E-Mail](mail.md) |
| `.msg` | `email` | strikt | wie `.eml` | [E-Mail](mail.md) |
| Feed-Detailseite | `html` | entfällt | wie `.html`; der Konnektor übergibt die Inhaltsbereiche als HTML | [HTML](html.md) |
| Confluence-Seite | `confluence` | entfällt | Überschriften h1 bis h3, Tabellen, Listen, Makros nach Regelwerk | [Confluence-Seite](confluence.md) |

## Grenzen beim Lesen

Eine präparierte Datei kann klein sein und beim Lesen trotzdem ein Vielfaches an Speicher und
Rechenzeit verlangen: ein Word-Dokument, dessen Inhaltsdatei sich auf das Hundertfache entpackt, ein
PDF, das denselben Textblock tausendfach auf eine Seite zeichnet, oder eines, das Zeichnungen
vielfach ineinander verschachtelt, ohne etwas Sichtbares zu zeichnen. Damit ein einzelnes Dokument
weder die Verarbeitung anderer Dokumente blockiert noch den Speicher des Backends aufbraucht, gelten
gemeinsame Obergrenzen:

- **Text je Dokument** (`max-text-characters`): gilt für PDF, Word, PowerPoint, Excel, ODS und CSV,
  HTML und Markdown, für die Auffang-Pipeline (`.txt`, `.doc`) und für den Text von E-Mails
  (Kopfdaten und Nachrichtentext, ohne Anhänge, die eigene Dokumente sind). PDF, ODS und die
  Auffang-Pipeline zählen schon beim Lesen mit — ODS auch beim Auffalten wiederholter Zellen — und
  hören auf, sobald die Grenze überschritten ist.
- **Entpackte Größe von Word, PowerPoint und Excel** (`max-ooxml-part-bytes` je Inhaltsdatei,
  `max-ooxml-total-bytes` für alle Inhaltsdateien zusammen): geprüft, bevor das Dokument gelesen
  wird. Gezählt wird jede Datei im Container, die als XML beginnen kann, gleich wie sie heißt oder
  als was sie sich ausgibt; Bilder, Videos, Schriften und eingebettete Dateien, die schon an ihren
  ersten Bytes als Binärdaten erkennbar sind, zählen nicht mit. Eine Präsentation mit einem großen
  Video scheitert deshalb nicht an dieser Grenze.
- **Rechenschritte eines PDF** (`max-pdf-operators`): alle Zeichenanweisungen, die beim Lesen eines
  PDF über alle Seiten, verschachtelten Zeichnungen und Lesedurchgänge ausgeführt werden; jedes
  Element einer Werteliste zählt als weiterer Schritt.
- **Gelesene Inhaltsdaten eines PDF** (`max-pdf-content-bytes`): die entpackten Zeichenanweisungen
  aller Seiten und Zeichnungen, eine Zeichnung so oft gezählt, wie sie gezeichnet wird.
- **Größe eines einzelnen Inhaltsteils eines PDF** (`max-pdf-stream-bytes`): Ein Seiteninhalt, eine
  Zeichnung und jedes Verzeichnis, das schon beim Öffnen der Datei entpackt wird (Querverweis- und
  Objektströme), darf entpackt nicht größer werden. Gemessen wird, bevor der Teil im Speicher
  ausgepackt wird; so kann eine wenige hundert Kilobyte große Datei nicht Hunderte Megabyte
  Speicher belegen. Bei einer verschlüsselten Datei mit beschädigtem Querverweis werden
  Objektströme nicht durchsucht; was nur dort steht, fehlt dann.
- **Seiten eines PDF** (`max-pdf-pages`): gezählt, indem der Seitenbaum durchlaufen wird, nicht nach
  der Seitenzahl, die die Datei selbst angibt; eine Seite, die mehrfach eingehängt ist, zählt
  mehrfach.
- **Objekte eines PDF** (`max-pdf-objects`): Einträge aller Querverweistabellen und -ströme der
  Datei, auch älterer Fassungen, dazu die Objekte jedes entpackten Objektstroms und bei beschädigtem
  Querverweis die Objekte, die die Suche in der Datei findet, zusammengezählt. Ein Querverweisstrom
  zählt höchstens so viele Einträge, wie seine entpackten Daten fassen; einer ohne Eintragsbreite
  (`/W [0 0 0]`) wird abgewiesen. Gezählt wird, bevor die Einträge gelesen werden: bei einer
  Querverweistabelle jede Zeile, bei der Suche in einer beschädigten Datei jede Fundstelle von
  `obj`. Nach dem Lesen einer Querverweistabelle wird zur Sicherheit zusätzlich ein Eintrag je
  sechs gelesene Bytes angerechnet, soweit das über die gezählten Zeilen hinausgeht. So kann ein
  Querverweis, der Millionen Einträge angibt, nicht den Speicher füllen. Die Zählung rechnet
  bewusst eher zu viel: Ein Objekt in einem Objektstrom zählt doppelt (als Eintrag und als Objekt
  des Stroms), und eine gewöhnliche Querverweistabelle mit 20 Bytes je Eintrag zählt gut dreifach.
  Ein PDF kann deshalb schon ab etwa 300.000 Objekten abgewiesen werden; dann hilft ein höherer
  Wert für `max-pdf-objects`.
- **Lesezeit eines PDF** (`max-pdf-parse-seconds`): als letzte Sicherung die Zeit, die das Lesen
  eines PDF insgesamt dauern darf.
- **Gliederung eines PDF:** Jeder Lesezeichen-Eintrag wird höchstens einmal gelesen, so dass eine
  Gliederung, die auf sich selbst zurückverweist, das Lesen nicht aufhält; tiefer als 64 Ebenen und
  über 50.000 Einträge hinaus wird sie nicht weiter verfolgt.
- **Wiederholter Kontext**: Was vor jedem Chunk wiederholt wird, wird gekürzt, damit eine lange
  Überschrift oder eine sehr breite Kopfzeile sich nicht mit der Zahl der Chunks vervielfacht. Der
  Überschriftenpfad, der Fundort sowie Blatt- und Tabellennamen werden fest auf 500 Zeichen gekürzt.
  Folientitel und Kontextzeile mit Kopfzeile einer Tabelle werden auf die Hälfte von
  `chunk-target-characters` gekürzt (mindestens 500, bei 4.000 Zeichen also 2.000). Ist die
  Kopfzeile einer Tabelle länger, steht sie einmal vollständig in einem eigenen Chunk, und die
  Zeilengruppen tragen nur die ersten Spaltennamen
  ([Tabellen](tabular.md#3-struktur-und-chunks)).
- **Chunks der Auffang-Pipeline und einer E-Mail** (fest, 10.000 je Text): Bräuchte ein Text
  mehr Chunks, ist das Dokument „fehlgeschlagen", statt dass der Rest in einem einzigen Chunk
  landet. Das kann einen Text unterhalb von `max-text-characters` treffen, der sich schlecht in
  Tokens zerlegen lässt, etwa seltene Schriftzeichen.
- **Lange Zeichenfolgen ohne Leerraum** (fest, 1.000 Zeichen): In der Auffang-Pipeline und im Text
  von E-Mails wird nach je 1.000 Zeichen ohne Leerzeichen ein Leerzeichen eingefügt, bevor der Text
  in Tokens zerlegt wird. Es bleibt im gespeicherten Chunk und in der Anzeige des Fundstellentexts
  stehen; eine Zeichenfolge wie ein langer Base64-Block erscheint dort also in Abschnitten von
  1.000 Zeichen.
- **Eingebettete Objekte in `.doc`** werden nicht gelesen, nur der Text des Dokuments selbst.

Ein Dokument über einer Grenze wird nicht gekürzt, sondern ist „fehlgeschlagen" wie eine nicht
lesbare Datei; bei einer erneuten Indexierung bleiben die bisherigen Chunks stehen. Dasselbe gilt,
wenn eine Lesebibliothek an einem einzelnen Dokument mit einem internen Fehler abbricht. Die
OpenDocument-Formate haben eigene Grenzen in ihren Kapiteln, E-Mails in [E-Mail](mail.md).

Läuft das Backend trotzdem einmal in einen Speichermangel, beendet es sich und wird neu gestartet,
statt in einem ungewissen Zustand weiterzuarbeiten
([Images und Laufzeit](../../../betrieb/images-und-laufzeit.md#speicher-und-container-grenzen)).

Schlüssel unter `opaa.indexing.parser-limits.*`, Umgebungsvariablen
`OPAA_INDEXING_PARSER_LIMITS_*`:

| Schlüssel | Standard | Wirkung |
|---|---|---|
| `max-text-characters` | 10.000.000 | Text, den ein Dokument insgesamt liefern darf |
| `max-ooxml-part-bytes` | 32 MiB | entpackte Größe einer einzelnen Inhaltsdatei eines Word-, PowerPoint- oder Excel-Dokuments |
| `max-ooxml-total-bytes` | 64 MiB | entpackte Größe aller Inhaltsdateien eines solchen Dokuments zusammen |
| `max-pdf-operators` | 200.000.000 | Zeichenanweisungen (und Elemente ihrer Wertelisten), die beim Lesen eines PDF insgesamt ausgeführt werden |
| `max-pdf-content-bytes` | 512 MiB | entpackte Inhaltsdaten, die beim Lesen eines PDF insgesamt verarbeitet werden |
| `max-pdf-stream-bytes` | 32 MiB | entpackte Größe eines einzelnen Seiteninhalts, einer Zeichnung oder eines Querverweis- oder Objektstroms eines PDF |
| `max-pdf-parse-seconds` | 120 | Sekunden, die das Öffnen und Lesen eines PDF höchstens dauern darf |
| `max-pdf-pages` | 10.000 | Seiten eines PDF |
| `max-pdf-objects` | 1.000.000 | Objekte eines PDF: Einträge aller Querverweise, Objekte entpackter Objektströme und bei beschädigter Datei die gefundenen Objekte; Objekte in Objektströmen zählen doppelt |

## Bewusst nicht zugelassen

| Format | Grund |
|---|---|
| Eingescannte PDFs ohne Textebene | werden erkannt und mit klarer Meldung abgewiesen, statt mit null Chunks als „indiziert" zu gelten. Texterkennung (OCR) ist ein eigenes Vorhaben. |
| Bilder und Einzelscans (TIFF, PNG, JPEG) | gehören zum selben OCR-Vorhaben |
| Ältere Office-Binärformate außer `.doc` (`.xls`, `.ppt`, `.vsd`, `.pub`) | keine geeignete Leselogik; nur `.doc` wird über die Auffang-Pipeline verarbeitet |
| RTF | nie aufgenommen, kein Bedarf gemeldet |
| Archive (ZIP) | ein Container ist kein Dokument; eingebettete Dokumente laufen über den Anhangsweg, den heute Mails und Feed-Seiten nutzen |
| Fach-XML (LegalDocML.de, XJustiz, XÖV) | lohnt sich erst mit einem konkreten Quellanschluss; ein Parser ohne Bestand kann sich nicht bewähren |
| Generisches XML und JSON | wären nur als Klartext verarbeitbar und würden die Textzulassung aufweichen |
| Quellcode | für einen Verwaltungsbestand ohne Nutzungsfall |
| Flat-XML-OpenDocument (`.fodt`, `.fodp`) | die OpenDocument-Leser setzen den ZIP-Container voraus |

Abgewiesene Dateien werden gezählt und im Laufprotokoll namentlich als „Format nicht unterstützt"
geführt; bei der Löscherkennung gelten sie als vorhanden.

Die Formatprüfung erkennt keinen Schadcode. Dafür kann der Betrieb eine Virenprüfung einschalten, die
jede Datei vor dem Lesen prüft ([Netz und Sicherheit](../../../betrieb/netz-und-sicherheit.md#virenprüfung-der-aufgenommenen-dateien)).
