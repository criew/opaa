package io.opaa.indexing.source.s3;

import io.opaa.sourceaccess.SourceRequestMeter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * An in-memory {@link S3ObjectStore} for the unit tests of everything above the access layer
 * (executor, connection test, events): buckets of objects with a content type, a storage class and
 * an optional archive flag, listed in key order with a configurable page size, and failures a test
 * scripts per bucket, per key or for the next call.
 */
public class FakeS3ObjectStore implements S3ObjectStore {

  /** One stored object. */
  public record StoredObject(
      byte[] bytes,
      String contentType,
      Instant lastModified,
      String storageClass,
      boolean archived) {

    public String eTag() {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private final Map<String, TreeMap<String, StoredObject>> buckets = new LinkedHashMap<>();
  private final Map<String, Supplier<S3AccessException>> bucketFailures = new LinkedHashMap<>();
  private final Map<String, Supplier<S3AccessException>> readFailures = new LinkedHashMap<>();
  private final List<Supplier<S3AccessException>> nextCallFailures =
      java.util.Collections.synchronizedList(new ArrayList<>());
  private final SourceRequestMeter meter = new SourceRequestMeter();
  private final List<Path> landedFiles = java.util.Collections.synchronizedList(new ArrayList<>());
  private final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
  private int pageSize = 1000;
  private boolean bucketListingPermitted = true;
  private boolean closed;

  public FakeS3ObjectStore pageSize(int pageSize) {
    this.pageSize = pageSize;
    return this;
  }

  public FakeS3ObjectStore bucket(String bucket) {
    buckets.computeIfAbsent(bucket, b -> new TreeMap<>());
    return this;
  }

  public FakeS3ObjectStore put(String bucket, String key, String text, String contentType) {
    return put(bucket, key, text.getBytes(StandardCharsets.UTF_8), contentType);
  }

  public FakeS3ObjectStore put(String bucket, String key, byte[] bytes, String contentType) {
    return put(
        bucket,
        key,
        new StoredObject(
            bytes, contentType, Instant.parse("2026-09-01T10:00:00Z"), "STANDARD", false));
  }

  public FakeS3ObjectStore put(String bucket, String key, StoredObject object) {
    bucket(bucket).buckets.get(bucket).put(key, object);
    return this;
  }

  public FakeS3ObjectStore remove(String bucket, String key) {
    Optional.ofNullable(buckets.get(bucket)).ifPresent(objects -> objects.remove(key));
    return this;
  }

  public Optional<StoredObject> stored(String bucket, String key) {
    return Optional.ofNullable(buckets.get(bucket)).map(objects -> objects.get(key));
  }

  /** Every listing and head/get of {@code bucket} fails with {@code failure}. */
  public FakeS3ObjectStore failBucket(String bucket, Supplier<S3AccessException> failure) {
    bucketFailures.put(bucket, failure);
    return this;
  }

  /** Every head/get of {@code bucket/key} fails with {@code failure}; listing still shows it. */
  public FakeS3ObjectStore failRead(
      String bucket, String key, Supplier<S3AccessException> failure) {
    readFailures.put(bucket + "/" + key, failure);
    return this;
  }

  /** The next call - whatever it is - fails with {@code failure}. */
  public FakeS3ObjectStore failNextCall(Supplier<S3AccessException> failure) {
    nextCallFailures.add(failure);
    return this;
  }

  public FakeS3ObjectStore bucketListingPermitted(boolean permitted) {
    this.bucketListingPermitted = permitted;
    return this;
  }

  /** Every call in order, e.g. {@code list docs/2025/}, {@code head docs/a.pdf}. */
  public List<String> calls() {
    return calls;
  }

  public boolean isClosed() {
    return closed;
  }

  /** Every temp file a download of this store wrote, whether or not a caller consumed it. */
  public List<Path> landedFiles() {
    return List.copyOf(landedFiles);
  }

  private void record(String call) throws S3AccessException {
    calls.add(call);
    meter.recordRequest();
    if (!nextCallFailures.isEmpty()) {
      throw nextCallFailures.remove(0).get();
    }
  }

  private TreeMap<String, StoredObject> objectsOf(String bucket) throws S3AccessException {
    Supplier<S3AccessException> failure = bucketFailures.get(bucket);
    if (failure != null) {
      throw failure.get();
    }
    TreeMap<String, StoredObject> objects = buckets.get(bucket);
    if (objects == null) {
      throw new S3AccessException.BucketNotFound(bucket);
    }
    return objects;
  }

  private StoredObject objectOf(String bucket, String key) throws S3AccessException {
    StoredObject object = objectsOf(bucket).get(key);
    Supplier<S3AccessException> failure = readFailures.get(bucket + "/" + key);
    if (failure != null) {
      throw failure.get();
    }
    if (object == null) {
      throw new S3AccessException.ObjectNotFound(bucket, key);
    }
    return object;
  }

  @Override
  public S3ListPage listObjects(S3Scope scope, String continuationToken) throws S3AccessException {
    record("list " + scope.key() + (continuationToken == null ? "" : " @" + continuationToken));
    List<String> keys =
        objectsOf(scope.bucket()).keySet().stream().filter(scope::contains).toList();
    int start = continuationToken == null ? 0 : Integer.parseInt(continuationToken);
    int end = Math.min(start + pageSize, keys.size());
    List<S3ObjectSummary> page = new ArrayList<>();
    for (String key : keys.subList(start, end)) {
      StoredObject object = buckets.get(scope.bucket()).get(key);
      page.add(
          new S3ObjectSummary(
              key,
              object.eTag(),
              object.bytes().length,
              object.lastModified(),
              object.storageClass()));
    }
    return new S3ListPage(page, end < keys.size() ? Integer.toString(end) : null);
  }

  @Override
  public S3ObjectHead headObject(String bucket, String key) throws S3AccessException {
    record("head " + bucket + "/" + key);
    StoredObject object = objectOf(bucket, key);
    return new S3ObjectHead(
        object.contentType(),
        object.eTag(),
        object.bytes().length,
        object.lastModified(),
        object.storageClass(),
        object.archived(),
        Map.of());
  }

  @Override
  public S3Download getObject(String bucket, String key) throws S3AccessException {
    return getObject(bucket, key, S3Properties.defaults().maxObjectSizeBytes());
  }

  @Override
  public S3Download getObject(String bucket, String key, long maxBytes) throws S3AccessException {
    record("get " + bucket + "/" + key);
    StoredObject object = objectOf(bucket, key);
    if (object.archived()) {
      throw new S3AccessException.Archived(bucket, key);
    }
    if (object.bytes().length > maxBytes) {
      throw new S3AccessException.ObjectTooLarge(bucket, key, maxBytes);
    }
    try {
      Path file = Files.createTempFile("opaa-s3-fake-", ".bin");
      Files.write(file, object.bytes());
      landedFiles.add(file);
      meter.recordBytes(object.bytes().length);
      return new S3Download(
          file, object.contentType(), object.eTag(), object.bytes().length, object.lastModified());
    } catch (IOException e) {
      throw new S3AccessException.Unreachable(e.getMessage());
    }
  }

  @Override
  public S3BucketListing listBuckets() throws S3AccessException {
    record("listBuckets");
    return bucketListingPermitted
        ? new S3BucketListing.Listed(List.copyOf(buckets.keySet()))
        : new S3BucketListing.NotPermitted();
  }

  @Override
  public S3AccessCheck testAccess(S3Scope scope) throws S3AccessException {
    record("testAccess " + scope.key());
    S3ListPage page;
    try {
      page = listObjects(scope, null);
    } catch (S3AccessException.BucketNotFound | S3AccessException.Authentication e) {
      // like the adapter: a missing bucket or a refused key reports the bucket as not reached
      return new S3AccessCheck(false, false, null, 0, false, e);
    } catch (S3AccessException e) {
      return new S3AccessCheck(true, false, null, 0, false, e);
    }
    Optional<S3ObjectSummary> first =
        page.objects().stream().filter(o -> !o.isFolderMarker()).findFirst();
    if (first.isEmpty()) {
      return new S3AccessCheck(true, true, null, page.objects().size(), !page.isLast(), null);
    }
    try {
      headObject(scope.bucket(), first.get().key());
    } catch (S3AccessException e) {
      return new S3AccessCheck(true, true, false, page.objects().size(), !page.isLast(), e);
    }
    return new S3AccessCheck(true, true, true, page.objects().size(), !page.isLast(), null);
  }

  @Override
  public SourceRequestMeter meter() {
    return meter;
  }

  @Override
  public void close() {
    closed = true;
  }
}
