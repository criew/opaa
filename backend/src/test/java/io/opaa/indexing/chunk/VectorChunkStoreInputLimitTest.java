package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.metadata.EmbeddingRateEstimator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * {@link VectorChunkStore} never stores a vector the endpoint cut short without saying so: an input
 * that could reach {@code opaa.embedding.max-input-tokens} is embedded alone and checked against
 * the endpoint's refusal or token count (#2611).
 */
class VectorChunkStoreInputLimitTest {

  private static final int LIMIT = 100;

  private final VectorStoreWriter writer = mock(VectorStoreWriter.class);
  private final BatchingStrategy oneBatch = List::of;

  private VectorChunkStore storeOn(EmbeddingModel endpoint, int limit) {
    return new VectorChunkStore(
        mock(VectorStore.class),
        endpoint,
        oneBatch,
        writer,
        mock(FullTextChunkStore.class),
        new EmbeddingRateEstimator(4.0),
        limit);
  }

  @Test
  void aChunkTheEndpointSilentlyCutsIsReportedAndNeverWritten() {
    // regression guard for #2611: Ollama's OpenAI-compatible route cut a chunk beyond its input
    // limit without an error, and the cut vector went into the index.
    ScriptedEmbeddingEndpoint endpoint = ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT);
    VectorChunkStore store = storeOn(endpoint, LIMIT);
    List<Document> chunks =
        List.of(chunk("kurz", "S. 1", 0), chunk("x".repeat(150), "Abschn. Tabelle 3", 1));

