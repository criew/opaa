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

  /**
   * #939 review, finding 1: a thousands-separator dot sits between two digits and must not end the
   * statement early - a truncated statement ("234,50 €" instead of "1.234,50 €") would compare the
   * wrong value.
   */
  @Test
  void keepsValidAcrossAThousandsSeparatorInTheStatedAmount() {
    String chunkText = "Für diesen Fall betragen die Kosten 1.234,50 € insgesamt.";
    String answer = "Die Kosten betragen 1.234,50 € 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", true));
  }

  /** #939 review, finding 1: a round-thousand amount ("1.000 €") must not be truncated either. */
  @Test
  void keepsValidForARoundThousandAmount() {
    String chunkText = "Der Zuschuss beträgt 1.000 €.";
    String answer = "Der Zuschuss beträgt 1.000 € 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", true));
  }

  /**
   * #939 review, finding 1: the statement-boundary bug also broke date extraction end-to-end, since
   * every numeric date's dots ended the statement right after its own last digit group - this is
   * the end-to-end date check the fix restores.
   */
  @Test
  void keepsValidForADateMatchingTheCitedChunk() {
    String chunkText = "Die Regel gilt ab dem 01.01.2027.";
    String answer = "Die Regel gilt ab dem 01.01.2027 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", true));
  }

  @Test
  void flagsADateThatContradictsTheCitedChunk() {
    String chunkText = "Die Regel gilt ab dem 01.01.2027.";
    String answer = "Die Regel gilt ab dem 01.03.2027 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", false));
  }

  /**
   * #939 review, finding 3: a sentence enumerating two amounts from two different chunks of the
   * <em>same</em> document, each named right before its own marker, must validate against the
   * nearest fact and the document's full chunk set - not the whole sentence against a single chunk.
   */
  @Test
  void keepsValidForAnEnumerationAcrossTwoChunksOfTheSameDocument() {
    String answer =
        "Der Ausweis kostet 37,00 €, der Reisepass kostet 70,00 € 【source: doc-1#1 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 1, "a.md"));
    List<Document> retrievedChunks =
        List.of(
            chunkWithText("doc-1", 0, "a.md", "Der Ausweis kostet 37,00 €."),
            chunkWithText("doc-1", 1, "a.md", "Der Reisepass kostet 70,00 €."));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 1, "a.md", true));
  }

  /**
   * #939 review, finding 2: an amount stated with a currency marker in the answer must still be
   * recognised against the same bare number in a fee table whose column header alone carries the
   * currency.
   */
  @Test
  void keepsValidForAnAmountWithoutACurrencyMarkerInTheCitedChunk() {
    String chunkText = "| Leistung | Gebühr (EUR) |\n| Personalausweis | 37,00 |";
    String answer = "Der Personalausweis kostet 37,00 € 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", true));
  }

  /**
   * #939 review, finding 4: a statement naming an approximation ("rund") skips the content check
   * entirely - a model rounding a real figure is not a fabrication.
   */
  @Test
  void keepsValidWhenTheStatementNamesAnApproximation() {
    String chunkText = "Die Gebühr beträgt 27,20 €.";
    String answer = "Die Gebühr beträgt rund 27 Euro 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", true));
  }

  /**
   * #939 review, finding 4: a statement naming a computed sum ("Zusammen sind das ...") skips the
   * content check entirely - the sum need not appear verbatim in any single chunk.
   */
  @Test
  void keepsValidWhenTheStatementNamesAComputedSum() {
    String chunkText = "Der Ausweis kostet 37,00 €, der Reisepass kostet 70,00 €.";
    String answer = "Zusammen sind das 107,00 € 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", true));
  }

  /** #939 review, finding 2: "Paragraf" (without "h") is an equally correct German spelling. */
  @Test
  void keepsValidForTheParagrafSpellingWithoutH() {
    String chunkText = "Nach Paragraf 3 PAuswG gilt ...";
    String answer = "Grundlage ist § 3 PAuswG 【source: doc-1#0 | a.md】.";
    List<ParsedCitation> citations = List.of(new ParsedCitation("doc-1", 0, "a.md"));
    List<Document> retrievedChunks = List.of(chunkWithText("doc-1", 0, "a.md", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result).containsExactly(new ValidatedCitation("doc-1", 0, "a.md", true));
  }

  /**
   * #939 review, finding 5: two markers in one answer, each citing a different document, must each
   * be checked against their own statement and their own cited document's chunks - this pins down
   * the previously untested assumption that {@code citations.get(i)} lines up with the {@code i}-th
   * marker actually found in {@code answer}.
   */
  @Test
  void validatesMultipleMarkersInOneAnswerIndependentlyAndInOrder() {
    String answer =
        "Der Ausweis kostet 37,00 € 【source: doc-1#0 | a.md】 und der Pass kostet 999,00 €"
            + " 【source: doc-2#0 | b.md】.";
    List<ParsedCitation> citations =
        List.of(new ParsedCitation("doc-1", 0, "a.md"), new ParsedCitation("doc-2", 0, "b.md"));
    List<Document> retrievedChunks =
        List.of(
            chunkWithText("doc-1", 0, "a.md", "Der Ausweis kostet 37,00 €."),
            chunkWithText("doc-2", 0, "b.md", "Der Pass kostet 70,00 €."));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result)
        .containsExactly(
            new ValidatedCitation("doc-1", 0, "a.md", true),
            new ValidatedCitation("doc-2", 0, "b.md", false));
  }

  /**
   * #939 review, finding 6: a Drehbuch-Frage-1-style answer citing the same amount from two
   * different, independently retrieved documents - each marker's own document actually contains the
   * value, so neither citation may be flagged.
   */
  @Test
  void keepsValidWhenTheSameAmountIsDoubleCitedFromTwoDifferentDocuments() {
    String answer =
        "Die Gebühr beträgt 27,20 Euro 【source: doc-1#0 | 001_personalausweis.md】【source:"
            + " doc-2#0 | 01_verwaltungsgebuehrensatzung.pdf】.";
    List<ParsedCitation> citations =
        List.of(
            new ParsedCitation("doc-1", 0, "001_personalausweis.md"),
            new ParsedCitation("doc-2", 0, "01_verwaltungsgebuehrensatzung.pdf"));
    List<Document> retrievedChunks =
        List.of(
            chunkWithText(
                "doc-1",
                0,
                "001_personalausweis.md",
                "Die Gebühr für Personen unter 24 Jahren beträgt 27,20 Euro."),
            chunkWithText(
                "doc-2",
                0,
                "01_verwaltungsgebuehrensatzung.pdf",
                "§ 3 VGS: Gebührenrahmen unter 24 Jahren 27,20 Euro."));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result)
        .containsExactly(
            new ValidatedCitation("doc-1", 0, "001_personalausweis.md", true),
            new ValidatedCitation("doc-2", 0, "01_verwaltungsgebuehrensatzung.pdf", true));
  }

  /**
   * #939 review, finding 6: a Drehbuch-Frage-4-style answer enumerating two fee variants in one
   * sentence, cited once - only the nearer amount ("11,30 Euro") must be checked, and it is present
   * in the cited chunk.
   */
  @Test
  void keepsValidForATwoAmountEnumerationCitedOnce() {
    String chunkText =
        "Die Gebühr beträgt 14,10 Euro bzw. 11,30 Euro bei Zulassung am Tag der"
            + " Online-Reservierung.";
    String answer =
        "Die Gebühr beträgt 14,10 Euro bzw. 11,30 Euro bei Zulassung am Tag der"
            + " Online-Reservierung 【source: doc-1#0 | 008_wunschkennzeichen.txt】.";
    List<ParsedCitation> citations =
        List.of(new ParsedCitation("doc-1", 0, "008_wunschkennzeichen.txt"));
    List<Document> retrievedChunks =
        List.of(chunkWithText("doc-1", 0, "008_wunschkennzeichen.txt", chunkText));

    List<ValidatedCitation> result = validator.validate(citations, retrievedChunks, answer);

    assertThat(result)
        .containsExactly(new ValidatedCitation("doc-1", 0, "008_wunschkennzeichen.txt", true));
  }
}
