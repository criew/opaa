package io.opaa.indexing.maintenance;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditQueryService;
import io.opaa.common.ConflictException;
import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunStatus;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.indexing.chunk.EmbeddingIndexState.Snapshot;
import io.opaa.indexing.chunk.EmbeddingIndexStore;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.chunk.VectorIndexSchema;
import io.opaa.indexing.chunk.VectorIndexSchema.ChunkCounts;
import io.opaa.instance.CurrentInstance;
import io.opaa.instance.InstanceRegistry;
import io.opaa.instance.ScheduledRunLocks;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentBatchLoop;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.VectorTable;
import io.opaa.permission.PersonThreshold;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Re-embeds the whole vector index under the configured embedding model, in the background and only
 * on a person's start. Document by document through {@link
 * ContextPrefixRerunService#reembedInPlace}: chunks, ids and stored text stay, only the vectors are
 * computed anew, into the target column. When every chunk carries a target vector, the target
 * column becomes the embedding column and the vector search resumes.
 *
 * <p>Progress is what the target column holds, so a stop - cancelled, failed or the process ended -
 * loses nothing. The run belongs to the instance that started or took it over, recorded in {@code
 * embedding_index} (ADR-0053, Entscheidung 8): any instance may cancel it there, and the worker of
 * the owner stops after its current document. A run whose owner is dead is taken over by the next
 * instance that looks, at its start or every {@value #TAKE_OVER_SWEEP_MILLIS} ms after; the worker
 * itself is held in the process (ADR-0053).
 */
@Service
public class EmbeddingReindexService {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingReindexService.class);

  /** The fixed actor of the run's own audit events; no person stands behind its end. */
  static final String SYSTEM_ACTOR = "embedding-reindex";

  /** The vector index has no row id of its own; a name-derived one identifies it in the log. */
  public static final UUID AUDIT_OBJECT_ID =
      UUID.nameUUIDFromBytes("embedding-index".getBytes(StandardCharsets.UTF_8));

  public static final String AUDIT_OBJECT_LABEL = "Vektorindex";

  /** Documents selected per round. */
  static final int BATCH_SIZE = 20;

  /**
   * After this many documents failing in a row the endpoint, not a document, is the likely cause.
   */
  static final int MAX_CONSECUTIVE_FAILURES = 5;

  static final int SWITCH_ATTEMPTS = 3;

  static final Duration SWITCH_RETRY_PAUSE = Duration.ofSeconds(5);

  static final String CANCELLED_MESSAGE =
      "Von der Systemverwaltung abgebrochen. Bereits neu eingebettete Abschnitte bleiben erhalten.";

  /** How often a run of a dead instance is looked for, well below {@code dead-after}. */
  static final long TAKE_OVER_SWEEP_MILLIS = 60_000L;

  private final EmbeddingIndexState state;
  private final EmbeddingIndexStore store;
  private final VectorIndexSchema schema;
  private final ContextPrefixRerunService rerunService;
  private final DocumentRepository documentRepository;
  private final VectorChunkStore vectorChunkStore;
  private final AuditEventRecorder auditEventRecorder;
  private final JdbcTemplate jdbcTemplate;
  private final KnowledgeLibraryRepository libraryRepository;
  private final PersonThreshold personThreshold;
  private final String vectorStoreTable;
  private final Clock clock;
  private final CurrentInstance currentInstance;
  private final InstanceRegistry instanceRegistry;

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "embedding-reindex");
            thread.setDaemon(true);
            return thread;
          });

  /** Whether a run is submitted or working in this process. */
  private final AtomicBoolean active = new AtomicBoolean();

  private volatile boolean stopRequested;

  public EmbeddingReindexService(
      EmbeddingIndexState state,
      EmbeddingIndexStore store,
      VectorIndexSchema schema,
      ContextPrefixRerunService rerunService,
      DocumentRepository documentRepository,
      VectorChunkStore vectorChunkStore,
      AuditEventRecorder auditEventRecorder,
      JdbcTemplate jdbcTemplate,
      KnowledgeLibraryRepository libraryRepository,
      PersonThreshold personThreshold,
      VectorTable vectorTable,
      Clock clock,
      CurrentInstance currentInstance,
      InstanceRegistry instanceRegistry) {
    this.currentInstance = currentInstance;
    this.instanceRegistry = instanceRegistry;
    this.state = state;
    this.store = store;
    this.schema = schema;
    this.rerunService = rerunService;
    this.documentRepository = documentRepository;
    this.vectorChunkStore = vectorChunkStore;
    this.auditEventRecorder = auditEventRecorder;
    this.jdbcTemplate = jdbcTemplate;
    this.libraryRepository = libraryRepository;
    this.personThreshold = personThreshold;
    this.vectorStoreTable = vectorTable.qualifiedName();
    this.clock = clock;
  }

  /** Read while the columns cannot be switched, so the counts belong to the reported state. */
  public EmbeddingReindexProgress progress() {
    boolean working = active.get();
    return state.withStableColumns(
        snapshot -> {
          Run run = store.load().map(EmbeddingIndexRecord::run).orElse(Run.NONE);
          boolean running = working || (run.running() && ownerAlive());
          boolean stale = snapshot != null && snapshot.stale();
          boolean privateCounted =
              personThreshold.discloses(libraryRepository.countAllPrivateLibraryOwners());
          long total = 0;
          long embedded = 0;
          if (schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN) != null) {
            ChunkCounts counts =
                schema.chunkCounts(
                    stale
                        ? EmbeddingIndexState.TARGET_COLUMN
                        : EmbeddingIndexState.EMBEDDING_COLUMN);
            total = counts.sharedTotal() + (privateCounted ? counts.privateTotal() : 0);
            embedded =
                total - counts.sharedWithout() - (privateCounted ? counts.privateWithout() : 0);
          }
          return new EmbeddingReindexProgress(
              snapshot == null ? null : snapshot.current(),
              snapshot == null ? null : snapshot.configured(),
              stale,
              running,
              run,
              total,
              embedded,
              privateCounted);
        });
  }

  /**
   * Starts or resumes the re-embedding as {@code userId} of {@code organizationId}, the
   * organization its audit events are recorded in, as a run of this instance.
   *
   * @throws ConflictException when the index is not stale or a live instance is working on a run
   */
  public synchronized EmbeddingReindexProgress start(UUID userId, UUID organizationId) {
    if (!state.isStale()) {
      throw new ConflictException(
          "Der Vektorindex entspricht dem konfigurierten Embedding-Modell; es gibt nichts neu"
              + " einzubetten.");
    }
    EmbeddingIndexRecord record = requireRecord();
    Optional<UUID> owner = store.runOwner();
    if (active.get() || (record.run().running() && owner.map(this::isAlive).orElse(false))) {
      throw new ConflictException("Die Neueinbettung läuft bereits.");
    }
    Run run = new Run(RunStatus.RUNNING, Instant.now(clock), userId, organizationId, null, null);
    if (!store.startRun(run, currentInstance.id(), record.run().status(), owner.orElse(null))) {
      throw new ConflictException("Die Neueinbettung läuft bereits.");
    }
    submit();
    return progress();
  }

  /**
   * Stops the running re-embedding after the document it is working on, whichever instance runs it.
   * What it embedded stays.
   *
   * @throws ConflictException when no run is going on
   */
  public synchronized EmbeddingReindexProgress cancel() {
    if (!store.endRun(null, RunStatus.CANCELLED, Instant.now(clock), CANCELLED_MESSAGE)) {
      throw new ConflictException("Es läuft keine Neueinbettung.");
    }
    stopRequested = true;
    return progress();
  }

  /**
   * Takes over a run left {@code RUNNING} by an instance that is gone, the last process included.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void resumeAfterRestart() {
    takeOverOrphanedRun();
  }

  /**
   * Takes over the running run when its owner is dead or none is recorded, and works on it here. A
   * dead instance stays dead, and the takeover is conditional on the owner it read, so of several
   * instances looking at once one takes over.
   */
  @Scheduled(fixedDelay = TAKE_OVER_SWEEP_MILLIS, initialDelay = TAKE_OVER_SWEEP_MILLIS)
  @SchedulerLock(
      name = "embedding-reindex-takeover",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public synchronized void takeOverOrphanedRun() {
    if (active.get() || !state.isStale()) {
      return;
    }
    Optional<EmbeddingIndexRecord> record = store.load();
    if (record.isEmpty() || !record.get().run().running()) {
      return;
    }
    Optional<UUID> owner = store.runOwner();
    if (owner.isPresent() && isAlive(owner.get())) {
      return;
    }
    if (store.takeOverRun(currentInstance.id(), owner.orElse(null))) {
      log.info(
          "Taking over the re-embedding of the vector index left running by instance {}",
          owner.map(UUID::toString).orElse("(none recorded)"));
      submit();
    }
  }

  private boolean ownerAlive() {
    return store.runOwner().map(this::isAlive).orElse(false);
  }

  /** This instance counts as alive while its heartbeat is current, whatever the register says. */
  private boolean isAlive(UUID instance) {
    return instance.equals(currentInstance.id())
        ? currentInstance.heartbeatCurrent()
        : instanceRegistry.isAlive(instance);
  }

  /**
   * Whether this instance still works on the run: nobody cancelled or ended it, nobody took it
   * over, and this instance has not lost its heartbeat.
   */
  private boolean stillOwned() {
    return !stopRequested
        && currentInstance.heartbeatCurrent()
        && store.ownsRunningRun(currentInstance.id());
  }

  /** Stops the worker with the process; the run stays {@code RUNNING} and resumes on next start. */
  @PreDestroy
  void shutdown() {
    stopRequested = true;
    executor.shutdownNow();
  }

  private void submit() {
    stopRequested = false;
    active.set(true);
    executor.execute(this::run);
  }

  enum Step {
    PROCESSED,
    ORPHAN_REMOVED,
    FAILED,
    STOPPED
  }

  /** One run until it switches over, fails or is stopped. */
  private void run() {
    Set<UUID> failed = ConcurrentHashMap.newKeySet();
    int[] consecutiveFailures = {0};
    try {
      while (true) {
        Map<Step, Integer> counts =
            DocumentBatchLoop.run(
                BATCH_SIZE,
                Step.class,
                Step.FAILED,
                Step.STOPPED,
                (limit, offset) -> pendingDocuments(failed, limit),
                documentId -> reembed(documentId, failed, consecutiveFailures));
        if (!stillOwned()) {
          return;
        }
        if (consecutiveFailures[0] >= MAX_CONSECUTIVE_FAILURES) {
          end(
              RunStatus.FAILED,
              "Abgebrochen: "
                  + MAX_CONSECUTIVE_FAILURES
                  + " Dokumente nacheinander ließen sich nicht einbetten. Bitte Erreichbarkeit"
                  + " und Zeitgrenze des Embedding-Modells prüfen und erneut starten.");
          return;
        }
        if (counts.get(Step.PROCESSED) + counts.get(Step.ORPHAN_REMOVED) == 0) {
          break;
        }
      }
      long remaining = schema.chunksWithout(EmbeddingIndexState.TARGET_COLUMN);
      if (remaining > 0) {
        end(
            RunStatus.FAILED,
            "Unvollständig: Einige Dokumente ließen sich nicht neu einbetten (Einzelheiten im"
                + " Log). Die Vektorsuche bleibt ausgesetzt; ein erneuter Start versucht nur"
                + " diese.");
        return;
      }
      switchOver();
    } catch (RuntimeException e) {
      if (stopRequested) {
        return;
      }
      log.error("The re-embedding of the vector index stopped on an unexpected failure", e);
      end(RunStatus.FAILED, "Abgebrochen nach einem technischen Fehler (Einzelheiten im Log).");
    } finally {
      active.set(false);
    }
  }

  /**
   * One document of the run. Every failure counts towards {@link #MAX_CONSECUTIVE_FAILURES} except
   * chunk rows held by another writer: such a document is skipped for the rest of the run without
   * counting.
   */
  Step reembed(UUID documentId, Set<UUID> failed, int[] consecutiveFailures) {
    if (consecutiveFailures[0] >= MAX_CONSECUTIVE_FAILURES || !stillOwned()) {
      return Step.STOPPED;
    }
    Optional<Document> document = documentRepository.findById(documentId);
    if (document.isEmpty()) {
      // Chunks outliving their document row are unreachable either way; there is nothing to
      // rebuild their input from.
      log.warn("Removing chunks of document {}, whose row no longer exists", documentId);
      vectorChunkStore.deleteByDocumentId(documentId);
      return Step.ORPHAN_REMOVED;
    }
    try {
      rerunService.reembedInPlace(document.get());
      consecutiveFailures[0] = 0;
      return Step.PROCESSED;
    } catch (CannotAcquireLockException e) {
      // Another writer holds the chunk rows, typically the erasure of the library. That says
      // nothing about the embedding endpoint: skip the document for this run without counting it.
      log.info("Document {} skipped in this re-embedding run: its chunks are locked", documentId);
      failed.add(documentId);
      return Step.FAILED;
    } catch (RuntimeException e) {
      log.warn("Document {} could not be re-embedded under the new model", documentId, e);
      failed.add(documentId);
      consecutiveFailures[0]++;
      return consecutiveFailures[0] >= MAX_CONSECUTIVE_FAILURES ? Step.STOPPED : Step.FAILED;
    }
  }

  /**
   * Documents with at least one chunk still without a target vector, minus those this run already
   * failed on - so the selection never needs an offset.
   */
  private List<UUID> pendingDocuments(Set<UUID> failed, int limit) {
    return jdbcTemplate.query(
        "SELECT DISTINCT (metadata->>'"
            + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
            + "')::uuid AS id FROM "
            + vectorStoreTable
            + " WHERE "
            + EmbeddingIndexState.TARGET_COLUMN
            + " IS NULL AND metadata->>'"
            + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
            + "' IS NOT NULL AND NOT ((metadata->>'"
            + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
            + "')::uuid = ANY (?::uuid[])) ORDER BY id LIMIT ?",
        (rs, row) -> rs.getObject("id", UUID.class),
        failed.stream().map(UUID::toString).toArray(String[]::new),
        limit);
  }

  /**
   * Builds the index on the target column without blocking, then switches columns while no writer
   * is between choosing a column and writing. A table lock not granted in time is retried.
   */
  private void switchOver() {
    schema.buildTargetIndex();
    for (int attempt = 1; attempt <= SWITCH_ATTEMPTS; attempt++) {
      try {
        if (switchExclusively()) {
          recordCompletion(AuditOutcome.SUCCESS, null);
        }
        return;
      } catch (DataAccessException e) {
        log.warn("Switching to the re-embedded vectors failed (attempt {})", attempt, e);
        if (attempt < SWITCH_ATTEMPTS) {
          pause();
        }
      }
    }
    end(
        RunStatus.FAILED,
        "Alle Abschnitte sind neu eingebettet, aber das Umschalten auf die neuen Vektoren war nicht"
            + " möglich (Einzelheiten im Log). Ein erneuter Start versucht es noch einmal.");
  }

  /** Under the service's monitor as well, so a concurrent cancel either wins or comes too late. */
  private synchronized boolean switchExclusively() {
    return state.exclusively(this::switchIfStillRunning);
  }

  /**
   * {@code false} when the run was cancelled, ended or taken over meanwhile; nothing is switched
   * then. Whether this instance still owns the run is asked again under the lock of the switch.
   */
  private boolean switchIfStillRunning() {
    EmbeddingIndexRecord record = requireRecord();
    Snapshot snapshot = state.snapshot();
    if (!stillOwned() || !record.run().running() || snapshot == null || !snapshot.stale()) {
      return false;
    }
    EmbeddingIdentity target = snapshot.target();
    long remaining = schema.chunksWithout(EmbeddingIndexState.TARGET_COLUMN);
    if (remaining > 0) {
      throw new IllegalStateException(remaining + " chunk(s) still lack a target vector");
    }
    boolean switched =
        schema.switchToTarget(
            () -> store.lockRunningRunOf(currentInstance.id()),
            () ->
                store.save(
                    new EmbeddingIndexRecord(
                        target,
                        null,
                        record.run().ended(RunStatus.COMPLETED, Instant.now(clock), null))));
    if (!switched) {
      return false;
    }
    state.replace(new Snapshot(target, snapshot.configured(), null));
    log.info(
        "The vector index now holds vectors of embedding model {} ({} dimensions); the vector"
            + " search is available again",
        target.model(),
        target.dimensions());
    return true;
  }

  /** Ends the run with {@code status} unless it was cancelled, ended or taken over meanwhile. */
  private synchronized void end(RunStatus status, String message) {
    if (store.endRun(currentInstance.id(), status, Instant.now(clock), message)) {
      recordCompletion(AuditOutcome.FAILURE, message);
    }
  }

  private void recordCompletion(AuditOutcome outcome, String reason) {
    Run run = requireRecord().run();
    if (run.organizationId() == null) {
      return;
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("status", String.valueOf(run.status()));
    if (reason != null && reason.length() > AuditQueryService.MAX_REASON_LENGTH) {
      reason = reason.substring(0, AuditQueryService.MAX_REASON_LENGTH);
    }
    try {
      auditEventRecorder.recordSystemProcessAction(
          AuditEvent.builder()
              .organizationId(run.organizationId())
              .actorRef(SYSTEM_ACTOR)
              .type(AuditEventType.EMBEDDING_REINDEX_COMPLETED)
              .object(AuditObjectType.SYSTEM_SETTING, AUDIT_OBJECT_ID, AUDIT_OBJECT_LABEL)
              .after(after)
              .outcome(outcome)
              .reason(reason)
              .build());
    } catch (RuntimeException e) {
      log.error("Failed to record the end of the re-embedding in the audit log", e);
    }
  }

  private EmbeddingIndexRecord requireRecord() {
    return store
        .load()
        .orElseThrow(() -> new IllegalStateException("embedding_index has not been reconciled"));
  }

  private static void pause() {
    try {
      Thread.sleep(SWITCH_RETRY_PAUSE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
