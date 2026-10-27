package io.opaa.mail;

import io.opaa.api.types.MailEncryption;
import io.opaa.common.FieldValidationException.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules of the SMTP settings (ADR-0033 Entscheidung 10), shared by the administration ({@link
 * MailSettingsService}) and the installation, so both refuse the same values with the same German
 * sentences. Each caller decides how a problem reaches its API; the rules themselves only find it.
 */
public final class MailSettingsRules {

  public static final int MAX_HOST_LENGTH = 255;
  public static final int MAX_USERNAME_LENGTH = 255;
  public static final int MAX_PASSWORD_LENGTH = 255;
  public static final int MAX_FROM_ADDRESS_LENGTH = 320;
  public static final int MAX_FROM_NAME_LENGTH = 120;
  public static final String TOO_LONG = "TOO_LONG";
  public static final String PASSWORD_REQUIRED = "PASSWORD_REQUIRED";

  /** The encryption of settings that name none. */
  public static final MailEncryption DEFAULT_ENCRYPTION = MailEncryption.STARTTLS;

  public static final String PASSWORD_FOLLOWS_TARGET =
      "Server, Port, Zugangsname oder Verschlüsselung ändern sich: Das gespeicherte Passwort"
          + " geht nicht an ein neues Ziel. Bitte das Passwort neu eingeben oder leeren.";

  public static final String HOST_MISSING =
      "Ohne SMTP-Server kann der Versand nicht eingeschaltet werden";
  public static final String PORT_MISSING = "Ohne Port kann der Versand nicht eingeschaltet werden";
  public static final String FROM_ADDRESS_MISSING =
      "Ohne Absenderadresse kann der Versand nicht eingeschaltet werden";

  static final int MIN_PORT = 1;
  static final int MAX_PORT = 65535;

  /**
   * Deliberately permissive - the mail server is the authority on what it accepts - but one mailbox
   * only: no list separator ({@code , ;}), no display-name syntax ({@code < > "}).
   */
  private static final Pattern MAIL_ADDRESS =
      Pattern.compile("^[^\\s@,;<>\"]+@[^\\s@,;<>\"]+\\.[^\\s@,;<>\"]+$");

  private MailSettingsRules() {}

  /**
   * The fields longer than the columns and the request schemas allow, each named as {@code prefix}
   * plus its field name ({@code ""} for the administration, {@code "mail."} for the installation).
   */
  public static List<FieldError> lengthErrors(
      String prefix,
      String host,
      String username,
      String password,
      String fromAddress,
      String fromName) {
    List<FieldError> errors = new ArrayList<>();
    requireLength(errors, prefix + "host", "Der SMTP-Server", host, MAX_HOST_LENGTH);
    requireLength(errors, prefix + "username", "Der Zugangsname", username, MAX_USERNAME_LENGTH);
    requireLength(errors, prefix + "password", "Das Passwort", password, MAX_PASSWORD_LENGTH);
    requireLength(
        errors,
        prefix + "fromAddress",
        "Die Absenderadresse",
        fromAddress,
        MAX_FROM_ADDRESS_LENGTH);
    requireLength(errors, prefix + "fromName", "Der Absendername", fromName, MAX_FROM_NAME_LENGTH);
    return errors;
  }

  /** Why {@code port} is no port, or empty; {@code null} is no problem of this rule. */
  public static Optional<String> portProblem(Integer port) {
    if (port == null || (port >= MIN_PORT && port <= MAX_PORT)) {
      return Optional.empty();
    }
    return Optional.of("Der Port muss zwischen " + MIN_PORT + " und " + MAX_PORT + " liegen");
  }

  /** Why {@code address} is no sender address, or empty; {@code null} is no problem here. */
  public static Optional<String> fromAddressProblem(String address) {
    if (address == null || MAIL_ADDRESS.matcher(address).matches()) {
      return Optional.empty();
    }
    return Optional.of(
        "Die Absenderadresse muss eine E-Mail-Adresse sein, zum Beispiel opaa@example.org");
  }

  /** Whether {@code address} has the form of a mail address, by the rule of the sender address. */
  public static boolean isMailAddress(String address) {
    return address != null && MAIL_ADDRESS.matcher(address).matches();
  }

  /**
   * Whether a stored password may stay with the new target: only for the same server, port, account
   * and encryption it was entered for.
   */
  public static boolean sameTarget(
      String storedHost,
      Integer storedPort,
      String storedUsername,
      MailEncryption storedEncryption,
      String host,
      Integer port,
      String username,
      MailEncryption encryption) {
    return Objects.equals(storedHost, host)
        && Objects.equals(storedPort, port)
        && Objects.equals(storedUsername, username)
        && storedEncryption == encryption;
  }

  /** {@code value} trimmed, or {@code null} when nothing is left. */
  public static String trimmedOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void requireLength(
      List<FieldError> errors, String field, String label, String value, int maxLength) {
    if (value != null && value.trim().length() > maxLength) {
      errors.add(
          new FieldError(
              field, TOO_LONG, label + " darf höchstens " + maxLength + " Zeichen lang sein."));
    }
  }
}
