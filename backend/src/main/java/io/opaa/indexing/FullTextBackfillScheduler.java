package io.opaa.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link FullTextBackfillService#backfillBatch} on a fixed tick (docs/features/hybrid-
 * retrieval.md, "Arbeitspaket 2a") - one small batch every {@link #TICK_DELAY_MILLIS}, not a tight
 * loop, so the backfill stays nachrangig (low priority) against query/ingest traffic on the same
 * database rather than saturating it. No leader election, no distributed lock: assumes exactly one
 * backend process (ADR-0021), matching every other {@code @Scheduled} job in this package ({@link
 * LibraryIndexingScheduler}, {@link IndexingJobRecoveryScheduler}). Resumability itself does not
 * depend on this scheduler at all - see {@link FullTextBackfillService}'s own Javadoc - a second
 * instance ticking concurrently would only mean duplicate, harmless (idempotent) work, not a
 * correctness risk; it is left unguarded here for the same reason {@code
 * AuditRetentionScheduler#deleteExpiredAuditLogPartitions} is.
 */
@Component
public class FullTextBackfillScheduler {

  private static final Logger log = LoggerFactory.getLogger(FullTextBackfillScheduler.class);

  /**
   * Five seconds: frequent enough that a freshly migrated installation's backlog drains in a
   * reasonable time at the default batch size, infrequent enough that the anti-join query {@link
   * FullTextBackfillService#backfillBatch} runs on every tick never competes noticeably with
   * foreground query/ingest load.
   */
  private static final long TICK_DELAY_MILLIS = 5_000L;

  private final FullTextBackfillService backfillService;
  private final IndexingProperties properties;

  public FullTextBackfillScheduler(
      FullTextBackfillService backfillService, IndexingProperties properties) {
    this.backfillService = backfillService;
    this.properties = properties;
  }

  @Scheduled(fixedDelay = TICK_DELAY_MILLIS)
  public void runBackfillBatch() {
    int processed = backfillService.backfillBatch(properties.fullTextBackfill().batchSize());
    if (processed > 0) {
      log.debug("Full-text backfill indexed {} chunk(s) this tick", processed);
    }
  }
}
