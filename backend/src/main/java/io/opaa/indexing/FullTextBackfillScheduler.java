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
 * until the next process restart resets {@link #consecutiveFailures}. Isolating a single malformed
 * ("poison") chunk within a batch is not implemented - a batch that keeps failing keeps failing at
 * the same {@code batchSize} on every retry until it halts; tracked as a follow-up (#1093), not
 * addressed here since no malformed row has ever actually been observed.
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
        log.debug("Full-text backfill indexed {} chunk(s) this tick", processed);
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
