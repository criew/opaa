package io.opaa.indexing.source.s3.events;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.ConflictException;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.indexing.source.s3.S3IndexingExecutor;
import io.opaa.indexing.source.s3.S3KeyPatterns;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
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
 * The intake behind {@code POST /api/v1/libraries/{libraryId}/s3-events} (ADR-0027, Entscheidung
 * 6). Authentication is uniform: an unknown library, a missing token, another source type and a
 * wrong token all answer 401, since the endpoint is reachable without a session and must not say
 * which it hit.
 *
 * <p>Reported keys are then queued per library as {@code bucket/key} and, {@code debounce} later,
 * one {@link IndexingRunMode#EVENT} run with trigger {@link JobTriggerSource#WEBHOOK} checks
 * exactly those objects; a batch past {@code maxPendingKeys} runs an ordinary full sync instead. A
 * notification never deletes by itself and never touches the resumption state - it is a hint to
 * look, the store's answer is the finding. An event for a bucket or key outside the library's
 * scopes or patterns is dropped and counted. While another run is in progress the batch waits up to
 * {@code maxDeferrals} times, then is dropped: the next run covers the same keys.
 */
@Service
public class S3EventService {

  private static final Logger log = LoggerFactory.getLogger(S3EventService.class);

  static final String UNAUTHORIZED_MESSAGE = "Ereignisbenachrichtigung nicht autorisiert";

  private final KnowledgeLibraryRepository libraryRepository;
  private final IndexingJobService indexingJobService;
  private final S3IndexingExecutor executor;
  private final S3EventProperties properties;
  private final TaskScheduler scheduler;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  private final Map<UUID, PendingBatch> pending = new HashMap<>();

  public S3EventService(
      KnowledgeLibraryRepository libraryRepository,
      IndexingJobService indexingJobService,
      S3IndexingExecutor executor,
      S3EventProperties properties,
      @Qualifier("s3EventScheduler") TaskScheduler scheduler,
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
   * request does not prove knowledge of the library's token; returns normally - also for the set-up
   * test message and for a body that names no object - once it does.
   */
  public void accept(UUID libraryId, byte[] body, String authorization, String sharedSecret) {
    byte[] rawBody = body == null ? new byte[0] : body;
    Optional<KnowledgeLibrary> library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getSourceType() == DocumentSourceType.S3);
    String token = library.map(KnowledgeLibrary::getWebhookSecret).orElse(null);
    if (!S3EventAuthentication.verify(authorization, sharedSecret, token)) {
      log.warn("Rejected S3 event notification for library {}: not authenticated", libraryId);
      throw new UnauthorizedException(UNAUTHORIZED_MESSAGE);
    }
    S3EventPayload.Parsed parsed = S3EventPayload.parse(rawBody, jsonMapper);
    if (parsed.testEvent()) {
      log.info("S3 set-up test event for library {} accepted", libraryId);
      return;
    }
    S3SourceSettings settings = library.get().getS3Settings();
    Set<String> admitted = new LinkedHashSet<>();
    int dropped = 0;
    S3KeyPatterns patterns = settings == null ? null : S3KeyPatterns.of(settings);
    for (S3ObjectEvent event : parsed.events()) {
      if (settings != null && inScope(settings, event) && patterns.admits(event.key())) {
        admitted.add(event.reference());
      } else {
        dropped++;
      }
    }
    if (dropped > 0) {
      log.info(
          "S3 event notification for library {}: {} of {} objects lie outside the scopes or"
              + " patterns and are ignored",
          libraryId,
          dropped,
          parsed.events().size());
    }
    if (admitted.isEmpty()) {
      // nothing inside the scopes: no timer, no run - a dropped count was logged above
      log.debug("S3 event notification for library {} named no object to check", libraryId);
      return;
    }
    enqueue(libraryId, admitted, dropped);
  }

  private static boolean inScope(S3SourceSettings settings, S3ObjectEvent event) {
    for (S3Scope scope : settings.scopes()) {
      if (scope.bucket().equals(event.bucket()) && scope.contains(event.key())) {
        return true;
      }
    }
    return false;
  }

  private synchronized void enqueue(UUID libraryId, Set<String> keys, int dropped) {
    PendingBatch batch = pending.get(libraryId);
    if (batch == null) {
      batch = new PendingBatch();
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
    Optional<KnowledgeLibrary> loaded =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getSourceType() == DocumentSourceType.S3)
            .filter(l -> l.getWebhookSecret() != null);
    if (loaded.isEmpty()) {
      log.info("Dropping S3 event batch for library {}: library gone or token removed", libraryId);
      return;
    }
    KnowledgeLibrary library = loaded.get();
    if (indexingJobService.isJobRunning(library.getId(), library.getOrganizationId())) {
      defer(libraryId, batch);
      return;
    }
    IndexingRunMode runMode = batch.overflowed ? IndexingRunMode.FULL : IndexingRunMode.EVENT;
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
        executor.refreshObjects(job.getId(), library, batch.keys(), batch.dropped);
      }
    } catch (TaskRejectedException e) {
      indexingJobService.failJob(
          job.getId(), "Indizierungslauf abgelehnt: Kapazität derzeit erschöpft");
      log.warn("S3 event run for library {} rejected: executor queue full", libraryId);
    }
  }

  private synchronized void defer(UUID libraryId, PendingBatch batch) {
    if (batch.deferrals >= properties.maxDeferrals()) {
      log.info(
          "Dropping S3 event batch for library {} after {} deferrals: a run is still in progress,"
              + " the next run covers the reported objects",
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
      current.merge(batch, properties.maxPendingKeys());
    }
  }

  /** What is pending for one library. Guarded by the service's monitor. */
  private static final class PendingBatch {
    private final Set<String> keys = new LinkedHashSet<>();
    private int dropped;
    private boolean overflowed;
    private int deferrals;

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
