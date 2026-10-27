package io.opaa.indexing.maintenance;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.format.config.ChunkTargetProperties;
import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunKind;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunStatus;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.indexing.chunk.EmbeddingIndexState.Snapshot;
import io.opaa.indexing.chunk.EmbeddingIndexStore;
import io.opaa.indexing.chunk.VectorIndexSchema;
import io.opaa.indexing.chunk.VectorIndexSchema.ChunkCounts;
import io.opaa.indexing.chunk.VectorIndexSchema.TextLengths;
import io.opaa.instance.CurrentInstance;
import io.opaa.instance.InstanceRegistry;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.llm.EmbeddingAnchor;
import io.opaa.llm.EmbeddingModelAdministration;
import io.opaa.llm.EmbeddingModelAdministration.Change;
import io.opaa.llm.EmbeddingModelAdministration.Comparison;
import io.opaa.llm.EmbeddingModelAdministration.Draft;
import io.opaa.llm.EmbeddingModelAdministration.Kind;
import io.opaa.llm.EmbeddingModelAdministration.Resolved;
import io.opaa.llm.EmbeddingModelService.ProbeOutcome;
import io.opaa.llm.ModelEndpointRules;
import io.opaa.metadata.EmbeddingRateEstimator;
import io.opaa.metadata.EmbeddingRateEstimator.RateSource;
import io.opaa.permission.PersonThreshold;
import io.opaa.ratelimit.InstallationCounters;
import io.opaa.ratelimit.RateLimitService.Decision;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The guided change of the embedding model (ADR-0061): the probe with its preview, the confirmed
 * model change, its discard before the switch, and the endpoint change of the same model.
 *
 * <p>Confirmation, discard and endpoint change decide under the schema lock of the vector table
 * from the {@code embedding_index} row locked in the database, never from this instance's state,
 * and wait for a table lock only as long as the switch does; a refused lock is a {@code 409} that
 * can be repeated (Entscheidung 3). They run only while every live instance reports this instance's
 * version (Entscheidung 6). Each is an audit event in the same transaction; probe and confirmation
 * share one installation-wide limit, the endpoint change has its own, the discard none
 * (Entscheidung 7).
 */
@Service
public class EmbeddingModelChangeService {

  /** The audit object of the embedding model row. */
  static final UUID AUDIT_OBJECT_ID =
      UUID.nameUUIDFromBytes("embedding_model".getBytes(StandardCharsets.UTF_8));

  static final String AUDIT_OBJECT_LABEL = "Embedding-Modell";

  static final String PROBE_COUNTER = "embedding-model-change";

  static final String ENDPOINT_COUNTER = "embedding-model-endpoint";

  static final Duration WINDOW = Duration.ofHours(1);

  static final int CALLS_PER_WINDOW = 30;

  /** The fixed estimate of characters per token behind the token count of the preview. */
  static final int CHARACTERS_PER_TOKEN = 4;

  /** Bytes per vector beyond its four bytes per dimension: the varlena header and the length. */
  static final int VECTOR_OVERHEAD_BYTES = 8;

  static final String DISCARDED_MESSAGE =
      "Der Wechsel wurde von der Systemverwaltung verworfen; das bisherige Modell gilt wieder.";

  static final String REFILL_ENDED_MESSAGE =
      "Beendet: Ein neuer Wechsel des Embedding-Modells bettet alle Abschnitte ein.";

  static final String VERSIONS_DIFFER =
      "Nicht alle laufenden Instanzen haben dieselbe Version. Bitte zuerst das Rolling Update"
          + " abschließen.";

  static final String LOCK_NOT_GRANTED =
      "Die Vektortabelle ist gerade belegt, etwa durch den Aufbau des Vektorindex. Bitte in"
          + " einigen Minuten erneut versuchen.";

  /**
   * What the preview names before a model change.
   *
   * @param estimatedTokens the text length divided by {@value #CHARACTERS_PER_TOKEN}
   * @param rateSource {@code MEASURED} when the throughput probe answered
   * @param storageLowerBytes the new vectors and their index
   * @param storageUpperBytes the lower bound plus today's table with its indexes
   */
  public record Preview(
      long totalChunks,
      boolean privateLibrariesCounted,
      long estimatedTokens,
      long estimatedSeconds,
      RateSource rateSource,
      long storageLowerBytes,
      long storageUpperBytes,
      long currentTableBytes) {}

