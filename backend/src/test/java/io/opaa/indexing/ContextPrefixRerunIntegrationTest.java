package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.LibraryMetadataFieldDefinition;
import io.opaa.indexing.metadata.LibraryMetadataFieldInput;
import io.opaa.indexing.metadata.LibraryMetadataFieldService;
import io.opaa.indexing.metadata.LibraryMetadataFieldValue;
import io.opaa.indexing.metadata.MetadataFieldRef;
import io.opaa.indexing.metadata.MetadataValueInput;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Kontextpräfix and its Nachlauf end to end (#1072): a prefix-effective value reaches embedding
 * input and full-text index without ever entering the stored chunk text, the run selects by the
 * version pair, and it is idempotent, document-granular, resumable and destructive of nothing.
 */
@OpaaIndexingIntegrationTest
class ContextPrefixRerunIntegrationTest {

  private static final Path classTempDir = OpaaIndexingTestDirectory.subdirectory("context-prefix");

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private ContextPrefixRerunService rerunService;
  @Autowired private LibraryMetadataFieldService fieldService;
  @Autowired private DocumentMetadataService metadataService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary library;
  private CurrentUser owner;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    jdbcTemplate.update("DELETE FROM document_metadata_values");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM library_metadata_field_values");
    jdbcTemplate.update("DELETE FROM library_metadata_fields");
    jdbcTemplate.update("DELETE FROM asset_grants");
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE name LIKE 'Kontextpräfix%'");
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'context-prefix-%'");
    owner = user("owner");
    library = library();
    grantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            Organization.DEFAULT_ID,
            owner.id(),
            AssetRole.OWNER,
            null,
            owner.id()));
    accessService.invalidateLibrary(library.getId());
    Files.createDirectories(classTempDir);
    try (var files = Files.list(classTempDir)) {
      for (Path file : files.toList()) {
        if (Files.isRegularFile(file)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  @Test
  void thePrefixReachesEmbeddingAndFullTextWhileTheStoredChunkTextStaysTheOriginalWording()
      throws IOException {
    LibraryMetadataFieldValue value = prefixEffectiveFassungField();
    Document document = indexed("verwaltungsgebuehrensatzung.md");
    setFassung(document, value);

    ContextPrefixRerunResult result =
        rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(result.processedDocuments()).isEqualTo(1);
    assertThat(result.isEmpty()).isFalse();
    assertThat(chunkTexts(document))
        .as("the quoted excerpt in a Beleg stays the original wording")
        .isNotEmpty()
        .allSatisfy(text -> assertThat(text).doesNotContain("Fassung 2026"));
    assertThat(fullTextMatches(document, "Fassung"))
        .as("a Fassungsbegriff that only exists in the prefix is found lexically")
        .isEqualTo(chunkCount(document));
    assertThat(fullTextMatches(document, "Verwaltungsgebührensatzung"))
        .as("the Kernfeld Titel rides on every chunk of the document")
        .isEqualTo(chunkCount(document));
  }

  @Test
  void theRunIsIdempotentDocumentGranularAndResumableAndTheMischzustandIsQueryable()
      throws IOException {
    LibraryMetadataFieldValue value = prefixEffectiveFassungField();
    Document first = indexed("satzung-a.md");
    Document second = indexed("satzung-b.md");
    setFassung(first, value);
    setFassung(second, value);

    assertThat(rerunService.pendingDocuments(library.getId())).isEqualTo(2);

    // One document per call: the Mischzustand between them is a defined, permitted state.
    ContextPrefixRerunResult firstBatch =
        rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 1);
    assertThat(firstBatch.processedDocuments()).isEqualTo(1);
    assertThat(progress().currentDocuments()).isEqualTo(1);
    assertThat(progress().pendingDocuments()).isEqualTo(1);
    assertThat(progress().isComplete()).isFalse();

    rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 1);
    assertThat(progress().pendingDocuments()).isZero();
    assertThat(progress().isComplete()).isTrue();

    // A second run over an already current bestand advances nothing and costs no embedding call.
    assertThat(rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 10).isEmpty())
        .isTrue();
  }

  @Test
  void nothingIsDestroyedBeforeItsReplacementExistsSoEveryChunkIdAndTextSurvives()
      throws IOException {
    LibraryMetadataFieldValue value = prefixEffectiveFassungField();
    Document document = indexed("satzung-erhalt.md");
    List<UUID> idsBefore = chunkIds(document);
    List<String> textsBefore = chunkTexts(document);
    setFassung(document, value);

    rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(chunkIds(document))
        .as("a Beleg and a deep link survive the Nachlauf")
        .containsExactlyElementsOf(idsBefore);
    assertThat(chunkTexts(document)).containsExactlyElementsOf(textsBefore);
    assertThat(fullTextRowCount(document)).isEqualTo(idsBefore.size());
  }

  @Test
  void switchingACoreFieldIntoThePrefixHandsTheWholeBestandToTheRunWithoutStartingIt()
      throws IOException {
    Document document = indexed("dienstanweisung.md");
    assertThat(rerunService.pendingDocuments(library.getId()))
        .as("a freshly indexed document is stamped with its library's current version")
        .isZero();

    fieldService.updateCoreContextPrefix(library.getId(), false, true, owner);

    assertThat(rerunService.pendingDocuments(library.getId()))
        .as("saving the schema change moves no bestand, it only marks it")
        .isEqualTo(1);
    assertThat(chunkTexts(document)).isNotEmpty();
    assertThat(fullTextRowCount(document))
        .as("the search stays available over the not yet re-embedded half")
        .isEqualTo(chunkCount(document));
  }

  @Test
  void aFieldThatOnlyFiltersLeavesTheBestandAloneWhileAPrefixEffectiveOneDoesNot()
      throws IOException {
    indexed("nur-filter.md");
    fieldService.createField(
        library.getId(),
        new LibraryMetadataFieldInput(
            "projekt",
            "Projekt",
            LibraryMetadataFieldType.SELECT,
            null,
            true,
            false,
            null,
            List.of(new LibraryMetadataFieldInput.LibraryFieldValueInput("P1", "Projekt 1"))),
        owner);

    assertThat(rerunService.pendingDocuments(library.getId()))
        .as("a field that only filters costs nothing on change")
        .isZero();

    fieldService.updateField(library.getId(), "projekt", "Projekt", true, true, null, owner);

    assertThat(rerunService.pendingDocuments(library.getId())).isEqualTo(1);
  }

  @Test
  void aManualCorrectionOfAPrefixEffectiveValueHandsTheDocumentBackInsteadOfReembedding()
      throws IOException {
    LibraryMetadataFieldValue value = prefixEffectiveFassungField();
    Document document = indexed("korrektur.md");
    rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 10);
    assertThat(rerunService.pendingDocuments(library.getId())).isZero();

    setFassung(document, value);

    assertThat(
            documentRepository.findById(document.getId()).orElseThrow().getContextPrefixVersion())
        .as("an explicit release re-embeds, not the correction itself")
        .isNull();
    assertThat(rerunService.pendingDocuments(library.getId())).isEqualTo(1);
    assertThat(fullTextMatches(document, "Fassung")).isZero();

    rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(fullTextMatches(document, "Fassung")).isEqualTo(chunkCount(document));
  }

  @Test
  void aCorrectionOfAFieldThatIsNotPrefixEffectiveDoesNotHandTheDocumentToTheRun()
      throws IOException {
    LibraryMetadataFieldDefinition definition =
        fieldService.createField(
            library.getId(),
            new LibraryMetadataFieldInput(
                "projekt",
                "Projekt",
                LibraryMetadataFieldType.SELECT,
                null,
                true,
                false,
                null,
                List.of(new LibraryMetadataFieldInput.LibraryFieldValueInput("P1", "Projekt 1"))),
            owner);
    Document document = indexed("filterfeld.md");

    metadataService.setManualValue(
        document.getId(),
        MetadataFieldRef.of(definition.field()),
        MetadataValueInput.libraryValue("P1", definition.values().getFirst().getId()),
        owner.id());

    assertThat(rerunService.pendingDocuments(library.getId())).isZero();
  }

  @Test
  void aCorrectedTitleAlwaysHandsTheDocumentToTheRunBecauseTheTitleIsAlwaysPrefixEffective()
      throws IOException {
    Document document = indexed("titel.md");
    assertThat(rerunService.pendingDocuments(library.getId())).isZero();

    metadataService.setManualValue(
        document.getId(),
        MetadataFieldRef.of(CoreMetadataField.TITLE),
        MetadataValueInput.text("Gebührenordnung Meldewesen"),
        owner.id());

    assertThat(rerunService.pendingDocuments(library.getId())).isEqualTo(1);

    rerunService.rerunBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(fullTextMatches(document, "Meldewesen")).isEqualTo(chunkCount(document));
  }

  private ContextPrefixRerunProgress progress() {
    return rerunService
        .progressForLibraries(List.of(library.getId()))
        .getOrDefault(library.getId(), ContextPrefixRerunProgress.empty(library.getId(), 1));
  }

  private LibraryMetadataFieldValue prefixEffectiveFassungField() {
    return fieldService
        .createField(
            library.getId(),
            new LibraryMetadataFieldInput(
                "fassung",
                "Fassung",
                LibraryMetadataFieldType.SELECT,
                null,
                false,
                true,
                null,
                List.of(
                    new LibraryMetadataFieldInput.LibraryFieldValueInput("F2026", "Fassung 2026"))),
            owner)
        .values()
        .getFirst();
  }

  private void setFassung(Document document, LibraryMetadataFieldValue value) {
    LibraryMetadataFieldDefinition definition =
        fieldService.fieldsOf(library.getId(), owner).stream()
            .filter(candidate -> "fassung".equals(candidate.field().getFieldKey()))
            .findFirst()
            .orElseThrow();
    metadataService.setManualValue(
        document.getId(),
        MetadataFieldRef.of(definition.field()),
        MetadataValueInput.libraryValue(value.getCode(), value.getId()),
        owner.id());
  }

  private Document indexed(String fileName) throws IOException {
    Path file = classTempDir.resolve(fileName);
    Files.writeString(
        file,
        """
        # Verwaltungsgebührensatzung

        ## § 7 Gebühren für Personaldokumente

        Für die Ausstellung eines Personalausweises wird eine Gebühr von 37,00 EUR erhoben.
        Die Gebühr ist bei Antragstellung fällig und wird nicht erstattet.

        ## § 8 Befreiungen

        Von der Gebühr befreit sind Personen, die Leistungen nach dem Zweiten Buch
        Sozialgesetzbuch beziehen und dies durch einen aktuellen Bescheid nachweisen.
        """,
        StandardCharsets.UTF_8);
    assertThat(fileProcessingService.processFile(file, library))
        .isEqualTo(FileProcessingResult.PROCESSED);
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .findFirst()
        .orElseThrow();
  }

  private List<UUID> chunkIds(Document document) {
    return jdbcTemplate.queryForList(
        "SELECT id FROM vector_store WHERE metadata->>'document_id' = ? ORDER BY id",
        UUID.class,
        document.getId().toString());
  }

  private List<String> chunkTexts(Document document) {
    return jdbcTemplate.queryForList(
        "SELECT content FROM vector_store WHERE metadata->>'document_id' = ? ORDER BY id",
        String.class,
        document.getId().toString());
  }

  private long chunkCount(Document document) {
    return chunkIds(document).size();
  }

  private long fullTextRowCount(Document document) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE document_id = ?",
            Long.class,
            document.getId());
    return count == null ? 0 : count;
  }

  private long fullTextMatches(Document document, String term) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE document_id = ?"
                + " AND content_tsv @@ plainto_tsquery('german', ?)",
            Long.class,
            document.getId(),
            term);
    return count == null ? 0 : count;
  }

  private CurrentUser user(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "context-prefix-" + id,
        "context-prefix-" + name + "-" + id + "@example.com",
        "Präfix " + name,
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, SystemRole.USER, "Präfix " + name);
  }

  private KnowledgeLibrary library() {
    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Kontextpräfix",
            null,
            owner.id(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            classTempDir.toString(),
            null,
            null,
            null,
            false));
  }
}
