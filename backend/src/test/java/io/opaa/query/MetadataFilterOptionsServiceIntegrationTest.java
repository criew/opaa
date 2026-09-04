package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.DocumentMetadataCorrectionService;
import io.opaa.indexing.metadata.MetadataValueInput;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
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
 * The Füllstand of the filter interface is built in the rights context of the asking person (#1070,
 * metadata-schema.md Rechte-Invariante): two persons with different readable libraries see
 * different numbers and different value lists over the same bestand, and the per-person cache is
 * discarded on every rights change - a grant, a group membership - through the real write paths.
 */
@OpaaIndexingIntegrationTest
class MetadataFilterOptionsServiceIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("metadata-filter-options");

  @Autowired private MetadataFilterOptionsService optionsService;
  @Autowired private MetadataFilterOptionsCache cache;
  @Autowired private MetadataFilterProperties properties;
  @Autowired private AssetGrantService grantService;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentMetadataCorrectionService correctionService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary libraryA;
  private KnowledgeLibrary libraryB;
  private CurrentUser admin;
  private CurrentUser onlyA;
  private CurrentUser both;

  @BeforeEach
  void setUp() throws IOException {
    cache.invalidateAll();
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM asset_grants");
    jdbcTemplate.update("DELETE FROM group_memberships");
    // Users, groups and libraries of earlier runs stay: the grant and membership history rows the
    // real write paths wrote reference them with RESTRICT. Every run creates its own, by id.
    admin = user("admin", SystemRole.SYSTEM_ADMIN);
    onlyA = user("nur-a", SystemRole.USER);
    both = user("beide", SystemRole.USER);
    libraryA = library("Optionen-A-" + UUID.randomUUID(), classTempDir.resolve("a"));
    libraryB = library("Optionen-B-" + UUID.randomUUID(), classTempDir.resolve("b"));
    grant(libraryA, onlyA);
    grant(libraryA, both);
    grant(libraryB, both);
    deletePdfsIn(classTempDir.resolve("a"));
    deletePdfsIn(classTempDir.resolve("b"));
    // A: one document with Dokumentart and date, one with neither. B: one with both.
    indexed(libraryA, "2024-03-12_Dienstanweisung_Nutzung.pdf");
    indexed(libraryA, "Unterlage.pdf");
    indexed(libraryB, "2026-01-05_Vermerk_Nutzung.pdf");
  }

  @Test
  void theFillLevelAndTheOfferedValuesFollowTheAskingPersonsRights() {
    MetadataFilterOptions forOnlyA = optionsService.optionsFor(onlyA, null, true, List.of());
    MetadataFilterOptions forBoth = optionsService.optionsFor(both, null, true, List.of());

    assertThat(forOnlyA.totalDocuments()).isEqualTo(2);
    assertThat(field(forOnlyA, CoreMetadataField.DOCUMENT_TYPE).filledDocuments()).isEqualTo(1);
    assertThat(field(forOnlyA, CoreMetadataField.DOCUMENT_TYPE).fillShare()).isEqualTo(0.5);
    assertThat(field(forOnlyA, CoreMetadataField.DOCUMENT_TYPE).offered()).isFalse();
    assertThat(forOnlyA.documentTypes())
        .extracting(MetadataFilterOptions.DocumentTypeOption::code)
        .containsExactly("DIENSTANWEISUNG");
    assertThat(forOnlyA.documentDateMin()).isEqualTo(LocalDate.of(2024, 3, 12));
    assertThat(forOnlyA.documentDateMax()).isEqualTo(LocalDate.of(2024, 3, 12));

    assertThat(forBoth.totalDocuments()).isEqualTo(3);
    assertThat(field(forBoth, CoreMetadataField.DOCUMENT_TYPE).filledDocuments()).isEqualTo(2);
    assertThat(forBoth.documentTypes())
        .extracting(MetadataFilterOptions.DocumentTypeOption::code)
        .containsExactlyInAnyOrder("DIENSTANWEISUNG", "VERMERK");
    assertThat(forBoth.documentDateMax()).isEqualTo(LocalDate.of(2026, 1, 5));
    // The committed thresholds travel with every field.
    assertThat(field(forBoth, CoreMetadataField.DOCUMENT_TYPE).threshold())
        .isEqualTo(properties.documentTypeOfferThreshold());
    assertThat(field(forBoth, CoreMetadataField.DOCUMENT_DATE).threshold())
        .isEqualTo(properties.documentDateOfferThreshold());
  }

  /** "Kein Wert ermittelbar" counts as answered: the entry condition can be reached by hand. */
  @Test
  void aFieldMarkedNotDeterminableCountsTowardsTheFillLevel() {
    UUID untyped =
        documentRepository.findAll().stream()
            .filter(document -> "Unterlage.pdf".equals(document.getFileName()))
            .findFirst()
            .orElseThrow()
            .getId();
    correctionService.setValue(
        libraryA.getId(), untyped, "document_type", MetadataValueInput.notDeterminable(), admin);
    cache.invalidateAll();

    MetadataFilterOptions options = optionsService.optionsFor(onlyA, null, true, List.of());

    assertThat(field(options, CoreMetadataField.DOCUMENT_TYPE).filledDocuments()).isEqualTo(2);
    assertThat(field(options, CoreMetadataField.DOCUMENT_TYPE).offered()).isTrue();
    assertThat(options.documentTypes())
        .as("the mark is no value: it is not offered as a choice")
        .extracting(MetadataFilterOptions.DocumentTypeOption::code)
        .containsExactly("DIENSTANWEISUNG");
  }

  @Test
  void revokingAGrantDiscardsThePersonsCachedOptions() {
    Set<UUID> scopeBefore = Set.of(libraryA.getId(), libraryB.getId());
    optionsService.optionsFor(both, null, true, List.of());
    assertThat(cache.contains(both.id(), scopeBefore)).isTrue();

    AssetGrant grantOnB =
        grantRepository.findByLibraryId(libraryB.getId()).stream()
            .filter(grant -> both.id().equals(grant.getSubjectUserId()))
            .findFirst()
            .orElseThrow();
    grantService.revokeGrant(libraryB.getId(), grantOnB.getId(), admin);

    assertThat(cache.contains(both.id(), scopeBefore)).isFalse();
    MetadataFilterOptions after = optionsService.optionsFor(both, null, true, List.of());
    assertThat(after.totalDocuments()).isEqualTo(2);
    assertThat(after.documentTypes())
        .extracting(MetadataFilterOptions.DocumentTypeOption::code)
        .containsExactly("DIENSTANWEISUNG");
  }

  @Test
  void aGroupMembershipChangeDiscardsThePersonsCachedOptions() {
    Group group =
        groupRepository.save(
            new Group(
                Organization.DEFAULT_ID, GroupKind.AD_HOC, "Filteroptionen", null, null, null));
    grantRepository.save(
        AssetGrant.forGroup(
            libraryB.getId(),
            Organization.DEFAULT_ID,
            group.getId(),
            AssetRole.VIEWER,
            null,
            admin.id()));
    accessService.invalidateLibrary(libraryB.getId());
    Set<UUID> scopeBefore = Set.of(libraryA.getId());
    optionsService.optionsFor(onlyA, null, true, List.of());
    assertThat(cache.contains(onlyA.id(), scopeBefore)).isTrue();

    groupService.addMember(group.getId(), onlyA.id(), admin);

    assertThat(cache.contains(onlyA.id(), scopeBefore)).isFalse();
    assertThat(optionsService.optionsFor(onlyA, null, true, List.of()).totalDocuments())
        .isEqualTo(3);
  }

  private static MetadataFilterOptions.FieldOption field(
      MetadataFilterOptions options, CoreMetadataField field) {
    return options.fields().stream().filter(f -> f.field() == field).findFirst().orElseThrow();
  }

  private CurrentUser user(String name, SystemRole role) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "metadata-options-" + id,
        "metadata-options-" + name + "-" + id + "@example.com",
        "Optionen " + name,
        role.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, role, "Optionen " + name);
  }

  private KnowledgeLibrary library(String name, Path sourcePath) {
    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            name,
            null,
            admin.id(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            sourcePath.toString(),
            null,
            null,
            null,
            false));
  }

  private void grant(KnowledgeLibrary target, CurrentUser subject) {
    grantRepository.save(
        AssetGrant.forUser(
            target.getId(),
            Organization.DEFAULT_ID,
            subject.id(),
            AssetRole.VIEWER,
            null,
            admin.id()));
    accessService.invalidateLibrary(target.getId());
  }

  private void indexed(KnowledgeLibrary target, String fileName) throws IOException {
    Path file = Path.of(target.getSourcePath()).resolve(fileName);
    Files.createDirectories(file.getParent());
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(50, 700);
        content.showText("Diese Unterlage regelt die Nutzung der IT.");
        content.endText();
      }
      doc.save(file.toFile());
    }
    assertThat(fileProcessingService.processFile(file, target))
        .isEqualTo(FileProcessingResult.PROCESSED);
  }

  private static void deletePdfsIn(Path directory) throws IOException {
    Files.createDirectories(directory);
    try (var files = Files.list(directory)) {
      for (Path file : files.toList()) {
        if (Files.isRegularFile(file)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }
}
