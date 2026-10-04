package io.opaa.connection.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A library repository double whose rows are the libraries a test puts into {@code rows}. */
public final class LibraryRows {

  private LibraryRows() {}

  public static KnowledgeLibraryRepository over(Map<UUID, KnowledgeLibrary> rows) {
    KnowledgeLibraryRepository repository = mock(KnowledgeLibraryRepository.class);
    when(repository.findById(any()))
        .thenAnswer(call -> Optional.ofNullable(rows.get(call.<UUID>getArgument(0))));
    return repository;
  }
}
