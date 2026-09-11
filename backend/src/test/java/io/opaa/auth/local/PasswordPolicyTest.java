package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.FieldValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The password policy of ADR-0033, Entscheidung 9: the configured minimum length, at most 64
 * characters and 72 bytes (BCrypt's input limit), not the account's own address, not on the
 * shipped list of the most common passwords (compared in lower case) - and nothing else: no
 * complexity rules. Every violated rule is reported with its own stable code, so the form can name
 * all of them at once.
 */
class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy(() -> 12);

  @Test
  void acceptsALongEnoughUncommonPassword() {
    assertThat(policy.check("korrekt-batterie-pferd-klammer", "erika@stadt.example")).isEmpty();
  }

  @Test
  void rejectsAPasswordBelowTheConfiguredMinimum() {
    assertThat(codes(policy.check("elf-zeichen", "erika@stadt.example")))
        .containsExactly(PasswordPolicy.TOO_SHORT);
    assertThat(new PasswordPolicy(() -> 8).check("acht-zei", "erika@stadt.example")).isEmpty();
  }

  @Test
  void rejectsMoreThan64CharactersOrMoreThan72Bytes() {
    assertThat(codes(policy.check("a".repeat(65), "erika@stadt.example")))
        .containsExactly(PasswordPolicy.TOO_LONG);
    assertThat(policy.check("a".repeat(64), "erika@stadt.example")).isEmpty();
    // 40 characters, but four bytes each in UTF-8
    assertThat(codes(policy.check("🔒".repeat(20), "erika@stadt.example")))
        .containsExactly(PasswordPolicy.TOO_LONG);
  }

  @Test
  void rejectsTheAccountsOwnAddressRegardlessOfCase() {
    assertThat(codes(policy.check("Erika.Muster@Stadt.Example", "erika.muster@stadt.example")))
        .containsExactly(PasswordPolicy.EQUALS_EMAIL);
  }

  @Test
  void rejectsTheMostCommonPasswordsComparedInLowerCase() {
    assertThat(codes(policy.check("Password1234", "erika@stadt.example")))
        .containsExactly(PasswordPolicy.TOO_COMMON);
    assertThat(codes(policy.check("QWERTZUIOP12", "erika@stadt.example")))
        .containsExactly(PasswordPolicy.TOO_COMMON);
  }

  @Test
  void hasNoComplexityRule() {
    assertThat(policy.check("nur kleinbuchstaben und leerzeichen", "erika@stadt.example"))
        .isEmpty();
  }

  @Test
  void reportsEveryViolatedRuleAtOnce() {
    // "123456789012" is 12 characters (long enough) and on the common list
    assertThat(codes(policy.check("123456789012", "erika@stadt.example")))
        .containsExactly(PasswordPolicy.TOO_COMMON);
    assertThat(codes(policy.check("a@b.de", "a@b.de")))
        .containsExactlyInAnyOrder(PasswordPolicy.TOO_SHORT, PasswordPolicy.EQUALS_EMAIL);
  }

  @Test
  void requireThrowsAFieldValidationExceptionNamingTheField() {
    assertThatThrownBy(() -> policy.require("newPassword", "kurz", "erika@stadt.example"))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(
                        FieldValidationException.FieldError::field,
                        FieldValidationException.FieldError::code)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("newPassword", PasswordPolicy.TOO_SHORT)));
  }

  @Test
  void shipsAListOfAtLeastAThousandCommonPasswords() {
    assertThat(PasswordPolicy.commonPasswordCount()).isGreaterThanOrEqualTo(1000);
  }

  private static List<String> codes(List<PasswordPolicy.Violation> violations) {
    return violations.stream().map(PasswordPolicy.Violation::code).toList();
  }
}
