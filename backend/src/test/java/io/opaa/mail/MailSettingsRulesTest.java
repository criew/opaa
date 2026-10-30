package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.api.types.MailEncryption;
import io.opaa.common.FieldValidationException.FieldError;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The rules the administration and the installation share for SMTP settings: the same fields, the
 * same limits and the same German sentences, only under a different field prefix.
 */
class MailSettingsRulesTest {

  @Test
  void everyOverlongFieldIsNamedUnderThePrefixOfItsCaller() {
    String tooLong = "x".repeat(MailSettingsRules.MAX_HOST_LENGTH + 1);

    assertThat(MailSettingsRules.lengthErrors("mail.", tooLong, null, null, null, null))
        .extracting(FieldError::field, FieldError::code, FieldError::message)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "mail.host",
                MailSettingsRules.TOO_LONG,
                "Der SMTP-Server darf höchstens 255 Zeichen lang sein."));
    assertThat(MailSettingsRules.lengthErrors("", tooLong, null, null, null, null))
        .extracting(FieldError::field)
        .containsExactly("host");
  }

  @Test
  void aPortLiesBetweenOneAndTheLastPort() {
    assertThat(MailSettingsRules.portProblem(null)).isEmpty();
    assertThat(MailSettingsRules.portProblem(587)).isEmpty();
    assertThat(MailSettingsRules.portProblem(0))
        .contains("Der Port muss zwischen 1 und 65535 liegen");
    assertThat(MailSettingsRules.portProblem(65536)).isPresent();
  }

  @Test
  void aSenderAddressHasTheFormOfAMailAddress() {
    assertThat(MailSettingsRules.fromAddressProblem("opaa@stadt.example")).isEmpty();
    assertThat(MailSettingsRules.fromAddressProblem("opaa"))
        .contains(
            "Die Absenderadresse muss eine E-Mail-Adresse sein, zum Beispiel opaa@example.org");
    assertThat(MailSettingsRules.isMailAddress("it@stadt.example")).isTrue();
    assertThat(MailSettingsRules.isMailAddress("it@stadt")).isFalse();
    assertThat(MailSettingsRules.isMailAddress("it@s.t")).isTrue();
    assertThat(MailSettingsRules.isMailAddress("it@.stadt.example")).isTrue();
    assertThat(MailSettingsRules.isMailAddress("it@.stadt")).isFalse();
    assertThat(MailSettingsRules.isMailAddress("it@stadt.")).isFalse();
    assertThat(MailSettingsRules.isMailAddress("it@a@stadt.example")).isFalse();
    assertThat(MailSettingsRules.isMailAddress(null)).isFalse();
  }

  // regression guard: an address field takes one mailbox, never a list or a display name
  @Test
  void anAddressIsOneMailboxWithoutListOrDisplayName() {
    for (String address :
        new String[] {
          "a@b.c,d@e.f", "a@b.c,d.e.f", "a@b.c;d@e.f", "OPAA <a@b.c>", "\"a\"@b.c", "a@b.c>"
        }) {
      assertThat(MailSettingsRules.isMailAddress(address)).as(address).isFalse();
      assertThat(MailSettingsRules.fromAddressProblem(address)).as(address).isPresent();
    }
  }

  @Test
  void aStoredPasswordFollowsOnlyTheSameServerPortAccountAndEncryption() {
    assertThat(same("smtp", 587, "u", MailEncryption.STARTTLS)).isTrue();
    assertThat(same("other", 587, "u", MailEncryption.STARTTLS)).isFalse();
    assertThat(same("smtp", 465, "u", MailEncryption.STARTTLS)).isFalse();
    assertThat(same("smtp", 587, "v", MailEncryption.STARTTLS)).isFalse();
    assertThat(same("smtp", 587, "u", MailEncryption.SSL)).isFalse();
  }

  private static boolean same(String host, int port, String user, MailEncryption encryption) {
    return MailSettingsRules.sameTarget(
        "smtp", 587, "u", MailEncryption.STARTTLS, host, port, user, encryption);
  }

  /** Regression guard: an overlong sender address is refused before the pattern backtracks. */
  @Test
  void anOverlongAddressIsNoMailAddressAndIsRejectedQuickly() {
    String address = "a@" + ".".repeat(64_000) + " ";
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          assertThat(MailSettingsRules.isMailAddress(address)).isFalse();
          assertThat(MailSettingsRules.fromAddressProblem(address)).isPresent();
        });
    assertThat(MailSettingsRules.isMailAddress("opaa@example.org")).isTrue();
  }
}