    assertThatThrownBy(() -> store.addChunks(chunks))
        .isInstanceOfSatisfying(
            ChunkNotEmbeddableException.class,
            e -> {
              assertThat(e.location()).isEqualTo("Abschn. Tabelle 3");
              assertThat(e.chunkIndex()).isEqualTo(1);
            });
    verifyNoInteractions(writer);
  }

  @Test
  void checkInputsNamesTheCutChunkWithTheTokenCountTheEndpointReported() {
    VectorChunkStore store = storeOn(ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT), LIMIT);

    EmbeddingInputCheck check =
        store.checkInputs(
            List.of(chunk("kurz", "S. 1", 0), chunk("x".repeat(150), "S. 2", 1)), Map.of());

    assertThat(check.overlong()).containsExactly(new EmbeddingInputCheck.Overlong(1, LIMIT));
    assertThat(check.unverified()).isEmpty();
  }

  @Test
  void aChunkTheEndpointRefusesForLengthIsReportedWithoutATokenCount() {
    VectorChunkStore store = storeOn(ScriptedEmbeddingEndpoint.refusing(LIMIT), LIMIT);

    EmbeddingInputCheck check =
        store.checkInputs(List.of(chunk("x".repeat(150), "S. 2", 0)), Map.of());

    assertThat(check.overlong()).containsExactly(new EmbeddingInputCheck.Overlong(0, null));
  }

  @Test
  void onlyInputsThatCouldReachTheLimitCostACallOfTheirOwn() {
    ScriptedEmbeddingEndpoint endpoint = ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT);
    VectorChunkStore store = storeOn(endpoint, LIMIT);
    String nearLimit = "y".repeat(LIMIT - VectorChunkStore.SPECIAL_TOKEN_RESERVE);
    String belowReach = "z".repeat(LIMIT - VectorChunkStore.SPECIAL_TOKEN_RESERVE - 1);

    EmbeddingInputCheck check =
        store.checkInputs(
            List.of(chunk(belowReach, "S. 1", 0), chunk(nearLimit, "S. 2", 1)), Map.of());

    assertThat(endpoint.requests()).containsExactly(List.of(nearLimit));
    assertThat(check.overlong()).isEmpty();
    assertThat(check.vectors()).containsOnlyKeys(nearLimit);
  }

  @Test
  void aCheckedVectorIsWrittenWithoutEmbeddingTheInputASecondTime() {
    ScriptedEmbeddingEndpoint endpoint = ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT);
    VectorChunkStore store = storeOn(endpoint, LIMIT);
    String nearLimit = "y".repeat(LIMIT - 1);
    List<Document> chunks = List.of(chunk("kurz", "S. 1", 0), chunk(nearLimit, "S. 2", 1));

    store.addChunks(chunks);

    assertThat(endpoint.requests()).containsExactly(List.of(nearLimit), List.of("kurz"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<float[]>> vectors = ArgumentCaptor.forClass(List.class);
    verify(writer).writeEmbeddedChunks(org.mockito.ArgumentMatchers.eq(chunks), vectors.capture());
    assertThat(vectors.getValue()).hasSize(2);
    assertThat(vectors.getValue().get(1)[0]).isEqualTo(LIMIT - 1);
  }

  @Test
  void knownVectorsAreReusedInsteadOfCheckingTheInputAgain() {
    ScriptedEmbeddingEndpoint endpoint = ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT);
    VectorChunkStore store = storeOn(endpoint, LIMIT);
    String nearLimit = "y".repeat(LIMIT - 1);

    store.addChunks(List.of(chunk(nearLimit, "S. 2", 0)), Map.of(nearLimit, new float[] {7f}));

    assertThat(endpoint.requests()).isEmpty();
  }

  @Test
  void withoutATokenCountAChunkThatCouldReachTheLimitIsKeptButReportedUnverified() {
    VectorChunkStore store =
        storeOn(ScriptedEmbeddingEndpoint.silentlyCuttingWithoutUsage(LIMIT), LIMIT);
    List<Document> chunks = List.of(chunk("x".repeat(150), "S. 2", 0));

    EmbeddingInputCheck check = store.checkInputs(chunks, Map.of());
    store.addChunks(chunks);

    assertThat(check.overlong()).isEmpty();
    assertThat(check.unverified()).containsExactly(0);
    verify(writer).writeEmbeddedChunks(org.mockito.ArgumentMatchers.eq(chunks), any());
  }

  @Test
  void aFailureThatIsNoRefusalForLengthKeepsItsOwnException() {
    EmbeddingModel endpoint = mock(EmbeddingModel.class);
    IllegalStateException unreachable = new IllegalStateException("Connection refused");
    when(endpoint.call(any(EmbeddingRequest.class))).thenThrow(unreachable);
    VectorChunkStore store = storeOn(endpoint, LIMIT);

    assertThatThrownBy(() -> store.checkInputs(List.of(chunk("x".repeat(150), null, 0)), Map.of()))
        .isSameAs(unreachable);
  }

  @Test
  void aBatchRefusedForLengthNamesTheChunkTheEndpointRefusesAlone() {
    // The configured limit lies above the endpoint's real one: nothing looks suspicious up front,
    // the batch is refused, and the refused chunk is still named instead of a bare failure.
    VectorChunkStore store = storeOn(ScriptedEmbeddingEndpoint.refusing(LIMIT), 10 * LIMIT);
    List<Document> chunks =
        List.of(chunk("kurz", "S. 1", 0), chunk("x".repeat(150), "Abschn. Anlage", 1));

    assertThatThrownBy(() -> store.addChunks(chunks))
        .isInstanceOfSatisfying(
            ChunkNotEmbeddableException.class,
            e -> assertThat(e.location()).isEqualTo("Abschn. Anlage"));
    verifyNoInteractions(writer);
  }

  private static Document chunk(String text, String location, int index) {
    Map<String, Object> metadata =
        location == null
            ? Map.of(VectorChunkStore.CHUNK_INDEX_METADATA_KEY, index)
            : Map.of(
                ChunkMetadataKeys.LOCATION_METADATA_KEY,
                location,
                VectorChunkStore.CHUNK_INDEX_METADATA_KEY,
                index);
    Document chunk = new Document(text, metadata);
    // As every stored chunk without a Kontextpraefix: its text alone is the embedding input.
    chunk.setContentFormatter((document, mode) -> document.getText());
    return chunk;
  }
}
