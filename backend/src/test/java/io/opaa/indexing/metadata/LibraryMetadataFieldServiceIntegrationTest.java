package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.query.MetadataFilterOptions;
import io.opaa.query.MetadataFilterOptionsService;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The library field schema end to end (#1071): the Aufnahmeregel and the field limit, the fact that
 * a value outside the configured list is not storable, the confirmed mapping with its Folgekosten,
 * audit events and chunk rewrite, and what a field deletion takes with it.
 */
@OpaaIndexingIntegrationTest
class LibraryMetadataFieldServiceIntegrationTest {

  private static final Path classTempDir = OpaaIndexingTestDirectory.subdirectory("library-fields");

  @Autowired private LibraryMetadataFieldService fieldService;
  @Autowired private DocumentMetadataCorrectionService correctionService;
  @Autowired private LibraryMetadataMaintenanceService maintenanceService;
  @Autowired private CitationMetadataReader citationReader;
  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private LibraryMetadataFieldValueRepository fieldValueRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private MetadataFilterOptionsService filterOptionsService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary library;
  private CurrentUser owner;
  private CurrentUser editor;
  private CurrentUser viewer;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    jdbcTemplate.update("DELETE FROM document_metadata_values");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM library_metadata_field_values");
    jdbcTemplate.update("DELETE FROM library_metadata_fields");
    jdbcTemplate.update("DELETE FROM asset_grants");
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE name LIKE 'Bibliotheksfelder%'");
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'library-fields-%'");
    owner = user("owner", SystemRole.USER);
    editor = user("editor", SystemRole.USER);
    viewer = user("viewer", SystemRole.USER);
    library = library("Bibliotheksfelder", classTempDir);
    grant(library, owner, AssetRole.OWNER);
    grant(library, editor, AssetRole.EDITOR);
    grant(library, viewer, AssetRole.VIEWER);
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
  void aFieldWithoutARetrievalEffectIsRejectedAndTheSixthFieldIsAConflict() {
    assertThatThrownBy(
            () ->
                fieldService.createField(
                    library.getId(),
                    input("nur_beleg", LibraryMetadataFieldType.SELECT, false, false, 1),
                    owner))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Beleg-Anzeige");

    for (int i = 0; i < LibraryMetadataFieldService.MAX_FIELDS; i++) {
      fieldService.createField(
          library.getId(),
          input("feld_" + i, LibraryMetadataFieldType.SELECT, true, false, null),
          owner);
    }
    assertThatThrownBy(
            () ->
                fieldService.createField(
                    library.getId(),
                    input("feld_zuviel", LibraryMetadataFieldType.SELECT, true, false, null),
                    owner))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("höchstens");
  }

  @Test
  void changingTheSchemaNeedsTheManagementRightWhileTheListIsReadableForEveryone() {
    assertThatThrownBy(
            () ->
                fieldService.createField(
                    library.getId(),
                    input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
                    editor))
        .as("editing documents is not managing the schema")
        .isInstanceOf(AccessDeniedException.class);
    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);

    assertThat(fieldService.fieldsOf(library.getId(), viewer))
        .singleElement()
        .satisfies(
            definition ->
                assertThat(definition.values())
                    .extracting(LibraryMetadataFieldValue::getCode)
                    .containsExactly("A", "B"));
  }

  @Test
  void aValueOutsideTheConfiguredListIsNotStorable() throws IOException {
    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);
    Document document = indexed("satzung.pdf");

    assertThatThrownBy(
            () ->
                correctionService.setValue(
                    library.getId(),
                    document.getId(),
                    "lib:fassung",
                    MetadataValueInput.text("C"),
                    editor))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Werteliste");

    correctionService.setValue(
        library.getId(), document.getId(), "lib:fassung", MetadataValueInput.text("A"), editor);
    assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "lib:fassung"))
        .get()
        .satisfies(
            row -> {
              assertThat(row.getTextValue()).isEqualTo("A");
              assertThat(row.getOrigin()).isEqualTo(MetadataOrigin.MANUAL);
              assertThat(row.getLibraryValueId()).isNotNull();
            });
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(
            metadata ->
                assertThat(metadata)
                    .containsEntry("lf_fassung", "A")
                    .containsEntry("lfs_fassung", "SET"));
  }

  @Test
  void theMappingKnowsItsFolgekostenRewritesEveryDocumentAndRemovesTheValueOnlyAfterwards()
      throws IOException {
    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    setLibraryValue(first, "A");
    setLibraryValue(second, "A");

    assertThat(fieldService.valueUsage(library.getId(), "fassung", "A", viewer)).isEqualTo(2);

    LibraryFieldValueRemapResult result =
        fieldService.remapValue(library.getId(), "fassung", "A", "B", owner);

    assertThat(result.remappedDocuments()).isEqualTo(2);
    assertThat(result.clearedDocuments()).isZero();
    assertThat(result.correlationRef()).startsWith("metadata-remap-");
    assertThat(fieldValueRepository.findByFieldIdAndCode(fieldId("fassung"), "A")).isEmpty();
    for (Document document : List.of(first, second)) {
      assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "lib:fassung"))
          .get()
          .satisfies(row -> assertThat(row.getTextValue()).isEqualTo("B"));
      assertThat(chunkMetadata(document.getId()))
          .allSatisfy(metadata -> assertThat(metadata).containsEntry("lf_fassung", "B"));
    }
    // One event per document, all under one correlationRef, each carrying its old value.
    List<Map<String, Object>> events = remapAuditPayloads(result.correlationRef());
    assertThat(events).hasSize(2);
    assertThat(events).allSatisfy(payload -> assertThat(payload).containsEntry("before", "A"));
  }

  @Test
  void mappingOntoLeerEmptiesTheValueAndPutsTheDocumentBackIntoTheAnchor() throws IOException {
    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);
    Document document = indexed("satzung.pdf");
    setLibraryValue(document, "A");
    assertThat(anchorOf("lib:fassung").documentsWithoutValue()).isZero();

    LibraryFieldValueRemapResult result =
        fieldService.remapValue(library.getId(), "fassung", "A", null, owner);

    assertThat(result.clearedDocuments()).isEqualTo(1);
    assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "lib:fassung"))
        .isEmpty();
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("lf_fassung"));
    assertThat(anchorOf("lib:fassung").documentsWithoutValue()).isEqualTo(1);
  }

  @Test
  void deletingAFieldRemovesItsValuesAndItsChunkKeys() throws IOException {
    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);
    Document document = indexed("satzung.pdf");
    setLibraryValue(document, "A");

    assertThat(fieldService.fieldUsage(library.getId(), "fassung", owner)).isEqualTo(1);
    fieldService.deleteField(library.getId(), "fassung", owner);

    assertThat(fieldService.fieldsOf(library.getId(), viewer)).isEmpty();
    assertThat(valueRepository.findByDocumentId(document.getId()))
        .noneMatch(row -> "lib:fassung".equals(row.getFieldKey()));
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(
            metadata ->
                assertThat(metadata)
                    .doesNotContainKey("lf_fassung")
                    .doesNotContainKey("lfs_fassung"));
  }

  @Test
  void atMostTwoLibraryFieldsReachTheBelegAndAnEmptyOneNeverDoes() throws IOException {
    fieldService.createField(
        library.getId(), input("fassung", LibraryMetadataFieldType.SELECT, true, false, 2), owner);
    fieldService.createField(
        library.getId(), input("gremium", LibraryMetadataFieldType.SELECT, true, false, 1), owner);
    fieldService.createField(
        library.getId(),
        input("projekt", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);
    Document document = indexed("satzung.pdf");
    setLibraryValue(document, "fassung", "A");
    setLibraryValue(document, "gremium", "A");
    setLibraryValue(document, "projekt", "A");

    List<CitationFieldValue> entries =
        citationReader.forDocuments(List.of(document)).get(document.getId());

    assertThat(entries)
        .extracting(CitationFieldValue::fieldKey)
        .as("only the two fields with a citation position, in that order")
        .containsExactly("lib:gremium", "lib:fassung");

    correctionService.deleteValue(library.getId(), document.getId(), "lib:gremium", editor);
    assertThat(citationReader.forDocuments(List.of(document)).get(document.getId()))
        .extracting(CitationFieldValue::fieldKey)
        .containsExactly("lib:fassung");
  }

  /**
   * The chunk keys of a field are owned by every rewrite, not only while the field filters: a field
   * that lost its Wirkstelle - or was deleted and re-created under the same key - must leave no
   * "has a value" marker behind, or a later filter would exclude a document that carries no value
   * at all, against the Leerwert rule.
   */
  @Test
  void aFieldThatStopsFilteringLosesItsChunkKeysAndANewFieldOfTheSameKeyStartsClean()
      throws IOException {
    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);
    Document document = indexed("satzung.pdf");
    setLibraryValue(document, "A");
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).containsKey("lfs_fassung"));

    fieldService.updateField(library.getId(), "fassung", "Fassung", false, true, null, owner);
    assertThat(chunkMetadata(document.getId()))
        .as("a field that no longer filters leaves no key on the chunks")
        .allSatisfy(
            metadata ->
                assertThat(metadata)
                    .doesNotContainKey("lf_fassung")
                    .doesNotContainKey("lfs_fassung"));

    fieldService.deleteField(library.getId(), "fassung", owner);
    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);

    assertThat(chunkMetadata(document.getId()))
        .as("the document carries no value for the new field and no leftover marker either")
        .allSatisfy(
            metadata ->
                assertThat(metadata)
                    .doesNotContainKey("lf_fassung")
                    .doesNotContainKey("lfs_fassung"));
  }

  /**
   * The management right is no trust boundary - every user may create a library and owns it - so a
   * field pattern is user input. A pattern whose evaluation explodes is refused where it is written
   * instead of binding a request thread when a value is set; every value check runs under the same
   * step budget, which is what bounds the patterns a probe would miss.
   */
  @Test
  void aPatternWithCatastrophicBacktrackingIsRefused() {
    assertThatThrownBy(
            () ->
                fieldService.createField(
                    library.getId(),
                    new LibraryMetadataFieldInput(
                        "aktenzeichen",
                        "Aktenzeichen",
                        LibraryMetadataFieldType.PATTERN,
                        "(.*a){20}b",
                        true,
                        false,
                        null,
                        List.of()),
                    owner))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("aufwendig");

    // A linear pattern of the same shape stays usable.
    assertThat(
            fieldService
                .createField(
                    library.getId(),
                    new LibraryMetadataFieldInput(
                        "aktenzeichen",
                        "Aktenzeichen",
                        LibraryMetadataFieldType.PATTERN,
                        "^AZ-[0-9]{1,6}$",
                        true,
                        false,
                        null,
                        List.of()),
                    owner)
                .field()
                .getValuePattern())
        .isEqualTo("^AZ-[0-9]{1,6}$");
  }

  /**
   * The filter options are derived from the schema, so a schema change has to reach them - the
   * per-person cache would otherwise offer a removed value (400 when chosen) or hide a new field
   * for up to its TTL.
   */
  @Test
  void aSchemaChangeReachesTheFilterOptionsImmediately() throws IOException {
    Document document = indexed("satzung.pdf");
    assertThat(
            filterOptionsService
                .optionsForScope(viewer.id(), Set.of(library.getId()))
                .libraryFields())
        .isEmpty();

    fieldService.createField(
        library.getId(),
        input("fassung", LibraryMetadataFieldType.SELECT, true, false, null),
        owner);
    setLibraryValue(document, "A");

    assertThat(
            filterOptionsService
                .optionsForScope(viewer.id(), Set.of(library.getId()))
                .libraryFields())
        .as("a freshly defined field is offered without waiting for the cache to expire")
        .extracting(MetadataFilterOptions.LibraryFieldOption::fieldKey)
        .containsExactly("fassung");
    assertThat(
            filterOptionsService
                .optionsForScope(viewer.id(), Set.of(library.getId()))
                .libraryFields()
                .getFirst()
                .values())
        .extracting(MetadataFilterOptions.LibraryFieldValueOption::code)
        .containsExactly("A");

    fieldService.remapValue(library.getId(), "fassung", "A", "B", owner);

    assertThat(
            filterOptionsService
                .optionsForScope(viewer.id(), Set.of(library.getId()))
                .libraryFields()
                .getFirst()
                .values())
        .as("the removed value is gone from the offered values right away")
        .extracting(MetadataFilterOptions.LibraryFieldValueOption::code)
        .containsExactly("B");
  }

  private LibraryMetadataFieldInput input(
      String key,
      LibraryMetadataFieldType type,
      boolean filter,
      boolean contextPrefix,
      Integer citationPosition) {
    return new LibraryMetadataFieldInput(
        key,
        key,
        type,
        null,
        filter,
        contextPrefix,
        citationPosition,
        List.of(
            new LibraryMetadataFieldInput.LibraryFieldValueInput("A", "Wert A"),
            new LibraryMetadataFieldInput.LibraryFieldValueInput("B", "Wert B")));
  }

  private void setLibraryValue(Document document, String code) {
    setLibraryValue(document, "fassung", code);
  }

  private void setLibraryValue(Document document, String fieldKey, String code) {
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "lib:" + fieldKey,
        MetadataValueInput.text(code),
        editor);
  }

  private UUID fieldId(String fieldKey) {
    return fieldService.fieldsOf(library.getId(), owner).stream()
        .filter(definition -> definition.field().getFieldKey().equals(fieldKey))
        .findFirst()
        .orElseThrow()
        .field()
        .getId();
  }

  private MetadataFieldMaintenance anchorOf(String fieldKey) {
    return maintenanceService.maintenanceOf(library.getId(), viewer).fields().stream()
        .filter(field -> field.fieldKey().equals(fieldKey))
        .findFirst()
        .orElseThrow();
  }

  private List<Map<String, Object>> remapAuditPayloads(String correlationRef) {
    return jdbcTemplate.query(
        "SELECT before FROM audit_log WHERE correlation_ref = ? ORDER BY recorded_at, event_id",
        (rs, i) -> Map.of("before", valueOf(rs.getString("before"))),
        correlationRef);
  }

  private static String valueOf(String json) {
    int index = json.indexOf("\"value\"");
    if (index < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', index)) + 1;
    return json.substring(start, json.indexOf('"', start));
  }

  private List<Map<String, Object>> chunkMetadata(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT metadata::text AS metadata FROM vector_store WHERE metadata->>'document_id' = ?",
        (rs, i) -> parseJson(rs.getString("metadata")),
        documentId.toString());
  }

  private static Map<String, Object> parseJson(String json) {
    return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  private CurrentUser user(String name, SystemRole role) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "library-fields-" + id,
        "library-fields-" + name + "-" + id + "@example.com",
        "Felder " + name,
        role.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, role, "Felder " + name);
  }

  private KnowledgeLibrary library(String name, Path sourcePath) {
    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            name,
            null,
            owner.id(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            sourcePath.toString(),
            null,
            null,
            null,
            false));
  }

  private void grant(KnowledgeLibrary target, CurrentUser subject, AssetRole role) {
    grantRepository.save(
        AssetGrant.forUser(
            target.getId(), Organization.DEFAULT_ID, subject.id(), role, null, owner.id()));
    accessService.invalidateLibrary(target.getId());
  }

  private Document indexed(String fileName) throws IOException {
    Path file = classTempDir.resolve(fileName);
    writePdf(file);
    assertThat(fileProcessingService.ingest(DocumentIngest.localFile(library, file).build(), null))
        .isEqualTo(FileProcessingResult.PROCESSED);
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .findFirst()
        .orElseThrow();
  }

  private static void writePdf(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(50, 700);
        content.showText("Diese Satzung regelt die Gebuehren.");
        content.endText();
      }
      doc.save(file.toFile());
    }
  }
}
