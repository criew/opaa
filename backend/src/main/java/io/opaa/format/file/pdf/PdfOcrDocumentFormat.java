package io.opaa.format.file.pdf;

import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.DocumentProperties;
import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.file.pdf.RecognizedPdfBlocks.Block;
import io.opaa.format.file.pdf.RecognizedPdfBlocks.Kind;
import io.opaa.format.shared.BoundarySplitter;
import io.opaa.format.shared.HeadingSectionSplitter;
import io.opaa.format.shared.RepeatedContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.ai.document.Document;

/**
 * The text of a scanned PDF as text recognition (OCR) read it ({@link RecognizedPdf}), cut along
 * its outline: headings open a section and form the heading path, paragraphs are packed up to the
 * target size of {@link HeadingSectionSplitter}, a table becomes rows with cell separators, each
 * row whole, the table's header line repeated in front. Page headers, footers and page numbers the
 * layout model recognised are not part of it.
 *
 * <p>Never routed by content - {@code DocumentIngestService} hands it the stored recognition of a
 * PDF the {@code pdf} format found without text, as {@link DocumentFormatSource#ofExtractedText} of
 * {@link RecognizedPdf#toJson}. Every chunk carries pages, heading path and, from a
 * DoclingDocument, one frame per page in its anchor, the text origin in its anchor and the
 * recipient under {@link ChunkMetadataKeys#TEXT_RECOGNITION_METADATA_KEY}.
 */
public final class PdfOcrDocumentFormat implements DocumentFormat {

  public static final String ID = "pdf-ocr";

  /** Raised whenever the cut or the structure metadata changes; it also lifts every lock. */
  public static final short VERSION = 1;

  private static final String PARAGRAPH_SEPARATOR = "\n\n";
  private static final String ROW_SEPARATOR = "\n";

  private final HeadingSectionSplitter splitter;

