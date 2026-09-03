package io.opaa.indexing.source.confluence;

import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * The first usable text of a Confluence storage-format body (#1136): the XHTML with its {@code
 * ac:}/{@code ri:} namespaces reduced to plain text, block elements separated by line breaks, table
 * cells by " | ", macro <em>parameters</em> (never visible on the page) dropped while macro
 * <em>bodies</em> (the rich text a panel, expand or note macro wraps) are kept. Macro-aware rules
 * and layout fidelity are #1137's; this only has to be good enough for the first bestand.
 */
final class ConfluenceStorageText {

  private static final Set<String> BLOCK_TAGS =
      Set.of(
          "p",
          "div",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "li",
          "tr",
          "table",
          "blockquote",
          "pre",
          "br",
          "hr",
          "ul",
          "ol",
          "ac:layout-cell",
          "ac:layout-section",
          "ac:rich-text-body");
  private static final Set<String> DROPPED_TAGS =
      Set.of("ac:parameter", "ri:url", "ri:page", "ri:attachment");

  private ConfluenceStorageText() {}

  static String toPlainText(String storageBody) {
    if (storageBody == null || storageBody.isBlank()) {
      return "";
    }
    // The XML parser keeps the namespaced tag names intact (the HTML parser would mangle them).
    Document document = Jsoup.parse(storageBody, "", Parser.xmlParser());
    for (String tag : DROPPED_TAGS) {
      document.getElementsByTag(tag).remove();
    }
    StringBuilder text = new StringBuilder();
    NodeTraversor.traverse(
        new NodeVisitor() {
          @Override
          public void head(Node node, int depth) {
            if (node instanceof TextNode textNode) {
              text.append(textNode.text());
            } else if (node instanceof Element element && isBlock(element)) {
              newline(text);
            }
          }

          @Override
          public void tail(Node node, int depth) {
            if (!(node instanceof Element element)) {
              return;
            }
            String tag = element.tagName();
            if (tag.equals("td") || tag.equals("th")) {
              text.append(" | ");
            } else if (isBlock(element)) {
              newline(text);
            }
          }
        },
        document);
    StringBuilder joined = new StringBuilder();
    for (String line : text.toString().split("\n")) {
      String stripped = line.strip().replaceAll("[ \\t]{2,}", " ");
      if (!stripped.isEmpty()) {
        joined.append(stripped).append('\n');
      }
    }
    return joined.toString().strip();
  }

  private static boolean isBlock(Element element) {
    return BLOCK_TAGS.contains(element.tagName());
  }

  private static void newline(StringBuilder text) {
    if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
      text.append('\n');
    }
  }
}
