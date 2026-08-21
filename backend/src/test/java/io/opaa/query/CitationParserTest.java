package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.query.CitationParser.ParsedCitation;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationParserTest {

  private final CitationParser parser = new CitationParser();

  @Test
  void extractsSingleCitation() {
    String answer = "The project uses Spring Boot 【source: abc-123#0 | readme.md】 for its backend.";

    List<ParsedCitation> citations = parser.extractCitations(answer);

    assertThat(citations).containsExactly(new ParsedCitation("abc-123", 0, "readme.md"));
  }

  @Test
  void extractsMultipleCitations() {
    String answer =
        "Module A handles indexing 【source: id-1#0 | indexing.md】 "
            + "while module B handles queries 【source: id-2#3 | query.pdf】.";

    List<ParsedCitation> citations = parser.extractCitations(answer);

    assertThat(citations)
        .extracting(ParsedCitation::documentId, ParsedCitation::chunkIndex)
        .containsExactly(tuple("id-1", 0), tuple("id-2", 3));
  }

  @Test
  void keepsRepeatedIdenticalCitationsAsSeparateMarkers() {
    String answer =
        "First mention 【source: id-1#0 | readme.md】 and again 【source: id-1#0 | readme.md】.";

    List<ParsedCitation> citations = parser.extractCitations(answer);

    assertThat(citations)
        .containsExactly(
            new ParsedCitation("id-1", 0, "readme.md"), new ParsedCitation("id-1", 0, "readme.md"));
  }

  @Test
  void keepsSameDocumentDifferentChunksAsSeparateMarkers() {
    String answer = "First 【source: id-1#0 | readme.md】 and second 【source: id-1#2 | readme.md】.";

    List<ParsedCitation> citations = parser.extractCitations(answer);

    assertThat(citations)
        .containsExactly(
            new ParsedCitation("id-1", 0, "readme.md"), new ParsedCitation("id-1", 2, "readme.md"));
  }

  @Test
  void returnsEmptyListForNoCitations() {
    assertThat(parser.extractCitations("No citations here.")).isEmpty();
  }

  @Test
  void returnsEmptyListForNullInput() {
    assertThat(parser.extractCitations(null)).isEmpty();
  }

  @Test
  void returnsEmptyListForEmptyInput() {
    assertThat(parser.extractCitations("")).isEmpty();
  }

  @Test
  void handlesUuidDocumentIds() {
    String answer = "Info from 【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#5 | report.pdf】.";

    List<ParsedCitation> citations = parser.extractCitations(answer);

    assertThat(citations)
        .containsExactly(
            new ParsedCitation("3fa85f64-5717-4562-b3fc-2c963f66afa6", 5, "report.pdf"));
  }

  @Test
  void handlesFileNamesWithSpacesAndSpecialChars() {
    String answer = "Info 【source: id-1#0 | my document (v2).pdf】.";

    List<ParsedCitation> citations = parser.extractCitations(answer);

    assertThat(citations).containsExactly(new ParsedCitation("id-1", 0, "my document (v2).pdf"));
  }
}
