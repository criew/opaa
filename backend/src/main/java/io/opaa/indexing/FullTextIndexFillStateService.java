package io.opaa.indexing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The queryable full-text index fill state (docs/features/hybrid-retrieval.md, "Arbeitspaket 2a") -
 * the data source for the administration page's index status and for the "Volltextpfad inaktiv oder
 * unvollständig" alarm. Read-only counterpart of {@link FullTextChunkStore}, which writes the
 * {@code chunk_full_text} rows these counts read.
 *
 * <p>{@code missingChunks} - not a subtraction of {@code totalChunks} and {@code indexedChunks} -
 * is what {@link FullTextIndexFillState#isComplete()} is defined on; see that record's own Javadoc.
 *
 * <p><b>Every count is filtered to {@link FullTextChunkStore#CURRENT_TSV_VERSION}, including {@code
 * indexedChunks}.</b> A row written under an older version carries lexemes the lexical search path
 * does not query for (it applies the same filter), so counting it as indexed would report a library
 * as complete whose rows that path cannot use.
 *
 * <p>Table/schema name are read from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} itself binds, mirroring {@code io.opaa.query.ChunkEmbeddingLookup}'s
 * pattern (never hardcoded independently of that configuration).
 */
@Component
public class FullTextIndexFillStateService {

  private final JdbcTemplate jdbcTemplate;
  private final String schemaName;
  private final String tableName;

  public FullTextIndexFillStateService(
      JdbcTemplate jdbcTemplate,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.schemaName = schemaName;
    this.tableName = tableName;
  }

  /**
   * The fill state of one library. All three counts come from a single query (a scalar subquery
   * each), not separate round trips, so a concurrently committing indexing run cannot produce an
   * inconsistent tuple - {@code READ COMMITTED} allows exactly that across separate statements.
   *
   * <p>The {@code vector_store} count is filtered by {@code metadata->>'library_id'}, backed by the
   * expression index from {@code changes/012-vector-store-library-id-index.yaml}.
   */
  public FullTextIndexFillState fillStateForLibrary(UUID libraryId) {
    String vectorStoreTable = schemaName + "." + tableName;
    String sql =
        "SELECT "
            + "  (SELECT count(*) FROM "
            + vectorStoreTable
            + " WHERE metadata->>'library_id' = ?) AS total, "
            + "  (SELECT count(*) FROM chunk_full_text "
            + "     WHERE library_id = ? AND content_tsv_version = ?) AS indexed, "
            + "  (SELECT count(*) FROM "
            + vectorStoreTable
            + " v WHERE metadata->>'library_id' = ? "
            + "     AND NOT EXISTS ("
            + "       SELECT 1 FROM chunk_full_text f "
            + "       WHERE f.chunk_id = v.id AND f.content_tsv_version = ?"
            + "     )"
            + "  ) AS missing";
    return jdbcTemplate.queryForObject(
        sql,
        (rs, rowNum) ->
            new FullTextIndexFillState(
                libraryId, rs.getLong("total"), rs.getLong("indexed"), rs.getLong("missing")),
        libraryId.toString(),
        libraryId,
        FullTextChunkStore.CURRENT_TSV_VERSION,
        libraryId.toString(),
        FullTextChunkStore.CURRENT_TSV_VERSION);
  }

  /**
   * The fill state of each of {@code libraryIds} that has at least one chunk in {@code
   * vector_store} or {@code chunk_full_text} - a three-way {@code FULL OUTER JOIN} rather than one
   * query per library, so a library with chunks only on one side (never indexed at all, or a stale
   * full-text row left behind by a bug) is still reported instead of silently missing from the
   * result. Libraries without a single chunk on any side do not appear; the caller supplies the
   * zero state for those.
   *
   * <p>The caller always passes the libraries it is allowed to display, never "all" - the query
   * would otherwise aggregate across every organization for a display that shows one of them.
   *
   * <p>The {@code vector_store} subquery filters by {@code metadata->>'library_id'} (backed by the
   * expression index from {@code changes/012-vector-store-library-id-index.yaml}) and groups by
   * that same value cast to {@code uuid}; no separate index on the cast is needed - it only ever
   * runs over the rows the text-expression index already restricted the scan to.
   */
  public List<FullTextIndexFillState> fillStateForLibraries(Collection<UUID> libraryIds) {
    Set<UUID> distinct = new LinkedHashSet<>(libraryIds);
    if (distinct.isEmpty()) {
      return List.of();
    }
    String vectorStoreTable = schemaName + "." + tableName;
    String textPlaceholders = placeholders(distinct.size());
    String sql =
        "SELECT COALESCE(v.library_id, f.library_id, m.library_id) AS library_id, "
            + "       COALESCE(v.total, 0) AS total, "
            + "       COALESCE(f.indexed, 0) AS indexed, "
            + "       COALESCE(m.missing, 0) AS missing "
            + "FROM ("
            + "  SELECT (metadata->>'library_id')::uuid AS library_id, count(*) AS total "
            + "  FROM "
            + vectorStoreTable
            + "  WHERE metadata->>'library_id' IN ("
            + textPlaceholders
            + ") GROUP BY 1"
            + ") v "
            + "FULL OUTER JOIN ("
            + "  SELECT library_id, count(*) AS indexed FROM chunk_full_text "
            + "  WHERE library_id IN ("
            + textPlaceholders
            + ") AND content_tsv_version = ? GROUP BY 1"
            + ") f ON v.library_id = f.library_id "
            + "FULL OUTER JOIN ("
            + "  SELECT (v2.metadata->>'library_id')::uuid AS library_id, count(*) AS missing "
            + "  FROM "
            + vectorStoreTable
            + " v2 "
            + "  WHERE v2.metadata->>'library_id' IN ("
            + textPlaceholders
            + ") "
            + "    AND NOT EXISTS ("
            + "      SELECT 1 FROM chunk_full_text f2 "
            + "      WHERE f2.chunk_id = v2.id AND f2.content_tsv_version = ?"
            + "    ) "
            + "  GROUP BY 1"
            + ") m ON COALESCE(v.library_id, f.library_id) = m.library_id";

    List<Object> arguments = new ArrayList<>();
    distinct.forEach(id -> arguments.add(id.toString()));
    arguments.addAll(distinct);
    arguments.add(FullTextChunkStore.CURRENT_TSV_VERSION);
    distinct.forEach(id -> arguments.add(id.toString()));
    arguments.add(FullTextChunkStore.CURRENT_TSV_VERSION);

    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new FullTextIndexFillState(
                (UUID) rs.getObject("library_id"),
                rs.getLong("total"),
                rs.getLong("indexed"),
                rs.getLong("missing")),
        arguments.toArray());
  }

  private static String placeholders(int count) {
    return String.join(", ", Collections.nCopies(count, "?"));
  }
}
