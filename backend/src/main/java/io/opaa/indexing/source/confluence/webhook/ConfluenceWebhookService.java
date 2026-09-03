package io.opaa.indexing.source.confluence.webhook;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ConflictException;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The intake behind {@code POST /api/v1/libraries/{libraryId}/confluence-webhook} (#1140).
 *
 * <p>Authentication first, uniformly: an unknown library, a library without a secret, a library of
 * another source type and a wrong signature are all answered with the same 401 and a warning in the
 * log - the endpoint is reachable without a session, so it must not tell a caller which of these it
 * hit. Then the page ids the body names are queued per library and, {@code debounce} later, one
 * short-lived {@link JobTriggerSource#WEBHOOK} run fetches exactly those pages ({@link
 * ConfluenceIndexingExecutor#refreshPages}); a batch that grew past {@code maxPendingPages} runs an
 * ordinary run in the mode the library's state calls for instead. A notification never deletes by
 * itself and never moves the incremental anchor - it is a hint to look, the instance's answer is
 * the finding (ADR-0023, Entscheidung 4). While another run of the library is in progress the batch
 * waits, up to {@code maxDeferrals} times, then it is dropped: the next scheduled or incremental
 * run covers the same pages, so a drop costs freshness, never correctness. Replays are not
 * detected: a captured, validly signed notification can be sent again and costs one targeted run
 * each time, bounded by the rate limit - harmless for the index (the fetch is the finding), stated
 * in the documentation.
 */
@Service
public class ConfluenceWebhookService {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceWebhookService.class);

  static final String UNAUTHORIZED_MESSAGE = "Webhook nicht autorisiert";

  private final KnowledgeLibraryRepository libraryRepository;
  private final IndexingJobService indexingJobService;
  private final ConfluenceIndexingExecutor executor;
  private final ConfluenceWebhookProperties properties;
  private final TaskScheduler scheduler;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  private final Map<UUID, PendingBatch> pending = new HashMap<>();

  public ConfluenceWebhookService(
      KnowledgeLibraryRepository libraryRepository,
      IndexingJobService indexingJobService,
      ConfluenceIndexingExecutor executor,
      ConfluenceWebhookProperties properties,
      @Qualifier("confluenceWebhookScheduler") TaskScheduler scheduler,
      JsonMapper jsonMapper,
      Clock clock) {
    this.libraryRepository = libraryRepository;
    this.indexingJobService = indexingJobService;
    this.executor = executor;
    this.properties = properties;
    this.scheduler = scheduler;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  /**
   * Authenticates and queues one notification. Throws {@link UnauthorizedException} (401) when the
   * request does not prove knowledge of the library's secret; returns normally - also for a body
   * that names no page - once it does.
   */
  public void accept(UUID libraryId, byte[] body, String hubSignature, String sharedSecret) {
    byte[] rawBody = body == null ? new byte[0] : body;
    Optional<KnowledgeLibrary> library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getSourceType() == DocumentSourceType.CONFLUENCE);
    String secret = library.map(KnowledgeLibrary::getConfluenceWebhookSecret).orElse(null);
    if (!ConfluenceWebhookSignature.verify(rawBody, hubSignature, sharedSecret, secret)) {
      log.warn("Rejected Confluence webhook for library {}: not authenticated", libraryId);
      throw new UnauthorizedException(UNAUTHORIZED_MESSAGE);
    }
    Set<String> pageIds = ConfluenceWebhookPayload.pageIds(rawBody, jsonMapper);
    if (pageIds.isEmpty()) {
      log.debug("Confluence webhook for library {} named no page - nothing queued", libraryId);
      return;
    }
    enqueue(libraryId, pageIds);
  }

  private synchronized void enqueue(UUID libraryId, Set<String> pageIds) {
    PendingBatch batch = pending.get(libraryId);
    if (batch == null) {
      batch = new PendingBatch();
      pending.put(libraryId, batch);
      schedule(libraryId);
    }
    batch.add(pageIds, properties.maxPendingPages());
  }

  private void schedule(UUID libraryId) {
    Instant at = clock.instant().plus(properties.debounce());
    scheduler.schedule(() -> drain(libraryId), at);
  }

  /** Runs on the webhook scheduler once the debounce elapsed. */
  void drain(UUID libraryId) {
    PendingBatch batch;
    synchronized (this) {
      batch = pending.remove(libraryId);
    }
    if (batch == null) {
      return;
    }
    Optional<KnowledgeLibrary> loaded =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getSourceType() == DocumentSourceType.CONFLUENCE)
            .filter(l -> l.getConfluenceWebhookSecret() != null);
    if (loaded.isEmpty()) {
      log.info("Dropping webhook batch for library {}: library gone or webhook removed", libraryId);
      return;
    }
    KnowledgeLibrary library = loaded.get();
    if (indexingJobService.isJobRunning(library.getId(), library.getOrganizationId())) {
      defer(libraryId, batch);
      return;
    }
    // A targeted refresh never lists, so it is INCREMENTAL by nature (nothing is removed for being
    // absent). An overflowed batch becomes an ordinary run, in the mode the library's own state
    // calls for - FULL while no full sync completed or one is due, INCREMENTAL otherwise.
    IndexingRunMode runMode =
        batch.overflowed ? executor.defaultRunMode(library) : IndexingRunMode.INCREMENTAL;
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
        executor.execute(job.getId(), library, runMode);
      } else {
        executor.refreshPages(job.getId(), library, batch.pageIds());
      }
    } catch (TaskRejectedException e) {
      indexingJobService.failJob(
          job.getId(), "Indizierungslauf abgelehnt: Kapazität derzeit erschöpft");
      log.warn("Webhook run for library {} rejected: executor queue full", libraryId);
    }
  }

  private synchronized void defer(UUID libraryId, PendingBatch batch) {
    if (batch.deferrals >= properties.maxDeferrals()) {
      log.info(
          "Dropping webhook batch for library {} after {} deferrals: a run is still in progress,"
              + " the next run covers the reported pages",
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
      current.merge(batch, properties.maxPendingPages());
    }
  }

  /** What is pending for one library. Guarded by the service's monitor. */
  private static final class PendingBatch {
    private final Set<String> ids = new LinkedHashSet<>();
    private boolean overflowed;
    private int deferrals;

    void add(Set<String> pageIds, int maxPendingPages) {
      if (overflowed) {
        return;
      }
      ids.addAll(pageIds);
      if (ids.size() > maxPendingPages) {
        overflowed = true;
        ids.clear();
      }
    }

    void merge(PendingBatch other, int maxPendingPages) {
      // a batch that already waited keeps its count - new notifications do not reset the clock
      deferrals = Math.max(deferrals, other.deferrals);
      if (other.overflowed) {
        overflowed = true;
        ids.clear();
        return;
      }
      add(other.ids, maxPendingPages);
    }

    Set<String> pageIds() {
      return Set.copyOf(ids);
    }
  }
}
