package io.opaa.indexing;

import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p><b>Goes dormant once drained (#1047 review, finding 5).</b> Every chunk written after #1047
 * already gets its {@code chunk_full_text} row at write time (see {@link
 * VectorChunkStore#addChunks}) - the backlog this backfill drains can only ever shrink, never grow
 * again. Once a tick sees an empty backlog ({@link #drained} set to {@code true}), every later tick
 * returns immediately without touching the database at all, rather than repeating the {@code NOT
 * EXISTS} anti-join scan forever. {@link #drained} is reset to {@code false} on every process
 * restart (a plain field, not persisted) - one more, cheap check on the first tick after a restart,
 * not a correctness requirement.
 *
 * <p>A failing batch is logged and retried on the next tick, not left to kill this scheduled method
 * silently (Spring drops a {@code @Scheduled} method's future ticks entirely if one throws) - see
 * {@link #runBackfillBatch}. Isolating a single malformed ("poison") chunk within a batch is not
 * implemented; a batch that keeps failing keeps failing at the same {@code batchSize} on every
 * retry. Accepted for #1047: {@code FileProcessingService} has always written well-formed rows, so
 * this guards a hypothetical, not an observed failure mode.
 */
@Component
public class FullTextBackfillScheduler {

  private static final Logger log = LoggerFactory.getLogger(FullTextBackfillScheduler.class);

  /** Five seconds - see this class's own Javadoc for the low-impact reasoning. */
  static final long DEFAULT_TICK_MS = 5_000L;

  private final FullTextBackfillService backfillService;
  private final IndexingProperties properties;
  private final AtomicBoolean drained = new AtomicBoolean(false);

  public FullTextBackfillScheduler(
      FullTextBackfillService backfillService, IndexingProperties properties) {
    this.backfillService = backfillService;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${opaa.indexing.full-text-backfill.tick-ms:" + DEFAULT_TICK_MS + "}")
  public void runBackfillBatch() {
    if (drained.get()) {
      return;
    }
    try {
      int processed = backfillService.backfillBatch(properties.fullTextBackfill().batchSize());
      if (processed == 0) {
        drained.set(true);
        log.debug("Full-text backfill backlog drained - scheduler tick going dormant");
      } else {
        log.debug("Full-text backfill indexed {} chunk(s) this tick", processed);
      }
    } catch (RuntimeException e) {
      log.warn("Full-text backfill batch failed - will retry on the next tick", e);
    }
  }
}
