package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.SourceSyncStateRepository.Revisit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link FolderRevisits} consumes in bounded batches, whatever a large deletion left behind. */
class FolderRevisitsTest {

  @Test
  void manyRevisitsAreConsumedInBatchesOfAtMostTheBatchSize() {
    SourceSyncStateRepository repository = mock(SourceSyncStateRepository.class);
    List<Revisit> noted = new ArrayList<>();
    int count = 2 * FolderRevisits.DELETE_BATCH + 7;
    for (int i = 0; i < count; i++) {
      noted.add(revisit(i % 10 == 0 ? "B" : "A"));
    }
    UUID stateId = UUID.randomUUID();
    when(repository.findRevisits(stateId)).thenReturn(noted);
    List<Integer> batches = new ArrayList<>();
    Set<UUID> deleted = new HashSet<>();
    when(repository.deleteRevisits(any()))
        .thenAnswer(
            invocation -> {
              Collection<UUID> ids = invocation.getArgument(0);
              batches.add(ids.size());
              deleted.addAll(ids);
              return ids.size();
            });

    FolderRevisits.load(repository, stateId).consume(Set.of("A"));

    long expected = noted.stream().filter(r -> r.getContainerKey().equals("A")).count();
    assertThat(batches).hasSize(2).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(1000));
    assertThat(deleted).hasSize((int) expected);
  }

  private static Revisit revisit(String containerKey) {
    UUID id = UUID.randomUUID();
    return new Revisit() {
      @Override
      public UUID getId() {
        return id;
      }

      @Override
      public String getContainerKey() {
        return containerKey;
      }

      @Override
      public String getHierarchyPath() {
        return "akten";
      }
    };
  }
}
