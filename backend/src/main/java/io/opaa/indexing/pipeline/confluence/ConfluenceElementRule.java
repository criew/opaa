package io.opaa.indexing.pipeline.confluence;

import io.opaa.indexing.pipeline.XhtmlEventBuilder;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Element;

/**
 * The Confluence storage format's own elements on top of the shared XHTML walk: layout and macro
 * bodies are blocks, resource references and editor bookkeeping are invisible, a macro is rendered
 * by its {@link ConfluenceMacroRules} class, a task list keeps its state and a {@code time} element
 * is replaced by its date. Everything else is plain XHTML and falls through to {@link
 * XhtmlEventBuilder}.
 */
final class ConfluenceElementRule implements XhtmlEventBuilder.ElementRule {

  static final ConfluenceElementRule INSTANCE = new ConfluenceElementRule();

  private static final Set<String> BLOCK_TAGS =
      Set.of(
          "ac:layout",
          "ac:layout-section",
          "ac:layout-cell",
          "ac:rich-text-body",
          // Cloud's new editor wraps its elements as ac:adf-extension: the adf-content is the body
          "ac:adf-content");

  /** Elements whose subtree never carries visible text. */
  private static final Set<String> INVISIBLE_TAGS =
      Set.of(
          "ac:parameter",
          // Cloud repeats an ADF element's content as a legacy fallback - one copy is enough
          "ac:adf-fallback",
          "ac:adf-attribute",
          "ac:adf-node-attribute",
          "ac:image",
          "ac:emoticon",
          "ac:placeholder",
          "ri:page",
          "ri:attachment",
          "ri:url",
          "ri:user",
          "ri:space",
          "ri:blog-post",
          "ri:content-entity",
          "ri:shortcut");

  private ConfluenceElementRule() {}

  @Override
  public boolean handle(Element element, XhtmlEventBuilder builder) {
    String tag = element.tagName().toLowerCase(Locale.ROOT);
    if (INVISIBLE_TAGS.contains(tag)) {
      return true;
    }
    if (BLOCK_TAGS.contains(tag)) {
      builder.block(element);
      return true;
    }
    switch (tag) {
      case "ac:structured-macro", "ac:macro" -> macro(element, builder);
      case "ac:task-list" -> taskList(element, builder);
      case "time" -> builder.appendInline(" " + element.attr("datetime") + " ");
      default -> {
        return false;
      }
    }
    return true;
  }

  private static void macro(Element macro, XhtmlEventBuilder builder) {
    String name = macro.attr("ac:name").toLowerCase(Locale.ROOT);
    ConfluenceMacroRules.Rule rule = ConfluenceMacroRules.ruleFor(name);
    switch (rule) {
      case DROP -> {
        // rendered at view time from somewhere else - nothing of it is page content
      }
      case VERBATIM -> {
        builder.flushBlock();
        Element body = macro.selectFirst("> ac|plain-text-body");
        if (body == null) {
          // a code macro without a plain-text body (old editors wrote a rich-text body) keeps
          // whatever text it carries rather than losing it
          for (Element child : macro.children()) {
            if (child.tagName().equalsIgnoreCase("ac:rich-text-body")) {
              builder.block(child);
            }
          }
          return;
        }
        String language = parameter(macro, "language");
        String code = body.wholeText().strip();
        if (!code.isEmpty()) {
          builder.verbatim(language.isEmpty() ? code : language + ":\n" + code);
        }
      }
      case INLINE_TITLE -> {
        String title = parameter(macro, "title");
        if (!title.isEmpty()) {
          builder.appendInline(" " + title + " ");
        }
      }
      case KEEP_BODY -> {
        builder.flushBlock();
        String title = parameter(macro, "title");
        if (!title.isEmpty()) {
          builder.emitLine(title);
        }
        for (Element body : macro.children()) {
          String bodyTag = body.tagName().toLowerCase(Locale.ROOT);
          if (bodyTag.equals("ac:rich-text-body")) {
            builder.block(body);
          } else if (bodyTag.equals("ac:plain-text-body")) {
            builder.verbatim(body.wholeText());
          }
        }
      }
    }
  }

  private static void taskList(Element taskList, XhtmlEventBuilder builder) {
    builder.flushBlock();
    for (Element task : taskList.children()) {
      if (!task.tagName().equalsIgnoreCase("ac:task")) {
        continue;
      }
      Element status = task.selectFirst("> ac|task-status");
      Element body = task.selectFirst("> ac|task-body");
      boolean complete = status != null && status.text().trim().equalsIgnoreCase("complete");
      String text = body == null ? "" : builder.inlineText(body);
      if (!text.isEmpty()) {
        builder.emitLine((complete ? "[x] " : "[ ] ") + text);
      }
    }
  }

  private static String parameter(Element macro, String name) {
    for (Element parameter : macro.children()) {
      if (parameter.tagName().equalsIgnoreCase("ac:parameter")
          && parameter.attr("ac:name").equalsIgnoreCase(name)) {
        return io.opaa.indexing.pipeline.Whitespace.normalize(parameter.text()).strip();
      }
    }
    return "";
  }
}
