package io.opaa.indexing.metadata;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTypeVocabularyRepository
    extends JpaRepository<DocumentTypeVocabularyEntry, String> {

  List<DocumentTypeVocabularyEntry> findAllByOrderBySortOrderAsc();

  /** The whole vocabulary as one immutable snapshot for a single extraction pass. */
  default DocumentTypeVocabulary snapshot() {
    return DocumentTypeVocabulary.of(findAllByOrderBySortOrderAsc());
  }
}
