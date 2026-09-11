package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.source.s3.MinioFixture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Both steps of the cleanup against a real MinIO ({@link MinioFixture}, ADR-0030): the report names
 * the objects no row points to, deleting removes exactly the named ones and nothing else, and the
 * next report is empty. The clock runs two hours ahead of the store, so an object written a moment
 * ago is already past the grace period without the test having to wait. Row lookups are mocked -
 * the rows themselves are covered by {@code OrphanedOriginalCleanupIntegrationTest}. Skipped
 * without Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrphanedOriginalCleanupMinioTest {

  private static final int GRACE_MINUTES = 60;

  private static MinioFixture minio;
  private static String bucket;
  private static S3UploadedOriginalStore store;

  @TempDir static Path tempDir;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();
  private final List<String> rows = new ArrayList<>();
  private OrphanedOriginalCleanupService service;

  @BeforeAll
  static void start() {
    minio = MinioFixture.get();
    bucket = minio.createBucket("opaa-aufraeumlauf");
    store =
        new S3UploadedOriginalStore(
            new UploadS3Properties(
                minio.endpoint().toString(),
                MinioFixture.REGION,
                bucket,
                "uploads/",
                true,
                minio.rootCredentials().accessKey(),
                minio.rootCredentials().secretKey(),
                tempDir,
                new UploadS3Properties.TargetValidation(true, List.of())));
  }

  @AfterAll
  static void stop() {
    if (store != null) {
      store.close();
    }
  }

  @BeforeEach
  void setUp() {
    KnowledgeLibraryRepository libraryRepository = mock(KnowledgeLibraryRepository.class);
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getOrganizationId()).thenReturn(organizationId);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findFilePathsByLibraryId(libraryId)).thenReturn(rows);
    UploadProperties uploadProperties =
        new UploadProperties(tempDir.toString(), null, 1024L, null, 0, GRACE_MINUTES);
    service =
        new OrphanedOriginalCleanupService(
            libraryRepository,
            documentRepository,
            store,
            uploadProperties,
            Clock.offset(Clock.systemUTC(), Duration.ofHours(2)));
  }

  @Test
  void theReportNamesOnlyUnreferencedObjectsAndDeletingRemovesOnlyTheNamedOnes()
      throws IOException {
    String owned = storedOriginal(libraryId, "gehört einer Zeile");
    String orphan = storedOriginal(libraryId, "verwaist");
    String keptOrphan = storedOriginal(libraryId, "verwaist, aber nicht genannt");
    UUID otherLibrary = UUID.randomUUID();
    String otherLibrarysObject = storedOriginal(otherLibrary, "andere Bibliothek");
    rows.add(owned);

    OrphanedOriginalReport report = service.report(organizationId, libraryId, null);

    assertThat(report.orphans())
        .extracting(OrphanedOriginal::locator)
        .containsExactlyInAnyOrder(orphan, keptOrphan);
    assertThat(report.scannedCount()).isEqualTo(3);
    assertThat(report.isTruncated()).isFalse();

    OrphanedOriginalDeletion deletion =
        service.delete(organizationId, libraryId, List.of(orphan, otherLibrarysObject));

    assertThat(deletion.deleted()).containsExactly(orphan);
    assertThat(deletion.skipped())
        .containsExactly(
            new OrphanedOriginalDeletion.Skipped(
                otherLibrarysObject, OrphanedOriginalSkipReason.NOT_IN_STORE));
    assertThat(store.belongsToLibrary(new UploadedOriginalRef(libraryId, orphan))).isFalse();
    assertThat(store.belongsToLibrary(new UploadedOriginalRef(libraryId, keptOrphan))).isTrue();
    assertThat(store.belongsToLibrary(new UploadedOriginalRef(libraryId, owned))).isTrue();
    assertThat(store.belongsToLibrary(new UploadedOriginalRef(otherLibrary, otherLibrarysObject)))
        .isTrue();

    OrphanedOriginalReport after = service.report(organizationId, libraryId, null);
    assertThat(after.orphans()).extracting(OrphanedOriginal::locator).containsExactly(keptOrphan);
  }

  private String storedOriginal(UUID library, String content) throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted = store.accept(library, ".pdf", bytes(content));
    UploadedOriginalRef ref = accepted.store();
    accepted.release();
    return ref.locator();
  }

  private static InputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }
}
