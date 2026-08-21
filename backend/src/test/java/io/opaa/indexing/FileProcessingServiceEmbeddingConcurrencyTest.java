package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.library.LibraryVisibility;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * Unit tests for the concurrent embedding path #734 adds to {@link FileProcessingService} (private
 * {@code addToVectorStore}, exercised only via {@link FileProcessingService#processFile}) -
 * deterministic, no real Ollama, a fake {@link VectorStore} standing in for the
 * embedding-triggering call. {@link FileProcessingServiceTest} already covers {@code
 * embeddingConcurrency == 1} exhaustively (its {@code defaultIndexingProperties()} always uses 1);
 * this class covers only what changes above 1.
 */
@ExtendWith(MockitoExtension.class)
class FileProcessingServiceEmbeddingConcurrencyTest {

  @Mock private DocumentService documentService;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentRepository documentRepository;
  @Mock private ChecksumService checksumService;
  @Mock private LibraryStorageQuotaService storageQuotaService;

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
    lenient().when(documentRepository.findByFilePath(any())).thenReturn(Optional.empty());
  }

  @AfterEach
  void tearDown() {
    executorsToShutdown.forEach(ExecutorService::shutdownNow);
  }

  private FileProcessingService service(
      VectorStore vectorStore, int embeddingConcurrency, int batchSize) {
    IndexingProperties properties =
        new IndexingProperties(
            null, 1000, 0, batchSize, 3, null, null, null, null, null, embeddingConcurrency);
    // Mirrors IndexingConfiguration#embeddingTaskExecutor exactly (#734): the concurrency bound
    // is the executor's own pool size, not anything FileProcessingService enforces itself - a
    // test executor sized differently from embeddingConcurrency would not actually exercise the
    // bound production relies on.
    ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, embeddingConcurrency));
    executorsToShutdown.add(executor);
    return new FileProcessingService(
        documentService,
        chunkingService,
        documentRepository,
        vectorStore,
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

    RecordingVectorStore vectorStore = new RecordingVectorStore();
    FileProcessingService service = service(vectorStore, 1, 2);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(vectorStore.addCalls).hasSize(1);
    assertThat(vectorStore.addCalls.getFirst()).hasSize(9);
    assertThat(vectorStore.threadNames).containsExactly(Thread.currentThread().getName());
  }

  @Test
  void embeddingConcurrencyAboveOneSplitsIntoBatchSizedSubBatchesOnTheExecutor()
      throws IOException {
    // 9 chunks, batchSize=2 -> 5 sub-batches (2,2,2,2,1), embeddingConcurrency=3 -> at most 3
    // vectorStore.add calls run at once, all on the shared executor's threads, never on the
    // calling (test) thread.
    Path file = tempDir.resolve("many-chunks.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "many-chunks.txt", chunksOf(9));

    RecordingVectorStore vectorStore = new RecordingVectorStore();
    FileProcessingService service = service(vectorStore, 3, 2);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(vectorStore.addCalls).hasSize(5);
    assertThat(vectorStore.addCalls.stream().mapToInt(List::size).sum()).isEqualTo(9);
    assertThat(vectorStore.threadNames).doesNotContain(Thread.currentThread().getName());
    assertThat(vectorStore.maxConcurrentAddCalls.get()).isGreaterThan(1);
    assertThat(vectorStore.maxConcurrentAddCalls.get()).isLessThanOrEqualTo(3);

    // Chunk order/metadata (#chunk_index) must survive being split into concurrent sub-batches -
    // sorting the union of every add() call's chunks by chunk_index must reproduce 0..8 in order.
    List<Integer> chunkIndices =
        vectorStore.addCalls.stream()
            .flatMap(List::stream)
            .map(doc -> (Integer) doc.getMetadata().get("chunk_index"))
            .sorted()
            .toList();
    assertThat(chunkIndices).containsExactlyElementsOf(IntStream.range(0, 9).boxed().toList());
  }

  @Test
  void documentWithFewerChunksThanBatchSizeTakesTheDirectPathEvenAtHighConcurrency()
      throws IOException {
    // #734: the common case (a document's own chunk count never exceeds batchSize, e.g. the
    // default batchSize=50) must not pay for a round trip through the executor at all - see
    // FileProcessingService#addToVectorStore's own Javadoc.
    Path file = tempDir.resolve("few-chunks.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "few-chunks.txt", chunksOf(3));

    RecordingVectorStore vectorStore = new RecordingVectorStore();
    FileProcessingService service = service(vectorStore, 8, 50);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(vectorStore.addCalls).hasSize(1);
    assertThat(vectorStore.threadNames).containsExactly(Thread.currentThread().getName());
  }

  @Test
  void aFailingSubBatchPropagatesTheOriginalExceptionAndFailsTheDocument() throws IOException {
    // #734: a sub-batch failure must surface exactly like a single vectorStore.add failure did
    // before this issue - processFile's own catch block (unchanged) marks the document FAILED and
    // cleans up whatever chunks the *other*, successful sub-batches already wrote.
    Path file = tempDir.resolve("failing-batch.txt");
    Files.writeString(file, "irrelevant");
    stubParseAndChunk(file, "failing-batch.txt", chunksOf(4));

    FailingVectorStore vectorStore = new FailingVectorStore();
    FileProcessingService service = service(vectorStore, 2, 2);

    assertThatThrownBy(() -> service.processFile(file, targetLibrary))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("embedding call blew up");

    verify(documentRepository).markFailed(any(), org.mockito.ArgumentMatchers.isNull());
  }

  /**
   * Records every {@code add} call's chunks, its thread name, and the observed peak concurrency.
   */
  private static final class RecordingVectorStore implements VectorStore {
    final List<List<org.springframework.ai.document.Document>> addCalls =
        new CopyOnWriteArrayList<>();
    final Set<String> threadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger concurrentAddCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger maxConcurrentAddCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void add(List<org.springframework.ai.document.Document> documents) {
      int current = concurrentAddCalls.incrementAndGet();
      maxConcurrentAddCalls.updateAndGet(max -> Math.max(max, current));
      threadNames.add(Thread.currentThread().getName());
      // A tiny sleep widens the window in which a second concurrent add() call can overlap this
      // one, so maxConcurrentAddCalls reliably observes >1 for the concurrency>1 test instead of
      // depending on scheduling luck.
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      addCalls.add(new ArrayList<>(documents));
      concurrentAddCalls.decrementAndGet();
    }

    @Override
    public void delete(List<String> idList) {}

    @Override
    public void delete(Filter.Expression filterExpression) {}

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(
        org.springframework.ai.vectorstore.SearchRequest request) {
      return List.of();
    }
  }

  /** Every {@code add} call throws - simulates one sub-batch's embedding call failing. */
  private static final class FailingVectorStore implements VectorStore {
    @Override
    public void add(List<org.springframework.ai.document.Document> documents) {
      throw new RuntimeException("embedding call blew up");
    }

    @Override
    public void delete(List<String> idList) {}

    @Override
    public void delete(Filter.Expression filterExpression) {}

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(
        org.springframework.ai.vectorstore.SearchRequest request) {
      return List.of();
    }
  }
}
