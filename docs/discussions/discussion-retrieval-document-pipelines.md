# Discussion: Retrieval-Pipelines pro Dokumenttyp

**Thema:** Dokumenttyp-spezifische Parsing- und Chunking-Strategien für Enterprise-RAG mit Spring AI

**Kontext:** Aufbauend auf der [Embedding-Diskussion](discussion-embeddings.md). OPAA nutzt aktuell `TikaDocumentReader` + `TokenTextSplitter` (1000 Tokens) einheitlich für alle Formate. Diese Diskussion behandelt, warum und wie das differenziert werden sollte.

**Bezug zum Code:** `DocumentService`, `ChunkingService`, `FileProcessingService` im Package `io.opaa.indexing`

---

## 1. Aktueller Stand: Ein Reader, ein Splitter für alles

```
Alle Dateien → TikaDocumentReader → TokenTextSplitter(1000) → VectorStore
```

**Probleme:**
- Tika gibt für PPTX alle Folien als einen Textblock zurück (Foliengrenzen verloren)
- PDF-Kapitelstruktur geht verloren (nur Rohtext)
- Markdown-Heading-Struktur wird ignoriert
- Source Code wird mitten in Funktionen geschnitten
- Einheitlich 1000 Tokens ist für manche Typen zu viel, für andere zu wenig

---

## 2. Spring AI bietet spezialisierte Reader (ungenutzt)

Spring AI 1.1.x hat **7 DocumentReader-Implementierungen** — OPAA nutzt aktuell nur eine davon.

### Sofort nutzbare Reader (nur Dependency hinzufügen)

| Reader | Artifact | Vorteil gegenüber Tika |
|---|---|---|
| `ParagraphPdfDocumentReader` | `spring-ai-pdf-document-reader` | Nutzt PDF-Katalog/TOC → kapitel-aware Splitting |
| `PagePdfDocumentReader` | `spring-ai-pdf-document-reader` | 1 Seite = 1 Document (saubere Seitengrenzen) |
| `MarkdownDocumentReader` | `spring-ai-markdown-document-reader` | Splittet nach Headers/Paragraphen |
| `JsoupDocumentReader` | `spring-ai-jsoup-document-reader` | HTML mit CSS-Selektoren, besser als Tika für HTML |
| `JsonReader` | core (bereits vorhanden) | JSONPath-Support |
| `TextReader` | core (bereits vorhanden) | Einfacher Plain-Text-Reader |

### Nicht verfügbar (eigene Implementierung nötig)

| Format | Grund | Lösung |
|---|---|---|
| **PPTX pro Folie** | Kein Framework bietet das | Eigener Reader via Apache POI (`XMLSlideShow`) |
| **Source Code pro Funktion** | Kein Framework bietet das | Eigener Reader mit Regex oder Tree-Sitter |
| **E-Mail granular** | Tika parst E-Mails, aber Body+Attachments als Block | Ggf. eigener Reader für separate Attachment-Behandlung |

---

## 3. Tika bleibt — als Fallback

Apache Tika ist nicht obsolet. Es bleibt der beste universelle Parser für:

- **E-Mails** (.eml, .msg, .mbox) — parst Headers, Body, Attachments nativ
- **Office-Formate** (.docx, .xlsx) — solide Textextraktion
- **Unbekannte/neue Formate** — 1000+ Formate ohne Codeänderung
- **Alle Formate ohne spezialisierten Reader** — generischer Fallback

**Fazit:** Tika = Sicherheitsnetz. Spezialisierte Reader = Qualität.

---

## 4. Empfohlene Pipeline-Architektur

### Konzept: Reader + Splitter pro Dateityp

```
┌─────────────┬──────────────────────────┬─────────────────────┬─────────────┐
│ Dateityp    │ Reader                   │ Splitter            │ Chunk-Size  │
├─────────────┼──────────────────────────┼─────────────────────┼─────────────┤
│ .pdf        │ ParagraphPdfDocReader     │ TokenTextSplitter   │ 600 Tokens  │
│ .md         │ MarkdownDocumentReader    │ (kein Split nötig)  │ per Header  │
│ .pptx       │ PptxSlideReader (eigener) │ (kein Split nötig)  │ per Folie   │
│ .html       │ JsoupDocumentReader       │ TokenTextSplitter   │ 500 Tokens  │
│ .java/.py   │ CodeReader (eigener)      │ (kein Split nötig)  │ per Funktion│
│ .eml/.msg   │ TikaDocumentReader        │ TokenTextSplitter   │ 400 Tokens  │
│ .docx/.txt  │ TikaDocumentReader        │ TokenTextSplitter   │ 800 Tokens  │
│ (Fallback)  │ TikaDocumentReader        │ TokenTextSplitter   │ 800 Tokens  │
└─────────────┴──────────────────────────┴─────────────────────┴─────────────┘
```

### Umsetzungsskizze im Code

**DocumentService** — Reader-Auswahl nach Extension:

```java
// Konzeptionell
DocumentReader resolveReader(Resource resource, String extension) {
    return switch (extension) {
        case ".pdf"  -> new ParagraphPdfDocumentReader(resource);
        case ".md"   -> new MarkdownDocumentReader(resource);
        case ".html" -> new JsoupDocumentReader(resource);
        case ".pptx" -> new PptxSlideDocumentReader(resource); // eigener
        default      -> new TikaDocumentReader(resource);       // Fallback
    };
}
```

**ChunkingService** — Splitter-Konfiguration nach Dateityp:

```java
// Konzeptionell
TokenTextSplitter resolveSplitter(String extension) {
    int chunkSize = switch (extension) {
        case ".pdf"  -> 600;
        case ".html" -> 500;
        case ".eml", ".msg" -> 400;
        default -> 800;
    };
    return new TokenTextSplitter(chunkSize, 350, 5, 10000, true);
}
```

