package io.opaa.format.file.pdf;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.DocumentProperties;
import io.opaa.format.FileDocumentFormat;
import io.opaa.format.FormatAdmission;
import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.DocumentTitleLine;
import io.opaa.format.shared.HeadingSectionSplitter;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.springframework.ai.document.Document;

/**
 * The PDF pipeline (ingestion-pipelines.md, Teil 1), reading through PDFBox rather than Spring AI's
 * PDF readers, which are absent from this classpath. With a resolvable outline every entry becomes
 * a heading of its own nesting level, cut without a depth cap - a legal text commonly nests § and
 * Absatz as two separately citable levels - and several entries on one page split that page's text
 * between their titles. Without an outline, chunking falls back to one chunk per non-blank page.
 * Either way every chunk's anchor names its pages; along the outline {@link PdfPageLocator} finds
 * them again.
 *
 * <p>Every page's text is extracted exactly once, into {@link PdfContent}; a page range is the
 * concatenation of its pages. A table enclosed by a complete ruled grid is written one line per
 * row, cells joined as in every other format ({@link PdfPageText}).
 *
 * <p>Scan detection is answered from this pipeline's own extraction: a PDF whose text is entirely
 * blank is rejected as {@code NO_EXTRACTABLE_TEXT}, and so is one where more than half of the pages
 * carry an image but no text - a typed cover sheet in front of scanned pages. An empty page without
 * an image of its own does not count as scanned; text only in form fields or annotations is not
 * extracted, so such a page over an image counts as scanned.
 *
 * <p>The extracted text is bounded by {@link ParserLimitProperties#maxTextCharacters()} (a PDF past
 * it fails to parse), the outline walk by {@link #MAX_OUTLINE_DEPTH} and {@link #MAX_OUTLINE_ITEMS}
 * and by visiting every item once.
 */
public class PdfDocumentFormat extends FileDocumentFormat<PdfDocumentFormat.PdfContent> {

  static final String ID = "pdf";
  static final short VERSION = 6;

  /** Outline nesting deeper than this is not walked; its entries fold into their ancestor. */
  static final int MAX_OUTLINE_DEPTH = 64;

  /** Outline items visited per document; the walk stops there and keeps what it has. */
  static final int MAX_OUTLINE_ITEMS = 50_000;

  private final HeadingSectionSplitter splitter;
  private final ParserLimitProperties limits;

  public PdfDocumentFormat(HeadingSectionSplitter splitter) {
    this(splitter, ParserLimitProperties.DEFAULTS);
  }

