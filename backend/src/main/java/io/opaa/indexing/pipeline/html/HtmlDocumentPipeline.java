package io.opaa.indexing.pipeline.html;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.source.web.DetailPageExtractor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.ai.document.Document;

/**
 * The HTML pipeline (docs/features/ingestion-pipelines.md, Teil 3, Punkt 4): strips boilerplate
 * (navigation, footer, cookie notice, sidebar) that Tika's reader otherwise leaves in the chunk as
 * if it were content, then cuts along h1-h3 using the same section-emission logic as the other
 * heading-driven pipelines ({@link HeadingSectionSplitter}). Reads the page with Jsoup directly,
 * not Spring AI's HTML reader (absent from this project's classpath).
 *
 * <p>Each chunk's heading path travels both as its Fundort metadata and as a leading line of the
 * chunk's own text, repeated on every further-split sub-chunk of an oversized section. h4-h6 stay
 * inside their enclosing section's text rather than cutting a further chunk. A genuinely empty
 * section still becomes a one-line chunk rather than being silently dropped; an ordinary title
 * heading immediately followed by its first subsection heading is not treated as empty, since its
 * title already opens the descendant section's own heading path.
 *
 * <p>Feed detail pages are a named exception: an RSS entry's already-extracted main text goes
 * straight to the Tika fallback pipeline (ADR-0017, decision 2) and never reaches this class. Only
 * genuine {@code .html} files are routed here.
 */
public class HtmlDocumentPipeline implements DocumentPipeline {

  static final String ID = "html";
  static final short VERSION = 1;

  /**
   * Boilerplate that is only ever boilerplate, never legitimate content - removed everywhere,
   * including inside the chosen content root: navigation, sidebar, cookie consent and
   * script/style/noscript sit in a content wrapper often enough (#1059 review, follow-up finding 2)
   * that they cannot be treated the same way as {@link #CONDITIONAL_BOILERPLATE_SELECTOR}.
   */
  private static final String UNCONDITIONAL_BOILERPLATE_SELECTOR =
      "nav, aside, [role=navigation], [role=complementary], .nav, .navigation, .menu,"
          + " .breadcrumb, .sidebar, .cookie-banner, .cookie-consent, #cookie-banner,"
          + " #cookie-consent, script, style, noscript";

  /**
   * Boilerplate stripped only when it sits <em>outside</em> every chosen content root (see {@link
   * #selectContentRoots}) - a standard CMS article/section legitimately nests its own {@code
   * <header>} (title, Stand-Datum) or {@code <footer>} (author, tags), and stripping those away
   * would silently drop real content along with the surrounding page chrome (#1059 review, finding
   * 4). Mirrors the set {@link DetailPageExtractor} uses for an RSS detail page, minus the elements
   * moved to {@link #UNCONDITIONAL_BOILERPLATE_SELECTOR} above.
   */
  private static final String CONDITIONAL_BOILERPLATE_SELECTOR =
      "header, footer, [role=banner], [role=contentinfo]";

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
   * delegates to {@link HeadingSectionSplitter#SOFT_CHUNK_CHAR_LIMIT}, the shared value {@link
   * #flushSection} (via {@link HeadingSectionSplitter#flushSection}) actually applies; kept as its
   * own named constant here because {@code HtmlDocumentPipelineTest} references it by this class's
   * name. <b>Gesetzt, nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen") - the evaluation
   * corpus contains no HTML documents at all, so there is nothing to measure a value against yet.
   */
  static final int SOFT_CHUNK_CHAR_LIMIT = HeadingSectionSplitter.SOFT_CHUNK_CHAR_LIMIT;

  /**
   * Absolute ceiling on a single chunk's rendered text length - delegates to {@link
   * HeadingSectionSplitter#HARD_CHUNK_CHAR_LIMIT} for the same reason {@link
   * #SOFT_CHUNK_CHAR_LIMIT} does.
   */
  static final int HARD_CHUNK_CHAR_LIMIT = HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT;

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
   * has to happen relative to them.
   *
   * <p>{@link #UNCONDITIONAL_BOILERPLATE_SELECTOR} is removed first, document-wide, regardless of
   * where the content area ends up - it is never legitimate content anywhere.
   *
   * <p>Every {@link #MAIN_CONTENT_SELECTOR} match is processed, not just the first (#1059 review,
   * finding 5) - an overview page routinely lists several {@code <article>} teasers, and taking
   * only the first would silently drop every other one. A match nested inside another match (e.g.
   * {@code <main><article>…</article></main>}, both matching the selector) is dropped in favour of
   * its outer match rather than kept as a second, overlapping root - otherwise the same content
   * would be cut and stored twice (#1059 review, follow-up finding 1).
   */
  private static List<Element> selectContentRoots(org.jsoup.nodes.Document htmlDoc) {
    htmlDoc.select(UNCONDITIONAL_BOILERPLATE_SELECTOR).remove();
    Elements mainCandidates = htmlDoc.select(MAIN_CONTENT_SELECTOR);
    if (!mainCandidates.isEmpty()) {
      List<Element> roots = topLevelOnly(mainCandidates);
      removeConditionalBoilerplateOutside(htmlDoc, roots);
      return roots;
    }
    Element body = htmlDoc.body();
    if (body == null) {
      return List.of();
    }
    // No dedicated content area was found at all - body itself is "the content", and the
    // ordinary, unconditional strip applies (there is no narrower area left to preserve nested
    // chrome for).
    body.select(CONDITIONAL_BOILERPLATE_SELECTOR).remove();
    return List.of(body);
  }

  /**
   * {@code candidates} with every match dropped that is itself a descendant of another match - the
   * fix for {@code <main><article>…</article></main>} both matching {@link #MAIN_CONTENT_SELECTOR}
   * (#1059 review, follow-up finding 1).
   */
  private static List<Element> topLevelOnly(Elements candidates) {
    List<Element> roots = new ArrayList<>();
    for (Element candidate : candidates) {
      if (!isDescendantOfAnother(candidate, candidates)) {
        roots.add(candidate);
      }
    }
    return roots;
  }

  private static boolean isDescendantOfAnother(Element candidate, Elements candidates) {
    for (Node node = candidate.parent(); node != null; node = node.parent()) {
      if (candidates.contains(node)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Removes every {@link #CONDITIONAL_BOILERPLATE_SELECTOR} match that is not itself part of {@code
   * roots} - {@link #UNCONDITIONAL_BOILERPLATE_SELECTOR} matches are already gone by the time this
   * runs (see {@link #selectContentRoots}).
   */
  private static void removeConditionalBoilerplateOutside(
      org.jsoup.nodes.Document htmlDoc, List<Element> roots) {
    for (Element candidate : htmlDoc.select(CONDITIONAL_BOILERPLATE_SELECTOR)) {
      if (!isWithinAnyOf(candidate, roots)) {
        candidate.remove();
      }
    }
  }

  private static boolean isWithinAnyOf(Element candidate, List<Element> roots) {
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
                int level = Integer.parseInt(tag.substring(1));
                flushSection(chunks, section.takeBlocks(), headingPath, level);
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
    flushSection(chunks, section.takeBlocks(), headingPath, null);
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

  /** Delegates to {@link HeadingSectionSplitter#flushSection}, shared with the other pipelines. */
  private static void flushSection(
      List<Document> chunks,
      List<String> blocks,
      NavigableMap<Integer, String> headingPath,
      Integer closingHeadingLevel) {
    HeadingSectionSplitter.flushSection(chunks, blocks, headingPath, closingHeadingLevel);
  }
}
