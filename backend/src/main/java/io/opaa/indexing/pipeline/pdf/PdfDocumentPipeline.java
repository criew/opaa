package io.opaa.indexing.pipeline.pdf;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentHeadText;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The PDF pipeline (docs/features/ingestion-pipelines.md, Teil 1). Reads directly through Apache
 * PDFBox rather than Spring AI's PDF readers, both absent from this project's classpath.
 *
 * <p>When the PDF catalog (outline/bookmarks) is present, every outline entry that resolves to a
 * page becomes a heading of its own nesting level, cut without a depth cap (unlike Markdown/DOCX) -
 * a legal text's catalog commonly nests § and Absatz as two levels, each citable on its own.
 * Several outline entries sharing one page split that page's text between their titles rather than
 * each claiming the whole page. Without a resolvable outline, chunking falls back to one chunk per
 * non-blank page.
 *
 * <p>Scan detection (#1055) is answered from this pipeline's own PDFBox extraction: a PDF whose
 * extracted text is entirely blank is rejected as {@code NO_EXTRACTABLE_TEXT}.
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
      DocumentProperties properties = properties(doc, entries, firstPageText(doc));
      if (!entries.isEmpty()) {
        return chunkByOutline(doc, entries).withProperties(properties);
      }
      return chunkByPage(doc).withProperties(properties);
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read PDF document {} via PDFBox", source.fileName(), e);
      return DocumentPipelineResult.noContent();
    }
  }

  /**
   * The Info dictionary's Title/CreationDate/ModDate, the first top-level outline entry as the
   * first heading (ADR-0024) and the opening of the first page's text as the head text (#1263) -
   * the only page whose text is extracted here.
   */
  @Override
  public DocumentProperties readProperties(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    try (PDDocument doc = Loader.loadPDF(source.file().toFile())) {
      return properties(doc, flattenOutline(doc), firstPageText(doc));
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read PDF properties of {} via PDFBox", source.fileName(), e);
      return DocumentProperties.EMPTY;
    }
  }

  /**
   * The first page's text as the head area (#1263), or {@code null} when it cannot be extracted -
   * never a failure. Read on both paths, so {@link #run} and {@link #readProperties} declare the
   * same head for the same file.
   */
  private static String firstPageText(PDDocument doc) {
    try {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(1);
      stripper.setEndPage(1);
      return stripper.getText(doc);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static DocumentProperties properties(
      PDDocument doc, List<OutlineEntry> entries, String text) {
    PDDocumentInformation info = doc.getDocumentInformation();
    String firstHeading =
        entries.stream()
            .filter(entry -> entry.level() == 1)
            .map(OutlineEntry::title)
            .findFirst()
            .orElse(null);
    String headText = DocumentHeadText.of(text);
    if (info == null) {
      return DocumentProperties.EMPTY.withFirstHeading(firstHeading).withHeadText(headText);
    }
    return new DocumentProperties(
        info.getTitle(),
        DocumentProperties.toLocalDate(info.getCreationDate()),
        DocumentProperties.toLocalDate(info.getModificationDate()),
        null,
        firstHeading,
        headText,
        null,
        false,
        Map.of());
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
