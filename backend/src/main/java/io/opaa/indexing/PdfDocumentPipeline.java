package io.opaa.indexing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The PDF pipeline (docs/features/ingestion-pipelines.md, Teil 1's parsing table: "PDF
 * (born-digital) | ParagraphPdfDocumentReader, alternativ PagePdfDocumentReader | Nutzt den
 * PDF-Katalog... die Seitenvariante ist der Rückfall, wenn kein Katalog vorhanden ist"). Reads
 * directly through Apache PDFBox (already on the classpath transitively via Tika's own PDF parser
 * module) rather than through Spring AI's {@code ParagraphPdfDocumentReader}/{@code
 * PagePdfDocumentReader}: neither module is on this project's classpath, and the catalog walk plus
 * page-range text extraction this pipeline needs is a direct, few-hundred-line use of PDFBox's own
 * outline and {@link PDFTextStripper} APIs - the same reasoning {@link HtmlDocumentPipeline}
 * already applies to Jsoup instead of a Spring AI HTML reader.
 *
 * <p><b>Scan detection runs first and is unchanged from #1055</b>: {@link
 * DocumentService#parseDocument}/{@link DocumentService#isTextlessPdf} decide "no extractable text
 * at all" before this pipeline's own PDFBox-based extraction ever runs, so a scan PDF is rejected
 * exactly as it was before this pipeline existed, regardless of whether it happens to carry an
 * empty outline.
 *
 * <p><b>The PDF catalog (outline/bookmarks) decides the cut when present.</b> Every outline entry
 * that resolves to a page becomes a heading of its own nesting level - unlike {@link
 * MarkdownDocumentPipeline}/{@link DocxDocumentPipeline}, cutting is <b>not</b> capped at level 3:
 * a legal text's catalog commonly nests § and Absatz as two levels, and a deeper catalog should
 * still yield a citable chunk per level rather than folding a third level back into its parent's
 * text. An entry whose destination cannot be resolved (an action-based bookmark pointing outside
 * the document) is skipped, its children kept at their own nesting depth regardless.
 *
 * <p><b>Page-based chunking is the fallback</b> when the document has no outline, or none of its
 * entries resolve to a page - one chunk per non-blank page, carrying {@code "S. n"} as its {@link
 * ChunkingService#LOCATION_METADATA_KEY location}, mirroring {@code PagePdfDocumentReader}'s own
 * per-page unit.
 */
public class PdfDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(PdfDocumentPipeline.class);

  static final String ID = "pdf";
  static final short VERSION = 1;

  /**
   * Last-resort backstop for the page fallback, mirroring {@link
   * HtmlDocumentPipeline#HARD_CHUNK_CHAR_LIMIT}.
   */
  static final int HARD_CHUNK_CHAR_LIMIT = 20_000;

  private static final String TRUNCATION_MARKER = " […gekürzt]";

  private final DocumentService documentService;

  public PdfDocumentPipeline(DocumentService documentService) {
    this.documentService = documentService;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  @Override
  public Set<String> handledFormats() {
    return Set.of(".pdf");
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    if (source.file() == null) {
      // A PDF pipeline is only ever reached through a genuine .pdf file (never RSS-extracted
      // text) - defensive fallback rather than an assumption the caller must uphold.
      return source.extractedText().isBlank()
          ? DocumentPipelineResult.noContent()
          : DocumentPipelineResult.chunked(List.of(new Document(source.extractedText())));
    }
    List<org.springframework.ai.document.Document> tikaParsed =
        documentService.parseDocument(source.file());
    if (documentService.isTextlessPdf(source.file(), tikaParsed)) {
      return DocumentPipelineResult.noExtractableText();
    }
    if (tikaParsed.isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    try (PDDocument doc = Loader.loadPDF(source.file().toFile())) {
      List<OutlineEntry> entries = flattenOutline(doc);
      if (!entries.isEmpty()) {
        return chunkByOutline(doc, entries);
      }
      return chunkByPage(doc);
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read PDF document {} via PDFBox", source.fileName(), e);
      return DocumentPipelineResult.noContent();
    }
  }

  private record OutlineEntry(int level, String title, int pageIndex) {}

  private static List<OutlineEntry> flattenOutline(PDDocument doc) {
    PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
    if (outline == null) {
      return List.of();
    }
    List<OutlineEntry> entries = new ArrayList<>();
    collectOutline(doc, outline, 1, entries);
    // A pre-order walk of the catalog is not guaranteed to be monotonic in page number for every
    // pathological document; a stable sort on page index restores that order (needed by
    // HeadingSectionSplitter's section algorithm) while keeping same-page entries in their
    // original, parent-before-child order.
    entries.sort(Comparator.comparingInt(OutlineEntry::pageIndex));
    return entries;
  }

  private static void collectOutline(
      PDDocument doc, PDOutlineNode node, int level, List<OutlineEntry> out) {
    for (PDOutlineItem item = node.getFirstChild(); item != null; item = item.getNextSibling()) {
      Integer pageIndex = resolvePageIndex(doc, item);
      String title = item.getTitle();
      if (pageIndex != null && title != null && !title.isBlank()) {
        out.add(new OutlineEntry(level, title.strip(), pageIndex));
      }
      collectOutline(doc, item, level + 1, out);
    }
  }

  private static Integer resolvePageIndex(PDDocument doc, PDOutlineItem item) {
    try {
      PDPage page = item.findDestinationPage(doc);
      if (page == null) {
        return null;
      }
      int index = doc.getPages().indexOf(page);
      return index < 0 ? null : index;
    } catch (IOException e) {
      return null;
    }
  }

  private static DocumentPipelineResult chunkByOutline(PDDocument doc, List<OutlineEntry> entries)
      throws IOException {
    List<HeadingSectionSplitter.Event> events = new ArrayList<>();
    int firstStart = entries.get(0).pageIndex();
    if (firstStart > 0) {
      String preamble = extractPageRangeText(doc, 0, firstStart);
      if (!preamble.isBlank()) {
        events.add(new HeadingSectionSplitter.Paragraph(preamble.strip()));
      }
    }
    for (int i = 0; i < entries.size(); i++) {
      OutlineEntry entry = entries.get(i);
      events.add(new HeadingSectionSplitter.Heading(entry.level(), entry.title()));
      int end = i + 1 < entries.size() ? entries.get(i + 1).pageIndex() : doc.getNumberOfPages();
      if (end > entry.pageIndex()) {
        String body = extractPageRangeText(doc, entry.pageIndex(), end);
        if (!body.isBlank()) {
          events.add(new HeadingSectionSplitter.Paragraph(body.strip()));
        }
      }
    }
    // No cap on the cutting level - see this class's own Javadoc for why the PDF catalog is
    // trusted at every nesting depth, unlike Markdown/DOCX.
    List<Document> chunks = HeadingSectionSplitter.chunk(events, Integer.MAX_VALUE);
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  private static DocumentPipelineResult chunkByPage(PDDocument doc) throws IOException {
    List<Document> chunks = new ArrayList<>();
    int pageCount = doc.getNumberOfPages();
    for (int i = 0; i < pageCount; i++) {
      String text = extractPageRangeText(doc, i, i + 1);
      if (text.isBlank()) {
        continue;
      }
      Map<String, Object> metadata = new HashMap<>();
      metadata.put(ChunkingService.LOCATION_METADATA_KEY, "S. " + (i + 1));
      chunks.add(new Document(capChunkLength(text.strip()), metadata));
    }
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /**
   * @param startPageIndex inclusive, 0-based. @param endPageIndexExclusive exclusive, 0-based.
   */
  private static String extractPageRangeText(
      PDDocument doc, int startPageIndex, int endPageIndexExclusive) throws IOException {
    if (endPageIndexExclusive <= startPageIndex) {
      return "";
    }
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(startPageIndex + 1);
    stripper.setEndPage(endPageIndexExclusive);
    return stripper.getText(doc);
  }

  private static String capChunkLength(String text) {
    if (text.length() <= HARD_CHUNK_CHAR_LIMIT) {
      return text;
    }
    log.warn(
        "A chunk exceeds the hard limit of {} characters ({} actual); truncating",
        HARD_CHUNK_CHAR_LIMIT,
        text.length());
    return text.substring(0, HARD_CHUNK_CHAR_LIMIT - TRUNCATION_MARKER.length())
        + TRUNCATION_MARKER;
  }
}
