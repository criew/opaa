package io.opaa.architecture.fixture.documentdelete.library;

import io.opaa.architecture.fixture.documentdelete.knowledge.DocumentRepository;
import java.util.function.ToLongFunction;

/** Deletes past the known places, directly and by method reference; reading is no finding. */
public class FolderSweep {
  ToLongFunction<Object> sweep(DocumentRepository repository, Object document) {
    repository.findById(document);
    repository.delete(document);
    return repository::deleteByLibraryId;
  }
}
