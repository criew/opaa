package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CitationFactCheckerTest {

  /**
   * #937 regression: Drehbuch-Frage-1's Gebührenfrage cited 001_personalausweis.md (which lists
   * 27,20 €/44,20 €/12 €) for a value of 25,70 € that actually only appears in a different,
   * unretrieved document - the exact case the deterministic content check must catch.
   */
  @Test
  void flagsAnAmountNotPresentInTheCitedChunk() {
    String chunkText =
        "Die Gebühr für einen Personalausweis beträgt 27,20 €, für Personen unter 24 Jahren 22,80"
            + " €. Bei Express-Bearbeitung fallen zusätzlich 44,20 € an. Kinder unter 12 Jahren"
            + " zahlen 12 €.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Die Gebühr beträgt 25,70 €", chunkText);

    assertThat(supported).isFalse();
  }

  /** #937: the same regression's positive counterpart - the amount actually in the chunk. */
  @Test
  void acceptsAnAmountThatIsPresentInTheCitedChunk() {
    String chunkText = "Die Gebühr für einen Personalausweis beträgt 27,20 €.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Die Gebühr beträgt 27,20 €", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void treatsEuroSignAndTheWordEuroAsEquivalent() {
    String chunkText = "Die Gebühr beträgt 27,20 Euro.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Die Gebühr beträgt 27,20 €", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void treatsTheEuroCentSplitNotationAsEquivalentToTheDecimalNotation() {
    String chunkText = "Die Gebühr beträgt 27 Euro 20.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Die Gebühr beträgt 27,20 €", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void normalisesThousandsSeparatorsBeforeComparing() {
    String chunkText = "Der Höchstbetrag liegt bei 1.234,56 €.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Der Höchstbetrag liegt bei 1234,56 €", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void treatsParagraphWithAndWithoutASpaceAsEquivalent() {
    String chunkText = "Siehe § 3 des Gesetzes.";

    boolean supported = CitationFactChecker.isSupportedByChunk("Vgl. §3.", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void treatsTheWordParagraphAsEquivalentToTheSectionSign() {
    String chunkText = "Siehe § 3 des Gesetzes.";

    boolean supported = CitationFactChecker.isSupportedByChunk("Vgl. Paragraph 3.", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void flagsAParagraphReferenceNotPresentInTheCitedChunk() {
    String chunkText = "Siehe § 3 des Gesetzes.";

    boolean supported = CitationFactChecker.isSupportedByChunk("Vgl. § 7.", chunkText);

    assertThat(supported).isFalse();
  }

  @Test
  void matchesADateAcrossOneAndTwoDigitDayAndMonthNotation() {
    String chunkText = "Gültig ab dem 01.02.2026.";

    boolean supported = CitationFactChecker.isSupportedByChunk("Gültig ab 1.2.2026.", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void flagsADateNotPresentInTheCitedChunk() {
    String chunkText = "Gültig ab dem 01.02.2026.";

    boolean supported = CitationFactChecker.isSupportedByChunk("Gültig ab 01.03.2026.", chunkText);

    assertThat(supported).isFalse();
  }

  @Test
  void matchesAHardNumberWithAThousandsSeparator() {
    String chunkText = "Die Bearbeitungsdauer beträgt bis zu 12.500 Tage in Ausnahmefällen.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Bis zu 12500 Tage in Ausnahmefällen.", chunkText);

    assertThat(supported).isTrue();
  }

  /**
   * #937 conservativity contract: a statement with no extractable hard fact must never be flagged,
   * regardless of how unrelated the chunk text is - false positives are worse than false negatives.
   */
  @Test
  void neverFlagsAStatementWithoutAnExtractableFact() {
    String chunkText = "Dies handelt von etwas völlig anderem, ganz ohne jede Zahl.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk(
            "Dieser Satz behauptet etwas, aber ohne belastbare Fakten.", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void doesNotFlagASmallBareNumberWithoutSeparatorOrDecimal() {
    String chunkText = "Die Bearbeitung dauert in der Regel 5 Werktage.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Die Bearbeitung dauert 3 Werktage.", chunkText);

    // A bare small integer is too weak a signal on its own (#937 conservativity contract) - only
    // numbers with a thousands separator or a decimal comma are compared.
    assertThat(supported).isTrue();
  }

  @Test
  void requiresEveryExtractedFactOfAMultiFactStatementToBeSupported() {
    String chunkText = "Die Gebühr beträgt 27,20 € gemäß § 3.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Die Gebühr beträgt 27,20 € gemäß § 9.", chunkText);

    assertThat(supported).isFalse();
  }

  /** #939 review, finding 2: a {@code null} chunk text must never be flagged. */
  @Test
  void neverFlagsWhenChunkTextIsNull() {
    boolean supported = CitationFactChecker.isSupportedByChunk("Die Gebühr beträgt 27,20 €", null);

    assertThat(supported).isTrue();
  }

  /**
   * #939 review, finding 2: a fee table column headed "EUR" typically states the bare number
   * underneath, without repeating the currency marker on every row - a money amount in the
   * statement must still be recognised against such a bare number in the chunk.
   */
  @Test
  void treatsAMoneyAmountAsEquivalentToTheSameBareNumberInAFeeTable() {
    String chunkText = "| Leistung | Gebühr (EUR) |\n| Personalausweis | 37,00 |";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Der Personalausweis kostet 37,00 €", chunkText);

    assertThat(supported).isTrue();
  }

  @Test
  void treatsParagrafSpellingAsEquivalentToTheSectionSign() {
    String chunkText = "Nach Paragraf 3 PAuswG gilt ...";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Grundlage ist § 3 PAuswG.", chunkText);

    assertThat(supported).isTrue();
  }

  /**
   * #939 review, finding 2: a bare integer percentage ("19 Prozent") carries no thousands separator
   * or decimal comma and is therefore never extracted as a fact - the chunk then has no fact of the
   * statement's category at all, which this class's conservative contract treats as "not
   * confirmable here", not a contradiction.
   */
  @Test
  void doesNotFlagWhenTheChunkHasNoFactOfTheStatementFactsCategoryAtAll() {
    String chunkText = "Der Satz liegt bei 19 Prozent.";

    boolean supported =
        CitationFactChecker.isSupportedByChunk("Der Satz liegt bei 19,0 Prozent.", chunkText);

    assertThat(supported).isTrue();
  }

  /**
   * #939 review, finding 3(a): {@link CitationFactChecker#nearestFact} resolves to the last fact
   * occurring in the text - the fact a trailing citation marker is taken to belong to.
   */
  @Test
  void nearestFactResolvesToTheLastFactInTheText() {
    var nearest =
        CitationFactChecker.nearestFact("Der Ausweis kostet 37,00 €, der Reisepass 70,00 €");

    assertThat(nearest).isPresent();
    assertThat(nearest.get().forms()).contains("MONEY:7000");
  }

  @Test
  void isNearestFactSupportedByChunkOnlyChecksTheLastFactInTheStatement() {
    String statement = "Der Ausweis kostet 37,00 €, der Reisepass kostet 70,00 €";
    String chunkText = "Der Reisepass kostet 70,00 €.";

    boolean supported = CitationFactChecker.isNearestFactSupportedByChunk(statement, chunkText);

    assertThat(supported).isTrue();
  }
}
