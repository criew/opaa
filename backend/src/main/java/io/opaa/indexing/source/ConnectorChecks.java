package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
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

  private ConnectorChecks() {}

  public static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * The configuration rule of the URL-based types: {@code sourceUrl} is required and an http(s)
   * address, {@code sourcePath} is forbidden.
   */
  public static void validateUrlBasedConfiguration(
      DocumentSourceType sourceType, String sourcePath, String sourceUrl) {
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
  public static String requireHttpUrl(DocumentSourceType sourceType, SourceSettings settings) {
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
  }

  /**
   * {@code sourceProxy} ({@code host:port}) and {@code sourceCredentials} ({@code user:password}),
   * or a 400 for an unusable proxy - never a 500 for a caller's typo.
   */
  public static ProxyAndCredentials parseProxyAndCredentials(SourceSettings settings) {
    try {
      return ProxyAndCredentials.parse(
          blankToNull(settings.sourceProxy()), blankToNull(settings.sourceCredentials()));
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  public static SourceConnectionTestResult reachable(String message, long documentCount) {
    return new SourceConnectionTestResult(true, message, documentCount);
  }

  public static SourceConnectionTestResult unreachable(String message) {
    return new SourceConnectionTestResult(false, message, null);
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
    if (e instanceof UnknownHostException) {
      return "Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).";
    }
    if (e instanceof ConnectException) {
      return "Die Verbindung wurde vom Server abgelehnt.";
    }
    if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
      return "Die Verbindung ist in ein Zeitlimit gelaufen.";
    }
    if (e instanceof SSLException) {
      return "Das Zertifikat des Servers konnte nicht geprüft werden. Bei einem bekannten,"
          + " selbstsignierten Zertifikat kann die Zertifikatsprüfung ausgesetzt werden.";
    }
    return "Die Adresse ist nicht erreichbar.";
  }
}
