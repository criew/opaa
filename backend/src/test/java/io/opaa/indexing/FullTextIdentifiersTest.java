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

  /**
   * The property the whole mechanism depends on: a document writes the file number behind a
   * keyword, a person asks for it bare. A pattern that needs the keyword fires on one side only,
   * and the protection then silently does nothing at all - which is exactly what happened to eight
   * of the ten {@code exact_identifier} golden cases before this test existed.
   */
  @Test
  void theSameFileNumberYieldsTheSameLexemeInAChunkAndInAQuestion() {
    for (String identifier : List.of("BAU-DA-2/2024", "SOZ-DA-1/2023", "KAE-07", "BUE-08")) {
      List<String> asWrittenInADocument =
          FullTextIdentifiers.extract(
              "Diese Dienstanweisung trägt das Aktenzeichen " + identifier + ".");
      List<String> asAskedInAQuestion =
          FullTextIdentifiers.extract("Was regelt die Dienstanweisung " + identifier + "?");

      assertThat(asAskedInAQuestion)
          .as("question form of %s", identifier)
          .isNotEmpty()
          .containsAnyElementsOf(asWrittenInADocument);
    }
  }

  @Test
  void keywordFreeAdministrativeFileNumbersAreRecognized() {
    assertThat(FullTextIdentifiers.extract("Was regelt die Dienstanweisung BAU-DA-2/2024?"))
        .containsExactly("xakzbauda22024");
    assertThat(FullTextIdentifiers.extract("Wofür wird Formular KAE-07 verwendet?"))
        .containsExactly("xakzkae07");
  }

  /** Two administrative file numbers differing in one component must not share a lexeme. */
  @Test
  void neighbouringAdministrativeFileNumbersStayApart() {
    assertThat(FullTextIdentifiers.extract("Dienstanweisung SOZ-DA-1/2023"))
        .doesNotContainAnyElementsOf(FullTextIdentifiers.extract("Dienstanweisung SOZ-DA-1/2024"));
    assertThat(FullTextIdentifiers.extract("Formular KAE-07"))
        .doesNotContainAnyElementsOf(FullTextIdentifiers.extract("Formular KAE-08"));
  }

  /**
   * A keyword followed by ordinary prose is not an identifier. Without this guard "Aktenzeichen der
   * Satzung" produces the lexeme {@code xakzder}, which then sits at weight {@code A} on every
   * prose chunk carrying the same phrase - noise at the top of the ranking, produced by the
   * mechanism meant to sharpen it.
   */
  @Test
  void aKeywordFollowedByProseProducesNoLexeme() {
    assertThat(FullTextIdentifiers.extract("Aktenzeichen der Satzung")).isEmpty();
    assertThat(FullTextIdentifiers.extract("Das Aktenzeichen ist unbekannt.")).isEmpty();
  }

  /** {@code Azubi} is a word, not an {@code Az} with something behind it. */
  @Test
  void aWordBeginningWithTheKeywordIsNotAKeyword() {
    assertThat(FullTextIdentifiers.extract("Azubi")).isEmpty();
    assertThat(FullTextIdentifiers.extract("Die Azubine im Amt")).isEmpty();
  }

  /** An uppercase abbreviation without a digit is a word, not a file number. */
  @Test
  void hyphenatedUppercaseAbbreviationsWithoutADigitProduceNoLexeme() {
    assertThat(FullTextIdentifiers.extract("Die EU-DSGVO gilt.")).isEmpty();
    assertThat(FullTextIdentifiers.extract("IT-SICHERHEIT")).isEmpty();
  }

  /**
   * German administrative texts enumerate behind {@code §§}. Keeping only the first number would
   * lose exactly the reference a question about the second one needs.
   */
  @Test
  void paragraphEnumerationsYieldALexemePerNumber() {
    assertThat(FullTextIdentifiers.extract("§§ 34, 35 BauGB"))
        .containsExactlyInAnyOrderElementsOf(
            List.of("xpar34", "xpar34baugb", "xpar35", "xpar35baugb"));
    assertThat(FullTextIdentifiers.extract("§§ 34 und 35 BauGB")).contains("xpar35baugb");
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

  /**
   * #1130 Befund 1, Querschnittsregel a: an email address survives as one lexeme, symmetric between
   * a chunk's text ("...Kontakt: max.mustermann@example.org...") and a question naming the same
   * address bare - the property {@link #theSameFileNumberYieldsTheSameLexemeInAChunkAndInAQuestion}
   * already pins for file numbers.
   */
  @Test
  void anEmailAddressSurvivesAsOneLexeme() {
    assertThat(FullTextIdentifiers.extract("Kontakt: max.mustermann@example.org"))
        .containsExactly("xmailmaxmustermannexampleorg");
    assertThat(FullTextIdentifiers.extract("Was regelt max.mustermann@example.org?"))
        .containsExactly("xmailmaxmustermannexampleorg");
  }

  /** Two email addresses differing in one local-part component must not share a lexeme. */
  @Test
  void neighbouringEmailAddressesStayApart() {
    assertThat(FullTextIdentifiers.extract("max.mustermann@example.org"))
        .doesNotContainAnyElementsOf(FullTextIdentifiers.extract("erika.musterfrau@example.org"));
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
