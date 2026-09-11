package io.opaa.indexing.source.s3;

import io.opaa.indexing.source.RequestBudgetExhaustedException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.InvalidObjectStateException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Turns every failure of an SDK call into one of the {@link S3AccessException} kinds - by modeled
 * exception type first, then by error code, then by HTTP status in the light of the {@link
 * S3Operation}. The SDK's own exception is never attached: its message can carry the request URL
 * and the access key id. Shared by the connector's store and the upload storage (ADR-0030,
 * Entscheidung 8); {@code allowlistHint} names the allowlist variable of the side that owns the
 * client, so a refused target tells the operator which setting unblocks it.
 */
public final class S3FailureTranslator {

  private static final Logger log = LoggerFactory.getLogger(S3FailureTranslator.class);

  private static final Set<String> AUTH_CODES =
      Set.of(
          "InvalidAccessKeyId",
          "SignatureDoesNotMatch",
          "InvalidToken",
          "ExpiredToken",
          "TokenRefreshRequired",
          "InvalidSecurity",
          "UnrecognizedClientException");
  private static final Set<String> REDIRECT_CODES =
      Set.of(
          "PermanentRedirect",
          "TemporaryRedirect",
          "AuthorizationHeaderMalformed",
          "IllegalLocationConstraintException");
  private static final Set<String> THROTTLE_CODES =
      Set.of(
          "SlowDown",
          "Throttling",
          "ThrottlingException",
          "RequestLimitExceeded",
          "TooManyRequests");

  private final Duration requestTimeout;
  private final int maxRetries;
  private final String allowlistHint;

  public S3FailureTranslator(Duration requestTimeout, int maxRetries, String allowlistHint) {
    this.requestTimeout = requestTimeout;
    this.maxRetries = maxRetries;
    this.allowlistHint = allowlistHint;
  }

  /** The translator of a library's store under the access layer's bounds and allowlist hint. */
  public static S3FailureTranslator forConnector(S3Properties properties) {
    return new S3FailureTranslator(
        properties.requestTimeout(),
        properties.maxRetries(),
        io.opaa.sourceaccess.TargetAddressValidator.ALLOWLIST_HINT);
  }

  /**
   * Runs {@code action} and translates whatever it throws: an interruption surfaces as {@link
   * InterruptedException} with the flag restored, a spent request budget as {@link
   * RequestBudgetExhaustedException}, an {@link S3AccessException} unchanged, anything else through
   * {@link #translate}.
   */
  public <T> T call(S3Operation op, String bucket, String key, Callable<T> action)
      throws S3AccessException, InterruptedException {
    try {
      return action.call();
    } catch (AbortedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedException("S3 request interrupted");
    } catch (S3AccessException e) {
      throw e;
    } catch (Exception e) {
      S3RequestGuard.BudgetSignal budget = findCause(e, S3RequestGuard.BudgetSignal.class);
      if (budget != null) {
        throw RequestBudgetExhaustedException.requests(budget.budget());
      }
      S3AccessException translated = translate(op, bucket, key, e);
      // the translated message is credential-free by contract; the SDK's own text is not logged
      log.debug(
          "S3 {} on {}{} failed: {} ({})",
          op.name(),
          bucket,
          key == null ? "" : "/" + key,
          translated.getMessage(),
          e.getClass().getSimpleName());
      throw translated;
    }
  }

  public S3AccessException translate(S3Operation op, String bucket, String key, Throwable e) {
    S3RequestGuard.TargetSignal target = findCause(e, S3RequestGuard.TargetSignal.class);
    if (target != null) {
      return target.unknownHost()
          ? new S3AccessException.Unreachable(target.getMessage())
          : new S3AccessException.TargetBlocked(target.getMessage(), allowlistHint);
    }
    if (e instanceof NoSuchBucketException && op != S3Operation.LIST_BUCKETS) {
      return new S3AccessException.BucketNotFound(bucket);
    }
    if (e instanceof NoSuchKeyException) {
      return new S3AccessException.ObjectNotFound(bucket, key);
    }
    if (e instanceof InvalidObjectStateException) {
      return new S3AccessException.Archived(bucket, key);
    }
    if (e instanceof AwsServiceException service) {
      return translateService(op, bucket, key, service);
    }
    if (e instanceof SdkClientException) {
      if (findCause(e, SSLException.class) != null) {
        return new S3AccessException.Tls();
      }
      if (findCause(e, UnknownHostException.class) != null) {
        return new S3AccessException.Unreachable(
            "Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).");
      }
      if (findCause(e, ConnectException.class) != null) {
        return new S3AccessException.Unreachable("Die Verbindung wurde abgelehnt.");
      }
      if (e instanceof ApiCallAttemptTimeoutException
          || e instanceof ApiCallTimeoutException
          || findCause(e, SocketTimeoutException.class) != null) {
        return new S3AccessException.Unreachable(
            "Zeitüberschreitung nach " + requestTimeout.toSeconds() + " Sekunden.");
      }
      IOException io = findCause(e, IOException.class);
      if (io != null) {
        return new S3AccessException.Unreachable(
            "Verbindungsfehler (" + io.getClass().getSimpleName() + ").");
      }
      return new S3AccessException(
          "Der Objektspeicher konnte nicht angesprochen werden ("
              + e.getClass().getSimpleName()
              + ").");
    }
    return new S3AccessException(
        "Unerwarteter Fehler beim Zugriff auf den Objektspeicher ("
            + e.getClass().getSimpleName()
            + ").");
  }

  private S3AccessException translateService(
      S3Operation op, String bucket, String key, AwsServiceException e) {
    String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    int status = e.statusCode();
    if (status == 301 || status == 307 || (code != null && REDIRECT_CODES.contains(code))) {
      return op == S3Operation.LIST_BUCKETS
          ? new S3AccessException.WrongRegionOrStyle()
          : new S3AccessException.WrongRegionOrStyle(bucket);
    }
    if (op == S3Operation.LIST_BUCKETS && (status == 404 || "NoSuchBucket".equals(code))) {
      // the listing names no bucket: a 404 here means the address is no S3 endpoint at all
      return new S3AccessException(
          "Der Endpoint antwortet nicht wie ein S3-Objektspeicher (HTTP 404 auf die"
              + " Bucket-Liste).");
    }
    if ("RequestTimeTooSkewed".equals(code) || e.isClockSkewException()) {
      return new S3AccessException.ClockSkew();
    }
    if (status == 401 || (code != null && AUTH_CODES.contains(code))) {
      return new S3AccessException.Authentication(code == null ? "HTTP " + status : code);
    }
    if (status == 429
        || (code != null && THROTTLE_CODES.contains(code))
        || e.isThrottlingException()) {
      return new S3AccessException.RateLimited(maxRetries);
    }
    if ("InvalidObjectState".equals(code)) {
      return new S3AccessException.Archived(bucket, key);
    }
    if ("NoSuchBucket".equals(code)) {
      return new S3AccessException.BucketNotFound(bucket);
    }
    if ("NoSuchKey".equals(code)) {
      return new S3AccessException.ObjectNotFound(bucket, key);
    }
    if (status == 403) {
      return op.forbidden(bucket, key);
    }
    if (status == 404) {
      return op.notFound(bucket, key);
    }
    return new S3AccessException(
        "Der Objektspeicher antwortete auf "
            + op.german()
            + " mit HTTP "
            + status
            + (code == null ? "" : " (" + code + ")")
            + ".");
  }

  private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return null;
  }
}
