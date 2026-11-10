package io.opaa.indexing.maintenance;

import io.opaa.indexing.chunk.VectorChunkStore;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Removes the chunks of documents whose row no longer exists, once at every start (ADR-0053): they
 * are unreachable through any document yet would still be found by the search of their library. The
 * same removal the pipeline and embedding re-index apply to such a chunk set, through {@link
 * VectorChunkStore#deleteByDocumentId}.
 *
 * <p>Every writer stores a document's row before its chunks, so a chunk set without its row is the
 * remnant of a deletion, never one in progress; a start on another instance meanwhile only finds
 * less. The candidates come from {@code chunk_full_text}, whose {@code document_id} index answers
 * the anti-join without reading the vector table.
 */
@Component
public class OrphanedChunkCleanup {

  /** Document ids per statement; a start with more remnants loops until none is left. */
  static final int BATCH_SIZE = 500;

  private static final Logger log = LoggerFactory.getLogger(OrphanedChunkCleanup.class);

  private final JdbcTemplate jdbcTemplate;
  private final VectorChunkStore vectorChunkStore;

  public OrphanedChunkCleanup(JdbcTemplate jdbcTemplate, VectorChunkStore vectorChunkStore) {
    this.jdbcTemplate = jdbcTemplate;
    this.vectorChunkStore = vectorChunkStore;
  }

  /** Runs once, when the application is ready. */
  @EventListener(ApplicationReadyEvent.class)
  public void removeOnStartup() {
    int removed = removeAll();
    if (removed > 0) {
      log.warn("Removed the chunks of {} document(s) whose row no longer exists", removed);
    }
  }

  /**
   * Removes the chunks of every document id that the full-text index names and {@code documents}
   * does not.
   *
   * @return how many documents' chunks went
   */
  public int removeAll() {
    int removed = 0;
    while (true) {
      List<UUID> orphaned =
          jdbcTemplate.queryForList(
              "SELECT DISTINCT f.document_id FROM chunk_full_text f WHERE NOT EXISTS"
                  + " (SELECT 1 FROM documents d WHERE d.id = f.document_id) LIMIT ?",
              UUID.class,
              BATCH_SIZE);
      for (UUID documentId : orphaned) {
        vectorChunkStore.deleteByDocumentId(documentId);
      }
      removed += orphaned.size();
      if (orphaned.size() < BATCH_SIZE) {
        return removed;
      }
    }
  }
}
