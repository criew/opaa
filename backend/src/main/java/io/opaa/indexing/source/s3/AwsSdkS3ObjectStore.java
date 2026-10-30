package io.opaa.indexing.source.s3;

import io.opaa.compliance.DataFlow;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.job.RunEndingFailures;
import io.opaa.indexing.spi.RenewableCredential;
import io.opaa.outbound.OutboundProxySelector;
import io.opaa.outbound.ProxyAddress;
import io.opaa.s3.S3AccessException;
import io.opaa.s3.S3ClientSettings;
import io.opaa.s3.S3Connection;
import io.opaa.s3.S3Credentials;
import io.opaa.s3.S3FailureTranslator;
import io.opaa.s3.S3Operation;
import io.opaa.s3.S3RequestGuard;
import io.opaa.s3.S3SdkClient;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The one adapter of {@link S3ObjectStore} on the AWS SDK for Java v2 (ADR-0027, Entscheidung 8 and
 * 9), on the client build, request guard and failure translation the upload storage shares
 * (ADR-0030, Entscheidung 8). What is the connector's own: the byte ceiling while a download
 * streams, the {@link SourceRequestMeter} every wire attempt and throttled answer is counted on,
 * and the request budget that ends a run in an orderly way. A run's store signs every request with
 * the credentials valid then.
 */
@DataFlow("source-s3")
final class AwsSdkS3ObjectStore implements S3ObjectStore {

  /** Buckets per ListBuckets page; AWS allows up to 10,000. */
  static final int BUCKET_PAGE_SIZE = 1000;

  private final S3Properties properties;
  private final SourceRequestMeter meter = new SourceRequestMeter();
  private final S3RequestGuard guard;
  private final S3FailureTranslator translator;
  private final S3SdkClient client;
  private final S3Client s3;
  private final RenewableCredential<S3Credentials> current;

  AwsSdkS3ObjectStore(
      S3Connection connection,
      S3Properties properties,
      TargetAddressValidator targetAddressValidator,
      int requestBudget,
      Consumer<SdkHttpRequest> requestObserver) {
    this(connection, null, properties, targetAddressValidator, requestBudget, requestObserver);
  }

  /**
   * {@code current}, when not {@code null}, answers the credentials of every request; a request
   * whose key the store rejected is sent once more when {@code current} was renewed meanwhile.
   */
  AwsSdkS3ObjectStore(
      S3Connection connection,
      RenewableCredential<S3Credentials> current,
      S3Properties properties,
      TargetAddressValidator targetAddressValidator,
      int requestBudget,
      Consumer<SdkHttpRequest> requestObserver) {
    this.properties = properties;
    this.current = current;
    S3ClientSettings settings =
        S3ClientSettings.of(
            connection,
            properties.requestTimeout(),
            properties.maxRetries(),
            properties.retryBackoff());
    Optional<ProxyAddress> outboundProxy =
        OutboundProxySelector.instance().proxyFor(connection.endpoint());
    if (outboundProxy.isPresent()) {
      settings = settings.withProxy(outboundProxy.get().host(), outboundProxy.get().port());
    }
    this.guard =
        new S3RequestGuard(
            S3RequestGuard.TargetPolicy.hostOnly(targetAddressValidator, outboundProxy.isPresent()),
            meter,
            requestBudget,
            requestObserver);
    this.translator =
        new S3FailureTranslator(
            properties.requestTimeout(),
            properties.maxRetries(),
            TargetAddressValidator.ALLOWLIST_HINT,
            RequestBudgetExhaustedException::requests,
            RunEndingFailures::endingCause);
    this.client =
        current == null
            ? S3SdkClient.open(settings, guard)
            : S3SdkClient.open(settings, S3ClientSettings.current(current), guard);
    this.s3 = client.s3();
  }

  int requestBudget() {
    return guard.requestBudget();
  }

  @Override
  public SourceRequestMeter meter() {
    return meter;
  }