  /**
   * The result of a probe.
   *
   * @param kind what the draft is to the installed model; {@code null} unless the probe succeeded
   * @param similarity the smallest similarity to the anchor, when one could be compared
   * @param preview set for a model change
   */
  public record ProbeResult(
      boolean succeeded,
      String message,
      Integer dimensions,
      Kind kind,
      Double similarity,
      Preview preview) {}

  private final EmbeddingModelAdministration models;
  private final EmbeddingReindexService runs;
  private final EmbeddingIndexState state;
  private final EmbeddingIndexStore store;
  private final VectorIndexSchema schema;
  private final KnowledgeLibraryRepository libraryRepository;
  private final PersonThreshold personThreshold;
  private final EmbeddingRateEstimator rateEstimator;
  private final ChunkTargetProperties chunkTarget;
  private final InstallationCounters counters;
  private final CurrentInstance currentInstance;
  private final InstanceRegistry instanceRegistry;
  private final AuditEventRecorder auditEventRecorder;
  private final TransactionTemplate transactions;
  private final Clock clock;

  EmbeddingModelChangeService(
      EmbeddingModelAdministration models,
      EmbeddingReindexService runs,
      EmbeddingIndexState state,
      EmbeddingIndexStore store,
      VectorIndexSchema schema,
      KnowledgeLibraryRepository libraryRepository,
      PersonThreshold personThreshold,
      EmbeddingRateEstimator rateEstimator,
      ChunkTargetProperties chunkTarget,
      InstallationCounters counters,
      CurrentInstance currentInstance,
      InstanceRegistry instanceRegistry,
      AuditEventRecorder auditEventRecorder,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.models = models;
    this.runs = runs;
    this.state = state;
    this.store = store;
    this.schema = schema;
    this.libraryRepository = libraryRepository;
    this.personThreshold = personThreshold;
    this.rateEstimator = rateEstimator;
    this.chunkTarget = chunkTarget;
    this.counters = counters;
    this.currentInstance = currentInstance;
    this.instanceRegistry = instanceRegistry;
    this.auditEventRecorder = auditEventRecorder;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * Probes {@code draft} and tells whether it is a model change - with a preview, measured with
   * artificial texts only - or an endpoint change of the installed model. Writes nothing.
   *
   * @throws FieldValidationException when the draft breaks a rule of the endpoint
   * @throws TooManyRequestsException beyond the limit of probes and confirmations
   */
  public ProbeResult probe(Draft draft) {
    count(PROBE_COUNTER);
    Resolved resolved = models.resolve(draft);
    ProbeOutcome probed = models.probe(resolved.endpoint());
    if (!probed.succeeded()) {
      return new ProbeResult(false, probed.message(), null, null, null, null);
    }
    Comparison comparison = models.compare(resolved.endpoint(), probed);
    Preview preview =
        comparison.kind() == Kind.MODEL_CHANGE
            ? preview(
                probed.dimensions(),
                models.measureThroughput(resolved.endpoint(), chunkTarget.chunkTargetCharacters()))
            : null;
    return new ProbeResult(
        true,
        probed.message(),
        probed.dimensions(),
        comparison.kind(),
        comparison.similarity(),
        preview);
  }

  /**
   * Changes the embedding model to {@code draft} and starts the re-embedding as a run of this
   * instance; the vector search is suspended from the commit until the switch. An index without a
   * chunk takes the new model at once. A refill still running ends: the re-embedding embeds every
   * chunk anyway.
   *
   * @param expectedDimensions the size the preview named; another one is refused
   * @throws ConflictException when a change waits for its switch, the probe names another size or
   *     the installed model, the instances run different versions, or the table lock is not granted
   */
  public EmbeddingReindexProgress confirm(
      Draft draft, int expectedDimensions, UUID userId, UUID organizationId) {
    count(PROBE_COUNTER);
    requireSameVersions();
    Resolved resolved = models.resolve(draft);
    ProbeOutcome probed = models.probe(resolved.endpoint());
    if (!probed.succeeded()) {
      throw FieldValidationException.of(
          EmbeddingModelAdministration.PREFIX + ".baseUrl",
          ModelEndpointRules.INVALID,
          probed.message());
    }
    if (probed.dimensions() != expectedDimensions) {
      throw new ConflictException(
          "Das Modell liefert jetzt Vektoren mit "
              + probed.dimensions()
              + " statt "
              + expectedDimensions
              + " Dimensionen. Bitte die Probeeinbettung wiederholen.");
    }
    Comparison comparison = models.compare(resolved.endpoint(), probed);
    if (comparison.kind() == Kind.ENDPOINT_CHANGE) {
      throw new ConflictException(
          "Das Modell gleicht dem aktiven; dafür genügt ein Wechsel des Endpunkts ohne"
              + " Neueinbettung.");
    }
    float[] anchor = EmbeddingModelAdministration.anchorOf(probed);
    long generation = models.nextGeneration();
    return locked(
        () -> {
          models.requireUnchanged(resolved, comparison);
          EmbeddingIndexRecord record = requireRecordForUpdate();
          if (record.target() != null) {
            throw new ConflictException(
                "Ein Wechsel des Embedding-Modells wartet noch auf sein Umschalten. Bitte ihn"
                    + " abschließen oder verwerfen.");
          }
          boolean refillEnded =
              record.run().running()
                  && record.run().kindOrReembed() == RunKind.REFILL
                  && store.endRun(
                      null, RunStatus.CANCELLED, Instant.now(clock), REFILL_ENDED_MESSAGE);
          Change change = models.replace(resolved, probed.dimensions(), anchor, generation);
          EmbeddingIdentity next =
              new EmbeddingIdentity(
                  resolved.endpoint().modelIdentifier(),
                  resolved.endpoint().baseUrl(),
                  probed.dimensions(),
                  generation);
          Counted counted = counted();
          Snapshot snapshot;
          Run run = store.load().map(EmbeddingIndexRecord::run).orElse(Run.NONE);
          if (!schema.hasChunks()) {
            Integer column = schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN);
            if (column != null && column != -1 && column != probed.dimensions()) {
              schema.resizeEmptyEmbeddingColumn(probed.dimensions());
            }
            schema.dropTargetColumn();
            models.dropPrevious();
            store.save(new EmbeddingIndexRecord(next, null, run));
            snapshot = new Snapshot(next, next, null);
          } else {
            schema.recreateTargetColumn(probed.dimensions());
            store.save(new EmbeddingIndexRecord(record.current(), next, run));
            runs.startWithin(RunKind.REEMBED, generation, userId, organizationId);
            snapshot = new Snapshot(record.current(), next, next);
          }
          Map<String, Object> after = new LinkedHashMap<>(change.after());
          after.put("totalChunks", counted.chunks());
          after.put("privateLibrariesCounted", counted.privateCounted());
          after.put("refillEnded", refillEnded);
          record(
              userId,
              organizationId,
              AuditEventType.EMBEDDING_MODEL_CHANGED,
              change.before(),
              after);
          return snapshot;
        });
  }

