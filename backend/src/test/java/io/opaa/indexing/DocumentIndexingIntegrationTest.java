package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.query.QueryResult;
import io.opaa.query.QueryService;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@OpaaIndexingIntegrationTest
class DocumentIndexingIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("document-indexing");

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingRunEventRepository indexingRunEventRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private QueryService queryService;
  @Autowired private ChatModel chatModel;

  // #758: AnswerGenerationService now resolves its ChatClient via ActiveChatModelResolver on every
  // call rather than holding one built once at startup - stubbed below to always hand back a
  // ChatClient wrapping the chatModel mock above.
  @Autowired private ActiveChatModelResolver activeChatModelResolver;

  private UUID userId;
  private UUID targetLibraryId;

  /**
   * {@link CurrentUser} snapshot for {@link #userId} - SYSTEM_ADMIN, {@link
   * Organization#DEFAULT_ID}.
   */
  private CurrentUser asCaller() {
    return CurrentUser.of(userId, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, null);
  }

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    // Clean up any leftover files from previous tests
    if (Files.exists(classTempDir)) {
      try (var files = Files.list(classTempDir)) {
        files.forEach(
            f -> {
              try {
                Files.deleteIfExists(f);
              } catch (IOException e) {
                // ignore cleanup failures
              }
            });
      }
    }

    // #419: every trigger needs a caller-chosen library and a caller who actually holds at least
    // EDITOR on it - a system admin is NOT bypassed for an ordinary library any more (PR #431
    // review, Befund 2: the /trigger endpoint already requires SYSTEM_ADMIN, so bypassing the
    // EDITOR check for that flag too would make it unreachable in practice). userId is granted
    // OWNER on its own library explicitly below, exactly like a real KnowledgeLibraryService
    // library creation would. The previous run's library is deleted first -
    // fk_knowledge_libraries_owner_user is RESTRICT, so the user row cannot go while it still owns
    // one.
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'indexing-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'indexing-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'indexing-it@example.com',"
            + " 'Indexing IT User', now(), ?, ?)",
        userId,
        "indexing-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);

    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zielbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                classTempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    targetLibraryId = library.getId();
    grantOwner(targetLibraryId, userId);
  }

  private void grantOwner(UUID libraryId, UUID granteeId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        Organization.DEFAULT_ID,
        granteeId);
  }

  private IndexingJob triggerIndexing() {
    return documentIndexingService.triggerIndexing(targetLibraryId, asCaller());
  }

  @Test
  void indexesDocumentsEndToEnd() throws IOException {
    Files.writeString(classTempDir.resolve("test.md"), "# Test Document\n\nThis is test content.");
    Files.writeString(classTempDir.resolve("notes.txt"), "Some plain text notes for testing.");

    IndexingJob job = triggerIndexing();
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(2);
    assertThat(completedJob.getDocumentsTotal()).isEqualTo(2);
    assertThat(completedJob.getDocumentsFailed()).isZero();
    assertThat(completedJob.getDocumentsSkipped()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(2);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getIndexedAt() != null);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);
    assertThat(documents).allMatch(d -> d.getChecksum() != null && d.getChecksum().length() == 64);
    // #201: every document belongs to exactly one library - against the real Liquibase schema,
    // not just the mocked FileProcessingServiceTest, so a missing fk_documents_library_organization
    // constraint or a NULL library_id would fail this insert, not just this assertion.
    assertThat(documents).allMatch(d -> targetLibraryId.equals(d.getLibraryId()));
    assertThat(documents).allMatch(d -> Organization.DEFAULT_ID.equals(d.getOrganizationId()));

    // Verify chunks with embeddings were stored in vector_store
    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("test").topK(100).similarityThreshold(0.0).build());
    assertThat(results).isNotEmpty();
    assertThat(results).allMatch(r -> r.getText() != null && !r.getText().isBlank());
    assertThat(results).allMatch(r -> r.getMetadata().containsKey("document_id"));
    assertThat(results)
        .allMatch(r -> targetLibraryId.toString().equals(r.getMetadata().get("library_id")));
    assertThat(results)
        .allMatch(
            r -> Organization.DEFAULT_ID.toString().equals(r.getMetadata().get("organization_id")));
  }

  @Test
  void skipsUnsupportedFileFormatsAndContinues() throws IOException {
    Files.writeString(classTempDir.resolve("good.txt"), "Valid content here.");
    Files.writeString(classTempDir.resolve("bad.xyz"), "a,b,c");

    IndexingJob job = triggerIndexing();
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    // Only .txt is a supported format, .xyz is rejected by the shared format list.
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedJob.getDocumentsFailed()).isZero();
    // Issue #375: a rejected document must be reported, not silently dropped. Both files were
    // found, so both are part of the job's total, and the rejected one shows up as skipped —
    // otherwise whoever runs the installation never learns that part of the stock went unindexed.
    assertThat(completedJob.getDocumentsTotal()).isEqualTo(2);
    assertThat(completedJob.getDocumentsSkipped()).isEqualTo(1);

    // #513: the run's own protocol names *why* the file was skipped, not just that it was -
    // without this, a rejected format is indistinguishable from any other skip reason once the
    // run has finished.
    List<IndexingRunEvent> events =
        indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(completedJob.getId());
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getCategory()).isEqualTo(IndexingEventCategory.UNSUPPORTED_FORMAT);
    assertThat(events.getFirst().getReference()).isEqualTo("bad.xyz");
    assertThat(completedJob.getEventsTruncatedCount()).isZero();

    // Verify only the supported file was indexed
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getFileName()).isEqualTo("good.txt");
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void retainsOnlyTheLastTenRunsPerLibraryAndPrunesTheirEvents() throws IOException {
    // #513, Umfangserweiterung (Maintainer-Ergaenzung 20.08.2026): only the last 10 runs of a
    // library stay around - older ones, and their own events, are pruned once an 11th run starts.
    Files.writeString(classTempDir.resolve("bad.xyz"), "a,b,c");

    // #604 review, nit (d): a second library's own single run, untouched by the first library's
    // eleven-run pruning below - proves retention is scoped per library, not to the first 10 rows
    // of indexing_jobs overall (which pruneOldRuns' own libraryId-scoped query would satisfy even
    // if it silently reverted to a global limit by mistake, unless another library's run is a
    // reference point for what must survive).
    KnowledgeLibrary otherLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Andere Bibliothek (Retention)",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                classTempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    UUID otherLibraryId = otherLibrary.getId();
    grantOwner(otherLibraryId, userId);
    IndexingJob otherLibraryJob =
        documentIndexingService.triggerIndexing(otherLibraryId, asCaller());
    awaitJobCompletion(otherLibraryJob);

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    var firstCompleted = indexingJobRepository.findById(firstJob.getId()).orElseThrow();
    assertThat(indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(firstCompleted.getId()))
        .as("the first run's own event exists before it gets pruned")
        .isNotEmpty();

    IndexingJob lastJob = firstJob;
    for (int i = 0; i < 10; i++) {
      lastJob = triggerIndexing();
      awaitJobCompletion(lastJob);
    }

    List<IndexingJob> remainingRuns =
        indexingJobRepository.findByLibraryIdOrderByStartedAtDesc(targetLibraryId);
    assertThat(remainingRuns).hasSize(10);
    assertThat(remainingRuns).noneMatch(job -> job.getId().equals(firstJob.getId()));
    assertThat(remainingRuns.getFirst().getId()).isEqualTo(lastJob.getId());

    // The pruned run's event is gone too (fk_indexing_run_events_job's ON DELETE CASCADE) - not
    // merely orphaned and still counted somewhere.
    assertThat(indexingJobRepository.findById(firstJob.getId())).isEmpty();
    assertThat(indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(firstJob.getId()))
        .isEmpty();

    // The other library's single run survived every one of targetLibraryId's eleven triggers -
    // pruning never looked past its own libraryId.
    assertThat(indexingJobRepository.findById(otherLibraryJob.getId())).isPresent();

    jdbcTemplate.update("DELETE FROM asset_grants WHERE library_id = ?", otherLibraryId);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", otherLibraryId);
  }

  @Test
  void indexesPdfAndDocxDocuments() throws IOException {
    copyTestResource("test-documents/test-document.pdf", "report.pdf");
    copyTestResource("test-documents/test-document.docx", "notes.docx");

    IndexingJob job = triggerIndexing();
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(2);
    assertThat(completedJob.getDocumentsFailed()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(2);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);

    // Verify chunks were stored in vector_store
    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("OPAA").topK(100).similarityThreshold(0.0).build());
    assertThat(results).isNotEmpty();
    assertThat(results).allMatch(r -> r.getText() != null && !r.getText().isBlank());

    // #1096/#1100 Hausstandard: pipeline_id at the stored chunk proves each file actually ran
    // through its own dedicated pipeline (PdfDocumentPipeline/DocxDocumentPipeline), not merely
    // through Tika - both would produce a non-empty, non-blank result above.
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT metadata->>'pipeline_id' FROM vector_store WHERE metadata->>'file_name' ="
                    + " ?",
                String.class,
                "report.pdf"))
        .isNotEmpty()
        .allMatch("pdf"::equals);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT metadata->>'pipeline_id' FROM vector_store WHERE metadata->>'file_name' ="
                    + " ?",
                String.class,
                "notes.docx"))
        .isNotEmpty()
        .allMatch("docx"::equals);
  }

  @Test
  void indexesOdfDocuments() throws IOException {
    // #1057: ODT/ODS/ODP are admitted the exact same way as their Microsoft counterparts (Teil 3,
    // Punkt 2). ODT and ODP resolve to their own OdtDocumentPipeline/OdpDocumentPipeline since
    // #1110; ODS resolves to TabularDocumentPipeline since #1058 - see
    // indexesXlsxCsvAndOdsDocumentsThroughTheTabularPipeline for the assertion that it actually
    // reads the file structurally rather than through the fallback.
    copyTestResource("test-documents/test-document.odt", "satzung.odt");
    copyTestResource("test-documents/test-document.ods", "haushalt.ods");
    copyTestResource("test-documents/test-document.odp", "vortrag.odp");

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(3);
    assertThat(completedJob.getDocumentsFailed()).isZero();
    assertThat(completedJob.getDocumentsSkipped()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(3);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);
  }

  @Test
  void indexesXlsxCsvAndOdsDocumentsThroughTheTabularPipeline() throws IOException {
    // #1096 review, finding 8: an end-to-end proof that XLSX/CSV/ODS actually flow through
    // TabularDocumentPipeline (admission -> registry -> pipeline -> stored chunk), not just the
    // pipeline's own unit tests - mirrors indexesPdfAndDocxDocuments's own end-to-end shape, with
    // the pipeline_id assertion the real point of this test.
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Gebühren");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("Leistung");
      header.createCell(1).setCellValue("Betrag");
      Row data = sheet.createRow(1);
      data.createCell(0).setCellValue("Personalausweis");
      data.createCell(1).setCellValue("37,00 EUR");
      try (var out = Files.newOutputStream(classTempDir.resolve("gebuehren.xlsx"))) {
        workbook.write(out);
      }
    }
    Files.writeString(classTempDir.resolve("zustaendigkeiten.csv"), "Name,Amt\nMüller,Bauamt\n");

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(2);
    assertThat(completedJob.getDocumentsFailed()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(2);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);

    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("Gebühren").topK(100).similarityThreshold(0.0).build());
    assertThat(results).isNotEmpty();
    assertThat(results)
        .allMatch(
            r ->
                "tabular"
                    .equals(r.getMetadata().get(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY)));
  }

  @Test
  void indexesHtmlDocumentsThroughTheHtmlPipeline() throws IOException {
    // #1059 review, finding 9: an end-to-end proof that .html actually flows through
    // HtmlDocumentPipeline (admission -> registry -> pipeline -> stored chunk), not just the
    // pipeline's own unit tests - mirrors indexesXlsxCsvAndOdsDocumentsThroughTheTabularPipeline's
    // own shape, with the pipeline_id and location assertions the real point of this test.
    Files.writeString(
        classTempDir.resolve("buergeramt.html"),
        "<html><body><nav><a href=\"/\">Startseite</a></nav>"
            + "<main><h1>Personalausweis beantragen</h1>"
            + "<p>Der Personalausweis ist ein amtliches Ausweisdokument.</p>"
            + "</main></body></html>");

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedJob.getDocumentsFailed()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);

    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("Personalausweis")
                .topK(100)
                .similarityThreshold(0.0)
                .build());
    assertThat(results).isNotEmpty();
    assertThat(results)
        .allMatch(
            r ->
                "html".equals(r.getMetadata().get(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY)));
    assertThat(results)
        .allMatch(
            r ->
                "Abschn. Personalausweis beantragen"
                    .equals(r.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY)));
  }

  @Test
  void indexesPptxDocumentsThroughTheDedicatedPipeline() throws IOException {
    // #1109 (Epic #1054/#1110 review, E3): PPTX was the only supported format never exercised
    // end-to-end at this layer (admission -> registry -> pipeline -> stored chunk) - only its own
    // unit test covered it. Mirrors indexesXlsxCsvAndOdsDocumentsThroughTheTabularPipeline's shape.
    try (XMLSlideShow ppt = new XMLSlideShow()) {
      XSLFSlide slide = ppt.createSlide();
      XSLFTextBox textBox = slide.createTextBox();
      textBox.setText("Der Personalausweis kostet 37,00 EUR Verwaltungsgebuehr.");
      try (var out = Files.newOutputStream(classTempDir.resolve("praesentation.pptx"))) {
        ppt.write(out);
      }
    }

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedJob.getDocumentsFailed()).isZero();
    assertThat(completedJob.getDocumentsSkipped()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(documents.getFirst().getChunkCount()).isPositive();

    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("Personalausweis")
                .topK(100)
                .similarityThreshold(0.0)
                .build());
    assertThat(results).isNotEmpty();
    // The pipeline_id proves the slide-per-chunk PptxDocumentPipeline actually ran, not the Tika
    // fallback, which would also happily produce a non-empty, non-blank chunk.
    assertThat(results)
        .allMatch(
            r ->
                "pptx".equals(r.getMetadata().get(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY)));
    assertThat(results)
        .allMatch(
            r -> "Folie 1".equals(r.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY)));
  }

  @Test
  void indexesEmlDocumentWithAttachmentRoutedRecursivelyThroughTheRealRegistry() throws Exception {
    // #1109 (Epic #1054/#1110 review, E3): EML/MSG were the only supported formats never exercised
    // end-to-end - and EML's attachment path is the only place in the whole system where
    // DocumentPipelineRegistry is used reentrantly (temp file -> sub-pipeline -> "Anhang: ..."
    // Fundort). MailDocumentPipelineTest already proves this against a hand-built registry; this
    // proves the real, circularly-wired Spring bean graph (MailDocumentPipeline's ObjectProvider<
    // DocumentPipelineRegistry>, see its own Javadoc) does the same thing.
    Message message =
        Message.Builder.of()
            .setSubject("Anfrage Bauantrag")
            .setFrom("Buergeramt <buergeramt@example.org>")
            .setTo("Sachbearbeitung <sachbearbeitung@example.org>")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Bitte pruefen Sie den Antrag.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(
                                "Anhangsinhalt fuer den Bauantrag."
                                    .getBytes(StandardCharsets.UTF_8),
                                "text/plain")
                            .setContentDisposition("attachment", "anlage.txt"))
                    .build())
            .build();
    Files.write(classTempDir.resolve("anfrage.eml"), DefaultMessageWriter.asBytes(message));

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedJob.getDocumentsFailed()).isZero();
    assertThat(completedJob.getDocumentsSkipped()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.INDEXED);
    // At least the body chunk and the attachment's own chunk.
    assertThat(documents.getFirst().getChunkCount()).isGreaterThanOrEqualTo(2);

    List<org.springframework.ai.document.Document> bodyResults =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("Bitte pruefen Sie den Antrag")
                .topK(100)
                .similarityThreshold(0.0)
                .build());
    assertThat(bodyResults).isNotEmpty();
    // Every chunk this pipeline produces - including the attachment's own - is attributed to the
    // mail pipeline's own id (MailDocumentPipeline's own Javadoc), never to the attachment's own
    // sub-pipeline (here, the Tika fallback for the .txt attachment).
    assertThat(bodyResults)
        .allMatch(
            r ->
                "email"
                    .equals(r.getMetadata().get(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY)));

    List<org.springframework.ai.document.Document> attachmentResults =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("Anhangsinhalt fuer den Bauantrag")
                .topK(100)
                .similarityThreshold(0.0)
                .build());
    assertThat(attachmentResults).isNotEmpty();
    assertThat(attachmentResults)
        .anyMatch(
            r ->
                "email".equals(r.getMetadata().get(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY))
                    && String.valueOf(r.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
                        .startsWith("Anhang: anlage.txt"));
  }

  @Test
  void anEmptyOdfDocumentIsRejectedInsteadOfIndexedWithZeroChunks() throws IOException {
    // #1055 guard carried over to ODF (#1057): a document that parses without error but yields no
    // usable text must be reported as skipped, the same way a scan PDF is - never silently INDEXED
    // with zero chunks.
    copyTestResource("test-documents/empty-document.odt", "leer.odt");

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isZero();
    assertThat(completedJob.getDocumentsFailed()).isZero();
    assertThat(completedJob.getDocumentsSkipped()).isEqualTo(1);
    // The document row itself is kept, marked FAILED with the same user-facing message a scan PDF
    // gets (DocumentService#NO_EXTRACTABLE_TEXT_MESSAGE) - not deleted or left INDEXED with zero
    // chunks.
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(documents.getFirst().getErrorMessage())
        .isEqualTo(DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
  }

  @Test
  void anEmptyOdpPresentationIsRejectedInsteadOfIndexedWithZeroChunks() throws IOException {
    // Same #1057 guard as anEmptyOdfDocumentIsRejectedInsteadOfIndexedWithZeroChunks above, for the
    // ODP counterpart: a <office:presentation/> without any draw:page must be reported as skipped,
    // not counted as failed.
    copyTestResource("test-documents/empty-document.odp", "leer.odp");

    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isZero();
    assertThat(completedJob.getDocumentsFailed()).isZero();
    assertThat(completedJob.getDocumentsSkipped()).isEqualTo(1);
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(documents.getFirst().getErrorMessage())
        .isEqualTo(DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
  }

  @Test
  void reindexingReplacesOldChunks() throws IOException {
    Files.writeString(classTempDir.resolve("doc.txt"), "Original content.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);

    var completedFirstJob = indexingJobRepository.findById(firstJob.getId()).orElseThrow();
    assertThat(completedFirstJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedFirstJob.getDocumentsSkipped()).isZero();

    // Remember initial state
    List<org.springframework.ai.document.Document> initialResults =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("content").topK(100).similarityThreshold(0.0).build());
    assertThat(initialResults).isNotEmpty();
    Document initialDoc = documentRepository.findAll().getFirst();
    assertThat(initialDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(initialDoc.getChecksum()).isNotNull();
    assertThat(initialDoc.getLibraryId()).isEqualTo(targetLibraryId);

    // Update file and re-index
    Files.writeString(classTempDir.resolve("doc.txt"), "Updated content with more text.");
    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);

    var completedSecondJob = indexingJobRepository.findById(secondJob.getId()).orElseThrow();
    assertThat(completedSecondJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedSecondJob.getDocumentsSkipped()).isZero();
    assertThat(documentRepository.count()).isEqualTo(1);

    // Verify the document content was actually re-indexed
    Document reindexedDoc = documentRepository.findAll().getFirst();
    assertThat(reindexedDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(reindexedDoc.getIndexedAt()).isNotNull();
    assertThat(reindexedDoc.getChecksum()).isNotEqualTo(initialDoc.getChecksum());
    // #201 acceptance criteria: re-indexing keeps the library assignment.
    assertThat(reindexedDoc.getLibraryId()).isEqualTo(targetLibraryId);

    // Verify chunk text was updated via similarity search
    List<org.springframework.ai.document.Document> newResults =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("Updated").topK(100).similarityThreshold(0.0).build());
    assertThat(newResults).isNotEmpty();
    String allChunkText =
        newResults.stream()
            .map(org.springframework.ai.document.Document::getText)
            .reduce("", String::concat);
    assertThat(allChunkText).contains("Updated");
  }

  @Test
  void indexingTheSameSourcePathIntoASecondLibraryLeavesTheFirstLibrarysChunksUntouched()
      throws IOException {
    // #877 (Epic #826, Befund B6): document identity is scoped to (library_id, file_path) -
    // indexing the same path into a second library must create an independent document with its
    // own chunks, never delete the first library's document/chunks the way the pre-#877 global
    // findByFilePath lookup did. Real Postgres/pgvector schema, not a mocked VectorStore, so the
    // library_id filter is actually exercised against real rows, not a string handed to a mock.
    // Same sourcePath as targetLibraryId's FILESYSTEM configuration (setUp) - both libraries watch
    // the same directory, so triggering otherLibraryId picks up the same file already indexed into
    // targetLibraryId.
    KnowledgeLibrary otherLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Andere Bibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                classTempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    UUID otherLibraryId = otherLibrary.getId();
    grantOwner(otherLibraryId, userId);

    Files.writeString(
        classTempDir.resolve("shared-source.txt"), "Content indexed into two libraries.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    assertThat(indexingJobRepository.findById(firstJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    Long chunksInOriginalLibrary =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            targetLibraryId.toString());
    assertThat(chunksInOriginalLibrary).isPositive();

    IndexingJob secondJob = documentIndexingService.triggerIndexing(otherLibraryId, asCaller());
    awaitJobCompletion(secondJob);
    assertThat(indexingJobRepository.findById(secondJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    Long chunksStillInOriginalLibrary =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            targetLibraryId.toString());
    assertThat(chunksStillInOriginalLibrary)
        .as("the first library's chunks must survive the second library's independent run")
        .isEqualTo(chunksInOriginalLibrary);

    Long chunksInNewLibrary =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            otherLibraryId.toString());
    assertThat(chunksInNewLibrary).isPositive();

    assertThat(
            documentRepository.findByLibraryIdAndFilePath(
                targetLibraryId, filePath("shared-source.txt")))
        .as("the first library keeps its own document")
        .isPresent();
    assertThat(
            documentRepository.findByLibraryIdAndFilePath(
                otherLibraryId, filePath("shared-source.txt")))
        .as("the second library has its own, independent document")
        .isPresent();
    assertThat(documentRepository.count()).isEqualTo(2);
  }

  private String filePath(String fileName) {
    return classTempDir.resolve(fileName).toAbsolutePath().toString();
  }

  @Test
  void aUserWithAGrantOnTheTargetLibraryFindsTheDocumentAndAUserWithoutOneDoesNot()
      throws IOException {
    // PR #431 review, Befund 3: closes the gap between "indexed through the real pipeline" and
    // "findable through /api/v1/query" - QueryIntegrationTest inserts its chunks by hand and never
    // exercises FileProcessingService at all, so this is the only test proving the two are
    // actually connected for a document that carries a caller-chosen library (#419).
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    var usage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return 10;
          }

          @Override
          public Integer getCompletionTokens() {
            return 10;
          }

          @Override
          public Object getNativeUsage() {
            return null;
          }
        };
    var chatResponseMetadata =
        ChatResponseMetadata.builder().model("test-model").usage(usage).build();
    var assistantMessage = new AssistantMessage("Answer referencing the indexed document.");
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(assistantMessage)), chatResponseMetadata));

    Files.writeString(
        classTempDir.resolve("findable.txt"), "A uniquely identifiable sentence about OPAA.");
    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);
    assertThat(indexingJobRepository.findById(job.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    // userId holds OWNER on targetLibraryId (granted in setUp) - the reader path.
    QueryResult withGrant =
        queryService.query(
            "uniquely identifiable sentence",
            null,
            CurrentUser.of(userId, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, null),
            true,
            java.util.List.of());
    assertThat(withGrant.getSources())
        .as("a user with a grant on the target library must find the indexed document")
        .anyMatch(source -> "findable.txt".equals(source.getFileName()));

    // A second user in the same organization with no grant on targetLibraryId - but not with an
    // empty readableLibraryIds altogether. Coordinator follow-up on the review: a stranger with
    // zero grants anywhere would let QueryService short-circuit on an empty readable-library set
    // before ever issuing the vector search, which would pass this assertion for the wrong reason
    // (no readable library at all, not "library_id filtered it out"). Granting the stranger a
    // completely unrelated library makes readableLibraryIds non-empty, so the negative result
    // actually exercises the library_id filter in the real similarity search.
    UUID strangerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'indexing-it-stranger@example.com',"
            + " 'Stranger', now(), 'USER', ?)",
        strangerId,
        "indexing-it-stranger-" + strangerId,
        Organization.DEFAULT_ID);
    KnowledgeLibrary strangerLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Bibliothek des Fremden",
                null,
                strangerId,
                LibraryVisibility.PRIVATE,
                false));
    grantOwner(strangerLibrary.getId(), strangerId);

    QueryResult withoutGrant =
        queryService.query(
            "uniquely identifiable sentence",
            null,
            CurrentUser.of(strangerId, Organization.DEFAULT_ID, SystemRole.USER, null),
            true,
            java.util.List.of());
    assertThat(withoutGrant.getSources())
        .as("a user without any grant on the target library must not find the indexed document")
        .noneMatch(source -> "findable.txt".equals(source.getFileName()));

    jdbcTemplate.update("DELETE FROM asset_grants WHERE library_id = ?", strangerLibrary.getId());
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", strangerLibrary.getId());
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", strangerId);
  }

  @Test
  void skipsUnchangedDocumentsOnReindex() throws IOException {
    Files.writeString(classTempDir.resolve("doc.txt"), "Same content.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);

    var completedFirstJob = indexingJobRepository.findById(firstJob.getId()).orElseThrow();
    assertThat(completedFirstJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedFirstJob.getDocumentsSkipped()).isZero();

    // Re-index without changing the file
    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);

    var completedSecondJob = indexingJobRepository.findById(secondJob.getId()).orElseThrow();
    assertThat(completedSecondJob.getDocumentsProcessed()).isZero();
    assertThat(completedSecondJob.getDocumentsSkipped()).isEqualTo(1);

    // Document record should still be there, unchanged
    assertThat(documentRepository.count()).isEqualTo(1);
    Document doc = documentRepository.findAll().getFirst();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(doc.getChecksum()).isNotNull();
    assertThat(doc.getChecksum()).hasSize(64);
  }

  @Test
  void triggerIndexingFailsTheJobWhenSourcePathIsOutsideTheConfiguredAllowlist() {
    // #484/ADR-0018 Entscheidung 6: the allowlist is enforced again at run time
    // (AsyncIndexingExecutor),
    // not only at library creation/update time - a Bestandsbibliothek whose sourcePath the
    // operator's
    // allowlist no longer covers must not silently succeed. This library is created directly
    // against
    // the repository (bypassing KnowledgeLibraryService's own creation-time check) with a
    // sourcePath outside this suite's configured allowlist (OpaaIndexingTestDirectory.BASE_DIR,
    // not just classTempDir - a sibling of classTempDir is still a subdirectory of BASE_DIR and
    // therefore still inside the allowlist), mirroring how such a library could exist if the
    // allowlist were narrowed after it was created.
    KnowledgeLibrary outsideAllowlistLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Ausserhalb der Allowlist",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                OpaaIndexingTestDirectory.BASE_DIR
                    .resolveSibling("opaa-484-outside-allowlist")
                    .toAbsolutePath()
                    .toString(),
                null,
                null,
                null,
                false));
    grantOwner(outsideAllowlistLibrary.getId(), userId);

    IndexingJob job =
        documentIndexingService.triggerIndexing(outsideAllowlistLibrary.getId(), asCaller());
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var failedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(failedJob.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(failedJob.getErrorMessage()).contains("außerhalb");
    assertThat(documentRepository.findByLibraryId(outsideAllowlistLibrary.getId())).isEmpty();

    libraryRepository.deleteById(outsideAllowlistLibrary.getId());
  }

  // --- #401: indexing_jobs organization boundary, exercised against two real organizations ---

  /**
   * #401 acceptance criteria: the status query answers only with the caller's own organization's
   * runs. Proven at two independent layers against a real, two-organization database - not just the
   * pre-existing library-ownership check ({@code
   * DocumentIndexingService#loadLibraryInOrganization}, which already 404s a foreign library) but
   * the {@code indexing_jobs} row's own {@code organization_id} (migration 049): {@link
   * IndexingJobService#getLatestJob} for organization B asking about organization A's library must
   * come back empty, exactly as if that library had never run at all - not merely blocked one layer
   * up.
   */
  @Test
  void statusQueryOnlyEverReturnsRunsBelongingToTheCallersOwnOrganization() throws IOException {
    UUID organizationA = insertOrganization("Org A 401");
    UUID organizationB = insertOrganization("Org B 401");
    UUID userInOrganizationA = insertUser(organizationA, "401-user-a@example.com");
    UUID userInOrganizationB = insertUser(organizationB, "401-user-b@example.com");
    KnowledgeLibrary libraryInOrganizationA =
        createLibraryAndGrantEditor(organizationA, userInOrganizationA, "401-org-a");

    IndexingJob job = indexingJobService.startJob(libraryInOrganizationA.getId(), organizationA);

    assertThat(
            documentIndexingService
                .getStatus(
                    libraryInOrganizationA.getId(), asCaller(userInOrganizationA, organizationA))
                .job()
                .map(IndexingJob::getId))
        .contains(job.getId());
    // The same library, asked about by a user of a genuinely different organization: 404, not
    // merely a different (empty) status - #436's "no grant at all looks like not found" applies
    // here too, since organization B never held any grant on organization A's library.
    assertThatThrownBy(
            () ->
                documentIndexingService.getStatus(
                    libraryInOrganizationA.getId(), asCaller(userInOrganizationB, organizationB)))
        .isInstanceOf(NotFoundException.class);

    // The second, independent guard this issue adds at the indexing_jobs row itself (#401): even
    // asked directly, bypassing the library-ownership check above entirely, the same libraryId
    // under the wrong organizationId comes back empty rather than leaking organization A's job.
    assertThat(indexingJobService.getLatestJob(libraryInOrganizationA.getId(), organizationB))
        .isEmpty();
    assertThat(
            indexingJobService
                .getLatestJob(libraryInOrganizationA.getId(), organizationA)
                .map(IndexingJob::getId))
        .contains(job.getId());
    assertThat(
            indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
                JobStatus.RUNNING, libraryInOrganizationA.getId(), organizationB))
        .isFalse();
    assertThat(
            indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
                JobStatus.RUNNING, libraryInOrganizationA.getId(), organizationA))
        .isTrue();
  }

  /**
   * #401 acceptance criteria: a running indexing job in one organization must not block a trigger
   * in a different organization. #478 already scoped concurrency per library rather than globally,
   * but that guarantee was previously only ever exercised with two libraries in the *same*
   * organization (see {@code
   * LibraryIndexingAuthorizationIntegrationTest#aSecondTriggerOfTheSameLibraryWhileRunningIsRejectedButAnotherLibraryRunsInParallel}).
   * This proves it holds across a genuine organization boundary too.
   */
  @Test
  void aRunningJobInOneOrganizationDoesNotBlockATriggerInAnotherOrganization() throws IOException {
    UUID organizationA = insertOrganization("Org A 401 Concurrency");
    UUID organizationB = insertOrganization("Org B 401 Concurrency");
    UUID userInOrganizationA = insertUser(organizationA, "401-conc-user-a@example.com");
    UUID userInOrganizationB = insertUser(organizationB, "401-conc-user-b@example.com");
    KnowledgeLibrary libraryInOrganizationA =
        createLibraryAndGrantEditor(organizationA, userInOrganizationA, "401-conc-org-a");
    KnowledgeLibrary libraryInOrganizationB =
        createLibraryAndGrantEditor(organizationB, userInOrganizationB, "401-conc-org-b");

    // Seeds a RUNNING row directly (mirrors IndexingJobRecoveryIntegrationTest's
    // seedOrphanedRunningJob) instead of relying on timing a real async run's RUNNING window -
    // deterministic, and it is uk_indexing_jobs_library_running (migration 028) plus
    // IndexingJobService#isJobRunning that this test actually needs held RUNNING, not a real
    // completed indexing pass.
    indexingJobService.startJob(libraryInOrganizationA.getId(), organizationA);

    IndexingJob jobInOrganizationB =
        documentIndexingService.triggerIndexing(
            libraryInOrganizationB.getId(), asCaller(userInOrganizationB, organizationB));

    assertThat(jobInOrganizationB.getStatus()).isEqualTo(JobStatus.RUNNING);
    awaitJobCompletion(jobInOrganizationB);
  }

  private UUID insertOrganization(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, now())", id, name);
    return id;
  }

  private UUID insertUser(UUID organizationId, String email) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Test-Nutzer', now(), ?, ?)",
        id,
        "401-" + id,
        email,
        SystemRole.USER.name(),
        organizationId);
    return id;
  }

  private CurrentUser asCaller(UUID userId, UUID organizationId) {
    return CurrentUser.of(userId, organizationId, SystemRole.USER, "Test-Nutzer");
  }

  private KnowledgeLibrary createLibraryAndGrantEditor(
      UUID organizationId, UUID ownerId, String subdirectoryName) throws IOException {
    Path libraryDir = classTempDir.resolve(subdirectoryName);
    Files.createDirectories(libraryDir);
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Bibliothek " + subdirectoryName,
                null,
                ownerId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                libraryDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    grantOwner(library.getId(), ownerId, organizationId);
    return library;
  }

  private void grantOwner(UUID libraryId, UUID granteeId, UUID organizationId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        organizationId,
        granteeId);
  }

  private void awaitJobCompletion(IndexingJob job) {
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () -> {
              var latestJob = indexingJobRepository.findById(job.getId()).orElseThrow();
              return latestJob.getStatus() != JobStatus.RUNNING;
            });
  }

  private void copyTestResource(String resourcePath, String targetFileName) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      assertThat(in).as("Test resource %s must exist", resourcePath).isNotNull();
      Files.copy(in, classTempDir.resolve(targetFileName), StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
