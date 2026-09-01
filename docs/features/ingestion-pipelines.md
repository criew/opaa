# Ingestion-Pipelines je Dokumenttyp

> **Status: Entwurf zur Review.**

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

Die drei Ausgänge, die `FileProcessingService` bisher selbst entschied — „Scan ohne Textebene",
„gar nichts geparst", „Text, aber keine Chunks" — entscheidet jetzt die Pipeline für ihr eigenes
Format. Genau das braucht eine PDF-Pipeline später, um Scan-Erkennung anders zu beantworten als eine
Tabellen-Pipeline.

**Keine Datenbankänderung nötig.** Die Pipeline-Version ist ein Chunk-Metadatum und liegt damit dort,
wo Chunk-Metadaten liegen: in `vector_store.metadata`. Diese Tabelle legt Spring AI beim Start an,
nicht Liquibase — eine Spalte wäre dort gar nicht verfügbar, und eine zweite Tabelle wäre eine dritte
Zeile je Chunk für einen Wert, der definitorisch zum Chunk gehört.

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

### 2. ODF — ODT, ODS, ODP

Viele Behörden arbeiten mit LibreOffice. Tika parst ODF nativ; es fehlt praktisch nur der Eintrag in
der Zulassungsliste und in der Medientyp-Zuordnung der Formaterkennung.

**Zuschnitt:** wie die jeweiligen Microsoft-Pendants — ODT wie DOCX, ODS wie XLSX, ODP wie PPTX. Die
Pipelines sind dieselben; nur das Routing kennt einen weiteren erkannten Medientyp.

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
[Punkt 3](#3-xlsx-und-csv). ODT und ODP laufen unverändert über die Tika-Fallback-Pipeline.

Baseline unberührt — kein Korpusdokument dieses Typs.

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
  …` für CSV) — direkt im Chunk-Text, nicht als separates Metadatenfeld, weil `FileProcessingService`
  nur eine feste, generische Metadatenmenge auf einen gespeicherten Chunk überträgt (siehe
  [Teil 5](#teil-5--übergabepunkt-an-das-metadatenschema)). Für XLSX/ODS fallen Blatt- und Tabellenname
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
Text wird dann mit sichtbarem „[…gekürzt]"-Vermerk gekappt, nach demselben Muster wie
`TabularDocumentPipeline#HARD_CHUNK_CHAR_LIMIT`.

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

- **Kopfdaten werden Metadaten, nicht Fließtext.** Von, An, Betreff, Datum gehören an den Chunk, nicht
  in seinen Text — sonst embedded jeder Chunk einer Mailablage denselben Verteilerkopf mit.
- **Ein Chunk je Nachricht**, bei langen Threads je Nachricht im Thread. Ein Thread ist kein Dokument,
  sondern eine Folge von Dokumenten.
- **Anhänge laufen durch die Pipeline ihres eigenen Typs.** Ein PDF-Anhang einer Mail wird von der
  PDF-Pipeline verarbeitet, nicht vom Mail-Parser mit extrahiert. Das ist die eigentliche
  Rechtfertigung der Registry: Der Anhangsfall braucht die Pipeline-Auswahl rekursiv, und mit der
  Abstraktion ist das ein Aufruf statt eines Sonderwegs.

Die Rechte- und Herkunftsfrage von Anhängen (welche Bibliothek, welche Fundstellenangabe, welcher
Beleg) folgt dabei den bestehenden Regeln des Anlagenwegs, siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md).

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

Paragrafenangaben, Aktenzeichen und Erlassnummern sind Identifikatoren, keine Wörter. Ein
Vektorvergleich trifft sie unzuverlässig; die Volltextsuche trifft sie exakt — aber nur, wenn sie dort
unzerlegt ankommen. „§ 3 Abs. 2 VwGebS" darf nicht durch Tokenisierung, Stemming oder Decompounding
zu Bruchstücken werden, und „AZ 31/2-2026-0815" ist kein Text, den man an Sonderzeichen trennt.

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

Ein Dokument aus einer entfernten Quelle kann nur sein eigener Konnektorlauf neu lesen; es wird dafür
vorgemerkt und fällt danach aus der Auswahl, damit der Lauf abschließt. Vorgemerkt heißt: **beide**
Änderungsmarker werden geleert. Der Lauf entscheidet vor dem Download allein anhand von
`last_modified_remote` und dem Status `INDEXED`; die Prüfsumme vergleicht er dort noch gar nicht, weil
die Bytes dafür bewusst noch nicht geholt wurden. Nur die Prüfsumme zu leeren wäre für den ersten
Ausgang folglich wirkungslos gewesen. Die Chunks bleiben bis zum Lauf als nachzuziehen ausgewiesen —
der Füllstand beschönigt das nicht.

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
   einheitlichen Form — nicht jede Pipeline mit eigenen Schlüsselnamen für dasselbe Konzept.
2. Struktur-Metadaten sind **abgeleitet, nicht geraten**. Sie stammen aus dem Dokument selbst
   (Gliederung, Folienzähler, Blattname, Mail-Header). Inhaltlich interpretierende Felder — Dokumentart,
   Fassung, Thema — entstehen hier ausdrücklich nicht; sie gehören in die Metadaten-Spezifikation, mit
   ihren eigenen Leitplanken gegen geratene Werte.
3. Die heutigen technischen Chunk-Metadaten (`document_id`, `chunk_index`, `file_name`, `library_id`,
   `organization_id`, `location`) bleiben unverändert; die Struktur-Metadaten und die Pipeline-Version
   aus [Regel (d)](#d-jeder-chunk-trägt-die-version-des-verfahrens-das-ihn-erzeugt-hat) treten daneben,
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
