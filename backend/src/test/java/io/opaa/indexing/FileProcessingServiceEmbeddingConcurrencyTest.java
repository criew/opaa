package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Unit tests for the concurrent embedding path #734 adds to {@link FileProcessingService} (private
 * {@code addToVectorStore}/{@code subBatchSize}, exercised only via {@link
 * FileProcessingService#processFile}) - no real Ollama, a fake {@link VectorStoreWriter} standing
 * in for the terminal write call {@link VectorChunkStore#addChunks} makes after embedding (#1047
 * moved that terminal call from {@code VectorStore#add} to {@code VectorStoreWriter}, see both
 * classes' own Javadoc). {@link FileProcessingServiceTest} already covers {@code
 * embeddingConcurrency == 1} exhaustively (its {@code defaultIndexingProperties()} always uses 1);
 * this class covers only what changes above 1.
 *
 * <p><b>Deterministic where it matters, not everywhere (#735 review, nit 8).</b> {@link
 * #embeddingConcurrencyAboveOneSplitsIntoBatchSizedSubBatchesOnTheExecutor} proves actual overlap
 * with a {@link CyclicBarrier} every write call must reach within a timeout - if the executor ran
 * calls one at a time instead of concurrently, the barrier would time out and fail the test loudly,
 * rather than the test merely observing whatever concurrency scheduling luck happened to produce.
 * The remaining tests (sub-batch count, chunk order, direct-path threading, failure propagation)
 * were always deterministic - only that one "did this actually run concurrently" assertion
 * previously relied on a sleep-widened race window, which this replaces.
 */
@ExtendWith(MockitoExtension.class)
class FileProcessingServiceEmbeddingConcurrencyTest {

  @Mock private DocumentService documentService;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentRepository documentRepository;
  @Mock private ChecksumService checksumService;
  @Mock private LibraryStorageQuotaService storageQuotaService;
  @Mock private EmbeddingModel embeddingModel;
  @Mock private BatchingStrategy batchingStrategy;
  @Mock private FullTextChunkStore fullTextChunkStore;

  @TempDir Path tempDir;

  private SimpleMeterRegistry meterRegistry;
  private final List<ExecutorService> executorsToShutdown = new ArrayList<>();
  private KnowledgeLibrary targetLibrary;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    targetLibrary =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    lenient()
        .when(storageQuotaService.wouldExceedQuota(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(false);
    lenient()
        .when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), any(), any()))
        .thenReturn(1);
    lenient().when(documentRepository.markFailed(any(), any())).thenReturn(1);
    lenient()
        .when(documentRepository.save(any(Document.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(documentRepository.findByLibraryIdAndFilePath(any(), any()))
        .thenReturn(Optional.empty());
  }

  @AfterEach
  void tearDown() {
    executorsToShutdown.forEach(ExecutorService::shutdownNow);
  }

  private FileProcessingService service(
      VectorStoreWriter vectorStoreWriter, int embeddingConcurrency, int batchSize) {
    IndexingProperties properties =
        new IndexingProperties(
            1000, 0, batchSize, null, null, null, null, null, null, embeddingConcurrency);
    // Mirrors IndexingConfiguration#embeddingTaskExecutor exactly (#734): the concurrency bound
    // is the executor's own pool size, not anything FileProcessingService enforces itself - a
    // test executor sized differently from embeddingConcurrency would not actually exercise the
    // bound production relies on.
    ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, embeddingConcurrency));
    executorsToShutdown.add(executor);
    VectorChunkStore vectorChunkStore =
        new VectorChunkStore(
            mock(VectorStore.class),
            embeddingModel,
            batchingStrategy,
            vectorStoreWriter,
            fullTextChunkStore);
    return new FileProcessingService(
        documentService,
        chunkingService,
        documentRepository,
        vectorChunkStore,
        checksumService,
        new IndexingMetrics(meterRegistry),
        storageQuotaService,
        properties,
        executor);
  }

  private List<org.springframework.ai.document.Document> chunksOf(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new org.springframework.ai.document.Document("chunk-" + i))
        .toList();
  }

  private void stubParseAndChunk(
      Path file, String fileName, List<org.springframework.ai.document.Document> chunks)
      throws IOException {
    when(checksumService.computeSha256(file)).thenReturn("checksum-" + fileName);
    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq(fileName), eq(parsed))).thenReturn(chunks);
  }

  @Test
  void embeddingConcurrencyOneAlwaysUsesTheSingleDirectVectorStoreAddCall() throws IOException {
    // #734: concurrency=1 must reproduce the pre-#734 behaviour exactly, regardless of how many
    // chunks the document has - it never even looks at batchSize.
    Path file = tempDir.resolve("many-chunks.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "many-chunks.txt", chunksOf(9));

    RecordingVectorStoreWriter writer = new RecordingVectorStoreWriter(null);
    FileProcessingService service = service(writer, 1, 2);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(writer.writeCalls).hasSize(1);
    assertThat(writer.writeCalls.getFirst()).hasSize(9);
    assertThat(writer.threadNames).containsExactly(Thread.currentThread().getName());
  }

  @Test
  void embeddingConcurrencyAboveOneSplitsIntoBatchSizedSubBatchesOnTheExecutor()
      throws IOException {
    // 6 chunks, batchSize=2, embeddingConcurrency=3 -> subBatchSize = min(2, ceil(6/3)=2) = 2 ->
    // exactly 3 sub-batches (2,2,2), matching both the 3-thread pool (see #service) and the
    // barrier's 3 parties in a single round - every write call must reach the barrier within a
    // timeout, deterministically proving all 3 genuinely overlap rather than merely being
    // observed to (#735 review, nit 8). A CyclicBarrier is cyclic - it resets after every trip -
    // so the sub-batch count is chosen to be an exact multiple of the pool size, or a second,
    // smaller round left over from an uneven split would time out waiting for parties that will
    // never arrive.
    Path file = tempDir.resolve("many-chunks.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "many-chunks.txt", chunksOf(6));

    CyclicBarrier concurrencyProof = new CyclicBarrier(3);
    RecordingVectorStoreWriter writer = new RecordingVectorStoreWriter(concurrencyProof);
    FileProcessingService service = service(writer, 3, 2);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(writer.writeCalls).hasSize(3);
    assertThat(writer.writeCalls.stream().mapToInt(List::size).sum()).isEqualTo(6);
    assertThat(writer.threadNames).doesNotContain(Thread.currentThread().getName());
    // The barrier itself already proved 3 calls overlapped (see RecordingVectorStoreWriter) - this
    // is an additional, redundant cross-check against the same evidence.
    assertThat(writer.maxConcurrentWriteCalls.get()).isEqualTo(3);

    // Chunk order/metadata (#chunk_index) must survive being split into concurrent sub-batches -
    // sorting the union of every write call's chunks by chunk_index must reproduce 0..5 in order.
    List<Integer> chunkIndices =
        writer.writeCalls.stream()
            .flatMap(List::stream)
            .map(doc -> (Integer) doc.getMetadata().get("chunk_index"))
            .sorted()
            .toList();
    assertThat(chunkIndices).containsExactlyElementsOf(IntStream.range(0, 6).boxed().toList());
  }

  @Test
  void singleChunkDocumentTakesTheDirectPathEvenAtHighConcurrency() throws IOException {
    // A single chunk can never be split into more than one sub-batch, regardless of concurrency
    // or batchSize - the direct path is the only path a one-chunk document can take.
    Path file = tempDir.resolve("one-chunk.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "one-chunk.txt", chunksOf(1));

    RecordingVectorStoreWriter writer = new RecordingVectorStoreWriter(null);
    FileProcessingService service = service(writer, 8, 50);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(writer.writeCalls).hasSize(1);
    assertThat(writer.threadNames).containsExactly(Thread.currentThread().getName());
  }

  @Test
  void aFewChunksEngageConcurrencyRegardlessOfBatchSize() throws IOException {
    // #735 review, finding 1: before this fix, the sub-batch size was batchSize itself, so with
    // the production default (batchSize=50) essentially no real document (city-landmarks' own
    // median is 8, max 13 chunks) ever exceeded it - the concurrent path was dead code. Now the
    // sub-batch size is chunkCount spread across embeddingConcurrency workers, capped by
    // batchSize only as an upper bound - so 3 chunks at concurrency=3 must engage 3 sub-batches
    // even though 3 is nowhere near batchSize=50.
    Path file = tempDir.resolve("few-chunks-high-batch-size.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "few-chunks-high-batch-size.txt", chunksOf(3));

    RecordingVectorStoreWriter writer = new RecordingVectorStoreWriter(null);
    FileProcessingService service = service(writer, 3, 50);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(writer.writeCalls).hasSize(3);
    assertThat(writer.threadNames).doesNotContain(Thread.currentThread().getName());
  }

  @Test
  void aFailingSubBatchPropagatesTheOriginalExceptionAndFailsTheDocument() throws IOException {
    // #734: a sub-batch failure must surface exactly like a single write failure did before this
    // issue - processFile's own catch block (unchanged) marks the document FAILED and cleans up
    // whatever chunks the *other*, successful sub-batches already wrote.
    Path file = tempDir.resolve("failing-batch.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "failing-batch.txt", chunksOf(4));

    FileProcessingService service = service(new FailingVectorStoreWriter(), 2, 2);

    assertThatThrownBy(() -> service.processFile(file, targetLibrary))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("embedding call blew up");

    verify(documentRepository).markFailed(any(), org.mockito.ArgumentMatchers.isNull());
  }

  /**
   * Records every {@link VectorStoreWriter#writeEmbeddedChunks} call's chunks, its thread name, and
   * the observed peak concurrency - the write-path counterpart of the pre-#1047 {@code
   * RecordingVectorStore}, moved here because {@link VectorChunkStore#addChunks} now hands
   * already-embedded chunks to {@link VectorStoreWriter} instead of calling {@code VectorStore#add}
   * directly (see both classes' own Javadoc).
   *
   * <p>{@code concurrencyProof}, when given (#735 review, nit 8), makes "these calls actually
   * overlapped" a deterministic fact rather than an observation that depends on scheduling luck:
   * every write call blocks on the same {@link CyclicBarrier} until as many parties as the barrier
   * was built for have all arrived, within a bounded timeout. If the executor ran calls one at a
   * time instead of concurrently, the first call would still be waiting when the timeout expires
   * and the test fails loudly with a clear cause, instead of silently passing on a {@code
   * maxConcurrentWriteCalls} value a sleep window merely made likely.
   *
   * <p>Extends the real {@link VectorStoreWriter} purely for its type (a mocked constructor's
   * dependencies are never touched - every one of them is a bare mock, and the only overridden
   * method never calls {@code super}), not to reuse any of its behaviour.
   */
  private static final class RecordingVectorStoreWriter extends VectorStoreWriter {
    final List<List<org.springframework.ai.document.Document>> writeCalls =
        new CopyOnWriteArrayList<>();
    final Set<String> threadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger concurrentWriteCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger maxConcurrentWriteCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    private final CyclicBarrier concurrencyProof;

    RecordingVectorStoreWriter(CyclicBarrier concurrencyProof) {
      super(
          mock(org.springframework.jdbc.core.JdbcTemplate.class),
          mock(FullTextChunkStore.class),
          mock(tools.jackson.databind.ObjectMapper.class),
          "public",
          "vector_store");
      this.concurrencyProof = concurrencyProof;
    }

    @Override
    public void writeEmbeddedChunks(
        List<org.springframework.ai.document.Document> chunks, List<float[]> embeddings) {
      int current = concurrentWriteCalls.incrementAndGet();
      maxConcurrentWriteCalls.updateAndGet(max -> Math.max(max, current));
      threadNames.add(Thread.currentThread().getName());
      if (concurrencyProof != null) {
        try {
          concurrencyProof.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while proving concurrency", e);
        } catch (BrokenBarrierException | TimeoutException e) {
          throw new IllegalStateException(
              "write calls did not overlap within the timeout - concurrency was not actually"
                  + " exercised",
              e);
        }
      }
      writeCalls.add(new ArrayList<>(chunks));
      concurrentWriteCalls.decrementAndGet();
    }
  }

  /** Every write call throws - simulates one sub-batch's embedding call failing. */
  private static final class FailingVectorStoreWriter extends VectorStoreWriter {
    FailingVectorStoreWriter() {
      super(
          mock(org.springframework.jdbc.core.JdbcTemplate.class),
          mock(FullTextChunkStore.class),
          mock(tools.jackson.databind.ObjectMapper.class),
          "public",
          "vector_store");
    }

    @Override
    public void writeEmbeddedChunks(
        List<org.springframework.ai.document.Document> chunks, List<float[]> embeddings) {
      throw new RuntimeException("embedding call blew up");
    }
  }
}
