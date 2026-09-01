package io.opaa.indexing;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The queryable full-text backfill fill state (docs/features/hybrid-retrieval.md, "Arbeitspaket
 * 2a") - the data source for the administration page's index status and for the "Volltextpfad
 * inaktiv oder unvollständig" alarm (Arbeitspaket 5, not built here). Read-only counterpart of
 * {@link FullTextBackfillService}, which writes the {@code chunk_full_text} rows these counts read.
 *
 * <p>{@code missingChunks} - not a subtraction of {@code totalChunks} and {@code indexedChunks} -
 * is what {@link FullTextBackfillProgress#isComplete()} actually gates on; see that record's own
 * Javadoc (#1047 review, finding 2).
 *
 * <p>Table/schema name are read from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} itself binds, mirroring {@code io.opaa.query.ChunkEmbeddingLookup}'s pattern
 * (never hardcoded independently of that configuration).
 */
@Component
public class FullTextBackfillProgressService {

  private final JdbcTemplate jdbcTemplate;
  private final String schemaName;
  private final String tableName;

  public FullTextBackfillProgressService(
      JdbcTemplate jdbcTemplate,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.schemaName = schemaName;
    this.tableName = tableName;
  }

  /**
   * The fill state of one library. All three counts come from a single query (a scalar subquery
   * each), not separate round trips, so a backfill batch committing concurrently cannot produce an
   * inconsistent triple - {@code READ COMMITTED} allows exactly that across separate statements.
   *
   * <p>{@code progressForLibrary}/{@code progressForAllLibraries} deliberately query {@code
   * vector_store} with a full sequential-ish scan pattern (a {@code metadata->>'library_id'}
   * predicate has no expression index backing it) - acceptable at today's data volumes, flagged as
   * a future optimization point, not fixed here (tracked informally alongside #1053).
   */
  public FullTextBackfillProgress progressForLibrary(UUID libraryId) {
    String vectorStoreTable = schemaName + "." + tableName;
    String sql =
        "SELECT "
            + "  (SELECT count(*) FROM "
            + vectorStoreTable
            + " WHERE metadata->>'library_id' = ?) AS total, "
            + "  (SELECT count(*) FROM chunk_full_text WHERE library_id = ?) AS indexed, "
            + "  (SELECT count(*) FROM "
            + vectorStoreTable
            + " v WHERE metadata->>'library_id' = ? "
            + "     AND NOT EXISTS (SELECT 1 FROM chunk_full_text f WHERE f.chunk_id = v.id)"
            + "  ) AS missing";
    return jdbcTemplate.queryForObject(
        sql,
        (rs, rowNum) ->
            new FullTextBackfillProgress(
                libraryId, rs.getLong("total"), rs.getLong("indexed"), rs.getLong("missing")),
        libraryId.toString(),
        libraryId,
        libraryId.toString());
  }

  /**
   * The fill state of every library that has at least one chunk in {@code vector_store} or {@code
   * chunk_full_text} - a three-way {@code FULL OUTER JOIN} rather than one query per library, so a
   * library with chunks only on one side (fully un-backfilled, or a stale full-text row left behind
   * by a bug) is still reported instead of silently missing from the result.
   */
  public List<FullTextBackfillProgress> progressForAllLibraries() {
    String vectorStoreTable = schemaName + "." + tableName;
    String sql =
        "SELECT COALESCE(v.library_id, f.library_id, m.library_id) AS library_id, "
            + "       COALESCE(v.total, 0) AS total, "
            + "       COALESCE(f.indexed, 0) AS indexed, "
            + "       COALESCE(m.missing, 0) AS missing "
            + "FROM ("
            + "  SELECT (metadata->>'library_id')::uuid AS library_id, count(*) AS total "
            + "  FROM "
            + vectorStoreTable
            + "  WHERE metadata->>'library_id' IS NOT NULL GROUP BY 1"
            + ") v "
            + "FULL OUTER JOIN ("
            + "  SELECT library_id, count(*) AS indexed FROM chunk_full_text GROUP BY 1"
            + ") f ON v.library_id = f.library_id "
            + "FULL OUTER JOIN ("
            + "  SELECT (v2.metadata->>'library_id')::uuid AS library_id, count(*) AS missing "
            + "  FROM "
            + vectorStoreTable
            + " v2 "
            + "  WHERE v2.metadata->>'library_id' IS NOT NULL "
            + "    AND NOT EXISTS (SELECT 1 FROM chunk_full_text f2 WHERE f2.chunk_id = v2.id) "
            + "  GROUP BY 1"
            + ") m ON COALESCE(v.library_id, f.library_id) = m.library_id";
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new FullTextBackfillProgress(
                (UUID) rs.getObject("library_id"),
                rs.getLong("total"),
                rs.getLong("indexed"),
                rs.getLong("missing")));
  }
}
