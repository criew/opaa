package io.opaa.metadata;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import io.opaa.metadata.EmbeddingRateMeasurements.Totals;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * How long embedding one chunk takes on this installation - the rate behind "4.812 Chunks, rund 40
 * Minuten" (metadata-schema.md, "Der Reindex-Preis, ehrlich ausgewiesen"). Contract: the measured
 * mean of the embedding calls every instance of the installation has actually made with the
 * configured model once {@link #MIN_MEASURED_CHUNKS} of them exist, the configured {@code
 * opaa.indexing.embedding-rate-estimate} before that - a fresh installation must still be able to
 * name a number.
 *
 * <p>The measurements are summed in {@link EmbeddingRateMeasurements} (ADR-0053, Entscheidung 12),
 * so every instance names the same duration. This process adds its own in portions of {@link
 * #FLUSH_CHUNKS} chunks and counts what it has not added yet into its own estimate.
 */
@PerInstance("the fallback until the installation has measured enough calls")
@NoDataImpact("a cost estimate")
@Component
public class EmbeddingRateEstimator {

  /** Below this many measured chunks a mean says more about warm-up than about throughput. */
  static final long MIN_MEASURED_CHUNKS = 20;

  /** How many measured chunks this process collects before it adds them to the shared sum. */
  static final long FLUSH_CHUNKS = 20;

  private static final Logger log = LoggerFactory.getLogger(EmbeddingRateEstimator.class);

  /** Where the rate a given estimate used came from. */
  public enum RateSource {
    MEASURED,
    CONFIGURED
  }

  private final double configuredChunksPerSecond;
  private final EmbeddingRateMeasurements shared;

  /** Measured here and not yet added to {@link #shared}; guarded by {@link #pendingLock}. */
  private final Object pendingLock = new Object();

  private long pendingChunks;
  private long pendingNanos;

  /**
   * Embedding calls currently in flight, and how many have ever started. Only a call that was alone
   * at both ends contributes a measurement: the wall times of concurrent sub-batches ({@code
   * opaa.indexing.embedding-concurrency}) overlap, and summing them would report a rate up to that
   * factor too pessimistic - while still calling it {@code MEASURED}.
   */
  private final AtomicInteger inFlight = new AtomicInteger();

  private final AtomicLong starts = new AtomicLong();

  /** An estimator whose measurements stay in this process - for a context without a database. */
  public EmbeddingRateEstimator(double configuredChunksPerSecond) {
    this(configuredChunksPerSecond, new ProcessLocalEmbeddingRateMeasurements());
  }

  @Autowired
  public EmbeddingRateEstimator(
      @Value("${opaa.indexing.embedding-rate-estimate:4.0}") double configuredChunksPerSecond,
      EmbeddingRateMeasurements shared) {
    this.configuredChunksPerSecond =
        configuredChunksPerSecond > 0 ? configuredChunksPerSecond : 4.0;
    this.shared = shared;
  }

  /**
   * Marks an embedding call as started; the returned token is handed back to {@link #record}. Every
   * caller must pair the two, in a {@code try}/{@code finally} if the call may throw.
   */
  public long started() {
    inFlight.incrementAndGet();
    return starts.incrementAndGet();
  }

  /**
   * Adds one completed embedding call to the mean - unless another one overlapped it, in which case
   * its wall time says more about the concurrency than about the throughput and is dropped.
   *
   * @param token what {@link #started()} returned for this call
   */
  public void record(int chunks, long nanos, long token) {
    // Alone at the end and no further call started in between: exactly the calls whose wall time
    // is the duration of that embedding round trip and nothing else.
    boolean ranAlone = inFlight.getAndDecrement() == 1 && starts.get() == token;
    if (!ranAlone || chunks <= 0 || nanos <= 0) {
      return;
    }
    long flushChunks;
    long flushNanos;
    synchronized (pendingLock) {
      pendingChunks += chunks;
      pendingNanos += nanos;
      if (pendingChunks < FLUSH_CHUNKS) {
        return;
      }
      flushChunks = pendingChunks;
      flushNanos = pendingNanos;
      pendingChunks = 0;
      pendingNanos = 0;
    }
    flush(flushChunks, flushNanos);
  }

  public RateSource rateSource() {
    return measured().chunks() >= MIN_MEASURED_CHUNKS ? RateSource.MEASURED : RateSource.CONFIGURED;
  }

  /** Seconds per chunk at the current rate; never zero, so an estimate is never falsely instant. */
  public double secondsPerChunk() {
    Totals measured = measured();
    if (measured.chunks() >= MIN_MEASURED_CHUNKS) {
      return measured.nanos() / 1_000_000_000.0 / measured.chunks();
    }
    return 1.0 / configuredChunksPerSecond;
  }

  /**
   * Seconds per chunk at the configured estimate, for a model nothing was measured with yet, such
   * as the target of a model change.
   */
  public double configuredSecondsPerChunk() {
    return 1.0 / configuredChunksPerSecond;
  }

  /** Adds this process's measurements to the shared sum before it stops. */
  @PreDestroy
  void flushPending() {
    long flushChunks;
    long flushNanos;
    synchronized (pendingLock) {
      flushChunks = pendingChunks;
      flushNanos = pendingNanos;
      pendingChunks = 0;
      pendingNanos = 0;
    }
    if (flushChunks > 0) {
      flush(flushChunks, flushNanos);
    }
  }

  /** The shared sum plus what this process has not added yet. */
  private Totals measured() {
    Totals sum;
    try {
      sum = shared.totals();
    } catch (DataAccessException e) {
      log.debug("Shared embedding measurements unreadable: {}", e.getMessage());
      sum = Totals.NONE;
    }
    synchronized (pendingLock) {
      return sum.plus(pendingChunks, pendingNanos);
    }
  }

  /**
   * Adds a portion to the shared sum; one that cannot be added goes back to the pending part, so a
   * measurement is never lost to a database hiccup and an embedding call never fails because of it.
   */
  private void flush(long chunks, long nanos) {
    try {
      shared.add(chunks, nanos);
    } catch (DataAccessException e) {
      log.debug("Shared embedding measurements not updated: {}", e.getMessage());
      synchronized (pendingLock) {
        pendingChunks += chunks;
        pendingNanos += nanos;
      }
    }
  }

  /** The expected runtime of {@code chunks} embedding calls, rounded up to a full second. */
  public long estimatedSeconds(long chunks) {
    if (chunks <= 0) {
      return 0;
    }
    return (long) Math.ceil(chunks * secondsPerChunk());
  }
}
