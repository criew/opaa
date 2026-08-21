package io.opaa.query;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Deterministic belief validation (#386): checks every citation a model placed in an answer against
 * the chunks actually retrieved for that answer - no second model call, no LLM judgment. A citation
 * is valid only when its document id, section (chunk index) and file name all agree with one and
 * the same retrieved chunk. A model that merely imitates the citation's shape (a fabricated
 * document id, a section that document does not have among the retrieved chunks, or a file name
 * that does not match the id it claims) produces a citation that <em>looks</em> correct but points
 * at nothing this answer was actually grounded in - this class is what turns that from an
 * unenforced request to the model into a checkable fact about the response.
 */
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
    Map<String, Map<Integer, String>> sectionsByDocument = new HashMap<>();
    for (Document chunk : retrievedChunks) {
      String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
      String fileName = chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
      int chunkIndex = parseChunkIndex(chunk.getMetadata().getOrDefault("chunk_index", "0"));
      sectionsByDocument
          .computeIfAbsent(documentId, id -> new HashMap<>())
          .put(chunkIndex, normalize(fileName));
    }

    return citations.stream()
        .map(
            citation -> {
              Map<Integer, String> sections = sectionsByDocument.get(citation.documentId());
              boolean valid =
                  sections != null
                      && normalize(citation.fileName()).equals(sections.get(citation.chunkIndex()));
              return new ValidatedCitation(
                  citation.documentId(), citation.chunkIndex(), citation.fileName(), valid);
            })
        .toList();
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
