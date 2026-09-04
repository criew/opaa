package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderRepository;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.organization.Organization;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end coverage of #1277 (ADR-0020 Nachtrag): an {@code HTTP_DIRECTORY} library mirrors the
 * crawled directory structure into {@code library_folders}, with the same materialize/prune rules
 * {@code FILESYSTEM} already follows - pruning only after a complete run. Drives the real,
 * Spring-wired bean graph against a loopback {@code com.sun.net.httpserver.HttpServer} serving a
 * {@code <ul>}-style autoindex; only the executor itself is hand-built, so crawler and downloader
 * can use {@link TargetAddressValidator#disabled()} without a context-splitting property override
 * (mirrors {@link UrlAttachmentIndexingIntegrationTest}).
 *
 * <p>{@code servedFiles} is keyed by the <em>raw</em> (still percent-encoded) path below the start
 * URL, and the stub answers on {@code getRawPath()}: only that way can a directory name whose
 * encoding matters ({@code Verg%C3%BCtung}, {@code %2E%2E}) be served exactly as a real autoindex
 * would.
 */
@OpaaIndexingIntegrationTest
class UrlFolderMappingIntegrationTest {

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingRunEventRepository indexingRunEventRepository;
  @Autowired private LibraryStorageQuotaService storageQuotaService;
  @Autowired private StaleDocumentCleanupService staleDocumentCleanupService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryFolderRepository folderRepository;
  @Autowired private LibraryFolderService folderService;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  private HttpServer server;
  private String baseUrl;

  /** Raw (percent-encoded) path below {@code /dokumente/} to file content. */
  private final Map<String, byte[]> servedFiles = new ConcurrentHashMap<>();

  /** Raw directory paths below {@code /dokumente/} whose listing answers with 500. */
  private final CopyOnWriteArraySet<String> unreachableDirectories = new CopyOnWriteArraySet<>();

  private UUID userId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/dokumente/", this::handle);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'URL Folder IT', now(), ?, ?)",
        userId,
        "url-folder-it-" + userId,
        "url-folder-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Webverzeichnis",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.HTTP_DIRECTORY,
                null,
                baseUrl + "/dokumente/",
                null,
                null,
                false));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    // Library-scoped cleanup, deepest-first for fk_documents_parent (#1217: no TRUNCATE on shared
    // tables); folders only after their documents, for fk_documents_folder.
    List<Document> documents =
        documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY);
    documents.stream()
        .sorted(Comparator.comparingInt((Document d) -> d.getFilePath().length()).reversed())
        .forEach(
            document -> {
              vectorChunkStore.deleteByDocumentId(document.getId());
              documentRepository.delete(document);
            });
    folderRepository.findByLibraryId(library.getId()).stream()
        .sorted(Comparator.comparingInt((LibraryFolder f) -> depthOf(f)).reversed())
        .forEach(folderRepository::delete);
    jdbcTemplate.update(
        "DELETE FROM indexing_run_events WHERE job_id IN"
            + " (SELECT id FROM indexing_jobs WHERE library_id = ?)",
        library.getId());
    jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", library.getId());
    libraryRepository.deleteById(library.getId());
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

  private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    String raw = exchange.getRequestURI().getRawPath().substring("/dokumente/".length());
    if (raw.isEmpty() || raw.endsWith("/")) {
      if (unreachableDirectories.contains(raw)) {
        exchange.sendResponseHeaders(500, -1);
        exchange.close();
        return;
      }
      respond(exchange, "text/html", listingOf(raw).getBytes(StandardCharsets.UTF_8));
      return;
    }
    byte[] body = servedFiles.get(raw);
    if (body == null) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    respond(exchange, "text/plain", body);
  }

  private void respond(
      com.sun.net.httpserver.HttpExchange exchange, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
    exchange.close();
  }

  /** The direct children of {@code directory}, rendered as the plain {@code <ul>} autoindex. */
  private String listingOf(String directory) {
    StringBuilder listing =
        new StringBuilder("<html><head><title>Index of /dokumente/</title></head><body><ul>");
    servedFiles.keySet().stream()
        .filter(path -> path.startsWith(directory))
        .map(path -> path.substring(directory.length()))
        .map(rest -> rest.contains("/") ? rest.substring(0, rest.indexOf('/') + 1) : rest)
        .distinct()
        .forEach(
            child ->
                listing
                    .append("<li><a href=\"")
                    .append(child)
                    .append("\">")
                    .append(child)
                    .append("</a></li>"));
    return listing.append("</ul></body></html>").toString();
  }

  private UrlIndexingExecutor executor(CrawlProperties crawlProperties) {
    TargetAddressValidator validator = TargetAddressValidator.disabled();
    return new UrlIndexingExecutor(
        new AutoindexCrawlerService(validator, crawlProperties),
        new BoundedDownloader(validator),
        fileProcessingService,
        indexingJobService,
        documentRepository,
        indexingRunEventRepository,
        storageQuotaService,
        staleDocumentCleanupService,
        crawlProperties,
        folderService);
  }

  /** Runs one full executor pass - synchronous, since the executor is called directly here. */
  private IndexingJob run() {
    return run(new CrawlProperties(0, 0, 0));
  }

  private IndexingJob run(CrawlProperties crawlProperties) {
    IndexingJob job = indexingJobService.startJob(library.getId(), Organization.DEFAULT_ID);
    executor(crawlProperties).execute(job.getId(), library);
    return job;
  }

  private Optional<LibraryFolder> findFolder(UUID parentFolderId, String name) {
    return folderRepository.findByLibraryId(library.getId()).stream()
        .filter(folder -> Objects.equals(folder.getParentFolderId(), parentFolderId))
        .filter(folder -> folder.getName().equals(name))
        .findFirst();
  }

  private Document documentAt(String rawPath) {
    return documentRepository
        .findByLibraryIdAndFilePath(library.getId(), baseUrl + "/dokumente/" + rawPath)
        .orElseThrow();
  }

  @Test
  void aCrawledDirectoryTreeIsMirroredAsFolders() {
    // AK 1: the same file name in two subtrees becomes two documents in two distinct folders.
    servedFiles.put("2025/protokolle/a.txt", "Protokoll 2025.".getBytes(StandardCharsets.UTF_8));
    servedFiles.put("2024/protokolle/a.txt", "Protokoll 2024.".getBytes(StandardCharsets.UTF_8));
    servedFiles.put("wurzel.txt", "Wurzeldokument.".getBytes(StandardCharsets.UTF_8));

    run();

    LibraryFolder jahr2025 = findFolder(null, "2025").orElseThrow();
    LibraryFolder jahr2024 = findFolder(null, "2024").orElseThrow();
    LibraryFolder protokolle2025 = findFolder(jahr2025.getId(), "protokolle").orElseThrow();
    LibraryFolder protokolle2024 = findFolder(jahr2024.getId(), "protokolle").orElseThrow();

    assertThat(documentAt("2025/protokolle/a.txt").getFolderId()).isEqualTo(protokolle2025.getId());
    assertThat(documentAt("2024/protokolle/a.txt").getFolderId()).isEqualTo(protokolle2024.getId());
    assertThat(documentAt("wurzel.txt").getFolderId()).isNull();
    assertThat(documentRepository.countByFolderId(protokolle2025.getId())).isEqualTo(1);
    assertThat(documentRepository.countByFolderId(protokolle2024.getId())).isEqualTo(1);
  }

  @Test
  void repeatedRunsReuseTheSameFolderRows() {
    servedFiles.put("archiv/protokoll.txt", "Protokoll.".getBytes(StandardCharsets.UTF_8));

    run();
    UUID firstFolderId = findFolder(null, "archiv").orElseThrow().getId();
    run();

    List<LibraryFolder> archiv =
        folderRepository.findByLibraryId(library.getId()).stream()
            .filter(folder -> folder.getName().equals("archiv"))
            .toList();
    assertThat(archiv).hasSize(1);
    assertThat(archiv.getFirst().getId()).isEqualTo(firstFolderId);
  }

  @Test
  void anEmptiedSubdirectoryDisappearsOnlyAfterACompleteRun() {
    // AK 2: pruning is bound to a run whose bestand is trustworthy. A truncated crawl leaves the
    // orphaned folder standing, the next complete run removes it.
    servedFiles.put("alt/a.txt", "Alt.".getBytes(StandardCharsets.UTF_8));
    servedFiles.put("bleibt/b.txt", "Bleibt.".getBytes(StandardCharsets.UTF_8));
    servedFiles.put("bleibt/c.txt", "Bleibt auch.".getBytes(StandardCharsets.UTF_8));

    run();
    UUID altId = findFolder(null, "alt").orElseThrow().getId();
    UUID bleibtId = findFolder(null, "bleibt").orElseThrow().getId();

    servedFiles.remove("alt/a.txt");

    // maxEntries = 1 cuts the crawl short, so this run's own bestand cannot stand in for the
    // source's complete one - neither the document nor the folder may be removed.
    run(new CrawlProperties(0, 1, 0));
    assertThat(folderRepository.findById(altId)).isPresent();

    run();

    assertThat(folderRepository.findById(altId)).isEmpty();
    assertThat(folderRepository.findById(bleibtId)).isPresent();
  }

  @Test
  void anEmptiedSubdirectoryStaysWhenASubdirectoryCouldNotBeFetched() {
    // AK 2, incomplete branch: an unreachable subtree is a different reason than a configured
    // limit, with the same consequence - nothing may be pruned by absence.
    servedFiles.put("alt/a.txt", "Alt.".getBytes(StandardCharsets.UTF_8));
    servedFiles.put("gestoert/b.txt", "Gestoert.".getBytes(StandardCharsets.UTF_8));

    run();
    UUID altId = findFolder(null, "alt").orElseThrow().getId();

    servedFiles.remove("alt/a.txt");
    unreachableDirectories.add("gestoert/");

    run();

    assertThat(folderRepository.findById(altId)).isPresent();
  }

  @Test
  void percentEncodedDirectorySegmentsAreDecodedIntoFolderNames() {
    // AK 3, decoding branch.
    servedFiles.put("Verg%C3%BCtung/lohn.txt", "Lohntabelle.".getBytes(StandardCharsets.UTF_8));

    run();

    LibraryFolder verguetung = findFolder(null, "Vergütung").orElseThrow();
    assertThat(documentAt("Verg%C3%BCtung/lohn.txt").getFolderId()).isEqualTo(verguetung.getId());
  }

  @Test
  void aSegmentThatDecodesToATraversalOrSeparatorLandsAtTheLibraryRoot() {
    // AK 3, rejection branch: "%2E%2E" and "%2F" survive URI normalization and only become a
    // traversal/separator once decoded - neither may become a folder name.
    servedFiles.put("%2E%2E/gefahr.txt", "Aufsteigend.".getBytes(StandardCharsets.UTF_8));
    servedFiles.put("a%2Fb/trenner.txt", "Trenner.".getBytes(StandardCharsets.UTF_8));

    run();

    assertThat(documentAt("%2E%2E/gefahr.txt").getFolderId()).isNull();
    assertThat(documentAt("a%2Fb/trenner.txt").getFolderId()).isNull();
    assertThat(folderRepository.findByLibraryId(library.getId())).isEmpty();
  }

  @Test
  void backfillsFolderIdOnADocumentIndexedBeforeFolderMappingExisted() {
    // AK 5: a row from before #1277 - correct content, folder_id still NULL. The next run must
    // assign the folder without re-indexing the document.
    byte[] content = "Protokoll aus 2025.".getBytes(StandardCharsets.UTF_8);
    servedFiles.put("archiv/2025/protokoll.txt", content);

    UUID legacyDocumentId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id,"
            + " created_at, folder_id) VALUES (?, ?, ?, 'text/plain', ?, 1, now(), ?, 'INDEXED',"
            + " 'HTTP_DIRECTORY', ?, ?, now(), NULL)",
        legacyDocumentId,
        "protokoll.txt",
        baseUrl + "/dokumente/archiv/2025/protokoll.txt",
        (long) content.length,
        sha256(content),
        library.getId(),
        Organization.DEFAULT_ID);

    IndexingJob job = run();

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getDocumentsSkipped()).isEqualTo(1);
    assertThat(completedJob.getDocumentsProcessed()).isZero();

    Document backfilled = documentRepository.findById(legacyDocumentId).orElseThrow();
    LibraryFolder archiv = findFolder(null, "archiv").orElseThrow();
    LibraryFolder jahr2025 = findFolder(archiv.getId(), "2025").orElseThrow();
    assertThat(backfilled.getFolderId()).isEqualTo(jahr2025.getId());
    // Never re-indexed: same checksum, same chunk count as the untouched legacy row.
    assertThat(backfilled.getChecksum()).isEqualTo(sha256(content));
    assertThat(backfilled.getChunkCount()).isEqualTo(1);
    assertThat(backfilled.getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void folderCrudOnAnHttpDirectoryLibraryIsRejectedWith409() {
    // AK 4: the CRUD endpoints stay closed for this type even though an indexing run now writes
    // folders for it - ConflictException is what LibraryController maps to 409.
    CurrentUser caller =
        CurrentUser.of(userId, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, null);

    assertThatThrownBy(() -> folderService.createFolder(library.getId(), "Neu", null, caller))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () -> folderService.renameFolder(library.getId(), UUID.randomUUID(), "Neu", caller))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> folderService.deleteFolder(library.getId(), UUID.randomUUID(), caller))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void anAttachmentLandsInTheFolderOfItsParentMail() throws Exception {
    // AK 7: ADR-0022's attachment path is unchanged - the attachment simply inherits its mail's
    // folder instead of staying at the library root.
    servedFiles.put("2025/anfrage.eml", emlWithTextAttachment("Bitte pruefen.", "Anhangsinhalt."));

    run();

    LibraryFolder jahr2025 = findFolder(null, "2025").orElseThrow();
    Document mail = documentAt("2025/anfrage.eml");
    List<Document> attachments = documentRepository.findByParentDocumentId(mail.getId());
    assertThat(attachments).hasSize(1);
    assertThat(mail.getFolderId()).isEqualTo(jahr2025.getId());
    assertThat(attachments.getFirst().getFolderId()).isEqualTo(jahr2025.getId());
  }

  private String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] emlWithTextAttachment(String bodyText, String attachmentText) throws Exception {
    Message message =
        Message.Builder.of()
            .setSubject("Anfrage")
            .setFrom("a@example.org")
            .setTo("b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart(bodyText, StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(attachmentText.getBytes(StandardCharsets.UTF_8), "text/plain")
                            .setContentDisposition("attachment", "anlage.txt"))
                    .build())
            .build();
    return DefaultMessageWriter.asBytes(message);
  }
}
