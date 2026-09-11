package io.opaa.indexing.source.s3;

import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The one adapter of {@link S3ObjectStore} on the AWS SDK for Java v2 (ADR-0027, Entscheidung 8 and
 * 9), on the client build, request guard and failure translation the upload storage shares
 * (ADR-0030, Entscheidung 8). What is the connector's own: the byte ceiling while a download
 * streams, the {@link SourceRequestMeter} every wire attempt and throttled answer is counted on,
 * and the request budget that ends a run in an orderly way.
 */
final class AwsSdkS3ObjectStore implements S3ObjectStore {

  private final S3Properties properties;
  private final SourceRequestMeter meter = new SourceRequestMeter();
  private final S3RequestGuard guard;
  private final S3FailureTranslator translator;
  private final S3SdkClient client;
  private final S3Client s3;

  AwsSdkS3ObjectStore(
      S3Connection connection,
      S3Properties properties,
      TargetAddressValidator targetAddressValidator,
      int requestBudget,
      Consumer<SdkHttpRequest> requestObserver) {
    this.properties = properties;
    this.guard =
        new S3RequestGuard(
            S3RequestGuard.TargetPolicy.hostOnly(targetAddressValidator),
            meter,
            requestBudget,
            requestObserver);
    this.translator = S3FailureTranslator.forConnector(properties);
    this.client = S3SdkClient.open(S3ClientSettings.of(connection, properties), guard);
    this.s3 = client.s3();
  }

  int requestBudget() {
    return guard.requestBudget();
  }

  @Override
  public SourceRequestMeter meter() {
    return meter;
  }

  @Override
  public S3ListPage listObjects(S3Scope scope, String continuationToken)
      throws S3AccessException, InterruptedException {
    return translator.call(
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
    return translator.call(
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
    return translator.call(
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
      List<String> names =
          translator.call(
              S3Operation.LIST_BUCKETS,
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
      translator.call(
          S3Operation.HEAD_BUCKET,
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
