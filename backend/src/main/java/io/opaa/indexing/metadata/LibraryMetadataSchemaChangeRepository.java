package io.opaa.indexing.metadata;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryMetadataSchemaChangeRepository
    extends JpaRepository<LibraryMetadataSchemaChange, UUID> {

  /** The running changes of one library, oldest first - the order a Charge works them off in. */
  @Query(
      "select c from LibraryMetadataSchemaChange c, LibraryMetadataField f"
          + " where f.id = c.fieldId and f.libraryId = :libraryId"
          + " order by c.requestedAt, c.id")
  List<LibraryMetadataSchemaChange> findByLibraryId(@Param("libraryId") UUID libraryId);

  List<LibraryMetadataSchemaChange> findByFieldId(UUID fieldId);

  List<LibraryMetadataSchemaChange> findByFieldIdIn(Collection<UUID> fieldIds);

  Optional<LibraryMetadataSchemaChange> findByValueId(UUID valueId);

  /** Whether a running mapping writes onto {@code valueId} - that value may not be retired. */
  boolean existsByTargetValueId(UUID valueId);
}
