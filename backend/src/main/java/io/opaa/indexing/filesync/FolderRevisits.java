package io.opaa.indexing.filesync;

import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.SourceSyncStateRepository.Revisit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The documents of one library deleted outside a run whose folders no full sync has listed since
 * (ADR-0040): no folder marker in or above one is handed over or remembered. A revisit noted before
 * the run is consumed once a complete full sync listed its container to the end; one noted while
 * the run is under way stays for the next.
 */
final class FolderRevisits {

  /** At most this many ids per delete, well below the bind-parameter limit of the driver. */
  static final int DELETE_BATCH = 1000;

  private final SourceSyncStateRepository repository;
  private final UUID stateId;
  private final List<Revisit> atStart;

  private FolderRevisits(
      SourceSyncStateRepository repository, UUID stateId, List<Revisit> atStart) {
    this.repository = repository;
    this.stateId = stateId;
    this.atStart = atStart;
  }

  /** The revisits noted for the state {@code stateId} so far - read when the run begins. */
  static FolderRevisits load(SourceSyncStateRepository repository, UUID stateId) {
    return new FolderRevisits(repository, stateId, List.copyOf(repository.findRevisits(stateId)));
  }

  /** The hierarchy paths noted for {@code containerKey} before the run, {@code ""} for its root. */
  List<String> atStart(String containerKey) {
    List<String> paths = new ArrayList<>();
    for (Revisit revisit : atStart) {
      if (containerKey.equals(revisit.getContainerKey())) {
        paths.add(pathOf(revisit));
      }
    }
    return paths;
  }

  /** Per container key, the hierarchy paths noted since the run began - read again now. */
  Map<String, Set<String>> notedSinceStart() {
    Set<UUID> known = new HashSet<>();
    atStart.forEach(revisit -> known.add(revisit.getId()));
    Map<String, Set<String>> noted = new LinkedHashMap<>();
    for (Revisit revisit : repository.findRevisits(stateId)) {
      if (!known.contains(revisit.getId())) {
        noted
            .computeIfAbsent(revisit.getContainerKey(), key -> new HashSet<>())
            .add(pathOf(revisit));
      }
    }
    return noted;
  }

  /**
   * Drops the revisits noted before the run for every container in {@code listedToTheEnd}; called
   * only after the memory of the run is saved.
   */
  void consume(Set<String> listedToTheEnd) {
    List<UUID> consumed = new ArrayList<>();
    for (Revisit revisit : atStart) {
      if (listedToTheEnd.contains(revisit.getContainerKey())) {
        consumed.add(revisit.getId());
      }
    }
    for (int from = 0; from < consumed.size(); from += DELETE_BATCH) {
      repository.deleteRevisits(
          consumed.subList(from, Math.min(from + DELETE_BATCH, consumed.size())));
    }
  }

  private static String pathOf(Revisit revisit) {
    return revisit.getHierarchyPath() == null ? "" : revisit.getHierarchyPath();
  }
}
