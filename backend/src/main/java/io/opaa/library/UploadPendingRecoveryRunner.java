package io.opaa.library;

import io.opaa.indexing.DocumentRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Recovers uploads left stuck at {@code PENDING} by a process that died mid-{@code
 * uploadTaskExecutor} task (#614, same class of defect as #501's {@code indexing_jobs.RUNNING}
 * rows, but for the upload path's own {@code PENDING} status instead). {@code
 * FileProcessingService#processUploadedFileAsync} always leaves a row at either {@code INDEXED} or
 * {@code FAILED} when it runs to completion - but if the JVM stops before that (a crash, an
 * operator-initiated restart, an out-of-memory kill), the in-flight task simply vanishes with it,
 * and nothing was ever running to finish the row on the next start. Without this recovery, such a
 * row would poll {@code PENDING} forever in the frontend, and a retry of the same file would answer
 * {@code 409} because only a {@code FAILED} row is replaced by a new upload ({@code
 * LibraryDocumentService#uploadDocument}, #589 review finding 3) - a dead end the caller cannot
 * resolve on their own.
 *
 * <p>Runs once, synchronously, at application startup, before any request can be served - so a row
 * genuinely still being processed by this same, freshly-started JVM can never be caught here: no
 * upload could have begun before this runner ran. Only rows older than {@link
 * UploadProperties#pendingRecoveryThresholdMinutes} are touched, so a row created moments before an
 * unrelated restart (already {@code PENDING}, its own worker task simply not scheduled yet) is left
 * alone rather than being failed the instant the new process comes up.
 */
@Component
public class UploadPendingRecoveryRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(UploadPendingRecoveryRunner.class);

  static final String FAILURE_REASON = "Die Verarbeitung wurde durch einen Neustart abgebrochen";

  private final DocumentRepository documentRepository;
  private final UploadProperties uploadProperties;

  public UploadPendingRecoveryRunner(
      DocumentRepository documentRepository, UploadProperties uploadProperties) {
    this.documentRepository = documentRepository;
    this.uploadProperties = uploadProperties;
  }

  @Override
  public void run(ApplicationArguments args) {
    Instant threshold =
        Instant.now().minus(Duration.ofMinutes(uploadProperties.pendingRecoveryThresholdMinutes()));
    int recovered = documentRepository.failStalePending(FAILURE_REASON, threshold);
    if (recovered > 0) {
      log.warn(
          "Marked {} upload(s) stuck at PENDING for more than {} minute(s) as FAILED on startup",
          recovered,
          uploadProperties.pendingRecoveryThresholdMinutes());
    }
  }
}
