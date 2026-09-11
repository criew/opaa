package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
        "change_me_change_me_change_me_change_me",
        "NICHT-FUER-DEN-PRODUKTIVBETRIEB-opaa-dev-jwt-secret-2026",
        "x-nicht-fuer-den-produktivbetrieb-x-0123456789-0123456789"
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

  @Test
  void theRequirementTextNamesTheVariableAndTheGenerator() {
    assertThat(SecretValidator.describeRequirement("OPAA_AUTH_JWT_SECRET"))
        .contains("OPAA_AUTH_JWT_SECRET")
        .contains("32")
        .contains("openssl rand -base64 48");
  }

  /**
   * The dev profile's shipped default in {@code application.yml} must never pass as a production
   * secret, however it is edited: it has to carry {@link SecretValidator#NON_PRODUCTION_MARKER}.
   */
  @Test
  void rejectsTheShippedDevDefault() throws IOException {
    String yaml;
    try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
      assertThat(in).as("application.yml on the test classpath").isNotNull();
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher defaults =
        Pattern.compile("jwt-secret: \\$\\{OPAA_AUTH_JWT_SECRET:([^}]+)\\}").matcher(yaml);

    assertThat(defaults.find()).as("a dev default for opaa.auth.local.jwt-secret").isTrue();
    do {
      String shipped = defaults.group(1);
      assertThat(shipped).contains(SecretValidator.NON_PRODUCTION_MARKER);
      assertThat(SecretValidator.isStrong(shipped)).as("shipped default %s", shipped).isFalse();
    } while (defaults.find());
  }
}
