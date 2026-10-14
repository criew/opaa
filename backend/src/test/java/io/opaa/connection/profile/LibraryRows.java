package io.opaa.connection.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
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
    KnowledgeLibraryRepository repository =
        mock(KnowledgeLibraryRepository.class, CALLS_REAL_METHODS);
    when(repository.findById(any()))
        .thenAnswer(call -> Optional.ofNullable(rows.get(call.<UUID>getArgument(0))));
    when(repository.findByIdAndOrganizationId(any(), any()))
        .thenAnswer(
            call ->
                Optional.ofNullable(rows.get(call.<UUID>getArgument(0)))
                    .filter(found -> found.getOrganizationId().equals(call.getArgument(1))));
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
    when(repository.findStoredSourceCredentials(any()))
        .thenAnswer(
            call ->
                Optional.ofNullable(rows.get(call.<UUID>getArgument(0)))
                    .map(KnowledgeLibrary::getSourceCredentials)
                    .orElse(null));
    when(repository.hasStoredSourceCredentials(any()))
        .thenAnswer(
            call ->
                Optional.ofNullable(rows.get(call.<UUID>getArgument(0)))
                    .map(KnowledgeLibrary::getSourceCredentials)
                    .isPresent());
    return repository;
  }
}
