package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.document.Document;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The containment check of the filesystem adapter (ADR-0030, Entscheidung 1) and the store/read/
 * delete contract built on it. Everything here is about what a tampered, foreign or vanished {@code
 * file_path} may reach: no read and no deletion ever gets past {@code managedFile}.
 */
class FilesystemUploadedOriginalStoreTest {

  @TempDir Path storageDir;

  private final UUID libraryId = UUID.randomUUID();
  private FilesystemUploadedOriginalStore store;

  @BeforeEach
  void setUp() {
    store =
        new FilesystemUploadedOriginalStore(
            new UploadProperties(storageDir.toString(), null, 1024L, null, 0, 0));
  }

  @Test
  void anAcceptedUploadIsStoredUnderTheLibrarysOwnDirectoryAndItsPathIsTheLocator()
      throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("content"));

    assertThat(accepted.workingFile())
        .hasParent(storageDir.resolve(libraryId.toString()))
        .hasContent("content");
    assertThat(accepted.workingFile().getFileName().toString()).endsWith(".pdf");

    UploadedOriginalRef ref = accepted.store();

    assertThat(Path.of(ref.locator())).isEqualTo(accepted.workingFile());
    assertThat(ref.libraryId()).isEqualTo(libraryId);
    assertThat(store.belongsToLibrary(ref)).isTrue();
  }

  @Test
  void anAcceptedUploadThatIsNeverStoredIsStillDiscardable() throws IOException {
    // The window between accepting and storing is where the content/extension and deduplication
    // checks reject an upload (ADR-0030, Entscheidung 2) - nothing may stay behind then.
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("content"));

    accepted.discard();

    assertThat(accepted.workingFile()).doesNotExist();
  }

  @Test
  void releasingTheWorkingFileKeepsTheStoredOriginal() throws IOException {
    // ADR-0030, Entscheidung 2: releasing ends the working file's life, not the original's. Here
    // they are the same file, so releasing has nothing to do - the original must survive it.
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("content"));
    UploadedOriginalRef ref = accepted.store();

    accepted.release();

    assertThat(accepted.workingFile()).exists();
    assertThat(store.belongsToLibrary(ref)).isTrue();
  }

  @Test
  void aDiscardedUploadLeavesNothingBehind() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("content"));
    UploadedOriginalRef ref = accepted.store();

    accepted.discard();

    assertThat(accepted.workingFile()).doesNotExist();
    assertThat(store.belongsToLibrary(ref)).isFalse();
  }

  @Test
  void aFailedWriteLeavesNoHalfWrittenFileBehind() {
    InputStream failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("connection reset mid-upload");
          }
        };

    assertThatThrownBy(() -> store.accept(libraryId, ".pdf", failing))
        .isInstanceOf(IOException.class);

    assertThat(storageDir.resolve(libraryId.toString())).isEmptyDirectory();
  }

  @Test
  void aStoredOriginalIsServedAsALocalFileWithTheRowsOwnContentType() throws IOException {
    UploadedOriginalRef ref = storedOriginal("content");

    Optional<DocumentContent> content =
        store.openForDownload(ref, "bericht.pdf", "application/pdf");

    assertThat(content).isPresent();
    // A local path, not a stream: this is what keeps HTTP range requests working (ADR-0030,
    // Entscheidung 6).
    assertThat(content.get().isStreamed()).isFalse();
    assertThat(content.get().path()).isEqualTo(Path.of(ref.locator()));
    assertThat(content.get().fileName()).isEqualTo("bericht.pdf");
    assertThat(content.get().contentType()).isEqualTo("application/pdf");
  }

  @Test
  void aRowWithoutAContentTypeIsStillServedWithOne() throws IOException {
    UploadedOriginalRef ref = storedOriginal("content");

    Optional<DocumentContent> content = store.openForDownload(ref, "bericht.pdf", null);

    assertThat(content).isPresent();
    assertThat(content.get().contentType()).isNotBlank();
  }

  @Test
  void aLocalFileIsHandedToTheActionWithoutACopy() throws IOException {
    UploadedOriginalRef ref = storedOriginal("Ein hochgeladener Vermerk.");

    Optional<String> read = store.withLocalFile(ref, this::readString);

    assertThat(read).contains("Ein hochgeladener Vermerk.");
    // The filesystem adapter hands out the stored file itself - it must survive the action.
    assertThat(Path.of(ref.locator())).exists();
  }

  @Test
  void deletingRemovesTheStoredOriginal() throws IOException {
    UploadedOriginalRef ref = storedOriginal("content");

    store.delete(ref);

    assertThat(Path.of(ref.locator())).doesNotExist();
  }

  @Test
  void aPathInsideAnotherLibrarysDirectoryIsNeitherReadNorDeleted() throws IOException {
    UUID otherLibraryId = UUID.randomUUID();
    Path foreign = Files.createDirectories(storageDir.resolve(otherLibraryId.toString()));
    Path foreignFile = foreign.resolve("fremd.pdf");
    Files.writeString(foreignFile, "content of another library");
    UploadedOriginalRef ref = new UploadedOriginalRef(libraryId, foreignFile.toString());

    assertNeitherReadableNorDeletable(ref);
    assertThat(foreignFile).exists();
  }

  @Test
  void aPathOutsideTheStorageDirectoryIsNeitherReadNorDeleted() throws IOException {
    Path outside = Files.createTempDirectory("outside-upload-storage").resolve("fremd.pdf");
    Files.writeString(outside, "never written by this service");
    UploadedOriginalRef ref = new UploadedOriginalRef(libraryId, outside.toString());

    assertNeitherReadableNorDeletable(ref);
    assertThat(outside).exists();
  }

  @Test
  void aSymlinkLeadingOutOfTheLibraryDirectoryIsNeitherReadNorDeleted() throws IOException {
    Path outside = Files.createTempDirectory("outside-upload-storage").resolve("geheim.pdf");
    Files.writeString(outside, "not ours");
    Path libraryDirectory = Files.createDirectories(storageDir.resolve(libraryId.toString()));
    Path link = libraryDirectory.resolve("harmlos.pdf");
    assumeTrue(createSymbolicLink(link, outside), "needs symlink support (Windows: privileged)");
    UploadedOriginalRef ref = new UploadedOriginalRef(libraryId, link.toString());

    // Lexically the link lies inside the library's own directory; only resolving it shows that its
    // bytes do not.
    assertNeitherReadableNorDeletable(ref);
    assertThat(outside).exists();
    assertThat(link).exists();
  }

  @Test
  void anAttachmentsSyntheticLocatorResolvesToNothingAndLeavesItsParentAlone() throws IOException {
    // ADR-0022/ADR-0030: attachment bytes are never stored, and an attachment row is deletable
    // through the same endpoint as any other. Its file_path embeds the parent's own path, so it
    // lies inside the right directory - but it names no stored original, and deleting the row must
    // not take the parent's original with it.
    UploadedOriginalRef parent = storedOriginal("die Mail mit ihrer Anlage");
    UploadedOriginalRef attachment =
        new UploadedOriginalRef(libraryId, parent.locator() + "/0/anlage.pdf");

    assertNeitherReadableNorDeletable(attachment);
    assertThat(Path.of(parent.locator())).exists();
  }

  @Test
  void aFileThatIsNoLongerThereIsSimplyUnavailable() throws IOException {
    UploadedOriginalRef ref = storedOriginal("content");
    Files.delete(Path.of(ref.locator()));

    assertNeitherReadableNorDeletable(ref);
  }

  @Test
  void aLocatorThatIsNoPathOnThisMachineIsSimplyUnavailable() {
    // What an S3 locator would look like to this adapter, and what a corrupted column can hold.
    UploadedOriginalRef ref =
        new UploadedOriginalRef(libraryId, "s3://bucket/" + libraryId + "/object.pdf");

    assertNeitherReadableNorDeletable(ref);
  }

  @Test
  void aDocumentThatIsNoUploadNeverNamesAnOriginalAtAll() {
    Document filesystemDocument =
        new Document(
            "dienstanweisung.txt",
            storageDir.resolve(libraryId.toString()).resolve("egal.txt").toString(),
            "text/plain",
            10L,
            DocumentSourceType.FILESYSTEM);
    filesystemDocument.setLibraryId(libraryId);

    assertThat(UploadedOriginalRef.of(filesystemDocument)).isEmpty();
  }

  @Test
  void listingVisitsEveryStoredOriginalOfTheLibraryUnderTheLocatorItsRowCarries()
      throws IOException {
    UploadedOriginalRef first = storedOriginal("eins");
    UploadedOriginalRef second = storedOriginal("zwei");
    store.accept(UUID.randomUUID(), ".pdf", bytes("fremde Bibliothek")).store();

    List<UploadedOriginalStore.StoredOriginal> visited = new ArrayList<>();
    store.forEachStoredOriginal(libraryId, visited::add);

    assertThat(visited)
        .extracting(UploadedOriginalStore.StoredOriginal::locator)
        .containsExactlyInAnyOrder(first.locator(), second.locator());
    assertThat(visited)
        .allSatisfy(
            original -> {
              assertThat(original.size()).isEqualTo(4);
              assertThat(original.lastModified()).isAfter(java.time.Instant.now().minusSeconds(60));
            });
  }

  @Test
  void listingALibraryWithoutAStorageAreaVisitsNothing() {
    List<UploadedOriginalStore.StoredOriginal> visited = new ArrayList<>();

    store.forEachStoredOriginal(UUID.randomUUID(), visited::add);

    assertThat(visited).isEmpty();
  }

  @Test
  void listingSkipsWhatALocatorOfThisLibraryWouldNotResolveTo() throws IOException {
    // The listing and the resolution must agree: what the listing reports, delete must be able
    // to remove; a subdirectory and a link leading out of the area resolve to nothing.
    UploadedOriginalRef own = storedOriginal("eigenes Original");
    Path libraryDirectory = storageDir.resolve(libraryId.toString());
    Files.createDirectories(libraryDirectory.resolve("unterordner"));
    Files.writeString(libraryDirectory.resolve("unterordner").resolve("tief.pdf"), "tief");
    Path outside = Files.createTempDirectory("outside-upload-storage").resolve("geheim.pdf");
    Files.writeString(outside, "not ours");
    boolean linked = createSymbolicLink(libraryDirectory.resolve("harmlos.pdf"), outside);

    List<UploadedOriginalStore.StoredOriginal> visited = new ArrayList<>();
    store.forEachStoredOriginal(libraryId, visited::add);

    assertThat(visited)
        .extracting(UploadedOriginalStore.StoredOriginal::locator)
        .containsExactly(own.locator());
    assertThat(outside).exists();
    if (linked) {
      assertThat(libraryDirectory.resolve("harmlos.pdf")).exists();
    }
  }

  private void assertNeitherReadableNorDeletable(UploadedOriginalRef ref) {
    assertThat(store.belongsToLibrary(ref)).isFalse();
    assertThat(store.openForDownload(ref, "harmlos.pdf", "application/pdf")).isEmpty();
    assertThat(store.withLocalFile(ref, this::readString)).isEmpty();
    store.delete(ref);
  }

  private UploadedOriginalRef storedOriginal(String content) throws IOException {
    return store.accept(libraryId, ".pdf", bytes(content)).store();
  }

  private static InputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private String readString(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new AssertionError("The handed-out file must be readable", e);
    }
  }

  private static boolean createSymbolicLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    }
  }
}
