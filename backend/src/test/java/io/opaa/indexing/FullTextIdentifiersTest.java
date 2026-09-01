package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The curated pattern list of the identifier protection (#1048, docs/features/hybrid-retrieval.md,
 * "Die deutschen Besonderheiten"). The load-bearing property this class pins is that two
 * neighbouring identifiers never collapse into one lexeme - "§ 34" and "§ 35" are the
 * specification's own example, and a chain that loses that distinction makes the whole lexical path
 * worthless for its main purpose.
 */
class FullTextIdentifiersTest {

  @Test
  void paragraphReferencesStayDistinguishable() {
    List<String> outerArea =
        FullTextIdentifiers.extract("Vorhaben im Außenbereich nach § 35 BauGB");
    List<String> innerArea =
        FullTextIdentifiers.extract("Vorhaben im Innenbereich nach § 34 BauGB");

    assertThat(outerArea).isNotEmpty();
    assertThat(innerArea).isNotEmpty();
    assertThat(outerArea).doesNotContainAnyElementsOf(innerArea);
  }

  /**
   * The bare form is always emitted next to the specific one: a question naming only "§ 35" must
   * still reach a chunk that writes "§ 35 BauGB", and vice versa.
   */
  @Test
  void aParagraphWithALawAbbreviationAlsoYieldsItsBareForm() {
    assertThat(FullTextIdentifiers.extract("§ 35 BauGB"))
        .containsExactlyInAnyOrderElementsOf(List.of("xpar35", "xpar35baugb"));
    assertThat(FullTextIdentifiers.extract("§ 35")).containsExactly("xpar35");
  }

  @Test
  void anAbsatzNarrowsTheLexemeWithoutLosingTheBaseParagraph() {
    assertThat(FullTextIdentifiers.extract("§ 3 Abs. 2 VGS"))
        .containsExactlyInAnyOrderElementsOf(
            List.of("xpar3", "xpar3abs2", "xpar3vgs", "xpar3abs2vgs"));
  }

  @Test
  void paragraphSpellingVariantsProduceTheSameLexeme() {
    assertThat(FullTextIdentifiers.extract("§34")).containsExactly("xpar34");
    assertThat(FullTextIdentifiers.extract("§§ 34")).containsExactly("xpar34");
    assertThat(FullTextIdentifiers.extract("§ 34a")).containsExactly("xpar34a");
  }

  /**
   * A capitalized ordinary word after a paragraph is not a law abbreviation - otherwise every
   * sentence continuing after the reference would produce a lexeme nobody ever searches for.
   */
  @Test
  void anOrdinaryCapitalizedWordIsNotReadAsALawAbbreviation() {
    assertThat(FullTextIdentifiers.extract("§ 3 Satzung")).containsExactly("xpar3");
  }

  @Test
  void courtStyleFileNumbersSurviveAsOneLexeme() {
    assertThat(FullTextIdentifiers.extract("Urteil im Verfahren 4 K 1023/24.NW"))
        .containsExactly("xakz4k102324nw");
    assertThat(FullTextIdentifiers.extract("Verfahren 4 K 1024/24.NW"))
        .doesNotContain("xakz4k102324nw");
  }

  @Test
  void keywordLedFileNumbersAndOrdinanceNumbersAreRecognized() {
    assertThat(FullTextIdentifiers.extract("Az. 12/2024")).contains("xakz122024");
    assertThat(FullTextIdentifiers.extract("Drucksache 19/1234")).contains("xnr191234");
    assertThat(FullTextIdentifiers.extract("Erlass Nr. 12/2024")).contains("xnr122024");
  }

  /** Two ordinance numbers differing in one digit must not share a lexeme. */
  @Test
  void ordinanceNumbersOfNeighbouringYearsStayApart() {
    assertThat(FullTextIdentifiers.extract("Az. 12/2024"))
        .doesNotContainAnyElementsOf(FullTextIdentifiers.extract("Az. 12/2023"));
  }

  /**
   * A list item is not an identifier: without a separator in the number, "Nr. 5" would flood every
   * chunk with lexemes that mean nothing.
   */
  @Test
  void aPlainListNumberProducesNoLexeme() {
    assertThat(FullTextIdentifiers.extract("Nr. 5 der Anlage")).isEmpty();
  }

  @Test
  void ordinaryProseProducesNothing() {
    assertThat(FullTextIdentifiers.extract("Die Gebührenbefreiung wegen Bedürftigkeit")).isEmpty();
    assertThat(FullTextIdentifiers.extract("")).isEmpty();
    assertThat(FullTextIdentifiers.extract(null)).isEmpty();
  }

  /** Every lexeme is lowercase ASCII alphanumeric - the property that makes it tsquery-safe. */
  @Test
  void everyLexemeIsAsciiAlphanumeric() {
    List<String> lexemes =
        FullTextIdentifiers.extract(
            "§ 35 BauGB, § 3 Abs. 2 VGS, Az. 12/2024, Drucksache 19/1234, 4 K 1023/24.NW");

    assertThat(lexemes).isNotEmpty();
    assertThat(lexemes).allMatch(lexeme -> lexeme.matches("[a-z0-9]+"));
  }

  @Test
  void theLexemeCountIsBounded() {
    StringBuilder manyIdentifiers = new StringBuilder();
    for (int i = 0; i < FullTextIdentifiers.MAX_LEXEMES * 2; i++) {
      manyIdentifiers.append("§ ").append(i).append(" ");
    }

    assertThat(FullTextIdentifiers.extract(manyIdentifiers.toString()))
        .hasSize(FullTextIdentifiers.MAX_LEXEMES);
  }
}
