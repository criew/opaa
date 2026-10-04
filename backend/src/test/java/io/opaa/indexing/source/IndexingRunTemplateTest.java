package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.attachment.AttachmentOutcome;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunCost;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceType;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.test.MutableClock;
import io.opaa.test.SourceTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The frame every connector run shares: one terminal call per run, every failure class translated
 * into the job's German message, reconciliation and assessment only where the listing and the run
 * mode allow it, and the cost written for every run that reached its body.
 */
class IndexingRunTemplateTest {

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final IndexingRunEventRepository eventRepository = mock(IndexingRunEventRepository.class);
  private final StaleDocumentCleanupService cleanupService =
      mock(StaleDocumentCleanupService.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final LibraryStorageQuotaService quotaService = mock(LibraryStorageQuotaService.class);
  private final IndexingRunTemplate template =
      new IndexingRunTemplate(
          jobService,
          eventRepository,
          cleanupService,
          documentRepository,
          quotaService,
          new LibrarySourceConnectionResolver());
  private final SourceIndexingExecutor fullListingExecutor =
      executor(
          SourceTypes.FILESYSTEM,
          Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
  private final SourceIndexingExecutor windowExecutor =
      executor(
          SourceTypes.RSS_FEED,
          Map.of(IndexingRunMode.INCREMENTAL, VanishedDocumentPolicy.KEEP_ON_ABSENCE));
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

  private static SourceIndexingExecutor executor(
      SourceType type, Map<IndexingRunMode, VanishedDocumentPolicy> modes) {
    SourceIndexingExecutor executor = mock(SourceIndexingExecutor.class);
    when(executor.sourceType()).thenReturn(type);
    when(executor.runModes()).thenReturn(modes);
    return executor;
  }

  // --- the terminal call -------------------------------------------------------------------

  @Test
  void anUndeclaredRunModeFailsTheJobWithoutRunningTheBody() {
    AtomicReference<IndexingRun> seen = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.INCREMENTAL,
        fullListingExecutor,
        run -> {
          seen.set(run);
          return ListingOutcome.complete();
        });

    assertThat(seen.get()).isNull();
    verify(jobService)
        .failJob(jobId, "Betriebsart INCREMENTAL wird für diesen Quellentyp nicht unterstützt");
    verify(jobService, never()).recordRunMetrics(any(), any());
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  // --- the resolved source -----------------------------------------------------------------

  /** The secret is asked again once its reuse is over, never fixed at the start of the run. */
  @Test
  void theBodySeesTheResolvedSettingsWithoutTheSecretAndAsksForTheSecretOnDemand() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
    SourceConnectionResolver resolver = mock(SourceConnectionResolver.class);
    when(resolver.resolve(library))
        .thenReturn(new SourceSettings(null, "https://quelle.example", null, "alt", false, null));
    when(resolver.currentSecret(library))
        .thenReturn(Secret.personal("erstes"), Secret.personal("erneuert"));
    AtomicReference<SourceSettings> settings = new AtomicReference<>();
    List<String> secrets = new ArrayList<>();

    new IndexingRunTemplate(
            jobService,
            eventRepository,
            cleanupService,
            documentRepository,
            quotaService,
            resolver,
            clock)
        .run(
            jobId,
            library,
            IndexingRunMode.INCREMENTAL,
            windowExecutor,
            run -> {
              settings.set(run.settings());
              secrets.add(run.credentials().value());
              clock.advance(RunCredentials.VALIDITY);
              secrets.add(run.credentials().value());
              return ListingOutcome.partial();
            });

    assertThat(settings.get().sourceUrl()).isEqualTo("https://quelle.example");
    assertThat(settings.get().sourceCredentials()).isNull();
    assertThat(secrets).containsExactly("erstes", "erneuert");
  }

  @Test
  void aSourceThatCannotBeResolvedFailsTheJobWithoutRunningTheBody() {
    SourceConnectionResolver resolver = mock(SourceConnectionResolver.class);
    when(resolver.resolve(library)).thenThrow(new IllegalStateException("Zugang gesperrt"));
    AtomicReference<IndexingRun> seen = new AtomicReference<>();

    templateWith(resolver)
        .run(
            jobId,
            library,
            IndexingRunMode.FULL,
            fullListingExecutor,
            run -> {
              seen.set(run);
              return ListingOutcome.complete();
            });

    assertThat(seen.get()).isNull();
    verify(jobService).failJob(jobId, "Zugang gesperrt");
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  private IndexingRunTemplate templateWith(SourceConnectionResolver resolver) {
    return new IndexingRunTemplate(
        jobService, eventRepository, cleanupService, documentRepository, quotaService, resolver);
  }

  @Test
  void aCompletedBodyEndsTheJobExactlyOnceAfterTheProtocolIsFinalized() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.progress().recordProcessed();
          run.progress().recordSkipped();
          run.progress().recordAttachment(AttachmentOutcome.PROCESSED);
          SourceRequestMeter meter = new SourceRequestMeter();
          for (int i = 0; i < 12; i++) {
            meter.recordRequest();
          }
          meter.recordThrottle(Duration.ofMillis(500));
          run.recordRequestCost(meter);
          return ListingOutcome.complete();
        });

