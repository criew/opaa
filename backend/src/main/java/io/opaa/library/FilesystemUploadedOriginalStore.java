package io.opaa.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uploaded originals on this machine (ADR-0030, {@code opaa.upload.store=filesystem}): {@code
 * <storage-path>/<organizationId>/<libraryId>/<uuid><extension>}, one file per document under a
 * random name, and the absolute path of that file as the locator. The organization segment is what
 * lets a walk over the whole storage area tell the tenants apart without a database row (ADR-0030,
 * addendum to Entscheidung 4). A network share the operator mounts onto that directory is this
 * adapter too - it only ever sees a directory.
 *
 * <p>Serving hands out the file itself rather than a stream, so the controller keeps answering HTTP
 * range requests from {@code FileSystemResource}, and a caller that needs a local file gets the
 * stored one without a copy.
 */
public class FilesystemUploadedOriginalStore implements UploadedOriginalStore {

  /** The {@code opaa.upload.store} value this adapter is selected by. */
  public static final String STORE_NAME = "filesystem";

  private static final Logger log = LoggerFactory.getLogger(FilesystemUploadedOriginalStore.class);

  private final UploadProperties uploadProperties;

  public FilesystemUploadedOriginalStore(UploadProperties uploadProperties) {
    this.uploadProperties = uploadProperties;
  }

