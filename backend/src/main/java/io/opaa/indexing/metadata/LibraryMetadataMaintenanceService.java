package io.opaa.indexing.metadata;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Pflege-Anker per library and core field (#1069, metadata-schema.md "Der Pflege-Anker"): "N
 * Dokumente ohne Wert", absolute and as a share of the library's indexed bestand. Reading it needs
 * no more than the right to read the library ({@link AssetRole#VIEWER}) - whoever knows the bestand
 * should see its gaps, while correcting a value keeps the editing right of #1068.
 *
 * <p>Every figure is counted when asked, over exactly the library the caller may read, and nothing
 * is stored or cached: an aggregate over documents only ever exists in the rights context of the
 * person asking (metadata-schema.md, Rechte-Invariante).
 */
@Service
public class LibraryMetadataMaintenanceService {

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final DocumentRepository documentRepository;
  private final DocumentMetadataValueRepository valueRepository;

  public LibraryMetadataMaintenanceService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      DocumentMetadataValueRepository valueRepository) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.valueRepository = valueRepository;
  }

  /**
   * The anchor of {@code libraryId} for the caller. A library of another organization, or one the
   * caller holds no right on at all, is absent (404) - the number itself would already tell how
   * large a bestand is that this person may not see.
   */
  @Transactional(readOnly = true)
  public LibraryMetadataMaintenance maintenanceOf(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> candidate.getOrganizationId().equals(caller.organizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);

    long total =
        documentRepository.countByLibraryIdAndStatus(library.getId(), DocumentStatus.INDEXED);
    Map<CoreMetadataField, Long> filled = new EnumMap<>(CoreMetadataField.class);
    Map<CoreMetadataField, Long> notDeterminable = new EnumMap<>(CoreMetadataField.class);
    for (DocumentMetadataValueRepository.FieldStateCount count :
        valueRepository.countByFieldAndState(library.getId(), DocumentStatus.INDEXED)) {
      CoreMetadataField.fromKey(count.getFieldKey())
          .ifPresent(
              field ->
                  (count.getState() == MetadataValueState.NOT_DETERMINABLE
                          ? notDeterminable
                          : filled)
                      .merge(field, count.getDocumentCount(), Long::sum));
    }
    List<MetadataFieldMaintenance> fields = new ArrayList<>();
    for (CoreMetadataField field : CoreMetadataField.values()) {
      fields.add(
          new MetadataFieldMaintenance(
              field,
              total,
              filled.getOrDefault(field, 0L),
              notDeterminable.getOrDefault(field, 0L)));
    }
    return new LibraryMetadataMaintenance(library.getId(), total, fields);
  }
}
