package io.opaa.indexing.metadata;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentMetadataValueRepository
    extends JpaRepository<DocumentMetadataValue, UUID> {

  List<DocumentMetadataValue> findByDocumentId(UUID documentId);

  /**
   * Every value of every document in {@code documentIds} - one query for a whole answer's sources.
   */
  List<DocumentMetadataValue> findByDocumentIdIn(Collection<UUID> documentIds);
}
