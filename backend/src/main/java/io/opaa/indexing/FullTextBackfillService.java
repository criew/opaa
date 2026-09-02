package io.opaa.indexing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * also why {@link FullTextChunkStore#indexChunks}'s own {@code ON CONFLICT (chunk_id) DO UPDATE}
 * matters here: even if two calls raced on the same chunk (e.g. this backfill and a concurrent
 * re-index of the same chunk on the write path, an unlikely but possible overlap since chunk ids
 * are freshly generated per write - see {@code FileProcessingService#storeChunks}), the second
 * write simply overwrites the first with equivalent content rather than raising a primary-key
 * violation.
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
 *
 * <p><b>Poison-chunk isolation (#1093).</b> A batch is not one atomic unit: {@link #backfillBatch}
 * never wraps its work in a Spring transaction, so each of the (possibly many) {@link
 * FullTextChunkStore#indexChunks} calls it makes commits independently, in the pooled connection's
 * default autocommit mode. A batch that fails is retried at half its size ({@link
 * #indexWithIsolation}) until the failing chunk is isolated alone; a chunk that still fails alone
 * is permanently recorded in {@code chunk_full_text_skip} via {@link FullTextChunkStore#recordSkip}
 * instead of being retried on every future tick - see {@link #isolateFailingChunk} for how a
 * systemic failure (the database itself, not this one row) is told apart from a genuine poison
 * chunk, and never silently swallowed that way.
 */
@Component
public class FullTextBackfillService {

  private static final Logger log = LoggerFactory.getLogger(FullTextBackfillService.class);

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
   * Selects up to {@code batchSize} not-yet-full-text-indexed (or indexed at an older {@code
   * content_tsv_version}, or previously permanently skipped at an older version - see the class
   * Javadoc) chunks and indexes them, isolating and permanently skipping any single chunk that
   * cannot be indexed rather than failing the whole batch. Returns how many of the selected chunks
   * this call <em>resolved</em> - indexed or newly recorded as permanently skipped; {@code 0} means
   * the backlog is empty (as of this call; new chunks written after #1047 never add to it, since
   * they are indexed at write time already). Chunks missing the {@code document_id}/{@code
   * library_id} metadata keys are excluded rather than failing the whole batch - {@code
   * FileProcessingService} has written both on every chunk since long before #1047, so this only
   * guards a hypothetical malformed row, not an expected case.
   *
   * <p>A genuinely systemic failure (the database itself is unreachable, the connection pool is
   * exhausted) still propagates out of this method - see {@link #isolateFailingChunk} - so {@link
   * FullTextBackfillScheduler}'s consecutive-failure backoff still applies to that case exactly as
   * before; only a single bad row no longer triggers it.
   */
  public int backfillBatch(int batchSize) {
    if (batchSize <= 0) {
      return 0;
    }
    List<org.springframework.ai.document.Document> pending = selectPending(batchSize);
    if (pending.isEmpty()) {
      return 0;
    }
    return indexWithIsolation(pending);
  }

  private List<org.springframework.ai.document.Document> selectPending(int batchSize) {
    return jdbcTemplate.query(
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
            + "  AND NOT EXISTS ("
            + "  SELECT 1 FROM chunk_full_text_skip s "
            + "  WHERE s.chunk_id = v.id AND s.content_tsv_version = ?"
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
        FullTextChunkStore.CURRENT_TSV_VERSION,
        batchSize);
  }

  /**
   * Tries to index {@code chunks} as one batch first - the common, cheap case where nothing in it
   * is a poison chunk. Only on failure does it bisect: half, then each half again, until the
   * failing chunk is alone in its own call ({@link #isolateFailingChunk}) - so a healthy majority
   * of a batch containing one bad row still gets indexed in as few round trips as the failure
   * pattern allows, not one row at a time from the start.
   */
  private int indexWithIsolation(List<org.springframework.ai.document.Document> chunks) {
    try {
      fullTextChunkStore.indexChunks(chunks);
      return chunks.size();
    } catch (DataAccessException failure) {
      if (chunks.size() == 1) {
        return isolateFailingChunk(chunks.get(0), failure);
      }
      int mid = chunks.size() / 2;
      int resolved = indexWithIsolation(chunks.subList(0, mid));
      resolved += indexWithIsolation(chunks.subList(mid, chunks.size()));
      return resolved;
    }
  }

  /**
   * Called once a single chunk, alone, still fails to index - the bisection in {@link
   * #indexWithIsolation} can narrow no further. Told apart here: a <b>systemic</b> failure (the
   * database is unreachable, the connection pool is exhausted) would fail this one chunk for a
   * reason that has nothing to do with its content, and every other chunk in the batch would fail
   * the same way - re-raising it lets it propagate out of {@link #backfillBatch} so {@link
   * FullTextBackfillScheduler}'s consecutive-failure backoff still applies, exactly as before this
   * isolation existed. A <b>poison chunk</b> is the opposite: the database itself is fine, only
   * this one chunk's content defeats {@code to_tsvector} - confirmed with a cheap {@code SELECT 1}
   * probe rather than inspected from the exception's type, because PostgreSQL maps unrelated
   * failure classes (e.g. {@code program_limit_exceeded} for "string is too long for tsvector",
   * SQLSTATE 54000) onto the same Spring exception types a genuine resource failure would raise -
   * the probe is the one signal that reliably distinguishes the two. A poison chunk is logged at
   * {@code ERROR} (a permanent, actionable condition, not routine) and recorded via {@link
   * FullTextChunkStore#recordSkip} so it is never selected again at this {@code
   * content_tsv_version} - see that method's own Javadoc for how a later version bump reopens it.
   */
  private int isolateFailingChunk(
      org.springframework.ai.document.Document chunk, DataAccessException failure) {
    if (!databaseIsHealthy()) {
      throw failure;
    }
    UUID chunkId = UUID.fromString(chunk.getId());
    UUID documentId =
        UUID.fromString(
            (String) chunk.getMetadata().get(VectorChunkStore.DOCUMENT_ID_METADATA_KEY));
    UUID libraryId =
        UUID.fromString((String) chunk.getMetadata().get(VectorChunkStore.LIBRARY_ID_METADATA_KEY));
    log.error(
        "Full-text backfill: permanently skipping poison chunk {} (document {}, library {}) - "
            + "it cannot be indexed into chunk_full_text",
        chunkId,
        documentId,
        libraryId,
        failure);
    fullTextChunkStore.recordSkip(chunkId, documentId, libraryId, failure.getMessage());
    return 1;
  }

  /**
   * A cheap, side-effect-free probe run on its own after a chunk failed to index - success means
   * the database and connection pool are fine and the preceding failure is attributable to that
   * chunk alone; failure means it is not, and the original failure should propagate instead (see
   * {@link #isolateFailingChunk}).
   */
  private boolean databaseIsHealthy() {
    try {
      jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      return true;
    } catch (DataAccessException e) {
      return false;
    }
  }
}
