package io.opaa.library;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.s3.S3AccessCheck;
import io.opaa.indexing.source.s3.S3AccessException;
import io.opaa.indexing.source.s3.S3BucketListing;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3Connection;
import io.opaa.indexing.source.s3.S3Credentials;
import io.opaa.indexing.source.s3.S3ObjectStore;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The connection test and the bucket listing for an S3 library (ADR-0027, Entscheidung 8 and the
 * probe of #1376), on top of the access layer: every scope is probed in three steps - bucket
 * reachable, listing allowed, reading allowed - through {@link S3ObjectStore#testAccess}, and a
 * store problem is the test's <em>result</em>, never an exception; only a caller's own mistake (an
 * address that is no endpoint, a proxy without a port, missing settings) is a 400. Messages come
 * from the access layer and are German and credential-free by its contract; the TLS diagnosis alone
 * gains the hint that the certificate check can be suspended, named as the last option.
 */
@Service
public class S3ConnectionService {

  static final String TLS_HINT =
      " Bei einem bekannten, selbstsignierten Zertifikat kann als letzte Option die TLS-Prüfung"
          + " ausgesetzt werden.";

  static final String BUCKETS_NOT_LISTABLE =
      "Die Bucket-Liste ist mit diesen Zugangsdaten nicht lesbar (s3:ListAllMyBuckets fehlt) - der"
          + " Bucket-Name kann von Hand eingetragen werden.";

  private final S3ClientFactory clientFactory;

  public S3ConnectionService(S3ClientFactory clientFactory) {
    this.clientFactory = clientFactory;
  }

  /**
   * @param objectCount the objects the first page of every scope showed, {@code null} unless every
   *     scope passed
   */
  public record Probe(
      boolean reachable,
      String message,
      boolean credentialsVerified,
      List<S3ScopeCheck> scopes,
      Long objectCount) {}

  /**
   * Probes every scope of {@code settings} against the endpoint. {@code credentials} is the stored
   * form; a missing or malformed value is a result, not an exception, because the wizard reaches
   * this before the key is complete.
   */
  public Probe probe(
      String rawUrl,
      String proxy,
      String credentials,
      boolean insecureSsl,
      S3SourceSettings settings)
      throws InterruptedException {
    if (settings == null) {
      throw new ValidationException("s3Settings sind für den Verbindungstest erforderlich");
    }
    URI endpoint = normalizeEndpoint(rawUrl);
    ProxyAndCredentials proxyConfig = parseProxy(proxy);
    if (credentials == null || credentials.isBlank()) {
      return new Probe(
          false,
          "Für den Verbindungstest sind Zugangsdaten erforderlich: Access Key und Secret Key,"
              + " getrennt durch einen Doppelpunkt.",
          false,
          List.of(),
          null);
    }
    S3Credentials parsed;
    try {
      parsed = S3Credentials.parse(credentials);
    } catch (S3Credentials.InvalidCredentialsFormatException e) {
      return new Probe(false, e.getMessage(), false, List.of(), null);
    }
    S3Connection connection =
        new S3Connection(
            endpoint,
            settings.effectiveRegion(),
            settings.pathStyle(),
            parsed,
            proxyConfig.proxyHost(),
            proxyConfig.proxyPort(),
            insecureSsl);
    try (S3ObjectStore store = clientFactory.create(connection, settings.scopes())) {
      List<S3ScopeCheck> checks = new ArrayList<>();
      for (S3Scope scope : settings.scopes()) {
        checks.add(toCheck(scope, store.testAccess(scope)));
      }
      return summarize(checks);
    } catch (S3AccessException e) {
      return new Probe(false, withTlsHint(e), false, List.of(), null);
    }
  }

  /**
   * The buckets {@code credentials} may see. A key without {@code s3:ListAllMyBuckets} is answered
   * with the fallback, never an error; a refused key, a blocked or unreachable endpoint is the
   * caller's 400 with the access layer's message.
   */
  public S3BucketListResult listBuckets(
      String rawUrl,
      String proxy,
      String credentials,
      boolean insecureSsl,
      String region,
      boolean pathStyle)
      throws InterruptedException {
    URI endpoint = normalizeEndpoint(rawUrl);
    ProxyAndCredentials proxyConfig = parseProxy(proxy);
    S3Credentials parsed;
    try {
      parsed = S3Credentials.parse(credentials);
    } catch (S3Credentials.InvalidCredentialsFormatException e) {
      throw new ValidationException(e.getMessage());
    }
    S3Connection connection =
        new S3Connection(
            endpoint,
            region,
            pathStyle,
            parsed,
            proxyConfig.proxyHost(),
            proxyConfig.proxyPort(),
            insecureSsl);
    try (S3ObjectStore store = clientFactory.create(connection, List.of())) {
      return switch (store.listBuckets()) {
        case S3BucketListing.Listed listed -> new S3BucketListResult(true, listed.names(), null);
        case S3BucketListing.NotPermitted ignored ->
            new S3BucketListResult(false, List.of(), BUCKETS_NOT_LISTABLE);
      };
    } catch (S3AccessException e) {
      throw new ValidationException(withTlsHint(e));
    }
  }

  private static S3ScopeCheck toCheck(S3Scope scope, S3AccessCheck check) {
    return new S3ScopeCheck(
        scope.bucket(),
        scope.prefix(),
        check.bucketReachable(),
        check.listAllowed(),
        check.readAllowed(),
        check.objectCount(),
        check.objectCountIsLowerBound(),
        check.failure() == null ? null : withTlsHint(check.failure()));
  }

  /**
   * One verdict over all scopes: refused credentials end the test as such; otherwise the test
   * passes only when every scope reached its bucket, listed it and read from it (or had nothing to
   * read), and the message names the first scope that did not.
   */
  private static Probe summarize(List<S3ScopeCheck> checks) {
    boolean anyRefusedKey = false;
    boolean anyAccepted = false;
    long objects = 0;
    boolean lowerBound = false;
    List<S3ScopeCheck> failed = new ArrayList<>();
    for (S3ScopeCheck check : checks) {
      if (check.message() != null && check.message().contains("Zugangsdaten abgelehnt")) {
        anyRefusedKey = true;
      }
      if (check.bucketReachable() || check.listAllowed()) {
        anyAccepted = true;
      }
      if (!check.passed()) {
        failed.add(check);
      }
      objects += check.objectCount();
      lowerBound |= check.objectCountIsLowerBound();
    }
    if (anyRefusedKey) {
      return new Probe(false, failed.get(0).message(), false, checks, null);
    }
    if (!failed.isEmpty()) {
      S3ScopeCheck first = failed.get(0);
      String message =
          "Bereich „"
              + key(first)
              + "“: "
              + first.message()
              + (failed.size() > 1
                  ? " ("
                      + (failed.size() - 1)
                      + " weitere Bereiche mit Befund, siehe Einzelheiten.)"
                  : "");
      return new Probe(false, message, anyAccepted, checks, null);
    }
    String count =
        objects == 1
            ? "1 Objekt"
            : objects
                + " Objekte"
                + (lowerBound ? " (mindestens; nur die erste Seite gezählt)" : "");
    String message =
        (checks.size() == 1 ? "Der Bereich ist" : "Alle " + checks.size() + " Bereiche sind")
            + " erreichbar, Auflistung und Lesen sind erlaubt. "
            + count
            + " gefunden.";
    return new Probe(true, message, true, checks, objects);
  }

  private static String key(S3ScopeCheck check) {
    return check.prefix().isEmpty() ? check.bucket() : check.bucket() + "/" + check.prefix();
  }

  private static String withTlsHint(S3AccessException e) {
    return e instanceof S3AccessException.Tls ? e.getMessage() + TLS_HINT : e.getMessage();
  }

  private static URI normalizeEndpoint(String rawUrl) {
    try {
      return S3Connection.normalizeEndpoint(rawUrl);
    } catch (S3Connection.InvalidEndpointException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  private static ProxyAndCredentials parseProxy(String proxy) {
    try {
      return ProxyAndCredentials.parse(proxy, null);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
  }
}
