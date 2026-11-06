package io.opaa.indexing.maintenance;

import io.opaa.format.file.pdf.PdfOcrDocumentFormat;
import io.opaa.indexing.attachment.StandaloneAttachmentAccess;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentTextRecognitionStore;
import io.opaa.indexing.document.TextRecognitionIndexing;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingEventSink;
import io.opaa.indexing.job.TextRecognitionRunBudget;
import io.opaa.indexing.registry.StoredDocumentSourceAccess;
import io.opaa.instance.ScheduledRunLocks;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.UploadStoreUnavailableException;
import io.opaa.llm.TextRecognitionModelRole.ActiveTextRecognition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Catches up on uploaded scans for text recognition (ADR-0069): an upload library has no runs, so
 * its scans rejected as without text - before text recognition was switched on, or left by a busy
 * instance, a failure of the service or an exhausted budget - are read here, in every library, in
 * passes of at most {@link io.opaa.llm.TextRecognitionProperties#maxPagesPerRun()} pages. Source
 * libraries catch up in their next run, which reads a rejected document again anyway.
 *
 * <p>A pass runs on its own scheduler under a cluster-wide lock (ADR-0053, Entscheidung 7), so one
 * instance at a time works through the backlog; each document is ingested with the conditional
 * writes of {@link DocumentIngestService}. It stops at the next document once the operation or the
 * role is switched off, the budget is spent or {@link #PASS_DURATION} has passed - that is how it
 * is halted. A locked scan is not selected again.
 */
public class TextRecognitionBacklog {

  private static final Logger log = LoggerFactory.getLogger(TextRecognitionBacklog.class);

  /**
   * No further document is started after this. With the hard deadline of one document ({@link
   * io.opaa.llm.TextRecognitionProperties#MAX_DOCUMENT_TIMEOUT}) and its embedding the pass stays
   * below {@link #LOCK_AT_MOST_FOR}.
   */
  static final Duration PASS_DURATION = Duration.ofMinutes(50);

  /** The cluster lock of a pass; outlasts {@link #PASS_DURATION} and one document (ADR-0053). */
  static final String LOCK_AT_MOST_FOR = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY;

  /**
   * Covers the clock skew between instances and stays below {@link
   * io.opaa.llm.TextRecognitionProperties#MIN_CATCH_UP_INTERVAL}, so every due time can take it.
   */
  static final String LOCK_AT_LEAST_FOR = "PT10S";

  private static final int SELECTION_SIZE = 20;

  private final TextRecognitionIndexing textRecognition;
  private final DocumentTextRecognitionStore store;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentIngestService documentIngestService;
  private final StoredDocumentSourceAccess sourceAccess;
  private final Clock clock;

  public TextRecognitionBacklog(
      TextRecognitionIndexing textRecognition,
      DocumentTextRecognitionStore store,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      DocumentIngestService documentIngestService,
      StoredDocumentSourceAccess sourceAccess,
      Clock clock) {
    this.textRecognition = textRecognition;
    this.store = store;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.documentIngestService = documentIngestService;
    this.sourceAccess = sourceAccess;
    this.clock = clock;
  }

  /** What one pass did, without any document or library named. */
  public record PassResult(int recognized, int notRecognized, int pages, boolean budgetSpent) {
    static final PassResult IDLE = new PassResult(0, 0, 0, false);
  }

  /**
   * Every {@code opaa.ocr.catch-up-interval} (default five minutes), first that long after the
   * start. A pass no longer finds a scan another pass has read as waiting, so the short {@link
   * #LOCK_AT_LEAST_FOR} lets a further pass on another instance only find less to do.
   */
  @Scheduled(
      fixedDelayString = "${opaa.ocr.catch-up-interval:PT5M}",
      initialDelayString = "${opaa.ocr.catch-up-interval:PT5M}",
      scheduler = "textRecognitionBacklogScheduler")
  @SchedulerLock(
      name = "text-recognition-backlog",
      lockAtLeastFor = LOCK_AT_LEAST_FOR,
      lockAtMostFor = LOCK_AT_MOST_FOR)
  public void catchUpPeriodically() {
    PassResult result = catchUp();
    if (result.recognized() + result.notRecognized() > 0) {
      log.info(
          "Text recognition catch-up: {} recognised, {} not, {} pages, budget spent: {}",
          result.recognized(),
          result.notRecognized(),
          result.pages(),
          result.budgetSpent());
    }
  }

  /** One pass over the uploaded scans still waiting; nothing while text recognition is off. */
  public PassResult catchUp() {
    Optional<ActiveTextRecognition> active = textRecognition.active();
    if (active.isEmpty()) {
      return PassResult.IDLE;
    }
    String configurationId = active.get().configurationId();
    TextRecognitionRunBudget budget = new TextRecognitionRunBudget();
    PassEvents events = new PassEvents(budget);
    Instant end = clock.instant().plus(PASS_DURATION);
    int recognized = 0;
    int notRecognized = 0;
    UUID after = null;
    while (true) {
      List<UUID> candidates =
          store.pendingUploads(
              after, PdfOcrDocumentFormat.VERSION, configurationId, SELECTION_SIZE);
      if (candidates.isEmpty()) {
        break;
      }
      for (UUID documentId : candidates) {
        if (textRecognition.active().isEmpty()
            || clock.instant().isAfter(end)
            || budget.deferred() > 0
            || events.serviceFailed
            || events.slotBusy) {
          return new PassResult(
              recognized, notRecognized, budget.pagesSpent(), budget.deferred() > 0);
        }
        after = documentId;
        Boolean processed;
        try {
          processed = process(documentId, events);
        } catch (UploadStoreUnavailableException e) {
          log.warn("Ending the text recognition catch-up: the upload store is not available");
          return new PassResult(recognized, notRecognized, budget.pagesSpent(), false);
        }
        if (processed == null) {
          continue;
        }
        if (processed) {
          recognized++;
        } else {
          notRecognized++;
        }
      }
    }
    return new PassResult(recognized, notRecognized, budget.pagesSpent(), budget.deferred() > 0);
  }

  /**
   * Ingests one waiting scan again from its stored original.
   *
   * @return whether it is indexed now; {@code null} when it could not be read here
   */
  private Boolean process(UUID documentId, IndexingEventSink events) {
    Optional<Document> found = documentRepository.findById(documentId);
    if (found.isEmpty() || found.get().getLibraryId() == null) {
      return null;
    }
    Document document = found.get();
    KnowledgeLibrary library = libraryRepository.findById(document.getLibraryId()).orElse(null);
    if (library == null) {
      return null;
    }
    boolean[] indexed = {false};
    java.util.function.Predicate<Path> ingest =
        file -> {
          indexed[0] = ingest(document, library, file, events);
          return true;
        };
    boolean read =
        document.getParentDocumentId() != null
            ? sourceAccess.withReextractedAttachment(document, ingest)
            : sourceAccess.withLocalSourceFile(document, "text recognition catch-up", ingest);
    return read ? indexed[0] : null;
  }

  private boolean ingest(
      Document document, KnowledgeLibrary library, Path file, IndexingEventSink events) {
    try {
      return documentIngestService.ingest(
              DocumentIngest.builder(library)
                  .file(file)
                  .filePath(document.getFilePath())
                  .fileName(document.getFileName())
                  .sourceType(document.getSourceType())
                  .parentDocumentId(document.getParentDocumentId())
                  .existingRow()
                  .malwareScanned()
                  .events(events)
                  .build(),
              new StandaloneAttachmentAccess(library, "Texterkennung"))
          == DocumentIngestResult.PROCESSED;
    } catch (Exception e) {
      log.warn(
          "Text recognition catch-up could not ingest document {}",
          document.getId(),
          library.loggedNames().of(e));
      return false;
    }
  }

  /**
   * The protocol of a pass: there is no run to show it in, so its notes go to the log only, and it
   * carries the pass's page budget. A failure of the service ends the pass at the next document:
   * the following ones would only meet the same failure, the next pass tries again.
   */
  private static final class PassEvents implements IndexingEventSink {

    private final TextRecognitionRunBudget budget;
    private volatile boolean serviceFailed;
    private volatile boolean slotBusy;

    PassEvents(TextRecognitionRunBudget budget) {
      this.budget = budget;
    }

    @Override
    public void record(IndexingEventCategory category, String message, String reference) {
      if (category == IndexingEventCategory.TEXT_RECOGNITION_FAILED) {
        serviceFailed = true;
      } else if (category == IndexingEventCategory.TEXT_RECOGNITION_PENDING) {
        slotBusy = true;
      }
      log.debug("Text recognition catch-up: {} {}", category, message);
    }

    @Override
    public TextRecognitionRunBudget textRecognitionBudget() {
      return budget;
    }

    /** A wait for a slot held by a run would push the pass past its lock. */
    @Override
    public boolean waitsForTextRecognitionSlot() {
      return false;
    }
  }
}
