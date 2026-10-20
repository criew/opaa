package io.opaa.indexing.spi;

import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.RunAbandonedException;
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
 * presence written with it are committed together or not at all, and a state deleted while a run
 * held it is never written again. Every write names the job of the writing run and happens only
 * while that job is {@code RUNNING}, its row locked against the recovery until the write commits: a
 * run another instance took over cannot overwrite the state of the run that followed it (ADR-0053,
 * Entscheidung 8).
 */
public class ScanJournal {

  /** At most this many values per statement, well below the bind-parameter limit of the driver. */
  public static final int BATCH = 1000;

  private static final String RUNNING = JobStatus.RUNNING.name();

  static final String DISCARDED_MESSAGE =
      "Die Quelle der Bibliothek wurde während des Laufs geändert; der Lauf endet ohne Änderung"
          + " am Bestand, der nächste beginnt von vorn.";

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

  /** Saves {@code state} for the run of {@code jobId}; continue with the returned instance. */
  @Transactional
  public SourceSyncState save(UUID jobId, SourceSyncState state) {
    requireHeld(jobId);
    requireRow(state);
    return repository.save(state);
  }

  /**
   * Saves {@code state} and notes the documents at {@code presentPaths} as seen in the round {@code
   * scanId}, in one transaction; continue with the returned instance.
   */
  @Transactional
  public SourceSyncState save(
      UUID jobId,
      SourceSyncState state,
      UUID scanId,
      UUID libraryId,
      Collection<String> presentPaths) {
    requireHeld(jobId);
    requireRow(state);
    SourceSyncState saved = repository.save(state);
    if (!presentPaths.isEmpty()) {
      repository.flush();
      insertPresence(saved.getId(), scanId, libraryId, presentPaths);
    }
    return saved;
  }

  /**
   * Notes the documents at {@code presentPaths} as seen in the round {@code scanId}; nothing when
   * the state is gone, and with it the round.
   */
  @Transactional
  public void recordPresence(
      UUID jobId, UUID stateId, UUID scanId, UUID libraryId, Collection<String> presentPaths) {
    requireHeld(jobId);
    if (repository.lockById(stateId).isPresent()) {
      insertPresence(stateId, scanId, libraryId, presentPaths);
    }
  }

  private void insertPresence(
      UUID stateId, UUID scanId, UUID libraryId, Collection<String> presentPaths) {
    for (List<String> batch : batches(presentPaths)) {
      repository.recordPresence(stateId, scanId, libraryId, batch);
    }
  }

  /** Saves {@code state} after its round ended and drops the presence of every round. */
  @Transactional
  public SourceSyncState saveEnded(UUID jobId, SourceSyncState state) {
    requireHeld(jobId);
    requireRow(state);
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

  /**
   * The job of the writing run is still {@code RUNNING}, its row share-locked until the caller's
   * transaction ends; a job without a row is not this journal's to judge.
   */
  private void requireHeld(UUID jobId) {
    repository
        .lockJobStatus(jobId)
        .filter(status -> !RUNNING.equals(status))
        .ifPresent(
            status -> {
              throw new RunAbandonedException(jobId, "its job is " + status);
            });
  }

  /**
   * A state read from the database still has its row, locked until the caller's transaction ends; a
   * deleted one is not brought back.
   */
  private void requireRow(SourceSyncState state) {
    if (state.isStored() && repository.lockById(state.getId()).isEmpty()) {
      throw new IndexingRunFailedException(DISCARDED_MESSAGE);
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