    InOrder order = inOrder(jobService);
    order
        .verify(jobService)
        .recordRunMetrics(jobId, new IndexingRunCost(12, 1, 500L, 1, 0, 0, false, 0L));
    order.verify(jobService).completeJob(jobId, 1, 0, 1, 2);
    verify(jobService, never()).failJob(any(), any());
  }

  // --- failure translation -----------------------------------------------------------------

  @Test
  void aRunFailedExceptionCarriesItsOwnMessageOntoTheJob() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new IndexingRunFailedException("Der Quellpfad ist nicht freigegeben");
        });

    verify(jobService).failJob(jobId, "Der Quellpfad ist nicht freigegeben");
    verify(jobService).recordRunMetrics(eq(jobId), any());
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void anInterruptionFailsTheJobAsInterruptedAndRestoresTheFlagOnlyAfterTheJobIsWritten() {
    // A pending interrupt makes the connection acquisition for the terminal job write fail, so
    // the flag is restored after failJob has run - and it is restored, for the executor thread.
    AtomicReference<Boolean> interruptedDuringFailJob = new AtomicReference<>();
    doAnswer(
            invocation -> {
              interruptedDuringFailJob.set(Thread.currentThread().isInterrupted());
              return null;
            })
        .when(jobService)
        .failJob(any(), any());
    try {
      template.run(
          jobId,
          library,
          IndexingRunMode.FULL,
          fullListingExecutor,
          run -> {
            throw new InterruptedException();
          });

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    verify(jobService).failJob(jobId, IndexingRunTemplate.INTERRUPTED_MESSAGE);
    assertThat(interruptedDuringFailJob.get()).isFalse();
  }

  @Test
  void anInterruptionRethrownWithTheFlagAlreadySetStillWritesTheJobOnAClearedThread() {
    // IndexingRun.rethrowRunEnding restores the flag before it throws; the frame must clear it
    // for the terminal writes and set it again afterwards.
    AtomicReference<Boolean> interruptedDuringFailJob = new AtomicReference<>();
    doAnswer(
            invocation -> {
              interruptedDuringFailJob.set(Thread.currentThread().isInterrupted());
              return null;
            })
        .when(jobService)
        .failJob(any(), any());
    try {
      template.run(
          jobId,
          library,
          IndexingRunMode.FULL,
          fullListingExecutor,
          run -> {
            try {
              throw new IllegalStateException("wrapped", new InterruptedException());
            } catch (Exception e) {
              IndexingRun.rethrowRunEnding(e);
              throw e;
            }
          });

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    verify(jobService).failJob(jobId, IndexingRunTemplate.INTERRUPTED_MESSAGE);
    assertThat(interruptedDuringFailJob.get()).isFalse();
  }

  @Test
  void aBrokenForeignKeyToTheLibraryFailsTheJobAsDeletedDuringTheRun() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new DataIntegrityViolationException(
              "insert or update on table \"documents\" violates foreign key constraint"
                  + " \"fk_documents_library\"");
        });

    verify(jobService).failJob(jobId, "Die Bibliothek wurde während des Laufs gelöscht.");
  }

  /** A secret refused mid-run ends the run with the block's notice, as a block at its start. */
  @Test
  void aBlockDuringTheRunFailsTheJobWithItsNotice() {
    SourceConnectionResolver resolver = mock(SourceConnectionResolver.class);
    when(resolver.resolve(library))
        .thenReturn(new SourceSettings("/srv/dokumente", null, null, null, false, null));
    when(resolver.currentSecret(library))
        .thenThrow(
            new SourceConnectionBlockedException(
                new SourceBlock(
                    SourceBlock.Reason.ACCESS_REMOVED,
                    "Verwaltende der Bibliothek",
                    "Zugang entfernt: Der Zugang dieser Bibliothek wurde gelöscht.")));

    templateWith(resolver)
        .run(
            jobId,
            library,
            IndexingRunMode.FULL,
            fullListingExecutor,
            run -> {
              run.credentials().check();
              return ListingOutcome.complete();
            });

    verify(jobService)
        .failJob(jobId, "Zugang entfernt: Der Zugang dieser Bibliothek wurde gelöscht.");
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void rejectedCredentialsFailTheJobWithTheSourcesMessageAndTellThePort() {
    SourceConnectionResolver resolver = mock(SourceConnectionResolver.class);
    when(resolver.resolve(library))
        .thenReturn(new SourceSettings("/srv/dokumente", null, null, null, false, null));

    templateWith(resolver)
        .run(
            jobId,
            library,
            IndexingRunMode.FULL,
            fullListingExecutor,
            run -> {
              throw new SourceCredentialsRejectedException("Die Quelle hat abgelehnt.");
            });

    verify(jobService).failJob(jobId, "Die Quelle hat abgelehnt.");
    verify(resolver).credentialsRejected(library);
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void anyOtherExceptionFailsTheJobWithItsOwnMessage() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new java.io.IOException("Verzeichnis nicht lesbar");
        });

    verify(jobService).failJob(jobId, "Verzeichnis nicht lesbar");
  }

  @Test
  void anExceptionWithoutAMessageStillFailsTheJob() {
    // A ConnectException from the JDK's networking stack can carry no message at all - the run
    // failed regardless, and must never end COMPLETED for lack of a text.
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new java.net.ConnectException();
        });

    verify(jobService).failJob(jobId, "Unerwarteter Fehler (ConnectException)");
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aFailedRunNeitherReconcilesNorAssessesItsListing() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.markPresent("/srv/dokumente/a.txt");
          throw new IllegalStateException("mitten im Lauf");
        });

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService, never()).recordListingAssessment(any(), anyBoolean(), any());
  }

  // --- reconciliation and assessment -------------------------------------------------------

  @Test
  void aCompleteListingReconcilesRunsTheHookAndRecordsACompleteAssessment() {
    AtomicReference<Boolean> hook = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.markReprocessed("/srv/dokumente/a.txt");
          run.markPresent("/srv/dokumente/b.txt");
          run.afterReconciliation(hook::set);
          return ListingOutcome.complete();
        });

    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(SourceTypes.FILESYSTEM),
            eq(Set.of("/srv/dokumente/a.txt", "/srv/dokumente/b.txt")),
            eq(Set.of("/srv/dokumente/a.txt")),
            any(),
            eq(fullListingExecutor),
            eq(IndexingRunMode.FULL));
    assertThat(hook.get()).isTrue();
    verify(jobService).recordListingAssessment(jobId, true, List.of());
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aFailedReconciliationIsReportedToTheHookAndNeverFailsTheRun() {
    AtomicReference<Boolean> hook = new AtomicReference<>();
    doThrow(new IllegalStateException("Datenbank nicht erreichbar"))
        .when(cleanupService)
        .reconcile(any(), any(), any(), any(), any(), any(), any());

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.afterReconciliation(hook::set);
          return ListingOutcome.complete();
        });

    assertThat(hook.get()).isFalse();
    verify(jobService).recordListingAssessment(jobId, true, List.of());
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void anIncompleteListingReconcilesNothingAndRecordsTheUnreadableContainers() {
    AtomicReference<Boolean> hook = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.afterReconciliation(hook::set);
          return ListingOutcome.incomplete(List.of("SEC"));
        });

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    assertThat(hook.get()).isNull();
    verify(jobService).recordListingAssessment(jobId, false, List.of("SEC"));
    verify(jobService).recordRunMetrics(jobId, new IndexingRunCost(0, 0, 0L, 0, 0, 0, false, 0L));
  }

  @Test
  void aListingWithUnreadableAreasReconcilesAllButTheRetainedDocuments() {
    AtomicReference<Boolean> hook = new AtomicReference<>();
    when(documentRepository.findByLibraryIdAndSourceType(library.getId(), SourceTypes.FILESYSTEM))
        .thenReturn(
            List.of(
                document("/srv/dokumente/gesperrt/a.txt"),
                document("/srv/dokumente/weg.txt"),
                document("/srv/dokumente/b.txt")));

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.markReprocessed("/srv/dokumente/b.txt");
          run.afterReconciliation(hook::set);
          return ListingOutcome.completeExcept(
              1, key -> key.startsWith("/srv/dokumente/gesperrt/"));
        });

    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(SourceTypes.FILESYSTEM),
            eq(Set.of("/srv/dokumente/b.txt", "/srv/dokumente/gesperrt/a.txt")),
            eq(Set.of("/srv/dokumente/b.txt")),
            any(),
            eq(fullListingExecutor),
            eq(IndexingRunMode.FULL));
    assertThat(hook.get()).isTrue();
    verify(jobService).recordListingAssessment(jobId, false, List.of());
    verify(jobService).recordUnreadableScopes(jobId, 1);
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void anAttachmentIsNeverRetainedByItsOwnKeyOnlyThroughItsParent() {
    // a crafted attachment name can make an attachment key point into an unreadable area; the
    // attachment must then not outlive its vanished parent mail
    Document mail = document("/srv/dokumente/weg.eml");
    Document attachment = document("/srv/dokumente/weg.eml/0/../../gesperrt/x.pdf");
    attachment.setParentDocumentId(mail.getId());
    when(documentRepository.findByLibraryIdAndSourceType(library.getId(), SourceTypes.FILESYSTEM))
        .thenReturn(List.of(mail, attachment));

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.markReprocessed("/srv/dokumente/b.txt");
          return ListingOutcome.completeExcept(1, key -> key.contains("gesperrt"));
        });

    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(SourceTypes.FILESYSTEM),
            eq(Set.of("/srv/dokumente/b.txt")),
            any(),
            any(),
            eq(fullListingExecutor),
            eq(IndexingRunMode.FULL));
  }

  @Test
  void aRunThatMetNothingButUnreadableAreasRetainsNothingAndSoDeletesNothing() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> ListingOutcome.completeExcept(1, key -> true));

    verify(documentRepository, never()).findByLibraryIdAndSourceType(any(), any());
    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(SourceTypes.FILESYSTEM),
            eq(Set.of()),
            eq(Set.of()),
            any(),
            eq(fullListingExecutor),
            eq(IndexingRunMode.FULL));
  }

  @Test
  void unreadableAreasWhoseDocumentsCannotBeLoadedReconcileNothing() {
    AtomicReference<Boolean> hook = new AtomicReference<>();
    when(documentRepository.findByLibraryIdAndSourceType(any(), any()))
        .thenThrow(new IllegalStateException("Datenbank nicht erreichbar"));

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.markPresent("/srv/dokumente/b.txt");
          run.afterReconciliation(hook::set);
          return ListingOutcome.completeExcept(1, key -> true);
        });

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    assertThat(hook.get()).isFalse();
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  private Document document(String filePath) {
    Document document = new Document("datei", filePath, "text/plain", 1L, SourceTypes.FILESYSTEM);
    document.setLibraryId(library.getId());
    return document;
  }

  @Test
  void aTruncatedListingLeavesTheAssessmentStandingAndMarksTheCostIncomplete() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> ListingOutcome.truncated());

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    verify(jobService).recordRunMetrics(jobId, new IndexingRunCost(0, 0, 0L, 0, 0, 0, true, 0L));
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aRunModeThatKeepsOnAbsenceNeverReconcilesNorAssessesWhateverTheBodyReports() {
    template.run(
        jobId,
        library,
        IndexingRunMode.INCREMENTAL,
        windowExecutor,
        run -> ListingOutcome.complete());
    template.run(
        jobId,
        library,
        IndexingRunMode.INCREMENTAL,
        windowExecutor,
        run -> ListingOutcome.partial());

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    verify(jobService, org.mockito.Mockito.times(2))
        .completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aPartialListingFromAFullyListingRunModeIsAContractViolationThatFailsTheRun() {
    template.run(
        jobId, library, IndexingRunMode.FULL, fullListingExecutor, run -> ListingOutcome.partial());

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService).failJob(eq(jobId), any());
  }

  // --- the run's own bounds ------------------------------------------------------------------

  private static ArgumentMatcher<IndexingRunEvent> note(
      IndexingEventCategory category, String messagePart) {
    return event ->
        event != null
            && event.getCategory() == category
            && event.getReference() == null
            && event.getMessage().contains(messagePart);
  }

  @Test
  void aSpentBudgetEndsTheRunTruncatedWithTheBodysContinuationAndNeverFailsIt() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.progress().recordProcessed();
          run.markPresent("/srv/dokumente/a.txt");
          run.budgetContinuation(() -> "der nächste Lauf setzt bei Ordner B fort");
          run.budgetStallAdvice("Budget anheben.");
          throw RequestBudgetExhaustedException.requests(40);
        });

    verify(eventRepository)
        .save(
            argThat(
                note(
                    IndexingEventCategory.BUDGET_EXHAUSTED,
                    "Anfragebudget von 40 Anfragen erschöpft; der nächste Lauf setzt bei Ordner B"
                        + " fort")));
    verify(eventRepository, never())
        .save(argThat(note(IndexingEventCategory.ERROR, "reicht für diese Bibliothek")));
    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    verify(jobService).recordRunMetrics(jobId, new IndexingRunCost(0, 0, 0L, 0, 0, 0, true, 0L));
    verify(jobService).completeJob(jobId, 1, 0, 0, 1);
    verify(jobService, never()).failJob(any(), any());
  }

  @Test
  void aBudgetSpentWithoutStoringAnythingAddsTheBodysAdviceAsAnError() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.progress().recordSkipped();
          run.budgetStallAdvice("Budget anheben oder die Auswahl aufteilen.");
          throw RequestBudgetExhaustedException.requests(40);
        });

    verify(eventRepository)
        .save(argThat(note(IndexingEventCategory.BUDGET_EXHAUSTED, "der nächste Lauf setzt fort")));
    verify(eventRepository)
        .save(
            argThat(
                note(
                    IndexingEventCategory.ERROR,
                    "Das Anfragebudget von 40 Anfragen reicht für diese Bibliothek nicht aus:"
                        + " Budget anheben oder die Auswahl aufteilen.")));
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aBodyWithoutAdviceGetsNoErrorNoteHoweverLittleItStored() {
    template.run(
        jobId,
        library,
        IndexingRunMode.INCREMENTAL,
        windowExecutor,
        run -> {
          throw RequestBudgetExhaustedException.throttleWait(Duration.ofMinutes(15));
        });

    verify(eventRepository)
        .save(
            argThat(
                note(
                    IndexingEventCategory.BUDGET_EXHAUSTED,
                    "Deckel der 429-Wartezeit von 15 Minuten je Lauf erreicht; der nächste Lauf"
                        + " setzt fort")));
    verify(eventRepository, never()).save(argThat(note(IndexingEventCategory.ERROR, "")));
    verify(jobService).recordRunMetrics(jobId, new IndexingRunCost(0, 0, 0L, 0, 0, 0, true, 0L));
  }

  @Test
  void aThrottledRunIsNotedOnceFromItsMeterWhetherItSucceededOrNot() {
    SourceRequestMeter meter = new SourceRequestMeter();
    meter.recordThrottle(Duration.ofSeconds(2));
    meter.recordThrottle(Duration.ofMillis(1500));

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.recordRequestCost(meter);
          throw new IndexingRunFailedException("Quelle nicht erreichbar");
        });

    verify(eventRepository)
        .save(
            argThat(
                note(
                    IndexingEventCategory.RATE_LIMITED,
                    "2-mal gedrosselt (HTTP 429/503); der Lauf hat insgesamt 3"
                        + " Sekunden gewartet statt abzubrechen")));
    verify(jobService)
        .recordRunMetrics(jobId, new IndexingRunCost(0, 2, 3500L, 0, 0, 0, false, 0L));
    verify(jobService).failJob(jobId, "Quelle nicht erreichbar");
  }

  @Test
  void anUnthrottledRunGetsNoRateLimitNote() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          SourceRequestMeter meter = new SourceRequestMeter();
          meter.recordRequest();
          run.recordRequestCost(meter);
          return ListingOutcome.complete();
        });

    verify(eventRepository, never()).save(argThat(note(IndexingEventCategory.RATE_LIMITED, "")));
  }

  // --- robustness --------------------------------------------------------------------------

  @Test
  void aFailedCostWriteNeverKeepsTheJobFromEnding() {
    doThrow(new IllegalStateException("Datenbank nicht erreichbar"))
        .when(jobService)
        .recordRunMetrics(any(), any());

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> ListingOutcome.complete());

    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void theProtocolOverflowIsPersistedBeforeTheJobEnds() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          for (int i = 0; i < 501; i++) {
            run.events().record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", "f");
          }
          return ListingOutcome.complete();
        });

    InOrder order = inOrder(jobService);
    order.verify(jobService).recordEventsTruncated(jobId, 1);
    order.verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }
}
