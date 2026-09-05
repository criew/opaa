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

  /** Ends the paragraph collected so far, if it holds any text. */
  public void flushBlock() {
    String text = normalize(inline.toString());
    inline.setLength(0);
    if (!text.isEmpty()) {
      events.add(new Paragraph(text));
    }
  }

  /** Ends the current paragraph and emits {@code line} as a paragraph of its own. */
  public void emitLine(String line) {
    flushBlock();
    if (!line.isBlank()) {
      events.add(new Paragraph(line.stripTrailing()));
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
      case "ul", "ol" -> list(element, tag.equals("ol"), 0, "");
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
   * @param numbering the enclosing ordered list's number prefix ("2." for the second item's nested
   *     list), so nesting is carried by the marker ("2.1.") - HeadingSectionSplitter strips every
   *     block, leading spaces would not survive the cut
   */
  private void list(Element listElement, boolean ordered, int depth, String numbering) {
    flushBlock();
    int index = 0;
    for (Element item : listElement.children()) {
      if (!item.tagName().equalsIgnoreCase("li")) {
        continue;
      }
      index++;
      String number = numbering + index + ".";
      String marker = ordered ? number + " " : bulletFor(depth);
      StringBuilder line = new StringBuilder(marker);
      for (Node child : item.childNodes()) {
        if (child instanceof Element nested
            && (nested.tagName().equalsIgnoreCase("ul")
                || nested.tagName().equalsIgnoreCase("ol"))) {
          // the item's own text so far becomes its line, the nested list follows indented
          String ownText = normalize(inline.toString());
          inline.setLength(0);
          if (line.length() > 0) {
            emitLine(ownText.isEmpty() ? "" : line + ownText);
            line.setLength(0);
          } else if (!ownText.isEmpty()) {
            emitLine(ownText);
          }
          list(nested, nested.tagName().equalsIgnoreCase("ol"), depth + 1, ordered ? number : "");
          continue;
        }
        walk(child);
      }
      String itemText = normalize(inline.toString());
      inline.setLength(0);
      if (line.length() > 0) {
        emitLine(itemText.isEmpty() ? "" : line + itemText);
      } else if (!itemText.isEmpty()) {
        emitLine(itemText);
      }
    }
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
