package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.UploadProperties;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The selective re-index by pipeline version (ingestion-pipelines.md cross-cutting rule (d)): every
 * chunk below version N of one pipeline must be triggerable, resumable, and its progress queryable
 * per library.
 *
 * <p>Chunks are seeded directly through {@link VectorStore#add} without the pipeline metadata -
 * that is exactly the state of every chunk written before the abstraction existed, the corpus the
 * whole mechanism is meant to be able to reach again.
 */
@OpaaIndexingIntegrationTest
class PipelineReindexServiceIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("pipeline-reindex");

  @Autowired private PipelineReindexService reindexService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UploadProperties uploadProperties;
  @Autowired private DocumentPipelineRegistry pipelineRegistry;

  private UUID userId;
  private KnowledgeLibrary library;
  private KnowledgeLibrary uploadLibrary;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    // A single-statement delete, not documentRepository.deleteAll(): per-entity deletes in
    // arbitrary order trip fk_documents_parent for a bestand with attachment child rows, while
    // one statement removes parents and children together.
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'pipeline-reindex-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'pipeline-reindex-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'pipeline-reindex-it@example.com',"
            + " 'Pipeline Reindex IT User', now(), ?, ?)",
        userId,
        "pipeline-reindex-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    // A FILESYSTEM library, because sourcePath is what a FILESYSTEM document's file must resolve
    // underneath before the re-index is allowed to read it again (ADR-0018, Entscheidung 6).
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zielbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                classTempDir.toString(),
                null,
                null,
                null,
                false));
    uploadLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Uploadbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false));
  }

  @Test
  void thePreAbstractionCorpusCountsAsStaleAndIsReportedPerLibrary() throws IOException {
    Document legacy = persistedFilesystemDocument("altbestand.txt", "Alter Inhalt");
    seedChunk(legacy.getId(), "alter chunk", null, null);
    Document current = persistedFilesystemDocument("neubestand.txt", "Neuer Inhalt");
    seedChunk(
        current.getId(), "neuer chunk", TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION);

    List<PipelineVersionProgress> progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID);

    assertThat(progress).hasSize(1);
    PipelineVersionProgress libraryProgress = progress.getFirst();
    assertThat(libraryProgress.libraryId()).isEqualTo(library.getId());
    assertThat(libraryProgress.totalChunks()).isEqualTo(2);
    assertThat(libraryProgress.currentVersionChunks()).isEqualTo(1);
    // A chunk carrying no pipeline metadata at all is not "unknown" but attributable: only the
    // Tika path ever produced chunks before, so it counts as that pipeline at version 0.
    assertThat(libraryProgress.staleChunks()).isEqualTo(1);
    assertThat(libraryProgress.isComplete()).isFalse();
  }

  @Test
  void aChunkNamingAnUnknownPipelineIsNeitherCurrentNorStale() throws IOException {
    Document document = persistedFilesystemDocument("fremd.txt", "Inhalt");
    seedChunk(document.getId(), "fremder chunk", "docling-pdf", (short) 4);

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();

    assertThat(progress.totalChunks()).isEqualTo(1);
    assertThat(progress.currentVersionChunks()).isZero();
    assertThat(progress.staleChunks()).isZero();
    assertThat(progress.isComplete()).isTrue();
  }

  @Test
  void aDocumentMisroutedToTheFallbackPipelineIsSelectableAndReportedAsStale() throws IOException {
    // Simulates the routing gap: the PDF pipeline was registered after this document was
    // indexed, so its chunks still carry tika-fallback at the fallback's own current version - a
    // state no version-only comparison against either pipeline can ever call stale.
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document = persistedFilesystemPdfDocument("satzung.pdf");
    seedChunk(
        document.getId(), "alter chunk", TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION);

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isEqualTo(1);
    assertThat(progress.currentVersionChunks()).isZero();
    assertThat(progress.isComplete()).isFalse();

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(pipelineIdsOf(document.getId())).containsOnly(pdfPipeline.id());
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isZero();
  }

  @Test
  void aChunkWithNoResolvedRoutingExtensionIsCompleteEvenWhenItsFileNameClaimsAStrictPipeline()
      throws IOException {
    // Closes gap (a): bericht.pdf with genuine plain-text content resolves no extension
    // at all (SupportedDocumentFormats#decideForFileName), so a forward-written chunk carries
    // ChunkPipelineMetadata#NO_ROUTING_EXTENSION rather than a guess from the file name. Without
    // the routing key (the earlier approximation the other misrouted tests exercise), the same
    // file name would make this chunk permanently stale - the exact gap this key closes.
    Document document =
        persistedFilesystemTextDocumentNamedLikePdf(
            "bericht.pdf", "Dies ist kein PDF, sondern reiner Text. ");
    seedChunk(
        document.getId(),
        library.getId(),
        "alter chunk",
        TikaFallbackPipeline.ID,
        TikaFallbackPipeline.VERSION,
        ChunkPipelineMetadata.NO_ROUTING_EXTENSION);

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();

    assertThat(progress.staleChunks()).isZero();
    assertThat(progress.currentVersionChunks()).isEqualTo(1);
    assertThat(progress.isComplete()).isTrue();
  }

  @Test
  void aChunkWithARoutingExtensionIsSelectableEvenWithoutAMatchingFileNameExtension()
      throws IOException {
    // Closes gap (b): a document with no extension the registry can recognize
    // ("download.aspx") has no file-name-based way back into a specialized pipeline once its
    // chunks are fallback-labeled (#currentPipelineIdForFileName never matches it). A forward-
    // written routing key sidesteps the file name entirely - the same misrouted branch now
    // selects it on an exact match against the requested pipeline's own extensions.
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document = persistedFilesystemPdfDocument("download.aspx");
    seedChunk(
        document.getId(),
        library.getId(),
        "alter chunk",
        TikaFallbackPipeline.ID,
        TikaFallbackPipeline.VERSION,
        ".pdf");

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isEqualTo(1);
    assertThat(progress.isComplete()).isFalse();

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(pipelineIdsOf(document.getId())).containsOnly(pdfPipeline.id());
  }

  private DocumentPipeline pdfPipeline() {
    return pipelineRegistry.pipelines().stream()
        .filter(candidate -> candidate.handledFormats().contains(".pdf"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No PDF pipeline registered"));
  }

  private DocumentPipeline htmlPipeline() {
    return pipelineRegistry.pipelines().stream()
        .filter(candidate -> candidate.handledFormats().contains(".html"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No HTML pipeline registered"));
  }

  @Test
  void aChunkAlreadyNamingASpecializedPipelineIsNotPulledBackByAnUnrelatedPipelineReindex()
      throws IOException {
    // Regression guard for #1125, for a chunk without a routing key: the
    // heuristic branch of the misrouted predicate only targets chunks still naming the fallback
    // pipeline (see #misroutedPredicateFor). A chunk that already names a different specialized
    // pipeline must stay excluded even though its file name matches another pipeline's claimed
    // extension - widening the file-name guess to "<> pipelineId" would pull such a chunk back
    // into a pipeline it was never routed to, and could never converge (see
    // #currentPipelineIdForFileName's own Javadoc). The exact branch below is what makes
    // this direction reachable for a chunk that does carry a routing key.
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document = persistedFilesystemPdfDocument("x.pdf");
    seedChunk(document.getId(), "html chunk", "html", (short) 1);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.isEmpty()).isTrue();
    assertThat(result.reindexedDocuments()).isZero();
    assertThat(pipelineIdsOf(document.getId())).containsOnly("html");
  }

  @Test
  void aChunkNamingAnotherSpecializedPipelineIsPulledIntoTheOneItsRoutingKeyClaimsToday()
      throws IOException {
    // The routing gap was only ever closed in the direction
    // "out of the fallback pipeline". A document whose chunks already name one specialized
    // pipeline ("html") but whose forward-written routing key now resolves to another
    // ("pdf") was unreachable before this fix, because #misroutedPredicateFor required the stored
    // pipeline_id to equal the fallback's own id. With the exact routing key, no such restriction
    // is needed: the extension-to-pipeline mapping is unique, so the corrected chunk always ends
    // up under pdfPipeline and is never selected again.
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document = persistedFilesystemPdfDocument("satzung.pdf");
    seedChunk(document.getId(), library.getId(), "html chunk", "html", (short) 1, ".pdf");

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isEqualTo(1);
    assertThat(progress.currentVersionChunks()).isZero();
    assertThat(progress.isComplete()).isFalse();

    PipelineReindexResult first =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);
    assertThat(first.reindexedDocuments()).isEqualTo(1);
    assertThat(pipelineIdsOf(document.getId())).containsOnly(pdfPipeline.id());

    // Convergence: a second call against the same target must not reselect it.
    PipelineReindexResult second =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);
    assertThat(second.isEmpty()).isTrue();
    assertThat(second.reindexedDocuments()).isZero();
  }

  @Test
  void aChunkNamingAPipelineNoLongerRegisteredIsStaleRatherThanInvisibleWhenItHasARoutingKey()
      throws IOException {
    // The renaming case: the chunk's routing key (".pdf") is still
    // claimed today, just by a pipeline registered under a different id than the one stored on the
    // chunk. progressForOrganization must not skip a chunk whose pipeline_id has no entry in
    // currentVersions (a pipeline this deployment no longer registers under that id) entirely -
    // counted in the total only, so a library holding only such chunks falsely reported
    // isComplete() as true. With the routing key, the chunk's target pipeline is resolved
    // from the key itself and never needs to look the stored pipeline_id up in currentVersions -
    // and selectStaleDocuments reaches it the same way, symmetric to the counting fix.
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document = persistedFilesystemPdfDocument("satzung.pdf");
    seedChunk(
        document.getId(),
        library.getId(),
        "verwaister chunk",
        "obsolete-pipeline",
        (short) 1,
        ".pdf");

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();

    assertThat(progress.totalChunks()).isEqualTo(1);
    assertThat(progress.currentVersionChunks()).isZero();
    assertThat(progress.staleChunks()).isEqualTo(1);
    assertThat(progress.isComplete()).isFalse();

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(pipelineIdsOf(document.getId())).containsOnly(pdfPipeline.id());
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isZero();
  }

  @Test
  void aChunkNamingADeinstalledPipelineWhoseFormatNooneClaimsIsPulledIntoTheFallbackPipeline()
      throws IOException {
    // The deinstallation case (not the renaming case above) - the
    // chunk's routing key names an extension no registered pipeline claims at all today, so its
    // target is the fallback pipeline itself. Before this fix, misroutedPredicateFor returned FALSE
    // unconditionally for a fallback target, so this chunk was counted stale by
    // progressForOrganization but unreachable by reindexBatch: stale forever, isComplete() stuck at
    // false with no way to drain it.
    Document document =
        persistedFilesystemDocument("altbestand.txt", "Alter Inhalt ohne beanspruchtes Format");
    seedChunk(
        document.getId(),
        library.getId(),
        "verwaister chunk",
        "deinstalled-pipeline",
        (short) 1,
        ".xyz-not-claimed-by-any-pipeline");

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isEqualTo(1);
    assertThat(progress.isComplete()).isFalse();

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION, 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(pipelineIdsOf(document.getId())).containsOnly(TikaFallbackPipeline.ID);
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isZero();
  }

  @Test
  void anRssEntryBelowTheHtmlPipelineVersionIsMarkedForItsNextRun() {
    // An RSS entry's body goes through the HTML pipeline, so a raised HTML pipeline version reaches
    // it like any other remote document: counted stale, handed to the next feed run by clearing
    // its change markers, and out of the backlog afterwards.
    DocumentPipeline htmlPipeline = htmlPipeline();
    Document document =
        persistedRssFeedDocument("Rat beschliesst Satzung", "https://example.test/feed/rat");
    seedChunk(
        document.getId(), "alter chunk", htmlPipeline.id(), (short) (htmlPipeline.version() - 1));

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isEqualTo(1);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, htmlPipeline.id(), htmlPipeline.version(), 10);

    assertThat(result.markedForNextRun()).isEqualTo(1);
    assertThat(result.reindexedDocuments()).isZero();
    Document marked = documentRepository.findById(document.getId()).orElseThrow();
    assertThat(marked.getChecksum()).isNull();
    assertThat(marked.getLastModifiedRemote()).isNull();
    assertThat(
            reindexService
                .reindexBatch(
                    Organization.DEFAULT_ID, htmlPipeline.id(), htmlPipeline.version(), 10)
                .isEmpty())
        .isTrue();
  }

  @Test
  void anRssEntryStillChunkedByTheFallbackPipelineIsPulledIntoTheHtmlPipelineByItsName() {
    // No special rule for RSS in the routing comparison any more: an entry left over from the
    // fallback era is treated like every other fallback-labeled remote document without a routing
    // key - selected by the file-name approximation and marked for its next run, whose fetch then
    // hands the body to the HTML pipeline by id.
    DocumentPipeline htmlPipeline = htmlPipeline();
    Document document =
        persistedRssFeedDocument("artikel.html", "https://example.test/feed/artikel.html");
    seedChunk(
        document.getId(), "alter chunk", TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, htmlPipeline.id(), htmlPipeline.version(), 10);

    assertThat(result.markedForNextRun()).isEqualTo(1);
    assertThat(result.reindexedDocuments()).isZero();
    assertThat(documentRepository.findById(document.getId()).orElseThrow().getChecksum()).isNull();
  }

  private Document persistedRssFeedDocument(String title, String url) {
    Document document = new Document(title, url, "text/html", 1024L, DocumentSourceType.RSS_FEED);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-rss");
    document.setLastModifiedRemote("2026-03-12T10:00:00Z");
    return documentRepository.save(document);
  }

  private Document persistedFilesystemPdfDocument(String fileName) throws IOException {
    Path file = classTempDir.resolve(UUID.randomUUID() + "-" + fileName);
    try (PDDocument pdf = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      pdf.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(50, 700);
        stream.showText("Die Verwaltungsgebühr für einen Personalausweis beträgt 37,00 EUR.");
        stream.endText();
      }
      pdf.save(file.toFile());
    }
    Document document =
        new Document(
            fileName, file.toAbsolutePath().toString(), "application/pdf", Files.size(file));
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-" + fileName);
    return documentRepository.save(document);
  }

  private List<String> pipelineIdsOf(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT metadata->>'pipeline_id' FROM vector_store WHERE metadata->>'document_id' = ?",
        String.class,
        documentId.toString());
  }

  @Test
  void aGenuineFallbackDocumentIsNotSelectedByAnUnrelatedSpecializedPipelineReindex()
      throws IOException {
    // Regression guard for #1105: the misrouted branch must stay
    // scoped to the one gap it exists for. A .txt document with no specialized pipeline of its own
    // is correctly fallback-labeled forever and must never be pulled into an unrelated pipeline's
    // batch just because that pipeline happens to be registered.
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document = persistedFilesystemDocument("altbestand.txt", "Alter Inhalt");
    seedChunk(
        document.getId(), "alter chunk", TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.isEmpty()).isTrue();
    assertThat(result.reindexedDocuments()).isZero();
    assertThat(pipelineIdsOf(document.getId())).containsOnly(TikaFallbackPipeline.ID);
  }

  @Test
  void aDocumentThatStaysMisroutedAfterReindexTerminatesInsteadOfLoopingForever()
      throws IOException {
    // Regression guard for #1105: reindexStoredDocument routes on
    // re-detected content (DocumentPipelineRegistry#routedPipelineFor), not on the file name
    // selectStaleDocuments guessed the candidate from. A document named like the target pipeline's
    // format but whose real content never resolves to it stays fallback-labeled after every
    // rewrite - without the loop protection this would be re-selected, re-embedded and re-written
    // on every single call, never converging (see IndexingAdminController's own guard against the
    // equivalent belowVersion case, which this same failure mode bypassed).
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document =
        persistedFilesystemTextDocumentNamedLikePdf(
            "bericht.pdf", "Dies ist kein PDF, sondern reiner Text. ");
    seedChunk(
        document.getId(), "alter chunk", TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION);

    PipelineReindexResult first =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);
    assertThat(first.isEmpty()).isTrue();
    assertThat(first.skippedDocuments()).isEqualTo(1);
    assertThat(first.reindexedDocuments()).isZero();
    assertThat(pipelineIdsOf(document.getId())).containsOnly(TikaFallbackPipeline.ID);

    PipelineReindexResult second =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);
    assertThat(second.isEmpty()).isTrue();
  }

  @Test
  void aDocumentThatStaysMisroutedIsNotReparsedOnASubsequentReindexCall() throws IOException {
    // Closes gap (c): FileProcessingService#storeChunks now writes
    // ChunkPipelineMetadata#NO_ROUTING_EXTENSION onto the chunks reindexStoredDocument just wrote
    // for a document that still resolves to the fallback pipeline. #misroutedPredicateFor's exact
    // branch then excludes those chunks outright (routing_extension "" never matches a pipeline's
    // own extension list) instead of relying on the file-name heuristic that kept re-selecting
    // them - so a second call must not touch this document's chunks at all, unlike an Altbestand
    // chunk
    // (see PipelineReindexResult#skippedDocuments's own Javadoc on this exact, previously accepted
    // cost).
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document =
        persistedFilesystemTextDocumentNamedLikePdf(
            "bericht.pdf", "Dies ist kein PDF, sondern reiner Text. ");
    seedChunk(
        document.getId(), "alter chunk", TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION);

    PipelineReindexResult first =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);
    assertThat(first.skippedDocuments()).isEqualTo(1);
    List<String> chunkIdsAfterFirstCall = chunkIdsOf(document.getId());
    assertThat(chunkIdsAfterFirstCall).isNotEmpty();

    PipelineReindexResult second =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(second.isEmpty()).isTrue();
    assertThat(second.skippedDocuments()).isZero();
    // Unchanged chunk ids prove the document was not re-read, re-chunked and re-embedded a second
    // time - reindexStoredDocument always deletes and rewrites under fresh ids (see its own
    // Javadoc), so identical ids are only possible if this call never touched it at all.
    assertThat(chunkIdsOf(document.getId())).isEqualTo(chunkIdsAfterFirstCall);
  }

  /**
   * A document selected purely for its stale lexical index is reported as re-indexed even when its
   * rewritten chunks still name the fallback pipeline - it was genuinely repaired, and the loop
   * protection that counts such a document as skipped exists for the routing gap, which is not why
   * this one was selected.
   */
  @Test
  void aDocumentRepairedOnlyForItsStaleFullTextRowsCountsAsReindexedNotSkipped()
      throws IOException {
    DocumentPipeline pdfPipeline = pdfPipeline();
    Document document =
        persistedFilesystemTextDocumentNamedLikePdf(
            "bericht.pdf", "Dies ist kein PDF, sondern reiner Text. ");
    // NO_ROUTING_EXTENSION excludes this chunk from the routing-gap branch, so only the stale
    // full-text row can select it - while a re-index still writes fallback-labeled chunks.
    UUID chunkId =
        seedChunk(
            document.getId(),
            library.getId(),
            "alter chunk",
            TikaFallbackPipeline.ID,
            TikaFallbackPipeline.VERSION,
            ChunkPipelineMetadata.NO_ROUTING_EXTENSION);
    jdbcTemplate.update(
        "UPDATE chunk_full_text SET content_tsv_version = ? WHERE chunk_id = ?",
        (short) (FullTextChunkStore.CURRENT_TSV_VERSION - 1),
        chunkId);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(result.skippedDocuments()).isZero();
    assertThat(pipelineIdsOf(document.getId())).containsOnly(TikaFallbackPipeline.ID);
    assertThat(currentVersionFullTextRowsOf(document.getId()))
        .isEqualTo(chunkTextsOf(document.getId()).size())
        .isPositive();
    // And it terminates: with the gap closed, nothing selects the document again.
    assertThat(
            reindexService
                .reindexBatch(Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10)
                .isEmpty())
        .isTrue();
  }

  private List<String> chunkIdsOf(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT id FROM vector_store WHERE metadata->>'document_id' = ? ORDER BY id",
        String.class,
        documentId.toString());
  }

  private Document persistedFilesystemTextDocumentNamedLikePdf(String fileName, String content)
      throws IOException {
    Path file = classTempDir.resolve(UUID.randomUUID() + "-" + fileName);
    Files.writeString(file, content.repeat(30));
    Document document =
        new Document(
            fileName, file.toAbsolutePath().toString(), "application/pdf", Files.size(file));
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-" + fileName);
    return documentRepository.save(document);
  }

  /**
   * raising FullTextChunkStore#CURRENT_TSV_VERSION raises no DocumentPipeline#version(), so a
   * selection tied to the pipeline version alone would report "nothing to do" for exactly the
   * situation the documented recovery path names. The document is selected on its stale full-text
   * row, and afterwards every chunk of it carries a row at the current version.
   */
  @Test
  void aDocumentWhoseFullTextRowsAreBelowTheCurrentTsvVersionIsReindexed() throws IOException {
    Document document =
        persistedFilesystemDocument(
            "gebuehrensatzung.txt", "Die Verwaltungsgebühr beträgt 37,00 EUR je Vorgang.");
    // Current pipeline version, so only the stale tsv version can select this document.
    UUID chunkId =
        seedChunk(
            document.getId(),
            "aktueller chunk",
            TikaFallbackPipeline.ID,
            TikaFallbackPipeline.VERSION);
    jdbcTemplate.update(
        "UPDATE chunk_full_text SET content_tsv_version = ? WHERE chunk_id = ?",
        (short) (FullTextChunkStore.CURRENT_TSV_VERSION - 1),
        chunkId);
    assertThat(currentVersionFullTextRowsOf(document.getId())).isZero();
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isEqualTo(1);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION, 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(currentVersionFullTextRowsOf(document.getId()))
        .isEqualTo(chunkTextsOf(document.getId()).size())
        .isPositive();
    // Drained: the rewritten rows carry the current version, so nothing selects them again.
    assertThat(
            reindexService
                .reindexBatch(
                    Organization.DEFAULT_ID,
                    TikaFallbackPipeline.ID,
                    TikaFallbackPipeline.VERSION,
                    10)
                .isEmpty())
        .isTrue();
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isZero();
  }

  @Test
  void aLocallyReadableDocumentIsReindexedInPlaceAndKeepsItsDocumentId() throws IOException {
    Document document =
        persistedFilesystemDocument(
            "satzung.txt", "Die Verwaltungsgebühr für einen Personalausweis beträgt 37,00 EUR.");
    seedChunk(document.getId(), "veralteter chunk", null, null);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION, 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(result.isEmpty()).isFalse();
    // Same document row, new chunks: citations and deep links into the document survive.
    assertThat(documentRepository.findById(document.getId())).isPresent();
    assertThat(chunkTextsOf(document.getId()))
        .noneMatch(text -> text.equals("veralteter chunk"))
        .isNotEmpty();
    assertThat(pipelineVersionsOf(document.getId()))
        .containsOnly((int) TikaFallbackPipeline.VERSION);
    // The re-index is the first path that deletes and rewrites chunks of an existing corpus, so
    // the lexical index has to come along: exactly one chunk_full_text row per vector_store chunk
    // of this document, none left over from the chunks that were replaced.
    assertThat(fullTextRowCountOf(document.getId()))
        .isEqualTo(chunkTextsOf(document.getId()).size());

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isZero();
    assertThat(progress.isComplete()).isTrue();

    // Drained: the next call finds nothing left, the signal to stop repeating.
    assertThat(
            reindexService
                .reindexBatch(
                    Organization.DEFAULT_ID,
                    TikaFallbackPipeline.ID,
                    TikaFallbackPipeline.VERSION,
                    10)
                .isEmpty())
        .isTrue();
  }

  @Test
  void repeatedSmallBatchesDrainTheBacklogWithoutRedoingFinishedWork() throws IOException {
    for (int i = 0; i < 3; i++) {
      Document document = persistedFilesystemDocument("dokument-" + i + ".txt", "Inhalt " + i);
      seedChunk(document.getId(), "alter chunk " + i, null, null);
    }

    // Simulates an interrupted, then resumed run: nothing but the chunk metadata itself remembers
    // where the previous call left off.
    assertThat(reindexBatch(2).reindexedDocuments()).isEqualTo(2);
    assertThat(reindexBatch(2).reindexedDocuments()).isEqualTo(1);
    assertThat(reindexBatch(2).isEmpty()).isTrue();

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isZero();
    assertThat(progress.currentVersionChunks()).isEqualTo(progress.totalChunks());
  }

  @Test
  void aConfluencePageIsMarkedForItsNextRunAndLosesItsVersionMarker() {
    // Querschnittsregel (d): a Confluence page has no local file to re-read, like an RSS
    // entry or a crawled file - so a pipeline version bump hands it to the next run, and clearing
    // the version marker is what makes the executor's pre-fetch check see "changed".
    Document document =
        persistedConfluenceDocument("https://wiki.example.test/pages/viewpage.action?pageId=102");
    seedChunk(document.getId(), "alter chunk", null, null);

    PipelineReindexResult first = reindexBatch(10);

    assertThat(first.markedForNextRun()).isEqualTo(1);
    assertThat(first.reindexedDocuments()).isZero();
    Document marked = documentRepository.findById(document.getId()).orElseThrow();
    assertThat(marked.getChecksum()).isNull();
    assertThat(marked.getLastModifiedRemote()).isNull();
    assertThat(reindexBatch(10).isEmpty()).isTrue();
  }

  @Test
  void aRemoteDocumentIsMarkedForItsNextRunOnceAndThenLeavesTheBacklog() {
    Document document = persistedRemoteDocument("https://example.test/satzung.pdf");
    seedChunk(document.getId(), "alter chunk", null, null);

    PipelineReindexResult first = reindexBatch(10);

    assertThat(first.markedForNextRun()).isEqualTo(1);
    assertThat(first.reindexedDocuments()).isZero();
    // Clearing the checksum is what stops processUrlFile from treating it as unchanged.
    assertThat(documentRepository.findById(document.getId()).orElseThrow().getChecksum()).isNull();

    // Its chunks are still stale (only the connector run can re-read the source), which the
    // progress figures keep showing - but the batch itself drains instead of reselecting it
    // forever.
    assertThat(reindexBatch(10).isEmpty()).isTrue();
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isEqualTo(1);
  }

  @Test
  void aFileOutsideTheLibrarysConfiguredDirectoryIsNeverReadAgain() throws IOException {
    // ADR-0018, Entscheidung 6: file_path was validated when the document was indexed, but the
    // allowlist and the library's own sourcePath can be narrowed afterwards. A re-index must not be
    // the one path that silently keeps reading a file the operator has since withdrawn.
    Path outside = Files.createTempDirectory("outside-allowlist").resolve("geheim.txt");
    Files.writeString(outside, "Inhalt außerhalb des konfigurierten Verzeichnisses. ".repeat(20));
    Document document =
        persistedDocumentPointingAt(
            "geheim.txt", outside, DocumentSourceType.FILESYSTEM, library.getId());
    seedChunk(document.getId(), "alter chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.skippedDocuments()).isEqualTo(1);
    assertThat(result.reindexedDocuments()).isZero();
    // The old chunk is still there untouched - the file was never opened, so there was nothing to
    // replace it with, and destroying it would have been the worse outcome.
    assertThat(chunkTextsOf(document.getId())).containsExactly("alter chunk");
    // A call that only skipped is the signal to stop; the outstanding chunk stays visible.
    assertThat(result.isEmpty()).isTrue();
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isEqualTo(1);
  }

  @Test
  void aLibraryWhoseSourcePathNoLongerPassesTheAllowlistIsNotReadAgain() throws IOException {
    // Isolates the allowlist check from the containment check: the file does lie underneath its
    // library's own configured sourcePath, so containment alone would let it through - only the
    // allowlist rejects it. This is the "operator narrowed (or emptied) the allowlist after the
    // library was created" case FilesystemPathAllowlist exists for.
    Path withdrawnDirectory = Files.createTempDirectory("withdrawn-source-path");
    Path file = withdrawnDirectory.resolve("satzung.txt");
    Files.writeString(file, "Inhalt in einem nicht mehr erlaubten Quellverzeichnis. ".repeat(20));
    KnowledgeLibrary withdrawnLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zurückgezogene Bibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                withdrawnDirectory.toString(),
                null,
                null,
                null,
                false));
    Document document =
        persistedDocumentPointingAt(
            "satzung.txt", file, DocumentSourceType.FILESYSTEM, withdrawnLibrary.getId());
    seedChunk(document.getId(), withdrawnLibrary.getId(), "alter chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.skippedDocuments()).isEqualTo(1);
    assertThat(result.reindexedDocuments()).isZero();
    assertThat(chunkTextsOf(document.getId())).containsExactly("alter chunk");
  }

  @Test
  void anUploadedDocumentInsideTheManagedStorageIsReindexedInPlace() throws IOException {
    // The positive counterpart to the two containment tests: without it, a wrongly resolved
    // storagePath would let every upload pass silently as "skipped" and nobody would notice that
    // uploads are never re-indexed at all.
    Path managedDirectory =
        Path.of(uploadProperties.storagePath()).resolve(uploadLibrary.getId().toString());
    Files.createDirectories(managedDirectory);
    Path file = managedDirectory.resolve(UUID.randomUUID() + "-vermerk.txt");
    Files.writeString(file, "Ein hochgeladener Vermerk über Verwaltungsgebühren. ".repeat(20));
    Document document =
        persistedDocumentPointingAt(
            "vermerk.txt", file, DocumentSourceType.UPLOAD, uploadLibrary.getId());
    seedChunk(document.getId(), uploadLibrary.getId(), "veralteter chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(result.skippedDocuments()).isZero();
    assertThat(chunkTextsOf(document.getId()))
        .noneMatch(text -> text.equals("veralteter chunk"))
        .isNotEmpty();
    assertThat(pipelineVersionsOf(document.getId()))
        .containsOnly((int) TikaFallbackPipeline.VERSION);
  }

  @Test
  void anUploadedFileOutsideTheManagedStorageIsNeverReadAgain() throws IOException {
    Path outside = Files.createTempDirectory("outside-upload-storage").resolve("fremd.txt");
    Files.writeString(outside, "Nicht von diesem Dienst geschrieben. ".repeat(20));
    Document document =
        persistedDocumentPointingAt(
            "fremd.txt", outside, DocumentSourceType.UPLOAD, uploadLibrary.getId());
    seedChunk(document.getId(), uploadLibrary.getId(), "alter chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.skippedDocuments()).isEqualTo(1);
    assertThat(chunkTextsOf(document.getId())).containsExactly("alter chunk");
  }

  @Test
  void aMarkedRemoteDocumentIsActuallyReprocessedByItsOwnConnectorRun() {
    // The gate that decides it, before anything is downloaded: IndexingRun#isUnchanged reads
    // last_modified_remote plus INDEXED - never the checksum, because the bytes it would be
    // computed from have deliberately not been fetched yet. Clearing the checksum alone would
    // therefore have been a no-op the run never notices.
    String remoteUrl = "https://example.test/satzung.pdf";
    String lastModified = "Tue, 01 Sep 2026 06:00:00 GMT";
    Document document = persistedRemoteDocument(remoteUrl);
    document.setLastModifiedRemote(lastModified);
    document.setStatus(DocumentStatus.INDEXED);
    documentRepository.save(document);
    seedChunk(document.getId(), "alter chunk", null, null);

    IndexingRun run = runForGateCheck();
    assertThat(run.isUnchanged(remoteUrl, lastModified))
        .as("before the re-index the run would skip this document as unchanged")
        .isTrue();

    assertThat(reindexBatch(10).markedForNextRun()).isEqualTo(1);

    assertThat(run.isUnchanged(remoteUrl, lastModified))
        .as("after being marked the very same run re-reads it instead of skipping it")
        .isFalse();
    Document marked = documentRepository.findById(document.getId()).orElseThrow();
    assertThat(marked.getLastModifiedRemote()).isNull();
    // The second gate, inside processUrlFile once the file has actually been downloaded.
    assertThat(marked.getChecksum()).isNull();
  }

  /**
   * The real {@link IndexingRun} change gate, with only the collaborators the decision does not
   * touch mocked away - the decision itself runs against this test's own database rows, not a
   * reimplementation of the rule.
   */
  private IndexingRun runForGateCheck() {
    UUID jobId = UUID.randomUUID();
    IndexingJobService jobService = org.mockito.Mockito.mock(IndexingJobService.class);
    return new IndexingRun(
        jobId,
        library,
        IndexingRunMode.FULL,
        DocumentSourceType.HTTP_DIRECTORY,
        new IndexingRunProgress(jobService, jobId),
        new IndexingRunEventRecorder(
            org.mockito.Mockito.mock(IndexingRunEventRepository.class), jobService, jobId),
        documentRepository,
        org.mockito.Mockito.mock(io.opaa.library.LibraryStorageQuotaService.class));
  }

  @Test
  void aMalformedDocumentIdInChunkMetadataDoesNotFailProgressOrReindexBatch() throws IOException {
    // Regression guard for #1125: document_id is a text-typed jsonb field, so nothing
    // stops a chunk from carrying a value that is not a well-formed UUID. Both
    // progressForOrganization (pre-existing text-comparison join) and selectStaleDocuments (this
    // round's fix, replacing an unguarded ::uuid cast) must tolerate such a chunk rather than fail
    // the query - and the surrounding organization's other documents must still be processed.
    Document document = persistedFilesystemDocument("gueltig.txt", "Gueltiger Inhalt");
    seedChunk(document.getId(), "gueltiger chunk", null, null);
    seedChunk(document.getId(), "chunk mit defekten metadaten", null, null);
    jdbcTemplate.update(
        "UPDATE vector_store SET metadata ="
            + " jsonb_set(metadata::jsonb, '{document_id}', '\"nicht-uuid\"')::json"
            + " WHERE content = ?",
        "chunk mit defekten metadaten");

    assertThat(reindexService.progressForOrganization(Organization.DEFAULT_ID)).isNotEmpty();

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(chunkTextsOf(document.getId())).noneMatch(text -> text.equals("gueltiger chunk"));
  }

  @Test
  void chunksWhoseDocumentRowIsGoneAreRemovedRatherThanReselectedForever() {
    UUID vanishedDocumentId = UUID.randomUUID();
    seedChunk(vanishedDocumentId, "verwaister chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.removedOrphanChunkSets()).isEqualTo(1);
    assertThat(chunkTextsOf(vanishedDocumentId)).isEmpty();
    assertThat(reindexBatch(10).isEmpty()).isTrue();
  }

  @Test
  void aMailVersionReindexCreatesTheAttachmentAsItsOwnDocumentInsteadOfLosingIt() throws Exception {
    // ADR-0022, Entscheidung 3's rule for every path that replaces a parent document, applied to
    // the operator-driven email-v4 bestandsmigration: re-running the mail pipeline over an
    // old, pre-ADR-0022 mail (whose attachment was an inline chunk, now no longer produced) must
    // hand the re-discovered attachment to the generalized attachment path - otherwise the
    // re-index deletes the old inline attachment chunks and creates nothing in their place, and
    // the subsequent checksum skip of the unchanged mail file cements the loss forever.
    DocumentPipeline mailPipeline = mailPipeline();
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
    Path emlFile = classTempDir.resolve(UUID.randomUUID() + "-anfrage.eml");
    Files.write(emlFile, DefaultMessageWriter.asBytes(message));
    Document mailDocument =
        persistedDocumentPointingAt(
            "anfrage.eml", emlFile, DocumentSourceType.FILESYSTEM, library.getId());
    seedChunk(
        mailDocument.getId(),
        "alter Mail-Chunk mit eingebettetem Anhang",
        mailPipeline.id(),
        (short) (mailPipeline.version() - 1));

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, mailPipeline.id(), mailPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    List<Document> attachments = documentRepository.findByParentDocumentId(mailDocument.getId());
    assertThat(attachments).hasSize(1);
    Document attachment = attachments.getFirst();
    assertThat(attachment.getFileName()).isEqualTo("anlage.txt");
    assertThat(attachment.getFilePath()).isEqualTo(emlFile.toAbsolutePath() + "/0/anlage.txt");
    assertThat(attachment.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(pipelineIdsOf(attachment.getId())).containsOnly(TikaFallbackPipeline.ID);
  }

  @Test
  void aPdfPipelineVersionBumpReachesAPdfAttachmentInsideAMail() throws Exception {
    // The core case: an attachment document's
    // file_path is synthetic and resolves to no file of its own - the re-index re-extracts its
    // bytes from the root mail file via the positional index in the path, so a raised PDF pipeline
    // version reaches a PDF inside a mail without the mail file itself having changed.
    DocumentPipeline pdfPipeline = pdfPipeline();
    byte[] pdfBytes =
        pdfBytes("Die Verwaltungsgebühr für einen Personalausweis beträgt 37,00 EUR.");
    Message message =
        Message.Builder.of()
            .setSubject("Gebuehrenbescheid")
            .setFrom("Buergeramt <buergeramt@example.org>")
            .setTo("Sachbearbeitung <sachbearbeitung@example.org>")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Der Bescheid haengt an.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(pdfBytes, "application/pdf")
                            .setContentDisposition("attachment", "anlage.pdf"))
                    .build())
            .build();
    Path emlFile = classTempDir.resolve(UUID.randomUUID() + "-bescheid.eml");
    Files.write(emlFile, DefaultMessageWriter.asBytes(message));
    Document mailDocument =
        persistedDocumentPointingAt(
            "bescheid.eml", emlFile, DocumentSourceType.FILESYSTEM, library.getId());
    Document attachmentDocument =
        new Document(
            "anlage.pdf",
            emlFile.toAbsolutePath() + "/0/anlage.pdf",
            "application/pdf",
            (long) pdfBytes.length);
    attachmentDocument.setLibraryId(library.getId());
    attachmentDocument.setOrganizationId(Organization.DEFAULT_ID);
    // The genuine checksum of the attachment bytes, as a real indexing run would have stored it -
    // the re-index verifies the re-extracted bytes against it before writing anything.
    attachmentDocument.setChecksum(new ChecksumService().computeSha256(pdfBytes));
    attachmentDocument.setParentDocumentId(mailDocument.getId());
    attachmentDocument = documentRepository.save(attachmentDocument);
    seedChunk(
        attachmentDocument.getId(),
        "alter Anhang-Chunk",
        pdfPipeline.id(),
        (short) (pdfPipeline.version() - 1));

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    // Same row, fresh chunks at the current PDF pipeline version.
    assertThat(documentRepository.findById(attachmentDocument.getId())).isPresent();
    assertThat(pipelineIdsOf(attachmentDocument.getId())).containsOnly(pdfPipeline.id());
    assertThat(chunkTextsOf(attachmentDocument.getId())).doesNotContain("alter Anhang-Chunk");
    List<Integer> versions =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT (metadata->>'pipeline_version')::int FROM vector_store WHERE"
                + " metadata->>'document_id' = ?",
            Integer.class,
            attachmentDocument.getId().toString());
    assertThat(versions).containsOnly((int) pdfPipeline.version());
  }

  @Test
  void aPdfPipelineVersionBumpReachesAPdfAttachmentInsideAnUploadedMail() throws Exception {
    // the UPLOAD counterpart of aPdfPipelineVersionBumpReachesAPdfAttachmentInsideAMail -
    // an attachment of an uploaded mail is re-extracted from the managed-storage mail file, so
    // attachmentAccessFor/reindexAttachmentDocument must accept UPLOAD roots instead of skipping.
    DocumentPipeline pdfPipeline = pdfPipeline();
    byte[] pdfBytes = pdfBytes("Die Hundesteuer betraegt 96,00 EUR im Jahr.");
    Message message =
        Message.Builder.of()
            .setSubject("Steuerbescheid")
            .setFrom("Steueramt <steueramt@example.org>")
            .setTo("Sachbearbeitung <sachbearbeitung@example.org>")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Der Bescheid haengt an.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(pdfBytes, "application/pdf")
                            .setContentDisposition("attachment", "anlage.pdf"))
                    .build())
            .build();
    Path managedDirectory =
        Path.of(uploadProperties.storagePath()).resolve(uploadLibrary.getId().toString());
    Files.createDirectories(managedDirectory);
    Path emlFile = managedDirectory.resolve(UUID.randomUUID() + ".eml");
    Files.write(emlFile, DefaultMessageWriter.asBytes(message));
    Document mailDocument =
        persistedDocumentPointingAt(
            "bescheid.eml", emlFile, DocumentSourceType.UPLOAD, uploadLibrary.getId());
    Document attachmentDocument =
        new Document(
            "anlage.pdf",
            emlFile.toAbsolutePath() + "/0/anlage.pdf",
            "application/pdf",
            (long) pdfBytes.length,
            DocumentSourceType.UPLOAD);
    attachmentDocument.setLibraryId(uploadLibrary.getId());
    attachmentDocument.setOrganizationId(Organization.DEFAULT_ID);
    attachmentDocument.setChecksum(new ChecksumService().computeSha256(pdfBytes));
    attachmentDocument.setParentDocumentId(mailDocument.getId());
    attachmentDocument = documentRepository.save(attachmentDocument);
    seedChunk(
        attachmentDocument.getId(),
        uploadLibrary.getId(),
        "alter Anhang-Chunk",
        pdfPipeline.id(),
        (short) (pdfPipeline.version() - 1));

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(documentRepository.findById(attachmentDocument.getId())).isPresent();
    assertThat(pipelineIdsOf(attachmentDocument.getId())).containsOnly(pdfPipeline.id());
    assertThat(chunkTextsOf(attachmentDocument.getId())).doesNotContain("alter Anhang-Chunk");
  }

  @Test
  void aRemoteAttachmentDocumentMarksItsWholeParentChainForTheNextRun() {
    // a remote (HTTP_DIRECTORY) attachment can only be re-extracted by its parent's own
    // connector run. Marking only the attachment row would never converge - the unchanged parent
    // would be skipped by the run's change check and the attachment never re-parsed - so the
    // whole chain, root included, has its change markers cleared.
    Document mail = persistedRemoteDocument("https://example.test/mail.eml");
    mail.setLastModifiedRemote("Mon, 01 Sep 2026 10:00:00 GMT");
    mail = documentRepository.save(mail);
    Document attachment =
        new Document(
            "anlage.pdf",
            "https://example.test/mail.eml/0/anlage.pdf",
            "application/pdf",
            1024L,
            DocumentSourceType.HTTP_DIRECTORY);
    attachment.setLibraryId(library.getId());
    attachment.setOrganizationId(Organization.DEFAULT_ID);
    attachment.setChecksum("checksum-attachment");
    attachment.setParentDocumentId(mail.getId());
    attachment = documentRepository.save(attachment);
    seedChunk(attachment.getId(), "alter chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.markedForNextRun()).isEqualTo(1);
    Document reloadedMail = documentRepository.findById(mail.getId()).orElseThrow();
    Document reloadedAttachment = documentRepository.findById(attachment.getId()).orElseThrow();
    assertThat(reloadedAttachment.getChecksum()).isNull();
    // The root parent is cleared too - without this, the next run skips the unchanged mail and
    // the attachment stays below version forever.
    assertThat(reloadedMail.getChecksum()).isNull();
    assertThat(reloadedMail.getLastModifiedRemote()).isNull();
    // Drains: the marked attachment leaves the backlog selection on the next call.
    assertThat(reindexBatch(10).isEmpty()).isTrue();
  }

  @Test
  void anIndexShiftedAttachmentIsSkippedInsteadOfReindexedWithForeignBytes() throws Exception {
    // Positional indices are only stable while the parent file is
    // unchanged. If the mail was edited since indexing (an attachment removed, order shifted),
    // today's attachment at the row's index carries DIFFERENT bytes - re-indexing them under this
    // row would put foreign content under a foreign name into search and citations. The checksum
    // verification must skip the row instead; the next scheduled run of the library heals it.
    DocumentPipeline pdfPipeline = pdfPipeline();
    byte[] shiftedPdfBytes = pdfBytes("Ein ganz anderer Bescheid ueber 99,00 EUR.");
    Message message =
        Message.Builder.of()
            .setSubject("Geaenderte Mail")
            .setFrom("Buergeramt <buergeramt@example.org>")
            .setTo("Sachbearbeitung <sachbearbeitung@example.org>")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart(
                        "Der urspruengliche Anhang wurde entfernt.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(shiftedPdfBytes, "application/pdf")
                            .setContentDisposition("attachment", "c.pdf"))
                    .build())
            .build();
    Path emlFile = classTempDir.resolve(UUID.randomUUID() + "-geaendert.eml");
    Files.write(emlFile, DefaultMessageWriter.asBytes(message));
    Document mailDocument =
        persistedDocumentPointingAt(
            "geaendert.eml", emlFile, DocumentSourceType.FILESYSTEM, library.getId());
    // The row was created for the mail's FORMER attachment at index 0 (b.pdf) - its checksum
    // belongs to bytes that today's index 0 no longer carries.
    Document attachmentDocument =
        new Document("b.pdf", emlFile.toAbsolutePath() + "/0/b.pdf", "application/pdf", 1024L);
    attachmentDocument.setLibraryId(library.getId());
    attachmentDocument.setOrganizationId(Organization.DEFAULT_ID);
    attachmentDocument.setChecksum(
        new ChecksumService()
            .computeSha256("frueherer Anhangsinhalt".getBytes(StandardCharsets.UTF_8)));
    attachmentDocument.setParentDocumentId(mailDocument.getId());
    attachmentDocument = documentRepository.save(attachmentDocument);
    seedChunk(
        attachmentDocument.getId(),
        "alter Anhang-Chunk",
        pdfPipeline.id(),
        (short) (pdfPipeline.version() - 1));

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, pdfPipeline.id(), pdfPipeline.version(), 10);

    assertThat(result.reindexedDocuments()).isZero();
    assertThat(result.skippedDocuments()).isEqualTo(1);
    // Nothing was destroyed or replaced - the old chunk survives untouched.
    assertThat(chunkTextsOf(attachmentDocument.getId())).containsExactly("alter Anhang-Chunk");
  }

  private DocumentPipeline mailPipeline() {
    return pipelineRegistry.pipelines().stream()
        .filter(candidate -> candidate.handledFormats().contains(".eml"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No mail pipeline registered"));
  }

  private byte[] pdfBytes(String text) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PDDocument pdf = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      pdf.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(50, 700);
        stream.showText(text);
        stream.endText();
      }
      pdf.save(out);
    }
    return out.toByteArray();
  }

  private PipelineReindexResult reindexBatch(int batchSize) {
    return reindexService.reindexBatch(
        Organization.DEFAULT_ID, TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION, batchSize);
  }

  private Document persistedFilesystemDocument(String fileName, String content) throws IOException {
    Path file = classTempDir.resolve(UUID.randomUUID() + "-" + fileName);
    Files.writeString(file, content.repeat(20));
    Document document =
        new Document(fileName, file.toAbsolutePath().toString(), "text/plain", Files.size(file));
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-" + fileName);
    return documentRepository.save(document);
  }

  private Document persistedDocumentPointingAt(
      String fileName, Path file, DocumentSourceType sourceType, UUID libraryId)
      throws IOException {
    Document document =
        new Document(
            fileName, file.toAbsolutePath().toString(), "text/plain", Files.size(file), sourceType);
    document.setLibraryId(libraryId);
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-" + fileName);
    return documentRepository.save(document);
  }

  private Document persistedRemoteDocument(String url) {
    Document document =
        new Document(
            "satzung.pdf", url, "application/pdf", 1024L, DocumentSourceType.HTTP_DIRECTORY);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-remote");
    return documentRepository.save(document);
  }

  /** A Confluence page row as the full sync writes it: identity URL, version as marker. */
  private Document persistedConfluenceDocument(String url) {
    Document document =
        new Document("Abschnitt 1.1", url, "text/html", 1024L, DocumentSourceType.CONFLUENCE);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-remote");
    document.setLastModifiedRemote("7");
    return documentRepository.save(document);
  }

  private UUID seedChunk(UUID documentId, String text, String pipelineId, Short pipelineVersion) {
    return seedChunk(documentId, library.getId(), text, pipelineId, pipelineVersion);
  }

  private UUID seedChunk(
      UUID documentId, UUID libraryId, String text, String pipelineId, Short pipelineVersion) {
    return seedChunk(documentId, libraryId, text, pipelineId, pipelineVersion, null);
  }

  /**
   * Like the four-argument overload, additionally seeding {@link
   * ChunkPipelineMetadata#ROUTING_EXTENSION_METADATA_KEY} - {@code null} omits the key entirely
   * (the earlier Altbestand this class's other tests already cover), matching a forward-written
   * chunk otherwise.
   */
  private UUID seedChunk(
      UUID documentId,
      UUID libraryId,
      String text,
      String pipelineId,
      Short pipelineVersion,
      String routingExtension) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString());
    metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString());
    metadata.put("organization_id", Organization.DEFAULT_ID.toString());
    if (pipelineId != null) {
      metadata.put(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY, pipelineId);
      metadata.put(ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY, (int) pipelineVersion);
    }
    if (routingExtension != null) {
      metadata.put(ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY, routingExtension);
    }
    org.springframework.ai.document.Document chunk =
        new org.springframework.ai.document.Document(text, metadata);
    vectorStore.add(List.of(chunk));
    // Mirrors the production write path, which fills chunk_full_text in the same transaction: a
    // chunk without a current-version row is stale for the re-index in its own right, and
    // this class's other tests are about pipeline routing, not about that.
    insertFullTextRow(
        UUID.fromString(chunk.getId()),
        documentId,
        libraryId,
        FullTextChunkStore.CURRENT_TSV_VERSION);
    return UUID.fromString(chunk.getId());
  }

  private void insertFullTextRow(
      UUID chunkId, UUID documentId, UUID libraryId, short contentTsvVersion) {
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, to_tsvector('german', ?), ?)",
        chunkId,
        documentId,
        libraryId,
        "inhalt",
        contentTsvVersion);
  }

  private List<String> chunkTextsOf(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT content FROM vector_store WHERE metadata->>'document_id' = ?",
        String.class,
        documentId.toString());
  }

  private long currentVersionFullTextRowsOf(UUID documentId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chunk_full_text WHERE document_id = ? AND content_tsv_version = ?",
        Long.class,
        documentId,
        FullTextChunkStore.CURRENT_TSV_VERSION);
  }

  private long fullTextRowCountOf(UUID documentId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chunk_full_text WHERE document_id = ?", Long.class, documentId);
  }

  private List<Integer> pipelineVersionsOf(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT (metadata->>'pipeline_version')::int FROM vector_store "
            + "WHERE metadata->>'document_id' = ?",
        Integer.class,
        documentId.toString());
  }
}
