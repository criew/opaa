package io.opaa.indexing;

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
 * Überschriftenabschnitt"). Used by {@link MarkdownDocumentPipeline} (events come from ATX heading
 * lines), {@link DocxDocumentPipeline} (events come from a paragraph's outline level) and {@link
 * PdfDocumentPipeline} (events come from the PDF catalog/outline) via {@link #chunk}, the
 * event-list entry point. {@link HtmlDocumentPipeline} is driven by DOM traversal rather than a
 * flat event list, so it builds its own {@code blocks}/{@code headingPath} state, but calls the
 * package-visible {@link #flushSection} and {@link #capChunkLength} directly instead of keeping a
 * second copy of the section-emission and size-budgeting logic - the #1100 review findings that
 * shaped this logic (empty-section suppression, soft/hard limits) live in exactly one place this
 * way, not two that could drift apart.
 *
 * <p>The maximum heading level that actually cuts a new chunk is a caller-supplied parameter, not a
 * constant: Markdown/DOCX cap at level 3 (ingestion-pipelines.md, Teil 2 table), while a PDF
 * catalog's outline cuts on every level it offers (§ and Absatz are two nesting levels, and a
 * document with deeper nesting still gets a citable chunk per level). A heading deeper than the cap
 * does not start a new chunk - it folds into the current section's text, exactly like h4-h6 in
 * {@link HtmlDocumentPipeline}.
 */
final class HeadingSectionSplitter {

  private static final Logger log = LoggerFactory.getLogger(HeadingSectionSplitter.class);

  /**
   * Soft budget a section's body text is grouped into before another chunk starts - mirrors {@link
   * HtmlDocumentPipeline#SOFT_CHUNK_CHAR_LIMIT} both in value and in intent. <b>Gesetzt, nicht
   * gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"): the evaluation corpus contains no
   * Markdown/DOCX/PDF documents with a real outline to measure a value against yet (Teil 2, "Ohne
   * diesen Korpus ist keine typspezifische Chunk-Größe messbar").
   */
  static final int SOFT_CHUNK_CHAR_LIMIT = 4_000;

  /** Last-resort backstop for a single block that alone already exceeds the soft budget. */
  static final int HARD_CHUNK_CHAR_LIMIT = 20_000;

  private static final String TRUNCATION_MARKER = " […gekürzt]";

  sealed interface Event {}

  /** Opens a new section at {@code level}, closing every open heading of level {@code >= level}. */
  record Heading(int level, String title) implements Event {}

  /** A block of body text belonging to the section currently open. */
  record Paragraph(String text) implements Event {}

  private HeadingSectionSplitter() {}

  /**
   * @param maxCuttingLevel the deepest heading level that still opens a new chunk; a {@link
   *     Heading} deeper than this folds into the current section's text instead.
   */
  static List<Document> chunk(List<Event> events, int maxCuttingLevel) {
    List<Document> chunks = new ArrayList<>();
    NavigableMap<Integer, String> headingPath = new TreeMap<>();
    List<String> blocks = new ArrayList<>();
    for (Event event : events) {
      if (event instanceof Heading heading && heading.level() <= maxCuttingLevel) {
        flushSection(chunks, blocks, headingPath, heading.level());
        blocks = new ArrayList<>();
        // A heading of level n closes every open heading of level >= n, exactly as an outline
        // reads (mirrors HtmlDocumentPipeline#buildChunks's own stack rule).
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
   * Turns one section's collected blocks into one or more chunks - package-visible so {@link
   * HtmlDocumentPipeline#buildChunks} can call it directly with the blocks/heading-path state its
   * own DOM traversal accumulates, instead of duplicating this method.
   *
   * @param closingLevel the level of the heading that is closing this section, or {@code null} when
   *     it closes because the event stream (or, for {@link HtmlDocumentPipeline}, the document)
   *     itself ended. A body-less section closed by a <em>deeper</em> heading is dropped rather
   *     than emitted as a redundant title-only chunk - its title already opens every descendant
   *     section's own heading path. A body-less section closed by a sibling/ancestor-level heading
   *     or by the end of the input is genuinely empty and still gets a one-line, heading-only
   *     chunk.
   */
  static void flushSection(
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
      // real, searchable content (mirrors HtmlDocumentPipeline#flushSection).
      bodies = List.of("");
    }
    for (String body : bodies) {
      String text =
          headingLine == null ? body : body.isEmpty() ? headingLine : headingLine + "\n\n" + body;
      Map<String, Object> metadata = new HashMap<>();
      if (location != null) {
        metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
      }
      chunks.add(new Document(capChunkLength(text), metadata));
    }
  }

  private static List<String> splitIntoBudgetedChunks(List<String> blocks) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String block : blocks) {
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

  /**
   * Package-visible so {@link PdfDocumentPipeline#chunkByPage} (a page-fallback chunk, never
   * heading-sectioned) and {@link HtmlDocumentPipeline} can apply the same last-resort backstop
   * without their own copy.
   */
  static String capChunkLength(String text) {
    if (text.length() <= HARD_CHUNK_CHAR_LIMIT) {
      return text;
    }
    log.warn(
        "A chunk exceeds the hard limit of {} characters ({} actual); truncating",
        HARD_CHUNK_CHAR_LIMIT,
        text.length());
    return text.substring(0, HARD_CHUNK_CHAR_LIMIT - TRUNCATION_MARKER.length())
        + TRUNCATION_MARKER;
  }
}
