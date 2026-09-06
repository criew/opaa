package io.opaa.indexing.pipeline.html;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

/**
 * The one boilerplate and main-content selection for an HTML page, shared by {@link
 * HtmlDocumentPipeline} and the feed connector's detail-page extraction: chrome that is never
 * content goes document-wide, header/footer only outside the chosen content roots, and every
 * top-level match of the main-content selector is a root - an overview page routinely lists several
 * teasers. {@link #select} mutates the document it is given.
 */
public final class HtmlContentRoots {

  /**
   * Boilerplate that is only ever boilerplate, never legitimate content - removed everywhere,
   * including inside the chosen content roots: navigation, sidebar, cookie consent and
   * script/style/noscript sit in a content wrapper often enough that they cannot be treated the
   * same way as {@link #CONDITIONAL_BOILERPLATE_SELECTOR}.
   */
  public static final String UNCONDITIONAL_BOILERPLATE_SELECTOR =
      "nav, aside, [role=navigation], [role=complementary], .nav, .navigation, .menu,"
          + " .breadcrumb, .sidebar, .cookie-banner, .cookie-consent, #cookie-banner,"
          + " #cookie-consent, script, style, noscript";

  /**
   * Boilerplate stripped only when it sits <em>outside</em> every chosen content root: a standard
   * CMS article legitimately nests its own {@code <header>} or {@code <footer>}, and stripping
   * those would drop real content along with the page chrome.
   */
  public static final String CONDITIONAL_BOILERPLATE_SELECTOR =
      "header, footer, [role=banner], [role=contentinfo]";

  /**
   * {@code main}/{@code article}/{@code [role=main]} cover the vast majority of German public
   * administration CMS templates without any per-site configuration.
   */
  public static final String DEFAULT_MAIN_CONTENT_SELECTOR = "main, article, [role=main]";

  private HtmlContentRoots() {}

  /** {@link #select(Document, String)} with {@link #DEFAULT_MAIN_CONTENT_SELECTOR}. */
  public static List<Element> select(Document htmlDoc) {
    return select(htmlDoc, DEFAULT_MAIN_CONTENT_SELECTOR);
  }

  /**
   * The content root(s) of {@code htmlDoc}, with the boilerplate stripping that has to happen
   * relative to them already applied: {@link #UNCONDITIONAL_BOILERPLATE_SELECTOR} goes
   * document-wide first. Every {@code mainContentSelector} match is a root except one nested in
   * another, which is dropped in favour of its outer one so no content is read twice. Without any
   * match the body is the root and header/footer go too - there is no narrower area left to
   * preserve nested chrome for.
   */
  public static List<Element> select(Document htmlDoc, String mainContentSelector) {
    htmlDoc.select(UNCONDITIONAL_BOILERPLATE_SELECTOR).remove();
    Elements mainCandidates = htmlDoc.select(mainContentSelector);
    if (!mainCandidates.isEmpty()) {
      List<Element> roots = topLevelOnly(mainCandidates);
      removeConditionalBoilerplateOutside(htmlDoc, roots);
      return roots;
    }
    Element body = htmlDoc.body();
    if (body == null) {
      return List.of();
    }
    body.select(CONDITIONAL_BOILERPLATE_SELECTOR).remove();
    return List.of(body);
  }

  private static List<Element> topLevelOnly(Elements candidates) {
    List<Element> roots = new ArrayList<>();
    for (Element candidate : candidates) {
      if (!isWithinAnyOf(candidate.parent(), candidates)) {
        roots.add(candidate);
      }
    }
    return roots;
  }

  private static void removeConditionalBoilerplateOutside(Document htmlDoc, List<Element> roots) {
    for (Element candidate : htmlDoc.select(CONDITIONAL_BOILERPLATE_SELECTOR)) {
      if (!isWithinAnyOf(candidate, roots)) {
        candidate.remove();
      }
    }
  }

  /** Whether {@code node} or one of its ancestors is one of {@code elements}. */
  private static boolean isWithinAnyOf(Node node, List<Element> elements) {
    for (Node current = node; current != null; current = current.parent()) {
      if (elements.contains(current)) {
        return true;
      }
    }
    return false;
  }
}
