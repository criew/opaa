package io.opaa.indexing.chunk;

import io.opaa.knowledge.SourceDocumentContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;

/**
 * Builds the Kontextpräfix every chunk of a document carries into embedding and full-text index
 * (metadata-schema.md, Wirkstelle 2): {@code Titel › Fassung 2026 › § 7 Gebühren}. The segments are
 * title, the prefix-effective metadata values in schema order and the chunk's own Strukturkontext;
 * a blank segment is left out entirely, and a prefix without any segment does not exist. The prefix
 * is part of the chunk's presentation, never of its stored text - the quoted excerpt in a Beleg
 * stays the original wording.
 *
 * <p>{@link #applyTo} is the single gate both writing paths go through, so a document re-embedded
 * by the Nachlauf carries the same indexed text as one freshly ingested; {@link #stampOf} is the
 * fingerprint of the document-level half of that decision, and what the Nachlauf selects by.
 */
public final class ChunkContextPrefix {

  /** Separates the segments, as in the specification's own example. */
  public static final String SEPARATOR = " › ";

  /** The marker {@code ChunkLocationResolver} puts in front of a heading path in a Fundort. */
  private static final String SECTION_LOCATION_MARKER = "Abschn. ";

  /** A Markdown heading marker opening a chunk text, with the indentation ATX permits. */
  private static final Pattern LEADING_HEADING_MARKER =
      Pattern.compile("^[ \\t]{0,3}#{1,6}[ \\t]+");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Length of a stamp in hex characters; a 128-bit prefix of the digest, collision-free enough. */
  private static final int STAMP_LENGTH = 32;

  private ChunkContextPrefix() {}

  /**
   * The ingest's own prefix title (ingestion-pipelines.md, Querschnittsregel (b)): a file name is
   * humanized by {@link ChunkContextTitle}; a synthetic name is the declared title verbatim behind
   * its hierarchy path, and {@code null} without a title - a URL fallback would share a prefix.
   */
  public static String ingestTitle(
      boolean syntheticName, String fileName, String declaredTitle, SourceDocumentContext context) {
    if (!syntheticName) {
      return ChunkContextTitle.deriveTitle(fileName);
    }
    if (declaredTitle == null) {
      return null;
    }
    if (context == null || context.hierarchyPath() == null || context.hierarchyPath().isBlank()) {
      return declaredTitle;
    }
    return context.hierarchyPath() + SourceDocumentContext.HIERARCHY_SEPARATOR + declaredTitle;
  }

  /** Whether a document gets a prefix at all: exactly when the ingest found a title for it. */
  public static boolean eligible(String ingestTitle) {
    return ingestTitle != null;
  }

  /** The single-chunk rule's input: a document counts as split from two chunks on. */
  public static boolean documentWasSplit(int chunkCount) {
    return chunkCount >= 2;
  }

  /**
   * The prefix of one chunk, or {@code null} when this chunk gets none.
   *
   * @param eligible the ingest's own decision whether this document type gets a prefix at all - an
   *     RSS entry without a headline never does, and the Nachlauf must honour it rather than guess
   * @param documentWasSplit a single-chunk document carries its whole text and gets no title in
   *     front of it; a prefix-effective value is not in that text, so it earns a prefix regardless
   * @param location the chunk's Fundort, read for its Strukturkontext by {@link
   *     #structureContextFrom}
   */
  public static String forChunk(
      boolean eligible,
      boolean documentWasSplit,
      String title,
      List<String> values,
      Object location,
      String chunkText) {
    if (!eligible || (!documentWasSplit && (values == null || values.isEmpty()))) {
      return null;
    }
    return build(title, values, structureContextFrom(title, location, chunkText));
  }

  /**
   * The prefix from its three segment groups, or {@code null} when no segment carries anything.
   * {@link #forChunk} is the entry point; this one exists for the parts a caller already has.
   */
  public static String build(String title, List<String> metadataValues, String structureContext) {
    List<String> segments = new ArrayList<>();
    addIfPresent(segments, title);
    if (metadataValues != null) {
      metadataValues.forEach(value -> addIfPresent(segments, value));
    }
    addIfPresent(segments, structureContext);
    return segments.isEmpty() ? null : String.join(SEPARATOR, segments);
  }

  /**
   * The title a document's prefix carries, computed the same way wherever it is needed: none at all
   * when the ingest decided this document type gets no prefix, otherwise the Kernfeld Titel and,
   * where none was extracted, the title the ingest itself used ({@code documents
   * .context_prefix_title} - a Confluence hierarchy path, a humanised file name, an RSS headline).
   * Reading the ingest's own choice back is what lets the Nachlauf reproduce it rather than
   * re-derive something else from the file name.
   */
  public static String titleAtRest(boolean eligible, String coreTitle, String ingestTitle) {
    if (!eligible) {
      return null;
    }
    return coreTitle != null && !coreTitle.isBlank() ? coreTitle : ingestTitle;
  }

