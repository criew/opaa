package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderRepository;
import io.opaa.library.LibraryFolderService;
import io.opaa.organization.Organization;
import io.opaa.sourceaccess.TargetAddressValidator;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The full sync against a real object store over the real, Spring-wired document path (ADR-0027,
 * #1382): what a fake cannot prove - pagination, path-style signing, ETag semantics of a real store
 * - is exercised here end to end, with MinIO in a container ({@link MinioFixture}) and the executor
 * hand-built over the real access layer (mirrors {@code S3FolderMappingIntegrationTest}). Every
 * test names the assurance it guards. Skipped without Docker; the CI runs it. Budget: one shared
 * MinIO container per JVM (~3 s start), a handful of small objects per test, well under a minute in
 * total.
 */
@OpaaIndexingIntegrationTest
class S3FullSyncMinioIntegrationTest {

  private static final String FIRST_TEXT = "Erste Fassung.";
  private static final String SECOND_TEXT = "Zweite Fassung mit mehr Text.";
  private static final String SMALL_TEXT = "klein";
  private static final String SESSION_TEXT = "Sitzung vom 6. September.";

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingRunEventRepository eventRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingRunTemplate indexingRunTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryFolderRepository folderRepository;
  @Autowired private LibraryFolderService folderService;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private StaleDocumentCleanupService cleanupService;
  @Autowired private SourceSyncStateRepository syncStateRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static MinioFixture minio;

  private final List<KnowledgeLibrary> createdLibraries = new ArrayList<>();
  private UUID userId;
  private String bucket;

  @BeforeAll
  static void start() {
    minio = MinioFixture.get();
  }

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'S3 MinIO IT', now(), ?, ?)",
        userId,
        "s3-minio-it-" + userId,
        "s3-minio-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    bucket = minio.createBucket("opaa-sync");
  }

  @AfterEach
  void tearDown() {
    for (KnowledgeLibrary created : createdLibraries) {
      List<Document> documents =
          documentRepository.findByLibraryIdAndSourceType(created.getId(), DocumentSourceType.S3);
      documents.stream()
          .sorted(Comparator.comparingInt((Document d) -> d.getFilePath().length()).reversed())
          .forEach(
              document -> {
                vectorChunkStore.deleteByDocumentId(document.getId());
                documentRepository.delete(document);
              });
      folderRepository.findByLibraryId(created.getId()).stream()
          .sorted(Comparator.comparingInt((LibraryFolder f) -> depthOf(f)).reversed())
          .forEach(folderRepository::delete);
      jdbcTemplate.update(
          "DELETE FROM indexing_run_events WHERE job_id IN"
              + " (SELECT id FROM indexing_jobs WHERE library_id = ?)",
          created.getId());
      jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", created.getId());
      libraryRepository.deleteById(created.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  private int depthOf(LibraryFolder folder) {
    int depth = 0;
    UUID parent = folder.getParentFolderId();
    while (parent != null) {
      depth++;
      Optional<LibraryFolder> next = folderRepository.findById(parent);
      if (next.isEmpty()) {
        break;
      }
      parent = next.get().getParentFolderId();
    }
    return depth;
  }

  private KnowledgeLibrary library(
      S3Credentials credentials, List<S3Scope> scopes, List<String> include, List<String> exclude) {
    KnowledgeLibrary fresh =
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "MinIO",
            null,
            userId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            minio.endpoint().toString(),
            null,
            credentials.accessKey() + ":" + credentials.secretKey(),
            false);
    fresh.updateS3Settings(
        new S3SourceSettings(MinioFixture.REGION, true, scopes, include, exclude));
    KnowledgeLibrary saved = libraryRepository.save(fresh);
    createdLibraries.add(saved);
    return saved;
  }

  private KnowledgeLibrary library(List<S3Scope> scopes) {
    return library(minio.rootCredentials(), scopes, null, null);
  }

  /** The production defaults (request budget included) with only the size bound replaced. */
  private static S3Properties withMaxObjectSize(long maxObjectSizeBytes) {
    S3Properties defaults = S3Properties.defaults();
    return new S3Properties(
        defaults.listPageSize(),
        maxObjectSizeBytes,
        defaults.requestTimeout(),
        defaults.maxRetries(),
        defaults.retryBackoff(),
        defaults.requestBudgetPerRun(),
        defaults.tempDirectory(),
        defaults.maxObjectsPerRun(),
        defaults.downloadConcurrency());
  }

  private S3IndexingExecutor executor(long maxObjectSizeBytes) {
    // the hand-built executor carries its own, disabled validator exactly as the URL directory
    // tests do; the shared context's validator is not involved
    S3Properties properties = withMaxObjectSize(maxObjectSizeBytes);
    return new S3IndexingExecutor(
        new S3ClientFactory(properties, TargetAddressValidator.disabled()),
        properties,
        fileProcessingService,
        documentRepository,
        folderService,
        cleanupService,
        syncStateRepository,
        Clock.systemUTC(),
        indexingRunTemplate);
  }

  /** One full run, synchronous - the executor is called directly. */
  private IndexingJob run(KnowledgeLibrary library, long maxObjectSizeBytes) {
    IndexingJob job =
        indexingJobService.startJob(
            library.getId(),
            Organization.DEFAULT_ID,
            JobTriggerSource.MANUAL,
            IndexingRunMode.FULL);
    executor(maxObjectSizeBytes).execute(job.getId(), library, IndexingRunMode.FULL);
    IndexingJob finished = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(finished.getStatus())
        .as("run %s: %s", job.getId(), finished.getErrorMessage())
        .isEqualTo(JobStatus.COMPLETED);
    return finished;
  }

  private IndexingJob run(KnowledgeLibrary library) {
    return run(library, 0);
  }

  private static long utf8Length(String text) {
    return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  private Optional<Document> documentAt(KnowledgeLibrary library, String bucketName, String key) {
    return documentRepository.findByLibraryIdAndFilePath(
        library.getId(), S3FullSync.filePath(bucketName, key));
  }

  private List<IndexingRunEvent> eventsOf(IndexingJob job, IndexingEventCategory category) {
    return eventRepository.findByJobIdOrderByCreatedAtAsc(job.getId()).stream()
        .filter(event -> event.getCategory() == category)
        .toList();
  }

  private Optional<LibraryFolder> findFolder(KnowledgeLibrary library, UUID parent, String name) {
    return folderRepository.findByLibraryId(library.getId()).stream()
        .filter(folder -> Objects.equals(folder.getParentFolderId(), parent))
        .filter(folder -> folder.getName().equals(name))
        .findFirst();
  }

  @Test
  void firstRunIndexesEveryAdmittedObjectAndSkipsFolderMarkersAndUnsupportedFormats() {
    // Assurance: the first run takes up every supported object of the scope with the real store's
    // ETag as change feature; a folder marker and an unsupported format never become documents.
    minio.putObject(bucket, "2025/protokolle/", new byte[0], "application/x-directory");
    minio.putObject(bucket, "2025/protokolle/sitzung.txt", SESSION_TEXT, "text/plain");
    minio.putObject(bucket, "2025/protokolle/foto.png", new byte[64], "image/png");
    KnowledgeLibrary library = library(List.of(S3Scope.of(bucket, "2025/")));

    IndexingJob job = run(library);

    Document sitzung = documentAt(library, bucket, "2025/protokolle/sitzung.txt").orElseThrow();
    assertThat(sitzung.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(sitzung.getSourceType()).isEqualTo(DocumentSourceType.S3);
    assertThat(sitzung.getLastModifiedRemote())
        .startsWith("e:")
        .endsWith("|" + utf8Length(SESSION_TEXT));
    assertThat(sitzung.getSourceContainerKey()).isEqualTo(bucket);
    assertThat(sitzung.getSourceHierarchyPath()).isEqualTo("protokolle");
    assertThat(documentAt(library, bucket, "2025/protokolle/")).isEmpty();
    assertThat(documentAt(library, bucket, "2025/protokolle/foto.png")).isEmpty();
    assertThat(job.getDocumentsProcessed()).isEqualTo(1);
    assertThat(job.getDocumentsSkipped()).as("marker and image").isEqualTo(2);
    assertThat(job.getListingComplete()).isTrue();
    assertThat(eventsOf(job, IndexingEventCategory.UNSUPPORTED_FORMAT))
        .extracting(IndexingRunEvent::getReference)
        .contains(S3FullSync.filePath(bucket, "2025/protokolle/foto.png"));
  }

  @Test
  void anUnchangedObjectCostsNoDownloadAndAChangedOneKeepsItsDocumentId() {
    // Assurance: the ETag the real store reports is stable across runs, so the second run lists
    // only (one request, nothing downloaded); a changed object is re-indexed under the same id.
    minio.putObject(bucket, "a.txt", FIRST_TEXT, "text/plain");
    KnowledgeLibrary library = library(List.of(S3Scope.of(bucket, "")));
    run(library);
    Document first = documentAt(library, bucket, "a.txt").orElseThrow();

    IndexingJob unchanged = run(library);
    assertThat(unchanged.getDocumentsSkipped()).isEqualTo(1);
    assertThat(unchanged.getDocumentsProcessed()).isZero();
    assertThat(unchanged.getMetrics().requestsSent()).as("the listing only").isEqualTo(1);
    assertThat(unchanged.getMetrics().bytesDownloaded()).isZero();

    minio.putObject(bucket, "a.txt", SECOND_TEXT, "text/plain");
    IndexingJob changed = run(library);
    Document second = documentAt(library, bucket, "a.txt").orElseThrow();
    assertThat(changed.getDocumentsProcessed()).isEqualTo(1);
    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(second.getChecksum()).isNotEqualTo(first.getChecksum());
    assertThat(second.getLastModifiedRemote()).isNotEqualTo(first.getLastModifiedRemote());
    assertThat(changed.getMetrics().bytesDownloaded()).isEqualTo(utf8Length(SECOND_TEXT));
  }

  @Test
  void aRemovedObjectDisappearsAfterACompleteRunButNotAfterAnUnlistableScope() {
    // Assurance: deletion needs a complete listing; a scope the credentials cannot list (a real
    // 403 from the store) keeps the whole document set and is named in the assessment.
    minio.putObject(bucket, "bleibt.txt", "Bleibt.", "text/plain");
    minio.putObject(bucket, "geht.txt", "Geht.", "text/plain");
    String geheim = minio.createBucket("opaa-geheim");
    minio.putObject(geheim, "vertrag.txt", "Vertrag.", "text/plain");
    S3Credentials ownOnly =
        minio.createUser(
            MinioFixture.policyAllowing(
                bucket, "s3:ListBucket", "s3:GetObject", "s3:GetBucketLocation"));
    KnowledgeLibrary library =
        library(ownOnly, List.of(S3Scope.of(bucket, ""), S3Scope.of(geheim, "")), null, null);

    IndexingJob incomplete = run(library);
    assertThat(incomplete.getListingComplete()).isFalse();
    assertThat(incomplete.getUnlistedScopeKeys()).containsExactly(geheim);
    assertThat(documentAt(library, bucket, "bleibt.txt")).isPresent();
    assertThat(documentAt(library, bucket, "geht.txt")).isPresent();

    minio.deleteObject(bucket, "geht.txt");
    IndexingJob stillIncomplete = run(library);
    assertThat(documentAt(library, bucket, "geht.txt"))
        .as("no reconciliation while one scope is unlistable")
        .isPresent();
    assertThat(stillIncomplete.getListingComplete()).isFalse();

    KnowledgeLibrary complete =
        library(minio.rootCredentials(), List.of(S3Scope.of(bucket, "")), null, null);
    run(complete);
    minio.putObject(bucket, "spaeter.txt", "Später.", "text/plain");
    run(complete);
    minio.deleteObject(bucket, "spaeter.txt");
    IndexingJob reconciled = run(complete);
    assertThat(documentAt(complete, bucket, "spaeter.txt")).isEmpty();
    assertThat(documentAt(complete, bucket, "bleibt.txt")).isPresent();
    assertThat(reconciled.getListingComplete()).isTrue();
    assertThat(eventsOf(reconciled, IndexingEventCategory.REMOVED))
        .extracting(IndexingRunEvent::getReference)
        .containsExactly(S3FullSync.filePath(bucket, "spaeter.txt"));
  }

  @Test
  void patternsPrefixesAndSeveralScopesShapeTheDocumentSetAndTheFolderTree() {
    // Assurance: include/exclude globs apply to the full key; with one scope its prefix is the
    // root, with two scopes each has its own root chain of bucket and prefix segments.
    minio.putObject(bucket, "2025/q1/a.txt", "A.", "text/plain");
    minio.putObject(bucket, "2025/q1/entwurf-b.txt", "B.", "text/plain");
    minio.putObject(bucket, "2025/notiz.md", "# Notiz", "text/markdown");
    String satzungen = minio.createBucket("opaa-satzungen");
    minio.putObject(satzungen, "haupt.txt", "Hauptsatzung.", "text/plain");

    KnowledgeLibrary single =
        library(
            minio.rootCredentials(),
            List.of(S3Scope.of(bucket, "2025/")),
            List.of("**/*.txt"),
            List.of("**/entwurf-*"));
    run(single);
    assertThat(documentAt(single, bucket, "2025/q1/a.txt")).isPresent();
    assertThat(documentAt(single, bucket, "2025/q1/entwurf-b.txt")).isEmpty();
    assertThat(documentAt(single, bucket, "2025/notiz.md")).isEmpty();
    LibraryFolder q1 = findFolder(single, null, "q1").orElseThrow();
    assertThat(documentAt(single, bucket, "2025/q1/a.txt").orElseThrow().getFolderId())
        .isEqualTo(q1.getId());
    assertThat(findFolder(single, null, "2025")).as("the prefix is the root").isEmpty();

    KnowledgeLibrary several =
        library(List.of(S3Scope.of(bucket, "2025/"), S3Scope.of(satzungen, "")));
    run(several);
    LibraryFolder bucketRoot = findFolder(several, null, bucket).orElseThrow();
    LibraryFolder year = findFolder(several, bucketRoot.getId(), "2025").orElseThrow();
    assertThat(findFolder(several, year.getId(), "q1")).isPresent();
    LibraryFolder satzungenRoot = findFolder(several, null, satzungen).orElseThrow();
    assertThat(documentAt(several, satzungen, "haupt.txt").orElseThrow().getFolderId())
        .isEqualTo(satzungenRoot.getId());
  }

  @Test
  void anObjectOverTheSizeBoundIsRefusedBeforeItsDownloadAndStaysPresent() {
    // Assurance: the listed size is checked against the bound before any transfer; the object is
    // named in the protocol, stays part of the document set and never becomes a document.
    minio.putObject(bucket, "klein.txt", SMALL_TEXT, "text/plain");
    minio.putObject(bucket, "riesig.txt", new byte[4096], "text/plain");
    KnowledgeLibrary library = library(List.of(S3Scope.of(bucket, "")));

    IndexingJob job = run(library, 1024);

    assertThat(documentAt(library, bucket, "klein.txt")).isPresent();
    assertThat(documentAt(library, bucket, "riesig.txt")).isEmpty();
    assertThat(job.getMetrics().bytesDownloaded()).isEqualTo(utf8Length(SMALL_TEXT));
    assertThat(eventsOf(job, IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains("Größenobergrenze von 1024 Bytes"));
    assertThat(job.getListingComplete()).isTrue();
  }
}
