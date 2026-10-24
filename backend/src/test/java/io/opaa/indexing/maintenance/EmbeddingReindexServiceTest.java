package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.indexing.chunk.EmbeddingIndexStore;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.chunk.VectorIndexSchema;
import io.opaa.indexing.maintenance.EmbeddingReindexService.Step;
import io.opaa.instance.CurrentInstance;
import io.opaa.instance.InstanceRegistry;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.VectorTable;
import io.opaa.permission.PersonThreshold;
import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One document of the re-embedding after a model change, without a database: a document whose chunk
 * rows another writer holds is skipped for this run, and it never counts towards the failures in a
 * row that stop the run as an endpoint problem.
 */
class EmbeddingReindexServiceTest {

  private final ContextPrefixRerunService rerunService = mock(ContextPrefixRerunService.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final EmbeddingIndexStore store = mock(EmbeddingIndexStore.class);
  private final CurrentInstance currentInstance = mock(CurrentInstance.class);
  private final EmbeddingReindexService service =
      new EmbeddingReindexService(
          new EmbeddingIndexState(),
          store,
          mock(VectorIndexSchema.class),
          rerunService,
          documentRepository,
          mock(VectorChunkStore.class),
          mock(AuditEventRecorder.class),
          mock(JdbcTemplate.class),
          mock(KnowledgeLibraryRepository.class),
          mock(PersonThreshold.class),
          new VectorTable("public", "vector_store"),
          Clock.systemUTC(),
          currentInstance,
          mock(InstanceRegistry.class));

  /** This instance owns the run throughout. */
  @BeforeEach
  void ownTheRun() {
    UUID self = UUID.randomUUID();
    when(currentInstance.id()).thenReturn(self);
    when(currentInstance.heartbeatCurrent()).thenReturn(true);
    when(store.ownsRunningRun(self)).thenReturn(true);
  }

  @Test
  void documentsWhoseRowsAnotherWriterHoldsAreSkippedWithoutStoppingTheRun() {
    Set<UUID> failed = new HashSet<>();
    int[] consecutiveFailures = {0};
    doThrow(new CannotAcquireLockException("could not obtain lock on row"))
        .when(rerunService)
        .reembedInPlace(any());

    for (int i = 0; i < EmbeddingReindexService.MAX_CONSECUTIVE_FAILURES + 1; i++) {
      UUID documentId = document();
      assertThat(service.reembed(documentId, failed, consecutiveFailures))
          .as("a held row says nothing about the embedding endpoint")
          .isEqualTo(Step.FAILED);
      assertThat(failed).as("not picked again in this run").contains(documentId);
    }
    assertThat(consecutiveFailures[0]).isZero();
  }

  @Test
  void anEmbeddingFailureStillCountsTowardsTheFailuresInARow() {
    Set<UUID> failed = new HashSet<>();
    int[] consecutiveFailures = {0};
    doThrow(new IllegalStateException("endpoint unreachable"))
        .when(rerunService)
        .reembedInPlace(any());

    Step last = null;
    for (int i = 0; i < EmbeddingReindexService.MAX_CONSECUTIVE_FAILURES; i++) {
      last = service.reembed(document(), failed, consecutiveFailures);
    }

    assertThat(consecutiveFailures[0]).isEqualTo(EmbeddingReindexService.MAX_CONSECUTIVE_FAILURES);
    assertThat(last).isEqualTo(Step.STOPPED);
  }

  private UUID document() {
    UUID documentId = UUID.randomUUID();
    Document document = mock(Document.class);
    when(document.getId()).thenReturn(documentId);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    return documentId;
  }

  @Test
  void aRunCancelledOrTakenOverElsewhereStopsBeforeTheNextDocument() {
    when(store.ownsRunningRun(currentInstance.id())).thenReturn(false);

    assertThat(service.reembed(document(), new HashSet<>(), new int[] {0})).isEqualTo(Step.STOPPED);
    verifyNoInteractions(rerunService);
  }
}