  /**
   * {@code action} under the failure translation; a rejected key is retried once when the run's
   * credentials hold another one now.
   */
  private <T> T call(S3Operation op, String bucket, String key, Callable<T> action)
      throws S3AccessException, InterruptedException {
    S3Credentials sent = current == null ? null : current.get();
    try {
      return translator.call(op, bucket, key, action);
    } catch (S3AccessException.Authentication e) {
      if (sent == null || !current.renewedAfterRejection(sent)) {
        throw e;
      }
    }
    return translator.call(op, bucket, key, action);
  }

  /**
   * {@code action} on one object, where a {@code 404} without the code {@code NoSuchKey} in its
   * body counts as {@link S3AccessException.ObjectNotFound} only once a listing confirms the key is
   * gone: a HEAD answer has no body, and a bare {@code 404} may come from a proxy or ingress in
   * front of the store. A key the listing still names, or a listing that fails, is another failure.
   */
  private <T> T confirmingAbsence(S3Operation op, String bucket, String key, Callable<T> action)
      throws S3AccessException, InterruptedException {
    try {
      return call(op, bucket, key, action);
    } catch (S3AccessException.ObjectNotFound notFound) {
      if (notFound.confirmed()) {
        throw notFound;
      }
      boolean listed =
          call(
              S3Operation.LIST_OBJECTS,
              bucket,
              null,
              () ->
                  s3
                      .listObjectsV2(
                          ListObjectsV2Request.builder()
                              .bucket(bucket)
                              .prefix(key)
                              .maxKeys(1)
                              .build())
                      .contents()
                      .stream()
                      .anyMatch(object -> key.equals(object.key())));
      if (listed) {
        throw new S3AccessException(
            "Der Objektspeicher meldet das Objekt „"
                + bucket
                + "/"
                + key
                + "“ als nicht vorhanden (HTTP 404), die Auflistung nennt es aber noch.");
      }
      throw notFound;
    }
  }

  @Override
  public S3ListPage listObjects(S3Scope scope, String continuationToken)
      throws S3AccessException, InterruptedException {
    return call(
        S3Operation.LIST_OBJECTS,
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
    return confirmingAbsence(
        S3Operation.HEAD_OBJECT,
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
    return confirmingAbsence(
        S3Operation.GET_OBJECT,
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
      // AWS names a bucket's region only to a paginated request, never to the bare ListBuckets
      List<Bucket> buckets =
          call(
              S3Operation.LIST_BUCKETS,
              "*",
              null,
              () -> {
                List<Bucket> all = new ArrayList<>();
                s3.listBucketsPaginator(
                        ListBucketsRequest.builder().maxBuckets(BUCKET_PAGE_SIZE).build())
                    .forEach(page -> all.addAll(page.buckets()));
                return all;
              });
      Map<String, String> regions = new HashMap<>();
      for (Bucket bucket : buckets) {
        if (bucket.bucketRegion() != null && !bucket.bucketRegion().isBlank()) {
          regions.put(bucket.name(), bucket.bucketRegion());
        }
      }
      return new S3BucketListing.Listed(buckets.stream().map(Bucket::name).toList(), regions);
    } catch (S3AccessException.ListForbidden e) {
      return new S3BucketListing.NotPermitted();
    }
  }

  @Override
  public void probeListing(String bucket) throws S3AccessException, InterruptedException {
    call(
        S3Operation.LIST_OBJECTS,
        bucket,
        null,
        () -> s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).maxKeys(1).build()));
  }

  @Override
  public S3AccessCheck testAccess(S3Scope scope) throws S3AccessException, InterruptedException {
    try {
      call(
          S3Operation.HEAD_BUCKET,
          scope.bucket(),
          null,
          () -> s3.headBucket(HeadBucketRequest.builder().bucket(scope.bucket()).build()));
    } catch (S3AccessException.ListForbidden | S3AccessException.BucketNotFound e) {
      // a HEAD answer carries no error code, so a 403 or 404 here may also be a refused key;
      // the listing below answers with a body and settles it
    } catch (S3AccessException e) {
      return new S3AccessCheck(false, false, null, 0, false, e);
    }
    S3ListPage page;
    try {
      page = listObjects(scope, null);
    } catch (S3AccessException.Authentication | S3AccessException.BucketNotFound e) {
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
    client.close();
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
}
