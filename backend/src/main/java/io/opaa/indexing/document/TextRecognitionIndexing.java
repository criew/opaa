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
import io.opaa.llm.textrecognition.TextRecognitionClient;
import io.opaa.llm.textrecognition.TextRecognitionClient.TextRecognitionRejectedException;
import io.opaa.llm.textrecognition.TextRecognitionClient.TextRecognitionUnavailableException;
import io.opaa.llm.textrecognition.TextRecognitionModelRole;
import io.opaa.llm.textrecognition.TextRecognitionModelRole.ActiveTextRecognition;
import io.opaa.llm.textrecognition.TextRecognitionProperties;
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
 *       connection, a partial conversion, an unexpected error) leaves the document rejected and
 *       unlocked for the next run. Only a refusal of this document, the page limit or a file that
 *       cannot be read locks the file for this procedure ({@link
 *       DocumentTextRecognitionStore#block}); a recognition without text is stored, not locked.
 *       There is no fallback to another procedure.
 *   <li><b>Limits.</b> Pages per document, the run's page budget ({@link TextRecognitionRunBudget},
 *       a deferred scan makes the run incomplete), a hard deadline per document for every call, and
 *       {@link TextRecognitionProperties#maxConcurrent()} documents at a time on this instance
 *       (ADR-0053, per instance). A caller without a run sends only while a slot is free and
 *       otherwise leaves the scan to the background catch-up.
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
      record(
          candidate,
          IndexingEventCategory.TEXT_RECOGNIZED,
          "Gespeicherte Texterkennung ohne Text ("
              + label(entry.connectionType())
              + ", Empfänger "
              + entry.configurationId()
              + "); keine neue Texterkennung");
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
    boolean wait = candidate.events() != null && candidate.events().waitsForTextRecognitionSlot();
    if (!acquireSlot(wait)) {
      if (budget != null) {
        budget.release(pages);
      }
      record(
          candidate,
          IndexingEventCategory.TEXT_RECOGNITION_PENDING,
          "Texterkennung ausstehend: kein freier Platz für die Texterkennung, sie wird nachgeholt");
      metrics.recordTextRecognition("pending", type, 0, null);
      return Optional.empty();
    }
    Instant started = clock.instant();
    try {
      RecognizedPdf recognized =
          read(candidate, active, pages, started.plus(limits.documentTimeout()));
      String json = recognized.toJson();
      DocumentFormatResult result = cut(candidate, json);
      store.saveRecognition(
          candidate.documentId(),
          candidate.checksum(),
          type,
          recipient,
          PdfOcrDocumentFormat.VERSION,
          pages,
          json,
          clock.instant());
      Duration took = Duration.between(started, clock.instant());
      if (result.outcome() != DocumentFormatResult.Outcome.CHUNKED) {
        return recognizedWithoutText(candidate, type, recipient, pages, took);
      }
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
      if (refusalOfTheDocument(active.connectionType(), e.status())) {
        return block(
            candidate,
            active,
            pages,
            "Der Dienst hat das Dokument abgelehnt (" + rejectionReason(e.status()) + ")");
      }
      return fail(
          candidate,
          active,
          pages,
          started,
          "failed",
          "Anfrage abgelehnt (HTTP " + e.status() + "), Modell und Einstellungen prüfen",
          e);
    } catch (PartialConversion e) {
      return fail(
          candidate,
          active,
          pages,
          started,
          "partial",
          "docling-serve hat das Dokument nur teilweise umgewandelt, nichts gespeichert",
          e);
    } catch (UnrenderablePages e) {
      return block(candidate, active, pages, unrenderableReason(e));
    } catch (TextRecognitionUnavailableException | DeadlineExceeded | WorkingFileUnreadable e) {
      return fail(candidate, active, pages, started, "failed", failureReason(e), e);
    } catch (IllegalStateException e) {
      if (role.active().isEmpty()) {
        // the operation or the role was switched off while the document waited: today's rejection
        log.info(
            "Text recognition of {} not started: {}",
            candidate.names().of(candidate.reference(), candidate.documentId()),
            e.getMessage());
        return Optional.empty();
      }
      return fail(
          candidate, active, pages, started, "failed", "Unerwarteter Fehler der Texterkennung", e);
    } catch (RuntimeException e) {
      return fail(
          candidate, active, pages, started, "failed", "Unerwarteter Fehler der Texterkennung", e);
    } finally {
      slots.release();
    }
  }

  /**
   * A recognition without text is stored, not locked: the document stays without content and is
   * read again only for a changed file or another recipient. A misconfigured vision model would
   * otherwise lock every scan.
   */
  private Optional<Recognized> recognizedWithoutText(
      Candidate candidate, String type, String recipient, int pages, Duration took) {
    log.info(
        "Text recognition found no text in {} ({} pages, {}, recipient {})",
        candidate.names().of(candidate.reference(), candidate.documentId()),
        pages,
        type,
        recipient);
    record(
        candidate,
        IndexingEventCategory.TEXT_RECOGNIZED,
        "Texterkennung ohne erkannten Text ("
            + label(type)
            + ", Empfänger "
            + recipient
            + "); das Dokument bleibt ohne Inhalt, bis sich die Datei oder der Empfänger ändert");
    metrics.recordTextRecognition("empty", type, pages, took);
    return Optional.empty();
  }

  /**
   * A failure that says nothing about the file: nothing is stored or locked, the next run or
   * catch-up tries again.
   */
  private Optional<Recognized> fail(
      Candidate candidate,
      ActiveTextRecognition active,
      int pages,
      Instant started,
      String result,
      String reason,
      Exception failure) {
    String type = active.connectionType().name();
    log.warn(
        "Text recognition of {} failed ({}, recipient {}): {} ({})",
        candidate.names().of(candidate.reference(), candidate.documentId()),
        type,
        active.configurationId(),
        failure.getClass().getSimpleName(),
        failure.getMessage());
    record(
        candidate,
        IndexingEventCategory.TEXT_RECOGNITION_FAILED,
        "Texterkennung gescheitert: "
            + reason
            + " (Empfänger "
            + active.configurationId()
            + "); der nächste Lauf versucht es erneut");
    metrics.recordTextRecognition(result, type, pages, Duration.between(started, clock.instant()));
    return Optional.empty();
  }

  /**
   * Whether a refusal concerns this document. docling-serve reads the file itself, so any refusal
   * does; a vision model gets a page image this application rendered, so only one too large (413)
   * does - a 400 or 404 there means a wrong model name or setting, which locks no scan.
   */
  static boolean refusalOfTheDocument(TextRecognitionConnectionType type, int status) {
    return type == TextRecognitionConnectionType.DOCLING_SERVE || status == 413;
  }

  /**
   * The service's reading of the file: the whole PDF for docling-serve, page by page as images for
   * a vision model, every call within the document's {@code deadline}.
   */
  private RecognizedPdf read(
      Candidate candidate, ActiveTextRecognition active, int pages, Instant deadline)
      throws DeadlineExceeded, PartialConversion, WorkingFileUnreadable, UnrenderablePages {
    if (active.connectionType() == TextRecognitionConnectionType.DOCLING_SERVE) {
      byte[] pdf;
      try {
        pdf = Files.readAllBytes(candidate.file());
      } catch (IOException e) {
        throw new WorkingFileUnreadable(e);
      }
      TextRecognitionClient.DoclingConversion conversion =
          role.convertWithDocling(active, pdf, remaining(deadline));
      if (conversion.partial()) {
        throw new PartialConversion();
      }
      return RecognizedPdf.docling(active.configurationId(), conversion.document());
    }
    List<String> markdown = new ArrayList<>(pages);
    try {
      pdfPages.render(
          candidate.file(),
          (page, png) -> {
            Duration left = remaining(deadline);
            if (left.isZero()) {
              throw new DeadlineExceededSignal();
            }
            markdown.add(role.recognizePage(active, png, PNG, left));
          });
    } catch (DeadlineExceededSignal e) {
      throw new DeadlineExceeded();
    } catch (IOException e) {
      throw new UnrenderablePages(e);
    }
    return RecognizedPdf.vision(active.configurationId(), markdown);
  }

  /** What is left until {@code deadline}; zero once it has passed. */
  private Duration remaining(Instant deadline) {
    Duration left = Duration.between(clock.instant(), deadline);
    return left.isNegative() ? Duration.ZERO : left;
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
   * A slot of this instance: a run waits for one, any other caller takes one only if free. An
   * interrupted wait takes none.
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
      return false;
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
    if (failure instanceof WorkingFileUnreadable) {
      return "Arbeitsdatei nicht lesbar";
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

  /** The German reason a scan whose pages could not be rendered is locked for. */
  static String unrenderableReason(UnrenderablePages failure) {
    if (failure.getCause() instanceof PdfPages.PageOverLimitException overLimit) {
      return "Seite "
          + overLimit.pageNumber()
          + " überschreitet die Grenzen der Bilddarstellung (Seitenmaß, Bildgröße oder"
          + " Rechenzeit)";
    }
    return "Die Seiten der PDF-Datei ließen sich nicht als Bild darstellen";
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

  /** docling-serve reported {@code partial_success}: some pages are missing from the answer. */
  static final class PartialConversion extends Exception {
    PartialConversion() {
      super("docling-serve converted the document only partially");
    }
  }

  /** The working file of the scan could not be read; says nothing about the scan itself. */
  static final class WorkingFileUnreadable extends Exception {
    WorkingFileUnreadable(IOException cause) {
      super("the scan could not be read from its working file", cause);
    }
  }

  /** The pages of the scan could not be rendered as images for a vision model. */
  static final class UnrenderablePages extends Exception {
    UnrenderablePages(IOException cause) {
      super("the scan's pages could not be rendered", cause);
    }
  }

  /** Unchecked carrier of {@link DeadlineExceeded} out of the page callback. */
  private static final class DeadlineExceededSignal extends RuntimeException {
    DeadlineExceededSignal() {
      super(null, null, false, false);
    }
  }
}
