package io.opaa.indexing.source;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ConflictException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * The one intake behind every push path (Confluence webhooks, S3 event notifications). A connector
 * authenticates and parses, then hands over the keys it wants looked at; from there on the intake
 * is the same for all of them: keys are queued per library and, {@code debounce} later, one {@link
 * JobTriggerSource#WEBHOOK} run checks exactly those keys through {@link
 * SourceEventTarget#refresh}. A batch past {@code maxPendingKeys} runs the executor's ordinary run
 * in its default mode instead. A notification never deletes and never moves any resumption state -
 * it is a hint to look, the source's answer is the finding. While another run is in progress the
 * batch waits up to {@code maxDeferrals} times, then is dropped: the next run covers the same keys,
 * so a drop costs freshness, never correctness.
 */
@Service
public class SourceEventIntake {

  private static final Logger log = LoggerFactory.getLogger(SourceEventIntake.class);

  private final KnowledgeLibraryRepository libraryRepository;
  private final IndexingJobService indexingJobService;
  private final SourceEventProperties properties;
  private final TaskScheduler scheduler;
  private final Clock clock;

  private final Map<UUID, PendingBatch> pending = new HashMap<>();

  public SourceEventIntake(
      KnowledgeLibraryRepository libraryRepository,
      IndexingJobService indexingJobService,
      SourceEventProperties properties,
      @Qualifier("sourceEventScheduler") TaskScheduler scheduler,
      Clock clock) {
    this.libraryRepository = libraryRepository;
    this.indexingJobService = indexingJobService;
    this.properties = properties;
    this.scheduler = scheduler;
    this.clock = clock;
  }

  /**
   * Queues {@code keys} for {@code libraryId} and starts the debounce timer if none is pending.
   * {@code dropped} is carried along to the targeted run. A library has one source type, so every
   * batch of one library comes from the same {@code target} (the adapters filter by source type
   * before enqueueing).
   */
  public synchronized void enqueue(
      SourceEventTarget target, UUID libraryId, Set<String> keys, int dropped) {
    PendingBatch batch = pending.get(libraryId);
    if (batch == null) {
      batch = new PendingBatch(target);
      pending.put(libraryId, batch);
      schedule(libraryId);
    }
    batch.add(keys, dropped, properties.maxPendingKeys());
  }

  private void schedule(UUID libraryId) {
    Instant at = clock.instant().plus(properties.debounce());
    scheduler.schedule(() -> drain(libraryId), at);
  }

  /** Runs on the event scheduler once the debounce elapsed. */
  void drain(UUID libraryId) {
    PendingBatch batch;
    synchronized (this) {
      batch = pending.remove(libraryId);
    }
    if (batch == null) {
      return;
    }
    SourceEventTarget target = batch.target;
    KnowledgeLibrary library;
    try {
      Optional<KnowledgeLibrary> loaded =
          libraryRepository
              .findById(libraryId)
              .filter(l -> l.getSourceType() == target.sourceType())
              .filter(l -> l.getWebhookSecret() != null);
      if (loaded.isEmpty()) {
        log.info(
            "Dropping {} event batch for library {}: library gone or push secret removed",
            target.sourceType(),
            libraryId);
        return;
      }
      library = loaded.get();
      if (indexingJobService.isJobRunning(library.getId(), library.getOrganizationId())) {
        defer(libraryId, batch);
        return;
      }
    } catch (RuntimeException e) {
      // the batch already left the queue; a lookup that fails (database briefly away) must not
      // lose it - it goes back as one more deferral, so a lasting outage still ends at the bound
      log.warn(
          "{} event batch for library {} could not be checked, putting it back: {}",
          target.sourceType(),
          libraryId,
          e.getMessage());
      defer(libraryId, batch);
      return;
    }
    // A targeted run never lists, so it is booked under the connector's non-removing mode. An
    // overflowed batch becomes the executor's ordinary run, in the mode the library's own state
    // calls for at drain time.
    IndexingRunMode runMode =
        batch.overflowed ? target.executor().defaultRunMode(library) : target.targetedRunMode();
    IndexingJob job;
    try {
      job =
          indexingJobService.startJob(
              library.getId(), library.getOrganizationId(), JobTriggerSource.WEBHOOK, runMode);
    } catch (ConflictException e) {
      defer(libraryId, batch);
      return;
    }
    try {
      if (batch.overflowed) {
        target.executor().execute(job.getId(), library, runMode);
      } else {
        target.refresh(job.getId(), library, batch.keys(), batch.dropped);
      }
    } catch (TaskRejectedException e) {
      indexingJobService.failJob(
          job.getId(), "Indizierungslauf abgelehnt: Kapazität derzeit erschöpft");
      log.warn(
          "{} event run for library {} rejected: executor queue full",
          target.sourceType(),
          libraryId);
    }
  }

  private synchronized void defer(UUID libraryId, PendingBatch batch) {
    if (batch.deferrals >= properties.maxDeferrals()) {
      log.info(
          "Dropping {} event batch for library {} after {} deferrals: a run is still in progress,"
              + " the next run covers the reported keys",
          batch.target.sourceType(),
          libraryId,
          batch.deferrals);
      return;
    }
    batch.deferrals++;
    PendingBatch current = pending.get(libraryId);
    if (current == null) {
      pending.put(libraryId, batch);
      schedule(libraryId);
    } else {
      // notifications arrived while this batch was being drained - they wait together
      current.merge(batch, properties.maxPendingKeys());
    }
  }

  /** What is pending for one library. Guarded by the intake's monitor. */
  private static final class PendingBatch {
    private final SourceEventTarget target;
    private final Set<String> keys = new LinkedHashSet<>();
    private int dropped;
    private boolean overflowed;
    private int deferrals;

    PendingBatch(SourceEventTarget target) {
      this.target = target;
    }

    void add(Set<String> reported, int droppedNow, int maxPendingKeys) {
      dropped += droppedNow;
      if (overflowed) {
        return;
      }
      keys.addAll(reported);
      if (keys.size() > maxPendingKeys) {
        overflowed = true;
        keys.clear();
      }
    }

    void merge(PendingBatch other, int maxPendingKeys) {
      // a batch that already waited keeps its count - new notifications do not reset the clock
      deferrals = Math.max(deferrals, other.deferrals);
      if (other.overflowed) {
        overflowed = true;
        keys.clear();
        dropped += other.dropped;
        return;
      }
      add(other.keys, other.dropped, maxPendingKeys);
    }

    Set<String> keys() {
      return Set.copyOf(keys);
    }
  }
}
