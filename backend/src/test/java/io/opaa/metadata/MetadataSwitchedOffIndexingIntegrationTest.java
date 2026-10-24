package io.opaa.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.api.types.SystemRole;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TestFeatureFlags;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.attachment.AttachmentLimits;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.ChecksumService;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OpaaTestDirectory;
import io.opaa.test.OwnLibraryFixtures;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The metadata feature switched off and on again (ADR-0044) against the real schema: a document
 * indexed while the feature is off keeps only its title - no other value, no Schlagwort, no
 * filterable chunk key - and is handed to the Bestandslauf, which fills it once the feature is back
 * on. Values stored before the feature went off stay in the database and are not read by a Beleg.
 * The ingest and the services are wired by hand with the switch off; the shared context keeps the
 * shipped default (on).
 */
@OpaaIntegrationTest
class MetadataSwitchedOffIndexingIntegrationTest {

  private static final Path classTempDir =
      OpaaTestDirectory.subdirectory("metadata-switched-off-indexing");

  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private ModelMetadataExtractor modelMetadataExtractor;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private DocumentKeywordRepository keywordRepository;
  @Autowired private DocumentTypeVocabularyRepository vocabularyRepository;
  @Autowired private LibraryMetadataFieldRepository libraryFieldRepository;
  @Autowired private LibraryMetadataFieldValueRepository libraryValueRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private DocumentFormatRegistry formatRegistry;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private ChecksumService checksumService;
  @Autowired private IndexingMetrics indexingMetrics;
  @Autowired private IndexingProperties indexingProperties;
  @Autowired private LibraryStorageQuotaService storageQuotaService;
  @Autowired private AttachmentLimits mailAttachmentLimits;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  @Autowired
  @Qualifier("embeddingTaskExecutor")
  private TaskExecutor embeddingTaskExecutor;

  private KnowledgeLibrary library;
  private UUID userId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'metadata-off-it@example.com',"
            + " 'Metadata Off IT User', now(), ?, ?)",
        userId,
        "metadata-off-it-" + userId,
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(Organization.DEFAULT_ID, "Ohne Metadaten", null, userId));
  }

  @AfterEach
  void removeOwnRows() {
    ownLibraryFixtures.removeLibraries(library.getId());
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void switchedOffOnlyTheTitleIsStoredAndTheBackfillFillsTheRestOnceSwitchedOn()
      throws IOException {
    Path file = classTempDir.resolve("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    writePdf(file, "Dienstanweisung zur IT-Nutzung");
    FeatureFlags off = TestFeatureFlags.without(Feature.METADATA);

    assertThat(ingestService(off).ingest(DocumentIngest.localFile(library, file).build(), null))
        .isEqualTo(DocumentIngestResult.PROCESSED);

    Document document = onlyOwnDocument();
    assertThat(valueRepository.findByDocumentId(document.getId()))
        .extracting(DocumentMetadataValue::getFieldKey)
        .containsExactly(CoreMetadataField.TITLE.key());
    assertThat(keywordRepository.findByDocumentIdOrderByKeywordAsc(document.getId())).isEmpty();
    assertThat(document.getMetadataExtractionVersion()).isNull();
    assertThat(chunkMetadata(document.getId()))
        .isNotEmpty()
        .allSatisfy(metadata -> assertThat(metadata).doesNotContainKeys("doc_type", "doc_date"));
    assertThat(metadataService(off).coreMetadataFor(Set.of(document.getId())))
        .containsEntry(
            document.getId(),
            new CoreMetadata(
                "Dienstanweisung zur IT-Nutzung",
                io.opaa.api.types.MetadataOrigin.DETERMINISTIC,
                null,
                null,
                null,
                null,
                null,
                null));

    // Switched on again: the Bestandslauf's own step fills what the ingest left out.
    CoreMetadata refilled = documentMetadataService.reextractFromFile(document, file);

    assertThat(refilled.documentTypeCode()).isEqualTo("DIENSTANWEISUNG");
    assertThat(refilled.documentDate()).isNotNull();
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "DIENSTANWEISUNG"));
  }

  @Test
  void valuesStoredBeforeSwitchingOffStayButNoBelegReadsThem() throws IOException {
    Path file = classTempDir.resolve("2026-03-12_Dienstanweisung_Telefon.pdf");
    writePdf(file, "Dienstanweisung Telefon");
    documentIngestService.ingest(DocumentIngest.localFile(library, file).build(), null);
    Document document = onlyOwnDocument();
    FeatureFlags off = TestFeatureFlags.without(Feature.METADATA);

    CoreMetadata read =
        metadataService(off).coreMetadataFor(Set.of(document.getId())).get(document.getId());

    assertThat(read.title()).isEqualTo("Dienstanweisung Telefon");
    assertThat(read.documentTypeCode()).isNull();
    assertThat(read.documentDate()).isNull();
    assertThat(documentMetadataService.coreMetadataFor(document.getId()).documentTypeCode())
        .isEqualTo("DIENSTANWEISUNG");
    assertThat(valueRepository.findByDocumentId(document.getId())).hasSizeGreaterThan(1);
  }

  private DocumentMetadataService metadataService(FeatureFlags flags) {
    return new DocumentMetadataService(
        valueRepository,
        vocabularyRepository,
        documentRepository,
        formatRegistry,
        vectorChunkStore,
        libraryFieldRepository,
        keywordRepository,
        libraryValueRepository,
        libraryRepository,
        transactionManager,
        flags);
  }

  @SuppressWarnings("unchecked")
  private DocumentIngestService ingestService(FeatureFlags flags) {
    return new DocumentIngestService(
        formatRegistry,
        documentRepository,
        vectorChunkStore,
        checksumService,
        indexingMetrics,
        storageQuotaService,
        indexingProperties,
        embeddingTaskExecutor,
        mock(ObjectProvider.class),
        mailAttachmentLimits,
        metadataService(flags),
        modelMetadataExtractor,
        flags,
        io.opaa.knowledge.MalwareScanService.disabled(),
        Clock.systemUTC());
  }

  private Document onlyOwnDocument() {
    List<Document> own = documentRepository.findByLibraryId(library.getId());
    assertThat(own).hasSize(1);
    return own.getFirst();
  }

  private List<Map<String, Object>> chunkMetadata(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT metadata::text AS metadata FROM vector_store WHERE metadata->>'document_id' = ?",
        (rs, i) -> parseJson(rs.getString("metadata")),
        documentId.toString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseJson(String json) {
    return new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
  }

  private static void writePdf(Path file, String title) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      for (String text :
          List.of(
              "Diese Anweisung regelt die Nutzung der IT.",
              "Passwoerter sind vertraulich zu behandeln.",
              "Private Nutzung ist untersagt.")) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(50, 700);
          content.showText(text);
          content.endText();
        }
      }
      PDDocumentInformation info = doc.getDocumentInformation();
      info.setTitle(title);
      doc.save(file.toFile());
    }
  }
}
