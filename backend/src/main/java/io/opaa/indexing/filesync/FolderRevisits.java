package io.opaa.indexing.filesync;

import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceSyncStateRepository.Revisit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The documents of one library deleted outside a run whose folders no full sync has listed since
 * (ADR-0040): no folder marker in or above one is handed over or remembered. A revisit is consumed
 * once a round listed its container from scratch to the end with the revisit already visible at
 * that start; one that became visible later stays for the next round.
 */
final class FolderRevisits {

  private final ScanJournal journal;
  private final UUID stateId;
  private final List<Revisit> atStart;

  private FolderRevisits(ScanJournal journal, UUID stateId, List<Revisit> atStart) {
    this.journal = journal;
    this.stateId = stateId;
    this.atStart = atStart;
  }

  /** The revisits noted for the state {@code stateId} so far - read when the run begins. */
  static FolderRevisits load(ScanJournal journal, UUID stateId) {
    return new FolderRevisits(journal, stateId, journal.revisits(stateId));
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

  /** The ids of the revisits noted for {@code containerKey} before the run. */
  Set<UUID> idsAtStart(String containerKey) {
    Set<UUID> ids = new HashSet<>();
    for (Revisit revisit : atStart) {
      if (containerKey.equals(revisit.getContainerKey())) {
        ids.add(revisit.getId());
      }
    }
    return ids;
  }

  /**
   * Per container key, the hierarchy paths of the revisits noted now - read again - that the
   * listing of their container did not see at its start ({@code seen}).
   */
  Map<String, Set<String>> notedOutside(Map<String, Set<UUID>> seen) {
    Map<String, Set<String>> noted = new LinkedHashMap<>();
    for (Revisit revisit : journal.revisits(stateId)) {
      if (!seen.getOrDefault(revisit.getContainerKey(), Set.of()).contains(revisit.getId())) {
        noted
            .computeIfAbsent(revisit.getContainerKey(), key -> new HashSet<>())
            .add(pathOf(revisit));
      }
    }
    return noted;
  }

  /** Drops the revisits {@code ids}; called only after the memory of the round is saved. */
  void consume(Collection<UUID> ids) {
    journal.consumeRevisits(ids);
  }

  private static String pathOf(Revisit revisit) {
    return revisit.getHierarchyPath() == null ? "" : revisit.getHierarchyPath();
  }
}
