package io.opaa.indexing;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link FullTextBackfillService#backfillBatch} on a fixed tick (docs/features/hybrid-
 * retrieval.md, "Arbeitspaket 2a") - one small batch every {@code
 * opaa.indexing.full-text-backfill.tick-ms} (default {@link #DEFAULT_TICK_MS}), not a tight loop,
 * so the backfill stays nachrangig (low priority) against query/ingest traffic on the same database
 * rather than saturating it. No leader election, no distributed lock: assumes exactly one backend
 * process (ADR-0021), matching every other {@code @Scheduled} job in this package ({@link
 * LibraryIndexingScheduler}, {@link IndexingJobRecoveryScheduler}). Resumability itself does not
 * depend on this scheduler at all - see {@link FullTextBackfillService}'s own Javadoc - a second
 * instance ticking concurrently would only mean duplicate, harmless (idempotent) work, not a
 * correctness risk; it is left unguarded here for the same reason {@code
 * AuditRetentionScheduler#deleteExpiredAuditLogPartitions} is.
 *
 * <p><b>Goes dormant once drained.</b> Every chunk written after the write path started indexing
 * both stores atomically (see {@link VectorChunkStore#addChunks}) already gets its {@code
 * chunk_full_text} row at write time - the backlog this backfill drains can only ever shrink, never
 * grow again. Once a tick sees an empty backlog ({@link #drained} set to {@code true}), every later
 * tick returns immediately without touching the database at all, rather than repeating the {@code
 * NOT EXISTS} anti-join scan forever. Both {@link #drained} and {@link #consecutiveFailures} are
 * plain fields, not persisted - reset to their initial state on every process restart, which is
 * exactly the trigger meant to make either worth re-checking (a freshly deployed {@link
 * FullTextChunkStore#CURRENT_TSV_VERSION} bump, or a transient failure whose cause was fixed and
 * shipped in the new deployment).
 *
 * <p>A failing batch is logged and retried on the next tick, not left to kill this scheduled method
 * silently (Spring drops a {@code @Scheduled} method's future ticks entirely if one throws) - see
 * {@link #runBackfillBatch}. After {@link #MAX_CONSECUTIVE_FAILURES} consecutive failures, ticking
 * stops entirely (one {@code ERROR} log, not an unbounded stream of {@code WARN} logs every tick)
 * until the next process restart resets {@link #consecutiveFailures}.
 *
 * <p><b>What reaches this class as a failure, after #1093.</b> {@link
 * FullTextBackfillService#backfillBatch} isolates and permanently records a single malformed
 * ("poison") chunk itself (see that class's own Javadoc) rather than letting it fail the whole
 * batch - a batch of otherwise-healthy chunks with one bad row among them therefore returns
 * normally, indexing the healthy ones and skipping only the bad one, and never reaches {@link
 * #runBackfillBatch}'s {@code catch} at all. Only a failure that is not attributable to any single
 * row - the database itself unreachable, the connection pool exhausted - still propagates here, so
 * this class's consecutive-failure backoff is reserved for exactly that: a systemic condition a
 * smaller {@code batchSize} or a different chunk selection could not have avoided.
 */
@Component
public class FullTextBackfillScheduler {

  private static final Logger log = LoggerFactory.getLogger(FullTextBackfillScheduler.class);

  /** Five seconds - see this class's own Javadoc for the low-impact reasoning. */
  static final long DEFAULT_TICK_MS = 5_000L;

  /**
   * Consecutive failed ticks tolerated before ticking halts for the rest of this process's lifetime
   * - bounds how many {@code WARN} logs a persistently broken batch (e.g. a database outage lasting
   * longer than a restart cycle, or a genuinely malformed row) can produce.
   */
  static final int MAX_CONSECUTIVE_FAILURES = 5;

  private final FullTextBackfillService backfillService;
  private final IndexingProperties properties;
  private final AtomicBoolean drained = new AtomicBoolean(false);
  private final AtomicInteger consecutiveFailures = new AtomicInteger();

  public FullTextBackfillScheduler(
      FullTextBackfillService backfillService, IndexingProperties properties) {
    this.backfillService = backfillService;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${opaa.indexing.full-text-backfill.tick-ms:" + DEFAULT_TICK_MS + "}")
  public void runBackfillBatch() {
    if (drained.get() || consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
      return;
    }
    try {
      int processed = backfillService.backfillBatch(properties.fullTextBackfill().batchSize());
      consecutiveFailures.set(0);
      if (processed == 0) {
        drained.set(true);
        log.debug("Full-text backfill backlog drained - scheduler tick going dormant");
      } else {
        log.debug("Full-text backfill resolved {} chunk(s) this tick", processed);
      }
    } catch (RuntimeException e) {
      int failures = consecutiveFailures.incrementAndGet();
      if (failures >= MAX_CONSECUTIVE_FAILURES) {
        log.error(
            "Full-text backfill batch failed {} times in a row - halting ticks until the next"
                + " restart",
            failures,
            e);
      } else {
        log.warn("Full-text backfill batch failed - will retry on the next tick", e);
      }
    }
  }
}
