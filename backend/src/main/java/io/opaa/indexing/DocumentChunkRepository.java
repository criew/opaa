package io.opaa.indexing;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

  void deleteByDocumentId(UUID documentId);

  int countByDocumentId(UUID documentId);
}
