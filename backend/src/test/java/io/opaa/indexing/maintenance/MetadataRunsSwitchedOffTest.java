package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.common.Feature;
import io.opaa.common.TestFeatureFlags;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.ChecksumService;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.StoredDocumentSourceAccess;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.metadata.DocumentMetadataService;
import io.opaa.metadata.MetadataFillCounter;
import io.opaa.metadata.ModelMetadataExtractor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * With the metadata feature off (ADR-0044) neither the Bestandslauf nor the Kontextpraefix-Nachlauf
 * advances a document: a call has nothing to do and touches no library and no document.
 */
class MetadataRunsSwitchedOffTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final KnowledgeLibraryRepository libraryRepository =
      mock(KnowledgeLibraryRepository.class);
  private final DocumentMetadataService metadataService = mock(DocumentMetadataService.class);
  private final VectorChunkStore vectorChunkStore = mock(VectorChunkStore.class);

  @Test
  void theBackfillDoesNotRun() {
    MetadataBackfillService service =
        new MetadataBackfillService(
            jdbcTemplate,
            documentRepository,
            libraryRepository,
            metadataService,
            mock(StoredDocumentSourceAccess.class),
            mock(SourceConnectorRegistry.class),
            mock(ChecksumService.class),
            mock(MetadataFillCounter.class),
            mock(ModelMetadataExtractor.class),
            vectorChunkStore,
            TestFeatureFlags.without(Feature.METADATA));

    MetadataBackfillResult result = service.backfillBatch(UUID.randomUUID(), UUID.randomUUID(), 10);

    assertThat(result.isEmpty()).isTrue();
    verifyNoInteractions(
        jdbcTemplate, documentRepository, libraryRepository, metadataService, vectorChunkStore);
  }

  @Test
  void theContextPrefixRerunDoesNotRun() {
    ContextPrefixRerunService service =
        new ContextPrefixRerunService(
            jdbcTemplate,
            documentRepository,
            libraryRepository,
            metadataService,
            vectorChunkStore,
            mock(ObjectMapper.class),
            "public",
            "vector_store",
            TestFeatureFlags.without(Feature.METADATA),
            new io.opaa.observability.IndexingMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    ContextPrefixRerunResult result = service.rerunBatch(UUID.randomUUID(), UUID.randomUUID(), 10);

    assertThat(result.isEmpty()).isTrue();
    verifyNoInteractions(
        jdbcTemplate, documentRepository, libraryRepository, metadataService, vectorChunkStore);
  }
}
