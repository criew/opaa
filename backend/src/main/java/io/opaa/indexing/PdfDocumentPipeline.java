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
 * <p><b>Scan detection is answered from this pipeline's own extraction, not a second Tika
 * parse.</b> #1055's rule is unchanged - a PDF whose extracted text is entirely blank is rejected
 * as {@code NO_EXTRACTABLE_TEXT} rather than silently indexed with zero chunks - but it is now
 * decided from the same {@link PDDocument} this pipeline already opens: {@link
 * PDFTextStripper}#getText(PDDocument)} over the whole document, blank or not. Running a full
 * {@code TikaDocumentReader} parse first only to discard it once PDFBox confirms the same thing
 * would cost a second, redundant extraction pass per document; Tika's own PDF module is a PDFBox
 * consumer for exactly this text-extraction step, so the two are not independent signals.
 *
 * <p><b>The PDF catalog (outline/bookmarks) decides the cut when present.</b> Every outline entry
 * that resolves to a page becomes a heading of its own nesting level - unlike {@link
 * MarkdownDocumentPipeline}/{@link DocxDocumentPipeline}, cutting is <b>not</b> capped at level 3:
 * a legal text's catalog commonly nests § and Absatz as two levels, and a deeper catalog should
 * still yield a citable chunk per level rather than folding a third level back into its parent's
 * text. An entry whose destination cannot be resolved (an action-based bookmark pointing outside
 * the document) is skipped, its children kept at their own nesting depth regardless.
 *
 * <p><b>Several outline entries on the same page</b> (the normal shape of a Satzung with several §§
 * per page) share that page's extracted text rather than each getting the whole page: the text
 * between one entry's title and the next entry's title (both located within the shared page's own
 * text) becomes that entry's body, so the first §'s text does not silently end up attached to the
 * second §'s chunk. Text found <em>before</em> the run's first title - the tail of whatever
 * preceded the run, e.g. the end of a § whose own heading sits on an earlier page while its body
 * continues onto this shared page - is not attributed to the run at all: it is folded into
 * whichever section is still open when the run starts (the preamble, or the previous entry's own
 * body), never dropped. When a title cannot be located verbatim in the extracted page text
 * (differing whitespace/line-break normalization between the catalog string and the page content
 * stream), the whole shared range falls back to the last entry in the run - the earlier entries in
 * the run still get their own heading-only chunk rather than being silently dropped.
 *
 * <p><b>Page-based chunking is the fallback</b> when the document has no outline, or none of its
 * entries resolve to a page - one chunk per non-blank page, carrying {@code "S. n"} as its {@link
 * ChunkingService#LOCATION_METADATA_KEY location}, mirroring {@code PagePdfDocumentReader}'s own
 * per-page unit.
 *
 * <p><b>A missing file source is treated as no content</b> - this pipeline, like {@link
 * DocxDocumentPipeline} and {@link PptxDocumentPipeline}, is only ever reached via a real file
 * routed by {@link DocumentPipelineRegistry}'s content detection; {@code
 * DocumentPipelineSource#extractedText()} (an RSS entry's already-extracted body, ADR-0017,
 * decision 2) can never carry PDF/DOCX/PPTX bytes, so there is nothing a binary-format pipeline
 * could parse from it.
 */
