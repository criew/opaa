package io.opaa.indexing;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The resumable backfill batch job for the pre-existing chunk bestand (docs/features/hybrid-
 * retrieval.md, "Arbeitspaket 2a: Backfill des Bestands"). Every chunk written after #1047 already
 * gets its {@code chunk_full_text} row at write time (see {@link VectorChunkStore#addChunks}); this
 * service only ever catches up chunks written before that - or, harmlessly, chunks a still-running
 * backfill has not reached yet.
 *
 * <p><b>Idempotent and resumable with persisted progress, by construction, not by a separate cursor
 * table.</b> {@link #backfillBatch} always selects chunks with {@code NOT EXISTS} a matching {@code
 * chunk_full_text} row at the current {@link FullTextChunkStore#CURRENT_TSV_VERSION} - the presence
 * or absence of such a row <em>is</em> the persisted progress. Calling this method again after a
 * crash, a restart or simply the next scheduled tick re-derives exactly the remaining work from the
 * database; nothing is lost, and nothing already done is redone (a chunk that made it into {@code
 * chunk_full_text} on a previous, interrupted call is excluded from every later batch). This is
 * also why {@link FullTextChunkStore#indexChunks}'s own {@code ON CONFLICT (chunk_id) DO NOTHING}
 * matters here: even if two calls raced on the same chunk (e.g. this backfill and a concurrent
 * re-index of the same chunk on the write path, an unlikely but possible overlap since chunk ids
 * are freshly generated per write - see {@code FileProcessingService#storeChunks}), neither raises
 * a primary-key violation.
 *
 * <p><b>Rückwirkungsarm (low-impact):</b> one call processes at most {@code batchSize} chunks and
 * returns; {@link FullTextBackfillScheduler} is what turns that into an ongoing background job,
 * ticking on a fixed delay rather than looping tightly - see that class's own Javadoc for the
 * scheduling rationale. Single-instance assumption (ADR-0021): no leader election, matching every
 * other {@code @Scheduled} job in this package.
 *
 * <p>Table/schema name are read from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} itself binds, mirroring {@code io.opaa.query.ChunkEmbeddingLookup}'s
 * pattern (never hardcoded independently of that configuration).
 */
@Component
public class FullTextBackfillService {

  private final JdbcTemplate jdbcTemplate;
  private final FullTextChunkStore fullTextChunkStore;
  private final String schemaName;
  private final String tableName;

  public FullTextBackfillService(
      JdbcTemplate jdbcTemplate,
      FullTextChunkStore fullTextChunkStore,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.fullTextChunkStore = fullTextChunkStore;
    this.schemaName = schemaName;
    this.tableName = tableName;
  }

  /**
   * Indexes up to {@code batchSize} not-yet-full-text-indexed (or indexed at an older {@code
   * content_tsv_version}) chunks and returns how many it actually processed - {@code 0} means the
   * backlog is empty (as of this call; new chunks written after #1047 never add to it, since they
   * are indexed at write time already). Chunks missing the {@code document_id}/{@code library_id}
   * metadata keys are excluded rather than failing the whole batch - {@code FileProcessingService}
   * has written both on every chunk since long before #1047, so this only guards a hypothetical
   * malformed row, not an expected case.
   */
  @Transactional
  public int backfillBatch(int batchSize) {
    if (batchSize <= 0) {
      return 0;
    }
    List<org.springframework.ai.document.Document> pending =
        jdbcTemplate.query(
            "SELECT id, content, metadata->>'document_id' AS document_id, "
                + "       metadata->>'library_id' AS library_id "
                + "FROM "
                + schemaName
                + "."
                + tableName
                + " v "
                + "WHERE NOT EXISTS ("
                + "  SELECT 1 FROM chunk_full_text f "
                + "  WHERE f.chunk_id = v.id AND f.content_tsv_version = ?"
                + ") "
                + "  AND metadata->>'document_id' IS NOT NULL "
                + "  AND metadata->>'library_id' IS NOT NULL "
                + "LIMIT ?",
            (rs, rowNum) ->
                new org.springframework.ai.document.Document(
                    rs.getString("id"),
                    rs.getString("content"),
                    Map.of(
                        VectorChunkStore.DOCUMENT_ID_METADATA_KEY, rs.getString("document_id"),
                        VectorChunkStore.LIBRARY_ID_METADATA_KEY, rs.getString("library_id"))),
            FullTextChunkStore.CURRENT_TSV_VERSION,
            batchSize);
    if (pending.isEmpty()) {
      return 0;
    }
    fullTextChunkStore.indexChunks(pending);
    return pending.size();
  }
}
