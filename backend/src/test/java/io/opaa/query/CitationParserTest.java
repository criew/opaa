package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationParserTest {

  private final CitationParser parser = new CitationParser();

  @Test
  void extractsSingleCitation() {
    String answer = "The project uses Spring Boot 【source: abc-123 | readme.md】 for its backend.";

    Set<String> ids = parser.extractCitedDocumentIds(answer);

    assertThat(ids).containsExactly("abc-123");
  }

  @Test
  void extractsMultipleCitations() {
    String answer =
        "Module A handles indexing 【source: id-1 | indexing.md】 "
            + "while module B handles queries 【source: id-2 | query.pdf】.";

    Set<String> ids = parser.extractCitedDocumentIds(answer);

    assertThat(ids).containsExactlyInAnyOrder("id-1", "id-2");
  }

  @Test
  void deduplicatesRepeatedCitations() {
    String answer =
        "First mention 【source: id-1 | readme.md】 and again 【source: id-1 | readme.md】.";

    Set<String> ids = parser.extractCitedDocumentIds(answer);

    assertThat(ids).containsExactly("id-1");
  }

  @Test
  void returnsEmptySetForNoCitations() {
    Set<String> ids = parser.extractCitedDocumentIds("No citations here.");

    assertThat(ids).isEmpty();
  }

  @Test
  void returnsEmptySetForNullInput() {
    assertThat(parser.extractCitedDocumentIds(null)).isEmpty();
  }

  @Test
  void returnsEmptySetForEmptyInput() {
    assertThat(parser.extractCitedDocumentIds("")).isEmpty();
  }

  @Test
  void handlesUuidDocumentIds() {
    String answer = "Info from 【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6 | report.pdf】.";

    Set<String> ids = parser.extractCitedDocumentIds(answer);

    assertThat(ids).containsExactly("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  }

  @Test
  void removeCitationsStripsAllCitationMarkers() {
    String answer =
        "The project 【source: id-1 | readme.md】 uses Spring Boot 【source: id-2 | arch.pdf】.";

    String clean = parser.removeCitations(answer);

    assertThat(clean).isEqualTo("The project  uses Spring Boot .");
  }

  @Test
  void removeCitationsReturnsNullForNull() {
    assertThat(parser.removeCitations(null)).isNull();
  }

  @Test
  void removeCitationsReturnsEmptyForEmpty() {
    assertThat(parser.removeCitations("")).isEmpty();
  }

  @Test
  void handlesFileNamesWithSpacesAndSpecialChars() {
    String answer = "Info 【source: id-1 | my document (v2).pdf】.";

    Set<String> ids = parser.extractCitedDocumentIds(answer);

    assertThat(ids).containsExactly("id-1");
  }
}
