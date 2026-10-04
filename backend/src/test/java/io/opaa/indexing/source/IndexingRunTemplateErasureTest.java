package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.test.MutableClock;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A library marked for erasure ends its running run at the next ask for the secret - past the item
 * catch of a connector, without reconciling - and starts none: the frame asks the row, never the
 * entity the run began with.
 */
class IndexingRunTemplateErasureTest {

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final StaleDocumentCleanupService cleanupService =
      mock(StaleDocumentCleanupService.class);
  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
  private final IndexingRunTemplate template =
      new IndexingRunTemplate(
          jobService,
          mock(IndexingRunEventRepository.class),
          cleanupService,
          mock(DocumentRepository.class),
          mock(LibraryStorageQuotaService.class),
          new LibrarySourceConnectionResolver(),
          clock,
          null,
          libraries);
  private final SourceIndexingExecutor executor = mock(SourceIndexingExecutor.class);
  private final UUID jobId = UUID.randomUUID();
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          SourceTypes.FILESYSTEM,
          "/srv/dokumente",
          null,
          null,
          null,
          false);

  IndexingRunTemplateErasureTest() {
    when(executor.sourceType()).thenReturn(SourceTypes.FILESYSTEM);
    when(executor.runModes())
        .thenReturn(Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
  }

  @Test
  void aMarkerSetDuringTheRunEndsItAtTheNextAskPastTheItemCatch() {
    AtomicBoolean marked = new AtomicBoolean();
    when(libraries.isErasureRequested(library.getId())).thenAnswer(call -> marked.get());
    AtomicInteger itemsAfterTheMarker = new AtomicInteger();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        executor,
        run -> {
          run.credentials().check();
          marked.set(true);
          clock.advance(RunCredentials.VALIDITY);
          for (int item = 0; item < 3; item++) {
            try {
              run.credentials().check();
              itemsAfterTheMarker.incrementAndGet();
            } catch (RuntimeException e) {
              IndexingRun.rethrowRunEnding(e);
            }
          }
          return ListingOutcome.complete();
        });

    assertThat(itemsAfterTheMarker).hasValue(0);
    verify(jobService).failJob(jobId, LibraryErasureRequestedException.MESSAGE);
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aMarkedLibraryStartsNoBody() {
    when(libraries.isErasureRequested(library.getId())).thenReturn(true);
    AtomicBoolean bodyRan = new AtomicBoolean();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        executor,
        run -> {
          bodyRan.set(true);
          return ListingOutcome.complete();
        });

    assertThat(bodyRan).isFalse();
    verify(jobService).failJob(jobId, LibraryErasureRequestedException.MESSAGE);
  }
}
