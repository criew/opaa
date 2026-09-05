package io.opaa.indexing.metadata;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentKeywordRepository extends JpaRepository<DocumentKeyword, UUID> {

  List<DocumentKeyword> findByDocumentIdOrderByKeywordAsc(UUID documentId);

  /** Replaces a document's keywords: the previous set of a re-extraction never survives it. */
  @Transactional
  void deleteByDocumentId(UUID documentId);
}
