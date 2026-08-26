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
   * #697 review, finding 3: a model correcting the casing of a real file name (e.g. "Readme.md" for
   * the indexed "readme.md") must not turn a genuine citation into a false-invalid verdict.
   */
  @Test
  void validCitationWithDifferentCapitalisationOfTheSameFileName() {
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "README.md"));
    List<Document> retrievedChunks = List.of(chunk("doc-1", 0, "readme.md"));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "README.md", true));
  }

  /**
   * #697 review, finding 3: the same visible file name can reach the retrieved chunk and the
   * citation in different Unicode normal forms - e.g. an "ü" as a precomposed NFC code point in one
   * and as a base letter plus a combining diaeresis (NFD) in the other, most commonly from a macOS
   * upload. Both render identically and must compare equal.
   */
  @Test
  void validCitationWhereFileNameDiffersOnlyInUnicodeNormalForm() {
    String nfcName = java.text.Normalizer.normalize("Verfügung.pdf", java.text.Normalizer.Form.NFC);
    String nfdName = java.text.Normalizer.normalize("Verfügung.pdf", java.text.Normalizer.Form.NFD);
    // Sanity check: the two literals actually differ at the byte level, otherwise this test would
    // pass even without normalisation and prove nothing.
    assertThat(nfcName).isNotEqualTo(nfdName);

    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, nfcName));
    List<Document> retrievedChunks = List.of(chunk("doc-1", 0, nfdName));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, nfcName, true));
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

  private static Document chunkWithText(
      String documentId, int chunkIndex, String fileName, String text) {
    return Document.builder()
        .text(text)
        .metadata(
            Map.of(
                "document_id", documentId,
                "chunk_index", String.valueOf(chunkIndex),
                "file_name", fileName))
        .build();
  }

  /**
   * #937 regression: the Drehbuch-Frage-1 case - a fee retrieval-valid citation whose cited chunk
   * does not actually contain the amount the answer names must be flagged invalid, even though it
   * would pass the pre-#937 retrieval-only check.
   */
  @Test
  void flagsARetrievalValidCitationWhoseCitedChunkDoesNotContainTheStatedAmount() {
    String chunkText =
        "Die Gebühr für einen Personalausweis beträgt 27,20 €. Bei Express-Bearbeitung fallen"
            + " zusätzlich 44,20 € an. Kinder unter 12 Jahren zahlen 12 €.";
    String answer = "Die Gebühr beträgt 25,70 € 【source: doc-1#0 | 001_personalausweis.md】.";
    List<ParsedCitation> citations =
        List.of(new ParsedCitation("doc-1", 0, "001_personalausweis.md"));
    List<Document> retrievedChunks =
        List.of(chunkWithText("doc-1", 0, "001_personalausweis.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result)
        .containsExactly(new ValidatedCitation("doc-1", 0, "001_personalausweis.md", false));
  }

  /** #937 regression: the same case's positive counterpart - the amount actually in the chunk. */
  @Test
  void keepsARetrievalValidCitationWhoseCitedChunkContainsTheStatedAmount() {
    String chunkText = "Die Gebühr für einen Personalausweis beträgt 27,20 €.";
    String answer = "Die Gebühr beträgt 27,20 € 【source: doc-1#0 | 001_personalausweis.md】.";
    List<ParsedCitation> citations =
        List.of(new ParsedCitation("doc-1", 0, "001_personalausweis.md"));
    List<Document> retrievedChunks =
        List.of(chunkWithText("doc-1", 0, "001_personalausweis.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result)
        .containsExactly(new ValidatedCitation("doc-1", 0, "001_personalausweis.md", true));
  }

  /**
   * #937 conservativity contract at the {@link CitationValidator} layer: a citation whose statement
   * carries no extractable hard fact stays at the retrieval-based verdict, unaffected by the
   * content check.
   */
  @Test
  void keepsARetrievalValidCitationWhenTheStatementHasNoExtractableFact() {
    String answer = "Das Verfahren ist unkompliziert 【source: doc-1#0 | readme.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "readme.md"));
    List<Document> retrievedChunks =
        List.of(chunkWithText("doc-1", 0, "readme.md", "Völlig unabhängiger Text ohne Zahlen."));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "readme.md", true));
  }

  /**
   * #937: the 2-arg overload (no answer text) never runs the content check - a caller with no
   * answer text available keeps exactly the pre-#937 retrieval-only behaviour.
   */
  @Test
  void twoArgOverloadSkipsTheContentCheckEvenForAnUnsupportedAmount() {
    List<ParsedCitation> citations =
        List.of(new ParsedCitation("doc-1", 0, "001_personalausweis.md"));
    List<Document> retrievedChunks =
        List.of(chunkWithText("doc-1", 0, "001_personalausweis.md", "Die Gebühr beträgt 27,20 €."));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks);

    assertThat(result)
        .containsExactly(new ValidatedCitation("doc-1", 0, "001_personalausweis.md", true));
  }
}
