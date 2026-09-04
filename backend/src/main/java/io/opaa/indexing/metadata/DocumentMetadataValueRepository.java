package io.opaa.indexing.metadata;

import io.opaa.api.types.DocumentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentMetadataValueRepository
    extends JpaRepository<DocumentMetadataValue, UUID> {

  List<DocumentMetadataValue> findByDocumentId(UUID documentId);

  Optional<DocumentMetadataValue> findByDocumentIdAndFieldKey(UUID documentId, String fieldKey);

  /**
   * Every value of every document in {@code documentIds} - one query for a whole answer's sources.
   */
  List<DocumentMetadataValue> findByDocumentIdIn(Collection<UUID> documentIds);

  /**
   * How many of {@code libraryId}'s documents in {@code status} carry a row per field and state -
   * the counted half of the Pflege-Anker (#1069), computed on every query, never precomputed
   * (metadata-schema.md, Rechte-Invariante). A field/state pair no document holds is simply absent
   * from the result; "leer" is the absence of a row and is derived by the caller from the total.
   */
  @Query(
      "select v.fieldKey as fieldKey, v.state as state, count(v) as documentCount"
          + " from DocumentMetadataValue v, Document d"
          + " where d.id = v.documentId and d.libraryId = :libraryId and d.status = :status"
          + " group by v.fieldKey, v.state")
  List<FieldStateCount> countByFieldAndState(
      @Param("libraryId") UUID libraryId, @Param("status") DocumentStatus status);

  /** One row of {@link #countByFieldAndState}. */
  interface FieldStateCount {
    String getFieldKey();

    MetadataValueState getState();

    long getDocumentCount();
  }
}
