package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.api.types.LibraryMetadataSchemaChangeKind;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.common.ConflictException;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OpaaTestDirectory;
import io.opaa.test.OwnLibraryFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two-phase value mapping and field deletion (#1361, metadata-schema.md "Nachlauf im Betrieb"):
 * the subject is retired first, the documents are rewritten in Chargen, each in its own
 * transaction, and the list entry respectively the field is removed only afterwards.
 *
 * <p>The invariant every method here circles: <b>no document ever carries a value the schema does
 * not list</b>. During the run the retired value stays listed - that is the Festlegung of this
 * issue - and is refused for every <em>new</em> setting, so the run is finite and the state
 * "Dokument trägt einen Wert, den es im Schema nicht mehr gibt" stays unreachable throughout.
 *
 * <p>The Charge size is passed explicitly: with the production default of 500 a two-document
 * fixture would finish in the confirming call and prove nothing about the resumption.
 */
@OpaaIntegrationTest
class LibraryMetadataSchemaChangeIntegrationTest {

  private static final Path classTempDir = OpaaTestDirectory.subdirectory("schema-changes");

  @Autowired private LibraryMetadataFieldService fieldService;
  @Autowired private LibraryMetadataSchemaChangeService schemaChangeService;
  @Autowired private DocumentMetadataCorrectionService correctionService;
  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private LibraryMetadataFieldValueRepository fieldValueRepository;
  @Autowired private LibraryMetadataFieldRepository fieldRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private MockMvc mockMvc;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private KnowledgeLibrary library;
  private CurrentUser owner;
  private CurrentUser editor;

  @BeforeEach
  void setUp() throws IOException {
    removeOwnFixtures();
    owner = user("owner");
    editor = user("editor");
    library = library();
    grant(library, owner, AssetRole.OWNER);
    grant(library, editor, AssetRole.EDITOR);
    Files.createDirectories(classTempDir);
    try (var files = Files.list(classTempDir)) {
      for (Path file : files.toList()) {
        if (Files.isRegularFile(file)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  /**
   * The Charge boundary itself: one document is rewritten, the other keeps the retired value, and
   * that value is still a listed value of the field - the state the specification calls a defined
   * Mischzustand rather than a fault.
   */
  @Test
  void aMappingRewritesOneChargeAtATimeAndKeepsTheOldValueListedUntilTheLastDocument()
      throws IOException {
    createSelectField("fassung");
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    setValue(first, "A");
    setValue(second, "A");

    LibraryFieldValueRemapResult firstCharge =
        fieldService.remapValue(library.getId(), "fassung", "A", "B", 1, owner);

    assertThat(firstCharge.complete()).isFalse();
    assertThat(firstCharge.remappedDocuments()).isEqualTo(1);
    assertThat(firstCharge.remainingDocuments()).isEqualTo(1);
    assertThat(fieldValueRepository.findByFieldIdAndCode(fieldId("fassung"), "A"))
        .as("the retired value stays listed while a document still carries it")
        .isPresent();
    assertThat(storedCodes())
        .as("every stored value is a value the field lists - before and after each Charge")
        .allSatisfy(code -> assertThat(listedCodes("fassung")).contains(code));
    assertThat(fieldService.overviewOf(library.getId(), owner).pendingSchemaChanges())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.kind()).isEqualTo(LibraryMetadataSchemaChangeKind.VALUE_REMAP);
              assertThat(change.valueCode()).isEqualTo("A");
              assertThat(change.targetCode()).isEqualTo("B");
              assertThat(change.processedDocuments()).isEqualTo(1);
              assertThat(change.remainingDocuments()).isEqualTo(1);
            });

    LibraryMetadataSchemaRunResult secondCharge =
        fieldService.runSchemaChanges(library.getId(), 1, owner);

    assertThat(secondCharge.complete()).isTrue();
    assertThat(secondCharge.processedDocuments()).isEqualTo(1);
    assertThat(fieldValueRepository.findByFieldIdAndCode(fieldId("fassung"), "A")).isEmpty();
    for (Document document : List.of(first, second)) {
      assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "lib:fassung"))
          .get()
          .satisfies(row -> assertThat(row.getTextValue()).isEqualTo("B"));
    }
    // One event per document, both under the one correlationRef the change carries from its
    // confirmation on - the whole mapping reads back as one operation across its Chargen.
    assertThat(auditedValues(firstCharge.correlationRef())).containsExactlyInAnyOrder("A", "A");
  }

  /**
   * What keeps the run finite and the invalid state unreachable at the same time: a retired value
   * is refused for every new setting, by hand and per Sammelzuweisung alike, while removing a value
   * stays possible - that is what the run itself does.
   */
  @Test
  void aRetiredValueCannotBeSetOnAnotherDocumentWhileTheMappingRuns() throws IOException {
    createSelectField("fassung");
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    Document third = indexed("beitrag.pdf");
    setValue(first, "A");
    setValue(second, "A");

    fieldService.remapValue(library.getId(), "fassung", "A", "B", 1, owner);

    assertThatThrownBy(() -> setValue(third, "A"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("abgebildet");
    assertThatThrownBy(
            () ->
                correctionService.bulkSetValue(
                    library.getId(),
                    "lib:fassung",
                    MetadataValueInput.text("A"),
                    List.of(third.getId()),
                    editor))
        .isInstanceOf(ConflictException.class);
    assertThat(valueRepository.findByDocumentIdAndFieldKey(third.getId(), "lib:fassung")).isEmpty();

    // The value that is not retired stays settable - a running mapping freezes one entry, not the
    // field.
    setValue(third, "B");
    assertThat(valueRepository.findByDocumentIdAndFieldKey(third.getId(), "lib:fassung"))
        .get()
        .satisfies(row -> assertThat(row.getTextValue()).isEqualTo("B"));
  }

  /**
   * A second confirmation resumes the same run; a different target is refused, not silently won.
   */
  @Test
  void repeatingTheConfirmationResumesWhileADifferentTargetIsAConflict() throws IOException {
    createSelectField("fassung");
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    setValue(first, "A");
    setValue(second, "A");

    LibraryFieldValueRemapResult firstCharge =
        fieldService.remapValue(library.getId(), "fassung", "A", "B", 1, owner);

    assertThatThrownBy(() -> fieldService.remapValue(library.getId(), "fassung", "A", null, owner))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("anderes Ziel");

    LibraryFieldValueRemapResult resumed =
        fieldService.remapValue(library.getId(), "fassung", "A", "B", 1, owner);

    assertThat(resumed.correlationRef()).isEqualTo(firstCharge.correlationRef());
    assertThat(resumed.complete()).isTrue();
    // A run over documents already rewritten does nothing at all.
    assertThat(fieldService.runSchemaChanges(library.getId(), 500, owner))
        .satisfies(
            run -> {
              assertThat(run.complete()).isTrue();
              assertThat(run.processedDocuments()).isZero();
            });
    assertThat(auditedValues(firstCharge.correlationRef())).hasSize(2);
  }

  /**
   * The field deletion takes the same two phases: while it runs the field still exists - which is
   * what lets the rewrite strip its chunk keys - and it disappears with its last emptied document.
   */
  @Test
  void aFieldDeletionEmptiesInChargenAndRemovesTheFieldOnlyAfterTheLastDocument()
      throws IOException {
    createSelectField("fassung");
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    setValue(first, "A");
    setValue(second, "B");

    LibraryMetadataSchemaRunResult firstCharge =
        fieldService.deleteField(library.getId(), "fassung", 1, owner);

    assertThat(firstCharge.complete()).isFalse();
    assertThat(firstCharge.remainingDocuments()).isEqualTo(1);
    assertThat(fieldRepository.findByLibraryIdAndFieldKey(library.getId(), "fassung"))
        .as("the field exists until its last value is gone - its chunk keys go with the rewrite")
        .isPresent();
    assertThat(fieldService.fieldsOf(library.getId(), owner))
        .singleElement()
        .satisfies(definition -> assertThat(definition.deletionPending()).isTrue());
    assertThatThrownBy(() -> setValue(first, "B"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("gelöscht");

    LibraryMetadataSchemaRunResult secondCharge =
        fieldService.runSchemaChanges(library.getId(), 500, owner);

    assertThat(secondCharge.complete()).isTrue();
    assertThat(fieldService.fieldsOf(library.getId(), owner)).isEmpty();
    for (Document document : List.of(first, second)) {
      assertThat(valueRepository.findByDocumentId(document.getId()))
          .noneMatch(row -> "lib:fassung".equals(row.getFieldKey()));
      assertThat(chunkMetadata(document.getId()))
          .allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("lf_fassung"));
    }
    assertThat(auditedValues(correlationRefOf(firstCharge))).containsExactlyInAnyOrder("A", "B");
  }

  /**
   * The failure path, and with it the transaction bracket the whole feature stands on: one document
   * cannot be advanced - here because another transaction holds its value row, which is what a
   * second Charge running in parallel does - and the other one is rewritten and <b>committed</b>
   * all the same. Were the confirming call transactional again, the failed unit would take the
   * finished one down with it and this test would fail; that is the regression it guards.
   */
  @Test
  void aDocumentThatCannotBeAdvancedIsSkippedWhileTheOtherStaysCommitted() throws Exception {
    createSelectField("fassung");
    Document rewritten = indexed("satzung.pdf");
    Document blocked = indexed("gebuehren.pdf");
    setValue(rewritten, "A");
    setValue(blocked, "A");

    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      lockValueRowOf(lock, blocked);

      LibraryFieldValueRemapResult confirmation =
          fieldService.remapValue(library.getId(), "fassung", "A", "B", 500, owner);

      assertThat(confirmation.remappedDocuments()).isEqualTo(1);
      assertThat(confirmation.complete()).isFalse();
      assertThat(confirmation.remainingDocuments()).isEqualTo(1);
      assertThat(valueRepository.findByDocumentIdAndFieldKey(rewritten.getId(), "lib:fassung"))
          .as("committed on its own, although its sibling failed in the same Charge")
          .get()
          .satisfies(row -> assertThat(row.getTextValue()).isEqualTo("B"));
      assertThat(valueRepository.findByDocumentIdAndFieldKey(blocked.getId(), "lib:fassung"))
          .get()
          .satisfies(row -> assertThat(row.getTextValue()).isEqualTo("A"));

      LibraryMetadataSchemaRunResult blockedCharge =
          fieldService.runSchemaChanges(library.getId(), 500, owner);

      assertThat(blockedCharge.processedDocuments()).isZero();
      assertThat(blockedCharge.complete()).isFalse();
      // Held, not broken: a document somebody else is writing right now is scanned past and stays
      // pending, but it is no fault and must not reach the "fehlgeschlagen" the status page shows.
      assertThat(blockedCharge.skippedDocuments()).isZero();
      assertThat(schemaChangeService.progressForLibraries(Set.of(library.getId())))
          .hasEntrySatisfying(
              library.getId(),
              progress -> {
                assertThat(progress.pendingDocuments()).isEqualTo(1);
                assertThat(progress.lastSkippedDocuments()).isZero();
              });
      lock.rollback();
    }

    assertThat(fieldService.runSchemaChanges(library.getId(), 500, owner).complete()).isTrue();
    assertThat(valueRepository.findByDocumentIdAndFieldKey(blocked.getId(), "lib:fassung"))
        .get()
        .satisfies(row -> assertThat(row.getTextValue()).isEqualTo("B"));
    assertThat(fieldValueRepository.findByFieldIdAndCode(fieldId("fassung"), "A")).isEmpty();
  }

  /**
   * Every answer is about the operation it was asked for, not about the library. A library may well
   * have several changes running - and then "is my deletion done?", "how much of my mapping is
   * left?" and "how much work does this library have?" are three different questions with three
   * different answers.
   */
  @Test
  void severalChangesOfOneLibraryDoNotAnswerForEachOther() throws IOException {
    createSelectField("fassung");
    createSelectField("stand");
    createSelectField("gremium");
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    setValue(first, "fassung", "A");
    setValue(second, "fassung", "A");
    setValue(first, "stand", "A");
    setValue(second, "stand", "A");
    setValue(first, "gremium", "A");

    fieldService.remapValue(library.getId(), "fassung", "A", "B", 1, owner);
    LibraryFieldValueRemapResult secondRemap =
        fieldService.remapValue(library.getId(), "stand", "A", "B", 1, owner);

    // Two fields of one library may carry the same value code; the remaining work of the other
    // mapping is none of this answer's business.
    assertThat(secondRemap.complete()).isFalse();
    assertThat(secondRemap.remainingDocuments()).isEqualTo(1);

    LibraryMetadataSchemaRunResult deletion =
        fieldService.deleteField(library.getId(), "gremium", 500, owner);

    assertThat(deletion.confirmedChangeComplete())
        .as(
            "the deletion finished within its first Charge - the mappings beside it are not its"
                + " business")
        .isTrue();
    assertThat(deletion.complete()).isFalse();
    assertThat(fieldService.fieldsOf(library.getId(), owner))
        .extracting(definition -> definition.field().getFieldKey())
        .containsExactlyInAnyOrder("fassung", "stand");

    assertThat(schemaChangeService.progressForLibraries(Set.of(library.getId())))
        .hasEntrySatisfying(
            library.getId(),
            progress -> {
              assertThat(progress.pendingChanges()).isEqualTo(2);
              // Summed per change, exactly like the settings page shows it: the one document both
              // mappings still have to rewrite is two pieces of work, not one.
              assertThat(progress.pendingDocuments()).isEqualTo(2);
            });
  }

  /**
   * The status code is the answer to "is my deletion done?", and it must stay one: a mapping
   * running at another field of the same library is not this deletion's business, and 202 would
   * send the client into a run it neither asked for nor has to wait for.
   */
  @Test
  void theDeletionAnswers204WhenItIsDoneEvenWithAnotherChangeRunning() throws Exception {
    createSelectField("fassung");
    createSelectField("gremium");
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    setValue(first, "fassung", "A");
    setValue(second, "fassung", "A");
    setValue(first, "gremium", "A");
    fieldService.remapValue(library.getId(), "fassung", "A", "B", 1, owner);

    mockMvc
        .perform(
            delete("/api/v1/libraries/" + library.getId() + "/metadata-fields/gremium")
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin"))
        .andExpect(status().isNoContent());

    assertThat(fieldService.fieldsOf(library.getId(), owner))
        .extracting(definition -> definition.field().getFieldKey())
        .containsExactly("fassung");
  }

  /** The running change is part of the library's index state, not only of its settings page. */
  @Test
  void theRunningChangeAppearsInTheIndexStatusOfItsLibrary() throws IOException {
    createSelectField("fassung");
    Document first = indexed("satzung.pdf");
    Document second = indexed("gebuehren.pdf");
    setValue(first, "A");
    setValue(second, "A");

    fieldService.remapValue(library.getId(), "fassung", "A", null, 1, owner);

    assertThat(schemaChangeService.progressForLibraries(Set.of(library.getId())))
        .hasEntrySatisfying(
            library.getId(),
            progress -> {
              assertThat(progress.pendingChanges()).isEqualTo(1);
              assertThat(progress.pendingDocuments()).isEqualTo(1);
              assertThat(progress.lastSkippedDocuments()).isZero();
            });

    fieldService.runSchemaChanges(library.getId(), 500, owner);

    assertThat(schemaChangeService.progressForLibraries(Set.of(library.getId()))).isEmpty();
  }

  /** Holds the document's value row in another transaction - what a parallel Charge would do. */
  private void lockValueRowOf(Connection connection, Document document) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM document_metadata_values WHERE document_id = ?"
                + " AND field_key = 'lib:fassung' FOR UPDATE")) {
      statement.setObject(1, document.getId());
      statement.executeQuery().close();
    }
  }

  private void createSelectField(String key) {
    fieldService.createField(
        library.getId(),
        new LibraryMetadataFieldInput(
            key,
            key,
            LibraryMetadataFieldType.SELECT,
            null,
            true,
            false,
            null,
            List.of(
                new LibraryMetadataFieldInput.LibraryFieldValueInput("A", "Wert A"),
                new LibraryMetadataFieldInput.LibraryFieldValueInput("B", "Wert B"))),
        owner);
  }

  private void setValue(Document document, String code) {
    setValue(document, "fassung", code);
  }

  private void setValue(Document document, String fieldKey, String code) {
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "lib:" + fieldKey,
        MetadataValueInput.text(code),
        editor);
  }

  private UUID fieldId(String fieldKey) {
    return fieldRepository
        .findByLibraryIdAndFieldKey(library.getId(), fieldKey)
        .orElseThrow()
        .getId();
  }

  private List<String> listedCodes(String fieldKey) {
    return fieldValueRepository.findByFieldIdOrderBySortOrderAscCodeAsc(fieldId(fieldKey)).stream()
        .map(LibraryMetadataFieldValue::getCode)
        .toList();
  }

  /** Every {@code lib:fassung} value stored in this library's documents. */
  private List<String> storedCodes() {
    return jdbcTemplate.queryForList(
        "SELECT v.text_value FROM document_metadata_values v, documents d"
            + " WHERE d.id = v.document_id AND d.library_id = ? AND v.field_key = 'lib:fassung'",
        String.class,
        library.getId());
  }

  private String correlationRefOf(LibraryMetadataSchemaRunResult result) {
    return result.pendingChanges().getFirst().correlationRef();
  }

  /** The old value of every audit event of one change, read back by its correlationRef. */
  private List<String> auditedValues(String correlationRef) {
    return jdbcTemplate.query(
        "SELECT before FROM audit_log WHERE correlation_ref = ? ORDER BY recorded_at, event_id",
        (rs, index) -> valueOf(rs.getString("before")),
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
        (rs, index) -> parseJson(rs.getString("metadata")),
        documentId.toString());
  }

  private static Map<String, Object> parseJson(String json) {
    return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  // Both hooks: the @BeforeEach call removes what a method aborted halfway left behind, the
  // @AfterEach call what this one created. Found by this class's own library name and user e-mail
  // pattern, never by table - the whole suite shares one database.
  @AfterEach
  void removeOwnFixtures() {
    List<UUID> ownLibraryIds =
        jdbcTemplate.queryForList(
            "SELECT id FROM knowledge_libraries WHERE name LIKE 'Schemaänderung%'", UUID.class);
    ownLibraryFixtures.removeLibraries(ownLibraryIds.toArray(new UUID[0]));
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'schema-change-%'");
  }

  private CurrentUser user(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "schema-change-" + id,
        "schema-change-" + name + "-" + id + "@example.com",
        "Schema " + name,
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, SystemRole.USER, "Schema " + name);
  }

  private KnowledgeLibrary library() {
    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Schemaänderung",
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

  private void grant(KnowledgeLibrary target, CurrentUser subject, AssetRole role) {
    grantRepository.save(
        AssetGrant.forUser(
            target.getId(), Organization.DEFAULT_ID, subject.id(), role, null, owner.id()));
    accessService.invalidateLibrary(target.getId());
  }

  private Document indexed(String fileName) throws IOException {
    Path file = classTempDir.resolve(fileName);
    writePdf(file);
    assertThat(documentIngestService.ingest(DocumentIngest.localFile(library, file).build(), null))
        .isEqualTo(DocumentIngestResult.PROCESSED);
    return documentRepository.findByLibraryId(library.getId()).stream()
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
