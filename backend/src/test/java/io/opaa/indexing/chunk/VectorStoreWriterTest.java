package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link VectorStoreWriter#rewriteEmbeddedChunks} without a database: the statement only ever
 * updates, and a chunk the update did not find gets no full-text row. The race itself runs against
 * pgvector in {@code ContextPrefixRerunIntegrationTest}.
 */
class VectorStoreWriterTest {

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
    Document stillStored = chunk("noch da");
    Document replaced = chunk("inzwischen ersetzt");
    Document alsoStillStored = chunk("auch noch da");
    // Two JDBC batches, so the position bookkeeping across batches is exercised too.
    when(jdbcTemplate.batchUpdate(
            anyString(),
            anyCollection(),
            anyInt(),
            any(ParameterizedPreparedStatementSetter.class)))
        .thenReturn(new int[][] {{1, 0}, {1}});

    int written =
        writer.rewriteEmbeddedChunks(
            List.of(stillStored, replaced, alsoStillStored),
            List.of(new float[] {0.1f}, new float[] {0.2f}, new float[] {0.3f}));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .batchUpdate(
            sql.capture(),
            anyCollection(),
            anyInt(),
            any(ParameterizedPreparedStatementSetter.class));
    assertThat(sql.getValue())
        .as("an update finds only what is still there; an upsert would recreate it")
        .startsWith("UPDATE public.vector_store SET embedding = ?")
        .contains("WHERE id = ?")
        .doesNotContainIgnoringCase("INSERT")
        .doesNotContainIgnoringCase("ON CONFLICT");
    assertThat(written).isEqualTo(2);
    verify(fullTextChunkStore).indexChunks(List.of(stillStored, alsoStillStored));
  }

  private static Document chunk(String text) {
    return new Document(
        UUID.randomUUID().toString(),
        text,
        Map.of(
            VectorChunkStore.DOCUMENT_ID_METADATA_KEY, UUID.randomUUID().toString(),
            VectorChunkStore.LIBRARY_ID_METADATA_KEY, UUID.randomUUID().toString()));
  }
}
