package io.opaa.format.file.html;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

/**
 * The one boilerplate and main-content selection for an HTML page, shared by {@link
 * HtmlDocumentFormat} and the feed connector's detail-page extraction: chrome that is never content
 * goes document-wide, header/footer only outside the chosen content roots, and every top-level
 * match of the main-content selector is a root - an overview page routinely lists several teasers.
 * {@link #select} mutates the document it is given.
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
    removeUnconditionalBoilerplate(htmlDoc);
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
    removeAll(body.select(CONDITIONAL_BOILERPLATE_SELECTOR));
    return List.of(body);
  }

  /** Removes {@link #UNCONDITIONAL_BOILERPLATE_SELECTOR} matches from all of {@code htmlDoc}. */
  public static void removeUnconditionalBoilerplate(Document htmlDoc) {
    removeAll(htmlDoc.select(UNCONDITIONAL_BOILERPLATE_SELECTOR));
  }

  private static List<Element> topLevelOnly(Elements candidates) {
    Set<Node> candidateSet = identitySetOf(candidates);
    List<Element> roots = new ArrayList<>();
    for (Element candidate : candidates) {
      if (!isWithinAnyOf(candidate.parent(), candidateSet)) {
        roots.add(candidate);
      }
    }
    return roots;
  }

  private static void removeConditionalBoilerplateOutside(Document htmlDoc, List<Element> roots) {
    Set<Node> rootSet = identitySetOf(roots);
    List<Element> outside = new ArrayList<>();
    for (Element candidate : htmlDoc.select(CONDITIONAL_BOILERPLATE_SELECTOR)) {
      if (!isWithinAnyOf(candidate, rootSet)) {
        outside.add(candidate);
      }
    }
    removeAll(outside);
  }

  /**
   * Detaches {@code elements} with one rebuild of each affected parent's child list, in time linear
   * in the number of children touched: jsoup's single-node removal renumbers every later sibling,
   * which makes removing many siblings one by one quadratic.
   */
  private static void removeAll(List<Element> elements) {
    Set<Node> doomed = identitySetOf(elements);
    Set<Element> parents = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Element element : elements) {
      Element parent = element.parent();
      if (parent != null) {
        parents.add(parent);
      }
    }
    for (Element parent : parents) {
      List<Node> kept = new ArrayList<>(parent.childNodeSize());
      for (Node child : parent.childNodes()) {
        if (!doomed.contains(child)) {
          kept.add(child);
        }
      }
      parent.empty();
      parent.appendChildren(kept);
    }
  }

  /** Elements are compared by identity, as jsoup's own equality does, at constant cost. */
  private static Set<Node> identitySetOf(List<Element> elements) {
    Set<Node> set = Collections.newSetFromMap(new IdentityHashMap<>(elements.size() * 2));
    set.addAll(elements);
    return set;
  }

  /** Whether {@code node} or one of its ancestors is one of {@code elements}. */
  private static boolean isWithinAnyOf(Node node, Set<Node> elements) {
    for (Node current = node; current != null; current = current.parent()) {
      if (elements.contains(current)) {
        return true;
      }
    }
    return false;
  }
}
