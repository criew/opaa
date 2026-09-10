package io.opaa.query.citation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Deterministic citation validation (docs/handbuch/suche.md Abschnitt 7): checks every citation a
 * model placed in an answer against the chunks actually retrieved for that answer - no second model
 * call, no LLM judgment. A citation is valid only when its document id, section (chunk index) and
 * file name all agree with one and the same retrieved chunk, so a merely well-shaped citation that
 * points at nothing this answer used is detectable rather than trusted.
 *
 * <p>A citation that passes that check is additionally held against {@link CitationFactChecker}.
 * That second check can only push a citation from valid to invalid, never the other way round.
 */
@Service
public class CitationValidator {

  /** One citation together with the verdict {@link #validate} reached for it. */
  public record ValidatedCitation(
      String documentId, int chunkIndex, String fileName, boolean valid) {}

  /**
   * Validates {@code citations} against {@code retrievedChunks} - the exact set handed to the
   * answer model, never a broader one, because a citation must be grounded in what <em>this</em>
   * answer used. A chunk without {@code chunk_index} defaults to {@code 0}, the same default the
   * citation instructions use. The file name comparison is Unicode-normalised (NFC) and
   * case-insensitive; no other leniency.
   *
   * <p>The content check ({@link CitationFactChecker}) then compares the statement preceding the
   * marker against every retrieved chunk of the cited <b>document</b>, not only the chunk the
   * marker names, and can only tighten the retrieval-based verdict.
   */
  public List<ValidatedCitation> validate(
      List<CitationParser.ParsedCitation> citations,
      List<Document> retrievedChunks,
      String answer) {
    Map<String, Map<Integer, String>> sectionsByDocument = new HashMap<>();
    Map<String, Map<Integer, Document>> chunksByDocument = new HashMap<>();
    for (Document chunk : retrievedChunks) {
      String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
      String fileName = chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
      int chunkIndex = parseChunkIndex(chunk.getMetadata().getOrDefault("chunk_index", "0"));
      sectionsByDocument
          .computeIfAbsent(documentId, id -> new HashMap<>())
          .put(chunkIndex, normalize(fileName));
      chunksByDocument.computeIfAbsent(documentId, id -> new HashMap<>()).put(chunkIndex, chunk);
    }

    List<Integer> markerStarts = citationMarkerStarts(answer);
    List<ValidatedCitation> result = new ArrayList<>(citations.size());
    for (int i = 0; i < citations.size(); i++) {
      CitationParser.ParsedCitation citation = citations.get(i);
      Map<Integer, String> sections = sectionsByDocument.get(citation.documentId());
      boolean retrievalValid =
          sections != null
              && normalize(citation.fileName()).equals(sections.get(citation.chunkIndex()));
      boolean valid =
          retrievalValid && contentPlausible(citation, chunksByDocument, answer, markerStarts, i);
      result.add(
          new ValidatedCitation(
              citation.documentId(), citation.chunkIndex(), citation.fileName(), valid));
    }
    return result;
  }

  // A statement naming an approximation or a computed sum is not a fabrication, so the content
  // check skips it rather than flagging a figure the model rounded or summed correctly.
  private static final Pattern APPROXIMATION_OR_SUM =
      Pattern.compile(
          "\\b(rund|etwa|ca\\.|circa|knapp|insgesamt|zusammen)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * The content check for one already retrieval-valid citation. Falls back to {@code true} - never
   * flag - whenever the marker position or the cited document's chunks cannot be resolved.
   */
  private boolean contentPlausible(
      CitationParser.ParsedCitation citation,
      Map<String, Map<Integer, Document>> chunksByDocument,
      String answer,
      List<Integer> markerStarts,
      int citationIndex) {
    if (citationIndex >= markerStarts.size()) {
      return true;
    }
    Map<Integer, Document> documentChunks = chunksByDocument.get(citation.documentId());
    if (documentChunks == null || documentChunks.isEmpty()) {
      return true;
    }
    String statement = statementBefore(answer, markerStarts.get(citationIndex));
    if (APPROXIMATION_OR_SUM.matcher(statement).find()) {
      return true;
    }
    String combinedChunkText =
        documentChunks.values().stream()
            .map(Document::getText)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    return CitationFactChecker.isNearestFactSupportedByChunk(statement, combinedChunkText);
  }

  /**
   * The text of {@code answer} from the previous sentence boundary up to {@code markerStart} - the
   * pragmatic "statement" a citation marker is taken to belong to. A sentence boundary is {@code
   * !}, {@code ?}, a newline, or a {@code .} that is <b>not</b> sitting between two digits - the
   * exemption keeps a thousands separator or a date ({@code "1.234,50"}, {@code "01.01.2027"}) from
   * truncating the very fact this check compares.
   */
  private String statementBefore(String answer, int markerStart) {
    int boundary = -1;
    for (int i = markerStart - 1; i >= 0; i--) {
      char c = answer.charAt(i);
      if (c == '!' || c == '?' || c == '\n') {
        boundary = i;
        break;
      }
      if (c == '.' && !isDigitAdjacentDot(answer, i)) {
        boundary = i;
        break;
      }
    }
    return answer.substring(boundary + 1, markerStart).trim();
  }

  private boolean isDigitAdjacentDot(String text, int dotIndex) {
    boolean precededByDigit = dotIndex > 0 && Character.isDigit(text.charAt(dotIndex - 1));
    boolean followedByDigit =
        dotIndex + 1 < text.length() && Character.isDigit(text.charAt(dotIndex + 1));
    return precededByDigit && followedByDigit;
  }

  /**
   * The start offset of every citation marker in {@code answer}, in appearance order - the same
   * order {@link CitationParser#extractCitations} returns its {@code ParsedCitation}s in, since
   * both are produced by the same pattern over the same text. Empty when {@code answer} does not
   * carry the literal marker text.
   */
  private List<Integer> citationMarkerStarts(String answer) {
    List<Integer> starts = new ArrayList<>();
    if (answer == null || answer.isEmpty()) {
      return starts;
    }
    Matcher matcher = CitationParser.CITATION_PATTERN.matcher(answer);
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    return starts;
  }

  /**
   * Normalises a file name for the comparison in {@link #validate}: Unicode NFC plus lower-casing,
   * so a differing normal form or capitalisation cannot turn a genuine citation into a
   * false-invalid verdict.
   */
  private String normalize(String fileName) {
    return Normalizer.normalize(fileName, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }

  private int parseChunkIndex(Object rawChunkIndex) {
    try {
      return Integer.parseInt(rawChunkIndex.toString());
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
