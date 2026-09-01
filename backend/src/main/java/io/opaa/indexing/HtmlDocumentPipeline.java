package io.opaa.indexing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The HTML pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 4): the core problem is
 * boilerplate - navigation, footer, cookie notice and sidebar make up a large share of a typical
 * page and, read through Tika, end up in the chunk as if they were content. This reads the page
 * with Jsoup (already a project dependency, used the same way by {@link DetailPageExtractor} for an
 * RSS entry's detail page), strips the boilerplate elements before anything else runs, then
 * addresses the main content via a CSS selector - mirroring {@code
 * IndexingProperties.Rss#DEFAULT_MAIN_CONTENT_SELECTOR}'s own choice of {@code main, article,
 * [role=main]}, which the project already found covers the vast majority of German public
 * administration CMS templates.
 *
 * <p><b>The cut follows h1-h3</b>: a new chunk starts at every heading of level 1 to 3, carrying
 * the heading path in effect (deepest three levels - h1-h3 never nests deeper) as its {@link
 * ChunkingService#LOCATION_METADATA_KEY}, the same generic Fundort channel {@code
 * ChunkLocationResolver} already populates for page/heading structure recovered from flat text -
 * this pipeline derives it directly from the DOM instead, because it still has the structure the
 * reader has not yet thrown away. h4-h6 stay inside their enclosing section's text rather than
 * cutting a further chunk; they are not part of the tracked path either.
 *
 * <p>Deliberately not the Spring AI {@code JsoupDocumentReader} the feature spec mentions by name:
 * no such module is on this project's classpath (only {@code org.jsoup:jsoup} itself, pulled in
 * transitively via Tika's HTML parser module and declared directly for {@link
 * DetailPageExtractor}), and the boilerplate-removal-plus-heading-split this pipeline needs is a
 * few hundred lines of Jsoup traversal, not a reason to add a dependency - the same reasoning
 * {@link TabularDocumentPipeline} already applies to Apache POI instead of a Spring AI spreadsheet
 * reader.
 */