  @Override
  public AcceptedUpload accept(
      UUID organizationId, UUID libraryId, String extension, InputStream bytes) throws IOException {
    Path libraryDirectory = libraryDirectory(organizationId, libraryId);
    Files.createDirectories(libraryDirectory);
    Path file = libraryDirectory.resolve(UUID.randomUUID() + extension);
    try {
      Files.copy(bytes, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException e) {
      deleteQuietly(file);
      throw e;
    }
    return new AcceptedFile(organizationId, libraryId, file);
  }

  @Override
  public Optional<DocumentContent> openForDownload(
      UploadedOriginalRef ref, String fileName, String declaredContentType) {
    Path file = managedFile(ref);
    if (file == null || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.of(
        new DocumentContent(file, fileName, ServedContentTypes.forFile(declaredContentType, file)));
  }

  @Override
  public <T> Optional<T> withLocalFile(UploadedOriginalRef ref, Function<Path, T> action) {
    Path file = managedFile(ref);
    // The stored file is already local: nothing to copy, and nothing to clean up afterwards.
    return file == null ? Optional.empty() : Optional.of(action.apply(file));
  }

  @Override
  public void delete(UploadedOriginalRef ref) {
    Path file = managedFile(ref);
    if (file != null) {
      deleteQuietly(file);
    }
  }

  @Override
  public boolean belongsToLibrary(UploadedOriginalRef ref) {
    return managedFile(ref) != null;
  }

  @Override
  public void forEachStoredOriginal(
      UUID organizationId, UUID libraryId, Consumer<StoredOriginal> visitor) {
    Path libraryDirectory = libraryDirectory(organizationId, libraryId);
    if (!Files.isDirectory(libraryDirectory)) {
      return;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(libraryDirectory)) {
      for (Path entry : entries) {
        // One level only, and the same check every other operation goes through: a subdirectory
        // (whose content this adapter never wrote) and a link leading out are not originals.
        String locator = entry.toString();
        Path file = managedFile(new UploadedOriginalRef(organizationId, libraryId, locator));
        if (file == null || !Files.isRegularFile(file)) {
          continue;
        }
        visitor.accept(
            new StoredOriginal(
                locator, Files.getLastModifiedTime(file).toInstant(), Files.size(file)));
      }
    } catch (IOException e) {
      log.warn("Could not list the stored originals under {}", libraryDirectory, e);
      throw new UploadStoreUnavailableException();
    }
  }

  @Override
  public void forEachStoredLibrary(UUID organizationId, Consumer<UUID> visitor) {
    Path organizationDirectory = realPath(organizationDirectory(organizationId));
    if (organizationDirectory == null || !Files.isDirectory(organizationDirectory)) {
      return;
    }
    Path storageRoot = realPath(Paths.get(uploadProperties.storagePath()));
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(organizationDirectory)) {
      for (Path entry : entries) {
        Path real = realPath(entry);
        if (real == null
            || !Files.isDirectory(real)
            || elsewhereInside(real, organizationDirectory, storageRoot)) {
          continue;
        }
        UUID libraryId = libraryId(entry.getFileName().toString());
        if (libraryId != null) {
          visitor.accept(libraryId);
        }
      }
    } catch (IOException e) {
      log.warn("Could not list the library directories under {}", organizationDirectory, e);
      throw new UploadStoreUnavailableException();
    }
  }

  /**
   * Whether {@code directory} leads to another place <em>inside</em> the storage path than the one
   * it is listed under - the one case a listing over a whole organization must leave out. A
   * directory the operator linked to a volume outside the storage path is not that case and is
   * visited like any other, so what the reads and deletes below resolve stays listable; a link to
   * another library's or another organization's area is, because it would make those originals
   * appear under this name and the deletion that follows the report would remove the wrong ones.
   *
   * <p>The parent of the real path is the segment-wise comparison; a lexical {@code startsWith} on
   * the entry's own path would pass every link.
   */
  private static boolean elsewhereInside(Path directory, Path listedUnder, Path storageRoot) {
    return !listedUnder.equals(directory.getParent())
        && storageRoot != null
        && directory.startsWith(storageRoot);
  }

  /**
   * The single containment check of this adapter, and the only way a locator ever becomes a path:
   * the real path of {@code ref}'s file when it lies underneath its own library's subdirectory of
   * its own organization's subdirectory of the storage path, {@code null} otherwise - another
   * organization's or another library's subdirectory, anything outside the storage path, a symlink
   * leading out of it, an unparseable locator, and a file that is no longer there all yield {@code
   * null}.
   *
   * <p>Resolves both sides with {@link Path#toRealPath()} rather than comparing them lexically: a
   * symlink inside the directory pointing outside it would otherwise pass the {@code startsWith}
   * check on its lexical path alone, and this is the point where the file is actually read, deleted
   * or streamed back to an HTTP caller.
   */
  private Path managedFile(UploadedOriginalRef ref) {
    Path candidate = localPath(ref.locator());
    if (candidate == null) {
      return null;
    }
    Path real = realPath(candidate);
    Path libraryDirectory = realPath(libraryDirectory(ref.organizationId(), ref.libraryId()));
    if (real == null || libraryDirectory == null) {
      return null;
    }
    return real.startsWith(libraryDirectory) ? real : null;
  }

  private Path organizationDirectory(UUID organizationId) {
    return Paths.get(uploadProperties.storagePath())
        .resolve(organizationId.toString())
        .toAbsolutePath()
        .normalize();
  }

  /**
   * The library id a directory name stands for, or {@code null} when it names none. Only a name
   * this adapter would itself have written counts, which is why the parsed id has to render back to
   * it: {@link UUID#fromString} also accepts abbreviated groups, and such a directory would resolve
   * to a library whose storage area lies elsewhere.
   */
  private static UUID libraryId(String directoryName) {
    try {
      UUID libraryId = UUID.fromString(directoryName);
      return libraryId.toString().equals(directoryName) ? libraryId : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Path libraryDirectory(UUID organizationId, UUID libraryId) {
    return Paths.get(uploadProperties.storagePath())
        .resolve(organizationId.toString())
        .resolve(libraryId.toString())
        .toAbsolutePath()
        .normalize();
  }

  private Path localPath(String locator) {
    try {
      return Path.of(locator);
    } catch (InvalidPathException e) {
      log.warn("Uploaded original locator is not a path on this machine: {}", locator, e);
      return null;
    }
  }

  /**
   * {@link Path#toRealPath()}, or {@code null} if the path does not (or no longer) exist - a file
   * that has since disappeared is not a traversal attempt, just the ordinary "gone" case every
   * caller already treats as "no original available".
   */
  private Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }

  private void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Could not delete file {}", file, e);
    }
  }

  /**
   * Here the accepted file already lies where the original belongs, so storing it only names it and
   * releasing it has nothing to do - discarding is the one deletion that removes both at once.
   */
  private final class AcceptedFile implements AcceptedUpload {

    private final UUID organizationId;
    private final UUID libraryId;
    private final Path file;

    private AcceptedFile(UUID organizationId, UUID libraryId, Path file) {
      this.organizationId = organizationId;
      this.libraryId = libraryId;
      this.file = file;
    }

    @Override
    public Path workingFile() {
      return file;
    }

    @Override
    public UploadedOriginalRef store() {
      return new UploadedOriginalRef(organizationId, libraryId, file.toString());
    }

    @Override
    public void release() {
      // Nothing to release: the working file is the stored original.
    }

    @Override
    public void discard() {
      deleteQuietly(file);
    }
  }
}
