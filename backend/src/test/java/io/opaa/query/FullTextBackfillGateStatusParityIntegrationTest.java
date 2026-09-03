package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.FullTextBackfillProgress;
import io.opaa.indexing.FullTextBackfillProgressService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link FullTextBackfillGate#searchableLibraries} against a real Postgres, not just the {@link
 * FullTextBackfillProgress} count it is derived from (#1120) - a library whose count says
 * "incomplete" must also be the one the gate actually excludes, on a library the gate has never
 * seen before this call so its process-lifetime cache cannot mask a divergence between the two.
 */
@OpaaIndexingIntegrationTest
class FullTextBackfillGateStatusParityIntegrationTest {

  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FullTextBackfillProgressService progressService;
  @Autowired private FullTextBackfillGate gate;

  private final UUID libraryId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
  }

  @Test
  void aLibraryTheProgressCountReportsIncompleteIsTheOneTheGateExcludes() {
    // Written straight into the vector store, bypassing the backfill - so no chunk_full_text row
    // exists and the library is exactly in the state the lexical path refuses to search.
    seed("Gebührenbefreiung wegen Bedürftigkeit.");

    FullTextBackfillProgress progress = progressService.progressForLibrary(libraryId);

    assertThat(progress.isComplete()).isFalse();
    assertThat(gate.searchableLibraries(Set.of(libraryId))).isEmpty();
  }

  private void seed(String text) {
    Document chunk =
        new Document(
            text,
            Map.of(
                VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString(),
                VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString()));
    vectorStore.add(List.of(chunk));
  }
}
