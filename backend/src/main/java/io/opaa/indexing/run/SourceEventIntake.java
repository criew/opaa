package io.opaa.indexing.run;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ConflictException;
import io.opaa.indexing.credentials.SourceConnectionResolver;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.JobTriggerSource;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The one intake behind every push path (Confluence webhooks, S3 event notifications). A connector
 * authenticates and parses, then hands over the keys it wants looked at; from there on the intake
 * is the same for all of them: keys are collected per library in {@link SourceEventBatches} and,
 * {@code debounce} after the first, one {@link JobTriggerSource#WEBHOOK} run checks exactly those
 * keys through {@link SourceEventTarget#refresh}. A batch past {@code maxPendingKeys} runs the
 * executor's ordinary run in its default mode instead. A notification never deletes and never moves
 * any resumption state - it is a hint to look, the source's answer is the finding. While another
 * run is in progress the batch waits up to {@code maxDeferrals} times, then is dropped: the next
 * run covers the same keys, so a drop costs freshness, never correctness.
 *
 * <p>The batches live in the database (ADR-0053, Entscheidung 10): every instance drains due
 * batches of the source types whose connectors registered here, every {@link #POLL_MILLIS} ms on
 * {@code sourceEventScheduler} (ADR-0021: one drain thread per process); a claim makes exactly one
 * instance take a batch, and no key is lost to a change of instance or a restart.
 */
@Service
public class SourceEventIntake {

  static final long POLL_MILLIS = 1000;

  /** Longer than one drain takes - a few lookups and handing the run to the executor. */
  static final Duration LEASE = Duration.ofMinutes(2);

  private static final Logger log = LoggerFactory.getLogger(SourceEventIntake.class);

  private final KnowledgeLibraryRepository libraryRepository;
  private final IndexingJobService indexingJobService;
  private final SourceEventProperties properties;
  private final SourceEventBatches batches;
  private final SourceConnectionResolver connectionResolver;
  private final DefaultRunModes defaultRunModes;
  private final Duration lease;
  private final Map<SourceType, SourceEventTarget> targets = new ConcurrentHashMap<>();
  private volatile boolean stopping;

  @Autowired
  SourceEventIntake(
      KnowledgeLibraryRepository libraryRepository,
      IndexingJobService indexingJobService,
      SourceEventProperties properties,
      SourceEventBatches batches,
      SourceConnectionResolver connectionResolver,
      DefaultRunModes defaultRunModes) {
    this(
        libraryRepository,
        indexingJobService,
        properties,
        batches,
        connectionResolver,
        defaultRunModes,
        LEASE);
  }

  SourceEventIntake(
      KnowledgeLibraryRepository libraryRepository,
      IndexingJobService indexingJobService,
      SourceEventProperties properties,
      SourceEventBatches batches,
      SourceConnectionResolver connectionResolver,
      DefaultRunModes defaultRunModes,
      Duration lease) {
    this.libraryRepository = libraryRepository;
    this.indexingJobService = indexingJobService;
    this.properties = properties;
    this.batches = batches;
    this.connectionResolver = connectionResolver;
    this.defaultRunModes = defaultRunModes;
    this.lease = lease;
  }

  /**
   * Makes this instance drain the batches of {@code target}'s source type, also those another
   * instance collected. A connector registers its target when it is created.
   */
  public void register(SourceEventTarget target) {
    targets.put(target.sourceType(), target);
  }

  /**
   * Adds {@code keys} for {@code libraryId} to its open batch, opening one - due after the debounce
   * - if there is none. {@code dropped} is carried along to the targeted run. A library has one
   * source type, so every batch of one library comes from the same {@code target} (the adapters
   * filter by source type before enqueueing).
   */
  public void enqueue(SourceEventTarget target, UUID libraryId, Set<String> keys, int dropped) {
    register(target);
    batches.add(
        libraryId,
        target.sourceType(),
        keys,
        dropped,
        properties.maxPendingKeys(),
        properties.debounce());
  }

  /** Drains every due batch this instance has a target for, one claim at a time. */
  @Scheduled(
      fixedDelay = POLL_MILLIS,
      initialDelay = POLL_MILLIS,
      scheduler = "sourceEventScheduler")
  public void drainDue() {
    try {
      Optional<SourceEventBatches.Claimed> claimed;
      while (!stopping && (claimed = batches.claimDue(targets.keySet(), lease)).isPresent()) {
        drain(claimed.get());
      }
    } catch (RuntimeException e) {
      log.warn(
          "Draining the event batches stopped with {}; a claimed batch is due again after its"
              + " lease",
          e.getClass().getSimpleName());
    }
  }

  private void drain(SourceEventBatches.Claimed batch) {
    UUID libraryId = batch.libraryId();
    SourceEventTarget target = targets.get(batch.sourceType());
    KnowledgeLibrary library;
    boolean running;
    try {
      Optional<KnowledgeLibrary> loaded =
          libraryRepository
              .findById(libraryId)
              .filter(l -> target.sourceType().equals(l.getSourceType()))
              .filter(l -> l.getWebhookSecret() != null);
      if (loaded.isEmpty()) {
        log.info(
            "Dropping {} event batch for library {}: library gone or push secret removed",
            target.sourceType(),
            libraryId);
        batches.remove(batch.id());
        return;
      }
      library = loaded.get();
      if (library.isErasureRequested()) {
        log.info(
            "Dropping {} event batch for library {}: it is being erased",
            target.sourceType(),
            libraryId);
        batches.remove(batch.id());
        return;
      }
      if (connectionResolver.isLocked(library)) {
        log.info(
            "Dropping {} event batch for library {}: its source is locked",
            target.sourceType(),
            libraryId);
        batches.remove(batch.id());
        return;
      }
      running = indexingJobService.isJobRunning(library.getId(), library.getOrganizationId());
    } catch (RuntimeException e) {
      // a lookup that fails (database briefly away) must not lose the batch - it goes back as one
      // more deferral, so a lasting outage still ends at the bound; failing that, its lease ends
      log.warn(
          "{} event batch for library {} could not be checked, putting it back: {}",
          target.sourceType(),
          libraryId,
          e.getMessage());
      defer(target, batch);
      return;
    }
    // outside the try: a deferral must never be caught and repeated for the same batch
    if (running) {
      defer(target, batch);
      return;
    }
    start(target, batch, library);
  }

  /**
   * A targeted run never lists, so it is booked under the connector's non-removing mode. An
   * overflowed batch becomes the executor's ordinary run, in the mode the library's own state calls
   * for at drain time. The batch is consumed once the run is handed over.
   */
  private void start(
      SourceEventTarget target, SourceEventBatches.Claimed batch, KnowledgeLibrary library) {
    IndexingRunMode runMode =
        batch.overflowed()
            ? defaultRunModes.of(target.executor(), library)
            : target.targetedRunMode();
    IndexingJob job;
    try {
      job =
          indexingJobService.startJob(
              library.getId(), library.getOrganizationId(), JobTriggerSource.WEBHOOK, runMode);
    } catch (ConflictException e) {
      defer(target, batch);
      return;
    }
    try {
      if (batch.overflowed()) {
        target.executor().execute(job.getId(), library, runMode);
      } else {
        target.refresh(job.getId(), library, batch.keys(), batch.dropped());
      }
    } catch (TaskRejectedException e) {
      indexingJobService.failJob(
          job.getId(), "Indizierungslauf abgelehnt: Kapazität derzeit erschöpft");
      log.warn(
          "{} event run for library {} rejected: executor queue full",
          target.sourceType(),
          library.getId());
    }
    batches.remove(batch.id());
  }

  private void defer(SourceEventTarget target, SourceEventBatches.Claimed batch) {
    if (batch.deferrals() >= properties.maxDeferrals()) {
      log.info(
          "Dropping {} event batch for library {} after {} deferrals: a run is still in progress,"
              + " the next run covers the reported keys",
          target.sourceType(),
          batch.libraryId(),
          batch.deferrals());
      batches.remove(batch.id());
      return;
    }
    try {
      batches.defer(batch, properties.maxPendingKeys(), properties.debounce());
    } catch (RuntimeException e) {
      log.warn(
          "{} event batch for library {} could not be put back ({}); it is due again after its"
              + " lease",
          target.sourceType(),
          batch.libraryId(),
          e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  void stop() {
    stopping = true;
  }
}