  /**
   * Goes back to the model before the confirmed change: the previous row returns, the target column
   * goes, a running re-embedding ends, and a refill of this instance embeds the chunks written
   * meanwhile without a vector of the embedding column. Refused while a live run builds the index
   * of the target column.
   *
   * @throws ConflictException when nothing waits to be discarded, the switch is under way, the
   *     instances run different versions or the table lock is not granted
   */
  public EmbeddingReindexProgress discard(UUID userId, UUID organizationId) {
    requireSameVersions();
    return locked(
        () -> {
          EmbeddingIndexRecord record = requireRecordForUpdate();
          if (record.target() == null) {
            throw new ConflictException(
                "Es gibt keinen Wechsel des Embedding-Modells zu verwerfen.");
          }
          Run run = record.run();
          if (run.running() && run.indexBuildStartedAt() != null && ownerAlive()) {
            throw new ConflictException(
                "Das Umschalten auf das neue Modell läuft bereits; der Wechsel lässt sich nicht"
                    + " mehr verwerfen.");
          }
          Optional<Change> change = models.restorePrevious();
          if (change.isEmpty()) {
            throw new ConflictException(
                "Für diesen Wechsel ist kein bisheriges Modell gespeichert; zurück geht es nur mit"
                    + " einem neuen Wechsel.");
          }
          String endedStatus = run.running() ? RunStatus.CANCELLED.name() : "NONE";
          store.endRun(null, RunStatus.CANCELLED, Instant.now(clock), DISCARDED_MESSAGE);
          schema.dropTargetColumn();
          Run ended = store.load().map(EmbeddingIndexRecord::run).orElse(Run.NONE);
          store.save(new EmbeddingIndexRecord(record.current(), null, ended));
          Counted refill = countedWithout(EmbeddingIndexState.EMBEDDING_COLUMN);
          if (schema.chunksWithout(EmbeddingIndexState.EMBEDDING_COLUMN) > 0) {
            runs.startWithin(RunKind.REFILL, record.current().generation(), userId, organizationId);
          }
          Map<String, Object> after = new LinkedHashMap<>(change.get().after());
          after.put("runStatus", endedStatus);
          after.put("refillChunks", refill.chunks());
          after.put("privateLibrariesCounted", refill.privateCounted());
          record(
              userId,
              organizationId,
              AuditEventType.EMBEDDING_MODEL_CHANGE_DISCARDED,
              change.get().before(),
              after);
          return new Snapshot(record.current(), record.current(), null);
        });
  }

