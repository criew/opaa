package io.opaa.library;

import io.opaa.indexing.source.s3.S3AccessException;
import io.opaa.indexing.source.s3.S3ClientSettings;
import io.opaa.indexing.source.s3.S3FailureTranslator;
import io.opaa.indexing.source.s3.S3Operation;
import io.opaa.indexing.source.s3.S3RequestGuard;
import io.opaa.indexing.source.s3.S3SdkClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Uploaded originals in an S3-compatible object store (ADR-0030, {@code opaa.upload.store=s3}): one
 * object per document under {@code <keyPrefix><libraryId>/<uuid><extension>}, {@code
 * s3://<bucket>/<key>} as the locator, one client for the whole lifetime of the application, no
 * request budget.
 *
 * <p>Accepting writes a working file under {@code tempDirectory}; only {@link
 * AcceptedUpload#store()} puts it into the bucket, so an upload the checks between the two reject
 * never costs a {@code PutObject}. A caller that needs a local file gets a copy that is removed on
 * every exit of its action. Serving streams the object body without a local copy, which is why HTTP
 * range requests are not available for these originals (Entscheidung 6).
 *
 * <p>Resolving a locator is the key prefix of the library plus {@code HeadObject}: a locator in
 * another bucket, another library's prefix, or one naming no object - an attachment row's synthetic
 * one included - resolves to "not there" without an error. A store that cannot be reached is the
 * other case: reads raise {@link UploadStoreUnavailableException}, a deletion logs and leaves the
 * object for the orphan cleanup (Entscheidung 9).
 */
public class S3UploadedOriginalStore implements UploadedOriginalStore, AutoCloseable {

  /** The {@code opaa.upload.store} value this adapter is selected by. */
  public static final String STORE_NAME = "s3";

  /** Per-attempt timeout of every call; connection, socket and attempt alike. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** Retries after a throttled or transiently failed attempt. */
  static final int MAX_RETRIES = 3;

  static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

  /** The whole reachability probe, retries included, ends within this. */
  static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

  /** Name prefix of every file this adapter writes under {@code tempDirectory}. */
  static final String TEMP_FILE_PREFIX = "opaa-upload-";

  /** {@code MaxKeys} of every {@code ListObjectsV2} page; the S3 maximum. */
  static final int LIST_PAGE_SIZE = 1000;

  private static final Logger log = LoggerFactory.getLogger(S3UploadedOriginalStore.class);

  private final String bucket;
  private final String keyPrefix;
  private final Path tempDirectory;
  private final String endpoint;
  private final int listPageSize;
  private final S3FailureTranslator translator;
  private final S3SdkClient client;
  private final S3Client s3;

  public S3UploadedOriginalStore(UploadS3Properties properties) {
    this(
        properties,
        UploadS3TargetPolicy.of(properties),
        REQUEST_TIMEOUT,
        MAX_RETRIES,
        RETRY_BACKOFF,
        LIST_PAGE_SIZE);
  }

  /**
   * With the bounds of the retry strategy and the listing page size chosen by the caller - for
   * tests against a dead port and for exercising pagination with a handful of objects.
   */
  S3UploadedOriginalStore(
      UploadS3Properties properties,
      S3RequestGuard.TargetPolicy targetPolicy,
      Duration requestTimeout,
      int maxRetries,
      Duration retryBackoff,
      int listPageSize) {
    properties.requireComplete();
    this.bucket = properties.bucket();
    this.keyPrefix = properties.keyPrefix();
    this.tempDirectory = properties.tempDirectory();
    this.endpoint = properties.endpointUri().toString();
    this.listPageSize = listPageSize;
    this.translator =
        new S3FailureTranslator(requestTimeout, maxRetries, UploadS3TargetPolicy.ALLOWLIST_HINT);
    this.client =
        S3SdkClient.open(
            new S3ClientSettings(
                properties.endpointUri(),
                properties.region(),
                properties.pathStyle(),
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()),
                null,
                0,
                false,
                requestTimeout,
                maxRetries,
                retryBackoff),
            S3RequestGuard.targetCheckOnly(targetPolicy));
    this.s3 = client.s3();
  }

  @Override
  public AcceptedUpload accept(UUID libraryId, String extension, InputStream bytes)
      throws IOException {
    Path workingFile = createTempFile(extension);
    try {
      Files.copy(bytes, workingFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException e) {
      deleteQuietly(workingFile);
      throw e;
    }
    return new AcceptedObject(
        libraryId, keyPrefix + libraryId + "/" + UUID.randomUUID() + extension, workingFile);
  }

  @Override
  public Optional<DocumentContent> openForDownload(
      UploadedOriginalRef ref, String fileName, String declaredContentType) {
    Optional<Resolved> resolved = resolve(ref);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    Resolved object = resolved.get();
    ResponseInputStream<GetObjectResponse> body;
    try {
      body =
          translator.call(
              S3Operation.GET_OBJECT,
              bucket,
              object.key(),
              () ->
                  s3.getObject(
                      GetObjectRequest.builder().bucket(bucket).key(object.key()).build()));
    } catch (S3AccessException.ObjectNotFound e) {
      return Optional.empty();
    } catch (S3AccessException e) {
      throw unavailable("download", object.key(), e);
    } catch (InterruptedException e) {
      throw interrupted();
    }
    return Optional.of(
        DocumentContent.ofStream(
            body, fileName, contentTypeFor(declaredContentType, object.head())));
  }

  @Override
  public <T> Optional<T> withLocalFile(UploadedOriginalRef ref, Function<Path, T> action) {
    Optional<Resolved> resolved = resolve(ref);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    String key = resolved.get().key();
    Path copy;
    try {
      copy = createTempFile(suffixOf(key));
    } catch (IOException e) {
      log.warn("Could not create a local copy for object {}/{}", bucket, key, e);
      throw new UploadStoreUnavailableException();
    }
    try {
      try {
        translator.call(
            S3Operation.GET_OBJECT,
            bucket,
            key,
            () -> {
              try (ResponseInputStream<GetObjectResponse> body =
                      s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
                  OutputStream out = Files.newOutputStream(copy)) {
                body.transferTo(out);
              }
              return null;
            });
      } catch (S3AccessException.ObjectNotFound e) {
        return Optional.empty();
      } catch (S3AccessException e) {
        throw unavailable("copy", key, e);
      } catch (InterruptedException e) {
        throw interrupted();
      }
      return Optional.of(action.apply(copy));
    } finally {
      deleteQuietly(copy);
    }
  }

  @Override
  public void delete(UploadedOriginalRef ref) {
    Optional<Resolved> resolved;
    try {
      resolved = resolve(ref);
    } catch (UploadStoreUnavailableException e) {
      log.warn(
          "Could not resolve {} for deletion; the object stays behind for the orphan cleanup",
          ref.locator());
      return;
    }
    if (resolved.isEmpty()) {
      return;
    }
    deleteObjectQuietly(resolved.get().key());
  }

  @Override
  public boolean belongsToLibrary(UploadedOriginalRef ref) {
    return resolve(ref).isPresent();
  }

  @Override
  public void forEachStoredOriginal(UUID libraryId, Consumer<StoredOriginal> visitor) {
    String prefix = keyPrefix + libraryId + "/";
    String continuationToken = null;
    do {
      ListObjectsV2Response page = listPage(prefix, continuationToken);
      for (S3Object object : page.contents()) {
        String locator = locator(object.key());
        // The same containment check resolving goes through, so what is listed can also be
        // deleted: a folder marker under the prefix names no original.
        if (managedKey(new UploadedOriginalRef(libraryId, locator)) == null) {
          continue;
        }
        // A listing without LastModified leaves the age unknown; the moment of the listing is the
        // one value that keeps such an object inside every grace period instead of past it.
        Instant lastModified = object.lastModified();
        visitor.accept(
            new StoredOriginal(
                locator,
                lastModified == null ? Instant.now() : lastModified,
                object.size() == null ? 0 : object.size()));
      }
      continuationToken =
          Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
    } while (continuationToken != null);
  }

  /**
   * One {@code ListObjectsV2} page on {@code prefix}'s own level - the delimiter keeps everything
   * nested deeper out of {@code contents()}, and this adapter never writes there. A page that
   * claims more without naming a continuation token is a failure, not the last page: reported
   * short, every unlisted original would look like it is not there at all.
   */
  private ListObjectsV2Response listPage(String prefix, String continuationToken) {
    try {
      ListObjectsV2Response page =
          translator.call(
              S3Operation.LIST_OBJECTS,
              bucket,
              null,
              () ->
                  s3.listObjectsV2(
                      ListObjectsV2Request.builder()
                          .bucket(bucket)
                          .prefix(prefix)
                          .delimiter("/")
                          .maxKeys(listPageSize)
                          .continuationToken(continuationToken)
                          .build()));
      String next = page.nextContinuationToken();
      if (Boolean.TRUE.equals(page.isTruncated()) && (next == null || next.isBlank())) {
        throw new S3AccessException.ListingIncomplete(bucket);
      }
      return page;
    } catch (S3AccessException e) {
      throw unavailable("list", prefix, e);
    } catch (InterruptedException e) {
      throw interrupted();
    }
  }

  /**
   * Removes every working file and local copy a killed process left under {@code tempDirectory} -
   * recognised by {@link #TEMP_FILE_PREFIX} and by being older than this process, nothing else
   * there is touched - and reports, without failing the start, whether the store answers (ADR-0030,
   * Entscheidung 7 and 9).
   */
  @Override
  public void recoverAfterRestart() {
    sweepTempDirectory();
    try {
      probe();
    } catch (S3AccessException e) {
      log.warn(
          "The upload store {} (bucket {}) is not usable at startup: {} - uploads and downloads"
              + " of originals will fail until it is",
          endpoint,
          bucket,
          e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * One round trip that settles whether the store is usable: the bucket answers, and a {@code
   * HeadObject} on a key that holds nothing comes back as "not found" - which needs the endpoint,
   * the bucket and accepted credentials alike. Bounded by {@link #PROBE_TIMEOUT} as a whole.
   *
   * @throws S3AccessException with the German, credential-free reason when it is not
   */
  public void probe() throws S3AccessException, InterruptedException {
    try {
      translator.call(
          S3Operation.HEAD_BUCKET,
          bucket,
          null,
          () ->
              s3.headBucket(
                  HeadBucketRequest.builder()
                      .bucket(bucket)
                      .overrideConfiguration(this::bound)
                      .build()));
    } catch (S3AccessException.ListForbidden e) {
      // a HEAD answer carries no error code, so a 403 here may be a rights gap or a refused key;
      // the object probe below settles it
    }
    String probeKey = keyPrefix + ".opaa-probe";
    try {
      translator.call(
          S3Operation.HEAD_OBJECT,
          bucket,
          probeKey,
          () ->
              s3.headObject(
                  HeadObjectRequest.builder()
                      .bucket(bucket)
                      .key(probeKey)
                      .overrideConfiguration(this::bound)
                      .build()));
    } catch (S3AccessException.ObjectNotFound e) {
      // the expected answer: reachable, bucket present, credentials accepted
    }
  }

  private void bound(
      software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration.Builder override) {
    override.apiCallTimeout(PROBE_TIMEOUT).apiCallAttemptTimeout(PROBE_TIMEOUT);
  }

  public String endpoint() {
    return endpoint;
  }

  public String bucket() {
    return bucket;
  }

  @Override
  public void close() {
    client.close();
  }

  /**
   * Only a file older than this process is abandoned: the web server already accepts requests when
   * the startup runners fire, so a working file of an upload in flight right now is younger than
   * the process and must survive the sweep.
   */
  private void sweepTempDirectory() {
    if (!Files.isDirectory(tempDirectory)) {
      return;
    }
    FileTime processStart =
        FileTime.fromMillis(ManagementFactory.getRuntimeMXBean().getStartTime());
    int removed = 0;
    try (Stream<Path> entries = Files.list(tempDirectory)) {
      for (Path entry : entries.toList()) {
        if (entry.getFileName().toString().startsWith(TEMP_FILE_PREFIX)
            && Files.isRegularFile(entry)
            && isOlderThan(entry, processStart)) {
          try {
            Files.deleteIfExists(entry);
            removed++;
          } catch (IOException e) {
            log.warn("Could not remove abandoned upload temp file {}", entry, e);
          }
        }
      }
    } catch (IOException e) {
      log.warn("Could not sweep the upload temp directory {}", tempDirectory, e);
    }
    if (removed > 0) {
      log.warn(
          "Removed {} abandoned upload temp file(s) from {} on startup", removed, tempDirectory);
    }
  }

  /**
   * The single containment check of this adapter: the key behind {@code ref}'s locator when it lies
   * in this bucket under {@code ref}'s own library prefix and names an object, empty otherwise. A
   * {@code 403} on the {@code HeadObject} is "not there" too: without {@code s3:ListBucket}, AWS
   * answers a missing key with {@code 403} instead of {@code 404}, and the two must stay
   * indistinguishable to the caller - a rights gap shows in {@link #probe()}, not here.
   *
   * @throws UploadStoreUnavailableException when the store cannot answer
   */
  private Optional<Resolved> resolve(UploadedOriginalRef ref) {
    String key = managedKey(ref);
    if (key == null) {
      return Optional.empty();
    }
    try {
      HeadObjectResponse head =
          translator.call(
              S3Operation.HEAD_OBJECT,
              bucket,
              key,
              () -> s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()));
      return Optional.of(new Resolved(key, head));
    } catch (S3AccessException.ObjectNotFound | S3AccessException.ReadForbidden e) {
      return Optional.empty();
    } catch (S3AccessException e) {
      throw unavailable("resolve", key, e);
    } catch (InterruptedException e) {
      throw interrupted();
    }
  }

  private String managedKey(UploadedOriginalRef ref) {
    String bucketPrefix = "s3://" + bucket + "/";
    if (!ref.locator().startsWith(bucketPrefix)) {
      return null;
    }
    String key = ref.locator().substring(bucketPrefix.length());
    String libraryPrefix = keyPrefix + ref.libraryId() + "/";
    return key.length() > libraryPrefix.length() && key.startsWith(libraryPrefix) ? key : null;
  }

  private String locator(String key) {
    return "s3://" + bucket + "/" + key;
  }

  private static String contentTypeFor(String declaredContentType, HeadObjectResponse head) {
    if (declaredContentType != null && !declaredContentType.isBlank()) {
      return declaredContentType;
    }
    String stored = head.contentType();
    return stored == null || stored.isBlank() ? "application/octet-stream" : stored;
  }

  private Path createTempFile(String suffix) throws IOException {
    Files.createDirectories(tempDirectory);
    return Files.createTempFile(tempDirectory, TEMP_FILE_PREFIX, suffix);
  }

  /** The key's own extension for the copy's name, so format routing by suffix keeps working. */
  private static String suffixOf(String key) {
    String name = key.substring(key.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot);
  }

  private UploadStoreUnavailableException unavailable(
      String what, String key, S3AccessException cause) {
    log.warn("Upload store could not {} object {}/{}: {}", what, bucket, key, cause.getMessage());
    return new UploadStoreUnavailableException();
  }

  private static UploadStoreUnavailableException interrupted() {
    Thread.currentThread().interrupt();
    return new UploadStoreUnavailableException();
  }

  private void deleteObjectQuietly(String key) {
    try {
      translator.call(
          S3Operation.DELETE_OBJECT,
          bucket,
          key,
          () -> s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()));
    } catch (S3AccessException e) {
      log.warn(
          "Could not delete object {}/{}; it stays behind for the orphan cleanup: {}",
          bucket,
          key,
          e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isOlderThan(Path file, FileTime instant) {
    try {
      return Files.getLastModifiedTime(file).compareTo(instant) < 0;
    } catch (IOException e) {
      return false;
    }
  }

  private void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Could not delete file {}", file, e);
    }
  }

  private record Resolved(String key, HeadObjectResponse head) {}

  /**
   * A working file under {@code tempDirectory} and the key it becomes on {@link #store()}. Storing
   * is the one {@code PutObject}; releasing removes only the file, discarding removes the object
   * too when it was stored.
   */
  private final class AcceptedObject implements AcceptedUpload {

    private final UUID libraryId;
    private final String key;
    private final Path workingFile;
    private volatile boolean stored;

    private AcceptedObject(UUID libraryId, String key, Path workingFile) {
      this.libraryId = libraryId;
      this.key = key;
      this.workingFile = workingFile;
    }

    @Override
    public Path workingFile() {
      return workingFile;
    }

    @Override
    public UploadedOriginalRef store() throws IOException {
      // Marked before the call: a PutObject that succeeds on the wire but times out on the way
      // back has written the object, and discarding must remove it. DeleteObject on a key that
      // was never written answers 204 and costs nothing.
      stored = true;
      try {
        translator.call(
            S3Operation.PUT_OBJECT,
            bucket,
            key,
            () ->
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(workingFile)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted while storing " + key);
      }
      return new UploadedOriginalRef(libraryId, locator(key));
    }

    @Override
    public void release() {
      deleteQuietly(workingFile);
    }

    @Override
    public void discard() {
      if (stored) {
        deleteObjectQuietly(key);
        stored = false;
      }
      deleteQuietly(workingFile);
    }
  }
}
