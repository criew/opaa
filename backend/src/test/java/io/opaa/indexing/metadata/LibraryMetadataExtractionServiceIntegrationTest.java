package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The operator's side of the model-backed extraction (#1073): both switches start off and only a
 * Verwaltungsberechtigter moves them, the Extraktionsgüte separates deterministic from derived and
 * manual values, and the Stichprobe is deterministic and holds no Schlagwort.
 */
@OpaaIntegrationTest
class LibraryMetadataExtractionServiceIntegrationTest {

  @Autowired private LibraryMetadataExtractionService extractionService;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private DocumentKeywordRepository keywordRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary library;
  private CurrentUser owner;
  private CurrentUser viewer;

  @BeforeEach
  void setUp() {
    removeOwnRows();
    owner = user("owner");
    viewer = user("viewer");
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Güte",
                null,
                owner.id(),
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.UPLOAD,
                null,
                null,
                null,
                null,
                false));
    grantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            Organization.DEFAULT_ID,
            owner.id(),
            AssetRole.OWNER,
            null,
            owner.id()));
    grantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            Organization.DEFAULT_ID,
            viewer.id(),
            AssetRole.VIEWER,
            null,
            owner.id()));
    accessService.invalidateLibrary(library.getId());
  }

  @Test
  void bothSwitchesStartOffAndOnlyAManagerMovesThem() {
    LibraryExtractionSettings settings = extractionService.settingsOf(library.getId(), owner);
    assertThat(settings.modelExtractionEnabled()).isFalse();
    assertThat(settings.keywordsEnabled()).isFalse();
    assertThat(settings.confidenceThreshold())
        .isEqualTo(ModelMetadataExtractor.CONFIDENCE_THRESHOLD);

    assertThatThrownBy(() -> extractionService.updateSettings(library.getId(), true, true, viewer))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> extractionService.settingsOf(library.getId(), viewer))
        .isInstanceOf(AccessDeniedException.class);

    LibraryExtractionSettings updated =
        extractionService.updateSettings(library.getId(), true, true, owner);
    assertThat(updated.modelExtractionEnabled()).isTrue();
    assertThat(updated.keywordsEnabled()).isTrue();
    assertThat(libraryRepository.findById(library.getId()).orElseThrow().isModelExtractionEnabled())
        .isTrue();
  }

  @Test
  void theQualitySeparatesDeterministicDerivedManualAndEmpty() {
    Document deterministic = indexed("guete-1.pdf");
    Document derived = indexed("guete-2.pdf");
    Document manual = indexed("guete-3.pdf");
    indexed("guete-4.pdf");
    valueRepository.save(
        DocumentMetadataValue.deterministic(
                deterministic.getId(), CoreMetadataField.DOCUMENT_TYPE, 1)
            .assignVocabularyCode("VERMERK"));
    valueRepository.save(
        DocumentMetadataValue.derived(
                derived.getId(), CoreMetadataField.DOCUMENT_TYPE, "test-model", 0.9, 1)
            .assignVocabularyCode("SATZUNG_ORDNUNG"));
    valueRepository.save(
        DocumentMetadataValue.manual(manual.getId(), CoreMetadataField.DOCUMENT_TYPE, owner.id())
            .assignVocabularyCode("PROTOKOLL"));

    // Readable with VIEWER: whoever knows the bestand may see how well it is described.
    LibraryMetadataQuality quality = extractionService.qualityOf(library.getId(), viewer);

    assertThat(quality.totalDocuments()).isEqualTo(4);
    MetadataFieldQuality documentType =
        quality.fields().stream()
            .filter(field -> field.fieldKey().equals(CoreMetadataField.DOCUMENT_TYPE.key()))
            .findFirst()
            .orElseThrow();
    assertThat(documentType.deterministicDocuments()).isEqualTo(1);
    assertThat(documentType.derivedDocuments()).isEqualTo(1);
    assertThat(documentType.manualDocuments()).isEqualTo(1);
    assertThat(documentType.emptyDocuments()).isEqualTo(1);
    assertThat(documentType.derivedShare()).isEqualTo(0.25);
    assertThat(documentType.emptyShare()).isEqualTo(0.25);
    assertThat(quality.modelExtraction().calls()).isZero();
  }

  @Test
  void aForeignLibraryIsAbsentRatherThanForbidden() {
    CurrentUser stranger = user("fremd");

    assertThatThrownBy(() -> extractionService.qualityOf(UUID.randomUUID(), stranger))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theSampleIsOrderedByDocumentIdCarriesTheProvenanceAndNoKeyword() {
    Document first = indexed("guete-a.pdf");
    Document second = indexed("guete-b.pdf");
    valueRepository.save(
        DocumentMetadataValue.derived(
                first.getId(), CoreMetadataField.DOCUMENT_TYPE, "test-model", 0.91, 1)
            .assignVocabularyCode("VERMERK"));
    keywordRepository.save(
        new DocumentKeyword(first.getId(), library.getId(), "Radverkehr", "test-model", 1));

    LibraryMetadataSample sample = extractionService.sampleOf(library.getId(), 100, owner);

    assertThat(sample.size()).isEqualTo(100);
    assertThat(sample.documents())
        .extracting(MetadataSampleDocument::documentId)
        .containsExactlyInAnyOrder(first.getId(), second.getId());
    // Deterministic: a repeat run of the Handauswertung examines the same documents in the same
    // order rather than a fresh random sample.
    assertThat(extractionService.sampleOf(library.getId(), 100, owner).documents())
        .extracting(MetadataSampleDocument::documentId)
        .containsExactlyElementsOf(
            sample.documents().stream().map(MetadataSampleDocument::documentId).toList());
    MetadataSampleValue value =
        sample.documents().stream()
            .filter(document -> document.documentId().equals(first.getId()))
            .findFirst()
            .orElseThrow()
            .values()
            .getFirst();
    assertThat(value.fieldKey()).isEqualTo(CoreMetadataField.DOCUMENT_TYPE.key());
    assertThat(value.label()).isEqualTo("Dokumentart");
    assertThat(value.value()).isEqualTo("Vermerk");
    assertThat(value.origin()).isEqualTo(MetadataOrigin.DERIVED);
    assertThat(value.confidence()).isEqualTo(0.91);
    assertThat(value.modelId()).isEqualTo("test-model");
    assertThat(sample.documents())
        .flatExtracting(MetadataSampleDocument::values)
        .noneMatch(entry -> "Radverkehr".equals(entry.value()));
    // The export is a bulk read of titles and values, so it stays at the management right.
    assertThatThrownBy(() -> extractionService.sampleOf(library.getId(), 100, viewer))
        .isInstanceOf(AccessDeniedException.class);
  }

  @AfterEach
  void tearDown() {
    // Also afterwards: this class shares its database with every other class of this signature, and
    // a leftover library blocks their libraryRepository.deleteAll() through documents.library_id.
    removeOwnRows();
  }

  private void removeOwnRows() {
    jdbcTemplate.update("DELETE FROM document_keywords WHERE model_id = 'test-model'");
    jdbcTemplate.update("DELETE FROM documents WHERE file_name LIKE 'guete-%'");
    jdbcTemplate.update(
        "DELETE FROM asset_grants WHERE granted_by_user_id IN (SELECT id FROM"
            + " users WHERE email LIKE 'metadata-quality-%')");
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE name LIKE 'Güte%'");
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'metadata-quality-%'");
  }

  private Document indexed(String fileName) {
    Document document =
        new Document(
            fileName,
            "/uploads/" + UUID.randomUUID() + "/" + fileName,
            "application/pdf",
            1L,
            DocumentSourceType.UPLOAD);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setStatus(DocumentStatus.INDEXED);
    return documentRepository.save(document);
  }

  private CurrentUser user(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "metadata-quality-" + id,
        "metadata-quality-" + name + "-" + id + "@example.com",
        "Güte " + name,
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, SystemRole.USER, "Güte " + name);
  }
}
