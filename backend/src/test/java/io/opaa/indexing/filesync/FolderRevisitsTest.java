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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link FolderRevisits} consumes in bounded batches, whatever a large deletion left behind, and
 * names the revisits a container's listing did not see at its start.
 */
class FolderRevisitsTest {

  private final SourceSyncStateRepository repository = mock(SourceSyncStateRepository.class);
  private final UUID stateId = UUID.randomUUID();

  @Test
  void manyRevisitsAreConsumedInBatchesOfAtMostTheBatchSize() {
    List<Revisit> noted = new ArrayList<>();
    int count = 2 * ScanJournal.BATCH + 7;
    for (int i = 0; i < count; i++) {
      noted.add(revisit(i % 10 == 0 ? "B" : "A"));
    }
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

    FolderRevisits revisits = FolderRevisits.load(new ScanJournal(repository), stateId);
    revisits.consume(revisits.idsAtStart("A"));

    long expected = noted.stream().filter(r -> r.getContainerKey().equals("A")).count();
    assertThat(batches).hasSize(2).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(1000));
    assertThat(deleted).hasSize((int) expected);
  }

  @Test
  void aRevisitTheListingOfItsContainerDidNotSeeAtItsStartIsNotedOutside() {
    Revisit seen = revisit("A");
    Revisit later = revisit("A");
    when(repository.findRevisits(stateId)).thenReturn(List.of(seen), List.of(seen, later));
    FolderRevisits revisits = FolderRevisits.load(new ScanJournal(repository), stateId);

    Map<String, Set<String>> outside = revisits.notedOutside(Map.of("A", revisits.idsAtStart("A")));

    assertThat(revisits.idsAtStart("A")).containsExactly(seen.getId());
    assertThat(outside).containsExactly(Map.entry("A", Set.of("akten")));
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
