package io.opaa.indexing;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
 * database; nothing is lost, and nothing already done is redone.
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
 * never wraps its work in a Spring transaction, so each {@link FullTextChunkStore#indexChunks} call
 * it makes commits independently. A batch that fails is retried at half its size ({@link
 * #indexWithIsolation}) until the failing chunk is isolated alone; that single chunk is then either
 * recorded as one more failed attempt in {@code chunk_full_text_skip} (see {@link
 * #isolateFailingChunk}) - permanently excluded from selection only once it has failed the same way
 * {@link #SKIP_CONFIRMATION_ATTEMPTS} times in a row - or, if it is not a poison-chunk candidate at
 * all, re-thrown so {@link FullTextBackfillScheduler}'s consecutive-failure backoff still applies.
 */
@Component
public class FullTextBackfillService {

  private static final Logger log = LoggerFactory.getLogger(FullTextBackfillService.class);

  /**
   * A chunk must fail this many consecutive attempts at the same {@code content_tsv_version} before
   * {@link #isolateFailingChunk} excludes it from further selection. Kept low: a poison chunk
   * (content {@code to_tsvector} genuinely cannot process) fails identically on every attempt and
   * reaches this quickly, while a transient, per-row failure (a concurrent {@code DELETE} on the
   * same row racing this backfill, a lock timeout) essentially never fails the same chunk twice in
   * a row - one scheduler tick apart is enough separation in practice.
   */
  static final int SKIP_CONFIRMATION_ATTEMPTS = 3;

  /**
   * PostgreSQL {@code SQLSTATE} classes {@link #isolateFailingChunk} accepts as evidence that a
   * failure is attributable to this one row's data, not to the database itself: {@code 22} (data
   * exception, e.g. invalid UTF-8), {@code 54} (program limit exceeded, e.g. "string is too long
   * for tsvector"), {@code 42} (syntax error or access rule violation). Deliberately an allowlist,
   * not a denylist: an unrecognised or absent {@code SQLSTATE} propagates the failure rather than
   * risking a systemic outage (connection lost, pool exhausted, deadlock class {@code 40}, operator
   * intervention class {@code 57}) being misdiagnosed as a poison chunk and silently dropped.
   */
  private static final Set<String> POISON_SQLSTATE_CLASSES = Set.of("22", "54", "42");

  /**
   * PostgreSQL's own regular-expression syntax for a canonical, hyphenated UUID - applied to the
   * {@code library_id} metadata value in {@link #selectPending}'s {@code WHERE} clause only. A row
   * whose {@code library_id} is not a well-formed UUID can never be attributed to a real library
   * anyway (every progress/gate query matches it by exact equality against a known library's UUID
   * string), so excluding it here mirrors the pre-existing "missing metadata" exclusion rather than
   * introducing a new kind of loss. {@code document_id} is deliberately <em>not</em> filtered here,
   * not even for {@code NULL} - see {@link #partitionByDocumentIdValidity} for why a missing or
   * malformed one is handled explicitly instead of being dropped from selection.
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
   * content_tsv_version}, or a skip candidate that has not yet been confirmed - see the class
   * Javadoc) chunks and indexes them, isolating any single chunk that cannot be indexed rather than
   * failing the whole batch. Returns how many of the selected chunks this call <em>resolved</em> -
   * indexed, or recorded as one more failed attempt of a skip candidate; {@code 0} means the
   * backlog is empty (as of this call; new chunks written after #1047 never add to it, since they
   * are indexed at write time already).
   *
   * <p>A chunk whose {@code document_id} metadata is missing or not a well-formed UUID is recorded
   * as an immediately confirmed skip (see {@link #partitionByDocumentIdValidity}) rather than
   * attempted - it is a structural defect no retry can fix, and leaving it merely "pending" would
   * keep {@link FullTextBackfillProgress#isComplete()} false for its library forever.
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
    Partition partition = partitionByDocumentIdValidity(pending);
    int resolved = partition.invalidDocumentIdCount();
    if (!partition.wellFormed().isEmpty()) {
      resolved += indexWithIsolation(partition.wellFormed());
    }
    return resolved;
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
            + "  AND metadata->>'library_id' ~ '"
            + UUID_PATTERN
            + "' "
            + "LIMIT ?",
        (rs, rowNum) -> {
          // A null document_id metadata value reaches this row mapper by design (see the
          // UUID_PATTERN javadoc above). Both Map.of() and the Document constructor itself reject
          // null metadata values, so the key is simply omitted rather than mapped to null -
          // partitionByDocumentIdValidity() treats an absent key the same as a null one.
          String documentId = rs.getString("document_id");
          Map<String, Object> metadata = new HashMap<>();
          if (documentId != null) {
            metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId);
          }
          metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, rs.getString("library_id"));
          return new org.springframework.ai.document.Document(
              rs.getString("id"), rs.getString("content"), metadata);
        },
        FullTextChunkStore.CURRENT_TSV_VERSION,
        FullTextChunkStore.CURRENT_TSV_VERSION,
        SKIP_CONFIRMATION_ATTEMPTS,
        batchSize);
  }

  /**
   * Splits {@code pending} into chunks whose {@code document_id} metadata is a well-formed UUID and
   * ones that are missing or not - immediately recording the latter as confirmed skips ({@link
   * FullTextChunkStore#recordConfirmedSkip}) rather than attempting to index them. A missing or
   * malformed {@code document_id} would otherwise reach {@code UUID.fromString} inside {@link
   * FullTextChunkStore#indexChunks} and throw - a per-row, structural defect no retry heals, so
   * routing it through the {@link #SKIP_CONFIRMATION_ATTEMPTS}-attempt confirmation dance would
   * only delay the same, inevitable outcome. Recording it here also sidesteps a real problem the
   * retry path cannot solve for this case: {@link #isolateFailingChunk} needs a well-formed {@code
   * documentId} to write a {@code chunk_full_text_skip} row at all, which is exactly the value this
   * chunk does not have.
   */
  private Partition partitionByDocumentIdValidity(
      List<org.springframework.ai.document.Document> pending) {
    List<org.springframework.ai.document.Document> wellFormed = new ArrayList<>(pending.size());
    int invalidCount = 0;
    for (org.springframework.ai.document.Document chunk : pending) {
      String documentId =
          (String) chunk.getMetadata().get(VectorChunkStore.DOCUMENT_ID_METADATA_KEY);
      if (documentId != null && documentId.matches(UUID_PATTERN)) {
        wellFormed.add(chunk);
      } else {
        recordInvalidDocumentIdSkip(chunk, documentId);
        invalidCount++;
      }
    }
    return new Partition(wellFormed, invalidCount);
  }

  private record Partition(
      List<org.springframework.ai.document.Document> wellFormed, int invalidDocumentIdCount) {}

  private void recordInvalidDocumentIdSkip(
      org.springframework.ai.document.Document chunk, String invalidDocumentId) {
    UUID chunkId = UUID.fromString(chunk.getId());
    UUID libraryId =
        UUID.fromString((String) chunk.getMetadata().get(VectorChunkStore.LIBRARY_ID_METADATA_KEY));
    String reason =
        invalidDocumentId == null
            ? "missing document_id metadata"
            : "malformed document_id metadata: " + invalidDocumentId;
    log.warn(
        "Full-text backfill: chunk {} (library {}) has {} - recording it as a permanently skipped "
            + "chunk instead of retrying, since this is a structural defect that cannot heal on its "
            + "own",
        chunkId,
        libraryId,
        reason);
    fullTextChunkStore.recordConfirmedSkip(
        chunkId, null, libraryId, reason, SKIP_CONFIRMATION_ATTEMPTS);
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
      fullTextChunkStore.clearSkipRows(
          chunks.stream().map(c -> UUID.fromString(c.getId())).toList());
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
   * #indexWithIsolation} can narrow no further. A <b>systemic</b> failure (the database is
   * unreachable, the connection pool is exhausted, a deadlock with a concurrent writer) is
   * re-thrown, propagating out of {@link #backfillBatch} so {@link FullTextBackfillScheduler}'s
   * consecutive-failure backoff still applies. A <b>poison-chunk candidate</b> ({@link
   * #isPoisonCandidate}) is instead recorded via {@link FullTextChunkStore#recordOrIncrementSkip} -
   * not yet excluded from selection until it has failed identically {@link
   * #SKIP_CONFIRMATION_ATTEMPTS} times in a row (logged at {@code WARN} below that threshold,
   * {@code ERROR} once confirmed), so a wrongly-classified transient failure heals itself within a
   * few ticks instead of causing a permanent, silent loss.
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
      // No stack trace below the confirmation threshold: a version bump can put many chunks
      // through this branch on the same tick, and the full trace adds nothing yet - it is only
      // reached again, and only escalates to ERROR with one, if the same chunk keeps failing.
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
   * database itself. A {@link DataAccessException} is a poison candidate only when its innermost
   * cause is a {@link SQLException} whose {@code SQLSTATE} class is in {@link
   * #POISON_SQLSTATE_CLASSES}; an absent or unrecognised {@code SQLSTATE} is treated as systemic
   * (propagated), not as poison. A plain {@link IllegalArgumentException} - the type {@code
   * UUID.fromString} throws - never reached the database at all, so it is definitionally about this
   * chunk's data; every <em>other</em> {@link RuntimeException} defaults to systemic for the same
   * reason an unrecognised {@code SQLSTATE} does: a programming defect that fails every chunk in
   * the same way (e.g. a bug in a shared helper {@link FullTextChunkStore#indexChunks} calls) must
   * reach {@link FullTextBackfillScheduler}'s backoff, not get isolated chunk-by-chunk into
   * confirmed skips until the whole backlog looks "complete" with nothing actually indexed.
   */
  private static boolean isPoisonCandidate(RuntimeException failure) {
    if (failure instanceof IllegalArgumentException) {
      return true;
    }
    if (!(failure instanceof DataAccessException dataAccessException)) {
      return false;
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
