package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TestFeatureFlags;
import io.opaa.format.DocumentProperties;
import io.opaa.format.DocumentService;
import io.opaa.format.chunk.ChunkingService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.attachment.AttachmentLimits;
import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.chunk.VectorStoreWriter;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.QuotaVerdict;
import io.opaa.metadata.CoreMetadataChunkKeys;
import io.opaa.metadata.DocumentChunkMetadata;
import io.opaa.metadata.DocumentMetadataService;
import io.opaa.metadata.EmbeddingRateEstimator;
import io.opaa.metadata.ModelExtractionOutcome;
import io.opaa.metadata.ModelMetadataExtractor;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The ingest with the metadata feature switched off (ADR-0044): the Kernfeld Titel is still
 * extracted and heads the Kontextpraefix, but no other schema value is extracted, no model is asked
 * and no filterable key reaches a chunk. With the feature on, the full extraction runs as before.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestServiceMetadataFeatureTest {

  private static final String ENTRY_URL = "https://example.gov/artikel/eintrag";
  private static final String PUBLISHED_AT = "2025-06-15T10:30:00Z";

  /** What the full extraction would deliver: a filterable Dokumentart and a prefix value. */
  private static final DocumentChunkMetadata FULL =
      new DocumentChunkMetadata(
          Map.of(CoreMetadataChunkKeys.DOCUMENT_TYPE, "SATZUNG_ORDNUNG"),
          CoreMetadataChunkKeys.ALL,
          "Kernfeld-Titel",
          List.of("Satzung/Ordnung"));

  /** What the title-only extraction delivers: the title and nothing else. */
  private static final DocumentChunkMetadata TITLE_ONLY =
      new DocumentChunkMetadata(Map.of(), CoreMetadataChunkKeys.ALL, "Kernfeld-Titel", List.of());

  @Mock private DocumentService documentService;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentRepository documentRepository;
  @Mock private VectorStore vectorStore;
  @Mock private FullTextChunkStore fullTextChunkStore;
  @Mock private EmbeddingModel embeddingModel;
  @Mock private BatchingStrategy batchingStrategy;
  @Mock private VectorStoreWriter vectorStoreWriter;
  @Mock private ChecksumService checksumService;
  @Mock private LibraryStorageQuotaService storageQuotaService;
  @Mock private DocumentMetadataService metadataService;
  @Mock private ModelMetadataExtractor modelExtractor;

  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Bibliothek", null, UUID.randomUUID());
    lenient()
        .when(storageQuotaService.verdictFor(any(), anyLong()))
        .thenReturn(QuotaVerdict.WITHIN);
    lenient()
        .when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), any(), any()))
        .thenReturn(1);
    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(eq(library.getId()), anyString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(chunkingService.chunkDocuments(anyString(), any()))
        .thenReturn(
            List.of(
                new org.springframework.ai.document.Document("first chunk text"),
                new org.springframework.ai.document.Document("second chunk text")));
  }

  @Test
  void switchedOffExtractsOnlyTheTitleAndAsksNoModel() throws IOException {
    when(metadataService.applyTitleExtraction(any(), any(), any())).thenReturn(TITLE_ONLY);

    serviceWith(TestFeatureFlags.without(Feature.METADATA)).ingest(entry(), null);

    ArgumentCaptor<DocumentProperties> properties =
        ArgumentCaptor.forClass(DocumentProperties.class);
    verify(metadataService).applyTitleExtraction(any(), eq("Titel"), properties.capture());
    assertThat(properties.getValue().title()).isEqualTo("Titel");
    verify(metadataService, never()).applyDeterministicExtraction(any(), any(), any());
    verify(modelExtractor, never()).extract(any(), any(), any(), any());
  }

  @Test
  void switchedOffWritesNoFilterableKeyAndOnlyTheTitleIntoThePrefix() throws IOException {
    when(metadataService.applyTitleExtraction(any(), any(), any())).thenReturn(TITLE_ONLY);

    serviceWith(TestFeatureFlags.without(Feature.METADATA)).ingest(entry(), null);

    List<org.springframework.ai.document.Document> stored = storedChunks();
    for (org.springframework.ai.document.Document chunk : stored) {
      assertThat(chunk.getMetadata())
          .doesNotContainKeys(CoreMetadataChunkKeys.ALL.toArray(String[]::new));
    }
    assertThat(stored.getFirst().getFormattedContent(MetadataMode.EMBED))
        .isEqualTo("[Kernfeld-Titel]\n\nfirst chunk text");
  }

  @Test
  void switchedOnRunsTheFullExtractionAsBefore() throws IOException {
    when(metadataService.applyDeterministicExtraction(any(), any(), any())).thenReturn(FULL);
    when(modelExtractor.extract(any(), any(), any(), any()))
        .thenReturn(ModelExtractionOutcome.UNCHANGED);

    serviceWith(TestFeatureFlags.allOn()).ingest(entry(), null);

    verify(metadataService, never()).applyTitleExtraction(any(), any(), any());
    verify(modelExtractor).extract(any(), any(), any(), any());
    List<org.springframework.ai.document.Document> stored = storedChunks();
    assertThat(stored.getFirst().getMetadata())
        .containsEntry(CoreMetadataChunkKeys.DOCUMENT_TYPE, "SATZUNG_ORDNUNG");
    assertThat(stored.getFirst().getFormattedContent(MetadataMode.EMBED))
        .isEqualTo("[Kernfeld-Titel › Satzung/Ordnung]\n\nfirst chunk text");
  }

  private DocumentIngest entry() {
    return DocumentIngests.extractedText(
        library, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT);
  }

  @SuppressWarnings("unchecked")
  private DocumentIngestService serviceWith(FeatureFlags flags) {
    VectorChunkStore vectorChunkStore =
        new VectorChunkStore(
            vectorStore,
            embeddingModel,
            batchingStrategy,
            vectorStoreWriter,
            fullTextChunkStore,
            new EmbeddingRateEstimator(4.0),
            2048);
    return new DocumentIngestService(
        TestPipelineRegistries.fallbackOnly(documentService, chunkingService),
        documentRepository,
        vectorChunkStore,
        checksumService,
        new IndexingMetrics(new SimpleMeterRegistry()),
        storageQuotaService,
        new IndexingProperties(1000, 0, 50, null, null, null, 1),
        Runnable::run,
        mock(ObjectProvider.class),
        new AttachmentLimits(0, 0),
        metadataService,
        modelExtractor,
        flags,
        io.opaa.knowledge.MalwareScanService.disabled(),
        Clock.systemUTC());
  }

  private List<org.springframework.ai.document.Document> storedChunks() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(captor.capture(), any());
    return captor.getValue();
  }
}