  /**
   * Moves the installed model - during a change the target model - to another endpoint without a
   * re-embedding: only for the same model name and size whose probe vectors are at least {@link
   * EmbeddingAnchor#MIN_SIMILARITY} similar to the anchor. Refused while a run works.
   *
   * @throws ConflictException when the probe names another model, a run works, or the instances run
   *     different versions
   * @throws TooManyRequestsException beyond the limit of endpoint changes
   */
  public EmbeddingReindexProgress changeEndpoint(Draft draft, UUID userId, UUID organizationId) {
    count(ENDPOINT_COUNTER);
    requireSameVersions();
    Resolved resolved = models.resolve(draft);
    ProbeOutcome probed = models.probe(resolved.endpoint());
    if (!probed.succeeded()) {
      throw FieldValidationException.of(
          EmbeddingModelAdministration.PREFIX + ".baseUrl",
          ModelEndpointRules.INVALID,
          probed.message());
    }
    Comparison comparison = models.compare(resolved.endpoint(), probed);
    if (comparison.kind() != Kind.ENDPOINT_CHANGE) {
      throw new ConflictException(endpointRefusal(comparison));
    }
    return locked(
        () -> {
          models.requireUnchanged(resolved, comparison);
          EmbeddingIndexRecord record = requireRecordForUpdate();
          if (record.run().running() && ownerAlive()) {
            throw new ConflictException(
                "Während ein Lauf des Vektorindex arbeitet, lässt sich der Endpunkt nicht ändern."
                    + " Bitte den Lauf zuerst anhalten.");
          }
          Change change = models.moveEndpoint(resolved);
          long generation = ((Number) change.after().get("generation")).longValue();
          store.updateBaseUrl(generation, resolved.endpoint().baseUrl());
          Map<String, Object> after = new LinkedHashMap<>(change.after());
          after.put("similarity", comparison.similarity());
          after.put("duringChange", record.target() != null);
          record(
              userId,
              organizationId,
              AuditEventType.EMBEDDING_MODEL_ENDPOINT_CHANGED,
              change.before(),
              after);
          EmbeddingIndexRecord updated = store.load().orElse(record);
          return new Snapshot(
              updated.current(),
              updated.target() == null ? updated.current() : updated.target(),
              updated.target());
        });
  }

  private static String endpointRefusal(Comparison comparison) {
    if (comparison.similarity() == null) {
      return "Der Endpunkt lässt sich nicht als dasselbe Modell bestätigen: Modell-Kennung oder"
          + " Dimension weichen ab, oder ohne gespeicherten Anker ist der bisherige Endpunkt nicht"
          + " erreichbar. Der Weg ist ein Wechsel des Modells mit Neueinbettung.";
    }
    return String.format(
        Locale.GERMANY,
        "Der Endpunkt bettet anders ein als das gespeicherte Modell (Ähnlichkeit %.4f, verlangt"
            + " mindestens %.2f). Der Weg ist ein Wechsel des Modells mit Neueinbettung.",
        comparison.similarity(),
        EmbeddingAnchor.MIN_SIMILARITY);
  }

