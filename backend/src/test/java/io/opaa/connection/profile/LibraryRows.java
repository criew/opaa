package io.opaa.connection.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** A library repository double whose rows are the libraries a test puts into {@code rows}. */
public final class LibraryRows {

  private LibraryRows() {}

  public static KnowledgeLibraryRepository over(Map<UUID, KnowledgeLibrary> rows) {
    KnowledgeLibraryRepository repository = mock(KnowledgeLibraryRepository.class);
    when(repository.findById(any()))
        .thenAnswer(call -> Optional.ofNullable(rows.get(call.<UUID>getArgument(0))));
    when(repository.findAllById(any()))
        .thenAnswer(
            call -> {
              Iterable<UUID> ids = call.getArgument(0);
              List<KnowledgeLibrary> found = new ArrayList<>();
              ids.forEach(id -> Optional.ofNullable(rows.get(id)).ifPresent(found::add));
              return found;
            });
    when(repository.findIdsHoldingSourceCredentials(any()))
        .thenAnswer(
            call ->
                call.<Collection<UUID>>getArgument(0).stream()
                    .filter(id -> rows.containsKey(id))
                    .filter(id -> rows.get(id).getSourceCredentials() != null)
                    .collect(Collectors.toSet()));
    return repository;
  }
}
