package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A password leaves a failure cause wherever it stands, in any case once it is three characters
 * long; a shorter one only where it does not continue a word, so the rest of the cause stays
 * readable.
 */
class MailLogTextTest {

  @Test
  void aMissingOrEmptyPasswordChangesNothing() {
    assertThat(MailLogText.withoutSecret("535 rejected", null)).isEqualTo("535 rejected");
    assertThat(MailLogText.withoutSecret("535 rejected", "")).isEqualTo("535 rejected");
    assertThat(MailLogText.withoutSecret(null, "geheim")).isNull();
  }

  @Test
  void aPasswordOfThreeCharactersOrMoreIsReplacedInsideAWordToo() {
    assertThat(MailLogText.withoutSecret("535 user=kennunggeheim1x rejected", "geheim1"))
        .isEqualTo("535 user=kennung***x rejected");
  }

  @Test
  void aPasswordOfThreeCharactersOrMoreIsReplacedInAnyCase() {
    assertThat(MailLogText.withoutSecret("535 GEHEIM1 rejected; geheim1", "Geheim1"))
        .isEqualTo("535 *** rejected; ***");
  }

  @Test
  void aShortPasswordIsCheckedForAWordOnlyAtAnEdgeThatIsAWordCharacter() {
    assertThat(MailLogText.withoutSecret("535 Error! rejected", "!"))
        .isEqualTo("535 Error*** rejected");
    assertThat(MailLogText.withoutSecret("535 x a-b rejected", "a-"))
        .isEqualTo("535 x ***b rejected");
    assertThat(MailLogText.withoutSecret("535 x ba-b rejected", "a-"))
        .isEqualTo("535 x ba-b rejected");
    assertThat(MailLogText.withoutSecret("535 x -bc rejected", "-b"))
        .isEqualTo("535 x -bc rejected");
    assertThat(MailLogText.withoutSecret("535 x a-bc rejected", "-b"))
        .isEqualTo("535 x a-bc rejected");
    assertThat(MailLogText.withoutSecret("535 x a-b. rejected", "-b"))
        .isEqualTo("535 x a***. rejected");
    assertThat(MailLogText.withoutSecret("535 pw ab rejected; abc", "ab"))
        .isEqualTo("535 pw *** rejected; abc");
  }
}
