package io.opaa.indexing.chunk;

import io.opaa.compliance.DataFlow;
import io.opaa.format.ChunkFormatMetadata;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.llm.EmbeddingClients;
import io.opaa.llm.EmbeddingClients.Pinned;
import io.opaa.metadata.ChunkMetadataStore;
import io.opaa.metadata.EmbeddingRateEstimator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Writes chunks to the {@link VectorStore} and deletes them by {@code document_id} or {@code
 * library_id}. {@link #DOCUMENT_ID_METADATA_KEY} and {@link #LIBRARY_ID_METADATA_KEY} are the
 * single source of truth for those two chunk metadata keys. {@link #addChunks} also writes {@code
 * chunk_full_text} in the same transaction as the vector write, via {@link VectorStoreWriter} -
 * never one without the other; embedding happens before that transaction opens.
 *
 * <p>No chunk is stored with a vector the endpoint cut short without saying so: an input that could
 * reach the input limit of the installed embedding model is embedded alone and kept only when the
 * endpoint's token count stays below the limit, see {@link #checkInputs}.
 *
 * <p>Each write embeds with one client pinned for it ({@link EmbeddingClients#pin}) and hands its
 * generation to {@link VectorStoreWriter}, which writes only into the column of that generation. A
 * model change, a discard or a switch in between fails the write before anything is stored; it is
 * then embedded again with the current client, at most {@value #GENERATION_ATTEMPTS} times
 * (ADR-0061, Entscheidung 6).
 *
 * <p>The delete methods deliberately do not share a transaction across the two stores: a caller
 * deleting from {@code afterCommit} cannot reliably start a fresh one, and each delete is a single
 * atomic statement, idempotent if only one of the two ever runs.
 */
@DataFlow("embedding-model")
@Component
public class VectorChunkStore implements ChunkMetadataStore {

  private static final Logger log = LoggerFactory.getLogger(VectorChunkStore.class);

  public static final String DOCUMENT_ID_METADATA_KEY = "document_id";
  public static final String LIBRARY_ID_METADATA_KEY = "library_id";
  public static final String CHUNK_INDEX_METADATA_KEY = "chunk_index";

  /**
   * Tokens a model adds to every input on its own (start and end markers), generously rounded. A
   * token covers at least one byte of UTF-8 in every tokenizer of the common embedding models, so
   * an input reaches at most its byte length plus this many tokens.
   */
  static final int SPECIAL_TOKEN_RESERVE = 8;

  private static final EmbeddingOptions OPTIONS = EmbeddingOptions.builder().build();

  /** How often a write embeds again after the generation changed under it. */
  static final int GENERATION_ATTEMPTS = 3;

  private final VectorStore vectorStore;
  private final EmbeddingClients clients;
  private final BatchingStrategy batchingStrategy;
  private final VectorStoreWriter vectorStoreWriter;
  private final FullTextChunkStore fullTextChunkStore;
  private final EmbeddingRateEstimator embeddingRateEstimator;

  /** With the client and input limit of the installed embedding model ({@code embedding_model}). */
  @Autowired
  public VectorChunkStore(
      VectorStore vectorStore,
      EmbeddingClients clients,
      BatchingStrategy batchingStrategy,
      VectorStoreWriter vectorStoreWriter,
      FullTextChunkStore fullTextChunkStore,
      EmbeddingRateEstimator embeddingRateEstimator) {
    this.vectorStore = vectorStore;
    this.clients = clients;
    this.batchingStrategy = batchingStrategy;
    this.vectorStoreWriter = vectorStoreWriter;
    this.fullTextChunkStore = fullTextChunkStore;
    this.embeddingRateEstimator = embeddingRateEstimator;
  }

  /** With a fixed client and input limit, under the initial generation. */
  public VectorChunkStore(
      VectorStore vectorStore,
      EmbeddingModel embeddingModel,
      BatchingStrategy batchingStrategy,
      VectorStoreWriter vectorStoreWriter,
      FullTextChunkStore fullTextChunkStore,
      EmbeddingRateEstimator embeddingRateEstimator,
      int maxInputTokens) {
    this(
        vectorStore,
        EmbeddingClients.fixed(embeddingModel, positive(maxInputTokens)),
        batchingStrategy,
        vectorStoreWriter,
        fullTextChunkStore,
        embeddingRateEstimator);
  }

  /** The input limit of the embedding endpoint, in tokens. */
  public int maxInputTokens() {
    return clients.pin().maxInputTokens();
  }

  private static int positive(int maxInputTokens) {
    if (maxInputTokens <= 0) {
      throw new IllegalArgumentException("maxInputTokens must be positive, got " + maxInputTokens);
    }
    return maxInputTokens;
  }

  /**
   * Embeds {@code chunks} on the calling thread - the same {@link EmbeddingModel}/{@link
   * BatchingStrategy} beans {@code PgVectorStore} uses - and only then hands them to {@link
   * VectorStoreWriter#writeEmbeddedChunks}, which writes both stores in one transaction. Two steps
   * rather than one {@code VectorStore#add} inside a transaction: embedding is an HTTP round trip,
   * and holding a pooled connection for its duration risks exhausting the pool.
   *
   * @throws ChunkNotEmbeddableException when one chunk alone exceeds the batching strategy's token
   *     budget or the endpoint proves it too long; nothing is written then
   */
  public void addChunks(List<Document> chunks) {
    addChunks(chunks, Map.of(), 0);
  }

  /**
   * {@link #addChunks(List)}, reusing {@code known} vectors (keyed by embedding input, as {@link
   * EmbeddingInputCheck#vectors} holds them) instead of embedding those inputs again - but only
   * while {@code knownGeneration} is the generation of the client this write pins; vectors of
   * another model are never written.
   */
  public void addChunks(List<Document> chunks, Map<String, float[]> known, long knownGeneration) {
    if (chunks.isEmpty()) {
      return;
    }
    requireEmbeddable(chunks);
    for (int attempt = 1; ; attempt++) {
      Pinned pinned = clients.pin();
      Map<String, float[]> reusable = pinned.generation() == knownGeneration ? known : Map.of();
      EmbeddingInputCheck check = checkInputs(pinned, chunks, reusable, Runnable::run);
      if (!check.overlong().isEmpty()) {
        EmbeddingInputCheck.Overlong first = check.overlong().getFirst();
        throw notEmbeddable(chunks.get(first.position()), null);
      }
      warnUnverified(pinned, chunks, check);
      try {
        vectorStoreWriter.writeEmbeddedChunks(
            chunks, vectorsFor(pinned, chunks, reusable, check.vectors()), pinned.generation());
        return;
      } catch (EmbeddingGenerationChangedException e) {
        if (attempt >= GENERATION_ATTEMPTS) {
          throw e;
        }
        log.info("The embedding generation changed during a write; embedding again");
      }
    }
  }

  /**
   * Re-embeds chunks of one document that are already stored, under their own ids and with their
   * stored text, and writes the new vectors and full-text rows only for those still stored at write
   * time - see {@link VectorStoreWriter#rewriteEmbeddedChunks}. This path cannot split: a chunk the
   * endpoint cut silently keeps its cut vector, so a re-embedding run can finish, and the
   * document's chunks are then marked with pipeline version 0, which makes the pipeline re-index
   * read the document again and split it.
   *
   * @throws ChunkNotEmbeddableException when the endpoint refuses a chunk instead of cutting it, or
   *     a chunk alone exceeds the batching strategy's token budget; nothing is written then
   */
  public Reembedded reembedChunks(List<Document> chunks) {
    if (chunks.isEmpty()) {
      return new Reembedded(0, 0, 0);
    }
    requireEmbeddable(chunks);
    for (int attempt = 1; ; attempt++) {
      try {
        return reembedOnce(clients.pin(), chunks);
      } catch (EmbeddingGenerationChangedException e) {
        if (attempt >= GENERATION_ATTEMPTS) {
          throw e;
        }
        log.info("The embedding generation changed during a re-embedding; embedding again");
      }
    }
  }

  private Reembedded reembedOnce(Pinned pinned, List<Document> chunks) {
    EmbeddingInputCheck check = checkInputs(pinned, chunks, Map.of(), Runnable::run);
    Map<String, float[]> vectors = new HashMap<>(check.vectors());
    for (EmbeddingInputCheck.Overlong overlong : check.overlong()) {
      float[] cut = check.cutVectors().get(inputOf(chunks.get(overlong.position())));
      if (cut == null) {
        throw notEmbeddable(chunks.get(overlong.position()), null);
      }
      vectors.put(inputOf(chunks.get(overlong.position())), cut);
    }
    warnUnverified(pinned, chunks, check);
    int rewritten =
        vectorStoreWriter.rewriteEmbeddedChunks(
            chunks, vectorsFor(pinned, chunks, Map.of(), vectors), pinned.generation());
    // Chunks a concurrent ingest replaced came from a fresh cut that already split; only a
    // document whose chunks were all rewritten here still carries a cut vector.
    if (!check.overlong().isEmpty() && rewritten == chunks.size()) {
      Object documentId = chunks.getFirst().getMetadata().get(DOCUMENT_ID_METADATA_KEY);
      if (documentId != null) {
        vectorStoreWriter.updateDocumentMetadata(
            UUID.fromString(documentId.toString()),
            Map.of(ChunkFormatMetadata.PIPELINE_VERSION_METADATA_KEY, 0),
            Set.of());
      }
    }
    return new Reembedded(rewritten, check.overlong().size(), check.unverified().size());
  }

  /**
   * The outcome of {@link #reembedChunks}.
   *
   * @param rewritten chunks still stored at write time that got their new vector
   * @param cut chunks written with a vector the endpoint cut short; their document waits for the
   *     pipeline re-index
   * @param unverified chunks that could reach the limit, embedded without a token count
   */
  public record Reembedded(int rewritten, int cut, int unverified) {}

  private void warnUnverified(Pinned pinned, List<Document> chunks, EmbeddingInputCheck check) {
    if (!check.unverified().isEmpty()) {
      log.warn(
          "{} chunk(s) of document {} could reach the embedding input limit of {} tokens, but the"
              + " endpoint reported no token count; a silent cut cannot be ruled out",
          check.unverified().size(),
          chunks.getFirst().getMetadata().get(DOCUMENT_ID_METADATA_KEY),
          pinned.maxInputTokens());
    }
  }

  /** The vector of every chunk in order, embedding in batches every input neither map holds. */
  private List<float[]> vectorsFor(
      Pinned pinned,
      List<Document> chunks,
      Map<String, float[]> known,
      Map<String, float[]> checked) {
    Map<String, float[]> precomputed = new HashMap<>(known);
    precomputed.putAll(checked);
    List<Document> remaining =
        precomputed.isEmpty()
            ? chunks
            : chunks.stream().filter(chunk -> !precomputed.containsKey(inputOf(chunk))).toList();
    List<float[]> embedded = remaining.isEmpty() ? List.of() : embedBatch(pinned, remaining);
    if (precomputed.isEmpty()) {
      return embedded;
    }
    List<float[]> embeddings = new ArrayList<>(chunks.size());
    Iterator<float[]> next = embedded.iterator();
    for (Document chunk : chunks) {
      float[] reused = precomputed.get(inputOf(chunk));
      embeddings.add(reused != null ? reused : next.next());
    }
    return embeddings;
  }

  /** {@link #checkInputs(List, Map, Executor)} with every call on the calling thread. */
  public EmbeddingInputCheck checkInputs(List<Document> chunks, Map<String, float[]> known) {
    return checkInputs(chunks, known, Runnable::run);
  }

  /**
   * {@link #checkInputs(Pinned, List, Map, Executor)} with a client pinned for this check; the
   * result names its generation, and {@code known} must be of the current one.
   */
  public EmbeddingInputCheck checkInputs(
      List<Document> chunks, Map<String, float[]> known, Executor executor) {
    return checkInputs(clients.pin(), chunks, known, executor);
  }

  /**
   * Embeds alone every chunk whose input could reach the input limit and is not in {@code known},
   * each call on {@code executor}. A chunk counts as too long when the endpoint refuses it as such
   * or reports a token count at the limit: a silently cutting endpoint (Ollama's OpenAI-compatible
   * route) counts exactly the tokens it kept. Every other chunk costs no call here.
   *
   * @throws RuntimeException the endpoint's own failure when it is not a refusal for length
   */
  private EmbeddingInputCheck checkInputs(
      Pinned pinned, List<Document> chunks, Map<String, float[]> known, Executor executor) {
    Map<String, CompletableFuture<Single>> calls = new LinkedHashMap<>();
    for (Document chunk : chunks) {
      String input = inputOf(chunk);
      if (mayReachLimit(pinned, input) && !known.containsKey(input) && !calls.containsKey(input)) {
        calls.put(input, CompletableFuture.supplyAsync(() -> embedAlone(pinned, input), executor));
      }
    }
    Map<String, Single> results = new HashMap<>();
    try {
      for (Map.Entry<String, CompletableFuture<Single>> call : calls.entrySet()) {
        results.put(call.getKey(), call.getValue().join());
      }
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException failure) {
        throw failure;
      }
      throw e;
    }
    Map<String, float[]> vectors = new HashMap<>();
    Map<String, float[]> cutVectors = new HashMap<>();
    List<EmbeddingInputCheck.Overlong> overlong = new ArrayList<>();
    List<Integer> unverified = new ArrayList<>();
    for (int position = 0; position < chunks.size(); position++) {
      String input = inputOf(chunks.get(position));
      Single single = results.get(input);
      if (single == null) {
        continue;
      }
      if (single.tooLong()) {
        overlong.add(new EmbeddingInputCheck.Overlong(position, single.reportedTokens()));
        if (single.vector() != null) {
          cutVectors.put(input, single.vector());
        }
        continue;
      }
      vectors.put(input, single.vector());
      if (single.reportedTokens() == null) {
        unverified.add(position);
      }
    }
    return new EmbeddingInputCheck(vectors, overlong, unverified, cutVectors, pinned.generation());
  }

  private static boolean mayReachLimit(Pinned pinned, String input) {
    return input.getBytes(StandardCharsets.UTF_8).length + SPECIAL_TOKEN_RESERVE
        >= pinned.maxInputTokens();
  }

  /** The text the embedding model receives for {@code chunk}, Kontextpräfix included. */
  private static String inputOf(Document chunk) {
    return chunk.getFormattedContent(MetadataMode.EMBED);
  }

  /**
   * One input embedded alone. {@code reportedTokens} is {@code null} when the endpoint refused it
   * or reported no count; {@code vector} is {@code null} exactly when it refused it, and is the cut
   * vector when it is too long by count.
   */
  private record Single(float[] vector, Integer reportedTokens, boolean tooLong) {}

  private Single embedAlone(Pinned pinned, String input) {
    long measurementToken = embeddingRateEstimator.started();
    long startedAt = System.nanoTime();
    EmbeddingResponse response;
    try {
      response = pinned.client().call(new EmbeddingRequest(List.of(input), OPTIONS));
    } catch (RuntimeException e) {
      if (EmbeddingInputRejection.isInputTooLong(e)) {
        return new Single(null, null, true);
      }
      throw e;
    } finally {
      embeddingRateEstimator.record(1, System.nanoTime() - startedAt, measurementToken);
    }
    Integer tokens = promptTokens(response);
    return new Single(
        response.getResult().getOutput(),
        tokens,
        tokens != null && tokens >= pinned.maxInputTokens());
  }

  /** The endpoint's token count for the request, or {@code null} when it reported none. */
  private static Integer promptTokens(EmbeddingResponse response) {
    Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    Integer tokens = usage == null ? null : usage.getPromptTokens();
    return tokens == null || tokens <= 0 ? null : tokens;
  }

  /**
   * Embeds {@code chunks} in batches. An endpoint refusing the batch for length names the chunk:
   * each is then embedded alone, and the first one refused is reported.
   */
  private List<float[]> embedBatch(Pinned pinned, List<Document> chunks) {
    // Measured around the embedding round trip only, never the write: the Folgekosten estimate
    // names the cost of embedding calls, and a slow disk must not make a reindex look expensive.
    long measurementToken = embeddingRateEstimator.started();
    long startedAt = System.nanoTime();
    try {
      return pinned.client().embed(chunks, OPTIONS, batchingStrategy);
    } catch (IllegalArgumentException e) {
      throw oversizedChunkOr(chunks, e);
    } catch (RuntimeException e) {
      if (!EmbeddingInputRejection.isInputTooLong(e)) {
        throw e;
      }
      for (Document chunk : chunks) {
        if (embedAlone(pinned, inputOf(chunk)).tooLong()) {
          throw notEmbeddable(chunk, e);
        }
      }
      throw e;
    } finally {
      embeddingRateEstimator.record(chunks.size(), System.nanoTime() - startedAt, measurementToken);
    }
  }

  /**
   * Checks every chunk against the batching strategy's token budget without embedding anything -
   * for a caller that splits {@code chunks} across several {@link #addChunks} calls, each of which
   * commits on its own.
   *
   * @throws ChunkNotEmbeddableException naming the first chunk that exceeds the budget alone
   */
  public void requireEmbeddable(List<Document> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
    try {
      batchingStrategy.batch(chunks);
    } catch (IllegalArgumentException e) {
      throw oversizedChunkOr(chunks, e);
    }
  }

  /**
   * The first chunk {@link #batchingStrategy} rejects on its own, as a {@link
   * ChunkNotEmbeddableException}; {@code failure} itself when every chunk passes alone, so an
   * unrelated argument error keeps its own cause. Only runs after a failed call.
   */
  private RuntimeException oversizedChunkOr(
      List<Document> chunks, IllegalArgumentException failure) {
    for (Document chunk : chunks) {
      try {
        batchingStrategy.batch(List.of(chunk));
      } catch (IllegalArgumentException rejected) {
        return notEmbeddable(chunk, failure);
      }
    }
    return failure;
  }

  /** {@code chunk} as a {@link ChunkNotEmbeddableException}, named by its Fundort and position. */
  private static ChunkNotEmbeddableException notEmbeddable(Document chunk, Throwable cause) {
    Object location = chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY);
    Object index = chunk.getMetadata().get(CHUNK_INDEX_METADATA_KEY);
    return new ChunkNotEmbeddableException(
        location == null ? null : location.toString(),
        index instanceof Integer position ? position : null,
        cause);
  }

  /**
   * The stored text of a document, its chunks in order and capped at {@code limit} characters - the
   * model step's input for a document whose file is not being parsed anyway (the Bestandslauf).
   */
  public String documentText(UUID documentId, int limit) {
    return vectorStoreWriter.documentText(documentId, limit);
  }

  /**
   * Rewrites document-level metadata keys on every chunk of {@code documentId} without touching
   * content or embedding (ADR-0024) - see {@link VectorStoreWriter#updateDocumentMetadata}. The
   * path a metadata correction and the Bestandslauf take instead of re-indexing.
   *
   * @return the number of chunks updated
   */
  @Override
  public int updateDocumentMetadata(
      UUID documentId, Map<String, Object> values, Set<String> keysToClear) {
    return vectorStoreWriter.updateDocumentMetadata(documentId, values, keysToClear);
  }

  /**
   * Deletes every chunk (vector and full-text) carrying the given {@code document_id} metadata. The
   * vector delete waits for a running {@link #reembedChunks} of the same document instead of
   * deadlocking with it (see {@link VectorStoreWriter#rewriteEmbeddedChunks}).
   */
  public void deleteByDocumentId(UUID documentId) {
    vectorStoreWriter.deleteChunksOfDocument(documentId);
    fullTextChunkStore.deleteByDocumentId(documentId);
  }

  /**
   * Deletes every chunk (vector and full-text) carrying the given {@code library_id} metadata;
   * without a vector table there is none.
   */
  public void deleteByLibraryId(UUID libraryId) {
    if (vectorStoreWriter.tableExists()) {
      vectorStore.delete(equalsFilter(LIBRARY_ID_METADATA_KEY, libraryId));
    }
    fullTextChunkStore.deleteByLibraryId(libraryId);
  }

  /**
   * Deletes, in the caller's transaction and without swallowing a failure, every chunk of {@code
   * libraryId} or of one of {@code documentIds} from both stores - for an erasure that verifies.
   *
   * @return how many vector chunks went
   */
  public int eraseAllOf(UUID libraryId, Collection<UUID> documentIds) {
    return vectorStoreWriter.deleteAllOf(libraryId, documentIds);
  }

  /**
   * How many chunks of {@code libraryId} or of one of {@code documentIds} are left in both stores.
   */
  public long remainingOf(UUID libraryId, Collection<UUID> documentIds) {
    return vectorStoreWriter.countAllOf(libraryId, documentIds);
  }

  private Filter.Expression equalsFilter(String metadataKey, UUID value) {
    return new FilterExpressionBuilder().eq(metadataKey, value.toString()).build();
  }
}
