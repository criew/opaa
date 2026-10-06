package io.opaa.query.citation;

import io.opaa.chat.CitationMarker;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;

/**
 * Brings the citation markers of a generated answer into the syntax of {@link
 * CitationMarker#PATTERN} before anything evaluates, stores or shows the answer.
 *
 * <p>Brackets the model put around nothing but markers are dropped first ({@link
 * CitationMarkers#unwrapBracketed}); the markers stay.
 *
 * <p>A marker that names a file instead of a document id ({@code 【source: a.pdf#2 | a.pdf】}) is
 * rewritten when exactly one document among {@code chunks} carries that file name and that
 * document's chunk with that index is among them too. A file name without extension looks like a
 * document id; it is resolved the same way unless it is the id of a document among {@code chunks}.
 * Every other malformed marker is removed: it resolves to nothing, would be shown raw and, left in
 * the conversation window, imitated by the next turn.
 *
 * <p>A well-formed marker that resolves to no file name stays untouched whatever it points at -
 * judging it is {@link CitationValidator}'s job.
 */
public final class CitationMarkerRepair {

  /**
   * The body of a marker that names a file: {@code <file>#<chunk>}, optionally {@code | <file>}.
   */
  private static final Pattern FILE_REFERENCE =
      Pattern.compile(
          "【source:\\s*(?<file>[^|】]+?)\\s*#\\s*(?<chunk>\\d+)\\s*(?:\\|\\s*(?<label>[^】]+?)\\s*)?】");

  private CitationMarkerRepair() {}

  /**
   * {@code answer} with every malformed marker rewritten or removed; {@code answer} itself when it
   * has none. {@code chunks} are the chunks handed to the model for this answer.
   */
  public static String repair(String answer, List<Document> chunks) {
    if (answer == null || answer.isEmpty()) {
      return answer;
    }
    String unwrapped = CitationMarkers.unwrapBracketed(answer);
    Matcher matcher = CitationMarker.CLAIMED_PATTERN.matcher(unwrapped);
    StringBuilder result = new StringBuilder(unwrapped.length());
    boolean rewritten = false;
    while (matcher.find()) {
      String marker = matcher.group();
      Optional<String> canonical =
          namesARetrievedDocument(marker, chunks) ? Optional.empty() : resolve(marker, chunks);
      matcher.appendReplacement(result, Matcher.quoteReplacement(canonical.orElse(marker)));
      rewritten |= canonical.isPresent();
    }
    String text = rewritten ? matcher.appendTail(result).toString() : unwrapped;
    return CitationMarkers.stripMalformed(text);
  }

  private static boolean namesARetrievedDocument(String marker, List<Document> chunks) {
    if (!CitationMarker.isWellFormed(marker)) {
      return false;
    }
    String documentId = CitationMarker.parse(marker).getFirst().documentId();
    return chunks.stream().anyMatch(chunk -> documentIdOf(chunk).equals(documentId));
  }

  private static Optional<String> resolve(String marker, List<Document> chunks) {
    Matcher reference = FILE_REFERENCE.matcher(marker);
    if (!reference.matches()) {
      return Optional.empty();
    }
    String fileName = CitationValidator.normalizeFileName(reference.group("file"));
    String label = reference.group("label");
    if (label != null && !CitationValidator.normalizeFileName(label).equals(fileName)) {
      return Optional.empty();
    }
    List<Document> sameFile =
        chunks.stream()
            .filter(
                chunk -> CitationValidator.normalizeFileName(fileNameOf(chunk)).equals(fileName))
            .toList();
    List<String> documentIds =
        sameFile.stream().map(CitationMarkerRepair::documentIdOf).distinct().toList();
    if (documentIds.size() != 1 || documentIds.getFirst().isBlank()) {
      return Optional.empty();
    }
    int chunkIndex = CitationValidator.parseChunkIndex(reference.group("chunk"));
    return sameFile.stream()
        .filter(chunk -> CitationValidator.parseChunkIndex(chunkIndexOf(chunk)) == chunkIndex)
        .findFirst()
        .map(chunk -> CitationMarker.render(documentIdOf(chunk), chunkIndex, fileNameOf(chunk)));
  }

  private static String fileNameOf(Document chunk) {
    return chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
  }

  private static String documentIdOf(Document chunk) {
    return chunk.getMetadata().getOrDefault("document_id", "").toString();
  }

  private static Object chunkIndexOf(Document chunk) {
    return chunk.getMetadata().getOrDefault("chunk_index", "0");
  }
}
