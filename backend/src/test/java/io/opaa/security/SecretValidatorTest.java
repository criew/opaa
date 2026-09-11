package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ValidSecret}/{@link SecretValidator} through a standalone Bean Validation {@link
 * Validator}, exactly as the constraint sits on a record component - and through the static check
 * the startup guard uses.
 */
class SecretValidatorTest {

  record Holder(@ValidSecret String secret) {}

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "short",
        "too-short-to-be-a-secret-31char",
        "CHANGE_ME",
        "change-me",
        "changeme",
        "changeit",
        "secret",
        "password",
        "opaa",
        "  opaa  ",
        "change_me_change_me_change_me_change_me"
      })
  void rejectsMissingShortOrPlaceholderSecrets(String value) {
    assertThat(validator.validate(new Holder(value)))
        .as("expected a violation for: [%s]", value)
        .isNotEmpty();
    assertThat(SecretValidator.isStrong(value)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "this-is-a-strong-test-secret-0123456789",
        "Zk9$fJ2!pQ7@xL4&mN1#vB8^cD6*aS3-eR5+",
        "d2h5IGFyZSB5b3UgcmVhZGluZyB0aGlzPyBnbyBnZW5lcmF0ZSBvbmUgd2l0aCBvcGVuc3Ns"
      })
  void acceptsStrongSecrets(String value) {
    assertThat(validator.validate(new Holder(value))).isEmpty();
    assertThat(SecretValidator.isStrong(value)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"opaa", "secret"})
  void theRequirementTextNamesTheVariableAndTheGenerator(String unused) {
    assertThat(SecretValidator.describeRequirement("OPAA_AUTH_JWT_SECRET"))
        .contains("OPAA_AUTH_JWT_SECRET")
        .contains("32")
        .contains("openssl rand -base64 48");
  }
}
