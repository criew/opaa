package io.opaa.indexing.source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a file sync keeps what outlasts its run: the sync state with the round's progress, the
 * presence the round has seen and the revisits of documents deleted by hand. A state and the
 * presence written with it are committed together or not at all.
 */
public class ScanJournal {

  /** At most this many values per statement, well below the bind-parameter limit of the driver. */
  public static final int BATCH = 1000;

  private final SourceSyncStateRepository repository;

  public ScanJournal(SourceSyncStateRepository repository) {
    this.repository = repository;
  }

  /** The state of {@code libraryId}, a fresh unsaved one when there is none yet. */
  public SourceSyncState load(UUID libraryId) {
    return repository.findByLibraryId(libraryId).orElseGet(() -> new SourceSyncState(libraryId));
  }

  /** The state of {@code libraryId}, empty when there is none yet. */
  public Optional<SourceSyncState> find(UUID libraryId) {
    return repository.findByLibraryId(libraryId);
  }

  /** Saves {@code state}; continue with the returned instance. */
  @Transactional
  public SourceSyncState save(SourceSyncState state) {
    return repository.save(state);
  }

  /**
   * Saves {@code state} and notes the documents at {@code presentPaths} as seen in the round {@code
   * scanId}, in one transaction; continue with the returned instance.
   */
  @Transactional
  public SourceSyncState save(
      SourceSyncState state, UUID scanId, UUID libraryId, Collection<String> presentPaths) {
    SourceSyncState saved = repository.save(state);
    if (!presentPaths.isEmpty()) {
      repository.flush();
      recordPresence(saved.getId(), scanId, libraryId, presentPaths);
    }
    return saved;
  }

  /** Notes the documents at {@code presentPaths} as seen in the round {@code scanId}. */
  @Transactional
  public void recordPresence(
      UUID stateId, UUID scanId, UUID libraryId, Collection<String> presentPaths) {
    for (List<String> batch : batches(presentPaths)) {
      repository.recordPresence(stateId, scanId, libraryId, batch);
    }
  }

  /** Saves {@code state} after its round ended and drops the presence of every round. */
  @Transactional
  public SourceSyncState saveEnded(SourceSyncState state) {
    SourceSyncState saved = repository.save(state);
    repository.flush();
    repository.clearPresence(saved.getId());
    return saved;
  }

  /** The {@code file_path} of every document the round {@code scanId} has seen. */
  public List<String> presentPaths(UUID stateId, UUID scanId) {
    return repository.findPresentPaths(stateId, scanId);
  }

  /**
   * Per container key, the hierarchy paths of the top-level documents of {@code libraryId} the
   * round {@code scanId} has not seen, {@code ""} for a container's root.
   */
  public Map<String, Set<String>> unseen(UUID stateId, UUID scanId, UUID libraryId) {
    Map<String, Set<String>> unseen = new LinkedHashMap<>();
    for (SourceSyncStateRepository.Place place :
        repository.findUnseen(libraryId, stateId, scanId)) {
      unseen
          .computeIfAbsent(place.getContainerKey(), key -> new HashSet<>())
          .add(place.getHierarchyPath() == null ? "" : place.getHierarchyPath());
    }
    return unseen;
  }

  /** Every revisit noted for the state {@code stateId}. */
  public List<SourceSyncStateRepository.Revisit> revisits(UUID stateId) {
    return List.copyOf(repository.findRevisits(stateId));
  }

  /** Drops the revisits {@code ids}, in batches. */
  public void consumeRevisits(Collection<UUID> ids) {
    for (List<UUID> batch : batches(ids)) {
      repository.deleteRevisits(batch);
    }
  }

  private static <T> List<List<T>> batches(Collection<T> values) {
    List<T> all = new ArrayList<>(values);
    List<List<T>> batches = new ArrayList<>();
    for (int from = 0; from < all.size(); from += BATCH) {
      batches.add(all.subList(from, Math.min(from + BATCH, all.size())));
    }
    return batches;
  }
}
