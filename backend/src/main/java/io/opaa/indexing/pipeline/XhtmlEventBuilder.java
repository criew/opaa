package io.opaa.indexing.pipeline;

import io.opaa.indexing.pipeline.HeadingSectionSplitter.Event;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Heading;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Paragraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Walks an XHTML DOM (a Jsoup tree, HTML- or XML-parsed) in document order and emits the
 * heading/paragraph events {@link HeadingSectionSplitter} cuts on: h1-h6 become {@link Heading}s,
 * block elements bound paragraphs, a table becomes one line per row ({@link TableText}), a list one
 * line per item with a nesting marker, {@code pre} keeps its line breaks, and inline text is
 * whitespace-normalized ({@link Whitespace}) - {@code <b>Personal</b>ausweis} reads
 * "Personalausweis". A format's own elements (Confluence macros) are handled by its {@link
 * ElementRule}, consulted before the built-in walk. One instance per document; not thread-safe.
 */
public final class XhtmlEventBuilder {

  /**
   * A format's hook into the walk, called for every element before the built-in handling: returns
   * {@code true} once it has taken care of the element and its subtree itself (possibly through the
   * builder's own methods), {@code false} to let the built-in walk handle it.
   */
  @FunctionalInterface
  public interface ElementRule {
    boolean handle(Element element, XhtmlEventBuilder builder);
  }

  /** Lets every element through to the built-in walk. */
  public static final ElementRule NO_RULE = (element, builder) -> false;

  /** Elements that open and close a paragraph of their own. */
  private static final Set<String> BLOCK_TAGS =
      Set.of(
          "p",
          "div",
          "section",
          "article",
          "main",
          "header",
          "footer",
          "aside",
          "nav",
          "blockquote",
          "hr",
          "br",
          "dl",
          "dt",
          "dd",
          "figure",
          "figcaption",
          "li",
          "tr",
          "td",
          "th",
          "caption",
          "details",
          "summary",
          "address",
          "form",
          "fieldset");

  /** Elements whose subtree never carries visible text. */
  private static final Set<String> INVISIBLE_TAGS =
      Set.of("script", "style", "noscript", "template", "head");

  private final ElementRule rule;
  private final List<Event> events = new ArrayList<>();
  private final StringBuilder inline = new StringBuilder();

  /**
   * The marker of the list item being walked, prefixed to the first paragraph that item yields -
   * whether its text is inline or wrapped in a block child - and cleared with it; {@code null}
   * outside an item or once the item has its marked line.
   */
  private String pendingMarker;

  /**
   * Nesting of the list item being walked, so a list reached through any wrapper inside an item
   * ({@code <li><div><ul>}) continues at the next depth and, for ordered lists, under the item's
   * number; -1 / "" outside any item.
   */
  private int itemDepth = -1;

  private String itemNumbering = "";

  public XhtmlEventBuilder() {
    this(NO_RULE);
  }

  public XhtmlEventBuilder(ElementRule rule) {
    this.rule = rule;
  }

  /**
   * The events of {@code root}'s content in document order; {@code root} itself is only a
   * container, never a heading or block of its own.
   */
  public List<Event> build(Element root) {
    walkChildren(root);
    flushBlock();
    return List.copyOf(events);
  }

  /** Walks {@code element}'s children with the built-in rules, {@code element} itself untouched. */
  public void walkChildren(Element element) {
    for (Node child : element.childNodes()) {
      walk(child);
    }
  }

  /** Treats {@code element} as a block: the text before it ends a paragraph, its own does too. */
  public void block(Element element) {
    flushBlock();
    walkChildren(element);
    flushBlock();
  }

  /**
   * Ends the paragraph collected so far, if it holds any text; inside a list item, the item's
   * marker leads the first such paragraph.
   */
  public void flushBlock() {
    String text = normalize(inline.toString());
    inline.setLength(0);
    if (!text.isEmpty()) {
      if (pendingMarker != null) {
        text = pendingMarker + text;
        pendingMarker = null;
      }
      events.add(new Paragraph(text));
    }
  }

  /**
   * Ends the current paragraph and emits {@code line} as a paragraph of its own; inside a list item
   * whose marker is still pending (a macro title as the item's first content), the line carries it.
   */
  public void emitLine(String line) {
    flushBlock();
    if (!line.isBlank()) {
      String text = line.stripTrailing();
      if (pendingMarker != null) {
        text = pendingMarker + text;
        pendingMarker = null;
      }
      events.add(new Paragraph(text));
    }
  }

