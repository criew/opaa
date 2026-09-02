package io.opaa.indexing;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * #indexWithIsolation}) until the failing chunk is isolated alone. A single isolated chunk is told
 * apart as systemic or per-row by its PostgreSQL {@code SQLSTATE} class, not by a later probe (see
 * {@link #isolateFailingChunk} for why a probe cannot do this reliably), and only permanently
 * excluded from selection after it fails the same way {@link #SKIP_CONFIRMATION_ATTEMPTS} times in
 * a row via {@link FullTextChunkStore#recordOrIncrementSkip} - so a transient, per-row failure that
 * heals within a couple of ticks is retried rather than misdiagnosed as poison.
 */
@Component
public class FullTextBackfillService {

  private static final Logger log = LoggerFactory.getLogger(FullTextBackfillService.class);

  /**
   * A chunk must fail this many consecutive attempts at the same {@code content_tsv_version} before
   * {@link #isolateFailingChunk} excludes it from further selection (#1093 review, Blocker 1). Kept
   * low: a poison chunk (content {@code to_tsvector} genuinely cannot process) fails identically on
   * every attempt and reaches this quickly, while a transient, per-row failure (a concurrent {@code
   * DELETE} on the same row racing this backfill, a lock timeout) essentially never fails the same
   * chunk twice in a row - one scheduler tick apart is enough separation in practice.
   */
  static final int SKIP_CONFIRMATION_ATTEMPTS = 3;

  /**
   * PostgreSQL {@code SQLSTATE} classes {@link #isolateFailingChunk} accepts as evidence that a
   * failure is attributable to this one row's data, not to the database itself: {@code 22} (data
   * exception, e.g. invalid UTF-8), {@code 54} (program limit exceeded, e.g. "string is too long
   * for tsvector" - the reproduction case for #1093), {@code 42} (syntax error or access rule
   * violation). Deliberately an allowlist, not a denylist: an unrecognised or absent {@code
   * SQLSTATE} propagates the failure rather than risking a systemic outage (connection lost, pool
   * exhausted, deadlock class {@code 40}, operator intervention class {@code 57}) being
   * misdiagnosed as a poison chunk and silently dropped.
   */
  private static final Set<String> POISON_SQLSTATE_CLASSES = Set.of("22", "54", "42");

  /**
   * PostgreSQL's own regular-expression syntax for a canonical, hyphenated UUID - applied to the
   * {@code document_id}/{@code library_id} metadata values in {@link #selectPending}'s {@code
   * WHERE} clause. A row whose metadata is present but not a well-formed UUID (#1093 review, W2) is
   * excluded from selection the same way a row with missing metadata already was, rather than
   * reaching {@link FullTextChunkStore#indexChunks} and throwing {@link IllegalArgumentException}
   * from {@code UUID.fromString} - which {@link #isolateFailingChunk} could not turn into a {@code
   * chunk_full_text_skip} row anyway, since that table's own {@code document_id}/{@code library_id}
   * columns are themselves {@code NOT NULL uuid}.
   */
  private static final String UUID_PATTERN =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

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
   * content_tsv_version}, or a permanently skipped poison chunk at an older version - see the class
   * Javadoc) chunks and indexes them, isolating any single chunk that cannot be indexed rather than
   * failing the whole batch. Returns how many of the selected chunks this call <em>resolved</em> -
   * indexed, or recorded as one more failed attempt of a skip candidate (confirmed poison or not
   * yet, see {@link #SKIP_CONFIRMATION_ATTEMPTS}); {@code 0} means the backlog is empty (as of this
   * call; new chunks written after #1047 never add to it, since they are indexed at write time
   * already). Chunks with missing or malformed {@code document_id}/{@code library_id} metadata are
   * excluded rather than failing the whole batch - {@code FileProcessingService} has written both,
   * well-formed, on every chunk since long before #1047, so this only guards a hypothetical
   * malformed row, not an expected case.
   *
   * <p>A genuinely systemic failure (the database itself is unreachable, the connection pool is
   * exhausted, a deadlock) still propagates out of this method - see {@link #isolateFailingChunk} -
   * so {@link FullTextBackfillScheduler}'s consecutive-failure backoff still applies to that case
   * exactly as before; only a single bad row no longer triggers it.
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
            + "  WHERE s.chunk_id = v.id AND s.content_tsv_version = ? AND s.attempts >= ?"
            + ") "
            + "  AND metadata->>'document_id' ~ '"
            + UUID_PATTERN
            + "' "
            + "  AND metadata->>'library_id' ~ '"
            + UUID_PATTERN
            + "' "
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
        SKIP_CONFIRMATION_ATTEMPTS,
        batchSize);
  }

  /**
   * Tries to index {@code chunks} as one batch first - the common, cheap case where nothing in it
   * is a poison chunk. Only on failure does it bisect: half, then each half again, until the
   * failing chunk is alone in its own call ({@link #isolateFailingChunk}) - so a healthy majority
   * of a batch containing one bad row still gets indexed in as few round trips as the failure
   * pattern allows, not one row at a time from the start.
   *
   * <p>Catches {@link RuntimeException} broadly, not only {@link DataAccessException} (#1093
   * review, W2): {@link FullTextChunkStore#indexChunks} can also throw a plain {@link
   * RuntimeException} that never reaches the database at all (historically, a malformed {@code
   * document_id}/{@code library_id} value would have thrown {@link IllegalArgumentException} from
   * {@code UUID.fromString} inside its {@code PreparedStatementSetter}) - {@link #selectPending}
   * now excludes that specific case at the source, but this catch stays broad as the general
   * backstop the class Javadoc promises: no single chunk's failure, of any kind, should halt the
   * whole batch.
   */
  private int indexWithIsolation(List<org.springframework.ai.document.Document> chunks) {
    try {
      fullTextChunkStore.indexChunks(chunks);
      return chunks.size();
    } catch (RuntimeException failure) {
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
   * database is unreachable, the connection pool is exhausted, a deadlock with a concurrent writer)
   * would fail this one chunk for a reason that has nothing to do with its content, and every other
   * chunk in the batch would likely fail the same way - re-raising it lets it propagate out of
   * {@link #backfillBatch} so {@link FullTextBackfillScheduler}'s consecutive-failure backoff still
   * applies, exactly as before this isolation existed.
   *
   * <p><b>Classified by {@code SQLSTATE}, not by a later probe (#1093 review, Blocker 1).</b> An
   * earlier version of this method ran a {@code SELECT 1} probe after the failure to tell the two
   * cases apart; that is unsound - the probe runs on a different, freshly borrowed connection at a
   * later point in time, so anything that heals in that window (a concurrent {@code DELETE} on the
   * same row finishing, a lock releasing, a brief pool exhaustion clearing) makes the probe report
   * "healthy" for a failure that was never about this row at all, permanently discarding a
   * perfectly good chunk. The {@code SQLSTATE} PostgreSQL returned <em>with the original failure
   * itself</em> has no such timing gap - see {@link #POISON_SQLSTATE_CLASSES} and {@link
   * #isPoisonCandidate} for the allowlist and {@link #extractSqlState} for how it is read via
   * {@link DataAccessException#getMostSpecificCause()}.
   *
   * <p>Even a genuine poison chunk is not skipped on the first failure: {@link
   * FullTextChunkStore#recordOrIncrementSkip} only excludes it from future selection once it has
   * failed identically {@link #SKIP_CONFIRMATION_ATTEMPTS} times in a row (logged at {@code WARN}
   * below that threshold, {@code ERROR} once confirmed) - the second safeguard the review asked
   * for, independent of the {@code SQLSTATE} allowlist: even a wrongly-classified transient failure
   * heals itself within a few ticks instead of causing a permanent, silent loss.
   */
  private int isolateFailingChunk(
      org.springframework.ai.document.Document chunk, RuntimeException failure) {
    if (!isPoisonCandidate(failure)) {
      throw failure;
    }
    UUID chunkId = UUID.fromString(chunk.getId());
    UUID documentId =
        UUID.fromString(
            (String) chunk.getMetadata().get(VectorChunkStore.DOCUMENT_ID_METADATA_KEY));
    UUID libraryId =
        UUID.fromString((String) chunk.getMetadata().get(VectorChunkStore.LIBRARY_ID_METADATA_KEY));
    String sqlState = extractSqlState(failure);
    String errorMessage = failure.getMessage() != null ? failure.getMessage() : failure.toString();
    int attempts =
        fullTextChunkStore.recordOrIncrementSkip(
            chunkId, documentId, libraryId, errorMessage, sqlState);
    if (attempts < SKIP_CONFIRMATION_ATTEMPTS) {
      // No stack trace below the confirmation threshold (#1093 review, minor point) - a version
      // bump (#1166) can put many chunks through this branch on the same tick, and the full trace
      // adds nothing yet: it is only reached again, and only escalates to ERROR with one, if the
      // same chunk keeps failing.
      log.warn(
          "Full-text backfill: chunk {} (document {}, library {}) failed attempt {}/{} ({}) - "
              + "will retry on a later tick before being permanently skipped",
          chunkId,
          documentId,
          libraryId,
          attempts,
          SKIP_CONFIRMATION_ATTEMPTS,
          errorMessage);
    } else {
      log.error(
          "Full-text backfill: permanently skipping poison chunk {} (document {}, library {}) "
              + "after {} consecutive failed attempts - it cannot be indexed into chunk_full_text",
          chunkId,
          documentId,
          libraryId,
          attempts,
          failure);
    }
    return 1;
  }

  /**
   * {@code true} when {@code failure} is evidence about this one chunk's data rather than about the
   * database itself - see {@link #isolateFailingChunk} for the reasoning. A {@link
   * RuntimeException} that is not a {@link DataAccessException} never reached the database at all
   * (e.g. a malformed value thrown from within a {@code PreparedStatementSetter}), so it is
   * definitionally about this chunk. A {@link DataAccessException} is only a poison candidate when
   * its innermost cause is a {@link SQLException} whose {@code SQLSTATE} class is in {@link
   * #POISON_SQLSTATE_CLASSES}; an absent or unrecognised {@code SQLSTATE} is treated as systemic
   * (propagated), not as poison - the safer default given the cost of misclassifying a real outage
   * as a bad row.
   */
  private static boolean isPoisonCandidate(RuntimeException failure) {
    if (!(failure instanceof DataAccessException dataAccessException)) {
      return true;
    }
    if (!(dataAccessException.getMostSpecificCause() instanceof SQLException sqlException)) {
      return false;
    }
    String sqlState = sqlException.getSQLState();
    return sqlState != null
        && sqlState.length() >= 2
        && POISON_SQLSTATE_CLASSES.contains(sqlState.substring(0, 2));
  }

  /**
   * The raw {@code SQLSTATE} recorded alongside a skip, for operator diagnosis - {@code null} for a
   * non-{@link DataAccessException} poison candidate (see {@link #isPoisonCandidate}), which never
   * had one to begin with.
   */
  private static String extractSqlState(RuntimeException failure) {
    if (failure instanceof DataAccessException dataAccessException
        && dataAccessException.getMostSpecificCause() instanceof SQLException sqlException) {
      return sqlException.getSQLState();
    }
    return null;
  }
}
