package io.opaa.format.shared;

import io.opaa.format.chunk.ChunkMetadataKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Cuts a flat sequence of heading/paragraph events into chunks along the heading path in effect at
 * each cut (docs/features/ingestion-pipelines.md, Teil 2: "Markdown, DOCX ... |
 * Überschriftenabschnitt").
 *
 * <p>No text is ever dropped for size: a block beyond {@link #SOFT_CHUNK_CHAR_LIMIT} is cut into
 * parts by {@link BoundarySplitter}, each part a chunk of its own under the same heading line and
 * Fundort. Conversely, neighbouring sections below {@link #SMALL_SECTION_CHAR_LIMIT} under a common
 * heading are combined up to {@link #SOFT_CHUNK_CHAR_LIMIT}, see {@link #chunk}.
 *
 * <p>The maximum heading level that actually cuts a new chunk is a caller-supplied parameter, not a
 * constant - callers cap it differently depending on how deep their format's own outline goes. A
 * heading deeper than the cap folds into the current section's text instead of starting a new
 * chunk.
 */
public final class HeadingSectionSplitter {

  private static final Logger log = LoggerFactory.getLogger(HeadingSectionSplitter.class);

  /**
   * Soft budget a section's body text is grouped into before another chunk starts. <b>Gesetzt,
   * nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"): the evaluation corpus contains no
   * Markdown/DOCX/PDF documents with a real outline to measure a value against yet.
   */
  public static final int SOFT_CHUNK_CHAR_LIMIT = 4_000;

  /**
   * Ceiling of every chunk text, reached only where a heading line or a unit's leading context is
   * itself too long for {@link #SOFT_CHUNK_CHAR_LIMIT}. Even at two characters per token - dense
   * numbers and file references - 8,000 characters stay well inside the embedding step's token
   * budget (7,372 tokens of Spring AI's default {@code TokenCountBatchingStrategy}).
   */
  public static final int HARD_CHUNK_CHAR_LIMIT = 8_000;

  /**
   * A section whose own title and body together stay below this many characters is a tiny chunk and
   * combined with its tiny neighbours. <b>Gemessen</b> against the verwaltung domain
   * (ingestion-pipelines.md, "Kleinstchunks"): at 500 a single § was no longer retrievable on its
   * own.
   */
  public static final int SMALL_SECTION_CHAR_LIMIT = 200;

  public sealed interface Event {}

  /** Opens a new section at {@code level}, closing every open heading of level {@code >= level}. */
  public record Heading(int level, String title) implements Event {}

  /** A block of body text belonging to the section currently open. */
  public record Paragraph(String text) implements Event {}

  private HeadingSectionSplitter() {}

  /**
   * The first level-1 heading of {@code events}, or {@code null} when there is none - the format's
   * own first heading (ADR-0024), which may sit anywhere in the document and is therefore not its
   * title line.
   */
  public static String firstTopLevelHeading(List<Event> events) {
    for (Event event : events) {
      if (event instanceof Heading heading && heading.level() == 1 && !heading.title().isBlank()) {
        return heading.title();
      }
    }
    return null;
  }

  /**
   * One heading of the path in effect, told apart from an equally named sibling by the position of
   * its event.
   */
  private record PathEntry(String title, int position) {}

  /**
   * A section after the budgeted split: {@code bodies} holds one entry per part, none for a section
   * that is nothing but its own heading.
   */
  private record Section(List<PathEntry> path, List<String> bodies) {

    /**
     * Below {@link #SMALL_SECTION_CHAR_LIMIT} with its own title and body - the ancestors' titles
     * repeat in every section below them - and under at least one heading.
     */
    boolean tiny() {
      if (path.isEmpty() || bodies.size() > 1) {
        return false;
      }
      int body = bodies.isEmpty() ? 0 : bodies.getFirst().length();
      return path.getLast().title().length() + body < SMALL_SECTION_CHAR_LIMIT;
    }
  }

  /**
   * Cuts {@code events} into one section per cutting heading, then combines each run of
   * neighbouring tiny sections ({@link Section#tiny}) into chunks of at most {@link
   * #SOFT_CHUNK_CHAR_LIMIT}. A combined chunk never leaves the parent of its first section: its
   * heading line and Fundort are the members' common heading path, and each member keeps its
   * remaining headings inline in front of its text. A section of regular size is never combined,
   * nor are sections without a common heading.
   *
   * @param maxCuttingLevel the deepest heading level that still opens a new chunk; a {@link
   *     Heading} deeper than this folds into the current section's text instead.
   */
  public static List<Document> chunk(List<Event> events, int maxCuttingLevel) {
    List<Section> sections = new ArrayList<>();
    NavigableMap<Integer, PathEntry> headingPath = new TreeMap<>();
    List<String> blocks = new ArrayList<>();
    for (int position = 0; position < events.size(); position++) {
      Event event = events.get(position);
      if (event instanceof Heading heading && heading.level() <= maxCuttingLevel) {
        closeSection(sections, blocks, headingPath, heading.level());
        blocks = new ArrayList<>();
        // A heading of level n closes every open heading of level >= n, exactly as an outline
        // reads.
        headingPath.tailMap(heading.level(), true).clear();
        if (!heading.title().isBlank()) {
          headingPath.put(heading.level(), new PathEntry(heading.title().strip(), position));
        }
        continue;
      }
      String text = event instanceof Heading heading ? heading.title() : ((Paragraph) event).text();
      if (text != null && !text.isBlank()) {
        blocks.add(text.strip());
      }
    }
    closeSection(sections, blocks, headingPath, null);
    return combined(sections);
  }

  /**
   * Adds the section {@code blocks} form under {@code headingPath}, if any.
   *
   * @param closingLevel the level of the heading that is closing this section, or {@code null} when
   *     it closes because the input itself ended. A body-less section closed by a <em>deeper</em>
   *     heading is dropped rather than emitted as a redundant title-only chunk - its title already
   *     opens every descendant section's own heading path. A body-less section closed by a
   *     sibling/ancestor-level heading or by the end of the input is genuinely empty and still gets
   *     a one-line, heading-only chunk.
   */
  private static void closeSection(
      List<Section> sections,
      List<String> blocks,
      NavigableMap<Integer, PathEntry> headingPath,
      Integer closingLevel) {
    if (blocks.isEmpty() && headingPath.isEmpty()) {
      return;
    }
    boolean closedByADeeperHeading =
        closingLevel != null && !headingPath.isEmpty() && closingLevel > headingPath.lastKey();
    if (blocks.isEmpty() && closedByADeeperHeading) {
      return;
    }
    sections.add(new Section(List.copyOf(headingPath.values()), splitIntoBudgetedChunks(blocks)));
  }

  private static List<Document> combined(List<Section> sections) {
    List<Document> chunks = new ArrayList<>();
    int start = 0;
    while (start < sections.size()) {
      Section first = sections.get(start);
      List<PathEntry> common = first.path();
      int end = start + 1;
      if (first.tiny()) {
        int floor = Math.max(1, first.path().size() - 1);
        while (end < sections.size()) {
          Section next = sections.get(end);
          if (!next.tiny()) {
            break;
          }
          List<PathEntry> shared = commonPrefix(common, next.path());
          // The first two members fix the common heading; every further one must lie below it.
          boolean leavesTheGroup =
              shared.size() < floor || (end > start + 1 && shared.size() < common.size());
          if (leavesTheGroup
              || combinedText(shared, sections.subList(start, end + 1)).length()
                  > SOFT_CHUNK_CHAR_LIMIT) {
            break;
          }
          common = shared;
          end++;
        }
      }
      if (end == start + 1) {
        chunks.addAll(sectionChunks(first));
      } else {
        String headingLine = headingLine(common);
        chunks.add(
            new Document(
                combinedText(common, sections.subList(start, end)), locationMetadata(headingLine)));
      }
      start = end;
    }
    return chunks;
  }

  private static List<Document> sectionChunks(Section section) {
    String headingLine = section.path().isEmpty() ? null : headingLine(section.path());
    // A section that is nothing but its own heading still becomes a one-line chunk - otherwise
    // a heading-only section would look like NO_EXTRACTABLE_TEXT even though its heading is
    // real, searchable content.
    List<String> bodies = section.bodies().isEmpty() ? List.of("") : section.bodies();
    Map<String, Object> metadata = locationMetadata(headingLine);
    List<Document> chunks = new ArrayList<>();
    for (String body : bodies) {
      String text =
          headingLine == null ? body : body.isEmpty() ? headingLine : headingLine + "\n\n" + body;
      chunks.addAll(withinCeiling(text, metadata));
    }
    return chunks;
  }

  /** The common heading line, then each member's own headings below it and its text. */
  private static String combinedText(List<PathEntry> common, List<Section> members) {
    StringBuilder text = new StringBuilder(headingLine(common));
    for (Section member : members) {
      String ownHeadings = headingLine(member.path().subList(common.size(), member.path().size()));
      String body = member.bodies().isEmpty() ? "" : member.bodies().getFirst();
      for (String part : List.of(ownHeadings, body)) {
        if (!part.isEmpty()) {
          text.append("\n\n").append(part);
        }
      }
    }
    return text.toString();
  }

  private static List<PathEntry> commonPrefix(List<PathEntry> a, List<PathEntry> b) {
    int length = 0;
    while (length < a.size() && length < b.size() && a.get(length).equals(b.get(length))) {
      length++;
    }
    return a.subList(0, length);
  }

  private static String headingLine(List<PathEntry> path) {
    return String.join(" › ", path.stream().map(PathEntry::title).toList());
  }

  private static Map<String, Object> locationMetadata(String headingLine) {
    Map<String, Object> metadata = new HashMap<>();
    if (headingLine != null) {
      metadata.put(ChunkMetadataKeys.LOCATION_METADATA_KEY, "Abschn. " + headingLine);
    }
    return metadata;
  }

  private static List<String> splitIntoBudgetedChunks(List<String> blocks) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String block : withinSoftBudget(blocks)) {
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

  /** {@code blocks} with every block beyond the soft budget replaced by its parts. */
  private static List<String> withinSoftBudget(List<String> blocks) {
    List<String> result = new ArrayList<>(blocks.size());
    for (String block : blocks) {
      if (block.length() <= SOFT_CHUNK_CHAR_LIMIT) {
        result.add(block);
      } else {
        result.addAll(BoundarySplitter.split(block, SOFT_CHUNK_CHAR_LIMIT));
      }
    }
    return result;
  }

  /** {@link #boundedChunks(String, String, String, Map)} with a blank line after the context. */
  public static List<Document> boundedChunks(
      String context, String body, Map<String, Object> metadata) {
    return boundedChunks(context, "\n\n", body, metadata);
  }

  /**
   * The chunks of one unit (page, slide, row group): {@code context + separator + body} as one
   * chunk while it fits {@link #SOFT_CHUNK_CHAR_LIMIT}, otherwise {@code body} cut by {@link
   * BoundarySplitter} with {@code context} - a slide title, a table's context and header line -
   * repeated in front of every part. Every chunk gets its own copy of {@code metadata}, so each
   * part keeps the unit's Fundort.
   *
   * @param context the leading text every part repeats, or {@code null}
   */
  public static List<Document> boundedChunks(
      String context, String separator, String body, Map<String, Object> metadata) {
    boolean hasContext = context != null && !context.isEmpty();
    String whole = !hasContext ? body : body.isEmpty() ? context : context + separator + body;
    if (whole.length() <= SOFT_CHUNK_CHAR_LIMIT || body.isBlank()) {
      return withinCeiling(whole, metadata);
    }
    int bodyLimit =
        hasContext
            ? Math.max(
                SOFT_CHUNK_CHAR_LIMIT - context.length() - separator.length(),
                SOFT_CHUNK_CHAR_LIMIT / 2)
            : SOFT_CHUNK_CHAR_LIMIT;
    List<Document> chunks = new ArrayList<>();
    for (String part : BoundarySplitter.split(body, bodyLimit)) {
      chunks.addAll(withinCeiling(hasContext ? context + separator + part : part, metadata));
    }
    return chunks;
  }

  /**
   * One chunk for {@code text}, or - beyond {@link #HARD_CHUNK_CHAR_LIMIT} - one per part cut by
   * {@link BoundarySplitter}, in order, each with its own copy of {@code metadata}.
   */
  private static List<Document> withinCeiling(String text, Map<String, Object> metadata) {
    if (text.length() <= HARD_CHUNK_CHAR_LIMIT) {
      return List.of(new Document(text, new HashMap<>(metadata)));
    }
    List<String> parts = BoundarySplitter.split(text, HARD_CHUNK_CHAR_LIMIT);
    log.debug(
        "Split a chunk of {} characters into {} parts at the hard limit of {}",
        text.length(),
        parts.size(),
        HARD_CHUNK_CHAR_LIMIT);
    return parts.stream().map(part -> new Document(part, new HashMap<>(metadata))).toList();
  }
}
