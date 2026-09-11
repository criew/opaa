package io.opaa.indexing.source.s3;

import io.opaa.library.KnowledgeLibrary;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads one already indexed object back out of its library's store - the citation jump onto an S3
 * document (ADR-0027, Entscheidung 5), the only read path outside an indexing run. Whether the
 * reader may see the document is decided by the caller before this class is reached; it decides
 * only whether the row still names an object of this library's own configuration.
 *
 * <p>Bounded like a probe rather than like a run ({@link S3ClientFactory#createForProbe}): a
 * waiting reader's request thread hangs on this call, not an unattended run. The endpoint passes
 * the target validation of {@link S3ClientFactory} on every call, so an address the deployment has
 * since blocked stops being readable. The download stays capped at {@code
 * opaa.indexing.s3.max-object-size-bytes}, so an object swapped in the bucket for a larger one
 * cannot fill the temp directory.
 */
public class S3OriginalAccess {

  private static final Logger log = LoggerFactory.getLogger(S3OriginalAccess.class);

  private final S3ClientFactory clientFactory;

  public S3OriginalAccess(S3ClientFactory clientFactory) {
    this.clientFactory = clientFactory;
  }

  /**
   * The object {@code filePath} names, downloaded into a temp file <b>the caller deletes</b>, or
   * empty when this library resolves it to no object at all: a path that is no {@code
   * s3://bucket/key}, a missing or defective S3 configuration, a key outside every configured scope
   * (the scopes can be narrowed after indexing), and an object the store reports as gone, archived
   * or above the size bound.
   *
   * @throws S3AccessException when the store cannot be reached or refuses the key - deliberately
   *     not folded into the empty result, because that is not "there is no original"
   */
  public Optional<S3Download> download(KnowledgeLibrary library, String filePath)
      throws S3AccessException, InterruptedException {
    Optional<S3ObjectRef> parsed = S3ObjectRef.parse(filePath);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    S3ObjectRef ref = parsed.get();
    S3SourceSettings settings;
    try {
      settings = library.getS3Settings();
    } catch (S3SourceSettings.InvalidS3SourceSettingsException
        | S3Scope.InvalidS3ScopeException e) {
      log.warn(
          "Library {} has an unreadable S3 configuration: {}", library.getId(), e.getMessage());
      return Optional.empty();
    }
    if (settings == null) {
      log.warn("Library {} carries no S3 configuration", library.getId());
      return Optional.empty();
    }
    Optional<S3Scope> scope =
        settings.scopes().stream()
            .filter(candidate -> candidate.bucket().equals(ref.bucket()))
            .filter(candidate -> candidate.contains(ref.key()))
            .findFirst();
    if (scope.isEmpty()) {
      log.info("Object {} lies outside every scope of library {}", filePath, library.getId());
      return Optional.empty();
    }
    S3Connection connection;
    try {
      connection = S3LibraryConnection.of(library, settings);
    } catch (S3LibraryConnection.InvalidS3ConfigurationException e) {
      log.warn("Library {} has a defective S3 connection: {}", library.getId(), e.getMessage());
      return Optional.empty();
    }
    try (S3ObjectStore store = clientFactory.createForProbe(connection, List.of(scope.get()))) {
      return Optional.of(store.getObject(ref.bucket(), ref.key()));
    } catch (S3AccessException.ObjectNotFound
        | S3AccessException.Archived
        | S3AccessException.ObjectTooLarge e) {
      log.info("Object {} is not servable: {}", filePath, e.getMessage());
      return Optional.empty();
    }
  }
}
