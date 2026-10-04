package io.opaa.architecture.fixture.documentdelete.knowledge;

/** Stands in for the repository: two delete methods and a finder. */
public interface DocumentRepository {
  void delete(Object document);

  long deleteByLibraryId(Object libraryId);

  Object findById(Object id);
}
