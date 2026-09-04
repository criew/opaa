package io.opaa.indexing.pipeline.confluence;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Event;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Heading;
import io.opaa.indexing.pipeline.HeadingSectionSplitter.Paragraph;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.ai.document.Document;

/**
 * Turns a Confluence page's storage-format body (XHTML with {@code ac:}/{@code ri:} macro elements,
 * identical for Cloud and Data Center) into heading-section chunks (#1137, ADR-0023;
 * docs/features/ingestion-pipelines.md, Teil 3, Punkt 6). Not a file format: the pipeline claims no
 * extension and is invoked by {@code FileProcessingService#processConfluencePage} directly, the way
 * an RSS entry's text goes to the fallback pipeline - the executor hands the body over as extracted
 * text.
 *
 * <p><b>Macro rules</b> ({@link ConfluenceMacroRules}): content that is embedded statically in the
 * page - panels, notes, expands, page properties, code blocks, status lozenges, excerpts - stays;
 * content that Confluence renders at view time from elsewhere - tables of contents, child lists,
 * Jira tables, label reports, includes, charts, calendars - is dropped, because it is either a
 * navigation aid or a copy of data that lives in another system (the epic keeps macros with their
 * own data store out of scope). Macro <em>parameters</em> never become text except where a
 * parameter <em>is</em> the visible content (a status lozenge's title, a panel's title).
 *
 * <p><b>Structure</b>: h1-h6 become headings ({@link #MAX_CUTTING_LEVEL} h1-h3 cut a chunk, deeper
 * headings fold into the text like every other outline-driven pipeline); tables become one line per
 * row with cells separated by " | "; lists become one line per item with a marker (•, ◦, ▪ by
 * nesting depth, or a nested number such as "2.1."); code and {@code noformat} blocks keep their
 * line breaks; task lists keep their state; link texts stay, link targets and images do not. The
 * heading path in effect at each cut is written as the chunk's first line and as its {@code
 * location} metadata (Fundort), shared with the other pipelines via {@link HeadingSectionSplitter}.
 *
 * <p><b>Context</b>: the space key and the page's hierarchy path are not in the body - the caller
 * knows them and writes them onto every chunk under {@link #SPACE_METADATA_KEY} and {@link
 * #HIERARCHY_METADATA_KEY}, which this pipeline declares as passthrough keys so {@code
 * FileProcessingService#storeChunks} keeps them.
 */
public class ConfluenceDocumentPipeline implements DocumentPipeline {

  public static final String ID = "confluence";
  static final short VERSION = 1;

  /** Chunk metadata: the Confluence space key the page belongs to. */
  public static final String SPACE_METADATA_KEY = "source_container_key";

  /**
   * Chunk metadata: the page's ancestors root first, joined with " / " - the same value as the
   * document's own column of that name; the page title is the chunk's {@code file_name}.
   */
  public static final String HIERARCHY_METADATA_KEY = "source_hierarchy_path";

  /** h1-h3 open a new chunk, like the HTML and Markdown pipelines; h4-h6 fold into the text. */
  static final int MAX_CUTTING_LEVEL = 3;

