package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceType;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What every remote connector's run keeps with the core's secret (ADR-0041, Entscheidung 4): it
 * asks before its requests, so a secret discarded or a source blocked while the run lists ends the
 * run at its next access - with the block's notice, and without reconciling by absence. A subclass
 * runs its real executor against its connector's test double; the frame and the port are the
 * core's, the port refusing from the moment the first document reached the index. The secret is
 * reused for no time here, so "the next access" is exact.
 */
public abstract class RunSecretContract {

  /** The notice the port refuses with. */
  public static final String NOTICE =
      "Verbindung getrennt: Für den Zugang \"Ablage\" sind keine Zugangsdaten hinterlegt.";

  private static final SourceBlock BLOCK =
      new SourceBlock(SourceBlock.Reason.NOT_CONNECTED, "Verwaltende der Bibliothek", NOTICE);

  protected final IndexingJobService jobService = mock(IndexingJobService.class);
  protected final IndexingRunEventRepository eventRepository =
      mock(IndexingRunEventRepository.class);
  protected final StaleDocumentCleanupService cleanupService =
      mock(StaleDocumentCleanupService.class);
  protected final DocumentRepository documentRepository = mock(DocumentRepository.class);
  protected final DocumentIngestService ingestService = mock(DocumentIngestService.class);

  private final AtomicBoolean refused = new AtomicBoolean();
  private final AtomicInteger asksAfterRefusal = new AtomicInteger();
  private final AtomicInteger ingested = new AtomicInteger();

  /** Target, connector settings and the secret of the connector's test double. */
  protected abstract SourceSettings settings();

  protected abstract SourceType type();

  /** How many documents the test double offers; a complete run would ingest them all. */
  protected abstract int documents();

  /** The run mode that lists the whole source. */
  protected IndexingRunMode runMode() {
    return IndexingRunMode.FULL;
  }

  /**
   * Runs the connector's real executor once, synchronously, over {@code template} and {@link
   * #ingestService}.
   */
  protected abstract void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library)
      throws Exception;

  @Test
  void aSecretDiscardedDuringTheRunEndsItAtTheNextAccessWithoutReconciling() throws Exception {
    when(ingestService.ingest(any(), any()))
        .thenAnswer(
            call -> {
              ingested.incrementAndGet();
              refused.set(true);
              return DocumentIngestResult.PROCESSED;
            });
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, NOTICE);
    verify(jobService, never()).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
    verifyNoInteractions(cleanupService);
    assertThat(asksAfterRefusal).as("the run asked the core after the discard").hasPositiveValue();
    assertThat(ingested.get())
        .as("documents reaching the index after the discard")
        .isPositive()
        .isLessThan(documents());
  }

  @Test
  void aSourceBlockedBetweenStartAndFirstRequestEndsTheRunBeforeAnyDocument() throws Exception {
    refused.set(true);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, NOTICE);
    verifyNoInteractions(cleanupService);
    verify(ingestService, never()).ingest(any(), any());
  }

  private IndexingRunTemplate template() {
    return new IndexingRunTemplate(
        jobService,
        eventRepository,
        cleanupService,
        documentRepository,
        mock(LibraryStorageQuotaService.class),
        new RefusingResolver(),
        Duration.ZERO);
  }

  private KnowledgeLibrary library() {
    SourceSettings settings = settings();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Ablage",
            null,
            UUID.randomUUID(),
            type(),
            settings.sourcePath(),
            settings.sourceUrl(),
            settings.sourceProxy(),
            settings.sourceCredentials(),
            settings.sourceInsecureSsl());
    if (settings.connectorSettings() != null) {
      library.updateSourceSettings(settings.connectorSettings().toJson());
    }
    return library;
  }

  /** The port: resolves the start as stored, then refuses the secret once {@link #refused}. */
  private final class RefusingResolver implements SourceConnectionResolver {

    @Override
    public SourceSettings resolve(KnowledgeLibrary library) {
      return settings();
    }

    @Override
    public Secret currentSecret(KnowledgeLibrary library) {
      if (refused.get()) {
        asksAfterRefusal.incrementAndGet();
        throw new SourceConnectionBlockedException(BLOCK);
      }
      return Secret.personal(settings().sourceCredentials());
    }

    @Override
    public ConnectorData effectiveSettings(KnowledgeLibrary library) {
      return settings().connectorSettings();
    }
  }
}
