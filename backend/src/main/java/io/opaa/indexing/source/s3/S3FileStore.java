package io.opaa.indexing.source.s3;

import io.opaa.indexing.filesync.Exclusion;
import io.opaa.indexing.filesync.FetchedFile;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.s3.S3AccessException;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link S3ObjectStore} as the {@link FileStore} of a full sync (ADR-0027): a container is a
 * scope, an entry's id its key, the change feature {@link S3ChangeMarker}. A folder marker is no
 * document, a key outside the patterns is deselected, an archived object unavailable. Every {@link
 * S3AccessException} becomes the neutral kind with the access layer's own sentence.
 */
final class S3FileStore implements FileStore {

  private final S3ObjectStore store;
  private final Map<String, S3Scope> scopes = new LinkedHashMap<>();
  private final S3KeyPatterns patterns;

  /** Whether bucket and prefix segments open every folder chain - a library with several scopes. */
  private final boolean scopeRootChain;

  S3FileStore(S3ObjectStore store, S3SourceSettings settings) {
    this.store = store;
    for (S3Scope scope : settings.scopes()) {
      scopes.put(scope.key(), scope);
    }
    this.patterns = S3KeyPatterns.of(settings);
    this.scopeRootChain = settings.scopes().size() > 1;
  }

  static FileContainer container(S3Scope scope) {
    return new FileContainer(scope.key());
  }

  @Override
  public List<FileContainer> containers() {
    return scopes.values().stream().map(S3FileStore::container).toList();
  }

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    S3Scope scope = scope(container);
    S3ListPage page;
    try {
      page = store.listObjects(scope, continuation);
    } catch (S3AccessException.ListForbidden
        | S3AccessException.BucketNotFound
        | S3AccessException.ListingIncomplete e) {
      throw new FileAccessException.ContainerUnlistable(e.getMessage());
    } catch (S3AccessException e) {
      throw translate(e);
    }
    List<FileEntry> entries = new ArrayList<>(page.objects().size());
    for (S3ObjectSummary object : page.objects()) {
      Exclusion exclusion =
          patterns.admits(object.key())
              ? exclusion(object)
              : new Exclusion.Deselected(S3FullSync.EXCLUDED_KEYS_SUFFIX);
      if (exclusion == null && object.isArchived()) {
        exclusion = unavailable(scope, object);
      }
      entries.add(entry(scope, object, null, exclusion));
    }
    return new FilePage(entries, page.nextContinuationToken());
  }

  @Override
  public FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException {
    S3Scope scope = scope(container);
    S3ObjectHead head;
    try {
      head = store.headObject(scope.bucket(), id);
    } catch (S3AccessException e) {
      throw translate(e);
    }
    // the head already judged the archive state (a completed restore reads normally)
    S3ObjectSummary object =
        new S3ObjectSummary(
            id,
            head.eTag(),
            head.size(),
            head.lastModified(),
            head.archived() ? head.storageClass() : null);
    return entry(
        scope,
        object,
        head.contentType(),
        head.archived() ? unavailable(scope, object) : exclusion(object));
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException {
    S3Download download;
    try {
      download = store.getObject(scope(entry.container()).bucket(), entry.id(), maxBytes);
    } catch (S3AccessException e) {
      throw translate(e);
    }
    return new FetchedFile(
        download.file(),
        download.size(),
        S3ChangeMarker.of(download.eTag(), download.size(), download.lastModified()));
  }

  @Override
  public SourceRequestMeter meter() {
    return store.meter();
  }

  @Override
  public void close() {
    store.close();
  }

  private S3Scope scope(FileContainer container) {
    S3Scope scope = scopes.get(container.key());
    if (scope == null) {
      throw new IllegalArgumentException("not a scope of this library: " + container.key());
    }
    return scope;
  }

  private FileEntry entry(
      S3Scope scope, S3ObjectSummary object, String contentType, Exclusion exclusion) {
    String key = object.key();
    return new FileEntry(
        container(scope),
        key,
        S3ObjectRef.filePath(scope.bucket(), key),
        object.fileName(),
        S3FolderPath.of(scope, key, scopeRootChain),
        new SourceDocumentContext(scope.bucket(), S3FullSync.hierarchyPath(scope, key)),
        object.size(),
        S3ChangeMarker.of(object),
        contentType,
        exclusion);
  }

  /** A folder marker or a key without a name is no document; {@code null} otherwise. */
  private static Exclusion exclusion(S3ObjectSummary object) {
    return object.isFolderMarker() || object.fileName().isEmpty()
        ? new Exclusion.NotADocument(S3FullSync.FOLDER_MARKERS_SUFFIX)
        : null;
  }

  private static Exclusion unavailable(S3Scope scope, S3ObjectSummary object) {
    return new Exclusion.Unavailable(
        "Das Objekt „"
            + scope.bucket()
            + "/"
            + object.key()
            + "“ liegt in der Archivklasse "
            + (object.storageClass() == null ? "(unbekannt)" : object.storageClass())
            + " und ist ohne Wiederherstellung nicht lesbar.");
  }

  /**
   * The neutral kind of an object-level failure: a store-wide one (credentials, clock, TLS, blocked
   * target, wrong region, unreachable) ends the run; anything unnamed is transient.
   */
  private static FileAccessException translate(S3AccessException e) {
    String message = e.getMessage();
    return switch (e) {
      case S3AccessException.ObjectNotFound gone -> new FileAccessException.Gone(message);
      case S3AccessException.ReadForbidden forbidden -> new FileAccessException.Unreadable(message);
      case S3AccessException.Archived archived -> new FileAccessException.Unavailable(message);
      case S3AccessException.ObjectTooLarge tooLarge -> new FileAccessException.TooLarge(message);
      case S3AccessException.Authentication authentication ->
          new FileAccessException.RunEnding(message);
      case S3AccessException.ClockSkew clockSkew -> new FileAccessException.RunEnding(message);
      case S3AccessException.Tls tls -> new FileAccessException.RunEnding(message);
      case S3AccessException.TargetBlocked blocked -> new FileAccessException.RunEnding(message);
      case S3AccessException.WrongRegionOrStyle wrong -> new FileAccessException.RunEnding(message);
      case S3AccessException.Unreachable unreachable -> new FileAccessException.RunEnding(message);
      default -> new FileAccessException.Transient(message);
    };
  }
}
