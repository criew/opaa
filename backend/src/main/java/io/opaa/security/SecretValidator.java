package io.opaa.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * The rule behind {@link ValidSecret}, also usable without Bean Validation ({@link #isStrong}): a
 * trimmed value of at least {@value #MIN_LENGTH} characters that neither starts with one of the
 * placeholders below nor contains the {@link #NON_PRODUCTION_MARKER}, compared case-insensitively,
 * and is none of the signing secrets once published in this repository. Below 32 characters,
 * brute-forcing the derived keys becomes feasible; the placeholders are what an operator who forgot
 * to set a real value, or copied a value from the repository's CI files ({@code ci-only-...}),
 * would run with. {@link #isPubliclyKnownEncryptionKey} is the matching rule for the AES keys,
 * which are random bytes and cannot carry a readable prefix.
 */
public class SecretValidator implements ConstraintValidator<ValidSecret, String> {

  public static final int MIN_LENGTH = 32;
  public static final String GENERATE_COMMAND = "openssl rand -base64 48";

  /**
   * Marks the shipped, publicly known development values in {@code application.yml}; a value
   * containing it is never accepted, however long it is.
   */
  public static final String NON_PRODUCTION_MARKER = "NICHT-FUER-DEN-PRODUKTIVBETRIEB";

  private static final Set<String> PLACEHOLDERS =
      Set.of(
          "change_me",
          "change-me",
          "changeme",
          "changeit",
          "secret",
          "password",
          "opaa",
          "example",
          "default",
          "ci-only");

  /**
   * AES keys published in this repository that do not decode to {@link #NON_PRODUCTION_MARKER}: the
   * {@code dev} profile defaults in {@code application.yml}, keys of the Helm chart's CI value sets
   * and their released predecessors, and the keys the E2E stacks, the chart's installation test and
   * the image smoke test once carried. Anyone who copied one holds a key everybody can read.
   */
  private static final Set<String> PUBLISHED_ENCRYPTION_KEYS =
      Set.of(
          "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
          "c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE=",
          "bjzhjv2AKbnBjZCYAR2V/Glp7TYZzqABwkqXs3lEyCY=",
          "CH1X+hsIYeijer8S9wq5wKRnQa4DpUPePzw4KxGMTMw=",
          "3J0X7GKz9q3Hq1zEo2mJ1n5Q2k8m3r9T4v6w8y0A2c4=",
          "Zm9yLWNpLW9ubHktc2V0dGluZ3Mta2V5LTAxMjM0NTY=",
          "Qo69uaPd4kBf0N5+Hne8cePloVxyZE6pye8qhEfdf+8=",
          "gljJpg/LCRbybV9cerT5pH0EjCPGz1LIN8ry7JfrUzE=",
          "bgkeQqaMpiL1xFl4ymM9MVJ+jYlt+GCb4w9J8o5btxI=",
          "NNEl4fE9dCl6LycesKNIxBXCY+hDKDDWV8D7hPi5e0A=");

  /**
   * Signing secrets the E2E stacks, the chart's installation test and the image smoke test once
   * carried in this repository, without a placeholder prefix or the marker. Anyone who copied one
   * signs tokens everybody can forge.
   */
  private static final Set<String> PUBLISHED_SECRETS =
      Set.of(
          "demo-smoke-local-auth-jwt-secret-not-for-production-0123456789",
          "e2e-local-auth-jwt-secret-not-for-production-0123456789",
          "installation-e2e-issuer-secret-not-for-production-0123456789",
          "local-auth-e2e-issuer-secret-not-for-production-0123456789",
          "multi-instance-e2e-issuer-secret-not-for-production-0123456789",
          "offline-e2e-issuer-secret-not-for-production-0123456789abcdef",
          "kind-install-test-jwt-secret-0123456789abcdefghij",
          "smoke-only-jwt-secret-0123456789abcdefghijklmnop");

  public static boolean isStrong(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed.length() < MIN_LENGTH || PUBLISHED_SECRETS.contains(trimmed)) {
      return false;
    }
    String lowered = trimmed.toLowerCase(Locale.ROOT);
    if (lowered.contains(NON_PRODUCTION_MARKER.toLowerCase(Locale.ROOT))) {
      return false;
    }
    return PLACEHOLDERS.stream().noneMatch(placeholder -> lowered.startsWith(placeholder));
  }

  /**
   * Whether a Base64 AES key is one everybody can read: one of the keys published in this
   * repository, or a key whose decoded bytes contain {@link #NON_PRODUCTION_MARKER}. A blank or
   * undecodable value is not "known" - whether it is usable at all is the encryptor's check.
   */
  public static boolean isPubliclyKnownEncryptionKey(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      return false;
    }
    String trimmed = base64Key.trim();
    if (PUBLISHED_ENCRYPTION_KEYS.contains(trimmed)) {
      return true;
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(trimmed);
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
    return new String(decoded, StandardCharsets.ISO_8859_1)
        .toUpperCase(Locale.ROOT)
        .contains(NON_PRODUCTION_MARKER);
  }

  /** One sentence that names the variable, the rule and the command that produces a valid value. */
  public static String describeRequirement(String variableName) {
    return variableName
        + " must be set to a strong, non-placeholder secret of at least "
        + MIN_LENGTH
        + " characters (values starting with change_me, secret, password, opaa or ci-only are"
        + " refused)."
        + " Generate one with: "
        + GENERATE_COMMAND;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return isStrong(value);
  }
}
