package io.opaa.auth.local;

import io.opaa.common.FieldValidationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The password policy of ADR-0033, Entscheidung 9: at least the configured minimum length ({@code
 * local_auth_settings.password_min_length}), at most {@value #MAX_LENGTH} characters and {@value
 * #MAX_BYTES} bytes (BCrypt's input limit), not equal to the account's address, not on the shipped
 * list of the most common passwords (compared in lower case) - and no complexity rules. Every
 * violated rule is reported with its own stable code, so a form can mark all of them at once.
 */
public class PasswordPolicy {

  public static final int MAX_LENGTH = 64;
  public static final int MAX_BYTES = 72;

  public static final String TOO_SHORT = "TOO_SHORT";
  public static final String TOO_LONG = "TOO_LONG";
  public static final String EQUALS_EMAIL = "EQUALS_EMAIL";
  public static final String TOO_COMMON = "TOO_COMMON";

  private static final String COMMON_PASSWORDS_RESOURCE = "/local-auth/common-passwords.txt";
  private static final Set<String> COMMON_PASSWORDS = loadCommonPasswords();

  private final IntSupplier minLength;

  /** {@code minLength} is read on every check, so a changed setting applies at once. */
  public PasswordPolicy(IntSupplier minLength) {
    this.minLength = minLength;
  }

  public List<Violation> check(String password, String email) {
    String value = password == null ? "" : password;
    List<Violation> violations = new ArrayList<>();
    int min = minLength.getAsInt();
    if (value.codePointCount(0, value.length()) < min) {
      violations.add(
          new Violation(TOO_SHORT, "Das Passwort muss mindestens " + min + " Zeichen lang sein."));
    }
    if (value.codePointCount(0, value.length()) > MAX_LENGTH
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      violations.add(
          new Violation(
              TOO_LONG,
              "Das Passwort darf höchstens "
                  + MAX_LENGTH
                  + " Zeichen ("
                  + MAX_BYTES
                  + " Byte) lang sein."));
    }
    String lower = value.toLowerCase(Locale.ROOT);
    if (email != null && !email.isBlank() && lower.equals(email.trim().toLowerCase(Locale.ROOT))) {
      violations.add(
          new Violation(EQUALS_EMAIL, "Das Passwort darf nicht die E-Mail-Adresse sein."));
    }
    if (COMMON_PASSWORDS.contains(lower)) {
      violations.add(
          new Violation(
              TOO_COMMON,
              "Dieses Passwort gehört zu den am häufigsten verwendeten und ist nicht erlaubt."));
    }
    return List.copyOf(violations);
  }

  /** Throws a {@link FieldValidationException} on {@code field} for every violated rule. */
  public void require(String field, String password, String email) {
    List<Violation> violations = check(password, email);
    if (violations.isEmpty()) {
      return;
    }
    throw new FieldValidationException(
        "Das neue Passwort entspricht nicht der Passwortrichtlinie.",
        violations.stream()
            .map(
                violation ->
                    new FieldValidationException.FieldError(
                        field, violation.code(), violation.message()))
            .toList());
  }

  /** How many passwords the shipped list holds. */
  public static int commonPasswordCount() {
    return COMMON_PASSWORDS.size();
  }

  /** One violated rule: a stable code and the user-facing message. */
  public record Violation(String code, String message) {}

  private static Set<String> loadCommonPasswords() {
    try (InputStream in = PasswordPolicy.class.getResourceAsStream(COMMON_PASSWORDS_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + COMMON_PASSWORDS_RESOURCE);
      }
      Set<String> passwords = new HashSet<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String trimmed = line.trim();
          if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
            passwords.add(trimmed.toLowerCase(Locale.ROOT));
          }
        }
      }
      return Set.copyOf(passwords);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + COMMON_PASSWORDS_RESOURCE, e);
    }
  }
}
