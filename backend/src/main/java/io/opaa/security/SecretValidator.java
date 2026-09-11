package io.opaa.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Locale;
import java.util.Set;

/**
 * The rule behind {@link ValidSecret}, also usable without Bean Validation ({@link #isStrong}): a
 * trimmed value of at least {@value #MIN_LENGTH} characters that neither starts with one of the
 * placeholders below nor contains the {@link #NON_PRODUCTION_MARKER}, compared case-insensitively.
 * Below 32 characters, brute-forcing the derived keys becomes feasible; the placeholders are what
 * an operator who forgot to set a real value would run with.
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
          "default");

  public static boolean isStrong(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed.length() < MIN_LENGTH) {
      return false;
    }
    String lowered = trimmed.toLowerCase(Locale.ROOT);
    if (lowered.contains(NON_PRODUCTION_MARKER.toLowerCase(Locale.ROOT))) {
      return false;
    }
    return PLACEHOLDERS.stream().noneMatch(placeholder -> lowered.startsWith(placeholder));
  }

  /** One sentence that names the variable, the rule and the command that produces a valid value. */
  public static String describeRequirement(String variableName) {
    return variableName
        + " must be set to a strong, non-placeholder secret of at least "
        + MIN_LENGTH
        + " characters (values starting with change_me, secret, password or opaa are refused)."
        + " Generate one with: "
        + GENERATE_COMMAND;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return isStrong(value);
  }
}
