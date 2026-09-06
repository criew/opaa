package io.opaa.indexing.source.s3;

import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.http.apache5.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.InvalidObjectStateException;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.utils.AttributeMap;

/**
 * The one adapter of {@link S3ObjectStore} on the AWS SDK for Java v2 (ADR-0027, Entscheidung 8 and
 * 9). What {@code io.opaa.sourceaccess} enforces for the HTTP connectors is rebuilt here on the SDK
 * client: static credentials and an explicit region (the SDK's own resolution chains never run),
 * the target validation of every request's host in {@link Guard}, the byte ceiling while a download
 * streams, one timeout for connection, socket and attempt, proxy and relaxed TLS from the library's
 * configuration, exponential retries on {@code 503 SlowDown}/{@code 429} counted on the {@link
 * S3RequestMeter}, and the mapping of every failure to a German message without a credential and
 * without the SDK's own exception as cause.
 */
final class AwsSdkS3ObjectStore implements S3ObjectStore {

  private static final Logger log = LoggerFactory.getLogger(AwsSdkS3ObjectStore.class);

  private static final ExecutionAttribute<Instant> THROTTLED_AT =
      new ExecutionAttribute<>("opaa.s3.throttledAt");
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);

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

  private enum Operation {
    LIST_OBJECTS("die Auflistung"),
    HEAD_BUCKET("die Bucket-Prüfung"),
    HEAD_OBJECT("die Objektprüfung"),
    GET_OBJECT("den Download"),
    LIST_BUCKETS("die Bucket-Liste");

    private final String german;

    Operation(String german) {
      this.german = german;
    }
  }

  private final S3Connection connection;
  private final S3Properties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final int requestBudget;
  private final Consumer<SdkHttpRequest> requestObserver;
  private final S3RequestMeter meter = new S3RequestMeter();
  private final SdkHttpClient httpClient;
  private final S3Client s3;

  AwsSdkS3ObjectStore(
      S3Connection connection,
      S3Properties properties,
      TargetAddressValidator targetAddressValidator,
      int requestBudget,
      Consumer<SdkHttpRequest> requestObserver) {
    this.connection = connection;
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.requestBudget = requestBudget;
    this.requestObserver = requestObserver;
    Duration timeout = properties.requestTimeout();
    this.httpClient = buildHttpClient(connection, timeout);
    try {
      this.s3 =
          S3Client.builder()
              .endpointOverride(connection.endpoint())
              .region(Region.of(connection.region()))
              .forcePathStyle(connection.pathStyle())
              .credentialsProvider(StaticCredentialsProvider.create(awsCredentials(connection)))
              .httpClient(httpClient)
              // S3-compatible stores do not all understand the SDK's newer request checksums
              .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
              .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
              .overrideConfiguration(
                  override ->
                      override
                          .retryStrategy(retryStrategy(properties))
                          .apiCallAttemptTimeout(timeout)
                          .addExecutionInterceptor(new Guard()))
              .build();
    } catch (RuntimeException e) {
      httpClient.close();
      throw e;
    }
  }

  private static SdkHttpClient buildHttpClient(S3Connection connection, Duration timeout) {
    Apache5HttpClient.Builder http =
        Apache5HttpClient.builder().connectionTimeout(timeout).socketTimeout(timeout);
    if (connection.proxyHost() != null && !connection.proxyHost().isBlank()) {
      http.proxyConfiguration(
          ProxyConfiguration.builder()
              .endpoint(
                  URI.create("http://" + connection.proxyHost() + ":" + connection.proxyPort()))
              .useSystemPropertyValues(false)
              .useEnvironmentVariableValues(false)
              .build());
    }
    return http.buildWithDefaults(
        AttributeMap.builder()
            .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, connection.insecureSsl())
            .build());
  }

  private static AwsCredentials awsCredentials(S3Connection connection) {
    S3Credentials c = connection.credentials();
    return c.hasSessionToken()
        ? AwsSessionCredentials.create(c.accessKey(), c.secretKey(), c.sessionToken())
        : AwsBasicCredentials.create(c.accessKey(), c.secretKey());
  }

  private static RetryStrategy retryStrategy(S3Properties properties) {
    Duration base = properties.retryBackoff();
    Duration max = base.multipliedBy(1L << Math.min(properties.maxRetries(), 10));
    if (max.compareTo(MAX_BACKOFF) > 0) {
      max = MAX_BACKOFF;
    }
    BackoffStrategy backoff = BackoffStrategy.exponentialDelay(base, max);
    return AwsRetryStrategy.standardRetryStrategy().toBuilder()
        .maxAttempts(properties.maxRetries() + 1)
        .backoffStrategy(backoff)
        .throttlingBackoffStrategy(backoff)
        .circuitBreakerEnabled(false)
        .build();
  }

  int requestBudget() {
    return requestBudget;
  }

  @Override
  public S3RequestMeter meter() {
    return meter;
  }

  @Override
  public S3ListPage listObjects(S3Scope scope, String continuationToken)
      throws S3AccessException, InterruptedException {
    return call(
        Operation.LIST_OBJECTS,
        scope.bucket(),
        null,
        () -> {
          ListObjectsV2Request.Builder request =
              ListObjectsV2Request.builder()
                  .bucket(scope.bucket())
                  .maxKeys(properties.listPageSize())
                  .continuationToken(continuationToken);
          if (!scope.prefix().isEmpty()) {
            request.prefix(scope.prefix());
          }
          ListObjectsV2Response response = s3.listObjectsV2(request.build());
          List<S3ObjectSummary> objects = new ArrayList<>();
          for (S3Object object : response.contents()) {
            objects.add(
                new S3ObjectSummary(
                    object.key(),
                    unquote(object.eTag()),
                    object.size() == null ? 0 : object.size(),
                    object.lastModified(),
                    object.storageClassAsString()));
          }
          boolean truncated = Boolean.TRUE.equals(response.isTruncated());
          String next = response.nextContinuationToken();
          if (truncated && (next == null || next.isBlank())) {
            // never reported as the last page: a full sync would take the gap for deletions
            throw new S3AccessException.ListingIncomplete(scope.bucket());
          }
          return new S3ListPage(objects, truncated ? next : null);
        });
  }

  @Override
  public S3ObjectHead headObject(String bucket, String key)
      throws S3AccessException, InterruptedException {
    return call(
        Operation.HEAD_OBJECT,
        bucket,
        key,
        () -> {
          HeadObjectResponse head =
              s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
          return new S3ObjectHead(
              head.contentType(),
              unquote(head.eTag()),
              head.contentLength() == null ? 0 : head.contentLength(),
              head.lastModified(),
              head.storageClassAsString(),
              S3ArchiveState.isArchived(
                  head.storageClassAsString(), head.archiveStatusAsString(), head.restore()),
              head.metadata());
        });
  }

  @Override
  public S3Download getObject(String bucket, String key)
      throws S3AccessException, InterruptedException {
    return getObject(bucket, key, properties.maxObjectSizeBytes());
  }

  @Override
  public S3Download getObject(String bucket, String key, long maxBytes)
      throws S3AccessException, InterruptedException {
    return call(
        Operation.GET_OBJECT,
        bucket,
        key,
        () -> {
          Path target = Files.createTempFile(properties.tempDirectory(), "opaa-s3-", suffixOf(key));
          boolean stored = false;
          try (ResponseInputStream<GetObjectResponse> body =
              s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            GetObjectResponse response = body.response();
            Long declared = response.contentLength();
            if (declared != null && declared > maxBytes) {
              body.abort();
              throw new S3AccessException.ObjectTooLarge(bucket, key, maxBytes);
            }
            long copied;
            try (OutputStream out = Files.newOutputStream(target)) {
              copied = BoundedStreams.input(body, maxBytes).transferTo(out);
            } catch (BoundedStreams.LimitExceededException e) {
              // the bytes read up to the ceiling were real traffic: count them, then drop the
              // connection instead of draining the rest of the body
              meter.recordBytes(Files.size(target));
              body.abort();
              throw new S3AccessException.ObjectTooLarge(bucket, key, maxBytes);
            }
            meter.recordBytes(copied);
            stored = true;
            return new S3Download(
                target,
                response.contentType(),
                unquote(response.eTag()),
                copied,
                response.lastModified());
          } finally {
            if (!stored) {
              Files.deleteIfExists(target);
            }
          }
        });
  }

  @Override
  public S3BucketListing listBuckets() throws S3AccessException, InterruptedException {
    try {
      List<String> names =
          call(
              Operation.LIST_BUCKETS,
              "*",
              null,
              () -> s3.listBuckets().buckets().stream().map(Bucket::name).toList());
      return new S3BucketListing.Listed(names);
    } catch (S3AccessException.ListForbidden e) {
      return new S3BucketListing.NotPermitted();
    }
  }

  @Override
  public S3AccessCheck testAccess(S3Scope scope) throws S3AccessException, InterruptedException {
    try {
      call(
          Operation.HEAD_BUCKET,
          scope.bucket(),
          null,
          () -> s3.headBucket(HeadBucketRequest.builder().bucket(scope.bucket()).build()));
    } catch (S3AccessException.ListForbidden e) {
      // a HEAD answer carries no error code, so a 403 here may be a rights gap or a refused key;
      // the listing below answers with a body and settles it
    } catch (S3AccessException e) {
      return new S3AccessCheck(false, false, null, 0, false, e);
    }
    S3ListPage page;
    try {
      page = listObjects(scope, null);
    } catch (S3AccessException.Authentication e) {
      return new S3AccessCheck(false, false, null, 0, false, e);
    } catch (S3AccessException e) {
      return new S3AccessCheck(true, false, null, 0, false, e);
    }
    int count = page.objects().size();
    boolean lowerBound = !page.isLast();
    Optional<S3ObjectSummary> first =
        page.objects().stream().filter(o -> !o.isFolderMarker()).findFirst();
    if (first.isEmpty()) {
      return new S3AccessCheck(true, true, null, count, lowerBound, null);
    }
    try {
      headObject(scope.bucket(), first.get().key());
    } catch (S3AccessException e) {
      return new S3AccessCheck(true, true, false, count, lowerBound, e);
    }
    return new S3AccessCheck(true, true, true, count, lowerBound, null);
  }

  @Override
  public void close() {
    s3.close();
    httpClient.close();
  }

  private <T> T call(Operation op, String bucket, String key, Callable<T> action)
      throws S3AccessException, InterruptedException {
    try {
      return action.call();
    } catch (AbortedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedException("S3 request interrupted");
    } catch (S3AccessException e) {
      throw e;
    } catch (Exception e) {
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

  /**
   * Every SDK failure becomes one of the {@link S3AccessException} kinds - by modeled exception
   * type first, then by error code, then by HTTP status in the light of the operation. The SDK's
   * own exception is never attached: its message can carry the request URL and the access key id.
   */
  private S3AccessException translate(Operation op, String bucket, String key, Throwable e) {
    BudgetSignal budget = findCause(e, BudgetSignal.class);
    if (budget != null) {
      return new S3AccessException.BudgetExhausted(budget.budget);
    }
    TargetSignal target = findCause(e, TargetSignal.class);
    if (target != null) {
      return target.unknownHost
          ? new S3AccessException.Unreachable(target.getMessage())
          : new S3AccessException.TargetBlocked(target.getMessage());
    }
    if (e instanceof NoSuchBucketException) {
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
            "Zeitüberschreitung nach " + properties.requestTimeout().toSeconds() + " Sekunden.");
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
      Operation op, String bucket, String key, AwsServiceException e) {
    String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    int status = e.statusCode();
    if (status == 301 || status == 307 || (code != null && REDIRECT_CODES.contains(code))) {
      return new S3AccessException.WrongRegionOrStyle(bucket);
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
      return new S3AccessException.RateLimited(properties.maxRetries());
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
      return switch (op) {
        case HEAD_OBJECT, GET_OBJECT -> new S3AccessException.ReadForbidden(bucket, key);
        case LIST_OBJECTS, HEAD_BUCKET, LIST_BUCKETS -> new S3AccessException.ListForbidden(bucket);
      };
    }
    if (status == 404) {
      return switch (op) {
        case HEAD_OBJECT, GET_OBJECT -> new S3AccessException.ObjectNotFound(bucket, key);
        case LIST_OBJECTS, HEAD_BUCKET, LIST_BUCKETS ->
            new S3AccessException.BucketNotFound(bucket);
      };
    }
    return new S3AccessException(
        "Der Objektspeicher antwortete auf "
            + op.german
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

  private static String unquote(String eTag) {
    if (eTag == null) {
      return null;
    }
    String value = eTag.strip();
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String suffixOf(String key) {
    String name = key.substring(key.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1 || name.length() - dot > 16) {
      return ".bin";
    }
    String ext = name.substring(dot);
    for (char c : ext.toCharArray()) {
      if (!(Character.isLetterOrDigit(c) || c == '.')) {
        return ".bin";
      }
    }
    return ext;
  }

  /** Raised from the interceptor when the run's request budget is spent. */
  private static final class BudgetSignal extends RuntimeException {
    private final int budget;

    BudgetSignal(int budget) {
      super("request budget of " + budget + " exhausted");
      this.budget = budget;
    }
  }

  /** Raised from the interceptor when a request's host fails the target validation. */
  private static final class TargetSignal extends RuntimeException {
    private final boolean unknownHost;

    TargetSignal(String message, boolean unknownHost) {
      super(message);
      this.unknownHost = unknownHost;
    }
  }

  /**
   * Sees every wire attempt: charges the budget, validates the host the signed request goes to,
   * counts the attempt and notes a throttled answer so the wait before the next attempt is
   * measured. Refusals are {@link NonRetryableException}s the SDK does not retry.
   */
  private final class Guard implements ExecutionInterceptor {

    @Override
    public void beforeTransmission(
        Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
      Instant throttledAt = executionAttributes.getAttribute(THROTTLED_AT);
      if (throttledAt != null && !Instant.EPOCH.equals(throttledAt)) {
        meter.recordThrottleWait(Duration.between(throttledAt, Instant.now()));
        executionAttributes.putAttribute(THROTTLED_AT, Instant.EPOCH);
      }
      SdkHttpRequest request = context.httpRequest();
      try {
        targetAddressValidator.validateHost(request.host());
      } catch (TargetAddressValidator.UnknownTargetHostException e) {
        throw NonRetryableException.create(
            "target unresolved", new TargetSignal(e.getMessage(), true));
      } catch (IOException e) {
        throw NonRetryableException.create(
            "target blocked", new TargetSignal(e.getMessage(), false));
      }
      if (!meter.recordRequestWithin(requestBudget)) {
        throw NonRetryableException.create(
            "request budget exhausted", new BudgetSignal(requestBudget));
      }
      if (requestObserver != null) {
        requestObserver.accept(request);
      }
    }

    @Override
    public void afterTransmission(
        Context.AfterTransmission context, ExecutionAttributes executionAttributes) {
      int status = context.httpResponse().statusCode();
      if (status == 429 || status == 503) {
        meter.recordThrottle();
        executionAttributes.putAttribute(THROTTLED_AT, Instant.now());
        log.debug(
            "S3 endpoint {} answered HTTP {} to {} - retrying with backoff ({} so far)",
            context.httpRequest().host(),
            status,
            context.httpRequest().encodedPath(),
            meter.throttles());
      }
    }
  }
}
