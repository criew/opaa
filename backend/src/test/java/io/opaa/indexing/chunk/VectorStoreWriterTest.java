package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link VectorStoreWriter#rewriteEmbeddedChunks} and the single-document writers without a
 * database: the rewrite only ever updates, skips a chunk it does not find, and takes the document
 * lock before any row; the other writers take the same lock before their first row. The races
 * themselves run against pgvector in {@code ContextPrefixRerunIntegrationTest}.
 */
class VectorStoreWriterTest {

  private static final String DOCUMENT_LOCK =
      "pg_advisory_xact_lock(" + VectorStoreWriter.DOCUMENT_CHUNK_LOCK_NAMESPACE + ", hashtext(";

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final FullTextChunkStore fullTextChunkStore = mock(FullTextChunkStore.class);
  private final VectorStoreWriter writer =
      new VectorStoreWriter(
          jdbcTemplate,
          fullTextChunkStore,
          new EmbeddingIndexState(),
          JsonMapper.builder().build(),
          "public",
          "vector_store");

  @Test
  @SuppressWarnings("unchecked")
  void aChunkNoLongerStoredIsNeitherWrittenBackNorFullTextIndexed() {
    UUID documentId = UUID.randomUUID();
    Document stillStored = chunk(documentId, "noch da");
    Document replaced = chunk(documentId, "inzwischen ersetzt");
    Document textChanged = chunk(documentId, "Text inzwischen geändert");
    Document alsoStillStored = chunk(documentId, "auch noch da");
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of(stillStored.getId(), textChanged.getId(), alsoStillStored.getId()));
    // Two JDBC batches, so the position bookkeeping across batches is exercised too.
    when(jdbcTemplate.batchUpdate(
            anyString(),
            anyCollection(),
            anyInt(),
            any(ParameterizedPreparedStatementSetter.class)))
        .thenReturn(new int[][] {{1, 0}, {1}});

    int written =
        writer.rewriteEmbeddedChunks(
            List.of(stillStored, replaced, textChanged, alsoStillStored),
            List.of(
                new float[] {0.1f}, new float[] {0.2f}, new float[] {0.3f}, new float[] {0.4f}));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<java.util.Collection<?>> rows =
        ArgumentCaptor.forClass(java.util.Collection.class);
    verify(jdbcTemplate)
        .batchUpdate(
            sql.capture(),
            (java.util.Collection<Object>) (java.util.Collection<?>) rows.capture(),
            anyInt(),
            any(ParameterizedPreparedStatementSetter.class));
    assertThat(sql.getValue())
        .as("an update finds only what is still there; an upsert would recreate it")
        .startsWith("UPDATE public.vector_store SET embedding = ?")
        .contains("WHERE id = ?")
        .doesNotContainIgnoringCase("INSERT")
        .doesNotContainIgnoringCase("ON CONFLICT");
    assertThat(rows.getValue()).as("the vanished chunk is not even attempted").hasSize(3);
    assertThat(written).isEqualTo(2);
    verify(fullTextChunkStore).indexChunks(List.of(stillStored, alsoStillStored));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRewriteTakesEveryDocumentLockInOrderBeforeItLocksAnyRowAndNeverWaitsForARow() {
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    Document ofSecond = chunk(second, "zweites Dokument");
    Document ofFirst = chunk(first, "erstes Dokument");
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of());

    writer.rewriteEmbeddedChunks(
        List.of(ofSecond, ofFirst), List.of(new float[] {0.1f}, new float[] {0.2f}));

    InOrder order = inOrder(jdbcTemplate);
    order
        .verify(jdbcTemplate)
        .queryForObject(
            org.mockito.ArgumentMatchers.contains(DOCUMENT_LOCK),
            eq(Boolean.class),
            eq(first.toString()));
    order
        .verify(jdbcTemplate)
        .queryForObject(
            org.mockito.ArgumentMatchers.contains(DOCUMENT_LOCK),
            eq(Boolean.class),
            eq(second.toString()));
    order
        .verify(jdbcTemplate)
        .queryForList(
            org.mockito.ArgumentMatchers.endsWith("ORDER BY id FOR UPDATE NOWAIT"),
            eq(String.class),
            any(Object[].class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyStatementOfTheRewriteRunsInsideTheColumnLock() {
    // The column switch holds the column lock while it waits for the table lock; a statement
    // before it would hold row locks while waiting for the switch, and both would hang.
    List<Boolean> insideColumnLock = new java.util.ArrayList<>();
    RecordingIndexState state = new RecordingIndexState();
    VectorStoreWriter recordingWriter =
        new VectorStoreWriter(
            jdbcTemplate,
            fullTextChunkStore,
            state,
            JsonMapper.builder().build(),
            "public",
            "vector_store");
    Document chunk = chunk(UUID.randomUUID(), "noch da");
    when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenAnswer(
            call -> {
              insideColumnLock.add(state.writing);
              return true;
            });
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenAnswer(
            call -> {
              insideColumnLock.add(state.writing);
              return List.of(chunk.getId());
            });
    when(jdbcTemplate.batchUpdate(
            anyString(),
            anyCollection(),
            anyInt(),
            any(ParameterizedPreparedStatementSetter.class)))
        .thenAnswer(
            call -> {
              insideColumnLock.add(state.writing);
              return new int[][] {{1}};
            });

    recordingWriter.rewriteEmbeddedChunks(List.of(chunk), List.of(new float[] {0.1f}));

    assertThat(insideColumnLock)
        .as("document lock, row lock and update")
        .hasSize(3)
        .containsOnly(true);
  }

  /** Records whether a writer is between taking and releasing the column lock. */
  private static final class RecordingIndexState extends EmbeddingIndexState {

    private boolean writing;

    @Override
    <T> T whileWriting(java.util.function.Function<String, T> write) {
      return super.whileWriting(
          column -> {
            writing = true;
            try {
              return write.apply(column);
            } finally {
              writing = false;
            }
          });
    }
  }

  @Test
  void deletingAndRelabellingADocumentsChunksTakeTheSameDocumentLock() {
    UUID documentId = UUID.randomUUID();

    writer.deleteChunksOfDocument(documentId);
    writer.updateDocumentMetadata(documentId, Map.of("title", "Neu"), Set.of());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
    assertThat(sql.getAllValues())
        .allSatisfy(
            statement ->
                assertThat(statement)
                    .contains(
                        "metadata->>'document_id' = ? AND (SELECT true FROM (SELECT "
                            + DOCUMENT_LOCK));
  }

  private static Document chunk(UUID documentId, String text) {
    return new Document(
        UUID.randomUUID().toString(),
        text,
        Map.of(
            VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString(),
            VectorChunkStore.LIBRARY_ID_METADATA_KEY, UUID.randomUUID().toString()));
  }
}
