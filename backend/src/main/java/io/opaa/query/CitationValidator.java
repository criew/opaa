package io.opaa.query;

import java.util.HashMap;
import java.util.List;
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
   * {@code chunk_index} metadata defaults to index {@code 0}, matching the default {@link
   * AnswerGenerationService#formatChunks} already applies when it writes the citation instructions
   * the model copies from.
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
          .put(chunkIndex, fileName);
    }

    return citations.stream()
        .map(
            citation -> {
              Map<Integer, String> sections = sectionsByDocument.get(citation.documentId());
              boolean valid =
                  sections != null
                      && citation.fileName().equals(sections.get(citation.chunkIndex()));
              return new ValidatedCitation(
                  citation.documentId(), citation.chunkIndex(), citation.fileName(), valid);
            })
        .toList();
  }

  private int parseChunkIndex(Object rawChunkIndex) {
    try {
      return Integer.parseInt(rawChunkIndex.toString());
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
