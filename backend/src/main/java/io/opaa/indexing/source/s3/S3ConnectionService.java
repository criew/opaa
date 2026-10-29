package io.opaa.indexing.source.s3;

import io.opaa.common.ValidationException;
import io.opaa.s3.S3AccessException;
import io.opaa.s3.S3Connection;
import io.opaa.s3.S3Credentials;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The connection test and the bucket listing for an S3 library (ADR-0027, Entscheidung 8), on top
 * of the access layer: every scope is probed in three steps - bucket reachable, listing allowed,
 * reading allowed - through {@link S3ObjectStore#testAccess}, and a store problem is the test's
 * <em>result</em>, never an exception; only a caller's own mistake (an address that is no endpoint,
 * missing settings) is a 400.
 *
 * <p>The probe is bounded like every other synchronous connection test: its store uses {@link
 * S3ClientFactory#createForProbe} (short timeout, one retry) and the whole test stops after {@link
 * #PROBE_DEADLINE}, reporting the remaining scopes as not probed - so a caller cannot bind a
 * request thread for long against a store that swallows packets. Messages come from the access
 * layer and are German and credential-free by its contract.
 */
@Service
public class S3ConnectionService {

  /** After this much of one test the remaining scopes are reported as not probed. */
  static final Duration PROBE_DEADLINE = Duration.ofSeconds(30);

  static final String BUCKETS_NOT_LISTABLE =
      "Die Bucket-Liste ist mit diesen Zugangsdaten nicht lesbar (s3:ListAllMyBuckets fehlt) - der"
          + " Bucket-Name kann von Hand eingetragen werden.";

  static final String NOT_PROBED =
      "Nicht geprüft: das Zeitlimit des Verbindungstests war vor diesem Bereich erreicht.";

  private final S3ClientFactory clientFactory;
  private final Clock clock;

  /** {@code clock} bounds one connection test by {@link #PROBE_DEADLINE}. */
  public S3ConnectionService(S3ClientFactory clientFactory, Clock clock) {
    this.clientFactory = clientFactory;
    this.clock = clock;
  }

  /**
   * @param credentialsVerified {@code false} when the store refused the key or none was usable as
   *     given (missing, malformed); {@code true} once any scope got an answer that presupposes a
   *     valid signature, also {@code AccessDenied}; {@code null} when no scope got that far
   *     (blocked, unreachable, TLS, region or addressing style, clock, throttling, server error)
   * @param objectCount the objects the first page of every scope showed, {@code null} unless every
   *     scope passed
   */
  public record Probe(
      boolean reachable,
      String message,
      Boolean credentialsVerified,
      List<S3ScopeCheck> scopes,
      Long objectCount) {}

  /**
   * Probes every scope of {@code settings} against the endpoint. {@code credentials} is the stored
   * form; a missing or malformed value is a result, not an exception, because the wizard reaches
   * this before the key is complete.
   */
  public Probe probe(String rawUrl, String credentials, S3SourceSettings settings)
      throws InterruptedException {
    if (settings == null) {
      throw new ValidationException("sourceSettings sind für den Verbindungstest erforderlich");
    }
    URI endpoint = normalizeEndpoint(rawUrl);
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
        new S3Connection(endpoint, settings.effectiveRegion(), settings.pathStyle(), parsed);
    Instant deadline = clock.instant().plus(PROBE_DEADLINE);
    try (S3ObjectStore store = clientFactory.createForProbe(connection, settings.scopes())) {
      List<ScopeFinding> findings = new ArrayList<>();
      for (S3Scope scope : settings.scopes()) {
        if (!findings.isEmpty() && clock.instant().isAfter(deadline)) {
          findings.add(new ScopeFinding(scope, null));
          continue;
        }
        findings.add(new ScopeFinding(scope, store.testAccess(scope)));
      }
      return summarize(findings);
    } catch (S3AccessException e) {
      return new Probe(
          false,
          e.getMessage(),
          e instanceof S3AccessException.Authentication ? Boolean.FALSE : null,
          List.of(),
          null);
    }
  }

  /**
   * The buckets {@code credentials} may see. A key without {@code s3:ListAllMyBuckets} is answered
   * with the fallback, never an error; a refused key, a blocked or unreachable endpoint is the
   * caller's 400 with the access layer's message.
   */
  public S3BucketListResult listBuckets(
      String rawUrl, String credentials, String region, boolean pathStyle)
      throws InterruptedException {
    URI endpoint = normalizeEndpoint(rawUrl);
    S3Credentials parsed;
    try {
      parsed = S3Credentials.parse(credentials);
    } catch (S3Credentials.InvalidCredentialsFormatException e) {
      throw new ValidationException(e.getMessage());
    }
    S3Connection connection = new S3Connection(endpoint, region, pathStyle, parsed);
    try (S3ObjectStore store = clientFactory.createForProbe(connection, List.of())) {
      return switch (store.listBuckets()) {
        case S3BucketListing.Listed listed -> new S3BucketListResult(true, listed.names(), null);
        case S3BucketListing.NotPermitted ignored ->
            new S3BucketListResult(false, List.of(), BUCKETS_NOT_LISTABLE);
      };
    } catch (S3AccessException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  /**
   * "Alle Buckets" as a run would resolve it now, each listed bucket probed once within {@link
   * #PROBE_DEADLINE}; {@code null} when the key may not list the buckets. A refused key, a blocked
   * or unreachable endpoint is the access layer's exception for the caller to render. The test
   * knows no indexed documents, so it reports an unreadable bucket as left out, never as withheld.
   */
  S3AllBuckets.Resolution resolveAllBuckets(
      String rawUrl, String credentials, String region, boolean pathStyle)
      throws S3AccessException, InterruptedException {
    URI endpoint = normalizeEndpoint(rawUrl);
    S3Credentials parsed;
    try {
      parsed = S3Credentials.parse(credentials);
    } catch (S3Credentials.InvalidCredentialsFormatException e) {
      throw new ValidationException(e.getMessage());
    }
    S3Connection connection = new S3Connection(endpoint, region, pathStyle, parsed);
    Instant deadline = clock.instant().plus(PROBE_DEADLINE);
    try (S3ObjectStore store = clientFactory.createForProbe(connection, List.of())) {
      if (!(store.listBuckets() instanceof S3BucketListing.Listed listed)) {
        return null;
      }
      List<S3Scope> scopes = new ArrayList<>();
      for (String name : listed.names()) {
        try {
          scopes.add(S3Scope.of(name, ""));
        } catch (S3Scope.InvalidS3ScopeException e) {
          // left out with its reason by S3AllBuckets
        }
      }
      clientFactory.validateTargets(connection, scopes);
      return S3AllBuckets.resolve(
          store, listed, connection.region(), Set.of(), () -> clock.instant().isAfter(deadline));
    }
  }

  /** One scope and what the store said; {@code check} is {@code null} when it was not asked. */
  private record ScopeFinding(S3Scope scope, S3AccessCheck check) {

    S3AccessException failure() {
      return check == null ? null : check.failure();
    }

    boolean probed() {
      return check != null;
    }

    boolean passed() {
      return check != null && check.failure() == null;
    }

    boolean keyRefused() {
      return failure() instanceof S3AccessException.Authentication;
    }

    /** An answer only a store that accepted the signature gives - the key is verified by it. */
    boolean keyAccepted() {
      S3AccessException failure = failure();
      return passed()
          || failure instanceof S3AccessException.BucketNotFound
          || failure instanceof S3AccessException.ListForbidden
          || failure instanceof S3AccessException.ReadForbidden;
    }

    S3ScopeCheck toCheck() {
      if (check == null) {
        return new S3ScopeCheck(
            scope.bucket(), scope.prefix(), false, false, null, 0, false, NOT_PROBED);
      }
      return new S3ScopeCheck(
          scope.bucket(),
          scope.prefix(),
          check.bucketReachable(),
          check.listAllowed(),
          check.readAllowed(),
          check.objectCount(),
          check.objectCountIsLowerBound(),
          check.failure() == null ? null : check.failure().getMessage());
    }
  }

  /**
   * One verdict over all scopes, decided by the failure <em>types</em>: refused credentials end the
   * test as such; otherwise the test passes only when every scope reached its bucket, listed it and
   * read from it (or had nothing to read - said so, never claimed), and the message names the first
   * scope that did not.
   */
  private static Probe summarize(List<ScopeFinding> findings) {
    List<S3ScopeCheck> checks = findings.stream().map(ScopeFinding::toCheck).toList();
    boolean keyRefused = findings.stream().anyMatch(ScopeFinding::keyRefused);
    boolean keyAccepted = findings.stream().anyMatch(ScopeFinding::keyAccepted);
    List<S3ScopeCheck> failed = checks.stream().filter(c -> !c.passed()).toList();
    if (keyRefused) {
      S3ScopeCheck refused =
          checks.get(
              findings.indexOf(
                  findings.stream().filter(ScopeFinding::keyRefused).findFirst().get()));
      return new Probe(false, refused.message(), false, checks, null);
    }
    if (!failed.isEmpty()) {
      S3ScopeCheck first = failed.get(0);
      int others = failed.size() - 1;
      String message =
          "Bereich „"
              + key(first)
              + "“: "
              + first.message()
              + (others == 0
                  ? ""
                  : " ("
                      + (others == 1 ? "1 weiterer Bereich" : others + " weitere Bereiche")
                      + " mit Befund, siehe Einzelheiten.)");
      return new Probe(false, message, keyAccepted ? Boolean.TRUE : null, checks, null);
    }
    long objects = checks.stream().mapToLong(S3ScopeCheck::objectCount).sum();
    boolean lowerBound = checks.stream().anyMatch(S3ScopeCheck::objectCountIsLowerBound);
    long unreadable = checks.stream().filter(c -> c.readAllowed() == null).count();
    String count =
        (objects == 1 ? "1 Objekt" : objects + " Objekte")
            + (lowerBound ? " (mindestens; nur die erste Seite je Bereich gezählt)" : "");
    String reading =
        unreadable == 0
            ? "Auflistung und Lesen sind erlaubt."
            : "Auflistung ist erlaubt; das Leserecht konnte mangels Objekt "
                + (checks.size() == 1
                    ? "nicht geprüft werden."
                    : "in "
                        + unreadable
                        + " von "
                        + checks.size()
                        + " Bereichen nicht geprüft werden.");
    String message =
        (checks.size() == 1 ? "Der Bereich ist" : "Alle " + checks.size() + " Bereiche sind")
            + " erreichbar, "
            + reading
            + " "
            + count
            + " gefunden.";
    return new Probe(true, message, true, checks, objects);
  }

  private static String key(S3ScopeCheck check) {
    return check.prefix().isEmpty() ? check.bucket() : check.bucket() + "/" + check.prefix();
  }

  private static URI normalizeEndpoint(String rawUrl) {
    try {
      return S3Connection.normalizeEndpoint(rawUrl);
    } catch (S3Connection.InvalidEndpointException e) {
      throw new ValidationException(e.getMessage());
    }
  }
}