public class HtmlDocumentPipeline implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(HtmlDocumentPipeline.class);

  static final String ID = "html";
  static final short VERSION = 1;

  /**
   * Elements stripped before the main content selector runs, so boilerplate never survives even
   * when it sits inside the matched main element - the same set {@link DetailPageExtractor} uses
   * for an RSS detail page, extended with cookie-banner markers (a page's own boilerplate, not a
   * detail page's, routinely carries one and Tika would otherwise index the consent text on every
   * page of a site).
   */
  private static final String BOILERPLATE_SELECTOR =
      "nav, header, footer, aside, [role=navigation], [role=banner], [role=contentinfo],"
          + " [role=complementary], .nav, .navigation, .menu, .breadcrumb, .sidebar,"
          + " .cookie-banner, .cookie-consent, #cookie-banner, #cookie-consent, script, style,"
          + " noscript";

  /** Mirrors {@code IndexingProperties.Rss#DEFAULT_MAIN_CONTENT_SELECTOR}'s own choice. */
  private static final String MAIN_CONTENT_SELECTOR = "main, article, [role=main]";

  private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3");

  /** Tags that start a visual paragraph break in the accumulated chunk text. */
  private static final Set<String> BLOCK_TAGS =
      Set.of(
          "p",
          "li",
          "div",
          "tr",
          "blockquote",
          "pre",
          "dt",
          "dd",
          "figcaption",
          "table",
          "h4",
          "h5",
          "h6",
          "br",
          "section",
          "article");

  /**
   * Absolute ceiling on a single chunk's rendered text length - <b>gesetzt, nicht gemessen</b>
   * (ingestion-pipelines.md, "Chunk-Größen"): the evaluation corpus contains no HTML documents at
   * all (see this pipeline's own PR description), so there is nothing to measure a value against
   * yet. Mirrors {@link TabularDocumentPipeline#HARD_CHUNK_CHAR_LIMIT} both in value and in intent
   * - guards a section between two headings that is itself pathologically large (a single page with
   * no further heading structure, or a table dumped between two h2s) from being handed to the
   * embedding model unbounded, where it would fail the call outright at its token limit instead of
   * degrading gracefully here.
   */
  static final int HARD_CHUNK_CHAR_LIMIT = 20_000;

  private static final String TRUNCATION_MARKER = " […gekürzt]";

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
    htmlDoc.select(BOILERPLATE_SELECTOR).remove();
    Element main = htmlDoc.selectFirst(MAIN_CONTENT_SELECTOR);
    Element content = main != null ? main : htmlDoc.body();
    if (content == null) {
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks = buildChunks(content);
    if (chunks.isEmpty()) {
      // A page whose entire body is boilerplate (nav/footer/cookie banner, no main/article and no
      // other body content) - the same "parsed, but nothing usable" outcome every other pipeline
      // reports for content that reduces to nothing (see TabularDocumentPipeline#run).
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
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

  /**
   * Walks {@code content} in document order via {@link Node#traverse}, so a nested section (e.g.
   * {@code <section><h2>…</h2><section><h3>…</h3>…</section></section>}) is handled correctly
   * regardless of how deep the heading actually sits in the DOM - a flat sibling-based scan would
   * miss that case. A chunk is flushed every time an h1-h3 element opens; a document's own text
   * appearing before the first heading (an intro paragraph) becomes its own chunk with no heading
   * path, exactly like a Markdown document with a lead paragraph before its first heading.
   */
  private static List<Document> buildChunks(Element content) {
    List<Document> chunks = new ArrayList<>();
    NavigableMap<Integer, String> headingPath = new TreeMap<>();
    StringBuilder buffer = new StringBuilder();

    content.traverse(
        new NodeVisitor() {
          @Override
          public void head(Node node, int depth) {
            if (node instanceof Element el) {
              String tag = el.tagName().toLowerCase(Locale.ROOT);
              if (HEADING_TAGS.contains(tag)) {
                flush(chunks, buffer, headingPath);
                int level = Integer.parseInt(tag.substring(1));
                // A heading of level n closes every open heading of level >= n, exactly as an
                // outline reads (mirrors ChunkLocationResolver#headingPath's own stack rule).
                headingPath.tailMap(level, true).clear();
                String title = el.text().strip();
                if (!title.isEmpty()) {
                  headingPath.put(level, title);
                }
                return;
              }
              if (BLOCK_TAGS.contains(tag) && !endsWithNewline(buffer)) {
                buffer.append('\n');
              }
              return;
            }
            if (node instanceof TextNode textNode && !isInsideHeading(node)) {
              appendText(buffer, textNode.text());
            }
          }

          @Override
          public void tail(Node node, int depth) {}
        });
    flush(chunks, buffer, headingPath);
    return chunks;
  }

  private static void appendText(StringBuilder buffer, String text) {
    if (text.isBlank()) {
      return;
    }
    if (buffer.length() > 0 && !endsWithSpaceOrNewline(buffer)) {
      buffer.append(' ');
    }
    buffer.append(text.strip());
  }

  private static boolean endsWithNewline(StringBuilder buffer) {
    return buffer.length() == 0 || buffer.charAt(buffer.length() - 1) == '\n';
  }

  private static boolean endsWithSpaceOrNewline(StringBuilder buffer) {
    char last = buffer.charAt(buffer.length() - 1);
    return last == ' ' || last == '\n';
  }

  /**
   * Whether {@code node} sits inside an h1-h3 element - its text is already captured by that
   * heading's own {@code el.text()} call in {@link #buildChunks} and must not be counted twice.
   */
  private static boolean isInsideHeading(Node node) {
    for (Node parent = node.parent(); parent != null; parent = parent.parent()) {
      if (parent instanceof Element el
          && HEADING_TAGS.contains(el.tagName().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static void flush(
      List<Document> chunks, StringBuilder buffer, NavigableMap<Integer, String> headingPath) {
    String text = normalizeWhitespace(buffer.toString());
    buffer.setLength(0);
    if (text.isBlank()) {
      return;
    }
    Map<String, Object> metadata = new HashMap<>();
    if (!headingPath.isEmpty()) {
      metadata.put(
          ChunkingService.LOCATION_METADATA_KEY,
          "Abschn. " + String.join(" › ", headingPath.values()));
    }
    chunks.add(new Document(capChunkLength(text), metadata));
  }

  private static String normalizeWhitespace(String raw) {
    return raw.replaceAll("[ \\t]+", " ")
        .replaceAll(" ?\\n ?", "\n")
        .replaceAll("\\n{3,}", "\n\n")
        .strip();
  }

  /** See {@link #HARD_CHUNK_CHAR_LIMIT}'s own Javadoc for why this exists. */
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
