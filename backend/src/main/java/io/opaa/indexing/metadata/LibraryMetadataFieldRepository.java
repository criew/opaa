package io.opaa.indexing.metadata;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryMetadataFieldRepository extends JpaRepository<LibraryMetadataField, UUID> {

  List<LibraryMetadataField> findByLibraryIdOrderBySortOrderAscFieldKeyAsc(UUID libraryId);

  /** The fields of a whole search scope in one query, for the filter options (#1070/#1071). */
  List<LibraryMetadataField> findByLibraryIdInOrderBySortOrderAscFieldKeyAsc(
      Collection<UUID> libraryIds);

  Optional<LibraryMetadataField> findByLibraryIdAndFieldKey(UUID libraryId, String fieldKey);

  long countByLibraryId(UUID libraryId);
}
