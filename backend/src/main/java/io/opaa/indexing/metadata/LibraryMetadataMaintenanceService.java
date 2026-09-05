package io.opaa.indexing.metadata;

import io.opaa.api.types.AssetRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Pflege-Anker per library and field (#1069, metadata-schema.md "Der Pflege-Anker"): "N
 * Dokumente ohne Wert", absolute and as a share of the library's indexed bestand, for the three
 * core fields and for the library's own fields (#1071) alike. Reading it needs no more than the
 * right to read the library ({@link AssetRole#VIEWER}) - whoever knows the bestand should see its
 * gaps, while correcting a value keeps the editing right of #1068.
 *
 * <p>Every figure is counted when asked through {@link MetadataFillCounter}, over exactly the
 * library the caller may read, and nothing is stored or cached: an aggregate over documents only
 * ever exists in the rights context of the person asking (metadata-schema.md, Rechte-Invariante).
 */
@Service
public class LibraryMetadataMaintenanceService {

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final LibraryMetadataFieldRepository fieldRepository;
  private final MetadataFillCounter fillCounter;

  public LibraryMetadataMaintenanceService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      LibraryMetadataFieldRepository fieldRepository,
      MetadataFillCounter fillCounter) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.fieldRepository = fieldRepository;
    this.fillCounter = fillCounter;
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

    Map<String, String> labelsByKey = new LinkedHashMap<>();
    for (CoreMetadataField field : CoreMetadataField.values()) {
      labelsByKey.put(field.key(), field.label());
    }
    for (LibraryMetadataField field :
        fieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(library.getId())) {
      labelsByKey.put(field.documentFieldKey(), field.getLabel());
    }
    Map<String, MetadataFieldFill> fills =
        fillCounter.countFor(library.getId(), List.copyOf(labelsByKey.keySet()));

    long total =
        fills.values().stream().findFirst().map(MetadataFieldFill::totalDocuments).orElse(0L);
    List<MetadataFieldMaintenance> fields = new ArrayList<>();
    for (Map.Entry<String, String> entry : labelsByKey.entrySet()) {
      MetadataFieldFill fill = fills.getOrDefault(entry.getKey(), MetadataFieldFill.EMPTY);
      fields.add(new MetadataFieldMaintenance(entry.getKey(), entry.getValue(), fill));
    }
    return new LibraryMetadataMaintenance(library.getId(), total, fields);
  }
}
