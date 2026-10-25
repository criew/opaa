package io.opaa.indexing.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.credentials.LibrarySourceConnectionResolver;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.spi.ListingOutcome;
import io.opaa.indexing.spi.RunCredentials;
import io.opaa.indexing.spi.Secret;
import io.opaa.indexing.spi.SourceIndexingExecutor;
import io.opaa.indexing.spi.VanishedDocumentPolicy;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A connection connected anew while a run goes ends the run at its next ask for the secret - past
 * the item catch of a connector, without reconciling and before the new secret reaches the run,
 * also on the retry after a rejection and when it happens while the secret is read; an unchanged
 * connection lets every renewal through.
 */
class IndexingRunTemplateReconnectionTest {

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final StaleDocumentCleanupService cleanupService =
      mock(StaleDocumentCleanupService.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T12:00:00Z"));
  private final AtomicReference<String> generation = new AtomicReference<>("erstes Konto");
  private final AtomicReference<String> secret = new AtomicReference<>("erstes-token");
  private final AtomicBoolean reconnectWhileReading = new AtomicBoolean();
  private final IndexingRunTemplate template =
      new IndexingRunTemplate(
          jobService,
          mock(IndexingRunEventRepository.class),
          cleanupService,
          mock(DocumentRepository.class),
          mock(LibraryStorageQuotaService.class),
          new LibrarySourceConnectionResolver() {
            @Override
            public String connectionGeneration(KnowledgeLibrary library) {
              return generation.get();
            }

            @Override
            public Secret currentSecret(KnowledgeLibrary library) {
              if (reconnectWhileReading.get()) {
                reconnect();
              }
              return Secret.personal(secret.get());
            }
          },
          clock,
          null,
          mock(KnowledgeLibraryRepository.class));
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
          null);

  IndexingRunTemplateReconnectionTest() {
    when(executor.sourceType()).thenReturn(SourceTypes.FILESYSTEM);
    when(executor.runModes())
        .thenReturn(Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
  }

  // regression guard for #3147: the secret of another account never reaches a running run
  @Test
  void aReconnectionDuringTheRunEndsItAtTheNextAskPastTheItemCatch() {
    AtomicInteger itemsAfterTheReconnection = new AtomicInteger();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        executor,
        run -> {
          assertThat(run.credentials().value()).isEqualTo("erstes-token");
          reconnect();
          clock.advance(RunCredentials.VALIDITY);
          for (int item = 0; item < 3; item++) {
            try {
              run.credentials().value();
              itemsAfterTheReconnection.incrementAndGet();
            } catch (RuntimeException e) {
              IndexingRun.rethrowRunEnding(e);
            }
          }
          return ListingOutcome.complete();
        });

    assertThat(itemsAfterTheReconnection).hasValue(0);
    verify(jobService).failJob(jobId, ConnectionReconnectedException.MESSAGE);
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aReconnectionBeforeARetryAfterARejectionEndsTheRunWithoutTheNewSecret() {
    AtomicReference<String> retriedWith = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        executor,
        run -> {
          assertThat(run.credentials().value()).isEqualTo("erstes-token");
          reconnect();
          try {
            run.credentials().renewedAfterRejection("erstes-token");
            retriedWith.set(run.credentials().value());
          } catch (RuntimeException e) {
            IndexingRun.rethrowRunEnding(e);
          }
          return ListingOutcome.complete();
        });

    assertThat(retriedWith).hasNullValue();
    verify(jobService).failJob(jobId, ConnectionReconnectedException.MESSAGE);
  }

  @Test
  void aReconnectionWhileTheSecretIsReadEndsTheRunWithoutTheNewSecret() {
    AtomicReference<String> renewed = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        executor,
        run -> {
          assertThat(run.credentials().value()).isEqualTo("erstes-token");
          reconnectWhileReading.set(true);
          clock.advance(RunCredentials.VALIDITY);
          try {
            renewed.set(run.credentials().value());
          } catch (RuntimeException e) {
            IndexingRun.rethrowRunEnding(e);
          }
          return ListingOutcome.complete();
        });

    assertThat(renewed).hasNullValue();
    verify(jobService).failJob(jobId, ConnectionReconnectedException.MESSAGE);
  }

  @Test
  void anUnchangedConnectionRenewsItsSecretAndCompletes() {
    AtomicReference<String> renewed = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        executor,
        run -> {
          run.credentials().check();
          secret.set("erneuertes-token");
          clock.advance(RunCredentials.VALIDITY);
          renewed.set(run.credentials().value());
          return ListingOutcome.complete();
        });

    assertThat(renewed).hasValue("erneuertes-token");
    verify(jobService, never()).failJob(any(), any());
  }

  /** The person connects another account: a new generation and a new secret. */
  private void reconnect() {
    generation.set("zweites Konto");
    secret.set("zweites-token");
  }
}
