# Ingestion-Pipelines je Dokumenttyp

> **Status: Umgesetzt bis auf dokumentierte Zurückstellungen (#1062, #1105); Pflege
> fortlaufend.**

Diese Spezifikation führt zusammen, was in den Diskussionspapieren zu
[Dateitypen der Verwaltung und geführter Metadaten-Anreicherung](../discussions/discussion-dateitypen-und-metadaten.md)
(welche Formate ein Verwaltungsbestand tatsächlich enthält) und im
[Retrieval-Tech-Report](../discussions/discussion-retrieval-strategien.md), Abschnitt 5
(Chunking-Strategien und ihre Evidenz), erarbeitet wurde; die ältere Pipeline-pro-Dokumenttyp-Diskussion
(Reader- und Splitter-Landschaft von Spring AI) ist vollständig in diese Spezifikation aufgegangen.

Sie beschreibt die **Aufnahmestrecke**: was zwischen „eine Datei liegt vor" und „ihre Inhalte sind
im Index" passiert. Woher die Dateien kommen, steht in
[Wissensquellen und Konnektoren](./knowledge-sources.md); was mit den Chunks zur Abfragezeit
geschieht, in [Wissensschicht und Retrieval](./data-indexing-rag.md) und
[Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md).

---

## Teil 0 — Worum es geht, ohne Vorwissen

Dieser Teil richtet sich an Leserinnen und Leser mit Softwarehintergrund, aber ohne RAG-Erfahrung.
Wer den Ablauf kennt, kann direkt zu [Teil 1](#teil-1--die-pipeline-abstraktion) springen.

### Was beim Aufnehmen eines Dokuments passiert

Vier Schritte, immer dieselben, in dieser Reihenfolge:

```
Datei  →  Parsen  →  Chunken  →  Embedden  →  Indexieren
```

1. **Parsen** heißt: aus einem Dateiformat wird Text. Aus einem PDF, einem Word-Dokument, einer
   Präsentation wird eine Zeichenkette, die eine Maschine weiterverarbeiten kann. Der Parser
   entscheidet dabei auch, **was verloren geht** — ob eine Tabelle als Tabelle erkennbar bleibt oder
   zu einer Zahlenkolonne zerläuft, ob eine Überschrift noch als Überschrift markiert ist oder nur
   noch eine Zeile unter vielen.
2. **Chunken** heißt: der Text wird in Stücke geschnitten. Nicht das ganze Dokument wird
   durchsuchbar gemacht, sondern seine Abschnitte — sonst wäre der Treffer immer „irgendwo in diesem
   200-seitigen Amtsblatt". Ein Chunk ist die kleinste Einheit, die gefunden, an das Sprachmodell
   übergeben und später als Fundstelle zitiert wird.
3. **Embedden** heißt: jeder Chunk bekommt einen Zahlenvektor, der seine Bedeutung darstellt. Zwei
   Texte mit ähnlichem Inhalt bekommen ähnliche Vektoren, auch wenn sie unterschiedliche Wörter
   verwenden. Darauf beruht, dass die Frage „Was kostet ein neuer Ausweis?" ein Dokument findet, in
   dem „Verwaltungsgebühr für die Ausstellung eines Personaldokuments" steht.
4. **Indexieren** heißt: Chunk, Vektor und ein paar Zusatzangaben (aus welchem Dokument, welcher
   Bibliothek, an welcher Stelle) werden gespeichert, damit die Suche sie wiederfindet.

Dieses Dokument behandelt **Schritt 1 und 2**. Schritt 3 und 4 sind bereits gebaut und ändern sich
hier nicht.

### Warum die Struktur beim Chunking zählt

Der heutige Zuschnitt zählt Tokens: alle 1000 Tokens ein Schnitt, mit 100 Tokens Überlappung zum
Nachbarn. Das ist billig, deterministisch und funktioniert bei Fließtext ordentlich. Bei allem
anderen erzeugt es einen bestimmten, gut beobachtbaren Fehler.

**Beispiel Gebührentabelle.** In einer Verwaltungsgebührensatzung steht:

```
§ 7 Gebühren für Personaldokumente

(1) Für die Ausstellung eines Personalausweises werden erhoben:
    Antragstellerin/Antragsteller ab 24 Jahren ........  37,00 EUR
    Antragstellerin/Antragsteller unter 24 Jahren ....  22,80 EUR
    Ausstellung im Ausland, Zuschlag .................  30,00 EUR
```

Fällt der Schnitt zwischen Überschrift und Tabelle, entsteht ein Chunk, der nur noch aus drei
Zahlenzeilen besteht. Er enthält weder das Wort „Personalausweis" noch „Gebühr" noch „§ 7". Sein
Embedding ist dann die Bedeutungsdarstellung einer Zahlenkolonne — die Frage „Was kostet ein
Personalausweis?" findet ihn nicht, obwohl er die Antwort enthält. Und selbst wenn er gefunden
würde, taugt er nicht als Beleg: „37,00 EUR" ohne Angabe, wofür.

Derselbe Fehler in drei anderen Gestalten:

- **Rechtstexte:** Ein Schnitt zwischen Tatbestand („Wer eine bauliche Anlage errichtet …") und
  Rechtsfolge („… bedarf der Genehmigung") liefert zwei Chunks, von denen keiner die Regel enthält.
- **Präsentationen:** Der heutige Parser gibt alle Folien als einen Textblock zurück. Ein Chunk
  enthält dann das Ende von Folie 4 und den Anfang von Folie 5 — zwei Themen, ein Vektor, keins
  davon gut getroffen.
- **Tabellenblätter:** Ohne wiederholte Spaltenköpfe ist eine Zeilengruppe aus der Mitte eines
  Zuständigkeitsverzeichnisses eine Liste von Namen ohne Aussage darüber, wofür sie zuständig sind.

Die Gegenmaßnahme ist naheliegend und in der Fachliteratur belegt (siehe
[Tech-Report 5.2](../discussions/discussion-retrieval-strategien.md#52-strukturbasiertes-chunking-nach-überschriften-paragrafen-folien)):
**Nicht nach Tokens schneiden, sondern entlang der Struktur, die das Dokument selbst mitbringt** —
Paragraf, Absatz, Überschriftenabschnitt, Folie, Tabellenblatt. Diese Struktur muss dafür allerdings
den Parser überleben, und genau das tut sie heute nicht: Ein einziger Universal-Parser für alle
Formate liefert für alle Formate dasselbe, nämlich flachen Text ohne Gliederungsinformation.

Deshalb hängen Parsen und Chunken zusammen und werden hier gemeinsam behandelt. Ein
strukturbewusster Zuschnitt ist nur möglich, wenn die Extraktion die Struktur nicht schon weggeworfen
hat.

### Was OCR hier bedeutet

**OCR** (optical character recognition, Texterkennung) ist der Schritt, der aus einem Bild von Text
wieder Text macht. Er wird gebraucht, wenn ein PDF keine Textebene hat, sondern nur ein Foto oder ein
Scan jeder Seite ist — typisch für eingescannte Altakten und für unterschriebene Originale.

Für die Aufnahmestrecke sind zwei Dinge daran wichtig:

1. **Ein Scan-PDF scheitert heute lautlos.** Der Parser findet keinen Text, liefert eine leere
   Zeichenkette zurück, und das Dokument landet mit null Chunks im Bestand. Es steht in der Liste der
   indizierten Dokumente, ist aber unauffindbar. Das ist die gefährlichste Art des Scheiterns, weil
   sie wie Erfolg aussieht.
2. **OCR ist nicht nur Zeichenerkennung.** Ein reiner OCR-Durchlauf liefert flachen Text in
   Lesereihenfolge — also genau die Strukturlosigkeit, gegen die der Rest dieses Dokuments arbeitet.
   Brauchbar wird OCR für RAG erst zusammen mit Layout-Analyse: Was ist Überschrift, was
   Tabellenzelle, was Fußnote, was Kopfzeile. Deshalb ist die OCR-Frage in dieser Spezifikation an
   die Docling-Frage gekoppelt und nicht an einen bloßen Tesseract-Aufruf (siehe
   [Ausblick OCR](#ausblick-scan-pdf-und-ocr-eigenes-epic)).

---

## Teil 1 — Die Pipeline-Abstraktion

### Ist-Stand

Heute läuft **jedes** Format durch denselben Weg:

```
Datei → SupportedDocumentFormats (Zulassung, inhaltsbasiert, #404)
      → DocumentService.parseDocument  (TikaDocumentReader + PageMarkingContentHandler)
      → ChunkingService                (TokenTextSplitter(1000) in OverlappingTokenTextSplitter(100))
      → FileProcessingService          (Embedding-Präfix aus dem Dateinamen, #933/#940)
      → VectorChunkStore
```

Daraus ergeben sich vier Eigenschaften, die diese Spezifikation ändert:

| Eigenschaft heute | Folge |
|---|---|
| Ein Parse-Einstieg für alle Formate (`parseDocument`) | Struktur überlebt die Extraktion nicht |
| Ein Splitter für alle Formate (1000/100 Token) | Zuschnitt ignoriert Paragrafen, Folien, Tabellenzeilen |
| Sechs zugelassene Formate (`.md .txt .pdf .docx .doc .pptx`) | ODF, Tabellen, HTML, E-Mail bleiben draußen |
| Chunk-Metadaten rein technisch (`document_id`, `chunk_index`, `file_name`, `library_id`, `organization_id`, `location`) | Kein Ort, an dem Gliederungspfad, Foliennummer oder Blattname landen könnten |

Die einzige inhaltliche Anreicherung, die es schon gibt, ist der Kontext-Präfix aus dem Dateinamen
(`ChunkContextTitle`, #933/#940) — eine LLM-freie Minimalvariante des Contextual Chunking. Sie ist
der Ansatzpunkt, an dem der Abschnittstitel später den Dateinamen ergänzt.

### Entscheidung: `DocumentPipeline` als erstes Arbeitspaket

Die Aufnahmestrecke bekommt eine Abstraktion `DocumentPipeline` mit einer Registry. **Eine Pipeline
je Dokumentklasse**, jede definiert vier Dinge:

1. **Reader** — womit das Dokument geparst wird,
2. **Splitter** — wie sein Output zerlegt wird (bei manchen Typen: gar nicht, weil der Reader bereits
   die richtigen Einheiten liefert),
3. **Metadaten-Anreicherung** — welche Strukturangaben je Chunk entstehen,
4. **Chunk-Größe** — welche Grenzen für diesen Typ gelten.

**Tika bleibt als Fallback-Pipeline.** Es ist nicht überflüssig, sondern das Sicherheitsnetz: der
universelle Parser für alles, wofür keine spezialisierte Pipeline registriert ist. Ein Format ohne
eigene Pipeline verhält sich damit exakt wie heute — die Umstellung ist für den bestehenden Bestand
verhaltensneutral, solange keine spezialisierte Pipeline registriert wurde.

**Routing über die inhaltsbasierte Formaterkennung.** Welche Pipeline zuständig ist, entscheidet der
per Tika **erkannte Inhalt**, nicht die Dateiendung — dasselbe Muster, das die Zulassung seit #404
verwendet, und aus demselben Grund: In gewachsenen Ablagen tragen Dateien routinemäßig die falsche
Endung. Die bestehende Sonderregel für Markdown und Klartext (Inhalt *und* Endung müssen passen, weil
sich beide Typen am Inhalt allein nicht unterscheiden lassen, siehe
[Welche Dateien OPAA verarbeitet](./data-indexing-rag.md#welche-dateien-opaa-verarbeitet)) gilt für
das Routing unverändert weiter: Sie entscheidet zugleich, ob die Markdown- oder die Klartext-Pipeline
greift.

**Open-Closed als Zuschnittskriterium.** Ein neues Format hinzuzufügen heißt: eine neue
Pipeline-Klasse schreiben und registrieren. Es heißt nicht: eine `switch`-Anweisung in
`DocumentService` erweitern, `ChunkingService` um einen Sonderfall ergänzen und die
Zulassungsentscheidung an einer dritten Stelle nachziehen. Wenn eine Formaterweiterung mehr als die
neue Pipeline und ihren Registry-Eintrag berührt, ist die Abstraktion falsch geschnitten — das ist
das Abnahmekriterium des ersten Arbeitspakets, nicht ein Stilwunsch.

```
                         ┌─ Formaterkennung (inhaltsbasiert, #404) ─┐
Datei ───────────────────┤                                          ├──→ Pipeline-Registry
                         └─ Zulassungsentscheidung ─────────────────┘            │
                                                                                 ▼
                     ┌──────────────────────────────────────────────────────────────┐
                     │  MarkdownPipeline · PdfPipeline · DocxPipeline · PptxPipeline │
                     │  SpreadsheetPipeline · HtmlPipeline · MailPipeline            │
                     │  TikaFallbackPipeline  ← alles ohne eigene Pipeline           │
                     └──────────────────────────────────────────────────────────────┘
                                                     │
                     Reader → Splitter → Metadaten-Anreicherung → Chunk-Größe
                                                     ▼
                              Embedding · Volltextindex · Vektorablage
```

### Umgesetzt: die Abstraktion selbst (#1056)

Die Abstraktion steht; noch ohne eine einzige Format-Pipeline, und das ist der Punkt: Solange nur die
Fallback-Pipeline registriert ist, läuft jedes Format exakt den bisherigen Weg — die Umstellung ist
für den Bestand nachweislich verhaltensneutral.

| Baustein | Was er tut |
|---|---|
| `DocumentPipeline` | Reader, Splitter, Metadaten-Anreicherung und Chunk-Größe einer Dokumentklasse hinter einem Aufruf; dazu `id()` und `version()`, die auf jedem erzeugten Chunk landen |
| `DocumentPipelineRegistry` | Routing über den **erkannten Inhalt** — es fragt dieselbe Stelle (`SupportedDocumentFormats.decideForFileName`), die auch über die Zulassung entscheidet, statt die Regel ein zweites Mal zu formulieren. Damit gilt die Markdown-/Klartext-Sonderregel (Inhalt *und* Endung) fürs Routing automatisch mit |
| `TikaFallbackPipeline` | Der bisherige Weg (Tika-Reader + Token-Splitter mit `opaa.indexing.chunk-size`/`-overlap`), zuständig für alles, wofür keine spezialisierte Pipeline registriert ist. Chunk-Größe: **gesetzt, nicht gemessen** |

Eine neue Format-Pipeline hinzuzufügen heißt: eine Klasse schreiben und als Bean registrieren. Weder
die Registry noch `FileProcessingService` noch `SupportedDocumentFormats` ändern dafür ihre Form.
Zwei Pipelines, die dasselbe Format beanspruchen, sind ein Verdrahtungsfehler und lassen den Kontext
beim Start scheitern, statt die Bean-Reihenfolge entscheiden zu lassen.

Die Ausgänge, die `FileProcessingService` bisher selbst entschied — „Scan ohne Textebene",
„gar nichts geparst", „Text, aber keine Chunks" — entscheidet jetzt die Pipeline für ihr eigenes
Format. Genau das braucht eine PDF-Pipeline später, um Scan-Erkennung anders zu beantworten als eine
Tabellen-Pipeline.

**Vierter Ausgang: `PARSE_FAILED` (#1268).** „Gar nichts geparst" trug bis dahin zwei
unterscheidbare Fälle: die Quelle war lesbar und ist leer (`NO_CONTENT`), und die Quelle ließ sich
gar nicht erst lesen — beschädigter Container, abgewiesene XXE-Auflösung, überschrittene
Schutzgrenze. Der zweite Fall heißt jetzt `PARSE_FAILED`. Eine Pipeline, die beides nicht
auseinanderhalten kann, meldet `PARSE_FAILED`; sie sagt damit nur, dass sie nichts über den Inhalt
weiß. Die Pipelines für PDF, DOCX, PPTX, ODT, ODP und XLSX/CSV/ODS fangen ihre Lesefehler selbst ab
und melden diesen Ausgang; HTML, Markdown und der Tika-Fallback werfen weiterhin eine Ausnahme —
der Aufrufer behandelt beides gleich.

### Übergabepunkt: die Reihenfolge, in der Chunks ersetzt werden

Der Übergabepunkt zwischen Pipeline und Ablage ist nicht nur eine Datenstruktur, sondern eine
**Reihenfolge**: Beim erneuten Aufnehmen eines geänderten Dokuments bleiben die alten Chunks stehen,
bis die neue Fassung tatsächlich geparst und gechunkt ist. Erst unmittelbar vor dem Schreiben der
neuen Chunks werden die alten gelöscht — in beiden Speichern (`vector_store`, `chunk_full_text`),
über denselben Aufruf, der sie auch beim Löschen eines Dokuments gemeinsam entfernt.

Vor #1268 löschten `processFile`, `processUrlFile` und `processRssEntry` die alten Chunks direkt
nach der Kontingentprüfung, also **vor** dem Parsen. Scheiterte das Parsen der neuen Fassung, stand
das Dokument bis zum nächsten erfolgreichen Lauf ohne Chunks im Bestand — der zuvor gültige,
durchsuchbare Stand war verloren, obwohl er fachlich weiterhin der beste verfügbare war. Der
Pipeline-Nachzug (`PipelineReindexService`) verfuhr schon vorher nach der jetzt allgemeinen Regel.

Welcher Ausgang was bedeutet — für die Konnektorwege (`processFile`, `processUrlFile`,
`processRssEntry`), die eine geänderte Quelle verarbeiten:

| Ausgang | Alte Chunks | Dokumentzustand |
|---|---|---|
| `CHUNKED` | werden unmittelbar vor dem Schreiben der neuen ersetzt | `INDEXED` |
| `NO_CONTENT` (geparst, leer) | werden entfernt — „leer" ist eine Aussage über die neue Fassung | `FAILED`, `chunk_count = 0` |
| `NO_EXTRACTABLE_TEXT` (z. B. Scan-PDF ohne Textebene) | werden entfernt — dieselbe Begründung | `FAILED`, `chunk_count = 0`, mit Hinweis auf fehlenden extrahierbaren Text |
| `PARSE_FAILED` oder Ausnahme **vor** dem Löschen | **bleiben unverändert** | `FAILED`, `chunk_count` unverändert, Ereignis protokolliert |
| Ausnahme **nach** dem Löschen (Embedding, Schreibvorgang) | sind bereits weg; angefangene neue werden mit entfernt | `FAILED`, `chunk_count = 0`, Ereignis protokolliert |

`chunk_count` ist damit auch die Auskunft darüber, welcher `FAILED`-Fall vorliegt: ein Wert größer
null heißt „noch mit dem alten Stand durchsuchbar", null heißt „ohne Chunks". Maßgeblich ist dabei
nicht der Ausgang, sondern ob gelöscht wurde — eine Ausnahme, die erst nach dem Löschen auftritt,
hinterlässt ebenfalls eine Null.

Auf dem **Nachzugsweg** (`PipelineReindexService` → `reindexStoredDocument`) bleiben auch die leeren
Ausgänge folgenlos: Dort ist die Datei unverändert und nur die Pipeline-Version neu, ein leeres
Ergebnis sagt also nichts über eine neue Fassung aus. Das Dokument behält seine Chunks und seine
`INDEXED`-Zeile und wird als nicht nachgezogen zurückgemeldet.

**Löschen und Schreiben liegen nicht in einer Transaktion.** Zwischen beiden liegt der
Embedding-Aufruf (ein HTTP-Rundlauf, bewusst außerhalb jeder Transaktion, siehe
`VectorStoreWriter`), und die Konnektorwege sind selbst nicht `@Transactional`. In diesem Fenster
hat das Dokument **keine** Chunks, während seine Zeile noch `INDEXED` mit dem alten `chunk_count`
zeigt; ein Absturz genau darin hinterlässt den chunklosen Zustand, den diese Reihenfolge verhindern
soll. Das ist der verbleibende Restfall — das Fenster ist aber deutlich kleiner als vor #1268, wo es
Parsen **und** Embedding umspannte. Alte und neue Chunks existieren zu keinem Zeitpunkt
nebeneinander; eine Fundstelle kann dasselbe Dokument also nie aus zwei Fassungen zugleich belegen.

**Das Speicherkontingent bleibt unberührt.** Es wird über die Dokumentzeile (`file_size`) gemessen,
nie über Chunks — die Reihenfolge des Chunk-Austauschs taucht in der Delta-Prüfung gar nicht auf.

Anhänge (ADR-0022) sind ebenfalls nicht betroffen: Eine Elternmail, deren Parsen scheitert, meldet
überhaupt keine Anhänge, der verallgemeinerte Anhangsweg läuft dann nicht an, und die vorhandenen
Anhangsdokumente bleiben mitsamt ihrem `parent_document_id` unverändert stehen.

**Keine Datenbankänderung nötig.** Die Pipeline-Version ist ein Chunk-Metadatum und liegt damit dort,
wo Chunk-Metadaten liegen: in `vector_store.metadata`. Diese Tabelle legt Spring AI beim Start an,
nicht Liquibase — eine Spalte wäre dort gar nicht verfügbar, und eine zweite Tabelle wäre eine dritte
Zeile je Chunk für einen Wert, der definitorisch zum Chunk gehört.

**Zweiter Rückgabekanal: entdeckte Anhänge (ADR-0022, Teil 2, #1181).** `DocumentPipelineResult`
trägt neben `chunks` eine Liste `discoveredAttachments` (Elementtyp `DiscoveredAttachment`:
Dateiname, temporäre Datei, erkannter Medientyp) — eine Pipeline kann damit melden, dass sie beim
Parsen eingebettete Objekte gefunden hat, ohne sie selbst zu verarbeiten. Default ist die leere
Liste; kein bestehender Aufrufer ändert sein Verhalten. Die `CHUNKED`-Regel gilt unverändert für
diesen Kanal mit: `chunks` bleibt leer für jeden Outcome außer `CHUNKED`, und `CHUNKED` ohne eigene
Chunks bleibt unzulässig, auch wenn `discoveredAttachments` nicht leer ist — eine Pipeline, die nur
Anhänge findet und selbst nichts liefert, ist ein Fall für den verallgemeinerten Anhangsweg (Teil 3,
#1182), nicht für diesen Vertrag. Die Verantwortung für die temporäre Datei eines gemeldeten Anhangs
geht mit der Rückgabe auf den Aufrufer über: `DocumentPipelineRunner#run` — der gemeinsame
Aufruf-Wrapper um `DocumentPipeline#run`, den `FileProcessingService` für jedes Dokument nutzt (seit
#1183 der einzige Aufrufer, `MailDocumentPipeline` rekursiert nicht mehr selbst) — reicht die Liste
zuerst an einen von `FileProcessingService` übergebenen Handler weiter, der einen Anhang über den
verallgemeinerten Anhangsweg indiziert, bevor `DocumentPipelineRunner#run` in einem `finally`
unbedingt und idempotent aufräumt: ein vom Handler bereits verarbeiteter Anhang wird kein zweites Mal
gelöscht, ein nie übernommener nie geleakt. Aktiv genutzt wird der Kanal seit der Umstellung von
`MailDocumentPipeline` in Teil 4 (#1183, siehe unten).

### Parsing-Strategie: hybrid, nicht ein Werkzeug für alles

Für die Frage „womit parsen" gibt es keine einheitliche Antwort, und der Versuch, eine zu erzwingen,
ist genau der heutige Zustand. Die Aufteilung:

| Bereich | Werkzeug | Begründung |
|---|---|---|
| Markdown | `MarkdownDocumentReader` (Spring AI) | Liefert Überschriftenabschnitte statt Fließtext |
| HTML | `JsoupDocumentReader` (Spring AI) | CSS-Selektoren erlauben Boilerplate-Entfernung; Tika liefert Navigation und Fußzeile als Inhalt mit |
| PDF (born-digital) | `ParagraphPdfDocumentReader`, alternativ `PagePdfDocumentReader` (Spring AI) | Nutzt den PDF-Katalog (Inhaltsverzeichnis) für kapitelbewusstes Splitting; die Seitenvariante ist der Rückfall, wenn kein Katalog vorhanden ist |
| DOCX/DOC | Tika, ergänzt um Überschriftenebenen aus den Absatzformaten | Textextraktion ist solide; die Gliederung muss zusätzlich mitgenommen werden |
| PPTX | Eigener Reader über Apache POI (`XMLSlideShow`) | Kein Rahmenwerk liefert folienweise Dokumente; POI ist über Tika ohnehin auf dem Classpath |
| XLSX | Eigener Reader über Apache POI | Tabellenstruktur muss erhalten bleiben, siehe Formatabschnitt |
| ODF, EML/MSG, alles Übrige | Tika | Nativ unterstützt, keine bessere Alternative in Sicht |

Es entsteht dabei **keine zweite Zulassungsliste**. `SupportedDocumentFormats` bleibt die eine Stelle,
die entscheidet, was aufgenommen wird; die Registry entscheidet nur, **wie** ein bereits zugelassenes
Dokument verarbeitet wird. Ein Format, das in der Registry eine Pipeline hat, aber nicht zugelassen
ist, kommt gar nicht erst an.

### Docling als vermerkte Option für PDF und Scan — nicht im ersten Ausbau

[Docling](https://github.com/docling-project/docling) (IBM, MIT-Lizenz) ist ein layoutbewusster
Dokumentkonverter: Er erkennt Überschriften, Absätze, Tabellen und Bildbereiche und gibt eine
strukturierte Darstellung zurück statt flachen Texts. Für Satzungen mit §-Gliederung und für
Gebührentabellen ist das genau die Fähigkeit, die den in
[Teil 0](#warum-die-struktur-beim-chunking-zählt) beschriebenen Fehler behebt.

**Der PDF-Pfad startet trotzdem mit Bordmitteln** (`ParagraphPdfDocumentReader`). Docling ist als
Option vermerkt, nicht eingeplant, und die Reihenfolge ist bewusst:

- Betriebsform wäre **docling-serve als eigener Container im Compose-Verbund**, angesprochen über
  REST — kein Python im Java-Prozess, keine native Bibliothek im Backend-Image. Das passt zum
  bestehenden Deployment-Modell (siehe
  [Deployment und Infrastruktur](./deployment-infrastructure.md)), kostet aber einen weiteren Dienst,
  den Betreiberinnen mitbetreiben, absichern und aktualisieren müssen.
- **Ressourcenbedarf** nach heutiger Kenntnis: CPU-only rund 8 GB RAM je Worker, Image rund 4,4 GB.
  Für eine Einrichtung, die OPAA auf einer mittleren VM betreibt, ist das keine Nebensache.
- Als REST-Client käme `io.arconia:arconia-ai-docling-document-reader` in Frage. Das ist ein junges
  Ein-Maintainer-Projekt; die Abhängigkeit ist deshalb keine Selbstverständlichkeit. Ein dünner
  Eigenbau-Client gegen die docling-serve-API ist die Rückfalloption und wäre überschaubar, weil nur
  ein Endpunkt gebraucht wird.

**Eintrittsbedingung ist ein PoC**, nicht eine Einschätzung. Er beantwortet vier Fragengruppen an
echten Verwaltungsdokumenten — drei fachliche und eine betriebliche:

1. **Gliederung.** Erkennt Docling die §-Gliederung deutscher Satzungen zuverlässig genug, um darauf zu
   schneiden?
2. **Tabellen.** Bleiben Gebühren- und Zuständigkeitstabellen als Tabellen erhalten?
3. **Ressourcen.** Trifft der oben genannte Ressourcenbedarf im Betrieb zu?
4. **Betrieb.** Ist der Dienst unter den Bedingungen betreibbar, unter denen OPAA betrieben wird? Diese
   Gruppe umfasst:
   - **Offline-Betrieb ohne Laufzeit-Download.** Modelle und Gewichte müssen im Image oder in einem
     bereitgestellten Volumen liegen; ein Dienst, der beim ersten Dokument etwas aus dem Netz nachlädt,
     ist in einer abgeschotteten Umgebung unbrauchbar (siehe
     [Betrieb ohne Netzanbindung](./deployment-infrastructure.md#betrieb-ohne-netzanbindung)).
   - **Geschlossenes Compose-Netz.** Der Dienst ist nur aus dem Backend erreichbar und veröffentlicht
     keinen Port nach außen.
   - **Authentifizierung zwischen Backend und Dienst.** Ein Konvertierungsdienst, den jeder im selben
     Netz aufrufen kann, ist ein Weg, fremde Dokumente durch die Infrastruktur zu schleusen.
   - **Timeout und Speicherobergrenze je Dokument.** Ein einzelnes pathologisches PDF darf weder den
     Indizierungslauf anhalten noch den Container an sein Speicherlimit treiben.
   - **Definiertes Fallback bei Ausfall.** Ist der Dienst nicht erreichbar oder bricht er ab, fällt die
     PDF-Pipeline auf den Bordmittel-Pfad zurück. Der so erzeugte Chunk ist **am Chunk erkennbar** als
     mit dem Rückfallverfahren erzeugt — über die Pipeline-Version aus
     [Regel (d)](#d-jeder-chunk-trägt-die-version-des-verfahrens-das-ihn-erzeugt-hat). Sonst entsteht
     ein Bestand aus zwei Verfahren, dessen Zusammensetzung von der Verfügbarkeit eines Containers zur
     Indizierungszeit abhängt und die niemand rekonstruieren kann.
   - **Aktualisierungsweg des Images im abgeschotteten Betrieb.** Ein 4,4-GB-Image, das nur über einen
     Internetzugang aktualisierbar ist, wird nicht aktualisiert.

**Ein negatives Betriebskriterium verwirft die Option genauso wie ein negatives Fachkriterium.** Ein
Konverter, der die §-Gliederung perfekt erkennt, aber Modelle zur Laufzeit nachlädt, ist für die
Zielinstallationen dieses Projekts keine Option — die vierte Gruppe ist kein Anhang zu den ersten drei.
Erst wenn alle vier positiv beantwortet sind, wird der PDF-Pfad umgestellt; die Schwellen im Einzelnen
stehen unter [Offene Punkte](#offene-punkte).

---

## Teil 2 — Strukturbewusstes Chunking je Typ

### Der Grundsatz

Der Zuschnitt folgt der Struktur, die der jeweilige Typ mitbringt. Wo eine solche Struktur fehlt —
OCR-Rohtext, unstrukturierte Alttexte, Klartextdateien ohne Gliederung —, bleibt es beim
Token-Chunking wie heute. Der Fallback verschwindet nicht, er wird nur zur Ausnahme statt zur Regel.

| Dokumentklasse | Schnittgrenze | Was jeder Chunk zusätzlich trägt |
|---|---|---|
| Satzungen, Ordnungen, Rechtstexte (aus PDF/DOCX) | § und Absatz, aus der erkannten Gliederung | Gliederungspfad (§, Absatz), Dokumenttitel |
| Markdown, DOCX, HTML | Überschriftenabschnitt (Ebene 1–3) | Überschriftenpfad |
| PPTX | Eine Folie = ein Chunk | Foliennummer, Folientitel, Notizen als Kontext |
| XLSX/CSV | Logische Tabelle bzw. Zeilengruppe | Blattname, Tabellenname, **Spaltenköpfe in jedem Chunk wiederholt** |
| EML/MSG | Eine Nachricht = ein Chunk; lange Threads je Nachricht im Thread | Betreff, Absender, Datum |
| Strukturlos (OCR-Rohtext, Alttexte, `.txt`) | Token-Fenster mit Überlappung, wie heute | Dokumenttitel (bestehendes Verhalten) |

Zwei Punkte daran sind keine Feinheiten:

**Ein §-genauer Chunk ist zugleich die zitierfähige Fundstelle.** Das ist der Grund, warum
strukturbewusstes Chunking in einem Nachweisapparat mehr wert ist als anderswo: Der Zuschnitt
bestimmt nicht nur, was gefunden wird, sondern auch, wie präzise der Beleg ausfällt. „§ 7 Abs. 1 der
Verwaltungsgebührensatzung" ist eine andere Belegqualität als „Seite 4".

**Die Wiederholung der Spaltenköpfe ist Redundanz mit Absicht.** Sie kostet Platz in jedem Chunk
einer großen Tabelle und ist trotzdem richtig: Ohne sie ist eine Zeilengruppe aus der Tabellenmitte
bedeutungsleer, mit ihr eine beantwortbare Frage.

#### Umgesetzt (#1061)

`PdfDocumentPipeline` (`id` `pdf`, Version 1), `DocxDocumentPipeline` (`id` `docx`, Version 2 seit
#1145) und `PptxDocumentPipeline` (`id` `pptx`, Version 1) sind registriert und beanspruchen `.pdf`,
`.docx` bzw. `.pptx` in der `DocumentPipelineRegistry`. `.doc` bleibt unverändert bei
`TikaFallbackPipeline` — POIs OOXML-Leser kann das ältere Binärformat gar nicht öffnen.

- **PDF** liest über Apache PDFBox direkt (nicht den in Teil 1 genannten Spring-AI-`ParagraphPdfDocumentReader`/`PagePdfDocumentReader` — kein solches Modul liegt auf dem Klassenpfad, dieselbe Abwägung wie bei `HtmlDocumentPipeline`/Jsoup). Trägt der PDF-Katalog ein Inhaltsverzeichnis (Outline/Bookmarks), schneidet die Pipeline entlang **jeder** dort vorhandenen Verschachtelungstiefe — anders als bei Markdown/DOCX/HTML gibt es hier **kein** Level-3-Limit, weil § und Absatz in einer Satzung typischerweise zwei Katalogebenen sind und eine tiefere Gliederung ebenso zitierfähig bleiben soll. Ein Katalogeintrag, dessen Ziel sich nicht auf eine Seite auflösen lässt, wird übersprungen (seine Kinder bleiben auf ihrer eigenen Ebene). **Mehrere Katalogeinträge auf derselben Seite** (der Satzungs-Normalfall: mehrere §§ je Seite) teilen sich den Seitentext nach ihren Titeltexten auf — der Text zwischen einem Titel und dem nächsten wird dem jeweils vorangehenden Eintrag zugeordnet, statt der gesamten Seite nur dem letzten Eintrag (#1104 Review, wichtig 1); lässt sich ein Titel im extrahierten Text nicht wortgleich wiederfinden, fällt der geteilte Bereich auf den letzten Eintrag zurück, die übrigen Geschwister behalten trotzdem ihren eigenen, wenn auch körperlosen Abschnitt. Ohne auflösbaren Katalog fällt die Pipeline auf eine Seite = ein Chunk zurück (`location` = „S. n"). Der #1055-Scan-Guard wird aus der eigenen PDFBox-Extraktion beantwortet (leerer Volltext über das ganze Dokument), nicht mehr aus einem separaten, anschließend verworfenen Tika-Lauf (#1104 Review, wichtig 6) — die Semantik ist unverändert, weil Tikas PDF-Modul selbst auf PDFBox aufsetzt.
- **DOCX** liest direkt über Apache POI (`XWPFDocument`) statt über Tika, weil die Absatzformat-Überschriftenebene — genau das, worauf diese Pipeline schneidet — bei Tikas Extraktion verloren geht. Die Ebene kommt aus der eingebauten Word-Formatvorlage (Style-ID `Heading1`…`Heading9` im üblichen englischsprachig-templateten Fall, aber die Style-ID ist **nicht** verlässlich englisch — LibreOffice und manche deutschen Word-Vorlagen exportieren `berschrift1`/`Ueberschrift1`, das führende „Ü" fällt der OOXML-Bereinigung zum Opfer, siehe #1104 Review, Nit 5) oder ersatzweise aus dem direkten Gliederungsattribut (`w:outlineLvl`); ein Absatz ohne beides bleibt Fließtext im laufenden Abschnitt. **Tabellen werden zellenweise gelesen**, nicht übersprungen (#1104 Review, wichtig 2): Ein Gebührenverzeichnis oder Formular ist praktisch immer eine Tabelle, und Tikas Extraktion (das Vor-#1061-Verhalten) trug diesen Inhalt bereits — die Pipeline durchläuft dafür `getBodyElements()` statt nur `getParagraphs()` und wandelt jede `XWPFTable` in einen Absatz-Textblock um (eine Zeile je Tabellenzeile, Zellen mit „ | " verbunden). **Kopf-/Fußzeilentext wird seit #1145 mitgelesen**, obwohl er nicht zu `getBodyElements()` gehört: Jeder Header-/Footer-Teil aus `XWPFDocument#getHeaderList()`/`#getFooterList()` — die Vereinigung über alle Abschnitte und alle Standard-/Erste-Seite-/Gerade-Varianten eines mehrabschnittigen Dokuments, nicht nur der zuletzt im Dokument stehende `sectPr`-Header/-Footer — wird über POI ausgelesen und — genau wie bei `OdtDocumentPipeline`/`OdpDocumentPipeline` — als ein einziger, deduplizierter führender Chunk aufgenommen (`location` „Kopf-/Fußzeile“, `RepeatingHeaderChunk`, geteilt mit den beiden ODF-Pipelines), statt pro Seite dupliziert oder verworfen zu werden. Ein Absatz wird über `XWPFRun#text()` gelesen, nicht `getText(0)` — Letzteres liefert nur den ersten `w:t`-Knoten eines Runs, während Word eine tabgetrennte mehrspaltige Kopfzeile („Stadt Musterstadt&lt;TAB&gt;Az. 12-34/2026“) routinemäßig als **einen** Run mit mehreren `w:t`/`w:tab`-Kindern schreibt; ein Aktenzeichen in der zweiten Spalte wäre mit `getText(0)` sonst still verloren gegangen. Ein Run mit nachverfolgt gelöschtem Text (`w:delText`) wird ausgeschlossen, dieselbe Ausnahme, die `XWPFParagraph#getText()` für den Körpertext bereits macht. Der zuletzt berechnete Wert eines Word-Feldes wird beim Auslesen ausgeschlossen — sowohl die komplexe Form (`w:fldChar`-Runs zwischen `separate` und `end`, mit einem Tiefenzähler statt eines Flags gegen verschachtelte Felder) als auch `w:fldSimple` (LibreOffices Exportform, ein eigener POI-Run-Typ `XWPFFieldRun` ohne eigenes `w:fldChar`/`w:instrText`, den die Zustandsmaschine allein nicht sieht). Zwei Absätze mit gleichem, auf Leerraum normalisiertem Text tragen nur einmal bei — der übliche Fall, wenn derselbe Header für mehrere Abschnitte oder Varianten gilt; die Normalisierung schließt geschützte Leerzeichen (U+00A0, U+202F) ein, da diese in Behördenkopfzeilen als Spaltentrenner üblich sind und ein bloßes `\s` sie nicht erfasst. `RepeatingHeaderChunk` verwirft als Netz darunter zusätzlich jeden Kandidaten ohne einen einzigen Buchstaben.
- **PPTX** liest über Apache POI (`XMLSlideShow`): eine Folie mit Text = ein Chunk, mit Folientitel und -nummer als Fundort und Sprechernotizen als eigenem, klar benannten Absatz (Platzhalter für Foliennummer/Datum in den Notizen werden dabei ausgefiltert, #1104 Review, Nit 7). Eine `XSLFGroupShape` wird rekursiv abgestiegen und eine `XSLFTable` zeilenweise gelesen (#1104 Review, wichtig 3) — beide sind keine `XSLFTextShape` und wären sonst unsichtbar; der Titel-Shape wird über Objektidentität ausgeschlossen, nicht über Textgleichheit, damit ein Textfeld mit zufällig demselben Wortlaut wie der Titel nicht mit verschwindet. Eine leere Folie neben anderen Folien mit Text erzeugt weiterhin einen (fast leeren) Chunk, damit die Foliennummerierung als Fundstelle lückenlos bleibt — **trägt aber keine einzige Folie der Präsentation Text**, meldet die Pipeline `NO_EXTRACTABLE_TEXT` statt `CHUNKED` mit lauter inhaltsleeren „Folie n"-Chunks (#1104 Review, wichtig 4): Ohne diese Schranke kehrt die in Teil 3, Punkt 1 behobene stille Leer-Index-Fehlfunktion für rein bildbasierte Präsentationen zurück.

Alle drei — sowie `HtmlDocumentPipeline` — nutzen dieselbe, geteilte `HeadingSectionSplitter`-Logik (Überschriftenpfad, Soft-/Hard-Zeichenlimit, „Abschn. …“-Fundort, Unterdrückung körperloser Abschnitte): `HtmlDocumentPipeline` baut seinen eigenen Block-/Überschriftenpfad-Zustand aus der DOM-Traversierung auf, ruft für die Abschnittsbildung selbst aber `HeadingSectionSplitter.flushSection`/`capChunkLength` direkt statt einer eigenen Kopie (#1104 Review, Nit 9) — die #1100-Nachbesserungen an dieser Logik leben damit an genau einer Stelle.

**Chunk-Größe: gesetzt, nicht gemessen** für alle drei — der bestehende Evaluierungskorpus enthält keine PDF-, DOCX- oder PPTX-Dokumente. **Baseline unberührt** — kein Korpusdokument dieses Typs.

**`MarkdownDocumentPipeline` (`id` `markdown`, Version 1) ist seit #1103 als Bean registriert**, anstelle von `TikaFallbackPipeline` für `.md`. Der gesamte Evaluierungskorpus (`eval/corpus/`) ist Markdown; das Umschalten war deshalb — anders als bei PDF/DOCX/PPTX — keine für den Bestand verhaltensneutrale Änderung, sondern eine Messvertrags-Änderung, siehe [ADR-0012, Nachtrag „Strukturbewusstes Markdown-Chunking"](decisions/0012-messvertrag-retrieval-harness.md#nachtrag-strukturbewusstes-markdown-chunking-issue-1103) für die gemessene Verschiebung und die Baseline-Folgen. Ein Fund bei der Registrierung: Alle drei Korpora beginnen jedes Dokument mit einem YAML-Frontmatter-Block vor der ersten Überschrift, den `HeadingSectionSplitter` sonst zu einem eigenen, überschriftslosen ersten Chunk gemacht hätte — `MarkdownDocumentPipeline` verwirft einen `---`-begrenzten Block am Dateianfang deshalb, statt ihn zu chunken (siehe die Pipeline-eigene Javadoc).

### Chunk-Größen: gemessen, wo Messmaterial existiert — und sonst ehrlich gesetzt

Die heutigen 1000 Token sind **gesetzt, nicht gemessen** — das steht so in
[ADR-0010](../decisions/0010-ein-chunk-invariante-evaluierungskorpus.md) und in den
[Stellschrauben](./data-indexing-rag.md#stellschrauben-und-ihre-wirkung). Die externe Evidenz deutet
in eine andere Richtung (Chroma und Microsoft messen ihr Optimum bei 200–512 Token mit 10–25 %
Überlappung, siehe
[Tech-Report 5.1](../discussions/discussion-retrieval-strategien.md#51-fixedrecursive-chunking-basis-heute-in-opaa)),
aber nicht auf einem Verwaltungskorpus.

Der naheliegende Satz wäre „die Chunk-Größen misst der Benchmark". Er wäre für einen Teil der
Typ-Pipelines schlicht unwahr: Für PPTX, XLSX und EML existiert heute **kein Messmaterial** — weder der
bestehende Evaluierungskorpus noch die geplante Verwaltungs-Evaldomäne enthalten Folien, Tabellenblätter
oder Mailthreads mit kuratierter Ground Truth. Eine Zahl, die dort im Spec-Text steht, ist geschätzt,
und sie als gemessen auszugeben wäre die gefährlichere Variante: Niemand hinterfragt eine Zahl, die
angeblich aus einer Messung stammt.

Deshalb gilt eine Verfahrensregel mit Kennzeichnungspflicht:

> **Jede Typ-Pipeline vermerkt zu ihrer Chunk-Größe, ob der Wert *gesetzt* oder *gemessen* ist** — bei
> `gemessen` mit Verweis auf Lauf und Domäne, bei `gesetzt` mit einer Zeile dazu, welche Messgrundlage
> fehlt und was sie herstellen würde.

Daraus folgt der Zielzustand, nicht der Startzustand: Wo Messmaterial existiert (Markdown, PDF, DOCX,
HTML gegen die mehrchunkigen Domänen), **bekommt die Pipeline ihre Chunk-Größe aus einer Messung gegen
den Referenzkorpus** und nicht aus einer Schätzung. Wo es fehlt, bleibt der Wert vorerst gesetzt und
trägt diesen Vermerk, bis eine Fallgruppe für den Typ existiert. Vorschlagswerte aus den
Diskussionspapieren (600 für PDF, 500 für HTML, 400 für E-Mail, 800 als Fallback) sind Ausgangspunkte,
keine Festlegungen — und im Sinne der Regel oben durchweg *gesetzt*.

Das Messverfahren — Korpus je Dokumentklasse, Kennzahlen, Baseline-Vergleich — steht in
[Retrieval-Benchmark](./retrieval-benchmark.md); der
Messvertrag selbst in [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md), das
Fehlerkriterium in [ADR-0013](../decisions/0013-fehlerkriterium-retrieval-regression.md).

Auch für die messbaren Typen gehört eine Einschränkung dazu, weil sie sonst überrascht: Der heutige
Evaluierungskorpus taugt nur teilweise. Die Domäne `comic-characters` unterliegt der
Ein-Chunk-Invariante aus ADR-0010 — wo jedes Dokument genau einen Chunk ergibt, kann eine Chunk-Größe
nichts bewirken. Die mehrchunkige Domäne aus dem ADR-0010-Nachtrag ist der Anfang, ein Verwaltungskorpus
mit echter §-Gliederung, echten Tabellen und echten Folien die Voraussetzung. **Ohne diesen Korpus ist
keine typspezifische Chunk-Größe messbar** — der Aufbau gehört deshalb vor die erste Messung, nicht nach
sie.

### Baseline-Aktualisierung als Schritt jedes Format-Issues

Jede Pipeline-Umstellung ändert den Zuschnitt und damit die Vektoren — die Retrieval-Kennzahlen
verschieben sich zwangsläufig, auch wenn die Änderung eine Verbesserung ist. Das ist kein Fehler,
sondern der erwartete Signalweg (siehe
[ADR-0011](../decisions/0011-search-quality-evaluation-harness.md), Entscheidung 5).

Deshalb enthält **jedes Format- und Pipeline-Issue, das den Zuschnitt von Korpusdokumenten berührt,
drei Schritte statt zwei**:

1. Pipeline bauen,
2. Kennzahlen vorher/nachher gegen den Referenzkorpus messen und die Differenz in der
   PR-Beschreibung ausweisen,
3. **Baseline bewusst aktualisieren** — als eigener, reviewter Schritt mit Begründung, nie als
   stillschweigendes Nachziehen einer roten Prüfung.

Eine Umstellung, die die Kennzahlen verschlechtert, ist damit nicht automatisch abgelehnt (ein
besserer Zuschnitt kann auf einem Korpus, der ihn nicht abbildet, schlechter messen). Sie ist aber
begründungspflichtig.

**Betroffenheit ist die Bedingung, nicht die Formalie.** Eine EML- oder XLSX-Pipeline ändert an einem
Korpus, der weder Mails noch Tabellenblätter enthält, keinen einzigen Chunk. Ein Vorher-Nachher-Lauf
misst dort zwangsläufig zweimal dieselben Zahlen und belegt nichts — außer, dass jemand ihn ausgeführt
hat. Ein Pflichtritual ohne Aussage ist der sichere Weg, dass es irgendwann mechanisch abgehakt wird,
auch dort, wo es zählt.

Berührt eine Änderung den Zuschnitt von Korpusdokumenten **nicht**, genügt deshalb die Feststellung:

> „Baseline unberührt — kein Korpusdokument dieses Typs."

Diese Feststellung ist keine Behauptung, sondern **maschinell belegt**: Die
Referenzvarianten-Selbstprüfung des Benchmarks (siehe
[Retrieval-Benchmark](./retrieval-benchmark.md#anforderungen-an-die-umsetzung)) fordert ohnehin
bitgleiche Zahlen zur committeten Baseline. Bleibt sie grün, ist der Nachweis geführt; schlägt sie
fehl, war die Änderung entgegen der Annahme betroffen — und dann gilt der volle Dreischritt.

**Seit Issue #1144 gilt eine zusätzliche, von "betroffen" unabhängige Auflage:**
`ingestionPipelineFingerprint` (ADR-0012, Nachtrag Ingestion-Pipeline-Fixpunkt) ist ein
Sammelabdruck über die Versionen aller registrierten Pipelines, nicht nur der vom Korpus
genutzten. Ein Versions-Bump — auch an einer Pipeline, deren Format "Baseline unberührt" gilt
— verschiebt diesen Abdruck und macht damit alle sechs committeten Baselines ungültig,
unabhängig davon, ob ein einziger Chunk des Korpus sich ändert. Die Folge: **Jedes
Format-/Pipeline-Issue, das `version()` irgendeiner registrierten Pipeline erhöht, zieht
`ingestionPipelineFingerprint` in allen sechs Baseline-Dateien im selben PR nach** — bei
einem im Korpus nicht vorkommenden Format als reine Fixpunkt-Ergänzung ohne neuen Messlauf
(genau das Verfahren, das PR #1196 vorführt), sonst als Teil des ohnehin fälligen
Vorher-Nachher-Laufs aus Schritt 2 oben. `PipelinePathIsolationTest`
(`committedIngestionPipelineFingerprintsMatchTheRealRegistry`) prüft das Docker-frei in
`check` — ein vergessenes Nachziehen fällt dort auf, nicht erst nach ~70 Minuten im
nächtlichen `evaluateRetrieval`-Lauf.

---

## Teil 3 — Formatzulassung: was dazukommt und in welcher Reihenfolge

Die Reihenfolge ist eine Maintainer-Entscheidung nach Aufwand und Nutzen, keine Ableitung aus der
Verwaltungsrelevanz allein. Jeder Punkt ist ein eigenes Issue mit dem Dreischritt aus dem vorigen
Abschnitt, soweit er einschlägig ist.

### 1. Scan-Erkennung und Bestandsprüfung

Dieser Punkt steht vor der Formatarbeit, weil er kein Format hinzufügt, sondern eine **stille
Fehlfunktion des heutigen Bestands** behebt. Ein PDF ohne Textebene wird heute erfolgreich „indiziert"
und landet mit null Chunks im Bestand: Es steht in der Dokumentliste, ist aber unauffindbar. Für die
Nutzerin sieht das aus wie eine schlechte Antwort, nicht wie ein fehlendes Dokument — der teuerste
Fehler, den eine Aufnahmestrecke machen kann.

Das Issue ist **unabhängig von Docling und von jeder OCR-Entscheidung** und wartet nicht auf sie. Es
besteht aus zwei Teilen:

**(1) Erkennung und klare Abweisung beim Ingest.** Ein PDF ohne extrahierbaren Text wird als solches
erkannt und mit einer verständlichen Meldung abgewiesen — „enthält keinen extrahierbaren Text,
vermutlich ein Scan; für diese Datei ist Texterkennung nötig, die derzeit nicht eingerichtet ist" —
statt mit null Chunks als erfolgreich zu gelten. Die Datei erscheint in der Zählung des
Indizierungslaufs als übersprungen und wird namentlich protokolliert, wie jede andere abgewiesene Datei
auch. Dasselbe gilt für TIFF, PNG und JPEG als Einzelscans, sobald sie zugelassen werden.

**(2) Einmalige Bestandsprüfung mit Bericht je Bibliothek.** Der bestehende Bestand enthält diese
Dokumente bereits, und die Erkennung wirkt nur nach vorn. Ein einmaliger Prüflauf ermittelt deshalb
**als indiziert geführte Dokumente mit null oder auffällig wenigen Chunks** und berichtet sie je
Bibliothek — mit Dateiname, Größe und Chunk-Zahl, damit die zuständige Stelle entscheiden kann, was mit
ihnen geschieht.

Diese Kennzahl bleibt anschließend **dauerhaft auf der Administrationsseite** stehen (siehe
[Hybrides Retrieval](./hybrid-retrieval.md#was-die-seite-anzeigt), Indexstatus je Bibliothek). Eine
einmalige Bereinigung, die den Zustand nicht sichtbar hält, wiederholt das Problem beim nächsten
Import; „null Chunks trotz erfolgreicher Aufnahme" ist ein Betriebszustand und gehört dorthin, wo
Betriebszustände stehen.

Der Dreischritt aus dem vorigen Abschnitt ist hier nicht einschlägig: Es entsteht kein neuer Zuschnitt,
kein Chunk ändert sich, die Baseline bleibt unberührt.

#### Umgesetzt (#1055)

Beide Teile sind gebaut. **(1) Erkennung und Abweisung:** Jede `DocumentPipeline` meldet für ein
Dokument ohne extrahierbaren Text `NO_EXTRACTABLE_TEXT` statt `CHUNKED` mit null Chunks — der
gemeinsame Guard, den `TikaFallbackPipeline` und jede seither hinzugekommene Typ-Pipeline (PDF, DOCX,
PPTX, Tabellen, HTML, E-Mail) für ihr eigenes Format beantworten, siehe [Teil 1](#umgesetzt-die-abstraktion-selbst-1056).
Eine so abgewiesene Datei erscheint in der Zählung des Indizierungslaufs als übersprungen und wird
namentlich protokolliert, wie jede andere abgewiesene Datei auch. **(2) Bestandsprüfung:**
`LowChunkDocumentAuditService` ermittelt als indiziert geführte Dokumente mit null oder auffällig
wenigen Chunks (`chunkCountThreshold`, Vorgabe konfigurierbar über den Aufruf) und berichtet sie je
Bibliothek mit Dateiname, Größe und Chunk-Zahl.

Die Kennzahl bleibt **dauerhaft** abfragbar, nicht nur als einmaliger Bericht: `GET
/api/v1/admin/indexing/low-chunk-documents` (`SYSTEM_ADMIN`, auf die eigene Organisation begrenzt,
seitenweise) liefert sie bei jedem Aufruf frisch aus dem aktuellen Bestand. Die UI-Anzeige auf der
Administrationsseite „Suche & Indexierung" folgt mit #1053 — bis dahin ist die Kennzahl über den
Endpunkt direkt abfragbar, aber ohne eigene Oberfläche.

Kein Dreischritt zur Baseline: Es ist kein Zuschnitt entstanden, kein Chunk hat sich geändert.

### 2. ODF — ODT, ODS, ODP

Viele Behörden arbeiten mit LibreOffice. Tika parst ODF nativ; es fehlt praktisch nur der Eintrag in
der Zulassungsliste und in der Medientyp-Zuordnung der Formaterkennung.

**Zuschnitt (Zusage korrigiert, #1104):** Ursprünglich hier zugesagt war „wie die jeweiligen
Microsoft-Pendants — ODT wie DOCX, ODS wie XLSX, ODP wie PPTX, die Pipelines sind dieselben". Das ist
seit #1104 uneinlösbar: Apache POI, das `DocxDocumentPipeline` und `PptxDocumentPipeline` für ihren
strukturbewussten Zuschnitt verwenden, liest kein ODF — POI deckt OOXML (DOCX/PPTX/XLSX) und die
alten Binärformate ab, nie OpenDocument (siehe die gleiche Einschränkung unter
[Punkt 3](#3-xlsx-und-csv)). Nur ODS bekommt tatsächlich einen strukturerhaltenden Zuschnitt, über
einen eigenen, POI-unabhängigen ODF-Leser (`TabularDocumentPipeline`, #1058) — „ODS wie XLSX" gilt
also im Ergebnis, aber nicht über dieselbe Pipeline-Implementierung. **Seit #1110 gilt dasselbe auch
für ODT und ODP**: `OdtDocumentPipeline`/`OdpDocumentPipeline` lesen `content.xml` über einen
eigenen, POI-unabhängigen ODF-SAX-Leser, im selben Stil wie `TabularDocumentPipeline`s ODS-Leser —
„ODT wie DOCX, ODP wie PPTX" gilt seitdem also ebenfalls im Ergebnis, aber über eine eigene
Pipeline-Implementierung statt über POI.

#### Umgesetzt (#1057)

`.odt`, `.ods` und `.odp` sind in `SupportedDocumentFormats` zugelassen, mit den jeweils eindeutigen
ODF-Medientypen (`application/vnd.oasis.opendocument.{text,spreadsheet,presentation}`) als
strikte Erkennungsgrenze — anders als bei OOXML gibt es keinen generischen, unaufgelösten
ODF-Containertyp, den es zusätzlich abzuweisen gälte. Da weder für DOCX/PPTX noch für XLSX bereits
eine eigene `DocumentPipeline` existiert (Stand #1056/#1058), läuft auch ODF vollständig über die
`TikaFallbackPipeline` — das Routing über `SupportedDocumentFormats.decideForFileName` reicht dafür
aus, ohne dass die Registry oder eine neue Pipeline-Klasse etwas dazulernen musste. Eine ODT-, ODS-
oder ODP-Datei ohne extrahierbaren Text wird über denselben generischen Leer-Chunk-Guard der
Fallback-Pipeline abgewiesen (`NO_EXTRACTABLE_TEXT` statt `INDEXED` mit null Chunks, #1055), nicht
über eine formatspezifische Prüfung.

**ODS wird seit #1058 von `TabularDocumentPipeline` mitbedient**, nicht mehr von der
Tika-Fallback-Pipeline: „ODS wie XLSX" gilt seitdem auch für den Reader, über einen eigenen,
POI-unabhängigen ODF-XML-Leser (POI selbst versteht kein ODF) — siehe die Begründung unter
[Punkt 3](#3-xlsx-und-csv).

Baseline unberührt — kein Korpusdokument dieses Typs. (Diese Feststellung galt zum Zeitpunkt
von #1058, vor `ingestionPipelineFingerprint`; die seit #1144 zusätzliche Auflage — den
Abdruck bei jedem `TabularDocumentPipeline`-Versions-Bump nachzuziehen — steht oben im
Abschnitt "Baseline-Aktualisierung als Schritt jedes Format-Issues".)

#### Umgesetzt (#1110, styles.xml seit #1145)

`OdtDocumentPipeline` (`id` `odt`, Version 2) und `OdpDocumentPipeline` (`id` `odp`, Version 2)
beanspruchen `.odt` bzw. `.odp` in der `DocumentPipelineRegistry` und lösen damit die
`TikaFallbackPipeline` für beide Formate ab. Beide lesen `content.xml` (eine ODT-/ODP-Datei ist wie
ODS ein ZIP-Archiv) direkt über einen gehärteten SAX-Parser, geteilt über `OdfContentXml` — dieselbe
XXE-Härtung (kein `<!DOCTYPE …>`, keine externen Entitäten) und derselbe Byte-Deckel auf den
entpackten `content.xml`-Strom (`opaa.indexing.odf.max-content-xml-bytes`, gesetzt 10 MiB) wie
`TabularDocumentPipeline`s ODS-Leser. Ein zweiter Element-Deckel gilt zusätzlich pro Format
(`opaa.indexing.odf.max-odt-paragraphs` bzw. `opaa.indexing.odf.max-odp-slides`, je gesetzt 50.000
bzw. 5.000) — anders als beim ODS-Leser wird `table:number-columns-repeated`/
`table:number-rows-repeated` hier nicht expandiert (eine Tabelle in einem Textdokument oder einer
Folie wird elementweise gelesen), sodass für diese beiden Attribute kein eigener
Verstärkungs-Deckel nötig ist. `text:s`s eigenes `text:c`-Wiederholungsattribut ist ein eigener
Verstärkungsvektor und braucht deshalb zwei Deckel: `opaa.indexing.odf.max-space-repeat` (gesetzt
1.000) begrenzt ein einzelnes `text:s`-Element, `opaa.indexing.odf.max-text-characters` (gesetzt
10.000.000) begrenzt zusätzlich, kumulativ über das ganze Dokument, wie viele Zeichen insgesamt in
einen Absatz-/Zellen-Textpuffer wachsen dürfen — ohne diesen zweiten Deckel summieren sich beliebig
viele `text:s`-Elemente innerhalb desselben Absatzes unbegrenzt, weil der Puffer nur einmal je Absatz
zurückgesetzt wird (#1143).

- **ODT** entspricht fachlich `DocxDocumentPipeline`: Die Gliederungsebene kommt direkt aus `text:h`s
  eigenem `text:outline-level`-Attribut (kein Stilname-Abgleich nötig, anders als bei DOCX' eingebauten
  Word-Formatvorlagen), mit Abbruch der Schnittebene bei 3 wie bei DOCX. Eine `table:table` wird
  zellenweise in einen einzelnen Fließtext-Absatz je Tabelle gelesen, nie als Überschrift.

**Bewusste Bestandsregression: eine in eine Tabellenzelle verschachtelte Tabelle geht vollständig
verloren.** Sowohl `OdtDocumentPipeline` als auch `OdpDocumentPipeline` lesen die äußere Tabelle
korrekt zeilenweise weiter — die Trägerzeile und ihre übrigen Zellen bleiben intakt —, aber der
Inhalt der verschachtelten Tabelle selbst wird verworfen, nicht etwa in die Trägerzelle
übernommen. Über `TikaFallbackPipeline` war dieser Inhalt bisher (unstrukturiert) mit indiziert;
mit den beiden ODF-Pipelines ist er es nicht mehr (#1110/#1143).
- **ODP** entspricht fachlich `PptxDocumentPipeline`: eine Folie (`draw:page`) = ein Chunk. Die Rolle
  eines Rahmens kommt aus seinem eigenen `presentation:class`-Attribut — `"title"` wird Fundort und
  führende Zeile des Chunks, jeder andere Rahmen (auch `"subtitle"`) wird Fließtext, Text in
  `presentation:notes` wird als eigener, benannter Absatz angehängt, mit denselben ausgefilterten
  Platzhalterklassen (`header`/`footer`/`date-time`/`page-number`) wie bei PPTX. Eine Präsentation, in
  der keine Folie Text trägt, meldet `NO_EXTRACTABLE_TEXT` — siehe die Guard-Regel weiter unten, die
  für alle drei Kopf-/Fußzeilen-/Masterfolien-tragenden Formate einheitlich gilt.

**`styles.xml` wird seit #1145 mitgelesen.** Beide Pipelines lesen zusätzlich `styles.xml` — über
denselben gehärteten `OdfContentXml`-Leser, mit demselben Byte-Deckel je Eintrag
(`opaa.indexing.odf.max-content-xml-bytes`, gilt pro Lesevorgang, nicht als über beide Einträge
geteiltes Budget) und derselben `text:s`-Härtung wie `content.xml`; der Zeichen-Budget-Zähler
(`opaa.indexing.odf.max-text-characters`) läuft dagegen je Handler eigenständig, ist also ebenfalls
ein Budget pro Eintrag, kein gemeinsames — und der Element-Deckel (`max-odt-paragraphs`/
`max-odp-slides`) gilt ausschließlich für `content.xml`, nicht für `styles.xml`. Kopf-/Fußzeilentext
(ODT: `style:header`/`style:footer` **und** ihre `-left`/`-first`-Varianten einer
`style:master-page` — ein Dokument mit „Erste Seite anders“, der übliche Fall eines deutschen
Behördenbriefkopfs, trägt seinen Briefkopf ausschließlich in der `-first`-Variante) und
Masterfolien-Text (ODP: jeder Absatztext innerhalb einer `style:master-page`, mit Ausnahme der
Platzhalterklassen `header`/`footer`/`date-time`/`page-number`, die schon bei den Notizen
ausgefiltert werden) werden nicht pro Seite/Folie dupliziert und nicht verworfen, sondern als **ein
einziger, deduplizierter führender Chunk** aufgenommen (`location` „Kopf-/Fußzeile“ bzw.
„Masterfolie“) — dieselbe `RepeatingHeaderChunk`-Bauweise wie bei `DocxDocumentPipeline` (siehe
unten). **Dedupliziert** heißt hier wortwörtlich: Zwei Absätze, deren auf Leerraum normalisierter
Text übereinstimmt — etwa dieselbe Fußzeile, wiederholt über mehrere Seitenvorlagen oder über
Standard-/Links-/Erste-Seite-Varianten —, tragen nur einmal bei; die Normalisierung schließt geschützte Leerzeichen (U+00A0, U+202F) ein, da diese in Behördenkopfzeilen als Spaltentrenner üblich sind und ein bloßes `\s` sie nicht erfasst. Ein ODF-Feldelement
(`text:page-number`, `text:page-count`, `text:date`, `text:time`) wird von der Sammlung
ausgenommen — sein zuletzt berechneter Wert ist beim nächsten Speichern falsch und kein
Dokumenteninhalt; als Netz darunter verwirft `RepeatingHeaderChunk` zusätzlich jeden Kandidaten,
dessen bereinigter Text keinen einzigen Buchstaben enthält (etwa eine Fußzeile, die nur aus dem
Seitenzahlfeld bestand). Ein fehlender `styles.xml`-Eintrag oder ein Dokument ohne Kopf-/Fußzeile
bzw. Masterfolien-Text bleibt unverändert (kein zusätzlicher Chunk); ein XXE-Versuch oder eine
Grenzüberschreitung in `styles.xml` kostet nur diesen führenden Chunk (`log.warn`, Dokument sonst
unverändert indiziert) — anders als bei `content.xml` scheitert dabei nicht das ganze Dokument,
weil `styles.xml` ergänzenden, nicht tragenden Inhalt liefert.

**Eine Guard-Regel für alle drei Formate: Kopf-/Fußzeilen- bzw. Masterfolien-Text rettet ein
sonst inhaltsleeres Dokument nie vor `NO_CONTENT`/`NO_EXTRACTABLE_TEXT`.** `OdtDocumentPipeline`,
`OdpDocumentPipeline` und `DocxDocumentPipeline` prüfen diesen Guard ausschließlich gegen den
Körperinhalt (`content.xml` bzw. `getBodyElements()`) und hängen den führenden Kopf-/Fußzeilen-
bzw. Masterfolien-Chunk erst danach an — nie umgekehrt. Der Grund gilt wörtlich für alle drei:
Kopf-/Fußzeilen- und Masterfolien-Text ist Vorlagentext, auf jeder Seite/Folie gleich präsent,
unabhängig davon, ob das Dokument selbst eine Textebene hat oder ein reiner Scan ist — er ist
damit kein Beleg für eigenen Inhalt. Ein gescannter Behördenbrief mit Briefkopf muss als
OCR-bedürftig sichtbar bleiben (`NO_EXTRACTABLE_TEXT`/`NO_CONTENT`), nicht als erfolgreich
indiziert mit einem einzigen Briefkopf-Chunk gelten — dieselbe stille Leer-Index-Fehlfunktion aus
#1055, die für PPTX-Scanpräsentationen bereits behoben ist (Teil 3, Punkt 1).

Vor #1145 war dieser Text über `TikaFallbackPipeline` indiziert, mit `OdtDocumentPipeline`/
`OdpDocumentPipeline` seit #1110 aber nicht mehr — eine bewusst in Kauf genommene
Bestandsregression, die #1145 behebt.

**Reindex-Nachzug (#1105):** Ein bereits als ODT/ODP indizierter Bestand trägt heute noch
`tika-fallback` als Pipeline-Metadatum. Der in #1105 gebaute Fehlrouting-Zweig von
`PipelineReindexService#selectStaleDocuments` erkennt diesen Fall generisch über
`DocumentPipeline#handledFormats()` der neu registrierten Pipeline (keine ODT-/ODP-spezifische
Anpassung nötig) und zieht solche Dokumente beim nächsten `reindexBatch`-Aufruf nach.

Baseline unberührt — der bestehende Evaluierungskorpus enthält keine ODT-/ODP-Dokumente.

### 3. XLSX und CSV

Gebührenverzeichnisse, Zuständigkeitslisten und Haushaltsdaten sind das Rückgrat vieler
Auskunftsfragen und heute gar nicht im Bestand.

**Die Anforderung ist nicht „Tabellen lesen", sondern „Tabellenstruktur erhalten".** Tika kann
XLSX-Text extrahieren; das Ergebnis ist eine zu Fließtext geglättete Zahlenwüste und in dieser Form
schlechter als nichts. Die Pipeline liest deshalb über POI blatt- und zellenweise und schneidet nach
logischen Tabellen bzw. Zeilengruppen, mit **wiederholten Spaltenköpfen in jedem Chunk**.

CSV kommt dabei mit einer eigenen Schwierigkeit: Eine CSV-Datei ist am Inhalt nicht sicher von einer
Klartext- oder Markdown-Datei zu unterscheiden — die gleiche Mehrdeutigkeit, wegen der `.md` und
`.txt` heute zusätzlich ihre Endung nachweisen müssen. CSV wird deshalb in derselben Weise behandelt:
Inhalt muss Text sein **und** die Datei muss `.csv` heißen.

#### Umgesetzt (#1058)

`TabularDocumentPipeline` (`id` `tabular`, Version 1) beansprucht `.xlsx`, `.csv` und `.ods` in der
`DocumentPipelineRegistry`. XLSX wird blatt- und zellenweise über Apache POI gelesen, CSV über einen
Trennzeichen-erkennenden Parser (Komma, Semikolon, Tabulator — die reale Exportvarianten, siehe
Test-Fixtures). **ODS liest POI nicht** — POI deckt OOXML (XLSX/DOCX/PPTX) und die alten
Binärformate ab, nie OpenDocument. Statt einer vollen ODF-Bibliothek (z. B. ODF Toolkit) für einen
einzigen, schmalen Lesezugriff liest die Pipeline `content.xml` (eine ODS-Datei ist ein ZIP-Archiv)
direkt über einen gehärteten SAX-Parser — `table:table`/`table:table-row`/`table:table-cell` sind
schlichtes, wohlgeformtes XML. Damit bedient dieselbe Pipeline auch das „ODS wie XLSX" aus
[Punkt 2](#2-odf--odt-ods-odp): #1057 lässt `.ods` zu, dieses Issue liest es strukturerhaltend statt
über den Tika-Fallback. Alle drei Leser teilen sich denselben Zuschnitt:

- Die erste nicht-leere Zeile eines Blatts bzw. der Datei ist die Kopfzeile; jeder folgende Chunk trägt
  sie erneut, zusammen mit einer Strukturkontext-Zeile (`Blatt: … · Tabelle: …` für XLSX/ODS, `Tabelle:
  …` für CSV) — direkt im Chunk-Text, nicht als separates Metadatenfeld. Das war bis #1107 auch
  technisch erzwungen (`FileProcessingService` übertrug eine feste, für alle Pipelines gemeinsame
  Metadatenmenge auf einen gespeicherten Chunk); seit `DocumentPipeline#passthroughMetadataKeys()`
  könnte diese Pipeline ein eigenes Metadatenfeld deklarieren, tut es aber unverändert nicht — die
  Zeile geht damit auch in den Volltextindex, den ein separates Metadatenfeld nicht erreicht (Regel
  (a)), und bleibt an derselben Stelle zitierfähig wie der übrige Chunk-Text. Für XLSX/ODS fallen Blatt- und Tabellenname
  zusammen: Excels separates Konzept „definierte Tabelle" wird nicht eigens erkannt.
- Eine Zeilengruppe von bis zu 50 Datenzeilen bildet einen Chunk, vorzeitig geschlossen, wenn die
  nächste Zeile den Chunk über 6.000 Zeichen triebe (Schutz gegen eine Riesenzeile mit hunderten
  Spalten oder einer sehr langen Zelle) — eine einzelne Zeile, die diese Grenze für sich allein schon
  überschreitet, wird trotzdem als eigener Chunk ausgegeben, statt mitten in der Zeile geschnitten oder
  verworfen zu werden. **Gesetzt, nicht gemessen** (siehe
  [Chunk-Größen](#chunk-größen-gemessen-wo-messmaterial-existiert--und-sonst-ehrlich-gesetzt)): Weder
  der bestehende Evaluierungskorpus noch die geplante Verwaltungs-Evaldomäne (#1036) enthalten heute
  Tabellenblätter mit kuratierter Ground Truth.
- Ein leeres Blatt liefert keinen Chunk; ein Blatt oder eine Datei mit zwei oder mehr Zeilen, von
  denen nach der Kopfzeile keine eine Datenzeile ist, ebenfalls nicht. Trägt kein Blatt einer
  Arbeitsmappe bzw. keine Zeile einer CSV-Datei Nutzdaten, meldet die Pipeline
  `NO_EXTRACTABLE_TEXT` — dasselbe Ergebnis, das `TikaFallbackPipeline` für Text meldet, der auf
  nichts herunter zerlegt (siehe [Punkt 1](#1-scan-erkennung-und-bestandsprüfung)).
- **Ausnahme: eine einzelne Zeile ist immer Inhalt, nie eine leere Kopfzeile.** Ein Blatt oder eine
  Datei, die nie mehr als eine nicht-leere Zeile hatte, hat keinen Kopfzeile-/Datenzeile-Unterschied
  zu treffen — die eine Zeile wird als eigener Chunk ausgegeben, unabhängig von ihrer Spaltenzahl.
  Das gilt sowohl für eine einzelne Zelle (ein Tabellenblatt als schlichter Textcontainer) als auch
  für eine einzelne, mehrspaltige Zeile (etwa eine einzeilige Ergebnistabelle) — beides sind
  Nutzdaten, deren Verwerfen ein stiller Datenverlust wäre. Eine echte, mehrspaltige Kopfzeile ohne
  jede Datenzeile ist von einer einzeiligen Ergebnistabelle strukturell nicht unterscheidbar, sobald
  Leerzeilen herausgefiltert sind — die Pipeline nimmt bewusst das Risiko in Kauf, gelegentlich eine
  reine Feldnamen-Zeile zu indizieren, statt gelegentlich echte Daten zu verwerfen.
- Eine einzelne Zeile, die für sich schon die 6.000-Zeichen-Grenze überschreitet, wird zusätzlich
  auf eine harte Obergrenze von 20.000 Zeichen gekürzt (mit Protokolleintrag und sichtbarem
  „[…gekürzt]"-Vermerk) — ohne diese zweite Grenze würde eine echte Riesenzeile unbegrenzt an das
  Einbettungsmodell gehen und dort am Token-Limit scheitern, statt beim Zerlegen selbst zu enden.
- Die Spaltenzahl je Zeile ist für XLSX und ODS gleichermaßen gedeckelt
  (`opaa.indexing.tabular.max-row-columns`, gesetzt 200) — eine überbreite Zeile wird abgeschnitten
  statt verworfen, mit Protokolleintrag je betroffenem Blatt.
- ODS-eigene Grenzfälle: `table:number-columns-repeated` — ODF-Exporte polstern eine Zeile
  routinemäßig mit einer einzigen wiederholten Leerzelle bis zur vollen Blattbreite (bis zu 16384) —
  wird pro Zelle (`opaa.indexing.tabular.max-ods-cell-repeat`, gesetzt 50) und pro Zeile (dieselbe
  Spaltengrenze wie oben) gedeckelt; `table:number-rows-repeated` wird nicht expandiert, eine
  wiederholte Zeile zählt einmal, schaltet aber die laufende Zeilennummerierung um den vollen
  Wiederholungsumfang weiter, damit eine Fundstelle nach einer Leerzeilen-Lücke die richtige
  Zeilennummer trägt. Der SAX-Parser lehnt eine `<!DOCTYPE …>` in `content.xml` ab (XXE-Härtung) —
  die Datei kommt aus einem hochgeladenen bzw. indizierten Dokument, nie aus vertrauenswürdiger
  Quelle. Zwei weitere, unabhängige Zip-Bomb-Wächter — ein Byte-Deckel auf den entpackten
  `content.xml`-Strom (`opaa.indexing.tabular.max-ods-content-xml-bytes`, gesetzt 10 MiB) und ein
  Zeilen-Deckel auf den Parse-Vorgang selbst (`opaa.indexing.tabular.max-ods-rows`, gesetzt
  100.000) — brechen mit einer benannten Abweisung ab, statt Speicher unbegrenzt zu verbrauchen; ein
  kleines, stark repetitives `content.xml` könnte sonst unter dem Byte-Deckel bleiben und trotzdem
  unverhältnismäßig viele Zeilen beschreiben.

**Baseline unberührt** — der bestehende Evaluierungskorpus enthält keine XLSX-, CSV- oder
ODS-Dokumente.

### 4. HTML

Intranet-Seiten, Government-Site-Builder-Auftritte, Ratsinformationssysteme. Heute nur mittelbar über
den Feed-Weg erreichbar, der bereits extrahierten Artikeltext liefert (siehe
[Wissensquellen](./knowledge-sources.md)).

**Kernproblem ist Boilerplate.** Eine HTML-Seite besteht zu erheblichen Teilen aus Navigation,
Fußzeile, Cookie-Hinweis und Seitenleiste. Tika liefert das alles als Inhalt mit; das Ergebnis sind
Chunks, die auf jeder Seite eines Auftritts nahezu gleich aussehen und die Trefferliste mit
Wiederholungen füllen. `JsoupDocumentReader` mit CSS-Selektoren erlaubt, den Hauptinhalt zu
adressieren. Der Zuschnitt folgt den Überschriften h1–h3.

#### Umgesetzt (#1059)

`HtmlDocumentPipeline` (`id` `html`, Version 1) beansprucht `.html` in der
`DocumentPipelineRegistry`; `.html` ist dafür neu in `SupportedDocumentFormats` zugelassen, über
den unzweideutigen Tika-Medientyp `text/html` (bzw. `application/xhtml+xml`) — wie bei PDF/DOCX ein
strenger, inhaltsbasierter Treffer, keine text-tolerante Sonderregel wie bei Markdown/Klartext/CSV.
Damit ist HTML nicht mehr auf den Feed-Weg angewiesen: Sowohl der Verzeichnis-Crawl
(`UrlIndexingExecutor`) als auch das Dateisystem erkennen und indizieren `.html`-Dateien jetzt
direkt über dieselbe Pipeline.

**Statt des in der Spezifikation genannten Spring-AI-`JsoupDocumentReader`** liest die Pipeline
direkt über `org.jsoup:jsoup` (bereits Projektabhängigkeit, von `DetailPageExtractor` für den
RSS-Weg genutzt) — kein solches Spring-AI-Modul liegt auf dem Klassenpfad, und Boilerplate-Entfernung
plus Überschriftenschnitt sind mit Jsoup unmittelbar wenige hundert Zeilen, keine Rechtfertigung für
eine zusätzliche Abhängigkeit. Dieselbe Abwägung trifft `TabularDocumentPipeline` bereits für Apache
POI statt eines Spring-AI-Tabellen-Readers.

**Boilerplate-Entfernung nur außerhalb des gewählten Inhaltsbereichs.** `nav`, `header`, `footer`,
`aside`, die zugehörigen ARIA-Rollen sowie gängige Cookie-Banner-Selektoren werden entfernt —
dieselbe Menge, die `DetailPageExtractor` für eine RSS-Detailseite bereits verwendet, um
Cookie-Banner-Marker ergänzt —, aber nur dort, wo sie **nicht** innerhalb des adressierten
Hauptinhalts liegen: Ein Standard-CMS-Artikel führt legitim ein eigenes `<header>` (Titel,
Stand-Datum) oder `<footer>` (Autor, Schlagworte), und ein unbedingtes Entfernen würde diese
zusammen mit der umgebenden Seitenchrome verwerfen (#1059 Review, Befund 4). Der Hauptinhalt wird
über `main, article, [role=main]` adressiert — derselbe Selektor, den
`IndexingProperties.Rss#DEFAULT_MAIN_CONTENT_SELECTOR` schon für die überwiegende Mehrheit
deutscher Verwaltungs-CMS-Templates für ausreichend befunden hat; **jeder** Treffer wird
verarbeitet, nicht nur der erste (#1059 Review, Befund 5) — eine Übersichtsseite mit mehreren
`<article>`-Teasern verliert damit nicht alle bis auf den ersten. Nur wenn der Selektor gar nichts
trifft, fällt die Pipeline auf `body` zurück; in diesem Fall gilt die unbedingte,
seitenweite Entfernung, weil es dann keinen engeren Bereich gibt, dessen eigene Chrome zu erhalten
wäre.

**Zuschnitt:** Ein neuer Chunk beginnt bei jeder h1-h3-Überschrift; h4-h6 bleiben Teil des
umgebenden Abschnitts. Jeder Chunk trägt seinen Überschriftenpfad doppelt: als bestehendes,
generisches `location`-Metadatenfeld (derselbe Kanal, über den `ChunkLocationResolver`
Seiten-/Überschriftenstruktur aus flachem Text rekonstruiert) **und** als erste Zeile des
Chunk-Texts selbst (#1059 Review, Befund 6) — ein Metadatenfeld allein ist für das Einbettungsmodell
und den lexikalischen Pfad (#1097) unerreichbar. Eine Überschrift ohne jeden Textkörper wird
trotzdem als eigener, einzeiliger Chunk ausgegeben, statt fälschlich als `NO_EXTRACTABLE_TEXT` zu
gelten. Text vor der ersten Überschrift wird als eigener, pfadloser Chunk ausgegeben.

**Größenkontrolle über Blockgrenzen, nicht nur über einen harten Deckel** (#1059 Review, Befund 3):
Ein Abschnitt ohne weitere Gliederung — eine „Div-Suppe" oder eine Seite, deren §-Überschriften nur
als `<p><strong>…</strong></p>` ausgezeichnet sind — wird an Blockgrenzen (Absätze, Listenpunkte,
Tabellenzeilen) in mehrere, je höchstens 4.000 Zeichen große Chunks weitergeschnitten, statt einen
einzigen, mit der Seite mitwachsenden Chunk zu bilden. **Gesetzt, nicht gemessen** — der
Evaluierungskorpus enthält bislang keine HTML-Dokumente (siehe unten); 4.000 Zeichen liegen in der
Größenordnung des bestehenden Bestands (`opaa.indexing.chunk-size` = 1000 Token). Eine harte
Obergrenze von 20.000 Zeichen bleibt als **letzter Rückfall** bestehen, wenn ein einzelner Block
(z. B. ein Absatz ohne jede innere Gliederung) für sich allein schon diese Grenze sprengt; betroffener
Text wird dann mit sichtbarem „[…gekürzt]"-Vermerk gekappt, über dieselbe geteilte
`HeadingSectionSplitter#HARD_CHUNK_CHAR_LIMIT`/`#capChunkLength`-Logik wie die
überschriftenbasierten Pipelines (#1108) — die Mail- und die Tika-Fallback-Pipeline nutzen
`HeadingSectionSplitter` nicht.

**Wortgrenzen an der rohen Textquelle, nicht pauschal** (#1059 Review, Befund 7): Inline-Auszeichnung
wie `<b>Personal</b>ausweis` darf keinen künstlichen Leerraum einfügen („Personal ausweis"). Ob
zwischen zwei Textfragmenten ein Trennzeichen eingefügt wird, entscheidet, ob an dieser Stelle im
Quelltext tatsächlich Leerraum stand — dasselbe Verhalten, das Jsoups eigenes `Element#text()`
bereits für eine einzelne Elementauswahl zeigt.

Eine Seite, deren gesamter Inhalt Boilerplate ist (nur Navigation/Fußzeile/Cookie-Banner, kein
`main`/`article` und kein sonstiger Textinhalt), meldet `NO_EXTRACTABLE_TEXT` — dasselbe Ergebnis,
das jede andere Pipeline für Text meldet, der auf nichts herunter zerlegt.

**Markdown-/Klartext-/CSV-Sonderregel geht der HTML-Erkennung vor** (#1059 Review, Befund 1): Tika
registriert `text/html` in `tika-mimetypes.xml` als Spezialisierung von `text/plain` — eine
Markdown-Datei, die mit einem rohen `<div>`/`<h1>` beginnt, wird deshalb als `text/html` erkannt.
`SupportedDocumentFormats#decideForFileName` prüft die text-tolerante Sonderregel (Inhalt *und*
Endung müssen passen) deshalb **vor** jeder strengen Erkennung, nicht nur als Rückfall — sonst würde
eine solche Datei stillschweigend über die HTML-Pipeline laufen, ohne dass auch nur ein
`FORMAT_MISMATCH` gemeldet würde.

**Grenze: Feed-Detailseiten laufen weiterhin über die Fallback-Pipeline, nicht über diese.**
`FileProcessingService#processRssEntry` übergibt den bereits extrahierten Haupttext eines
RSS-Eintrags direkt an die Tika-Fallback-Pipeline (ADR-0017, Entscheidung 2) — dieser Text war nie
eine Datei und durchläuft das inhaltsbasierte Routing der `DocumentPipelineRegistry` gar nicht, kann
diese Pipeline also grundsätzlich nicht erreichen. Nur echte `.html`-Dateien — Verzeichnis-Crawl,
Dateisystem oder ein Anhang eines RSS-Eintrags — profitieren von `HtmlDocumentPipeline`.

**Baseline unberührt** — der bestehende Evaluierungskorpus enthält keine HTML-Dokumente.

### 5. EML und MSG

Vorgangskommunikation und Verfügungen per Mail. Tika parst beide Formate nativ, aber als einen Block
aus Kopfdaten, Text und Anhangstext.

Die Pipeline trennt drei Dinge:

- **Kopfdaten landen sowohl als Metadaten als auch als Kontextzeilen im Text des ersten Chunks.** Von,
  An, Betreff, Datum werden auf jeden Kopfdaten tragenden Chunk als Metadatenfeld geschrieben — seit
  #1164 mit Leser: `QueryService#mapSources` liest sie zurück und reicht sie bis in die
  Fundstellen-Anzeige durch (Beleganzeige; die strukturierte Filterung nach Absender/Zeitraum/Betreff
  selbst steht noch aus, siehe Issue #1211) — und zusätzlich
  einmalig, deutsch beschriftet, vor den Nachrichtentext des jeweils ersten erzeugten Chunks gesetzt —
  nicht wiederholt auf jedes Thread-Segment oder jedes weiter zerlegte Teilstück, sonst würde derselbe
  Verteilerkopf jeden Chunk eines langen Threads verwässern (#1130 Befund 1).
- **Ein Chunk je Nachricht**, bei langen Threads je Nachricht im Thread. Ein Thread ist kein Dokument,
  sondern eine Folge von Dokumenten.
- **Ein Anhang ist ein eigenes Dokument, das durch die Pipeline seines eigenen Typs läuft** (ADR-0022,
  #1183). Ein PDF-Anhang einer Mail wird von der PDF-Pipeline verarbeitet und als eigene
  `Document`-Zeile mit eigener Prüfsumme, eigener Quote und `parent_document_id` auf die Mail
  gespeichert — derselbe verallgemeinerte Anhangsweg, den RSS und (künftig) Confluence auch nutzen,
  nicht ein mail-spezifischer Sonderpfad. `MailDocumentPipeline` selbst routet dafür nichts mehr
  rekursiv; sie meldet jeden Anhang nur noch über `DocumentPipelineResult#discoveredAttachments()`.

Die Rechte- und Herkunftsfrage von Anhängen (welche Bibliothek, welche Fundstellenangabe, welcher
Beleg) folgt dabei den bestehenden Regeln des Anlagenwegs, siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md).

#### Umgesetzt (#1060)

`MailDocumentPipeline` (`id` `email`, Version 4 seit #1183) beansprucht `.eml` und `.msg` in der
`DocumentPipelineRegistry`; beide Endungen sind jetzt in `SupportedDocumentFormats` zugelassen —
unterschiedlich streng, mit einem empirisch belegten Grund: `.msg` bekommt mit
`application/vnd.ms-outlook` einen eindeutigen, strikten Medientyp (wie PDF/DOCX). `.eml` dagegen
läuft wie Markdown/Klartext/CSV über die textolerante Erkennung (Inhalt *und* Endung müssen passen) —
Tikas `message/rfc822`-Erkennung ist eine lose Textzeilen-Heuristik (sucht nach kopfzeilenartigen
Zeilen wie `Date:`/`Subject:`/`To:`/`From:` nahe dem Anfang), keine feste Byte-Signatur, und
`message/rfc822` ist in Tikas eigener Medientyp-Hierarchie tatsächlich eine Spezialisierung von
`text/plain` (#1101 Review, empirisch bestätigt). Als strikter Typ hätte das zwei Fehlklassen
erzeugt: ein `log.txt` mit `Date:`/`Status:`-Zeilen, ein `protokoll.md` mit `To:`/`From:`-Zeilen oder
ein `export.csv` mit `Date:`/`Subject:`-Spalten wären ohne jedes Mismatch-Ereignis in die Mail-Pipeline
geroutet worden — und umgekehrt wäre eine echte `.eml` mit unüblicher erster Kopfzeile (z. B.
`Authentication-Results:` oder deutsches `Von:`/`An:`) komplett abgewiesen worden, weil Tikas
Heuristik dafür nicht zuverlässig genug feuert. Die textolerante Einordnung löst beides: Die drei
genannten Fehlklassen behalten ihre eigene, namensbasierte Einordnung (das Routing entscheidet immer
anhand der *eigenen* beanspruchten Endung der Datei, nie anhand dessen, welchem texttoleranten Typ
der Inhalt bloß ähnelt), und eine echte `.eml` wird unabhängig davon angenommen, welche Kopfzeile
zuerst kommt — es genügt, dass der Inhalt überhaupt wie Text aussieht.

**Nachtrag (#1229): Der URL-Weg entscheidet zunächst über eine Leseprobe — und das reichte für MSG
nicht.** `UrlIndexingExecutor` liest von jedem Verzeichniseintrag erst nur die ersten 64 KiB
(`SupportedDocumentFormats.DETECTION_PREFIX_BYTES`) und entscheidet daran, ob der Eintrag überhaupt
vollständig geladen wird; sonst füllte jede beliebige Datei neben dem Bestand die temporäre Partition.
Bei OLE2-Dateien geht das nicht auf: Das Verzeichnis eines OLE2-Containers — der Teil, der die
`__substg1.0_`-Ströme und damit das Format benennt — kann irgendwo in der Datei liegen. Eine echte
`.msg` oberhalb der Leseprobe wird darin nur als generisches `application/x-tika-msoffice` erkannt,
und genau dieser unaufgelöste Containertyp ist als `.msg`-Inhalt bewusst *nicht* zugelassen (er würde
sonst jede nicht identifizierbare OLE2-Datei durchlassen). Ergebnis: Dieselbe Mail, die per Upload und
über `FILESYSTEM` sauber indiziert wurde, wurde am `HTTP_DIRECTORY`-Weg als „Dateiformat wird nicht
unterstützt" abgewiesen — für `.doc` galt dasselbe. Der Server-`Content-Type` war nie beteiligt; er
fließt in die Zulassung des URL-Wegs überhaupt nicht ein.

Die Auflösung führt keine zweite Zulassungsregel ein, sondern behandelt einen unaufgelösten
Containertyp als das, was er ist — **kein Urteil, nur eine zu kurze Leseprobe**:
`SupportedDocumentFormats.decideForPrefix` entscheidet weiterhin an der Leseprobe, holt aber genau
dann die vollständige Datei nach und entscheidet an ihr, wenn die Probe `application/x-tika-msoffice`
oder `application/x-tika-ooxml` ergab und daran gescheitert wäre. Jede andere Erkennung — ein
aufgelöster Typ ebenso wie Inhalt, den Tika gar nicht einordnen kann — bleibt an der Leseprobe
endgültig. Die nachgeladene Datei wird für die anschließende Verarbeitung wiederverwendet, nicht ein
zweites Mal übertragen.

Zwei Grenzen dieser Auflösung gehören dazu:

- **Der Nachlade-Weg trifft mehr als nur MSG.** Ein unaufgelöster OLE2-Container ist jede
  Legacy-Office-Datei — `.xls`, `.ppt`, `.vsd`, `.pub`, `.mpp` —, die neben dem Bestand im
  Verzeichnis liegt. Solche Einträge werden jetzt vollständig geladen und danach verworfen — seit
  #1236 aber nur bis zum Bytedeckel des Verzeichnis-Wegs
  (`opaa.indexing.crawl.max-file-size-bytes`, Vorgabe 100 MiB): Wird er überschritten, bricht die
  Übertragung ab, bevor die überschüssigen Bytes geschrieben sind, der Eintrag wird als Ablehnung
  protokolliert und übersprungen, und der Lauf geht weiter. Für ZIP-basierte Formate
  entsteht der Fall dagegen praktisch nicht: OOXML trägt `[Content_Types].xml`, ODF sein
  `mimetype` als erste Archiv-Eintragung, beide werden also schon aus der Leseprobe aufgelöst —
  belegt in `DocumentFormatParityTest` an einer DOCX oberhalb der Probe.
- **Auch die vollständige Datei löst nicht unbegrenzt auf.** Tikas `POIFSContainerDetector` liest
  höchstens sein `markLimit` (voreingestellt 128 MiB) und meldet darüber hinaus wieder den
  unaufgelösten Containertyp. Eine `.msg` jenseits dieser Grenze bleibt also abgewiesen, trotz
  vollständigem Download. Auf dem Verzeichnis-Weg ist dieser Fall seit #1236 praktisch unerreichbar:
  Der Bytedeckel (Vorgabe 100 MiB) liegt bewusst unterhalb des `markLimit`, ein so großer Eintrag
  wird also schon beim Übertragen abgewiesen. Für Upload und Dateisystem, die die vollständige Datei
  ohnehin lokal vorliegen haben, gilt die `markLimit`-Grenze unverändert.

**Zwei eigene Leser statt eines gemeinsamen Tika-Parsers**, weil Kopfdaten, Text und Anhänge getrennt
werden müssen, statt in einen Block zu fließen:

- **EML** über `org.apache.james.mime4j.dom` (bereits transitiv über `tika-parser-mail-module` auf
  dem Klassenpfad, jetzt direkt referenziert): läuft den MIME-Baum ab, wählt aus einem
  `multipart/alternative` genau eine Repräsentation (bevorzugt `text/plain`, sonst `text/html` über
  Jsoup von Markup befreit) als Nachrichtentext, und behandelt jeden weiteren Teil mit
  `Content-Disposition: attachment` oder einem Dateinamen als Anhang.
- **MSG** über Apache POI HSMF (`org.apache.poi.hsmf.MAPIMessage`, `poi-scratchpad` jetzt direkt
  referenziert): liest Betreff/Von/An/Datum/Text sowie `AttachmentChunks` für Anhänge.

**Kopfdaten landen als Chunk-Metadaten** — `ChunkMailMetadata` definiert
`mail_from`/`mail_to`/`mail_subject`/`mail_date`, deklariert über
`MailDocumentPipeline#passthroughMetadataKeys()` (#1107); `FileProcessingService#storeChunks` kopiert
sie auf den gespeicherten Chunk, genau wie es das schon für `location` tut (Teil 5, Übergabepunkt).
Seit #1164 (PR #1201) haben diese Felder einen Leser: `QueryService#mapSources` liest sie zurück, die
Fundstellen-Anzeige zeigt Absender/Datum/Betreff. Die strukturierte Filterung nach
Absender/Zeitraum/Betreff selbst steht noch aus (Issue #1211, keine Fehlmodellierung — siehe
`ChunkMailMetadata`-Javadoc). `mail_date` wird seit PR #1201 auf Sekundenpräzision gekürzt
geschrieben (`MailDocumentPipeline#renderMailDate`), damit ein künftiger Zeitraumfilter
lexikografisch sortieren kann — `Instant#toString()` allein wäre das nicht zuverlässig (siehe
`ChunkMailMetadata`-Javadoc). `MailDocumentPipeline#version()` stieg dafür 2 → 3; ein bereits
indizierter Mail-Bestand unterhalb dieser Version trägt weiterhin den alten, potenziell nicht
sortierbaren `mail_date`-Wert, bis die Betreiberin ihn über die vorhandenen
Administrationsendpunkte (`GET /pipeline-versions`, `POST /pipeline-reindex`) nachzieht — Regel (d):
„Ausgelöst wird nichts von selbst", unverändert.

**Dieselben Kopfdaten landen zusätzlich, deutsch beschriftet, als Kontextzeilen vor dem
Nachrichtentext** (#1130 Befund 1, entschieden gegen die zuvor offene Formfrage aus Teil 5, Punkt 1)
— nach dem Vorbild von `TabularDocumentPipeline`/`HtmlDocumentPipeline`/`PptxDocumentPipeline`, die
ihren Strukturkontext ebenfalls in den Chunk-Text backen, nicht nur in ein Metadatenfeld. Damit wirken
Absender, Empfänger und Betreff in Embedding **und** Volltextindex, sobald der Chunk (neu) entsteht —
eine Frage wie „Mail von Müller zum Bebauungsplan" findet eine neu oder erneut indizierte Nachricht
jetzt tatsächlich. Für den bestehenden Mail-Bestand gilt das erst nach einer gezielten
Neuindizierung: Regel (d) („Ausgelöst wird nichts von selbst") gilt unverändert — die Pipeline-Version
steigt mit diesem Zuschnitt (siehe unten), ein Altbestand unterhalb dieser Version bleibt bis zum
Nachzug beim alten, metadatenreinen Chunk-Text. Eine fehlende Angabe erzeugt keine leere Zeile.

**Das Kopfdaten-Feld `An` steht als letzte Zeile des Blocks, nicht in der natürlichen
Von/An/Betreff/Datum-Lesereihenfolge** (#1130 Befund 1, Review-Runde 3): `An` ist als einziges Feld
unbegrenzt lang (`EmlReader` rendert jeden Empfänger einzeln), ein Rundschreiben an hunderte
Empfänger würde die kurzen, aussagekräftigen Felder Betreff und Datum sonst hinter die Adressliste
verdrängen. Von/Betreff/Datum bleiben so zusammen in der ersten aus dem Kopfblock entstehenden Zeile,
unabhängig davon, wie lang `An` wird.

**Der Kopfblock wird VOR dem Zuschnitt an den Rohtext gehängt, nicht danach** — er durchläuft
`ChunkingService#chunkDocuments` wie jeder andere Text und ist deshalb selbst nicht unbegrenzt lang:
Bei einem sehr großen Verteiler zerlegt derselbe Token-Splitter, der auch einen langen Rundbrief
zerschneidet (siehe unten), den Kopfblock in mehrere `Teil j von M`-Chunks. Der Nachrichtentext folgt
dabei unmittelbar auf das letzte Feld des Kopfblocks (`An`) — bei einem großen Verteiler landet der
Nachrichtentext deshalb im letzten dieser Kopfblock-Chunks, nicht im ersten. Betreff/Datum sind aber
in jedem Fall im ersten Chunk auffindbar, weil sie vor `An` stehen.

**Ein Chunk je Nachricht, bei erkanntem Zitatverlauf ein Chunk je Nachricht im Thread** —
`MailThreadSplitter` schneidet an den Zitat-Trennzeilen, die Outlook/Thunderbird/Gmail auf Deutsch und
Englisch erzeugen (`"Am … schrieb …:"`, `"On … wrote:"`, der `-----Ursprüngliche
Nachricht-----`/`-----Original Message-----`-Block). **Gesetzt, nicht gemessen**: Eine nicht erkannte
Zitierkonvention bleibt bewusst ein einziger Chunk (falsches Negativ), statt Fließtext an einer
zufällig passenden Zeile mitten im Satz zu zerschneiden (falsches Positiv). Jedes Thread-Segment trägt
dieselben Kopfdaten der äußeren MIME-Hülle als Metadatum — die Kopfzeile einer zitierten Nachricht ist
freier Text der jeweiligen Zitierkonvention, keine zuverlässig strukturiert rückführbare Angabe. Der
Kopfblock im Chunk-Text landet dagegen **nur am Anfang der Nachricht** (im ersten nicht-leeren
Segment, vor dessen Zuschnitt), nicht auf jedem weiteren Thread-Segment und nicht erneut auf einem
später ohnehin schon eigenständig zerlegten Teilstück — sonst würde derselbe Verteilerkopf jeden Chunk
eines langen Threads verwässern, dasselbe Problem, das #1145s `RepeatingHeaderChunk` für einen
wiederholten Seitenkopf vermeidet.

**Eine Nachricht mit leerem Body bekommt einen reinen Kopfdaten-Chunk, aber nur, wenn sie mindestens
einen Anhang trägt** (#1130 Befund 1, Review-Runde 3, Entscheidung 3): Ohne diese Sonderbehandlung
würde die verbreitete „Anbei der Bescheid"-Mail ihren Anhang indizieren, aber Absender und Betreff
verlieren, weil `MailThreadSplitter` aus einem leeren Body keinen Chunk erzeugt. Der Kopfdaten-Chunk
durchläuft denselben Zuschnitt wie jeder andere Kopfblock (siehe oben) — ein großer Verteiler bei
leerem Body zerlegt sich ebenso in `Teil j von M`-Chunks. **Ohne Anhang bleibt eine leere Nachricht
`NO_EXTRACTABLE_TEXT`**: Ohne jeden eigenen Inhalt sind ihre Kopfdaten dann Vorlagentext wie ein
wiederholter Seitenkopf, kein Beleg für tatsächlichen Inhalt — dieselbe Regel, die
`DocxDocumentPipeline` für Kopf-/Fußzeilentext bereits festhält („Header/footer text never rescues
this outcome").

**Ein drittes Muster für denselben Zweck, bewusst keines der beiden bestehenden.**
`TabularDocumentPipeline` backt ihre Strukturzeile in **jeden** Chunk (Blatt-/Tabellenname ist für
jede Zeilengruppe eigenständig relevant, keine Dopplung desselben Inhalts); `RepeatingHeaderChunk`
erzeugt einen **eigenen**, von der Nachricht getrennten Chunk (ein Seitenkopf trägt für sich genommen
keinen zitierfähigen Inhalt). Der Mail-Kopf ist keines von beiden: Er gehört inhaltlich zum Anfang
einer Nachricht, nicht zu jedem ihrer Chunks, und er ist als Kontext einer konkreten Nachricht
sinnvoll zitierfähig, nicht als eigenständiger, inhaltsloser Beleg. Prepending an den Anfang der
Nachricht ist deshalb die engste Passung. **Die Folge ist bewusst in Kauf genommen:** Für eine Frage
wie „Mail von Müller zum Bebauungsplan" antworten zuverlässig nur die führenden Chunks einer Nachricht
— findet die Suche stattdessen einen späteren Chunk (eine spätere Antwort im selben Thread, oder bei
einem sehr großen Verteiler den Chunk, der den eigentlichen Nachrichtentext trägt), trägt der keinen
Von/Betreff-Kontext im Text mehr, nur noch die strukturierten `mail_*`-Metadaten. Genau das ist der
Grund, warum #1164 (PR #1201) zusätzlich zum Textweg eine strukturierte Beleganzeige nachgeliefert
hat — `QueryService#mapSources` liest die `mail_*`-Felder unabhängig davon zurück, welcher Chunk
gefunden wurde, und die Fundstellen-Anzeige zeigt Absender/Datum/Betreff auch dann, wenn der
gefundene Chunk selbst keinen Kopfblock im Text trägt.

**Ein Segment, das trotzdem zu lang für einen Chunk ist, fällt auf `ChunkingService`s gewöhnlichen
Token-Splitter zurück** (#1101 Review): Ein langer Rundbrief oder eine Weiterleitungskette ohne
erkennbare Zitat-Trennzeile würde sonst ein einzelner, unbegrenzt großer Chunk — jenseits des
Token-Limits des Einbettungsmodells, das ganze Dokument scheitert am Embedding-Aufruf. Da
`ChunkingService#chunkDocuments` für Text unterhalb der konfigurierten `opaa.indexing.chunk-size`
ohnehin nur einen einzigen, unveränderten Chunk zurückgibt, ändert sich am Normalfall (kurze
Nachricht, ein Chunk) nichts; nur ein Segment, das die Grenze tatsächlich überschreitet, wird weiter
zerlegt — derselbe Rückfall, den Token-Chunking projektweit spielt, sobald Struktur ausgeht (Teil 2,
„Der Grundsatz"). Jedes weiter zerlegte Teilstück trägt weiterhin dieselben Kopfdaten und einen
disambiguierenden Fundort (`Teil j von M`, ggf. kombiniert mit `Nachricht i von N`).

**Anhänge laufen über den verallgemeinerten Anhangsweg, nicht mehr rekursiv innerhalb dieser
Pipeline** (ADR-0022, #1183, löst #1130 Befund 2 strukturell). `EmlReader`/`MsgReader` extrahieren
jeden Anhang weiterhin in eine eigene temporäre Datei (unverändert Parse-Zeit-Aufgabe), aber
`MailDocumentPipeline` routet ihn nicht mehr selbst durch `DocumentPipelineRegistry` — sie meldet ihn
nur noch als `DiscoveredAttachment` über `DocumentPipelineResult#discoveredAttachments()`.
`FileProcessingService` übergibt jeden gemeldeten Anhang an
`io.opaa.indexing.source.attachment.AttachmentIndexer#indexAll`, denselben Weg, den RSS-Anhänge
schon seit #1182 nehmen: eigene `Document`-Zeile, eigene Prüfsumme, eigene Speicherquote,
`parent_document_id` auf die Mail, und — der eigentliche Fix — die korrekte `pipeline_id`/
`pipeline_version` der tatsächlich zuständigen Sub-Pipeline (PDF-Anhang trägt `pipeline_id=pdf`,
nicht `email`). Formatzulassung (`SupportedDocumentFormats.decideForFileName`) und die
Fehlerbehandlung je Anhang („ein defekter Anhang kostet nur ihn selbst") übernimmt jetzt
`AttachmentIndexer`, nicht mehr diese Pipeline. Ein EML-in-EML-Anhang (eine Weiterleitung) wird zu
einem eigenen `Document`, dessen eigene, rekursiv gemeldeten Anhänge wiederum `parent_document_id`
auf **diese** innere Mail setzen, nicht auf die äußerste — eine Kette, kein Sonderfall. Der
Anhangsweg ist auf **jedem** Zufluss angeschlossen, über den eine Mail in eine Bibliothek gelangt:
FILESYSTEM-Läufe (#1183), Upload (#1218) und HTTP_DIRECTORY-Läufe (#1219) — die beiden Letzteren
haben ihn zwischen #1183 und ihrem eigenen Anschluss vorübergehend nicht gehabt (gemeldete Anhänge
wurden dort verworfen); beide Lücken sind geschlossen.

**`file_path` eines Mail-Anhangs** (ADR-0022, Entscheidung 2, festgelegt in #1183):
`<file_path des Elterndokuments>/<Positionsindex>/<Dateiname>` — ein gewöhnlicher `/`-Separator
genügt, weil der Elternpfad eine *Datei* benennt: Unterhalb einer Datei kann kein reales Dateisystem
weitere Einträge führen, ein Pfad dieser Form ist also für jede echte Datei unerreichbar und
kollisionsfrei (ein `!`-Sonderzeichen nach JAR-URL-Vorbild wäre dagegen in echten Dateinamen legal
und könnte kollidieren). Der Positionsindex (0-basiert,
Extraktionsreihenfolge) disambiguiert zwei gleichnamige Anhänge derselben Mail; der eingebettete
Elternpfad allein sorgt bereits für Eindeutigkeit über verschiedene Mails hinweg und für Stabilität
über Läufe hinweg, solange die Mail-Datei selbst nicht verschoben wird. Für eine verschachtelte Mail
gilt dieselbe Regel rekursiv: Der `file_path` eines Anhangs einer weitergeleiteten Mail enthält
bereits den synthetischen `file_path` dieser weitergeleiteten Mail selbst.

**Das Original eines Anhangs wird beim Öffnen nachextrahiert, nicht beim Indizieren abgelegt**
(#1239): Die Anhangsbytes existieren während der Indizierung nur als temporäre Datei. Fragt jemand
über „Im Dokument öffnen" (`GET /documents/{id}/content`, `LibraryDocumentService#loadContent`) das
Original eines Anhangsdokuments an, lädt OPAA das Original des Wurzel-Elterndokuments über den ganz
normalen Weg seines Quelltyps (UPLOAD/FILESYSTEM von der Platte, HTTP_DIRECTORY/RSS_FEED über den
Proxy-Abruf), lässt dieselbe Pipeline es erneut parsen und streamt den Anhang an dem im `file_path`
kodierten Positionsindex; die gemeinsame Extraktion dafür ist `io.opaa.indexing.AttachmentExtractor`,
die auch der selektive Re-Index nutzt — nur so ist die Extraktionsreihenfolge (und damit die Bedeutung
des Index) dieselbe wie beim Indizieren, und es gelten dieselben Parse-Grenzen aus `MailProperties`.
Verschachtelung ist kein Sonderfall: Jede Kettenstufe ist ein weiterer Extraktionsschritt. Der
Positionsindex ist nur bei unveränderter Elterndatei aussagekräftig, deshalb muss der Dateiname des
extrahierten Anhangs zum `file_name` der Zeile passen — sonst antwortet der Endpunkt mit demselben
404 wie bei „kein Originaldokument verfügbar", statt fremde Bytes unter diesem Namen auszuliefern.
Temporäre Dateien dieses Wegs werden beim Schließen des Antwortstroms gelöscht (Spring schließt die
Ressource in jedem Fall, auch bei Abbruch durch den Client). Kein dauerhafter Zweitspeicher, keine
doppelte Quotenzählung, gleiches Verhalten für alle Quelltypen.

**Der Preis dieses Wegs, ausdrücklich benannt und seit #1243 gedeckelt:** Ein Abruf parst die
Elternnachricht weiterhin vollständig — das ist der unvermeidbare Teil. Materialisiert wird dabei
aber nur noch die *angeforderte* Anlage: Der Anfragepfad reicht die gesuchte Position über
`DocumentPipelineSource#attachmentIndex` bis in `EmlReader`/`MsgReader` durch, die jede Anlage
weiterhin in derselben Reihenfolge lesen, aber nur für diese eine eine temporäre Datei schreiben.
Statt bis zu `max-attachments-per-message` (Vorgabe 50) temporären Dateien je Abruf entsteht damit
genau eine je Kettenstufe. Bei Konnektor-Beständen kommt der vollständige Abruf des Elternoriginals
in eine weitere temporäre Datei hinzu; das bleibt so.

**Die Positionszählung bleibt dabei exakt die des unfilterten Laufs**, denn der gespeicherte Index
ist die Listenposition in `discoveredAttachments` (`FileProcessingService#processDiscoveredAttachments`).
Eine Anlage, die der unfilterte Lauf gar nicht erst meldet — weil sie `max-attachment-bytes`
überschreitet, sich nicht dekodieren lässt oder (bei MSG) ein eingebettetes Outlook-Objekt ist —,
**verbraucht deshalb auch im filternden Lauf keine Position**. Würde sie mitgezählt, verschöbe sich
die Position gegenüber der gespeicherten.

Zusätzlich begrenzt `AttachmentExtractionLimiter` den Anfragepfad (nicht den Hintergrundlauf von
`PipelineReindexService`): Abrufe **desselben Elterndokuments** laufen nacheinander, und ein globaler
Deckel (`opaa.documents.attachment-extraction.max-concurrent`, Vorgabe 4) begrenzt die Zahl
**gleichzeitig laufender** Nachextraktionen. Jede der beiden Schranken wird mit
`opaa.documents.attachment-extraction.acquire-timeout` (Vorgabe 10 s) versucht — im ungünstigsten
Fall wartet ein Abruf also das Doppelte —, danach antwortet er mit **429** und einer deutschen
Meldung statt unbegrenzt zu warten; denselben Status verwendet das Rate-Limit dieses Endpunkts
bereits.

**Was der Deckel deckelt, und was nicht:** Er begrenzt die gleichzeitig laufende Extraktion — also
Parsen, Herunterladen und Schreiben —, nicht die Lebensdauer der geschriebenen Datei. Der Platz wird
freigegeben, sobald die Extraktion zurückkehrt; gelöscht wird die temporäre Datei erst beim Schließen
des Antwortstroms. Wer langsam lädt oder den Tab offen lässt, hält seine Datei also über den Deckel
hinaus. Die Zahl gleichzeitig offener Antworten begrenzt nicht dieser Deckel, sondern das Rate-Limit
auf `GET /api/v1/documents/{documentId}/content` (`RateLimitProperties`). Die eigentliche Entlastung
dieses Wegs ist der Faktor: eine statt bis zu 50 temporären Dateien je Abruf.

Was bewusst **nicht** gebaut wurde: ein Bytes-Cache der zuletzt nachextrahierten Anhänge.
Wiederholte Klicks auf denselben Anhang parsen weiterhin erneut. Das bleibt vertretbar, weil ein
Abruf jetzt nur noch eine Anlage materialisiert und die Parallelität gedeckelt ist; ein Cache brächte
zusätzlich die Frage nach Invalidierung bei geändertem Elterndokument mit sich, ohne die es Bytes
unter dem falschen Namen ausliefern könnte.

**Nur ein Anhang ohne eigene Quellidentität wird nachextrahiert:** Ein Anhang aus dem
`AttachmentSource.Download`-Schnitt (RSS heute, Confluence künftig) trägt zwar ebenfalls
`parent_document_id`, hat als `file_path` aber seine echte Download-URL — er wird unverändert über
den Proxy-Weg geliefert. Unterschieden wird deshalb an der Pfadform (`attachmentIndexIn` erkennt den
eingebetteten Elternpfad), nicht am Vorhandensein eines Elterndokuments. Aus demselben Grund endet
auch die Kettenwanderung an der ersten Stufe mit eigener Quellidentität: Eine per RSS
heruntergeladene `.eml` ist Anlage ihres Eintrags **und** Elterndokument ihrer eigenen Mail-Anlagen —
sie wird von ihrer URL geholt, statt aus dem Eintrag extrahiert zu werden.

**Die Rekursionstiefe (Mail-in-Mail) lebt auf dem verallgemeinerten Anhangsweg, nicht mehr in dieser
Pipeline** (ADR-0022, Entscheidung 6): `AttachmentIndexer` zählt die Verschachtelungstiefe über einen
threadlokalen Zähler, sobald ein gemeldeter Anhang selbst wieder über `FileProcessingService
#processUrlFile` verarbeitet wird und dabei erneut Anhänge meldet — dieselbe Rolle, die
`MailDocumentPipeline`s eigenes `RECURSION_DEPTH`-Feld vor #1183 gespielt hat, jetzt auf der
gemeinsamen Ebene, weil auch RSS/Confluence-Anhänge grundsätzlich verschachtelt sein können.

**Drei Sicherheits-Grenzfälle, Muster `TabularProperties`** (`MailProperties`,
`opaa.indexing.mail.*`): `max-attachment-depth` (Standard 5, seit #1183 nur noch der Vorgabewert für
`AttachmentDownloadLimits#maxAttachmentDepth()` auf dem Anhangsweg, siehe oben) deckelt die
Rekursionstiefe gegen eine Mail, die sich selbst oder zyklisch weiterleitet; `max-attachments-per-message`
(gesetzt 50) deckelt die Anzahl der Anhänge — durchgesetzt direkt in der Extraktionsschleife von
`EmlReader`/`MsgReader` selbst, sodass für einen Anhang jenseits der Grenze erst gar keine temporäre
Datei entsteht; und `max-attachment-bytes` (gesetzt 50 MiB) deckelt die Größe eines einzelnen
Anhangs. Bei EML wird diese
Byte-Grenze beim Kopieren des Anhangs in eine temporäre Datei durchgesetzt (wie
`TabularDocumentPipeline`s ODS-Leser), bei MSG nur nachträglich (siehe unten). **Diese drei Grenzen
schützen Platte und nachgelagerte Verarbeitung, nicht den Parse-Vorgang selbst** — sowohl mime4j
(`BasicBodyFactory`) als auch POI (`MAPIMessage`) halten beim Parsen ohnehin jeden Teil der Nachricht,
Anhänge eingeschlossen, vollständig im Heap, bevor dieser Code auch nur entscheidet, ob ein Teil ein
Anhang ist. Die eigentliche Speichergrenze ist eine vierte, neue Eigenschaft: `max-message-bytes`
(gesetzt 100 MiB) — geprüft gegen die Größe der `.eml`/`.msg`-Datei selbst, bevor überhaupt geparst
wird, denn `FileProcessingService#processFile` erzwingt keine Einzeldateigrößen-Grenze (nur die
Speicherplatz-Quote der Bibliothek insgesamt). Bei MSG bleibt die Anhangsgrenze zusätzlich
Best-Effort: `MAPIMessage` liest die gesamte Datei samt aller Anhangsbytes vollständig in den Speicher,
bevor dieser Code sie zu sehen bekommt, sodass `max-attachment-bytes` dort nur noch verhindert, dass
ein überdimensionierter Anhang auf die Platte geschrieben und weiterverarbeitet wird — ein eingebetteter
Outlook-Anhang (ein Element als eigenes MAPI-Objekt statt als Datei) wird zudem übersprungen statt
rekonstruiert, da POI dafür keinen öffentlichen `.msg`-Writer anbietet.

**Chunk-Größe:** im Regelfall entfällt sie — eine Nachricht wird genau ein Chunk (oder einer je
Thread-Segment), nie nach Tokenzahl geschnitten. Ein Segment, das trotzdem die konfigurierte
`opaa.indexing.chunk-size` überschreitet (ein langer Rundbrief ohne erkennbare Zitatgrenze), fällt auf
denselben Token-Splitter zurück, den `TikaFallbackPipeline` ohnehin verwendet — kein eigener
Zuschnitts-Parameter dieser Pipeline, sondern der bestehende projektweite Fallback.

**`file_size` des Mail-Elterndokuments zählt seit #1183 nur noch Kopfdaten und Nachrichtentext, nicht
die Anhangsbytes** (ADR-0022, Entscheidung 6): Die rohe `.eml`/`.msg`-Datei enthält Anhänge
base64-kodiert bereits in ihrer eigenen Dateigröße; sobald ein Anhang eine eigene `Document`-Zeile
mit eigenem `fileSize` ist, würde er sonst doppelt gegen die Speicherquote der Bibliothek zählen.
`DocumentPipelineResult#contentByteSizeOverride()` trägt dafür die Summe aus Kopfblock- und
Nachrichtentext-Bytes; `FileProcessingService` überschreibt `Document#getFileSize()` damit, sobald
die Pipeline einen Wert meldet — ein reines Mail-spezifisches Detail, jede andere Pipeline lässt
diesen Kanal leer und behält die Dateigröße auf der Platte.

**Baseline unberührt** — der bestehende Evaluierungskorpus enthält keine EML- oder MSG-Dokumente.

### Ausblick: Scan-PDF und OCR (eigenes Epic)

Gescannte Altakten und unterschriebene Originale sind der größte unerschlossene Bestand und der
größte Aufwand. **Dieser Abschnitt ist ein Ausblick, kein Bauplan** — das Epic hat einen eigenen
Zuschnitt.

Drei Festlegungen stehen aber schon:

**Erstens: Scan-Erkennung ist nicht Teil dieses Epics.** Sie ist vorgezogen und steht als
[Punkt 1 der Umsetzungsreihenfolge](#1-scan-erkennung-und-bestandsprüfung) —
zusammen mit der einmaligen Bestandsprüfung. Wenn das OCR-Epic beginnt, ist der stille Leer-Index aus
[Teil 0](#was-ocr-hier-bedeutet) also bereits behoben; das Epic muss nur noch beantworten, was mit den
erkannten Scans geschieht.

**Zweitens: OCR setzt auf Docling auf, nicht auf Tika plus Tesseract.** Tikas OCR-Anbindung ist
konfigurationsanfällig (Tesseract muss im Image liegen, Sprachpakete müssen passen, die Auslösung
hängt an Schwellenwerten, die je Dokument anders greifen) und liefert am Ende flachen Text in
Lesereihenfolge — Spaltenlayouts, Tabellen und Kopfzeilen laufen ineinander. Damit wäre der
teuerste Bestand zugleich der mit dem schlechtesten Zuschnitt. Doclings integrierte OCR liefert die
Layoutanalyse mit, und das ist der eigentliche Grund für die Wahl, nicht die Zeichengenauigkeit.

Daraus folgt die Reihenfolge: **Der Docling-PoC ist Voraussetzung des OCR-Epics**, nicht umgekehrt.
Fällt der PoC negativ aus, wird die OCR-Frage neu gestellt — dann aber als eigene Entscheidung mit
eigenen Optionen, nicht als stiller Rückfall auf Tesseract.

**Drittens: OCR-Text wird als OCR-Text gekennzeichnet.** Aus Texterkennung gewonnener Inhalt ist
unsicherer als digital erzeugter; niedrige Erkennungskonfidenz gehört als Warnhinweis an den Beleg.
Das schließt an die bestehende Regel für Handschriftliches an (siehe
[Bild- und Handschriftenverständnis](./data-indexing-rag.md#bild--und-handschriftenverständnis)).
TIFF, PNG und JPEG als Einzelscans fallen unter dasselbe Epic; die Abweisungsregel für sie gilt bereits
mit Punkt 1 der Umsetzungsreihenfolge.

---

## Teil 4 — Querschnittsregeln

Diese vier Regeln gelten für **jede** Pipeline. Sie sind der Grund, warum die Typ-Pipeline mehr ist
als eine Parser-Auswahl.

### (a) Exakte Kennungen müssen den lexikalischen Suchpfad unzerlegt erreichen

Paragrafenangaben, Aktenzeichen, Erlassnummern und E-Mail-Adressen sind Identifikatoren, keine
Wörter. Ein Vektorvergleich trifft sie unzuverlässig; die Volltextsuche trifft sie exakt — aber nur,
wenn sie dort unzerlegt ankommen. „§ 3 Abs. 2 VwGebS" darf nicht durch Tokenisierung, Stemming oder
Decompounding zu Bruchstücken werden, und „AZ 31/2-2026-0815" ist kein Text, den man an Sonderzeichen
trennt — ebenso wenig „max.mustermann@example.org" (#1130).

Die Typ-Pipeline ist der Ort, an dem der Text entsteht, der in **beide** Indizes geht — Vektorindex
und Volltextindex. Sie befüllt deshalb beide, und die Kennungen gehen dabei in exakte Felder statt
durch die Analysekette. Wie die lexikalische Seite arbeitet und wie beide Listen zusammengeführt
werden, steht in [Hybrides Retrieval](./hybrid-retrieval.md); hier zählt nur die Schnittstelle: **Die Aufnahmestrecke schuldet dem lexikalischen
Pfad einen Text, in dem Kennungen intakt sind.**

### (b) Jeder Chunk trägt seinen Strukturkontext

Der heutige Embedding-Präfix führt den aus dem Dateinamen abgeleiteten Titel mit (`ChunkContextTitle`,
#933/#940). Mit strukturbewusstem Chunking kommt die eigentlich wirksame Angabe hinzu: **der
Abschnittstitel statt nur des Dateinamens**.

Aus „37,00 EUR" wird damit „Verwaltungsgebührensatzung › § 7 Gebühren für Personaldokumente › 37,00
EUR" — der Chunk aus dem Eingangsbeispiel wird auffindbar, ohne dass ein Sprachmodell dafür gelaufen
ist. Das ist die LLM-freie Ausbaustufe des Contextual Chunking, dessen Wirksamkeit extern belegt ist
(siehe
[Tech-Report 5.3](../discussions/discussion-retrieval-strategien.md#53-contextual-retrieval-anthropic)).

Der Präfix geht in Embedding **und** Volltextindex — die Anthropic-Zahlen zeigen den größeren Effekt
gerade bei der kontextualisierten lexikalischen Seite. Er ist Teil der Chunk-Darstellung, nicht seines
Rohtexts: Der zitierte Auszug im Beleg bleibt der Originalwortlaut.

### (c) Chunk-Größen entscheidet die Pipeline, nicht ein Admin-Regler

Die Chunk-Größe je Dokumentklasse ist eine Eigenschaft der Pipeline — gemessen, wo Messmaterial
existiert, sonst gesetzt und als gesetzt gekennzeichnet — und in beiden Fällen **kein
Konfigurationsknopf in der Oberfläche**. Eine Betreiberin soll nicht raten müssen, ob ihre Satzungen
mit 400 oder 800 Token besser laufen — die Antwort ist eine Messung, und die hat das Projekt zu
liefern, nicht jede Installation einzeln.

Damit fällt diese Größe in die Konfigurations-Ebene 1 (projektseitig gesetzt, betreiberseitig nicht
angeboten) im Sinne des Konfigurations-Querschnitts in
[Hybrides Retrieval](./hybrid-retrieval.md). Die globalen
`opaa.indexing.chunk-size`/`-overlap`-Eigenschaften bleiben als Fallback für die Tika-Pipeline und für
strukturlose Texte bestehen; sie hören nur auf, für **alles** zu gelten.

Die weitergehende Frage, ob eine Wissensbibliothek eine eigene Festlegung bekommen soll (Rechtsquellen
und Besprechungsnotizen vertragen nicht dieselbe Zerlegung), bleibt offen und steht unverändert in
[Wissensschicht und Retrieval](./data-indexing-rag.md#offene-fragen--zukünftige-erweiterungen). Die
Typ-Pipeline entschärft sie, weil sie den größten Teil des Unterschieds bereits über den Dokumenttyp
auffängt.

---

### (d) Jeder Chunk trägt die Version des Verfahrens, das ihn erzeugt hat

Eine Pipeline-Umstellung wirkt nicht rückwirkend. Nach jeder Umstellung liegen im selben Bestand
Chunks aus zwei Verfahren nebeneinander — die alten aus dem Token-Splitter, die neuen aus dem
strukturbewussten Zuschnitt. Ohne Kennzeichnung ist dieser Zustand **nicht feststellbar**: Man sieht
einem Chunk nicht an, nach welchen Regeln er geschnitten wurde, und damit ist weder eine gezielte
Nachbesserung noch eine ehrliche Messung möglich.

Deshalb trägt **jeder Chunk eine Pipeline-Version als Metadatum** — dasselbe Prinzip, aus dem das
Einbettungsmodell gepinnt wird (siehe
[Aktualisierung im laufenden Betrieb](./deployment-infrastructure.md#aktualisierung-im-laufenden-betrieb)),
angewandt auf das Zerlegungsverfahren statt auf den Vektorraum. Die Version wird erhöht, wenn sich der
Zuschnitt oder die erzeugten Struktur-Metadaten ändern; nicht bei einer Fehlerbehebung ohne Wirkung auf
das Ergebnis.

Daraus folgen drei Fähigkeiten:

- **Selektive Neuindizierung nach Version.** Ein Lauf kann gezielt „alle Chunks unterhalb Version N
  dieser Pipeline" neu erzeugen, statt den gesamten Bestand anzufassen. Das ist der Unterschied
  zwischen einer Umstellung, die über Nacht läuft, und einer, die niemand auslöst.
- **Wiederaufnehmbarkeit mit Fortschritt je Bibliothek.** Der Lauf darf abbrechen und fortsetzen; sein
  Stand ist je Bibliothek abfragbar — dieselbe Anforderung wie an den Volltext-Backfill in
  [Hybrides Retrieval](./hybrid-retrieval.md#arbeitspaket-2a-backfill-des-bestands), und aus demselben
  Grund: Ein Lauf über den ganzen Bestand, der nur ganz oder gar nicht kann, wird im Betrieb nicht
  gefahren.
- **Ehrliche Messung.** Ein Vergleich, der versehentlich über gemischte Verfahrensstände läuft, misst
  einen Mittelwert aus zwei Pipelines. Mit der Version am Chunk ist dieser Zustand erkennbar statt
  unsichtbar.

**Das Zeitfenster ist die Auslegungsgrenze.** `deployment-infrastructure.md` setzt als Zielwert, dass
eine vollständige Neuindizierung des Bestands in einem nächtlichen Fenster abzuschließen ist (siehe
[Skalierung und Zielwerte](./deployment-infrastructure.md#skalierung-und-zielwerte)). Für die
versionsgetriebene Neuindizierung gilt dieser Zielwert unverändert weiter — die selektive Auswahl nach
Version arbeitet ihm zu, weil sie in aller Regel nur einen Teil des Bestands betrifft. Sollte sich bei
der Umsetzung zeigen, dass er für einen vollständigen Verfahrenswechsel nicht haltbar ist, wird er
ausdrücklich und begründet verworfen und in `deployment-infrastructure.md` nachgeführt — nicht
stillschweigend unterschritten.

**Diese Entscheidung fällt vor der ersten Typ-Pipeline**, nicht nach ihr. Nachträglich eingeführt,
trägt die Version für den gesamten bis dahin erzeugten Bestand den Wert „unbekannt", und genau dieser
Bestand ist der, den man später gezielt anfassen möchte.

#### Umgesetzt (#1056)

Jeder Chunk trägt `pipeline_id` und `pipeline_version` als Metadatum. Der Bestand von **vor** dieser
Einführung trägt keines von beidem — er ist trotzdem nicht „unbekannt", sondern zuordenbar: Bis zur
Abstraktion erzeugte ausschließlich der Tika-Weg Chunks, also zählt ein Chunk ohne diese Angaben als
`tika-fallback` in Version 0. Damit ist gerade der Altbestand vollständig auswählbar, statt der einzige
zu sein, den man nicht ansprechen kann.

Zwei Administrationsendpunkte (`SYSTEM_ADMIN`, auf die eigene Organisation begrenzt):

- `GET /api/v1/admin/indexing/pipeline-versions` — registrierte Pipelines mit ihrer aktuellen Version
  und der Füllstand je Bibliothek (Chunks insgesamt / auf aktueller Version / darunter). Ein Chunk, der
  eine Pipeline nennt, die diese Installation gar nicht hat, zählt weder als aktuell noch als
  nachzuziehen — er ließe sich von keiner vorhandenen Pipeline neu erzeugen.
- `POST /api/v1/admin/indexing/pipeline-reindex` — verarbeitet **eine Charge** und wird wiederholt
  aufgerufen, bis `done` gemeldet wird. Der Reststand wird bei jedem Aufruf neu aus den
  Chunk-Metadaten abgeleitet; ein abgebrochener Lauf verliert damit höchstens eine Charge und setzt
  fort, statt von vorn zu beginnen.

Ein Dokument, dessen Quelldatei lokal liegt (Dateisystem, Upload), wird sofort neu gelesen, zerlegt
und gespeichert — **unter seiner eigenen Dokument-ID**, damit Belege und Deep Links es überleben.
Gelesen wird dabei nur, was diese Installation lesen darf: Die Datei muss dieselbe Laufzeitprüfung
bestehen wie beim Ausliefern eines Originals (Allowlist, Lage unterhalb des konfigurierten
Quellverzeichnisses der Bibliothek bzw. des verwalteten Upload-Verzeichnisses, aufgelöst über
`toRealPath` gegen Symlinks; ADR-0018, Entscheidung 6). Eine nachträglich verkleinerte Allowlist wirkt
damit auch auf die Neuindizierung — sie ist nicht der eine Pfad, der weiterliest.

**Ein Anhangsdokument (ADR-0022) wird über seine Elternkette neu gewonnen** (#1183): Sein
`file_path` ist synthetisch (`<Elternpfad>/<Index>/<Name>`, siehe oben) und löst zu keiner eigenen
Datei auf. Die Neuindizierung läuft stattdessen die `parent_document_id`-Kette bis zum
Wurzeldokument hoch, dessen Quelldatei denselben Laufzeitprüfungen wie oben unterliegt, extrahiert
den Anhang entlang der im Pfad kodierten Positionsindizes erneut (auch über mehrere
Verschachtelungsebenen, Mail-in-Mail) und verarbeitet ihn unter seiner eigenen Dokument-ID neu — so
erreicht ein Versionssprung einer Sub-Pipeline (z. B. PDF) einen Anhang in einer Mail, ohne dass die
Mail-Datei selbst sich geändert haben muss (der Kernfall aus #1130 Befund 2). Passt die Kette nicht
mehr (Elterndatei geändert, Index entfallen), wird das Dokument übersprungen, nie zerstört.
Umgekehrt überlebt der Anhangsbestand die Neuindizierung seines **Elterndokuments**: Meldet die neu
laufende Eltern-Pipeline Anhänge, gehen sie denselben verallgemeinerten Anhangsweg wie im
Konnektorlauf — ein unveränderter Anhang wird per Prüfsumme bestätigt, ein noch nie als eigenes
Dokument erfasster (der Altbestand vor ADR-0022, Bestandsmigration email v4) entsteht dabei
erstmals. Das gilt seit #1218 auch für Upload-Bibliotheken: Anhänge hochgeladener Mails laufen über
denselben verallgemeinerten Anhangsweg (nur ohne Job — Ereignisse werden protokolliert, kein
Fortschritt gezählt), und das Elterndokument trägt auch dort die um Anhangsbytes bereinigte
Dateigröße.

Ein Dokument aus einer entfernten Quelle kann nur sein eigener Konnektorlauf neu lesen; es wird dafür
vorgemerkt und fällt danach aus der Auswahl, damit der Lauf abschließt. Vorgemerkt heißt: **beide**
Änderungsmarker werden geleert. Der Lauf entscheidet vor dem Download allein anhand von
`last_modified_remote` und dem Status `INDEXED`; die Prüfsumme vergleicht er dort noch gar nicht, weil
die Bytes dafür bewusst noch nicht geholt wurden. Nur die Prüfsumme zu leeren wäre für den ersten
Ausgang folglich wirkungslos gewesen. Die Chunks bleiben bis zum Lauf als nachzuziehen ausgewiesen —
der Füllstand beschönigt das nicht. Für ein **Anhangsdokument** einer entfernten Quelle
(HTTP_DIRECTORY, RSS) wird dabei die ganze Elternkette bis zur Wurzel vorgemerkt, nicht nur die
Anhangszeile selbst (#1219): Nur der nächste Lauf kann die Wurzel neu lesen, und nur eine
Ebene für Ebene neu geparste Kette erreicht den Anhang — eine allein vorgemerkte Anhangszeile bliebe
sonst hinter einer unverändert übersprungenen Elternmail für immer auf der alten Version.

**Nichts wird zerstört, bevor der Ersatz existiert.** Die alten Chunks fallen erst, wenn die Pipeline
tatsächlich neue erzeugt hat. Ein Dokument, das diesmal nicht verarbeitbar ist — vorübergehend
unlesbare Datei, Lesefehler —, behält seine funktionierenden Chunks und seinen Status und wird als
*übersprungen* zurückgemeldet, statt dauerhaft chunklos und fehlerhaft zurückzubleiben. Dasselbe gilt
für Dokumente, deren Datei außerhalb des Erlaubten liegt. Eine Charge, die nur noch übersprungen hat,
meldet `done` — Weiterlaufen hieße, dieselben unerreichbaren Dokumente endlos erneut zu versuchen.

`belowVersion` oberhalb der aktuellen Version der Pipeline wird mit 400 abgewiesen: Jeder neu
geschriebene Chunk läge weiterhin unterhalb der Schranke, dieselben Dokumente würden in jeder weiteren
Charge erneut ausgewählt — kein langsamer Lauf, sondern ein unbegrenzter.

Der auslösende Aufruf wird protokolliert (`INDEXING_PIPELINE_REINDEX_TRIGGERED`, mit Pipeline,
`belowVersion` und den Chargenzählern) — ein Eintrag je Aufruf, nicht je Dokument: Der Aufruf ist die
administrative Entscheidung, die Dokumente sind seine Wirkung.

**Ausgelöst wird nichts von selbst.** Ob und wann ein Bestand nachgezogen wird, bleibt die oben unter
[Offene Punkte](#offene-punkte) genannte, bewusst offene Frage; deshalb gibt es hier keinen
Hintergrund-Tick, der bei einer Versionserhöhung eigenmächtig den ganzen Bestand anfasst.

#### Nachgezogen: die tika-fallback-Lücke nach Routing-Umstellungen (#1105)

Ein Dokument, das zwischen der Einführung der Pipeline-Metadaten und der Registrierung seiner heutigen
Format-Pipeline indiziert wurde, trägt `tika-fallback` in der aktuellen Version der Fallback-Pipeline
selbst — für keinen Versionsvergleich mehr von einem stale unterscheidbar, obwohl das Dokument
inzwischen einer spezialisierten Pipeline gehört. Die Auswahl erweitert deshalb ihr Kriterium bewusst
um eine **Näherung, die von der sonst geltenden Regel abweicht**: Ob eine Pipeline heute zuständig
wäre, wird hier — anders als beim eigentlichen Routing (siehe oben, „nicht die Dateiendung") — allein
über die Dateiendung gegen die von den registrierten Pipelines beanspruchten Formate geschätzt, ohne
den Dateiinhalt erneut zu lesen. Dieser Zweig greift nur bei Chunks, die noch die Fallback-Pipeline
nennen; ein Chunk, der bereits eine spezialisierte Pipeline nennt, bleibt dem reinen
Versionsvergleich überlassen, selbst wenn seine Dateiendung nicht mehr passt (umbenannt, oder nie
zutreffend) — sonst würde die tatsächliche, inhaltsbasierte Neuzuordnung beim Nachziehen jedes Mal
widersprechen und derselbe Kandidat auf jeder Charge erneut ausgewählt werden. Ein RSS-Eintrag ist
davon grundsätzlich ausgenommen: sein Textkörper geht per ADR-0017 immer an die Fallback-Pipeline,
unabhängig vom Dateinamen (Titel oder Eintrags-URL) — der ist dort kein Routing-Signal. Ein Altbestand,
dessen Dateiname von seinem tatsächlichen Inhalt abweicht (z. B. eine PDF, deren Endung nie zur
zuständigen Pipeline passte), bleibt entsprechend teilweise offen: Über die Näherung nicht erreichbar,
weil ihr Dateiname keiner registrierten Pipeline zuordenbar ist.

Die Näherung schließt nur eine Richtung — sie holt einen Kandidaten aus dem Fallback heraus, sie hält
ihn aber nicht davon ab, dorthin zurückzufallen. Für die strikten Formate (`.pdf`/`.docx`/`.pptx`/
`.xlsx`/`.html`/`.msg`/`.od*`) bleibt ein zweiter Fall offen: Passt die Dateiendung zur zuständigen
Pipeline, aber entscheidet die inhaltsbasierte Erkennung in `routedPipelineFor` beim Nachziehen erneut
auf die Fallback-Pipeline (z. B. eine `.pdf` mit reinem Textinhalt), bleibt der Chunk als
Fallback-Chunk stehen. Für einen Chunk **ohne** den Routing-Schlüssel aus #1126 (siehe unten) bleibt das
dauerhaft: `isComplete()` für die Bibliothek dauerhaft `false`, und kein weiterer Nachzieh-Aufruf kann
daran etwas ändern — der Kandidat scheitert nicht an der Auswahl, sondern am selben Routing-Ergebnis
wie beim letzten Versuch. Der text-tolerante Pipeline-Satz ist davon nicht betroffen. Für einen Chunk
**mit** dem Routing-Schlüssel ist das kein offener Fall mehr, siehe unten.

#### Exakter Vergleich statt Näherung: der Routing-Schlüssel (#1126)

Die Endungsnäherung oben rät, welche Pipeline heute zuständig wäre — mit den beiden gerade genannten
Lücken. Seit #1126 schreibt `storeChunks` zusätzlich fest, mit welchem Ergebnis ein Chunk tatsächlich
geroutet wurde: `ChunkPipelineMetadata#ROUTING_EXTENSION_METADATA_KEY` trägt dieselbe Endung, die
`DocumentPipelineRegistry#routedPipelineFor` beim Schreiben dieses Chunks tatsächlich aufgelöst hat —
oder `ChunkPipelineMetadata#NO_ROUTING_EXTENSION`, wenn die Routing-Entscheidung keine Endung auflösen
konnte (Inhalt weder streng noch texttolerant zuordenbar). Der Schlüssel fehlt sowohl beim Altbestand
von vor #1126 als auch, wenn das Lesen der Datei zur Erkennung technisch fehlschlug
(`DocumentPipelineRegistry.Routed#formatDetectionFailed`, z. B. kurzzeitige Sperre durch einen
Virenscanner) — ein Lesefehler ist kein Routing-Verdikt und darf nicht als „korrekt fallback-geroutet"
persistiert werden. Beide Fälle bleiben auf der Endungsnäherung.

Wo der Schlüssel vorliegt, vergleicht sowohl `progressForOrganization` als auch
`selectStaleDocuments` exakt (`DocumentPipelineRegistry#pipelineIdForRoutingExtension`) statt über
den Dateinamen zu raten — beide oben genannten Lücken schließen sich dadurch für jeden künftig
geschriebenen Chunk: Ein Dokument ohne zuordenbare Endung (`download.aspx`) ist über seinen
tatsächlichen Routing-Schlüssel erreichbar, auch wenn die Endungsnäherung ihn nie gefunden hätte; und
ein Chunk, dessen Inhalt dauerhaft auf die Fallback-Pipeline zurückfällt, trägt
`NO_ROUTING_EXTENSION` und gilt damit als korrekt fallback-geroutet, nicht als dauerhaft nachzuziehen.
Derselbe exakte Vergleich verhindert außerdem, dass ein einmal so umgeschriebenes Dokument bei jedem
weiteren Nachzieh-Aufruf erneut geparst und eingebettet wird, obwohl sein Inhalt weiterhin auf den
Fallback zurückfällt.

**Für einen Chunk mit Schlüssel gilt die Verengung auf die Fallback-Pipeline nicht mehr (#1167).**
Die Endungsnäherung oben bleibt bewusst auf „heraus aus der Fallback-Pipeline" beschränkt, weil sie
nur rät — ein weiteres Kriterium in die Gegenrichtung würde nie konvergieren. Der exakte Schlüssel
rät nicht: `pipelineIdForRoutingExtension` bildet jede Endung eindeutig auf höchstens eine Pipeline
ab, also konvergiert ein Vergleich `pipelineIdForRoutingExtension(routing_extension) <>
gespeicherte pipeline_id` unabhängig davon, welche Pipeline gerade gespeichert ist — ein
Nachzieh-Lauf schreibt den Schlüssel frisch aus neu erkanntem Inhalt, trifft also beim nächsten
Aufruf garantiert wieder zu. Damit ist auch die Richtung „heraus aus einer bereits spezialisierten
Pipeline in eine andere" erreichbar: Ein Chunk, dessen `pipeline_id` eine seither umbenannte oder
neu registrierte Pipeline nennt, deren beanspruchte Endung aber weiterhin von genau einer Pipeline
geführt wird, wird vom Nachzieh-Aufruf gegen diese Pipeline erfasst (`misroutedPredicateFor`s
exakter Zweig). Nennt der Schlüssel dagegen eine Endung, die **keine** registrierte Pipeline mehr
beansprucht (die zuständige Pipeline wurde deinstalliert, nicht nur umbenannt), ist das Ziel die
Fallback-Pipeline selbst — dafür gibt es einen eigenen, ebenfalls exakten Zweig
(`misroutedPredicateForFallback`), der nicht rät, welche Endungen unbeansprucht sind, sondern sie
gegen die Vereinigung aller heute beanspruchten Formate prüft. `progressForOrganization` zählt
beide Fälle direkt als nachzuziehen, ohne `currentVersions` nach der gespeicherten `pipeline_id` zu
fragen, statt sie (wie zuvor) unsichtbar nur im Gesamtwert mitzuzählen und die Bibliothek fälschlich
als vollständig zu melden. Für einen Chunk **ohne** Schlüssel (Altbestand vor #1126) bleibt die
Fallback+Endungsnäherung unverändert der einzige Weg - ihre Konvergenzgarantie beruht weiterhin auf
der Beschränkung auf die Fallback-Pipeline, und ein solcher Chunk, dessen `pipeline_id` eine
deinstallierte Pipeline nennt, bleibt entsprechend weiterhin unsichtbar (nur im Gesamtwert gezählt)
- diese Altbestandsgrenze schließt erst ein vollständiger Durchlauf mit erneuter Inhaltserkennung,
siehe den nächsten Absatz.

**Kein Nachtrag für den Altbestand in #1126 selbst.** Der Schlüssel wird ab #1126 vorwärts
geschrieben; ein nachträgliches Setzen für vorhandene Chunks erfordert erneutes Erkennen des
Inhaltstyps je Dokument (ein vollständiger Durchlauf über den Bestand, siehe die eng verwandte, aber
gesondert zu entscheidende Frage oben unter [Offene Punkte](#offene-punkte): wann ein Bestand nach
einer Pipeline-Umstellung tatsächlich nachgezogen wird) — eine eigene Betriebsentscheidung, kein
Nebeneffekt dieses Refactorings.

---

## Teil 5 — Übergabepunkt an das Metadatenschema

Die Typ-Pipeline ist der **Entstehungsort** von Struktur-Metadaten: Gliederungspfad (§, Absatz),
Überschriftenpfad, Foliennummer und Folientitel, Blatt- und Tabellenname, E-Mail-Kopfdaten, Seitenzahl.
Das sind Angaben, die niemand nachträglich aus dem Chunk-Text rekonstruieren kann — sie existieren nur
in dem Moment, in dem der Reader das Dokument noch strukturiert vor sich hat.

**Das Metadatenschema selbst ist nicht Gegenstand dieser Spezifikation.** Welche Kernfelder es gibt,
welche eine Bibliothek zusätzlich führt, wie Schlagworte entstehen, ob und wie ein geführter Assistent
beim Anlegen einer Bibliothek ein Schema vorschlägt — das ist eine eigene, spätere Spezifikation
(Konzeptstand:
[discussion-dateitypen-und-metadaten.md, Abschnitt 3](../discussions/discussion-dateitypen-und-metadaten.md)).

Hier wird nur der **Übergabepunkt** definiert:

1. Jede Pipeline liefert ihre Struktur-Metadaten als Teil des Chunks ab, in einer für alle Pipelines
   einheitlichen Form — nicht jede Pipeline mit eigenen Schlüsselnamen für dasselbe Konzept. **Der
   Übergabemechanismus selbst ist seit #1107 offen für jede Pipeline**: `DocumentPipeline` deklariert
   über `passthroughMetadataKeys()`, welche seiner Chunk-Metadatenschlüssel `storeChunks` auf den
   gespeicherten Chunk kopiert — eine neue Pipeline erweitert diese Menge selbst, ohne
   `FileProcessingService` zu ändern (Open-Closed, Teil 1). Die *Form* war bis #1130 uneinheitlich:
   `MailDocumentPipeline` nutzte ausschließlich eigene Metadatenfelder (`mail_from` usw.), während
   `TabularDocumentPipeline`/`HtmlDocumentPipeline`/`PptxDocumentPipeline` ihren Strukturkontext in den
   Chunk-Text backen (`location` plus eine Kontextzeile). **Entschieden mit #1130 Befund 1: beides.**
   Die Metadatenfelder bleiben — seit #1164 (PR #1201) gelesen für die Fundstellen-Anzeige, als
   Grundlage einer künftigen strukturierten Filterung nach Absender/Zeitraum/Betreff (Issue #1211)
   noch offen —, zusätzlich trägt `MailDocumentPipeline` dieselben Kopfdaten jetzt auch als deutsch
   beschriftete Kontextzeilen in den Chunk-Text, einmalig auf dem ersten erzeugten Chunk, damit sie
   Embedding und Volltextindex tatsächlich erreichen. Ein Metadatenfeld ohne Leser ist wirkungslos —
   zum Zeitpunkt dieser Entscheidung galt das noch für beide Wege: Die Textform war die einzige, die
   vor Retrieval-Filterung/Beleganzeige in Suchtreffern ankam.
2. Struktur-Metadaten sind **abgeleitet, nicht geraten**. Sie stammen aus dem Dokument selbst
   (Gliederung, Folienzähler, Blattname, Mail-Header). Inhaltlich interpretierende Felder — Dokumentart,
   Fassung, Thema — entstehen hier ausdrücklich nicht; sie gehören in die Metadaten-Spezifikation, mit
   ihren eigenen Leitplanken gegen geratene Werte.
3. Die heutigen technischen Chunk-Metadaten (`document_id`, `chunk_index`, `file_name`, `library_id`,
   `organization_id`, `location`, seit #1126 `routing_extension`) bleiben unverändert; die
   Struktur-Metadaten und die Pipeline-Version aus [Regel
   (d)](#d-jeder-chunk-trägt-die-version-des-verfahrens-das-ihn-erzeugt-hat) treten daneben,
   ersetzen nichts. Insbesondere bleibt `location` die Angabe, aus der der Beleg seine Fundstelle bildet — der
   Gliederungspfad verbessert sie, verdrängt sie aber nicht.

---

## Integrationspunkte

- **[Wissensschicht und Retrieval](./data-indexing-rag.md)** — Zielbild der Formaterkennung, Tabelle
  der verarbeiteten Dateien, erklärbares Chunking, Stellschrauben. Dieses Dokument konkretisiert die
  dortige Aufnahmestrecke; die Parameterwerte selbst bleiben dort geführt.
- **[Retrieval-Algorithmus (Ist-Stand)](./retrieval-algorithm.md)** — was mit den erzeugten Chunks zur
  Abfragezeit geschieht.
- **[Hybrides Retrieval](./hybrid-retrieval.md)** — der lexikalische Pfad, den
  die Typ-Pipeline mitbefüllt, und der Konfigurations-Querschnitt, aus dem Regel (c) stammt.
- **[Retrieval-Benchmark](./retrieval-benchmark.md)** — das Verfahren, das die
  typspezifischen Chunk-Größen misst und die Baseline führt.
- **[Wissensquellen und Konnektoren](./knowledge-sources.md)** — liefert die Dateien; die
  Zulassungsliste gilt für alle dateibasierten Aufnahmewege gleichermaßen.
- **[Suchqualität messbar machen](./search-quality-evaluation.md)**,
  [ADR-0010](../decisions/0010-ein-chunk-invariante-evaluierungskorpus.md),
  [ADR-0011](../decisions/0011-search-quality-evaluation-harness.md),
  [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md),
  [ADR-0013](../decisions/0013-fehlerkriterium-retrieval-regression.md) — Korpus, Harness, Messvertrag
  und Fehlerkriterium jeder Pipeline-Umstellung.
- **[Deployment und Infrastruktur](./deployment-infrastructure.md)** — betrifft die Docling-Option: ein
  weiterer Container im Compose-Verbund ist eine Betriebsentscheidung, keine Bibliotheksentscheidung.
- **[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)** — die Bibliotheks-Kennung als
  Filterachse bleibt unverändert; keine Pipeline erzeugt einen Chunk ohne sie.

---

## Bewusst nicht gebaut

- **Semantic Chunking** (Schnitt an semantischen Bruchstellen über Embedding-Ähnlichkeit benachbarter
  Sätze) — die NAACL-Findings-2025-Studie „Is Semantic Chunking Worth the Computational Cost?"
  ([arXiv:2410.13070](https://arxiv.org/abs/2410.13070)) findet keine konsistenten Gewinne bei
  erheblichen Rechenkosten, und für strukturierte Verwaltungsdokumente ist strukturbasiertes Schneiden
  ohnehin das schärfere Werkzeug (siehe
  [Tech-Report 5.5](../discussions/discussion-retrieval-strategien.md#55-semantic-chunking--evidenz-dagegen)).
- **Late Chunking** (Pooling der Chunk-Vektoren nach der Aufmerksamkeitsberechnung über das ganze
  Dokument) — fachlich attraktiv als kostenlose Alternative zum Contextual Chunking, am aktuellen Stack
  aber nicht umsetzbar: Es braucht Token-Zugriff auf den Embedder, den die OpenAI-kompatible
  Embedding-Schnittstelle wegabstrahiert (siehe
  [Tech-Report 5.4](../discussions/discussion-retrieval-strategien.md#54-late-chunking-jina)).
- **Wechsel zu LangChain4j** wegen dessen reicherer Splitter-Auswahl (Paragraph, Satz, Regex, rekursiv)
  — ein Rahmenwerkwechsel für eine Fähigkeit, die als eigene `DocumentTransformer`-Implementierung
  wenige hundert Zeilen kostet, wäre unverhältnismäßig.
- **Unstructured, MinerU und marker** als Parsing-Alternativen zu Docling — sie scheiden aus
  Lizenzgründen aus (kommerzielle Einschränkungen bzw. Copyleft-Bedingungen, die mit der
  Weitergabeform dieses Projekts kollidieren); Docling ist MIT-lizenziert und damit die einzige
  Option dieser Klasse, die ohne Lizenzvorbehalt geprüft werden kann.
- **XML-Fachformate** (LegalDocML.de, XJustiz, XÖV) — strukturtreues Parsen wäre hier besonders
  lohnend, lohnt sich aber erst mit einem konkreten Quellanschluss (etwa NeuRIS); ein Parser ohne
  angeschlossene Quelle hätte keinen Bestand, an dem er sich bewähren könnte (siehe
  [discussion-dateitypen-und-metadaten.md, Abschnitt 2](../discussions/discussion-dateitypen-und-metadaten.md)).
- **Quellcode-Pipelines** (Schnitt je Funktion über Tree-Sitter oder Regex) — in den
  Diskussionspapieren als Idee vorhanden, für einen Verwaltungsbestand ohne erkennbaren Nutzungsfall.

---

## Offene Punkte

- **Abnahmekriterien des Docling-PoC.** Die vier Fragengruppen stehen fest; offen sind die Schwellen
  der drei fachlichen: (1) Wie zuverlässig muss die §-Gliederungserkennung auf deutschen Satzungen sein,
  damit darauf geschnitten werden darf — und woran wird das gemessen, an einer Handauswertung einer
  Dokumentstichprobe oder an Retrieval-Kennzahlen? (2) Welche Tabellenqualität genügt: reicht „Zeilen
  und Spalten bleiben zuordenbar", oder muss auch die Verbundzelle sitzen? (3) Ab welchem
  Ressourcenbedarf ist die Option verworfen — die genannten ~8 GB RAM je Worker und ~4,4 GB Image sind
  Fremdangaben und im PoC zu bestätigen. Die vierte, betriebliche Gruppe ist dagegen nicht
  schwellenbehaftet: Ihre Punkte sind erfüllt oder die Option ist verworfen.
- **Bleibt Arconia der REST-Client oder wird ein dünner Eigenbau daraus?** Zu entscheiden erst nach
  einem positiven PoC, dann anhand des tatsächlich benötigten API-Ausschnitts und des Zustands des
  Upstream-Projekts.
- **Woher kommt der Verwaltungskorpus für die typspezifischen Messungen?** Ohne Dokumente mit echter
  §-Gliederung, echten Tabellen und echten Folien ist keine Chunk-Größe je Typ messbar. Zu klären ist,
  ob dafür öffentlich verfügbare Satzungen und Gebührenverzeichnisse genügen (rechtlich unbedenklich,
  aber nicht repräsentativ für interne Vermerke) oder ob ein synthetischer Korpus nach dem Muster der
  bestehenden Generatoren daneben treten muss.
- **Wie wird ein Dokument behandelt, dessen Struktur nur teilweise erkennbar ist** — ein PDF mit
  Inhaltsverzeichnis für die ersten drei Kapitel und flachem Text danach? Vollständiger Rückfall auf
  Token-Chunking oder gemischter Zuschnitt innerhalb eines Dokuments? Letzteres ist fachlich besser und
  macht die Erklärbarkeit der Zerlegung schwerer.
- **Wann wird ein Bestand nach einer Pipeline-Umstellung tatsächlich neu indiziert?** Dass ein
  gemischter Bestand **erkennbar** ist und selektiv nachgezogen werden **kann**, ist mit
  [Regel (d)](#d-jeder-chunk-trägt-die-version-des-verfahrens-das-ihn-erzeugt-hat) entschieden. Offen
  bleibt die Auslösung: Startet eine Umstellung den Nachlauf für die betroffenen Formate selbsttätig,
  oder ist es eine bewusste Handlung der Betreiberin mit eigener Zeitplanung? Für große Bestände spricht
  einiges für Letzteres, für die Konsistenz der Antworten für Ersteres.