  public PdfDocumentFormat(HeadingSectionSplitter splitter, ParserLimitProperties limits) {
    this.splitter = splitter;
    this.limits = limits;
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
  public String chunkingFingerprint() {
    return splitter.chunkingFingerprint();
  }

  @Override
  public String unrecordedChunkingFingerprint() {
    return HeadingSectionSplitter.UNRECORDED_CHUNKING_FINGERPRINT;
  }

  @Override
  public Set<FormatAdmission> admittedFormats() {
    return Set.of(FormatAdmission.detectedAs(".pdf", "application/pdf"));
  }

  /**
   * One PDF's extraction, as plain data outliving the {@link PDDocument}: {@code pageTexts} in page
   * order, the flattened outline, and the Info dictionary's own properties.
   */
  public record PdfContent(
      List<String> pageTexts,
      List<OutlineEntry> entries,
      DocumentProperties info,
      int scannedPages) {}

  /** Nesting of form XObjects followed when looking for an image on a page. */
  private static final int IMAGE_SEARCH_DEPTH = 3;

  record OutlineEntry(int level, String title, int pageIndex) {}

  @Override
  protected PdfContent read(DocumentFormatSource source) throws IOException {
    try (PDDocument doc = Loader.loadPDF(source.file().toFile())) {
      List<String> pageTexts = new ArrayList<>(doc.getNumberOfPages());
      PdfReadBudget budget = PdfReadBudget.of(limits);
      int scannedPages = 0;
      for (int i = 0; i < doc.getNumberOfPages(); i++) {
        String text = PdfPageText.extract(doc, i, budget);
        pageTexts.add(text);
        if (text.isBlank() && carriesImage(ownResources(doc.getPage(i)), IMAGE_SEARCH_DEPTH)) {
          scannedPages++;
        }
      }
      return new PdfContent(pageTexts, flattenOutline(doc), infoProperties(doc), scannedPages);
    }
  }

  @Override
  protected DocumentFormatResult chunks(DocumentFormatSource source, PdfContent content) {
    if (content.pageTexts().stream().allMatch(String::isBlank)
        || content.scannedPages() * 2 > content.pageTexts().size()) {
      return DocumentFormatResult.noExtractableText();
    }
    return content.entries().isEmpty()
        ? chunkByPage(content.pageTexts())
        : chunkByOutline(content.pageTexts(), content.entries());
  }

  /**
   * The Info dictionary's Title/CreationDate/ModDate, the first top-level outline entry as the
   * first heading (ADR-0024) and the first page's text as both title line and head text - the head
   * area is the only place a title line or an anchored date is read from.
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
        .withTitleLine(DocumentTitleLine.of(firstPageText))
        .withHeadText(firstPageText);
  }

  /**
   * Extracts the first page alone: the head area is the only page a title line is read from, so the
   * Bestandslauf does not pay for the whole document's text.
   */
  @Override
  protected DocumentProperties declaredProperties(DocumentFormatSource source) throws IOException {
    try (PDDocument doc = Loader.loadPDF(source.file().toFile())) {
      List<String> firstPage =
          doc.getNumberOfPages() == 0
              ? List.of()
              : List.of(PdfPageText.extract(doc, 0, PdfReadBudget.of(limits)));
      return properties(new PdfContent(firstPage, flattenOutline(doc), infoProperties(doc), 0));
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
    Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    visited.add(outline.getCOSObject());
    collectOutline(doc, outline, 1, entries, visited);
    // A pre-order walk of the catalog is not guaranteed to be monotonic in page number for every
    // pathological document; a stable sort on page index restores that order (needed by
    // HeadingSectionSplitter's section algorithm) while keeping same-page entries in their
    // original, parent-before-child order.
    entries.sort(Comparator.comparingInt(OutlineEntry::pageIndex));
    return entries;
  }

  /**
   * Pre-order walk of the outline tree. The catalog is untrusted: {@code /First} and {@code /Next}
   * may point back to an item already seen, so every item is visited at most once (by identity of
   * its dictionary), at most {@link #MAX_OUTLINE_DEPTH} levels deep and at most {@link
   * #MAX_OUTLINE_ITEMS} items in total. A cycle or a cut-off ends that branch; the entries found so
   * far stand, so the document is still read along its outline.
   */
  private static void collectOutline(
      PDDocument doc,
      PDOutlineNode node,
      int level,
      List<OutlineEntry> out,
      Set<COSDictionary> visited) {
    if (level > MAX_OUTLINE_DEPTH) {
      return;
    }
    for (PDOutlineItem item = node.getFirstChild();
        item != null && visited.size() <= MAX_OUTLINE_ITEMS && visited.add(item.getCOSObject());
        item = item.getNextSibling()) {
      Integer pageIndex = resolvePageIndex(doc, item);
      String title = item.getTitle();
      if (pageIndex != null && title != null && !title.isBlank()) {
        out.add(new OutlineEntry(level, title.strip(), pageIndex));
      }
      collectOutline(doc, item, level + 1, out, visited);
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

  private DocumentFormatResult chunkByOutline(List<String> pageTexts, List<OutlineEntry> entries) {
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
    List<Document> chunks = splitter.chunk(events, Integer.MAX_VALUE);
    if (chunks.isEmpty()) {
      return DocumentFormatResult.noExtractableText();
    }
    PdfPageLocator.forPages(pageTexts).addPages(chunks);
    return DocumentFormatResult.chunked(chunks);
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

  private DocumentFormatResult chunkByPage(List<String> pageTexts) {
    List<Document> chunks = new ArrayList<>();
    for (int i = 0; i < pageTexts.size(); i++) {
      String text = pageTexts.get(i);
      if (text.isBlank()) {
        continue;
      }
      Map<String, Object> metadata = new HashMap<>();
      metadata.put(ChunkMetadataKeys.LOCATION_METADATA_KEY, "S. " + (i + 1));
      ChunkAnchors.write(metadata, ChunkAnchor.EMPTY.withPages(i + 1, i + 1));
      chunks.addAll(splitter.boundedChunks(null, text.strip(), metadata));
    }
    if (chunks.isEmpty()) {
      return DocumentFormatResult.noExtractableText();
    }
    return DocumentFormatResult.chunked(chunks);
  }

  /**
   * The resources the page itself names, not those inherited from the page tree: a logo shared by
   * every page must not make an empty separator page count as scanned.
   */
  private static PDResources ownResources(PDPage page) {
    COSDictionary own = page.getCOSObject().getCOSDictionary(COSName.RESOURCES);
    return own == null ? null : new PDResources(own);
  }

  /**
   * Whether {@code resources} name an image, directly or inside a form up to {@code depth} deep; an
   * XObject that cannot be read counts as none, as it does for the text extraction.
   */
  private static boolean carriesImage(PDResources resources, int depth) {
    if (resources == null || depth == 0) {
      return false;
    }
    for (COSName name : resources.getXObjectNames()) {
      PDXObject object;
      try {
        object = resources.getXObject(name);
      } catch (IOException | RuntimeException e) {
        continue;
      }
      if (object instanceof PDImageXObject) {
        return true;
      }
      if (object instanceof PDFormXObject form && carriesImage(form.getResources(), depth - 1)) {
        return true;
      }
    }
    return false;
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
}
