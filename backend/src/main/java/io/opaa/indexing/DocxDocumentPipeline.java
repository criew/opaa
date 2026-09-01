package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The DOCX pipeline (docs/features/ingestion-pipelines.md, Teil 2: "Markdown, DOCX, HTML |
 * Überschriftenabschnitt"; Teil 1's parsing table: "Tika, ergänzt um Überschriftenebenen aus den
 * Absatzformaten"). Reads a {@code .docx} directly through Apache POI's {@code XWPFDocument}
 * (already a project dependency for {@link TabularDocumentPipeline}'s XLSX reader) rather than
 * through Tika: the paragraph-format heading levels this pipeline cuts on are exactly what Tika's
 * flattened text extraction discards, so a second, format-aware reader is the only way to keep them
 * - Tika is not "ergänzt" after the fact, it is replaced for this one format.
 *
 * <p><b>Heading level comes from the paragraph's own formatting, not from guessed text
 * patterns</b>: a built-in Word heading style (styleId {@code Heading1}..{@code Heading9},
 * regardless of the UI locale the document was authored in - Word persists the English styleId even
 * under a German UI) or, failing that, the paragraph's direct outline level ({@code w:outlineLvl},
 * the same property Word's own "Gliederungsebene" dropdown sets and a table of contents is built
 * from). A paragraph with neither is body text, folded into the current section - exactly {@link
 * HtmlDocumentPipeline}'s treatment of everything that is not h1-h3.
 *
 * <p><b>Only {@code .docx} is handled, not the legacy binary {@code .doc}</b>: POI's OOXML reader
 * used here cannot open the older binary format at all, and the older {@code HWPFDocument} reader
 * has no equivalent paragraph-style API to extract a heading level from. {@code .doc} keeps running
 * through {@link TikaFallbackPipeline} unchanged.
 *
 * <p>Only top-level body paragraphs are read ({@link XWPFDocument#getParagraphs()}) - a table
 * cell's paragraphs are a known limitation, not covered by this pipeline (a document that relies on
 * a table for its layout loses that content, same trade-off {@link TabularDocumentPipeline} accepts
 * for a spreadsheet's own formulas).
 */
public class DocxDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(DocxDocumentPipeline.class);

  static final String ID = "docx";
  static final short VERSION = 1;

  /**
   * Cutting stops at level 3, per the Teil 2 table; a deeper heading folds into its section's text.
   */
  private static final int MAX_CUTTING_LEVEL = 3;

  private static final Pattern HEADING_STYLE =
      Pattern.compile("(?i)^(?:heading|berschrift|ueberschrift)[ _]?(\\d)$");

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
    return Set.of(".docx");
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    List<XWPFParagraph> paragraphs = readParagraphs(source);
    if (paragraphs == null) {
      return DocumentPipelineResult.noContent();
    }
    List<HeadingSectionSplitter.Event> events = toEvents(paragraphs);
    if (events.isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks = HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /** {@code null} when the file could not be opened as a DOCX at all - reported as no content. */
  private static List<XWPFParagraph> readParagraphs(DocumentPipelineSource source) {
    try (InputStream in = openStream(source)) {
      try (XWPFDocument document = new XWPFDocument(in)) {
        return document.getParagraphs();
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read DOCX document {}", source.fileName(), e);
      return null;
    }
  }

  private static InputStream openStream(DocumentPipelineSource source) throws IOException {
    if (source.file() != null) {
      return Files.newInputStream(source.file());
    }
    return new java.io.ByteArrayInputStream(
        source.extractedText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static List<HeadingSectionSplitter.Event> toEvents(List<XWPFParagraph> paragraphs) {
    List<HeadingSectionSplitter.Event> events = new ArrayList<>();
    for (XWPFParagraph paragraph : paragraphs) {
      String text = paragraph.getText();
      Integer level = headingLevel(paragraph);
      if (level != null) {
        events.add(new HeadingSectionSplitter.Heading(level, text == null ? "" : text));
      } else if (text != null && !text.isBlank()) {
        events.add(new HeadingSectionSplitter.Paragraph(text));
      }
    }
    return events;
  }

  private static Integer headingLevel(XWPFParagraph paragraph) {
    String styleId = paragraph.getStyleID();
    if (styleId != null) {
      Matcher matcher = HEADING_STYLE.matcher(styleId);
      if (matcher.matches()) {
        return Integer.parseInt(matcher.group(1));
      }
    }
    try {
      var pPr = paragraph.getCTP().getPPr();
      if (pPr != null && pPr.isSetOutlineLvl()) {
        // w:outlineLvl is zero-based (0 = "Ebene 1"); the same property Word's own
        // "Gliederungsebene" control writes and a table of contents is generated from.
        return pPr.getOutlineLvl().getVal().intValue() + 1;
      }
    } catch (RuntimeException e) {
      // Malformed/unexpected paragraph properties - treat as body text rather than fail the
      // whole document over one paragraph's formatting.
      log.debug("Could not read outline level of a DOCX paragraph", e);
    }
    return null;
  }
}
