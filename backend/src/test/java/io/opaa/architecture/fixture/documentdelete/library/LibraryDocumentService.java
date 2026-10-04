package io.opaa.architecture.fixture.documentdelete.library;

import io.opaa.architecture.fixture.documentdelete.knowledge.DocumentRepository;

/** A known deleter, also from a class nested in it. */
public class LibraryDocumentService {
  void delete(DocumentRepository repository, Object document) {
    repository.delete(document);
  }

  static class Batch {
    void delete(DocumentRepository repository, Object document) {
      repository.delete(document);
    }
  }
}
