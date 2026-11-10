package io.opaa.indexing.maintenance;

import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.instance.ScheduledRunLocks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Nightly, on one instance at a time (ADR-0053): removes the chunks of documents whose row no
 * longer exists - unreachable through any document, yet still found by the search of their library
 * - and clears the proposed values of rejected model extractions, which are no longer written.
 *
 * <p>A document id goes only if it is still orphaned after {@link #SETTLE_TIME}: the run lists the
 * candidates, waits, and removes those a second look finds orphaned too, so a chunk set seen in the
 * middle of a write or a deletion is left alone. Nothing is kept between runs. The candidates come
 * from {@code chunk_full_text}, whose {@code document_id} index answers the anti-join without
 * reading the vector table. The wait runs on its own scheduler thread, not on the shared tick. A
 * failure is logged and leaves the next run to try again.
 */
@Component
public class OrphanedChunkCleanup {

  /** Document ids per query; a run walks all of them in id order. */
  static final int BATCH_SIZE = 500;

  /** How many orphaned ids one run looks at; the rest wait for the next run. */
  static final int MAX_CANDIDATES = 100_000;

  /** How long a candidate has to stay orphaned before its chunks go. */
  static final Duration SETTLE_TIME = Duration.ofSeconds(60);

  private static final String ORPHANED_AFTER =
      "SELECT DISTINCT f.document_id FROM chunk_full_text f WHERE f.document_id > ?"
          + " AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = f.document_id)"
          + " ORDER BY f.document_id LIMIT ?";

  private static final String STILL_ORPHANED =
      "SELECT DISTINCT f.document_id FROM chunk_full_text f WHERE f.document_id = ANY(?)"
          + " AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = f.document_id)";

  private static final Logger log = LoggerFactory.getLogger(OrphanedChunkCleanup.class);

  private final JdbcTemplate jdbcTemplate;
  private final VectorChunkStore vectorChunkStore;

  public OrphanedChunkCleanup(JdbcTemplate jdbcTemplate, VectorChunkStore vectorChunkStore) {
    this.jdbcTemplate = jdbcTemplate;
    this.vectorChunkStore = vectorChunkStore;
  }

  @Scheduled(cron = "0 41 3 * * *", scheduler = "orphanedChunkCleanupScheduler")
  @SchedulerLock(
      name = "orphaned-chunk-cleanup",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void runNightly() {
    try {
      int cleared = clearProposedValues();
      int removed = removeOrphans(SETTLE_TIME);
      if (cleared > 0) {
        log.info("Cleared the proposed value of {} rejected model extraction(s)", cleared);
      }
      if (removed > 0) {
        log.warn("Removed the chunks of {} document(s) whose row no longer exists", removed);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      log.warn("Orphaned chunk cleanup failed, the next run tries again", e);
    }
  }

  /**
   * Sets every remaining proposed value of a rejected model extraction to {@code null}; none is
   * written any more, so after the first run there is nothing left to clear.
   *
   * @return how many rows were cleared
   */
  public int clearProposedValues() {
    return jdbcTemplate.update(
        "UPDATE metadata_model_rejections SET proposed_value = NULL"
            + " WHERE proposed_value IS NOT NULL");
  }

  /**
   * Removes the chunks of every document id that is orphaned now and still after {@code settle}.
   *
   * @return how many documents' chunks went
   */
  int removeOrphans(Duration settle) throws InterruptedException {
    List<UUID> candidates = candidates();
    if (candidates.isEmpty()) {
      return 0;
    }
    Thread.sleep(settle);
    int removed = 0;
    for (int from = 0; from < candidates.size(); from += BATCH_SIZE) {
      List<UUID> batch = candidates.subList(from, Math.min(from + BATCH_SIZE, candidates.size()));
      List<UUID> stillOrphaned =
          jdbcTemplate.queryForList(
              STILL_ORPHANED, UUID.class, (Object) batch.toArray(UUID[]::new));
      for (UUID documentId : stillOrphaned) {
        vectorChunkStore.deleteByDocumentId(documentId);
        removed++;
      }
    }
    return removed;
  }

  private List<UUID> candidates() {
    List<UUID> candidates = new ArrayList<>();
    UUID after = new UUID(0, 0);
    while (candidates.size() < MAX_CANDIDATES) {
      List<UUID> orphaned =
          jdbcTemplate.queryForList(
              ORPHANED_AFTER,
              UUID.class,
              after,
              Math.min(BATCH_SIZE, MAX_CANDIDATES - candidates.size()));
      candidates.addAll(orphaned);
      if (orphaned.size() < BATCH_SIZE) {
        break;
      }
      after = orphaned.get(orphaned.size() - 1);
    }
    return candidates;
  }

  /** The thread of {@link #runNightly}: its wait blocks only this one, not the shared tick. */
  @Configuration(proxyBeanMethods = false)
  static class SchedulerConfiguration {

    @Bean(destroyMethod = "shutdown")
    TaskScheduler orphanedChunkCleanupScheduler() {
      ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
      scheduler.setPoolSize(1);
      scheduler.setThreadNamePrefix("orphaned-chunk-cleanup-");
      scheduler.setDaemon(true);
      scheduler.setWaitForTasksToCompleteOnShutdown(false);
      scheduler.initialize();
      return scheduler;
    }
  }
}