  public PdfOcrDocumentFormat(HeadingSectionSplitter splitter) {
    this.splitter = splitter;
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
  public Set<String> passthroughMetadataKeys() {
    return Set.of(
        ChunkMetadataKeys.LOCATION_METADATA_KEY,
        ChunkMetadataKeys.ANCHOR_METADATA_KEY,
        ChunkMetadataKeys.TEXT_RECOGNITION_METADATA_KEY);
  }

  @Override
  public DocumentFormatResult run(DocumentFormatSource source) {
    if (source.extractedText() == null) {
      throw new IllegalArgumentException("pdf-ocr reads a stored recognition, not a file");
    }
    RecognizedPdf recognized = RecognizedPdf.parse(source.extractedText());
    List<Block> blocks = RecognizedPdfBlocks.of(recognized);
    List<Document> chunks = new Cut(recognized).chunks(blocks);
    if (chunks.isEmpty()) {
      return DocumentFormatResult.noExtractableText();
    }
    return DocumentFormatResult.chunked(chunks).withProperties(properties(blocks));
  }

  /** The first title or heading as first heading and title line, the opening text as head text. */
  private static DocumentProperties properties(List<Block> blocks) {
    String firstHeading = null;
    StringBuilder head = new StringBuilder();
    for (Block block : blocks) {
      if (firstHeading == null && block.kind() == Kind.HEADING) {
        firstHeading = block.text();
      }
      if (head.length() < DocumentProperties.MAX_HEAD_TEXT_LENGTH) {
        head.append(block.text()).append('\n');
      }
    }
    String headText =
        head.length() > DocumentProperties.MAX_HEAD_TEXT_LENGTH
            ? head.substring(0, DocumentProperties.MAX_HEAD_TEXT_LENGTH)
            : head.toString().strip();
    return DocumentProperties.EMPTY
        .withFirstHeading(firstHeading)
        .withTitleLine(firstHeading)
        .withHeadText(headText.isEmpty() ? null : headText);
  }

  /** One pass over the blocks of one recognition. */
  private final class Cut {

    private final ChunkAnchor.TextOrigin origin;
    private final Map<String, Object> recognition;
    private final List<Document> chunks = new ArrayList<>();
    private final NavigableMap<Integer, String> path = new TreeMap<>();
    private final List<Block> body = new ArrayList<>();
    private Block heading;

    Cut(RecognizedPdf recognized) {
      this.origin = new ChunkAnchor.TextOrigin(recognized.connectionType());
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("connectionType", recognized.connectionType());
      values.put("configurationId", recognized.configurationId());
      this.recognition = values;
    }

    List<Document> chunks(List<Block> blocks) {
      for (Block block : blocks) {
        if (block.kind() == Kind.HEADING) {
          closeSection(block.level());
          path.tailMap(block.level(), true).clear();
          path.put(block.level(), RepeatedContext.cut(block.text()));
          heading = block;
        } else {
          body.add(block);
        }
      }
      closeSection(null);
      return chunks;
    }

    /**
     * Emits the open section. A section that is nothing but its heading becomes a heading-only
     * chunk, unless a deeper heading closes it: its title then opens every descendant's path.
     */
    private void closeSection(Integer closingLevel) {
      if (body.isEmpty()) {
        boolean closedByDeeper =
            closingLevel != null && !path.isEmpty() && closingLevel > path.lastKey();
        if (heading != null && !closedByDeeper) {
          emit(List.of(heading), "", heading.text());
        }
        heading = null;
        return;
      }
      String headingLine = headingLine();
      String lead = headingLine.isEmpty() ? "" : headingLine + PARAGRAPH_SEPARATOR;
      List<Block> run = new ArrayList<>();
      boolean first = true;
      for (Block block : body) {
        if (!run.isEmpty() && !Objects.equals(run.getFirst().table(), block.table())) {
          pack(run, lead, first);
          first = false;
          run = new ArrayList<>();
        }
        run.add(block);
      }
      pack(run, lead, first);
      body.clear();
      heading = null;
    }

    /** Packs a run of paragraphs, or of rows of one table, into chunks up to the target size. */
    private void pack(List<Block> run, String sectionLead, boolean withHeading) {
      boolean rows = run.getFirst().kind() == Kind.TABLE_ROW;
      String tableHeader = run.getFirst().tableHeader();
      String lead =
          rows && tableHeader != null
              ? (sectionLead.isEmpty()
                  ? tableHeader + ROW_SEPARATOR
                  : sectionLead + tableHeader + ROW_SEPARATOR)
              : sectionLead;
      String separator = rows ? ROW_SEPARATOR : PARAGRAPH_SEPARATOR;
      int budget =
          lead.isEmpty() ? splitter.softChunkCharLimit() : splitter.bodyBudget(lead.length());
      List<Block> members = new ArrayList<>();
      StringBuilder text = new StringBuilder();
      if (withHeading && heading != null) {
        members.add(heading);
      }
      for (Block block : run) {
        for (String part : BoundarySplitter.split(block.text(), budget)) {
          if (!text.isEmpty() && text.length() + separator.length() + part.length() > budget) {
            emit(members, lead, text.toString());
            members = new ArrayList<>();
            text.setLength(0);
          }
          if (!text.isEmpty()) {
            text.append(separator);
          }
          text.append(part);
          members.add(block);
        }
      }
      if (!text.isEmpty()) {
        emit(members, lead, text.toString());
      }
    }

    private void emit(List<Block> members, String lead, String text) {
      int from = Integer.MAX_VALUE;
      int to = 0;
      Map<Integer, List<ChunkAnchor.Region>> byPage = new TreeMap<>();
      for (Block member : members) {
        from = Math.min(from, member.pageFrom());
        to = Math.max(to, member.pageTo());
        if (member.regions() != null) {
          for (ChunkAnchor.Region region : member.regions()) {
            byPage.computeIfAbsent(region.page(), page -> new ArrayList<>()).add(region);
          }
        }
      }
      List<ChunkAnchor.Region> regions =
          byPage.values().stream().map(RecognizedPdfBlocks::union).toList();
      List<String> headings = List.copyOf(path.values());
      ChunkAnchor anchor =
          ChunkAnchor.EMPTY
              .withPath(headings)
              .withPages(from, to)
              .withRegions(regions)
              .withTextOrigin(origin);
      Map<String, Object> metadata = new HashMap<>();
      String pages = from == to ? "S. " + from : "S. " + from + "–" + to;
      String headingLine = headingLine();
      metadata.put(
          ChunkMetadataKeys.LOCATION_METADATA_KEY,
          headingLine.isEmpty() ? pages : pages + " · Abschn. " + headingLine);
      metadata.put(ChunkMetadataKeys.TEXT_RECOGNITION_METADATA_KEY, recognition);
      ChunkAnchors.write(metadata, anchor);
      ChunkAnchors.markLead(metadata, lead);
      String whole = lead.isEmpty() ? text : lead + text;
      if (whole.length() <= HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT) {
        chunks.add(new Document(whole, metadata));
        return;
      }
      for (String part : BoundarySplitter.split(text, splitter.softChunkCharLimit())) {
        chunks.add(new Document(lead.isEmpty() ? part : lead + part, new HashMap<>(metadata)));
      }
    }

    private String headingLine() {
      return RepeatedContext.cut(String.join(" › ", path.values()));
    }
  }
}
