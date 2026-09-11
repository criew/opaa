package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.source.s3.S3AccessException;
import io.opaa.indexing.source.s3.StubS3Server;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The S3 adapter against the {@link StubS3Server} (ADR-0030): accepting and storing as two steps,
 * the three exits of an accepted upload, streaming download, the local copy that is gone on every
 * exit, the containment of a locator by bucket and library prefix, the synthetic attachment
 * locator, the two answers "not there" and "not reachable", and the startup sweep. The stub is
 * reached on the loopback with the target validation switched on - the configured endpoint passes
 * on its own (Entscheidung 8).
 */
class S3UploadedOriginalStoreTest {

  private static final String BUCKET = "ablage";

  @TempDir Path tempDir;

  private final UUID libraryId = UUID.randomUUID();
  private StubS3Server server;
  private S3UploadedOriginalStore store;

  @BeforeEach
  void start() throws IOException {
    server = new StubS3Server();
    server.addBucket(BUCKET);
    store = store(properties(""));
  }

  /** The production policy on fast retry bounds, so a dead port fails within milliseconds. */
  private static S3UploadedOriginalStore store(UploadS3Properties properties) {
    return store(properties, S3UploadedOriginalStore.LIST_PAGE_SIZE);
  }

  private static S3UploadedOriginalStore store(UploadS3Properties properties, int listPageSize) {
    return new S3UploadedOriginalStore(
        properties,
        UploadS3TargetPolicy.of(properties),
        java.time.Duration.ofSeconds(5),
        1,
        java.time.Duration.ofMillis(1),
        listPageSize);
  }

  @AfterEach
  void stop() {
    store.close();
    server.close();
  }

  private UploadS3Properties properties(String keyPrefix) {
    return new UploadS3Properties(
        server.endpoint(),
        null,
        BUCKET,
        keyPrefix,
        true,
        "AKIASTUB",
        "geheim:mit:doppelpunkt",
        tempDir,
        new UploadS3Properties.TargetValidation(true, List.of()));
  }

