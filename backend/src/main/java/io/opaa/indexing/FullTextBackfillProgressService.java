package io.opaa.indexing;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The queryable full-text backfill fill state (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a") - the data source for the administration page's index status and for the "Volltextpfad
 * inaktiv oder unvollständig" alarm (Arbeitspaket 5, not built here). Read-only counterpart of
 * {@link FullTextBackfillService}, which writes the {@code chunk_full_text} rows these counts read.
 */
@Component
public class FullTextBackfillProgressService {

  private final JdbcTemplate jdbcTemplate;

  public FullTextBackfillProgressService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * The fill state of one library. Both counts come from a single query (a scalar subquery per
   * side), not two separate round trips, so a backfill batch committing concurrently cannot produce
   * an inconsistent pair (e.g. {@code indexedChunks > totalChunks} from a read of {@code
   * chunk_full_text} that landed after the read of {@code vector_store}, or vice versa - {@code
   * READ COMMITTED} still allows that across two statements).
   */
  public FullTextBackfillProgress progressForLibrary(UUID libraryId) {
    String sql =
        "SELECT "
            + "  (SELECT count(*) FROM vector_store WHERE metadata->>'library_id' = ?) AS total, "
            + "  (SELECT count(*) FROM chunk_full_text WHERE library_id = ?) AS indexed";
    return jdbcTemplate.queryForObject(
        sql,
        (rs, rowNum) ->
            new FullTextBackfillProgress(libraryId, rs.getLong("total"), rs.getLong("indexed")),
        libraryId.toString(),
        libraryId);
  }

  /**
   * The fill state of every library that has at least one chunk in {@code vector_store} or {@code
   * chunk_full_text} - a {@code FULL OUTER JOIN} rather than one query per library, so a library
   * with chunks only on one side (fully un-backfilled, or a stale full-text row left behind by a
   * bug) is still reported instead of silently missing from the result.
   */
  public List<FullTextBackfillProgress> progressForAllLibraries() {
    String sql =
        "SELECT COALESCE(v.library_id, f.library_id) AS library_id, "
            + "       COALESCE(v.total, 0) AS total, "
            + "       COALESCE(f.indexed, 0) AS indexed "
            + "FROM ("
            + "  SELECT (metadata->>'library_id')::uuid AS library_id, count(*) AS total "
            + "  FROM vector_store WHERE metadata->>'library_id' IS NOT NULL GROUP BY 1"
            + ") v "
            + "FULL OUTER JOIN ("
            + "  SELECT library_id, count(*) AS indexed FROM chunk_full_text GROUP BY 1"
            + ") f ON v.library_id = f.library_id";
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new FullTextBackfillProgress(
                (UUID) rs.getObject("library_id"), rs.getLong("total"), rs.getLong("indexed")));
  }
}