public class PdfDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(PdfDocumentPipeline.class);

  static final String ID = "pdf";
  static final short VERSION = 1;

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
      return DocumentPipelineResult.noContent();
    }
    try (PDDocument doc = Loader.loadPDF(source.file().toFile())) {
      String fullText = new PDFTextStripper().getText(doc);
      if (fullText.isBlank()) {
        return DocumentPipelineResult.noExtractableText();
      }
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
    int i = 0;
    while (i < entries.size()) {
      int page = entries.get(i).pageIndex();
      int runEnd = i;
      while (runEnd + 1 < entries.size() && entries.get(runEnd + 1).pageIndex() == page) {
        runEnd++;
      }
      List<OutlineEntry> run = entries.subList(i, runEnd + 1);
      int rangeEnd =
          runEnd + 1 < entries.size()
              ? entries.get(runEnd + 1).pageIndex()
              : doc.getNumberOfPages();
      String rangeText = extractPageRangeText(doc, page, rangeEnd);
      Attribution attribution = splitAmongSiblingTitles(rangeText, run);
      if (!attribution.head().isBlank()) {
        // Text before the run's first title belongs to whichever section is still open when
        // this run starts - the preamble (first run) or the previous run's last entry (#1104
        // review round 2, wichtig 1). Added before this run's own Heading events, so
        // HeadingSectionSplitter#chunk folds it into that still-open section rather than
        // dropping it.
        events.add(new HeadingSectionSplitter.Paragraph(attribution.head().strip()));
      }
      for (int k = 0; k < run.size(); k++) {
        OutlineEntry entry = run.get(k);
        events.add(new HeadingSectionSplitter.Heading(entry.level(), entry.title()));
        String body = attribution.bodies().get(k);
        if (!body.isBlank()) {
          events.add(new HeadingSectionSplitter.Paragraph(body.strip()));
        }
      }
      i = runEnd + 1;
    }
    // No cap on the cutting level - see this class's own Javadoc for why the PDF catalog is
    // trusted at every nesting depth, unlike Markdown/DOCX.
    List<Document> chunks = HeadingSectionSplitter.chunk(events, Integer.MAX_VALUE);
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /**
   * @param head the text before the run's first entry's title, in document order - see {@link
   *     #chunkByOutline}'s own use of it. Empty when the run's first title sits at the very start
   *     of {@code rangeText}, or when the fallback below applies.
   * @param bodies one entry per {@code run} entry, its own text between its title and the next
   *     one's (or the range's end for the last entry).
   */
  private record Attribution(String head, List<String> bodies) {}

  /**
   * Attributes {@code rangeText} (the extracted text of the page(s) {@code run} shares) to each
   * entry in {@code run} individually plus the run's own head, by locating every entry's title text
   * in document order and cutting between consecutive matches - see this class's own Javadoc,
   * "Several outline entries on the same page". Titles are located even for a single-entry run: the
   * page a lone entry is bookmarked to can itself carry trailing content from whatever preceded it
   * (the same head-attribution concern, just with a run of one). Falls back to attributing the
   * whole range to the last entry, with no head - today's pre-split-fix behaviour, kept as the
   * graceful degradation - when any title cannot be located verbatim (differing
   * whitespace/line-break normalization between the catalog string and the page content stream).
   */
  private static Attribution splitAmongSiblingTitles(String rangeText, List<OutlineEntry> run) {
    List<Integer> starts = new ArrayList<>(run.size());
    int searchFrom = 0;
    for (OutlineEntry entry : run) {
      int index = rangeText.indexOf(entry.title(), searchFrom);
      if (index < 0) {
        List<String> fallbackBodies = new ArrayList<>(run.size());
        for (int i = 0; i < run.size() - 1; i++) {
          fallbackBodies.add("");
        }
        fallbackBodies.add(rangeText);
        return new Attribution("", fallbackBodies);
      }
      starts.add(index);
      searchFrom = index + entry.title().length();
    }
    String head = rangeText.substring(0, starts.get(0));
    List<String> bodies = new ArrayList<>(run.size());
    for (int i = 0; i < run.size(); i++) {
      int bodyStart = starts.get(i) + run.get(i).title().length();
      int bodyEnd = i + 1 < run.size() ? starts.get(i + 1) : rangeText.length();
      bodies.add(rangeText.substring(bodyStart, Math.max(bodyStart, bodyEnd)));
    }
    return new Attribution(head, bodies);
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
      chunks.add(new Document(HeadingSectionSplitter.capChunkLength(text.strip()), metadata));
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
}