  /**
   * Runs {@code change} under this instance's column exclusion and in one transaction under the
   * schema lock with a limited wait for table locks, replaces this instance's state with the
   * snapshot it returns after the commit, and answers the progress. The versions of the instances
   * are checked again under the lock, so no instance of another version joins unnoticed.
   */
  private EmbeddingReindexProgress locked(Supplier<Snapshot> change) {
    try {
      state.exclusively(
          () -> {
            Snapshot snapshot =
                transactions.execute(
                    status -> {
                      schema.limitLockWait();
                      schema.lockSchemaChanges();
                      requireSameVersions();
                      return change.get();
                    });
            state.replace(snapshot);
            return null;
          });
    } catch (DataAccessException e) {
      if (isLockTimeout(e)) {
        throw new ConflictException(LOCK_NOT_GRANTED);
      }
      throw e;
    }
    return runs.progress();
  }

  /** PostgreSQL's {@code lock_not_available} (55P03) after the wait for a table lock. */
  private static boolean isLockTimeout(DataAccessException e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
        return true;
      }
      cause = cause.getCause();
    }
    return e instanceof PessimisticLockingFailureException;
  }

  private EmbeddingIndexRecord requireRecordForUpdate() {
    return store
        .loadForUpdate()
        .orElseThrow(() -> new ConflictException("Der Vektorindex ist noch nicht eingerichtet."));
  }

  private void requireSameVersions() {
    if (!currentInstance.allLiveInstancesRunThisVersion()) {
      throw new ConflictException(VERSIONS_DIFFER);
    }
  }

  private boolean ownerAlive() {
    return store
        .runOwner()
        .map(
            owner ->
                owner.equals(currentInstance.id())
                    ? currentInstance.heartbeatCurrent()
                    : instanceRegistry.isAlive(owner))
        .orElse(false);
  }

  private void count(String counter) {
    Decision decision = counters.tryCount(counter, WINDOW, CALLS_PER_WINDOW);
    if (!decision.allowed()) {
      throw new TooManyRequestsException(decision.retryAfterSeconds());
    }
  }

  /** Chunks as the page counts them, the private libraries only from the minimum group size. */
  private record Counted(long chunks, boolean privateCounted) {}

  private Counted counted() {
    if (schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN) == null) {
      return new Counted(0, false);
    }
    boolean privateCounted =
        personThreshold.discloses(libraryRepository.countAllPrivateLibraryOwners());
    ChunkCounts counts = schema.chunkCounts(EmbeddingIndexState.EMBEDDING_COLUMN);
    return new Counted(
        counts.sharedTotal() + (privateCounted ? counts.privateTotal() : 0), privateCounted);
  }

  private Counted countedWithout(String column) {
    boolean privateCounted =
        personThreshold.discloses(libraryRepository.countAllPrivateLibraryOwners());
    ChunkCounts counts = schema.chunkCounts(column);
    return new Counted(
        counts.sharedWithout() + (privateCounted ? counts.privateWithout() : 0), privateCounted);
  }

  private Preview preview(int dimensions, Double chunksPerSecond) {
    Counted counted = counted();
    long characters = 0;
    if (counted.chunks() > 0) {
      TextLengths lengths = schema.textLengths();
      characters = lengths.shared() + (counted.privateCounted() ? lengths.privateLibraries() : 0);
    }
    long tokens = (characters + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN;
    double secondsPerChunk =
        chunksPerSecond == null || chunksPerSecond <= 0
            ? rateEstimator.configuredSecondsPerChunk()
            : 1.0 / chunksPerSecond;
    long seconds = (long) Math.ceil(counted.chunks() * secondsPerChunk);
    long vectors = counted.chunks() * (4L * dimensions + VECTOR_OVERHEAD_BYTES);
    long lower = 2 * vectors;
    long table = schema.tableBytes();
    return new Preview(
        counted.chunks(),
        counted.privateCounted(),
        tokens,
        seconds,
        chunksPerSecond == null ? RateSource.CONFIGURED : RateSource.MEASURED,
        lower,
        lower + table,
        table);
  }

  private void record(
      UUID userId,
      UUID organizationId,
      AuditEventType type,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(userId)
            .type(type)
            .object(AuditObjectType.SYSTEM_SETTING, AUDIT_OBJECT_ID, AUDIT_OBJECT_LABEL)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
