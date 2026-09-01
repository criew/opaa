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
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The HTML pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 4): the core problem is
 * boilerplate - navigation, footer, cookie notice and sidebar make up a large share of a typical
 * page and, read through Tika, end up in the chunk as if they were content. This reads the page
 * with Jsoup (already a project dependency, used the same way by {@link DetailPageExtractor} for an
 * RSS entry's detail page), strips boilerplate outside the chosen content area, addresses the
 * content itself via a CSS selector - mirroring {@code
 * IndexingProperties.Rss#DEFAULT_MAIN_CONTENT_SELECTOR}'s own choice of {@code main, article,
 * [role=main]}, which the project already found covers the vast majority of German public
 * administration CMS templates - and cuts along h1-h3.
 *
 * <p><b>Feed detail pages are a named exception, not covered by this pipeline.</b> {@code
 * FileProcessingService#processRssEntry} hands an RSS entry's already-extracted main text straight
 * to the Tika fallback pipeline (ADR-0017, decision 2) - it never was a file and never goes through
 * {@link DocumentPipelineRegistry}'s content-based routing, so it cannot reach this class. Only
 * genuine {@code .html} files - a directory crawl, the filesystem, or an RSS entry's own attachment
 * - are routed here.
 *
 * <p><b>The cut follows h1-h3</b>: a new chunk starts at every heading of level 1 to 3. Each
 * chunk's heading path travels twice: as its {@link ChunkingService#LOCATION_METADATA_KEY} (the
 * same generic Fundort channel {@code ChunkLocationResolver} already populates for page/heading
 * structure recovered from flat text), and - because a metadata field alone is invisible to
 * embedding and to the lexical path (#1097) - as a leading line of the chunk's own text, repeated
 * on every chunk of a section that itself had to be split further (mirrors {@link
 * TabularDocumentPipeline}'s repeated column header, for the identical reason). h4-h6 stay inside
 * their enclosing section's text rather than cutting a further chunk; they are not part of the
 * tracked path either. A section that is nothing but its own heading (no body text at all) still
 * becomes a one-line chunk rather than being silently dropped as {@code NO_EXTRACTABLE_TEXT}.
 *
 * <p><b>An oversized section is split further at block boundaries</b>
 * (paragraph/list-item/table-row breaks), each sub-chunk repeating the heading line, so a page with
 * no further heading structure (a "Div-Suppe", or §-style headings expressed as {@code <p><strong>}
 * rather than real headings) still lands in chunks the same order of magnitude as the rest of the
 * bestand, not one that grows with the page. {@link #HARD_CHUNK_CHAR_LIMIT} is the last-resort
 * backstop for the one case block splitting cannot help - a single block that is itself
 * pathologically large.
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
   * Candidate boilerplate elements - stripped only when they sit <em>outside</em> every chosen
   * content root (see {@link #selectContentRoots}), never unconditionally: a standard CMS
   * article/section legitimately nests its own {@code <header>} (title, Stand-Datum) or {@code
   * <footer>} (author, tags), and stripping those away would silently drop real content along with
   * the surrounding page chrome (#1059 review, finding 4). Extends the set {@link
   * DetailPageExtractor} uses for an RSS detail page with cookie-banner markers - a page's own
   * boilerplate, not a detail page's, routinely carries one.
   */
  private static final String BOILERPLATE_SELECTOR =
      "nav, header, footer, aside, [role=navigation], [role=banner], [role=contentinfo],"
          + " [role=complementary], .nav, .navigation, .menu, .breadcrumb, .sidebar,"
          + " .cookie-banner, .cookie-consent, #cookie-banner, #cookie-consent, script, style,"
          + " noscript";

  /** Mirrors {@code IndexingProperties.Rss#DEFAULT_MAIN_CONTENT_SELECTOR}'s own choice. */
  private static final String MAIN_CONTENT_SELECTOR = "main, article, [role=main]";

  private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3");

  /** Tags that start a new block (a paragraph-break/split point) in the accumulated chunk text. */
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
   * Soft budget a section's rendered body text is grouped into before another chunk starts -
   * roughly the same order of magnitude as {@code opaa.indexing.chunk-size}'s own default of 1000
   * tokens (~4 characters/token), so an HTML chunk without further heading structure lands in the
   * same bestand size class as everything else instead of scaling with the page (#1059 review,
   * finding 3). <b>Gesetzt, nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen") - the
   * evaluation corpus contains no HTML documents at all, so there is nothing to measure a value
   * against yet. A single block that alone already exceeds this still becomes its own chunk rather
   * than being cut mid-block - see {@link #HARD_CHUNK_CHAR_LIMIT} for the absolute ceiling that
   * chunk is still subject to.
   */
  static final int SOFT_CHUNK_CHAR_LIMIT = 4_000;

  /**
   * Absolute ceiling on a single chunk's rendered text length, applied after {@link
   * #SOFT_CHUNK_CHAR_LIMIT} has already done its normal job - the last-resort backstop for a single
   * block that is itself pathologically large (one giant paragraph with no further internal
   * structure), not the ordinary size-control mechanism. Mirrors {@link
   * TabularDocumentPipeline#HARD_CHUNK_CHAR_LIMIT} both in value and in intent.
   */
  static final int HARD_CHUNK_CHAR_LIMIT = 20_000;

  private static final String TRUNCATION_MARKER = " […gekürzt]";

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
    List<Element> contentRoots = selectContentRoots(htmlDoc);
    if (contentRoots.isEmpty()) {
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks = new ArrayList<>();
    for (Element root : contentRoots) {
      chunks.addAll(buildChunks(root));
    }
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
   * The content root(s) this document is cut from, and the boilerplate-stripping side effect that
   * has to happen relative to them (see {@link #BOILERPLATE_SELECTOR}'s own Javadoc for why the
   * distinction matters).
   *
   * <p>Every {@link #MAIN_CONTENT_SELECTOR} match is processed, not just the first (#1059 review,
   * finding 5) - an overview page routinely lists several {@code <article>} teasers, and taking
   * only the first would silently drop every other one.
   */
  private static List<Element> selectContentRoots(org.jsoup.nodes.Document htmlDoc) {
    Elements mainCandidates = htmlDoc.select(MAIN_CONTENT_SELECTOR);
    if (!mainCandidates.isEmpty()) {
      removeBoilerplateOutside(htmlDoc, mainCandidates);
      return List.copyOf(mainCandidates);
    }
    Element body = htmlDoc.body();
    if (body == null) {
      return List.of();
    }
    // No dedicated content area was found at all - body itself is "the content", and the ordinary,
    // unconditional strip applies (there is no narrower area left to preserve nested chrome for).
    body.select(BOILERPLATE_SELECTOR).remove();
    return List.of(body);
  }

  /** Removes every {@link #BOILERPLATE_SELECTOR} match that is not itself part of {@code roots}. */
  private static void removeBoilerplateOutside(org.jsoup.nodes.Document htmlDoc, Elements roots) {
    for (Element candidate : htmlDoc.select(BOILERPLATE_SELECTOR)) {
      if (!isWithinAnyOf(candidate, roots)) {
        candidate.remove();
      }
    }
  }

  private static boolean isWithinAnyOf(Element candidate, Elements roots) {
    for (Node node = candidate; node != null; node = node.parent()) {
      if (roots.contains(node)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Walks {@code root} in document order via {@link Node#traverse}, so a nested section (e.g.
   * {@code <section><h2>…</h2><section><h3>…</h3>…</section></section>}) is handled correctly
   * regardless of how deep the heading actually sits in the DOM - a flat sibling-based scan would
   * miss that case. A chunk is flushed every time an h1-h3 element opens; a document's own text
   * appearing before the first heading (an intro paragraph) becomes its own chunk with no heading
   * path, exactly like a Markdown document with a lead paragraph before its first heading.
   */
  private static List<Document> buildChunks(Element root) {
    List<Document> chunks = new ArrayList<>();
    NavigableMap<Integer, String> headingPath = new TreeMap<>();
    SectionAccumulator section = new SectionAccumulator();

    root.traverse(
        new NodeVisitor() {
          @Override
          public void head(Node node, int depth) {
            if (node instanceof Element el) {
              String tag = el.tagName().toLowerCase(Locale.ROOT);
              if (HEADING_TAGS.contains(tag)) {
                flushSection(chunks, section.takeBlocks(), headingPath);
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
              if (BLOCK_TAGS.contains(tag)) {
                section.breakBlock();
              }
              return;
            }
            if (node instanceof TextNode textNode && !isInsideHeading(node)) {
              section.appendText(textNode.getWholeText());
            }
          }

          @Override
          public void tail(Node node, int depth) {}
        });
    flushSection(chunks, section.takeBlocks(), headingPath);
    return chunks;
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

  /**
   * Accumulates one section's body text as a list of already-whitespace-normalized blocks (one per
   * {@link #BLOCK_TAGS} boundary), each built by {@link InlineTextAccumulator} so inline markup
   * inside a block (e.g. {@code <b>Personal</b>ausweis}) re-joins without an artificial space
   * (#1059 review, finding 7).
   */
  private static final class SectionAccumulator {
    private final List<String> blocks = new ArrayList<>();
    private InlineTextAccumulator current = new InlineTextAccumulator();

    void appendText(String rawWholeText) {
      current.append(rawWholeText);
    }

    void breakBlock() {
      String text = current.finish();
      if (!text.isBlank()) {
        blocks.add(text);
      }
      current = new InlineTextAccumulator();
    }

    /** Closes the current block and returns every block collected since the last call. */
    List<String> takeBlocks() {
      breakBlock();
      List<String> result = List.copyOf(blocks);
      blocks.clear();
      return result;
    }
  }

  /**
   * Joins a sequence of raw, un-normalized text-node fragments the way a reader would expect them
   * spoken - collapsing internal whitespace within each fragment, and inserting a single separating
   * space between two fragments only when the source actually had whitespace at that boundary
   * (leading/trailing whitespace on either fragment, or a whitespace-only fragment between them).
   * Two fragments with no whitespace anywhere at their boundary re-join directly instead: {@code
   * <b>Personal</b>ausweis} must read "Personalausweis", not "Personal ausweis" - the bug a blanket
   * "always insert a separator" rule produced (#1059 review, finding 7). Mirrors what {@link
   * Element#text()} already does correctly for a single element (confirmed empirically); this
   * pipeline needs its own equivalent because it accumulates text across an explicit block
   * boundary, not a whole subtree in one call.
   */
  private static final class InlineTextAccumulator {
    private final StringBuilder text = new StringBuilder();
    private boolean spacePending;

    void append(String rawWholeText) {
      if (rawWholeText.isEmpty()) {
        return;
      }
      if (rawWholeText.isBlank()) {
        if (text.length() > 0) {
          spacePending = true;
        }
        return;
      }
      boolean leadingWhitespace = Character.isWhitespace(rawWholeText.charAt(0));
      boolean trailingWhitespace =
          Character.isWhitespace(rawWholeText.charAt(rawWholeText.length() - 1));
      String collapsed = WHITESPACE.matcher(rawWholeText).replaceAll(" ").strip();
      if (collapsed.isEmpty()) {
        if (text.length() > 0) {
          spacePending = true;
        }
        return;
      }
      if (text.length() > 0 && (spacePending || leadingWhitespace)) {
        text.append(' ');
      }
      text.append(collapsed);
      spacePending = trailingWhitespace;
    }

    String finish() {
      return text.toString();
    }
  }

  /**
   * Turns one section's collected blocks into one or more chunks, each carrying the section's
   * heading path both as {@link ChunkingService#LOCATION_METADATA_KEY} and as the chunk text's own
   * leading line (#1059 review, finding 6) - repeated on every sub-chunk if the section had to be
   * split further by {@link #splitIntoBudgetedChunks}, the same repetition {@link
   * TabularDocumentPipeline} already applies to its column header.
   */
  private static void flushSection(
      List<Document> chunks, List<String> blocks, NavigableMap<Integer, String> headingPath) {
    if (blocks.isEmpty() && headingPath.isEmpty()) {
      // Nothing before the first heading, and no heading opened yet - a true empty prefix, not a
      // section in its own right.
      return;
    }
    String headingLine = headingPath.isEmpty() ? null : String.join(" › ", headingPath.values());
    String location = headingLine == null ? null : "Abschn. " + headingLine;
    List<String> bodies = splitIntoBudgetedChunks(blocks);
    if (bodies.isEmpty()) {
      // A section that is nothing but its own heading (no body text at all) still becomes a
      // one-line chunk - otherwise a heading-only page would look like NO_EXTRACTABLE_TEXT even
      // though its heading is real, searchable content (#1059 review, finding 6).
      bodies = List.of("");
    }
    for (String body : bodies) {
      String text =
          headingLine == null ? body : body.isEmpty() ? headingLine : headingLine + "\n\n" + body;
      Map<String, Object> metadata = new HashMap<>();
      if (location != null) {
        metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
      }
      chunks.add(new Document(capChunkLength(text), metadata));
    }
  }

  /**
   * Groups {@code blocks} into chunks of at most {@link #SOFT_CHUNK_CHAR_LIMIT} characters each,
   * closing a chunk early rather than letting the next block push it over the budget - the fix for
   * a section with no further heading structure (a "Div-Suppe", or §-style headings expressed as
   * {@code <p><strong>} rather than real headings) that would otherwise become a single,
   * ever-growing chunk (#1059 review, finding 3). A single block that alone already exceeds the
   * budget still becomes its own chunk rather than being cut mid-block - {@link #flushSection}'s
   * own {@link #capChunkLength} call is the backstop that chunk is still subject to.
   */
  private static List<String> splitIntoBudgetedChunks(List<String> blocks) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String block : blocks) {
      boolean wouldExceed =
          current.length() > 0 && current.length() + 2 + block.length() > SOFT_CHUNK_CHAR_LIMIT;
      if (wouldExceed) {
        result.add(current.toString());
        current.setLength(0);
      }
      if (current.length() > 0) {
        current.append("\n\n");
      }
      current.append(block);
    }
    if (current.length() > 0) {
      result.add(current.toString());
    }
    return result;
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
