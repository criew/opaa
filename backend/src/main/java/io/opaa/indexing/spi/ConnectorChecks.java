package io.opaa.indexing.spi;

import io.opaa.common.ValidationException;
import io.opaa.knowledge.SourceType;
import io.opaa.outbound.ProxyRefusal;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BasicCredentials;
import io.opaa.sourceaccess.UntrustedCertificate;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLException;

/**
 * Validation and connection-test building blocks several connectors share, so the same input gets
 * the same German message whichever connector checks it.
 */
public final class ConnectorChecks {

  /**
   * Per-request timeout of a synchronous connection test - well under the 30 s connect timeout of
   * {@code SourceHttpClientFactory#buildHttpClient}, so a caller cannot bind request threads for
   * long against a filtered address.
   */
  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Credentials in the address ({@code user:pass@host}) are refused: the address is stored and
   * logged in clear text, the message never repeats it.
   */
  static final String USER_INFO_REJECTED_MESSAGE =
      "sourceUrl darf keine Zugangsdaten enthalten (Form \"https://benutzer:passwort@host\")."
          + " Tragen Sie die Adresse ohne Zugangsdaten ein und hinterlegen Sie Benutzername und"
          + " Passwort in den Zugangsdaten der Quelle.";

  private ConnectorChecks() {}

  public static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * The configuration rule of the URL-based types: {@code sourceUrl} is required and an http(s)
   * address, {@code sourcePath} is forbidden.
   */
  public static void validateUrlBasedConfiguration(
      SourceType sourceType, String sourcePath, String sourceUrl) {
    if (sourceUrl == null) {
      throw new ValidationException(
          "sourceUrl ist erforderlich, wenn sourceType " + sourceType + " ist");
    }
    if (sourcePath != null) {
      throw new ValidationException(
          "sourcePath ist für sourceType " + sourceType + " nicht zulässig");
    }
    requireHttpScheme(sourceUrl);
  }

  /**
   * The same rule for a connection test, with the test's own wording for a missing address; returns
   * the trimmed address.
   */
  public static String requireHttpUrl(SourceType sourceType, SourceSettings settings) {
    String sourceUrl = blankToNull(settings.sourceUrl());
    if (sourceUrl == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    if (blankToNull(settings.sourcePath()) != null) {
      throw new ValidationException(
          "sourcePath ist für sourceType " + sourceType + " nicht zulässig");
    }
    requireHttpScheme(sourceUrl);
    return sourceUrl;
  }

  private static void requireHttpScheme(String sourceUrl) {
    URI uri;
    try {
      uri = URI.create(sourceUrl);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("sourceUrl ist keine gültige URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new ValidationException("sourceUrl muss mit http:// oder https:// beginnen");
    }
    if (uri.getRawUserInfo() != null) {
      throw new ValidationException(USER_INFO_REJECTED_MESSAGE);
    }
  }

  /**
   * The address of a request to a connector whose only target is {@code apiBase}: none, or {@code
   * apiBase} with a trailing slash, is {@code apiBase}; anything else stays as sent.
   */
  public static String fixedSourceUrl(String apiBase, String requested) {
    String url = blankToNull(requested);
    if (url == null) {
      return apiBase;
    }
    String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    return trimmed.equals(apiBase) ? apiBase : url;
  }

  /**
   * The configuration rule of a connector whose only target is {@code apiBase}: no {@code
   * sourcePath}, no address but {@code apiBase} as {@link #fixedSourceUrl} reads it. Returns {@code
   * apiBase}.
   */
  public static String requireFixedAddress(
      SourceType sourceType, String apiBase, SourceSettings requested) {
    if (blankToNull(requested.sourcePath()) != null) {
      throw new ValidationException(
          "sourcePath ist für sourceType " + sourceType + " nicht zulässig");
    }
    if (!fixedSourceUrl(apiBase, requested.sourceUrl()).equals(apiBase)) {
      throw new ValidationException(
          "sourceUrl ist für sourceType "
              + sourceType
              + " fest "
              + apiBase
              + " und nicht änderbar");
    }
    return apiBase;
  }

  /**
   * {@link #requireFixedAddress} for a new library whose connector settings are required: returns
   * {@code requested} at {@code apiBase} with its settings as {@code read} normalises them.
   */
  public static SourceSettings validateAtFixedAddress(
      SourceType sourceType,
      String apiBase,
      SourceSettings requested,
      UnaryOperator<ConnectorData> read) {
    String url = requireFixedAddress(sourceType, apiBase, requested);
    if (requested.connectorSettings() == null) {
      throw new ValidationException(
          "sourceSettings sind erforderlich, wenn sourceType " + sourceType + " ist");
    }
    return requested
        .withSourceUrl(url)
        .withConnectorSettings(read.apply(requested.connectorSettings()));
  }

  /**
   * A replaced connection as {@code validate} checks it: with the requested connector settings, or
   * the {@code stored} ones when the request keeps them; the result carries settings only when
   * {@code requested} does.
   */
  public static SourceSettings validateReplacement(
      UnaryOperator<SourceSettings> validate, SourceSettings stored, SourceSettings requested) {
    ConnectorData asked = requested.connectorSettings();
    SourceSettings validated =
        validate.apply(
            requested.withConnectorSettings(asked != null ? asked : stored.connectorSettings()));
    return asked == null ? validated.withConnectorSettings(null) : validated;
  }

  /** {@code sourceCredentials} ({@code user:password}) for Basic Auth. */
  public static BasicCredentials basicCredentials(SourceSettings settings) {
    return BasicCredentials.parse(blankToNull(settings.sourceCredentials()));
  }

  public static SourceConnectionTestResult reachable(String message, long documentCount) {
    return new SourceConnectionTestResult(true, message, documentCount);
  }

  public static SourceConnectionTestResult unreachable(String message) {
    return new SourceConnectionTestResult(false, message, null);
  }

  /**
   * An answer other than the expected {@code 200} as German, user-facing text; a {@code 407} is the
   * outbound proxy refusing, not the server.
   */
  public static String unexpectedStatus(int status) {
    return ProxyRefusal.describeStatus(status)
        .orElse("Der Server antwortete mit HTTP " + status + ".");
  }

  /**
   * A connection-level {@link IOException} as German, user-facing text - never the exception's own
   * (English, sometimes internals-revealing) message, except the target validator's, which is
   * German and names only the refused host.
   */
  public static String translateConnectionError(IOException e) {
    if (e instanceof TargetAddressValidator.TargetAddressBlockedException) {
      return e.getMessage();
    }
    Optional<String> proxyRefusal = ProxyRefusal.describe(e);
    if (proxyRefusal.isPresent()) {
      return proxyRefusal.get();
    }
    if (e instanceof UnknownHostException) {
      return "Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).";
    }
    if (e instanceof ConnectException) {
      return "Die Verbindung wurde vom Server abgelehnt.";
    }
    if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
      return "Die Verbindung ist in ein Zeitlimit gelaufen.";
    }
    if (UntrustedCertificate.causes(e)) {
      return UntrustedCertificate.MESSAGE;
    }
    if (e instanceof SSLException) {
      return "Die TLS-Verbindung zum Server ist fehlgeschlagen.";
    }
    return "Die Adresse ist nicht erreichbar.";
  }
}
