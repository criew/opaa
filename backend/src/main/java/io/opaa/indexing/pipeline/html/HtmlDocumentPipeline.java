package io.opaa.indexing.pipeline.html;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.DocumentTitleLine;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Event;
import io.opaa.indexing.pipeline.XhtmlEventBuilder;
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
public class HtmlDocumentPipeline implements DocumentPipeline {

  public static final String ID = "html";
  static final short VERSION = 2;

  /** h1-h3 open a new chunk, h4-h6 fold into the text - like the Confluence pipeline. */
  static final int MAX_CUTTING_LEVEL = 3;

  /**
   * Soft budget a section's body text is grouped into before another chunk starts - the shared
   * {@link HeadingSectionSplitter#SOFT_CHUNK_CHAR_LIMIT}, named here because the tests reference it
   * by this class. <b>Gesetzt, nicht gemessen</b>: the evaluation corpus contains no HTML documents
   * to measure against.
   */
  static final int SOFT_CHUNK_CHAR_LIMIT = HeadingSectionSplitter.SOFT_CHUNK_CHAR_LIMIT;

  /** Absolute ceiling on a single chunk's text - {@link HeadingSectionSplitter}'s backstop. */
  static final int HARD_CHUNK_CHAR_LIMIT = HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT;

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
    return Set.of(".html");
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    org.jsoup.nodes.Document htmlDoc = parse(source);
    List<Element> contentRoots = contentRoots(source, htmlDoc);
    if (contentRoots.isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks = new ArrayList<>();
    String titleLine = null;
    for (Element root : contentRoots) {
      // Each root is cut on its own outline; a heading path never carries across roots.
      List<Event> events = new XhtmlEventBuilder().build(root);
      chunks.addAll(HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL));
      if (titleLine == null) {
        titleLine = DocumentTitleLine.ofEvents(events);
      }
    }
    if (chunks.isEmpty()) {
      // A page whose entire body is boilerplate - the same "parsed, but nothing usable" outcome
      // every other pipeline reports for content that reduces to nothing.
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks)
        .withProperties(properties(source, htmlDoc, titleLine));
  }

  /**
   * The {@code <title>}, the first {@code <h1>} (ADR-0024) and the title line of the content - read
   * from the same boilerplate-stripped view {@link #run} sees, so a navigation label never becomes
   * a Dokumentart.
   */
  @Override
  public DocumentProperties readProperties(DocumentPipelineSource source) {
    try {
      org.jsoup.nodes.Document htmlDoc = parse(source);
      String titleLine = null;
      for (Element root : contentRoots(source, htmlDoc)) {
        titleLine = DocumentTitleLine.ofEvents(new XhtmlEventBuilder().build(root));
        if (titleLine != null) {
          break;
        }
      }
      return properties(source, htmlDoc, titleLine);
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
      DocumentPipelineSource source, org.jsoup.nodes.Document htmlDoc) {
    if (source.file() != null) {
      return HtmlContentRoots.select(htmlDoc);
    }
    htmlDoc.select(HtmlContentRoots.UNCONDITIONAL_BOILERPLATE_SELECTOR).remove();
    Element body = htmlDoc.body();
    return body == null ? List.of() : List.of(body);
  }

  /**
   * The title line is a file's alone: text that never was a file is a feed entry, which names other
   * documents than itself - a press release names the Satzung it reports about, and would inherit
   * its Dokumentart.
   */
  private static DocumentProperties properties(
      DocumentPipelineSource source, org.jsoup.nodes.Document htmlDoc, String titleLine) {
    Element h1 = htmlDoc.selectFirst("h1");
    return DocumentProperties.EMPTY
        .withTitle(htmlDoc.title())
        .withFirstHeading(h1 == null ? null : h1.text())
        .withTitleLine(source.file() == null ? null : titleLine);
  }

  private static org.jsoup.nodes.Document parse(DocumentPipelineSource source) {
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
