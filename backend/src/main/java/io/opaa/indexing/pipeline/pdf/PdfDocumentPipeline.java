package io.opaa.indexing.pipeline.pdf;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.DocumentTitleLine;
import io.opaa.indexing.pipeline.FileDocumentPipeline;
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
import org.springframework.ai.document.Document;

/**
 * The PDF pipeline (ingestion-pipelines.md, Teil 1), reading through PDFBox rather than Spring AI's
 * PDF readers, which are absent from this classpath. With a resolvable outline every entry becomes
 * a heading of its own nesting level, cut without a depth cap - a legal text commonly nests § and
 * Absatz as two separately citable levels - and several entries on one page split that page's text
 * between their titles. Without an outline, chunking falls back to one chunk per non-blank page.
 *
 * <p>Every page's text is extracted exactly once, into {@link PdfContent}; a page range is the
 * concatenation of its pages, which is what the stripper itself produces for that range.
 *
 * <p>Scan detection is answered from this pipeline's own extraction: a PDF whose text is entirely
 * blank is rejected as {@code NO_EXTRACTABLE_TEXT}.
 */
public class PdfDocumentPipeline extends FileDocumentPipeline<PdfDocumentPipeline.PdfContent> {

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

  /**
   * One PDF's extraction, as plain data outliving the {@link PDDocument}: {@code pageTexts} in page
   * order, the flattened outline, and the Info dictionary's own properties.
   */
  public record PdfContent(
      List<String> pageTexts, List<OutlineEntry> entries, DocumentProperties info) {}

  record OutlineEntry(int level, String title, int pageIndex) {}

  @Override
  protected PdfContent read(DocumentPipelineSource source) throws IOException {
    try (PDDocument doc = Loader.loadPDF(source.file().toFile())) {
      List<String> pageTexts = new ArrayList<>(doc.getNumberOfPages());
      for (int i = 0; i < doc.getNumberOfPages(); i++) {
        pageTexts.add(extractPageText(doc, i));
      }
      return new PdfContent(pageTexts, flattenOutline(doc), infoProperties(doc));
    }
  }

  @Override
  protected DocumentPipelineResult chunks(DocumentPipelineSource source, PdfContent content) {
    if (content.pageTexts().stream().allMatch(String::isBlank)) {
      return DocumentPipelineResult.noExtractableText();
    }
    return content.entries().isEmpty()
        ? chunkByPage(content.pageTexts())
        : chunkByOutline(content.pageTexts(), content.entries());
  }

  /**
   * The Info dictionary's Title/CreationDate/ModDate, the first top-level outline entry as the
   * first heading (ADR-0024) and the opening of the first page's text as the head text - the head
   * area is the only place a title line is read from.
   */
  @Override
  protected DocumentProperties properties(PdfContent content) {
    String firstHeading =
        content.entries().stream()
            .filter(entry -> entry.level() == 1)
            .map(OutlineEntry::title)
            .findFirst()
            .orElse(null);
    String firstPageText = content.pageTexts().isEmpty() ? null : content.pageTexts().getFirst();
    return content
        .info()
        .withFirstHeading(firstHeading)
        .withTitleLine(DocumentTitleLine.of(firstPageText));
  }

  /**
   * Extracts the first page alone: the head area is the only page a title line is read from, so the
   * Bestandslauf does not pay for the whole document's text.
   */
  @Override
  protected DocumentProperties declaredProperties(DocumentPipelineSource source)
      throws IOException {
    try (PDDocument doc = Loader.loadPDF(source.file().toFile())) {
      List<String> firstPage =
          doc.getNumberOfPages() == 0 ? List.of() : List.of(extractPageText(doc, 0));
      return properties(new PdfContent(firstPage, flattenOutline(doc), infoProperties(doc)));
    }
  }

  private static DocumentProperties infoProperties(PDDocument doc) {
    PDDocumentInformation info = doc.getDocumentInformation();
    if (info == null) {
      return DocumentProperties.EMPTY;
    }
    return DocumentProperties.builder()
        .title(info.getTitle())
        .createdAt(DocumentProperties.toLocalDate(info.getCreationDate()))
        .modifiedAt(DocumentProperties.toLocalDate(info.getModificationDate()))
        .build();
  }

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

  private static DocumentPipelineResult chunkByOutline(
      List<String> pageTexts, List<OutlineEntry> entries) {
    List<HeadingSectionSplitter.Event> events = new ArrayList<>();
    int firstStart = entries.get(0).pageIndex();
    if (firstStart > 0) {
      String preamble = pageRangeText(pageTexts, 0, firstStart);
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
          runEnd + 1 < entries.size() ? entries.get(runEnd + 1).pageIndex() : pageTexts.size();
      String rangeText = pageRangeText(pageTexts, page, rangeEnd);
      Attribution attribution = splitAmongSiblingTitles(rangeText, run);
      if (!attribution.head().isBlank()) {
        // Text before the run's first title belongs to whichever section is still open when
        // this run starts - the preamble (first run) or the previous run's last entry. Added
        // before this run's own Heading events, so HeadingSectionSplitter#chunk folds it into
        // that still-open section rather than dropping it.
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

  private static DocumentPipelineResult chunkByPage(List<String> pageTexts) {
    List<Document> chunks = new ArrayList<>();
    for (int i = 0; i < pageTexts.size(); i++) {
      String text = pageTexts.get(i);
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
  private static String pageRangeText(
      List<String> pageTexts, int startPageIndex, int endPageIndexExclusive) {
    if (endPageIndexExclusive <= startPageIndex) {
      return "";
    }
    return String.join("", pageTexts.subList(startPageIndex, endPageIndexExclusive));
  }

  /** A page whose text cannot be extracted fails the whole document - nothing is known about it. */
  private static String extractPageText(PDDocument doc, int pageIndex) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(pageIndex + 1);
    stripper.setEndPage(pageIndex + 1);
    return stripper.getText(doc);
  }
}