  @Test
  void acceptingWritesAWorkingFileAndOnlyStoringPutsTheObject() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("inhalt"));

    assertThat(accepted.workingFile()).hasParent(tempDir).hasContent("inhalt");
    assertThat(accepted.workingFile().getFileName().toString())
        .startsWith(S3UploadedOriginalStore.TEMP_FILE_PREFIX)
        .endsWith(".pdf");
    assertThat(server.seen()).as("nothing was sent before store()").isEmpty();

    UploadedOriginalRef ref = accepted.store();

    assertThat(server.keys(BUCKET)).hasSize(1);
    String key = server.keys(BUCKET).get(0);
    assertThat(key).startsWith(libraryId + "/").endsWith(".pdf");
    assertThat(ref.locator()).isEqualTo("s3://" + BUCKET + "/" + key);
    assertThat(ref.libraryId()).isEqualTo(libraryId);
    assertThat(accepted.workingFile()).as("the working file outlives store()").exists();
    assertThat(server.seen()).extracting(StubS3Server.Seen::method).containsExactly("PUT");
  }

  @Test
  void discardingBeforeStoringNeverTouchesTheBucket() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("inhalt"));

    accepted.discard();

    assertThat(accepted.workingFile()).doesNotExist();
    assertThat(server.seen()).isEmpty();
  }

  @Test
  void discardingAfterStoringRemovesObjectAndWorkingFile() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("inhalt"));
    accepted.store();

    accepted.discard();

    assertThat(accepted.workingFile()).doesNotExist();
    assertThat(server.keys(BUCKET)).isEmpty();
    assertThat(server.seen())
        .extracting(StubS3Server.Seen::method)
        .containsExactly("PUT", "DELETE");
  }

  @Test
  void releasingRemovesOnlyTheWorkingFile() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("inhalt"));
    UploadedOriginalRef ref = accepted.store();

    accepted.release();

    assertThat(accepted.workingFile()).doesNotExist();
    assertThat(server.keys(BUCKET)).hasSize(1);
    assertThat(store.belongsToLibrary(ref)).isTrue();
  }

  @Test
  void aFailedWriteLeavesNoWorkingFileBehind() {
    InputStream failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("connection reset mid-upload");
          }
        };

    assertThatThrownBy(() -> store.accept(libraryId, ".pdf", failing))
        .isInstanceOf(IOException.class);

    assertThat(ownTempFiles()).isEmpty();
    assertThat(server.seen()).isEmpty();
  }

  @Test
  void aRefusedPutIsAnIOExceptionAndLeavesNoObject() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(libraryId, ".pdf", bytes("inhalt"));
    server.failNextMatching("PUT", "/" + BUCKET + "/", 403, "AccessDenied");

    assertThatThrownBy(accepted::store)
        .isInstanceOf(S3AccessException.WriteForbidden.class)
        .hasMessageContaining("s3:PutObject")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("geheim"));

    accepted.discard();
    assertThat(server.keys(BUCKET)).isEmpty();
    // Discarding after a failed put still sends the DeleteObject: a put that succeeded on the wire
    // but timed out on the way back has written the object, and only the delete takes it away.
    assertThat(server.seen())
        .extracting(StubS3Server.Seen::method)
        .containsExactly("PUT", "DELETE");
  }

  @Test
  void aForbiddenHeadIsNotThereNotUnavailable() throws IOException {
    // Without s3:ListBucket AWS answers a HeadObject on a missing key with 403 - which must stay
    // the same "not there" as a 404, or "does not exist" becomes distinguishable again (#736).
    UploadedOriginalRef ref = storedOriginal("inhalt");
    server.failNextMatching("HEAD", "/" + BUCKET + "/", 403, "AccessDenied");

    assertThat(store.openForDownload(ref, "x.pdf", "application/pdf")).isEmpty();
  }

  @Test
  void aStoredOriginalIsServedAsAStreamWithTheRowsOwnContentType() throws IOException {
    UploadedOriginalRef ref = storedOriginal("Originaltext");

    Optional<DocumentContent> content =
        store.openForDownload(ref, "bescheid.pdf", "application/pdf");

    assertThat(content).isPresent();
    assertThat(content.get().isStreamed()).isTrue();
    assertThat(content.get().fileName()).isEqualTo("bescheid.pdf");
    assertThat(content.get().contentType()).isEqualTo("application/pdf");
    try (InputStream stream = content.get().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Originaltext");
    }
  }

  @Test
  void aRowWithoutAContentTypeIsServedWithTheStoredOne() throws IOException {
    // RequestBody.fromFile types the object from the working file's name on PutObject; that is
    // what a row without a type of its own falls back to.
    UploadedOriginalRef ref = storedOriginal("x");

    Optional<DocumentContent> content = store.openForDownload(ref, "x.bin", null);

    assertThat(content).isPresent();
    assertThat(content.get().contentType()).isEqualTo("application/pdf");
    content.get().stream().close();
  }

  @Test
  void aLocalCopyIsHandedToTheActionAndRemovedOnEveryExit() throws IOException {
    UploadedOriginalRef ref = storedOriginal("Ein hochgeladener Vermerk.");

    Optional<String> read =
        store.withLocalFile(
            ref,
            file -> {
              assertThat(file).hasParent(tempDir);
              assertThat(file.getFileName().toString()).endsWith(".pdf");
              return readString(file);
            });
    assertThat(read).contains("Ein hochgeladener Vermerk.");
    assertThat(ownTempFiles()).as("the copy is gone after a successful action").isEmpty();

    // MetadataBackfillService#reextractFromOwnFile throws an UncheckedIOException through here -
    // the copy must not survive that exit either, and the exception reaches the caller unchanged.
    assertThatThrownBy(
            () ->
                store.withLocalFile(
                    ref,
                    file -> {
                      throw new IllegalStateException("aus der Aktion");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("aus der Aktion");
    assertThat(ownTempFiles()).as("the copy is gone after a failing action").isEmpty();
    assertThat(server.keys(BUCKET)).as("the original is untouched").hasSize(1);
  }

  @Test
  void deletingRemovesTheObjectAndAMissingOneIsLeftAloneWithoutADeleteRequest() throws IOException {
    UploadedOriginalRef ref = storedOriginal("inhalt");

    store.delete(ref);
    assertThat(server.keys(BUCKET)).isEmpty();

    server.seen().clear();
    store.delete(ref);
    assertThat(server.seen()).extracting(StubS3Server.Seen::method).containsExactly("HEAD");
  }

  @Test
  void aLocatorInAnotherLibrarysPrefixIsNeitherReadNorDeletedNorEvenLookedUp() throws IOException {
    UploadedOriginalRef own = storedOriginal("fremd");
    String key = own.locator().substring(("s3://" + BUCKET + "/").length());
    UploadedOriginalRef foreign = new UploadedOriginalRef(UUID.randomUUID(), own.locator());
    UploadedOriginalRef otherBucket =
        new UploadedOriginalRef(libraryId, "s3://anderer-bucket/" + key);
    UploadedOriginalRef noPath = new UploadedOriginalRef(libraryId, "/var/opaa/uploads/x.pdf");
    server.seen().clear();

    for (UploadedOriginalRef ref : List.of(foreign, otherBucket, noPath)) {
      assertNeitherReadableNorDeletable(ref);
    }

    assertThat(server.seen()).as("a locator outside the prefix costs no request").isEmpty();
    assertThat(server.keys(BUCKET)).hasSize(1);
  }

  @Test
  void anAttachmentsSyntheticLocatorResolvesToNothingAndLeavesItsParentAlone() throws IOException {
    UploadedOriginalRef parent = storedOriginal("die Mail mit ihrer Anlage");
    UploadedOriginalRef attachment =
        new UploadedOriginalRef(libraryId, parent.locator() + "/0/anlage.pdf");
    server.seen().clear();

    assertNeitherReadableNorDeletable(attachment);

    assertThat(server.seen()).extracting(StubS3Server.Seen::method).containsOnly("HEAD");
    assertThat(server.keys(BUCKET)).hasSize(1);
  }

  @Test
  void anObjectThatIsNoLongerThereIsSimplyUnavailable() throws IOException {
    UploadedOriginalRef ref = storedOriginal("inhalt");
    server.keys(BUCKET).forEach(key -> server.removeObject(BUCKET, key));

    assertNeitherReadableNorDeletable(ref);
  }

  @Test
  void aStoreThatCannotBeReachedIsUnavailableNotMissing() throws IOException {
    UploadedOriginalRef ref = storedOriginal("inhalt");
    server.close();

    assertThatThrownBy(() -> store.openForDownload(ref, "x.pdf", "application/pdf"))
        .isInstanceOf(UploadStoreUnavailableException.class)
        .hasMessageContaining("nicht erreichbar");
    assertThatThrownBy(() -> store.withLocalFile(ref, this::readString))
        .isInstanceOf(UploadStoreUnavailableException.class);
    assertThatThrownBy(() -> store.belongsToLibrary(ref))
        .isInstanceOf(UploadStoreUnavailableException.class);
    // deletion runs after the row's commit and must not throw; the object stays for the cleanup
    assertThatCode(() -> store.delete(ref)).doesNotThrowAnyException();
    assertThat(ownTempFiles()).isEmpty();
  }

  @Test
  void theKeyPrefixIsPartOfEveryKeyAndOfTheContainment() throws IOException {
    store.close();
    store = store(properties("mandant-a/uploads/"));

    UploadedOriginalRef ref = storedOriginal("inhalt");

    assertThat(server.keys(BUCKET))
        .singleElement()
        .satisfies(key -> assertThat(key).startsWith("mandant-a/uploads/" + libraryId + "/"));
    assertThat(ref.locator())
        .startsWith("s3://" + BUCKET + "/mandant-a/uploads/" + libraryId + "/");
    assertThat(store.belongsToLibrary(ref)).isTrue();
    // the same key without the prefix names nothing of this store
    String key = server.keys(BUCKET).get(0);
    UploadedOriginalRef unprefixed =
        new UploadedOriginalRef(
            libraryId, "s3://" + BUCKET + "/" + key.substring("mandant-a/uploads/".length()));
    assertNeitherReadableNorDeletable(unprefixed);
  }

  @Test
  void recoveringAfterARestartSweepsOnlyThisAdaptersOwnFilesOlderThanTheProcess()
      throws IOException {
    // The web server already accepts uploads when the startup runners fire: a working file
    // written by this process is in flight, not abandoned, and must survive the sweep.
    java.nio.file.attribute.FileTime beforeThisProcess =
        java.nio.file.attribute.FileTime.fromMillis(
            java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()
                - java.time.Duration.ofMinutes(5).toMillis());
    Path abandonedWorkingFile =
        Files.writeString(
            tempDir.resolve(S3UploadedOriginalStore.TEMP_FILE_PREFIX + "abandoned.pdf"), "x");
    Path abandonedCopy =
        Files.writeString(
            tempDir.resolve(S3UploadedOriginalStore.TEMP_FILE_PREFIX + "copy.tmp"), "x");
    Path somebodyElses = Files.writeString(tempDir.resolve("other-tool.tmp"), "x");
    Path ownDirectory =
        Files.createDirectory(tempDir.resolve(S3UploadedOriginalStore.TEMP_FILE_PREFIX + "dir"));
    for (Path old : List.of(abandonedWorkingFile, abandonedCopy, somebodyElses, ownDirectory)) {
      Files.setLastModifiedTime(old, beforeThisProcess);
    }
    Path inFlight =
        Files.writeString(
            tempDir.resolve(S3UploadedOriginalStore.TEMP_FILE_PREFIX + "in-flight.pdf"), "x");

    store.recoverAfterRestart();

    assertThat(abandonedWorkingFile).doesNotExist();
    assertThat(abandonedCopy).doesNotExist();
    assertThat(inFlight).exists();
    assertThat(somebodyElses).exists();
    assertThat(ownDirectory).exists();
  }

  @Test
  void recoveringAfterARestartDoesNotFailWhenTheStoreIsDown() {
    server.close();

    assertThatCode(store::recoverAfterRestart).doesNotThrowAnyException();
  }

  @Test
  void theProbeSettlesReachabilityBucketAndCredentials() {
    assertThatCode(store::probe).doesNotThrowAnyException();
    assertThat(server.seen())
        .extracting(StubS3Server.Seen::method, StubS3Server.Seen::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("HEAD", "/" + BUCKET),
            org.assertj.core.groups.Tuple.tuple("HEAD", "/" + BUCKET + "/.opaa-probe"));

    server.failNextMatching("HEAD", "/" + BUCKET, 404, "NoSuchBucket");
    assertThatThrownBy(store::probe).isInstanceOf(S3AccessException.BucketNotFound.class);

    // a HEAD on the bucket answers 403 without a body; the object probe names the real reason
    server.failNextMatching("HEAD", "/" + BUCKET, 403, "AccessDenied");
    server.failNextMatching("HEAD", "/" + BUCKET + "/.opaa-probe", 403, "AccessDenied");
    assertThatThrownBy(store::probe).isInstanceOf(S3AccessException.ReadForbidden.class);
  }

  @Test
  void theHealthIndicatorReportsUpWithDetailsAndDownWithTheReason() {
    UploadStoreHealthIndicator indicator = new UploadStoreHealthIndicator(store);

    Health up = indicator.health();
    assertThat(up.getStatus()).isEqualTo(Status.UP);
    assertThat(up.getDetails())
        .containsEntry("endpoint", server.endpoint())
        .containsEntry("bucket", BUCKET);

    server.close();
    Health down = indicator.health();
    assertThat(down.getStatus()).isEqualTo(Status.DOWN);
    assertThat(down.getDetails().get("reason").toString())
        .contains("nicht erreichbar")
        .doesNotContain("geheim");
  }

  @Test
  void listingWalksTheLibrarysPrefixPageByPageAndVisitsOnlyItsOwnObjects() throws IOException {
    store.close();
    store = store(properties("uploads/"), 2);
    List<String> own = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      own.add(storedOriginal("original " + i).locator());
    }
    server.putObject(BUCKET, "uploads/" + UUID.randomUUID() + "/fremd.pdf", raw("x"), "a/b");
    server.putObject(BUCKET, libraryId + "/ohne-praefix.pdf", raw("x"), "a/b");
    server.putObject(BUCKET, "uploads/" + libraryId + "/", new byte[0], "a/b");
    server.seen().clear();

    List<UploadedOriginalStore.StoredOriginal> visited = new ArrayList<>();
    store.forEachStoredOriginal(libraryId, visited::add);

    assertThat(visited)
        .extracting(UploadedOriginalStore.StoredOriginal::locator)
        .containsExactlyInAnyOrderElementsOf(own);
    assertThat(visited)
        .allSatisfy(
            original -> {
              assertThat(original.size()).isEqualTo("original 0".length());
              assertThat(original.lastModified()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
            });
    List<StubS3Server.Seen> listings =
        server.seen().stream().filter(seen -> seen.query().contains("list-type=2")).toList();
    assertThat(listings).as("five own objects plus the marker, two per page").hasSize(3);
    assertThat(listings)
        .allSatisfy(seen -> assertThat(seen.query()).contains("prefix=uploads%2F" + libraryId));
    assertThat(listings.get(1).query()).contains("continuation-token=");
    assertThat(server.seen())
        .as("the listing itself costs no HeadObject")
        .extracting(StubS3Server.Seen::method)
        .containsOnly("GET");
  }

  @Test
  void listingAStoreThatCannotBeReachedIsUnavailable() throws IOException {
    storedOriginal("inhalt");
    server.close();

    assertThatThrownBy(() -> store.forEachStoredOriginal(libraryId, original -> {}))
        .isInstanceOf(UploadStoreUnavailableException.class);
  }

  @Test
  void aListingThatClaimsMorePagesWithoutATokenIsUnavailableNotShort() throws IOException {
    // A silently short listing would make every unlisted original look like it is not there -
    // and the cleanup's second step would then refuse to touch it, which is the safe direction,
    // but the report must not pretend to be complete either.
    store.close();
    store = store(properties(""), 1);
    storedOriginal("eins");
    storedOriginal("zwei");
    server.omitContinuationToken();

    assertThatThrownBy(() -> store.forEachStoredOriginal(libraryId, original -> {}))
        .isInstanceOf(UploadStoreUnavailableException.class);
  }

  private void assertNeitherReadableNorDeletable(UploadedOriginalRef ref) {
    assertThat(store.belongsToLibrary(ref)).isFalse();
    assertThat(store.openForDownload(ref, "harmlos.pdf", "application/pdf")).isEmpty();
    assertThat(store.withLocalFile(ref, this::readString)).isEmpty();
    store.delete(ref);
    assertThat(server.seen()).extracting(StubS3Server.Seen::method).doesNotContain("DELETE");
  }

  private UploadedOriginalRef storedOriginal(String content) throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted = store.accept(libraryId, ".pdf", bytes(content));
    UploadedOriginalRef ref = accepted.store();
    accepted.release();
    return ref;
  }

  private List<Path> ownTempFiles() {
    try (Stream<Path> entries = Files.list(tempDir)) {
      return entries
          .filter(
              p -> p.getFileName().toString().startsWith(S3UploadedOriginalStore.TEMP_FILE_PREFIX))
          .toList();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] raw(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
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
}
