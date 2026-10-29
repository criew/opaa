package io.opaa.format.file.html;

import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.DocumentProperties;
import io.opaa.format.FormatAdmission;
import io.opaa.format.shared.DocumentHeadText;
import io.opaa.format.shared.DocumentTitleLine;
import io.opaa.format.shared.HeadingSectionSplitter;
import io.opaa.format.shared.HeadingSectionSplitter.Event;
import io.opaa.format.shared.ParserLimitProperties;
import io.opaa.format.shared.XhtmlEventBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.ai.document.Document;

/**
 * The HTML pipeline (ingestion-pipelines.md, Teil 3, Punkt 4): strips the page chrome via {@link
 * HtmlContentRoots}, reads each content root through the shared {@link XhtmlEventBuilder} and cuts
 * along h1-h3 via {@link HeadingSectionSplitter}. Claims {@code .html} files and is named by id for
 * content that never was a file - a feed entry's detail page, handed over as the HTML of its
 * content roots.
 *
 * <p>Each chunk's heading path travels as Fundort metadata and as a leading text line, repeated on
 * every further-split sub-chunk; h4-h6 stay inside their section. An empty section still becomes a
 * one-line chunk.
 */
public class HtmlDocumentFormat implements DocumentFormat {

  public static final String ID = "html";
  static final short VERSION = 6;

  /** h1-h3 open a new chunk, h4-h6 fold into the text - like the Confluence pipeline. */
  static final int MAX_CUTTING_LEVEL = 3;

  /**
   * The shared cut; its target size is <b>gesetzt, nicht gemessen</b> for HTML: the evaluation
   * corpus contains no HTML documents to measure against.
   */
  private final HeadingSectionSplitter splitter;

  private final ParserLimitProperties limits;

  public HtmlDocumentFormat(HeadingSectionSplitter splitter) {
    this(splitter, ParserLimitProperties.DEFAULTS);
  }

  public HtmlDocumentFormat(HeadingSectionSplitter splitter, ParserLimitProperties limits) {
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

  /**
   * HTML has a distinctive enough signature (DOCTYPE/{@code <html>} tag) that Tika tells it apart
   * from plain text; the XHTML variant means the same format here, mirroring {@code
   * DetailPageExtractor}'s own {@code isHtmlContentType} (ingestion-pipelines.md, Teil 3, Punkt 4).
   */
  @Override
  public Set<FormatAdmission> admittedFormats() {
    return Set.of(FormatAdmission.detectedAs(".html", "text/html", "application/xhtml+xml"));
  }

  @Override
  public DocumentFormatResult run(DocumentFormatSource source) {
    org.jsoup.nodes.Document htmlDoc = parse(source);
    List<Element> contentRoots = contentRoots(source, htmlDoc);
    if (contentRoots.isEmpty()) {
      return DocumentFormatResult.noContent();
    }
    List<Document> chunks = new ArrayList<>();
    String titleLine = null;
    String headText = null;
    for (Element root : contentRoots) {
      // Each root is cut on its own outline; a heading path never carries across roots.
      List<Event> events = new XhtmlEventBuilder().build(root);
      chunks.addAll(splitter.chunk(events, MAX_CUTTING_LEVEL));
      if (titleLine == null) {
        titleLine = DocumentTitleLine.ofEvents(events);
        headText = DocumentHeadText.ofEvents(events);
      }
    }
    if (chunks.isEmpty()) {
      // A page whose entire body is boilerplate - the same "parsed, but nothing usable" outcome
      // every other pipeline reports for content that reduces to nothing.
      return DocumentFormatResult.noExtractableText();
    }
    limits.requireTextWithinLimit(chunks, "HTML document");
    return DocumentFormatResult.chunked(chunks)
        .withProperties(properties(source, htmlDoc, titleLine, headText));
  }

  /**
   * The {@code <title>}, the first {@code <h1>} (ADR-0024) and the title line of the content - read
   * from the same boilerplate-stripped view {@link #run} sees, so a navigation label never becomes
   * a Dokumentart.
   */
  @Override
  public DocumentProperties readProperties(DocumentFormatSource source) {
    try {
      org.jsoup.nodes.Document htmlDoc = parse(source);
      String titleLine = null;
      String headText = null;
      for (Element root : contentRoots(source, htmlDoc)) {
        List<Event> events = new XhtmlEventBuilder().build(root);
        titleLine = DocumentTitleLine.ofEvents(events);
        headText = DocumentHeadText.ofEvents(events);
        if (titleLine != null) {
          break;
        }
      }
      return properties(source, htmlDoc, titleLine, headText);
    } catch (UncheckedIOException e) {
      return DocumentProperties.EMPTY;
    }
  }

  /**
   * A file is cut from the content roots {@link HtmlContentRoots} selects. Extracted text is
   * already the reduced content the connector chose (under its own selector), so it is taken as one
   * root without a second selection: a header or teaser inside that content is content, and the
   * conditional chrome stripping must not run against it. Only what is never content is still
   * removed, which is idempotent on a connector-reduced fragment.
   */
  private static List<Element> contentRoots(
      DocumentFormatSource source, org.jsoup.nodes.Document htmlDoc) {
    if (source.file() != null) {
      return HtmlContentRoots.select(htmlDoc);
    }
    htmlDoc.select(HtmlContentRoots.UNCONDITIONAL_BOILERPLATE_SELECTOR).remove();
    Element body = htmlDoc.body();
    return body == null ? List.of() : List.of(body);
  }

  /**
   * Title line and head text are a file's alone: text that never was a file is a feed entry, which
   * names other documents than itself - a press release names the Satzung it reports about, and
   * would inherit its Dokumentart and its Inkrafttretensdatum.
   */
  private static DocumentProperties properties(
      DocumentFormatSource source,
      org.jsoup.nodes.Document htmlDoc,
      String titleLine,
      String headText) {
    Element h1 = htmlDoc.selectFirst("h1");
    return DocumentProperties.EMPTY
        .withTitle(htmlDoc.title())
        .withFirstHeading(h1 == null ? null : h1.text())
        .withTitleLine(source.file() == null ? null : titleLine)
        .withHeadText(source.file() == null ? null : headText);
  }

  private static org.jsoup.nodes.Document parse(DocumentFormatSource source) {
    try {
      if (source.file() != null) {
        // charsetName null: Jsoup detects it from a BOM or a <meta charset> tag and falls back to
        // UTF-8 itself - the same auto-detection DetailPageExtractor relies on for a fetched page.
        return Jsoup.parse(source.file().toFile(), null);
      }
      return Jsoup.parse(source.extractedText());
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read HTML document " + source.fileName(), e);
    }
  }
}
