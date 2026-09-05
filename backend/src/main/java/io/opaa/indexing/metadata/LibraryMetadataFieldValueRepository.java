package io.opaa.indexing.metadata;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryMetadataFieldValueRepository
    extends JpaRepository<LibraryMetadataFieldValue, UUID> {

  List<LibraryMetadataFieldValue> findByFieldIdOrderBySortOrderAscCodeAsc(UUID fieldId);

  List<LibraryMetadataFieldValue> findByFieldIdInOrderBySortOrderAscCodeAsc(
      Collection<UUID> fieldIds);

  Optional<LibraryMetadataFieldValue> findByFieldIdAndCode(UUID fieldId, String code);

  void deleteByFieldId(UUID fieldId);
}
