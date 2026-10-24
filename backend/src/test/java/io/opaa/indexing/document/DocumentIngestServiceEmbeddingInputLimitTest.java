package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.format.ChunkFormatMetadata;
import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.DocumentService;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.chunk.ChunkingService;
import io.opaa.format.file.fallback.TikaFallbackFormat;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.ScriptedEmbeddingEndpoint;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.chunk.VectorStoreWriter;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.QuotaVerdict;
import io.opaa.metadata.EmbeddingRateEstimator;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * A chunk the embedding endpoint proves too long is split at text boundaries and stored whole in
 * parts instead of cut. Every part keeps the Fundort, and a chunk opening with its heading path
 * repeats it.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestServiceEmbeddingInputLimitTest {

  private static final int LIMIT = 300;
  private static final String HEADING = "Haushaltsplan › Kapitel 050";
  private static final String ROW = "| 0601 | 425 01 | Zuweisungen | 1.234.567,89 | -12,5 % |";

  @Mock private DocumentService documentService;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentRepository documentRepository;
  @Mock private ChecksumService checksumService;
  @Mock private LibraryStorageQuotaService storageQuotaService;
  @Mock private VectorStoreWriter writer;

  @TempDir Path tempDir;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final List<ExecutorService> executors = new ArrayList<>();
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Bibliothek", null, UUID.randomUUID());
    lenient()
        .when(storageQuotaService.verdictFor(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(QuotaVerdict.WITHIN);
    lenient()
        .when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), any(), any()))
        .thenReturn(1);
    lenient().when(documentRepository.markFailedWithoutChunks(any(), any())).thenReturn(1);
    lenient()
        .when(documentRepository.save(any(Document.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(documentRepository.findByLibraryIdAndFilePath(any(), any()))
        .thenReturn(Optional.empty());
  }

  @AfterEach
  void tearDown() {
    executors.forEach(ExecutorService::shutdownNow);
  }

  @Test
  void aChunkTheEndpointSilentlyCutsIsStoredWholeInPartsUnderTheLimit() throws IOException {
    // regression guard for #2611: Ollama's OpenAI-compatible route cut a dense table chunk at its
    // input limit without an error, and the vector covered only the start of the chunk.
    ScriptedEmbeddingEndpoint endpoint = ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT);
    String body = table(12);

    List<org.springframework.ai.document.Document> stored =
        ingest(endpoint, 1, List.of(sectionChunk(body), plain("Kurzer Schluss.")));

    assertThat(stored).hasSizeGreaterThan(2);
    assertThat(stored)
        .allSatisfy(
            chunk ->
                assertThat(ScriptedEmbeddingEndpoint.tokensOf(embeddingInput(chunk)))
                    .isLessThan(LIMIT));
    List<org.springframework.ai.document.Document> parts = stored.subList(0, stored.size() - 1);
    assertThat(parts)
        .allSatisfy(
            part -> {
              assertThat(part.getText()).startsWith(HEADING + "\n\n");
              assertThat(part.getMetadata())
                  .containsEntry(ChunkMetadataKeys.LOCATION_METADATA_KEY, "Abschn. " + HEADING);
            });
    assertThat(
            String.join(
                "\n",
                parts.stream().map(p -> p.getText().substring(HEADING.length() + 2)).toList()))
        .isEqualTo(body);
    assertThat(stored)
        .extracting(chunk -> chunk.getMetadata().get(VectorChunkStore.CHUNK_INDEX_METADATA_KEY))
        .containsExactlyElementsOf(
            java.util.stream.IntStream.range(0, stored.size()).boxed().toList());
    assertThat(stored.getLast().getText()).isEqualTo("Kurzer Schluss.");
    verify(documentRepository).markIndexedFromSource(any(), eq(stored.size()), any(), any(), any());
    assertThat(counter("split")).isPositive();
  }

  @Test
  void everySplitPartCarriesThePipelinesChunkingFingerprint() throws IOException {
    // The pipeline re-index selects chunks by this key; a part without it would stay due forever.
    List<org.springframework.ai.document.Document> stored =
        ingest(
            ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT),
            1,
            List.of(sectionChunk(table(12)), plain("Kurzer Schluss.")),
            registryWithFingerprint("fp-2611"));

    assertThat(stored).hasSizeGreaterThan(2);
    assertThat(stored)
        .allSatisfy(
            chunk ->
                assertThat(chunk.getMetadata())
                    .containsEntry(
                        ChunkFormatMetadata.CHUNKING_FINGERPRINT_METADATA_KEY, "fp-2611"));
  }

  @Test
  void aChunkTheEndpointRefusesForLengthIsSplitTheSameWay() throws IOException {
    List<org.springframework.ai.document.Document> stored =
        ingest(ScriptedEmbeddingEndpoint.refusing(LIMIT), 1, List.of(sectionChunk(table(12))));

    assertThat(stored).hasSizeGreaterThan(1);
    assertThat(stored)
        .allSatisfy(
            chunk ->
                assertThat(ScriptedEmbeddingEndpoint.tokensOf(embeddingInput(chunk)))
                    .isLessThanOrEqualTo(LIMIT));
  }

  @Test
  void theSplitAlsoHoldsWhenSubBatchesAreEmbeddedConcurrently() throws IOException {
    ScriptedEmbeddingEndpoint endpoint = ScriptedEmbeddingEndpoint.silentlyCutting(LIMIT);

    List<org.springframework.ai.document.Document> stored =
        ingest(endpoint, 3, List.of(plain("Erster."), sectionChunk(table(12)), plain("Letzter.")));

    assertThat(stored)
        .allSatisfy(
            chunk ->
                assertThat(ScriptedEmbeddingEndpoint.tokensOf(embeddingInput(chunk)))
                    .isLessThan(LIMIT));
    assertThat(stored.getFirst().getText()).isEqualTo("Erster.");
    assertThat(stored.getLast().getText()).isEqualTo("Letzter.");
  }

  @Test
  void withoutATokenCountTheChunkStaysWholeAndIsCountedUnverified() throws IOException {
    String body = table(12);

    List<org.springframework.ai.document.Document> stored =
        ingest(
            ScriptedEmbeddingEndpoint.silentlyCuttingWithoutUsage(LIMIT),
            1,
            List.of(sectionChunk(body)));

    assertThat(stored).hasSize(1);
    assertThat(counter("unverified")).isEqualTo(1.0);
    assertThat(counter("split")).isZero();
  }

  private List<org.springframework.ai.document.Document> ingest(
      EmbeddingModel endpoint,
      int concurrency,
      List<org.springframework.ai.document.Document> chunks)
      throws IOException {
    return ingest(
        endpoint,
        concurrency,
        chunks,
        TestPipelineRegistries.fallbackOnly(documentService, chunkingService));
  }

  private List<org.springframework.ai.document.Document> ingest(
      EmbeddingModel endpoint,
      int concurrency,
      List<org.springframework.ai.document.Document> chunks,
      DocumentFormatRegistry registry)
      throws IOException {
    Path file = tempDir.resolve("haushalt.txt");
    Files.writeString(file, "irrelevant");
    when(checksumService.computeSha256(file)).thenReturn("checksum");
    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq("haushalt.txt"), eq(parsed))).thenReturn(chunks);

    DocumentIngestResult result =
        service(endpoint, concurrency, registry)
            .ingest(DocumentIngest.localFile(library, file).build(), null);

    assertThat(result).isEqualTo(DocumentIngestResult.PROCESSED);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> written =
        ArgumentCaptor.forClass(List.class);
    verify(writer, atLeastOnce()).writeEmbeddedChunks(written.capture(), any());
    return written.getAllValues().stream()
        .flatMap(List::stream)
        .sorted(
            java.util.Comparator.comparingInt(
                chunk ->
                    (Integer) chunk.getMetadata().get(VectorChunkStore.CHUNK_INDEX_METADATA_KEY)))
        .toList();
  }

  /** The fallback pipeline, reporting {@code fingerprint} as the configuration of its cut. */
  private DocumentFormatRegistry registryWithFingerprint(String fingerprint) {
    TikaFallbackFormat fallback = new TikaFallbackFormat(documentService, chunkingService);
    DocumentFormat fingerprinted =
        new DocumentFormat() {
          @Override
          public String id() {
            return fallback.id();
          }

          @Override
          public short version() {
            return fallback.version();
          }

          @Override
          public DocumentFormatResult run(DocumentFormatSource source) {
            return fallback.run(source);
          }

          @Override
          public java.util.Set<String> passthroughMetadataKeys() {
            return fallback.passthroughMetadataKeys();
          }

          @Override
          public String chunkingFingerprint() {
            return fingerprint;
          }
        };
    return new DocumentFormatRegistry(List.of(fingerprinted), fingerprinted);
  }

  private DocumentIngestService service(
      EmbeddingModel endpoint, int concurrency, DocumentFormatRegistry registry) {
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    executors.add(executor);
    VectorChunkStore store =
        new VectorChunkStore(
            mock(VectorStore.class),
            endpoint,
            List::of,
            writer,
            mock(FullTextChunkStore.class),
            new EmbeddingRateEstimator(4.0),
            LIMIT);
    return new DocumentIngestService(
        registry,
        documentRepository,
        store,
        checksumService,
        new IndexingMetrics(meterRegistry),
        storageQuotaService,
        new IndexingProperties(1000, 0, 2, null, null, null, concurrency),
        executor,
        mock(org.springframework.beans.factory.ObjectProvider.class),
        new io.opaa.indexing.attachment.AttachmentLimits(0, 0),
        TestDocumentMetadataServices.returningEmpty(),
        TestDocumentMetadataServices.notExtracting(),
        io.opaa.common.TestFeatureFlags.allOn(),
        io.opaa.knowledge.MalwareScanService.disabled(),
        Clock.systemUTC());
  }

  private double counter(String result) {
    return meterRegistry
        .get("opaa.indexing.embedding.inputs")
        .tag("result", result)
        .counter()
        .count();
  }

  private static String table(int rows) {
    return String.join("\n", java.util.Collections.nCopies(rows, ROW));
  }

  private static org.springframework.ai.document.Document sectionChunk(String body) {
    return new org.springframework.ai.document.Document(
        HEADING + "\n\n" + body,
        Map.of(ChunkMetadataKeys.LOCATION_METADATA_KEY, "Abschn. " + HEADING));
  }

  private static org.springframework.ai.document.Document plain(String text) {
    return new org.springframework.ai.document.Document(text);
  }

  private static String embeddingInput(org.springframework.ai.document.Document chunk) {
    return chunk.getFormattedContent(MetadataMode.EMBED);
  }
}
