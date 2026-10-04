package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.JobTriggerSource;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryFolder;
import io.opaa.knowledge.LibraryFolderRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.ProductionDocumentFormats;
import io.opaa.test.SourceTypes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A folder chain longer than 2000 characters through the real document path and the Liquibase
 * schema: stored uncut, and a run over it without a change costs neither a download nor a failure.
 */
@OpaaIntegrationTest
class FileSyncLongHierarchyPathIntegrationTest {

  private static final SourceType TYPE = SourceTypes.S3;

  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingRunEventRepository eventRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryFolderRepository folderRepository;
  @Autowired private LibraryFolderService folderService;
  @Autowired private LibraryStorageQuotaService quotaService;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private StaleDocumentCleanupService cleanupService;
  @Autowired private SourceSyncStateRepository syncStateRepository;
  @Autowired private ScanJournal scanJournal;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Long Path IT', now(), ?, ?)",
        userId,
        "long-path-it-" + userId,
        "long-path-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Ablage mit tiefen Ordnern",
                null,
                userId,
                TYPE,
                null,
                "https://ablage.example",
                null,
                null,
                false));
  }

  @AfterEach
  void tearDown() {
    if (library != null) {
      List<Document> documents =
          documentRepository.findByLibraryIdAndSourceType(library.getId(), TYPE);
      for (Document document : documents) {
        vectorChunkStore.deleteByDocumentId(document.getId());
        documentRepository.delete(document);
      }
      List<LibraryFolder> folders =
          new ArrayList<>(folderRepository.findByLibraryId(library.getId()));
      folders.sort(Comparator.comparingInt(this::depthOf));
      Collections.reverse(folders);
      folders.forEach(folderRepository::delete);
      jdbcTemplate.update(
          "DELETE FROM indexing_run_events WHERE job_id IN"
              + " (SELECT id FROM indexing_jobs WHERE library_id = ?)",
          library.getId());
      jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", library.getId());
      libraryRepository.deleteById(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void aSecondRunOverAnUnchangedFileUnderAPathBeyond2000CharactersNeitherFailsNorDownloads() {
    // regression guard for #2201: the row keeps the uncut path, so the folder memory and the
    // pre-fetch check find the file at its place again
    List<String> segments = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      segments.add(Character.toString('a' + i).repeat(230));
    }
    String name = String.join("/", segments) + "/bericht.txt";
    String hierarchyPath = String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, segments);
    assertThat(hierarchyPath).hasSizeGreaterThan(2100);
    InMemoryFileStore store =
        new InMemoryFileStore()
            .withFolderMarkers()
            .withStableIds()
            .container("A")
            .put("A", name, "Ein Bericht aus tiefen Ordnern.");

    IndexingJob first = run(store);
    IndexingJob second = run(store.reset());

    assertThat(first.getDocumentsProcessed()).isEqualTo(1);
    List<String> protocol =
        eventRepository.findByJobIdOrderByCreatedAtAsc(second.getId()).stream()
            .map(event -> event.getCategory() + ": " + event.getMessage())
            .toList();
    Document stored =
        documentRepository
            .findByLibraryIdAndFilePath(library.getId(), store.filePathOf("A", name))
            .orElseThrow();
    SoftAssertions.assertSoftly(
        softly -> {
          softly
              .assertThat(second.getDocumentsFailed())
              .as("failed entries of the second run, protocol %s", protocol)
              .isZero();
          softly
              .assertThat(store.calls())
              .as("the unchanged folder is neither listed nor fetched again")
              .containsExactly("list A");
          softly.assertThat(second.getMetrics().bytesDownloaded()).isZero();
          softly.assertThat(second.getDocumentsProcessed()).isZero();
          softly
              .assertThat(stored.getSourceHierarchyPath())
              .as("the stored hierarchy path")
              .isEqualTo(hierarchyPath);
        });
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
                  new FileSyncSettings(
                      FileSyncHarness.MAX_FILE_SIZE, 1_000, 1, "long-path-download-", null),
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

  private int depthOf(LibraryFolder folder) {
    int depth = 0;
    UUID parent = folder.getParentFolderId();
    while (parent != null) {
      depth++;
      parent = folderRepository.findById(parent).map(LibraryFolder::getParentFolderId).orElse(null);
    }
    return depth;
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
