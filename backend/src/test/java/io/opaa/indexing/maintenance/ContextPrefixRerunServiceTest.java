package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.common.TestFeatureFlags;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryMaintenanceSkips;
import io.opaa.knowledge.VectorTable;
import io.opaa.metadata.DocumentChunkMetadata;
import io.opaa.metadata.DocumentMetadataService;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ContextPrefixRerunService#reembedInPlace} - the one unit both the Kontextpräfix-Nachlauf
 * and the re-embedding after a model change run - writes its chunks back only through {@link
 * VectorChunkStore#reembedChunks}, which never recreates a chunk a concurrent ingest replaced.
 */
class ContextPrefixRerunServiceTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

  @BeforeEach
  void theVectorTableExists() {
    when(jdbcTemplate.queryForObject(
            eq("SELECT to_regclass(?) IS NOT NULL"), eq(Boolean.class), any()))
        .thenReturn(true);
  }

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final DocumentMetadataService metadataService = mock(DocumentMetadataService.class);
  private final VectorChunkStore vectorChunkStore = mock(VectorChunkStore.class);
  private final ContextPrefixRerunService service =
      new ContextPrefixRerunService(
          jdbcTemplate,
          documentRepository,
          mock(KnowledgeLibraryRepository.class),
          metadataService,
          vectorChunkStore,
          JsonMapper.builder().build(),
          new VectorTable("public", "vector_store"),
          TestFeatureFlags.allOn(),
          mock(PlatformTransactionManager.class),
          new io.opaa.observability.IndexingMetrics(
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
          mock(LibraryMaintenanceSkips.class));

  @Test
  @SuppressWarnings("unchecked")
  void theStoredChunksGoBackThroughTheUpdateOnlyPathNeverThroughAnInsert() throws Exception {
    UUID documentId = UUID.randomUUID();
    String chunkId = UUID.randomUUID().toString();
    Document document = mock(Document.class);
    when(document.getId()).thenReturn(documentId);
    when(metadataService.chunkMetadataFor(document)).thenReturn(DocumentChunkMetadata.EMPTY);
    ResultSet row = mock(ResultSet.class);
    when(row.getString("id")).thenReturn(chunkId);
    when(row.getString("content")).thenReturn("Gespeicherter Text");
    when(row.getString("metadata"))
        .thenReturn("{\"document_id\":\"" + documentId + "\",\"chunk_index\":0}");
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(documentId.toString())))
        .thenAnswer(call -> List.of(((RowMapper<Object>) call.getArgument(1)).mapRow(row, 0)));
    when(vectorChunkStore.reembedChunks(anyList()))
        .thenReturn(new VectorChunkStore.Reembedded(0, 0, 0));

    service.reembedInPlace(document);

    ArgumentCaptor<List<org.springframework.ai.document.Document>> written =
        ArgumentCaptor.forClass(List.class);
    verify(vectorChunkStore).reembedChunks(written.capture());
    assertThat(written.getValue())
        .singleElement()
        .satisfies(
            chunk -> {
              assertThat(chunk.getId()).isEqualTo(chunkId);
              assertThat(chunk.getText()).isEqualTo("Gespeicherter Text");
            });
    verify(vectorChunkStore, never()).addChunks(anyList());
  }

  @Test
  void theStampIsRecordedOnlyWhenEveryStoredChunkWasRewritten() throws Exception {
    Document document = documentWithStoredChunks(2);
    when(vectorChunkStore.reembedChunks(anyList()))
        .thenReturn(new VectorChunkStore.Reembedded(2, 0, 0));

    UUID documentId = document.getId();

    service.reembedInPlace(document);

    verify(documentRepository).recordContextPrefix(eq(documentId), any(), anyBoolean(), any());
  }

  @Test
  void aDocumentWhoseChunksAConcurrentIngestReplacedKeepsTheStampTheIngestRecorded()
      throws Exception {
    Document document = documentWithStoredChunks(2);
    // One chunk was gone at write time: the ingest replaced the chunks and owns the document row.
    when(vectorChunkStore.reembedChunks(anyList()))
        .thenReturn(new VectorChunkStore.Reembedded(1, 0, 0));

    service.reembedInPlace(document);

    verify(documentRepository, never()).recordContextPrefix(any(), any(), anyBoolean(), any());
  }

  @Test
  void aCorrectionDuringTheEmbeddingLeavesTheDocumentPending() throws Exception {
    Document document = documentWithStoredChunks(2);
    // Read before the embedding, then again under the document row lock - by then a correction
    // has put a prefix-effective value on the document.
    when(metadataService.chunkMetadataFor(document))
        .thenReturn(DocumentChunkMetadata.EMPTY)
        .thenReturn(
            new DocumentChunkMetadata(
                java.util.Map.of(), java.util.Set.of(), null, List.of("Fassung 2026")));
    when(vectorChunkStore.reembedChunks(anyList()))
        .thenReturn(new VectorChunkStore.Reembedded(2, 0, 0));

    service.reembedInPlace(document);

    verify(documentRepository, never()).recordContextPrefix(any(), any(), anyBoolean(), any());
  }

  @Test
  void aDocumentDeletedBeforeTheStampIsRecordedGetsNone() throws Exception {
    Document document = documentWithStoredChunks(2);
    when(vectorChunkStore.reembedChunks(anyList()))
        .thenReturn(new VectorChunkStore.Reembedded(2, 0, 0));
    UUID documentId = document.getId();
    when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(documentId)))
        .thenReturn(List.of());

    service.reembedInPlace(document);

    verify(documentRepository, never()).recordContextPrefix(any(), any(), anyBoolean(), any());
  }

  @SuppressWarnings("unchecked")
  private Document documentWithStoredChunks(int count) throws Exception {
    UUID documentId = UUID.randomUUID();
    Document document = mock(Document.class);
    when(document.getId()).thenReturn(documentId);
    when(metadataService.chunkMetadataFor(document)).thenReturn(DocumentChunkMetadata.EMPTY);
    ResultSet row = mock(ResultSet.class);
    when(row.getString("id")).thenAnswer(call -> UUID.randomUUID().toString());
    when(row.getString("content")).thenReturn("Gespeicherter Text");
    when(row.getString("metadata")).thenReturn("{\"document_id\":\"" + documentId + "\"}");
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(documentId.toString())))
        .thenAnswer(
            call -> {
              RowMapper<Object> mapper = call.getArgument(1);
              List<Object> chunks = new java.util.ArrayList<>();
              for (int i = 0; i < count; i++) {
                chunks.add(mapper.mapRow(row, i));
              }
              return chunks;
            });
    when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(documentId)))
        .thenReturn(List.of(documentId));
    when(documentRepository.findById(documentId)).thenReturn(java.util.Optional.of(document));
    return document;
  }
}
