package io.opaa.indexing.maintenance;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditQueryService;
import io.opaa.common.ConflictException;
import io.opaa.common.TransactionCallbacks;
import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunKind;
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
import io.opaa.llm.EmbeddingModelAdministration;
import io.opaa.permission.PersonThreshold;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * The runs of the vector index, in the background (ADR-0061, Entscheidung 4). A re-embedding fills
 * the target column under the target generation, document by document through {@link
 * ContextPrefixRerunService#reembedInPlace} - chunks, ids and stored text stay - and switches the
 * columns when every chunk carries a target vector. A refill, after a discarded change, fills the
 * chunks without a vector in the embedding column under its generation and never switches. Which
 * column a vector goes to follows its generation, so both kinds share the write path.
 *
 * <p>Progress is what the column holds, so a stop - cancelled, failed or the process ended - loses
 * nothing. The run belongs to the instance that started or took it over, recorded in {@code
 * embedding_index} together with its kind and generation (ADR-0053, Entscheidung 8): any instance
 * may cancel it there, and a worker stops after its current document once owner, kind or generation
 * are no longer its own. A run whose owner is dead is taken over by the next instance that looks,
 * at its start or every {@value #TAKE_OVER_SWEEP_MILLIS} ms after; the worker itself is held in the
 * process (ADR-0053).
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

  /** What one worker works on; it stops once the record names anything else. */
  record Work(RunKind kind, Long generation) {

    String column() {
      return kind == RunKind.REFILL
          ? EmbeddingIndexState.EMBEDDING_COLUMN
          : EmbeddingIndexState.TARGET_COLUMN;
    }
  }

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
  private final EmbeddingModelAdministration modelAdministration;
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

  /** Workers submitted or working in this process. */
  private final AtomicInteger active = new AtomicInteger();

  private volatile boolean shuttingDown;

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
      EmbeddingModelAdministration modelAdministration,
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
    this.modelAdministration = modelAdministration;
    this.vectorStoreTable = vectorTable.qualifiedName();
    this.clock = clock;
  }

  /** Read while the columns cannot be switched, so the counts belong to the reported state. */
  public EmbeddingReindexProgress progress() {
    boolean working = active.get() > 0;
    EmbeddingIdentity previous =
        modelAdministration
            .previous()
            .map(
                model ->
                    new EmbeddingIdentity(
                        model.model(), model.baseUrl(), model.dimensions(), model.generation()))
            .orElse(null);
    return state.withStableColumns(
        snapshot -> {
          Run run = store.load().map(EmbeddingIndexRecord::run).orElse(Run.NONE);
          boolean liveOwner = run.running() && ownerAlive();
          boolean running = working || liveOwner;
          boolean stale = snapshot != null && snapshot.stale();
          boolean privateCounted =
              personThreshold.discloses(libraryRepository.countAllPrivateLibraryOwners());
          Counted counted = schema.whileUnchanged(() -> counted(stale, privateCounted));
          return new EmbeddingReindexProgress(
              snapshot == null ? null : snapshot.current(),
              snapshot == null ? null : snapshot.configured(),
              stale,
              running,
              run,
              counted.total(),
              counted.embedded(),
              privateCounted,
              previous,
              liveOwner && run.indexBuildStartedAt() != null,
              counted.withoutCurrent());
        });
  }

  /** The chunks {@link #progress} reports, of the private libraries only when they are counted. */
  private record Counted(long total, long embedded, long withoutCurrent) {}

  /**
   * Counts the target column while it is filled, the embedding column otherwise. A stale snapshot
   * without a target column means another instance switched the columns before this one heard of
   * it: the embedding column then holds the vectors the target column was filled with.
   */
  private Counted counted(boolean stale, boolean privateCounted) {
    if (schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN) == null) {
      return new Counted(0, 0, 0);
    }
    boolean filling = stale && schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN) != null;
    ChunkCounts counts =
        schema.chunkCounts(
            filling ? EmbeddingIndexState.TARGET_COLUMN : EmbeddingIndexState.EMBEDDING_COLUMN);
    long total = counts.sharedTotal() + (privateCounted ? counts.privateTotal() : 0);
    long embedded = total - counts.sharedWithout() - (privateCounted ? counts.privateWithout() : 0);
    ChunkCounts current =
        filling ? schema.chunkCounts(EmbeddingIndexState.EMBEDDING_COLUMN) : counts;
    return new Counted(
        total, embedded, current.sharedWithout() + (privateCounted ? current.privateWithout() : 0));
  }

  /**
   * Starts or resumes the run as {@code userId} of {@code organizationId}, the organization its
   * audit events are recorded in, as a run of this instance: the re-embedding of a stale index, or
   * the refill a discard left unfinished.
   *
   * @throws ConflictException when there is nothing to do or a live instance is working on a run
   */
  public synchronized EmbeddingReindexProgress start(UUID userId, UUID organizationId) {
    EmbeddingIndexRecord record = requireRecord();
    Work work = resumable(record);
    Optional<UUID> owner = store.runOwner();
    if (active.get() > 0 || (record.run().running() && owner.map(this::isAlive).orElse(false))) {
      throw new ConflictException("Die Neueinbettung läuft bereits.");
    }
    Run run =
        new Run(
            RunStatus.RUNNING,
            Instant.now(clock),
            userId,
            organizationId,
            null,
            null,
            work.kind(),
            work.generation(),
            null);
    if (!store.startRun(run, currentInstance.id(), record.run().status(), owner.orElse(null))) {
      throw new ConflictException("Die Neueinbettung läuft bereits.");
    }
    submit(work);
    return progress();
  }

  /**
   * Starts a run of {@code kind} for {@code generation} owned by this instance, in the caller's
   * transaction; its worker starts after the commit. Used by a model change and by a discard, which
   * hold the record locked.
   */
  void startWithin(RunKind kind, long generation, UUID userId, UUID organizationId) {
    jdbcTemplate.update(
        "UPDATE embedding_index SET run_status = 'RUNNING', run_started_at = ?,"
            + " run_started_by = ?, run_organization_id = ?, run_finished_at = NULL,"
            + " run_message = NULL, run_kind = ?, run_generation = ?,"
            + " index_build_started_at = NULL, run_owner_instance_id = ?, updated_at = ?"
            + " WHERE id = 1",
        Timestamp.from(Instant.now(clock)),
        userId,
        organizationId,
        kind.name(),
        generation,
        currentInstance.id(),
        Timestamp.from(Instant.now(clock)));
    Work work = new Work(kind, generation);
    TransactionCallbacks.afterCommitOrNow(() -> submit(work));
  }

  /**
   * What a start resumes, as the record describes it: the re-embedding of a stale index or an
   * unfinished refill. The generation is the record's, never this instance's state.
   */
  private Work resumable(EmbeddingIndexRecord record) {
    if (record.target() != null) {
      return new Work(RunKind.REEMBED, record.target().generation());
    }
    if (record.run().kindOrReembed() == RunKind.REFILL
        && record.run().status() != RunStatus.COMPLETED
        && schema.chunksWithout(EmbeddingIndexState.EMBEDDING_COLUMN) > 0) {
      return new Work(RunKind.REFILL, record.current().generation());
    }
    throw new ConflictException(
        "Der Vektorindex entspricht dem konfigurierten Embedding-Modell; es gibt nichts neu"
            + " einzubetten.");
  }

  /**
   * Stops the running run after the document it is working on, whichever instance runs it. What it
   * embedded stays.
   *
   * @throws ConflictException when no run is going on
   */
  public synchronized EmbeddingReindexProgress cancel() {
    if (!store.endRun(null, RunStatus.CANCELLED, Instant.now(clock), CANCELLED_MESSAGE)) {
      throw new ConflictException("Es läuft keine Neueinbettung.");
    }
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
   * re-embedding is taken over only on a stale index, a refill only on a current one. A dead
   * instance stays dead, and the takeover is conditional on the owner it read, so of several
   * instances looking at once one takes over.
   */
  @Scheduled(fixedDelay = TAKE_OVER_SWEEP_MILLIS, initialDelay = TAKE_OVER_SWEEP_MILLIS)
  @SchedulerLock(
      name = "embedding-reindex-takeover",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public synchronized void takeOverOrphanedRun() {
    if (active.get() > 0) {
      return;
    }
    Optional<EmbeddingIndexRecord> record = store.load();
    if (record.isEmpty() || !record.get().run().running()) {
      return;
    }
    Run run = record.get().run();
    boolean refill = run.kindOrReembed() == RunKind.REFILL;
    if (refill == state.isStale()) {
      return;
    }
    Optional<UUID> owner = store.runOwner();
    if (owner.isPresent() && isAlive(owner.get())) {
      return;
    }
    if (store.takeOverRun(currentInstance.id(), owner.orElse(null))) {
      log.info(
          "Taking over the {} of the vector index left running by instance {}",
          refill ? "refill" : "re-embedding",
          owner.map(UUID::toString).orElse("(none recorded)"));
      submit(new Work(run.kindOrReembed(), run.generation()));
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
   * Whether this instance still works on {@code work}: nobody cancelled, ended or replaced the run,
   * nobody took it over, and this instance has not lost its heartbeat.
   */
  private boolean stillOwned(Work work) {
    return !shuttingDown
        && currentInstance.heartbeatCurrent()
        && store.ownsRunningRun(currentInstance.id(), work.kind(), work.generation());
  }

  /** Stops the worker with the process; the run stays {@code RUNNING} and resumes on next start. */
  @PreDestroy
  void shutdown() {
    shuttingDown = true;
    executor.shutdownNow();
  }

  private void submit(Work work) {
    active.incrementAndGet();
    try {
      executor.execute(() -> run(work));
    } catch (RuntimeException e) {
      active.decrementAndGet();
      throw e;
    }
  }

  enum Step {
    PROCESSED,
    ORPHAN_REMOVED,
    FAILED,
    STOPPED
  }

  /** One run until it switches over or ends, fails or is stopped. */
  private void run(Work work) {
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
                (limit, offset) -> pendingDocuments(work, failed, limit),
                documentId -> reembed(work, documentId, failed, consecutiveFailures));
        if (!stillOwned(work)) {
          return;
        }
        if (consecutiveFailures[0] >= MAX_CONSECUTIVE_FAILURES) {
          end(
              work,
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
      long remaining = schema.chunksWithout(work.column());
      if (remaining > 0) {
        end(
            work,
            RunStatus.FAILED,
            work.kind() == RunKind.REFILL
                ? "Unvollständig: Einige Dokumente ließen sich nicht nachfüllen (Einzelheiten im"
                    + " Log); die Volltextsuche findet sie weiter. Ein erneuter Start versucht"
                    + " nur diese."
                : "Unvollständig: Einige Dokumente ließen sich nicht neu einbetten (Einzelheiten"
                    + " im Log). Die Vektorsuche bleibt ausgesetzt; ein erneuter Start versucht"
                    + " nur diese.");
        return;
      }
      if (work.kind() == RunKind.REFILL) {
        if (store.endRun(currentInstance.id(), RunStatus.COMPLETED, Instant.now(clock), null)) {
          log.info("The refill of the vector index is complete");
        }
        return;
      }
      switchOver(work);
    } catch (RuntimeException e) {
      if (shuttingDown) {
        return;
      }
      log.error("The run of the vector index stopped on an unexpected failure", e);
      end(
          work,
          RunStatus.FAILED,
          "Abgebrochen nach einem technischen Fehler (Einzelheiten im Log).");
    } finally {
      active.decrementAndGet();
    }
  }

  /**
   * One document of the run. Every failure counts towards {@link #MAX_CONSECUTIVE_FAILURES} except
   * chunk rows held by another writer: such a document is skipped for the rest of the run without
   * counting.
   */
  Step reembed(Work work, UUID documentId, Set<UUID> failed, int[] consecutiveFailures) {
    if (consecutiveFailures[0] >= MAX_CONSECUTIVE_FAILURES || !stillOwned(work)) {
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
      log.info(
          "Document {} skipped in this run of the vector index: its chunks are locked", documentId);
      failed.add(documentId);
      return Step.FAILED;
    } catch (RuntimeException e) {
      log.warn("Document {} could not be embedded in this run of the vector index", documentId, e);
      failed.add(documentId);
      consecutiveFailures[0]++;
      return consecutiveFailures[0] >= MAX_CONSECUTIVE_FAILURES ? Step.STOPPED : Step.FAILED;
    }
  }

  /**
   * Documents with at least one chunk still without a vector in the column of {@code work}, minus
   * those this run already failed on - so the selection never needs an offset.
   */
  private List<UUID> pendingDocuments(Work work, Set<UUID> failed, int limit) {
    return jdbcTemplate.query(
        "SELECT DISTINCT (metadata->>'"
            + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
            + "')::uuid AS id FROM "
            + vectorStoreTable
            + " WHERE "
            + work.column()
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
   * Marks the index build, builds the index on the target column without blocking, then switches
   * columns while no writer is between choosing a column and writing. A table lock not granted in
   * time is retried.
   */
  private void switchOver(Work work) {
    if (!store.markIndexBuild(currentInstance.id(), work.kind(), work.generation())) {
      return;
    }
    schema.buildTargetIndex();
    for (int attempt = 1; attempt <= SWITCH_ATTEMPTS; attempt++) {
      try {
        if (switchExclusively(work)) {
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
        work,
        RunStatus.FAILED,
        "Alle Abschnitte sind neu eingebettet, aber das Umschalten auf die neuen Vektoren war nicht"
            + " möglich (Einzelheiten im Log). Ein erneuter Start versucht es noch einmal.");
  }

  /** Under the service's monitor as well, so a concurrent cancel either wins or comes too late. */
  private synchronized boolean switchExclusively(Work work) {
    return state.exclusively(() -> switchIfStillRunning(work));
  }

  /**
   * {@code false} when the run was cancelled, ended, replaced or taken over meanwhile; nothing is
   * switched then. Whether this instance still owns the run is asked again under the lock of the
   * switch. The switch makes the target generation the current one, and drops the previous model
   * and the mark of the index build in the same transaction.
   */
  private boolean switchIfStillRunning(Work work) {
    EmbeddingIndexRecord record = requireRecord();
    Snapshot snapshot = state.snapshot();
    if (!stillOwned(work) || !record.run().running() || snapshot == null || !snapshot.stale()) {
      return false;
    }
    EmbeddingIdentity target = record.target();
    if (target == null || (work.generation() != null && target.generation() != work.generation())) {
      return false;
    }
    long remaining = schema.chunksWithout(EmbeddingIndexState.TARGET_COLUMN);
    if (remaining > 0) {
      throw new IllegalStateException(remaining + " chunk(s) still lack a target vector");
    }
    boolean switched =
        schema.switchToTarget(
            () -> store.lockRunningRunOf(currentInstance.id(), work.kind(), work.generation()),
            () -> {
              store.save(
                  new EmbeddingIndexRecord(
                      target,
                      null,
                      record.run().ended(RunStatus.COMPLETED, Instant.now(clock), null)));
              modelAdministration.dropPrevious();
            });
    if (!switched) {
      return false;
    }
    state.replace(new Snapshot(target, snapshot.configured(), null));
    log.info(
        "The vector index now holds vectors of embedding model {} ({} dimensions, generation {});"
            + " the vector search is available again",
        target.model(),
        target.dimensions(),
        target.generation());
    return true;
  }

  /** Ends the run with {@code status} unless it was cancelled, ended or taken over meanwhile. */
  private synchronized void end(Work work, RunStatus status, String message) {
    if (store.endRun(currentInstance.id(), status, Instant.now(clock), message)
        && work.kind() == RunKind.REEMBED) {
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
