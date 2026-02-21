package io.opaa.indexing;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

  void deleteByDocument(Document document);

  int countByDocument(Document document);

  @Query(
      value = "SELECT COUNT(*) FROM document_chunks WHERE id = :id AND embedding IS NOT NULL",
      nativeQuery = true)
  int countByIdWithEmbedding(@Param("id") UUID id);
}