---

## 5. Schwäche von Spring AI: Nur ein Splitter

Spring AI bietet **nur `TokenTextSplitter`**. Es gibt keinen Paragraph-, Sentence- oder Recursive-Splitter.

### Vergleich mit LangChain4j

| Splitter | Spring AI | LangChain4j |
|---|---|---|
| Token-basiert | `TokenTextSplitter` | (ähnlich) |
| Paragraph-basiert | - | `DocumentByParagraphSplitter` |
| Satz-basiert | - | `DocumentBySentenceSplitter` |
| Regex-basiert | - | `DocumentByRegexSplitter` |
| Rekursiv (hierarchisch) | - | `DocumentSplitters.recursive()` |

**Empfehlung:** Nicht zu LangChain4j wechseln — stattdessen bei Bedarf eigene `DocumentTransformer`-Implementierungen bauen (Spring AI Interface ist einfach). Für den Start reicht der `TokenTextSplitter` mit typspezifischer Konfiguration.

---

## 6. Weitere Formate: E-Mails, Wiki, Zukunft

### E-Mails (.eml, .msg)

- **Parser:** `TikaDocumentReader` (unterstützt E-Mail-Formate nativ)
- **Tika extrahiert:** Headers (From, To, Subject, Date), Body, Attachment-Text
- **Verbesserung:** Eigener Reader, der Subject+Sender als Metadaten speichert und Attachments separat chunked

### Wiki-Seiten (Confluence, MediaWiki)

- **Kein Framework bietet native Wiki-Reader**
- **Praxis:** Export als HTML → `JsoupDocumentReader`
- **Oder:** Confluence REST API → HTML → JsoupDocumentReader

### Weitere Office-Formate (.xlsx, .csv)

- **Tabellen:** Tika extrahiert den Text, aber Tabellenstruktur geht verloren
- **Besser:** Apache POI direkt für strukturiertes Row/Column-Parsing
- **CSV:** `JsonReader` mit Vorverarbeitung oder eigener Reader

---

## 7. Community-Option: Docling (IBM)

**Artifact:** `io.arconia:arconia-ai-docling-document-reader`

Open-Source-Projekt von IBM für layout-aware Dokumentenparsing:
- ~97.9% Accuracy bei komplexen Tabellen
- Erkennt Dokumentstruktur (Überschriften, Absätze, Tabellen, Bilder)
- Spring AI Integration via Arconia (Community, seit Dezember 2025)

**Einschätzung:** Interessant für komplexe PDFs mit Tabellen/Grafiken. Für den Start nicht nötig — `ParagraphPdfDocumentReader` reicht. Als Option im Hinterkopf behalten.

---

## 8. Entscheidung: Eine Pipeline pro Dokumenttyp

### Warum?

- Verschiedene Formate haben **unterschiedliche semantische Dichten**
- Ein Universal-Ansatz erzeugt entweder zu große oder zu kleine Chunks
- Strukturinformationen (Kapitel, Folien, Funktionen) gehen sonst verloren
- Neue Formate können sauber hinzugefügt werden, ohne bestehende zu beeinflussen

### Architektur-Prinzip

```
DocumentPipeline (Interface)
  ├── PdfPipeline         → ParagraphPdfReader + TokenSplitter(600)
  ├── MarkdownPipeline    → MarkdownReader (header-based, kein extra Split)
  ├── PptxPipeline        → PptxSlideReader (eigener, pro Folie)
  ├── HtmlPipeline        → JsoupReader + TokenSplitter(500)
  ├── CodePipeline        → CodeReader (eigener, pro Funktion)
  └── DefaultPipeline     → TikaReader + TokenSplitter(800)  ← Fallback
```

Jede Pipeline definiert:
1. **Welcher Reader** parst das Dokument
2. **Welcher Splitter** (falls nötig) chunked den Output
3. **Welche Metadaten** angereichert werden (Kapitel, Foliennummer, Funktionsname, ...)
4. **Welche Chunk-Größe** verwendet wird

### Erweiterbarkeit

Neue Formate hinzufügen = neue Pipeline-Klasse + Extension registrieren. Kein bestehender Code wird geändert (Open-Closed Principle).

---

## 9. Nächste Schritte

1. **Quick Win:** `ParagraphPdfDocumentReader` und `MarkdownDocumentReader` als Dependencies hinzufügen und in `DocumentService` einbauen
2. **Pipeline-Abstraktion:** `DocumentPipeline`-Interface + Registry im `ChunkingService`/`DocumentService`
3. **Eigene Reader:** `PptxSlideDocumentReader` (Apache POI)
4. **Später:** Code-Reader, E-Mail-Reader, Docling evaluieren

---

## 10. Zusammenfassung der Kernerkenntnisse

| Erkenntnis | Detail |
|---|---|
| Spring AI hat mehr Reader als wir nutzen | `ParagraphPdfReader`, `MarkdownReader`, `JsoupReader` sofort einsetzbar |
| Tika bleibt als Fallback | Universell, 1000+ Formate, besonders gut für E-Mails |
| Pipeline pro Dokumenttyp ist der richtige Ansatz | Unterschiedliche Reader, Splitter und Chunk-Sizes pro Format |
| Spring AI Splitter-Auswahl ist begrenzt | Nur `TokenTextSplitter`, aber für den Start ausreichend |
| PPTX und Code brauchen eigene Reader | ~100-200 Zeilen pro Reader, POI ist schon als Dependency da |
| Docling als Zukunftsoption | Layout-aware PDF-Parsing, Community-Integration vorhanden |
