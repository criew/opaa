package io.opaa.indexing.maintenance;

import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.instance.ScheduledRunLocks;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly, on one instance at a time (ADR-0053): removes the chunks of documents whose row no
 * longer exists - unreachable through any document, yet still found by the search of their library
 * - and clears the proposed values of rejected model extractions, which are no longer written.
 *
 * <p>A document id goes only once an earlier run on the same instance found it orphaned too, so a
 * chunk set is never removed in the run that first saw it; the ids of that earlier run are
 * process-local and start empty on every instance. The candidates come from {@code
 * chunk_full_text}, whose {@code document_id} index answers the anti-join without reading the
 * vector table. A failure is logged and leaves the next run to try again.
 */
@Component
public class OrphanedChunkCleanup {

  /** Document ids per query; a run walks all of them in id order. */
  static final int BATCH_SIZE = 500;

  /** How many orphaned ids one run remembers for the next; the rest wait for a later run. */
  static final int MAX_REMEMBERED = 100_000;

  private static final Logger log = LoggerFactory.getLogger(OrphanedChunkCleanup.class);

  private final JdbcTemplate jdbcTemplate;
  private final VectorChunkStore vectorChunkStore;
  private Set<UUID> seenByLastRun = Set.of();

  public OrphanedChunkCleanup(JdbcTemplate jdbcTemplate, VectorChunkStore vectorChunkStore) {
    this.jdbcTemplate = jdbcTemplate;
    this.vectorChunkStore = vectorChunkStore;
  }

  @Scheduled(cron = "0 41 3 * * *")
  @SchedulerLock(
      name = "orphaned-chunk-cleanup",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void runNightly() {
    try {
      int cleared = clearProposedValues();
      int removed = removeOrphans();
      if (cleared > 0) {
        log.info("Cleared the proposed value of {} rejected model extraction(s)", cleared);
      }
      if (removed > 0) {
        log.warn("Removed the chunks of {} document(s) whose row no longer exists", removed);
      }
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
   * Removes the chunks of every orphaned document id the previous call on this instance found
   * orphaned too, and remembers the others for the next call.
   *
   * @return how many documents' chunks went
   */
  public synchronized int removeOrphans() {
    Set<UUID> seenNow = new HashSet<>();
    int removed = 0;
    UUID after = new UUID(0, 0);
    while (true) {
      List<UUID> orphaned =
          jdbcTemplate.queryForList(
              "SELECT DISTINCT f.document_id FROM chunk_full_text f WHERE f.document_id > ?"
                  + " AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = f.document_id)"
                  + " ORDER BY f.document_id LIMIT ?",
              UUID.class,
              after,
              BATCH_SIZE);
      for (UUID documentId : orphaned) {
        if (seenByLastRun.contains(documentId)) {
          vectorChunkStore.deleteByDocumentId(documentId);
          removed++;
        } else if (seenNow.size() < MAX_REMEMBERED) {
          seenNow.add(documentId);
        }
      }
      if (orphaned.size() < BATCH_SIZE) {
        seenByLastRun = seenNow;
        return removed;
      }
      after = orphaned.get(orphaned.size() - 1);
    }
  }
}
