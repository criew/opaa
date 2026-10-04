package io.opaa.indexing.source.confluence;

import io.opaa.indexing.source.RenewableCredential;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Credentials of a Confluence library, typed by edition (ADR-0023, Entscheidung 3). Stored as one
 * string in {@code knowledge_libraries.source_credentials}: Cloud as {@code <e-mail>:<API-Token>}
 * (HTTP Basic with the e-mail as user name - exactly what Cloud is), Data Center as the bare
 * personal access token (sent as {@code Bearer}).
 *
 * <p>{@link Object#toString() toString()} never reveals the secret and no implementation exposes
 * the raw token beyond {@link #authorizationHeader()} - the single place the header value is built
 * - so a credential can appear in no log line, exception message or API response by accident.
 */
public sealed interface ConfluenceCredentials {

  /**
   * The value of the {@code Authorization} header for this edition - the only way the secret leaves
   * this object.
   */
  String authorizationHeader();

  ConfluenceEdition edition();

  /**
   * Whether, after the instance rejected the header {@code sent}, a different one is worth one
   * retry; a stored credential never is.
   */
  default boolean renewedAfterRejection(String sent) {
    return false;
  }

  /**
   * Parses the stored form for {@code edition}.
   *
   * @throws InvalidCredentialsFormatException with a German, user-facing message when the value is
   *     blank or, for Cloud, lacks the {@code e-mail:token} separator
   */
  static ConfluenceCredentials parse(ConfluenceEdition edition, String stored) {
    if (stored == null || stored.isBlank()) {
      throw new InvalidCredentialsFormatException("Für Confluence sind Zugangsdaten erforderlich.");
    }
    return switch (edition) {
      case CLOUD -> {
        int separator = stored.indexOf(':');
        if (separator <= 0 || separator == stored.length() - 1) {
          throw new InvalidCredentialsFormatException(
              "Confluence Cloud erwartet E-Mail-Adresse und API-Token, getrennt durch einen"
                  + " Doppelpunkt (E-Mail:Token).");
        }
        yield new CloudApiToken(stored.substring(0, separator), stored.substring(separator + 1));
      }
      case DATA_CENTER -> {
        String token = stored.strip();
        if (token.indexOf(':') >= 0) {
          throw new InvalidCredentialsFormatException(
              "Ein Personal Access Token für Confluence Data Center enthält keinen Doppelpunkt -"
                  + " E-Mail:Token ist das Format für Confluence Cloud.");
        }
        yield new DataCenterPersonalAccessToken(token);
      }
    };
  }

  /** Cloud: e-mail + API token, sent as HTTP Basic. */
  record CloudApiToken(String email, String apiToken) implements ConfluenceCredentials {

    @Override
    public String authorizationHeader() {
      String raw = email + ":" + apiToken;
      return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ConfluenceEdition edition() {
      return ConfluenceEdition.CLOUD;
    }

    @Override
    public String toString() {
      return "CloudApiToken[email=***, apiToken=***]";
    }
  }

  /** Data Center: personal access token, sent as {@code Bearer}. */
  record DataCenterPersonalAccessToken(String token) implements ConfluenceCredentials {

    @Override
    public String authorizationHeader() {
      return "Bearer " + token;
    }

    @Override
    public ConfluenceEdition edition() {
      return ConfluenceEdition.DATA_CENTER;
    }

    @Override
    public String toString() {
      return "DataCenterPersonalAccessToken[token=***]";
    }
  }

  /**
   * The credentials of a run: every header is built from those {@code current} answers now, so a
   * renewed secret is sent and a refused one ends the run at its next request.
   */
  record Current(ConfluenceEdition edition, RenewableCredential<ConfluenceCredentials> current)
      implements ConfluenceCredentials {

    @Override
    public String authorizationHeader() {
      return current.get().authorizationHeader();
    }

    @Override
    public boolean renewedAfterRejection(String sent) {
      ConfluenceCredentials now = current.get();
      return !now.authorizationHeader().equals(sent) || current.renewedAfterRejection(now);
    }

    @Override
    public String toString() {
      return "Current[edition=" + edition + "]";
    }
  }

  /** Thrown by {@link #parse} for a value that cannot be a credential of the given edition. */
  final class InvalidCredentialsFormatException extends RuntimeException {
    public InvalidCredentialsFormatException(String message) {
      super(message);
    }
  }
}
