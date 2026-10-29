package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.TestEncryptionKeys;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        "x-nicht-fuer-den-produktivbetrieb-x-0123456789-0123456789",
        "ci-only-jwt-secret-0123456789abcdefghijklmnop"
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE=",
        "bjzhjv2AKbnBjZCYAR2V/Glp7TYZzqABwkqXs3lEyCY=",
        "CH1X+hsIYeijer8S9wq5wKRnQa4DpUPePzw4KxGMTMw=",
        "3J0X7GKz9q3Hq1zEo2mJ1n5Q2k8m3r9T4v6w8y0A2c4=",
        "Zm9yLWNpLW9ubHktc2V0dGluZ3Mta2V5LTAxMjM0NTY=",
        "  TklDSFQtRlVFUi1ERU4tUFJPRFVLVElWQkVUUklFQjE=  ",
        "bmljaHQtZnVlci1kZW4tcHJvZHVrdGl2YmV0cmllYjk=",
        "Qo69uaPd4kBf0N5+Hne8cePloVxyZE6pye8qhEfdf+8=",
        "gljJpg/LCRbybV9cerT5pH0EjCPGz1LIN8ry7JfrUzE=",
        "bgkeQqaMpiL1xFl4ymM9MVJ+jYlt+GCb4w9J8o5btxI=",
        "NNEl4fE9dCl6LycesKNIxBXCY+hDKDDWV8D7hPi5e0A="
      })
  void recognisesPublishedEncryptionKeys(String key) {
    assertThat(SecretValidator.isPubliclyKnownEncryptionKey(key)).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "not base64 !"})
  void doesNotTreatBlankOrInvalidKeysAsPublished(String key) {
    assertThat(SecretValidator.isPubliclyKnownEncryptionKey(key)).isFalse();
  }

  @Test
  void doesNotTreatAFreshlyGeneratedKeyAsPublished() {
    assertThat(SecretValidator.isPubliclyKnownEncryptionKey(TestEncryptionKeys.generate()))
        .isFalse();
  }

  /**
   * No tracked file of the repository holds an AES key the {@code oidc} profile would accept: every
   * Base64 value decoding to 32 bytes is refused, so a copied env or value file never runs with a
   * key everybody can read. Test stacks generate their keys per run. Stored ciphertext ({@code
   * enc:v1:...}) is no key.
   */
  @Test
  void everyAesKeyInTheRepositoryIsRefusedByTheOidcProfile()
      throws IOException, InterruptedException {
    String repositoryRoot = System.getProperty("opaa.repositoryRoot");
    assertThat(repositoryRoot).as("system property opaa.repositoryRoot set by Gradle").isNotNull();
    // git grep reads the tracked files of the working tree, binaries excluded (-I); each output
    // line is "<path>\0<line>". The exact boundaries are checked below, ERE has no lookbehind.
    Process grep =
        new ProcessBuilder(
                "git", "-C", repositoryRoot, "grep", "-I", "-z", "-E", "-e", "[A-Za-z0-9+/]{43}=")
            .redirectErrorStream(true)
            .start();
    String output = new String(grep.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    assertThat(grep.waitFor()).as("git grep in %s: %s", repositoryRoot, output).isZero();
    Pattern key =
        Pattern.compile("(?<![A-Za-z0-9+/])(?<!enc:v1:)[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])");
    List<String> accepted = new ArrayList<>();
    int found = 0;
    for (String hit : output.split("\n")) {
      int separator = hit.indexOf('\0');
      Matcher matcher = key.matcher(hit.substring(separator + 1));
      while (matcher.find()) {
        found++;
        if (!SecretValidator.isPubliclyKnownEncryptionKey(matcher.group())) {
          accepted.add(matcher.group() + " in " + hit.substring(0, Math.max(separator, 0)));
        }
      }
    }
    assertThat(found).as("AES keys found in the repository").isPositive();
    assertThat(accepted).as("keys the oidc profile would accept").isEmpty();
  }
}
