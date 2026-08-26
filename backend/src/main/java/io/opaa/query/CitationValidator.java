package io.opaa.query;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * {@code @Service} (#889, O2): previously wired manually in {@code QueryConfiguration}.
 *
 * <p>Deterministic belief validation (#386): checks every citation a model placed in an answer
 * against the chunks actually retrieved for that answer - no second model call, no LLM judgment. A
 * citation is valid only when its document id, section (chunk index) and file name all agree with
 * one and the same retrieved chunk. A model that merely imitates the citation's shape (a fabricated
 * document id, a section that document does not have among the retrieved chunks, or a file name
 * that does not match the id it claims) produces a citation that <em>looks</em> correct but points
 * at nothing this answer was actually grounded in - this class is what turns that from an
 * unenforced request to the model into a checkable fact about the response.
 *
 * <p>Stufe 1 content check (#937): a citation that passes the check above is additionally checked
 * against {@link CitationFactChecker} - see {@link #validate(List, List, String)}'s Javadoc. A
 * citation can only be pushed from valid to invalid by this second check, never the other way
 * round.
 */
@Service
public class CitationValidator {

  /** One citation together with the verdict {@link #validate} reached for it. */
  public record ValidatedCitation(
      String documentId, int chunkIndex, String fileName, boolean valid) {}

  /**
   * Validates {@code citations} against {@code retrievedChunks} - the exact set handed to the
   * answer model for this answer, never a broader "everything indexed" set (that would defeat the
   * point: a citation must be grounded in what <em>this</em> answer actually used). A chunk with no
   * {@code chunk_index} metadata defaults to index {@code 0} - the same default {@code
   * AnswerGenerationService} falls back to when it writes the citation instructions the model
   * copies from, so a chunk that never carried the metadata still matches the citation the model
   * was told to produce for it.
   *
   * <p>#697 review, finding 3: the file name comparison is Unicode-normalised (NFC) and
   * case-insensitive before matching - a model routinely echoes a file name back with a different
   * capitalisation (a model correcting "readme.md" to "Readme.md") or, for a name that reached the
   * index via a macOS upload, a different Unicode normal form for the same visible characters (an
   * "ü" as a precomposed NFC code point versus a base letter plus combining diaeresis in NFD - both
   * render identically but compare unequal as raw {@code String}s). Neither loosening admits a
   * citation for a genuinely different name: a fabricated name still fails unless it is the same
   * name up to case and normalisation, which is exactly the class of "harmless model rewrite" this
   * validation should not punish. No other leniency is applied - a truncated name, a path prefix or
   * a different extension still invalidates the citation, because those describe an actually
   * different reference, not the same one written differently.
   */
  public List<ValidatedCitation> validate(
      List<CitationParser.ParsedCitation> citations, List<Document> retrievedChunks) {
    return validate(citations, retrievedChunks, "");
  }

  /**
   * Same retrieval-based check as {@link #validate(List, List)}, additionally tightened by a Stufe
   * 1 (#937) content check: for a citation that is otherwise valid, the statement immediately
   * preceding its marker in {@code answer} - the text back to the previous sentence boundary
   * ({@code .}, {@code !}, {@code ?} or a newline), or the start of {@code answer} - is checked for
   * hard facts ({@link CitationFactChecker}) against the text of the exact chunk the citation
   * names. A statement with no extractable fact, or a citation whose marker cannot be located in
   * {@code answer} (e.g. {@code answer} is empty, as {@link #validate(List, List)} passes), is left
   * at the retrieval-based verdict - this check only ever tightens, never loosens, the verdict
   * {@link #validate(List, List)} alone would have reached.
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

  /**
   * The Stufe 1 (#937) content check for one already retrieval-valid citation - see {@link
   * #validate(List, List, String)}'s Javadoc for the statement boundary and the conservative
   * fallback to {@code true} (never flag) whenever the marker position or the cited chunk itself
   * cannot be resolved.
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
    Document chunk =
        chunksByDocument.getOrDefault(citation.documentId(), Map.of()).get(citation.chunkIndex());
    if (chunk == null) {
      return true;
    }
    String statement = statementBefore(answer, markerStarts.get(citationIndex));
    return CitationFactChecker.isSupportedByChunk(statement, chunk.getText());
  }

  /**
   * The text of {@code answer} from the previous sentence boundary ({@code .}, {@code !}, {@code ?}
   * or a newline) up to {@code markerStart} - the pragmatic "statement" a citation marker is taken
   * to belong to (#937). When two citation markers share one sentence, the statement of the later
   * marker also contains the earlier marker's literal text; that marker syntax carries neither a
   * decimal comma nor a thousands separator, so it does not itself produce a spurious fact for
   * {@link CitationFactChecker} to compare.
   */
  private String statementBefore(String answer, int markerStart) {
    int boundary = -1;
    for (int i = markerStart - 1; i >= 0; i--) {
      char c = answer.charAt(i);
      if (c == '.' || c == '!' || c == '?' || c == '\n') {
        boundary = i;
        break;
      }
    }
    return answer.substring(boundary + 1, markerStart).trim();
  }

  /**
   * The start offset of every citation marker in {@code answer}, in appearance order - the same
   * order {@link CitationParser#extractCitations} returns its {@code ParsedCitation}s in, since
   * both are produced by the same pattern over the same text. Empty when {@code answer} does not
   * carry the literal marker text (e.g. {@link #validate(List, List)}'s {@code ""} placeholder).
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
   * Normalises a file name for the comparison in {@link #validate} - Unicode NFC plus lower-casing,
   * so a harmless model rewrite (different normal form, different case) cannot turn a genuine
   * citation into a false-invalid verdict. See {@link #validate}'s Javadoc for exactly what this
   * does and does not forgive.
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
