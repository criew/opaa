package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetShellService;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.JobTriggerSource;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.PersonalStorageQuota;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProductionDocumentFormats;
import io.opaa.test.SourceTypes;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs of a private library at its owner's exhausted storage quota, through the real document path
 * and the Liquibase schema: a file past the quota is rejected like one past the library's quota,
 * the run goes on to its reconciliation, and deleting at the source frees the room the next run
 * takes the rejected file in with. A listed size past the quota spares the download; the intake
 * stays the binding check.
 */
@OpaaIntegrationTest
class FileSyncPersonalQuotaIntegrationTest {

  private static final SourceType TYPE = SourceTypes.S3;
  private static final long QUOTA = 1000;
  private static final String CONTAINER = "A";

  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingRunEventRepository eventRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryFolderService folderService;
  @Autowired private LibraryStorageQuotaService quotaService;
  @Autowired private PersonalStorageQuota personalQuota;
  @Autowired private StaleDocumentCleanupService cleanupService;
  @Autowired private SourceSyncStateRepository syncStateRepository;
  @Autowired private ScanJournal scanJournal;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID ownerId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Personal Quota IT', now(), ?,"
            + " ?)",
        ownerId,
        "personal-quota-it-" + ownerId,
        "personal-quota-it-" + ownerId + "@example.com",
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    personalQuota.setQuotaBytes(QUOTA);
    library =
        transactionTemplate.execute(
            status -> {
              KnowledgeLibrary stored =
                  libraryRepository.save(
                      KnowledgeLibrary.ownerOnly(
                          Organization.DEFAULT_ID,
                          "Private Ablage " + ownerId,
                          null,
                          ownerId,
                          TYPE,
                          null,
                          "https://ablage.example",
                          null,
                          null,
                          false));
              shellService.registerCreated(
                  stored, ownerId, Map.of("name", stored.getName(), "sourceType", TYPE.toString()));
              return stored;
            });
  }

  @AfterEach
  void tearDown() {
    if (library != null) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", library.getId().toString());
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library.getId());
      ownLibraryFixtures.removeLibraries(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);
  }

  @Test
  void aRunAtTheExhaustedQuotaRunsToItsEndAndDeletingAtTheSourceFreesTheRoom() {
    InMemoryFileStore store = new InMemoryFileStore().put(CONTAINER, "a.txt", text("a", 600));
    assertThat(run(store).getDocumentsProcessed()).isEqualTo(1);

    store.put(CONTAINER, "b.txt", text("b", 600));
    IndexingJob atTheQuota = run(store.reset());

    assertThat(atTheQuota.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(atTheQuota.getListingComplete())
        .as("the run at the quota reaches its reconciliation")
        .isTrue();
    assertThat(atTheQuota.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(atTheQuota.isIncomplete()).isTrue();
    List<IndexingRunEvent> rejections = rejectionsOf(atTheQuota);
    assertThat(rejections).hasSize(1);
    assertThat(rejections.getFirst().getReference()).isEqualTo(pathOf(store, "b.txt"));
    assertThat(rejections.getFirst().getMessage())
        .startsWith("Speicherkontingent Ihrer privaten Bibliotheken erschöpft");
    assertThat(storedPaths()).containsExactly(pathOf(store, "a.txt"));

    store.remove(CONTAINER, "a.txt");
    IndexingJob removing = run(store.reset());
    assertThat(removing.getListingComplete()).isTrue();
    assertThat(storedPaths()).as("the file deleted at the source is removed").isEmpty();

    IndexingJob next = run(store.reset());
    assertThat(next.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(next.getFailureCategory()).isNull();
    assertThat(next.isIncomplete()).isFalse();
    assertThat(storedPaths())
        .as("the next run takes the file rejected before")
        .containsExactly(pathOf(store, "b.txt"));
  }

  @Test
  void aRejectedNewVersionLeavesTheStoredOneStanding() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .put(CONTAINER, "a.txt", text("a", 400))
            .put(CONTAINER, "b.txt", text("b", 500));
    assertThat(run(store).getDocumentsProcessed()).isEqualTo(2);
    Document before =
        documentRepository
            .findByLibraryIdAndFilePath(library.getId(), pathOf(store, "a.txt"))
            .orElseThrow();

    store.put(CONTAINER, "a.txt", text("a-neu", 700));
    IndexingJob atTheQuota = run(store.reset());

    assertThat(atTheQuota.getListingComplete()).isTrue();
    assertThat(atTheQuota.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");
    Document after =
        documentRepository
            .findByLibraryIdAndFilePath(library.getId(), pathOf(store, "a.txt"))
            .orElseThrow();
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getFileSize()).isEqualTo(400L);
    assertThat(after.getChecksum()).isEqualTo(before.getChecksum());
    assertThat(after.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(after.getChunkCount()).isPositive();
    assertThat(storedPaths()).hasSize(2);
  }

  /** A listed size past the owner's quota is rejected before the download, as after it. */
  @Test
  void aFileWhoseListedSizeExceedsTheOwnersQuotaIsRejectedWithoutADownload() {
    InMemoryFileStore store = new InMemoryFileStore().put(CONTAINER, "a.txt", text("a", 600));
    assertThat(run(store).getDocumentsProcessed()).isEqualTo(1);

    store.put(CONTAINER, "b.txt", text("b", 600));
    IndexingJob atTheQuota = run(store.reset());

    assertThat(store.calls()).doesNotContain("fetch " + CONTAINER + "/b.txt");
    assertThat(atTheQuota.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(atTheQuota.getListingComplete()).isTrue();
    assertThat(atTheQuota.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");
    List<IndexingRunEvent> rejections = rejectionsOf(atTheQuota);
    assertThat(rejections).hasSize(1);
    assertThat(rejections.getFirst().getReference()).isEqualTo(pathOf(store, "b.txt"));
    assertThat(rejections.getFirst().getMessage())
        .startsWith("Speicherkontingent Ihrer privaten Bibliotheken erschöpft");
    assertThat(storedPaths()).containsExactly(pathOf(store, "a.txt"));
  }

  /** A changed file is measured by the growth its listed size announces over the stored row. */
  @Test
  void aChangedFileIsMeasuredBeforeItsDownloadByItsGrowthOnly() {
    InMemoryFileStore store =
        new InMemoryFileStore()
            .put(CONTAINER, "a.txt", text("a", 400))
            .put(CONTAINER, "b.txt", text("b", 500));
    assertThat(run(store).getDocumentsProcessed()).isEqualTo(2);

    store.put(CONTAINER, "a.txt", text("a-neu", 450));
    IndexingJob within = run(store.reset());

    assertThat(within.getFailureCategory()).isNull();
    assertThat(within.getDocumentsProcessed()).isEqualTo(1);

    store.put(CONTAINER, "a.txt", text("a-neu-und-laenger", 700));
    IndexingJob past = run(store.reset());

    assertThat(store.calls()).doesNotContain("fetch " + CONTAINER + "/a.txt");
    assertThat(past.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(
            documentRepository
                .findByLibraryIdAndFilePath(library.getId(), pathOf(store, "a.txt"))
                .orElseThrow()
                .getFileSize())
        .as("the stored version stands")
        .isEqualTo(450L);
  }

  /** The check before the download only advises: the intake still holds a file to the quota. */
  @Test
  void aListingThatUnderstatesTheSizeIsStillHeldToTheQuotaAtIntake() {
    InMemoryFileStore store = new InMemoryFileStore().put(CONTAINER, "a.txt", text("a", 600));
    assertThat(run(store).getDocumentsProcessed()).isEqualTo(1);

    store.put(CONTAINER, "b.txt", text("b", 600)).listedSize(CONTAINER, "b.txt", 10);
    IndexingJob atTheQuota = run(store.reset());

    assertThat(store.calls()).contains("fetch " + CONTAINER + "/b.txt");
    assertThat(atTheQuota.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(rejectionsOf(atTheQuota))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(pathOf(store, "b.txt"));
    assertThat(storedPaths()).containsExactly(pathOf(store, "a.txt"));
  }

  /**
   * A listing that overstates the size holds the file back only while the listed size finds no
   * room; it is not lost, the run after the room frees takes it with its real size.
   */
  @Test
  void aListingThatOverstatesTheSizeLosesNoFile() {
    InMemoryFileStore store = new InMemoryFileStore().put(CONTAINER, "a.txt", text("a", 600));
    assertThat(run(store).getDocumentsProcessed()).isEqualTo(1);

    store.put(CONTAINER, "b.txt", text("b", 300)).listedSize(CONTAINER, "b.txt", 700);
    IndexingJob held = run(store.reset());

    assertThat(store.calls()).doesNotContain("fetch " + CONTAINER + "/b.txt");
    assertThat(held.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");

    store.remove(CONTAINER, "a.txt");
    run(store.reset());
    IndexingJob next = run(store.reset());

    assertThat(next.getFailureCategory()).isNull();
    assertThat(storedPaths()).containsExactly(pathOf(store, "b.txt"));
    assertThat(
            documentRepository
                .findByLibraryIdAndFilePath(library.getId(), pathOf(store, "b.txt"))
                .orElseThrow()
                .getFileSize())
        .isEqualTo(300L);
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /** Text of exactly {@code bytes} bytes that begins with {@code key}. */
  private static String text(String key, int bytes) {
    String head = key + " ";
    return head + "x".repeat(bytes - head.length());
  }

  private static String pathOf(InMemoryFileStore store, String name) {
    return store.filePathOf(CONTAINER, name);
  }

  private List<String> storedPaths() {
    return documentRepository.findByLibraryIdAndSourceType(library.getId(), TYPE).stream()
        .map(Document::getFilePath)
        .toList();
  }

  private List<IndexingRunEvent> rejectionsOf(IndexingJob job) {
    return eventRepository.findByJobIdOrderByCreatedAtAsc(job.getId()).stream()
        .filter(event -> event.getCategory() == IndexingEventCategory.REJECTED)
        .filter(event -> event.getReference() != null)
        .toList();
  }

  private IndexingJob run(InMemoryFileStore store) {
    IndexingJob job =
        indexingJobService.startJob(
            library.getId(),
            Organization.DEFAULT_ID,
            JobTriggerSource.MANUAL,
            IndexingRunMode.FULL);
    IndexingRunTemplate template =
        new IndexingRunTemplate(
            indexingJobService,
            eventRepository,
            cleanupService,
            documentRepository,
            quotaService,
            new LibrarySourceConnectionResolver());
    SourceSyncState state =
        syncStateRepository
            .findByLibraryId(library.getId())
            .orElseGet(() -> new SourceSyncState(library.getId()));
    template.run(
        job.getId(),
        library,
        IndexingRunMode.FULL,
        EXECUTOR,
        frame -> {
          frame.recordRequestCost(store.meter());
          try (FileSync sync =
              new FileSync(
                  frame,
                  store,
                  new FileSyncSettings(1024 * 1024, 1_000, 1, "personal-quota-download-", null),
                  FileSyncHarness.WORDING,
                  documentIngestService,
                  documentRepository,
                  folderService,
                  cleanupService,
                  state,
                  scanJournal,
                  Clock.systemUTC(),
                  ProductionDocumentFormats.supportedFormats())) {
            return sync.run();
          }
        });
    return indexingJobRepository.findById(job.getId()).orElseThrow();
  }

  private static final SourceIndexingExecutor EXECUTOR =
      new SourceIndexingExecutor() {
        @Override
        public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
          return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
        }

        @Override
        public IndexingRunMode defaultRunMode(KnowledgeLibrary library, ConnectorData settings) {
          return IndexingRunMode.FULL;
        }

        @Override
        public SourceType sourceType() {
          return TYPE;
        }

        @Override
        public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode mode) {}
      };
}