  /** Ends the current paragraph and emits {@code text} with its line breaks kept. */
  public void verbatim(String text) {
    flushBlock();
    String stripped = text.strip();
    if (!stripped.isEmpty()) {
      events.add(new Paragraph(stripped));
    }
  }

  /** Appends {@code text} to the paragraph being collected, as if it were a text node. */
  public void appendInline(String text) {
    inline.append(text);
  }

  /**
   * {@code element} rendered on its own as one line: its blocks, lists and format-specific elements
   * flatten to a space-separated line - a table cell, a heading, a task body.
   */
  public String inlineText(Element element) {
    XhtmlEventBuilder nested = new XhtmlEventBuilder(rule);
    nested.walkChildren(element);
    nested.flushBlock();
    List<String> parts = new ArrayList<>();
    for (Event event : nested.events) {
      String text = event instanceof Paragraph p ? p.text() : ((Heading) event).title();
      if (!text.isBlank()) {
        parts.add(normalize(text));
      }
    }
    return String.join(" ", parts);
  }

  private void walk(Node node) {
    if (node instanceof TextNode text) {
      inline.append(text.getWholeText());
      return;
    }
    if (!(node instanceof Element element)) {
      return;
    }
    if (rule.handle(element, this)) {
      return;
    }
    String tag = element.tagName().toLowerCase(Locale.ROOT);
    if (INVISIBLE_TAGS.contains(tag)) {
      return;
    }
    switch (tag) {
      case "h1", "h2", "h3", "h4", "h5", "h6" -> heading(element, tag.charAt(1) - '0');
      case "ul", "ol" -> list(element, tag.equals("ol"), itemDepth + 1, itemNumbering);
      case "table" -> table(element);
      case "pre" -> verbatim(element.wholeText());
      default -> {
        if (BLOCK_TAGS.contains(tag)) {
          block(element);
        } else {
          walkChildren(element);
        }
      }
    }
  }

  private void heading(Element element, int level) {
    flushBlock();
    String title = inlineText(element);
    if (!title.isEmpty()) {
      events.add(new Heading(level, title));
    }
  }

  /**
   * One line per item, led by its marker: the item's first paragraph carries it, whether the text
   * is inline or wrapped in a block child ({@code <li><p>Text</p></li>}); further blocks of the
   * same item and text after a nested list follow unmarked, a nested list (a direct child or inside
   * any wrapper, reached through {@link #walk}) follows with the next depth's marker.
   *
   * @param numbering the enclosing ordered list's number prefix ("2." for the second item's nested
   *     list), so nesting is carried by the marker ("2.1.") - HeadingSectionSplitter strips every
   *     block, leading spaces would not survive the cut
   */
  private void list(Element listElement, boolean ordered, int depth, String numbering) {
    flushBlock();
    int outerDepth = itemDepth;
    String outerNumbering = itemNumbering;
    int index = 0;
    for (Element item : listElement.children()) {
      if (!item.tagName().equalsIgnoreCase("li")) {
        continue;
      }
      index++;
      String number = numbering + index + ".";
      pendingMarker = ordered ? number + " " : bulletFor(depth);
      itemDepth = depth;
      itemNumbering = ordered ? number : "";
      walkChildren(item);
      flushBlock();
      pendingMarker = null;
    }
    itemDepth = outerDepth;
    itemNumbering = outerNumbering;
  }

  private static String bulletFor(int depth) {
    return switch (depth) {
      case 0 -> "• ";
      case 1 -> "◦ ";
      default -> "▪ ";
    };
  }

  private void table(Element table) {
    flushBlock();
    for (Element row : table.select("tr")) {
      if (row.closest("table") != table) {
        // a nested table's rows are already flattened into their outer cell
        continue;
      }
      List<String> cells = new ArrayList<>();
      for (Element cell : row.children()) {
        String cellTag = cell.tagName().toLowerCase(Locale.ROOT);
        if (!cellTag.equals("td") && !cellTag.equals("th")) {
          continue;
        }
        cells.add(inlineText(cell));
      }
      if (!cells.isEmpty()) {
        emitLine(TableText.row(cells));
      }
    }
  }

  private static String normalize(String text) {
    return Whitespace.normalize(text).strip();
  }
}
