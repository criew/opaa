package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.CitationParser.ParsedCitation;
import io.opaa.query.CitationValidator.ValidatedCitation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class CitationValidatorTest {

  private final CitationValidator validator = new CitationValidator();

  private static Document chunk(String documentId, int chunkIndex, String fileName) {
    return Document.builder()
        .text("content")
        .metadata(
            Map.of(
                "document_id", documentId,
                "chunk_index", String.valueOf(chunkIndex),
                "file_name", fileName))
        .build();
  }

  /** #386 acceptance criterion: a citation matching a retrieved chunk exactly is valid. */
  @Test
  void validCitationMatchingARetrievedChunk() {
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "readme.md"));
    List<Document> retrievedChunks = List.of(chunk("doc-1", 0, "readme.md"));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "readme.md", true));
  }

  /**
   * #386 acceptance criterion: a document id that is not among the retrieved chunks is invalid,
   * even when the marker is formally well-formed.
   */
  @Test
  void invalidWhenDocumentIdIsNotAmongTheRetrievedChunks() {
    List<ParsedCitation> citations = List.of(new ParsedCitation("fabricated-id", 0, "readme.md"));
    List<Document> retrievedChunks = List.of(chunk("doc-1", 0, "readme.md"));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result)
        .containsExactly(new ValidatedCitation("fabricated-id", 0, "readme.md", false));
  }

  /**
   * #386 acceptance criterion: a valid document id and section number with a mismatching file name
   * is invalid - a citation with the right id and a wrong name is more misleading than none.
   */
  @Test
  void invalidWhenFileNameDoesNotMatchTheDocumentId() {
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "wrong-name.pdf"));
    List<Document> retrievedChunks = List.of(chunk("doc-1", 0, "readme.md"));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "wrong-name.pdf", false));
  }

  /**
   * #386 acceptance criterion: a section number that belongs to the cited document but was not
   * among the chunks retrieved for this answer is invalid.
   */
  @Test
  void invalidWhenSectionNumberIsOutsideTheRetrievedChunksForThatDocument() {
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 7, "readme.md"));
    List<Document> retrievedChunks = List.of(chunk("doc-1", 0, "readme.md"));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 7, "readme.md", false));
  }

  @Test
  void defaultsMissingChunkIndexMetadataToZero() {
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "readme.md"));
    Document chunkWithoutChunkIndex =
        Document.builder()
            .text("content")
            .metadata(Map.of("document_id", "doc-1", "file_name", "readme.md"))
            .build();

    List<ValidatedCitation> result = validator.validate(citations, List.of(chunkWithoutChunkIndex));

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "readme.md", true));
  }

  @Test
  void validatesEachOccurrenceIndependently() {
    List<ParsedCitation> citations =
        List.of(
            new ParsedCitation("doc-1", 0, "readme.md"),
            new ParsedCitation("doc-1", 9, "readme.md"));
    List<Document> retrievedChunks = List.of(chunk("doc-1", 0, "readme.md"));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result)
        .containsExactly(
            new ValidatedCitation("doc-1", 0, "readme.md", true),
            new ValidatedCitation("doc-1", 9, "readme.md", false));
  }

  @Test
  void returnsEmptyListWhenNoCitationsGiven() {
    assertThat(validator.validate(List.of(), List.of(chunk("doc-1", 0, "readme.md")))).isEmpty();
  }
}
