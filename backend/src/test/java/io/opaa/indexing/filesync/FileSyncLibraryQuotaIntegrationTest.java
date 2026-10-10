package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetShellService;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.attachment.AttachmentLimits;
import io.opaa.indexing.attachment.AttachmentProperties;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.AttachmentFilePath;
import io.opaa.indexing.document.AttachmentIndexer;
import io.opaa.indexing.document.ChecksumService;
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
import io.opaa.knowledge.LibraryProperties;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.PersonalStorageQuota;
import io.opaa.knowledge.SourceType;
import io.opaa.metadata.DocumentMetadataService;
import io.opaa.metadata.ModelMetadataExtractor;
import io.opaa.observability.IndexingMetrics;
import io.opaa.organization.Organization;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProductionDocumentFormats;
import io.opaa.test.SourceTypes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs of a shared library at its own storage quota, through the real document and attachment path
 * and the Liquibase schema. An upload that takes the room right after a mail archive was admitted
 * makes the archive's second attachment hit the quota: the run ends incomplete with {@code
 * QUOTA_EXHAUSTED}, the archive is stored without its change marker, and once the upload is gone
 * the next run reads the archive again and takes the attachment in - needing room only for what is
 * not stored of it yet.
 */
@OpaaIntegrationTest
class FileSyncLibraryQuotaIntegrationTest {

  private static final SourceType TYPE = SourceTypes.S3;
  private static final String CONTAINER = "A";
  private static final String ARCHIVE = "archiv.eml";
  private static final int ATTACHMENT_BYTES = 10_000;

  @Autowired private DocumentFormatRegistry formatRegistry;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private ChecksumService checksumService;
  @Autowired private IndexingMetrics indexingMetrics;
  @Autowired private IndexingProperties indexingProperties;

  @Autowired
  @Qualifier("embeddingTaskExecutor")
  private TaskExecutor embeddingTaskExecutor;

