package io.opaa.indexing.document;

import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.file.pdf.PdfOcrDocumentFormat;
import io.opaa.format.file.pdf.PdfPages;
import io.opaa.format.file.pdf.RecognizedPdf;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.TextRecognitionRunBudget;
import io.opaa.llm.TextRecognitionClient.TextRecognitionRejectedException;
import io.opaa.llm.TextRecognitionClient.TextRecognitionUnavailableException;
import io.opaa.llm.TextRecognitionModelRole;
import io.opaa.llm.TextRecognitionModelRole.ActiveTextRecognition;
import io.opaa.llm.TextRecognitionProperties;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text recognition (OCR) of scanned PDFs in the indexing (ADR-0069): the whole file goes to the
 * service of {@link TextRecognitionModelRole}, the answer is stored with the document and cut by
 * {@link PdfOcrDocumentFormat}.
 *
 * <ul>
 *   <li><b>Stored recognition first.</b> A recognition of the same file is cut again without the
 *       service - always in a re-index, which never calls it, and otherwise while the role in force
 *       is the one that made it or none is in force (switched off, nothing is silently dropped).
 *   <li><b>Failure classes.</b> A failure of the service (timeout, 401/403, 429, 5xx, no
 *       connection) leaves the document rejected and unlocked for the next run. Only a refusal of
 *       this document, the page limit or a recognition without text locks the file for this
 *       procedure ({@link DocumentTextRecognitionStore#block}); there is no fallback to another
 *       procedure.
 *   <li><b>Limits.</b> Pages per document, the run's page budget ({@link TextRecognitionRunBudget},
 *       a deferred scan makes the run incomplete), a deadline per document for the page calls of a
 *       vision model, and {@link TextRecognitionProperties#maxConcurrent()} documents at a time on
 *       this instance (ADR-0053, per instance). A caller without a run sends only while a slot is
 *       free and otherwise leaves the scan to the background catch-up.
 * </ul>
 *
 * <p>Protocol, log and metrics name connection type, recipient ({@code configurationId}), pages and
 * the reason - never an account and never content.
 */
public class TextRecognitionIndexing implements ScannedDocumentRecognition {

  private static final Logger log = LoggerFactory.getLogger(TextRecognitionIndexing.class);

  static final String PNG = "image/png";

  private final TextRecognitionModelRole role;
  private final DocumentTextRecognitionStore store;
  private final PdfOcrDocumentFormat format;
  private final PdfPages pdfPages;
  private final IndexingMetrics metrics;
  private final Clock clock;

  /** Documents in recognition on this instance (ADR-0053: per instance, see the register). */
  private final Semaphore slots;

  public TextRecognitionIndexing(
      TextRecognitionModelRole role,
      DocumentTextRecognitionStore store,
      PdfOcrDocumentFormat format,
      PdfPages pdfPages,
      IndexingMetrics metrics,
      Clock clock) {
    this.role = role;
    this.store = store;
    this.format = format;
    this.pdfPages = pdfPages;
    this.metrics = metrics;
    this.clock = clock;
    this.slots = new Semaphore(role.limits().maxConcurrent(), true);
  }

  /** Whether the operation and the role allow text recognition now. */
  public Optional<ActiveTextRecognition> active() {
    return role.active();
  }

  @Override
  public Optional<Recognized> recognize(Candidate candidate) {
    Optional<DocumentTextRecognitionStore.Entry> stored = store.find(candidate.documentId());
    Optional<ActiveTextRecognition> active = role.active();
    Optional<Recognized> reused = reuse(candidate, stored, active);
    if (reused != null) {
      return reused;
    }
    if (candidate.reindex() || active.isEmpty()) {
      return Optional.empty();
    }
    return recognizeNew(candidate, stored, active.get());
  }

  /**
   * The stored recognition of this very file cut again, or {@code null} when there is none to use:
   * a re-index and a switched-off role always use it, an ingest only while the role in force made
   * it - another procedure reads the file anew.
   */
  private Optional<Recognized> reuse(
      Candidate candidate,
      Optional<DocumentTextRecognitionStore.Entry> stored,
      Optional<ActiveTextRecognition> active) {
    if (stored.isEmpty()
        || stored.get().blocked()
        || !stored.get().checksum().equals(candidate.checksum())) {
      return null;
    }
    DocumentTextRecognitionStore.Entry entry = stored.get();
    boolean sameProcedure =
        active.isEmpty()
            || (active.get().connectionType().name().equals(entry.connectionType())
                && active.get().configurationId().equals(entry.configurationId()));
    if (!candidate.reindex() && !sameProcedure) {
      return null;
    }
    DocumentFormatResult result = cut(candidate, entry.content());
    if (result.outcome() != DocumentFormatResult.Outcome.CHUNKED) {
      return Optional.empty();
    }
    log.info(
        "Cut {} again from its stored text recognition ({}, recipient {}), without the service",
        candidate.names().of(candidate.reference(), candidate.documentId()),
        entry.connectionType(),
        entry.configurationId());
    record(
        candidate,
        IndexingEventCategory.TEXT_RECOGNIZED,
        "Aus dem gespeicherten Erkennungsstand indiziert, ohne neue Texterkennung ("
            + label(entry.connectionType())
            + ", Empfänger "
            + entry.configurationId()
            + ")");
    metrics.recordTextRecognition("reused", entry.connectionType(), 0, null);
    return Optional.of(new Recognized(result, format));
  }

  private Optional<Recognized> recognizeNew(
      Candidate candidate,
      Optional<DocumentTextRecognitionStore.Entry> stored,
      ActiveTextRecognition active) {
    String type = active.connectionType().name();
    String recipient = active.configurationId();
    if (stored.isPresent()
        && stored.get().blocked()
        && stored
            .get()
            .madeFor(candidate.checksum(), type, recipient, PdfOcrDocumentFormat.VERSION)) {
      record(
          candidate,
          IndexingEventCategory.TEXT_RECOGNITION_BLOCKED,
          "Texterkennung gesperrt: " + stored.get().blockedReason());
      metrics.recordTextRecognition("blocked", type, 0, null);
      return Optional.empty();
    }
    TextRecognitionProperties limits = role.limits();
    int pages;
    try {
      pages = pdfPages.count(candidate.file());
    } catch (IOException | RuntimeException e) {
      return block(
          candidate, active, null, "Die PDF-Datei ließ sich für die Texterkennung nicht lesen");
    }
    if (pages > limits.maxPagesPerDocument()) {
      return block(
          candidate,
          active,
          pages,
          "Seitengrenze überschritten ("
              + pages
              + " Seiten, erlaubt sind "
              + limits.maxPagesPerDocument()
              + ")");
    }
    TextRecognitionRunBudget budget =
        candidate.events() == null ? null : candidate.events().textRecognitionBudget();
    if (budget != null && !budget.tryReserve(pages, limits.maxPagesPerRun())) {
      record(
          candidate,
          IndexingEventCategory.TEXT_RECOGNITION_PENDING,
          "Texterkennung ausstehend: Seitenbudget dieses Laufs erschöpft, der nächste Lauf setzt"
              + " fort");
      metrics.recordTextRecognition("pending", type, 0, null);
      return Optional.empty();
    }
    if (!acquireSlot(budget != null)) {
      if (budget != null) {
        budget.release(pages);
      }
      metrics.recordTextRecognition("pending", type, 0, null);
      return Optional.empty();
    }
    Instant started = clock.instant();
    try {
      RecognizedPdf recognized = read(candidate, active, pages, limits.documentTimeout());
      DocumentFormatResult result = cut(candidate, recognized.toJson());
      if (result.outcome() != DocumentFormatResult.Outcome.CHUNKED) {
        return block(candidate, active, pages, "Die Texterkennung hat keinen Text erkannt");
      }
      store.saveRecognition(
          candidate.documentId(),
          candidate.checksum(),
          type,
          recipient,
          PdfOcrDocumentFormat.VERSION,
          pages,
          recognized.toJson(),
          clock.instant());
      Duration took = Duration.between(started, clock.instant());
      log.info(
          "Text recognition read {} ({} pages, {}, recipient {}) in {} ms",
          candidate.names().of(candidate.reference(), candidate.documentId()),
          pages,
          type,
          recipient,
          took.toMillis());
      record(
          candidate,
          IndexingEventCategory.TEXT_RECOGNIZED,
          "Per Texterkennung indiziert ("
              + label(type)
              + ", Empfänger "
              + recipient
              + ", "
              + pages
              + (pages == 1 ? " Seite)" : " Seiten)"));
      metrics.recordTextRecognition("recognized", type, pages, took);
      return Optional.of(new Recognized(result, format));
    } catch (TextRecognitionRejectedException e) {
      return block(
          candidate,
          active,
          pages,
          "Der Dienst hat das Dokument abgelehnt (" + rejectionReason(e.status()) + ")");
    } catch (TextRecognitionUnavailableException | DeadlineExceeded e) {
      String reason = failureReason(e);
      log.warn(
          "Text recognition of {} failed on the service ({}, recipient {}): {}",
          candidate.names().of(candidate.reference(), candidate.documentId()),
          type,
          recipient,
          e.getMessage());
      record(
          candidate,
          IndexingEventCategory.TEXT_RECOGNITION_FAILED,
          "Texterkennung gescheitert: "
              + reason
              + " (Empfänger "
              + recipient
              + "); der nächste Lauf versucht es erneut");
      metrics.recordTextRecognition(
          "failed", type, pages, Duration.between(started, clock.instant()));
      return Optional.empty();
    } catch (IllegalStateException e) {
      // the operation or the role was switched off while the document waited: today's rejection
      log.info(
          "Text recognition of {} not started: {}",
          candidate.names().of(candidate.reference(), candidate.documentId()),
          e.getMessage());
      return Optional.empty();
    } finally {
      slots.release();
    }
  }

  /**
   * The service's reading of the file: the whole PDF for docling-serve, page by page as images for
   * a vision model, those calls within {@code documentTimeout} together.
   */
  private RecognizedPdf read(
      Candidate candidate, ActiveTextRecognition active, int pages, Duration documentTimeout)
      throws DeadlineExceeded {
    if (active.connectionType() == TextRecognitionConnectionType.DOCLING_SERVE) {
      byte[] pdf;
      try {
        pdf = Files.readAllBytes(candidate.file());
      } catch (IOException e) {
        throw new IllegalStateException("the scan could not be read from its working file", e);
      }
      return RecognizedPdf.docling(
          active.configurationId(), role.convertWithDocling(active, pdf).document());
    }
    Instant deadline = clock.instant().plus(documentTimeout);
    List<String> markdown = new ArrayList<>(pages);
    try {
      pdfPages.render(
          candidate.file(),
          (page, png) -> {
            if (clock.instant().isAfter(deadline)) {
              throw new DeadlineExceededSignal();
            }
            markdown.add(role.recognizePage(active, png, PNG));
          });
    } catch (DeadlineExceededSignal e) {
      throw new DeadlineExceeded();
    } catch (IOException e) {
      throw new IllegalStateException("the scan's pages could not be rendered", e);
    }
    return RecognizedPdf.vision(active.configurationId(), markdown);
  }

  private DocumentFormatResult cut(Candidate candidate, String recognition) {
    return DocumentFormatRunner.run(
        format,
        DocumentFormatSource.ofExtractedText(recognition, candidate.reference())
            .withLoggedName(candidate.names()));
  }

  private Optional<Recognized> block(
      Candidate candidate, ActiveTextRecognition active, Integer pages, String reason) {
    store.block(
        candidate.documentId(),
        candidate.checksum(),
        active.connectionType().name(),
        active.configurationId(),
        PdfOcrDocumentFormat.VERSION,
        pages,
        reason,
        clock.instant());
    log.warn(
        "Text recognition of {} locked ({}, recipient {}): {}",
        candidate.names().of(candidate.reference(), candidate.documentId()),
        active.connectionType(),
        active.configurationId(),
        reason);
    record(
        candidate,
        IndexingEventCategory.TEXT_RECOGNITION_BLOCKED,
        "Texterkennung gesperrt: " + reason);
    metrics.recordTextRecognition("blocked", active.connectionType().name(), 0, null);
    return Optional.empty();
  }

  /**
   * A slot of this instance: a run waits for one, a caller without a run takes one only if free.
   */
  private boolean acquireSlot(boolean wait) {
    if (!wait) {
      return slots.tryAcquire();
    }
    try {
      slots.acquire();
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for text recognition", e);
    }
  }

  private static void record(Candidate candidate, IndexingEventCategory category, String message) {
    if (candidate.events() != null) {
      candidate.events().record(category, message, candidate.reference());
    }
  }

  /** The German reason of a failure of the service, as the protocol names it. */
  static String failureReason(Exception failure) {
    if (failure instanceof DeadlineExceeded) {
      return "Zeitgrenze je Dokument überschritten";
    }
    TextRecognitionUnavailableException e = (TextRecognitionUnavailableException) failure;
    if (e.timedOut()) {
      return "Zeitgrenze überschritten";
    }
    if (e.keyRejected()) {
      return "Zugang abgelehnt (HTTP " + e.status() + ")";
    }
    if (e.status() == 429) {
      return "Dienst überlastet (HTTP 429)";
    }
    if (e.status() >= 500) {
      return "Fehler des Dienstes (HTTP " + e.status() + ")";
    }
    if (!e.answered()) {
      return "Dienst nicht erreichbar";
    }
    return e.status() == 200
        ? "Antwort des Dienstes nicht verwertbar"
        : "Dienst nicht verfügbar (HTTP " + e.status() + ")";
  }

  private static String rejectionReason(int status) {
    return status == 200 ? "Umwandlung gescheitert" : "HTTP " + status;
  }

  private static String label(String connectionType) {
    return TextRecognitionConnectionType.OPENAI_VISION.name().equals(connectionType)
        ? "Vision-Modell"
        : "docling-serve";
  }

  /** The page calls of a vision model exceeded the deadline of the document. */
  static final class DeadlineExceeded extends Exception {
    DeadlineExceeded() {
      super("the document's deadline for text recognition passed");
    }
  }

  /** Unchecked carrier of {@link DeadlineExceeded} out of the page callback. */
  private static final class DeadlineExceededSignal extends RuntimeException {
    DeadlineExceededSignal() {
      super(null, null, false, false);
    }
  }
}
