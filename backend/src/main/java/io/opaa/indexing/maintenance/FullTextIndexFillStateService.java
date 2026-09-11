package io.opaa.indexing.maintenance;

import io.opaa.indexing.chunk.FullTextChunkStore;
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
 * The queryable full-text index fill state (docs/features/hybrid-retrieval.md, "Arbeitspaket 2a"),
 * behind the administration page's index status. Read-only counterpart of {@link
 * FullTextChunkStore}.
 *
 * <p>A row is counted as indexed only at {@link FullTextChunkStore#CURRENT_TSV_VERSION} and as
 * outdated at any other version: it lacks the lexemes the current version adds, so counting it as
 * indexed would report a library whose re-index is still outstanding as up to date. The lexical
 * search path itself does not apply this filter (ADR-0028) - the fill state reports what still
 * needs the re-index, not what is unsearchable. Schema and table name come from the same {@code
 * spring.ai.vectorstore.pgvector.*} properties {@code PgVectorStore} binds.
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
   * The fill state of one library. All three counts come from a single query, not separate round
   * trips, so a concurrently committing indexing run cannot produce an inconsistent tuple - {@code
   * READ COMMITTED} allows exactly that across separate statements. The {@code vector_store} count
   * is filtered by {@code metadata->>'library_id'}, backed by its own expression index.
   */
  public FullTextIndexFillState fillStateForLibrary(UUID libraryId) {
    String vectorStoreTable = schemaName + "." + tableName;
    String chunkStillPresent = chunkStillPresent(vectorStoreTable);
    String sql =
        "SELECT "
            + "  (SELECT count(*) FROM "
            + vectorStoreTable
            + " WHERE metadata->>'library_id' = ?) AS total, "
            + "  (SELECT count(*) FROM chunk_full_text f "
            + "     WHERE f.library_id = ? AND f.content_tsv_version = ?"
            + chunkStillPresent
            + ") AS indexed, "
            + "  (SELECT count(*) FROM chunk_full_text f "
            + "     WHERE f.library_id = ? AND f.content_tsv_version <> ?"
            + chunkStillPresent
            + ") AS outdated";
    return jdbcTemplate.queryForObject(
        sql,
        (rs, rowNum) ->
            new FullTextIndexFillState(
                libraryId, rs.getLong("total"), rs.getLong("indexed"), rs.getLong("outdated")),
        libraryId.toString(),
        libraryId,
        FullTextChunkStore.CURRENT_TSV_VERSION,
        libraryId,
        FullTextChunkStore.CURRENT_TSV_VERSION);
  }

  /**
   * The fill state of each of {@code libraryIds} with at least one chunk on either side - a {@code
   * FULL OUTER JOIN} rather than one query per library, so a library with chunks on only one side
   * is still reported instead of silently missing. Libraries without any chunk do not appear; the
   * caller supplies the zero state and always passes only the libraries it may display, since the
   * query would otherwise aggregate across every organization.
   */
  public List<FullTextIndexFillState> fillStateForLibraries(Collection<UUID> libraryIds) {
    Set<UUID> distinct = new LinkedHashSet<>(libraryIds);
    if (distinct.isEmpty()) {
      return List.of();
    }
    String vectorStoreTable = schemaName + "." + tableName;
    String chunkStillPresent = chunkStillPresent(vectorStoreTable);
    String idPlaceholders = placeholders(distinct.size());
    String sql =
        "SELECT COALESCE(v.library_id, f.library_id) AS library_id, "
            + "       COALESCE(v.total, 0) AS total, "
            + "       COALESCE(f.indexed, 0) AS indexed, "
            + "       COALESCE(f.outdated, 0) AS outdated "
            + "FROM ("
            + "  SELECT (metadata->>'library_id')::uuid AS library_id, count(*) AS total "
            + "  FROM "
            + vectorStoreTable
            + "  WHERE metadata->>'library_id' IN ("
            + idPlaceholders
            + ") GROUP BY 1"
            + ") v "
            + "FULL OUTER JOIN ("
            + "  SELECT f.library_id, "
            + "         count(*) FILTER (WHERE f.content_tsv_version = ?) AS indexed, "
            + "         count(*) FILTER (WHERE f.content_tsv_version <> ?) AS outdated "
            + "  FROM chunk_full_text f WHERE f.library_id IN ("
            + idPlaceholders
            + ")"
            + chunkStillPresent
            + " GROUP BY 1"
            + ") f ON v.library_id = f.library_id";

    List<Object> arguments = new ArrayList<>();
    distinct.forEach(id -> arguments.add(id.toString()));
    arguments.add(FullTextChunkStore.CURRENT_TSV_VERSION);
    arguments.add(FullTextChunkStore.CURRENT_TSV_VERSION);
    arguments.addAll(distinct);

    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new FullTextIndexFillState(
                (UUID) rs.getObject("library_id"),
                rs.getLong("total"),
                rs.getLong("indexed"),
                rs.getLong("outdated")),
        arguments.toArray());
  }

  /**
   * Counts only rows whose chunk still exists - a primary-key lookup. {@code VectorChunkStore}
   * deletes the two stores in sequence rather than in one transaction, so a half-failed delete can
   * leave a {@code chunk_full_text} row behind; counted, such a row would put its library into a
   * backlog no re-index can clear, because the re-index selects over {@code vector_store}.
   */
  private static String chunkStillPresent(String vectorStoreTable) {
    return " AND EXISTS (SELECT 1 FROM " + vectorStoreTable + " v WHERE v.id = f.chunk_id)";
  }

  private static String placeholders(int count) {
    return String.join(", ", Collections.nCopies(count, "?"));
  }
}
