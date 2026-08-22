package io.opaa.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a human-readable, German location ("Fundort", #667) for a chunk from the structure of the
 * text it was cut from - the information mockup 1a prints next to a footnote ("S. 2–4", "Abschn.
 * 4.2 ‚Fristsetzung'"). Two structural signals are recognised, independently of each other:
 *
 * <ul>
 *   <li><b>Page breaks</b>: a form feed ({@code \f}) marks the start of every page after the first
 *       - emitted by {@link PageMarkingContentHandler} for formats Tika paginates (PDF, Office
 *       documents). A chunk's page range is the page its first character sits on up to the page its
 *       last character sits on.
 *   <li><b>Headings</b>: Markdown-style {@code # Heading} lines. The heading path in effect at the
 *       chunk's start is reported, deepest two levels only, so a long document's location stays a
 *       short label rather than a breadcrumb trail.
 * </ul>
 *
 * <p>Both signals combine ("S. 3 · Abschn. Fristen › Verlängerung"); a text carrying neither yields
 * {@code null}, and the caller stores no location at all - the frontend then shows the footnote
 * without a Fundort, which is the honest answer for a flat text file.
 *
 * <p>The text is scanned once per document ({@link #forText}) and then queried per chunk ({@link
 * #locate}), so a document with hundreds of chunks does not rescan its headings hundreds of times.
 */
final class ChunkLocationResolver {

  static final char PAGE_BREAK = '\f';

  /** Deepest heading levels kept in the reported path. */
  private static final int MAX_HEADING_LEVELS = 2;

  // A heading starts a line - or a page: Tika's page marker (\f) is not a line terminator to
  // Java's MULTILINE "^", yet a page always starts fresh.
  private static final Pattern HEADING =
      Pattern.compile("(?m)(?:^|\\f)[ \\t]{0,3}(#{1,6})[ \\t]+(\\S.*?)[ \\t#]*$");

  private record Heading(int offset, int end, int level, String title) {}

  private final String text;
  private final int[] pageBreakOffsets;
  private final List<Heading> headings;

  private ChunkLocationResolver(String text, int[] pageBreakOffsets, List<Heading> headings) {
    this.text = text;
    this.pageBreakOffsets = pageBreakOffsets;
    this.headings = headings;
  }

  static ChunkLocationResolver forText(String text) {
    List<Integer> breaks = new ArrayList<>();
    for (int i = text.indexOf(PAGE_BREAK); i >= 0; i = text.indexOf(PAGE_BREAK, i + 1)) {
      breaks.add(i);
    }
    List<Heading> headings = new ArrayList<>();
    Matcher matcher = HEADING.matcher(text);
    while (matcher.find()) {
      headings.add(
          new Heading(
              matcher.start(1), matcher.end(), matcher.group(1).length(), matcher.group(2).trim()));
    }
    return new ChunkLocationResolver(
        text, breaks.stream().mapToInt(Integer::intValue).toArray(), headings);
  }

  /**
   * The location of the chunk spanning {@code [start, end)} in the scanned text, or {@code null}
   * when the text carries neither page breaks nor headings before {@code start}.
   */
  String locate(int start, int end) {
    List<String> parts = new ArrayList<>(2);
    String pages = pageRange(start, Math.max(start, end - 1));
    if (pages != null) {
      parts.add(pages);
    }
    String path = headingPath(skipLeadingHeadings(start));
    if (path != null) {
      parts.add(path);
    }
    return parts.isEmpty() ? null : String.join(" · ", parts);
  }

  private String pageRange(int start, int last) {
    if (pageBreakOffsets.length == 0) {
      return null;
    }
    int first = pageAt(start);
    int lastPage = pageAt(last);
    return first == lastPage ? "S. " + first : "S. " + first + "–" + lastPage;
  }

  private int pageAt(int offset) {
    int page = 1;
    for (int breakOffset : pageBreakOffsets) {
      if (breakOffset > offset) {
        break;
      }
      page++;
    }
    return page;
  }

  /**
   * A chunk that opens with heading lines belongs to those headings: its Fundort is the path in
   * effect after them, not the (parent) path in effect at the heading's first character.
   */
  private int skipLeadingHeadings(int offset) {
    int position = offset;
    for (Heading heading : headings) {
      if (heading.offset() < position) {
        continue;
      }
      if (!text.substring(position, heading.offset()).isBlank()) {
        break;
      }
      position = heading.end();
    }
    return position;
  }

  private String headingPath(int offset) {
    // The heading stack in effect at offset: a heading of level n closes every open heading of
    // level >= n, exactly as an outline reads.
    List<Heading> stack = new ArrayList<>();
    for (Heading heading : headings) {
      if (heading.offset() > offset) {
        break;
      }
      while (!stack.isEmpty() && stack.getLast().level() >= heading.level()) {
        stack.removeLast();
      }
      stack.add(heading);
    }
    if (stack.isEmpty()) {
      return null;
    }
    List<String> titles =
        stack.subList(Math.max(0, stack.size() - MAX_HEADING_LEVELS), stack.size()).stream()
            .map(Heading::title)
            .toList();
    return "Abschn. " + String.join(" › ", titles);
  }
}