  @Autowired private AttachmentLimits mailAttachmentLimits;
  @Autowired private AttachmentProperties attachmentProperties;
  @Autowired private BoundedDownloader boundedDownloader;
  @Autowired private SupportedDocumentFormats supportedFormats;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private ModelMetadataExtractor modelMetadataExtractor;
  @Autowired private PersonalStorageQuota personalQuota;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingRunEventRepository eventRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryFolderService folderService;
  @Autowired private StaleDocumentCleanupService cleanupService;
  @Autowired private SourceSyncStateRepository syncStateRepository;
  @Autowired private ScanJournal scanJournal;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID ownerId;
  private KnowledgeLibrary library;
  private AdjustableQuota quota;
  private DocumentIngestService documentIngestService;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Library Quota IT', now(), ?,"
            + " ?)",
        ownerId,
        "library-quota-it-" + ownerId,
        "library-quota-it-" + ownerId + "@example.com",
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    library =
        transactionTemplate.execute(
            status -> {
              KnowledgeLibrary stored =
                  libraryRepository.save(
                      KnowledgeLibrary.ownedByUser(
                          Organization.DEFAULT_ID,
                          "Geteilte Ablage " + ownerId,
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
    quota = new AdjustableQuota(documentRepository, personalQuota);
    AttachmentIndexer[] attachmentIndexer = new AttachmentIndexer[1];
    @SuppressWarnings("unchecked")
    ObjectProvider<AttachmentIndexer> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenAnswer(invocation -> attachmentIndexer[0]);
    documentIngestService =
        new DocumentIngestService(
            formatRegistry,
            documentRepository,
            vectorChunkStore,
            checksumService,
            indexingMetrics,
            quota,
            indexingProperties,
            embeddingTaskExecutor,
            provider,
            mailAttachmentLimits,
            documentMetadataService,
            modelMetadataExtractor);
    attachmentIndexer[0] =
        new AttachmentIndexer(
            boundedDownloader,
            documentIngestService,
            quota,
            attachmentProperties,
            supportedFormats);
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

  /**
   * The attachment the quota rejected is not lost behind its archive's change marker: the run ends
   * as incomplete, and the run after the room frees takes the attachment in.
   */
  @Test
  void anAttachmentRejectedAtTheLibraryQuotaComesWithTheRunAfterTheRoomFrees() {
    InMemoryFileStore store = new InMemoryFileStore().put(CONTAINER, ARCHIVE, archive());
    long raw = archive().getBytes(StandardCharsets.UTF_8).length;
    // room for everything but the second attachment once the upload took its share, and after
    // the upload is gone room for the whole archive again
    quota.quotaBytes = raw + ATTACHMENT_BYTES + 1_000;
    long upload = ATTACHMENT_BYTES + 1_000 + ATTACHMENT_BYTES / 2;
    quota.afterNextIntake = () -> uploadConcurrently(upload);

    IndexingJob atTheQuota = run(store);

    assertThat(atTheQuota.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(atTheQuota.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(atTheQuota.isIncomplete()).isTrue();
    assertThat(rejectionsOf(atTheQuota))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(attachmentPath(store, 1));
    assertThat(stored(attachmentPath(store, 0))).isTrue();
    assertThat(stored(attachmentPath(store, 1))).isFalse();
    Document archive = row(pathOf(store));
    assertThat(archive.getChecksum()).as("the archive is to be read again").isNull();
    assertThat(archive.getLastModifiedRemote()).isNull();

    removeUpload();
    IndexingJob next = run(store.reset());

    assertThat(next.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(next.getFailureCategory()).isNull();
    assertThat(next.isIncomplete()).isFalse();
    assertThat(stored(attachmentPath(store, 1)))
        .as("the next run takes the rejected attachment")
        .isTrue();
    assertThat(row(pathOf(store)).getLastModifiedRemote()).isNotNull();
  }

  /**
   * An archive read again for its rejected attachment is measured by what it adds beyond what is
   * already stored of it: its stored attachments do not count a second time, so room for the
   * missing attachment is enough, and the archive itself is not reported as rejected.
   */
  @Test
  void anArchiveReadAgainNeedsRoomOnlyForTheAttachmentsNotStoredYet() {
    InMemoryFileStore store = new InMemoryFileStore().put(CONTAINER, ARCHIVE, archive());
    long raw = archive().getBytes(StandardCharsets.UTF_8).length;
    // after the upload is gone there is room for the second attachment, not for both again
    quota.quotaBytes = raw + 100;
    long upload = 100 + ATTACHMENT_BYTES / 2;
    quota.afterNextIntake = () -> uploadConcurrently(upload);

    IndexingJob atTheQuota = run(store);

    assertThat(atTheQuota.getFailureCategory()).isEqualTo("QUOTA_EXHAUSTED");
    assertThat(stored(attachmentPath(store, 0))).isTrue();
    assertThat(stored(attachmentPath(store, 1))).isFalse();

    removeUpload();
    IndexingJob next = run(store.reset());

    assertThat(rejectionsOf(next)).as("neither the archive nor its attachment").isEmpty();
    assertThat(next.getFailureCategory()).isNull();
    assertThat(stored(attachmentPath(store, 1))).isTrue();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /** The per-library quota of these runs, and what happens right after the next admission. */
  private static final class AdjustableQuota extends LibraryStorageQuotaService {

    private long quotaBytes;
    private Runnable afterNextIntake;

    AdjustableQuota(DocumentRepository documentRepository, PersonalStorageQuota personalQuota) {
      super(documentRepository, new LibraryProperties(0), personalQuota);
    }

    @Override
    public long quotaBytes() {
      return quotaBytes;
    }

    @Override
    public IntakeHold holdIntake(KnowledgeLibrary library) {
      IntakeHold hold = super.holdIntake(library);
      return () -> {
        hold.close();
        Runnable next = afterNextIntake;
        afterNextIntake = null;
        if (next != null) {
          next.run();
        }
      };
    }
  }

  private static final String UPLOAD_PATH = "upload/gleichzeitig.txt";

  /** An upload into the same library that takes {@code bytes} of its room. */
  private void uploadConcurrently(long bytes) {
    Document upload =
        new Document("gleichzeitig.txt", UPLOAD_PATH, "text/plain", bytes, SourceType.UPLOAD);
    upload.setLibraryId(library.getId());
    upload.setOrganizationId(library.getOrganizationId());
    documentRepository.save(upload);
  }

  private void removeUpload() {
    documentRepository.delete(row(UPLOAD_PATH));
  }

  /** A mail with a short body and two text attachments of {@link #ATTACHMENT_BYTES} each. */
  private static String archive() {
    return ("""
        From: absender@example.org
        To: empfaenger@example.org
        Subject: Zwei Anlagen
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary="GRENZE"

        --GRENZE
        Content-Type: text/plain; charset=utf-8

        Text der Mail.
        --GRENZE
        Content-Type: text/plain; name="anlage-1.txt"
        Content-Disposition: attachment; filename="anlage-1.txt"

        %s
        --GRENZE
        Content-Type: text/plain; name="anlage-2.txt"
        Content-Disposition: attachment; filename="anlage-2.txt"

        %s
        --GRENZE--
        """
            .formatted(
                text("Erste Anlage", ATTACHMENT_BYTES), text("Zweite Anlage", ATTACHMENT_BYTES)))
        .replace("\n", "\r\n");
  }

  /** Text of exactly {@code bytes} bytes that begins with {@code key}, in lines of 70. */
  private static String text(String key, int bytes) {
    StringBuilder text = new StringBuilder(key).append(' ');
    while (text.length() < bytes) {
      text.append(text.length() % 70 == 0 ? '\n' : 'x');
    }
    return text.substring(0, bytes);
  }

  private static String pathOf(InMemoryFileStore store) {
    return store.filePathOf(CONTAINER, ARCHIVE);
  }

  private static String attachmentPath(InMemoryFileStore store, int index) {
    return AttachmentFilePath.of(pathOf(store), index, "anlage-" + (index + 1) + ".txt");
  }

  private Document row(String filePath) {
    return documentRepository.findByLibraryIdAndFilePath(library.getId(), filePath).orElseThrow();
  }

  private boolean stored(String filePath) {
    return documentRepository.findByLibraryIdAndFilePath(library.getId(), filePath).isPresent();
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
            quota,
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
                  new FileSyncSettings(1024 * 1024, 1_000, 1, "library-quota-download-", null),
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
