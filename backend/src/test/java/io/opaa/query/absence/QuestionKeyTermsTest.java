package io.opaa.query.absence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class QuestionKeyTermsTest {

  @Test
  void readsIdentifiersAndContentWordsWithoutQuestionWords() {
    QuestionKeyTerms terms =
        QuestionKeyTerms.of(
            "Welche Gebühr gilt nach § 34 und Tarifstelle AB-08 für die BAföG-Stelle?");

    assertThat(terms.identifiers()).containsExactly("§ 34", "AB-08");
    assertThat(terms.words()).contains("gebühr", "tarifstelle").doesNotContain("welche");
    assertThat(terms.searchQuery()).startsWith("§ 34 AB-08 ");
  }

  @Test
  void readsAnUppercaseAbbreviationAsAnIdentifier() {
    assertThat(QuestionKeyTerms.of("Was kostet ein Zugang zu ELSTER?").identifiers())
        .containsExactly("ELSTER");
  }

  @Test
  void anUppercaseQuestionWordIsNoIdentifier() {
    QuestionKeyTerms terms = QuestionKeyTerms.of("WELCHE FRIST GILT?");

    assertThat(terms.identifiers()).isEmpty();
    assertThat(terms.words()).doesNotContain("welche");
  }

  @Test
  void aPassageMatchesByAnIdentifierAsAWholeToken() {
    QuestionKeyTerms terms = QuestionKeyTerms.of("Wie hoch ist die Gebühr nach Tarifstelle AB-08?");

    assertThat(terms.matches("Tarifstelle ab-08: 12 Euro")).isTrue();
    assertThat(terms.matches("Tarifstelle AB-081: 40 Euro")).isFalse();
    assertThat(terms.matches("Die Gebühr der Tarifstelle AB-07 beträgt 9 Euro")).isFalse();
  }

  @Test
  void aParagraphMatchesWithOrWithoutTheSpace() {
    QuestionKeyTerms terms = QuestionKeyTerms.of("Was regelt §34?");

    assertThat(terms.matches("Nach § 34 Abs. 2 gilt")).isTrue();
    assertThat(terms.matches("Nach § 341 gilt")).isFalse();
  }

  @Test
  void withoutIdentifiersAPassageNeedsTwoContentWordsByTheirStem() {
    QuestionKeyTerms terms =
        QuestionKeyTerms.of("Welche Gebühren fallen beim Bewohnerparkausweis an?");

    assertThat(terms.matches("Für den Bewohnerparkausweis wird eine Gebühr erhoben.")).isTrue();
    assertThat(terms.matches("Die Gebühr für Sondernutzungen")).isFalse();
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void aLongRunOfParagraphSignsIsReadInLinearTime() {
    String signs = "§".repeat(200_000);

    QuestionKeyTerms terms = QuestionKeyTerms.of("Was regelt " + signs + " x §§ 34?");
    QuestionKeyTerms paragraph = QuestionKeyTerms.of("Was regelt § 34?");

    assertThat(paragraph.matches(signs + " " + signs + "x")).isFalse();
    assertThat(paragraph.mentionedIn(signs + "a" + signs)).isFalse();
    assertThat(terms.identifiers()).isNotNull();
    assertThat(QuestionKeyTerms.of(signs + " 34").identifiers()).isEmpty();
  }

  @Test
  void aParagraphAfterSeveralSignsIsStillRead() {
    assertThat(QuestionKeyTerms.of("Was regelt §§ 34?").identifiers()).containsExactly("§ 34");
  }

  @Test
  void aQuestionWithoutKeyTermsMatchesNothing() {
    QuestionKeyTerms terms = QuestionKeyTerms.of("Und was ist mit dem?");

    assertThat(terms.isEmpty()).isTrue();
    assertThat(terms.matches("Irgendein Text")).isFalse();
  }
}
