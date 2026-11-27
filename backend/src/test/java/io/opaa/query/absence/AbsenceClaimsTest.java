package io.opaa.query.absence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AbsenceClaimsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Die Gebühr ist in den vorliegenden Dokumenten nicht enthalten.",
        "Die Frist ist in den bereitgestellten Quellen leider nicht genannt.",
        "Das steht nicht in den Quellen.",
        "Dazu finden sich keine Angaben in den Unterlagen.",
        "Zur Tarifstelle AB-08 gibt es keine Angabe.",
        "Zu den Kosten liegen mir keine Informationen vor.",
        "Den Unterlagen lässt sich die Höhe nicht entnehmen.",
        "Aus den Kontextdokumenten geht die Zuständigkeit nicht hervor.",
        "Die Dokumente nennen den Betrag nicht.",
        "Eine Regelung dazu konnte ich nicht finden.",
        "Der Betrag ist 12 Euro. Die Frist ist in den Dokumenten\nnicht geregelt.",
        "The fee is not mentioned in the provided documents.",
        "The sources do not specify a deadline.",
        "There is no information about the fee.",
        "I could not find the fee in the context."
      })
  void recognisesAClaimThatSomethingIsMissing(String answer) {
    assertThat(AbsenceClaims.claimsAbsence(answer)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Die Gebühr nach Tarifstelle AB-08 beträgt 12 Euro.",
        "Die Mehrwertsteuer ist im Betrag nicht enthalten.",
        "Der Antrag muss nicht schriftlich gestellt werden.",
        "Die Quellen nennen eine Frist von einem Monat.",
        "The fee is 12 euros according to the documents.",
        ""
      })
  void leavesAnAnswerWithoutSuchAClaimAlone(String answer) {
    assertThat(AbsenceClaims.claimsAbsence(answer)).isFalse();
  }
}
