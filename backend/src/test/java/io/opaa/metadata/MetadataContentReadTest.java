package io.opaa.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetAuthorization;
import io.opaa.asset.OwnerOnlyRule;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.permission.AssetAccessService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Values a model or a person extracted from a document are content, not configuration: the
 * per-document field view and the Stichprobe ask the content check of the asset shell ({@link
 * AssetAuthorization#requireContentRole}), which a system administrator without a grant fails,
 * while the aggregated Extraktionsgüte stays with the administration (handbook berechtigungen.md,
 * section 10).
 */
class MetadataContentReadTest {

  private final AssetAuthorization authorization = mock(AssetAuthorization.class);
  private final LibraryAccessService accessService =
      new LibraryAccessService(
          mock(AssetAccessService.class), authorization, mock(OwnerOnlyRule.class));
  private final KnowledgeLibraryRepository libraryRepository =
      mock(KnowledgeLibraryRepository.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final DocumentMetadataValueRepository valueRepository =
      mock(DocumentMetadataValueRepository.class);
  private final LibraryMetadataFieldRepository fieldRepository =
      mock(LibraryMetadataFieldRepository.class);

  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(Organization.DEFAULT_ID, "Fremd", null, UUID.randomUUID());
  private final UUID libraryId = UUID.randomUUID();
  private final CurrentUser admin =
      CurrentUser.of(
          UUID.randomUUID(), Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Verwaltung");

  @BeforeEach
  void administrationReachesTheLibraryButTheFormulaGrantsNothing() {
    when(libraryRepository.findByIdAndOrganizationId(libraryId, Organization.DEFAULT_ID))
        .thenReturn(Optional.of(library));
    when(libraryRepository.requireInOrganization(libraryId, Organization.DEFAULT_ID))
        .thenReturn(library);
    when(authorization.requireRole(eq(library), eq(admin.id()), anyBoolean(), any()))
        .thenReturn(AssetRole.OWNER);
    when(authorization.requireContentRole(eq(library), eq(admin.id()), anyBoolean(), any()))
        .thenThrow(new AccessDeniedException("Verwaltungsrechte genügen dafür nicht."));
  }

  @Test
  void theSampleOfAForeignLibraryIsRefusedToTheAdministration() {
    LibraryMetadataExtractionService service = extractionService();

    assertThatThrownBy(() -> service.sampleOf(libraryId, 100, admin))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(documentRepository, valueRepository);
  }

  @Test
  void theFieldValuesOfAForeignDocumentAreRefusedToTheAdministration() {
    DocumentMetadataCorrectionService service =
        new DocumentMetadataCorrectionService(
            libraryRepository,
            accessService,
            documentRepository,
            mock(DocumentMetadataService.class),
            mock(DocumentTypeVocabularyRepository.class),
            fieldRepository,
            mock(LibraryMetadataFieldValueRepository.class),
            mock(LibraryMetadataSchemaChangeRepository.class),
            mock(UserRepository.class),
            mock(AuditEventRecorder.class));

    assertThatThrownBy(() -> service.fieldsOf(libraryId, UUID.randomUUID(), admin))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(documentRepository);
  }

  @Test
  void theAggregatedQualityStaysWithTheAdministration() {
    when(valueRepository.countByFieldOriginAndState(any(), any())).thenReturn(List.of());
    when(fieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(any()))
        .thenReturn(List.of());

    LibraryMetadataQuality quality = extractionService().qualityOf(libraryId, admin);

    assertThat(quality.fields()).isNotEmpty();
  }

  private LibraryMetadataExtractionService extractionService() {
    return new LibraryMetadataExtractionService(
        libraryRepository,
        accessService,
        fieldRepository,
        valueRepository,
        documentRepository,
        mock(DocumentTypeVocabularyRepository.class),
        mock(ModelExtractionCounters.class),
        mock(ActiveChatModelResolver.class));
  }
}
