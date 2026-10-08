package io.opaa.eval;

import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Planner statistics and result-window check for the raw-vector path. An HNSW index scan returns at
 * most {@code hnsw.ef_search} (default 40) rows whatever the requested top-k; the raw-vector
 * metrics assume the full window. {@link #refresh} removes the dependency on autovacuum timing;
 * whether the planner then scans exactly still depends on table size and top-k, which {@link
 * #requireFullWindow} checks per run instead of assuming.
 */
final class VectorStoreStatistics {

  private VectorStoreStatistics() {}

  /** Collects the planner statistics of {@code vector_store}; call once the corpus is indexed. */
  static void refresh(JdbcTemplate jdbcTemplate, Logger log) {
    jdbcTemplate.execute("ANALYZE vector_store");
    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT reltuples::bigint FROM pg_class WHERE oid = 'vector_store'::regclass",
            Long.class);
    log.info("Planner statistics of vector_store collected: {} rows", rows);
  }

  /**
   * Throws unless every unfiltered search returned {@code min(chunkTopK, totalChunks)} hits.
   *
   * @param minHitsReturned the smallest hit count of any unfiltered search of the run
   */
  static void requireFullWindow(int minHitsReturned, int chunkTopK, int totalChunks) {
    int expected = Math.min(chunkTopK, totalChunks);
    if (minHitsReturned < expected) {
      throw new IllegalStateException(
          "Vector search returned "
              + minHitsReturned
              + " instead of "
              + expected
              + " hits (chunkTopK="
              + chunkTopK
              + ", "
              + totalChunks
              + " chunks indexed) - most likely an HNSW index scan capped by hnsw.ef_search."
              + " The raw-vector metrics require the full window.");
    }
  }
}
