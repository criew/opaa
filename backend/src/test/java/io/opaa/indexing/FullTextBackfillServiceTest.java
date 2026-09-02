package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The isolation decision inside {@link FullTextBackfillService#backfillBatch}: whether a single
 * chunk's failure is treated as a poison-chunk candidate (recorded, retried a bounded number of
 * times, then permanently skipped) or as a systemic failure that must reach {@link
 * FullTextBackfillScheduler}'s consecutive-failure backoff untouched. {@link
 * FullTextBackfillServiceIntegrationTest} covers the real, end-to-end poison-chunk case against
 * Postgres; this class isolates the classification logic itself with a scripted failure, which a
 * real database cannot reliably reproduce for the systemic (non-poison) branch.
 */
@ExtendWith(MockitoExtension.class)
class FullTextBackfillServiceTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private FullTextChunkStore fullTextChunkStore;

  private FullTextBackfillService service;

  @BeforeEach
  void setUp() {
    service =
        new FullTextBackfillService(jdbcTemplate, fullTextChunkStore, "public", "vector_store");
  }

  @SuppressWarnings("unchecked")
  private void stubOnePendingChunk(Document chunk) {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(chunk));
  }

  private Document chunk() {
    return new Document(
        UUID.randomUUID().toString(),
        "some content",
        Map.of(
            VectorChunkStore.DOCUMENT_ID_METADATA_KEY, DOCUMENT_ID.toString(),
            VectorChunkStore.LIBRARY_ID_METADATA_KEY, LIBRARY_ID.toString()));
  }

  /**
   * Reproduction for #1093 review Blocker 1: a failure whose {@code SQLSTATE} class is outside the
   * poison allowlist (here {@code 40001}, serialization_failure - a concurrent-write conflict, not
   * a bad row) must propagate out of {@link FullTextBackfillService#backfillBatch} untouched, so
   * {@link FullTextBackfillScheduler}'s backoff still applies - and must never reach {@link
   * FullTextChunkStore#recordOrIncrementSkip}, which would misdiagnose a transient conflict as a
   * permanent poison chunk.
   */
  @Test
  void aFailureWithASqlstateOutsideTheAllowlistPropagatesWithoutRecordingASkip() {
    Document chunk = chunk();
    stubOnePendingChunk(chunk);
    SQLException serializationFailure = new SQLException("could not serialize access", "40001");
    doThrow(new TransientDataAccessResourceException("simulated conflict", serializationFailure))
        .when(fullTextChunkStore)
        .indexChunks(List.of(chunk));

    assertThatThrownBy(() -> service.backfillBatch(10))
        .isInstanceOf(TransientDataAccessResourceException.class);

    verify(fullTextChunkStore, never()).recordOrIncrementSkip(any(), any(), any(), any(), any());
  }

  /**
   * The reproduction case for #1093 itself, at the unit level: {@code SQLSTATE 54000}
   * (program_limit_exceeded - "string is too long for tsvector") is in the poison allowlist, so the
   * chunk is recorded as a skip candidate instead of propagating and halting the batch.
   */
  @Test
  void aPoisonSqlstateIsRecordedAsASkipCandidateInsteadOfPropagating() {
    Document chunk = chunk();
    stubOnePendingChunk(chunk);
    SQLException tooLongForTsvector = new SQLException("string is too long for tsvector", "54000");
    doThrow(new DataIntegrityViolationException("simulated poison chunk", tooLongForTsvector))
        .when(fullTextChunkStore)
        .indexChunks(List.of(chunk));
    when(fullTextChunkStore.recordOrIncrementSkip(any(), any(), any(), any(), any())).thenReturn(1);

    int resolved = service.backfillBatch(10);

    assertThat(resolved).isEqualTo(1);
    verify(fullTextChunkStore)
        .recordOrIncrementSkip(
            eq(UUID.fromString(chunk.getId())),
            eq(DOCUMENT_ID),
            eq(LIBRARY_ID),
            any(),
            eq("54000"));
  }

  /**
   * {@link IllegalArgumentException} - the type {@code UUID.fromString} throws - never reached the
   * database at all, so it is treated as a poison-chunk candidate even though it is not a {@link
   * org.springframework.dao.DataAccessException}.
   */
  @Test
  void anIllegalArgumentExceptionIsTreatedAsAPoisonCandidate() {
    Document chunk = chunk();
    stubOnePendingChunk(chunk);
    doThrow(new IllegalArgumentException("simulated malformed value"))
        .when(fullTextChunkStore)
        .indexChunks(List.of(chunk));
    when(fullTextChunkStore.recordOrIncrementSkip(any(), any(), any(), any(), any())).thenReturn(1);

    int resolved = service.backfillBatch(10);

    assertThat(resolved).isEqualTo(1);
    verify(fullTextChunkStore)
        .recordOrIncrementSkip(any(), eq(DOCUMENT_ID), eq(LIBRARY_ID), any(), eq((String) null));
  }

  /**
   * Reproduction for #1093 review round 3, finding 3: a {@link RuntimeException} that is neither a
   * {@link org.springframework.dao.DataAccessException} nor an {@link IllegalArgumentException}
   * (e.g. a programming defect in a helper every chunk goes through) must propagate rather than be
   * isolated as a poison-chunk candidate. Isolating it instead would let the bisection in {@link
   * FullTextBackfillService#backfillBatch} confirm-skip every chunk in the backlog independently
   * over a few ticks - {@code missingChunks} would fall to zero, {@code
   * io.opaa.query.FullTextBackfillGate} would open on an index that is, in truth, empty, and the
   * lexical path would silently return nothing while reporting itself complete.
   */
  @Test
  void aGenericRuntimeExceptionThatNeverReachedTheDatabasePropagatesWithoutRecordingASkip() {
    Document chunk = chunk();
    stubOnePendingChunk(chunk);
    doThrow(new NullPointerException("simulated programming defect"))
        .when(fullTextChunkStore)
        .indexChunks(List.of(chunk));

    assertThatThrownBy(() -> service.backfillBatch(10)).isInstanceOf(NullPointerException.class);

    verify(fullTextChunkStore, never()).recordOrIncrementSkip(any(), any(), any(), any(), any());
  }

  /**
   * The second safeguard the review asked for, independent of the {@code SQLSTATE} allowlist: a
   * chunk is not excluded from future selection on its first failure, and a later success clears
   * whatever attempt count it had accrued - so a fresh, later failure of the same chunk starts over
   * rather than continuing an old count.
   */
  @Test
  void aChunkThatLaterSucceedsIsClearedAndALaterFreshFailureIsRecordedAgain() {
    Document chunk = chunk();
    stubOnePendingChunk(chunk);
    SQLException tooLongForTsvector = new SQLException("string is too long for tsvector", "54000");
    doThrow(new DataIntegrityViolationException("simulated poison chunk", tooLongForTsvector))
        .doNothing()
        .doThrow(
            new DataIntegrityViolationException("simulated poison chunk again", tooLongForTsvector))
        .when(fullTextChunkStore)
        .indexChunks(List.of(chunk));
    when(fullTextChunkStore.recordOrIncrementSkip(any(), any(), any(), any(), any())).thenReturn(1);

    // First tick: fails, recorded as one failed attempt.
    assertThat(service.backfillBatch(10)).isEqualTo(1);
    verify(fullTextChunkStore, times(1)).recordOrIncrementSkip(any(), any(), any(), any(), any());

    // Second tick: the chunk now indexes successfully (its cause was fixed) - the service clears
    // any earlier skip row for it, rather than leaving a stale one behind that would keep
    // inflating FullTextBackfillProgress#skippedChunks for a chunk that is, in truth, indexed.
    assertThat(service.backfillBatch(10)).isEqualTo(1);
    verify(fullTextChunkStore).clearSkipRows(List.of(UUID.fromString(chunk.getId())));

    // Third tick: a fresh failure. FullTextChunkStore#recordOrIncrementSkip's own ON CONFLICT
    // logic is what actually resets attempts to 1 in the real database (the second tick's
    // success already deleted the earlier row) - this service only calls it again and trusts
    // whatever attempt count it returns, never tracking a counter of its own.
    assertThat(service.backfillBatch(10)).isEqualTo(1);
    verify(fullTextChunkStore, times(2)).recordOrIncrementSkip(any(), any(), any(), any(), any());
  }
}
