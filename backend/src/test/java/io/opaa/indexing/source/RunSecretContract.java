package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import io.opaa.test.MutableClock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * What every remote connector's run keeps with the core's secret (ADR-0041, Entscheidung 4): it
 * asks before its requests, so a secret discarded or a source blocked while the run lists ends the
 * run at its next access - with the block's notice, without reconciling by absence - and a secret
 * renewed meanwhile is what its next request sends. A subclass runs its real executor against its
 * connector's test double; frame and port are the core's, the run's clock is the test's.
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

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
  private final AtomicBoolean refused = new AtomicBoolean();
  private final AtomicBoolean renewed = new AtomicBoolean();
  private final AtomicBoolean refusing = new AtomicBoolean();
  private final AtomicInteger asksAfterRefusal = new AtomicInteger();
  private final AtomicInteger asksAfterRejection = new AtomicInteger();
  private final AtomicInteger rejectionsReported = new AtomicInteger();
  private final AtomicInteger ingested = new AtomicInteger();

  /** Target, connector settings and the secret of the connector's test double. */
  protected abstract SourceSettings settings();

  /** A second secret the test double accepts, which the port hands out once renewed. */
  protected abstract String renewedSecret();

  /** Whether a request the test double received carried {@link #renewedSecret()}. */
  protected abstract boolean sawRenewedSecret();

  protected abstract SourceType type();

  /** How many documents the test double offers; a complete run would ingest them all. */
  protected abstract int documents();

  /**
   * Whether the connector asks the core once more after its source rejected the secret ({@link
   * RunCredentials#renewedAfterRejection}); {@code RunSecretContractCoverageTest} holds a connector
   * that does to say so here.
   */
  protected boolean usesRejectionSeam() {
    return false;
  }

  /**
   * A secret the test double refuses as credentials, so the connector reports a rejection; {@code
   * null} for a connector without one. {@code RunSecretContractCoverageTest} holds a connector that
   * reports rejections to name one here.
   */
  protected String refusedSecret() {
    return null;
  }

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

  /** From now on the port refuses, and the secret the run holds is past its reuse. */
  protected final void discardNow() {
    refused.set(true);
    clock.advance(RunCredentials.VALIDITY);
  }

  /** From now on the port hands out {@link #renewedSecret()}, the old one past its reuse. */
  protected final void renewNow() {
    renewed.set(true);
    clock.advance(RunCredentials.VALIDITY);
  }

  protected final int asksAfterRefusal() {
    return asksAfterRefusal.get();
  }

  @Test
  void aSecretDiscardedDuringTheRunEndsItAtTheNextAccessWithoutReconciling() throws Exception {
    when(ingestService.ingest(any(), any()))
        .thenAnswer(
            call -> {
              ingested.incrementAndGet();
              discardNow();
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

  @Test
  void aSecretRenewedDuringTheRunIsWhatTheNextRequestSends() throws Exception {
    verifyRenewal();
  }

  /**
   * Renews the secret once the first document reached the index and expects the next request to
   * send it; a connector that sends its secret only when it signs in proves that instead.
   */
  protected void verifyRenewal() throws Exception {
    when(ingestService.ingest(any(), any()))
        .thenAnswer(
            call -> {
              if (!renewed.get()) {
                renewNow();
              }
              return DocumentIngestResult.PROCESSED;
            });
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    assertThat(sawRenewedSecret())
        .as("a request after the renewal sent the renewed secret")
        .isTrue();
    verify(jobService, never()).failJob(eq(jobId), anyString());
  }

  @Test
  void aSecretTheSourceRejectsEndsTheRunAsRejectedAndTellsThePortOnce() throws Exception {
    Assumptions.assumeTrue(refusedSecret() != null, "the connector reports no rejection");
    refusing.set(true);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(eq(jobId), anyString());
    verify(jobService, never()).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
    verify(ingestService, never()).ingest(any(), any());
    verifyNoInteractions(cleanupService);
    assertThat(rejectionsReported).as("the port heard of the rejection once").hasValue(1);
    assertThat(asksAfterRejection)
        .as("asked once more after the rejection where the connector asks again")
        .hasValue(usesRejectionSeam() ? 1 : 0);
  }

  protected final IndexingRunTemplate template() {
    return new IndexingRunTemplate(
        jobService,
        eventRepository,
        cleanupService,
        documentRepository,
        mock(LibraryStorageQuotaService.class),
        new RefusingResolver(),
        clock);
  }

  protected final KnowledgeLibrary library() {
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

  /**
   * The port: resolves the start as stored, hands out the renewed secret once {@link #renewed} and
   * refuses once {@link #refused}.
   */
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
      if (refusing.get()) {
        return Secret.personal(refusedSecret());
      }
      return Secret.personal(renewed.get() ? renewedSecret() : settings().sourceCredentials());
    }

    @Override
    public Secret secretAfterRejection(KnowledgeLibrary library, Secret rejected) {
      asksAfterRejection.incrementAndGet();
      return currentSecret(library);
    }

    @Override
    public void credentialsRejected(KnowledgeLibrary library) {
      rejectionsReported.incrementAndGet();
    }

    @Override
    public ConnectorData effectiveSettings(KnowledgeLibrary library) {
      return settings().connectorSettings();
    }
  }
}
