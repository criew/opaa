package io.opaa.architecture.fixture.documentdelete.library;

import io.opaa.architecture.fixture.documentdelete.knowledge.DocumentRepository;
import java.util.function.ToLongFunction;

/**
 * Deletes past the known places, directly, by method reference and through a modifying query;
 * reading and updating are no finding.
 */
public class FolderSweep {
  ToLongFunction<Object> sweep(DocumentRepository repository, Object document) {
    repository.findById(document);
    repository.markStale(document);
    repository.delete(document);
    repository.purgeStale(document);
    return repository::deleteByLibraryId;
  }
}