  /**
   * The fingerprint of the document-level prefix parts - title and prefix-effective values, without
   * the per-chunk Strukturkontext, which cannot change without re-chunking or a title change. Two
   * documents whose stamps differ carry a different prefix; a document whose stored stamp still
   * matches its current one needs no re-embedding.
   */
  public static String stampOf(String title, List<String> values) {
    StringBuilder source = new StringBuilder(title == null ? "" : title);
    if (values != null) {
      values.forEach(value -> source.append('\0').append(value));
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(source.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, STAMP_LENGTH);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }

  /**
   * Puts the prefix {@link #forChunk} decides for {@code chunk} onto its embedding and full-text
   * input ({@code getFormattedContent(EMBED)}), reading the Strukturkontext from the chunk's own
   * Fundort. The stored text stays untouched, no metadata key ever reaches that input, and a chunk
   * without a prefix gets its text byte-identically. Both writing paths go through here.
   */
  public static void applyTo(
      Document chunk,
      boolean eligible,
      boolean documentWasSplit,
      String title,
      List<String> values) {
    String prefix =
        forChunk(
            eligible,
            documentWasSplit,
            title,
            values,
            chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY),
            chunk.getText());
    chunk.setContentFormatter(
        prefix == null
            ? (document, mode) -> document.getText()
            : (document, mode) -> format(prefix, document.getText()));
  }

  /**
   * The embedding and full-text input of a chunk: its prefix in brackets, a blank line, the chunk
   * text. Unchanged from the title-only prefix of #933/#940, so a document whose prefix did not
   * change keeps a byte-identical input.
   */
  public static String format(String prefix, String chunkText) {
    return "[" + prefix + "]\n\n" + chunkText;
  }

  /**
   * The Strukturkontext segment derived from a chunk's Fundort: the heading path of a section, with
   * the {@code "Abschn. "} marker and every leading heading equal to {@code title} (ignoring case
   * and whitespace) stripped. It is {@code null} for a page, slide or row Fundort, which names no
   * content, when nothing but the title remains, and when the chunk text already opens with the
   * path or the stripped remainder - as plain text, behind a Markdown heading marker, or as the
   * last of the Markdown heading lines it opens with.
   */
  public static String structureContextFrom(String title, Object location, String chunkText) {
    if (!(location instanceof String text) || !text.startsWith(SECTION_LOCATION_MARKER)) {
      return null;
    }
    String path = text.substring(SECTION_LOCATION_MARKER.length()).trim();
    List<String> headings = List.of(path.split(SEPARATOR.strip(), -1));
    String normalizedTitle = normalized(title);
    int first = 0;
    while (first < headings.size() && normalized(headings.get(first)).equals(normalizedTitle)) {
      first++;
    }
    String context =
        String.join(
            SEPARATOR,
            headings.subList(first, headings.size()).stream().map(String::strip).toList());
    if (context.isEmpty() || opensWith(chunkText, path) || opensWith(chunkText, context)) {
      return null;
    }
    return context;
  }

  private static boolean opensWith(String chunkText, String context) {
    if (chunkText == null) {
      return false;
    }
    if (chunkText.startsWith(context)
        || LEADING_HEADING_MARKER.matcher(chunkText).replaceFirst("").startsWith(context)) {
      return true;
    }
    List<String> segments = List.of(context.split(SEPARATOR.strip(), -1));
    List<String> leading = leadingHeadingTitles(chunkText);
    if (leading.size() < segments.size()) {
      return false;
    }
    List<String> tail = leading.subList(leading.size() - segments.size(), leading.size());
    for (int i = 0; i < segments.size(); i++) {
      if (!normalized(tail.get(i)).equals(normalized(segments.get(i)))) {
        return false;
      }
    }
    return true;
  }

  /** The titles of the Markdown heading lines a chunk text opens with, blank lines skipped. */
  private static List<String> leadingHeadingTitles(String chunkText) {
    List<String> titles = new ArrayList<>();
    for (String line : chunkText.split("\n", -1)) {
      if (line.isBlank()) {
        continue;
      }
      Matcher heading = MarkdownHeading.LINE.matcher(line.stripTrailing());
      if (!heading.matches()) {
        break;
      }
      titles.add(heading.group(MarkdownHeading.TITLE_GROUP));
    }
    return titles;
  }

  private static String normalized(String segment) {
    return segment == null
        ? ""
        : WHITESPACE.matcher(segment.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
  }

  private static void addIfPresent(List<String> segments, String segment) {
    if (segment != null && !segment.isBlank()) {
      segments.add(segment.trim());
    }
  }
}