  // Jsoup counts U+00A0 as whitespace, Java does not - and Confluence writes <p>&nbsp;</p> for
  // every empty editor line (the same pattern the DOCX/ODF pipelines use).
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u202F]+");
  private static final Set<String> BLOCK_TAGS =
      Set.of(
          "p",
          "div",
          "blockquote",
          "section",
          "hr",
          "br",
          "dt",
          "dd",
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

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  /** No file format - invoked directly by the Confluence run, never routed (see class Javadoc). */
  @Override
  public Set<String> handledFormats() {
    return Set.of();
  }

  @Override
  public Set<String> passthroughMetadataKeys() {
    return Set.of(
        ChunkingService.LOCATION_METADATA_KEY, SPACE_METADATA_KEY, HIERARCHY_METADATA_KEY);
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    String body = bodyOf(source);
    if (body == null || body.isBlank()) {
      return DocumentPipelineResult.noContent();
    }
    // The XML parser keeps the namespaced macro elements intact; the HTML parser would not.
    org.jsoup.nodes.Document document = Jsoup.parse(body, "", Parser.xmlParser());
    List<Event> events = new EventBuilder().build(document);
    List<Document> chunks = HeadingSectionSplitter.chunk(events, MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  private static String bodyOf(DocumentPipelineSource source) {
    if (source.file() == null) {
      return source.extractedText();
    }
    try {
      return Files.readString(source.file(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read Confluence body " + source.fileName(), e);
    }
  }

  /** Walks the storage-format DOM and emits heading/paragraph events in document order. */
  private static final class EventBuilder {
    private final List<Event> events = new ArrayList<>();
    private final StringBuilder inline = new StringBuilder();

    List<Event> build(org.jsoup.nodes.Document document) {
      for (Node child : document.childNodes()) {
        walk(child);
      }
      flushBlock();
      return events;
    }

    private void walk(Node node) {
      if (node instanceof TextNode text) {
        inline.append(text.getWholeText());
        return;
      }
      if (!(node instanceof Element element)) {
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
        case "ac:structured-macro", "ac:macro" -> macro(element);
        case "ac:task-list" -> taskList(element);
        case "ac:link" -> walkChildren(element);
        case "time" -> inline.append(' ').append(element.attr("datetime")).append(' ');
        default -> {
          if (BLOCK_TAGS.contains(tag)) {
            flushBlock();
            walkChildren(element);
            flushBlock();
          } else {
            walkChildren(element);
          }
        }
      }
    }

    private void walkChildren(Element element) {
      for (Node child : element.childNodes()) {
        walk(child);
      }
    }

    private void heading(Element element, int level) {
      flushBlock();
      // through the macro-aware walker, not Element#text(): a status lozenge or a Jira macro inside
      // a heading must not leak its parameters into the heading path
      String title = cellText(element);
      if (!title.isEmpty()) {
        events.add(new Heading(level, title));
      }
    }

    /**
     * @param numbering the enclosing ordered list's number prefix ("2." for the second item's
     *     nested list), so nesting is carried by the marker ("2.1.") - HeadingSectionSplitter
     *     strips every block, leading spaces would not survive the cut
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
          cells.add(cellText(cell));
        }
        if (!cells.isEmpty()) {
          emitLine(String.join(" | ", cells));
        }
      }
    }

    /** A cell rendered on its own: nested lists and macros inside it flatten to one line. */
    private String cellText(Element cell) {
      EventBuilder nested = new EventBuilder();
      nested.walkChildren(cell);
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

    private void macro(Element macro) {
      String name = macro.attr("ac:name").toLowerCase(Locale.ROOT);
      ConfluenceMacroRules.Rule rule = ConfluenceMacroRules.ruleFor(name);
      switch (rule) {
        case DROP -> {
          // rendered at view time from somewhere else - nothing of it is page content
        }
        case VERBATIM -> {
          flushBlock();
          Element body = macro.selectFirst("> ac|plain-text-body");
          if (body == null) {
            // a code macro without a plain-text body (old editors wrote a rich-text body) keeps
            // whatever text it carries rather than losing it
            for (Element child : macro.children()) {
              if (child.tagName().equalsIgnoreCase("ac:rich-text-body")) {
                walkChildren(child);
                flushBlock();
              }
            }
            return;
          }
          String language = parameter(macro, "language");
          String code = body.wholeText().strip();
          if (!code.isEmpty()) {
            events.add(new Paragraph(language.isEmpty() ? code : language + ":\n" + code));
          }
        }
        case INLINE_TITLE -> {
          String title = parameter(macro, "title");
          if (!title.isEmpty()) {
            inline.append(' ').append(title).append(' ');
          }
        }
        case KEEP_BODY -> {
          flushBlock();
          String title = parameter(macro, "title");
          if (!title.isEmpty()) {
            emitLine(title);
          }
          for (Element body : macro.children()) {
            String bodyTag = body.tagName().toLowerCase(Locale.ROOT);
            if (bodyTag.equals("ac:rich-text-body")) {
              walkChildren(body);
              flushBlock();
            } else if (bodyTag.equals("ac:plain-text-body")) {
              verbatim(body.wholeText());
            }
          }
        }
      }
    }

    private void taskList(Element taskList) {
      flushBlock();
      for (Element task : taskList.children()) {
        if (!task.tagName().equalsIgnoreCase("ac:task")) {
          continue;
        }
        Element status = task.selectFirst("> ac|task-status");
        Element body = task.selectFirst("> ac|task-body");
        boolean complete = status != null && status.text().trim().equalsIgnoreCase("complete");
        String text = body == null ? "" : cellText(body);
        if (!text.isEmpty()) {
          emitLine((complete ? "[x] " : "[ ] ") + text);
        }
      }
    }

    private void verbatim(String text) {
      flushBlock();
      String stripped = text.strip();
      if (!stripped.isEmpty()) {
        events.add(new Paragraph(stripped));
      }
    }

    private static String parameter(Element macro, String name) {
      for (Element parameter : macro.children()) {
        if (parameter.tagName().equalsIgnoreCase("ac:parameter")
            && parameter.attr("ac:name").equalsIgnoreCase(name)) {
          return normalize(parameter.text());
        }
      }
      return "";
    }

    private void emitLine(String line) {
      flushBlock();
      if (!line.isBlank()) {
        events.add(new Paragraph(line.stripTrailing()));
      }
    }

    private void flushBlock() {
      String text = normalize(inline.toString());
      inline.setLength(0);
      if (!text.isEmpty()) {
        events.add(new Paragraph(text));
      }
    }

    private static String normalize(String text) {
      return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }
  }
}
