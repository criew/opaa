package io.opaa.eval;

import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Collects the planner statistics of {@code vector_store} once the corpus is indexed, so the query
 * plan of every following similarity search no longer depends on whether autovacuum has analyzed
 * the table yet. Without statistics PostgreSQL plans the search as an HNSW index scan, which
 * returns at most {@code hnsw.ef_search} (default 40) rows whatever the requested top-k.
 */
final class VectorStoreStatistics {

  private VectorStoreStatistics() {}

  static void refresh(JdbcTemplate jdbcTemplate, Logger log) {
    jdbcTemplate.execute("ANALYZE vector_store");
    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT reltuples::bigint FROM pg_class WHERE oid = 'vector_store'::regclass",
            Long.class);
    log.info("Planner statistics of vector_store collected: {} rows", rows);
  }
}
