package io.opaa.architecture.fixture.documentdelete.knowledge;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Stands in for the repository: two delete methods, a modifying query that deletes under another
 * name, a modifying update and a finder.
 */
public interface DocumentRepository {
  void delete(Object document);

  long deleteByLibraryId(Object libraryId);

  @Modifying
  @Query("  DELETE FROM Document d WHERE d.libraryId = :libraryId")
  int purgeStale(Object libraryId);

  @Modifying
  @Query("update Document d set d.checksum = null where d.id = :id")
  int markStale(Object id);

  Object findById(Object id);
}
