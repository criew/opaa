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
 * Überschriftenabschnitt"). {@link #chunk} is the event-list entry point; {@link #flushSection} is
 * exposed separately for a caller (e.g. a DOM-driven pipeline) that accumulates its own {@code
 * blocks}/{@code headingPath} state instead of an event list.
 *
 * <p>No text is ever dropped for size: a block beyond {@link #SOFT_CHUNK_CHAR_LIMIT} is cut into
 * parts by {@link BoundarySplitter}, each part a chunk of its own under the same heading line and
 * Fundort.
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
   * Ceiling of every chunk text, for the formats whose natural unit (page, slide, row group) has no
   * soft budget; {@link #boundedChunks} splits anything longer. Even at two characters per token -
   * dense numbers and file references - 8,000 characters stay well inside the embedding step's
   * token budget (7,372 tokens of Spring AI's default {@code TokenCountBatchingStrategy}).
   */
  public static final int HARD_CHUNK_CHAR_LIMIT = 8_000;

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
   * @param maxCuttingLevel the deepest heading level that still opens a new chunk; a {@link
   *     Heading} deeper than this folds into the current section's text instead.
   */
  public static List<Document> chunk(List<Event> events, int maxCuttingLevel) {
    List<Document> chunks = new ArrayList<>();
    NavigableMap<Integer, String> headingPath = new TreeMap<>();
    List<String> blocks = new ArrayList<>();
    for (Event event : events) {
      if (event instanceof Heading heading && heading.level() <= maxCuttingLevel) {
        flushSection(chunks, blocks, headingPath, heading.level());
        blocks = new ArrayList<>();
        // A heading of level n closes every open heading of level >= n, exactly as an outline
        // reads.
        headingPath.tailMap(heading.level(), true).clear();
        if (!heading.title().isBlank()) {
          headingPath.put(heading.level(), heading.title().strip());
        }
        continue;
      }
      String text = event instanceof Heading heading ? heading.title() : ((Paragraph) event).text();
      if (text != null && !text.isBlank()) {
        blocks.add(text.strip());
      }
    }
    flushSection(chunks, blocks, headingPath, null);
    return chunks;
  }

  /**
   * Turns one section's collected blocks into one or more chunks.
   *
   * @param closingLevel the level of the heading that is closing this section, or {@code null} when
   *     it closes because the input itself ended. A body-less section closed by a <em>deeper</em>
   *     heading is dropped rather than emitted as a redundant title-only chunk - its title already
   *     opens every descendant section's own heading path. A body-less section closed by a
   *     sibling/ancestor-level heading or by the end of the input is genuinely empty and still gets
   *     a one-line, heading-only chunk.
   */
  public static void flushSection(
      List<Document> chunks,
      List<String> blocks,
      NavigableMap<Integer, String> headingPath,
      Integer closingLevel) {
    if (blocks.isEmpty() && headingPath.isEmpty()) {
      return;
    }
    boolean closedByADeeperHeading =
        closingLevel != null && !headingPath.isEmpty() && closingLevel > headingPath.lastKey();
    if (blocks.isEmpty() && closedByADeeperHeading) {
      return;
    }
    String headingLine = headingPath.isEmpty() ? null : String.join(" › ", headingPath.values());
    String location = headingLine == null ? null : "Abschn. " + headingLine;
    List<String> bodies = splitIntoBudgetedChunks(blocks);
    if (bodies.isEmpty()) {
      // A section that is nothing but its own heading still becomes a one-line chunk - otherwise
      // a heading-only section would look like NO_EXTRACTABLE_TEXT even though its heading is
      // real, searchable content.
      bodies = List.of("");
    }
    Map<String, Object> metadata = new HashMap<>();
    if (location != null) {
      metadata.put(ChunkMetadataKeys.LOCATION_METADATA_KEY, location);
    }
    for (String body : bodies) {
      String text =
          headingLine == null ? body : body.isEmpty() ? headingLine : headingLine + "\n\n" + body;
      chunks.addAll(boundedChunks(text, metadata));
    }
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

  /**
   * One chunk for {@code text}, or - beyond {@link #HARD_CHUNK_CHAR_LIMIT} - one per part cut by
   * {@link BoundarySplitter}, in order. Every chunk gets its own copy of {@code metadata}, so each
   * part keeps the Fundort of the unit it came from.
   */
  public static List<Document> boundedChunks(String text, Map<String, Object> metadata) {
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
